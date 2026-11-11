package com.xlollx.songport.sync

import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate

/**
 * TicketOne (ticketone.it) as an unofficial source of concert dates in Italy, for [Concerts]. Its site
 * answers browsers only, so it is never asked over plain HTTP: its normal search page is opened in a
 * real browser engine on the user's own phone, out of sight, the way the user's browser would open
 * it, and what the page shows is read from it ([reader]: the web connectors' `ticketone.read`, built
 * into the GitHub and F-Droid builds, through the connector plugin elsewhere). Docker and Desktop
 * have no browser engine: there [reader] is null and TicketOne stays a search button.
 *
 * Only when the user runs Concerts with Italy ([COUNTRY]), one search page an artist (and that
 * artist's own page when the search shows only a link to it), at least 1.5 seconds between two pages
 * (the reader paces them), each artist's dates kept a day with the others. No sign-in, no form but
 * the search. A check page (captcha), a cookie wall without a "necessary only" choice, an error or
 * a page no longer shaped as expected: TicketOne is left alone for an hour ([STOP_MS]), the log
 * says why, and the search buttons stay.
 *
 * What is read (see [parsePage]): the page's own structured data (JSON-LD `Event`s: name, start,
 * place, address, link, picture, performers), else its event links and the text around them.
 * Assumed from how ticketone.it (an Eventim site) pages were built, not checked in a browser from
 * here: the search lives at /search/?searchterm=, events at /event/..., an artist's page at
 * /artist/<slug>/ with its dates as JSON-LD. To check on a device when the site changes.
 */
object TicketOne {
    const val COUNTRY = "IT"
    const val SITE = "https://www.ticketone.it"
    const val STOP_MS = 3_600_000L

    /**
     * Opens a page of [SITE] in the browser engine and answers what it shows (the collector's JSON:
     * see [parsePage]); null when no engine can be asked (a connector plugin too old for it). Throws
     * when the page could not be read. Null where there is no browser engine at all.
     */
    @Volatile var reader: (suspend (String) -> String?)? = null

    /** Whether [reader] may be used now: in the Play build, only while a connector plugin answers. */
    @Volatile var gate: () -> Boolean = { true }

    val enabled: Boolean get() = reader != null && gate()

    /** Until when the site is left alone after a check page, a cookie wall, an error or a changed page. */
    @Volatile internal var stoppedUntil = 0L

    /** The page could not be read as expected: TicketOne is left alone for a while. */
    class Stop(message: String) : Exception(message)

    fun searchUrl(artist: String) = "$SITE/search/?searchterm=" + URLEncoder.encode(artist, "UTF-8").replace("+", "%20")

    /**
     * [artist]'s dates on TicketOne in [country]: empty when not asked (no browser engine, another
     * country), null when it could not be read (then not remembered). The range is left to the
     * caller ([Concerts.inRange]): the page shows every date anyway, so one day's reading serves all.
     */
    internal suspend fun dates(env: Env, artist: String, country: String): List<Concerts.Event>? {
        val read = reader
        // The gate may ask the plugin (a call to another app): never on the main thread.
        if (read == null || !country.equals(COUNTRY, ignoreCase = true) || !withContext(Dispatchers.IO) { gate() }) return emptyList()
        val ck = Concerts.cacheKey(Concerts.Source.TICKETONE, artist, COUNTRY, null)
        Concerts.cached(env, ck)?.let { return it }
        if (Concerts.now() < stoppedUntil) return null
        val today = Instant.ofEpochMilli(Concerts.now()).atZone(Concerts.zone()).toLocalDate()
        return try {
            val first = parsePage(read(searchUrl(artist)) ?: return null, artist, today)
            // The search may show only the artist's own page, not its dates: that page, once.
            val page = if (first.events.isEmpty() && first.artistPage != null) parsePage(read(first.artistPage) ?: return null, artist, today) else first
            Concerts.remember(env, ck, page.events)
            page.events
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            stoppedUntil = Concerts.now() + STOP_MS
            env.log("concerts", "TicketOne (unofficial): ${e.message ?: e.javaClass.simpleName}; left alone for an hour")
            null
        }
    }

    /** What one page gave: [artist]'s dates, and the address of the artist's own page when it links to it. */
    data class Page(val events: List<Concerts.Event>, val artistPage: String?)

    /**
     * The collector's answer for one page (the JSON the browser engine's script returns):
     * `state` ("ready", "empty" for a search with no results; "challenge", "consent", "waiting"
     * otherwise), `url`, `ld` (the texts of the page's JSON-LD scripts), `cards` (event links:
     * `href`, `title`, `text` around it, `time` from a `<time datetime>`, `img`) and `artists`
     * (artist links: `href`, `text`). [artist]'s dates still to come from [today], in Italy;
     * a [Stop] for a page that is not a result.
     */
    fun parsePage(body: String, artist: String, today: LocalDate): Page {
        val j = runCatching { parseJson(body) as? JsonObject }.getOrNull() ?: throw Stop("the page reader's answer changed")
        when (val state = j["state"].str) {
            "ready", "empty" -> {}
            "challenge" -> throw Stop("the site showed a check page (captcha)")
            "consent" -> throw Stop("a cookie wall with no \"necessary only\" choice")
            "waiting" -> throw Stop("the page showed no results in time (it may have changed)")
            else -> throw Stop("the page could not be read" + (j["error"].str?.let { ": " + it.take(200) } ?: state?.let { " ($it)" }.orEmpty()))
        }
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return Page(emptyList(), null)
        val fromLd = ldEvents(j["ld"].arr.mapNotNull { it.str }, artist, today)
        val events = fromLd.ifEmpty { cardEvents(j["cards"].arr, artist, today) }
        val artistPage = j["artists"].arr.firstNotNullOfOrNull { a ->
            val href = siteUrl(a["href"].str) ?: return@firstNotNullOfOrNull null
            val slug = ARTIST_PATH.find(href)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            href.takeIf { Matcher.normalizeArtist(a["text"].str.orEmpty()) == want || Matcher.normalizeArtist(slug.replace('-', ' ')) == want }
        }
        return Page(events.distinctBy { "${it.date}|${it.time}|${Matcher.normalizeArtist(it.city)}|${Matcher.normalizeArtist(it.venue)}" }.sortedWith(compareBy({ it.date }, { it.time ?: "" })), artistPage)
    }

    // ---------------------------------------------------------------- JSON-LD

    /** The `Event`s (and MusicEvent, Festival…) in the page's JSON-LD that are [artist]'s, in Italy, to come. */
    fun ldEvents(texts: List<String>, artist: String, today: LocalDate): List<Concerts.Event> {
        val want = Matcher.normalizeArtist(artist)
        val found = ArrayList<JsonObject>()
        for (t in texts) {
            val root = runCatching { parseJson(t.trim()) }.getOrNull() ?: continue
            collect(root, found, 0)
        }
        return found.mapNotNull { e ->
            if (Matcher.normalizeArtist(e["eventStatus"].str.orEmpty()).contains("cancelled")) return@mapNotNull null
            val name = text(e["name"])
            val performers = list(e["performer"]).mapNotNull { p -> text(p["name"]).ifEmpty { text(p) }.takeIf { it.isNotEmpty() } }
            val theirs = if (performers.isNotEmpty()) performers.any { Matcher.normalizeArtist(it) == want } else Matcher.normalizeArtist(name).startsWith(want)
            if (!theirs) return@mapNotNull null
            val start = e["startDate"].str?.trim() ?: return@mapNotNull null
            val date = start.take(10).takeIf { DAY.matches(it) } ?: return@mapNotNull null
            if (date < today.toString()) return@mapNotNull null
            // "T00:00" is how a date without its hour is often written: then the hour is not known.
            val time = start.drop(11).take(5).takeIf { start.getOrNull(10) == 'T' && TIME.matches(it) && it != "00:00" }
            val place = list(e["location"]).firstOrNull { it is JsonObject }
            val address = place["address"]
            val country = list(address["addressCountry"]).firstNotNullOfOrNull { c -> text(c["name"]).ifEmpty { text(c) }.takeIf { it.isNotEmpty() } }
            if (country != null && Matcher.normalizeArtist(country) !in ITALY) return@mapNotNull null
            val city = text(address["addressLocality"])
            val url = siteUrl(text(e["url"])) ?: list(e["offers"]).firstNotNullOfOrNull { siteUrl(text(it["url"])) }.orEmpty()
            Concerts.Event(
                artist = artist, name = name, date = date, time = time,
                city = Vivaticket.cityName(city.trim()), venue = text(place["name"]).trim(), country = COUNTRY,
                url = url, source = Concerts.Source.TICKETONE, image = image(e["image"]),
            )
        }
    }

    /** Every object whose `@type` is an event, wherever it is (`@graph`, an ItemList's items, a list). */
    private fun collect(e: JsonElement?, out: MutableList<JsonObject>, depth: Int) {
        if (depth > 6 || out.size >= 300) return
        when (e) {
            is JsonArray -> e.forEach { collect(it, out, depth + 1) }
            is JsonObject -> {
                val types = list(e["@type"]).mapNotNull { it.str }
                if (types.any { it.endsWith("Event") || it == "Festival" }) { out += e; return }
                for (k in listOf("@graph", "itemListElement", "item", "subEvent", "event", "events")) collect(e[k], out, depth + 1)
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- the page's own links

    /**
     * When the page has no JSON-LD dates: its event links ([cards]) with a date nearby, the artist's
     * name in their title or text. Venue and city are the first lines around the link that are not
     * its title, its date, a price or a button. A guess at the page's layout: to check on a device.
     */
    fun cardEvents(cards: List<JsonElement>, artist: String, today: LocalDate): List<Concerts.Event> {
        val want = Matcher.normalizeArtist(artist)
        return cards.mapNotNull { c ->
            val url = siteUrl(c["href"].str)?.takeIf { EVENT_PATH.containsMatchIn(it) } ?: return@mapNotNull null
            val title = c["title"].str.orEmpty().trim()
            val text = c["text"].str.orEmpty()
            if (!(" " + Matcher.normalizeArtist("$title\n$text") + " ").contains(" $want ")) return@mapNotNull null
            val (date, time) = dateOf(c["time"].str) ?: dateOf(text) ?: return@mapNotNull null
            if (date < today.toString()) return@mapNotNull null
            val lines = text.split('\n').map { it.trim() }.filter { l ->
                l.isNotEmpty() && l.length <= 80 && !l.equals(title, ignoreCase = true) && dateOf(l) == null &&
                    !TIME_ONLY.matches(l) && !l.contains('€') && Matcher.normalizeArtist(l) !in BUTTONS && Matcher.normalizeArtist(l) != want
            }
            Concerts.Event(
                artist = artist, name = title.ifEmpty { artist }, date = date, time = time,
                venue = lines.getOrNull(0).orEmpty(), city = Vivaticket.cityName(lines.getOrNull(1).orEmpty()), country = COUNTRY,
                url = url, source = Concerts.Source.TICKETONE, image = webUrl(c["img"].str),
            )
        }
    }

    /**
     * The date (yyyy-MM-dd) and time (HH:mm or null) in [s]: an ISO date-time, "14/06/2026" or
     * "14.06.2026", or "14 giu 2026" / "14 giugno 2026", with "21:00" or "21.00" after it.
     */
    fun dateOf(s: String?): Pair<String, String?>? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        ISO.find(t)?.let { m ->
            val time = m.groupValues[2].takeIf { it.isNotEmpty() && it != "00:00" }
            return m.groupValues[1] to time
        }
        val (day, month, year, rest) = NUMERIC.find(t)?.let { m -> listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], t.substring(m.range.last + 1)) }
            ?: WORDS.find(t)?.let { m ->
                val mon = MONTHS.entries.firstOrNull { (k, _) -> m.groupValues[2].lowercase().startsWith(k) }?.value ?: return null
                listOf(m.groupValues[1], mon.toString(), m.groupValues[3], t.substring(m.range.last + 1))
            } ?: return null
        val date = runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()).toString() }.getOrNull() ?: return null
        val time = CLOCK.find(rest)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }?.takeIf { it != "00:00" }
        return date to time
    }

    // ---------------------------------------------------------------- helpers

    /** [s] when it is a page of TicketOne's own site (https), else null. */
    private fun siteUrl(s: String?): String? {
        val u = webUrl(s)
        if (!u.startsWith("https://", ignoreCase = true)) return null
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: return null
        return u.takeIf { host == "ticketone.it" || host.endsWith(".ticketone.it") }
    }

    private fun list(e: JsonElement?): List<JsonElement> = when (e) { null -> emptyList(); is JsonArray -> e; else -> listOf(e) }

    private fun text(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::unescape).orEmpty()

    /** The picture: a link, a list of them, or an ImageObject's. */
    private fun image(e: JsonElement?): String = list(e).firstNotNullOfOrNull { i ->
        webUrl(text(i).ifEmpty { text(i["url"]).ifEmpty { text(i["contentUrl"]) } }).takeIf { it.isNotEmpty() }
    }.orEmpty()

    /** The few HTML entities JSON-LD written into a page sometimes keeps. */
    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"")

    private val ITALY = setOf("it", "ita", "italia", "italy")
    private val BUTTONS = setOf("biglietti", "acquista", "acquista biglietti", "tickets", "buy tickets", "scopri", "scopri di piu", "info", "disponibile", "in vendita", "sold out", "esaurito")
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")
    private val TIME_ONLY = Regex("""(ore\s*)?\d{1,2}[:.]\d{2}(\s*h)?""", RegexOption.IGNORE_CASE)
    private val ISO = Regex("""(\d{4}-\d{2}-\d{2})(?:T(\d{2}:\d{2}))?""")
    private val NUMERIC = Regex("""\b(\d{1,2})[/.](\d{1,2})[/.](\d{4})\b""")
    private val WORDS = Regex("""\b(\d{1,2})\s+([a-zA-Z]{3,9})\.?\s+(\d{4})\b""")
    private val CLOCK = Regex("""\b([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
    private val EVENT_PATH = Regex("""^https://[^/]+/event/""")
    private val ARTIST_PATH = Regex("""^https://[^/]+/artist/([^/?#]+)/?(?:[?#].*)?$""")
    private val MONTHS = linkedMapOf(
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6, "lug" to 7, "ago" to 8, "set" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
        "jan" to 1, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "dec" to 12,
    )
}

package com.xlollx.songport.sync

import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/**
 * The artist's own site as a source of concert dates for [Concerts]: its official site (MusicBrainz's
 * when kept, else Wikidata's or Spotify's, [ArtistSites.official]), its home page and, when that has
 * no dates, its tour page (a link named or addressed "tour", "live", "concerts", "events", "dates",
 * "tickets"... in several languages, [tourPaths]). Read from each page:
 *
 * - the schema.org `MusicEvent`/`Event` data it carries for search engines: date, venue, city,
 *   country, ticket link ([parseEvents]);
 * - the tour widgets it embeds: Bandsintown's (its public events of the artist, asked with the
 *   app id the site's widget itself gives, never one of ours; a widget with none is not asked) and
 *   Seated's (its public tour JSON).
 *
 * Every page through [Merch]'s requests (robots.txt honoured, one a second per host, the app named),
 * at most [MAX_REQUESTS] an artist, the dates kept a day ([Concerts.TTL_MS]) for every country at once.
 * Only when the user runs Concerts, never in the background. A date whose country is not said is left out.
 */
object SiteConcerts {
    const val MAX_REQUESTS = 8

    /**
     * [artist]'s dates on their own site in [country] from [today] to [end]: empty when no site is
     * known or [ArtistSites.enabled] is off, null when the site could not be read (then not kept).
     */
    internal suspend fun dates(env: Env, artist: String, country: String, today: LocalDate, end: LocalDate?, background: Boolean): List<Concerts.Event>? {
        if (!ArtistSites.enabled || background) return emptyList()
        val all = all(env, artist) ?: return null
        return all.filter { it.country.equals(country, ignoreCase = true) && Concerts.inRange(it, today, end) }
    }

    /** Every date the site gives, all countries, kept a day. */
    private suspend fun all(env: Env, artist: String): List<Concerts.Event>? {
        val ck = Concerts.cacheKey(Concerts.Source.SITE, artist, "*", null)
        Concerts.cached(env, ck)?.let { return it }
        val (site, unread) = try { ArtistSites.official(env, artist) } catch (e: CancellationException) { throw e } catch (e: Exception) { null to true }
        if (site == null) {
            if (unread) return null
            Concerts.remember(env, ck, emptyList())
            return emptyList()
        }
        val t = Merch.Trace(MAX_REQUESTS)
        val events = try { read(artist, site, t) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            t.unread = true; t.steps += "failed: ${e.javaClass.simpleName}"; emptyList()
        }
        env.log("concerts", "$artist, official site: " + t.steps.joinToString("; ") + " → ${events.size} dates")
        if (events.isEmpty() && t.unread) return null
        Concerts.remember(env, ck, events)
        return events
    }

    private suspend fun read(artist: String, site: String, t: Merch.Trace): List<Concerts.Event> {
        val u = URI(site)
        val h = u.host?.lowercase() ?: return emptyList()
        val start = u.rawPath.orEmpty().ifEmpty { "/" }
        val home = Merch.fetch(h, start, "text/html", t) ?: return emptyList()
        val homeUrl = "https://$h$start"
        var events = fromPage(artist, home.body, homeUrl, t)
        if (events.isEmpty()) {
            val tour = tourPaths(home.body, h).firstOrNull()
            if (tour == null) t.steps += "$h: no tour page linked"
            if (tour != null) {
                val th = runCatching { URI(tour).host?.lowercase() }.getOrNull() ?: h
                val path = runCatching { URI(tour).rawPath.orEmpty().ifEmpty { "/" } + (URI(tour).rawQuery?.let { "?$it" } ?: "") }.getOrDefault("/")
                Merch.fetch(th, path, "text/html", t)?.let { events = fromPage(artist, it.body, "https://$th$path", t) }
            }
        }
        return events.distinctBy { "${it.date}|${Matcher.normalizeArtist(it.city)}|${Matcher.normalizeArtist(it.venue)}" }.sortedBy { it.date }
    }

    /** A page's dates: its schema.org events, else the widget it embeds. */
    private suspend fun fromPage(artist: String, html: String, page: String, t: Merch.Trace): List<Concerts.Event> {
        val own = parseEvents(html, page, artist)
        if (own.isNotEmpty()) { t.steps += "${host(page)}: ${own.size} schema.org dates"; return own }
        bandsintownWidget(html)?.let { (name, app) ->
            val path = "/artists/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}/events?app_id=${URLEncoder.encode(app, "UTF-8")}&date=upcoming"
            val r = Merch.fetch(BANDSINTOWN, path, "application/json", t) ?: return emptyList()
            return runCatching { parseBandsintown(r.body, artist) }.getOrDefault(emptyList()).also { t.steps += "Bandsintown widget: ${it.size} dates" }
        }
        if (html.contains("bit-widget-initializer")) t.steps += "${host(page)}: Bandsintown widget without an app id, not asked"
        seatedWidget(html)?.let { id ->
            val r = Merch.fetch(SEATED, "/api/tour/${URLEncoder.encode(id, "UTF-8")}?include=tour-events", "application/json", t) ?: return emptyList()
            return runCatching { parseSeated(r.body, artist) }.getOrDefault(emptyList()).also { t.steps += "Seated widget: ${it.size} dates" }
        }
        return emptyList()
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull().orEmpty()

    const val BANDSINTOWN = "rest.bandsintown.com"
    const val SEATED = "cdn.seated.com"

    // ---------------------------------------------------------------- the tour page

    /** Words a tour page goes by, in its address or its link. */
    private val TOUR_WORDS = setOf(
        "tour", "tours", "touring", "tourdates", "tour-dates", "live", "concerts", "concert", "events", "event", "dates", "shows",
        "tickets", "gigs", "konzerte", "termine", "tournee", "tournée", "conciertos", "gira", "giras", "concerti", "date",
        "agenda", "concertos", "shows-ao-vivo", "turne", "turnê", "trasa", "koncerty", "keikat", "turné", "spelningar", "optredens",
    )
    private val TOUR_TEXT = Regex(
        """\b(tour|tour dates|live|concerts?|events|dates|shows|tickets|gigs|konzerte|termine|tourn[ée]e|conciertos|gira|concerti|agenda|turn[eêé]|koncerty|keikat|spelningar|optredens)\b|ライブ|コンサート|공연|투어|演唱会|巡演|концерты|тур""",
        RegexOption.IGNORE_CASE,
    )

    /** The tour pages a home page links to on the site (or a subdomain of it), the likeliest first. */
    fun tourPaths(html: String, site: String): List<String> {
        val domain = site.lowercase().removePrefix("www.")
        return Merch.links(html).mapNotNull { (href, label) ->
            if (href.startsWith("#") || href.startsWith("mailto:", true) || href.startsWith("javascript:", true)) return@mapNotNull null
            val abs = JsonLd.absolute(href, "https://$site/") ?: return@mapNotNull null
            val u = runCatching { URI(abs) }.getOrNull() ?: return@mapNotNull null
            val h = u.host?.lowercase()?.removePrefix("www.") ?: return@mapNotNull null
            if (h != domain && !h.endsWith(".$domain")) return@mapNotNull null
            val segments = u.rawPath.orEmpty().lowercase().trim('/').split('/').filter { it.isNotEmpty() }
            val byPath = segments.any { it in TOUR_WORDS } || h.substringBefore('.') in setOf("tour", "live", "tickets", "events")
            val byText = TOUR_TEXT.containsMatchIn(Merch.text(label).take(80))
            if (!byPath && !byText) return@mapNotNull null
            if (segments.isEmpty() && h == domain) return@mapNotNull null
            (if (byPath) 0 else 1) to abs.substringBefore('#')
        }.distinctBy { it.second }.sortedBy { it.first }.map { it.second }.toList()
    }

    // ---------------------------------------------------------------- schema.org events

    private val CANCELLED = Regex("EventCancelled|EventPostponed|EventMovedOnline", RegexOption.IGNORE_CASE)
    private val EVENT_TYPES = setOf("MusicEvent", "Event", "Festival", "TheaterEvent")

    /**
     * The schema.org events of a page ([page] its address): not cancelled nor postponed, with a day; the
     * venue, its city and country (an ISO code, [countryCode]), the ticket link (an offer's, else the
     * event's, else the page).
     */
    fun parseEvents(html: String, page: String, artist: String): List<Concerts.Event> = JsonLd.nodes(html).mapNotNull { e ->
        if (JsonLd.types(e).none { it in EVENT_TYPES }) return@mapNotNull null
        if (e["eventStatus"].str?.let { CANCELLED.containsMatchIn(it) } == true) return@mapNotNull null
        val start = e["startDate"].str?.trim() ?: return@mapNotNull null
        val date = start.take(10).takeIf { DAY.matches(it) } ?: return@mapNotNull null
        val time = start.drop(11).take(5).takeIf { TIME.matches(it) && it != "00:00" }
        val place = (e["location"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() ?: e["location"]
        val address = place["address"]
        val (city, country) = when (address) {
            is JsonObject -> (JsonLd.text(address["addressLocality"]).orEmpty()) to countryCode(JsonLd.text(address["addressCountry"]))
            else -> address.str?.let { a -> a.split(',').map { it.trim() }.filter { it.isNotEmpty() } }?.let { parts ->
                parts.firstOrNull().orEmpty() to countryCode(parts.lastOrNull())
            } ?: ("" to null)
        }
        val ticket = JsonLd.offers(e["offers"]).firstNotNullOfOrNull { JsonLd.absolute(JsonLd.url(it["url"]), page) }
        Concerts.Event(
            artist = artist, name = JsonLd.text(e["name"])?.let { ShopPlatforms.plain(it) }.orEmpty(), date = date, time = time,
            city = city.let { ShopPlatforms.plain(it) }, venue = JsonLd.text(place["name"])?.let { ShopPlatforms.plain(it) }.orEmpty(),
            country = country.orEmpty(), url = ticket ?: JsonLd.absolute(JsonLd.url(e["url"]), page) ?: page, source = Concerts.Source.SITE,
        )
    }.filter { it.country.isNotEmpty() }

    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")

    // ---------------------------------------------------------------- Bandsintown

    private val BIT_WIDGET = Regex("""<[a-z]+\b[^>]*class\s*=\s*["'][^"']*bit-widget-initializer[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String) = Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.trim()

    /** The artist a page's Bandsintown widget shows and the app id it asks with; null without a widget or its app id. */
    fun bandsintownWidget(html: String): Pair<String, String>? {
        val tag = BIT_WIDGET.find(html)?.value ?: return null
        val name = attr(tag, "data-artist-name")?.let { ShopPlatforms.plain(it) }?.takeIf { it.isNotEmpty() } ?: return null
        val app = attr(tag, "data-app-id")?.takeIf { it.isNotEmpty() } ?: return null
        return name to app
    }

    /** Bandsintown's events of the artist (`/artists/{name}/events`): date, time, venue, city, country, the ticket offer's link. */
    fun parseBandsintown(body: String, artist: String): List<Concerts.Event> = parseJson(body).arr.mapNotNull { e ->
        val at = e["datetime"].str ?: e["starts_at"].str ?: return@mapNotNull null
        val date = at.take(10).takeIf { DAY.matches(it) } ?: return@mapNotNull null
        val v = e["venue"]
        val country = countryCode(v["country"].str) ?: return@mapNotNull null
        val ticket = e["offers"].arr.firstNotNullOfOrNull { o -> webUrl(o["url"].str).takeIf { it.isNotEmpty() && o["status"].str != "sold out" } }
        Concerts.Event(
            artist = artist, name = e["title"].str.orEmpty().ifEmpty { v["name"].str.orEmpty() }, date = date,
            time = at.drop(11).take(5).takeIf { TIME.matches(it) }, city = v["city"].str.orEmpty(), venue = v["name"].str.orEmpty(),
            country = country, url = ticket ?: webUrl(e["url"].str), source = Concerts.Source.SITE,
        )
    }

    // ---------------------------------------------------------------- Seated

    private val SEATED_WIDGET = Regex("""<[a-z]+\b[^>]*\bid\s*=\s*["']seated-[0-9a-f]+["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val UUID = Regex("[0-9a-fA-F-]{8,64}")

    /** The artist id of a page's Seated widget (`<div id="seated-…" data-artist-id="…">` with widget.seated.com), or null. */
    fun seatedWidget(html: String): String? {
        if (!html.contains("widget.seated.com", ignoreCase = true)) return null
        val tag = SEATED_WIDGET.find(html)?.value ?: return null
        return attr(tag, "data-artist-id")?.takeIf { UUID.matches(it) }
    }

    /**
     * Seated's public tour (`/api/tour/{artist}?include=tour-events`, JSON:API): its tour events' local
     * day, venue, city and country (its own fields, else the formatted address's first and last parts), the event's ticket page.
     */
    fun parseSeated(body: String, artist: String): List<Concerts.Event> = parseJson(body)["included"].arr.mapNotNull { e ->
        if (e["type"].str != "tour-events") return@mapNotNull null
        val a = e["attributes"]
        val date = listOf("starts-at-date-local", "starts-at-date", "starts-at").firstNotNullOfOrNull { a[it].str?.take(10)?.takeIf { d -> DAY.matches(d) } }
            ?: return@mapNotNull null
        val parts = a["formatted-address"].str?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val country = listOf("venue-country-code", "venue-country", "country").firstNotNullOfOrNull { countryCode(a[it].str) }
            ?: parts.lastOrNull()?.takeIf { it.length > 3 }?.let { countryCode(it) } ?: return@mapNotNull null
        if (a["is-cancelled"].bool == true) return@mapNotNull null
        val id = e["id"].str
        Concerts.Event(
            artist = artist, name = a["name"].str.orEmpty(), date = date, city = a["venue-city"].str ?: parts.firstOrNull().orEmpty(),
            venue = a["venue-name"].str.orEmpty(), country = country,
            url = webUrl(a["ticket-url"].str).ifEmpty { id?.let { "https://link.seated.com/$it" }.orEmpty() }, source = Concerts.Source.SITE,
        )
    }

    // ---------------------------------------------------------------- countries

    /** Country names in English, in the main languages and in each country's own, to their ISO code. */
    private val NAMES: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        val iso3 = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val l = Locale("", code)
            m[l.getDisplayCountry(Locale.ENGLISH).lowercase()] = code
            runCatching { iso3[l.isO3Country.lowercase()] = code }
        }
        // In the languages tour pages are most written in, and each country's own.
        val languages = listOf("de", "fr", "es", "it", "pt", "nl", "pl", "sv", "da", "nb", "fi", "cs", "tr", "ru", "ja", "ko", "zh").map { Locale(it) }
        for (code in Locale.getISOCountries()) for (lang in languages) m.putIfAbsent(Locale("", code).getDisplayCountry(lang).lowercase(), code)
        for (loc in Locale.getAvailableLocales()) {
            if (loc.country.length != 2) continue
            m.putIfAbsent(Locale("", loc.country).getDisplayCountry(loc).lowercase(), loc.country)
        }
        m += mapOf("usa" to "US", "united states of america" to "US", "u.s.a." to "US", "uk" to "GB", "england" to "GB", "scotland" to "GB",
            "wales" to "GB", "northern ireland" to "GB", "great britain" to "GB", "the netherlands" to "NL", "holland" to "NL",
            "south korea" to "KR", "korea" to "KR", "czech republic" to "CZ", "russia" to "RU", "türkiye" to "TR", "turkey" to "TR")
        iso3.forEach { (k, v) -> m.putIfAbsent(k, v) }
        m
    }

    /** "FR", "France", "Frankreich", "FRA" as an ISO 3166 code; null when it is no country. */
    fun countryCode(s: String?): String? {
        val v = s?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length == 2) return Concerts.country(v)
        return NAMES[v.lowercase()]
    }
}

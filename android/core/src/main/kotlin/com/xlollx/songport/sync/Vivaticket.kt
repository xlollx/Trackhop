package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

/**
 * Vivaticket (vivaticket.com) as an unofficial source of concert dates in Italy, for [Concerts]: the
 * JSON its own web pages read (its "API gateway"), without signing in. Only where the unofficial
 * sources are allowed ([enabled]: the GitHub and F-Droid builds, Docker and Desktop but the Microsoft
 * Store package; never Google Play or AppGallery) and only for Italy ([COUNTRY]). Polite: one request
 * a second ([pace]), the app's own User-Agent, each artist's answer kept a day like Ticketmaster's.
 * When the site answers with an error, or with something no longer shaped as it was, it is left
 * alone for an hour ([STOP_MS]) and the log says why: the other sources carry on.
 *
 * For an artist: the site's search finds the artist's own page by its exact name (no tributes, no
 * shows that only mention them), the artist's page lists what is on sale; a product that covers
 * several nights (first and last on different days) is opened for its dates, at most [MAX_DETAILS]
 * an artist. Its robots.txt allows every agent everywhere.
 *
 * TicketOne is not read: its site answers browsers only, and the search API behind it
 * (public-api.eventim.com) is closed by its robots.txt to every agent but Google's.
 */
object Vivaticket {
    const val COUNTRY = "IT"
    const val MAX_DETAILS = 6
    const val STOP_MS = 3_600_000L
    /** Where a product's page is: the tickets are bought there. */
    const val SITE = "https://www.vivaticket.com/it"

    /** On where the unofficial sources are allowed: set at start by those builds only. */
    @Volatile var enabled: Boolean = false

    // Tests point it at a local server.
    @Volatile internal var base = "https://apigatewayb2cstore.vivaticket.com/api"
    @Volatile internal var pace = Pacer(1_100)
    /** Until when the site is left alone after an error or an answer that changed shape. */
    @Volatile internal var stoppedUntil = 0L

    /** The site answered something this reader no longer understands, or an error. */
    class Stop(message: String) : Exception(message)

    /** A product on sale: [start]/[end] are the site's local date-times (yyyy-MM-ddTHH:mm). */
    data class Product(val id: String, val title: String, val slug: String, val start: String, val end: String, val city: String, val venue: String) {
        val url: String get() = "$SITE/ticket/${Http.enc(slug)}/${Http.enc(id)}"
        val severalDays: Boolean get() = start.take(10) != end.take(10)
    }

    /**
     * [artist]'s dates on Vivaticket in [country]: empty when it is not asked (not allowed in this
     * build, another country), null when it did not answer (then not remembered).
     */
    internal suspend fun dates(env: Env, artist: String, country: String): List<Concerts.Event>? {
        if (!enabled || !country.equals(COUNTRY, ignoreCase = true)) return emptyList()
        val ck = Concerts.cacheKey(Concerts.Source.VIVATICKET, artist, COUNTRY)
        Concerts.cached(env, ck)?.let { return it }
        if (Concerts.now() < stoppedUntil) return null
        val today = Instant.ofEpochMilli(Concerts.now()).atZone(Concerts.zone()).toLocalDate()
        return try {
            val id = get("Home/Search/it/it-IT?q=${Http.enc(artist)}")?.let { artistId(it, artist) }
            val events = if (id == null) emptyList() else {
                val products = get("Artist/${Http.enc(id)}/it/it-IT")?.let { parseArtist(it, today) }.orEmpty()
                var opened = 0
                products.flatMap { p ->
                    val days = if (p.severalDays && opened++ < MAX_DETAILS) get("Event/${Http.enc(p.id)}/it/it-IT")?.let { parseDates(it) } else null
                    toEvents(artist, p, days, today)
                }
            }
            Concerts.remember(env, ck, events)
            events
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            stoppedUntil = Concerts.now() + STOP_MS
            env.log("concerts", "Vivaticket (unofficial): ${e.message ?: e.javaClass.simpleName}; left alone for an hour")
            null
        }
    }

    /** One paced GET of the gateway; null for "no content" (a product without its own dates). */
    private suspend fun get(path: String): String? {
        val r = pace.paced {
            Http.send(
                "GET", "$base/$path", mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                maxRateLimitRetries = 0, retryServerErrors = false,
            )
        }
        if (r.code == 204) return null
        if (!r.ok) throw Stop("answered ${r.code}")
        // A waiting room or a challenge page instead of the JSON: the site does not want this now.
        if (!r.body.trimStart().startsWith("{")) throw Stop("the answer is no longer JSON")
        return r.body
    }

    private fun shaped(body: String, what: String): JsonObject =
        runCatching { parseJson(body) as? JsonObject }.getOrNull() ?: throw Stop("the $what answer changed")

    /** The id of the artist named exactly [artist] in a search's answer, or null when there is none. */
    fun artistId(body: String, artist: String): String? {
        val items = shaped(body, "search")["artists"]["items"] as? JsonArray ?: throw Stop("the search answer changed")
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return null
        return items.firstOrNull { Matcher.normalizeArtist(it["name"].str.orEmpty()) == want }?.get("id").str
    }

    /**
     * The products on an artist's page that are concerts still to come: held somewhere (not the
     * "online exclusive" merchandise, which has no city), not over before [today].
     */
    fun parseArtist(body: String, today: LocalDate): List<Product> {
        val events = shaped(body, "artist")["events"] as? JsonArray ?: throw Stop("the artist answer changed")
        return events.mapNotNull { e ->
            if (e["typology"].str?.equals("ticket", ignoreCase = true) == false) return@mapNotNull null
            val id = e["id"].str ?: return@mapNotNull null
            val start = e["startDate"].str?.takeIf { DAY.matches(it.take(10)) } ?: return@mapNotNull null
            val end = e["endDate"].str?.takeIf { DAY.matches(it.take(10)) } ?: start
            val city = e["cityName"].str?.trim().orEmpty()
            if (city.isEmpty() || end.take(10) < today.toString()) return@mapNotNull null
            Product(id, e["title"].str.orEmpty(), e["slug"].str.orEmpty().ifEmpty { "event" }, start, end, city, e["venueName"].str?.trim().orEmpty())
        }
    }

    /** The dates (yyyy-MM-dd to HH:mm or null) of a product's own page, each once. */
    fun parseDates(body: String): List<Pair<String, String?>> {
        val info = shaped(body, "event")["infoPerformance"] as? JsonObject ?: throw Stop("the event answer changed")
        val fromPerformances = info["performances"].arr.mapNotNull { p ->
            val at = p["datetime"].str ?: return@mapNotNull null
            if (!DAY.matches(at.take(10))) return@mapNotNull null
            at.take(10) to at.drop(11).take(5).takeIf { TIME.matches(it) }
        }
        val fromDates = info["dates"].arr.mapNotNull { d -> d.str?.take(10)?.takeIf { DAY.matches(it) } }.map { it to null as String? }
        // The site sometimes repeats the first night's date-time on every performance: then its list
        // of dates is the better one (when it has more).
        val days = if (fromDates.size > fromPerformances.distinctBy { it.first }.size) fromDates else fromPerformances
        return days.distinctBy { it.first }
    }

    /**
     * [p] as dates: one for a single night; for several, [days] from its own page (else its first
     * night only, the one date known for sure), all within its first and last night, none past.
     */
    fun toEvents(artist: String, p: Product, days: List<Pair<String, String?>>?, today: LocalDate): List<Concerts.Event> {
        val first = p.start.take(10)
        val last = p.end.take(10)
        val ownTime = p.start.drop(11).take(5).takeIf { TIME.matches(it) }
        val list = if (!p.severalDays || days.isNullOrEmpty()) listOf(first to ownTime)
            else days.filter { it.first in first..last }.map { (d, t) -> d to (t ?: ownTime) }.ifEmpty { listOf(first to ownTime) }
        return list.filter { it.first >= today.toString() }.map { (d, t) ->
            Concerts.Event(
                artist = artist, name = p.title, date = d, time = t, city = cityName(p.city), venue = p.venue,
                country = COUNTRY, url = p.url, source = Concerts.Source.VIVATICKET,
            )
        }
    }

    /** "CASALECCHIO DI RENO" as "Casalecchio di Reno": the site writes its cities in capitals. */
    fun cityName(s: String): String {
        if (s != s.uppercase()) return s
        return s.lowercase().split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            if (i > 0 && w in PARTICLES) w else w.split('\'').joinToString("'") { it.replaceFirstChar(Char::titlecase) }
        }.joinToString(" ")
    }

    private val PARTICLES = setOf("di", "del", "della", "delle", "dei", "degli", "de", "da", "sul", "sulla", "in", "al", "e")
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")
}

package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Concerts of the artists of this playlist" (Discover): the upcoming dates of a playlist's artists
 * in one country, from the Ticketmaster Discovery API and from MusicBrainz's events.
 *
 * Ticketmaster takes a Consumer Key (never its secret): the user's own, in the token vault
 * ([KEY_ID], [ServiceKeys]), or else a server's owner's ([BUILD_KEY], the environment of a Docker or
 * Desktop server). No app build carries one: Ticketmaster's licence cannot be passed on to the
 * app's users, so without their own key the app shows MusicBrainz's dates only. It is used when
 * the user asks, and in the background only with the user's own key, for the artists they follow
 * ([ArtistAlerts], once a day at most), every answer is kept a day per artist, country and
 * time range ([TTL_MS]), and when the day's calls (5000) are gone it stops and says so
 * ([Result.quotaHit]) until Ticketmaster's reset. On the whole playlist, the
 * [LookupCaps.CONCERTS] artists it holds most are looked up. MusicBrainz is free and keyless, one request a second (shared with
 * the rest of the app through [MusicBrainz.pace]); its places are looked up once to know their
 * city and country, and remembered for months. In Italy, where the unofficial sources are allowed,
 * Vivaticket's dates too ([Vivaticket], unofficial), and on Android TicketOne's, read from its web
 * pages in a browser engine on the phone ([TicketOne], unofficial). Where the user has the Spotify (web)
 * connector, the tour dates of the artists' pages on Spotify ([SpotifyConcerts], unofficial), in any
 * country. A date several know is shown once: a ticket
 * site's own (Ticketmaster's first), with the other ticket sites' links ([Event.alsoOn]). The artists
 * with no date found get search links to ticket sites ([searches]): links only, opened in the
 * user's browser.
 */
object Concerts {
    /** The user's own key, in the token vault ([ServiceKeys]). */
    const val KEY_ID = "ticketmaster"
    /** A server's own key, through BuildDefaults (the environment; app builds carry none). */
    const val BUILD_KEY = "TICKETMASTER_API_KEY"
    const val TTL_MS = 24L * 3_600_000
    /** Where to get a key: the first page of the steps (Ticketmaster's "Getting started", with "Get your API key"). */
    const val KEY_URL = "https://developer.ticketmaster.com/products-and-docs/apis/getting-started/"

    /** [unofficial]: read from the site's own web pages, not from an API offered to apps. */
    enum class Source(val label: String, val unofficial: Boolean = false) {
        TICKETMASTER("Ticketmaster"), MUSICBRAINZ("MusicBrainz"), VIVATICKET("Vivaticket", unofficial = true),
        TICKETONE("TicketOne", unofficial = true), SPOTIFY("Spotify", unofficial = true),
    }

    /** Another ticket site selling the same date. */
    @Serializable
    data class TicketLink(val source: Source, val url: String)

    /** How far ahead to look: [months] from today, null = any date. */
    enum class Range(val id: String, val months: Long?) {
        MONTH("1m", 1), THREE_MONTHS("3m", 3), SIX_MONTHS("6m", 6), YEAR("1y", 12), ANY("any", null);

        /** The last day included, from [today]; null = no end. */
        fun end(today: LocalDate): LocalDate? = months?.let { today.plusMonths(it) }

        companion object {
            fun of(id: String?): Range = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: ANY
        }
    }

    /** One date: [date] is yyyy-MM-dd, [time] HH:mm when known, [country] ISO 3166 (two letters). */
    @Serializable
    data class Event(
        val artist: String,
        val name: String,
        val date: String,
        val time: String? = null,
        val city: String = "",
        val venue: String = "",
        val country: String = "",
        val url: String = "",
        val source: Source,
        /** The other ticket sites with the same date ([merge]). */
        val alsoOn: List<TicketLink> = emptyList(),
        /** The event's time zone (an IANA id) when the source says it: Ticketmaster does. */
        val timeZone: String? = null,
        /** A small picture of the event (Ticketmaster's), and the credit it asks for; "" when none. */
        val image: String = "",
        val imageCredit: String = "",
    )

    /** A search on a ticket site; [site] null = the artist's official site, through a web search. */
    data class Link(val site: String?, val url: String)

    data class ArtistDates(val artist: String, val events: List<Event>, val searches: List<Link>)

    data class Result(
        val country: String,
        /** Artists with dates first (soonest first), then those without. */
        val artists: List<ArtistDates>,
        /** The artists asked for, before [LookupCaps.CONCERTS]. */
        val total: Int,
        /** Ticketmaster was asked (a key is there). */
        val ticketmaster: Boolean,
        val ownKey: Boolean,
        /** The day's Ticketmaster calls ran out: some artists have MusicBrainz's dates only. */
        val quotaHit: Boolean,
        /** Ticketmaster refused the key (401/403). */
        val keyRefused: Boolean,
        val range: Range = Range.ANY,
        /** TicketOne's pages were read for every artist ([TicketOne]): its concerts are not missing. */
        val ticketOneRead: Boolean = false,
    )

    // Tests point these at a local server and a fixed clock.
    @Volatile internal var tmBase = "https://app.ticketmaster.com/discovery/v2"
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var zone: () -> ZoneId = { ZoneId.systemDefault() }
    /** Under Ticketmaster's 5 a second, for the whole process. */
    @Volatile var tmPace = Pacer(550) // the FAQ says 2 a second (the API page 5): the lower one
        internal set

    private const val CACHE = "concerts"
    private const val PLACES = "musicbrainz_places"
    private const val STATE = "concerts_state"
    private const val PLACE_TTL_MS = 180L * 24 * 3_600_000

    fun ownKey(env: Env): String? = ServiceKeys.get(env, KEY_ID)

    /** The key used: the user's, else the build's; null when there is none (MusicBrainz only). */
    fun key(env: Env): String? = ownKey(env) ?: BuildDefaults[BUILD_KEY].trim().takeIf { it.isNotEmpty() }

    /** Saves the user's own Consumer Key (blank removes it). */
    fun saveKey(env: Env, key: String) = ServiceKeys.save(env, KEY_ID, key)

    /** The time range last picked, remembered. */
    fun savedRange(env: Env): Range = Range.of(env.keyValues(STATE)["range"])

    fun saveRange(env: Env, range: Range) { env.keyValues(STATE)["range"] = range.id }

    /** The country last picked (an ISO code), remembered for the artists followed ([ArtistAlerts]); null before any. */
    fun savedCountry(env: Env): String? = country(env.keyValues(STATE)["country"])

    fun saveCountry(env: Env, country: String) { country(country)?.let { env.keyValues(STATE)["country"] = it } }

    /** What a calendar entry for [e] holds: an all-day one when its time is not known. */
    data class CalendarEntry(
        val title: String,
        /** Epoch millis; for an all-day entry, the date's midnight in UTC (as calendars want it). */
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val timeZone: String,
        val location: String,
        val description: String,
    )

    /**
     * The calendar entry for [e]: "Artist – Venue", starting at its local time in its own time zone
     * (else [fallbackZone]), three hours long, at "Venue, City", the event's link in the description.
     */
    fun calendarEntry(e: Event, fallbackZone: ZoneId): CalendarEntry {
        val zone = e.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
        val day = LocalDate.parse(e.date)
        val time = e.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val title = e.artist + " – " + e.venue.ifBlank { e.name.ifBlank { e.city } }
        val location = listOf(e.venue, e.city).filter { it.isNotBlank() }.joinToString(", ")
        return if (time != null) {
            val begin = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
            CalendarEntry(title, begin, begin + 3 * 3_600_000L, false, zone.id, location, e.url)
        } else {
            val begin = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            CalendarEntry(title, begin, begin + 24 * 3_600_000L, true, "UTC", location, e.url)
        }
    }

    /** The artists to look up, the ones the playlist holds most first (the same pick as the artist mix). */
    fun artistsOf(tracks: List<Track>): List<String> = ArtistMix.topArtists(tracks, Int.MAX_VALUE)

    /**
     * The country to start from, with no location permission: the mobile network's, else the SIM's,
     * else the language's region; "US" when none is a country.
     */
    fun defaultCountry(network: String?, sim: String?, locale: Locale = Locale.getDefault()): String =
        listOf(network, sim, locale.country).firstNotNullOfOrNull { country(it) } ?: "US"

    /** [s] as an ISO 3166 country code, uppercase, or null. */
    fun country(s: String?): String? = s?.trim()?.uppercase()?.takeIf { it.length == 2 && it in Locale.getISOCountries() }

    /** The site searches for an artist with no date found, the country's ticket sellers first. */
    fun searches(artist: String, country: String): List<Link> {
        val q = enc(artist)
        val sites = when (country.uppercase()) {
            "IT" -> listOf("TicketOne" to "https://www.ticketone.it/search/?searchterm=$q", "Vivaticket" to "https://www.vivaticket.com/it/search?q=$q")
            "DE" -> listOf("Eventim" to "https://www.eventim.de/search/?searchterm=$q")
            "AT" -> listOf("oeticket" to "https://www.oeticket.com/search/?searchterm=$q")
            "CH" -> listOf("Ticketcorner" to "https://www.ticketcorner.ch/search/?searchterm=$q")
            else -> emptyList()
        }
        return sites.map { (s, u) -> Link(s, u) } + Link(null, "https://duckduckgo.com/?q=" + enc("$artist official site tour"))
    }

    /**
     * True when a known gap is worth saying for [country]: in Italy, the concerts sold only on
     * TicketOne (the app says it unless [Result.ticketOneRead]).
     */
    fun ticketOneGap(country: String) = country.equals("IT", ignoreCase = true)

    /** When the day's calls are back: Ticketmaster's reset when it said one, for this key. */
    private fun exhaustedUntil(env: Env, key: String): Long {
        val saved = env.keyValues(STATE)["tm_exhausted"] ?: return 0
        return if (saved.substringAfter('|') == key.hashCode().toString()) saved.substringBefore('|').toLongOrNull() ?: 0 else 0
    }

    private fun markExhausted(env: Env, key: String, reset: String?) {
        val at = reset?.trim()?.let { r -> r.toLongOrNull() ?: runCatching { Instant.parse(r).toEpochMilli() }.getOrNull() }
            ?.takeIf { it > now() }
            ?: Instant.ofEpochMilli(now()).truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).toEpochMilli()
        env.keyValues(STATE)["tm_exhausted"] = "$at|${key.hashCode()}"
    }

    /** The day's calls ran out: the next call is not worth sending. */
    class QuotaGone : Exception("Ticketmaster: today's limit reached")
    class KeyRefused : Exception("Ticketmaster refused the key")

    /**
     * The upcoming dates of [artists] in [country] (an ISO code). [progress] gets (done, total).
     * Never throws for one artist's failure: that artist just has fewer dates. [background]: the
     * check of the artists followed, with no one looking: Ticketmaster only with the user's own key,
     * and no source read in a browser engine on the phone ([TicketOne]) or with a web sign-in ([SpotifyConcerts]).
     */
    suspend fun find(
        env: Env, artists: List<String>, country: String, range: Range = Range.ANY, whole: Boolean = true, progress: (Int, Int) -> Unit = { _, _ -> },
        background: Boolean = false,
        partial: (Result) -> Unit = {},
    ): Result {
        val c = country(country) ?: throw IllegalArgumentException("\"$country\" is not a country code (IT, US, DE...)")
        val key = if (background) ownKey(env) else key(env)
        val own = ownKey(env) != null
        val asked = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Matcher.normalizeArtist(it).ifEmpty { it.lowercase() } }
        val list = LookupCaps.cut(asked, LookupCaps.CONCERTS, whole)
        // The user's day, not UTC's: in the evening in the Americas tonight's concerts are still to come.
        val today = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
        val end = range.end(today)
        var quotaHit = key != null && now() < exhaustedUntil(env, key)
        var refused = false
        var ticketOneRead = !background && c == TicketOne.COUNTRY && withContext(Dispatchers.IO) { TicketOne.enabled }
        val out = ArrayList<ArtistDates>()
        list.forEachIndexed { i, artist ->
            progress(i, list.size)
            val (tm, mb, sites) = coroutineScope {
                val mb = async { musicBrainz(env, artist, c, today, end) }
                val vt = async { Vivaticket.dates(env, artist, c, end) }
                val t1 = async { if (background) emptyList() else TicketOne.dates(env, artist, c).also { if (it == null) ticketOneRead = false } }
                val sp = async { if (background) emptyList() else SpotifyConcerts.dates(env, artist, c, today, end) }
                val tm = if (key == null || quotaHit || refused) null else try {
                    ticketmaster(env, key, artist, c, end)
                } catch (e: QuotaGone) {
                    quotaHit = true; null
                } catch (e: KeyRefused) {
                    refused = true; null
                }
                Triple(tm, mb.await(), vt.await().orEmpty() + t1.await().orEmpty() + sp.await().orEmpty())
            }
            val events = merge(tm.orEmpty(), mb.orEmpty(), sites).filter { inRange(it, today, end) }
            out += ArtistDates(artist, events, if (events.isEmpty()) searches(artist, c) else emptyList())
            // What is found so far: kept when the user stops the search.
            partial(result(c, out, asked.size, key != null, own, quotaHit, refused, range, ticketOneRead))
        }
        progress(list.size, list.size)
        if (quotaHit) env.log("concerts", "Ticketmaster: today's limit reached")
        return result(c, out, asked.size, key != null, own, quotaHit, refused, range, ticketOneRead)
    }

    private fun result(
        c: String, out: List<ArtistDates>, total: Int, tm: Boolean, own: Boolean, quotaHit: Boolean, refused: Boolean, range: Range, ticketOneRead: Boolean,
    ): Result {
        val (dated, without) = out.partition { it.events.isNotEmpty() }
        return Result(c, dated.sortedBy { it.events.first().date } + ArtistPick.byName(without) { it.artist }, total, tm, own, quotaHit, refused, range, ticketOneRead)
    }

    /** [e] is on [today] or after, and on [end] or before (no end = any later date). */
    fun inRange(e: Event, today: LocalDate, end: LocalDate?): Boolean =
        e.date >= today.toString() && (end == null || e.date <= end.toString())

    /**
     * One list for an artist: each source's own repeats gone; the same day in the same city (or a
     * city one of them does not say) shown once. A ticket site's date ([sites]: Vivaticket's, TicketOne's, Spotify's) joins
     * Ticketmaster's as one more link ([Event.alsoOn]); a MusicBrainz date is dropped when a ticket
     * site has it.
     */
    fun merge(tm: List<Event>, mb: List<Event>, sites: List<Event> = emptyList()): List<Event> {
        fun city(e: Event) = Matcher.normalizeArtist(e.city)
        fun same(k: Event, e: Event) = k.date == e.date && (city(k) == city(e) || city(k).isEmpty() || city(e).isEmpty())
        val seen = HashSet<String>()
        val kept = ArrayList<Event>()
        for (e in tm + sites + mb) {
            if (!seen.add("${e.source}|${e.date}|${city(e)}|${Matcher.normalizeArtist(e.venue)}")) continue
            if (e.source == Source.MUSICBRAINZ && kept.any { k -> k.source != Source.MUSICBRAINZ && same(k, e) }) continue
            if (e.source != Source.TICKETMASTER && e.source != Source.MUSICBRAINZ) {
                val i = kept.indexOfFirst { k -> k.source != e.source && same(k, e) }
                if (i >= 0) {
                    val k = kept[i]
                    if (e.url.isNotEmpty() && k.alsoOn.none { it.source == e.source }) kept[i] = k.copy(alsoOn = k.alsoOn + TicketLink(e.source, e.url))
                    continue
                }
            }
            kept += e
        }
        return kept.sortedWith(compareBy({ it.date }, { it.time ?: "" }))
    }

    // ---------------------------------------------------------------- cache

    private val eventsSerializer = ListSerializer(Event.serializer())

    internal fun cached(env: Env, k: String): List<Event>? {
        val saved = env.keyValues(CACHE)[k] ?: return null
        val at = saved.substringBefore('|').toLongOrNull() ?: return null
        if (now() - at >= TTL_MS) return null
        return runCatching { json.decodeFromString(eventsSerializer, saved.substringAfter('|')) }.getOrNull()
    }

    internal fun remember(env: Env, k: String, events: List<Event>) {
        val kv = env.keyValues(CACHE)
        if (kv.size >= 4_000) kv.clear()
        kv[k] = "${now()}|" + json.encodeToString(eventsSerializer, events)
    }

    internal fun cacheKey(source: Source, artist: String, country: String, end: LocalDate?) =
        "${source.name}|$country|${end ?: ""}|${Matcher.normalizeArtist(artist).ifEmpty { artist.lowercase() }}"

    // ---------------------------------------------------------------- Ticketmaster

    /** The artist's dates on Ticketmaster in [country]; null when it did not answer (then not remembered). */
    private suspend fun ticketmaster(env: Env, key: String, artist: String, country: String, end: LocalDate?): List<Event>? {
        val ck = cacheKey(Source.TICKETMASTER, artist, country, end)
        cached(env, ck)?.let { return it }
        val start = Instant.ofEpochMilli(now()).truncatedTo(ChronoUnit.SECONDS).toString()
        // The end's whole day, in UTC: a few hours past it are filtered out by date afterwards.
        val until = end?.let { "&endDateTime=" + enc(it.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString()) }.orEmpty()
        val url = "$tmBase/events.json?apikey=${enc(key)}&keyword=${enc(artist)}&classificationName=music" +
            "&countryCode=$country&size=50&sort=date,asc&startDateTime=${enc(start)}$until"
        for (attempt in 0..1) {
            val r = try {
                tmPace.paced { Http.send("GET", url, mapOf("Accept" to "application/json"), maxRateLimitRetries = 0) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                env.log("concerts", "Ticketmaster: ${e.message ?: e.javaClass.simpleName}"); return null
            }
            if (r.code == 429) {
                // A burst over 5 a second ("SpikeArrest", the shared key used by many at once) passes after
                // a second; the day's quota does not. A second burst only leaves this artist without its dates.
                if (!r.body.contains("QuotaViolation", ignoreCase = true)) {
                    if (attempt == 0) { delay(1_000); continue }
                    env.log("concerts", "Ticketmaster: too many requests at once"); return null
                }
                markExhausted(env, key, r.header("Rate-Limit-Reset"))
                throw QuotaGone()
            }
            if (r.code == 401 || r.code == 403) throw KeyRefused()
            if (!r.ok) { env.log("concerts", "Ticketmaster answered ${r.code}"); return null }
            val events = parseTicketmaster(r.body, artist, country)
            remember(env, ck, events)
            // The last call of the day: the next one would be refused.
            if (r.header("Rate-Limit-Available")?.trim() == "0") markExhausted(env, key, r.header("Rate-Limit-Reset"))
            return events
        }
        return null
    }

    /**
     * The events of a Discovery API answer that are [artist]'s (one of the event's attractions is
     * the artist; a keyword search also finds tributes and festivals naming them), in [country],
     * not cancelled.
     */
    fun parseTicketmaster(body: String, artist: String, country: String): List<Event> {
        val want = Matcher.normalizeArtist(artist)
        return parseJson(body)["_embedded"]["events"].arr.mapNotNull { e ->
            val attractions = e["_embedded"]["attractions"].arr.mapNotNull { it["name"].str }
            val theirs = if (attractions.isNotEmpty()) attractions.any { Matcher.normalizeArtist(it) == want }
                else Matcher.normalizeArtist(e["name"].str.orEmpty()).startsWith(want)
            if (!theirs || want.isEmpty()) return@mapNotNull null
            if (e["dates"]["status"]["code"].str.equals("cancelled", ignoreCase = true)) return@mapNotNull null
            val date = e["dates"]["start"]["localDate"].str ?: return@mapNotNull null
            val venue = e["_embedded"]["venues"][0]
            val where = venue["country"]["countryCode"].str.orEmpty().uppercase()
            if (where.isNotEmpty() && where != country) return@mapNotNull null
            Event(
                artist = artist, name = e["name"].str.orEmpty(), date = date,
                time = e["dates"]["start"]["localTime"].str?.take(5),
                city = venue["city"]["name"].str.orEmpty(), venue = venue["name"].str.orEmpty(),
                country = where.ifEmpty { country }, url = webUrl(e["url"].str), source = Source.TICKETMASTER,
                timeZone = e["dates"]["timezone"].str?.takeIf { it.isNotBlank() },
            ).let { ev -> image(e["images"].arr)?.let { (url, credit) -> ev.copy(image = url, imageCredit = credit) } ?: ev }
        }
    }

    /**
     * The picture to show small from a Discovery API event's images: a 16:9 one (else 3:2, else any)
     * of the smallest width from 200 pixels up (else the widest), with its attribution; null when none.
     */
    fun image(images: List<kotlinx.serialization.json.JsonElement>): Pair<String, String>? {
        val all = images.mapNotNull { i -> webUrl(i["url"].str).takeIf { it.isNotEmpty() }?.let { Triple(i, it, i["width"].int ?: 0) } }
        val shape = listOf("16_9", "3_2").firstNotNullOfOrNull { r -> all.filter { it.first["ratio"].str == r }.takeIf { it.isNotEmpty() } } ?: all
        val pick = shape.filter { it.third >= 200 }.minByOrNull { it.third } ?: shape.maxByOrNull { it.third } ?: return null
        return pick.second to pick.first["attribution"].str.orEmpty().trim()
    }

    // ---------------------------------------------------------------- MusicBrainz

    /** The artist's upcoming events on MusicBrainz in [country]; null when it did not answer. */
    private suspend fun musicBrainz(env: Env, artist: String, country: String, today: LocalDate, end: LocalDate?): List<Event>? {
        val ck = cacheKey(Source.MUSICBRAINZ, artist, country, end)
        cached(env, ck)?.let { return it }
        val query = "artist:\"${artist.replace("\\", "\\\\").replace("\"", "\\\"")}\" AND begin:[$today TO ${end ?: "2100-12-31"}]"
        val body = mbGet("event?query=${enc(query)}&fmt=json&limit=50") ?: return null
        val found = parseMusicBrainz(body, artist)
        val events = ArrayList<Event>()
        var unplaced = false
        for ((e, placeId, area) in found) {
            if (!inRange(e, today, end)) continue
            val at = placeId?.let { place(env, it).also { p -> if (p == null) unplaced = true } }
            var (city, where) = at ?: (null to null)
            // A place with no area (or no place at all, only "held in" a city): the event's own area.
            if (where.isNullOrEmpty() && area != null) {
                val code = areaCountry(env, area.first, 0)
                if (code == null) unplaced = true
                where = code; if (city.isNullOrEmpty()) city = area.second
            }
            // Without a known country the date cannot be placed: left out rather than shown in the wrong country.
            if (where != country) continue
            events += e.copy(city = city.orEmpty(), country = where)
        }
        // A place MusicBrainz did not answer for (busy): used now, but not remembered for a day without its date.
        if (!unplaced) remember(env, ck, events)
        return events
    }

    /** A MusicBrainz event, the id of the place it is held at, and the area it is held in (id, name). */
    data class MbEvent(val event: Event, val placeId: String?, val area: Pair<String, String>?)

    /**
     * The events of a MusicBrainz event search that [artist] performs at (a performer relation
     * naming them), not cancelled, each with the place it is held at and the area it is held in.
     */
    fun parseMusicBrainz(body: String, artist: String): List<MbEvent> {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        return parseJson(body)["events"].arr.mapNotNull { e ->
            if (e["cancelled"].bool == true) return@mapNotNull null
            val rels = e["relations"].arr
            if (rels.none { r -> r["artist"]["name"].str?.let { Matcher.normalizeArtist(it) } == want }) return@mapNotNull null
            val date = e["life-span"]["begin"].str?.takeIf { it.length == 10 } ?: return@mapNotNull null
            val place = rels.firstOrNull { it["place"]["id"].str != null }?.get("place")
            val area = rels.firstOrNull { it["area"]["id"].str != null }?.get("area")
            val id = e["id"].str ?: return@mapNotNull null
            MbEvent(
                Event(
                    artist = artist, name = e["name"].str.orEmpty(), date = date, time = e["time"].str?.take(5),
                    venue = place["name"].str.orEmpty(), url = "https://musicbrainz.org/event/$id", source = Source.MUSICBRAINZ,
                ),
                place["id"].str, area?.let { a -> a["id"].str!! to a["name"].str.orEmpty() },
            )
        }
    }

    /** A place's city and country, asked once and remembered: places do not move. */
    private suspend fun place(env: Env, id: String): Pair<String, String>? {
        val kv = env.keyValues(PLACES)
        kv["p|$id"]?.let { s ->
            val at = s.substringBefore('|').toLongOrNull() ?: 0
            if (now() - at < PLACE_TTL_MS) return s.substringAfter('|').split('\t').let { it[0] to it.getOrElse(1) { "" } }
        }
        val body = mbGet("place/${enc(id)}?fmt=json") ?: return null
        val area = parseJson(body)["area"]
        val city = area["name"].str.orEmpty()
        val areaId = area["id"].str
        // An area whose country could not be read (MusicBrainz busy on the way up) is not remembered
        // for months as "nowhere": asked again next time.
        val country = areaId?.let { areaCountry(env, it, 0) ?: return null }.orEmpty()
        if (kv.size >= 5_000) kv.clear()
        kv["p|$id"] = "${now()}|$city\t$country"
        return city to country
    }

    /** The country an area is in: its own ISO code, or its parent's ("part of"), a few levels up at most. */
    private suspend fun areaCountry(env: Env, id: String, depth: Int): String? {
        val kv = env.keyValues(PLACES)
        kv["a|$id"]?.let { return it }
        val a = parseJson(mbGet("area/${enc(id)}?inc=area-rels&fmt=json") ?: return null)
        var found = codeOf(a)
        if (found == null) {
            val parents = a["relations"].arr.filter { it["type"].str == "part of" && it["direction"].str == "backward" }.mapNotNull { it["area"] }
            found = parents.firstNotNullOfOrNull { codeOf(it) }
            if (found == null && depth < 3) found = parents.firstNotNullOfOrNull { p -> p["id"].str?.let { areaCountry(env, it, depth + 1) } }
        }
        if (found != null) kv["a|$id"] = found
        return found
    }

    /** "IT" from an area's iso-3166-1 code, or from the prefix of its iso-3166-2 code ("PL-MZ"). */
    private fun codeOf(area: kotlinx.serialization.json.JsonElement?): String? =
        area["iso-3166-1-codes"].arr.firstNotNullOfOrNull { country(it.str) }
            ?: area["iso-3166-2-codes"].arr.firstNotNullOfOrNull { country(it.str?.substringBefore('-')) }

    private suspend fun mbGet(path: String): String? = MusicBrainz.get(path)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * [s] when it is a web address (http or https), else "": a link from another service's answer is
 * opened by the app and put in the web pages, where a javascript: or intent: one must never get.
 */
internal fun webUrl(s: String?): String {
    val u = s?.trim().orEmpty()
    return if (u.startsWith("https://", ignoreCase = true) || u.startsWith("http://", ignoreCase = true)) u else ""
}

package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Followed venues (Discover › Concerts › Venues) and "Concerts near you": the venues are searched
 * on Ticketmaster's Discovery API (`/venues.json`, with the same key, pace and daily count as
 * [Concerts]) and among MusicBrainz's places (keyless, one request a second), then followed; what is
 * on there in the next [WEEKS] weeks comes from Ticketmaster's events of the venue (`venueId`) or
 * MusicBrainz's events held at the place, whoever plays. A city the user types (no location
 * permission) adds Ticketmaster's music events there. Every answer is kept a day, like the artists'
 * dates. The playlist: the best known songs of who plays ([nearbyTracks]).
 */
object Venues {
    const val WEEKS = 4
    /** The artists of "Concerts near you", the soonest to play first, and the songs of each. */
    const val MAX_ARTISTS = 40
    const val SONGS_EACH = 3
    private const val TOP_TTL_MS = 30L * 24 * 3_600_000
    private const val TOP = "venues_top"

    /** A venue followed: [id] is "tm:<Ticketmaster's id>" or "mb:<MusicBrainz place id>"; [country] an ISO code, "" when not known. */
    @Serializable
    data class Venue(val id: String, val name: String, val city: String = "", val country: String = "") {
        val ticketmaster: Boolean get() = id.startsWith("tm:")
        val key: String get() = id.substringAfter(':')
    }

    /** One date at a followed venue or in the city: [venue] the followed venue it is at (null: the city's). */
    data class Night(val event: Concerts.Event, val venue: Venue?)

    /** [unanswered]: the venues (and the city) no source answered for; [ticketmaster]: a key is there. */
    data class Upcoming(val nights: List<Night>, val unanswered: Int, val ticketmaster: Boolean, val keyRefused: Boolean = false, val quotaHit: Boolean = false)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun norm(s: String) = Matcher.normalizeArtist(s).ifEmpty { s.trim().lowercase() }

    // ---------------------------------------------------------------- the list

    fun isFollowed(list: List<Venue>, v: Venue): Boolean = list.any { it.id == v.id }

    /** [list] with [v] followed, or no longer. */
    fun toggle(list: List<Venue>, v: Venue): List<Venue> = if (isFollowed(list, v)) list.filter { it.id != v.id } else list + v

    // ---------------------------------------------------------------- search

    /** The venues of a Discovery API venue search. */
    fun parseTicketmasterVenues(body: String): List<Venue> = parseJson(body)["_embedded"]["venues"].arr.mapNotNull { v ->
        val id = v["id"].str ?: return@mapNotNull null
        val name = v["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Venue("tm:$id", name, v["city"]["name"].str.orEmpty(), v["country"]["countryCode"].str.orEmpty().uppercase())
    }

    /** The places of a MusicBrainz place search that are somewhere to play (a venue, a stadium, a festival stage, or not said), not closed. */
    fun parseMusicBrainzPlaces(body: String): List<Venue> = parseJson(body)["places"].arr.mapNotNull { p ->
        val id = MusicBrainz.mbid(p["id"].str) ?: return@mapNotNull null
        val name = p["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (p["type"].str?.lowercase() in setOf("studio", "pressing plant", "religious building", "educational institution")) return@mapNotNull null
        if (p["life-span"]["ended"].bool == true) return@mapNotNull null
        Venue("mb:$id", name, p["area"]["name"].str.orEmpty())
    }

    /**
     * The venues named like [query] (in [country] on Ticketmaster): Ticketmaster's first, then the
     * places only MusicBrainz knows. Throws [Concerts.KeyRefused] or [Concerts.QuotaGone] only when
     * MusicBrainz has nothing either.
     */
    suspend fun search(env: Env, query: String, country: String): List<Venue> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val key = Concerts.key(env)
        var failure: Exception? = null
        val tm = if (key == null) emptyList() else try {
            Concerts.ticketmasterCall(env, key, "${Concerts.tmBase}/venues.json?apikey=${enc(key)}&keyword=${enc(q)}&countryCode=${enc(country)}&size=20")
                ?.let { parseTicketmasterVenues(it) }.orEmpty()
        } catch (e: Concerts.QuotaGone) { failure = e; emptyList() } catch (e: Concerts.KeyRefused) { failure = e; emptyList() }
        val mb = MusicBrainz.get("place?query=${enc(q.replace("\"", " "))}&fmt=json&limit=10")?.let { parseMusicBrainzPlaces(it) }.orEmpty()
        val known = tm.mapTo(HashSet()) { norm(it.name) + "|" + norm(it.city) }
        val all = tm + mb.filter { norm(it.name) + "|" + norm(it.city) !in known }
        if (all.isEmpty() && failure != null) throw failure
        return all
    }

    // ---------------------------------------------------------------- what is on

    /**
     * The music events of a Discovery API answer, whoever plays: the first act named is the artist,
     * all of them the bill; an event naming no act goes under its own name. Cancelled ones too, marked.
     */
    fun parseTicketmasterEvents(body: String): List<Concerts.Event> = parseJson(body)["_embedded"]["events"].arr.mapNotNull { e ->
        val acts = e["_embedded"]["attractions"].arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val artist = acts.firstOrNull() ?: e["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Concerts.ticketmasterEvent(e, artist, null)?.copy(lineup = acts)
    }

    /** The events of a MusicBrainz event search held at a place, with who performs; cancelled ones left out. */
    fun parseMusicBrainzEvents(body: String, venue: Venue): List<Concerts.Event> = parseJson(body)["events"].arr.mapNotNull { e ->
        if (e["cancelled"].bool == true) return@mapNotNull null
        val id = e["id"].str ?: return@mapNotNull null
        val date = e["life-span"]["begin"].str?.takeIf { it.length == 10 } ?: return@mapNotNull null
        val acts = e["relations"].arr.mapNotNull { it["artist"]["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct()
        val artist = acts.firstOrNull() ?: e["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Concerts.Event(
            artist = artist, name = e["name"].str.orEmpty(), date = date, time = e["time"].str?.take(5), city = venue.city, venue = venue.name,
            country = venue.country, url = "https://musicbrainz.org/event/$id", source = Concerts.Source.MUSICBRAINZ, lineup = acts,
        )
    }

    private val eventsSerializer = ListSerializer(Concerts.Event.serializer())

    private fun window(weeks: Int): Pair<LocalDate, LocalDate> {
        val today = Instant.ofEpochMilli(Concerts.now()).atZone(Concerts.zone()).toLocalDate()
        return today to today.plusWeeks(weeks.toLong())
    }

    private suspend fun ticketmasterEvents(env: Env, key: String, ck: String, where: String, end: LocalDate): List<Concerts.Event>? {
        Concerts.cached(env, ck)?.let { return it }
        val start = Instant.ofEpochMilli(Concerts.now()).truncatedTo(ChronoUnit.SECONDS).toString()
        val until = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString()
        val body = Concerts.ticketmasterCall(env, key, "${Concerts.tmBase}/events.json?apikey=${enc(key)}&$where&classificationName=music&size=100&sort=date,asc&startDateTime=${enc(start)}&endDateTime=${enc(until)}")
            ?: return null
        return parseTicketmasterEvents(body).also { Concerts.remember(env, ck, it) }
    }

    /**
     * What is on at [venue] from today to [weeks] weeks on, the soonest first (cancelled dates
     * included, marked); null when its source did not answer or, for a Ticketmaster venue, there is
     * no key. [background]: only with the user's own key, as for the artists followed.
     */
    suspend fun events(env: Env, venue: Venue, weeks: Int = WEEKS, background: Boolean = false): List<Concerts.Event>? {
        val (today, end) = window(weeks)
        if (venue.ticketmaster) {
            val key = (if (background) Concerts.ownKey(env) else Concerts.key(env)) ?: return null
            return ticketmasterEvents(env, key, "V|${venue.id}|$end", "venueId=${enc(venue.key)}", end)
        }
        val ck = "V|${venue.id}|$end"
        Concerts.cached(env, ck)?.let { return it }
        val body = MusicBrainz.get("event?query=${enc("pid:${venue.key} AND begin:[$today TO $end]")}&fmt=json&limit=100") ?: return null
        return parseMusicBrainzEvents(body, venue).filter { Concerts.inRange(it, today, end) }.sortedBy { it.date }.also { Concerts.remember(env, ck, it) }
    }

    /** The music events in [city] (as typed) of [country] on Ticketmaster; null without a key or an answer. */
    suspend fun cityEvents(env: Env, city: String, country: String, weeks: Int = WEEKS, background: Boolean = false): List<Concerts.Event>? {
        val key = (if (background) Concerts.ownKey(env) else Concerts.key(env)) ?: return null
        val (_, end) = window(weeks)
        return ticketmasterEvents(env, key, "C|$country|${norm(city)}|$end", "city=${enc(city.trim())}&countryCode=${enc(country)}", end)
    }

    /**
     * The nights to come at [venues] and in [city] (blank: none), the soonest first, each once, the
     * cancelled ones left out. Never throws for one venue: it is counted in [Upcoming.unanswered].
     */
    suspend fun upcoming(
        env: Env, venues: List<Venue>, city: String, country: String, weeks: Int = WEEKS, background: Boolean = false,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Upcoming {
        val nights = ArrayList<Night>()
        var unanswered = 0
        var refused = false
        var quota = false
        val steps = venues.size + if (city.isBlank()) 0 else 1
        suspend fun ask(i: Int, venue: Venue?, read: suspend () -> List<Concerts.Event>?) {
            progress(i, steps)
            val found = if (refused || quota) null else try { read() } catch (e: Concerts.KeyRefused) { refused = true; null } catch (e: Concerts.QuotaGone) { quota = true; null }
            if (found == null) unanswered++ else nights += found.filter { it.status != Concerts.CANCELLED }.map { Night(it, venue) }
        }
        venues.forEachIndexed { i, v -> ask(i, v) { events(env, v, weeks, background) } }
        if (city.isNotBlank()) ask(venues.size, null) { cityEvents(env, city, country, weeks, background) }
        progress(steps, steps)
        val once = nights.distinctBy { n -> n.event.id.ifEmpty { "${n.event.date}|${norm(n.event.venue)}|${norm(n.event.artist)}" } }
        return Upcoming(once.sortedWith(compareBy({ it.event.date }, { it.event.time ?: "" })), unanswered, Concerts.key(env) != null, refused, quota)
    }

    /** Who plays, the soonest first, each once: every act of each night's bill. */
    fun artists(nights: List<Night>): List<String> = nights.flatMap { n -> n.event.lineup.ifEmpty { listOf(n.event.artist) } }.distinctBy { norm(it) }

    // ---------------------------------------------------------------- "Concerts near you"

    private val trackList = ListSerializer(Track.serializer())

    /** An artist's best known songs ([ArtistMixSource.popular], as the playlist generator), asked once a month; empty when nobody knows the artist. */
    private suspend fun topSongs(env: Env, artist: String): List<Track> {
        val kv = env.keyValues(TOP)
        val k = norm(artist)
        kv[k]?.let { s ->
            val at = s.substringBefore('|').toLongOrNull() ?: 0
            if (Concerts.now() - at < TOP_TTL_MS) runCatching { json.decodeFromString(trackList, s.substringAfter('|')) }.getOrNull()?.let { return it }
        }
        val a = ArtistIds.mbid(env, artist)?.let { ArtistMix.Artist(it, artist) } ?: ArtistMixSource.findArtist(artist)
        // A festival night or a tribute act MusicBrainz knows under another name: only the very same name.
        val songs = if (a == null || norm(a.name) != k) emptyList()
            else ArtistMixSource.popular(a, ArtistMixSource.listenBrainzToken(env)).take(SONGS_EACH).map { CsvCodec.withStableId(it.copy(id = "")) }
        if (kv.size >= 3_000) kv.clear()
        kv[k] = "${Concerts.now()}|" + json.encodeToString(trackList, songs)
        return songs
    }

    /**
     * "Concerts near you": the best known songs of who plays at the venues followed or in the city
     * typed, in the next [WEEKS] weeks, the soonest to play first. Throws when there is nowhere to
     * look, or no source answered: a sync that learned nothing must not empty its playlist.
     */
    suspend fun nearbyTracks(env: Env, progress: (Int, Int) -> Unit = { _, _ -> }): List<Track> {
        val s = env.store.data.settings
        if (s.followedVenues.isEmpty() && s.concertCity.isBlank()) throw ProviderException(env.text("venues_empty"))
        val country = Concerts.savedCountry(env) ?: Concerts.defaultCountry(null, null)
        val up = upcoming(env, s.followedVenues, s.concertCity, country)
        val asked = s.followedVenues.size + if (s.concertCity.isBlank()) 0 else 1
        if (up.unanswered == asked) throw ProviderException(env.text("venues_unanswered"))
        val who = artists(up.nights).take(MAX_ARTISTS)
        val out = ArrayList<Track>()
        who.forEachIndexed { i, artist ->
            progress(i, who.size)
            out += try { topSongs(env, artist) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        return out.distinctBy { it.id }
    }
}

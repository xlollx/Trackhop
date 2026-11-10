package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.json
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.time.ZoneId

/**
 * New releases from the artists of a playlist: the albums, EPs and singles they put out in the
 * last days (7, 30 or 90), and the ones announced, where the source knows them. Two public
 * catalogues, no key and no account, so on every build:
 *
 * - Deezer, `https://api.deezer.com/search/artist?q=…` then `/artist/<id>/albums`: each album with
 *   its release date and type (album, ep, single, compile). Paced like the artist mix's Deezer calls.
 * - MusicBrainz, a release-group search by the artist's name with `firstreleasedate:[<from> TO *]`:
 *   one request per artist, future dates included. Through the same one-a-second pace and
 *   User-Agent as every other MusicBrainz call of the app ([MusicBrainz.pace], [ArtistMixSource.userAgent]).
 *
 * The same album on both is shown once. What an artist has is remembered for a day ([TTL_MS]), so
 * another window, another playlist with the same artists or the weekly sync ask nothing again.
 */
object NewReleases {
    enum class Type { ALBUM, EP, SINGLE }

    @Serializable
    data class Release(
        val artist: String,
        val title: String,
        val type: Type,
        /** "2026-11-07", or "2026-11" when the source knows only the month. */
        val date: String,
        val deezerId: Long? = null,
        val mbid: String? = null,
        val cover: String? = null,
    ) {
        /** Where to see it: Deezer's page when Deezer has it, else MusicBrainz's. */
        val link: String get() = deezerId?.let { "https://www.deezer.com/album/$it" } ?: "https://musicbrainz.org/release-group/$mbid"
        val day: LocalDate? get() = dayOf(date)
    }

    /** What a search found: the releases in the window, the artists asked about, and how many could not be asked (a source down). */
    data class Result(val releases: List<Release>, val artists: List<String>, val unanswered: Int)

    val WINDOWS = listOf(7, 30, 90)
    const val MAX_ARTISTS = 50
    private const val KV = "new_releases"
    const val TTL_MS = 24L * 3_600_000
    /** How far back an artist's releases are kept: the longest window, and a little more. */
    private const val KEEP_DAYS = 100L

    /** Tests point these at a local server and a fixed clock. */
    @Volatile internal var deezer = "https://api.deezer.com"
    @Volatile internal var musicBrainz = "https://musicbrainz.org/ws/2"
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }
    @Volatile internal var deezerPace = Pacer(150)

    private fun today(): LocalDate = java.time.Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate()

    /** "2026-11-07" or "2026-11" (the first of the month); null for a year alone or anything else. */
    fun dayOf(date: String): LocalDate? = runCatching {
        when {
            Regex("""\d{4}-\d{2}-\d{2}""").matches(date) -> LocalDate.parse(date)
            Regex("""\d{4}-\d{2}""").matches(date) -> LocalDate.parse("$date-01")
            else -> null
        }
    }.getOrNull()?.takeIf { it.year > 1900 }

    /** The artists of a playlist, most frequent first: the first credited artist of each track. */
    fun artists(tracks: List<Track>, max: Int = MAX_ARTISTS): List<String> = CoverPrompt.topArtists(tracks, max)

    // ------------------------------------------------------------------ parsing

    /** Deezer's `/artist/<id>/albums`: data[] with id, title, release_date, record_type, cover_medium. Compilations left out. */
    fun parseDeezerAlbums(body: String, artist: String): List<Release> = parseJson(body)["data"].arr.mapNotNull { a ->
        val type = when (a["record_type"].str?.lowercase()) {
            "album" -> Type.ALBUM
            "ep" -> Type.EP
            "single" -> Type.SINGLE
            else -> return@mapNotNull null
        }
        val date = a["release_date"].str?.takeIf { dayOf(it) != null } ?: return@mapNotNull null
        val title = a["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Release(artist, title, type, date, deezerId = a["id"].long, cover = a["cover_medium"].str ?: a["cover"].str)
    }

    /** Secondary types that are not new music of the artist's own. */
    private val SKIPPED = setOf("compilation", "live", "dj-mix", "demo", "interview", "spokenword", "audiobook", "audio drama", "field recording")

    /**
     * MusicBrainz's release-group search: those credited to [artist] by that very name (the search
     * matches words, so "Muse" also finds "Muse & Friends"), albums, EPs and singles only.
     */
    fun parseMusicBrainz(body: String, artist: String): List<Release> {
        val want = Matcher.normalizeArtist(artist)
        return parseJson(body)["release-groups"].arr.mapNotNull { g ->
            val credited = g["artist-credit"].arr.flatMap { listOfNotNull(it["name"].str, it["artist"]["name"].str) }
            if (credited.none { Matcher.normalizeArtist(it) == want }) return@mapNotNull null
            val type = when (g["primary-type"].str?.lowercase()) {
                "album" -> Type.ALBUM
                "ep" -> Type.EP
                "single" -> Type.SINGLE
                else -> return@mapNotNull null
            }
            if (g["secondary-types"].arr.any { it.str?.lowercase() in SKIPPED }) return@mapNotNull null
            val date = g["first-release-date"].str?.takeIf { dayOf(it) != null } ?: return@mapNotNull null
            val title = g["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Release(artist, title, type, date, mbid = MusicBrainz.mbid(g["id"].str))
        }
    }

    // ------------------------------------------------------------------ merging and the window

    /** One key per album whatever the source writes after it: "Title - EP", "Title (Deluxe)"… */
    private fun key(r: Release) = Matcher.normalizeArtist(r.artist) + "|" +
        Matcher.normalizeTitle(r.title.replace(Regex("""\s+-\s+(single|ep)$""", RegexOption.IGNORE_CASE), ""))

    /**
     * Deezer's and MusicBrainz's lists as one: an album both know is shown once, with Deezer's page
     * and cover, MusicBrainz's id, and the earlier of the two dates. Within one source nothing is
     * merged (a single and the album of the same name are two releases).
     */
    fun merge(deezer: List<Release>, musicBrainz: List<Release>): List<Release> {
        val out = deezer.distinctBy { key(it) + it.type }.toMutableList()
        val taken = HashSet<Int>()
        for (m in musicBrainz.distinctBy { key(it) + it.type }) {
            val k = key(m)
            val i = out.indices.firstOrNull { it !in taken && out[it].mbid == null && key(out[it]) == k }
            if (i == null) { out += m; continue }
            taken += i
            val d = out[i]
            val earlier = listOf(d.date, m.date).minByOrNull { dayOf(it) ?: LocalDate.MAX } ?: d.date
            out[i] = d.copy(mbid = m.mbid, date = earlier)
        }
        return out
    }

    /**
     * The releases out in the last [days] days (today included), and with [upcoming] the ones
     * announced for later; newest first, the announced ones on top, soonest first.
     */
    fun window(list: List<Release>, days: Int, upcoming: Boolean, today: LocalDate = today()): List<Release> {
        val from = today.minusDays(days.toLong() - 1)
        val (later, out) = list.filter { r -> r.day?.let { d -> !d.isBefore(from) && (upcoming || !d.isAfter(today)) } == true }
            .partition { it.day!!.isAfter(today) }
        return later.sortedBy { it.day } + out.sortedByDescending { it.day }
    }

    fun isUpcoming(r: Release, today: LocalDate = today()) = r.day?.isAfter(today) == true

    // ------------------------------------------------------------------ asking

    private val listSerializer = ListSerializer(Release.serializer())

    /**
     * What [artist] put out from [KEEP_DAYS] days ago on, both sources merged, from the day's cache
     * when there is one. Null when neither source answered (nothing remembered then).
     */
    suspend fun ofArtist(env: Env, artist: String): List<Release>? {
        val k = Matcher.normalizeArtist(artist).ifEmpty { artist.trim().lowercase() }
        if (k.isEmpty()) return emptyList()
        val kv = env.keyValues(KV)
        kv[k]?.let { saved ->
            val at = saved.substringBefore('|').toLongOrNull() ?: 0
            if (now() - at in 0 until TTL_MS) runCatching { return json.decodeFromString(listSerializer, saved.substringAfter('|')) }
        }
        val from = today().minusDays(KEEP_DAYS)
        val dz = ask { fromDeezer(artist) }
        val mb = ask { fromMusicBrainz(artist, from) }
        if (dz == null && mb == null) return null
        val merged = merge(dz.orEmpty(), mb.orEmpty()).filter { r -> r.day?.isBefore(from) == false }
        // Half an answer is used but not remembered: the next search asks the silent source again.
        if (dz != null && mb != null) {
            if (kv.size >= 5_000) kv.clear()
            kv[k] = "${now()}|" + json.encodeToString(listSerializer, merged)
        }
        return merged
    }

    private suspend fun <T> ask(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun deezerGet(url: String): HttpResponse = deezerPace.paced { Http.send("GET", url, maxRateLimitRetries = 2) }

    /** Null when Deezer did not answer; empty when it has no artist of that name. */
    private suspend fun fromDeezer(artist: String): List<Release>? {
        val s = deezerGet("$deezer/search/artist?" + Http.query(mapOf("q" to artist, "limit" to "10")))
        if (!s.ok || s.body.contains("\"error\"")) return null
        val id = ArtistMix.parseDeezerArtist(parseJson(s.body), artist) ?: return emptyList()
        val r = deezerGet("$deezer/artist/$id/albums?limit=100")
        if (!r.ok || r.body.contains("\"error\"")) return null
        return parseDeezerAlbums(r.body, artist)
    }

    /** Null when MusicBrainz did not answer (busy: 503). */
    private suspend fun fromMusicBrainz(artist: String, from: LocalDate): List<Release>? {
        val q = "artistname:\"${ArtistMixSource.escape(artist)}\" AND firstreleasedate:[$from TO *]"
        val r = MusicBrainz.pace.paced {
            Http.send(
                "GET", "$musicBrainz/release-group?" + Http.query(mapOf("query" to q, "fmt" to "json", "limit" to "100")),
                mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                maxRateLimitRetries = 0, retryServerErrors = false,
            )
        }
        if (!r.ok) return null
        return parseMusicBrainz(r.body, artist)
    }

    /**
     * The releases of [artists] in the window: one artist after another (both sources are paced),
     * [onProgress] told (artists done, artists). Never throws but for a cancellation.
     */
    suspend fun find(
        env: Env, artists: List<String>, days: Int, upcoming: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val names = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val all = ArrayList<Release>()
        var unanswered = 0
        names.forEachIndexed { i, a ->
            onProgress(i, names.size)
            val found = ofArtist(env, a)
            if (found == null) unanswered++ else all += found
        }
        onProgress(names.size, names.size)
        if (unanswered > 0) env.log("releases", "$unanswered of ${names.size} artists not answered (Deezer and MusicBrainz both silent)")
        return Result(window(all, days, upcoming), names, unanswered)
    }

    // ------------------------------------------------------------------ the tracks

    /** The tracks of [r]: Deezer's track list when Deezer has it, else the first release of the group on MusicBrainz. */
    suspend fun tracks(env: Env, r: Release): List<Track> {
        val year = r.day?.year ?: 0
        r.deezerId?.let { id ->
            val resp = deezerGet("$deezer/album/$id/tracks?limit=200")
            if (resp.ok && !resp.body.contains("\"error\"")) return parseDeezerTracks(resp.body, r, year)
        }
        val mbid = r.mbid ?: return emptyList()
        val resp = MusicBrainz.pace.paced {
            Http.send(
                "GET", "$musicBrainz/release?" + Http.query(mapOf("release-group" to mbid, "inc" to "recordings", "fmt" to "json", "limit" to "1")),
                mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                maxRateLimitRetries = 0, retryServerErrors = false,
            )
        }
        if (!resp.ok) return emptyList()
        return parseMusicBrainzTracks(resp.body, r, year)
    }

    fun parseDeezerTracks(body: String, r: Release, year: Int = r.day?.year ?: 0): List<Track> = parseJson(body)["data"].arr.mapNotNull { t ->
        val title = t["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Track(
            id = "dz:" + (t["id"].long ?: 0L), title = title,
            artists = listOf(t["artist"]["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: r.artist),
            album = r.title, durationMs = (t["duration"].long ?: 0L) * 1000, isrc = t["isrc"].str, year = year,
        )
    }

    fun parseMusicBrainzTracks(body: String, r: Release, year: Int = r.day?.year ?: 0): List<Track> =
        parseJson(body)["releases"].arr.firstOrNull()?.get("media").arr.flatMap { it["tracks"].arr }.mapNotNull { t ->
            val title = (t["title"].str ?: t["recording"]["title"].str)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val rec = MusicBrainz.mbid(t["recording"]["id"].str)
            Track(
                id = "mb:" + (rec ?: title), title = title, artists = listOf(r.artist), album = r.title,
                durationMs = t["length"].long ?: t["recording"]["length"].long ?: 0L, year = year, mbids = listOfNotNull(rec),
            )
        }

    /**
     * The tracks of [releases] (the announced ones have none yet and are skipped), each song once:
     * a single that is also on the album is added once.
     */
    suspend fun tracksOf(env: Env, releases: List<Release>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Track> {
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        val today = today()
        val released = releases.filter { !isUpcoming(it, today) }
        released.forEachIndexed { i, r ->
            onProgress(i, released.size)
            val list = ask { tracks(env, r) }.orEmpty()
            for (t in list) if (seen.add(Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()) + "|" + Matcher.normalizeTitle(t.title))) out += t
        }
        onProgress(released.size, released.size)
        return out
    }

    /** Tests: forgets the remembered answers. */
    internal fun reset(env: Env) = env.keyValues(KV).clear()
}

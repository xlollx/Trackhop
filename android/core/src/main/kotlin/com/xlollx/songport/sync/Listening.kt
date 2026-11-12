package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the user actually listened to, for the Wrapped: the songs and artists played most in a
 * period, as a listening service counts them. Last.fm and ListenBrainz count every play
 * (scrobbles), and so does a personal server (Navidrome and the other Subsonic servers, Jellyfin,
 * Emby, Plex, Lyrion) for the songs it served, over all time; Spotify and Deezer only rank (their
 * "top items"), YouTube Music only knows the recent plays. The answers are read here, from the
 * services' own JSON, so the parsing is covered by tests.
 */
object Listening {
    /** Songs and artists asked of a service: enough for a top five and a musical age. */
    const val TOP = 50

    /** Songs whose year (and cover) is looked up when the service did not say: the most played ones. */
    const val LOOKUPS = 30

    /**
     * The span a Wrapped speaks of. [RECENT]: the latest plays, for a service that keeps no counts.
     * [ALL]: no span chosen, the service's own: every play a personal server counted, or the ranking
     * Deezer gives without saying over which period.
     */
    enum class Period(val days: Int) {
        MONTH(30), HALF_YEAR(182), YEAR(365), RECENT(0), ALL(0);

        val key: String get() = name.lowercase()

        companion object {
            fun of(key: String?): Period? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * The services that know what the user listened to, the best first: those counting every play
     * (the scrobblers, then the personal servers), then Spotify's ranking (the developer sign-in,
     * then the web player's), then Deezer's, then TIDAL's monthly top artists (real stream counts,
     * but artists only: no songs), then YouTube Music's recent plays, then Apple Music's (its recent
     * plays, or Replay's ranking of the latest year: added last, so it is never chosen over a source
     * that was there before it). The periods each can tell.
     */
    enum class Source(val serviceId: String, val periods: List<Period>, val counts: Boolean) {
        LASTFM("lastfm", listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR), true),
        LISTENBRAINZ("listenbrainz", listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR), true),
        SUBSONIC("subsonic", listOf(Period.ALL), true),
        JELLYFIN("jellyfin", listOf(Period.ALL), true),
        EMBY("emby", listOf(Period.ALL), true),
        PLEX("plex", listOf(Period.ALL), true),
        LYRION("lyrion", listOf(Period.ALL), true),
        SPOTIFY("spotify", listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR), false),
        SPOTIFY_WEB("spotify_bridge", listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR), false),
        DEEZER("deezer", listOf(Period.ALL), false),
        // MONTH: the latest month TIDAL has data for; YEAR: the months of the last twelve, summed.
        TIDAL("tidal", listOf(Period.MONTH, Period.YEAR), true),
        YTM("ytm", listOf(Period.RECENT), false),
        // RECENT: the recent plays; YEAR: Replay's top songs of the latest year (the recent plays when Replay has none).
        APPLE("apple", listOf(Period.RECENT, Period.YEAR), false);

        /** A personal server: it counts the plays of every song it holds, so a playlist of its own is ranked by them. */
        val server: Boolean get() = counts && periods == listOf(Period.ALL)

        /** It tells the artists played most and no song (TIDAL): the songs come from elsewhere. */
        val artistsOnly: Boolean get() = this == TIDAL

        companion object {
            fun of(serviceId: String?): Source? = entries.firstOrNull { it.serviceId == serviceId }
        }
    }

    /** A song played, how many times (0: the service does not count) and its cover when known. */
    data class Play(val track: Track, val plays: Int = 0, val cover: String? = null)

    /** An artist played, how many times (0: not counted). */
    data class ArtistPlays(val name: String, val plays: Int = 0)

    /**
     * What a service said about a period: the songs and the artists, the most played first, the
     * totals it gives (0 when it does not), and the artists of the period before ([before],
     * normalized; null when the service cannot tell), for the discovery of the period.
     */
    data class Report(
        val tracks: List<Play>,
        val artists: List<ArtistPlays> = emptyList(),
        val totalTracks: Int = 0,
        val totalArtists: Int = 0,
        val totalPlays: Int = 0,
        val before: Set<String>? = null,
    )

    /** Spotify refused the top items: the sign-in predates the permission (user-top-read). Only a new sign-in grants it. */
    class MissingScope : Exception("user-top-read")

    /** The service did not answer (refused, not found, an answer of another shape); already logged: the caller goes on without it. */
    class Unavailable(message: String) : Exception(message)

    /**
     * The source to read: [wanted] when it is there, else the best one there; null = the playlists.
     * Apple Music only when chosen: a Wrapped made from the playlists stays the default it was.
     */
    fun choose(available: Collection<Source>, wanted: Source?): Source? =
        wanted?.takeIf { it in available } ?: available.filter { it != Source.APPLE }.minByOrNull { it.ordinal }

    /** The period to read from [source]: [wanted] when the source can tell it, else its first. */
    fun period(source: Source, wanted: Period?): Period = wanted?.takeIf { it in source.periods } ?: source.periods.first()

    /** Last.fm's period names (user.gettoptracks, user.gettopartists). */
    fun lastFmPeriod(p: Period): String? = when (p) {
        Period.MONTH -> "1month"; Period.HALF_YEAR -> "6month"; Period.YEAR -> "12month"; Period.RECENT, Period.ALL -> null
    }

    /** ListenBrainz's statistics ranges (/1/stats/user/…?range=). */
    fun listenBrainzRange(p: Period): String? = when (p) {
        Period.MONTH -> "month"; Period.HALF_YEAR -> "half_yearly"; Period.YEAR -> "year"; Period.RECENT, Period.ALL -> null
    }

    /** Spotify's time ranges (/v1/me/top/…?time_range=): about four weeks, six months, a year. */
    fun spotifyRange(p: Period): String? = when (p) {
        Period.MONTH -> "short_term"; Period.HALF_YEAR -> "medium_term"; Period.YEAR -> "long_term"; Period.RECENT, Period.ALL -> null
    }

    /** The web player's time ranges (pathfinder userTopContent's timeRange): the same three spans in its own words. */
    fun spotifyWebRange(p: Period): String? = when (p) {
        Period.MONTH -> "SHORT_TERM"; Period.HALF_YEAR -> "MID_TERM"; Period.YEAR -> "LONG_TERM"; Period.RECENT, Period.ALL -> null
    }

    // ------------------------------------------------------------------ Last.fm

    /** Last.fm's JSON comes from its XML: a list of one arrives as the object itself. */
    private fun list(e: JsonElement?): List<JsonElement> = if (e is JsonObject) listOf(e) else e.arr

    private fun count(e: JsonElement?): Int = e.str?.trim()?.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: e.long?.toInt() ?: 0

    /** Last.fm's grey star, given for every track without art since 2019: no cover. */
    private const val LASTFM_BLANK = "2a96cbd8b46e442fc41c2b86b821562f"

    /** user.gettoptracks: the songs, the most played first, and how many different songs the period had. */
    fun lastFmTracks(j: JsonElement?): Pair<List<Play>, Int> {
        val root = j["toptracks"]
        val plays = list(root["track"]).mapNotNull { t ->
            val title = t["name"].str?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = t["artist"]["name"].str ?: t["artist"]["#text"].str
            val cover = list(t["image"]).lastOrNull()?.get("#text").str?.takeIf { it.isNotBlank() && !it.contains(LASTFM_BLANK) }
            Play(
                Track(
                    id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist",
                    title = title, artists = listOfNotNull(artist?.takeIf { it.isNotBlank() }),
                    durationMs = (t["duration"].str?.toLongOrNull() ?: 0) * 1000,
                ),
                count(t["playcount"]), cover,
            )
        }
        return plays to count(root["@attr"]["total"])
    }

    /** user.gettopartists: the artists, the most played first, and how many different ones the period had. */
    fun lastFmArtists(j: JsonElement?): Pair<List<ArtistPlays>, Int> {
        val root = j["topartists"]
        val artists = list(root["artist"]).mapNotNull { a -> a["name"].str?.takeIf { it.isNotBlank() }?.let { ArtistPlays(it, count(a["playcount"])) } }
        return artists to count(root["@attr"]["total"])
    }

    /** user.getweeklyartistchart over the period before: every artist heard then, normalized. */
    fun lastFmBefore(j: JsonElement?): Set<String> =
        list(j["weeklyartistchart"]["artist"]).mapNotNull { a -> a["name"].str?.let(Matcher::normalizeArtist)?.takeIf { it.isNotEmpty() } }.toSet()

    /** user.getrecenttracks (from the period's start, one per page): the plays in the period. */
    fun lastFmTotal(j: JsonElement?): Int = count(j["recenttracks"]["@attr"]["total"])

    // ------------------------------------------------------------------ ListenBrainz

    /** /1/stats/user/{user}/recordings: the songs, the most played first, and the period's different songs. Null body (stats not ready): none. */
    fun listenBrainzTracks(j: JsonElement?): Pair<List<Play>, Int> {
        val p = j["payload"]
        val plays = p["recordings"].arr.mapNotNull { r ->
            val title = r["track_name"].str?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = r["artist_name"].str?.takeIf { it.isNotBlank() }
            val release = r["caa_release_mbid"].str?.takeIf { it.isNotBlank() }
            Play(
                Track(
                    id = r["recording_mbid"].str?.takeIf { it.isNotBlank() } ?: "$title|$artist",
                    title = title, artists = listOfNotNull(artist), album = r["release_name"].str.orEmpty(),
                ),
                count(r["listen_count"]),
                release?.let { "https://coverartarchive.org/release/$it/front-250" },
            )
        }
        return plays to count(p["total_recording_count"])
    }

    /** /1/stats/user/{user}/artists: the artists, the most played first, and the period's different artists. */
    fun listenBrainzArtists(j: JsonElement?): Pair<List<ArtistPlays>, Int> {
        val p = j["payload"]
        val artists = p["artists"].arr.mapNotNull { a -> a["artist_name"].str?.takeIf { it.isNotBlank() }?.let { ArtistPlays(it, count(a["listen_count"])) } }
        return artists to count(p["total_artist_count"])
    }

    // ------------------------------------------------------------------ Spotify

    /** /v1/me/top/tracks: the songs Spotify ranks highest (no counts), with year and cover. */
    fun spotifyTracks(j: JsonElement?): List<Play> = j["items"].arr.mapNotNull { t ->
        val id = t["id"].str ?: return@mapNotNull null
        val title = t["name"].str?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val images = t["album"]["images"].arr
        Play(
            Track(
                id = id, title = title, artists = t["artists"].arr.mapNotNull { it["name"].str },
                album = t["album"]["name"].str.orEmpty(), durationMs = t["duration_ms"].long ?: 0,
                isrc = t["external_ids"]["isrc"].str, uri = t["uri"].str ?: "spotify:track:$id",
                year = Durations.year(t["album"]["release_date"].str),
            ),
            cover = (images.getOrNull(1) ?: images.firstOrNull())?.get("url").str,
        )
    }

    /** /v1/me/top/artists: the artists Spotify ranks highest (no counts). */
    fun spotifyArtists(j: JsonElement?): List<ArtistPlays> = j["items"].arr.mapNotNull { a -> a["name"].str?.takeIf { it.isNotBlank() }?.let { ArtistPlays(it) } }

    // ------------------------------------------------------------------ Deezer

    /** /user/me/charts/tracks: the songs Deezer ranks highest for the user (no counts, no period said), with their covers. */
    fun deezerTracks(j: JsonElement?): List<Play> = j["data"].arr.mapNotNull { t ->
        val id = t["id"].long?.toString() ?: t["id"].str ?: return@mapNotNull null
        val title = t["title"].str?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Play(
            Track(
                id = id, title = title, artists = listOfNotNull(t["artist"]["name"].str?.takeIf { it.isNotBlank() }),
                album = t["album"]["title"].str.orEmpty(), durationMs = (t["duration"].long ?: 0) * 1000, isrc = t["isrc"].str,
            ),
            cover = t["album"]["cover_medium"].str?.takeIf { it.isNotBlank() } ?: t["album"]["cover_big"].str?.takeIf { it.isNotBlank() },
        )
    }

    /** /user/me/charts/artists: the artists Deezer ranks highest for the user (no counts). */
    fun deezerArtists(j: JsonElement?): List<ArtistPlays> = j["data"].arr.mapNotNull { a -> a["name"].str?.takeIf { it.isNotBlank() }?.let { ArtistPlays(it) } }

    // ------------------------------------------------------------------ TIDAL

    /** A month of TIDAL's activity: its year and month (1-12). */
    data class Month(val year: Int, val month: Int) {
        val index: Int get() = year * 12 + month - 1
    }

    /**
     * /my-activity/timeline (or a top-artists answer's `metadata.timeline`): the months TIDAL has
     * data for, the latest first. Null when the answer has no timeline at all (another shape).
     */
    fun tidalTimeline(j: JsonElement?): List<Month>? {
        val list = j["timeline"].takeIf { it is JsonArray }
            ?: j["metadata"]["timeline"].takeIf { it is JsonArray } ?: return null
        return list.arr.mapNotNull { m ->
            val y = m["year"].long?.toInt() ?: return@mapNotNull null
            val mo = m["month"].long?.toInt()?.takeIf { it in 1..12 } ?: return@mapNotNull null
            Month(y, mo)
        }.distinct().sortedByDescending { it.index }
    }

    /**
     * The months to read for [period]: the latest one with data ([Period.MONTH]), or every month
     * with data among the twelve ending with [now] ([Period.YEAR]).
     */
    fun tidalMonths(timeline: List<Month>, period: Period, now: Month): List<Month> = when (period) {
        Period.YEAR -> timeline.filter { it.index in (now.index - 11)..now.index }
        else -> timeline.take(1)
    }

    /**
     * /my-activity/top-artists: the artists with their streams (`nrOfStreams`), the most streamed
     * first. The top-level `artists` (with the full artist) first, else `activity.artists`. Null
     * when neither list is there (another shape).
     */
    fun tidalArtists(j: JsonElement?): List<ArtistPlays>? {
        val top = j["artists"].takeIf { it is JsonArray }
        val list = top?.arr?.map { it["artist"]["name"] to streams(it) }
            ?: j["activity"]["artists"].takeIf { it is JsonArray }?.arr?.map { it["name"] to streams(it) }
            ?: return null
        return list.mapNotNull { (name, streams) -> name.str?.trim()?.takeIf { it.isNotEmpty() }?.let { ArtistPlays(it, count(streams)) } }
            .sortedByDescending { it.plays }
    }

    /** An artist's streams: `nrOfStreams` (the crate's serde renames to camelCase), or the snake-case spelling should TIDAL use it. */
    private fun streams(e: JsonElement?): JsonElement? = e["nrOfStreams"] ?: e["nr_of_streams"]

    /** The artists of several months as one list: an artist's streams added up (by normalized name), the most streamed first. */
    fun sumArtists(months: List<List<ArtistPlays>>): List<ArtistPlays> {
        val sum = LinkedHashMap<String, ArtistPlays>()
        for (a in months.flatten()) {
            val k = Matcher.normalizeArtist(a.name).ifEmpty { a.name.lowercase() }
            sum[k] = sum[k]?.let { it.copy(plays = it.plays + a.plays) } ?: a
        }
        return sum.values.sortedByDescending { it.plays }
    }

    // ------------------------------------------------------------------ personal servers

    /** A song a server lists, with the plays it counted for the user: Subsonic's `playCount` (getPlaylist, getAlbum, getStarred2). */
    fun subsonicPlay(s: JsonElement?, track: Track): Play = Play(track, count(s["playCount"]))

    /** Jellyfin and Emby: the plays are the item's `UserData.PlayCount`. */
    fun jellyfinPlay(i: JsonElement?, track: Track): Play = Play(track, count(i["UserData"]["PlayCount"]))

    /** Plex: the plays are the item's `viewCount` (absent: never played). */
    fun plexPlay(m: JsonElement?, track: Track): Play = Play(track, count(m["viewCount"]))

    /** Lyrion: the plays are the `playcount` its CLI returns for tag O. */
    fun lyrionPlay(s: JsonElement?, track: Track): Play = Play(track, count(s["playcount"]))

    /**
     * What a server counted, as a report: the songs played at least once, the most played first (a
     * song listed twice counted once), the artists ranked by the plays of their songs, and the total
     * of the plays. Over all time: a server keeps a count, not a history.
     */
    fun fromCounts(plays: List<Play>): Report {
        val songs = plays.filter { it.plays > 0 && it.track.title.isNotBlank() && it.track.kind.isEmpty() }
            .distinctBy { it.track.id }.sortedByDescending { it.plays }
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (p in songs) {
            val a = p.track.artists.firstOrNull { it.isNotBlank() } ?: continue
            val k = Matcher.normalizeArtist(a)
            if (k.isEmpty() || k == "various artists") continue
            counts[k] = (counts[k]?.first ?: a.trim()) to ((counts[k]?.second ?: 0) + p.plays)
        }
        val artists = counts.values.sortedByDescending { it.second }.map { ArtistPlays(it.first, it.second) }
        return Report(songs, artists, totalTracks = songs.size, totalArtists = counts.size, totalPlays = songs.sumOf { it.plays })
    }

    // ------------------------------------------------------------------ recent plays

    /**
     * The recent plays (YouTube Music's history: a song once, the latest first) as a report: the
     * songs in their order, the artists ranked by how many of the songs are theirs. No counts.
     */
    fun fromHistory(tracks: List<Track>): Report {
        val songs = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.distinctBy { Wrapped.key(it) }
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in songs) for (a in t.artists.distinct()) {
            val k = Matcher.normalizeArtist(a)
            if (k.isEmpty() || k == "various artists") continue
            counts[k] = (counts[k]?.first ?: a.trim()) to ((counts[k]?.second ?: 0) + 1)
        }
        val artists = counts.values.sortedByDescending { it.second }.map { ArtistPlays(it.first) }
        return Report(songs.map { Play(it) }, artists, totalTracks = songs.size, totalArtists = counts.size)
    }

    // ------------------------------------------------------------------ Apple Music

    /** An Apple Music artwork template ("…/{w}x{h}bb.jpg") at [side] pixels; null without one. */
    fun appleArtwork(template: String?, side: Int = 300): String? =
        template?.takeIf { it.isNotBlank() }?.replace("{w}", "$side")?.replace("{h}", "$side")

    /**
     * Replay ([report]: its top songs and artists, ranked, no counts), the songs' ids in Replay's
     * order ([order]), and the ids of the songs it named without their attributes ([missing]), to be
     * read from the catalogue.
     */
    class AppleReplay(val report: Report, val order: List<String>, val missing: List<String>)

    /**
     * /v1/me/music-summaries: the first summary's views, `top-songs` (each a song-period-summary whose
     * `song` relationship holds the song) and `top-artists` (the same with `artist`).
     */
    fun appleReplay(j: JsonElement?): AppleReplay {
        val views = j["data"][0]["views"]
        val order = ArrayList<String>()
        val missing = ArrayList<String>()
        val plays = ArrayList<Play>()
        for (s in views["top-songs"]["data"].arr) {
            val song = s["relationships"]["song"]["data"][0]
            val id = song["id"].str ?: continue
            order += id
            val a = song["attributes"]
            val title = a["name"].str?.takeIf { it.isNotBlank() }
            if (title == null) { missing += id; continue }
            plays += Play(
                Track(
                    id = id, title = title,
                    artists = listOfNotNull(a["artistName"].str?.takeIf { it.isNotBlank() }),
                    album = a["albumName"].str.orEmpty(), durationMs = a["durationInMillis"].long ?: 0,
                    isrc = a["isrc"].str, year = Durations.year(a["releaseDate"].str),
                ),
                cover = appleArtwork(a["artwork"]["url"].str),
            )
        }
        val artists = views["top-artists"]["data"].arr.mapNotNull { s ->
            s["relationships"]["artist"]["data"][0]["attributes"]["name"].str?.takeIf { it.isNotBlank() }?.let { ArtistPlays(it) }
        }.ifEmpty { fromHistory(plays.map { it.track }).artists }
        return AppleReplay(Report(plays, artists, totalTracks = order.size, totalArtists = artists.size), order, missing)
    }

    // ------------------------------------------------------------------ years and covers

    /** A song's release year (0: not found) and cover, as a catalogue answers. */
    data class Details(val year: Int, val cover: String?)

    /**
     * [r]'s songs with their year and cover filled in where the service did not give them: the
     * first [max] songs are looked up, one after the other (never a burst), through [lookup];
     * [onProgress] gets (looked up, to look up). A lookup that fails leaves the song as it was.
     */
    suspend fun fill(r: Report, max: Int = LOOKUPS, onProgress: (Int, Int) -> Unit = { _, _ -> }, lookup: suspend (Track) -> Details?): Report {
        val todo = r.tracks.take(max).withIndex().filter { (_, p) -> p.track.year == 0 || p.cover == null }
        val out = r.tracks.toMutableList()
        todo.forEachIndexed { n, (i, p) ->
            onProgress(n, todo.size)
            val d = try { lookup(p.track) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (d != null) out[i] = p.copy(
                track = if (p.track.year == 0 && d.year > 0) p.track.copy(year = d.year) else p.track,
                cover = p.cover ?: d.cover?.takeIf { it.isNotBlank() },
            )
        }
        onProgress(todo.size, todo.size)
        return r.copy(tracks = out)
    }

    /** The first artist of the period not heard in the one before; null when the service cannot tell, or nothing was heard before. */
    fun discovery(r: Report): String? {
        val before = r.before?.takeIf { it.isNotEmpty() } ?: return null
        return r.artists.firstOrNull { val k = Matcher.normalizeArtist(it.name); k.isNotEmpty() && k !in before }?.name
    }
}

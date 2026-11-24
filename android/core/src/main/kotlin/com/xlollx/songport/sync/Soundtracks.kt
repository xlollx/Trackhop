package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement

/**
 * Soundtracks (of games, films, series) from MusicBrainz, the open music encyclopedia: a release
 * group of the secondary type "Soundtrack" ("Grand Theft Auto: Vice City – Wave 103", "Persona 5
 * Original Soundtrack") read as the songs of its fullest official release, with each recording's
 * artists, length, MusicBrainz id and ISRCs. The official API, no key, one request a second through
 * [MusicBrainz.get]. The data is CC0.
 */
object Soundtracks {
    const val SERVICE = "musicbrainz"

    data class Found(val id: String, val title: String, val artist: String, val year: Int, val types: List<String>)

    /** MusicBrainz did not answer (busy, offline), or the id names nothing there. */
    class Unavailable(message: String) : Exception(message)

    /** "release-group/<mbid>" or "release/<mbid>": the id a pasted musicbrainz.org link gives. */
    fun idOf(kind: String, mbid: String) = "$kind/${mbid.lowercase()}"

    private suspend fun json(path: String): JsonElement =
        MusicBrainz.get(path)?.let { runCatching { parseJson(it) }.getOrNull() } ?: throw Unavailable("MusicBrainz is not answering: $path")

    /** Soundtrack release groups whose title holds [words], the best matches first. */
    suspend fun search(words: String, limit: Int = 25): List<Found> {
        val q = words.trim().replace("\"", "")
        if (q.isEmpty()) return emptyList()
        val query = Http.enc("releasegroup:\"$q\" AND secondarytype:soundtrack")
        return parseSearch(json("release-group?query=$query&fmt=json&limit=$limit"))
    }

    internal fun parseSearch(j: JsonElement): List<Found> = j["release-groups"].arr.mapNotNull { g ->
        val id = g["id"].str ?: return@mapNotNull null
        Found(
            idOf("release-group", id), g["title"].str ?: return@mapNotNull null, credit(g["artist-credit"]),
            g["first-release-date"].str?.take(4)?.toIntOrNull() ?: 0, g["secondary-types"].arr.mapNotNull { it.str },
        )
    }

    private val recent = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, List<Track>>>>()

    /** A release group's title and its songs, read from its fullest official release; kept ten minutes. */
    suspend fun read(id: String): Pair<String, List<Track>> {
        recent[id]?.let { (at, v) -> if (System.currentTimeMillis() - at < 600_000L) return v }
        return readFresh(id).also { if (recent.size > 50) recent.clear(); recent[id] = System.currentTimeMillis() to it }
    }

    private suspend fun readFresh(id: String): Pair<String, List<Track>> {
        val mbid = MusicBrainz.mbid(id.substringAfter('/')) ?: throw Unavailable("not a MusicBrainz id: $id")
        val release = if (id.startsWith("release-group/")) {
            pick(json("release?release-group=$mbid&inc=media&fmt=json&limit=100")) ?: throw Unavailable("no release in $id")
        } else mbid
        val r = json("release/$release?inc=recordings+artist-credits+isrcs&fmt=json")
        return (r["title"].str ?: id) to tracksOf(r)
    }

    /** The official release with the most tracks, the oldest of those; any release when none is official. */
    internal fun pick(browse: JsonElement): String? {
        val all = browse["releases"].arr
        val official = all.filter { it["status"].str.equals("Official", ignoreCase = true) }.ifEmpty { all }
        return official.sortedWith(
            compareByDescending<JsonElement> { r -> r["media"].arr.sumOf { it["track-count"].int ?: 0 } }
                .thenBy { it["date"].str?.takeIf { d -> d.isNotBlank() } ?: "9999" },
        ).firstOrNull()?.get("id").str
    }

    /** A release's tracks, every medium in order; a recording heard twice on it is listed once. */
    internal fun tracksOf(release: JsonElement): List<Track> {
        val album = release["title"].str.orEmpty()
        val year = release["date"].str?.take(4)?.toIntOrNull() ?: 0
        return release["media"].arr.flatMap { it["tracks"].arr }.mapNotNull { t ->
            val rec = t["recording"]
            val recId = rec["id"].str?.lowercase() ?: return@mapNotNull null
            val credit = t["artist-credit"].takeIf { it.arr.isNotEmpty() } ?: rec["artist-credit"]
            Track(
                id = "mb:$recId", title = t["title"].str ?: rec["title"].str ?: return@mapNotNull null,
                artists = credit.arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }
                    .filterNot { it.equals("Various Artists", ignoreCase = true) },
                album = album, durationMs = t["length"].long ?: rec["length"].long ?: 0L, year = year,
                isrc = rec["isrcs"].arr.firstOrNull()?.str, mbids = listOf(recId),
            )
        }.distinctBy { it.id }
    }

    /** An artist credit as one line ("A feat. B"). */
    private fun credit(c: JsonElement?): String = c.arr.joinToString("") { (it["name"].str ?: "") + (it["joinphrase"].str ?: "") }.trim()
}

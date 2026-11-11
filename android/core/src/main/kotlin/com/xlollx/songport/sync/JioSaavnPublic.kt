package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Public playlists, albums and songs of JioSaavn, read the way www.jiosaavn.com's own pages read
 * them: no account, no key, only the site's api.php (webapi.get, as robots.txt leaves it open). Not
 * an official interface: when the site changes this stops working until Trackhop is updated, and
 * it says so ([Changed]). Read-only, from a pasted link.
 *
 * An id here is the link's path, which says what it is: featured/<name>/<token> or
 * s/playlist/<user>/<name>/<token> (a playlist), album/<name>/<token>, song/<name>/<token>. The
 * site lists a playlist 50 songs a page; the pages are asked a second apart. JioSaavn's answers
 * carry no ISRC, so the songs are matched by title, artists, album and length.
 */
object JioSaavnPublic {
    const val SERVICE = "jiosaavn"
    const val SITE = "https://www.jiosaavn.com"
    private const val PAGE = 50
    private const val MAX_TRACKS = 10_000

    /** The playlist is private, deleted, or never existed. */
    class NotPublic : Exception("not public")
    /** The site no longer answers the way this reads it. */
    class Changed(detail: String) : Exception(detail)

    private val HEADERS = mapOf("User-Agent" to Http.DESKTOP_UA, "Referer" to "$SITE/", "Accept" to "application/json")
    private val pacer = Pacer(1000)

    /** What the link's path names, as webapi.get calls it: "playlist", "album" or "song". */
    fun kindOf(path: String): String = when {
        path.startsWith("album/") -> "album"
        path.startsWith("song/") -> "song"
        else -> "playlist"
    }

    /** The site writes some text with HTML entities ("Vishal &amp; Shekhar", "From &quot;Prem Geet&quot;"). */
    private fun clean(s: String?): String = s.orEmpty()
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()

    /** A song as the site lists it, or null for anything else (an episode) or without an id or a title. */
    fun parseTrack(j: JsonElement?): Track? {
        if (j["type"].str.let { it != null && it != "song" }) return null
        val id = j["id"].str?.takeIf { it.isNotBlank() } ?: return null
        val title = clean(j["title"].str).takeIf { it.isNotEmpty() } ?: return null
        val info = j["more_info"]
        val map = info["artistMap"]
        fun names(list: List<JsonElement>) = list.mapNotNull { clean(it["name"].str).takeIf { n -> n.isNotEmpty() } }
        // The performers; a song with none listed as such names its singers among the credits.
        val artists = names(map["primary_artists"].arr + map["featured_artists"].arr).distinct()
            .ifEmpty { names(map["artists"].arr.filter { it["role"].str == "singer" }).distinct() }
        return Track(
            id = id,
            title = title,
            artists = artists,
            album = clean(info["album"].str),
            durationMs = (info["duration"].long ?: 0) * 1000,
            uri = j["perma_url"].str,
            explicit = j["explicit_content"].str?.let { it == "1" },
            year = j["year"].str?.toIntOrNull() ?: 0,
        )
    }

    /** The songs of an answer: a playlist's or an album's "list", or the "songs" of a song's. */
    fun tracksOf(answer: JsonElement): List<Track> = (answer["list"].arr + answer["songs"].arr).mapNotNull { parseTrack(it) }

    fun playlistOf(answer: JsonElement, id: String): Playlist {
        val song = answer["songs"][0]
        val p = song ?: answer
        return Playlist(
            id = id,
            name = clean(p["title"].str).ifEmpty { id },
            trackCount = if (song != null) 1 else answer["list_count"].int ?: answer["list"].arr.size,
            ownedByMe = false,
            description = clean(answer["header_desc"].str),
            // The listing's 150×150 cover; the site serves the same at 500×500.
            imageUrl = p["image"].str?.takeIf { it.isNotBlank() }?.replace("150x150", "500x500"),
            isPublic = true,
        )
    }

    /** Whether the answer is the site's word for a missing playlist, album or song: no id, no songs. */
    fun missing(answer: JsonElement): Boolean =
        if (answer is JsonObject && "songs" !in answer) answer["id"].str.isNullOrBlank() else answer["songs"].arr.isEmpty()

    // ---- Network ---------------------------------------------------------------------------

    private suspend fun fetch(path: String, page: Int = 1, size: Int = PAGE): JsonElement {
        val kind = kindOf(path)
        val token = path.trimEnd('/').substringAfterLast('/')
        val paging = if (kind == "playlist") "&p=$page&n=$size" else ""
        val url = "$SITE/api.php?__call=webapi.get&token=${Http.enc(token)}&type=$kind$paging&includeMetaTags=0&ctx=web6dot0&api_version=4&_format=json&_marker=0"
        val r = pacer.paced { Relay.send("GET", url, HEADERS, maxRateLimitRetries = 1) }
        if (!r.ok) throw Changed("jiosaavn.com HTTP ${r.code}")
        val j = runCatching { parseJson(r.body) }.getOrElse { throw Changed("not JSON") }
        // A missing song is an empty list; any other answer that is not an object is the site changing.
        if (j !is JsonObject) throw if (kind == "song" && j is JsonArray && j.isEmpty()) NotPublic() else Changed("not an object")
        if (j["status"].str == "failure") throw Changed("failure ${j["err_code"].str.orEmpty()}")
        if (missing(j)) throw NotPublic()
        if (if (kind == "song") "songs" !in j else "list" !in j) throw Changed("no list")
        return j
    }

    suspend fun info(path: String): Playlist = playlistOf(fetch(path, size = 1), path)

    suspend fun tracks(path: String): List<Track> {
        val first = fetch(path)
        if (kindOf(path) != "playlist") return tracksOf(first)
        val total = (first["list_count"].int ?: 0).coerceAtMost(MAX_TRACKS)
        val out = tracksOf(first).toMutableList()
        var page = 1
        var last = first["list"].arr.size
        while (last >= PAGE && out.size < total) {
            page++
            val next = fetch(path, page)["list"].arr
            last = next.size
            out += next.mapNotNull { parseTrack(it) }
        }
        return out.distinctBy { it.id }
    }

    /** Changes when the playlist does: the site's own "last updated" stamp (albums and songs have none). */
    suspend fun version(path: String): String? =
        if (kindOf(path) != "playlist") null else fetch(path, size = 1)["more_info"]["last_updated"].str?.takeIf { it.isNotBlank() }
}

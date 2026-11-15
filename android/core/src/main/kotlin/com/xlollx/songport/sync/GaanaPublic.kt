package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Public playlists of Gaana, read the way gaana.com's own pages read them: no account, no token,
 * only the site's `playlist/detail` (api.gaana.com), which answers a public playlist to anyone
 * that names itself as the site does ([HEADERS]; without `deviceType` it says the songs are not
 * available). Not an official interface: when the site changes this stops working until Trackhop
 * is updated, and it says so ([Changed]). Read-only, from a pasted link.
 *
 * An id here is the playlist's `seokey`, the last part of its link (gaana.com/playlist/<seokey>).
 * The answer carries the songs the site shows (up to about a hundred for its own charts, which
 * count more than they list), each with its ISRC, so they match exactly elsewhere.
 */
object GaanaPublic {
    const val SERVICE = "gaana"
    const val SITE = "https://gaana.com"
    private const val API = "https://api.gaana.com"

    /** The playlist is private, deleted, or never existed. */
    class NotPublic : Exception("not public")
    /** The site no longer answers the way this reads it. */
    class Changed(detail: String) : Exception(detail)

    /** How the site names itself to its API (seen 07/11/2026 and in its scripts): the answer depends on `deviceType`. */
    val HEADERS = mapOf(
        "User-Agent" to Http.DESKTOP_UA, "Referer" to "$SITE/", "Origin" to SITE, "Accept" to "application/json",
        "deviceType" to "GaanaWapApp", "deviceId" to "website", "COUNTRY" to "IN",
        "appVersion" to "V6", "gaanaAppVersion" to "gaanaAndroid-8.60.2",
    )
    private val pacer = Pacer(1000)

    /** A song as the site lists it, or null without an id or a title. */
    fun parseTrack(j: JsonElement?): Track? {
        val id = j["track_id"].str?.takeIf { it.isNotBlank() } ?: return null
        val title = j["track_title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Track(
            id = id,
            title = title,
            artists = j["artist"].arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct(),
            album = j["album_title"].str.orEmpty().trim(),
            durationMs = (j["duration"].seconds ?: 0) * 1000,
            isrc = j["isrc"].str?.trim()?.takeIf { it.isNotEmpty() },
            uri = j["seokey"].str?.takeIf { it.isNotBlank() }?.let { "$SITE/song/$it" },
            explicit = j["parental_warning"].seconds?.let { it == 1L },
            year = j["release_date"].str?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 } ?: 0,
        )
    }

    fun tracksOf(answer: JsonElement): List<Track> = answer["tracks"].arr.mapNotNull { parseTrack(it) }.distinctBy { it.id }

    fun playlistOf(answer: JsonElement, id: String): Playlist {
        val p = answer["playlist"]
        return Playlist(
            id = id,
            name = p["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            // What the answer lists: the site's own count of a chart can be higher than the songs it gives.
            trackCount = tracksOf(answer).size,
            ownedByMe = false,
            description = (answer["detailed_description"].str ?: p["detailed_description"].str).orEmpty()
                .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]*>"), "").trim(),
            imageUrl = (p["atw"].str ?: p["artwork"].str)?.takeIf { it.startsWith("https://") },
            isPublic = true,
        )
    }

    /** The site's word for a playlist it does not have (or does not show): status 2, error code 1002. */
    fun missing(answer: JsonElement): Boolean =
        answer["playlist"] !is JsonObject && (answer["error_code"].seconds == 1002L || answer["status"].seconds == 2L)

    // ---- Network ---------------------------------------------------------------------------

    private suspend fun fetch(seokey: String): JsonElement {
        val url = "$API/playlist/detail?seokey=${Http.enc(seokey)}&request_type=playlist"
        val r = pacer.paced { Relay.send("GET", url, HEADERS, maxRateLimitRetries = 1) }
        if (!r.ok) throw Changed("api.gaana.com HTTP ${r.code}")
        val j = runCatching { parseJson(r.body) }.getOrElse { throw Changed("not JSON") }
        if (j !is JsonObject) throw Changed("not an object")
        if (missing(j)) throw NotPublic()
        if (j["playlist"] !is JsonObject || "tracks" !in j) throw Changed("no playlist (${j["error"].str.orEmpty().take(60)})")
        return j
    }

    suspend fun info(seokey: String): Playlist = playlistOf(fetch(seokey), seokey)

    suspend fun tracks(seokey: String): List<Track> = tracksOf(fetch(seokey))

    /** Changes when the playlist does: the site's own "modified on" stamp. */
    suspend fun version(seokey: String): String? = fetch(seokey).let { it["modified_on"].str ?: it["playlist"]["modified_on"].str }?.takeIf { it.isNotBlank() }
}

/** A number the site may send as a number or as a string ("278"). */
private val JsonElement?.seconds: Long? get() = long ?: str?.trim()?.toLongOrNull()

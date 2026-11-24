package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.obj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import java.time.Instant

/**
 * Fortnite Festival's Jam Tracks, read from the public content page Epic's own sites read
 * (fortnitecontent-website-prod07.ol.epicgames.com, "spark-tracks"): every Jam Track with its title,
 * artist, year, length, ISRC where Epic gives one, and the day it came to the game. Not a documented
 * API: only the builds with unofficial connectors read it. The page is large (about a megabyte):
 * read once an hour at most.
 */
object FortniteFestival {
    const val SERVICE = "fortnite"
    const val URL = "https://fortnitecontent-website-prod07.ol.epicgames.com/content/api/pages/fortnite-game/spark-tracks"

    /** The page changed shape, or did not answer as expected. */
    class Changed(message: String) : Exception(message)

    data class JamTrack(val id: String, val title: String, val artist: String, val year: Int, val seconds: Int, val isrc: String?, val addedAt: Long) {
        fun track() = Track(
            id = "fn:$id", title = GameTitles.clean(title), artists = GameTitles.artists(artist),
            durationMs = seconds * 1000L, isrc = isrc, year = year, addedAt = addedAt,
        )
    }

    @Volatile private var cached: Pair<Long, List<JamTrack>>? = null

    /** Tests: forget the last read. */
    internal fun forget() { cached = null }

    /** Every Jam Track, the newest first. */
    suspend fun all(): List<JamTrack> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < 3_600_000L && list.isNotEmpty()) return list }
        val r = Relay.send("GET", URL, mapOf("Accept" to "application/json", "User-Agent" to "Trackhop"))
        if (!r.ok) throw Changed("HTTP ${r.code}")
        val list = parse(r.body)
        if (list.isEmpty()) throw Changed("no Jam Tracks in the page")
        cached = System.currentTimeMillis() to list
        return list
    }

    /** The page as Jam Tracks, the newest first; entries without a title are left out. */
    fun parse(body: String): List<JamTrack> {
        val root = runCatching { parseJson(body).obj }.getOrNull() ?: throw Changed("not JSON")
        return root.entries.mapNotNull { (key, v) ->
            val t = v["track"] ?: return@mapNotNull null
            val title = t["tt"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val added = v["_activeDate"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            JamTrack(
                id = t["sn"].str ?: key, title = title, artist = t["an"].str.orEmpty().trim(),
                year = t["ry"].int ?: 0, seconds = t["dn"].int ?: 0,
                isrc = t["isrc"].str?.trim()?.uppercase()?.takeIf { it.length >= 10 }, addedAt = added,
            )
        }.sortedByDescending { it.addedAt }
    }

    /** The Jam Tracks that came to the game in the [days] before [now]. */
    fun recent(list: List<JamTrack>, now: Long, days: Int = 30): List<JamTrack> = list.filter { it.addedAt >= now - days * 86_400_000L }
}

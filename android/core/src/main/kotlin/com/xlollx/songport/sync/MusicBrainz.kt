package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env

/**
 * The MusicBrainz recordings behind an ISRC, from the public MusicBrainz API (no key, no account):
 * `https://musicbrainz.org/ws/2/isrc/<ISRC>?fmt=json`.
 *
 * A personal server (Navidrome, Jellyfin, Emby, Plex) whose files were tagged with MusicBrainz
 * Picard or beets knows each file's recording id. A track coming from Spotify, Apple Music, TIDAL
 * or Deezer carries an ISRC; MusicBrainz says which recordings that ISRC is, and the server's file
 * tagged with one of them is the same recording, whatever its title is written like (issue #57).
 *
 * MusicBrainz asks for at most one request a second per client and a User-Agent that names it:
 * every request of this app to MusicBrainz goes through [pace], and names itself as
 * [ArtistMixSource.userAgent] ("Trackhop/<version> ( https://trackhop.org )"). An answer is
 * remembered for months, "no recording" included. It is a help, never a step: when MusicBrainz is
 * unreachable or busy the sync matches by title, as it always did.
 */
object MusicBrainz {
    /** One request a second, for the whole process: the sync engine's lookups and the artist mix share it. */
    @Volatile var pace = Pacer(1100)
        internal set

    private const val PREFS = "musicbrainz_isrc"
    private const val TTL_MS = 180L * 24 * 3_600_000
    /** "No recording" is asked again sooner: MusicBrainz gains ISRCs every day. */
    private const val NEGATIVE_TTL_MS = 30L * 24 * 3_600_000
    private const val MAX = 20_000
    /** After this many failures in a row MusicBrainz is left alone for [REST_MS]: a server without internet must not wait on each track. */
    private const val MAX_FAILURES = 3
    private const val REST_MS = 15L * 60_000

    /** Tests point it at a local server. */
    @Volatile internal var base = "https://musicbrainz.org/ws/2"
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }

    private val memory = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 5_000
    }
    @Volatile private var failures = 0
    @Volatile private var restUntil = 0L

    private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")

    /** [s] as a MusicBrainz id (lowercase), or null when it is not one. */
    fun mbid(s: String?): String? = s?.trim()?.lowercase()?.takeIf { MBID.matches(it) }

    /** The recording ids MusicBrainz lists for [isrc]; empty when it knows none, or could not be asked. Never throws. */
    suspend fun recordings(env: Env, isrc: String): List<String> {
        val k = isrc.trim().uppercase().replace("-", "")
        if (!ISRC.matches(k)) return emptyList()
        synchronized(memory) { memory[k] }?.let { return it }
        val prefs = env.keyValues(PREFS)
        prefs[k]?.let { saved ->
            val ids = saved.substringBefore('|').split(',').filter { it.isNotEmpty() }
            val at = saved.substringAfter('|', "0").toLongOrNull() ?: 0
            if (now() - at < (if (ids.isEmpty()) NEGATIVE_TTL_MS else TTL_MS)) { synchronized(memory) { memory[k] = ids }; return ids }
        }
        if (now() < restUntil) return emptyList()
        val ids = try {
            pace.paced { lookup(k) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // The run ended: not a failed lookup.
        } catch (e: Exception) {
            null
        }
        if (ids == null) {
            // Not remembered: a busy MusicBrainz (503) or a network hiccup is not "no recording".
            if (++failures >= MAX_FAILURES) {
                failures = 0
                restUntil = now() + REST_MS
                env.log("musicbrainz", "not answering: matching by title for the next ${REST_MS / 60_000} minutes")
            }
            return emptyList()
        }
        failures = 0
        synchronized(memory) { memory[k] = ids }
        if (prefs.size >= MAX) prefs.clear()
        prefs[k] = "${ids.joinToString(",")}|${now()}"
        return ids
    }

    /** The ids, empty when MusicBrainz does not know the ISRC (404), null when it did not answer the question. */
    private suspend fun lookup(isrc: String): List<String>? {
        // No retry inside: a second request right after a 503 would break the one-a-second rule.
        val r = Http.send(
            "GET", "$base/isrc/$isrc?fmt=json",
            mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
            maxRateLimitRetries = 0, retryServerErrors = false,
        )
        if (r.code == 404 || r.code == 400) return emptyList()
        if (!r.ok) return null
        return parseJson(r.body)["recordings"].arr.mapNotNull { mbid(it["id"].str) }.distinct()
    }

    /** Tests: forgets what is in memory and any rest after failures. */
    internal fun reset() {
        synchronized(memory) { memory.clear() }
        failures = 0
        restUntil = 0
    }
}

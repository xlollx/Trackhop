package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider

/**
 * Tools › Mark as listened (1.18): listens Last.fm or ListenBrainz never saw (a record, a CD, an app
 * that does not scrobble, a trip offline), told by hand. The songs are laid back to back by their
 * lengths from the time given, each once: this is for real listening, never repeated to swell the
 * figures. Last.fm takes listens of the last [LASTFM_DAYS] days only; older ones go to ListenBrainz.
 */
object Scrobbles {
    /** Last.fm ignores scrobbles older than this (last.fm/api/show/track.scrobble). */
    const val LASTFM_DAYS = 14
    const val LASTFM_MS = LASTFM_DAYS * 24 * 3_600_000L
    /** Scrobbles in one track.scrobble call, at most. */
    const val LASTFM_BATCH = 50
    /** Listens in one submit-listens call (ListenBrainz's MAX_LISTENS_PER_REQUEST). */
    const val LISTENBRAINZ_BATCH = 1000
    /** A song whose length is not known is counted as this long. */
    const val GUESS_MS = 210_000L

    /** One song listened to, from [at] (epoch ms). */
    data class Listen(val track: Track, val at: Long)

    /** Each song once, in order: a song listed twice is one listen per run. */
    fun songs(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>()
        return tracks.filter { it.title.isNotBlank() && seen.add(Matcher.normalizeTitle(it.title) + "|" + Matcher.normalizeArtist(it.artists.firstOrNull().orEmpty())) }
    }

    /** [tracks] one after the other: the last one ending at [time] when [finished], else the first one starting at it. */
    fun lay(tracks: List<Track>, time: Long, finished: Boolean): List<Listen> {
        val lengths = tracks.map { if (it.durationMs > 0) it.durationMs else GUESS_MS }
        var at = if (finished) time - lengths.sum() else time
        return tracks.mapIndexed { i, t -> Listen(t, at).also { at += lengths[i] } }
    }

    /** Last.fm takes it: not older than [LASTFM_DAYS] days, and not in the future. */
    fun lastFmTakes(at: Long, now: Long = System.currentTimeMillis()) = at in (now - LASTFM_MS)..now

    /** What was marked: listens taken by each (null when not asked), and those too old for Last.fm. */
    data class Sent(val lastFm: Int?, val listenBrainz: Int?, val tooOldForLastFm: Int)

    /**
     * Sends [listens] to the services given; a listen in the future is sent to neither, one older
     * than [LASTFM_DAYS] days to ListenBrainz only.
     */
    suspend fun send(env: Env, listens: List<Listen>, lastFm: LastFmProvider?, listenBrainz: ListenBrainzProvider?, now: Long = System.currentTimeMillis()): Sent {
        val past = listens.filter { it.at <= now }
        val forLastFm = past.filter { lastFmTakes(it.at, now) }
        val lf = lastFm?.let { if (forLastFm.isEmpty()) 0 else it.scrobble(env, forLastFm) }
        val lb = listenBrainz?.let { if (past.isEmpty()) 0 else it.submit(env, past) }
        return Sent(lf, lb, if (lastFm != null) past.size - forLastFm.size else 0)
    }
}

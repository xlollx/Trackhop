package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider

/**
 * A transfer made once (a tool's new playlist, a one-off transfer) reads its playlist back after
 * writing: a service can answer "added" and keep nothing (YouTube now and then). A sync finds those
 * on its next run; a transfer has no next run, so it looks now and says which ones are not there.
 */
object ReadBack {
    /** The pause before the second look, for a service whose listing takes a moment to show what was added. */
    internal var settleMs = 4_000L

    /** Among [sent], the tracks [listed] does not hold, by id or, where the service lists another id, by name. */
    fun missing(listed: List<Track>, sent: List<Track>): List<Track> {
        if (sent.isEmpty()) return emptyList()
        val ids = listed.mapTo(HashSet()) { it.id }
        val left = sent.filter { it.id !in ids }
        if (left.isEmpty()) return left
        val index = Matcher.TrackIndex(listed)
        return left.filter { index.best(it) == null }
    }

    /**
     * The tracks of [sent] that [playlistId] on [p] does not list, looked at twice (a pause between)
     * before saying so. Empty when the playlist cannot be read: a read that fails says nothing.
     */
    suspend fun check(env: Env, p: MusicProvider, playlistId: String, sent: List<Track>): List<Track> {
        if (sent.isEmpty() || !p.canRead(env, playlistId)) return emptyList()
        suspend fun read() = kotlinx.coroutines.withContext(MusicProvider.FreshRead) { p.tracks(env, playlistId) }
        var gone = runCatching { missing(read(), sent) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; return emptyList() }
        if (gone.isEmpty()) return gone
        kotlinx.coroutines.delay(settleMs)
        gone = runCatching { missing(read(), gone) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; return emptyList() }
        return gone
    }
}

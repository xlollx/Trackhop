package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * "Vanished songs": tracks that were in one of the user's playlists and are not any more. The app
 * keeps dated versions of each backed-up playlist (LocalFilesProvider.writeBackup, up to five):
 * a track in an older version and missing from the newest one has left the playlist since,
 * taken out by hand or pulled by the service. Pure Kotlin, covered by tests.
 *
 * A copy is only believed while it reads as the same playlist as the copy after it. A step where
 * the newer copy is empty, or keeps under half of the older one's songs while losing at least
 * [BREAK_MIN_LOST] of them, is a break: a listing the service sent in part, another account's
 * library under the same name, a playlist that renews itself. Nothing at or beyond a break is
 * counted as vanished; what a mirror sync really removed is listed from its reports anyway.
 */
object Vanished {

    /** One dated copy of a playlist. */
    data class Version(val at: Long, val tracks: List<Track>)

    /** A track gone from [playlist] (on [service], when known), last seen in the copy of [lastSeen]. */
    data class Gone(val track: Track, val playlist: String, val service: String?, val lastSeen: Long)

    /** A step between two copies losing at least this many songs, and over half of them, is a break. */
    const val BREAK_MIN_LOST = 10

    /** What left [playlist] between its older [versions] and the newest one; most recently lost first. */
    fun between(playlist: String, service: String?, versions: List<Version>): List<Gone> {
        if (versions.size < 2) return emptyList()
        val sorted = versions.sortedByDescending { it.at }.map { Version(it.at, songs(it.tracks)) }
        val now = Keys(sorted.first().tracks)
        val gone = LinkedHashMap<String, Gone>()
        var newer = sorted.first().tracks
        for (v in sorted.drop(1)) {
            // The older copies are only as good as the chain that leads from them to the newest one.
            if (isBreak(older = v.tracks, newer = newer)) break
            for (t in v.tracks) {
                if (now.has(t)) continue
                // The newest older copy that still had it: the first met, as copies come newest first.
                gone.putIfAbsent(Wrapped.key(t), Gone(t, playlist, service, v.at))
            }
            newer = v.tracks
        }
        return gone.values.sortedByDescending { it.lastSeen }
    }

    /**
     * True when [newer] cannot be [older] read whole some time later: it is empty while [older] had
     * songs, or it keeps fewer than half of them while losing at least [BREAK_MIN_LOST].
     */
    fun isBreak(older: List<Track>, newer: List<Track>): Boolean {
        val before = songs(older).distinctBy { Wrapped.key(it) }
        if (before.isEmpty()) return false
        val after = songs(newer)
        if (after.isEmpty()) return true
        val keys = Keys(after)
        val kept = before.count { keys.has(it) }
        val lost = before.size - kept
        return lost >= BREAK_MIN_LOST && kept * 2 < before.size
    }

    /** The songs of a copy: not albums, artists or podcasts (a library section), not untitled rows. */
    private fun songs(tracks: List<Track>) = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }

    /**
     * Every name a song goes by: its ISRC when it has one, and its normalized title with each of
     * its artists. Two copies match on any of them, so a copy that lost (or gained) the ISRCs, or
     * lists the artists in another order, still holds the same songs.
     */
    internal fun keysOf(t: Track): List<String> {
        val title = Matcher.normalizeTitle(t.title)
        val artists = t.artists.map { Matcher.normalizeArtist(it) }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        return listOfNotNull(t.isrcNorm?.let { "isrc:$it" }) + artists.map { "$title|$it" }
    }

    private class Keys(tracks: List<Track>) {
        private val set = tracks.flatMapTo(HashSet()) { keysOf(it) }
        fun has(t: Track): Boolean = keysOf(t).any { it in set }
    }
}

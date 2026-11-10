package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * The entries of a target playlist the last reads listed, kept a few minutes for the review screen:
 * taking a wrong match off the target needs the entry that holds it (a search result has none), and
 * reading a long playlist again takes seconds. Only for services whose entries keep their id
 * ([com.xlollx.songport.providers.MusicProvider.keepsEntryIds]): a kept entry names the same entry or
 * one that is gone, never another. Shared by every engine of the process; keyed by [key].
 */
internal object TargetEntries {
    const val TTL_MS = 5 * 60_000L

    /** Replaced in tests. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private class Listing(val at: Long, val entries: MutableList<Track>, var version: String?)
    private val listings = HashMap<String, Listing>()
    /** Removals counted per playlist: a read that began before one must not bring back what it removed. */
    private val removals = HashMap<String, Long>()

    fun key(dataDir: String, provider: String, playlistId: String) = "$dataDir|$provider|$playlistId"

    /** Where the removals of [key] stand now: what [put] is given when the read began. */
    @Synchronized fun mark(key: String): Long = removals[key] ?: 0L

    /** A whole listing just read: it replaces what was kept, unless something was removed since [since] (a [mark]). */
    @Synchronized fun put(key: String, entries: List<Track>, since: Long? = null): Boolean {
        if (since != null && mark(key) != since) return false
        // The other playlists' old listings go: kept past their time they would only use memory.
        val now = clock()
        listings.values.removeAll { now - it.at > TTL_MS }
        listings[key] = Listing(now, entries.toMutableList(), null)
        return true
    }

    /** Entries read on the way (part of a listing): added to what is kept, or the start of a new listing. */
    @Synchronized fun merge(key: String, entries: List<Track>) {
        if (entries.isEmpty()) return
        val l = fresh(key) ?: Listing(clock(), ArrayList(), null).also { listings[key] = it }
        for (e in entries) if (l.entries.none { same(it, e) }) l.entries += e
    }

    /** The playlist's version once the sync's own writes were done, where the service has one. */
    @Synchronized fun setVersion(key: String, version: String?) { fresh(key)?.version = version }

    /** The kept entries and the version they go with; null when nothing is kept or it is too old. */
    @Synchronized fun get(key: String): Pair<List<Track>, String?>? = fresh(key)?.let { it.entries.toList() to it.version }

    /** [removed] left the playlist: one entry each where removal is by entry ([singleEntry]), every copy otherwise. */
    @Synchronized fun drop(key: String, removed: List<Track>, singleEntry: Boolean) {
        removals[key] = mark(key) + 1
        val l = listings[key] ?: return
        for (r in removed) {
            if (singleEntry && r.itemId != null) l.entries.firstOrNull { same(it, r) }?.let { l.entries.remove(it) }
            else l.entries.removeAll { it.id == r.id }
        }
    }

    @Synchronized fun forget(key: String) {
        removals[key] = mark(key) + 1
        listings.remove(key)
    }

    private fun fresh(key: String): Listing? {
        val l = listings[key] ?: return null
        if (clock() - l.at <= TTL_MS) return l
        listings.remove(key)
        return null
    }

    private fun same(a: Track, b: Track) = a.id == b.id && a.itemId == b.itemId
}

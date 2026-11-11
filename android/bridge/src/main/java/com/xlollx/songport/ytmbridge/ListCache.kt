package com.xlollx.songport.ytmbridge

/**
 * The listings a connector fetched, each fetched once and served in pages for [ttlMs] (Binder
 * transactions are capped at ~1 MB); a write to one drops it. A few are kept, so that two syncs
 * reading two playlists page by page, in turns, do not push each other's listing out and fetch it
 * whole again for every page: up to [maxLists] listings and [maxEntries] entries together, the
 * least recently used going first. The listing just fetched always stays, however long it is.
 *
 * [get] loads under this cache's lock, as before: two reads of the same connector take turns.
 */
internal class ListCache<T>(
    private val maxLists: Int = MAX_LISTS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Kept<T>(val at: Long, val list: List<T>)

    /** In order of use, the least recent first. */
    private val kept = LinkedHashMap<String, Kept<T>>(8, 0.75f, true)
    private var entries = 0

    @Synchronized fun get(id: String, load: () -> List<T>): List<T> {
        fresh(id)?.let { return it }
        val list = load()
        drop(id)
        kept[id] = Kept(clock(), list)
        entries += list.size
        trim()
        return list
    }

    /** The listing kept for [id] when there is one still fresh; never reads. */
    @Synchronized fun peek(id: String): List<T>? = fresh(id)

    @Synchronized fun invalidate(id: String) { drop(id) }

    @Synchronized fun clear() { kept.clear(); entries = 0 }

    /** The ids kept, the least recently used first: for the tests. */
    @Synchronized fun ids(): List<String> = kept.keys.toList()

    private fun fresh(id: String): List<T>? {
        val k = kept[id] ?: return null
        if (clock() - k.at < ttlMs) return k.list
        drop(id)
        return null
    }

    private fun drop(id: String) { kept.remove(id)?.let { entries -= it.list.size } }

    /** Stale listings first, then the least recently used, until within bounds; the newest is never dropped. */
    private fun trim() {
        val now = clock()
        kept.entries.filter { now - it.value.at >= ttlMs }.forEach { drop(it.key) }
        val it = kept.entries.iterator()
        while (kept.size > 1 && (kept.size > maxLists || entries > maxEntries)) {
            val e = it.next()
            entries -= e.value.list.size
            it.remove()
        }
    }

    companion object {
        /** Listings kept per connector (every account of it together). */
        const val MAX_LISTS = 4
        /** Entries kept per connector, all listings together: a few large playlists, not dozens. */
        const val MAX_ENTRIES = 6_000
        /** How long a listing serves its pages and the reads that follow (unchanged: two minutes). */
        const val TTL_MS = 120_000L
    }
}

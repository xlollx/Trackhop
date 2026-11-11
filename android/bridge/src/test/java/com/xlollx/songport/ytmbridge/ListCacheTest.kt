package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The connectors' listings kept for paging ([ListCache]): two or three syncs reading their
 * playlists page by page, in turns, each fetch their own once while it is fresh; past the bounds
 * the least recently used goes; stale or written listings are fetched again.
 */
class ListCacheTest {
    private var now = 1_000_000L
    private val fetched = ArrayList<String>()

    private fun cache(lists: Int = ListCache.MAX_LISTS, entries: Int = ListCache.MAX_ENTRIES) =
        ListCache<Int>(lists, entries, ListCache.TTL_MS) { now }

    /** A "page" read of [id], as `spotify.tracks` does: served from the cache or fetched whole. */
    private fun ListCache<Int>.read(id: String, size: Int = 500): List<Int> = get(id) { fetched += id; List(size) { it } }

    @Test fun alternatingReadsOfTwoOrThreePlaylistsFetchEachOnce() {
        val c = cache()
        // Three syncs, each paging through its own playlist, interleaved; time passes between pages.
        repeat(6) { for (id in listOf("p1", "p2", "p3")) { c.read(id); now += 5_000 } }
        assertEquals(listOf("p1", "p2", "p3"), fetched)
        // The same listing object every time: the pages agree with each other.
        assertSame(c.read("p1"), c.read("p1"))
    }

    @Test fun aStaleListingIsFetchedAgain() {
        val c = cache()
        c.read("p1"); c.read("p2")
        now += ListCache.TTL_MS - 1
        c.read("p1")
        assertEquals("still fresh", listOf("p1", "p2"), fetched)
        now += 1
        c.read("p1")
        assertNull("peek never reads, and a stale one is gone", c.peek("p2"))
        assertEquals(listOf("p1", "p2", "p1"), fetched)
    }

    @Test fun pastTheListCapTheLeastRecentlyUsedGoes() {
        val c = cache(lists = 4)
        for (id in listOf("p1", "p2", "p3", "p4")) c.read(id)
        c.read("p1") // used again: now the most recent
        c.read("p5")
        assertEquals(listOf("p3", "p4", "p1", "p5"), c.ids())
        c.read("p2")
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p2"), fetched)
    }

    @Test fun pastTheEntryCapLongListingsGoFirstButTheNewestStays() {
        val c = cache(lists = 4, entries = 6_000)
        c.read("liked", 2_500); c.read("metal", 1_200); c.read("rap", 600)
        assertEquals(listOf("liked", "metal", "rap"), c.ids())
        c.read("big", 2_500) // 6,800 entries: the least recently used goes
        assertEquals(listOf("metal", "rap", "big"), c.ids())
        // A listing larger than the whole cap is still kept on its own, or paging it would fetch it per page.
        c.read("huge", 9_000); c.read("huge"); c.read("huge")
        assertEquals(listOf("huge"), c.ids())
        assertEquals(1, fetched.count { it == "huge" })
    }

    @Test fun aWriteDropsOnlyThatListing() {
        val c = cache()
        c.read("p1"); c.read("p2")
        c.invalidate("p1")
        assertNull(c.peek("p1"))
        c.read("p1"); c.read("p2")
        assertEquals(listOf("p1", "p2", "p1"), fetched)
        c.clear()
        assertEquals(emptyList<String>(), c.ids())
    }

    @Test fun aFailedFetchKeepsWhatWasThere() {
        val c = cache()
        c.read("p1")
        runCatching { c.get("p2") { throw IllegalStateException("network") } }
        assertEquals(listOf("p1"), c.ids())
        c.read("p1")
        assertEquals(listOf("p1"), fetched)
    }
}

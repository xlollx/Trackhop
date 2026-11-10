package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Taking a wrong match off the target from the review: the entry that holds it comes from the
 * entries the last sync read while they are fresh and still valid, otherwise from a read that stops
 * at the wanted track. Never another entry than the one meant, duplicates included.
 */
class ReviewRemovalTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: EntryService
    private lateinit var engine: SyncEngine

    private val a = Track(id = "a", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val b = Track(id = "b", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    /** As the search answers it: no entry id. */
    private val da = Track(id = "da", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val db = Track(id = "db", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    private val dz = Track(id = "dz", title = "Ultra Violet", artists = listOf("Kite Engine"), durationMs = 210_000)

    private fun entry(t: Track, item: String) = t.copy(itemId = item)
    private fun other(n: Int) = Track(id = "x$n", title = "Other $n", artists = listOf("Someone"), itemId = "o$n")

    /** A service whose entries have ids, read two at a time, removed one entry each, refusing an entry it no longer has. */
    class EntryService : MemoryService("mdst") {
        var keeps = true
        var pagesRead = 0
        var wholeReads = 0
        val removedItems = ArrayList<String?>()
        private var next = 100
        override val keepsEntryIds: Boolean get() = keeps
        override val removesSingleEntry: Boolean get() = true
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> { wholeReads++; return super.tracks(ctx, playlistId) }
        override suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit): List<Track> =
            MusicProvider.entriesInPages(tracks, seen) { key ->
                pagesRead++
                val all = lists.getValue(playlistId).toList()
                val from = key?.toInt() ?: 0
                all.drop(from).take(2) to (from + 2).takeIf { it < all.size }?.toString()
            }
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) =
            super.addTracks(ctx, playlistId, tracks.map { it.copy(itemId = "e${next++}") })
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            val list = lists.getValue(playlistId)
            for (t in tracks) {
                val i = list.indexOfFirst { it.id == t.id && it.itemId == t.itemId }
                if (i < 0) throw ProviderException("mdst: no entry ${t.itemId} (HTTP 404)")
                list.removeAt(i)
                removedItems += t.itemId
            }
        }
    }

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = EntryService()
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(a, b)
        dst.catalogue += listOf(da, db, dz)
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        TargetEntries.clock = System::currentTimeMillis
    }

    private val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))

    /** A run, then its report made to list [reviews]; the read counters start from zero after it. */
    private fun synced(vararg reviews: MatchReview): String = runBlocking {
        env.store.upsertJob(job)
        val report = engine.run(job)
        env.store.updateReport(report.id) { it.copy(reviewTracks = reviews.toList()) }
        dst.pagesRead = 0
        dst.wholeReads = 0
        report.id
    }

    private fun items() = dst.lists.getValue("t").map { it.itemId }

    @Test fun anEntryTheLastSyncReadIsRemovedWithoutReadingAgain() = runBlocking {
        dst.lists["t"] = mutableListOf(other(1), entry(da, "e1"), entry(db, "e2"), other(2))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(listOf("o1", "e2", "o2"), items())
        assertEquals(0, dst.pagesRead)
        assertEquals(0, dst.wholeReads)
    }

    @Test fun aTrackTheSyncAddedIsLookedForAndTheReadStopsThere() = runBlocking {
        // Read before the run added db: not among the kept entries, so the service is asked.
        dst.lists["t"] = mutableListOf(other(1), entry(da, "e1"))
        val rb = MatchReview(b, db, 0.7)
        val id = synced(rb)
        assertEquals(listOf("o1", "e1", "e100"), items())
        dst.lists.getValue("t") += listOf(other(2), other(3), other(4), other(5))
        assertEquals(1, engine.discardMatches(job, id, listOf(rb)))
        assertEquals(listOf("o1", "e1", "o2", "o3", "o4", "o5"), items())
        assertEquals("the page holding it, not the four after", 2, dst.pagesRead)
        assertEquals(0, dst.wholeReads)
    }

    @Test fun whatTheReadSawIsKeptForTheNextRemoval() = runBlocking {
        dst.lists["t"] = mutableListOf(other(1))
        val ra = MatchReview(a, da, 0.7)
        val rb = MatchReview(b, db, 0.7)
        val id = synced(ra, rb)
        assertEquals(listOf("o1", "e100", "e101"), items())
        engine.discardMatches(job, id, listOf(rb))
        val pages = dst.pagesRead
        engine.discardMatches(job, id, listOf(ra))
        assertEquals("da was on a page already read", pages, dst.pagesRead)
        assertEquals(listOf("o1"), items())
    }

    @Test fun keptEntriesTooOldAreReadAgain() = runBlocking {
        dst.lists["t"] = mutableListOf(entry(da, "e1"), other(1))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        val later = System.currentTimeMillis() + TargetEntries.TTL_MS + 1_000
        TargetEntries.clock = { later }
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(1, dst.pagesRead)
        assertEquals(listOf("o1", "e100"), items())
    }

    @Test fun aPlaylistChangedSinceTheSyncIsReadAgain() = runBlocking {
        dst.versions["t"] = "v1"
        dst.lists["t"] = mutableListOf(entry(da, "e1"), other(1))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        // Someone wrote to the playlist after the run.
        dst.versions["t"] = "v2"
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(1, dst.pagesRead)
    }

    @Test fun anUnchangedVersionKeepsTheEntries() = runBlocking {
        dst.versions["t"] = "v1"
        dst.lists["t"] = mutableListOf(other(1), other(2), entry(da, "e1"))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(0, dst.pagesRead)
        assertEquals(listOf("o1", "o2", "e100"), items())
    }

    @Test fun aKeptEntryGoneMeanwhileFallsBackToReading() = runBlocking {
        dst.lists["t"] = mutableListOf(entry(da, "e1"), other(1))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        // Removed and added again on the service's own app: a new entry, the kept one is gone.
        val list = dst.lists.getValue("t")
        list.removeAll { it.itemId == "e1" }
        list += entry(da, "e9")
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertTrue(dst.pagesRead > 0)
        assertEquals(listOf("e9"), dst.removedItems)
        assertTrue(list.none { it.id == "da" })
    }

    // The second copy comes after the run: a run repairs the copies a source does not have.
    @Test fun onlyTheFirstOfTwoCopiesLeaves() = runBlocking {
        dst.lists["t"] = mutableListOf(other(1), entry(da, "e1"), other(2))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        dst.lists.getValue("t").add(3, entry(da, "e2"))
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(listOf("o1", "o2", "e2", "e100"), items())
    }

    @Test fun onlyTheFirstOfTwoCopiesLeavesWhenRead() = runBlocking {
        dst.keeps = false
        dst.lists["t"] = mutableListOf(other(1), entry(da, "e1"), other(2))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        dst.lists.getValue("t").add(3, entry(da, "e2"))
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(listOf("o1", "o2", "e2", "e100"), items())
        assertEquals("stops at the first copy", 1, dst.pagesRead)
    }

    @Test fun replacingTakesOneCopyAndAddsTheChoice() = runBlocking {
        dst.lists["t"] = mutableListOf(entry(da, "e1"))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        dst.lists.getValue("t").add(1, entry(da, "e2"))
        engine.replaceMatch(job, id, ra, dz)
        assertEquals(listOf("e1"), dst.removedItems)
        assertEquals(listOf("e2", "e100", "e101"), items())
        assertEquals(listOf("da", "db", "dz"), dst.lists.getValue("t").map { it.id })
        assertEquals(0, dst.pagesRead)
    }

    @Test fun aServiceWhoseEntriesArePositionsAlwaysReads() = runBlocking {
        dst.keeps = false
        dst.lists["t"] = mutableListOf(entry(da, "e1"), other(1))
        val ra = MatchReview(a, da, 0.7)
        val id = synced(ra)
        assertEquals(1, engine.discardMatches(job, id, listOf(ra)))
        assertEquals(1, dst.pagesRead)
    }

    @Test fun anAbsentTrackRemovesNothing() = runBlocking {
        dst.lists["t"] = mutableListOf(other(1), other(2), other(3))
        val rz = MatchReview(a, dz, 0.7)
        val id = synced()
        env.store.updateReport(id) { it.copy(reviewTracks = listOf(rz)) }
        assertEquals(0, engine.discardMatches(job, id, listOf(rz)))
        assertTrue(dst.removedItems.isEmpty())
    }

    @Test fun byOffsetTheLastPagesComeFirst() = runBlocking {
        val all = (0 until 10).map { Track(id = "t$it", title = "T$it") }
        val read = ArrayList<Int>()
        val seen = ArrayList<Track>()
        val found = MusicProvider.entriesByOffset(listOf(all[8]), 2, { seen += it }) { offset ->
            read += offset
            all.drop(offset).take(2) to all.size
        }
        assertEquals(listOf("t8"), found.map { it.id })
        assertEquals(listOf(0, 8), read)
        assertEquals(4, seen.size)
        // A track at the start: one page.
        read.clear()
        MusicProvider.entriesByOffset(listOf(all[1]), 2, {}) { offset -> read += offset; all.drop(offset).take(2) to all.size }
        assertEquals(listOf(0), read)
        // Not there at all: every page once.
        read.clear()
        assertTrue(MusicProvider.entriesByOffset(listOf(Track(id = "none", title = "")), 2, {}) { offset -> read += offset; all.drop(offset).take(2) to all.size }.isEmpty())
        assertEquals(listOf(0, 8, 6, 4, 2), read)
    }
}

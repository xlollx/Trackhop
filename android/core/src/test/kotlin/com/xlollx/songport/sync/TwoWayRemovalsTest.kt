package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A two-way pair that follows removals (issue #55): what both sides held after the last complete
 * run tells a removal from an addition. Every guard has its test here, because a mistake removes
 * the user's tracks: the first run only notes the state, a listing read in part or a search cut
 * short removes nothing, a quarter of the state waits for a confirmation, a track the service hides
 * is not a removed one, and a track removed on one side and put back on the other does not bounce.
 */
class TwoWayRemovalsTest {
    /** A service that can hide entries from its listing while still counting them (tracks it no longer plays). */
    private class Side(id: String) : MemoryService(id) {
        val hidden = HashSet<String>()
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = super.tracks(ctx, playlistId).filter { it.id !in hidden }
    }

    private lateinit var env: ContractEnv
    private lateinit var a: Side
    private lateinit var b: Side
    private lateinit var engine: SyncEngine

    /** Titles far enough apart that the matcher never takes one for another. */
    private fun title(n: String) = mapOf("x" to "Xylophone dreams", "y" to "Yesterday's rain", "z" to "Zebra crossing")[n] ?: "Song $n"
    private fun ta(n: String, addedAt: Long = 0) = Track(id = "a-$n", title = title(n), artists = listOf("Band"), album = "Album", durationMs = 200_000, addedAt = addedAt)
    private fun tb(n: String, addedAt: Long = 0) = Track(id = "b-$n", title = title(n), artists = listOf("Band"), album = "Album", durationMs = 200_000, addedAt = addedAt)
    private val listA get() = a.lists["pa"]!!
    private val listB get() = b.lists["pb"]!!
    private fun names(l: List<Track>) = l.map { it.title.removePrefix("Song ") }.sorted()

    @Before fun setUp() {
        env = ContractEnv()
        a = Side("ma")
        b = Side("mb")
        Providers.testDoubles = listOf(a, b)
        engine = SyncEngine(env)
        val all = (1..40).map { it.toString() } + listOf("x", "y", "z")
        a.catalogue += all.map { ta(it) }
        b.catalogue += all.map { tb(it) }
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    /** The pair: "ab" copies A to B, "ba" (the twin) B to A. */
    private fun pair(removals: Boolean = true) {
        env.store.upsertJob(SyncJob(id = "ab", name = "pair", source = PlaylistRef("ma", "pa", "pa"), target = PlaylistRef("mb", "pb", "pb"), linkedJobId = "ba", twoWayRemovals = removals))
        env.store.upsertJob(SyncJob(id = "ba", name = "pair ↔", source = PlaylistRef("mb", "pb", "pb"), target = PlaylistRef("ma", "pa", "pa"), linkedJobId = "ab", twoWayRemovals = removals, twin = true))
    }

    private suspend fun run(id: String, unattended: Boolean = false): SyncReport = engine.run(env.store.job(id)!!, unattended)
    private suspend fun both(): Pair<SyncReport, SyncReport> = run("ab") to run("ba")
    private fun state() = env.store.pairState(env.store.job("ab")!!)

    /** Both sides hold [n] songs and the pair has run once: the state is there. */
    private suspend fun synced(n: Int = 8) {
        a.lists["pa"] = (1..n).map { ta("$it") }.toMutableList()
        b.lists["pb"] = (1..n).map { tb("$it") }.toMutableList()
        pair()
        both()
        assertEquals(n, state()!!.links.size)
    }

    @Test fun theFirstRunOnlyNotesTheStateAndRemovesNothing() = runBlocking {
        // Before the option: song 3 was removed from A, B still has it. Nothing says it was a removal.
        a.lists["pa"] = mutableListOf(ta("1"), ta("2"))
        b.lists["pb"] = mutableListOf(tb("1"), tb("2"), tb("3"))
        pair()
        assertNull(state())
        val (ab, ba) = both()
        assertEquals(0, ab.removed)
        assertEquals(0, ba.removed)
        assertTrue(ab.notes.toString(), "note_two_way_started" in ab.notes)
        assertEquals("copied back, as a pair always did", listOf("1", "2", "3"), names(listA))
        assertEquals(listOf("1", "2", "3"), names(listB))
        assertEquals(3, state()!!.links.size)
        assertFalse("said once", "note_two_way_started" in both().first.notes)
    }

    @Test fun aRemovalOnEitherSideReachesTheOther() = runBlocking {
        synced()
        listA.removeAll { it.id == "a-2" }
        listB.removeAll { it.id == "b-5" }
        val (ab, ba) = both()
        assertEquals(1, ab.removed)
        assertEquals("song 5 is not put back on B", 0, ab.added)
        assertEquals(1, ba.removed)
        assertEquals(0, ba.added)
        assertEquals(listOf("1", "3", "4", "6", "7", "8"), names(listA))
        assertEquals(names(listA), names(listB))
        assertEquals(6, state()!!.links.size)
        // The removed tracks are in the report, where they can be put back.
        assertEquals(listOf("b-2"), ab.removedTracks.map { it.id })
        // Nothing left to do.
        val (again, back) = both()
        assertEquals(0, again.added + again.removed + back.added + back.removed)
    }

    @Test fun additionsOnEitherSideAreStillCopied() = runBlocking {
        synced()
        listA += ta("x")
        listB += tb("y")
        val (ab, ba) = both()
        assertEquals(1, ab.added)
        assertEquals(1, ba.added)
        assertEquals(0, ab.removed + ba.removed)
        assertEquals(10, listA.size)
        assertEquals(names(listA), names(listB))
        assertEquals(10, state()!!.links.size)
        // And an addition can later be removed like any other track.
        listB.removeAll { it.id == "b-x" }
        both()
        assertFalse(listA.any { it.id == "a-x" })
    }

    @Test fun aSearchCutShortRemovesNothing() = runBlocking {
        synced()
        listA.removeAll { it.id == "a-2" }
        // Four new songs on A: the first is found, then B refuses three searches in a row.
        listA += listOf(ta("x"), ta("y"), ta("z"), ta("9"))
        b.failingSearches += listOf(title("y"), title("z"), title("9"))
        val ab = run("ab")
        assertTrue(ab.searchInterrupted)
        assertEquals(0, ab.removed)
        assertTrue(ab.notes.toString(), ab.notes.any { it == "note_removals_deferred(1)" })
        assertTrue(listB.any { it.id == "b-2" })
        assertTrue("the state waits for a complete run", "a-2" in state()!!.links)
        b.failingSearches.clear()
        assertEquals(1, run("ab").removed)
    }

    @Test fun aSourceListedInPartRemovesNothing() = runBlocking {
        synced()
        // A sends 7 of its 8 tracks: song 8 looks removed.
        a.shortReads += 7
        val ab = run("ab")
        assertEquals(0, ab.removed)
        assertEquals(0, ab.heldBack)
        assertTrue(ab.notes.toString(), ab.notes.any { it == "note_two_way_listed_short(ma,7,8)" })
        assertEquals(8, listB.size)
        assertEquals("the state is left as it was", 8, state()!!.links.size)
        assertEquals(0, run("ab").removed)
    }

    @Test fun aTargetListedInPartAddsNothingTwiceAndRemovesNothing() = runBlocking {
        synced()
        // B sends 7 of its 8 tracks, to both halves.
        b.shortReads += 7
        val ab = run("ab")
        assertEquals("song 8 is on B, only not listed: not added again", 0, ab.added)
        b.shortReads += 7
        val ba = run("ba")
        assertEquals(0, ba.removed)
        assertTrue(ba.notes.toString(), ba.notes.any { it == "note_two_way_listed_short(mb,7,8)" })
        assertEquals(8, listA.size)
        assertEquals(8, listB.size)
    }

    @Test fun aQuarterOfTheStateWaitsForAConfirmation() = runBlocking {
        synced(8)
        listA.removeAll { it.id == "a-1" || it.id == "a-2" }
        val held = run("ab")
        assertEquals(0, held.removed)
        assertEquals(2, held.heldBack)
        assertTrue(held.notes.toString(), held.notes.any { it == "note_two_way_removals_held(2,8)" })
        assertEquals(8, listB.size)
        // The twin does not copy them back to A meanwhile.
        assertEquals(0, run("ba").added)
        assertEquals(6, listA.size)
        // A scheduled run never lifts it, even after a confirmation.
        val plan = engine.plan(env.store.job("ab")!!, createTarget = false, interactive = true)
        assertTrue(plan.removalsNeedConfirmation)
        assertEquals(listOf("b-1", "b-2"), plan.toRemove.map { it.id })
        env.store.confirmRemovals("ab", plan.toRemove.map { it.id })
        assertEquals(0, run("ab", unattended = true).removed)
        // Confirmed in the preview, the next run started by hand removes them.
        val done = run("ab")
        assertEquals(2, done.removed)
        assertEquals(0, done.heldBack)
        assertEquals(names(listA), names(listB))
        assertEquals(6, state()!!.links.size)
    }

    @Test fun lessThanAQuarterGoesThrough() = runBlocking {
        synced(12)
        listA.removeAll { it.id == "a-1" || it.id == "a-2" }
        assertEquals(2, run("ab").removed)
    }

    @Test fun aTrackTheServiceNoLongerPlaysIsNotARemovedOne() = runBlocking {
        synced()
        // Greyed out on B but still listed: present, as for any sync.
        listB[0] = listB[0].copy(available = false)
        val (ab, ba) = both()
        assertEquals(0, ab.added + ab.removed + ba.added + ba.removed)
        // Left out of B's listing, which still counts it: A keeps its copy.
        b.hidden += "b-3"
        val (ab2, ba2) = both()
        assertEquals(0, ab2.added)
        assertEquals(0, ba2.removed)
        assertTrue(ba2.notes.toString(), ba2.notes.any { it == "note_two_way_listed_short(mb,7,8)" })
        assertTrue(listA.any { it.id == "a-3" })
        // Even a real removal waits while B hides something.
        listB.removeAll { it.id == "b-4" }
        assertEquals(0, both().second.removed)
        b.hidden.clear()
        assertEquals(1, both().second.removed)
        assertEquals(listOf("1", "2", "3", "5", "6", "7", "8"), names(listA))
    }

    @Test fun removedOnOneSideAndPutBackOnTheOtherDoesNotBounce() = runBlocking {
        synced()
        val later = System.currentTimeMillis() + 60_000
        // Song 2 leaves A; on B it was taken out and put back (the service dates it after the state).
        listA.removeAll { it.id == "a-2" }
        listB.removeAll { it.id == "b-2" }
        listB += tb("2", addedAt = later)
        val (ab, ba) = both()
        assertEquals("B's new copy stays", 0, ab.removed)
        assertEquals("and comes back to A", 1, ba.added)
        assertEquals(1, listA.count { it.title == "Song 2" })
        assertEquals(1, listB.count { it.title == "Song 2" })
        // Settled: further runs change nothing.
        repeat(2) {
            val (x, y) = both()
            assertEquals(0, x.added + x.removed + y.added + y.removed)
        }
        assertEquals(8, listA.size)
        assertEquals(8, listB.size)
    }

    @Test fun putBackBetweenTheTwoHalvesItIsCopiedNotRemoved() = runBlocking {
        synced()
        listA.removeAll { it.id == "a-2" }
        assertEquals(1, run("ab").removed)
        // Before the twin runs, the user changes their mind and adds it to A again.
        listA += ta("2")
        assertEquals(0, run("ba").removed)
        val (ab, ba) = both()
        assertEquals(1, ab.added)
        assertEquals(0, ab.removed + ba.removed + ba.added)
        assertEquals(names(listA), names(listB))
        assertEquals(8, listA.size)
    }

    @Test fun anIgnoredSourceTrackIsNotARemoval() = runBlocking {
        synced()
        env.store.upsertJob(env.store.job("ab")!!.copy(ignoredSourceIds = listOf("a-2")))
        val (ab, ba) = both()
        assertEquals(0, ab.removed + ba.removed)
        assertEquals(8, listB.size)
    }

    @Test fun aMatchOnlyOneHalfKnowsIsNeitherAddedTwiceNorRemoved() = runBlocking {
        // Song 1 is called something else on B: matched by hand for A to B, unknown the other way.
        a.lists["pa"] = mutableListOf(ta("1"), ta("2"))
        b.lists["pb"] = mutableListOf(Track(id = "b-odd", title = "Another name entirely", artists = listOf("Someone"), durationMs = 100_000), tb("2"))
        b.catalogue += b.lists["pb"]!![0]
        env.store.putMatches(mapOf(FileStore.cacheKey("ma", "a-1", "mb") to "b-odd"))
        pair()
        both()
        // The first pair of runs could not know: B's odd one went to the review as not found on A.
        val (ab, ba) = both()
        assertEquals(0, ab.added + ab.removed + ba.added + ba.removed)
        assertEquals(2, listA.size)
        assertEquals(2, listB.size)
        assertEquals("b-odd", state()!!.links["a-1"])
    }

    @Test fun aMatchReplacedByHandDoesNotRemoveTheOriginal() = runBlocking {
        synced()
        // In the review the user replaced the match of A's song 2 on B with another edition, which
        // A's catalogue does not have: B's old copy is gone, the new one is matched by hand only.
        val edition = Track(id = "b-edition", title = "A rare edition", artists = listOf("Someone else"), durationMs = 90_000)
        listB.removeAll { it.id == "b-2" }
        listB += edition
        env.store.putMatches(mapOf(FileStore.cacheKey("ma", "a-2", "mb") to "b-edition"))
        // The twin runs first: A's song 2 has lost its old partner on B, but the review gave it a new one.
        val ba = run("ba")
        assertEquals(0, ba.removed)
        assertTrue(listA.any { it.id == "a-2" })
        assertEquals(0, run("ab").added)
        assertEquals(8, listA.size)
        assertEquals(8, listB.size)
    }

    @Test fun aSideWhoseTracksAllGotNewIdsStartsOver() = runBlocking {
        synced()
        // A personal server scanned its library again: the same songs under new ids, one of them
        // (matched by hand before) no longer recognisable by name.
        a.lists["pa"] = ((1..7).map { ta("$it").copy(id = "new-$it") } + Track(id = "new-8", title = "Untitled 08", artists = listOf("?"), durationMs = 1_000)).toMutableList()
        val (ab, ba) = both()
        assertEquals(0, ab.removed)
        assertEquals(0, ba.removed)
        assertTrue(ab.notes.toString(), "note_two_way_started" in ab.notes)
        assertEquals(8, listB.size)
        assertTrue(listA.any { it.id == "new-8" })
    }

    @Test fun turnedOffThePairOnlyAddsAndForgetsTheState() = runBlocking {
        synced()
        env.store.upsertJob(env.store.job("ab")!!.copy(twoWayRemovals = false))
        env.store.upsertJob(env.store.job("ba")!!.copy(twoWayRemovals = false))
        listA.removeAll { it.id == "a-2" }
        val (ab, ba) = both()
        assertEquals(0, ab.removed)
        assertEquals("copied back, as before the option", 1, ba.added)
        assertNull(state())
        // Turned on again it starts over: the first run only notes the state.
        env.store.upsertJob(env.store.job("ab")!!.copy(twoWayRemovals = true))
        env.store.upsertJob(env.store.job("ba")!!.copy(twoWayRemovals = true))
        listA.removeAll { it.id == "a-3" }
        assertEquals(0, run("ab").removed)
        assertNotNull(state())
    }

    @Test fun onlyOneHalfWithTheOptionRemovesNothing() = runBlocking {
        synced()
        env.store.upsertJob(env.store.job("ba")!!.copy(twoWayRemovals = false))
        listA.removeAll { it.id == "a-2" }
        assertEquals(0, run("ab").removed)
        assertEquals(8, listB.size)
    }

    @Test fun aPairMovedToOtherPlaylistsStartsOver() = runBlocking {
        synced()
        // The pair now points at another playlist on B, which happens to hold only some of the songs.
        b.lists["other"] = mutableListOf(tb("1"), tb("2"))
        env.store.upsertJob(env.store.job("ab")!!.let { it.copy(target = it.target.copy(playlistId = "other")) })
        env.store.upsertJob(env.store.job("ba")!!.let { it.copy(source = it.source.copy(playlistId = "other")) })
        assertNull(state())
        val (ab, ba) = both()
        assertEquals(0, ab.removed + ba.removed)
        assertEquals(6, ab.added)
        assertEquals(8, listA.size)
    }

    @Test fun theStateSurvivesARestartAndStaysOutOfAConfigurationFile() = runBlocking {
        synced()
        env.store.flush()
        val reopened = FileStore(env.dataDir)
        assertEquals(8, reopened.pairState(reopened.job("ab")!!)!!.links.size)
        // A file moved to another device carries the option, not the state: that device's first run only notes it.
        val file = ConfigBackup.export(env, "test")
        assertFalse(file.contains("pairStates"))
        assertTrue(ConfigBackup.read(file).jobs.all { it.twoWayRemovals })
        env.store.deleteJob("ba")
        assertTrue(env.store.data.pairStates.isEmpty())
    }

    @Test fun anEmptiedSideIsNeverMirrored() = runBlocking {
        synced()
        listA.clear()
        val ab = run("ab")
        assertEquals(0, ab.removed)
        assertEquals(8, listB.size)
        assertEquals("nor is A filled again behind the user's back before they confirm", 0, run("ba").added)
    }

    @Test fun aPairWithoutTheOptionIsUntouchedByAll() = runBlocking {
        a.lists["pa"] = mutableListOf(ta("1"))
        b.lists["pb"] = mutableListOf(tb("1"), tb("2"))
        pair(removals = false)
        val (ab, ba) = both()
        assertEquals(0, ab.removed)
        assertEquals(1, ba.added)
        assertTrue(env.store.data.pairStates.isEmpty())
        assertFalse(ab.notes.any { it.startsWith("note_two_way") })
    }

}

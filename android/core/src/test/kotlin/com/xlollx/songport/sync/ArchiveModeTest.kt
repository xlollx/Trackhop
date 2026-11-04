package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Keep every track" (SyncJob.archive): a Discover Weekly, a daylist or a chart kept as a playlist
 * that only grows. Nothing is removed, whatever Mirror removals says; only what is new is added, at
 * the end; a source that replaces all its tracks leaves the old ones in place. And a sync between
 * two playlists of the same account, which such an archive usually is.
 */
class ArchiveModeTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(mirror: Boolean = false, follows: Boolean = false, keepOrder: Boolean = false): SyncJob =
        SyncJob(
            id = "job-" + System.nanoTime(), name = "Discover Weekly",
            source = PlaylistRef("msrc", "dw", "Discover Weekly"), target = PlaylistRef("mdst", "arch", "Discover Weekly archive"),
            archive = true, mirrorRemovals = mirror, followsChanges = follows, keepOrder = keepOrder,
        ).also { env.store.upsertJob(it) }

    /** Each title by an artist of its own (its reverse), so no song of one week looks like another's. */
    private fun song(id: String, title: String) = t(id, title).copy(artists = listOf(title.reversed()), album = title)
    private fun week(vararg titles: String) = titles.map { song("s-$it", it) }.toMutableList()
    private fun publish(vararg titles: String) { dst.catalogue += titles.map { song("d-$it", it) } }

    @Test fun anArchiveNeverRemovesEvenWithMirrorRemovalsOn() = runBlocking {
        publish("A", "B", "C")
        src.lists["dw"] = week("A")
        dst.lists["arch"] = mutableListOf(song("d-B", "B"), song("d-C", "C"), t("mine", "Added by hand"))
        val r = engine.run(job(mirror = true, follows = true))
        assertTrue(r.error, r.ok)
        assertEquals(0, r.removed)
        assertEquals(0, r.heldBack)
        assertEquals(1, r.added)
        assertEquals(listOf("d-B", "d-C", "mine", "d-A"), dst.lists["arch"]!!.map { it.id })
        assertTrue(r.notes.toString(), r.notes.none { it.startsWith("note_removals") })
    }

    @Test fun onlyNewTracksAreAddedAtTheEnd() = runBlocking {
        publish("A", "B", "C", "D")
        src.lists["dw"] = week("A", "B")
        dst.lists["arch"] = mutableListOf()
        val j = job()
        assertEquals(2, engine.run(j).added)
        src.lists["dw"] = week("B", "C", "D")
        val r = engine.run(env.store.job(j.id)!!)
        assertEquals(2, r.added)
        assertEquals(0, r.removed)
        assertEquals(listOf("d-A", "d-B", "d-C", "d-D"), dst.lists["arch"]!!.map { it.id })
    }

    /** Every track of the source replaced (a new week): the old ones stay, the new ones join them, no confirmation asked. */
    @Test fun aSourceThatRotatesCompletelyLeavesTheArchiveWhole() = runBlocking {
        // Titles far apart (letters drawn from a fixed seed), so no week's song looks like another's.
        val rnd = java.util.Random(7)
        fun word() = (1..7).map { 'a' + rnd.nextInt(26) }.joinToString("").replaceFirstChar { it.uppercase() }
        val first = Array(30) { word() + " " + word() }
        val second = Array(30) { word() + " " + word() }
        publish(*first, *second)
        src.lists["dw"] = week(*first)
        dst.lists["arch"] = mutableListOf()
        val j = job(mirror = true)
        assertEquals(30, engine.run(j).added)
        src.lists["dw"] = week(*second)
        val plan = engine.plan(env.store.job(j.id)!!, createTarget = false, interactive = true)
        assertTrue(plan.toRemove.isEmpty())
        assertTrue(!plan.removalsNeedConfirmation)
        val r = engine.run(env.store.job(j.id)!!, unattended = true)
        assertTrue(r.error, r.ok)
        assertEquals(30, r.added)
        assertEquals(0, r.removed)
        assertEquals(0, r.heldBack)
        assertEquals(60, dst.lists["arch"]!!.size)
        assertEquals("d-" + second.last(), dst.lists["arch"]!!.last().id)
        // A third run of the same week: nothing to do, the archive does not shrink.
        src.lists["dw"] = week()
        val empty = engine.run(env.store.job(j.id)!!, unattended = true)
        assertEquals(0, empty.added)
        assertEquals(0, empty.removed)
        assertEquals(60, dst.lists["arch"]!!.size)
    }

    /** Same account, two playlists: no search at all, the source's own tracks go in. */
    @Test fun aSyncBetweenTwoPlaylistsOfTheSameAccountAddsTheSourceTracksThemselves() = runBlocking {
        src.lists["dw"] = week("A", "B")
        src.lists["arch"] = mutableListOf(song("s-A", "A"))
        val j = SyncJob(
            id = "same", name = "Discover Weekly", source = PlaylistRef("msrc", "dw", "Discover Weekly"),
            target = PlaylistRef("msrc", "arch", "Archive"), archive = true,
        ).also { env.store.upsertJob(it) }
        val r = engine.run(j)
        assertTrue(r.error, r.ok)
        assertEquals(1, r.added)
        assertTrue(r.unmatched.isEmpty())
        assertEquals(listOf("s-A", "s-B"), src.lists["arch"]!!.map { it.id })
    }

    @Test fun aPlaylistSyncedOntoItselfIsRefused() = runBlocking {
        src.lists["dw"] = week("A")
        val j = SyncJob(
            id = "self", name = "Loop", source = PlaylistRef("msrc", "dw", "Discover Weekly"),
            target = PlaylistRef("msrc", "dw", "Discover Weekly"),
        ).also { env.store.upsertJob(it) }
        val r = engine.run(j)
        assertEquals("error_same_playlist", r.error)
        assertEquals(1, src.lists["dw"]!!.size)
    }
}

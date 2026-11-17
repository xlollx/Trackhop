package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A run the system stops while it is adding (the worker's time is up, the app is closed, the server
 * shuts down): it ends there as stopped, so whoever started it runs it again. It is not a block the
 * service refused: nothing is tried again track by track, no track is listed as "not added", and
 * the run that follows adds what is left once.
 */
class StoppedWhileAddingTest {
    private class Stoppable(id: String) : MemoryService(id) {
        /** The add call that is stopped (1-based); none when null. */
        var stopAt: Int? = null
        /** Runs at the start of every add. */
        var onAdd: (() -> Unit)? = null
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            onAdd?.invoke()
            if (addCalls + 1 == stopAt) { addCalls++; throw CancellationException("the worker was stopped") }
            super.addTracks(ctx, playlistId, tracks)
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: Stoppable

    private fun t(prefix: String, n: Int) = Track(id = "$prefix$n", title = "Song number $n", artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("csrc")
        dst = Stoppable("cdst")
        Providers.testDoubles = listOf(src, dst)
        src.catalogue += (1..120).map { t("s", it) }
        dst.catalogue += (1..120).map { t("d", it) }
        src.lists["p"] = (1..120).map { t("s", it) }.toMutableList()
        dst.lists["t"] = ArrayList()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun aRunStoppedWhileAddingEndsAsStoppedAndTheNextAddsTheRestOnce() = runBlocking {
        val job = SyncJob(id = "j", name = "copy", source = PlaylistRef("csrc", "p", "p"), target = PlaylistRef("cdst", "t", "t")).also { env.store.upsertJob(it) }
        dst.stopAt = 2
        val stopped = runCatching { SyncEngine(env).run(job) }.exceptionOrNull()
        assertTrue("the stop reaches whoever started the run: $stopped", stopped is CancellationException)
        // The first block went in, the second was stopped: no third, and no track tried by itself.
        assertEquals(2, dst.addCalls)
        assertEquals(50, dst.lists["t"]!!.size)
        assertTrue("no report listing the tracks left as failed", env.store.data.reports.none { it.unmatched.isNotEmpty() || it.added > 0 })

        dst.stopAt = null
        val r = SyncEngine(env).run(job)
        assertTrue(r.ok)
        assertEquals(70, r.added)
        assertEquals((1..120).map { "d$it" }.toSet(), dst.lists["t"]!!.map { it.id }.toSet())
        assertEquals("nothing added twice", 120, dst.lists["t"]!!.size)
    }

    @Test fun thePlaylistARunMakesIsOnTheDiskBeforeAnythingIsAddedToIt() = runBlocking {
        val job = SyncJob(id = "n", name = "new", source = PlaylistRef("csrc", "p", "p"), target = PlaylistRef("cdst", null, "Fresh")).also { env.store.upsertJob(it) }
        env.store.flush()
        // What a process starting now would read, at the moment the first tracks go in.
        val onDisk = ArrayList<String?>()
        dst.onAdd = { onDisk += com.xlollx.songport.data.FileStore(env.dataDir).job("n")?.target?.playlistId }
        // Closed by the system right there: the run that follows must find the playlist, not make another.
        dst.stopAt = 1
        assertTrue(runCatching { SyncEngine(env).run(job) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf<String?>("Fresh"), onDisk)
        dst.stopAt = null
        dst.onAdd = null
        val again = com.xlollx.songport.data.FileStore(env.dataDir).job("n")!!
        val r = SyncEngine(env).run(again)
        assertTrue(r.ok)
        assertEquals("one playlist, made once", listOf("t", "Fresh"), dst.lists.keys.toList())
        assertEquals(120, dst.lists["Fresh"]!!.size)
    }
}

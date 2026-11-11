package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * A connector that lists one playlist at a time: who holds it, who waits behind whom, and a preview
 * that hears the sync it waits for (and logs it) instead of spinning in silence.
 */
class ServiceReadsTest {
    private fun reader(job: String, since: Long = 1L) = ServiceReads.Reader(job, "Sync $job", "Spotify (web)", since)

    @After fun noneLeft() = assertTrue(ServiceReads.state.value.isEmpty())

    @Test fun theFirstReaderHoldsTheGateAndTheOthersWaitBehindIt() {
        val a = ServiceReads.enter("bridge", reader("a", 10))
        val b = ServiceReads.enter("bridge", reader("b", 20))
        val other = ServiceReads.enter("deezer_bridge", reader("c", 30))
        try {
            assertEquals(reader("a", 10), ServiceReads.holder("bridge"))
            assertNull("the holder waits for no one", ServiceReads.ahead(a))
            assertEquals(reader("a", 10), ServiceReads.ahead(b))
            assertNull("another gate is free of them", ServiceReads.ahead(other))
            assertNull(ServiceReads.holder("qobuz_web"))
            ServiceReads.leave(a)
            assertEquals(reader("b", 20), ServiceReads.holder("bridge"))
            assertNull(ServiceReads.ahead(b))
        } finally {
            listOf(a, b, other).forEach(ServiceReads::leave)
        }
    }

    @Test fun twoReadsOfTheSameSyncAreStillTwoReaders() {
        // A preview opened on a sync that is running: the preview waits behind the run.
        val run = ServiceReads.enter("bridge", reader("a"))
        val preview = ServiceReads.enter("bridge", reader("a"))
        try {
            assertEquals(reader("a"), ServiceReads.ahead(preview))
            assertNull(ServiceReads.ahead(run))
        } finally {
            ServiceReads.leave(run); ServiceReads.leave(preview)
        }
    }

    @Test fun withoutAGateTheReadIsNotNoted() = runBlocking {
        val heard = ArrayList<ServiceReads.Reader?>()
        val got = ServiceReads.reading(null, reader("a"), { heard += it }) { ServiceReads.state.value.size }
        assertEquals(0, got)
        assertTrue(heard.isEmpty())
    }

    /** A source whose listings take turns, as the web connectors' do; the read of [held] stops until [release]. */
    private class OneAtATime(id: String) : MemoryService(id) {
        override val readGate: String get() = "gate"
        val lock = Mutex()
        var held = ""
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = lock.withLock {
            if (playlistId == held) { inside.complete(Unit); release.await() }
            super.tracks(ctx, playlistId)
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: OneAtATime

    @Before fun setUp() {
        env = ContractEnv()
        src = OneAtATime("gsrc")
        val dst = MemoryService("gdst")
        Providers.testDoubles = listOf(src, dst)
        src.lists["p1"] = mutableListOf(Track(id = "a", title = "Alpha", artists = listOf("Band")))
        src.lists["p2"] = mutableListOf(Track(id = "b", title = "Beta", artists = listOf("Band")))
        dst.lists["t1"] = mutableListOf(); dst.lists["t2"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(id: String, name: String, playlist: String, target: String) =
        SyncJob(id = id, name = name, source = PlaylistRef("gsrc", playlist, playlist), target = PlaylistRef("gdst", target, target))
            .also { env.store.upsertJob(it) }

    @Test fun aPreviewHearsTheSyncItWaitsBehindAndGoesOnWhenItIsDone() = runBlocking(Dispatchers.Default) {
        src.held = "p1"
        val running = job("a", "Metal & More", "p1", "t1")
        val previewed = job("b", "Rap", "p2", "t2")
        val first = async { SyncEngine(env).plan(running, createTarget = false) }
        withTimeout(5_000) { src.inside.await() }
        assertEquals("Metal & More", ServiceReads.holder("gate")?.jobName)

        val heard = Collections.synchronizedList(ArrayList<ServiceReads.Reader?>())
        val waiting = CompletableDeferred<ServiceReads.Reader>()
        val second = async {
            SyncEngine(env).plan(previewed, createTarget = false, interactive = true, onWait = { r -> heard += r; r?.let { waiting.complete(it) } })
        }
        val behind = withTimeout(5_000) { waiting.await() }
        assertEquals("a", behind.jobId)
        assertEquals("Metal & More", behind.jobName)
        assertEquals("gsrc", behind.service)
        assertTrue(env.logs.toString(), env.logs.any { it.startsWith("[engine] preview of Rap: waiting for \"Metal & More\", reading gsrc for ") })

        src.release.complete(Unit)
        val done = withTimeout(5_000) { first.await() }
        assertEquals(1, done.toAdd.size + done.unmatched.size)
        val plan = withTimeout(5_000) { second.await() }
        assertEquals(1, plan.toAdd.size + plan.unmatched.size)
        assertNull("its turn came: no longer waiting", heard.last())
        assertEquals("logged once", 1, env.logs.count { it.contains("waiting for \"Metal & More\"") })
    }
}

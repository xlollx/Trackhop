package com.xlollx.songport.server

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.Timing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server's minute loop (Runner.startScheduler): an error in one turn, or in one sync of a turn,
 * is written in the log and the scheduled syncs go on. It used to end the loop until a restart.
 */
class SchedulerLoopTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: ServerEnv
    private lateinit var runner: Runner

    @Before fun start() {
        Log.reset()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "https://songport.example.org")
        runner = Runner(env)
    }

    @After fun stop() { runner.stop() }

    @Test fun aTurnThatThrowsDoesNotStopTheNextOne() {
        val turns = AtomicInteger()
        val third = CountDownLatch(3)
        runner.loop(0, 5) {
            third.countDown()
            if (turns.incrementAndGet() == 1) throw IllegalStateException("the backup folder is gone")
        }
        assertTrue("the loop ended at the turn that threw (turns: ${turns.get()})", third.await(10, TimeUnit.SECONDS))
        assertTrue(Log.recent().toString(), Log.recent().any { it.contains("[scheduler]") && it.contains("the backup folder is gone") })
    }

    @Test fun aSyncThatThrowsDoesNotKeepTheOthersOfTheTurnFromStarting() = kotlinx.coroutines.runBlocking<Unit> {
        fun hourly(id: String) = env.store.upsertJob(Timing.apply(SyncJob(id, "Sync $id", PlaylistRef("csv", "x", "X"), PlaylistRef("csv", null, "Y")), 60, null, emptyList()))
        hourly("a"); hourly("b"); hourly("c")
        val started = ArrayList<String>()
        val later = System.currentTimeMillis() + 3 * 86_400_000L
        runner.tick(later) { job -> if (job.id == "a") throw IllegalStateException("boom") else started += job.id }
        assertEquals(listOf("b", "c"), started)
        assertTrue(Log.recent().toString(), Log.recent().any { it.contains("[scheduler] Sync a: IllegalStateException: boom") })
        // And the turn after starts them all.
        started.clear()
        runner.tick(later + 60_000) { started += it.id }
        assertEquals(listOf("a", "b", "c"), started)
    }
}

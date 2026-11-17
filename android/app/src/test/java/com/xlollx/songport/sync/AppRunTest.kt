package com.xlollx.songport.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work the app keeps for itself (restoring a configuration file, continuing a playlist): the
 * screen that asked for it goes away and the work still ends, with its outcome waiting.
 */
class AppRunTest {
    @Test fun theWorkEndsAlthoughTheScreenThatStartedItIsGone() = runBlocking<Unit> {
        val run = AppRun<Int>(Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val screen = CoroutineScope(Job() + Dispatchers.Default)
        // The screen asks, as a tap does, and is left before the work has ended.
        screen.launch { run.start { gate.await(); 3 } }.join()
        assertTrue(run.running.value)
        screen.cancel()
        gate.complete(Unit)
        val done = withTimeout(10_000) { run.finished.first { it != null } }
        assertEquals(3, done!!.getOrNull())
        withTimeout(10_000) { run.running.first { !it } }
    }

    @Test fun aSecondStartWhileOneRunsIsRefusedAndAFailureIsKeptAsTheOutcome() = runBlocking<Unit> {
        val run = AppRun<Int>(Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        assertTrue(run.start { gate.await(); throw IllegalStateException("the file is from a newer version") })
        assertFalse("one at a time", run.start { 1 })
        gate.complete(Unit)
        val done = withTimeout(10_000) { run.finished.first { it != null } }
        assertEquals("the file is from a newer version", done!!.exceptionOrNull()?.message)
        withTimeout(10_000) { run.running.first { !it } }
        assertTrue("free again", run.start { 1 })
    }
}

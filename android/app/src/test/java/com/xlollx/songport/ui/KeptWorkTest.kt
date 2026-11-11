package com.xlollx.songport.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lookups a page hands to [KeptWork]: their progress and result live outside the page, so a
 * page drawn again (the screen turned, the activity remade) reads the same run and then its result.
 */
class KeptWorkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun progressThenTheResultForWhatWasAsked() {
        val work = KeptWork<String>(scope, now = { 42L })
        val gate = CompletableDeferred<Unit>()
        assertTrue(work.start("playlist-1") { step -> step(0, 3); step(1, 3); gate.await(); step(3, 3); "found" })
        // A page drawn now (the old one is gone) sees the same run where it is.
        assertTrue(work.running)
        assertEquals(1 to 3, work.progress)
        assertNull(work.result)
        gate.complete(Unit)
        assertFalse(work.running)
        assertEquals("found", work.result)
        assertEquals("playlist-1", work.key)
        assertEquals(42L, work.at)
        assertNull(work.error)
    }

    @Test fun aSecondStartWhileRunningIsIgnored() {
        val work = KeptWork<Int>(scope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        assertTrue(work.start("a") { runs++; gate.await(); 1 })
        assertFalse(work.start("b") { runs++; 2 })
        gate.complete(Unit)
        assertEquals(1, runs)
        assertEquals(1, work.result)
        assertEquals("a", work.key)
        // Done: a new run can start.
        assertTrue(work.start("b") { 2 })
        assertEquals(2, work.result)
    }

    @Test fun aFailureIsKeptToBeShownAndTheLastResultStays() {
        val work = KeptWork<String>(scope)
        work.start("a") { "first" }
        work.start("b") { throw IllegalStateException("Discogs is down") }
        assertFalse(work.running)
        assertEquals("Discogs is down", work.error)
        assertEquals("first", work.result)
        assertEquals("a", work.key)
        work.start("c") { throw RuntimeException() }
        assertEquals("a failure without a message is still told", "", work.error)
    }

    @Test fun cancelStopsTheRunWithoutAnError() {
        val work = KeptWork<String>(scope)
        val gate = CompletableDeferred<Unit>()
        work.start("a") { gate.await(); "late" }
        work.cancel()
        gate.complete(Unit)
        assertFalse(work.running)
        assertNull(work.result)
        assertNull(work.error)
    }

    @Test fun clearForgetsTheResult() {
        val work = KeptWork<String>(scope)
        work.start("a") { "x" }
        work.clear()
        assertNull(work.result)
        assertNull(work.key)
    }
}

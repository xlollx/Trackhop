package com.xlollx.songport.update

import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Google Play build may ask for Google Play's review prompt. */
class ReviewPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private fun run(error: String? = null, partial: Boolean = false, shrunk: Int = 0) =
        SyncReport(id = "r", jobId = "j", jobName = "n", startedEpoch = 0, durationMs = 1, error = error, partial = partial, targetShrunkFrom = shrunk)
    private val never = ReviewPolicy.State(lastAskedMs = 0, done = false)
    private val good = List(ReviewPolicy.GOOD_RUNS) { run() }

    @Test fun afterEnoughSyncsThatWentWell() {
        assertTrue(ReviewPolicy.due(good, never, now))
        assertFalse("too few runs yet", ReviewPolicy.due(good.drop(1), never, now))
        assertFalse(ReviewPolicy.due(emptyList(), never, now))
    }

    @Test fun neverAfterAnError() {
        assertFalse("the run that just ended failed", ReviewPolicy.due(listOf(run(error = "boom")) + good, never, now))
        assertFalse("one of the latest failed", ReviewPolicy.due(good.take(3) + run(error = "boom") + good, never, now))
        assertFalse("a run that stopped before writing", ReviewPolicy.due(listOf(run(shrunk = 40)) + good, never, now))
        // An old failure, before the latest good runs, no longer counts.
        assertTrue(ReviewPolicy.due(good + run(error = "boom"), never, now))
        // A partial report is not a run: it neither counts nor hides a failure behind it.
        assertTrue(ReviewPolicy.due(listOf(run(partial = true)) + good, never, now))
        assertFalse(ReviewPolicy.due(listOf(run(partial = true), run(error = "boom")) + good, never, now))
    }

    @Test fun atMostOnceEveryFewMonths() {
        assertFalse(ReviewPolicy.due(good, ReviewPolicy.State(now - 30 * day, false), now))
        assertFalse(ReviewPolicy.due(good, ReviewPolicy.State(now - 119 * day, false), now))
        assertTrue(ReviewPolicy.due(good, ReviewPolicy.State(now - 121 * day, false), now))
    }

    @Test fun neverAgainOnceTheFlowWasShownAndCompleted() {
        assertFalse(ReviewPolicy.due(good, ReviewPolicy.State(now - 400 * day, done = true), now))
    }
}

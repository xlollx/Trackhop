package com.xlollx.songport.update

import com.xlollx.songport.model.SyncReport

/**
 * When the Google Play build may ask Google Play to show its review prompt (update/StoreReview.kt,
 * Play build only; the other builds never ask). A good moment only: the syncs that just ended went
 * well and so did the ones before; at most once every few months; never again once the prompt's
 * flow was started and ran to its end. Google Play itself decides whether anything is shown, and
 * does not say whether a review was left.
 */
object ReviewPolicy {
    /** This many complete runs, the latest ones, all without an error. */
    const val GOOD_RUNS = 5

    /** The least time between two requests. */
    const val GAP_MS = 120L * 24 * 60 * 60 * 1000

    /** [lastAskedMs]: when Google Play was last asked (0: never); [done]: its flow was started and completed. */
    class State(val lastAskedMs: Long, val done: Boolean)

    /** [reports] newest first, as the store keeps them. */
    fun due(reports: List<SyncReport>, state: State, nowMs: Long): Boolean {
        if (state.done) return false
        if (state.lastAskedMs != 0L && nowMs - state.lastAskedMs < GAP_MS) return false
        val latest = reports.filter { !it.partial }.take(GOOD_RUNS)
        // Never after an error, nor after a run that stopped before writing.
        return latest.size == GOOD_RUNS && latest.all { it.ok && it.targetShrunkFrom == 0 }
    }
}

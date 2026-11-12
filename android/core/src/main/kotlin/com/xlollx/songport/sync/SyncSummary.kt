package com.xlollx.songport.sync

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport

/**
 * The one line at the top of the Syncs tab: how the syncs stand, the most pressing thing first.
 * Nothing of its own is counted: the running ones are [SyncState.Tally]'s, the rest the filter's
 * statuses ([SyncFilter.Status]), so the line and the chips under it always agree. [filter] is what a
 * tap on the line shows (empty: nothing to show, the line is not a button).
 */
data class SyncSummary(val kind: Kind, val first: Int = 0, val second: Int = 0, val filter: Set<SyncFilter.Status> = emptySet()) {
    enum class Kind {
        /** [first] syncs running. */
        RUNNING,
        /** [first] running, [second] paused. */
        RUNNING_PAUSED,
        /** Every running sync paused: [first] of them. */
        PAUSED,
        /** [first] syncs whose last run failed, [second] that left something to fix; either may be 0, not both. */
        ATTENTION,
        /** Every sync that is on ran well, with nothing left to look at and no source seen changed since. */
        ALL_OK,
        /** Nothing to say but how many syncs there are (some never run, all off, a source changed since). */
        IDLE,
    }

    companion object {
        /**
         * [changed]: a source was seen changed since its sync's last run (Freshness), so "all up to
         * date" would not be true even though every last run ended well.
         */
        fun of(jobs: List<SyncJob>, reports: List<SyncReport>, tally: SyncState.Tally, running: Set<String>, changed: Boolean = false): SyncSummary {
            if (tally.total > 0) {
                val show = setOf(SyncFilter.Status.RUNNING)
                return when {
                    tally.allPaused -> SyncSummary(Kind.PAUSED, tally.total, filter = show)
                    tally.somePaused -> SyncSummary(Kind.RUNNING_PAUSED, tally.running, tally.paused, show)
                    else -> SyncSummary(Kind.RUNNING, tally.total, filter = show)
                }
            }
            val counts = SyncFilter.counts(jobs, reports, running)
            val failed = counts[SyncFilter.Status.ERROR] ?: 0
            val toFix = counts[SyncFilter.Status.NEEDS_FIX] ?: 0
            if (failed > 0 || toFix > 0) {
                return SyncSummary(Kind.ATTENTION, failed, toFix, setOfNotNull(SyncFilter.Status.ERROR.takeIf { failed > 0 }, SyncFilter.Status.NEEDS_FIX.takeIf { toFix > 0 }))
            }
            // Every sync up to date or turned off, and one at least up to date.
            val settled = SyncFilter(statuses = setOf(SyncFilter.Status.UP_TO_DATE, SyncFilter.Status.DISABLED)).select(jobs, reports, running).size == jobs.size
            return if (settled && !changed && (counts[SyncFilter.Status.UP_TO_DATE] ?: 0) > 0) SyncSummary(Kind.ALL_OK) else SyncSummary(Kind.IDLE)
        }
    }
}

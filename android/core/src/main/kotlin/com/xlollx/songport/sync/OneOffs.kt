package com.xlollx.songport.sync

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport

/**
 * The one-off transfers ([SyncJob.oneOff]): a setlist made a playlist or added to one, run once. They
 * are not syncs: only [StoreData.jobs] holds them, every list of syncs reads [StoreData.syncs]. What
 * they did (added, already there, not found) and their review stay in Tools › Recent transfers, the
 * newest [MAX]; one goes once its review is done (nothing left of what there was to review) or a week
 * after it was made, whichever comes first. Deleting one never touches its target playlist.
 */
object OneOffs {
    const val KEEP_MS = 7L * 24 * 3_600_000
    const val MAX = 20

    /** The newest first, at most [MAX]. */
    fun recent(d: StoreData): List<SyncJob> = d.jobs.filter { it.oneOff }.sortedByDescending { it.createdEpoch }.take(MAX)

    /** What is left to review in [r]: songs not found and uncertain matches. */
    fun toReview(r: SyncReport): Int = r.unmatchedTracks.size + r.reviewTracks.size

    /** The review went from something to review to nothing left. */
    fun reviewed(before: SyncReport, after: SyncReport): Boolean = toReview(before) > 0 && toReview(after) == 0

    /** The ids of the one-offs to delete now: a week old, or beyond the newest [MAX]. */
    fun over(d: StoreData, now: Long): List<String> {
        val all = d.jobs.filter { it.oneOff }.sortedByDescending { it.createdEpoch }
        return all.filterIndexed { i, j -> i >= MAX || now - j.createdEpoch >= KEEP_MS }.map { it.id }
    }
}

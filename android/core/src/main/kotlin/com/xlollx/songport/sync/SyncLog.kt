package com.xlollx.songport.sync

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.shared.SharedProvider

/**
 * What the diagnostic log says about a run and about a batch of them. A sync is named as everywhere
 * in the log, by its own name; a batch by its group's name and its services' ids. Never an account
 * or a user name, a token, a key or a cookie. Kotlin puro: coperto dai test.
 */
object SyncLog {
    /** The multi sync or shared playlist [jobs] all belong to, when they do. */
    fun groupOf(jobs: List<SyncJob>): String? =
        jobs.map { SharedGroups.sharedSide(it) }.distinct().singleOrNull()

    /**
     * One sync of a group by its way and its service: "up:spotify" sends the playlist's changes to
     * the group's list, "down:tidal" brings the list to the playlist. Any other sync: "spotify>tidal".
     */
    fun role(job: SyncJob): String = when {
        job.source.provider == SharedProvider.ID -> "down:${job.target.provider}"
        job.target.provider == SharedProvider.ID -> "up:${job.source.provider}"
        else -> "${job.source.provider}>${job.target.provider}"
    }

    /** A batch in a log line: its group by id and name, when it is one group's. */
    private fun head(group: String?, name: String?): String =
        (group?.let { " group=$it" } ?: "") + (name?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: "")

    fun batchStart(group: String?, name: String?, jobs: List<SyncJob>, scheduled: Boolean): String =
        "batch start" + head(group, name) + " n=${jobs.size} (${if (scheduled) "scheduled" else "manual"}) order=" + jobs.joinToString(", ") { role(it) }

    /** What the end line of a run says after the sync's tag. */
    fun end(r: SyncReport): String =
        "+${r.added} -${r.removed} nf=${r.unmatched.size} src=${r.sourceCount} dst=${r.targetCount} held=${r.heldBack} interrupted=${r.searchInterrupted} net=${r.netError ?: "-"}" +
            (r.error?.let { " ERR $it" } ?: "")

    fun result(r: SyncReport) = SyncState.JobResult(r.ok, r.offline, r.error, r.added, r.removed, r.unmatched.size, r.heldBack)

    /** The summary of a batch that is over ([stopped]: its worker was stopped before the end). */
    fun batchEnd(b: SyncState.Batch, name: String? = null, stopped: Boolean = false): String {
        val o = b.outcomes.values
        val ran = o.filter { it.skipped == null }
        return "batch ${if (stopped) "stopped" else "end"}" + head(b.group, name) +
            " ran=${ran.size}/${b.order.size} ok=${ran.count { it.ok }} failed=${ran.count { it.failed }} offline=${ran.count { it.offline }}" +
            " skipped=${o.count { it.skipped != null }} left=${b.left.size} +${ran.sumOf { it.added }} -${ran.sumOf { it.removed }} nf=${ran.sumOf { it.notFound }} held=${ran.sumOf { it.held }}"
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.SyncFilter.Status
import com.xlollx.songport.sync.SyncSummary.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSummaryTest {
    private fun job(id: String, report: String? = null, enabled: Boolean = true) =
        SyncJob(id = id, name = id, source = PlaylistRef("spotify", "p-$id", ""), target = PlaylistRef("ytm", null, ""), enabled = enabled, lastReportId = report)

    private fun report(id: String, jobId: String, error: String? = null, unmatched: List<String> = emptyList(), heldBack: Int = 0) =
        SyncReport(id = id, jobId = jobId, jobName = jobId, startedEpoch = 0, durationMs = 0, error = error, unmatched = unmatched, heldBack = heldBack)

    private val idle = SyncState.Tally(0, 0)
    private fun of(jobs: List<SyncJob>, reports: List<SyncReport>, tally: SyncState.Tally = idle, running: Set<String> = emptySet(), changed: Boolean = false) =
        SyncSummary.of(jobs, reports, tally, running, changed)

    @Test fun everySyncRanWellIsAllUpToDate() {
        val jobs = listOf(job("a", "r1"), job("b", "r2"))
        val reports = listOf(report("r1", "a"), report("r2", "b"))
        assertEquals(SyncSummary(Kind.ALL_OK), of(jobs, reports))
        // A sync turned off does not stand in the way; one never run does.
        assertEquals(Kind.ALL_OK, of(jobs + job("c", enabled = false), reports).kind)
        assertEquals(Kind.IDLE, of(jobs + job("c"), reports).kind)
        // Nothing up to date at all (every sync off, or none run yet): no tick.
        assertEquals(Kind.IDLE, of(listOf(job("a", enabled = false)), emptyList()).kind)
        assertEquals(Kind.IDLE, of(listOf(job("a")), emptyList()).kind)
    }

    @Test fun aSourceSeenChangedSinceIsNotUpToDate() {
        val jobs = listOf(job("a", "r1"))
        assertEquals(Kind.IDLE, of(jobs, listOf(report("r1", "a")), changed = true).kind)
    }

    @Test fun failuresAndThingsToFixAreCountedAsTheChipsCountThem() {
        val jobs = listOf(job("a", "r1"), job("b", "r2"), job("c", "r3"), job("d", "r4"))
        val reports = listOf(report("r1", "a"), report("r2", "b", unmatched = listOf("x")), report("r3", "c", heldBack = 2), report("r4", "d", error = "token expired"))
        val s = of(jobs, reports)
        assertEquals(SyncSummary(Kind.ATTENTION, 1, 2, setOf(Status.ERROR, Status.NEEDS_FIX)), s)
        val counts = SyncFilter.counts(jobs, reports, emptySet())
        assertEquals(counts[Status.ERROR], s.first)
        assertEquals(counts[Status.NEEDS_FIX], s.second)
        // A tap shows exactly those syncs.
        assertEquals(listOf("b", "c", "d"), SyncFilter(statuses = s.filter).select(jobs, reports, emptySet()).map { it.id })
        // Only one of the two kinds: only its chip.
        assertEquals(SyncSummary(Kind.ATTENTION, 0, 2, setOf(Status.NEEDS_FIX)), of(jobs.take(3), reports))
        assertEquals(SyncSummary(Kind.ATTENTION, 1, 0, setOf(Status.ERROR)), of(listOf(jobs[0], jobs[3]), reports))
    }

    @Test fun runningComesFirstAndFollowsTheTally() {
        val jobs = listOf(job("a", "r1"), job("b", "r2"), job("c"))
        val reports = listOf(report("r1", "a", error = "x"), report("r2", "b"))
        val show = setOf(Status.RUNNING)
        assertEquals(SyncSummary(Kind.RUNNING, 2, filter = show), of(jobs, reports, SyncState.Tally(2, 0), setOf("a", "b")))
        assertEquals(SyncSummary(Kind.RUNNING_PAUSED, 1, 1, show), of(jobs, reports, SyncState.Tally(2, 1), setOf("a", "b")))
        assertEquals(SyncSummary(Kind.PAUSED, 2, filter = show), of(jobs, reports, SyncState.Tally(2, 2), setOf("a", "b")))
    }
}

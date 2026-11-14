package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vanished songs leaves out the removals of a playlist that renews itself (a daylist), however it is told. */
class VanishedRotationTest {
    private fun job(id: String, follows: Boolean = false, archive: Boolean = false) =
        SyncJob(id = id, name = id, source = PlaylistRef("s", "p", id), target = PlaylistRef("d", "q", id), followsChanges = follows, archive = archive)
    private fun run(job: String, source: Int, removed: Int) =
        SyncReport(id = "$job-$removed", jobId = job, jobName = job, startedEpoch = 0, durationMs = 0, sourceCount = source, removed = removed)

    @Test fun aRunTakingHalfTheSourceAwayIsARotationEvenForADeletedSync() {
        val reports = listOf(run("daylist", 50, 46), run("gone-daylist", 40, 38), run("normal", 300, 3), run("small", 6, 4))
        assertEquals(setOf("daylist", "gone-daylist"), Vanished.rotatingSyncs(listOf(job("daylist"), job("normal"), job("small")), reports))
    }

    @Test fun followingChangesKeepingEverythingOrMarkedByHandAreLeftOut() {
        val jobs = listOf(job("weekly", follows = true), job("archive", archive = true), job("marked"), job("normal"))
        assertEquals(setOf("weekly", "archive", "marked"), Vanished.rotatingSyncs(jobs, emptyList(), hidden = setOf("marked")))
    }
}

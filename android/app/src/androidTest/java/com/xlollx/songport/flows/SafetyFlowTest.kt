package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guards that keep a sync from losing tracks: large Mirror removals held back until the user
 * confirms them in the preview (reached from the card's status), and a two-way pair edited as one
 * sync from either card, without touching the other half's own name, ignore list or reports.
 */
@RunWith(AndroidJUnit4::class)
class SafetyFlowTest : FlowTest() {

    private fun reports(jobId: String) = store.data.reports.filter { it.jobId == jobId && !it.partial }

    @Test
    fun heldBackRemovalsAreConfirmedInThePreview() {
        // Deezer's "Big Mix" holds 18 songs; Spotify's "Chill Evening" (5 of them) mirrors into it:
        // 13 removals, far over a quarter of the target.
        FakeWorld.playlists.getValue("deezer") += FakeWorld.FakePlaylist("bigmix", "Big Mix", FakeWorld.songs("deezer", 0 until 18))
        val job = SyncJob(
            "job-m", "Chill mirror", PlaylistRef("spotify", FakeWorld.SPOTIFY_CHILL, "Chill Evening"), PlaylistRef("deezer", "bigmix", "Big Mix"),
            mirrorRemovals = true,
        )
        store.update { it.copy(jobs = listOf(job)) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Chill mirror")

        // A normal run adds what it can and holds the removals back; the card says so.
        clickDesc(s(R.string.run_now))
        waitFor("the first run", 60_000) { reports(job.id).size == 1 }
        val first = reports(job.id).single()
        assertTrue(first.error ?: "", first.ok)
        assertEquals(0, first.removed)
        assertEquals(13, first.heldBack)
        assertEquals(18, FakeWorld.playlist("deezer", "Big Mix")!!.tracks.size)
        waitText(s(R.string.pill_held_back))
        assertFalse(exists(s(R.string.pill_ok)))

        // The status leads to the preview, which shows them and asks. Run without the tick: still held back.
        click(s(R.string.pill_held_back))
        waitText(s(R.string.preview_remove_warning, 13, 18), ms = 30_000)
        waitText(s(R.string.preview_remove_confirm, 13))
        click(s(R.string.run_now))
        waitFor("the second run", 60_000) { reports(job.id).size == 2 }
        assertEquals(13, reports(job.id).first().heldBack)
        assertEquals(18, FakeWorld.playlist("deezer", "Big Mix")!!.tracks.size)

        // Ticked, the same run removes exactly what was shown, once.
        waitText(s(R.string.pill_held_back))
        click(s(R.string.pill_held_back))
        waitText(s(R.string.preview_remove_confirm, 13), ms = 30_000)
        click(s(R.string.preview_remove_confirm, 13))
        click(s(R.string.run_now))
        waitFor("the confirmed run", 60_000) { reports(job.id).size == 3 }
        val confirmed = reports(job.id).first()
        assertEquals(confirmed.notes.toString(), 13, confirmed.removed)
        assertEquals(0, confirmed.heldBack)
        assertEquals(5, FakeWorld.playlist("deezer", "Big Mix")!!.tracks.size)
        assertNull("the confirmation is spent", store.data.removalConfirmations[job.id])
        // Nothing held back any more. Since 1.6 "Up to date" needs a version check the fake services
        // cannot answer, so a card that just ran says that instead.
        waitGone(s(R.string.pill_held_back))
        waitText(s(R.string.pill_just_ran))
    }

    @Test
    fun editingTheTwinEditsThePairAndKeepsItsOwnFields() {
        val now = System.currentTimeMillis()
        // A pair saved by an older version: no twin flag, and Mirror removals left on the original.
        val original = SyncJob(
            "job-a", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"),
            mirrorRemovals = true, keepOrder = true, linkedJobId = "job-b", ignoredSourceIds = listOf("ignored-on-a"),
        )
        val twin = SyncJob(
            "job-b", "Road Trip ↔", PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"), PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"),
            linkedJobId = "job-a", ignoredSourceIds = listOf("ignored-on-b"), lastRunEpoch = now - 60_000, lastReportId = "rb",
        )
        val report = SyncReport(id = "rb", jobId = "job-b", jobName = twin.name, startedEpoch = now - 60_000, durationMs = 5_000, sourceCount = 3, targetCount = 8)
        // The twin listed first: its card, and the menu it opens, at the top of the screen.
        store.update { it.copy(jobs = listOf(twin, original), reports = listOf(report)) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip ↔")

        // Edit on the "↔" card opens the original, with a note that says so.
        clickDesc(s(R.string.more_actions), index = 0)
        click(s(R.string.edit))
        waitText(s(R.string.editor_twin_note, "Road Trip ↔"))
        // With Two-way on, Mirror removals is off and cannot be turned on.
        waitText(s(R.string.editor_mirror_two_way))
        scrollTo(compose.onAllNodes(isToggleable() and hasText(s(R.string.editor_mirror))).onFirst()).assertIsNotEnabled()
        click(s(R.string.schedule_daily))
        click(s(R.string.save))
        waitText("Road Trip ↔")

        val a = store.job("job-a")!!
        val b = store.job("job-b")!!
        // The original: its own name and direction, the new schedule, no removals in a pair.
        assertEquals("Road Trip", a.name)
        assertEquals("spotify", a.source.provider)
        assertEquals("deezer", a.target.provider)
        assertEquals(Schedule.DAILY, a.schedule)
        assertFalse(a.mirrorRemovals)
        assertTrue(a.keepOrder)
        assertEquals(listOf("ignored-on-a"), a.ignoredSourceIds)
        assertEquals("job-b", a.linkedJobId)
        // The twin: the shared settings, and still its own name, ignore list and report.
        assertEquals("Road Trip ↔", b.name)
        assertEquals("deezer", b.source.provider)
        assertEquals("spotify", b.target.provider)
        assertEquals(Schedule.DAILY, b.schedule)
        assertFalse(b.mirrorRemovals)
        assertEquals(listOf("ignored-on-b"), b.ignoredSourceIds)
        assertEquals("rb", b.lastReportId)
        assertEquals("job-a", b.linkedJobId)
        assertTrue("marked as the twin from now on", b.twin)
        assertEquals(2, store.data.jobs.size)
    }
}

package com.xlollx.songport.flows

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review screen acting on several tracks at once (issue #26): a long press starts the
 * selection, "Select all" takes a section, and the uncertain matches picked are removed from the
 * target together (or kept), the tracks not found ignored together.
 */
@RunWith(AndroidJUnit4::class)
class BulkReviewFlowTest : FlowTest() {

    /** Road Trip to Deezer's Workout: three loose matches that landed on Workout's songs, two not found. */
    private fun seed(): String {
        val job = SyncJob(
            id = "bulk", name = "Road Trip",
            source = PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"),
            target = PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"),
        )
        store.upsertJob(job)
        val reviews = (0 until 3).map { i -> MatchReview(FakeWorld.song("spotify", i), FakeWorld.song("deezer", 15 + i), 0.62 + i * 0.05) }
        val missing = listOf(FakeWorld.song("spotify", 5), FakeWorld.song("spotify", 6))
        store.addReport(
            SyncReport(
                id = "bulk-r", jobId = job.id, jobName = job.name, startedEpoch = System.currentTimeMillis(), durationMs = 1000,
                sourceCount = 8, unmatched = missing.map { it.toString() }, unmatchedTracks = missing, reviewTracks = reviews,
            ),
        )
        store.flush()
        return job.id
    }

    @Test
    fun pickSeveralThenRemoveKeepAndIgnore() {
        val jobId = seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        click(s(R.string.review_now, 5))
        waitText(s(R.string.review_section_uncertain, 3))
        waitText(s(R.string.review_select_hint))
        snapshot("bulk-1-review")

        // A long press picks the first loose match; the bar offers what fits it.
        val first = FakeWorld.song("spotify", 0).title
        compose.onAllNodes(hasClickAction() and hasText(first, substring = true)).onFirst().performTouchInput { longClick() }
        waitText(s(R.string.review_selected, 1))
        snapshot("bulk-2-one-picked")

        // The uncertain section's "Select all", then remove the three from Deezer.
        compose.onAllNodes(hasText(s(R.string.review_select_all))).onFirst().performClick()
        waitText(s(R.string.review_selected, 3))
        snapshot("bulk-3-section-picked")
        click(s(R.string.review_discard))
        waitText(s(R.string.review_discard_title, "Deezer"))
        snapshot("bulk-4-confirm")
        // The dialog's own button (the last one with that label).
        val buttons = compose.onAllNodes(hasClickAction() and hasText(s(R.string.review_discard)))
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        waitGone(s(R.string.review_section_uncertain, 3), ms = 20_000)
        assertEquals("Workout lost the three wrong matches", emptyList<String>(), FakeWorld.playlist("deezer", "Workout")!!.tracks.map { it.title })

        // Both not found, ignored together.
        compose.onAllNodes(hasClickAction() and hasText(FakeWorld.song("spotify", 5).title, substring = true)).onFirst().performTouchInput { longClick() }
        compose.onAllNodes(hasText(s(R.string.review_select_all))).onFirst().performClick()
        waitText(s(R.string.review_selected, 2))
        snapshot("bulk-5-not-found-picked")
        click(s(R.string.unmatched_ignore))
        waitText(s(R.string.unmatched_none), ms = 20_000)

        val job = store.data.jobs.first { it.id == jobId }
        assertEquals(setOf(0, 1, 2, 5, 6).map { FakeWorld.trackId("spotify", it) }.toSet(), job.ignoredSourceIds.toSet())
        assertTrue(store.data.reports.first { it.id == "bulk-r" }.let { it.reviewTracks.isEmpty() && it.unmatchedTracks.isEmpty() })
    }
}

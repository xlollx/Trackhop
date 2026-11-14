package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.ui.JOB_CARD_TAG
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** 1.18's Sync activity: figures that filter, problems first, and the run's summary at the end. */
@RunWith(AndroidJUnit4::class)
class LiveSheetFlowTest : FlowTest() {
    override val preview2: Boolean = true

    @Test
    fun theFiguresFilterAndTheSheetStaysWithTheSummary() {
        val job = SyncJob("job-live", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d1", "Road Trip"))
        store.update { it.copy(jobs = listOf(job)) }
        SyncState.start(job.id)
        try {
            SyncState.item(job.id, SyncState.LiveItem("Toto – Africa", "Toto – Africa (Live 1990)", SyncState.Outcome.FOUND, Track("a", "Africa", listOf("Toto")), uncertain = true))
            SyncState.item(job.id, SyncState.LiveItem("M83 – Midnight City", "M83 – Midnight City", SyncState.Outcome.FOUND, Track("m", "Midnight City", listOf("M83"))))
            SyncState.item(job.id, SyncState.LiveItem("Tiziano Ferro – Sere nere", null, SyncState.Outcome.NOT_FOUND, Track("s", "Sere nere", listOf("Tiziano Ferro"))))
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitFor("the card") { compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).onFirst().performClick()
            waitText(s(R.string.nf_review) + " 1")
            // "Found:" only when it reads otherwise.
            waitText(s(R.string.live_found, "Toto – Africa (Live 1990)"))
            assertFalse(exists(s(R.string.live_found, "M83 – Midnight City")))
            // A figure shows only its songs.
            click(s(R.string.nf_review) + " 1")
            waitFor("only the one to review") { !exists("M83 – Midnight City") && !exists("Tiziano Ferro – Sere nere") }
            click(s(R.string.nf_review) + " 1")
            waitText("Tiziano Ferro – Sere nere")
        } finally {
            store.update {
                it.copy(reports = listOf(SyncReport(
                    "r-live", job.id, job.name, System.currentTimeMillis() - 60_000, 30_000, added = 1,
                    unmatched = listOf("Tiziano Ferro – Sere nere"), unmatchedTracks = listOf(Track("s", "Sere nere", listOf("Tiziano Ferro"))),
                    reviewTracks = listOf(MatchReview(Track("a", "Africa", listOf("Toto")), Track("b", "Africa (Live 1990)", listOf("Toto")), 0.7)),
                )))
            }
            SyncState.finish(job.id)
        }
        // The run over, the sheet stays and says what it did.
        waitText(s(R.string.live_done_title, "Road Trip"))
        waitText(s(R.string.live_retry_n, 1))
        click(s(R.string.live_review_n, 2))
        waitFor("the review opened and the sheet closed") { !exists(s(R.string.live_done_title, "Road Trip")) }
    }
}

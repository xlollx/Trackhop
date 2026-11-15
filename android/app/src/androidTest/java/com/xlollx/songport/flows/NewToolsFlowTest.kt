package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tools of 1.18 from the owner's preview (Saved matches, Duplicate playlists, Mark as listened):
 * their sheets and forms stay as they were when Android makes the activity anew and after Home.
 */
@RunWith(AndroidJUnit4::class)
class NewToolsFlowTest : FlowTest() {
    override val preview2: Boolean = true

    private fun openTool(title: Int) {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        click(s(title))
    }

    @Test
    fun aSavedMatchsSheetStaysOpen() {
        val src = FakeWorld.song("spotify", 0)
        val dst = FakeWorld.song("deezer", 0)
        val job = SyncJob("j", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"), lastReportId = "r")
        store.update { d ->
            d.copy(
                jobs = listOf(job),
                reports = listOf(SyncReport(id = "r", jobId = "j", jobName = "Road Trip", startedEpoch = 1, durationMs = 1, reviewTracks = listOf(MatchReview(src, dst, 0.9)))),
                matchCache = mapOf("spotify:${src.id}>deezer" to dst.id),
            )
        }
        openTool(R.string.saved_matches_title)
        waitText(src.title, substring = true)
        click(src.title, substring = true)
        waitText(s(R.string.saved_matches_forget))
        remade()
        waitText(s(R.string.saved_matches_forget))
        homeAndBack()
        waitText(s(R.string.saved_matches_forget))
        click(s(R.string.saved_matches_forget))
        waitFor("the match forgotten") { store.data.matchCache.isEmpty() }
    }
}

package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.ui.Previews
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 1.18: a preview left half way goes on; the card says so and opens it again with its result. */
@RunWith(AndroidJUnit4::class)
class BackgroundPreviewFlowTest : FlowTest() {
    override val preview2: Boolean = true

    @After fun forget() = Previews.clear("job-bg")

    @Test
    fun aPreviewLeftHalfWayGoesOnAndTheCardOpensItAgain() {
        val job = SyncJob("job-bg", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", null, "Road Trip"))
        store.update { it.copy(jobs = listOf(job)) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.preview))
        // Left at once: the preview is not stopped.
        back()
        waitFor("the preview done with its page closed", 30_000) { Previews.state.value["job-bg"]?.plan != null }
        waitText(s(R.string.pill_preview_ready))
        click(s(R.string.pill_preview_ready))
        waitText(s(R.string.preview_add, 6), ms = 10_000)
        assertTrue("the preview writes nothing", FakeWorld.playlist("deezer", "Road Trip") == null)
        // Run now starts from it and leaves no preview behind.
        click(s(R.string.run_now))
        waitFor("the run's report", 60_000) { store.data.reports.any { it.jobId == job.id && !it.partial } }
        assertTrue(Previews.state.value["job-bg"] == null)
    }
}

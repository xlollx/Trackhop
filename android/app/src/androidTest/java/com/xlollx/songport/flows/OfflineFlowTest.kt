package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.net.Connectivity
import com.xlollx.songport.sync.OfflineQueue
import com.xlollx.songport.ui.OFFLINE_BANNER_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone without internet, simulated through the app's connectivity source (never airplane mode):
 * the Syncs header says so, a sync run by hand waits for the connection instead of failing (its card
 * says "Waiting for internet", the snackbar's Cancel takes it out again), the other tabs show a slim
 * banner, and everything goes back by itself with a "Back online".
 */
@RunWith(AndroidJUnit4::class)
class OfflineFlowTest : FlowTest() {
    @After fun online() {
        Connectivity.simulate(true)
        OfflineQueue.cancel(app, JOB)
    }

    private fun seed() {
        store.upsertJob(SyncJob(JOB, "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", null, "Road Trip copy")))
        store.flush()
    }

    @Test fun aRunWithoutInternetWaitsForItAndCanBeCancelled() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        Connectivity.simulate(false)
        // The status line under the header says it, in place of how the syncs stand.
        waitText(s(R.string.offline_banner))

        clickDesc(s(R.string.run_now))
        waitText(s(R.string.offline_queued, "Road Trip"))
        waitText(s(R.string.pill_waiting_network))
        assertTrue(JOB in OfflineQueue.waiting.value)
        // Nothing ran, nothing failed.
        assertTrue(store.data.reports.none { it.jobId == JOB })
        assertFalse(exists(s(R.string.pill_error)))
        // Asked again, still offline: it keeps waiting, a second tap is no way round.
        clickDesc(s(R.string.run_now))
        compose.waitForIdle()
        assertTrue(JOB in OfflineQueue.waiting.value)
        assertTrue(JOB !in com.xlollx.songport.sync.SyncState.running.value)
        assertTrue(store.data.reports.none { it.jobId == JOB })
        waitText(s(R.string.offline_queued, "Road Trip"))

        // Cancel: it no longer waits.
        click(s(R.string.cancel))
        waitFor("the run taken out of the queue") { JOB !in OfflineQueue.waiting.value }
        waitGone(s(R.string.pill_waiting_network))

        // Back online: the line is the syncs' again, and a short "Back online" says so.
        Connectivity.simulate(true)
        waitText(s(R.string.offline_back))
        waitGone(s(R.string.offline_banner))
    }

    @Test fun theOtherTabsShowASlimBannerThatGoesByItself() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.tab_accounts))
        assertTrue(compose.onAllNodes(hasTestTag(OFFLINE_BANNER_TAG)).fetchSemanticsNodes().isEmpty())
        Connectivity.simulate(false)
        waitNode("the offline banner", hasTestTag(OFFLINE_BANNER_TAG))
        assertTrue(exists(s(R.string.net_offline)))
        Connectivity.simulate(true)
        waitFor("the banner gone") { compose.onAllNodes(hasTestTag(OFFLINE_BANNER_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    private companion object {
        const val JOB = "offline-road"
    }
}

package com.xlollx.songport.flows

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.ui.SELECTION_TICK_TAG
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selection of several syncs (#68) ends only when the user ends it: its X, back, the last tick
 * taken off, or an action that finishes it. Not by a scroll, a sync starting or ending under it, or
 * the list being written again by a run (owner's report on 2.1.0, minimal tiles: after two were
 * ticked the normal header seemed to come back by itself).
 */
@RunWith(AndroidJUnit4::class)
class SelectionKeptFlowTest : FlowTest() {
    private val n = 12

    private fun seed(minimal: Boolean) {
        store.update { d ->
            d.copy(
                jobs = (1..n).map { i -> SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", "p$i", "Source $i"), PlaylistRef("deezer", "d$i", "Target $i")) },
                settings = d.settings.copy(minimalSyncs = minimal),
            )
        }
        store.flush()
    }

    private fun tick(i: Int) = compose.onNode(hasTestTag(SELECTION_TICK_TAG) and hasContentDescription("Sync number $i"))
    private fun selectedCount(k: Int) = plural(R.plurals.files_selected, k, k)
    private fun scroll(dy: Float) {
        compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        compose.waitForIdle()
    }

    /** Two syncs ticked from the first one's menu, then everything that goes on under a selection without the user ending it. */
    private fun twoTickedStayTicked() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        // Select, from the first sync's menu (a card's "⋮"; a tile's is an accessibility action of the tile, and what a hold opens).
        if (store.data.settings.minimalSyncs) {
            val label = s(R.string.more_actions)
            val tile = compose.onAllNodes(hasTestTag(com.xlollx.songport.ui.JOB_TILE_TAG) and androidx.compose.ui.test.hasText("Sync number 1")).onFirst().fetchSemanticsNode()
            val menu = tile.config[SemanticsActions.CustomActions].first { it.label == label }
            instr.runOnMainSync { menu.action() }
            compose.waitForIdle()
        } else clickDesc(s(R.string.more_actions))
        click(s(R.string.files_select))
        waitText(selectedCount(1))
        tap(tick(2), "Sync number 2")
        waitText(selectedCount(2))

        // A scroll to the end of the list and back.
        scroll(6000f)
        waitText(selectedCount(2))
        scroll(-6000f)
        waitText(selectedCount(2))

        // A sync starts, goes on and ends: one of the ticked ones, and another.
        try {
            SyncState.start("job-2"); SyncState.start("job-5")
            SyncState.progress("job-2", Progress(Progress.Step.MATCHING, 3, 8))
            waitFor("two syncs running") { SyncState.running.value.size == 2 }
            compose.waitForIdle()
            waitText(selectedCount(2))
        } finally {
            SyncState.finish("job-2"); SyncState.finish("job-5")
        }
        compose.waitForIdle()
        waitText(selectedCount(2))

        // The run writes the list again: a report, and the sync's last run.
        val now = System.currentTimeMillis()
        store.update { d ->
            d.copy(
                reports = d.reports + SyncReport(id = "r2", jobId = "job-2", jobName = "Sync number 2", startedEpoch = now, durationMs = 1_000, sourceCount = 8, targetCount = 8, added = 1),
                jobs = d.jobs.map { if (it.id == "job-2") it.copy(lastRunEpoch = now, lastReportId = "r2") else it },
            )
        }
        waitFor("the run written") { store.data.jobs.first { it.id == "job-2" }.lastReportId == "r2" }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        waitText(selectedCount(2))
        tick(1).assertIsOn()
        tick(2).assertIsOn()

        // The activity made anew: the same two.
        remade()
        waitText(selectedCount(2))

        // And it ends when the user ends it.
        clickDesc(s(R.string.cancel))
        waitGone(selectedCount(2))
        assertTrue("no tick left", compose.onAllNodes(hasTestTag(SELECTION_TICK_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theSelectionOutlivesAScrollARunAndTheListWrittenAgainOnTiles() {
        seed(minimal = true)
        twoTickedStayTicked()
    }

    @Test
    fun theSelectionOutlivesAScrollARunAndTheListWrittenAgainOnCards() {
        seed(minimal = false)
        twoTickedStayTicked()
    }
}

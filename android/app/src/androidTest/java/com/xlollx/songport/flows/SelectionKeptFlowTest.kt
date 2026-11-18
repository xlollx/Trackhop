package com.xlollx.songport.flows

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
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
 * Selecting several syncs (#68), made easier in 2.1.1 as the preview approved on 18/11 shows it: a
 * tile held still opens its menu, with Select first and set apart; a "Select syncs" button beside
 * the view's toggle starts with none ticked; while selecting a tap ticks and a hold ticks up to the
 * last one ticked. And selecting ends only when the user ends it (its X, back, an action that
 * finishes it): not by a scroll, a sync starting or ending under it, the list written again by a
 * run, nor the last tick taken off (owner's report on 2.1.0, minimal tiles: after two were ticked
 * the normal header came back by itself).
 */
@RunWith(AndroidJUnit4::class)
class SelectionKeptFlowTest : FlowTest() {
    private val n = 12

    private fun seed(minimal: Boolean) {
        store.update { d ->
            d.copy(
                jobs = (1..n).map { i -> SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", "p$i", "Source $i"), PlaylistRef("deezer", "d$i", "Target $i")) },
                // Small cards: four of them fit a phone's screen, for the hold that ticks a range.
                settings = d.settings.copy(minimalSyncs = minimal, cardSize = com.xlollx.songport.ui.CardSize.SMALL.key),
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

    private fun tile(i: Int) = compose.onAllNodes(hasTestTag(com.xlollx.songport.ui.JOB_TILE_TAG) and androidx.compose.ui.test.hasText("Sync number $i")).onFirst()
    private fun selectButton() = compose.onAllNodes(hasTestTag(com.xlollx.songport.ui.SELECT_SYNCS_TAG) and androidx.compose.ui.test.hasClickAction()).onFirst()
    private fun newSyncOffered() = compose.onAllNodes(hasContentDescription(s(R.string.new_sync)) or androidx.compose.ui.test.hasText(s(R.string.new_sync))).fetchSemanticsNodes().isNotEmpty()

    /** A tile held: its menu, as ever (the owner likes it so), with Select first and what it leads to; a tap on it and that sync is ticked. */
    @Test
    fun aTileHeldOpensItsMenuWithSelectFirst() {
        seed(minimal = true)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        tile(2).performTouchInput { longClick() }
        waitText(s(R.string.bulk_select_hint))
        // The menu's other entries are all still there, under it.
        for (id in listOf(R.string.preview, R.string.edit, R.string.job_style, R.string.sync_menu_favorite, R.string.sync_menu_move_to, R.string.delete)) assertTrue(s(id), exists(s(id)))
        val top = { text: String -> compose.onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().minOf { it.positionInWindow.y } }
        assertTrue("Select is the menu's first entry", top(s(R.string.files_select)) < top(s(R.string.preview)))
        snapshot("select-1-menu")
        click(s(R.string.files_select))
        waitText(selectedCount(1))
        tick(2).assertIsOn()
        // Then a tap on the others adds them.
        tap(tick(3), "Sync number 3")
        waitText(selectedCount(2))
    }

    /** The button beside the view's toggle: selecting with none ticked, in both views, kept when Android makes the activity anew. */
    private fun theButtonStartsWithNoneTicked() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        assertTrue("its name for a screen reader", compose.onAllNodes(hasContentDescription(s(R.string.bulk_select_syncs))).fetchSemanticsNodes().isNotEmpty())
        tap(selectButton(), "Select syncs")
        waitText(s(R.string.bulk_select_tap))
        waitText(s(R.string.tracks_select_shown))
        assertTrue("no tick yet", compose.onAllNodes(hasTestTag(SELECTION_TICK_TAG) and androidx.compose.ui.test.isOn()).fetchSemanticsNodes().isEmpty())
        assertTrue("nothing to act on yet: no bar", !exists(s(R.string.bulk_removals)))
        assertTrue("New sync is not offered while selecting", !newSyncOffered())
        snapshot("select-2-none-ticked")

        // Selecting with nothing ticked outlives the activity made anew.
        remade()
        waitText(s(R.string.bulk_select_tap))

        // A tap ticks, a second one unticks: still selecting, with none ticked (it ended here until 2.1.0).
        tap(tick(1), "Sync number 1")
        waitText(selectedCount(1))
        waitText(s(R.string.bulk_removals))
        tap(tick(1), "Sync number 1")
        waitText(s(R.string.bulk_select_tap))
        tick(1).assertIsOff()

        // A hold ticks from the last one ticked up to the one held, in the order shown.
        tap(tick(1), "Sync number 1")
        waitText(selectedCount(1))
        tick(4).performTouchInput { longClick() }
        waitText(selectedCount(4))
        for (i in 1..4) tick(i).assertIsOn()
        // And with the ticks, across the activity made anew.
        remade()
        waitText(selectedCount(4))

        // Back ends it.
        androidx.test.espresso.Espresso.pressBack()
        waitGone(selectedCount(4))
        waitFor("New sync is back") { newSyncOffered() }
        assertTrue(!exists(s(R.string.bulk_select_tap)))
    }

    @Test
    fun theSelectButtonStartsWithNoneTickedAndAHoldTicksARangeOnTiles() {
        seed(minimal = true)
        theButtonStartsWithNoneTicked()
    }

    @Test
    fun theSelectButtonStartsWithNoneTickedAndAHoldTicksARangeOnCards() {
        seed(minimal = false)
        theButtonStartsWithNoneTicked()
    }

    /** Option B of the preview: a card held is selected straight away, as before. */
    @Test
    fun aCardHeldIsSelectedStraightAway() {
        seed(minimal = false)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 2")
        compose.onAllNodes(androidx.compose.ui.test.hasText("Sync number 2")).onFirst().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(12f, height / 2f)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200); up()
        }
        compose.waitForIdle()
        waitText(selectedCount(1))
        tick(2).assertIsOn()
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

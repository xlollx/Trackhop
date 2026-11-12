package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.sync.LookupCaps
import com.xlollx.songport.ui.SharedUi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Work under way is not cut off by the screen turning (nor by the activity being made anew), and
 * back on a form with something typed asks before dropping it, closing the form, not the screen
 * behind it.
 */
@RunWith(AndroidJUnit4::class)
class RotationBackFlowTest : FlowTest() {
    /** 2.0's features, hidden in the build: shown here with the developer's preview. */
    override val preview2: Boolean = true


    @Test
    fun aRecordsLookupStartedBeforeTheScreenTurnsStillShowsItsResults() {
        // Reading the playlist waits until the test lets it go: the lookup is under way while the phone turns.
        val hold = CompletableDeferred<Unit>()
        FakeWorld.hold = hold
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_records_title))
        clickTile(s(R.string.discover_records_title))
        waitText(s(R.string.records_find))
        // The playlist field opens the picker's sheet: the service, then its playlist.
        waitText(s(R.string.editor_playlist))
        click(s(R.string.editor_playlist))
        waitFor("the picker's services") { compose.onAllNodes(hasClickAction() and hasText("Spotify")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasClickAction() and hasText("Spotify")).onFirst().performClick()
        waitText("Road Trip")
        click("Road Trip")
        click(s(R.string.records_find))
        compose.waitForIdle()

        rotate(UiAutomation.ROTATION_FREEZE_90)
        // And a remade activity too (as a change of language makes it): the lookup lives outside it.
        val before = main()
        instr.runOnMainSync { before.recreate() }
        waitForNewMain(before)
        compose.waitForIdle()
        // The new screen still shows the lookup under way, with its Stop.
        waitText(s(R.string.search_stop))

        hold.complete(Unit)
        // Once the playlist's artists are read, the button says how many it looks up; it is free again when the lookup ends
        // (the artists found are shown while it runs, so their results alone do not say it ended).
        val labels = setOf(s(R.string.records_find)) + (1..60).map { plural(R.plurals.records_find_for, it, it) } +
            listOf(LookupCaps.RECORDS_ANON, LookupCaps.RECORDS_TOKEN).map { s(R.string.records_find_top, it) }
        val search = SemanticsMatcher("a search button label") { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text in labels } }
        waitFor("the search button free again", ms = 120_000) { compose.onAllNodes(hasClickAction() and isEnabled() and search).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("the lookup ended", exists(s(R.string.search_stop)))
        // The network is closed in the tests: Discogs "does not answer", which the results say per artist.
        assertTrue("the lookup's results are shown", exists(s(R.string.records_discogs_down), substring = true))
    }

    private fun openCreateGroup() {
        instr.runOnMainSync { SharedUi.request.value = SharedUi.Request.List }
        waitText(s(R.string.shared_title))
        click(s(R.string.shared_create))
        waitText(s(R.string.shared_name))
    }

    private fun pressBack() {
        runCatching { Espresso.closeSoftKeyboard() }
        Espresso.pressBack()
        compose.waitForIdle()
    }

    @Test
    fun backOnAFilledCreateGroupDialogAsksBeforeDroppingIt() {
        launch()
        openCreateGroup()
        compose.onAllNodes(hasSetTextAction() and hasText(s(R.string.shared_name))).onFirst().performTextInput("Friday friends")
        waitText("Friday friends")

        pressBack()
        waitText(s(R.string.discard_changes_title))
        assertTrue("the form is still there under the question", exists("Friday friends"))

        // Keep editing: the form as it was.
        click(s(R.string.discard_changes_keep))
        waitGone(s(R.string.discard_changes_title))
        assertTrue(exists("Friday friends"))

        // Discard: the form closes, the shared playlists' screen behind it stays.
        pressBack()
        waitText(s(R.string.discard_changes_title))
        click(s(R.string.discard_changes_discard))
        waitGone(s(R.string.shared_name))
        assertTrue("back closed the dialog, not the screen", exists(s(R.string.shared_title)))
        assertFalse(exists("Friday friends"))

        // Nothing typed: back closes it at once, without asking.
        click(s(R.string.shared_create))
        waitText(s(R.string.shared_name))
        pressBack()
        waitGone(s(R.string.shared_name))
        assertFalse(exists(s(R.string.discard_changes_title)))
        assertTrue(exists(s(R.string.shared_title)))
    }
}

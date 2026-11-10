package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.Demo
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wide windows and the phone on its side: the tabs as a bar at the bottom while the phone is
 * upright and as a rail at the side once it is turned (the same layout a tablet gets), the sync
 * cards in two columns where the window is wide enough, and what is being typed in a dialog, a
 * form or the sync editor still there after the turn.
 */
@RunWith(AndroidJUnit4::class)
class TabletLayoutFlowTest : FlowTest() {
    private val tabs by lazy { listOf(R.string.tab_syncs, R.string.tab_accounts, R.string.tab_discover, R.string.tab_tools, R.string.tab_settings) }

    private fun seed(n: Int) = store.update { d ->
        d.copy(jobs = (1..n).map { i -> SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d$i", "Road Trip $i")) })
    }

    private fun navItem(label: String) = compose.onAllNodes(hasClickAction() and hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).onFirst()

    private fun tabNodes(): List<SemanticsNode> = tabs.map { navItem(s(it)).fetchSemanticsNode() }

    private fun card(i: Int): SemanticsNode = compose.onAllNodes(hasText("Sync number $i")).onFirst().fetchSemanticsNode()

    /** The window's shape as the app works it out, from the activity on screen now. */
    private fun layoutNow(): WindowLayout = main().resources.configuration.let { WindowLayout.of(it.screenWidthDp, it.screenHeightDp) }

    private fun assertBottomBar() {
        val nodes = tabNodes()
        assertEquals("the five tabs side by side", 1, nodes.map { it.boundsInRoot.top }.distinct().size)
        assertEquals(5, nodes.map { it.boundsInRoot.left }.distinct().size)
        assertTrue("the bar is under the cards", nodes.first().boundsInRoot.top > card(1).boundsInRoot.top)
    }

    private fun assertRail() {
        val nodes = tabNodes()
        assertEquals("the five tabs one above the other", 1, nodes.map { it.boundsInRoot.left }.distinct().size)
        assertEquals(5, nodes.map { it.boundsInRoot.top }.distinct().size)
        assertTrue("the rail is left of the cards", nodes.first().boundsInRoot.right <= card(1).boundsInRoot.left)
    }

    @Test
    fun bottomBarUprightRailAndColumnsOnItsSide() {
        seed(4)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        assertEquals(WindowLayout.Phone, layoutNow())
        assertBottomBar()
        // Upright: one column, the cards one under the other.
        assertEquals(card(1).positionInRoot.x, card(2).positionInRoot.x, 1f)
        assertTrue(card(2).positionInRoot.y > card(1).positionInRoot.y)

        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText("Sync number 1")
        val layout = layoutNow()
        assertTrue("a phone on its side gets the rail", layout.rail)
        assertRail()
        snapshot("tablet-rail-syncs")
        if (layout.syncColumns == 2) {
            // Wide enough for two: the first two cards share a row.
            assertEquals(card(1).positionInRoot.y, card(2).positionInRoot.y, 1f)
            assertTrue(card(2).positionInRoot.x > card(1).boundsInRoot.right - 1f)
        } else {
            assertEquals(card(1).positionInRoot.x, card(2).positionInRoot.x, 1f)
        }
        // The rail works as the bar did: every tab named, a tap goes there, the floating button still in reach.
        assertTrue(exists(s(R.string.new_sync)))
        for (tab in tabs) {
            navItem(s(tab)).performClick()
            compose.waitForIdle()
            navItem(s(tab)).assertIsSelected()
            assertRail0()
        }
        navItem(s(R.string.tab_syncs)).performClick()
        waitText("Sync number 1")

        rotate(UiAutomation.ROTATION_FREEZE_0)
        waitText("Sync number 1")
        assertBottomBar()
    }

    /** The rail on a tab with no sync cards to compare it with: the tabs one above the other at the left edge. */
    private fun assertRail0() {
        val nodes = tabNodes()
        assertEquals(1, nodes.map { it.boundsInRoot.left }.distinct().size)
        assertEquals(5, nodes.map { it.boundsInRoot.top }.distinct().size)
    }

    @Test
    fun pagesKeepAReadableWidthOnItsSide() {
        seed(1)
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitNode("the settings tab", hasText(s(R.string.tab_settings)))
        val density = main().resources.displayMetrics.density
        val max = WindowLayout.READABLE_CONTENT.value * density
        // The page's scrolling column is no wider than the readable width, whatever the window's.
        val scrollers = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        assertTrue("a scrolling page on screen", scrollers.isNotEmpty())
        val widest = scrollers.maxOf { it.boundsInRoot.width }
        assertTrue("content $widest px wider than the readable $max px", widest <= max + 1f)
    }

    @Test
    fun aDialogBeingFilledInSurvivesARotation() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.files_title))
        click(s(R.string.files_title))
        click(s(R.string.paste_list))
        waitText(s(R.string.paste_list_hint))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_name))).performTextReplacement("Pasted")
        val lines = (0 until 3).joinToString("\n") { "${Demo.SONGS[it].artist} - ${Demo.SONGS[it].title}" }
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_text))).performTextReplacement(lines)

        rotate(UiAutomation.ROTATION_FREEZE_90)
        // Still open, with both fields as they were typed.
        waitText(s(R.string.paste_list_hint))
        waitNode("the name typed", hasSetTextAction() and hasText("Pasted"))
        waitNode("the list typed", hasSetTextAction() and hasText(Demo.SONGS[0].title, substring = true))

        rotate(UiAutomation.ROTATION_FREEZE_0)
        waitNode("the name typed", hasSetTextAction() and hasText("Pasted"))
        click(s(R.string.import_action))
        waitText(s(R.string.csv_imported, 3))
    }

    @Test
    fun aLinkPastedInTheEditorSurvivesARotation() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_source))
        click(s(R.string.editor_source_link))
        val link = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        compose.onAllNodes(hasSetTextAction() and hasText(s(R.string.link_paste))).onFirst().performTextReplacement(link)
        waitNode("the link typed", hasSetTextAction() and hasText(link))

        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText(s(R.string.editor_title_new))
        log("link field after the turn: " + compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.EditableText)?.text to it.config.getOrNull(SemanticsProperties.Focused) })
        waitNode("the link typed", hasSetTextAction() and hasText(link))

        rotate(UiAutomation.ROTATION_FREEZE_0)
        waitNode("the link typed", hasSetTextAction() and hasText(link))
    }

    /** The parts of a node that show are all of it: nothing cut by its scroller or the dialog's edge. */
    private fun whole(n: SemanticsNode) = n.boundsInRoot.height >= n.size.height - 1 && n.boundsInRoot.width >= n.size.width - 1

    @Test
    fun theStyleDialogOnItsSideHasThePreviewBesideTheColours() {
        seed(1)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText("Sync number 1")
        assertTrue("a low window", layoutNow().short)
        clickDesc(s(R.string.more_actions))
        click(s(R.string.job_style))
        waitNode("the colour swatches", hasContentDescription(s(R.string.job_color_default)))
        snapshot("style-dialog-landscape")
        // The choices are a column of their own, to the right of the card's preview, wider than Material's narrow dialog.
        val choices = compose.onNode(androidx.compose.ui.test.hasTestTag(com.xlollx.songport.ui.STYLE_CHOICES_TAG)).fetchSemanticsNode()
        val density = main().resources.displayMetrics.density
        assertTrue("the choices start right of the preview (${choices.boundsInRoot.left})", choices.boundsInRoot.left > 200 * density)
        assertTrue("the dialog is wide (${choices.boundsInRoot.right / density} dp)", choices.boundsInRoot.right > 560 * density)
        // Every ready colour shows whole without scrolling, and so do Save and Cancel.
        for (name in listOf(R.string.job_color_default, R.string.color_teal, R.string.color_pink)) {
            val swatch = compose.onAllNodes(hasContentDescription(s(name)) and hasClickAction()).onFirst()
            swatch.assertIsDisplayed()
            assertTrue("${s(name)} is cut", whole(swatch.fetchSemanticsNode()))
        }
        for (button in listOf(R.string.save, R.string.cancel)) {
            val b = compose.onAllNodes(hasText(s(button)) and hasClickAction()).onFirst()
            b.assertIsDisplayed()
            assertTrue("${s(button)} is cut", whole(b.fetchSemanticsNode()))
        }
        // And it works as upright: a colour picked and saved.
        click(s(R.string.color_teal))
        click(s(R.string.save))
        waitFor("teal stored") { store.data.jobs.single().color == "teal" }

        // Upright again: the one-column dialog, no side-by-side column.
        rotate(UiAutomation.ROTATION_FREEZE_0)
        waitText("Sync number 1")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.job_style))
        waitNode("the colour swatches", hasContentDescription(s(R.string.job_color_default)))
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag(com.xlollx.songport.ui.STYLE_CHOICES_TAG)).fetchSemanticsNodes().isEmpty())
        click(s(R.string.cancel))
    }
}

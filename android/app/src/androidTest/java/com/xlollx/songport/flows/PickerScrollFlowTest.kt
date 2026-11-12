package com.xlollx.songport.flows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.ui.PICKER_LIST_TAG
import com.xlollx.songport.ui.PICKER_TITLE_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picker's sheet with a long list (120 playlists), under a finger: a drag that starts slowly
 * (as a finger does: a few pixels at a time) scrolls the list every time, also right after a fling;
 * a pull down at the list's top and a fling that reaches the top leave the sheet where it is, open.
 * The clock is the test's, so the sheet is looked at while the finger is still down and frame by
 * frame after it lifts.
 */
@RunWith(AndroidJUnit4::class)
class PickerScrollFlowTest : FlowTest() {

    @After
    fun clockBack() { compose.mainClock.autoAdvance = true }

    private val density get() = app.resources.displayMetrics.density
    private fun list() = compose.onNode(hasTestTag(PICKER_LIST_TAG), useUnmergedTree = true)
    private fun title(): Rect = compose.onNode(hasTestTag(PICKER_TITLE_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun scrolled(): Float = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun openLongList() {
        val spotify = FakeWorld.playlists.getValue("spotify")
        repeat(117) { i -> spotify += FakeWorld.FakePlaylist("long-$i", "Mixtape %03d".format(i), FakeWorld.songs("spotify", 0 until 3)) }
        store.update {
            it.copy(jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"))))
        }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
        openSourcePicker()
        waitText("Mixtape 000")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        assertEquals("the list opens at its top", 0f, scrolled())
    }

    /** A finger down on the list that starts slowly ([slow] moves of 2 dp, under the touch slop), then goes [dy] dp further in moves of 12 dp. It stays down. */
    private fun drag(dy: Int, slow: Int = 10) {
        val dir = if (dy < 0) -1f else 1f
        list().performTouchInput {
            down(Offset(centerX, if (dy < 0) height * 0.75f else height * 0.2f))
            repeat(slow) { moveBy(Offset(0f, dir * 2 * density)) }
            repeat(kotlin.math.abs(dy) / 12) { moveBy(Offset(0f, dir * 12 * density)) }
        }
    }

    private fun lift() = list().performTouchInput { up() }

    private fun assertSheetStill(was: Rect, whenWhat: String) {
        val now = runCatching { title() }.getOrNull() ?: throw AssertionError("the sheet closed $whenWhat")
        assertEquals("the sheet moved $whenWhat", was, now)
    }

    @Test
    fun aSlowDragScrollsTheListEveryTimeAlsoRightAfterAFling() {
        openLongList()
        val sheet = title()
        var at = scrolled()
        repeat(5) { i ->
            drag(-180)
            compose.mainClock.advanceTimeByFrame()
            val dragged = scrolled()
            assertTrue("drag ${i + 1} up from ${if (i == 0) "the top" else "a fling"} did not scroll the list (at $at, then $dragged)", dragged > at)
            lift()
            // The fling it leaves is still running when the finger comes down again.
            compose.mainClock.advanceTimeBy(48)
            at = scrolled()
            assertSheetStill(sheet, "under drag ${i + 1}")
        }
        // Back at the top, at rest: the first drag again, as in the owner's recording.
        compose.mainClock.advanceTimeBy(3_000)
        list().performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0f, scrolled())
        // Up and down without lifting, slowly all the way: the list follows.
        list().performTouchInput {
            down(Offset(centerX, height * 0.6f))
            repeat(40) { moveBy(Offset(0f, -3 * density)) }
        }
        compose.mainClock.advanceTimeByFrame()
        val up = scrolled()
        assertTrue("a slow drag up from the top, at rest, did not scroll the list", up > 0f)
        list().performTouchInput { repeat(20) { moveBy(Offset(0f, 3 * density)) } }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the list did not follow the finger back down", scrolled() < up)
        lift()
        compose.mainClock.advanceTimeBy(2_000)
        assertSheetStill(sheet, "after the drags")
    }

    @Test
    fun aPullDownAtTheTopLeavesTheSheetInPlace() {
        openLongList()
        val sheet = title()
        repeat(3) { i ->
            drag(240)
            compose.mainClock.advanceTimeByFrame()
            assertSheetStill(sheet, "under pull ${i + 1} down at the list's top")
            lift()
            repeat(40) { compose.mainClock.advanceTimeByFrame(); assertSheetStill(sheet, "after pull ${i + 1} down at the list's top") }
            compose.mainClock.advanceTimeBy(1_000)
            assertSheetStill(sheet, "after pull ${i + 1} down at the list's top")
            assertEquals(0f, scrolled())
        }
        // The list still scrolls after it, at once.
        drag(-180)
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the list did not scroll after the pulls", scrolled() > 0f)
        lift()
        compose.mainClock.advanceTimeBy(3_000)
        assertSheetStill(sheet, "after the pulls")
        // A pull on the sheet's title is not a way to move it either: ✕, a tap above it or Back close it.
        compose.onNode(hasTestTag(PICKER_TITLE_TAG), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 2 * density)) }
            repeat(15) { moveBy(Offset(0f, 12 * density)) }
        }
        compose.mainClock.advanceTimeByFrame()
        assertSheetStill(sheet, "under a pull on its title")
        compose.onNode(hasTestTag(PICKER_TITLE_TAG), useUnmergedTree = true).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertSheetStill(sheet, "after a pull on its title")
    }

    @Test
    fun aFlingToTheTopLeavesTheSheetInPlaceAndClosesWithItsCross() {
        openLongList()
        val sheet = title()
        repeat(2) {
            list().performScrollToIndex(30)
            compose.mainClock.advanceTimeBy(500)
            assertTrue(scrolled() > 0f)
            // A fast flick down: 400 dp in 5 moves, which carries the list to its top with speed to spare.
            list().performTouchInput {
                down(Offset(centerX, height * 0.2f))
                repeat(5) { moveBy(Offset(0f, 80 * density)) }
                up()
            }
            repeat(240) { compose.mainClock.advanceTimeByFrame(); assertSheetStill(sheet, "under a fling to the list's top") }
            assertEquals("the fling reached the top", 0f, scrolled())
            // And the list answers at once after it.
            drag(-180)
            compose.mainClock.advanceTimeByFrame()
            assertTrue("the list did not scroll right after a fling to its top", scrolled() > 0f)
            lift()
            compose.mainClock.advanceTimeBy(3_000)
            assertSheetStill(sheet, "after the fling")
        }
        compose.mainClock.autoAdvance = true
        clickDesc(s(R.string.ai_done_close))
        waitFor("the sheet closed by its cross") { compose.onAllNodes(hasTestTag(PICKER_TITLE_TAG), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}

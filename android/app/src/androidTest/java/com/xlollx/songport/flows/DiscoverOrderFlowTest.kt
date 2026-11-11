package com.xlollx.songport.flows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.ui.DISCOVER_TILE_TAG
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The Discover cards held and dragged: the order is saved, and "Reset order" brings the default back. */
@RunWith(AndroidJUnit4::class)
class DiscoverOrderFlowTest : FlowTest() {

    private fun tile(title: Int) = compose.onAllNodes(hasTestTag(DISCOVER_TILE_TAG) and hasText(s(title))).onFirst()

    private fun at(title: Int): Offset = tile(title).fetchSemanticsNode().positionInRoot

    @Test
    fun aCardDraggedAcrossItsRowIsSavedThereAndResetBringsTheDefaultBack() {
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_releases_title))
        // New releases first, Universal link beside it.
        waitFor("New releases and Universal link on one row") { at(R.string.discover_releases_title).y == at(R.string.discover_link_title).y }
        val dx = at(R.string.discover_link_title).x - at(R.string.discover_releases_title).x
        tile(R.string.discover_releases_title).performTouchInput {
            down(Offset(width * 0.3f, height / 2f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveBy(Offset(1f, 0f))
        }
        compose.waitForIdle()
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        repeat(10) {
            list.performTouchInput { repeat(3) { advanceEventTime(16); moveBy(Offset(dx / 30f, 0f)) } }
            compose.waitForIdle()
        }
        list.performTouchInput { advanceEventTime(100); up() }
        compose.waitForIdle()
        waitFor("the order saved") { store.data.settings.discoverOrder.take(2) == listOf("link", "releases") }
        // The default again, from the button under the cards.
        clickTile(s(R.string.discover_order_reset))
        waitFor("the order reset") { store.data.settings.discoverOrder.isEmpty() }
        assertEquals(emptyList<String>(), store.data.settings.discoverOrder)
    }
}

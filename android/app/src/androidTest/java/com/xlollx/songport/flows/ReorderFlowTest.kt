package com.xlollx.songport.flows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cards held and dragged at the end of the list, where the upper card is the list's first visible
 * item, half under the Sync-all pill: the drop lands where it was let go (it used to snap back).
 */
@RunWith(AndroidJUnit4::class)
class ReorderFlowTest : FlowTest() {

    private fun seed(n: Int) = store.update { d ->
        d.copy(jobs = (1..n).map { i -> SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d$i", "Road Trip $i")) })
    }

    private fun toTheEnd() {
        repeat(4) { compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 5000f) } }
        compose.waitForIdle()
    }

    /** Holds the card titled [name] and drags it by [dy] pixels in small steps, then lets go. */
    private fun drag(name: String, dy: Float) {
        compose.onAllNodes(hasText(name)).onFirst().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            val steps = 30
            repeat(steps) { moveBy(Offset(0f, dy / steps)); advanceEventTime(16) }
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
    }

    private fun order() = store.data.jobs.map { it.id }

    @Test
    fun theUpperCardAtTheEndGoesLastAndBackUp() {
        seed(5)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        toTheEnd()
        waitText("Sync number 5")
        val height = compose.onAllNodes(hasText("Sync number 4")).onFirst().fetchSemanticsNode().let { n ->
            var p = n; repeat(6) { p.parent?.let { p = it } }; p.boundsInRoot.height
        }.coerceAtLeast(400f)
        drag("Sync number 4", height * 1.2f)
        waitFor("job-4 last") { order().last() == "job-4" }
        assertEquals(listOf("job-1", "job-2", "job-3", "job-5", "job-4"), order())
        toTheEnd()
        drag("Sync number 4", -height * 1.2f)
        waitFor("job-4 back before job-5") { order().takeLast(2) == listOf("job-4", "job-5") }
    }

    // The case reported on 07/11: two cards swapped once (here by the store, as the menu does) while
    // the list is open, then dragged back to the order they had when the list was drawn: kept.
    @Test
    fun aDropBackToAnEarlierOrderIsKept() {
        seed(5)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        store.moveJob("job-5", 3)
        waitFor("job-5 before job-4") { order().takeLast(2) == listOf("job-5", "job-4") }
        toTheEnd()
        waitText("Sync number 5")
        val height = compose.onAllNodes(hasText("Sync number 5")).onFirst().fetchSemanticsNode().let { n ->
            var p = n; repeat(6) { p.parent?.let { p = it } }; p.boundsInRoot.height
        }.coerceAtLeast(400f)
        drag("Sync number 5", height * 1.2f)
        waitFor("job-5 last again") { order().last() == "job-5" }
        assertEquals(listOf("job-1", "job-2", "job-3", "job-4", "job-5"), order())
    }
}

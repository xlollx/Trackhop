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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cards held and dragged at the end of the list, where the upper card on screen is the list's
 * first visible item, half under the Sync-all pill (07/11 report): the card dragged down past the
 * next one stays there instead of snapping back, and the last card dragged up lands above it.
 */
@RunWith(AndroidJUnit4::class)
class ReorderFlowTest : FlowTest() {

    private val n = 6

    private fun seed() = store.update { d ->
        d.copy(jobs = (1..n).map { i -> SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d$i", "Road Trip $i")) })
    }

    private fun toTheEnd() {
        repeat(4) { compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 5000f) } }
        compose.waitForIdle()
    }

    private fun order() = store.data.jobs.map { it.id }

    /** Where the list shows its first card: below the app's header (its title). */
    private fun listTop(): Float = compose.onAllNodes(hasText("Trackhop")).fetchSemanticsNodes().minOf { it.boundsInRoot.bottom }

    /** The cards whose titles show on screen (below the header), top to bottom, with their titles' tops. */
    private fun cardsOnScreen(): List<Pair<Int, Float>> {
        val top = listTop()
        return (1..n).mapNotNull { i ->
            compose.onAllNodes(hasText("Sync number $i")).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.takeIf { it.center.y > top }?.let { i to it.top }
        }.sortedBy { it.second }
    }

    /**
     * Holds card [i] by the left end of its title (clear of the Sync-all pill, which may cover the
     * rest of it) and drags it by [dy] pixels in small steps, then lets go.
     */
    private fun drag(i: Int, dy: Float) {
        android.util.Log.d("Reorder", "test: drag $i by $dy, order ${order()}")
        compose.onAllNodes(hasText("Sync number $i")).onFirst().performTouchInput {
            down(Offset(12f, height / 2f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            val steps = 30
            repeat(steps) { moveBy(Offset(0f, dy / steps)); advanceEventTime(16) }
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        android.util.Log.d("Reorder", "test: after drag, order ${order()}")
    }

    @Test
    fun theFirstVisibleCardDraggedDownStaysWhereItIsLetGo() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        toTheEnd()
        waitText("Sync number $n")
        val cards = cardsOnScreen()
        assertTrue("several cards on screen: $cards", cards.size >= 2)
        val (upper, upperTop) = cards.first()
        val (next, nextTop) = cards[1]
        drag(upper, (nextTop - upperTop) * 1.3f)
        waitFor("job-$upper after job-$next") { order().indexOf("job-$upper") == order().indexOf("job-$next") + 1 }
    }

    @Test
    fun theLastCardDraggedUpLandsAboveTheFirstVisible() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        toTheEnd()
        waitText("Sync number $n")
        val cards = cardsOnScreen()
        val (prev, prevTop) = cards[cards.size - 2]
        val (last, lastTop) = cards.last()
        drag(last, -(lastTop - prevTop) * 1.3f)
        waitFor("job-$last before job-$prev") { order().indexOf("job-$last") == order().indexOf("job-$prev") - 1 }
    }

    // Two cards swapped while the list is open (by the store, as the menu does), then dragged back to
    // the order they had when the list was drawn: kept.
    @Test
    fun aDropBackToAnEarlierOrderIsKept() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        store.moveJob("job-$n", n - 2)
        waitFor("job-$n before job-${n - 1}") { order().takeLast(2) == listOf("job-$n", "job-${n - 1}") }
        toTheEnd()
        waitText("Sync number $n")
        val cards = cardsOnScreen().toMap()
        drag(n, (cards.getValue(n - 1) - cards.getValue(n)) * 1.3f)
        waitFor("job-$n last again") { order().last() == "job-$n" }
        assertEquals((1..n).map { "job-$it" }, order())
    }
}

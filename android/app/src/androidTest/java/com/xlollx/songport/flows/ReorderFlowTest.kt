package com.xlollx.songport.flows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.ui.JOB_TILE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cards held and dragged at the end of the list, where the upper card on screen is partly under the
 * Sync-all pill (07/11 report): the card dragged down past the
 * next one stays there instead of snapping back, and the last card dragged up lands above it.
 * And the tiles of the minimal view, two to a row (10/11 report): a tile dragged across a column and
 * down a row lands there, and a starred one stays among the starred while it is dragged.
 */
@RunWith(AndroidJUnit4::class)
class ReorderFlowTest : FlowTest() {

    private val n = 6

    private fun seed(starred: Set<Int> = emptySet()) = store.update { d ->
        d.copy(jobs = (1..n).map { i -> SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d$i", "Road Trip $i"), favorite = i in starred) })
    }

    private fun toTheEnd() {
        repeat(4) { compose.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 5000f) } }
        compose.waitForIdle()
    }

    private fun order() = store.data.jobs.map { it.id }

    /** Where the list shows its first card: below the app's header (its title). */
    private fun listTop(): Float = compose.onAllNodes(hasText("Trackhop")).fetchSemanticsNodes().minOf { it.boundsInRoot.bottom }

    /**
     * The cards on screen (below the header), top to bottom, with their tops where they really are:
     * a card partly above the list's top is clipped in its bounds, not in its position.
     */
    private fun cardsOnScreen(): List<Pair<Int, Float>> {
        val top = listTop()
        // Under the pinned Sync-all pill nothing of a card shows: one counts once a good part of it is below.
        val shows = (pinnedPillBottom() ?: top) + 48 * app.resources.displayMetrics.density
        return (1..n).mapNotNull { i ->
            // Only a card the list has placed: one that left the screen in a single frame of a fast scroll
            // may be kept for reuse for a while, with the place it last had.
            compose.onAllNodes(hasText("Sync number $i")).fetchSemanticsNodes().firstOrNull { it.layoutInfo.isPlaced }
                ?.takeIf { it.boundsInRoot.center.y > top && it.positionInRoot.y + it.size.height > shows }?.let { i to it.positionInRoot.y }
        }.sortedBy { it.second }
    }

    /**
     * Holds card [i] near its left edge, where it shows: by its name, or just under the pinned
     * Sync-all pill when its name has gone beneath it (the pill and its background take a touch
     * there, as a finger finds). Then drags it by [dy] pixels as a finger does: a few pixels at a
     * time, with frames drawn in between, and lets go.
     */
    private fun drag(i: Int, dy: Float) {
        android.util.Log.d("Reorder", "test: drag $i by $dy, order ${order()}")
        val name = compose.onAllNodes(hasText("Sync number $i")).onFirst()
        val top = name.fetchSemanticsNode().positionInRoot.y
        val clear = pinnedPillBottom()?.let { it + 4 * app.resources.displayMetrics.density }
        name.performTouchInput {
            down(Offset(12f, if (clear != null && top + height / 2f < clear) clear - top else height / 2f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveBy(Offset(0f, 1f))
        }
        compose.waitForIdle()
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        val steps = 30
        repeat(steps / 3) {
            list.performTouchInput { repeat(3) { advanceEventTime(16); moveBy(Offset(0f, dy / steps)) } }
            compose.waitForIdle()
        }
        list.performTouchInput { advanceEventTime(100); up() }
        compose.waitForIdle()
        android.util.Log.d("Reorder", "test: after drag, order ${order()}")
    }

    private fun tile(i: Int) = compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number $i")).onFirst()

    private fun tileAt(i: Int): Offset = tile(i).fetchSemanticsNode().positionInRoot

    /** The minimal view, its first tiles on screen, two to a row. */
    private fun openTiles() {
        store.updateSettings { it.copy(minimalSyncs = true) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        waitFor("tiles 1 to 4 on two rows") {
            runCatching { tileAt(1).y == tileAt(2).y && tileAt(3).y > tileAt(1).y && tileAt(4).x == tileAt(2).x }.getOrDefault(false)
        }
    }

    /**
     * Holds tile [i] and drags it along each of [legs] in turn, a few pixels at a time with frames
     * drawn in between, as a finger does; [held] runs at the end, before the tile is let go.
     */
    private fun dragTile(i: Int, legs: List<Offset>, held: () -> Unit = {}) {
        tile(i).performTouchInput {
            down(Offset(width * 0.3f, height / 2f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveBy(Offset(0f, 1f))
        }
        compose.waitForIdle()
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        for (leg in legs) repeat(10) {
            list.performTouchInput { repeat(3) { advanceEventTime(16); moveBy(leg / 30f) } }
            compose.waitForIdle()
        }
        held()
        list.performTouchInput { advanceEventTime(100); up() }
        compose.waitForIdle()
    }

    @Test
    fun aTileDraggedAcrossAColumnAndDownARowLandsThere() {
        seed()
        openTiles()
        // Tile 1 (top left) to where tile 4 is (second row, right): across, then down.
        val one = tileAt(1); val two = tileAt(2); val four = tileAt(4)
        dragTile(1, listOf(Offset(two.x - one.x, 0f), Offset(0f, four.y - two.y)))
        waitFor("job-1 fourth") { order().indexOf("job-1") == 3 }
        assertEquals(listOf("job-2", "job-3", "job-4", "job-1", "job-5", "job-6"), order())
        waitFor("tile 1 where tile 4 was") { tileAt(1).let { kotlin.math.abs(it.x - four.x) < 2f && kotlin.math.abs(it.y - four.y) < 2f } }
    }

    @Test
    fun aStarredTileDraggedAmongTheOthersStaysAmongTheStarred() {
        // Tiles 1 and 2 starred: the first row; the others below.
        seed(starred = setOf(1, 2))
        openTiles()
        val one = tileAt(1); val two = tileAt(2); val four = tileAt(4)
        dragTile(1, listOf(Offset(two.x - one.x, 0f), Offset(0f, four.y - two.y))) {
            // Held over the second row: its tiles have not made way (they would jump back on release).
            assertTrue("tile 3 still on the second row", tileAt(3).y > tileAt(2).y + 1f)
            assertTrue("tile 4 still on the second row", tileAt(4).y > tileAt(2).y + 1f)
        }
        waitFor("job-1 after job-2") { order().take(2) == listOf("job-2", "job-1") }
        assertEquals(listOf("job-2", "job-1", "job-3", "job-4", "job-5", "job-6"), order())
        waitFor("tile 1 back on the first row, right") { tileAt(1).let { kotlin.math.abs(it.x - two.x) < 2f && kotlin.math.abs(it.y - two.y) < 2f } }
    }

    @Test
    fun withFavouritesNotOnTopAStarredTileGoesAmongTheOthers() {
        // Settings › Favourites always on top, off: tiles 1 and 2 starred, the list as stored.
        store.updateSettings { it.copy(favoritesOnTop = false) }
        seed(starred = setOf(1, 2))
        openTiles()
        val one = tileAt(1); val two = tileAt(2); val four = tileAt(4)
        dragTile(1, listOf(Offset(two.x - one.x, 0f), Offset(0f, four.y - two.y))) {
            // Held where tile 4 was: the tiles it passed have made way, as for any other tile. Tile 1 is
            // fourth now (2, 3 / 4, 1): tile 3 has gone up to the first row and tile 4 to the left on
            // its own (not up a row: it is third).
            waitFor("tile 3 up on the first row, tile 4 to its left") { tileAt(3).y < four.y - 1f && tileAt(4).x < four.x - 1f }
        }
        waitFor("job-1 fourth") { order().indexOf("job-1") == 3 }
        assertEquals(listOf("job-2", "job-3", "job-4", "job-1", "job-5", "job-6"), order())
        assertTrue("job-1 still starred", store.data.jobs.first { it.id == "job-1" }.favorite)
        // Let go, it stays among the unstarred, where tile 4 was.
        waitFor("tile 1 where tile 4 was") { tileAt(1).let { kotlin.math.abs(it.x - four.x) < 2f && kotlin.math.abs(it.y - four.y) < 2f } }
    }

    /** Whether a tile's menu shows (its Style entry, as the menu of every sync has). */
    private fun menuShown() = compose.onAllNodes(hasText(s(com.xlollx.songport.R.string.job_style))).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    // A hold that turns into a drag is a drag only: no menu on release (11/11 report), also when the
    // tile cannot go where it is dragged (unstarred, over the starred tiles) and changes no places.
    @Test
    fun aTileHeldAndDraggedOpensNoMenu() {
        seed(starred = setOf(1, 2))
        openTiles()
        val one = tileAt(1); val three = tileAt(3)
        dragTile(3, listOf(Offset(0f, one.y - three.y)))
        Thread.sleep(800) // the menu, if any, would open on release
        compose.waitForIdle()
        assertTrue("no menu after a drag that moved nothing", !menuShown())
        assertEquals((1..n).map { "job-$it" }, order())
        // And after one that did move it: tile 3 across, past tile 4.
        val four = tileAt(4)
        dragTile(3, listOf(Offset(four.x - three.x, 0f)))
        waitFor("job-3 after job-4") { order().indexOf("job-3") == order().indexOf("job-4") + 1 }
        compose.waitForIdle()
        assertTrue("no menu after a drag that moved it", !menuShown())
    }

    // Held and let go without moving: the tile's menu, as before.
    @Test
    fun aTileHeldStillOpensItsMenu() {
        seed()
        openTiles()
        tile(2).performTouchInput { longClick() }
        waitFor("the tile's menu") { menuShown() }
        assertEquals((1..n).map { "job-$it" }, order())
    }

    @Test
    fun theFirstVisibleCardDraggedDownStaysWhereItIsLetGo() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        toTheEnd()
        waitText("Sync number $n")
        val cards = cardsOnScreen()
        android.util.Log.d("Reorder", "test: cards on screen $cards, list top ${listTop()}, pill bottom ${pinnedPillBottom()}")
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
        // The store's order is not yet the list's: the positions are read once the cards show it
        // (read before, card n still sat below n - 1 and the drag went the wrong way).
        var cards = emptyMap<Int, Float>()
        waitFor("card $n drawn above card ${n - 1}") {
            cards = cardsOnScreen().toMap()
            val upper = cards[n]; val lower = cards[n - 1]
            upper != null && lower != null && upper < lower
        }
        drag(n, (cards.getValue(n - 1) - cards.getValue(n)) * 1.3f)
        waitFor("job-$n last again") { order().last() == "job-$n" }
        assertEquals((1..n).map { "job-$it" }, order())
    }
}

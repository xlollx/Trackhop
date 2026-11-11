package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click as touchClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.ui.JOB_CARD_TAG
import com.xlollx.songport.ui.JOB_TILE_TAG
import com.xlollx.songport.ui.RUN_ALL_BAND_TAG
import com.xlollx.songport.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cards' size (Settings › Card size, and smaller by itself on a phone on its side), the
 * minimal view of tiles, and the pinned Sync-all pill that never covers a card.
 */
@RunWith(AndroidJUnit4::class)
class CardDensityFlowTest : FlowTest() {

    /** Syncs that have run, so their cards carry their figures and run history (the tallest card). */
    private fun seed(n: Int) {
        val now = System.currentTimeMillis()
        val jobs = (1..n).map { i ->
            SyncJob(
                "job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d$i", "Road Trip $i"),
                lastRunEpoch = now - i * 60_000L, lastReportId = "r$i-2", color = "teal",
            )
        }
        val reports = (1..n).flatMap { i ->
            (0..2).map { k -> SyncReport(id = "r$i-$k", jobId = "job-$i", jobName = "Sync number $i", startedEpoch = now - i * 60_000L - (2 - k) * 3_600_000L, durationMs = 9_000, sourceCount = 8, targetCount = 8, added = i + k, removed = k) }
        }
        store.update { it.copy(jobs = jobs, reports = reports) }
    }

    private fun density() = app.resources.displayMetrics.density

    private fun cards(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun tiles(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(JOB_TILE_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun cardOf(i: Int): SemanticsNode = compose.onAllNodes(hasTestTag(JOB_CARD_TAG) and hasText("Sync number $i")).onFirst().fetchSemanticsNode()

    /** The sync list: the widest vertical scroller (on its side the narrow rail of tabs scrolls too). */
    private fun list(): SemanticsNode = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        .filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        .maxBy { it.boundsInRoot.width }

    private fun scrollList(dy: Float) {
        val id = list().id
        compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indexOfFirst { it.id == id }.let { i ->
            compose.onAllNodes(hasScrollAction())[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        }
        compose.waitForIdle()
    }

    private fun band(): SemanticsNode? = compose.onAllNodes(hasTestTag(RUN_ALL_BAND_TAG), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()

    /**
     * Swiped as a finger does (so the bar at the top slides away as it would) until the Sync-all
     * pill stays at the top, the cards passing under it.
     */
    private fun pinPill() {
        var tries = 0
        while (band() == null && tries++ < 20) {
            val l = list()
            compose.onAllNodes(isRoot())[0].performTouchInput {
                val x = l.boundsInRoot.center.x
                swipe(androidx.compose.ui.geometry.Offset(x, l.boundsInRoot.bottom - 60 * density()), androidx.compose.ui.geometry.Offset(x, l.boundsInRoot.bottom - 200 * density()), 400)
            }
            compose.waitForIdle()
        }
        assertTrue("the Sync-all pill pinned", band() != null)
    }

    @Test
    fun theMinimalViewShowsTilesThatOpenTheirSync() {
        seed(5)
        // Sync 1 runs by itself (a "Next" line on its tile), sync 2 only by hand (none).
        store.update { d -> d.copy(jobs = d.jobs.map { if (it.id == "job-1") it.copy(schedule = com.xlollx.songport.model.Schedule.DAILY) else it }) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        assertTrue("cards at first", tiles().isEmpty() && cards().isNotEmpty())

        clickDesc(s(R.string.syncs_view_tiles))
        waitFor("tiles on screen") { tiles().size >= 2 }
        assertTrue("the choice remembered", store.data.settings.minimalSyncs)
        assertTrue("no full card left in the list", cards().isEmpty())
        snapshot("syncs-tiles")
        // Two tiles side by side, the first two syncs in the list's order.
        val one = compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number 1")).onFirst().fetchSemanticsNode()
        val two = compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number 2")).onFirst().fetchSemanticsNode()
        assertEquals("tiles 1 and 2 on one row", one.positionInRoot.y, two.positionInRoot.y, 1f)
        assertTrue("tile 2 right of tile 1", two.positionInRoot.x > one.boundsInRoot.right - 1f)
        assertEquals("tiles 1 and 2 as tall as each other", one.size.height.toFloat(), two.size.height.toFloat(), 1f)
        // A tile says little: no route, no figures. Its name and status, a run button.
        assertFalse(exists("Road Trip 1", substring = true))
        assertTrue(compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number 1") and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription(s(R.string.run_now)))).fetchSemanticsNodes().isNotEmpty())

        // A tap: the whole card, as the list of cards shows it, with its menu; Edit leads to the editor.
        click("Sync number 1")
        waitFor("the card opened") { cards().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("Sync number 1") } == true } }
        waitText("Road Trip 1", substring = true)
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
        assertTrue("the sheet closed under the editor", cards().isEmpty())
        back()
        waitFor("tiles again") { tiles().size >= 2 }

        // Held down and let go: the card's menu, here too.
        compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number 2")).onFirst().performTouchInput { longClick() }
        waitText(s(R.string.job_style))
        click(s(R.string.sync_menu_favorite))
        waitFor("sync 2 starred") { store.data.jobs.first { it.id == "job-2" }.favorite }
        // Starred first, in the tiles as in the cards.
        waitFor("sync 2 first") {
            val t2 = compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number 2")).fetchSemanticsNodes().firstOrNull()
            val t1 = compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Sync number 1")).fetchSemanticsNodes().firstOrNull()
            t1 != null && t2 != null && t2.positionInRoot.x < t1.positionInRoot.x
        }
        assertEquals("the order itself is not changed", listOf("job-1", "job-2", "job-3", "job-4", "job-5"), store.data.jobs.map { it.id })

        // And back to the cards.
        clickDesc(s(R.string.syncs_view_cards))
        waitFor("cards again") { cards().isNotEmpty() && tiles().isEmpty() }
        assertFalse(store.data.settings.minimalSyncs)
    }

    @Test
    fun theCardSizeIsChosenInSettingsAndEachIsSmaller() {
        seed(3)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        val heights = com.xlollx.songport.ui.CardSize.entries.map { size ->
            val shownAlready = com.xlollx.songport.ui.CardSize.of(store.data.settings.cardSize) == size
            val was = cardOf(1).size.height
            store.updateSettings { it.copy(cardSize = size.key) }
            // The new size reaches the list a frame or more later, and the card then animates to it:
            // on a slow emulator a fixed pause measured the Medium card again as the Small one. So the
            // card is measured once it has changed (an unchanged one fails the comparison below) and
            // has held its height for a while.
            if (!shownAlready) runCatching { compose.waitUntil(10_000) { runCatching { cardOf(1).size.height != was }.getOrDefault(false) } }
            var last = -1
            var since = 0L
            waitFor("${size.key} cards holding their height") {
                val h = cardOf(1).size.height
                val now = android.os.SystemClock.uptimeMillis()
                if (h != last) { last = h; since = now }
                h > 0 && now - since >= 600
            }
            snapshot("cards-${size.key}")
            cardOf(1).size.height / density()
        }
        log("card heights (dp): $heights")
        assertTrue("each size smaller than the one before: $heights", heights.zipWithNext().all { (a, b) -> b < a - 8 })
        // A small card keeps its figures, in one row of marks read with their words.
        val added = "3 " + s(R.string.stat_added)
        assertTrue(compose.onAllNodes(SemanticsMatcher("added mark") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == added } == true }).fetchSemanticsNodes().isNotEmpty())

        store.updateSettings { it.copy(cardSize = "large") }
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        click(s(R.string.settings_card_size))
        waitText(s(R.string.card_size_landscape))
        click(s(R.string.card_size_medium))
        waitFor("medium stored") { store.data.settings.cardSize == "medium" }
        click(s(R.string.settings_minimal_view))
        waitFor("tiles stored") { store.data.settings.minimalSyncs }
    }

    @Test
    fun onItsSideTwoCardsFitAndThePillCoversNone() {
        seed(6)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        rotate(UiAutomation.ROTATION_FREEZE_90)
        // The header and the finder may fill a phone's height on its side: the cards are further down.
        waitText(s(R.string.hero_title))
        val window = main().resources.configuration.let { WindowLayout.of(it.screenWidthDp, it.screenHeightDp) }
        assertTrue("a low window", window.short)
        // Large chosen, small shown: the user's large cards would fill the whole height.
        assertEquals("large", store.data.settings.cardSize)

        pinPill()
        val pill = band()!!
        val fade = com.xlollx.songport.ui.PILL_FADE.value * density()
        val viewBottom = list().boundsInRoot.bottom
        // The first card wholly under the pill, brought up to just below it.
        val next = cards().filter { it.boundsInRoot.top >= pill.boundsInRoot.bottom }.minBy { it.positionInRoot.y }
        scrollList(next.positionInRoot.y - (pill.boundsInRoot.bottom + fade))
        val shown = cards().filter { c ->
            c.positionInRoot.y >= pill.boundsInRoot.bottom - 1 && c.positionInRoot.y + c.size.height <= viewBottom + 1
        }
        snapshot("landscape-two-cards")
        val h = cards().maxOf { it.size.height }
        log("landscape: card ${h / density()} dp, list ${pill.boundsInRoot.bottom / density()}..${viewBottom / density()} dp, whole cards ${shown.size}")
        assertTrue("at least two whole cards below the pill: ${shown.size}", shown.size >= 2)
        // Two rows of cards fit one above the other, whatever the columns.
        assertTrue("two cards of ${h / density()} dp fit one above the other", 2 * h + 12 * density() <= viewBottom - pill.boundsInRoot.bottom)

        // A card half under the pill: what is under it does not show, beside the pill either.
        val card = cards().filter { it.positionInRoot.y > pill.boundsInRoot.bottom }.minBy { it.positionInRoot.y }
        val mid = (pill.boundsInRoot.top + pill.boundsInRoot.bottom) / 2
        scrollList(card.positionInRoot.y - mid + 2 * density())
        val under = cardOf(card.config[SemanticsProperties.Text].first { it.text.startsWith("Sync number") }.text.removePrefix("Sync number ").trim().toInt())
        assertTrue("the card has gone half under the pill", under.positionInRoot.y < mid && under.positionInRoot.y + under.size.height > pill.boundsInRoot.bottom + fade)
        snapshot("landscape-card-under-pill")
        val shot = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val inCard = (under.positionInRoot.x + 12 * density()).toInt()
        val gutter = (under.positionInRoot.x - 8 * density()).toInt()
        val y = (mid + pill.boundsInRoot.height / 4).toInt()
        val below = (pill.boundsInRoot.bottom + fade + 6 * density()).toInt()
        fun same(a: Int, b: Int) = listOf(16, 8, 0).all { sh -> kotlin.math.abs((a shr sh and 0xFF) - (b shr sh and 0xFF)) <= 6 }
        assertFalse("the card's own colour differs from the page's (else this proves nothing)", same(shot.getPixel(inCard, below), shot.getPixel(gutter, below)))
        assertTrue("under the pill the page's background, not the card", same(shot.getPixel(inCard, y), shot.getPixel(gutter, y)))
        // A tap beside the pill reaches no card hidden beneath: nothing opens, nothing runs.
        compose.onAllNodes(isRoot())[0].performTouchInput { touchClick(androidx.compose.ui.geometry.Offset(inCard.toFloat(), y.toFloat())) }
        compose.waitForIdle()
        assertTrue("nothing started", com.xlollx.songport.sync.SyncState.running.value.isEmpty())
        // The pill itself still answers.
        assertTrue(compose.onAllNodes(hasClickAction() and hasText(s(R.string.sync_all))).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * A phone-sized window on its side, narrower than a tablet (the owner's phone: about 730 dp
     * wide, where 1.17 showed one column): two columns of cards, and the New sync button at the head
     * of the rail, over no card.
     */
    @Test
    fun aPhoneOnItsSideHasTwoColumns() {
        seed(4)
        shell("wm size 1080x1920")
        try {
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitText("Sync number 1")
            rotate(UiAutomation.ROTATION_FREEZE_90)
            waitText("Sync number 2")
            val cfg = main().resources.configuration
            log("phone-sized window on its side: ${cfg.screenWidthDp} x ${cfg.screenHeightDp} dp")
            assertTrue("narrower than a tablet: ${cfg.screenWidthDp} dp", cfg.screenWidthDp < 840)
            snapshot("phone-landscape-columns")
            val one = cardOf(1); val two = cardOf(2)
            assertEquals("cards 1 and 2 side by side", one.positionInRoot.y, two.positionInRoot.y, 1f)
            assertTrue(two.positionInRoot.x >= one.positionInRoot.x + one.size.width)
            val fab = compose.onAllNodes(hasClickAction() and androidx.compose.ui.test.hasContentDescription(s(R.string.new_sync))).onFirst().fetchSemanticsNode()
            assertTrue("New sync beside the cards, not over them", fab.boundsInRoot.right <= one.boundsInRoot.left)
        } finally {
            val before = resumed()
            val was = configOf(before)
            shell("wm size reset")
            runCatching { waitForConfigChange(before, was, ms = 10_000) }
            compose.waitForIdle()
        }
    }

    /** The card's Style dialog and its Move-to dialog stay open through a turn of the phone, with what was chosen. */
    @Test
    fun theCardsDialogsSurviveARotation() {
        seed(3)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.job_style))
        waitNode("the colour swatches", androidx.compose.ui.test.hasContentDescription(s(R.string.job_color_default)))
        click(s(R.string.color_pink))
        compose.onNode(hasTestTag(com.xlollx.songport.ui.LABEL_NAME_TAG)).performTextReplacement("Loud")

        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitNode("the style dialog, still open", hasTestTag(com.xlollx.songport.ui.STYLE_CHOICES_TAG))
        waitNode("the name typed", hasTestTag(com.xlollx.songport.ui.LABEL_NAME_TAG) and hasText("Loud"))
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(s(R.string.color_pink)) and androidx.compose.ui.test.isSelectable()).onFirst().assertIsSelected()
        click(s(R.string.save))
        waitFor("pink stored with its name") {
            val j = store.data.jobs.first { it.id == "job-1" }
            j.color == "pink" && com.xlollx.songport.sync.Labels.name(j, store.data.settings.labels) == "Loud"
        }

        // Move to, upright again: the number typed is still there after a turn.
        rotate(UiAutomation.ROTATION_FREEZE_0)
        waitText("Sync number 1")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.sync_menu_move_to))
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("3")
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitNode("the position typed", androidx.compose.ui.test.hasSetTextAction() and hasText("3"))
        click(s(R.string.ok))
        waitFor("sync 1 moved to the end") { store.data.jobs.last().id == "job-1" }
    }
}

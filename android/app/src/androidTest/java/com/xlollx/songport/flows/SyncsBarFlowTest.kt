package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.ui.BAR_SUPPORT_TAG
import com.xlollx.songport.ui.BAR_UPDATE_TAG
import com.xlollx.songport.ui.SYNC_SEARCH_TAG
import com.xlollx.songport.ui.SYNC_STATUS_TAG
import com.xlollx.songport.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The head of the Syncs tab: the bar (the mark, the search field, the update's arrow, the cup) and
 * the status line under it, where the tagline's card was.
 */
@RunWith(AndroidJUnit4::class)
class SyncsBarFlowTest : FlowTest() {

    private val names = listOf("Road Trip", "Chill Evening", "Workout Mix", "Party Night", "Morning Run", "Focus")

    /** [n] syncs that ran a few minutes ago; [reports] changes the report of the sync at its index. */
    private fun seed(n: Int, reports: Map<Int, (SyncReport) -> SyncReport> = emptyMap()) {
        val now = System.currentTimeMillis()
        val jobs = (0 until n).map { i ->
            SyncJob("job-$i", names[i], PlaylistRef("spotify", "pl$i", names[i]), PlaylistRef("deezer", "d$i", names[i]), lastRunEpoch = now - (i + 1) * 60_000L, lastReportId = "r$i")
        }
        val made = (0 until n).map { i ->
            SyncReport(id = "r$i", jobId = "job-$i", jobName = names[i], startedEpoch = now - (i + 1) * 60_000L, durationMs = 9_000, sourceCount = 8, targetCount = 8, added = 2)
                .let { reports[i]?.invoke(it) ?: it }
        }
        store.update { it.copy(jobs = jobs, reports = made) }
    }

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
    private fun boxOf(tag: String): Rect = tagged(tag).single().boundsInRoot
    private fun mark(): Rect = compose.onAllNodes(hasContentDescription("Trackhop")).onFirst().fetchSemanticsNode().boundsInRoot

    /** A newer version to offer, as the GitHub build keeps it between checks. */
    private fun offerUpdate() = app.getSharedPreferences("app_updates", 0).edit().putString("name", "99.9.9")
        .putString("url", "https://github.com/xlollx/Trackhop/releases/latest").putLong("checked", System.currentTimeMillis()).commit()

    /** The window [dp] wide: the screen's own pixels at a higher density. */
    private fun widthDp(dp: Int) {
        val px = shell("wm size").lines().last { it.contains(':') }.substringAfter(':').trim().substringBefore('x').toInt()
        shell("wm density ${px * 160 / dp}")
    }

    private fun fontScale(scale: Float) {
        shell("settings put system font_scale $scale")
        waitFor("font scale $scale") { kotlin.math.abs(app.resources.configuration.fontScale - scale) <= 0.01f }
    }

    /**
     * The bar's parts, each whole and in the window, in their order with nothing over anything else:
     * the mark, the search field, the update's arrow, the cup; and the status line under them all.
     */
    @org.junit.After
    fun noUpdateLeftBehind() {
        app.getSharedPreferences("app_updates", 0).edit().remove("name").remove("url").remove("checked").commit()
        UpdateState.set(null)
    }

    private fun assertBarInOrder(what: String) {
        waitFor("$what: the update's arrow, the cup and the search field") { tagged(BAR_UPDATE_TAG).size == 1 && tagged(BAR_SUPPORT_TAG).size == 1 && tagged(SYNC_SEARCH_TAG).size == 1 }
        compose.waitForIdle()
        val density = app.resources.displayMetrics.density
        val window = main().window.decorView.width.toFloat()
        val mark = mark(); val search = boxOf(SYNC_SEARCH_TAG); val update = boxOf(BAR_UPDATE_TAG); val cup = boxOf(BAR_SUPPORT_TAG); val status = boxOf(SYNC_STATUS_TAG)
        log("$what: window ${window / density} dp, mark $mark, search $search, update $update, cup $cup, status $status")
        for ((name, box) in listOf("update" to update, "cup" to cup)) {
            assertTrue("$what: the $name button is a whole 48dp target ($box)", box.width >= 48 * density - 1 && box.height >= 48 * density - 1)
            assertTrue("$what: the $name button is in the window ($box of $window)", box.left >= 0 && box.right <= window + 1)
            assertTrue("$what: the $name button is tappable", compose.onAllNodes(hasTestTag(if (name == "update") BAR_UPDATE_TAG else BAR_SUPPORT_TAG) and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        }
        assertTrue("$what: the search field after the mark ($mark, $search)", search.left >= mark.right)
        assertTrue("$what: the update's arrow after the search field, not over it ($search, $update)", update.left >= search.right)
        assertTrue("$what: the cup after the update's arrow, not over it ($update, $cup)", cup.left >= update.right)
        assertFalse("$what: the search field under the update's arrow", search.overlaps(update))
        assertFalse("$what: the search field under the cup", search.overlaps(cup))
        assertFalse("$what: the mark under the search field", mark.overlaps(search))
        // The field gives way, but stays a field: room for its magnifier and a few letters.
        assertTrue("$what: the search field keeps a usable width (${search.width / density} dp)", search.width >= 96 * density)
        assertTrue("$what: the search field is a whole 48dp target", search.height >= 48 * density - 1)
        val barBottom = maxOf(search.bottom, update.bottom, cup.bottom)
        assertTrue("$what: the status line under the bar, under none of its parts ($status, bar to $barBottom)", status.top >= barBottom)
        // Drawn and still before a picture is taken of it.
        Thread.sleep(700); compose.waitForIdle()
    }

    /** The owner's rule: with an update to offer, its control is always in the bar, beside the cup, and nothing is cramped out. */
    @Test
    fun theUpdateAndTheCupAlwaysFitBesideTheSearchField() {
        // The Play build has neither a GitHub check to answer nor a cup (no payments outside Google Play).
        assumeFalse(BuildConfig.STORE_BUILD)
        seed(6)
        offerUpdate()
        try {
            // A small phone, 320dp wide.
            widthDp(320)
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitText("Road Trip")
            assertTrue("a 320dp window: ${main().resources.configuration.screenWidthDp}", main().resources.configuration.screenWidthDp in 316..324)
            assertBarInOrder("320dp")
            snapshot("bar-update-320")
            // The same with a large font.
            fontScale(1.5f)
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitText("Road Trip")
            assertBarInOrder("320dp, font 1.5")
            snapshot("bar-update-320-bigfont")
            fontScale(1.3f)
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitText("Road Trip")
            assertBarInOrder("320dp, font 1.3")
            // 360dp, the common small phone, still with the large font.
            widthDp(360)
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitText("Road Trip")
            assertBarInOrder("360dp, font 1.3")
            snapshot("bar-update-360-bigfont")
        } finally {
            shell("settings put system font_scale 1.0")
            shell("wm density reset")
            runCatching { waitFor("font scale back") { kotlin.math.abs(app.resources.configuration.fontScale - 1f) <= 0.01f } }
        }
        // The phone as it is, upright and on its side (the tabs on a rail, the bar beside it).
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        assertBarInOrder("upright")
        snapshot("bar-update")
        rotate(UiAutomation.ROTATION_FREEZE_90)
        assertBarInOrder("on its side")
        snapshot("bar-update-landscape")
        // A tap on the arrow is the update's, on the cup the support sheet's.
        rotate(UiAutomation.ROTATION_FREEZE_0)
        clickDesc(s(R.string.support_row))
        waitText(s(R.string.support_row), substring = true)
    }

    /** A tablet-wide window: the same bar, the field no wider than reads well. */
    @Test
    fun theBarFitsAWideWindowToo() {
        assumeFalse(BuildConfig.STORE_BUILD)
        seed(6)
        offerUpdate()
        try {
            widthDp(840)
            launch(tabIntent(MainActivity.TAB_SYNCS))
            waitText("Road Trip")
            assertBarInOrder("840dp")
            assertTrue("the field no wider than 520dp", boxOf(SYNC_SEARCH_TAG).width <= 521 * app.resources.displayMetrics.density)
            snapshot("bar-update-tablet")
        } finally {
            shell("wm density reset")
        }
    }

    @Test
    fun theStatusLineSaysWhatNeedsALookAndShowsIt() {
        seed(6, mapOf(
            1 to { r -> r.copy(unmatched = listOf("a", "b")) },
            3 to { r -> r.copy(error = "token expired") },
        ))
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        val line = plural(R.plurals.status_failed, 1, 1) + " · " + plural(R.plurals.status_to_fix, 1, 1)
        waitText(line)
        snapshot("status-attention")
        // A tap shows those syncs: the chips' own "Errors" and "To fix", both on.
        click(line)
        waitText(plural(R.plurals.filter_shown, 6, 2, 6))
        assertTrue(exists("Chill Evening"))
        assertTrue(exists("Party Night"))
        assertFalse(exists("Road Trip"))
        // A second tap takes the filter off again.
        click(line)
        waitGone(plural(R.plurals.filter_shown, 6, 2, 6))
        waitText("Road Trip")
    }

    @Test
    fun allUpToDateWithTheLastSyncAndTheNumbers() {
        seed(6)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        // Every sync ran well: the tick, and when the last one ran (a minute ago: the time alone).
        waitText(s(R.string.status_all_ok), substring = true)
        val status = compose.onAllNodes(hasText(s(R.string.status_all_ok), substring = true)).onFirst().fetchSemanticsNode()
        assertFalse("nothing to show: the line is not a button", compose.onAllNodes(hasTestTag(SYNC_STATUS_TAG) and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("under the bar", status.boundsInRoot.top >= mark().bottom)
        // "Your numbers" at its end, as the tagline's card had it.
        click(s(R.string.stats_hero_link))
        waitText(s(R.string.stats_title))
    }

    @Test
    fun aFewSyncsHaveTheSearchFieldAndKeepTheLine() {
        seed(3)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        // From one sync on (the preview code is typed there too); the chips still wait for five.
        assertEquals("the search field with three syncs", 1, tagged(SYNC_SEARCH_TAG).size)
        waitText(s(R.string.status_all_ok), substring = true)
        assertTrue(mark().width > 0)
    }

    /** The page's glow is painted once, behind the list: scrolling the cards over it paints nothing again. */
    @Test
    fun scrollingTheListDoesNotRepaintTheGlow() {
        seed(6)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        compose.waitForIdle()
        val painted = com.xlollx.songport.ui.PageBackdrop.paints
        assertTrue("the glow painted behind the page", painted > 0)
        for (dy in listOf(700f, 700f, 700f, -900f, -1200f)) {
            compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, dy) }
            compose.waitForIdle()
        }
        waitText("Road Trip")
        assertEquals("no glow painted while the list scrolled", painted, com.xlollx.songport.ui.PageBackdrop.paints)
    }
}

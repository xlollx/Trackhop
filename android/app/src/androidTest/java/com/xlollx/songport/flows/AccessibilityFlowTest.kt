package com.xlollx.songport.flows

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility basics, read from the Compose semantics of each screen: every control (anything
 * that can be tapped) has a name TalkBack can read (its text or a content description), and a
 * touch target of at least 48×48 dp.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityFlowTest : FlowTest() {
    private val problems = ArrayList<String>()

    private fun label(n: SemanticsNode): String = listOfNotNull(
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        n.config.getOrNull(SemanticsProperties.EditableText)?.text,
    ).joinToString(" ").trim()

    /** Every enabled control on screen now: named, and big enough to hit. */
    private fun audit(screen: String) {
        compose.waitForIdle()
        val density = app.resources.displayMetrics.density
        // With a dialog or a sheet open, only its window: the screen under it cannot be reached.
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
        val top = roots.last().id
        for (n in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            if (n.config.contains(SemanticsProperties.Disabled)) continue
            if (rootOf(n).id != top) continue
            // Only controls wholly on screen: a clipped one (scrolled half out) has no size to judge.
            val b = n.boundsInRoot
            if (b.width < n.size.width - 1f || b.height < n.size.height - 1f) continue
            val name = label(n)
            // The area that takes the touch: Compose widens small controls to the minimum touch target.
            val r = touchBounds(n)
            val w = r.width / density
            val h = r.height / density
            if (name.isBlank()) problems += "$screen: a control without a name (${"%.0f".format(w)}×${"%.0f".format(h)} dp at ${n.boundsInRoot.topLeft})"
            if (w < 47.5f || h < 47.5f) problems += "$screen: \"${name.take(40)}\" is ${"%.0f".format(w)}×${"%.0f".format(h)} dp (< 48)"
        }
    }

    private fun rootOf(n: SemanticsNode): SemanticsNode { var p = n; while (p.parent != null) p = p.parent!!; return p }

    private fun touchBounds(n: SemanticsNode): androidx.compose.ui.geometry.Rect = runCatching {
        SemanticsNode::class.java.methods.first { it.name.startsWith("getTouchBoundsInRoot") }.invoke(n) as androidx.compose.ui.geometry.Rect
    }.getOrDefault(n.boundsInRoot)

    private fun seed() {
        val now = System.currentTimeMillis()
        val report = SyncReport(id = "r1", jobId = "job-1", jobName = "Road Trip", startedEpoch = now - 60_000, durationMs = 9_000,
            sourceCount = 8, targetCount = 6, added = 6, removed = 0, unmatched = listOf("Duality", "Enter Sandman"))
        store.update { d ->
            d.copy(
                jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d1", "Road Trip"), lastRunEpoch = report.startedEpoch, lastReportId = "r1")),
                reports = listOf(report),
            )
        }
    }

    @Test
    fun everyControlIsNamedAndBigEnough() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        audit("Syncs")
        // The card's menu: every entry named and 48 dp tall.
        clickDesc(s(R.string.more_actions))
        waitText(s(R.string.job_style))
        audit("Sync card menu")
        androidx.test.espresso.Espresso.pressBack()
        waitGone(s(R.string.job_style))

        click(s(R.string.new_sync))
        waitText(s(R.string.editor_source))
        audit("Sync editor")
        back()

        click(s(R.string.tab_accounts))
        waitText(s(R.string.accounts_intro))
        audit("Accounts")
        click(s(R.string.connector_add))
        waitText(s(R.string.connector_pick_title))
        audit("Add connector dialog")
        click(s(R.string.cancel))

        click(s(R.string.tab_discover))
        waitText(s(R.string.discover_wrapped_title))
        audit("Discover")
        click(s(R.string.tools_poster_title))
        waitText(s(R.string.card_cover_title))
        audit("Poster")
        back()

        click(s(R.string.tab_tools))
        waitText(s(R.string.tools_manage_title))
        audit("Tools")
        click(s(R.string.tools_manage_title))
        waitText("Road Trip")
        audit("Manage playlists")
        back()
        click(s(R.string.tools_unplayable_title))
        waitText(s(R.string.unplayable_scan))
        audit("Unplayable tracks")
        back()

        click(s(R.string.tab_settings))
        waitText(s(R.string.settings_theme))
        audit("Settings")
        click(s(R.string.settings_language))
        waitText("Deutsch")
        audit("Language dialog")
        click(s(R.string.cancel))
        click(s(R.string.settings_log))
        waitText(s(R.string.tab_log))
        audit("Sync log")

        assertTrue("Accessibility problems:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun onboardingAndShareSheetControls() {
        store.updateSettings { it.copy(onboardingDone = false) }
        launch()
        waitText(s(R.string.onboarding_title))
        audit("Welcome")
        click(s(R.string.onboarding_skip))

        launch(shareIntent("https://open.spotify.com/track/${FakeWorld.trackId("spotify", 0)}"))
        waitText(s(R.string.share_sheet_title))
        waitText(s(R.string.share_sheet_add), ms = 20_000)
        audit("Share sheet")
        assertTrue("Accessibility problems:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }
}

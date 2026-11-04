package com.xlollx.songport.flows

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frame of the app: the glass bottom bar on every tab (content scrolls under it and still
 * ends above it), dark mode (system and forced, and the status bar icons that go with it), and
 * long or right-to-left languages (no control label cut off).
 */
@RunWith(AndroidJUnit4::class)
class ChromeFlowTest : FlowTest() {
    private val tabs by lazy { listOf(R.string.tab_syncs, R.string.tab_accounts, R.string.tab_discover, R.string.tab_tools, R.string.tab_settings) }

    @After fun dayAgain() { night(false) }

    private fun aliveActivities(): Int {
        var n = 0
        instr.runOnMainSync {
            val reg = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            n = listOf(androidx.test.runner.lifecycle.Stage.CREATED, androidx.test.runner.lifecycle.Stage.STARTED, androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED, androidx.test.runner.lifecycle.Stage.STOPPED)
                .sumOf { reg.getActivitiesInStage(it).size }
        }
        return n
    }

    /** The phone's night mode, waited for until the app's configuration has it (else the activity started next is recreated under the test). */
    private fun night(on: Boolean) {
        // No activity alive while the mode flips: one recreated under the next test's frames breaks it.
        resumed()?.let { a -> instr.runOnMainSync { a.finish() } }
        val gone = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < gone && aliveActivities() > 0) Thread.sleep(100)
        shell("cmd uimode night ${if (on) "yes" else "no"}")
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && app.resources.configuration.isNightModeActive != on) Thread.sleep(100)
        Thread.sleep(500)
    }

    private fun seedJobs(n: Int) {
        val now = System.currentTimeMillis()
        val jobs = (1..n).map { i ->
            SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d$i", "Road Trip $i"), lastRunEpoch = now - i * 60_000L, lastReportId = "r$i")
        }
        val reports = (1..n).map { i -> SyncReport(id = "r$i", jobId = "job-$i", jobName = "Sync number $i", startedEpoch = now - i * 60_000L, durationMs = 9_000, sourceCount = 8, targetCount = 8, added = i, removed = 0) }
        store.update { it.copy(jobs = jobs, reports = reports) }
    }

    private fun navItem(label: String) = compose.onAllNodes(hasClickAction() and hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).onFirst()

    private fun barTop(): Float = tabs.minOf { navItem(s(it)).fetchSemanticsNode().boundsInRoot.top }

    @Test
    fun glassBarOnEveryTabWithContentEndingAboveIt() {
        seedJobs(6)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        for (tab in tabs) {
            navItem(s(tab)).also { it.performClickSafely() }.assertIsSelected()
            // All five destinations, each named, on every tab.
            tabs.forEach { assertTrue("tab ${s(it)} missing", compose.onAllNodes(hasText(s(it)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) }
            val top = barTop()
            val list = verticalScroller()
            if (list != null) {
                // Glass: the scrolling content runs under the bar...
                assertTrue("${s(tab)}: content stops above the bar (${list.boundsInRoot.bottom} <= $top), the bar is not over it", list.boundsInRoot.bottom > top)
                // ...and at the end of the list the last control clears it.
                // To the end by the list's own scroll action: a swipe from its bottom edge would start on the bar.
                repeat(6) { compose.onAllNodes(verticalScroll)[verticalScrollerIndex()].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 5000f) } }
                compose.waitForIdle()
            }
            // The control lowest on the page (the tabs and the floating button aside) clears the bar.
            val fabText = when (tab) { R.string.tab_syncs -> s(R.string.new_sync); R.string.tab_accounts -> s(R.string.connector_add); else -> null }
            val last = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                .filter { !it.config.contains(SemanticsProperties.Disabled) && it.config.getOrNull(SemanticsProperties.Role) != Role.Tab && (fabText == null || it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(fabText) != true) }
                .maxByOrNull { it.boundsInRoot.bottom } ?: throw AssertionError("${s(tab)}: no control")
            val lastName = (last.config.getOrNull(SemanticsProperties.Text) ?: last.config.getOrNull(SemanticsProperties.ContentDescription))?.joinToString()
            assertTrue("${s(tab)}: the last control ($lastName, bottom ${last.boundsInRoot.bottom}) ends under the bar (top $top)", last.boundsInRoot.bottom <= top + 1)
            // The floating button, where there is one, sits above the bar.
            val fab = when (tab) { R.string.tab_syncs -> s(R.string.new_sync); R.string.tab_accounts -> s(R.string.connector_add); else -> null }
            if (fab != null) {
                val b = compose.onAllNodes(hasClickAction() and (hasText(fab) or androidx.compose.ui.test.hasContentDescription(fab))).onFirst().fetchSemanticsNode().boundsInRoot
                assertTrue("${s(tab)}: the button \"$fab\" overlaps the bar", b.bottom <= top + 1)
            }
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafely() { performClick(); compose.waitForIdle() }

    private val verticalScroll = hasScrollAction() and SemanticsMatcher("scrolls vertically") { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }

    /** The tab's main list: the tallest vertical scroller on screen. */
    private fun verticalScrollerIndex(): Int = compose.onAllNodes(verticalScroll).fetchSemanticsNodes().withIndex().maxByOrNull { it.value.boundsInRoot.height }?.index ?: -1

    private fun verticalScroller(): SemanticsNode? = verticalScrollerIndex().takeIf { it >= 0 }?.let { compose.onAllNodes(verticalScroll).fetchSemanticsNodes()[it] }

    private fun statusBarIconsDark(): Boolean {
        var light = false
        val a = main()
        instr.runOnMainSync { light = WindowCompat.getInsetsController(a.window, a.window.decorView).isAppearanceLightStatusBars }
        return light
    }

    private fun navBarIconsDark(): Boolean {
        var light = false
        val a = main()
        instr.runOnMainSync { light = WindowCompat.getInsetsController(a.window, a.window.decorView).isAppearanceLightNavigationBars }
        return light
    }

    @Test
    fun darkModeFollowsThePhoneAndEveryTabStillWorks() {
        night(true)
        store.updateSettings { it.copy(themeMode = "SYSTEM") }
        seedJobs(2)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        assertTrue("the activity is in night mode", main().resources.configuration.isNightModeActive)
        assertFalse("dark app: the status bar icons must be light", statusBarIconsDark())
        for (tab in tabs) { navItem(s(tab)).performClickSafely(); navItem(s(tab)).assertIsSelected() }
        waitText(s(R.string.settings_theme))
    }

    @Test
    fun forcedDarkThemeOnALightPhoneHasLightStatusBarIcons() {
        night(false)
        store.updateSettings { it.copy(themeMode = "DARK") }
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        assertFalse("app drawn dark on a light phone: status bar icons must be light, not dark on dark", statusBarIconsDark())
        assertFalse("app drawn dark on a light phone: navigation bar icons must be light", navBarIconsDark())
    }

    @Test
    fun forcedLightThemeOnADarkPhoneHasDarkStatusBarIcons() {
        night(true)
        store.updateSettings { it.copy(themeMode = "LIGHT") }
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        assertTrue("app drawn light on a dark phone: status bar icons must be dark, not light on light", statusBarIconsDark())
    }

    // ------------------------------------------------------------------ long and RTL languages

    /** Texts cut off elsewhere (card stats, descriptions limited on purpose): logged for the report, not failed. */
    private val otherClipped = ArrayList<String>()

    /**
     * Control labels (buttons, tabs, chips, switches, checkboxes) that end in an ellipsis or do not fit
     * their width on screen. Texts outside such controls are only logged.
     */
    private fun clippedControlLabels(screen: String): List<String> {
        val out = ArrayList<String>()
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        for (n in textNodes) {
            val results = ArrayList<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val r = results.firstOrNull() ?: continue
            val ellipsized = r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1)
            // An ellipsis is what a reader sees of a cut: Compose's overflow flags are set for any text
            // laid out at its intrinsic width here, so they say nothing.
            if (!ellipsized) continue
            val line = "$screen: \"${n.config.getOrNull(SemanticsProperties.Text)?.joinToString()}\" ellipsized (${n.dp(n.size.width.toFloat())}dp)"
            if (controlRole(n) != null) out += line else otherClipped += line
        }
        return out
    }

    /** The role of the control [n] labels: the nearest ancestor (or itself) with a role, within its merged node. */
    private fun controlRole(n: SemanticsNode): Role? {
        var p: SemanticsNode? = n
        while (p != null) {
            p.config.getOrNull(SemanticsProperties.Role)?.let { return it }
            if (p.config.isMergingSemanticsOfDescendants) return null
            p = p.parent
        }
        return null
    }

    private fun visitEveryTab(): List<String> {
        val problems = ArrayList<String>()
        for (tab in tabs) {
            navItem(s(tab)).performClickSafely()
            waitFor("tab ${s(tab)}") { compose.onAllNodesWithText(s(tab)).fetchSemanticsNodes().isNotEmpty() }
            problems += clippedControlLabels(s(tab))
        }
        click(s(R.string.tab_syncs))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_source))
        problems += clippedControlLabels("editor")
        return problems
    }

    @Test
    fun germanLabelsAreNotCutOff() {
        seedJobs(2)
        setLocale("de")
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        assertEquals("de", main().resources.configuration.locales[0].language)
        val problems = visitEveryTab()
        if (otherClipped.isNotEmpty()) log("German, other cut-off texts:\n" + otherClipped.joinToString("\n"))
        assertTrue("Cut-off labels in German:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun rightToLeftLayoutIsMirroredAndLabelsFit() {
        seedJobs(2)
        setLocale("ar")
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, main().resources.configuration.layoutDirection)
        // The first tab sits on the right.
        val first: Rect = navItem(s(R.string.tab_syncs)).fetchSemanticsNode().boundsInRoot
        val lastTab: Rect = navItem(s(R.string.tab_settings)).fetchSemanticsNode().boundsInRoot
        assertTrue("tabs not mirrored: Syncs at ${first.left}, Settings at ${lastTab.left}", first.left > lastTab.left)
        val problems = visitEveryTab()
        if (otherClipped.isNotEmpty()) log("RTL, other cut-off texts:\n" + otherClipped.joinToString("\n"))
        assertTrue("Cut-off labels right-to-left:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}

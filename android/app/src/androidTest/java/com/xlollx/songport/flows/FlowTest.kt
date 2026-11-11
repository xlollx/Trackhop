package com.xlollx.songport.flows

import com.xlollx.songport.ai.clear
import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.xlollx.songport.MainActivity
import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.net.Http
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * The base of the UI flow tests: the app on fake services ([FakeWorld]), a fresh store for each
 * test, the network closed (every request through the app's HTTP client fails at once, and the
 * emulator runs in airplane mode in CI), and on a failure a screenshot in /sdcard/ui-test-failures
 * plus the semantics tree in the log. MainActivity is started from the app context as the store
 * screenshots do (ActivityScenario does not see a singleTask activity come up).
 */
abstract class FlowTest {
    protected val compose: ComposeTestRule = createEmptyComposeRule()
    protected val instr = InstrumentationRegistry.getInstrumentation()
    protected val app: Context = instr.targetContext.applicationContext
    protected val store: Store get() = Store.get(app)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(compose).around(FailureAndCleanup())

    /** Hosts the app tried to reach during the test: always refused, logged at the end. */
    protected val attemptedHosts: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Who is signed in when the test starts; a test can change it before [launch]. */
    protected open val connectedAtStart: List<String> = listOf("spotify", "deezer")

    @Before
    fun baseSetUp() {
        com.xlollx.songport.auth.AuthEvents.consume()
        // The guided tour lives in the process: none left running from the test before.
        com.xlollx.songport.ui.Tour.step.value = null
        Http.onRequest = { host -> attemptedHosts += host; throw IOException("UI test: network is closed ($host)") }
        if (Build.VERSION.SDK_INT >= 33) instr.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("settings put global hide_error_dialogs 1")
        resumed()?.let { a -> instr.runOnMainSync { a.finish() } }
        setRotation(UiAutomation.ROTATION_FREEZE_0)
        setLocale("")
        FakeWorld.reset()
        FakeWorld.connected += connectedAtStart
        FakeWorld.install()
        File(app.filesDir, "csv").deleteRecursively()
        com.xlollx.songport.ai.AiClient.clear(app)
        store.update { StoreData(settings = baseSettings()) }
        store.flush()
    }

    /**
     * Onboarding and the guided tour done, the connectors signed in, nothing that would open by itself
     * (support card, ISRC lookups, the "What's new" sheet: only WhatsNewFlowTest clears it).
     */
    protected open fun baseSettings() = Settings(
        connectors = connectedAtStart, connectorsInit = true, onboardingDone = true, tourDone = true,
        isrcLookup = false, supportPromptDone = true, appOpens = 0, themeMode = "LIGHT",
        whatsNewSeen = com.xlollx.songport.ui.WhatsNew.newestVersion,
        // The "Did you know?" pill only where TipsFlowTest asks for it.
        tipsPillDismissed = true,
    )

    // ------------------------------------------------------------------ the activity

    protected fun mainIntent(): Intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    protected fun tabIntent(tab: Int): Intent = mainIntent().putExtra(MainActivity.EXTRA_TAB, tab)

    protected fun shareIntent(text: String): Intent = mainIntent().setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    /** Starts [intent] and waits for a new MainActivity to be resumed and drawn. */
    protected fun launch(intent: Intent = mainIntent()): MainActivity {
        store.updateSettings { it.copy(appOpens = 0) }
        val before = resumed()
        val t0 = System.currentTimeMillis()
        app.startActivity(intent)
        val a = waitForNewMain(before)
        val t1 = System.currentTimeMillis()
        compose.waitForIdle()
        log("launch ${intent.action}: resumed after ${t1 - t0} ms, idle after ${System.currentTimeMillis() - t1} ms; on screen: ${screenTexts().take(40)}")
        return a
    }

    protected fun waitForNewMain(before: Activity?, ms: Long = 30_000): MainActivity {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val now = resumed()
            if (now is MainActivity && now !== before) return now
            Thread.sleep(100)
        }
        throw AssertionError("MainActivity did not come up (resumed: ${resumed()?.javaClass?.simpleName})")
    }

    protected fun resumed(): Activity? {
        var found: Activity? = null
        instr.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return found
    }

    protected fun main(): MainActivity = resumed() as? MainActivity ?: throw AssertionError("MainActivity is not on screen")

    /** Rotates the screen and waits until the app is drawn turned (MainActivity is not remade for it: the same one). */
    protected fun rotate(to: Int) {
        val before = resumed()
        val was = configOf(before)
        setRotation(to)
        rotatedAway = to != UiAutomation.ROTATION_FREEZE_0
        waitForConfigChange(before, was)
        compose.waitForIdle()
    }

    protected fun configOf(a: Activity?): Configuration? = a?.let { Configuration(it.resources.configuration) }

    /**
     * After the phone's configuration changed (turned, text size, window size, language): the activity
     * on screen has the change, whether it is the same one (MainActivity handles the screen, the
     * theme and the text size itself) or a new one (a language).
     */
    protected fun waitForConfigChange(before: Activity?, was: Configuration?, ms: Long = 30_000): MainActivity {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val now = resumed()
            if (now is MainActivity && (now !== before || was == null || now.resources.configuration.diff(was) != 0)) return now
            Thread.sleep(100)
        }
        throw AssertionError("MainActivity did not take the new configuration (resumed: ${resumed()?.javaClass?.simpleName})")
    }

    /** The test left the screen turned or the app in another language: putting them back recreates the activity. */
    private var rotatedAway = false
    private var localeChanged = false

    private fun setRotation(r: Int) { runCatching { instr.uiAutomation.setRotation(r) } }

    /** The app's language, as the in-app picker sets it ("" = the phone's). */
    protected fun setLocale(tag: String) {
        localeChanged = tag.isNotEmpty()
        if (Build.VERSION.SDK_INT < 33) {
            // Android 12 and older: the app keeps the choice itself (AppLocale) and applies it on the next activity.
            app.getSharedPreferences("app_locale", Context.MODE_PRIVATE).edit().putString("tag", tag).commit()
            return
        }
        val lm = app.getSystemService(LocaleManager::class.java)
        val want = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        if (lm.applicationLocales != want) lm.applicationLocales = want
    }

    protected fun shell(cmd: String): String =
        instr.uiAutomation.executeShellCommand(cmd).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() } }

    // ------------------------------------------------------------------ strings and nodes

    /** A string in the language the app is shown in now. */
    protected fun s(id: Int, vararg args: Any): String = (resumed() ?: app).getString(id, *args)

    protected fun plural(id: Int, n: Int, vararg args: Any): String = (resumed() ?: app).resources.getQuantityString(id, n, *args)

    protected fun waitText(text: String, ms: Long = 15_000, substring: Boolean = false) =
        waitFor("text \"$text\"", ms) { compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** A tile of the editor's source or target row, which scrolls sideways: off screen on a phone, the row goes to it first. */
    protected fun clickTile(label: String) {
        val tile = androidx.compose.ui.test.hasText(label)
        compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(tile) }
        }
        compose.waitForIdle()
        click(label)
    }

    protected fun waitGone(text: String, ms: Long = 15_000, substring: Boolean = false) =
        waitFor("no text \"$text\"", ms) { compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    protected fun waitNode(what: String, matcher: SemanticsMatcher, ms: Long = 15_000) =
        waitFor(what, ms) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitFor(what: String, ms: Long = 15_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(ms) { runCatching(condition).getOrDefault(false) }
        } catch (e: Throwable) {
            throw AssertionError("Timed out after $ms ms waiting for $what", e)
        }
    }

    /** Every text on screen now, in all windows (for the log). */
    protected fun screenTexts(): List<String> = runCatching {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }
    }.getOrDefault(emptyList())

    protected fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The first clickable node showing or named [text] (a button, a row, a menu entry), scrolled into view and tapped. */
    protected fun click(text: String, substring: Boolean = false) =
        tap(compose.onAllNodes(hasClickAction() and (hasText(text, substring = substring) or hasContentDescription(text, substring = substring))).onFirst(), text)

    protected fun clickDesc(desc: String, index: Int = 0) =
        tap(compose.onAllNodes(hasClickAction() and hasContentDescription(desc))[index], desc)

    /** The way back from a page: its arrow, named "Back" (or "Cancel" where leaving drops the edits). */
    protected fun back() {
        val nav = compose.onAllNodes(hasClickAction() and hasContentDescription(s(com.xlollx.songport.R.string.nav_back))).fetchSemanticsNodes()
        if (nav.isNotEmpty()) clickDesc(s(com.xlollx.songport.R.string.nav_back)) else clickDesc(s(com.xlollx.songport.R.string.cancel))
    }

    /** Touches that could not be injected and were replaced by the node's click action (logged, see [tap]). */
    protected val semanticClicks = ArrayList<String>()

    /**
     * Scrolls [n] into view, clear of the glass bottom bar, and taps it. Should the touch not be
     * injectable, the node's own click action is used instead, and that is logged.
     */
    protected fun tap(n: SemanticsNodeInteraction, what: String) {
        runCatching { n.performScrollTo() }
        clearOfBottomBar(n)
        clearOfPinnedPill(n)
        settle(n)
        try {
            n.performClick()
        } catch (e: AssertionError) {
            if (e.message?.contains("inject") != true) throw e
            semanticClicks += what
            log("touch on \"$what\" could not be injected: using its click action")
            n.performSemanticsAction(SemanticsActions.OnClick)
        }
        compose.waitForIdle()
    }

    /**
     * Waits until [n] stands still. A page ends above the keyboard, so while the keyboard slides in
     * or out (a field was just typed in) the page is still moving and a touch aimed at where the
     * node was would land beside it; should the move push the node out of view, it is scrolled to again.
     */
    private fun settle(n: SemanticsNodeInteraction) {
        // A field has the focus and the keyboard is not up yet: it is on its way (it takes the
        // emulator up to a second), and the page will move when it arrives.
        val typing = runCatching {
            compose.onAllNodes(hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
        if (typing && keyboardHeight() == 0) {
            val shown = System.currentTimeMillis() + 2_000
            while (keyboardHeight() == 0 && System.currentTimeMillis() < shown) Thread.sleep(50)
        }
        var last = (runCatching { n.fetchSemanticsNode().boundsInWindow }.getOrNull() ?: return) to keyboardHeight()
        var still = 0
        var moves = 0
        var stuck = 0
        val end = System.currentTimeMillis() + 5_000
        while (still < 3 && System.currentTimeMillis() < end) {
            Thread.sleep(80)
            compose.waitForIdle()
            val node = runCatching { n.fetchSemanticsNode() }.getOrNull() ?: return
            val now = node.boundsInWindow to keyboardHeight()
            // In view: what shows of it (its bounds, cut to its scroller) is all of it. The field being
            // typed in is kept in view by the page as the keyboard arrives, which can push the node out again.
            val inView = node.boundsInWindow.height >= node.size.height - 1 && node.boundsInWindow.width >= node.size.width - 1
            when {
                now == last && inView -> still++
                // Cut by something that does not scroll (a card under the pinned pill): as much in view as it gets.
                now == last && ++stuck >= 4 -> break
                else -> { still = 0; moves++; last = now; runCatching { n.performScrollTo() }; clearOfBottomBar(n); clearOfPinnedPill(n) }
            }
        }
        if (moves > 0) log("settle: moved $moves times, in the end ${runCatching { n.fetchSemanticsNode().boundsInWindow }.getOrNull()} with the keyboard at ${keyboardHeight()} px, still=$still")
    }

    /** How much of the window the keyboard covers now, in pixels (0 = not shown). */
    private fun keyboardHeight(): Int {
        var h = 0
        runCatching {
            val a = resumed() ?: return 0
            instr.runOnMainSync {
                h = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            }
        }
        return h
    }


    private fun topOf(n: SemanticsNode): SemanticsNode { var p = n; while (p.parent != null) p = p.parent!!; return p }

    /**
     * performScrollTo leaves a node at the bottom edge of its scroller, which on the tabs runs under
     * the glass bar (the bar would get the tap): the scroller moves it up above the bar.
     */
    protected fun clearOfBottomBar(n: SemanticsNodeInteraction) {
        val node = runCatching { n.fetchSemanticsNode() }.getOrNull() ?: return
        val root = topOf(node).id
        val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes().filter { topOf(it).id == root }
        if (tabs.any { it.id == node.id }) return
        // On its side (and on a tablet) the tabs are a rail at the left: nothing lies over the end of the page.
        if (tabs.map { it.boundsInRoot.top }.distinct().size > 1) return
        val top = tabs.minOfOrNull { it.boundsInRoot.top } ?: return
        if (node.boundsInRoot.bottom <= top) return
        var p = node.parent
        while (p != null && !p.config.contains(SemanticsActions.ScrollBy)) p = p.parent
        val scroll = p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action ?: return
        val dy = node.boundsInRoot.bottom - top + 24 * app.resources.displayMetrics.density
        compose.runOnIdle { scroll(0f, dy) }
        compose.waitForIdle()
    }

    /** Where the pinned Sync-all pill's background ends (its fade included), in root pixels; null while it is not pinned. */
    protected fun pinnedPillBottom(): Float? =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(com.xlollx.songport.ui.RUN_ALL_BAND_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
            ?.let { it.boundsInRoot.bottom + com.xlollx.songport.ui.PILL_FADE.value * app.resources.displayMetrics.density }

    /**
     * performScrollTo leaves a node scrolled up at the top edge of the sync list, which is under the
     * pinned Sync-all pill and its background (they take the touch, the card beneath shows nowhere):
     * the list moves it down to below the pill's fade.
     */
    protected fun clearOfPinnedPill(n: SemanticsNodeInteraction) {
        val node = runCatching { n.fetchSemanticsNode() }.getOrNull() ?: return
        val root = topOf(node).id
        val band = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(com.xlollx.songport.ui.RUN_ALL_BAND_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull { topOf(it).id == root } ?: return
        // The pill and its "?" are in the band: they are what is tapped there.
        var a: SemanticsNode? = node
        while (a != null) { if (a.id == band.id) return; a = a.parent }
        val density = app.resources.displayMetrics.density
        val bottom = band.boundsInRoot.bottom + com.xlollx.songport.ui.PILL_FADE.value * density
        if (node.boundsInRoot.top >= bottom || node.boundsInRoot.bottom <= band.boundsInRoot.top) return
        var p = node.parent
        while (p != null && !p.config.contains(SemanticsActions.ScrollBy)) p = p.parent
        val scroll = p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action ?: return
        val dy = bottom - node.boundsInRoot.top + 8 * density
        compose.runOnIdle { scroll(0f, -dy) }
        compose.waitForIdle()
    }

    protected fun scrollTo(node: SemanticsNodeInteraction): SemanticsNodeInteraction = node.also { runCatching { it.performScrollTo() } }

    protected fun SemanticsNode.dp(px: Float): Float = px / app.resources.displayMetrics.density

    protected fun log(msg: String) = Log.i("UiFlow", msg)

    // ------------------------------------------------------------------ failures

    /**
     * Puts rotation and language back, then waits until the app is still. This runs inside the compose
     * rule, whose teardown drains the frames still queued on its test clock from the instrumentation
     * thread, laying out every compose root there: had the phone just been turned back, the activity
     * (recreated for a language, redrawn for a rotation) would be in its own layout on the main thread at that moment, and Compose throws
     * "performMeasureAndLayout called during measure layout" (CardsFlowTest.twoSharedLinksAreComparedAtOnce
     * on API 34, ending in landscape). Waiting for the new activity and for idle here leaves no frame
     * queued and no layout under way.
     */
    private fun restoreAndSettle() {
        val recreates = rotatedAway || (localeChanged && Build.VERSION.SDK_INT >= 33)
        val before = if (recreates) runCatching { resumed() }.getOrNull() else null
        val was = configOf(before)
        if (rotatedAway) setRotation(UiAutomation.ROTATION_FREEZE_0)
        if (localeChanged) runCatching { setLocale("") }
        rotatedAway = false; localeChanged = false
        if (before is MainActivity) runCatching { waitForConfigChange(before, was, ms = 10_000) }
        runCatching { compose.waitForIdle() }
    }

    /** The screen as it is now, kept with the failure screenshots (ui-out/failures): a preview for review. */
    protected fun snapshot(name: String) {
        runCatching {
            shell("mkdir -p /sdcard/ui-test-failures")
            shell("screencap -p /sdcard/ui-test-failures/preview-$name.png")
        }
    }

    /** On failure: the screen to a PNG and the semantics tree to the log. Always: rotation and locale put back. */
    private inner class FailureAndCleanup : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    val name = "${description.testClass.simpleName}.${description.methodName}"
                    runCatching {
                        shell("mkdir -p /sdcard/ui-test-failures")
                        shell("screencap -p /sdcard/ui-test-failures/$name.png")
                    }
                    runCatching {
                        // Where the app's own threads are: a request or a lock that never returns shows here.
                        Thread.getAllStackTraces().filter { (t, st) -> st.any { it.className.startsWith("com.xlollx.songport") && !it.className.contains(".flows.") } }
                            .forEach { (t, st) -> Log.e("UiFlowThreads", "$name ${t.name}\n" + st.take(40).joinToString("\n") { "  at $it" }) }
                        // A coroutine waiting on another thread shows no app frame: the main thread and
                        // every busy worker (network, coroutines) too, idle pool threads left out.
                        val idle = listOf("CoroutineScheduler\$Worker.park", "CoroutineScheduler\$Worker.tryPark", "LinkedBlockingQueue.take", "SynchronousQueue", "nativePollOnce")
                        Thread.getAllStackTraces().filter { (t, st) ->
                            t === android.os.Looper.getMainLooper().thread ||
                                (st.any { f -> f.className.startsWith("okhttp3") || f.className.startsWith("kotlinx.coroutines") || f.className.startsWith("java.net") } &&
                                    st.none { f -> idle.any { "${f.className}.${f.methodName}".contains(it) } })
                        }.forEach { (t, st) -> Log.e("UiFlowThreads", "$name busy ${t.name} (${t.state})\n" + st.take(30).joinToString("\n") { "  at $it" }) }
                    }
                    runCatching {
                        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                            compose.onAllNodes(isRoot())[i].printToString(maxDepth = Int.MAX_VALUE).chunked(3500).forEach { Log.e("UiFlowTree", "$name root $i\n$it") }
                        }
                    }
                    throw t
                } finally {
                    if (semanticClicks.isNotEmpty()) log("${description.methodName}: touch not injectable on $semanticClicks")
                    if (attemptedHosts.isNotEmpty()) log("${description.methodName}: refused network requests to $attemptedHosts")
                    restoreAndSettle()
                }
            }
        }
    }
}

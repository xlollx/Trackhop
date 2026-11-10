package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tall dialogs on a phone on its side (about 400 dp high): wider than Material's narrow dialog,
 * their content scrolling so the last field can be reached, and their buttons side by side and
 * always in sight, also with the text made larger.
 */
@RunWith(AndroidJUnit4::class)
class LandscapeDialogsFlowTest : FlowTest() {

    private fun density() = app.resources.displayMetrics.density

    /** The parts of a node that show are all of it: nothing cut by its scroller or the dialog's edge. */
    private fun whole(n: SemanticsNode) = n.boundsInRoot.height >= n.size.height - 1 && n.boundsInRoot.width >= n.size.width - 1

    private fun button(id: Int): SemanticsNodeInteraction = compose.onAllNodes(hasClickAction() and hasText(s(id))).onLast()

    private fun assertShown(what: String, n: SemanticsNodeInteraction) {
        n.assertIsDisplayed()
        assertTrue("$what is cut", whole(n.fetchSemanticsNode()))
    }

    /** Settings' Theme row, scrolled to: on its side with larger text the page is long. */
    private fun themeRow() {
        val theme = s(R.string.settings_theme)
        waitText(theme, 30_000)
        scrollTo(compose.onAllNodes(hasText(theme)).onFirst())
    }

    /** Settings, turned on its side. */
    private fun settingsOnItsSide() {
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        themeRow()
        rotate(UiAutomation.ROTATION_FREEZE_90)
        themeRow()
        val config = main().resources.configuration
        assertTrue("a low window", WindowLayout.of(config.screenWidthDp, config.screenHeightDp).short)
    }

    private fun yourAiOnItsSide(name: String) {
        settingsOnItsSide()
        click(s(R.string.ai_setup_title))
        waitText(s(R.string.ai_vendor))
        snapshot(name)
        // Wider than Material's 560 dp dialog: the key field alone is wider than its content would be.
        val key = compose.onNode(hasSetTextAction() and hasText(s(R.string.ai_key)))
        val keyWidth = key.fetchSemanticsNode().size.width / density()
        assertTrue("the dialog is wide (key field $keyWidth dp)", keyWidth > 520)
        // Save and Cancel in one row, in sight from the start.
        assertShown("Save", button(R.string.save))
        assertShown("Cancel", button(R.string.cancel))
        assertEquals(button(R.string.save).fetchSemanticsNode().boundsInRoot.top, button(R.string.cancel).fetchSemanticsNode().boundsInRoot.top, 1f)
        // Every field down to the last button of the body can be reached, the dialog's buttons still in sight.
        for (field in listOf(R.string.ai_key, R.string.ai_model)) {
            assertShown(s(field), compose.onNode(hasSetTextAction() and hasText(s(field))).also { it.performScrollTo() })
        }
        assertShown(s(R.string.ai_check), compose.onAllNodes(hasText(s(R.string.ai_check)) and hasClickAction()).onFirst().also { it.performScrollTo() })
        assertShown("Save after scrolling", button(R.string.save))
        click(s(R.string.cancel))
        waitGone(s(R.string.ai_vendor))
    }

    @Test
    fun yourAiOnItsSideReachesEveryField() = yourAiOnItsSide("ai-dialog-landscape")

    @Test
    fun yourAiOnItsSideWithLargerText() {
        try {
            fontScale(1.3f)
            yourAiOnItsSide("ai-dialog-landscape-large-text")
        } finally {
            fontScale(1.0f)
        }
    }

    /**
     * Sets the phone's text size and waits until the app has it: the change reaches the app a moment
     * later and recreates the activity on screen. Started (or rotated) before that, the activity was
     * replaced under the test and Settings was not found in time (CI, 10/11).
     */
    private fun fontScale(scale: Float) {
        if (kotlin.math.abs(app.resources.configuration.fontScale - scale) <= 0.01f) return
        val before = resumed()
        shell("settings put system font_scale $scale")
        val end = System.currentTimeMillis() + 15_000
        while (kotlin.math.abs(app.resources.configuration.fontScale - scale) > 0.01f) {
            if (System.currentTimeMillis() > end) throw AssertionError("font scale $scale not applied (${app.resources.configuration.fontScale})")
            Thread.sleep(100)
        }
        // An activity on screen is recreated with the new size: wait for the new one.
        if (before is MainActivity) {
            waitForNewMain(before)
            compose.waitForIdle()
        }
    }

    @Test
    fun theLanguageListOnItsSideReachesTheLastLanguage() {
        settingsOnItsSide()
        click(s(R.string.settings_language))
        waitText("Deutsch")
        snapshot("language-dialog-landscape")
        val options = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        assertTrue("a list of languages", options.fetchSemanticsNodes().size > 5)
        assertShown("the last language", options.onLast().also { it.performScrollTo() })
        assertShown("Cancel after scrolling", button(R.string.cancel))
        click(s(R.string.cancel))
    }

    @Test
    fun thePastedListOnItsSideReachesItsButton() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.files_title))
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText(s(R.string.files_title))
        click(s(R.string.files_title))
        click(s(R.string.paste_list))
        waitText(s(R.string.paste_list_hint))
        snapshot("paste-dialog-landscape")
        assertShown("the list field", compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_text))).also { it.performScrollTo() })
        assertShown("Import", button(R.string.import_action))
        assertShown("Cancel", button(R.string.cancel))
        click(s(R.string.cancel))
    }
}

package com.xlollx.songport.flows

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaving the app and coming back changes nothing on screen (owner's rule, 14/11): with Home and the
 * app's icon, and when Android makes the activity anew meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class StateKeptFlowTest : FlowTest() {

    /** What Android does to an app left in the background: the activity made anew from its saved state. */
    private fun remade() {
        val before = main()
        instr.runOnMainSync { before.recreate() }
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && (resumed() === before || resumed() !is MainActivity)) Thread.sleep(100)
        compose.waitForIdle()
    }

    /** Home, then the app's icon, as a person does it. */
    private fun homeAndBack() {
        shell("input keyevent KEYCODE_HOME")
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && resumed() != null) Thread.sleep(100)
        Thread.sleep(800)
        app.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        val back = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < back && resumed() !is MainActivity) Thread.sleep(100)
        Thread.sleep(800)
        compose.waitForIdle()
    }

    @Test
    fun aChannelBeingAddedSurvivesTheActivityMadeAnew() {
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        scrollTo(compose.onNode(hasText(s(R.string.notify_channels_title))))
        click(s(R.string.notify_channels_title))
        click(s(R.string.notify_channel_add))
        click("Telegram")
        // The new channel's name field holds its kind's name; the fields come in order: name, token, chat id.
        waitNode("the channel's name field", hasSetTextAction() and hasText("Telegram"))
        compose.onNode(hasSetTextAction() and hasText("Telegram")).performTextReplacement("My phone")
        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("123456789")
        compose.waitForIdle()
        remade()
        waitNode("the name kept", hasSetTextAction() and hasText("My phone"))
        waitNode("the chat id kept", hasSetTextAction() and hasText("123456789"))
        homeAndBack()
        waitNode("the name kept after Home", hasSetTextAction() and hasText("My phone"))
    }

    @Test
    fun theAiSetupStaysOpenAfterHome() {
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        scrollTo(compose.onNode(hasText(s(R.string.ai_setup_title))))
        click(s(R.string.ai_setup_title))
        waitText(s(R.string.ai_vendor))
        homeAndBack()
        snapshot("ai-setup-after-home")
        waitText(s(R.string.ai_vendor))
        remade()
        waitText(s(R.string.ai_vendor))
    }
}

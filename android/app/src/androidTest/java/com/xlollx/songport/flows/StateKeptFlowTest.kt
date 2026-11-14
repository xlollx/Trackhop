package com.xlollx.songport.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaving the app and coming back changes nothing on screen (owner's rule, 14/11), also when
 * Android makes the activity anew meanwhile: a channel being added is still open and filled in.
 */
@RunWith(AndroidJUnit4::class)
class StateKeptFlowTest : FlowTest() {

    @Test
    fun aChannelBeingAddedSurvivesTheActivityMadeAnew() {
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        scrollTo(compose.onNode(hasText(s(R.string.notify_channels_title))))
        click(s(R.string.notify_channels_title))
        click(s(R.string.notify_channel_add))
        click("Telegram")
        val name = hasSetTextAction() and hasText(s(R.string.notify_channel_name))
        waitNode("the channel's name field", name)
        compose.onNode(name).performTextReplacement("My phone")
        compose.onNode(hasSetTextAction() and hasText("Chat id", substring = true)).performTextReplacement("123456789")
        compose.waitForIdle()

        // What Android does to an app left in the background: the activity made anew from its saved state.
        val before = main()
        instr.runOnMainSync { before.recreate() }
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && (resumed() === before || resumed() !is MainActivity)) Thread.sleep(100)
        compose.waitForIdle()

        waitNode("the name kept", hasSetTextAction() and hasText("My phone"))
        waitNode("the chat id kept", hasSetTextAction() and hasText("123456789"))
    }
}

package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** First run: the three-page welcome, its page indicator, Skip, the first service to connect, and "show the welcome again". */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest : FlowTest() {
    override val connectedAtStart = emptyList<String>()
    override fun baseSettings() = super.baseSettings().copy(onboardingDone = false)

    @Test
    fun pagerWalksThroughThreePagesAndOpensTheChoiceOfTheFirstService() {
        launch()
        waitText(s(R.string.onboarding_title))
        compose.onNodeWithContentDescription(s(R.string.onboarding_page, 1, 3)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onboarding_skip)).assertIsDisplayed()

        click(s(R.string.onboarding_next))
        waitNode("page 2 of 3", hasContentDescription(s(R.string.onboarding_page, 2, 3)))
        compose.onNodeWithText(s(R.string.onboarding_step1_title)).assertIsDisplayed()

        // The page survives a rotation.
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitNode("page 2 of 3 after rotation", hasContentDescription(s(R.string.onboarding_page, 2, 3)))
        rotate(UiAutomation.ROTATION_FREEZE_0)

        click(s(R.string.onboarding_next))
        waitNode("page 3 of 3", hasContentDescription(s(R.string.onboarding_page, 3, 3)))
        compose.onNodeWithText(s(R.string.security_title)).assertIsDisplayed()
        assertFalse("not done until the last button", store.data.settings.onboardingDone)
        // On the last page Skip gives way to the guided tour, and the main button connects a service.
        assertFalse("Skip still shown on the last page", exists(s(R.string.onboarding_skip)))
        assertTrue(exists(s(R.string.tour_offer)))
        click(s(R.string.onboarding_connect_first))

        // The choice of the first service, as "New sync" with nothing connected opens it.
        waitText(s(R.string.first_sync_title))
        assertTrue(store.data.settings.onboardingDone)
    }

    @Test
    fun skipGoesStraightToTheAppAndTheWelcomeCanBeShownAgain() {
        launch()
        waitText(s(R.string.onboarding_title))
        click(s(R.string.onboarding_skip))
        waitText(s(R.string.empty_syncs_title))
        assertTrue(store.data.settings.onboardingDone)

        // Settings > About > "Show the welcome again".
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_show_onboarding))
        click(s(R.string.settings_show_onboarding))
        waitText(s(R.string.onboarding_title))
        assertFalse(store.data.settings.onboardingDone)
    }
}

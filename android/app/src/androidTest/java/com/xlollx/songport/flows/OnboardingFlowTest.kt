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

/** First run: the four-page welcome, its page indicator, Skip, Start, and "show the welcome again". */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest : FlowTest() {
    override val connectedAtStart = emptyList<String>()
    override fun baseSettings() = super.baseSettings().copy(onboardingDone = false)

    @Test
    fun pagerWalksThroughFivePagesAndStartsTheApp() {
        launch()
        waitText(s(R.string.onboarding_title))
        compose.onNodeWithContentDescription(s(R.string.onboarding_page, 1, 5)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onboarding_skip)).assertIsDisplayed()

        click(s(R.string.onboarding_next))
        waitNode("page 2 of 5", hasContentDescription(s(R.string.onboarding_page, 2, 5)))
        compose.onNodeWithText(s(R.string.onboarding_step1_title)).assertIsDisplayed()

        // The page survives a rotation.
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitNode("page 2 of 5 after rotation", hasContentDescription(s(R.string.onboarding_page, 2, 5)))
        rotate(UiAutomation.ROTATION_FREEZE_0)

        click(s(R.string.onboarding_next))
        waitNode("page 3 of 5", hasContentDescription(s(R.string.onboarding_page, 3, 5)))
        compose.onNodeWithText(s(R.string.onboarding_discover_title)).assertIsDisplayed()

        click(s(R.string.onboarding_next))
        waitNode("page 4 of 5", hasContentDescription(s(R.string.onboarding_page, 4, 5)))
        compose.onNodeWithText(s(R.string.security_title)).assertIsDisplayed()
        assertTrue("Skip still offered before the last page", exists(s(R.string.onboarding_skip)))

        click(s(R.string.onboarding_next))
        waitNode("page 5 of 5", hasContentDescription(s(R.string.onboarding_page, 5, 5)))
        // Where the documentation and the code are: a link to open, or not.
        compose.onNodeWithText(s(R.string.onboarding_docs_title)).assertIsDisplayed()
        assertTrue(exists(s(R.string.onboarding_docs_site_desc)))
        assertTrue(exists(s(R.string.onboarding_docs_github_desc)))
        assertFalse("not done until Start", store.data.settings.onboardingDone)
        // On the last page Skip gives way to Start.
        assertFalse("Skip still shown on the last page", exists(s(R.string.onboarding_skip)))
        click(s(R.string.onboarding_start))

        waitText(s(R.string.empty_syncs_title))
        assertTrue(store.data.settings.onboardingDone)
        // Nothing connected yet: the empty Syncs tab sends to Accounts.
        click(s(R.string.empty_syncs_cta_accounts))
        waitText(s(R.string.connectors_empty_title))
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

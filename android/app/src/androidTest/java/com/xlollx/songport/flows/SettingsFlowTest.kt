package com.xlollx.songport.flows

import android.app.LocaleManager
import android.os.Build
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings: Material You on and off, the "new look" of the sync cards, the theme, and the in-app language. */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest : FlowTest() {

    private fun switchRow(title: String) = compose.onNode(isToggleable() and hasText(title)).also { it.performScrollTo(); clearOfBottomBar(it) }

    @Test
    fun materialYouTurnsOnAndOff() {
        assumeTrue("Material You needs Android 12", Build.VERSION.SDK_INT >= 31)
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_dynamic_color))
        switchRow(s(R.string.settings_dynamic_color)).assertIsOff().performClick()
        waitFor("dynamic colour on") { store.data.settings.dynamicColor }
        switchRow(s(R.string.settings_dynamic_color)).assertIsOn()
        // The whole screen is recoloured, nothing lost: the section is still there.
        waitText(s(R.string.settings_section_appearance))
        switchRow(s(R.string.settings_dynamic_color)).performClick()
        waitFor("dynamic colour off") { !store.data.settings.dynamicColor }
        switchRow(s(R.string.settings_dynamic_color)).assertIsOff()
    }

    @Test
    fun newLookSwitchesTheSyncCardBetweenNumbersAndASentence() {
        val report = SyncReport(id = "r1", jobId = "job-1", jobName = "Road Trip", startedEpoch = System.currentTimeMillis() - 60_000,
            durationMs = 12_000, sourceCount = 8, targetCount = 6, added = 6, removed = 0, unmatched = listOf("a", "b"))
        store.update { d ->
            d.copy(
                jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "x", "Road Trip"), lastRunEpoch = report.startedEpoch, lastReportId = "r1")),
                reports = listOf(report),
            )
        }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("+6")
        val sentence = Notifications.summary(main(), report)
        assertFalse(exists(sentence))

        click(s(R.string.tab_settings))
        openCardStyle()
        switchRow(s(R.string.settings_new_look)).assertIsOn().performClick()
        waitFor("new look off") { !store.data.settings.newLook }
        click(s(R.string.tab_syncs))
        waitText(sentence)
        assertFalse("no big numbers in the old look", exists("+6"))

        click(s(R.string.tab_settings))
        openCardStyle()
        switchRow(s(R.string.settings_new_look)).performClick()
        click(s(R.string.tab_syncs))
        waitText("+6")
    }

    @Test
    fun themeChoiceIsSavedAndShown() {
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        // The Theme row opens a choice of three; the row then says the one chosen.
        click(s(R.string.settings_theme))
        waitText(s(R.string.settings_theme_dark))
        click(s(R.string.settings_theme_dark))
        waitFor("dark theme saved") { store.data.settings.themeMode == "DARK" }
        waitFor("the row says Dark") { compose.onAllNodes(hasClickAction() and hasText(s(R.string.settings_theme)) and hasText(s(R.string.settings_theme_dark))).fetchSemanticsNodes().isNotEmpty() }
        click(s(R.string.settings_theme))
        waitText(s(R.string.settings_theme_system))
        compose.onAllNodes(hasClickAction() and hasText(s(R.string.settings_theme_dark)) and androidx.compose.ui.test.isSelectable()).onFirst().assertIsSelected()
        click(s(R.string.settings_theme_system))
        waitFor("system theme saved") { store.data.settings.themeMode == "SYSTEM" }
    }

    @Test
    fun languageIsChosenInTheApp() {
        assumeTrue("per-app language needs Android 13", Build.VERSION.SDK_INT >= 33)
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_language))
        val before = main()
        click(s(R.string.settings_language))
        waitText("Deutsch")
        click("Deutsch")
        // The activity comes back in German, still on Settings.
        waitForNewMain(before)
        waitText("Einstellungen", ms = 20_000)
        waitText("Sprache")
        assertEquals("de", app.getSystemService(LocaleManager::class.java).applicationLocales[0]?.language)

        // And back to the phone's language, from the first entry of the same list.
        val german = main()
        click("Sprache")
        val systemLabel = main().getString(R.string.settings_language_system)
        waitText(systemLabel)
        click(systemLabel)
        waitForNewMain(german)
        waitText(s(R.string.tab_settings), ms = 20_000)
        assertTrue(app.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
    }

    /** The card options sit under "Card style": opened when closed. */
    private fun openCardStyle() {
        waitText(s(R.string.settings_card_style))
        if (!exists(s(R.string.settings_new_look))) click(s(R.string.settings_card_style))
        waitText(s(R.string.settings_new_look))
    }
}

package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Did you know?" pill on Syncs: shown after a completed sync, it opens the page on widgets,
 * shortcuts and automation, and once closed (or opened) it stays away. The page is also in
 * Settings › Help, and leads to the automation switch.
 */
@RunWith(AndroidJUnit4::class)
class TipsFlowTest : FlowTest() {

    override fun baseSettings() = super.baseSettings().copy(tipsPillDismissed = false)

    private fun oneRun() {
        val report = SyncReport(id = "r1", jobId = "job-1", jobName = "Road Trip", startedEpoch = System.currentTimeMillis() - 60_000,
            durationMs = 9_000, sourceCount = 8, targetCount = 8, added = 8, removed = 0)
        store.update { d ->
            d.copy(
                jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d1", "Road Trip"), lastRunEpoch = report.startedEpoch, lastReportId = "r1")),
                reports = listOf(report),
            )
        }
        store.flush()
    }

    private fun assertThreeSections() {
        waitText(s(R.string.tips_intro))
        waitText(s(R.string.tips_widgets_title))
        waitText(s(R.string.tips_shortcuts_title))
        waitText(s(R.string.tips_automation_title))
        // What the shortcuts are, as in shortcuts.xml.
        waitText(s(R.string.shortcut_sync_all_long))
        waitText(s(R.string.shortcut_new_long))
    }

    @Test
    fun noPillBeforeAnySyncHasRun() {
        store.update { d ->
            d.copy(jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d1", "Road Trip"))))
        }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        assertFalse(exists(s(R.string.tips_pill)))
    }

    @Test
    fun pillOpensThePageAndDoesNotComeBack() {
        oneRun()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        waitText(s(R.string.tips_pill))

        click(s(R.string.tips_pill))
        waitText(s(R.string.tips_title))
        assertThreeSections()
        // Opening it counts as seen.
        waitFor("pill marked dismissed") { store.data.settings.tipsPillDismissed }

        back()
        waitText("Road Trip")
        assertFalse(exists(s(R.string.tips_pill)))
    }

    @Test
    fun closingThePillHidesItForGood() {
        oneRun()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.tips_pill))

        clickDesc(s(R.string.tips_pill_dismiss))
        waitGone(s(R.string.tips_pill))
        waitFor("pill marked dismissed") { store.data.settings.tipsPillDismissed }
        store.flush()

        // Still gone after the app is opened again.
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        assertFalse(exists(s(R.string.tips_pill)))
        assertTrue(store.data.settings.tipsPillDismissed)

        // The page stays reachable from Settings › Help, and leads to the automation switch.
        click(s(R.string.tab_settings))
        waitText(s(R.string.settings_theme))
        click(s(R.string.settings_tips_row))
        waitText(s(R.string.tips_title))
        assertThreeSections()
        click(s(R.string.tips_automation_open))
        waitText(s(R.string.settings_automation))
        waitGone(s(R.string.tips_intro))
        assertFalse("automation stays off until switched on", store.data.settings.automation)
    }
}

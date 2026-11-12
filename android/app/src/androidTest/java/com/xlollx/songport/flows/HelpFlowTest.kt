package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-app help: a "?" opens the sheet of the setting it sits next to (and only that one), the "?"
 * of a list explains its parts, and Settings › How to lists the guides and opens them.
 */
@RunWith(AndroidJUnit4::class)
class HelpFlowTest : FlowTest() {

    private fun help(label: String) = s(R.string.help_cd, label)

    @Test
    fun aQuestionMarkOpensTheSheetOfItsSetting() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_source))

        // Mirror removals: its own text, with an example, and not the text of the switch next to it.
        clickDesc(help(s(R.string.editor_mirror)))
        waitText(s(R.string.help_mirror_body))
        waitText(s(R.string.help_example))
        waitText(s(R.string.help_mirror_example))
        assertFalse(exists(s(R.string.help_two_way_body)))
        androidx.test.espresso.Espresso.pressBack()
        waitGone(s(R.string.help_mirror_body))

        // Two-way: another "?", another sheet, and from it the longer guide, in place.
        clickDesc(help(s(R.string.editor_bidirectional)))
        waitText(s(R.string.help_two_way_body))
        assertFalse(exists(s(R.string.help_mirror_body)))
        click(s(R.string.help_more, s(R.string.howto_modes_title)))
        waitText(heading(R.string.howto_modes_body, 1))
        // The arrow back from the guide is the short text again; the editor under it is untouched.
        clickDesc(s(R.string.nav_back))
        waitText(s(R.string.help_two_way_body))
    }

    @Test
    fun howToListsTheGuidesAndOpensOne() {
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        click(s(R.string.howto_title))
        waitText(s(R.string.howto_intro))
        com.xlollx.songport.ui.HowTo.shown().forEach { waitText(s(it.title)) }
        // 1.18's guides come with it (com.xlollx.songport.Features).
        com.xlollx.songport.ui.HowTo.V2.forEach { assertFalse(it.name, exists(s(it.title))) }

        click(s(R.string.howto_preview_title))
        waitText(heading(R.string.howto_preview_body, 1))
        assertFalse("the list is gone", exists(s(R.string.howto_intro)))
        // Back goes to the list, then out to Settings.
        back()
        waitText(s(R.string.howto_intro))
        back()
        waitText(s(R.string.settings_theme))
    }

    /** The [n]th heading ("# …" line) of a guide's text, as shown. */
    private fun heading(res: Int, n: Int): String = s(res).lines().filter { it.startsWith("# ") }[n - 1].removePrefix("# ")

    @Test
    fun theSyncListQuestionMarkExplainsTheCard() {
        val report = SyncReport(id = "r1", jobId = "job-1", jobName = "Road Trip", startedEpoch = System.currentTimeMillis() - 60_000,
            durationMs = 9_000, sourceCount = 8, targetCount = 6, added = 6, removed = 0, unmatched = listOf("a", "b"))
        store.update { d ->
            d.copy(
                jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d1", "Road Trip"), lastRunEpoch = report.startedEpoch, lastReportId = "r1")),
                reports = listOf(report),
            )
        }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        clickDesc(help(s(R.string.help_cards_title)))
        waitText(s(R.string.help_status_body))
        waitText(s(R.string.help_route_body))
    }
}

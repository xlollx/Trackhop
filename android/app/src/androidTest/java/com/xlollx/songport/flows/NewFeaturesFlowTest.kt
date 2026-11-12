package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.Demo
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 1.1 additions, lightly: a sync's colour, the tappable figures of its last run, find and
 * filter with many syncs, the "Sync all" pill, "Trackhop in numbers", and a song shared from Shazam.
 */
@RunWith(AndroidJUnit4::class)
class NewFeaturesFlowTest : FlowTest() {

    /** [n] syncs from Spotify to Deezer, each with one completed run that added two songs. */
    private fun seedJobs(n: Int, colors: Map<Int, String> = emptyMap()) {
        val now = System.currentTimeMillis()
        val names = listOf("Road Trip", "Chill Evening", "Workout Mix", "Party Night", "Morning Run", "Focus", "Late Drive")
        val jobs = (0 until n).map { i ->
            SyncJob("job-$i", names[i], PlaylistRef("spotify", "pl$i", names[i]), PlaylistRef("deezer", "d$i", names[i]),
                lastRunEpoch = now - (i + 1) * 60_000L, lastReportId = "r$i", color = colors[i])
        }
        val reports = (0 until n).map { i ->
            SyncReport(id = "r$i", jobId = "job-$i", jobName = names[i], startedEpoch = now - (i + 1) * 60_000L, durationMs = 9_000,
                sourceCount = 8, targetCount = 8, added = 2, addedTracks = listOf(FakeWorld.song("deezer", i), FakeWorld.song("deezer", i + 1)))
        }
        store.update { it.copy(jobs = jobs, reports = reports) }
    }

    @Test
    fun colourChosenFromTheMenuIsKeptAndShown() {
        seedJobs(1)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.job_style))
        waitNode("the colour swatches", hasContentDescription(s(R.string.job_color_default)))
        click(s(R.string.color_teal))
        click(s(R.string.save))
        waitFor("teal stored") { store.data.jobs.single().color == "teal" }
        // Opened again, the picker has Teal ticked.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.job_style))
        compose.onAllNodes(hasContentDescription(s(R.string.color_teal)) and hasClickAction()).onFirst().assertIsSelected()
        click(s(R.string.cancel))
    }

    /** States of a sync's scheduled work, as WorkManager holds them. */
    private fun work(jobId: String): List<WorkInfo.State> =
        kotlinx.coroutines.runBlocking { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Scheduler.name(jobId)).first() }.map { it.state }

    @Test
    fun menuEnablesAndDisablesASyncAndItsTwoWayTwin() {
        seedJobs(5)
        // Road Trip runs daily, two-way with Chill Evening's card as its "↔" half; both start disabled,
        // so nothing is scheduled yet.
        store.update { d ->
            d.copy(jobs = d.jobs.map {
                when (it.id) {
                    "job-0" -> it.copy(schedule = Schedule.DAILY, linkedJobId = "job-1", enabled = false)
                    "job-1" -> it.copy(schedule = Schedule.DAILY, linkedJobId = "job-0", twin = true, enabled = false)
                    else -> it
                }
            })
        }
        store.data.jobs.forEach { Scheduler.apply(app, it) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")

        // The Disabled filter (its chip says how many: "Disabled · 2") shows exactly those two.
        click(s(R.string.filter_disabled) + " · 2")
        waitText(plural(R.plurals.filter_shown, 5, 2, 5))
        assertTrue(exists("Road Trip"))
        assertTrue(exists("Chill Evening"))
        assertFalse(exists("Workout Mix"))
        // Off again: every sync listed (the cards further down are not composed until scrolled to).
        click(s(R.string.filter_disabled) + " · 2")
        waitGone(plural(R.plurals.filter_shown, 5, 2, 5))

        // On from the first card's menu: both halves on and scheduled, a snackbar says so.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.sync_menu_enable))
        waitText(s(R.string.sync_enabled_snack))
        waitFor("both halves enabled") { store.data.jobs.all { it.enabled } }
        for (id in listOf("job-0", "job-1")) waitFor("$id scheduled") { work(id).any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING } }
        // A periodic sync runs once at once: let those first runs end, the menu is off while a card runs.
        runCatching { waitFor("the first scheduled runs", 30_000) { store.data.jobs.filter { it.id == "job-0" || it.id == "job-1" }.none { it.lastReportId == "r" + it.id.removePrefix("job-") } } }
        waitFor("nothing running", 60_000) { com.xlollx.songport.sync.SyncState.running.value.isEmpty() && listOf("job-0", "job-1").none { WorkInfo.State.RUNNING in work(it) } }

        // Off again from the same menu, which now says Disable: both halves off, their work cancelled.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.sync_menu_disable))
        waitText(s(R.string.sync_disabled_snack))
        waitFor("both halves disabled") { store.data.jobs.filter { it.id == "job-0" || it.id == "job-1" }.none { it.enabled } }
        assertTrue(store.data.jobs.filter { it.id != "job-0" && it.id != "job-1" }.all { it.enabled })
        for (id in listOf("job-0", "job-1")) waitFor("$id work cancelled") { work(id).none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING } }
        click(s(R.string.filter_disabled) + " · 2")
        waitText(plural(R.plurals.filter_shown, 5, 2, 5))
        assertTrue(exists("Road Trip"))
        assertFalse(exists("Workout Mix"))
    }

    @Test
    fun addedFigureOpensTheListOfTracks() {
        seedJobs(1)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("+2")
        click("+2")
        waitText(s(R.string.stat_list_added))
        waitText(Demo.SONGS[0].title, substring = true)
        waitText(Demo.SONGS[1].title, substring = true)
    }

    @Test
    fun finderFiltersManySyncsByWordAndColour() {
        seedJobs(6, colors = mapOf(1 to "teal", 4 to "teal"))
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        // With two syncs or more, the pinned "Sync all" pill.
        assertTrue(exists(s(R.string.sync_all)))
        snapshot("sync-all-pill")
        // By word: the search is a field in the top bar, beside the mark, saying how many syncs it looks through.
        val hint = plural(R.plurals.syncs_find_n, 6, 6)
        val fields = { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size }
        assertEquals("the search field in the bar", 1, fields())
        val field = { compose.onAllNodes(hasSetTextAction() and hasContentDescription(s(R.string.syncs_find_hint))).onFirst() }
        assertTrue("the field says how many syncs it searches", compose.onAllNodes(hasSetTextAction() and hasText(hint)).fetchSemanticsNodes().size == 1)
        val bar = compose.onAllNodes(hasContentDescription("Trackhop")).onFirst().fetchSemanticsNode().boundsInRoot
        val box = field().fetchSemanticsNode().boundsInRoot
        assertTrue("the field beside the mark, in the bar", box.left >= bar.right && box.center.y in bar.top..bar.bottom)
        // The pill and the chips are still there, in their one row under the status line.
        assertTrue(exists(s(R.string.sync_all)))
        field().performTextReplacement("Road")
        waitText(plural(R.plurals.filter_shown, 6, 1, 6))
        assertTrue(exists("Road Trip"))
        assertFalse(exists("Chill Evening"))
        snapshot("home-search-open")
        // Turned on its side and back: the field still there, with what was typed and what it found.
        rotate(android.app.UiAutomation.ROTATION_FREEZE_90)
        waitFor("the search field on its side, with its words") { compose.onAllNodes(hasSetTextAction() and hasText("Road")).fetchSemanticsNodes().size == 1 }
        waitText(plural(R.plurals.filter_shown, 6, 1, 6))
        assertFalse(exists("Chill Evening"))
        snapshot("home-search-open-landscape")
        rotate(android.app.UiAutomation.ROTATION_FREEZE_0)
        waitFor("the search field upright again, with its words") { compose.onAllNodes(hasSetTextAction() and hasText("Road")).fetchSemanticsNodes().size == 1 }
        // The field's own "Clear search": the words go, every sync is back, the field stays.
        clickDesc(s(R.string.syncs_find_clear))
        waitText("Chill Evening")
        waitGone(plural(R.plurals.filter_shown, 6, 1, 6))
        waitFor("the field empty again") { compose.onAllNodes(hasSetTextAction() and hasText(hint)).fetchSemanticsNodes().size == 1 }
        // Typed again and cleared with the list's "Clear": every sync back, nothing left in the field.
        field().performTextReplacement("Road")
        waitText(plural(R.plurals.filter_shown, 6, 1, 6))
        click(s(R.string.filter_clear))
        waitText("Chill Evening")
        waitFor("the field empty after Clear") { compose.onAllNodes(hasSetTextAction() and hasText(hint)).fetchSemanticsNodes().size == 1 }
        // By colour: only the teal ones.
        click(s(R.string.filter_color, s(R.string.color_teal)))
        waitText(plural(R.plurals.filter_shown, 6, 2, 6))
        assertTrue(exists("Chill Evening"))
        assertTrue(exists("Morning Run"))
        assertFalse(exists("Workout Mix"))
    }

    @Test
    fun statsScreenOpensFromTools() {
        seedJobs(2)
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.stats_title))
        click(s(R.string.stats_title))
        waitText(s(R.string.stats_tracks_moved))
        assertTrue(exists(s(R.string.stats_syncs_done)))
        back()
        waitText(s(R.string.tools_manage_title))
    }

    @Test
    fun shazamShareOpensTheSheetWithTheSong() {
        launch(shareIntent("I used Shazam to discover Get Lucky by Daft Punk. https://www.shazam.com/track/66932935/get-lucky"))
        waitText(s(R.string.share_sheet_title), ms = 20_000)
        waitText("Get Lucky", substring = true)
        waitText("Daft Punk", substring = true)
        // No sync of the Shazam playlist yet: the sheet suggests one.
        waitText(s(R.string.shazam_share_hint_button))
        assertEquals(0, store.data.jobs.size)
    }
}

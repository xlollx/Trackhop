package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.ui.SCHEDULE_ROW_TAG
import com.xlollx.songport.ui.SCHEDULE_TAB_HAND
import com.xlollx.songport.ui.SELECTION_TICK_TAG
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Trackhop 2.1: several syncs changed at once (#68) and saved schedules, as the previews approved
 * on 17/11 show them. A selection starts from a card's menu or a long press, its bar acts on every
 * ticked sync, and what was not touched stays; a saved schedule is linked to its syncs, edited for
 * all of them, and leaves them their timing when it is deleted. What is ticked and what a sheet
 * holds are kept when Android makes the activity anew.
 */
@RunWith(AndroidJUnit4::class)
class SelectionSchedulesFlowTest : FlowTest() {
    private val names = listOf("Road Trip", "Chill Evening", "Workout Mix", "Party Night")
    private val morning = SavedSchedule("m", "Morning refresh", 1440, "07:30")

    /** [n] manual syncs from Spotify to Deezer with one completed run each, and the saved schedule "Morning refresh". */
    private fun seed(n: Int, change: (SyncJob) -> SyncJob = { it }) {
        val now = System.currentTimeMillis()
        val jobs = (0 until n).map { i ->
            // The first between two playlists the fake services have (its editor is opened); the names of the playlists are not the syncs'.
            val source = if (i == 0) PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") else PlaylistRef("spotify", "pl$i", "Source $i")
            val target = if (i == 0) PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") else PlaylistRef("deezer", "d$i", "Target $i")
            change(SyncJob("job-$i", names[i], source, target, lastRunEpoch = now - (i + 1) * 60_000L, lastReportId = "r$i"))
        }
        val reports = (0 until n).map { i -> SyncReport(id = "r$i", jobId = "job-$i", jobName = names[i], startedEpoch = now - (i + 1) * 60_000L, durationMs = 9_000, sourceCount = 8, targetCount = 8, added = 2) }
        store.update { it.copy(jobs = jobs, reports = reports, settings = it.settings.copy(savedSchedules = listOf(morning))) }
        store.flush()
    }

    private fun job(i: Int) = store.data.jobs.first { it.id == "job-$i" }

    /** The tick over a card while selecting: one node with the sync's name and its state (the merged tree). */
    private fun tick(name: String) = compose.onNode(hasTestTag(SELECTION_TICK_TAG) and hasContentDescription(name))

    private fun selectedCount(n: Int) = plural(R.plurals.files_selected, n, n)

    private fun work(jobId: String): List<WorkInfo.State> =
        kotlinx.coroutines.runBlocking { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Scheduler.name(jobId)).first() }.map { it.state }

    @Test
    fun twoSyncsLinkedToASavedScheduleThenTheScheduleEditedAndDeleted() {
        seed(3)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")

        // Select, from the first card's menu: the selection's header and its bar, and no "New sync" under it.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.files_select))
        waitText(selectedCount(1))
        waitText(s(R.string.bulk_removals))
        assertTrue("New sync is still offered while selecting", compose.onAllNodes(hasContentDescription(s(R.string.new_sync))).fetchSemanticsNodes().isEmpty())
        tick("Road Trip").assertIsOn()
        tap(tick("Chill Evening"), "Chill Evening")
        waitText(selectedCount(2))
        snapshot("selection-1-two-ticked")

        // The activity made anew: the same two ticked.
        remade()
        waitText(selectedCount(2))
        tick("Road Trip").assertIsOn()
        tick("Chill Evening").assertIsOn()
        tick("Workout Mix").assertIsOff()

        // Schedule: the saved schedules on top. One picked; the sheet and the pick outlive the activity too.
        click(s(R.string.bulk_schedule))
        waitText(s(R.string.bulk_use_saved))
        click("Morning refresh")
        compose.onNode(hasTestTag(SCHEDULE_ROW_TAG) and hasText("Morning refresh")).assertIsSelected()
        remade()
        waitText(s(R.string.bulk_use_saved))
        compose.onNode(hasTestTag(SCHEDULE_ROW_TAG) and hasText("Morning refresh")).assertIsSelected()
        snapshot("selection-2-schedule-sheet")
        tap(compose.onNode(hasTestTag("bulk_apply") and hasText(plural(R.plurals.bulk_apply_n, 2, 2))), "Apply")

        // Both linked, daily at 07:30, and their work set; the third as it was.
        waitFor("the two syncs linked") { job(0).scheduleId == "m" && job(1).scheduleId == "m" }
        waitText(plural(R.plurals.bulk_done, 2, 2))
        for (i in 0..1) { assertEquals("07:30", job(i).at); assertEquals(1440, job(i).everyMinutes) }
        assertNull(job(2).scheduleId)
        assertEquals(0, job(2).everyMinutes ?: 0)
        for (id in listOf("job-0", "job-1")) waitFor("$id scheduled") { work(id).any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING } }
        // Still selecting, to go on with the same syncs; the cross ends it.
        waitText(selectedCount(2))
        clickDesc(s(R.string.cancel))
        waitGone(selectedCount(2))

        // Settings › Schedules: the library, "Used by 2 syncs".
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        scrollTo(compose.onAllNodes(hasClickAction() and hasText(s(R.string.sched_title))).onFirst())
        click(s(R.string.sched_title))
        waitText(s(R.string.sched_intro))
        compose.onNode(hasTestTag(SCHEDULE_ROW_TAG) and hasText("Morning refresh") and hasText(plural(R.plurals.sched_used_by, 2, 2))).assertExists()
        snapshot("schedules-1-library")

        // Edited: weekly instead of daily. "Save for 2 syncs" changes both, linked as before.
        click("Morning refresh")
        waitText(s(R.string.sched_edit_title))
        waitText(s(R.string.sched_edit_note))
        click(s(R.string.schedule_weekly))
        snapshot("schedules-2-editor")
        tap(compose.onNode(hasTestTag("schedule_save") and hasText(plural(R.plurals.sched_save_for, 2, 2))), "Save for 2 syncs")
        waitFor("both syncs weekly") { (0..1).all { job(it).days.size == 1 && job(it).scheduleId == "m" && job(it).at == "07:30" } }
        assertTrue(job(2).days.isEmpty())

        // Deleted: the syncs are named, and keep the timing they have as their own.
        waitText(s(R.string.sched_intro))
        click("Morning refresh")
        waitText(s(R.string.sched_edit_title))
        clickDesc(s(R.string.sched_delete))
        waitText(s(R.string.sched_delete_title, "Morning refresh"))
        waitText(s(R.string.sched_delete_keep))
        assertTrue(exists("Road Trip") && exists("Chill Evening"))
        snapshot("schedules-3-delete")
        tap(compose.onAllNodes(hasClickAction() and hasText(s(R.string.sched_delete))).onFirst(), "Delete schedule")
        waitFor("the schedule deleted") { store.data.settings.savedSchedules.isEmpty() }
        for (i in 0..1) job(i).let { assertNull(it.scheduleId); assertEquals(1, it.days.size); assertEquals("07:30", it.at); assertTrue(com.xlollx.songport.sync.Timing.scheduled(it)) }
        waitText(s(R.string.sched_empty))
    }

    @Test
    fun differentValuesStayUnlessTouchedAndDeleteNamesTheSyncs() {
        // Road Trip mirrors removals, the other two only add.
        seed(3) { if (it.id == "job-0") it.copy(mirrorRemovals = true) else it }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Chill Evening")

        // A long press on a card starts the selection with it; "Select shown" takes the rest.
        // Held near its left edge, as ReorderFlowTest holds a card, and let go without moving.
        compose.onAllNodes(hasText("Chill Evening")).onFirst().performTouchInput {
            down(Offset(12f, height / 2f)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200); up()
        }
        compose.waitForIdle()
        waitText(selectedCount(1))
        tick("Chill Evening").assertIsOn()
        click(s(R.string.tracks_select_shown))
        waitText(selectedCount(3))

        // Removals: "Different values" is the starting option, and applies nothing.
        click(s(R.string.bulk_removals))
        waitText(s(R.string.bulk_removals_leave, 1, 2))
        compose.onNode(hasTestTag("bulk_choice_leave")).assertIsSelected()
        compose.onNode(hasTestTag("bulk_apply")).assertIsNotEnabled()
        snapshot("selection-3-removals")
        // Add only, for all three.
        tap(compose.onNode(hasTestTag("bulk_choice_no")), "Add only")
        remade()
        compose.onNode(hasTestTag("bulk_choice_no")).assertIsSelected()
        tap(compose.onNode(hasTestTag("bulk_apply")), "Apply")
        waitFor("no sync mirrors removals") { store.data.jobs.none { it.mirrorRemovals } }
        waitText(plural(R.plurals.bulk_done, 3, 3))

        // More › Delete: the three are named, and what stays is said.
        click(s(R.string.more_actions))
        click(s(R.string.delete))
        waitText(plural(R.plurals.bulk_delete_title, 3, 3))
        waitText(s(R.string.bulk_delete_text))
        names.take(3).forEach { assertTrue("$it is not named", exists(it)) }
        snapshot("selection-4-delete")
        tap(compose.onNode(hasTestTag("bulk_delete_ok")), "Delete 3 syncs")
        waitFor("the three syncs deleted") { store.data.jobs.isEmpty() }
        // Their past runs stay in the log, as the dialog says.
        assertEquals(3, store.data.reports.size)
        waitText(s(R.string.empty_syncs_title))
    }

    @Test
    fun aLinkedSyncShowsItsScheduleAndDetachGivesItItsOwnCopy() {
        seed(2) { Schedules.link(it, morning) }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))

        // Its schedule, linked, and who else uses it.
        scrollTo(compose.onNode(hasTestTag("schedule_detach")))
        compose.onNode(hasTestTag("schedule_linked") and hasText("Morning refresh") and hasText(s(R.string.sched_linked)) and hasText(plural(R.plurals.sched_also_used, 1, 1))).assertExists()
        remade()
        scrollTo(compose.onNode(hasTestTag("schedule_detach")))
        compose.onNode(hasTestTag("schedule_linked") and hasText("Morning refresh")).assertExists()
        snapshot("schedules-4-linked-sync")

        // Detach: the same timing, set by hand; saved, the sync no longer follows the schedule, the other does.
        tap(compose.onNode(hasTestTag("schedule_detach")), "Detach")
        compose.onNode(hasTestTag(SCHEDULE_TAB_HAND)).assertIsSelected()
        click(s(R.string.save))
        waitFor("Road Trip detached") { job(0).scheduleId == null }
        job(0).let { assertEquals("07:30", it.at); assertEquals(1440, it.everyMinutes) }
        assertEquals("m", job(1).scheduleId)
        assertEquals(listOf("job-1"), Schedules.usedBy(store.data, "m").map { it.id })
    }
}

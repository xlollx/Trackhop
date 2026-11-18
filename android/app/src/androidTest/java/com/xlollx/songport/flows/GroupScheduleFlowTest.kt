package com.xlollx.songport.flows

import android.view.View
import android.widget.TimePicker
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.sync.Timing
import com.xlollx.songport.ui.GROUP_SCHEDULE_ROW_TAG
import com.xlollx.songport.ui.GROUP_SCHEDULE_SAVE_TAG
import com.xlollx.songport.ui.NWAY_EXACT_OK_TAG
import com.xlollx.songport.ui.SCHEDULE_ROW_TAG
import com.xlollx.songport.ui.SCHEDULE_TAB_HAND
import com.xlollx.songport.ui.SCHEDULE_TAB_SAVED
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** "Trip mix": Spotify's Road Trip its source, Deezer's Workout a target that keeps its additions; every 6 hours, as made before 2.1.2. */
private fun tripMix(env: com.xlollx.songport.platform.Env): SharedGroup = Nway.create(env, "Trip mix", listOf(
    PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") to true,
    PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") to false,
))

/**
 * The multi sync page's "Automatic schedule" (2.1.2): the sync editors' own chooser, a saved schedule
 * or a timing by hand with its day and time, and Detach. 2.1.1 offered five plain choices and wiped
 * the day, the time and the link of every sync of the group. What is chosen reaches every sync of the
 * group, the row says it as a card does, and the chooser is still there when Android makes the
 * activity anew.
 */
@RunWith(AndroidJUnit4::class)
class GroupScheduleFlowTest : FlowTest() {

    private lateinit var group: SharedGroup
    private fun jobs() = Nway.jobsOf(store.data, group.id)

    /** A day as the chooser's chips name it, and as the cards do ("Sat", "Saturday"). */
    private fun short(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    private fun full(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        .let { n -> if (n.isNotEmpty() && n.first().isDigit()) day.getDisplayName(TextStyle.FULL, Locale.getDefault()) else n }

    private fun openPage() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        click("Trip mix")
        waitText(s(R.string.nway_delete))
    }

    private fun openChooser() {
        tap(compose.onNode(hasTestTag(GROUP_SCHEDULE_ROW_TAG)), "Automatic schedule")
        waitText(s(R.string.nway_schedule_note))
    }

    private fun save() {
        tap(compose.onNode(hasTestTag(GROUP_SCHEDULE_SAVE_TAG)), "Save")
        waitGone(s(R.string.nway_schedule_note))
    }

    /** The time of day in the system's own picker (a View dialog): [h]:[m], then its OK. */
    private fun pickTime(h: Int, m: Int) {
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(RootMatchers.isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "set the time to $h:$m"
            override fun perform(uiController: UiController, view: View) {
                (view as TimePicker).hour = h
                view.minute = m
            }
        })
        Espresso.onView(ViewMatchers.withId(android.R.id.button1)).inRoot(RootMatchers.isDialog()).perform(ViewActions.click())
    }

    @Test
    fun aWeeklyTimeChosenOnThePageReachesEverySyncAndTheRowSaysIt() {
        group = tripMix(app.env)
        assertEquals("both halves of Road Trip and Workout's one", 3, jobs().size)
        openPage()
        // As a card says it, not as one of the five old choices.
        waitText(s(R.string.timing_every_hours, 6))

        // By hand: Weekly, on Saturday, at 07:30.
        openChooser()
        compose.onNode(hasTestTag(SCHEDULE_TAB_HAND)).assertExists()
        click(s(R.string.schedule_weekly))
        click(short(DayOfWeek.SATURDAY))
        click(s(R.string.timing_at_label, "09:00"))
        pickTime(7, 30)
        val saturday = s(R.string.timing_weekly_on, full(DayOfWeek.SATURDAY), "07:30")
        waitText(saturday)
        snapshot("group-schedule-1-by-hand")

        // Made anew: the chooser with what was chosen, nothing saved yet.
        remade()
        waitText(s(R.string.nway_schedule_note))
        waitText(saturday)
        // Every 6 hours as a group made before 2.1.2 holds it: the plain choice, no interval of its own.
        assertTrue("nothing saved by choosing", jobs().all { Timing.interval(it) == 360 && it.days.isEmpty() && it.at == null && it.scheduleId == null })
        save()

        // Every sync of the group: weekly, Saturday, 07:30, set by hand.
        waitFor("every sync of the group on Saturday at 07:30") { jobs().all { it.days == listOf(6) && it.at == "07:30" && it.everyMinutes == 10080 } }
        assertTrue(jobs().all { it.scheduleId == null && it.enabled })
        // The row says the whole timing.
        waitText(saturday)
        snapshot("group-schedule-2-row")
    }

    @Test
    fun aSavedScheduleIsLinkedToEverySyncAndDetachKeepsItsTiming() {
        group = tripMix(app.env)
        store.update { d -> com.xlollx.songport.sync.Schedules.save(d, SavedSchedule("m", "Morning refresh", 1440, "06:45")) }
        store.flush()
        openPage()

        // Saved schedules: one picked links the whole group.
        openChooser()
        tap(compose.onNode(hasTestTag(SCHEDULE_TAB_SAVED)), "Saved schedules")
        tap(compose.onNode(hasTestTag(SCHEDULE_ROW_TAG) and hasText("Morning refresh")), "Morning refresh")
        save()
        waitFor("every sync linked") { jobs().all { it.scheduleId == "m" && it.at == "06:45" && it.everyMinutes == 1440 } }
        val daily = s(R.string.timing_at, s(R.string.schedule_daily), "06:45")
        // The row: the schedule's name, then its timing.
        waitText("Morning refresh · $daily")

        // Opened again: linked, with Detach; detached and saved, the same timing as the group's own.
        openChooser()
        waitNode("the linked schedule", hasTestTag("schedule_linked"))
        tap(compose.onNode(hasTestTag("schedule_detach")), "Detach")
        save()
        waitFor("every sync detached") { jobs().all { it.scheduleId == null } }
        assertTrue(jobs().all { it.at == "06:45" && it.everyMinutes == 1440 && it.days.isEmpty() })
        waitText(daily)
    }
}

/**
 * The multi sync page as the editor says it (2.1.2): a target's menu offers its three ways to follow,
 * "Make it the source" and Remove, and the source none; an exact copy is asked first (the question
 * kept when Android makes the activity anew); "Add a target" opens the editor's picker; a match to
 * check is said on the card and on the row, as on a classic sync's card.
 */
@RunWith(AndroidJUnit4::class)
class GroupPageFlowTest : FlowTest() {

    private fun deezer() = Providers.byId("deezer")!!.label(app.env)

    @Test
    fun anExactCopyIsAskedFirstAndTheSourceHasNoMenu() {
        val group = tripMix(app.env)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        click("Trip mix")
        waitText(s(R.string.nway_delete))
        // One menu: Workout's. The source changes only by making a target the source.
        assertEquals(1, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(s(R.string.more_actions))).fetchSemanticsNodes().size)
        clickDesc(s(R.string.more_actions))
        waitText(s(R.string.nway_make_source))
        assertTrue(exists(s(R.string.editor_bidirectional)))
        assertTrue("no old wording", !exists(s(R.string.nway_make_main)) && !exists(s(R.string.nway_make_follow)))
        click(s(R.string.shared_mode_exact))

        // Asked first, and still asked when the activity is made anew; nothing changed meanwhile.
        waitText(s(R.string.nway_exact_desc))
        remade()
        waitText(s(R.string.nway_exact_desc))
        snapshot("group-page-exact-ask")
        assertTrue(Nway.members(app.env, group).first { it.playlist.provider == "deezer" }.keepMine)
        tap(compose.onNode(hasTestTag(NWAY_EXACT_OK_TAG)), "Exact copy")
        waitFor("Workout an exact copy") { Nway.members(app.env, group).first { it.playlist.provider == "deezer" }.let { !it.main && !it.keepMine } }
        waitText(s(R.string.shared_mode_exact) + " · " + s(R.string.last_run_never))

        // "Add a target": the editor, its picker open.
        click(s(R.string.nway_add_target))
        waitText(s(R.string.nway_editor_edit))
        waitText(s(R.string.shared_service))
    }

    @Test
    fun aMatchToCheckIsSaidOnTheCardAndOnTheRow() {
        val group = tripMix(app.env)
        val down = Nway.members(app.env, group).first { it.playlist.provider == "deezer" }.down
        val now = System.currentTimeMillis()
        // Every sync of the group ran well; Deezer's left one match to double-check, and nothing not found.
        store.update { d ->
            val reports = Nway.jobsOf(d, group.id).map { j ->
                SyncReport(id = "r-${j.id}", jobId = j.id, jobName = j.name, startedEpoch = now, durationMs = 1_000, sourceCount = 8, targetCount = 8,
                    reviewTracks = if (j.id == down.id) listOf(MatchReview(Track(id = "s1", title = "Song"), Track(id = "d1", title = "Song (Live)"), 0.7)) else emptyList())
            }
            d.copy(reports = d.reports + reports, jobs = d.jobs.map { j -> if (Nway.jobsOf(d, group.id).any { it.id == j.id }) j.copy(lastReportId = "r-${j.id}", lastRunEpoch = now) else j })
        }
        store.flush()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        // The header, the card and the page say the same thing.
        waitText(plural(R.plurals.status_to_fix, 1, 1), substring = true)
        val toCheck = plural(R.plurals.widget_to_check, 1, 1)
        waitText("$toCheck · ${deezer()}")
        click("Trip mix")
        waitText(s(R.string.nway_delete))
        waitText(s(R.string.shared_mode_keep) + " · " + toCheck)
    }
}

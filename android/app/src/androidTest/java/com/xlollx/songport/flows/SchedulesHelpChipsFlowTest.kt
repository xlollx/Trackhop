package com.xlollx.songport.flows

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.env
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.ui.SCHEDULE_ROW_TAG
import com.xlollx.songport.ui.SCHEDULE_USER_TAG
import com.xlollx.songport.ui.SELECT_SYNCS_TAG
import com.xlollx.songport.ui.SYNC_CHIPS_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Trackhop 2.2, as the preview approved on 19/11 shows it: a multi sync counts as one sync where a
 * saved schedule says who uses it; Settings › Appearance › Help buttons (?) takes every "?" away;
 * the Syncs tab's filter chips have a row of their own, the first one whole at rest.
 */
@RunWith(AndroidJUnit4::class)
class SchedulesHelpChipsFlowTest : FlowTest() {
    private val morning = SavedSchedule("m", "Morning refresh", 1440, "07:30")

    private fun density() = app.resources.displayMetrics.density

    private fun help(label: String) = s(R.string.help_cd, label)

    /** The "?" beside Sync all, by what TalkBack reads. */
    private fun cardsHelp() = compose.onAllNodes(hasContentDescription(help(s(R.string.help_cards_title))), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    /** The multi sync "Rap" on three services, every one of its syncs linked to "Morning refresh". */
    private fun seedRap(): SharedGroup {
        val group = Nway.create(app.env, "Rap", listOf(
            PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") to true,
            PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") to false,
            PlaylistRef("ytm", FakeWorld.YT_MIX, "Metal Mix") to false,
        ))
        store.update { d ->
            d.copy(
                jobs = d.jobs.map { if (SharedGroups.sharedSide(it) == group.id) Schedules.link(it, morning) else it },
                settings = d.settings.copy(savedSchedules = listOf(morning)),
            )
        }
        store.flush()
        return group
    }

    @Test
    fun aMultiSyncIsOneSyncOfItsSchedule() {
        val group = seedRap()
        val linked = Nway.jobsOf(store.data, group.id)
        assertTrue("every sync of the group linked", linked.size >= 3 && linked.all { it.scheduleId == "m" })
        assertEquals(1, Schedules.cards(store.data, "m").size)

        // Settings › Schedules: "Used by Rap", not "Used by 3 syncs".
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        scrollTo(compose.onAllNodes(hasClickAction() and hasText(s(R.string.sched_title))).onFirst())
        click(s(R.string.sched_title))
        waitText(s(R.string.sched_intro))
        compose.onNode(hasTestTag(SCHEDULE_ROW_TAG) and hasText("Morning refresh") and hasText(s(R.string.sched_used_by_name, "Rap"))).assertExists()
        for (n in 2..4) assertFalse("counted by its syncs ($n)", exists(plural(R.plurals.sched_used_by, n, n)))
        snapshot("schedules-multi-1-library")

        // The editor: one row for the group, with its services, and "Save".
        click("Morning refresh")
        waitText(s(R.string.sched_edit_title))
        waitText(plural(R.plurals.sched_used_by, 1, 1))
        val users = { compose.onAllNodes(hasTestTag(SCHEDULE_USER_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false) }
        assertEquals("one row for the multi sync", 1, users().size)
        compose.onNode(hasTestTag(SCHEDULE_USER_TAG) and hasText("Rap") and hasText(plural(R.plurals.sched_services, 3, 3)) and hasText(s(R.string.sched_multi_pill))).assertExists()
        waitText(s(R.string.sched_edit_note_multi_one))
        compose.onNode(hasTestTag("schedule_save") and hasText(s(R.string.save))).assertExists()
        snapshot("schedules-multi-2-editor")

        // Delete asks with the same count, and the question outlives the activity made anew.
        clickDesc(s(R.string.sched_delete))
        waitText(s(R.string.sched_delete_title, "Morning refresh"))
        waitText(plural(R.plurals.sched_delete_used, 1, 1))
        remade()
        waitText(s(R.string.sched_delete_title, "Morning refresh"))
        waitText(plural(R.plurals.sched_delete_used, 1, 1))
        waitFor("the multi sync named in the question") { compose.onAllNodes(hasTestTag(SCHEDULE_USER_TAG) and hasText("Rap")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        // The system's back closes the question only (the editor's own Cancel and arrow are under it).
        androidx.test.espresso.Espresso.pressBack()
        waitGone(s(R.string.sched_delete_title, "Morning refresh"))
        waitText(s(R.string.sched_edit_title))

        // Saved: still every sync of the group, as before.
        click(s(R.string.schedule_weekly))
        tap(compose.onNode(hasTestTag("schedule_save") and hasText(s(R.string.save))), "Save")
        waitFor("every sync of the group weekly") { Nway.jobsOf(store.data, group.id).all { it.days.size == 1 && it.scheduleId == "m" && it.at == "07:30" } }
    }

    /** Six syncs from Spotify to six services, the first starred, the last never run: many chips. */
    private fun seedSyncs() {
        val now = System.currentTimeMillis()
        val targets = listOf("deezer", "ytm", "tidal", "youtube", "soundcloud", "apple")
        val jobs = targets.mapIndexed { i, t ->
            SyncJob("job-$i", "Sync number ${i + 1}", PlaylistRef("spotify", "pl$i", "Source $i"), PlaylistRef(t, "t$i", "Target $i"),
                lastRunEpoch = if (i < 5) now - (i + 1) * 60_000L else 0L, lastReportId = if (i < 5) "r$i" else null, favorite = i == 0)
        }
        val reports = (0 until 5).map { i -> SyncReport(id = "r$i", jobId = "job-$i", jobName = "Sync number ${i + 1}", startedEpoch = now - (i + 1) * 60_000L, durationMs = 9_000, sourceCount = 8, targetCount = 8, added = 2) }
        store.update { it.copy(jobs = jobs, reports = reports) }
        store.flush()
    }

    private fun favourites(): SemanticsNode =
        compose.onAllNodes(hasClickAction() and hasText(s(R.string.filter_favorites))).onFirst().fetchSemanticsNode()

    @Test
    fun theFirstFilterChipIsWholeOnItsOwnRow() {
        seedSyncs()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        waitText(s(R.string.filter_favorites))
        compose.waitForIdle()
        val window = main().window.decorView.width.toFloat()
        val chip = favourites().boundsInRoot
        val pill = compose.onAllNodes(hasClickAction() and hasText(s(R.string.sync_all))).onFirst().fetchSemanticsNode().boundsInRoot
        val select = compose.onAllNodes(hasTestTag(SELECT_SYNCS_TAG)).onFirst().fetchSemanticsNode().boundsInRoot
        val view = compose.onAllNodes(hasContentDescription(s(R.string.syncs_view_tiles))).onFirst().fetchSemanticsNode().boundsInRoot
        val row = compose.onAllNodes(hasTestTag(SYNC_CHIPS_ROW_TAG), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("the first chip in the chips' row ($chip, $row)", chip.top >= row.top - 1 && chip.bottom <= row.bottom + 1)
        log("chips: favourites $chip, pill $pill, select $select, view $view, row $row, window $window")
        // At the list's side, whole, on a row of its own under the pill; the trailing buttons stay up there.
        assertTrue("the first chip starts at the list's side ($chip)", chip.left >= 15 * density() && chip.left <= 24 * density())
        assertTrue("the first chip whole in the window ($chip of $window)", chip.right <= window && chip.width >= 48 * density())
        assertTrue("the chips under the pill ($chip, $pill)", chip.top >= pill.bottom - 1)
        assertFalse("the select button over the first chip", chip.overlaps(select))
        assertFalse("the view button over the first chip", chip.overlaps(view))
        assertTrue("select and view at the right of the pill's row", select.left > pill.right && view.left >= select.right - 1 && select.center.y in pill.top..pill.bottom)
        snapshot("chips-own-row")

        // Turned on its side and back, it is still whole, under none of the buttons.
        rotate(android.app.UiAutomation.ROTATION_FREEZE_90)
        waitText(s(R.string.filter_favorites))
        compose.waitForIdle()
        val side = favourites().boundsInRoot
        val sideWindow = main().window.decorView.width.toFloat()
        assertTrue("on its side, the first chip whole ($side of $sideWindow)", side.left >= 0 && side.right <= sideWindow)
        assertFalse("on its side, the select button over the first chip", side.overlaps(compose.onAllNodes(hasTestTag(SELECT_SYNCS_TAG)).onFirst().fetchSemanticsNode().boundsInRoot))
        rotate(android.app.UiAutomation.ROTATION_FREEZE_0)
        waitText(s(R.string.filter_favorites))
        waitFor("upright again, the first chip at the side") { favourites().boundsInRoot.left in (15 * density())..(24 * density()) }
    }

    @Test
    fun theHelpSwitchTakesTheQuestionMarkAwayFromTheSyncs() {
        seedSyncs()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        waitFor("the \"?\" beside Sync all") { cardsHelp().isNotEmpty() }
        val chipWith = favourites().boundsInRoot

        // Settings › Appearance: on by default; off, saved.
        assertTrue(store.data.settings.helpButtons)
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        scrollTo(compose.onAllNodes(hasText(s(R.string.settings_help_buttons))).onFirst())
        waitText(s(R.string.settings_help_buttons_summary, s(R.string.settings_section_help), s(R.string.howto_title)))
        snapshot("help-switch-on")
        click(s(R.string.settings_help_buttons))
        waitFor("the help buttons off") { !store.data.settings.helpButtons }

        // Syncs: no "?" (nor its circle), the pill, the buttons and the chips as they were.
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")
        waitText(s(R.string.sync_all))
        waitText(s(R.string.filter_favorites))
        compose.waitForIdle()
        assertTrue("no \"?\" beside Sync all", cardsHelp().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag(SELECT_SYNCS_TAG)).fetchSemanticsNodes().isNotEmpty())
        assertEquals("the first chip where it was", chipWith.left, favourites().boundsInRoot.left, density())
        snapshot("help-off-syncs")

        // The activity made anew: still off.
        remade()
        waitText("Sync number 1")
        assertTrue("no \"?\" after the activity is made anew", cardsHelp().isEmpty())

        // On again: back.
        store.updateSettings { it.copy(helpButtons = true) }
        waitFor("the \"?\" back") { cardsHelp().isNotEmpty() }
    }
}

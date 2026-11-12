package com.xlollx.songport.flows

import android.app.UiAutomation
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sync from start to end: the three-card editor (source, target, options), the preview of the
 * first run, the run itself on the fake services, its result on the card, the run history and
 * the log; then editing and deleting it. Spotify's "Road Trip" has 8 songs, Deezer's catalogue
 * lacks 2 of them.
 */
@RunWith(AndroidJUnit4::class)
class SyncJobFlowTest : FlowTest() {

    private fun fieldShows(label: String, value: String) =
        compose.onAllNodes(hasText(label) and hasText(value)).fetchSemanticsNodes().isNotEmpty()

    /** Source: Spotify (the first service) and its "Road Trip"; target: Deezer (the second), a new playlist. */
    private fun fillEditorWithRoadTrip() {
        waitText(s(R.string.editor_source))
        waitText(s(R.string.editor_playlist))
        click(s(R.string.editor_playlist))
        waitText("Road Trip")
        click("Road Trip")
        waitFor("Road Trip chosen as source") { fieldShows(s(R.string.editor_playlist), "Road Trip") }
        chooseTarget("Deezer")
    }

    /**
     * A new sync opens with no target: the field empty as the source's, no "Create a new playlist"
     * switch, and a sheet with no service selected. Saving says the target is missing.
     */
    @Test
    fun aNewSyncOpensWithNoTargetAndSaveSaysItIsMissing() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        // Source and target both say "Choose a playlist", and nothing offers to create one yet.
        waitFor("both fields empty") { compose.onAllNodesWithText(s(R.string.editor_choose_playlist)).fetchSemanticsNodes().size == 2 }
        assertFalse("no new-playlist switch before a target service", exists(s(R.string.editor_new_playlist)))
        assertFalse("no new playlist's name before a target service", exists(s(R.string.editor_new_playlist_name)))
        assertFalse("Deezer is not named on the page", exists("Deezer", substring = true))

        // A source, and still no target: Save says what is missing and saves nothing.
        click(s(R.string.editor_playlist))
        waitText("Road Trip")
        click("Road Trip")
        waitFor("Road Trip chosen as source") { fieldShows(s(R.string.editor_playlist), "Road Trip") }
        assertEquals("the target still empty", 1, compose.onAllNodesWithText(s(R.string.editor_choose_playlist)).fetchSemanticsNodes().size)
        click(s(R.string.save))
        waitText(s(R.string.editor_error_incomplete))
        assertTrue(store.data.jobs.isEmpty())

        // The target's sheet: the services, none selected, and no list until one is tapped.
        openTargetPicker()
        val tile = { name: String -> compose.onAllNodes(hasClickAction() and hasText(name)).onFirst() }
        waitFor("Deezer's mark") { compose.onAllNodes(hasClickAction() and hasText("Deezer")).fetchSemanticsNodes().isNotEmpty() }
        tile("Spotify").assertIsNotSelected()
        tile("Deezer").assertIsNotSelected()
        assertTrue("no new-playlist row before a service", compose.onAllNodes(newPlaylistRow).fetchSemanticsNodes().isEmpty())
        clickTile("Deezer")
        waitFor("Deezer selected") { compose.onAllNodes(hasClickAction() and hasText("Deezer") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        waitFor("the new playlist row") { compose.onAllNodes(newPlaylistRow).fetchSemanticsNodes().isNotEmpty() }
        tap(compose.onAllNodes(newPlaylistRow).onFirst(), "Create a new playlist")
        waitFor("the target picker closed") { compose.onAllNodesWithText(s(R.string.editor_target)).fetchSemanticsNodes().size < 2 }
        // Now the switch and the name are there, and Save goes through.
        waitText(s(R.string.editor_new_playlist_name))
        assertTrue(exists(s(R.string.editor_new_playlist)))
        click(s(R.string.save))
        waitText(s(R.string.preview_add, 6), ms = 30_000)
        val job = store.data.jobs.single()
        assertEquals("deezer", job.target.provider)
        assertNull(job.target.playlistId)
    }

    @Test
    fun createPreviewRunSeeResultAndHistoryThenEditAndDelete() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        // The three numbered cards.
        assertTrue(exists(s(R.string.editor_source)))
        assertTrue(exists(s(R.string.editor_target)))
        assertTrue(exists(s(R.string.editor_step_options)))
        // Save before choosing a playlist: told what is missing, nothing saved.
        click(s(R.string.save))
        waitText(s(R.string.editor_error_incomplete))
        assertTrue(store.data.jobs.isEmpty())

        fillEditorWithRoadTrip()
        click(s(R.string.save))

        // A new sync shows what its first run would do.
        waitText(s(R.string.preview_add, 6), ms = 30_000)
        assertTrue(exists(s(R.string.preview_target_new)))
        assertTrue(exists(s(R.string.preview_unmatched, 2)))
        val job = store.data.jobs.single()
        assertEquals("Road Trip", job.name)
        assertEquals("spotify", job.source.provider)
        assertEquals("deezer", job.target.provider)
        assertNull(job.target.playlistId)
        assertTrue("the preview writes nothing", FakeWorld.playlist("deezer", "Road Trip") == null)

        click(s(R.string.run_now))
        waitFor("the first run's report", 60_000) { store.data.reports.any { it.jobId == job.id && !it.partial } }
        val first = store.data.reports.first { it.jobId == job.id }
        assertTrue(first.error ?: "", first.ok)
        assertEquals(6, first.added)
        assertEquals(2, first.unmatched.size)
        val made = FakeWorld.playlist("deezer", "Road Trip")
        assertNotNull("the run created the playlist on Deezer", made)
        assertEquals(6, made!!.tracks.size)

        // The card: done, the numbers, the two songs left to fix, which its status names too.
        waitText("+6")
        waitText(s(R.string.pill_not_found, 2))
        assertFalse("not \"up to date\" with songs missing", exists(s(R.string.pill_ok)))
        // The figure opens the list of what was added.
        click("+6")
        waitText(s(R.string.stat_list_added))
        waitText(made.tracks.first().title, substring = true)
        androidx.test.espresso.Espresso.pressBack()
        waitGone(s(R.string.stat_list_added))
        assertTrue(exists(s(R.string.review_now, 2)))
        assertTrue(exists(s(R.string.stat_not_found)))

        // Again, from the card's play button: nothing new to add, and the history now has two runs.
        clickDesc(s(R.string.run_now))
        waitFor("the second run's report", 60_000) { store.data.reports.count { it.jobId == job.id && !it.partial } == 2 }
        waitText(plural(R.plurals.stat_history, 2, 2, 0))
        // Nothing added this time: a plain 0, without a sign, where the first run's "+6" was.
        waitGone("+6")
        waitFor("0 added and 0 removed") { compose.onAllNodesWithText("0", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        assertEquals(6, FakeWorld.playlist("deezer", "Road Trip")!!.tracks.size)

        // The log, from Settings: both runs, each with its summary.
        click(s(R.string.tab_settings))
        click(s(R.string.settings_log))
        waitText(s(R.string.tab_log))
        val reports = store.data.reports.filter { it.jobId == job.id }
        reports.forEach { r -> waitText(Notifications.summary(main(), r), substring = true) }
        back()
        click(s(R.string.tab_syncs))

        // Edit: a new name and a daily schedule.
        waitText("Road Trip")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
        val nameField = compose.onAllNodes(hasSetTextAction() and hasText(s(R.string.editor_name))).onFirst()
        nameField.performScrollTo()
        nameField.performTextReplacement("Road Trip on Deezer")
        click(s(R.string.schedule_daily))
        click(s(R.string.save))
        waitText("Road Trip on Deezer")
        val edited = store.data.jobs.single()
        assertEquals(job.id, edited.id)
        assertEquals("Road Trip on Deezer", edited.name)
        assertEquals(Schedule.DAILY, edited.schedule)
        waitText(s(R.string.schedule_daily), substring = true)

        // Delete, confirmed.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.delete))
        waitText(s(R.string.delete_confirm_title))
        click(s(R.string.yes))
        waitText(s(R.string.empty_syncs_title))
        assertTrue(store.data.jobs.isEmpty())
    }

    @Test
    fun deleteCanBeCancelled() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        click(s(R.string.new_sync))
        fillEditorWithRoadTrip()
        click(s(R.string.save))
        waitText(s(R.string.preview_add, 6), ms = 30_000)
        back()
        waitText("Road Trip")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.delete))
        click(s(R.string.no))
        assertEquals(1, store.data.jobs.size)
        assertTrue(exists("Road Trip"))
    }

    @Test
    fun editorKeepsWhatWasChosenAcrossARotation() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        click(s(R.string.new_sync))
        fillEditorWithRoadTrip()
        val newName = compose.onAllNodes(hasSetTextAction() and hasText(s(R.string.editor_new_playlist_name))).onFirst()
        newName.performScrollTo()
        newName.performTextReplacement("Trip copy")
        click(s(R.string.schedule_weekly))

        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText(s(R.string.editor_title_new))
        waitFor("source still Road Trip after rotation") { fieldShows(s(R.string.editor_playlist), "Road Trip") }
        waitFor("new playlist name kept") { fieldShows(s(R.string.editor_new_playlist_name), "Trip copy") }
        scrollTo(compose.onAllNodes(hasClickAction() and hasText(s(R.string.schedule_weekly))).onFirst()).assertIsSelected()

        rotate(UiAutomation.ROTATION_FREEZE_0)
        click(s(R.string.save))
        waitText(s(R.string.preview_add, 6), ms = 30_000)
        val job = store.data.jobs.single()
        assertEquals("Trip copy", job.target.playlistName)
        assertEquals(Schedule.WEEKLY, job.schedule)
    }

    @Test
    fun tabsKeepTheirPlaceAcrossARotation() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_manage_title))
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText(s(R.string.tools_manage_title))
        assertTrue(compose.onAllNodesWithText(s(R.string.tab_tools)).fetchSemanticsNodes().isNotEmpty())
        // Discover too: a card's page open before the rotation is still open after it.
        click(s(R.string.tab_discover))
        waitText(s(R.string.discover_wrapped_title))
        clickTile(s(R.string.tools_poster_title))
        waitText(s(R.string.card_cover_title))
        rotate(UiAutomation.ROTATION_FREEZE_0)
        waitText(s(R.string.card_cover_title))
    }
}

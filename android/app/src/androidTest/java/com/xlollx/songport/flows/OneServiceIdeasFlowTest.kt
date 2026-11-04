package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One music service only (YouTube Music): the empty Syncs tab offers ideas for it, and "Save your
 * Discover Mix every week" opens the editor filled in: the Discover Mix as the source, a new
 * playlist on the same account as the target, "Keep every track" chosen, Mirror out of sight.
 * Saved, it is an archive sync between two playlists of one account.
 */
@RunWith(AndroidJUnit4::class)
class OneServiceIdeasFlowTest : FlowTest() {
    override val connectedAtStart: List<String> = listOf("ytm")

    private val discoverMix = "PLfakeDiscoverMix2026x"

    @Test
    fun theIdeasForOneServiceOpenAFilledInArchiveSync() {
        FakeWorld.playlists.getValue("ytm") += FakeWorld.FakePlaylist(discoverMix, "Discover Mix", FakeWorld.songs("ytm", 4 until 9), owned = false)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        waitText(s(R.string.ideas_title, "YouTube Music"))
        // Only what one service can do: no second service needed.
        assertTrue(exists(s(R.string.idea_backup_title)))
        assertTrue(exists(s(R.string.idea_wrapped_title)))
        snapshot("one-service-ideas")

        tap(compose.onNodeWithTag("idea_WEEKLY"), "the weekly idea")
        waitText(s(R.string.editor_title_new))
        // The source found by its name, and the choice under it already made.
        waitFor("Discover Mix picked as the source") { compose.onAllNodes(hasText(s(R.string.editor_playlist)) and hasText("Discover Mix")).fetchSemanticsNodes().isNotEmpty() }
        waitText(s(R.string.editor_changes_title))
        compose.onNodeWithTag("editor_archive").assertIsOn()
        assertFalse("an archive hides Mirror removals", exists(s(R.string.editor_mirror)))
        click(s(R.string.save))

        waitFor("the archive sync saved") { store.data.jobs.size == 1 }
        val job = store.data.jobs.single()
        assertTrue(job.archive)
        assertFalse(job.mirrorRemovals)
        assertFalse(job.keepOrder)
        assertEquals("ytm", job.source.provider)
        assertEquals(discoverMix, job.source.playlistId)
        assertEquals("ytm", job.target.provider)
        assertNull(job.target.playlistId)
        assertEquals(s(R.string.idea_weekly_target, "Discover Mix"), job.target.playlistName)
        assertEquals(Schedule.WEEKLY, job.schedule)
    }

    /** "Copy the latest version" and "Keep every track" switch each other: the editor keeps one. */
    @Test
    fun theTwoChoicesForASelfChangingSourceExcludeEachOther() {
        FakeWorld.playlists.getValue("ytm") += FakeWorld.FakePlaylist(discoverMix, "Discover Mix", FakeWorld.songs("ytm", 4 until 9), owned = false)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.ideas_title, "YouTube Music"))
        tap(compose.onNodeWithTag("idea_WEEKLY"), "the weekly idea")
        waitText(s(R.string.editor_changes_title))
        tap(compose.onNodeWithTag("changes_replace"), "copy the latest version")
        compose.onNodeWithTag("editor_archive").assertIsOff()
        waitText(s(R.string.editor_mirror))
        tap(compose.onNodeWithTag("changes_archive"), "keep every track")
        compose.onNodeWithTag("editor_archive").assertIsOn()
        assertFalse("Mirror out of sight again", exists(s(R.string.editor_mirror)))
        click(s(R.string.save))
        waitFor("saved as an archive") { store.data.jobs.singleOrNull()?.archive == true }
        assertFalse(store.data.jobs.single().followsChanges)
    }

    /** The Backup idea opens the Backup page. */
    @Test
    fun theBackupIdeaOpensTheBackupPage() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.ideas_title, "YouTube Music"))
        tap(compose.onNodeWithTag("idea_BACKUP"), "the backup idea")
        waitText(s(R.string.backup_title))
    }
}

package com.xlollx.songport.flows

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Shazam sync: "Shazam" as the source finds the playlist the Shazam app fills on the connected
 * Spotify, the sync is saved hourly with the Shazam flag, and its card wears Shazam's mark. Without
 * that playlist the editor says how to turn it on in Shazam, and finds it on "Check again".
 */
@RunWith(AndroidJUnit4::class)
class ShazamSyncFlowTest : FlowTest() {

    private fun openEditorOnShazam() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        // After the services, File and "From link": off screen on a phone, the row scrolls to it.
        clickTile(s(R.string.editor_source_shazam))
    }

    @Test
    fun shazamAsTheSourceFindsItsPlaylistAndMarksTheCard() {
        FakeWorld.addShazamTracks()
        openEditorOnShazam()
        waitText(s(R.string.shazam_source_title))
        waitText(FakeWorld.SHAZAM_NAME, substring = true)
        click(s(R.string.save))

        waitFor("the Shazam sync saved") { store.data.jobs.size == 1 }
        val job = store.data.jobs.single()
        assertTrue(job.shazam)
        assertEquals("spotify", job.source.provider)
        assertEquals(FakeWorld.SPOTIFY_SHAZAM, job.source.playlistId)
        assertEquals(FakeWorld.SHAZAM_NAME, job.name)
        assertEquals(Schedule.HOURLY, job.schedule)
        assertFalse(job.mirrorRemovals)

        // Past the preview of its first run, the card: Shazam's mark before the name.
        waitText(s(R.string.preview_target_new), ms = 30_000)
        back()
        waitNode("Shazam's mark on the card", hasContentDescription(s(R.string.shazam_badge_desc)))
        snapshot("shazam-card")

        // Edited, it opens on Shazam again.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
        waitText(s(R.string.shazam_source_title))
    }

    @Test
    fun withoutShazamsPlaylistTheEditorSaysHowToTurnItOnAndChecksAgain() {
        openEditorOnShazam()
        waitText(s(R.string.shazam_howto_title))
        // Spotify is connected: nothing more to connect, only Shazam's setting to turn on.
        assertFalse(exists(s(R.string.shazam_howto_connect)))
        click(s(R.string.save))
        waitText(s(R.string.editor_error_incomplete))
        assertTrue(store.data.jobs.isEmpty())

        // Shazam connected to Spotify meanwhile: found on "Check again".
        FakeWorld.addShazamTracks()
        click(s(R.string.shazam_howto_retry))
        waitText(s(R.string.shazam_source_title))
        waitText(FakeWorld.SHAZAM_NAME, substring = true)
    }

    @Test
    fun withNeitherSpotifyNorAppleMusicTheEditorOffersToConnectOne() {
        FakeWorld.addShazamTracks()
        FakeWorld.connected -= "spotify"
        store.updateSettings { it.copy(connectors = listOf("deezer")) }
        openEditorOnShazam()
        waitText(s(R.string.shazam_howto_title))
        waitText(s(R.string.shazam_howto_connect))
        assertTrue(exists(s(R.string.shazam_howto_retry)))
    }
}

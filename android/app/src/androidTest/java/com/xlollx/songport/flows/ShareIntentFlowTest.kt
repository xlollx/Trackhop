package com.xlollx.songport.flows

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Share with Trackhop": a playlist link from Spotify, YouTube, Deezer or SoundCloud opens a new
 * sync from it towards the user's own service; a track link opens the share sheet, which finds the
 * song on the user's service and opens it there or adds it to a playlist. Links that leave the app
 * are caught (Espresso-Intents) and never reach a browser.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentFlowTest : FlowTest() {
    override val connectedAtStart = listOf("spotify", "deezer", "ytm")

    @Before fun stubOutgoing() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After fun releaseIntents() = Intents.release()

    private fun fieldShows(label: String, value: String) =
        compose.onAllNodes(hasText(label) and hasText(value)).fetchSemanticsNodes().isNotEmpty()

    /** The editor opened on the shared playlist, named after it, with the "link received" note on screen. */
    private fun expectEditorFrom(name: String) {
        waitText(s(R.string.editor_title_new), ms = 20_000)
        waitFor("source named \"$name\"", 20_000) { fieldShows(s(R.string.editor_playlist), name) }
        waitText(s(R.string.share_received))
    }

    @Test
    fun spotifyPlaylistLinkBecomesANewSyncToMyService() {
        launch(shareIntent("Listen to this! https://open.spotify.com/playlist/${FakeWorld.PUBLIC_SPOTIFY}?si=a1b2c3"))
        expectEditorFrom("Friday Night Rock")
        click(s(R.string.save))
        waitText(s(R.string.preview_add, 8), ms = 30_000) // 10 songs, 2 not on Deezer
        val job = store.data.jobs.single()
        assertEquals("spotify", job.source.provider)
        assertEquals(FakeWorld.PUBLIC_SPOTIFY, job.source.playlistId)
        assertEquals("Friday Night Rock", job.name)
        // Towards one of my own services, not back to the link's.
        assertTrue(job.target.provider != "spotify")
    }

    @Test
    fun youtubePlaylistLinkOpensOnTheConnectedYouTubeMusicRoute() {
        launch(shareIntent("https://music.youtube.com/playlist?list=${FakeWorld.PUBLIC_YOUTUBE}&si=xyz"))
        expectEditorFrom("YouTube Rock")
        click(s(R.string.save))
        waitText(s(R.string.preview_add, 4), ms = 30_000) // 6 songs, 2 not on Deezer
        assertEquals("ytm", store.data.jobs.single().source.provider)
    }

    @Test
    fun deezerPlaylistLink() {
        launch(shareIntent("https://www.deezer.com/en/playlist/${FakeWorld.PUBLIC_DEEZER}"))
        expectEditorFrom("Deezer Hits")
    }

    @Test
    fun soundCloudSetLinkOnlyWhereTheBuildCarriesSoundCloud() {
        launch(shareIntent("https://soundcloud.com/${FakeWorld.PUBLIC_SOUNDCLOUD}"))
        if (!BuildConfig.STORE_BUILD) {
            expectEditorFrom("Night Drive")
        } else {
            // The Play build has no SoundCloud: the link is not one it can use.
            waitText(s(R.string.share_invalid))
            assertTrue(store.data.jobs.isEmpty())
        }
    }

    @Test
    fun textWithoutALinkIsRefused() {
        launch(shareIntent("just some words, no link"))
        waitText(s(R.string.share_invalid))
        assertTrue(store.data.jobs.isEmpty())
    }

    @Test
    fun trackLinkFindsTheSongOnMyServiceOpensItThereAndAddsIt() {
        store.updateSettings { it.copy(shareTarget = "deezer") }
        val spotifyId = FakeWorld.trackId("spotify", 0)
        launch(shareIntent("https://open.spotify.com/track/$spotifyId?si=q"))
        waitText(s(R.string.share_sheet_title))
        waitText(s(R.string.share_sheet_from, "Spotify"))
        waitText("Master of Puppets", substring = true)
        // Found on Deezer by the sync's matching.
        waitText(s(R.string.share_sheet_best, "Deezer"), ms = 20_000)

        // "Open in Deezer": the Deezer page of the matched song.
        click(s(R.string.share_sheet_open, "Deezer"))
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("https://www.deezer.com/track/${FakeWorld.trackId("deezer", 0)}")))
        // "Open in Spotify": the original link's service.
        click(s(R.string.share_sheet_open, "Spotify"))
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("https://open.spotify.com/track/$spotifyId")))

        // Into the Deezer playlist "Workout".
        val before = FakeWorld.playlist("deezer", "Workout")!!.tracks.size
        click(s(R.string.share_sheet_add))
        waitText(s(R.string.share_track_title, "Deezer"))
        click("Workout")
        waitText(s(R.string.share_track_added, "Workout"))
        val after = FakeWorld.playlist("deezer", "Workout")!!.tracks
        assertEquals(before + 1, after.size)
        assertEquals(FakeWorld.trackId("deezer", 0), after.last().id)
        assertEquals("deezer", store.data.settings.shareTarget)
    }
}

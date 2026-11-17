package com.xlollx.songport.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tools of 1.18 from the owner's preview (Saved matches, Duplicate playlists, Mark as listened):
 * their sheets and forms stay as they were when Android makes the activity anew and after Home.
 */
@RunWith(AndroidJUnit4::class)
class NewToolsFlowTest : FlowTest() {
    override val preview2: Boolean = true

    /** What the pages keep for the process: nothing left from the test before. */
    @Before fun nothingKept() {
        com.xlollx.songport.ui.SavedMatchesKept.readAt = 0
        com.xlollx.songport.ui.SavedMatchesKept.names = emptyMap()
        com.xlollx.songport.ui.SavedMatchesKept.members = emptyMap()
        com.xlollx.songport.ui.TwinsKept.groups.clear()
        com.xlollx.songport.ui.ListenedKept.tracks = null
        com.xlollx.songport.ui.ListenedKept.from = ""
        com.xlollx.songport.ui.ListenedKept.albums = emptyList()
    }

    private fun openTool(title: Int) {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        click(s(title))
    }

    @Test
    fun aSavedMatchsSheetStaysOpen() {
        val src = FakeWorld.song("spotify", 0)
        val dst = FakeWorld.song("deezer", 0)
        val job = SyncJob("j", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"), lastReportId = "r")
        store.update { d ->
            d.copy(
                jobs = listOf(job),
                reports = listOf(SyncReport(id = "r", jobId = "j", jobName = "Road Trip", startedEpoch = 1, durationMs = 1, reviewTracks = listOf(MatchReview(src, dst, 0.9)))),
                matchCache = mapOf("spotify:${src.id}>deezer" to dst.id),
            )
        }
        openTool(R.string.saved_matches_title)
        // The page opens on To fix (2.1): the remembered matches are the second tab.
        click(s(R.string.to_fix_tab_saved))
        waitText(s(R.string.saved_matches_search))
        waitText(src.title, substring = true)
        click(src.title, substring = true)
        waitText(s(R.string.saved_matches_forget))
        remade()
        waitText(s(R.string.saved_matches_forget))
        homeAndBack()
        waitText(s(R.string.saved_matches_forget))
        click(s(R.string.saved_matches_forget))
        waitFor("the match forgotten") { store.data.matchCache.isEmpty() }
    }

    @Test
    fun theDuplicatePlaylistsConfirmationStaysOpen() {
        // A second Road Trip on Spotify, with two songs fewer than the first.
        FakeWorld.playlists.getValue("spotify") += FakeWorld.FakePlaylist("5RoadTripCopyFakeList05", "Road Trip", FakeWorld.songs("spotify", 0 until 6))
        openTool(R.string.twins_title)
        val delete = plural(R.plurals.twins_delete_n, 1, 1)
        waitText(delete)
        click(delete)
        val title = plural(R.plurals.twins_confirm_title, 1, 1, "Spotify")
        waitText(title)
        remade()
        waitText(title)
        homeAndBack()
        waitText(title)
        click(s(R.string.delete))
        waitFor("the copy deleted") { FakeWorld.playlists.getValue("spotify").count { it.name == "Road Trip" } == 1 }
        // Neither says when it changed: the longer one is kept.
        assertEquals(8, FakeWorld.playlist("spotify", "Road Trip")!!.tracks.size)
    }

    @Test
    fun markAsListenedKeepsItsDialogsAndTheSongsUnticked() {
        openTool(R.string.listened_title)
        click(s(R.string.listened_from_text))
        waitNode("the list's field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("Fleetwood Mac - Dreams\nFleetwood Mac - Songbird")
        // When: the date, then the time, each still open after the activity is made anew.
        click(s(R.string.review_change))
        waitText(s(R.string.ok))
        remade()
        waitText(s(R.string.ok))
        click(s(R.string.ok))
        waitNode("the time's dialog", hasText(s(R.string.ok)))
        remade()
        waitText(s(R.string.ok))
        click(s(R.string.ok))
        click(plural(R.plurals.listened_see, 2, 2))
        waitText(plural(R.plurals.listened_review_title, 2, 2))
        click("Songbird")
        waitText(plural(R.plurals.listened_review_title, 1, 1))
        remade()
        waitText(plural(R.plurals.listened_review_title, 1, 1))
        waitText(s(R.string.listened_skipped))
        homeAndBack()
        waitText(plural(R.plurals.listened_review_title, 1, 1))
        // No Last.fm or ListenBrainz here: nothing can be sent.
        waitText(plural(R.plurals.listened_send, 0, 0))
    }
}

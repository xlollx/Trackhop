package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.ui.TO_FIX_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saved matches › To fix (2.1, issue #69): the tracks of every sync in one list with their count in
 * the tab, a track of two syncs fixed once for both, "Later" and the search by hand as a swipe, the
 * tab, the grouping and the search sheet kept when Android makes the activity anew, and the way in
 * from a sync's own review.
 */
@RunWith(AndroidJUnit4::class)
class ToFixFlowTest : FlowTest() {
    override val preview2: Boolean = true

    private val evening = "777000111"
    private val loose = FakeWorld.song("spotify", 0)
    private val shared = FakeWorld.song("spotify", 5)
    private val absent = FakeWorld.song("spotify", 6)
    private val proposed = FakeWorld.song("deezer", 9)

    @Before fun nothingKept() {
        com.xlollx.songport.ui.SavedMatchesKept.readAt = 0
        com.xlollx.songport.ui.SavedMatchesKept.names = emptyMap()
        com.xlollx.songport.ui.SavedMatchesKept.members = emptyMap()
        com.xlollx.songport.ui.ReviewFocus.request.value = null
    }

    private fun key(t: Track) = FileStore.cacheKey("spotify", t.id, "deezer")
    private fun row(t: Track) = compose.onNode(hasTestTag(TO_FIX_ROW_TAG + key(t)))

    /**
     * Road Trip to Deezer's Workout: a loose match, a track with a candidate, one with nothing.
     * Chill Evening to Deezer's Evening: the track with a candidate again.
     */
    private fun seed() {
        FakeWorld.playlists.getValue("deezer") += FakeWorld.FakePlaylist(evening, "Evening", mutableListOf())
        val road = SyncJob("road", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"), lastReportId = "r-road")
        val chill = SyncJob("chill", "Chill Evening", PlaylistRef("spotify", FakeWorld.SPOTIFY_CHILL, "Chill Evening"), PlaylistRef("deezer", evening, "Evening"), lastReportId = "r-chill")
        store.update { d ->
            d.copy(
                jobs = listOf(road, chill),
                reports = listOf(
                    SyncReport(
                        id = "r-road", jobId = "road", jobName = "Road Trip", startedEpoch = System.currentTimeMillis(), durationMs = 1000, sourceCount = 8,
                        unmatched = listOf(shared, absent).map { it.toString() }, unmatchedTracks = listOf(shared, absent),
                        reviewTracks = listOf(MatchReview(loose, FakeWorld.song("deezer", 15), 0.62)), suggestions = mapOf(shared.id to proposed), absent = listOf(absent.id),
                    ),
                    SyncReport(
                        id = "r-chill", jobId = "chill", jobName = "Chill Evening", startedEpoch = System.currentTimeMillis(), durationMs = 1000, sourceCount = 5,
                        unmatched = listOf(shared.toString()), unmatchedTracks = listOf(shared), suggestions = mapOf(shared.id to proposed),
                    ),
                ),
                matchCache = mapOf(key(loose) to FakeWorld.trackId("deezer", 15)),
            )
        }
        store.flush()
    }

    private fun openSavedMatches() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        click(s(R.string.saved_matches_title))
        waitText(s(R.string.to_fix_once), substring = true)
    }

    /** The tab as a screen reader says it: its name and its count, one node. */
    private fun waitTab(count: Int) = waitFor("the To fix tab with $count") {
        compose.onAllNodes(hasText(s(R.string.to_fix_tab)) and hasText(count.toString())).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun oneListFixedOnceLaterAndSearchByHand() {
        seed()
        openSavedMatches()
        waitTab(3)
        waitText(plural(R.plurals.to_fix_in_syncs, 2, 2))
        waitText(s(R.string.to_fix_not_found_on, "Deezer"))
        snapshot("tofix-1-by-sync")

        // The grouping and the tab are still there when the activity is made anew.
        click(s(R.string.to_fix_by_service))
        waitText(s(R.string.to_fix_on_service, "Deezer"))
        remade()
        waitText(s(R.string.to_fix_on_service, "Deezer"))
        waitTab(3)
        snapshot("tofix-2-by-service")
        click(s(R.string.to_fix_by_sync))
        waitGone(s(R.string.to_fix_on_service, "Deezer"))

        // Nothing proposed: swiping right opens the search by hand, which stays open; "Ignore this track" is there.
        row(absent).performScrollTo().performTouchInput { swipeRight() }
        waitText(s(R.string.to_fix_search_on, "Deezer"))
        remade()
        waitText(s(R.string.to_fix_search_on, "Deezer"))
        homeAndBack()
        waitText(s(R.string.to_fix_search_on, "Deezer"))
        snapshot("tofix-3-search")
        click(s(R.string.to_fix_ignore))
        waitFor("the track ignored for every sync to Deezer") { store.isIgnored("spotify", absent.id, "deezer") }
        waitTab(2)

        // Swiping left sets the loose match aside: nothing is decided, the row is gone until Road Trip runs again.
        row(loose).performScrollTo().performTouchInput { swipeLeft() }
        waitFor("the row set aside") { store.data.toFixLater["r-road"] == setOf(loose.id) }
        waitTab(1)
        assertTrue("no choice written by Later", store.data.reviewDecisions.keys.none { it == key(loose) })

        // The track both syncs miss: accepted once, saved and added to both playlists.
        click(s(R.string.to_fix_accept))
        waitFor("the match saved") { store.cachedMatch("spotify", shared.id, "deezer") == proposed.id }
        waitFor("both playlists hold the song", ms = 20_000) {
            listOf("Workout", "Evening").all { name -> FakeWorld.playlist("deezer", name)!!.tracks.count { it.id == proposed.id } == 1 }
        }
        waitText(plural(R.plurals.to_fix_done, 2, 2))
        assertTrue(store.data.reports.all { r -> r.unmatchedTracks.none { it.id == shared.id } })

        // Nothing left: the empty state, without a count, and the way to the saved matches.
        waitText(s(R.string.to_fix_empty_title))
        assertEquals(0, compose.onAllNodes(hasText(s(R.string.to_fix_tab)) and hasText("1")).fetchSemanticsNodes().size)
        snapshot("tofix-4-empty")
        remade()
        waitText(s(R.string.to_fix_empty_title))
        click(s(R.string.to_fix_see_saved))
        waitText(s(R.string.saved_matches_search))
    }

    @Test
    fun aSyncsReviewLeadsToTheWholeList() {
        seed()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        com.xlollx.songport.ui.ReviewFocus.request.value = "r-chill"
        waitText(s(R.string.review_title, "Chill Evening"))
        click(s(R.string.to_fix_open_all, 3))
        waitText(s(R.string.to_fix_once), substring = true)
        waitTab(3)
        // The Saved tab is one tap away, and kept.
        click(s(R.string.to_fix_tab_saved))
        waitText(s(R.string.saved_matches_search))
        remade()
        waitText(s(R.string.saved_matches_search))
    }
}

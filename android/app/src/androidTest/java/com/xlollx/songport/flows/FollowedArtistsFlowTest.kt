package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.sync.ArtistSuggest
import com.xlollx.songport.ui.FOLLOWED_SEARCH_TAG
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discover › Followed artists: an artist followed from the names suggested while typing (MusicBrainz
 * answered by a fake: the tests have no network), its alerts turned off as a whole, then unfollowed
 * and brought back with Undo.
 */
@RunWith(AndroidJUnit4::class)
class FollowedArtistsFlowTest : FlowTest() {
    private val id = "a".repeat(8) + "-" + "1".repeat(4) + "-" + "2".repeat(4) + "-" + "3".repeat(4) + "-" + "b".repeat(12)

    @After
    fun theRealSearchAgain() { ArtistSuggest.remote = ArtistSuggest::musicBrainz }

    private fun followed() = store.data.settings.followedArtists

    @Test
    fun anArtistIsFollowedFromASuggestionMutedUnfollowedAndBroughtBack() {
        ArtistSuggest.remote = { q ->
            if (q.contains("park", ignoreCase = true)) listOf(ArtistSuggest.Suggestion("Parkway Drive", id, "metalcore · Australia")) else emptyList()
        }
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_wrapped_title))
        clickTile(s(R.string.alerts_followed_title))
        waitText(s(R.string.alerts_none))

        // Typed in lower case: the suggestion carries the name as it is written, and what tells it apart.
        compose.onNode(hasTestTag(FOLLOWED_SEARCH_TAG)).performTextInput("parkway dr")
        waitText("Parkway Drive")
        waitText("metalcore · Australia")
        click("Parkway Drive")
        waitFor("the artist followed by its proper name and id") { followed().singleOrNull()?.let { it.name == "Parkway Drive" && it.mbid == id && !it.muted } == true }
        waitText(plural(R.plurals.alerts_count, 1, 1))

        // The same name again, however written, is marked as followed and adds nothing.
        compose.onNode(hasTestTag(FOLLOWED_SEARCH_TAG)).performTextInput("PARKWAY DRIVE")
        waitText(s(R.string.alerts_already))
        clickDesc(s(R.string.alerts_search_clear))

        // The artist's switch: still followed, no alerts.
        clickDesc(s(R.string.alerts_for, "Parkway Drive"))
        waitFor("the artist muted") { followed().singleOrNull()?.muted == true }
        waitText(s(R.string.alerts_muted))

        // Unfollowed from the menu, back with Undo as it was.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.alerts_unfollow, "Parkway Drive"))
        waitFor("nobody followed") { followed().isEmpty() }
        click(s(R.string.unplayable_undo))
        waitFor("the artist back, still muted") { followed().singleOrNull()?.let { it.name == "Parkway Drive" && it.muted && it.mbid == id } == true }
    }
}

package com.xlollx.songport.ui

import com.xlollx.songport.sync.FakeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ideas for one service: only what it can do, its own weekly playlist only where Trackhop reads it. */
class ServiceIdeasTest {
    private fun kinds(serviceId: String, liked: Boolean = true, removes: Boolean = true, creates: Boolean = true) =
        ServiceIdeas.forProvider(object : com.xlollx.songport.providers.MusicProvider by FakeProvider(serviceId) {
            override val supportsLikedSongs = liked
            override val canRemoveTracks = removes
            override val canCreatePlaylists = creates
        }).map { it.kind }

    @Test fun spotifyOnTheWebRouteGetsItsDiscoverWeekly() {
        val ideas = ServiceIdeas.forProvider(object : com.xlollx.songport.providers.MusicProvider by FakeProvider("spotify_bridge") {
            override val supportsLikedSongs = true
        })
        assertEquals("Discover Weekly", ideas.first().weekly)
        assertEquals(
            listOf(IdeaKind.WEEKLY, IdeaKind.BACKUP, IdeaKind.LIKED, IdeaKind.CHART, IdeaKind.DEDUPE, IdeaKind.WRAPPED),
            ideas.map { it.kind },
        )
    }

    @Test fun spotifysWebApiCannotReadItsOwnPlaylistsSoNoWeekly() {
        assertFalse(IdeaKind.WEEKLY in kinds("spotify"))
        assertTrue(IdeaKind.CHART in kinds("spotify"))
    }

    /** Where Spotify's quick route can be connected, the official one's idea says Spotify's rule and leads there. */
    @Test fun withTheQuickRouteAtHandTheOfficialSpotifyIdeaLeadsToIt() {
        val ideas = ServiceIdeas.forProvider(FakeProvider("spotify"), quickRoute = true)
        val weekly = ideas.single { it.kind == IdeaKind.WEEKLY }
        assertTrue(weekly.needsQuickRoute)
        assertFalse("no quick route idea elsewhere", ServiceIdeas.forProvider(FakeProvider("deezer"), quickRoute = true).any { it.needsQuickRoute })
    }

    @Test fun aServiceThatCannotRemoveOrCreateGetsOnlyWhatItCanDo() {
        val k = kinds("plex", liked = false, removes = false, creates = false)
        assertEquals(listOf(IdeaKind.BACKUP, IdeaKind.WRAPPED), k)
    }

    @Test fun theChartIsTheUsersCountryWhereAppleHasOne() {
        assertEquals("apple:it", ServiceIdeas.chartFor("it").first)
        assertEquals("deezer:3155776842", ServiceIdeas.chartFor("aq").first)
    }
}

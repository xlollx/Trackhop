package com.xlollx.songport.providers

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SpotifyMadeTest {
    @Test
    fun spotifysOwnPlaylistsAreKnownByIdOrOwner() {
        assertTrue(SpotifyMade.isSpotifyMade("37i9dQZF1EP6YuccBxUcC1")) // a daylist
        assertTrue(SpotifyMade.isSpotifyMade("37i9dQZEVXcJZyENOWUFo7")) // Discover Weekly
        assertTrue(SpotifyMade.isSpotifyMade("5abc", owner = "spotify"))
        assertFalse(SpotifyMade.isSpotifyMade("3cEYpjA9oz9GiPac4AsH4n", owner = "lorenzo.cannillo"))
        assertFalse(SpotifyMade.isSpotifyMade(null))
    }
}

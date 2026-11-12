package com.xlollx.songport.providers

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved albums on Amazon Music (11/2026): listed, saved and removed through the Bridge from its
 * 1.11.0 (versionCode 57), or built in. An album row is searched among albums only on its way to the
 * saved albums: synced into an Amazon playlist, which takes songs only, it is searched as songs.
 */
class AmazonAlbumSearchGateTest {
    private val album = Track(id = "", title = "Lo-files", artists = listOf("Bring Me The Horizon"), kind = Track.KIND_ALBUM)
    private val song = Track(id = "", title = "Lo Files", artists = listOf("Dream Events"))

    @Test fun savedAlbumsFromBridge57OrBuiltIn() {
        assertEquals(57, BridgePlugin.AMAZON_ALBUMS_VERSION)
        assertEquals(BridgePlugin.SITES_VERSION, BridgePlugin.AMAZON_ALBUMS_VERSION)
        // Trackhop Bridge 1.10.2 (56), the search fix, and before: `__albums__` would be read as a playlist id.
        assertFalse(AmazonBridgeProvider.savedAlbums(builtIn = false, version = 56))
        assertFalse(AmazonBridgeProvider.savedAlbums(builtIn = false, version = 0))
        assertTrue(AmazonBridgeProvider.savedAlbums(builtIn = false, version = 57))
        assertTrue(AmazonBridgeProvider.savedAlbums(builtIn = false, version = 60))
        assertTrue(AmazonBridgeProvider.savedAlbums(builtIn = true, version = 0))
    }

    @Test fun theGitHubBuildOffersSavedAlbumsButNotFollowedArtists() {
        // This test runs on the full flavour, where the connectors are built in.
        assertTrue(BridgePlugin.builtIn)
        val amazon = AmazonBridgeProvider()
        assertTrue(amazon.supportsAlbums)
        assertTrue(amazon.supportsLibrary(MusicProvider.ALBUMS_ID, asTarget = true))
        assertTrue(amazon.supportsLibrary(MusicProvider.ALBUMS_ID, asTarget = false))
        assertFalse(amazon.supportsArtists)
    }

    @Test fun theAlbumsSearchOnlyForAnAlbumGoingToTheSavedAlbums() {
        assertTrue(AmazonBridgeProvider.searchesAlbums(album, supportsAlbums = true, targetId = MusicProvider.ALBUMS_ID))
        // Into a playlist (or one still to be made): songs, as a playlist takes no album ids.
        assertFalse(AmazonBridgeProvider.searchesAlbums(album, supportsAlbums = true, targetId = "PL1"))
        assertFalse(AmazonBridgeProvider.searchesAlbums(album, supportsAlbums = true, targetId = null))
        // A Bridge without saved albums: songs everywhere.
        assertFalse(AmazonBridgeProvider.searchesAlbums(album, supportsAlbums = false, targetId = MusicProvider.ALBUMS_ID))
        assertFalse(AmazonBridgeProvider.searchesAlbums(song, supportsAlbums = true, targetId = MusicProvider.ALBUMS_ID))
        assertFalse(AmazonBridgeProvider.searchesAlbums(song, supportsAlbums = false, targetId = "PL1"))
    }
}

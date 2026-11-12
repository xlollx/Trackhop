package com.xlollx.songport.providers

import com.xlollx.songport.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved albums from Spotify into Amazon Music (11/2026): Amazon has no saved albums in Trackhop, so
 * they go into a playlist, which takes songs only. The albums search, whose ids a playlist refuses,
 * waits for a saved-albums target.
 */
class AmazonAlbumSearchGateTest {
    private val album = Track(id = "", title = "Lo-files", artists = listOf("Bring Me The Horizon"), kind = Track.KIND_ALBUM)
    private val song = Track(id = "", title = "Lo Files", artists = listOf("Dream Events"))

    @Test fun amazonHasNoSavedAlbumsSoAnAlbumIsSearchedAsSongs() {
        assertFalse(AmazonBridgeProvider().supportsAlbums)
        assertFalse(AmazonBridgeProvider.searchesAlbums(album, AmazonBridgeProvider().supportsAlbums))
    }

    @Test fun theAlbumsSearchOnlyForAnAlbumWhereSavedAlbumsExist() {
        assertTrue(AmazonBridgeProvider.searchesAlbums(album, supportsAlbums = true))
        assertFalse(AmazonBridgeProvider.searchesAlbums(song, supportsAlbums = true))
        assertFalse(AmazonBridgeProvider.searchesAlbums(song, supportsAlbums = false))
    }
}

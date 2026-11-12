package com.xlollx.songport.providers

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Saved albums and followed artists on Amazon Music (11/2026): listed and changed through the Bridge
 * from its 1.11.0 (versionCode 57), or built in. An album row is searched among albums only on its
 * way to the saved albums, an artist among artists only on its way to the followed artists: synced
 * into an Amazon playlist, which takes songs only, they are searched as songs.
 */
class AmazonLibraryGateTest {
    private val album = Track(id = "", title = "Lo-files", artists = listOf("Bring Me The Horizon"), kind = Track.KIND_ALBUM)
    private val artist = Track(id = "", title = "Bring Me The Horizon", kind = Track.KIND_ARTIST)
    private val song = Track(id = "", title = "Lo Files", artists = listOf("Dream Events"))

    @Test fun savedAlbumsAndFollowedArtistsFromBridge57OrBuiltIn() {
        assertEquals(57, BridgePlugin.AMAZON_LIBRARY_VERSION)
        assertEquals(BridgePlugin.SITES_VERSION, BridgePlugin.AMAZON_LIBRARY_VERSION)
        // Trackhop Bridge 1.10.2 (56), the search fix, and before: `__albums__` would be read as a playlist id.
        assertFalse(AmazonBridgeProvider.libraryKinds(builtIn = false, version = 56))
        assertFalse(AmazonBridgeProvider.libraryKinds(builtIn = false, version = 0))
        assertTrue(AmazonBridgeProvider.libraryKinds(builtIn = false, version = 57))
        assertTrue(AmazonBridgeProvider.libraryKinds(builtIn = false, version = 60))
        assertTrue(AmazonBridgeProvider.libraryKinds(builtIn = true, version = 0))
    }

    @Test fun theGitHubBuildOffersSavedAlbumsAndFollowedArtists() {
        // This test runs on the full flavour, where the connectors are built in.
        assertTrue(BridgePlugin.builtIn)
        val amazon = AmazonBridgeProvider()
        assertTrue(amazon.supportsAlbums)
        assertTrue(amazon.supportsArtists)
        for (id in listOf(MusicProvider.ALBUMS_ID, MusicProvider.ARTISTS_ID)) {
            assertTrue(amazon.supportsLibrary(id, asTarget = true))
            assertTrue(amazon.supportsLibrary(id, asTarget = false))
        }
    }

    @Test fun albumsAndArtistsAreSearchedAsSuchOnlyOnTheirWayToTheirLibrary() {
        assertEquals(Track.KIND_ALBUM, AmazonBridgeProvider.searchedKind(album, library = true, targetId = MusicProvider.ALBUMS_ID))
        assertEquals(Track.KIND_ARTIST, AmazonBridgeProvider.searchedKind(artist, library = true, targetId = MusicProvider.ARTISTS_ID))
        // Into a playlist (or one still to be made): songs, as a playlist takes no album or artist ids.
        assertNull(AmazonBridgeProvider.searchedKind(album, library = true, targetId = "PL1"))
        assertNull(AmazonBridgeProvider.searchedKind(album, library = true, targetId = null))
        assertNull(AmazonBridgeProvider.searchedKind(artist, library = true, targetId = "PL1"))
        // Nor into the other library.
        assertNull(AmazonBridgeProvider.searchedKind(album, library = true, targetId = MusicProvider.ARTISTS_ID))
        assertNull(AmazonBridgeProvider.searchedKind(artist, library = true, targetId = MusicProvider.ALBUMS_ID))
        // A Bridge without them: songs everywhere.
        assertNull(AmazonBridgeProvider.searchedKind(album, library = false, targetId = MusicProvider.ALBUMS_ID))
        assertNull(AmazonBridgeProvider.searchedKind(artist, library = false, targetId = MusicProvider.ARTISTS_ID))
        assertNull(AmazonBridgeProvider.searchedKind(song, library = true, targetId = MusicProvider.ALBUMS_ID))
    }
}

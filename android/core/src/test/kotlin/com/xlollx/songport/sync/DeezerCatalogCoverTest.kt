package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerCatalogCoverTest {
    @Test
    fun youtubeIdsGetTheirThumbnails() {
        val urls = DeezerCatalog.ownCover("dQw4w9WgXcQ", "ytm")
        assertEquals(listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"), urls)
        assertEquals(urls, DeezerCatalog.ownCover("dQw4w9WgXcQ", "youtube"))
    }

    @Test
    fun otherServicesAndBadIdsGetNone() {
        assertTrue(DeezerCatalog.ownCover("dQw4w9WgXcQ", "spotify").isEmpty())
        assertTrue(DeezerCatalog.ownCover("not-a-video-id", "ytm").isEmpty())
    }

    private fun album(title: String, artist: String, cover: String) =
        """{"title":"$title","artist":{"name":"$artist"},"cover":"$cover-small.jpg","cover_medium":"$cover-250.jpg"}"""

    @Test
    fun aRecordWithoutAPictureGetsTheArtistsAlbumOfThatTitle() {
        val body = """{"data":[
            ${album("Sempiternal", "Astral Bloom", "https://cdn/other")},
            ${album("Sempiternal (Expanded Edition)", "Bring Me The Horizon", "https://cdn/expanded")},
            ${album("Sempiternal", "Bring Me The Horizon", "https://cdn/sempiternal")}]}"""
        assertEquals("https://cdn/sempiternal-250.jpg", DeezerCatalog.pickAlbum(body, "Bring Me The Horizon", "Sempiternal"))
        // Only an edition of it: that one; "POST HUMAN" in capitals is the same title.
        val edition = """{"data":[${album("That's The Spirit (Deluxe Edition)", "Bring Me The Horizon", "https://cdn/spirit")}]}"""
        assertEquals("https://cdn/spirit-250.jpg", DeezerCatalog.pickAlbum(edition, "Bring Me The Horizon", "That's The Spirit"))
        val caps = """{"data":[${album("POST HUMAN: SURVIVAL HORROR", "Bring Me The Horizon", "https://cdn/ph")}]}"""
        assertEquals("https://cdn/ph-250.jpg", DeezerCatalog.pickAlbum(caps, "Bring Me The Horizon", "Post Human: Survival Horror"))
    }

    @Test
    fun anotherArtistOrAnotherTitleGivesNoPicture() {
        val body = """{"data":[
            ${album("Sempiternal", "Astral Bloom", "https://cdn/other")},
            ${album("Sempiternal Live", "Bring Me The Horizon Tribute", "https://cdn/tribute")},
            ${album("Amo", "Bring Me The Horizon", "javascript:alert(1)")}]}"""
        assertNull(DeezerCatalog.pickAlbum(body, "Bring Me The Horizon", "Sempiternal"))
        assertNull("not a web address", DeezerCatalog.pickAlbum(body, "Bring Me The Horizon", "Amo"))
        assertNull(DeezerCatalog.pickAlbum("""{"data":[]}""", "Bring Me The Horizon", "Amo"))
    }
}

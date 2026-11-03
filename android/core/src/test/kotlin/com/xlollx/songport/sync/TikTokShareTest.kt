package com.xlollx.songport.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A TikTok link shared to Trackhop: the song its page names, or why none. Pages saved in test/resources/tiktok. */
class TikTokShareTest {
    private fun page(name: String) = javaClass.getResource("/tiktok/$name.html")!!.readText()

    @After fun on() { TikTokShare.enabled = true }

    @Test fun linksOfEveryShape() {
        val cases = mapOf(
            "https://www.tiktok.com/@teamsabrina/video/7369238555878657326?lang=en" to "7369238555878657326",
            "Watch this https://vm.tiktok.com/ZMh3kLxYz/ !" to "ZMh3kLxYz",
            "https://vt.tiktok.com/ZSabc123/" to "ZSabc123",
            "https://www.tiktok.com/t/ZT8abcDEF/" to "ZT8abcDEF",
            "https://m.tiktok.com/v/7369238555878657326.html" to "7369238555878657326",
        )
        for ((text, id) in cases) {
            assertTrue(text, TikTokShare.isTikTok(text))
            assertEquals(text, id, TikTokShare.id(text))
            assertEquals(text, TrackLinks.Ref(TikTokShare.SERVICE, id), TrackLinks.parse(text))
        }
        assertFalse(TikTokShare.isTikTok("https://www.tiktok.com/@teamsabrina"))
        assertFalse(TikTokShare.isTikTok("https://open.spotify.com/track/abc"))
    }

    @Test fun aReleasedSongIsReadWithItsArtistAndAlbum() {
        val s = TikTokShare.parse(page("song")) as TikTokShare.Sound.Song
        assertEquals("Espresso", s.track.title)
        assertEquals(listOf("Sabrina Carpenter"), s.track.artists)
        assertEquals("Espresso", s.track.album)
        assertEquals("Sabrina Carpenter - Espresso", TikTokShare.query(s.track))
    }

    @Test fun theCreatorsOwnSoundNamesNoSong() {
        assertEquals(TikTokShare.Sound.Original("tiff"), TikTokShare.parse(page("original")))
        // No title at all: the same.
        assertTrue(TikTokShare.parse(page("notitle")) is TikTokShare.Sound.Original)
        // "original sound" in another language.
        val it = page("original").replace("original sound", "suono originale - Marco")
        assertTrue(TikTokShare.parse(it) is TikTokShare.Sound.Original)
    }

    @Test fun aPageWithoutTheDataIsUnknown() {
        assertEquals(TikTokShare.Sound.Unknown, TikTokShare.parse("<html><body>Log in to TikTok</body></html>"))
    }

    @Test fun offInTheOfficialOnlyBuilds() {
        TikTokShare.enabled = false
        assertFalse(TikTokShare.isTikTok("https://vm.tiktok.com/ZMh3kLxYz/"))
        assertNull(TrackLinks.parse("https://vm.tiktok.com/ZMh3kLxYz/"))
    }
}

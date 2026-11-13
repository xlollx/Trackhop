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

    @After fun on() { TikTokShare.enabled = true; TikTokShare.recognizer = null }

    @Test fun anOriginalSoundIsRecognisedFromItsAudioWhereRecognitionIsAvailable() = kotlinx.coroutines.runBlocking {
        val original = TikTokShare.Sound.Original("sophia", "https://v16m.tiktokcdn-us.com/abc/?mime_type=audio_mpeg")
        assertEquals(original, TikTokShare.hear(original))
        var asked: String? = null
        TikTokShare.recognizer = { url -> asked = url; com.xlollx.songport.model.Track("shz", "A Thousand Years", listOf("Christina Perri")) }
        val s = TikTokShare.hear(original) as TikTokShare.Sound.Song
        assertTrue(s.recognized)
        assertEquals("A Thousand Years", s.track.title)
        assertEquals(original.audio, asked)
        // Nothing recognised, or recognition failing: still the original sound.
        TikTokShare.recognizer = { null }
        assertEquals(original, TikTokShare.hear(original))
        TikTokShare.recognizer = { error("offline") }
        assertEquals(original, TikTokShare.hear(original))
        // Without its audio there is nothing to listen to.
        assertEquals(TikTokShare.Sound.Original("tiff"), TikTokShare.hear(TikTokShare.Sound.Original("tiff")))
    }

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
        assertEquals("tiff", (TikTokShare.parse(page("original")) as TikTokShare.Sound.Original).creator)
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

    /** No internet is the connection's failure, not a page "that could not be read": a run waits for it. */
    @Test fun noInternetIsNotAnUnreadablePage() = kotlinx.coroutines.runBlocking {
        val link = "https://vm.tiktok.com/ZMh3kLxYz/"
        try {
            com.xlollx.songport.net.Relay.via = { throw java.net.UnknownHostException("Unable to resolve host \"vm.tiktok.com\": No address associated with hostname") }
            val e = runCatching { TikTokShare.sound(link) }.exceptionOrNull()
            assertTrue(com.xlollx.songport.net.NetError.isNetwork(e))
            // TikTok's own answer (a page gone) is still "unreadable", as before.
            com.xlollx.songport.net.Relay.via = { com.xlollx.songport.net.Relay.Answer(404, "", it.url) }
            assertEquals(TikTokShare.Sound.Unknown, TikTokShare.sound(link))
            com.xlollx.songport.net.Relay.via = { throw IllegalStateException("bad answer") }
            assertEquals(TikTokShare.Sound.Unknown, TikTokShare.sound(link))
        } finally {
            com.xlollx.songport.net.Relay.via = null
        }
    }
}

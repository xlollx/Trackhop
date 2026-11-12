package com.xlollx.songport.providers

import com.xlollx.songport.net.parseJson
import com.xlollx.songport.sync.NewReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The connectors' answer to "spotify.whatsNew" as the followed artists' alerts read it. Made-up values. */
class SpotifyWhatsNewFeedTest {
    @Test fun aReleaseOfTheFeed() {
        val r = SpotifyBridgeProvider.feedRelease(parseJson("""{"uri":"spotify:album:al1","name":"Paper Lanterns","type":"SINGLE","date":"2030-03-14","artists":["Neon Harbour","Ölfa"]}"""))!!
        assertEquals("Paper Lanterns", r.title)
        assertEquals(NewReleases.Type.SINGLE, r.type)
        assertEquals("2030-03-14", r.date)
        assertEquals(listOf("Neon Harbour", "Ölfa"), r.artists)
        assertEquals("https://open.spotify.com/album/al1", r.url)
        assertEquals(NewReleases.Type.EP, SpotifyBridgeProvider.feedRelease(parseJson("""{"uri":"spotify:album:al2","name":"N","type":"EP","date":"2030-03","artists":[]}"""))!!.type)
        assertEquals(NewReleases.Type.ALBUM, SpotifyBridgeProvider.feedRelease(parseJson("""{"uri":"spotify:album:al3","name":"N","type":"COMPILATION","date":"2030-03-14","artists":[]}"""))!!.type)
        assertNull(SpotifyBridgeProvider.feedRelease(parseJson("""{"uri":"spotify:episode:e1","name":"Talk","date":"2030-03-14"}""")))
        assertNull(SpotifyBridgeProvider.feedRelease(parseJson("""{"uri":"spotify:album:al4","name":"No day","date":""}""")))
    }
}

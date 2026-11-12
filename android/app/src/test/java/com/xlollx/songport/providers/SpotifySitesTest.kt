package com.xlollx.songport.providers

import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The connectors' answer to "spotify.artist" as Merch reads it: the artist's id, links and merch; null for no artist. Made-up values. */
class SpotifySitesTest {
    @Test fun anArtistsPageAndNoArtist() {
        val a = SpotifySites.parse(parseJson("""{"uri":"spotify:artist:a1","links":["https://band.example/"],"merch":["https://shop.band.example/products/tee"]}"""))!!
        assertEquals("a1", a.id)
        assertEquals(listOf("https://band.example/"), a.links)
        assertEquals(listOf("https://shop.band.example/products/tee"), a.merch)
        assertNull(SpotifySites.parse(parseJson("null")))
        assertNull(SpotifySites.parse(parseJson("""{"uri":"spotify:track:t1"}""")))
    }
}

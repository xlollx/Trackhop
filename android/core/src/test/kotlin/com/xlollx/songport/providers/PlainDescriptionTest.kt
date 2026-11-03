package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #28: a Spotify daylist's description is HTML, and YouTube refuses "<" and ">" with a 400. */
class PlainDescriptionTest {
    @Test fun aDaylistsLinksBecomePlainText() {
        val daylist = "You&#x27;ve been listening to <a href=\"spotify:playlist:37i9dQZF1EIfwgv0\">dreamy</a> and " +
            "<a href=\"spotify:playlist:37i9dQZF1EIcJuX6\">indie folk</a> this tuesday morning &amp; more"
        assertEquals("You've been listening to dreamy and indie folk this tuesday morning & more", MusicProvider.plainDescription(daylist))
    }

    @Test fun nothingLeftForYouTubeToRefuse() {
        val d = MusicProvider.plainDescription("a < b > c &lt;tag&gt;")
        assertEquals(false, d.contains('<') || d.contains('>'))
        assertEquals("", MusicProvider.plainDescription(null))
        assertEquals(300, MusicProvider.plainDescription("x".repeat(500)).length)
    }
}

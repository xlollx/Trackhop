package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.SetlistBrowseActivity.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** setlist.fm browsed for Trackhop's store builds: where it starts, what stays on screen, what goes back and to whom. */
class SetlistBrowsePageTest {
    private val link = "https://www.setlist.fm/setlist/muse/2026/unipol-forum-milan-italy-63de4613.html"

    @Test fun itStartsOnAPastedLinkElseTheArtistsSearchElseTheHome() {
        assertEquals(link, Page.start("Muse", link))
        assertEquals("https://www.setlist.fm/search?query=Florence%20%2B%20the%20Machine", Page.start("Florence + the Machine", null))
        assertEquals("https://www.setlist.fm/search?query=Muse", Page.start(" Muse ", "https://example.com/setlist/x"))
        assertEquals("https://www.setlist.fm/", Page.start(null, null))
        assertEquals("https://www.setlist.fm/", Page.start(" ", null))
    }

    @Test fun setlistFmStaysHereAndOnlyAConcertsPageOffersThePlaylist() {
        assertTrue(Page.onSite("https://www.setlist.fm/search?query=Muse"))
        assertTrue(Page.onSite("https://setlist.fm/"))
        assertFalse(Page.onSite("https://setlist.fm.example.com/"))
        assertFalse(Page.onSite("https://example.com/?u=https://www.setlist.fm/"))
        assertFalse(Page.onSite("javascript:alert(1)"))
        assertTrue(Page.isSetlist(link))
        assertFalse(Page.isSetlist("https://www.setlist.fm/setlists/muse-13d6ad21.html"))
        assertFalse(Page.isSetlist("https://www.setlist.fm/search?query=Muse"))
        assertFalse(Page.isSetlist("https://example.com/?https://www.setlist.fm/setlist/x.html"))
    }

    @Test fun onlyTrackhopGetsTheAnswer() {
        assertTrue(Page.callerOk("com.xlollx.songport", "com.xlollx.songport.ytmbridge"))
        assertTrue(Page.callerOk("com.xlollx.songport.ytmbridge", "com.xlollx.songport.ytmbridge"))
        assertFalse(Page.callerOk("com.example.other", "com.xlollx.songport.ytmbridge"))
        // Not started for a result: there is no one to answer.
        assertFalse(Page.callerOk(null, "com.xlollx.songport.ytmbridge"))
    }

    @Test fun thePagesPartsComeBackAsTheScriptReturnedThem() {
        // evaluateJavascript gives the returned string as a JSON literal.
        val inner = """{"songs":2,"html":"<meta property=\"og:title\" content=\"Muse Setlist at Forum\">\n<a class=\"songLabel\" href=\"#\">Hysteria</a>"}"""
        val value = kotlinx.serialization.json.JsonPrimitive(inner).toString()
        val (html, songs) = Page.decode(value)!!
        assertEquals(2, songs)
        assertTrue(html.startsWith("<meta property=\"og:title\""))
        assertTrue(html.contains("<a class=\"songLabel\" href=\"#\">Hysteria</a>"))
        // No song yet, nothing read, or more than a concert's page: nothing goes back.
        assertNull(Page.decode(kotlinx.serialization.json.JsonPrimitive("""{"songs":0,"html":"<meta>"}""").toString()))
        assertNull(Page.decode("null"))
        assertNull(Page.decode(null))
        assertNull(Page.decode(kotlinx.serialization.json.JsonPrimitive("""{"songs":1,"html":"${"x".repeat(Page.MAX_PARTS + 1)}"}""").toString()))
        // The script reads, it does not touch the page.
        assertFalse(Page.COLLECT.contains(".click("))
        assertTrue(Page.COLLECT.contains("[class=\"songLabel\"]"))
    }
}

package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TicketOne's pages for Concerts: which addresses may be opened, and how the page's answer comes back. */
class TicketOnePageTest {
    @Test fun onlyTheSearchAndTheArtistsPagesOfTheSite() {
        assertTrue(TicketOnePage.Scripts.allowed("https://www.ticketone.it/search/?searchterm=Vasco%20Rossi"))
        assertTrue(TicketOnePage.Scripts.allowed("https://www.ticketone.it/artist/vasco-rossi/"))
        listOf(
            "http://www.ticketone.it/search/?searchterm=x", // not https
            "https://www.ticketone.it/", "https://www.ticketone.it/event/x-1/", "https://www.ticketone.it/myaccount/",
            "https://www.ticketone.it.example.com/search/?searchterm=x", "https://evil.example/search/?u=https://www.ticketone.it/search/",
            "https://public-api.eventim.com/search/", "javascript:alert(1)",
        ).forEach { assertFalse(it, TicketOnePage.Scripts.allowed(it)) }
        // Its own navigation (a redirect, the reload after the cookie choice) stays on the site.
        assertTrue(TicketOnePage.Scripts.onSite("https://www.ticketone.it/event/x-1/"))
        assertFalse(TicketOnePage.Scripts.onSite("https://ticketone.queue-it.net/?c=ticketone"))
    }

    @Test fun thePagesAnswerComesBackAsTheScriptReturnedIt() {
        // evaluateJavascript gives the returned string as a JSON literal.
        val value = "\"{\\\"state\\\":\\\"found\\\",\\\"ld\\\":[\\\"{\\\\\\\"@type\\\\\\\":\\\\\\\"MusicEvent\\\\\\\"}\\\"]}\""
        val o = TicketOnePage.Scripts.decode(value)!!
        assertEquals("\"found\"", o["state"].toString())
        assertEquals("clicked", TicketOnePage.Scripts.decodeString("\"clicked\""))
        assertNull(TicketOnePage.Scripts.decode("null"))
        assertNull(TicketOnePage.Scripts.decode("\"not json\""))
    }

    @Test fun theScriptsReadAndNeverSubmit() {
        // The collector only reads; the cookie script clicks one "necessary only" button at most.
        listOf("submit(", ".submit", "fetch(", "XMLHttpRequest", "location.href =", "location.assign", "password").forEach {
            assertFalse(it, TicketOnePage.Scripts.COLLECT.contains(it))
            assertFalse(it, TicketOnePage.Scripts.CONSENT.contains(it))
        }
        assertFalse(TicketOnePage.Scripts.COLLECT.contains(".click("))
    }
}

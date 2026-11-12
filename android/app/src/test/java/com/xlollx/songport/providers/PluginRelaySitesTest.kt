package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store builds' setlist.fm browsing and Vivaticket dates through a connector plugin
 * ([PluginRelay]): asked only of a plugin new enough for them, and how its answers are read.
 */
class PluginRelaySitesTest {
    @Test fun onlyAPluginThatRelaysAndIsNewEnoughIsAsked() {
        assertEquals(57, BridgePlugin.SITES_VERSION)
        assertTrue(PluginRelay.sitesGate(capable = true, version = BridgePlugin.SITES_VERSION))
        assertTrue(PluginRelay.sitesGate(capable = true, version = BridgePlugin.SITES_VERSION + 3))
        // Trackhop Bridge 1.10.2 (56), the Amazon Music fix, and before: not asked, Vivaticket stays off.
        assertFalse(PluginRelay.sitesGate(capable = true, version = 56))
        assertFalse(PluginRelay.sitesGate(capable = true, version = 55))
        // No status answered yet, or no plugin: nothing.
        assertFalse(PluginRelay.sitesGate(capable = true, version = 0))
        assertFalse(PluginRelay.sitesGate(capable = false, version = BridgePlugin.SITES_VERSION))
    }

    @Test fun thePluginsGatewayAnswerIsReadAsTheAppsOwn() {
        val a = PluginRelay.vivaticketAnswer(null, 200, """{"artists":{"items":[]}}""")!!
        assertEquals(200, a.code); assertEquals("""{"artists":{"items":[]}}""", a.body)
        assertEquals(204, PluginRelay.vivaticketAnswer(null, 204, null)!!.code)
        assertEquals("", PluginRelay.vivaticketAnswer(null, 204, null)!!.body)
        // A plugin that does not know the method: no answer (Vivaticket is left alone), not an error.
        assertNull(PluginRelay.vivaticketAnswer("unknown method vivaticket.read", 0, null))
        // Its other errors fail the request.
        assertTrue(runCatching { PluginRelay.vivaticketAnswer("vivaticket: only the gateway's search, artist and event answers are read", 0, null) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun theConcertChosenInThePluginsScreenComesBackAsASetlist() {
        val link = "https://www.setlist.fm/setlist/muse/2026/unipol-forum-milan-italy-63de4613.html"
        val parts = """<meta property="og:title" content="Muse Setlist at Unipol Forum, Milan, Italy">
            <div class="dateBlock"><span class="month">Nov</span><span class="day">8</span><span class="year">2026</span></div>
            <a class="songLabel" href="#">Hysteria</a>
            <a class="songLabel" href="#">Uprising</a>"""
        val s = PluginRelay.setlistFromPlugin(parts, link)
        assertEquals("Muse", s.artist); assertEquals("2026-11-08", s.date)
        assertEquals(listOf("Hysteria", "Uprising"), s.songs.map { it.title })
        // Nothing handed back, or not a concert's page of setlist.fm: no setlist.
        assertTrue(runCatching { PluginRelay.setlistFromPlugin(null, link) }.isFailure)
        assertTrue(runCatching { PluginRelay.setlistFromPlugin(parts, "https://example.com/setlist/x.html") }.isFailure)
        assertTrue(runCatching { PluginRelay.setlistFromPlugin(parts, null) }.isFailure)
    }
}

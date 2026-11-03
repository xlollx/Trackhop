package com.xlollx.songport.providers

import com.xlollx.songport.sync.PlaylistLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emby: Jellyfin's API under /emby, with Emby's own headers and web routes. */
class EmbyProviderTest {
    @Test fun theApiLivesUnderEmby() {
        assertEquals("https://emby.example.com/emby", EmbyProvider.embyRoot("https://emby.example.com"))
        assertEquals("https://emby.example.com/emby", EmbyProvider.embyRoot("https://emby.example.com/"))
        assertEquals("http://nas:8096/emby", EmbyProvider.embyRoot("http://nas:8096/emby"))
    }

    @Test fun theTokenTravelsInEmbysHeaders() {
        val anon = EmbyProvider.embyHeaders(null)
        assertTrue(anon.getValue("X-Emby-Authorization").startsWith("MediaBrowser Client=\"Trackhop\""))
        assertNull(anon["X-Emby-Token"])
        val signed = EmbyProvider.embyHeaders("abc")
        assertEquals("abc", signed["X-Emby-Token"])
        assertTrue(signed.getValue("X-Emby-Authorization").endsWith("Token=\"abc\""))
    }

    @Test fun webLinksUseEmbysRoutes() {
        assertEquals("http://nas:8096/web/index.html#!/search?query=Back+in+Black", EmbyProvider.embySearchUrl("http://nas:8096/emby", "Back in Black").replace("%20", "+"))
        assertEquals("http://nas:8096/web/index.html#!/item?id=42", PlaylistLinks.webUrl("emby", "42", base = "http://nas:8096"))
    }
}

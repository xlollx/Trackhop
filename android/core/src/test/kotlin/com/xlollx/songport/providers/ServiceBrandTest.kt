package com.xlollx.songport.providers

import com.xlollx.songport.sync.MemoryService
import org.junit.Test
import org.junit.Assert.assertEquals

class ServiceBrandTest {
    @Test fun aConnectorIsNamedAsItsService() {
        // The web route, the plugin, a renamed connector, a second account: always the service.
        assertEquals("Spotify", ServiceBrand.of("spotify_bridge", "Spotify (web)"))
        assertEquals("Spotify", ServiceBrand.of("spotify_bridge", "Spotify (plugin)"))
        assertEquals("Spotify", ServiceBrand.of("spotify", "My work account"))
        assertEquals("Spotify", ServiceBrand.of("spotify", "Spotify @2"))
        assertEquals("YouTube Music", ServiceBrand.of("youtube", "YouTube Music (Google API)"))
        assertEquals("YouTube Music", ServiceBrand.of("ytm", "YouTube Music"))
        assertEquals("Apple Music", ServiceBrand.of("apple_bridge", "Apple Music (web)"))
        assertEquals("Deezer", ServiceBrand.of("deezer_bridge", "Deezer (web)"))
    }

    @Test fun anUnknownServiceLosesItsRouteAndNumber() {
        assertEquals("Subsonic / Navidrome", ServiceBrand.of("subsonic", "Subsonic / Navidrome"))
        assertEquals("Newfy", ServiceBrand.of("newfy", "Newfy (web) @2"))
        assertEquals("Newfy", ServiceBrand.plain("Newfy #3"))
        assertEquals("(web)", ServiceBrand.plain("(web)"))
    }

    @Test fun aProviderGivesItsBrand() {
        val web = object : MemoryService("spotify_bridge") { override val displayName: String get() = "Spotify (web)" }
        assertEquals("Spotify", web.brand)
        val other = object : MemoryService("homeserver") { override val displayName: String get() = "Home (plugin)" }
        assertEquals("Home", other.brand)
    }
}

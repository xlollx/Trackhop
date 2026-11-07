package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Start over": which sites lose their stored data, and which connectors offer it. */
class WebResetTest {
    @Test fun theServicesOwnSitesOnly() {
        assertEquals(
            listOf("https://play.anghami.com", "https://anghami.com", "https://coussa.anghami.com"),
            WebReset.storageOrigins(AnghamiBridge.session.sites),
        )
        // Apple's player, never Apple ID's sign-in (shared with every app signed in through it).
        assertEquals(listOf("https://music.apple.com"), WebReset.storageOrigins(AppleBridge.session.sites))
        assertEquals(listOf("https://play.qobuz.com", "https://qobuz.com", "https://www.qobuz.com"), WebReset.storageOrigins(QobuzBridge.session.sites))
        listOf("accounts.google.com", "google.com", "www.facebook.com", "idmsa.apple.com", "music.youtube.com").forEach {
            assertTrue(it, WebReset.storageOrigins(listOf(it)).isEmpty())
        }
    }

    @Test fun theWebConnectorsOfferItYouTubeMusicAndAmazonDoNot() {
        listOf("anghami", "qobuz", "liveone", "deezer", "spotify", "apple", "soundcloud").forEach {
            assertTrue(it, BridgeCore.resettable(it, "") != null)
        }
        assertNull(BridgeCore.resettable("", ""))
        assertNull(BridgeCore.resettable("amazon", ""))
    }
}

package com.xlollx.songport.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The alpha and beta flags, the same in both builds (FlavorFlowTest checks how the cards show them). */
class ProviderFlagsTest {
    @Test fun deezerWebIsAlphaAndNotAlsoBeta() {
        val deezer = DeezerBridgeProvider()
        assertTrue(deezer.alpha)
        assertFalse(deezer.beta)
    }

    @Test fun theOfficialDeezerRouteIsNotAlpha() = assertFalse(DeezerProvider().alpha)

    /** SoundCloud with an account came out of alpha after real-account use (1.3.7) and is beta. */
    @Test fun soundCloudAccountIsBeta() {
        val sc = SoundCloudBridgeProvider()
        assertFalse("soundcloud is out of alpha", sc.alpha)
        assertTrue("soundcloud is beta", sc.beta)
    }
}

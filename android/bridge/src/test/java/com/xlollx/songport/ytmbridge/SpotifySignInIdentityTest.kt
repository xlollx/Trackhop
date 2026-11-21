package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotify's sign-in page as Chrome on the phone: the WebView's user agent without its WebView marks. */
class SpotifySignInIdentityTest {
    @Test fun theWebViewMarksAreDropped() {
        val webView = "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Mobile Safari/537.36",
            BridgeUa.phoneChromeUa(webView),
        )
    }

    @Test fun aChromeUserAgentStaysAsItIs() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, BridgeUa.phoneChromeUa(chrome))
    }

    @Test fun onlySpotifysSignInHostsGetIt() {
        assertTrue(BridgeUa.isSpotifySignIn("accounts.spotify.com"))
        assertTrue(BridgeUa.isSpotifySignIn("challenge.spotify.com"))
        assertFalse(BridgeUa.isSpotifySignIn("open.spotify.com"))
        assertFalse(BridgeUa.isSpotifySignIn("accounts.google.com"))
        assertFalse(BridgeUa.isSpotifySignIn(null))
    }
}

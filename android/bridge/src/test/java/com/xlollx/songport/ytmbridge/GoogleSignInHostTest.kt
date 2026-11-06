package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInHostTest {
    @Test fun googleSignInPagesAreTold() {
        assertTrue(BridgeUa.isGoogleSignIn("accounts.google.com"))
        assertTrue(BridgeUa.isGoogleSignIn("ACCOUNTS.GOOGLE.COM"))
        assertTrue(BridgeUa.isGoogleSignIn("accounts.youtube.com"))
        assertFalse(BridgeUa.isGoogleSignIn("accounts.spotify.com"))
        assertFalse(BridgeUa.isGoogleSignIn("open.spotify.com"))
        assertFalse(BridgeUa.isGoogleSignIn("google.com.evil.example"))
        assertFalse(BridgeUa.isGoogleSignIn("notgoogle.com"))
        assertFalse(BridgeUa.isGoogleSignIn(null))
    }
}

package com.xlollx.songport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved sign-ins when the phone's secure storage does not open: the app still starts (on an
 * empty store), and why is kept for Accounts and written in the diagnostic log. It used to be silent:
 * every service looked disconnected with no reason given.
 */
class TokenStoreFallbackTest {
    @Test fun aStoreThatDoesNotOpenFallsBackAndSaysWhy() {
        val said = ArrayList<String>()
        val got = TokenStore.openOr(
            encrypted = { throw java.security.GeneralSecurityException("Keystore operation failed", IllegalStateException("user not authenticated")) },
            plain = { "plain" },
            said = { said += it },
        )
        assertEquals("plain", got)
        val cause = TokenStore.unavailable
        assertTrue("$cause", cause!!.contains("GeneralSecurityException: Keystore operation failed") && cause.contains("IllegalStateException: user not authenticated"))
        assertEquals(listOf(cause), said)
    }

    @Test fun aLogThatCannotBeWrittenDoesNotStopTheFallback() {
        assertEquals("plain", TokenStore.openOr<String>({ throw java.io.IOException("no key") }, { "plain" }, { throw IllegalStateException("no disk") }))
        assertTrue(TokenStore.unavailable!!.contains("IOException: no key"))
    }

    @Test fun aStoreThatOpensHasNothingToSay() {
        val said = ArrayList<String>()
        assertEquals("encrypted", TokenStore.openOr({ "encrypted" }, { "plain" }, { said += it }))
        assertNull(TokenStore.unavailable)
        assertTrue(said.isEmpty())
    }
}

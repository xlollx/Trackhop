package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing out of a web connector (and starting the sign-in of an account that is not signed in)
 * deletes the service's cookies wherever they were set, so its page asks which account again.
 */
class CookieDeletionsTest {
    @Test fun aCookieIsDeletedForTheHostAndItsParentDomainsWithAndWithoutSecure() {
        assertEquals(
            listOf(
                "arl=; Max-Age=0; Path=/", "arl=; Max-Age=0; Path=/; Secure",
                "arl=; Max-Age=0; Domain=.www.deezer.com; Path=/", "arl=; Max-Age=0; Domain=.www.deezer.com; Path=/; Secure",
                "arl=; Max-Age=0; Domain=.deezer.com; Path=/", "arl=; Max-Age=0; Domain=.deezer.com; Path=/; Secure",
            ),
            WebSession.deletions("www.deezer.com", "arl"),
        )
    }

    @Test fun neverForATopLevelDomain() {
        val lines = WebSession.deletions("deezer.com", "sid")
        assertEquals(4, lines.size)
        assertTrue(lines.none { it.contains("Domain=.com") })
        assertTrue(lines.any { it.contains("Domain=.deezer.com") })
    }

    @Test fun aSecurePrefixedNameHasALineItAccepts() {
        assertTrue(WebSession.deletions("accounts.spotify.com", "__Host-sp_csrf_sid").contains("__Host-sp_csrf_sid=; Max-Age=0; Path=/; Secure"))
    }
}

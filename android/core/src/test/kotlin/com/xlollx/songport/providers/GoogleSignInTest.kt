package com.xlollx.songport.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInTest {
    private val g = "accounts.google.com"

    @Test fun accessDeniedOrAnUnverifiedAppNeedsATestUser() {
        assertTrue(GoogleSignIn.needsTestUser(g, "access_denied", null))
        assertTrue(GoogleSignIn.needsTestUser(g, null, "Access blocked: Trackhop has not completed the Google verification process"))
    }

    @Test fun otherErrorsAndOtherServicesAreLeftAlone() {
        assertFalse(GoogleSignIn.needsTestUser(g, "invalid_scope", "bad scope"))
        assertFalse(GoogleSignIn.needsTestUser("accounts.spotify.com", "access_denied", null))
        assertFalse(GoogleSignIn.needsTestUser(null, "access_denied", null))
    }

    @Test
    fun theAudiencePageOpensTheClientsProject() {
        org.junit.Assert.assertEquals("https://console.cloud.google.com/auth/audience?project=123456789012", GoogleSignIn.audienceUrl("123456789012-abc123def.apps.googleusercontent.com"))
        org.junit.Assert.assertEquals(GoogleSignIn.AUDIENCE_URL, GoogleSignIn.audienceUrl(null))
        org.junit.Assert.assertEquals(GoogleSignIn.AUDIENCE_URL, GoogleSignIn.audienceUrl("not-a-google-client"))
    }
}

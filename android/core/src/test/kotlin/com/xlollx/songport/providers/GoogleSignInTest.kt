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

    @Test
    fun consolePagesGoThroughTheAccountChooser() {
        org.junit.Assert.assertEquals("https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Fconsole.cloud.google.com%2Fauth%2Faudience%3Fproject%3D12345678",
            GoogleSignIn.chooseAccount("https://console.cloud.google.com/auth/audience?project=12345678"))
        org.junit.Assert.assertEquals("https://trackhop.org/guide/youtube-google-key", GoogleSignIn.chooseAccount("https://trackhop.org/guide/youtube-google-key"))
    }
}

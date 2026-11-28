package com.xlollx.songport.providers

/** What Google's sign-in answers mean for a person using their own Google key. */
object GoogleSignIn {
    const val AUDIENCE_URL = "https://console.cloud.google.com/auth/audience"
    const val DOMAIN = "accounts.google.com"

    /**
     * Google refused the sign-in of a project still in Testing because the person's Gmail is not under
     * "Test users" (access_denied, "has not completed the Google verification process"): the fix is on
     * Google's Audience page, not in the app. [authDomain] is the sign-in page's host.
     */
    fun needsTestUser(authDomain: String?, error: String?, description: String?): Boolean =
        authDomain == DOMAIN && (error.equals("access_denied", true) ||
            description?.contains("verification process", true) == true || description?.contains("access_denied", true) == true)
}

package com.xlollx.songport.providers

/** What Google's sign-in answers mean for a person using their own Google key. */
object GoogleSignIn {
    const val AUDIENCE_URL = "https://console.cloud.google.com/auth/audience"
    const val DOMAIN = "accounts.google.com"

    /**
     * The Audience page of the project the person's client belongs to: a Google client ID starts with
     * its project's number ("123456789012-abc….apps.googleusercontent.com"), and the console opens that
     * project with `?project=<number>` whichever one was open last. Without a client ID, the page as is.
     */
    /**
     * A Google Cloud console page through Google's account chooser: the console opens otherwise with the
     * phone's first Google account, often not the one the key is for. Other addresses are left as they are.
     */
    fun chooseAccount(url: String): String =
        if (url.startsWith("https://console.cloud.google.com/") || url.startsWith("https://console.developers.google.com/"))
            "https://accounts.google.com/AccountChooser?continue=" + java.net.URLEncoder.encode(url, "UTF-8")
        else url

    fun audienceUrl(clientId: String?): String =
        clientId?.trim()?.let { Regex("""^(\d{6,})-""").find(it)?.groupValues?.get(1) }?.let { "$AUDIENCE_URL?project=$it" } ?: AUDIENCE_URL

    /**
     * Google refused the sign-in of a project still in Testing because the person's Gmail is not under
     * "Test users" (access_denied, "has not completed the Google verification process"): the fix is on
     * Google's Audience page, not in the app. [authDomain] is the sign-in page's host.
     */
    fun needsTestUser(authDomain: String?, error: String?, description: String?): Boolean =
        authDomain == DOMAIN && (error.equals("access_denied", true) ||
            description?.contains("verification process", true) == true || description?.contains("access_denied", true) == true)
}

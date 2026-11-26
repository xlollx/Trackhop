package com.xlollx.songport.ytmbridge

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Amazon sign-in keeps the account's own regional site (26/11: an Italian account's sign-in
 * closed on music.amazon.com, where the player started first, and the session was refused a minute
 * later; Amazon moves the account to music.amazon.it only afterwards).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AmazonRegionalSignInTest {
    @Test fun readyOnDotComWithAnItalianMarketplaceGoesOnToMusicAmazonIt() {
        assertEquals("music.amazon.it", AmazonClient.regionalDomain("music.amazon.com", "gql.music.amazon.com", "APJ6JRA9NG5V4", null))
        // The territory alone says it too, when the configuration has no marketplace id.
        assertEquals("music.amazon.it", AmazonClient.regionalDomain("music.amazon.com", null, null, "IT"))
        assertEquals("music.amazon.co.uk", AmazonClient.regionalDomain("music.amazon.com", null, "A1F83G8C2ARO7P", null))
        assertEquals("music.amazon.co.jp", AmazonClient.regionalDomain("music.amazon.com", null, null, "jp"))
    }

    @Test fun theAccountsOwnSiteIsKept() {
        assertNull(AmazonClient.regionalDomain("music.amazon.it", "gql.music.amazon.it", "APJ6JRA9NG5V4", "IT"))
        assertNull(AmazonClient.regionalDomain("music.amazon.com", "gql.music.amazon.com", "ATVPDKIKX0DER", "US"))
        // Nothing known of the store: kept where the player is.
        assertNull(AmazonClient.regionalDomain("music.amazon.com", null, null, null))
        assertNull(AmazonClient.regionalDomain("music.amazon.com", null, "UNKNOWN", "XX"))
    }

    @Test fun restOfEuropeAccountsOnDotComWithAnEuHostStayThere() {
        // AmazonBridge's EU-side API hosts (gql-eu…, eu.gql…): the account lives on music.amazon.com.
        assertNull(AmazonClient.regionalDomain("music.amazon.com", "gql-eu.music.amazon.com", "A1805IZSGTT6HS", "NL"))
        assertNull(AmazonClient.regionalDomain("music.amazon.com", "eu.gql.music.amazon.com", null, "NL"))
        // A store with no regional site of its own (Belgium, Turkey): kept where it is.
        assertNull(AmazonClient.regionalDomain("music.amazon.com", "gql.music.amazon.com", "AMEN7PMS3EDWL", "BE"))
        assertNull(AmazonClient.regionalDomain("music.amazon.com", null, "A33AVAJ2PDY3EV", null))
    }

    @Test fun theLastWorkingSiteOutlivesASignOut() {
        val app = RuntimeEnvironment.getApplication() as Application
        AmazonSession.save(app, "at-acbit=x; session-id=1", "music.amazon.it", null)
        assertEquals("music.amazon.it", AmazonSession.lastDomain(app))
        AmazonSession.clear(app)
        assertEquals("music.amazon.it", AmazonSession.lastDomain(app))
        // Only a regional player's host is kept.
        AmazonSession.rememberDomain(app, "evil.example.com")
        assertEquals("music.amazon.it", AmazonSession.lastDomain(app))
    }
}

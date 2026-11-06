package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a web connector's second account is offered ([WebAccounts]): only with a WebView that keeps
 * profiles apart and connectors that take a slot; a plugin from before slots is told to update.
 */
class WebAccountsTest {
    @Test fun anotherAccountNeedsProfilesAndConnectorsThatTakeASlot() {
        assertEquals(WebAccounts.State.AVAILABLE, WebAccounts.decide(profiles = true, answered = true, slots = true))
        // The built-in connectors and an updated plugin say `slots`; an older plugin says nothing.
        assertEquals(WebAccounts.State.PLUGIN_OLD, WebAccounts.decide(profiles = true, answered = true, slots = false))
        // No connectors answer: nothing to offer, and nothing to explain either.
        assertEquals(WebAccounts.State.NOT_SERVED, WebAccounts.decide(profiles = true, answered = false, slots = false))
        assertEquals(WebAccounts.State.NOT_SERVED, WebAccounts.decide(profiles = true, answered = false, slots = true))
    }

    @Test fun aWebViewWithoutProfilesOffersNoExtraAccountWhateverTheConnectorsSay() {
        assertEquals(WebAccounts.State.NO_PROFILES, WebAccounts.decide(profiles = false, answered = true, slots = true))
        assertEquals(WebAccounts.State.NO_PROFILES, WebAccounts.decide(profiles = false, answered = true, slots = false))
        assertEquals(WebAccounts.State.NO_PROFILES, WebAccounts.decide(profiles = false, answered = false, slots = false))
    }

    @Test fun withoutTheApplicationNothingIsOffered() {
        WebAccounts.app = null
        assertEquals(false, WebAccounts.allowed("spotify.status"))
    }
}

package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Amazon Music's purchased music is offered only where the Bridge app reads it, and asked about at most every few hours. */
class AmazonPurchasedOfferTest {
    private val hour = 3_600_000L

    @Test fun askedOnceThenRememberedForAWhile() {
        var asked = 0
        val first = AmazonBridgeProvider.purchasedOffered(null, now = 0) { asked++; 12 }
        assertEquals(12, first)
        assertEquals(12, AmazonBridgeProvider.purchasedOffered(0L to 12, now = 5 * hour) { asked++; 0 })
        assertEquals(1, asked)
        // After six hours, asked again.
        assertEquals(0, AmazonBridgeProvider.purchasedOffered(0L to 12, now = 6 * hour) { asked++; 0 })
        assertEquals(2, asked)
    }

    @Test fun notReadableIsNotOfferedAndAskedAgainSooner() {
        // An older Bridge app, the skill API, or a failure: -1, not offered.
        assertEquals(-1, AmazonBridgeProvider.purchasedOffered(null, now = 0) { null })
        var asked = 0
        assertEquals(-1, AmazonBridgeProvider.purchasedOffered(0L to -1, now = hour - 1) { asked++; 3 })
        assertEquals(3, AmazonBridgeProvider.purchasedOffered(0L to -1, now = hour) { asked++; 3 })
        assertEquals(1, asked)
    }
}

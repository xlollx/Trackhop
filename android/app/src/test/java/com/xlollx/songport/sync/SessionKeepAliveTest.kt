package com.xlollx.songport.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeepAliveTest {
    @Test fun scheduledOnlyWhileASoundCloudConnectorIsThere() {
        assertTrue(SessionKeepAlive.wanted(listOf("spotify", "soundcloud")))
        assertFalse(SessionKeepAlive.wanted(listOf("spotify", "deezer_web")))
        assertFalse(SessionKeepAlive.wanted(emptyList()))
    }

    @Test fun theRenewalWaitsForTheNetworkAndABatteryNotLow() {
        val c = Scheduler.keepAliveConstraints()
        assertEquals(NetworkType.CONNECTED, c.requiredNetworkType)
        assertTrue(c.requiresBatteryNotLow())
        assertFalse(c.requiresCharging())
        assertTrue(SessionKeepAlive.EVERY_HOURS in 12..24)
    }

    @Test fun anExpiryIsToldOnce() {
        assertFalse("a live session", SessionKeepAlive.shouldNotify(null, null))
        assertTrue("a new expiry", SessionKeepAlive.shouldNotify("1000", null))
        assertFalse("already told", SessionKeepAlive.shouldNotify("1000", "1000"))
        // Signed in again, then expired again: a new expiry, told again.
        assertTrue(SessionKeepAlive.shouldNotify("2000", "1000"))
    }
}

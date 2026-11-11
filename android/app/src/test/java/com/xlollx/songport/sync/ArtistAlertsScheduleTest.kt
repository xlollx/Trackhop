package com.xlollx.songport.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAlertsScheduleTest {
    @Test fun theLookWaitsForTheNetworkAndABatteryNotLow() {
        val c = Scheduler.alertConstraints(ArtistAlerts.Plan(1, unmetered = false))
        assertEquals(NetworkType.CONNECTED, c.requiredNetworkType)
        assertTrue(c.requiresBatteryNotLow())
        assertFalse(c.requiresCharging())
    }

    @Test fun wifiOnlyWaitsForAnUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, Scheduler.alertConstraints(ArtistAlerts.Plan(7, unmetered = true)).requiredNetworkType)
    }
}

package com.xlollx.songport.ui

import com.xlollx.songport.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which "What's new" releases open by themselves at a start, from what the device has seen. */
class WhatsNewTest {
    private val releases = listOf("1.7", "1.6", "1.5", "1.4")

    @Test fun aFreshInstallSeesNothing() {
        // The welcome is still to come: it marks the newest as seen when it ends.
        assertEquals(emptyList<String>(), WhatsNew.versionsToShow(releases, seen = "", onboardingDone = false))
    }

    @Test fun anUpdateFromBeforeTheSheetSeesTheNewestThree() {
        assertEquals(listOf("1.7", "1.6", "1.5"), WhatsNew.versionsToShow(releases, seen = "", onboardingDone = true))
        assertEquals(listOf("1.4"), WhatsNew.versionsToShow(listOf("1.4"), seen = "", onboardingDone = true))
    }

    @Test fun theNewestAlreadySeenShowsNothing() {
        assertEquals(emptyList<String>(), WhatsNew.versionsToShow(releases, seen = "1.7", onboardingDone = true))
        // A release without an entry (a bug fix) after it changes nothing.
        assertEquals(emptyList<String>(), WhatsNew.versionsToShow(releases, seen = "1.7.2", onboardingDone = true))
    }

    @Test fun anOlderSeenShowsOnlyTheNewerOnes() {
        assertEquals(listOf("1.7", "1.6"), WhatsNew.versionsToShow(releases, seen = "1.5", onboardingDone = true))
        assertEquals(listOf("1.7"), WhatsNew.versionsToShow(releases, seen = "1.6.3", onboardingDone = true))
    }

    @Test fun neverMoreThanThree() {
        val many = (20 downTo 1).map { "1.$it" }
        assertEquals(listOf("1.20", "1.19", "1.18"), WhatsNew.versionsToShow(many, seen = "1.2", onboardingDone = true))
        assertEquals(WhatsNew.MAX_SHOWN, WhatsNew.versionsToShow(many, seen = "", onboardingDone = true).size)
    }

    @Test fun versionsCompareAsNumbers() {
        assertTrue(WhatsNew.compareVersions("1.10", "1.9") > 0)
        assertTrue(WhatsNew.compareVersions("1.4", "1.3.10") > 0)
        assertEquals(0, WhatsNew.compareVersions("1.4", "1.4.0"))
        assertTrue(WhatsNew.compareVersions("1.4", "1.4.1") < 0)
        assertTrue(WhatsNew.compareVersions("", "1.0") < 0)
        assertTrue(WhatsNew.compareVersions("1.0", "") > 0)
        assertEquals(0, WhatsNew.compareVersions("", ""))
    }

    @Test fun theAppsOwnListIsNewestFirstAndDueOnlyAfterAnUpdate() {
        val versions = WhatsNew.releases.map { it.version }
        assertEquals(versions, versions.sortedWith { a, b -> WhatsNew.compareVersions(b, a) })
        assertEquals("1.12", WhatsNew.newestVersion)
        assertTrue(WhatsNew.releases.all { it.items.isNotEmpty() })
        assertEquals(listOf(WhatsNew.newestVersion), WhatsNew.due(Settings(onboardingDone = true)).map { it.version }.take(1))
        assertTrue(WhatsNew.due(Settings(onboardingDone = false)).isEmpty())
        assertTrue(WhatsNew.due(Settings(onboardingDone = true, whatsNewSeen = WhatsNew.newestVersion)).isEmpty())
    }
}

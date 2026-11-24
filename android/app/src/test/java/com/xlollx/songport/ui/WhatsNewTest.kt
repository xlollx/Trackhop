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
        assertTrue(WhatsNew.compareVersions("2.0", "1.17") > 0)
        assertTrue(WhatsNew.compareVersions("2.0", "1.16.3") > 0)
        assertEquals(0, WhatsNew.compareVersions("2.0", "2.0.0"))
        assertTrue(WhatsNew.compareVersions("2.1", "2.0") > 0)
        assertTrue(WhatsNew.compareVersions("2.1", "2.0.9") > 0)
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
        assertEquals("2.2", WhatsNew.newestVersion)
        assertTrue(WhatsNew.releases.all { it.items.isNotEmpty() })
        assertEquals(listOf(WhatsNew.newestVersion), WhatsNew.due(Settings(onboardingDone = true)).map { it.version }.take(1))
        assertTrue(WhatsNew.due(Settings(onboardingDone = false)).isEmpty())
        assertTrue(WhatsNew.due(Settings(onboardingDone = true, whatsNewSeen = WhatsNew.newestVersion)).isEmpty())
    }

    @Test fun twoPointZeroOpensWithGooglePlayAndHoldsTheUnreleasedOneSeventeen() {
        val versions = WhatsNew.releases.map { it.version }
        // 1.17 was never released on its own: its highlights are in 2.0.
        assertTrue("1.17" !in versions)
        val two = WhatsNew.releases.first { it.version == "2.0" }
        assertEquals(com.xlollx.songport.R.string.whats_new_20_celebrate_title, two.items.first().title)
        assertTrue(two.items.any { it.title == com.xlollx.songport.R.string.whats_new_117_look_title })
        assertTrue(two.items.any { it.title == com.xlollx.songport.R.string.whats_new_119_shared_title })
        // Someone coming from 1.16.3 sees the newest three, which hold 2.0.
        assertEquals(listOf("2.2", "2.1", "2.0"), WhatsNew.versionsToShow(versions, seen = "1.16.3", onboardingDone = true))
        assertEquals(listOf("2.2", "2.1", "2.0"), WhatsNew.versionsToShow(versions, seen = "", onboardingDone = true))
    }

    @Test fun twoPointOneComesAfterTwoPointZero() {
        val versions = WhatsNew.releases.map { it.version }
        assertEquals(listOf("2.2", "2.1", "2.0"), versions.take(3))
        val one = WhatsNew.releases.first { it.version == "2.1" }
        assertEquals(com.xlollx.songport.R.string.whats_new_21_collections_title, one.items.first().title)
        assertTrue(one.items.any { it.title == com.xlollx.songport.R.string.whats_new_21_tofix_title })
        // Someone on 2.0.0 sees only 2.1.
        assertEquals(listOf("2.2", "2.1"), WhatsNew.versionsToShow(versions, seen = "2.0.0", onboardingDone = true))
    }

    @Test fun twoPointTwoIsTheNewest() {
        val two = WhatsNew.releases.first()
        assertEquals("2.2", two.version)
        assertEquals(com.xlollx.songport.R.string.whats_new_22_chooser_title, two.items.first().title)
        assertEquals(listOf("2.2"), WhatsNew.versionsToShow(WhatsNew.releases.map { it.version }, seen = "2.1.6", onboardingDone = true))
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.model.RadioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RadioTitlesTest {

    @Test fun readsTheTitleOutOfTheIcyBlock() {
        assertEquals("Daft Punk - Digital Love", RadioTitles.streamTitle("StreamTitle='Daft Punk - Digital Love';StreamUrl='';\u0000\u0000"))
        assertNull(RadioTitles.streamTitle("StreamTitle='';"))
        assertNull(RadioTitles.streamTitle("nothing"))
    }

    @Test fun aSongIsArtistDashTitle() {
        val t = RadioTitles.track("Daft Punk - Digital Love")!!
        assertEquals("Digital Love", t.title)
        assertEquals(listOf("Daft Punk"), t.artists)
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), RadioTitles.track("Calvin Harris & Dua Lipa – One Kiss (Radio Edit)")!!.artists)
        assertEquals("One Kiss (Radio Edit)", RadioTitles.track("Calvin Harris – One Kiss (Radio Edit) | Radio Deejay")!!.title)
    }

    @Test fun mediasetStationsSendARecordTitleFirst() {
        val raw = "Sweet Home Alabama~Lynyrd Skynyrd~~1974~~211~2026-11-02T08:54:14~2026-11-02T08:56:17~Virgin Radio Classic Rock~123.96~dbced0a3-8299-472b-93e8-ff26ff6d22d8"
        val t = RadioTitles.track(raw, "Virgin Radio Classic Rock")!!
        assertEquals("Sweet Home Alabama", t.title)
        assertEquals(listOf("Lynyrd Skynyrd"), t.artists)
        assertEquals("Lynyrd Skynyrd - Sweet Home Alabama", RadioTitles.display(raw, "Virgin Radio Classic Rock"))
        // The station talking: no artist.
        assertNull(RadioTitles.track("Virgin Radio Classic Rock~~~~~0~2026-11-02T09:00:00~~Virgin Radio Classic Rock~~x", "Virgin Radio Classic Rock"))
        assertEquals("Virgin Radio Classic Rock", RadioTitles.display("Virgin Radio Classic Rock~~~~~0~2026-11-02T09:00:00~~Virgin Radio Classic Rock~~x"))
        // "Artist ~ Title" with spaces stays the plain separator.
        assertEquals("Digital Love", RadioTitles.track("Daft Punk ~ Digital Love")!!.title)
    }

    @Test fun theStationTalkingIsNotASong() {
        assertNull(RadioTitles.track("Radio Deejay", "Radio Deejay"))
        assertNull(RadioTitles.track("Radio Deejay - Radio Deejay", "Radio Deejay"))
        assertNull(RadioTitles.track("Advert - Spring Sale"))
        assertNull(RadioTitles.track("Giornale Radio - Ore 12"))
        assertNull(RadioTitles.track("www.radio.it - Listen live"))
        assertNull(RadioTitles.track("Just a title"))
    }

    @Test fun oneKeyPerSongWhateverTheSpelling() {
        assertEquals(RadioTitles.key(RadioTitles.track("Daft Punk - Digital Love")!!), RadioTitles.key(RadioTitles.track("DAFT PUNK  -  Digital  Love")!!))
    }

    @Test fun theWindowFollowsDaysAndHoursAcrossMidnight() {
        val weekdays = RadioSource("R", "http://x", days = listOf(1, 2, 3, 4, 5), startMinute = 8 * 60, endMinute = 10 * 60)
        val monday9 = LocalDateTime.of(2026, 11, 2, 9, 0)
        assertTrue(RadioTitles.inWindow(weekdays, monday9))
        assertFalse(RadioTitles.inWindow(weekdays, monday9.withHour(10)))
        assertFalse(RadioTitles.inWindow(weekdays, monday9.plusDays(5)))
        val night = RadioSource("R", "http://x", days = listOf(5), startMinute = 22 * 60, endMinute = 2 * 60)
        val friday23 = LocalDateTime.of(2026, 11, 6, 23, 0)
        assertTrue(RadioTitles.inWindow(night, friday23))
        assertTrue("Saturday 01:00 belongs to Friday's night", RadioTitles.inWindow(night, friday23.plusHours(2)))
        assertFalse(RadioTitles.inWindow(night, friday23.minusDays(1).plusHours(2)))
        assertTrue(RadioTitles.inWindow(RadioSource("R", "http://x"), monday9))
        assertEquals("Mon–Fri 08:00–10:00", RadioTitles.describe(weekdays))
    }
}

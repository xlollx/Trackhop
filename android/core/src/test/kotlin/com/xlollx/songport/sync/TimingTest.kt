package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimingTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun t(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), rome).toInstant().toEpochMilli()
    private val base = SyncJob(id = "j", name = "j", source = PlaylistRef("a", "p"), target = PlaylistRef("b", "q"))

    @Test fun oldSyncsKeepTheirSchedule() {
        val j = base.copy(schedule = Schedule.EVERY_6H, lastRunEpoch = t("2026-11-03T10:00"))
        assertEquals(t("2026-11-03T16:00"), Timing.nextRun(j, t("2026-11-03T11:00"), rome))
        assertNull(Timing.nextRun(base, t("2026-11-03T11:00"), rome))
    }

    @Test fun everyThreeHours() {
        val j = Timing.apply(base, 180, null, emptyList()).copy(lastRunEpoch = t("2026-11-03T10:00"))
        assertEquals(t("2026-11-03T13:00"), Timing.nextRun(j, t("2026-11-03T11:00"), rome))
        assertFalse(Timing.isDue(j, t("2026-11-03T12:59"), rome))
        assertTrue(Timing.isDue(j, t("2026-11-03T13:00"), rome))
    }

    @Test fun everyDayAtHalfPastSeven() {
        val j = Timing.apply(base, 1440, "07:30", emptyList()).copy(lastRunEpoch = t("2026-11-03T07:41"))
        assertEquals(t("2026-11-04T07:30"), Timing.nextRun(j, t("2026-11-03T12:00"), rome))
        // A run that came late does not push the next one by a day.
        val late = j.copy(lastRunEpoch = t("2026-11-03T09:10"))
        assertEquals(t("2026-11-04T07:30"), Timing.nextRun(late, t("2026-11-03T12:00"), rome))
    }

    @Test fun everyTwoDaysAtNight() {
        val j = Timing.apply(base, 2880, "23:00", emptyList()).copy(lastRunEpoch = t("2026-11-03T23:02"))
        assertEquals(t("2026-11-05T23:00"), Timing.nextRun(j, t("2026-11-04T08:00"), rome))
    }

    @Test fun mondayWednesdayFriday() {
        // 2026-11-03 is a Tuesday.
        val j = Timing.apply(base, 10080, "08:00", listOf(5, 1, 3)).copy(lastRunEpoch = t("2026-11-02T08:05"))
        assertEquals(listOf(1, 3, 5), j.days)
        assertEquals(t("2026-11-04T08:00"), Timing.nextRun(j, t("2026-11-03T12:00"), rome))
        val afterFriday = j.copy(lastRunEpoch = t("2026-11-06T08:01"))
        assertEquals(t("2026-11-09T08:00"), Timing.nextRun(afterFriday, t("2026-11-07T12:00"), rome))
    }

    @Test fun everyDayOfTheWeekIsDaily() {
        val j = Timing.apply(base, 10080, "07:30", (1..7).toList())
        assertEquals(emptyList<Int>(), j.days)
        assertEquals(1440, Timing.interval(j))
        assertEquals(Schedule.DAILY, j.schedule)
    }

    @Test fun limitsAndTheOldChoice() {
        assertEquals(60, Timing.interval(Timing.apply(base, 5, null, emptyList())))
        assertEquals(10080, Timing.interval(Timing.apply(base, 99999, null, emptyList())))
        assertEquals(Schedule.EVERY_6H, Timing.apply(base, 480, null, emptyList()).schedule)
        assertNull(Timing.apply(base, 180, "07:30", emptyList()).at)
        val never = Timing.apply(base, 1440, "07:30", emptyList())
        assertEquals(t("2026-11-04T07:30"), Timing.nextRun(never, t("2026-11-03T12:00"), rome))
    }

    @Test fun weeklyOnTheChosenDay() {
        // 2026-11-07 is a Saturday: "Every Saturday at 09:00", run last Saturday a little late.
        val j = Timing.apply(base, 10080, "09:00", listOf(6)).copy(lastRunEpoch = t("2026-10-31T09:04"))
        assertEquals(Schedule.WEEKLY, j.schedule)
        assertEquals("every Sat at 09:00", Timing.describe(j))
        assertEquals(t("2026-11-07T09:00"), Timing.nextRun(j, t("2026-11-03T12:00"), rome))
        // Saved on a Wednesday, never run: the coming Saturday, not right away.
        val fresh = Timing.apply(base, 10080, "09:00", listOf(6))
        assertEquals(t("2026-11-07T09:00"), Timing.nextRun(fresh, t("2026-11-04T18:00"), rome))
    }
}

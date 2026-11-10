package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The warning about many syncs reaching one service at once (issue #56: eleven Qobuz syncs at 09:00). */
class CrowdedTest {
    private fun job(id: String, src: String, at: String?, minutes: Int = 1440, days: List<Int> = emptyList(), dst: String = "spotify", on: Boolean = true) =
        Timing.apply(SyncJob(id = id, name = id, source = PlaylistRef(src, id, id), target = PlaylistRef(dst, "t$id", id), enabled = on), minutes, at, days)

    @Test fun fourDailySyncsOfOneServiceWithinHalfAnHourAreACrowd() {
        val jobs = listOf(job("a", "qobuz_web", "09:00"), job("b", "qobuz_web", "09:00"), job("c", "qobuz_web", "09:10"), job("d", "qobuz_web", "09:30"), job("e", "qobuz_web", "14:00"))
        val crowd = Timing.crowded(jobs)!!
        // Each sync also writes to Spotify: same group, either service may be named; Qobuz reads come first in the sorted set.
        assertEquals(listOf("a", "b", "c", "d"), crowd.jobIds)
        assertEquals("09:00", crowd.at)
    }

    @Test fun spreadOutDisabledUntimedOrOnOtherDaysIsNoCrowd() {
        assertNull(Timing.crowded(listOf(job("a", "x", "09:00"), job("b", "x", "10:00"), job("c", "x", "11:00"), job("d", "x", "12:00"), job("e", "x", "13:00"))))
        assertNull(Timing.crowded(listOf(job("a", "x", "09:00"), job("b", "x", "09:00"), job("c", "x", "09:00"), job("d", "x", "09:00", on = false))))
        assertNull(Timing.crowded(listOf(job("a", "x", null, 180), job("b", "x", null, 180), job("c", "x", null, 180), job("d", "x", null, 180))))
        // Weekly on four different days: never together.
        assertNull(Timing.crowded((1..4).map { job("w$it", "x", "09:00", 10080, listOf(it)) }))
        // Files only: nothing goes over the network.
        assertNull(Timing.crowded((1..4).map { job("f$it", "csv", "09:00", dst = "csv") }))
    }

    @Test fun theLargestGroupIsTheOneShown() {
        val jobs = (1..4).map { job("x$it", "deezer", "08:00", dst = "tidal") } + (1..6).map { job("y$it", "qobuz_web", "21:00", dst = "plex") }
        val crowd = Timing.crowded(jobs)!!
        assertEquals(6, crowd.jobIds.size)
        assertEquals("21:00", crowd.at)
    }

    @Test fun differentSchedulesMeetOnTheDaysTheyShare() {
        // Three daily at 09:00 and a weekly Monday one at 09:10: four on Mondays.
        val jobs = listOf(job("a", "x", "09:00"), job("b", "x", "09:00"), job("c", "x", "09:00"), job("m", "x", "09:10", 10080, listOf(1)))
        assertEquals(listOf("a", "b", "c", "m"), Timing.crowded(jobs)!!.jobIds)
        // Two daily, one on Mondays and one on Tuesdays: never four on one day.
        assertNull(Timing.crowded(listOf(job("a", "x", "09:00"), job("b", "x", "09:00"), job("m", "x", "09:00", 10080, listOf(1)), job("t", "x", "09:00", 10080, listOf(2)))))
    }

    @Test fun onlyServicesThatTurnBurstsAwayCount() {
        val jobs = (1..5).map { job("j$it", "navidrome", "09:00", dst = "plex") }
        assertNull(Timing.crowded(jobs, sensitive = { it == "qobuz_web" }))
        assertEquals(5, Timing.crowded(jobs, sensitive = { it == "plex" })!!.jobIds.size)
    }

    @Test fun spreadingGivesEachSyncItsOwnQuarterHourAndLeavesNoCrowd() {
        val jobs = (1..11).map { job("q$it", "qobuz_web", "09:00") }
        val times = Timing.spread(jobs, Timing.crowded(jobs)!!)
        // The first keeps 09:00, so it is not a change.
        assertEquals(10, times.size)
        assertEquals("09:15", times["q2"]); assertEquals("11:30", times["q11"])
        assertNull(Timing.crowded(jobs.map { j -> times[j.id]?.let { j.copy(at = it) } ?: j }))
    }

    @Test fun spreadingMovesTwoWayHalvesTogetherAndAvoidsOtherSyncs() {
        val a = job("a", "x", "09:00"); val b = job("b", "x", "09:00")
        val pair1 = job("p1", "x", "09:00").copy(linkedJobId = "p2"); val pair2 = job("p2", "y", "09:00", dst = "x").copy(linkedJobId = "p1")
        // A weekly sync of the same service already at 09:15 and 09:30 on Mondays.
        val fixed = listOf(job("w1", "x", "09:15", 10080, listOf(1)), job("w2", "x", "09:30", 10080, listOf(1)))
        val jobs = listOf(a, b, pair1, pair2) + fixed
        val crowd = Timing.crowded(jobs)!!
        val times = Timing.spread(jobs, crowd)
        val after = jobs.map { j -> times[j.id]?.let { j.copy(at = it) } ?: j }
        assertEquals(after.first { it.id == "p1" }.at, after.first { it.id == "p2" }.at)
        assertNull(Timing.crowded(after))
    }

    @Test fun lateCrowdsAreSpreadWithinTheSameDay() {
        val jobs = (1..6).map { job("n$it", "x", "23:30") }
        val times = Timing.spread(jobs, Timing.crowded(jobs)!!)
        assertTrue(times.values.all { it < "24:00" && it >= "22:00" })
        assertEquals(6, (jobs.map { times[it.id] ?: it.at }).toSet().size)
    }

    @Test fun everyKindOfScheduleCountsWhenItStartsAtTheSameMoment() {
        val zone = java.time.ZoneOffset.UTC
        val now = java.time.LocalDateTime.of(2026, 11, 9, 7, 0).toInstant(zone).toEpochMilli()
        val sixAm = now - 3_600_000L
        // Two daily at 09:00 and two every 3 hours that last ran at 06:00: all four start at 09:00.
        val every3h = (1..2).map { job("h$it", "x", null, 180).copy(lastRunEpoch = sixAm) }
        val jobs = listOf(job("a", "x", "09:00"), job("b", "x", "09:00")) + every3h
        val crowd = Timing.crowded(jobs, now = now, zone = zone)!!
        assertEquals(setOf("a", "b", "h1", "h2"), crowd.jobIds.toSet())
        assertEquals("09:00", crowd.at)
        // Spreading moves the timed ones only: the ones every few hours keep their rhythm.
        val times = Timing.spread(jobs, crowd, now = now, zone = zone)
        assertTrue(times.keys.none { it.startsWith("h") })
        assertNull(Timing.crowded(jobs.map { j -> times[j.id]?.let { j.copy(at = it) } ?: j }, now = now, zone = zone))
    }

    @Test fun overdueSyncsOpenedAfterDaysOffAreNoCrowd() {
        val zone = java.time.ZoneOffset.UTC
        val now = java.time.LocalDateTime.of(2026, 11, 9, 12, 37).toInstant(zone).toEpochMilli()
        // Four daily syncs through the day, the phone off for two days: all four are overdue and run
        // once now, but their clock times are hours apart.
        val jobs = listOf("06:00", "09:00", "14:00", "20:00").mapIndexed { i, at ->
            val last = java.time.LocalDateTime.of(2026, 11, 7, at.take(2).toInt(), 0).toInstant(zone).toEpochMilli()
            job("d$i", "x", at).copy(lastRunEpoch = last)
        }
        assertNull(Timing.crowded(jobs, now = now, zone = zone))
        // Syncs that do share a clock time are still a crowd, named by that time and not by now.
        val same = (0..3).map { job("s$it", "x", "09:00").copy(lastRunEpoch = now - 2 * 86_400_000L) }
        assertEquals("09:00", Timing.crowded(same, now = now, zone = zone)!!.at)
    }
}

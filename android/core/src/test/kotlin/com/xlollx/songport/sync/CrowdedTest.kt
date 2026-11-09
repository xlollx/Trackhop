package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

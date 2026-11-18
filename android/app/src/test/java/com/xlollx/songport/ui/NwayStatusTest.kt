package com.xlollx.songport.ui

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a multi sync's card says: the most serious thing its services left, by the order every card uses, and which service. */
class NwayStatusTest {
    private val hub = PlaylistRef(SharedProvider.ID, "g1", "Mix")
    private fun report(id: String, held: Int = 0, notFound: Int = 0, interrupted: Boolean = false, error: String? = null, net: String? = null) = SyncReport(
        id = id, jobId = id, jobName = id, startedEpoch = 0, durationMs = 0,
        unmatchedTracks = (1..notFound).map { Track(id = "n$it", title = "Song $it") },
        heldBack = held, searchInterrupted = interrupted, error = error, netError = net,
    )
    private fun down(service: String, r: SyncReport? = report("d-$service"), enabled: Boolean = true, up: String? = null) =
        SyncJob("d-$service", service, hub, PlaylistRef(service, "p", "Mix"), enabled = enabled, lastReportId = r?.id, linkedJobId = up, lastRunEpoch = if (r == null) 0 else 5)
    private fun up(service: String, r: SyncReport? = report("u-$service")) =
        SyncJob("u-$service", service, PlaylistRef(service, "p", "Mix"), hub, lastReportId = r?.id, linkedJobId = "d-$service", lastRunEpoch = if (r == null) 0 else 5)

    private fun group(vararg jobs: Pair<SyncJob, SyncReport?>): NwayStatus.Group {
        val d = StoreData(jobs = jobs.map { it.first }, reports = jobs.mapNotNull { it.second })
        return NwayStatus.group(d, Nway.members(d.jobs, "g1"))
    }
    private fun ok(service: String) = report("d-$service").let { down(service, it) to it }
    private fun with(service: String, r: SyncReport) = down(service, r) to r

    @Test fun allFineIsUpToDateAndNamesNoService() {
        val g = group(ok("spotify"), ok("tidal"))
        assertEquals(CardStatus.OK, g.status)
        assertNull(g.provider)
    }

    @Test fun aFailureComesFirstAndNamesItsService() {
        val g = group(ok("spotify"), with("tidal", report("d-tidal", held = 3)), with("qobuz", report("d-qobuz", error = "Signed out")), with("apple", report("d-apple", notFound = 12)))
        assertEquals(CardStatus.ERROR, g.status)
        assertEquals("qobuz", g.provider)
        assertEquals(listOf("qobuz"), NwayStatus.failing(g).map { it.provider })
    }

    @Test fun noInternetIsNotAFailure() {
        val offline = report("d-tidal", error = "No internet", net = "OFFLINE")
        val g = group(ok("spotify"), with("tidal", offline))
        assertEquals(CardStatus.OFFLINE, g.status)
        assertEquals("tidal", g.provider)
        assertEquals(emptyList<String>(), NwayStatus.failing(g).map { it.provider })
        // Something that waits for the user says more than a run that starts again by itself.
        assertEquals(CardStatus.NOT_FOUND, group(with("spotify", report("d-spotify", notFound = 2)), with("tidal", offline)).status)
    }

    @Test fun removalsOnHoldCountAndComeBeforeNotFound() {
        val g = group(ok("spotify"), with("tidal", report("d-tidal", held = 3, notFound = 1)), with("apple", report("d-apple", notFound = 12)))
        assertEquals(CardStatus.HELD_BACK, g.status)
        assertEquals("tidal", g.provider)
        assertEquals(3, g.count)
    }

    /** Held in the sync that sends a two-way member's changes: it counts as well. */
    @Test fun removalsHeldOnTheWayUpCount() {
        val u = report("u-tidal", held = 7)
        val d = report("d-tidal")
        val g = group(ok("spotify"), down("tidal", d, up = "u-tidal") to d, up("tidal", u) to u)
        assertEquals(CardStatus.HELD_BACK, g.status)
        assertEquals(7, g.count)
        assertEquals("u-tidal", g.about.single().job.id)
    }

    @Test fun notFoundAddsUpOverTheServices() {
        val g = group(ok("spotify"), with("tidal", report("d-tidal", notFound = 5)), with("apple", report("d-apple", notFound = 7)))
        assertEquals(CardStatus.NOT_FOUND, g.status)
        assertEquals(12, g.count)
        assertNull("two services: none is named", g.provider)
    }

    @Test fun anUnfinishedSearchComesBeforeNotFound() {
        assertEquals(CardStatus.INCOMPLETE, group(with("tidal", report("d-tidal", interrupted = true, notFound = 4)), with("apple", report("d-apple", notFound = 7))).status)
    }

    @Test fun pausedAndNeverRunComeFirstAsOnACard() {
        val failed = report("d-tidal", error = "HTTP 500")
        assertEquals(CardStatus.DISABLED, group(down("spotify", enabled = false) to report("d-spotify"), down("tidal", failed, enabled = false) to failed).status)
        assertEquals(CardStatus.NEVER, group(down("spotify", null) to null, down("tidal", null) to null).status)
        // A target added since the last run has not run yet: the others still speak.
        assertEquals(CardStatus.OK, group(ok("spotify"), down("tidal", null) to null).status)
    }
}

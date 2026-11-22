package com.xlollx.songport.shared

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedProvider
import com.xlollx.songport.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A multi sync at work: which service is being synced, which are done, wait or failed, and how far the whole run is. */
class NwayRunTest {
    private val hub = PlaylistRef(SharedProvider.ID, "g1", "Mix")
    private fun down(s: String, up: Boolean = false) = SyncJob("d-$s", s, hub, PlaylistRef(s, "p", "Mix"), linkedJobId = if (up) "u-$s" else null, twoWayRemovals = up)
    private fun up(s: String) = SyncJob("u-$s", s, PlaylistRef(s, "p", "Mix"), hub, linkedJobId = "d-$s")
    // Spotify is the source (two-way with the group's list); TIDAL and Qobuz are targets.
    private val jobs = listOf(down("spotify", up = true), up("spotify"), down("tidal"), down("qobuz"))
    private val members = Nway.members(jobs, "g1")
    private val order = listOf("u-spotify", "d-spotify", "d-tidal", "d-qobuz")
    private fun phases(s: NwayRun.State) = s.services.associate { it.provider to it.phase }

    @Test fun nothingRunningIsNoState() {
        assertNull(NwayRun.of(members, emptyMap(), null))
        assertNull(NwayRun.of(members, mapOf("other" to null), null))
    }

    @Test fun theBatchSaysWhoIsDoneWhoIsNowAndWhoWaits() {
        val batch = SyncState.Batch("b", "g1", order, listOf("u-spotify", "d-spotify"), mapOf("u-spotify" to SyncState.JobResult(added = 2), "d-spotify" to SyncState.JobResult(removed = 1)))
        val s = NwayRun.of(members, mapOf("d-tidal" to Progress(Progress.Step.MATCHING, 50, 100)), batch)!!
        assertEquals(mapOf("spotify" to NwayRun.Phase.DONE, "tidal" to NwayRun.Phase.NOW, "qobuz" to NwayRun.Phase.WAITING), phases(s))
        assertEquals("tidal", s.now!!.provider)
        assertEquals(2 to 3, s.place to s.total)
        // Two of four syncs over and half of the third.
        assertEquals(0.625f, s.fraction!!, 0.001f)
        val done = s.services.first { it.provider == "spotify" }.result!!
        // Its own playlist only (the down sync's −1): the +2 its up sync wrote went to the group's list.
        assertEquals(0 to 1, done.added to done.removed)
    }

    /** The source sends its changes first, then waits for its turn to receive: it is not done until both are. */
    @Test fun aServiceWithTwoSyncsIsDoneWhenBothAre() {
        val batch = SyncState.Batch("b", "g1", order, listOf("u-spotify"), mapOf("u-spotify" to SyncState.JobResult()))
        val s = NwayRun.of(members, mapOf("d-spotify" to null), batch)!!
        assertEquals(NwayRun.Phase.NOW, phases(s)["spotify"])
        assertEquals(1 to 3, s.place to s.total)
        assertEquals(NwayRun.Phase.WAITING, phases(NwayRun.of(members, emptyMap(), batch)!!)["spotify"])
    }

    @Test fun aFailureIsSaidAndTheRunGoesOn() {
        val batch = SyncState.Batch(
            "b", "g1", order, listOf("u-spotify", "d-spotify", "d-tidal"),
            mapOf("u-spotify" to SyncState.JobResult(), "d-spotify" to SyncState.JobResult(), "d-tidal" to SyncState.JobResult(ok = false, error = "Signed out")),
        )
        val s = NwayRun.of(members, mapOf("d-qobuz" to null), batch)!!
        assertEquals(mapOf("spotify" to NwayRun.Phase.DONE, "tidal" to NwayRun.Phase.FAILED, "qobuz" to NwayRun.Phase.NOW), phases(s))
        assertEquals("Signed out", s.services.first { it.provider == "tidal" }.result!!.error)
        assertEquals(3 to 3, s.place to s.total)
    }

    /** Stopped for lack of internet: not a failure, the service is over for this run. */
    @Test fun noInternetIsNotAFailure() {
        val batch = SyncState.Batch("b", "g1", order, order.take(3), order.take(3).associateWith { SyncState.JobResult() } + ("d-tidal" to SyncState.JobResult(ok = false, offline = true, error = "No internet")))
        assertEquals(NwayRun.Phase.DONE, phases(NwayRun.of(members, mapOf("d-qobuz" to null), batch)!!)["tidal"])
    }

    /** One service run alone, by its own schedule: it is the one at work, the others just stand by, and there is no "n of m". */
    @Test fun aServiceRunningAloneHasNoPlace() {
        val s = NwayRun.of(members, mapOf("d-qobuz" to Progress(Progress.Step.ADDING, 1, 4)), null)!!
        assertEquals(mapOf("spotify" to NwayRun.Phase.IDLE, "tidal" to NwayRun.Phase.IDLE, "qobuz" to NwayRun.Phase.NOW), phases(s))
        assertEquals(0, s.place)
        assertEquals(0.25f, s.fraction!!, 0.001f)
    }

    @Test fun theSourceComesFirst() {
        assertEquals(listOf("tidal", "spotify", "qobuz"), NwayRun.sourceFirst(members, "d-tidal").map { it.playlist.provider })
        assertEquals(listOf("spotify", "tidal", "qobuz"), NwayRun.sourceFirst(members, null).map { it.playlist.provider })
    }
}

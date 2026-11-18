package com.xlollx.songport.ui

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import com.xlollx.songport.sync.SyncFilter
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.SyncSummary
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

    /**
     * The owner's "Metal & More" (2.1.1): Amazon Music's last run left one match to check and nothing
     * not found. The header said "1 to fix" while the card and the page said "Up to date". The group
     * says what a classic sync's card says for the same run, names the service, and is counted once.
     */
    @Test fun aMatchToCheckIsWhatTheCardSaysAndTheHeaderCountsItOnce() {
        val toCheck = report("d-amazon").copy(reviewTracks = listOf(MatchReview(Track(id = "s1", title = "Song"), Track(id = "a1", title = "Song (Live)"), 0.72)))
        val src = report("d-spotify")
        val srcUp = report("u-spotify")
        val jobs = listOf(down("spotify", src, up = "u-spotify"), up("spotify", srcUp), down("amazon", toCheck))
        val d = StoreData(
            jobs = jobs, reports = listOf(src, srcUp, toCheck),
            groups = listOf(SharedGroup("g1", "Metal & More", "k", "k", "", transport = Invite.LOCAL).copy(jobId = "d-spotify", twinId = "u-spotify")),
        )
        val g = NwayStatus.group(d, Nway.members(d.jobs, "g1"))
        // The same state as a classic sync's card for the same run, with its count and the service it is about.
        assertEquals(cardStatus(isRunning = false, enabled = true, report = toCheck), g.status)
        assertEquals(CardStatus.TO_CHECK, g.status)
        assertEquals(1, g.count)
        assertEquals("amazon", g.provider)
        assertEquals("d-amazon", g.about.single().report?.id)
        // Its row on the page says the same.
        assertEquals(CardStatus.TO_CHECK, g.members.first { it.provider == "amazon" }.status)

        // The header, as the Syncs list counts it: the shown syncs (the group's card only), each card standing for its syncs.
        val hidden = NwayUi.hiddenJobIds(d)
        val shown = d.syncs.filter { it.id !in hidden }
        assertEquals(listOf("d-spotify"), shown.map { it.id })
        val summary = SyncSummary.of(shown, d.reports, SyncState.Tally(0, 0), emptySet(), standsFor = NwayUi.standsFor(d))
        assertEquals(SyncSummary.Kind.ATTENTION, summary.kind)
        assertEquals(0, summary.first)
        assertEquals("one card to fix, counted once", 1, summary.second)
        // Its tap shows that card, which says the same.
        assertEquals(listOf("d-spotify"), SyncFilter(statuses = summary.filter).select(shown, d.reports, emptySet(), standsFor = NwayUi.standsFor(d)).map { it.id })
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

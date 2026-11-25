package com.xlollx.songport.shared

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A multi sync's card figures and run history (2.1.2): its services' reports, run by run, added up. */
class NwayHistoryTest {
    private val hub = PlaylistRef(SharedProvider.ID, "g1", "Mix")
    private val jobs = listOf(
        SyncJob("u-spotify", "spotify", PlaylistRef("spotify", "p", "Mix"), hub, linkedJobId = "d-spotify"),
        SyncJob("d-spotify", "spotify", hub, PlaylistRef("spotify", "p", "Mix"), linkedJobId = "u-spotify"),
        SyncJob("d-tidal", "tidal", hub, PlaylistRef("tidal", "t", "Mix")),
        SyncJob("d-deezer", "deezer", hub, PlaylistRef("deezer", "z", "Mix")),
        SyncJob("other", "other", PlaylistRef("spotify", "o", "O"), PlaylistRef("deezer", "o", "O")),
    )
    private val members = Nway.members(jobs, "g1")
    private fun r(id: String, job: String, at: Long, added: Int = 0, removed: Int = 0, notFound: Int = 0, source: Int = 0, error: String? = null, partial: Boolean = false) =
        SyncReport(id, job, job, at, 1_000, sourceCount = source, added = added, removed = removed, unmatched = (1..notFound).map { "$job $it" }, error = error, partial = partial)

    private val min = 60_000L

    @Test fun theSyncsOfOneRunAreOneRunAndTheirFiguresAddUp() {
        val reports = listOf(
            // An earlier run of the whole group, one that failed on Deezer.
            r("a1", "u-spotify", 0, added = 1, source = 40), r("a2", "d-tidal", 2_000, added = 1), r("a3", "d-deezer", 4_000, error = "Signed out"),
            // A sync of another card, in between: not the group's.
            r("x", "other", 30 * min, added = 9),
            // The last run: each service once, close together.
            r("b1", "u-spotify", 60 * min, added = 2, source = 42), r("b2", "d-spotify", 60 * min + 2_000),
            r("b3", "d-tidal", 60 * min + 4_000, added = 2, removed = 1, notFound = 3), r("b4", "d-deezer", 60 * min + 6_000, added = 2, notFound = 1),
            // Still running: not counted yet.
            r("c1", "u-spotify", 61 * min, added = 50, partial = true),
        )
        val batches = NwayHistory.batches(reports, members)
        assertEquals(listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2", "b3", "b4")), batches.map { b -> b.map { it.id } })
        val runs = NwayHistory.runs(reports, members)
        assertFalse("a run fails when one of its services fails", runs[0].ok)
        val last = runs.last()
        assertTrue(last.ok)
        // The services' playlists only (TIDAL 2 + Deezer 2): Spotify's 2 went into the group's own list, which the added list does not show either.
        assertEquals(4, last.added)
        assertEquals(1, last.removed)
        assertEquals(4, last.unmatched.size)
        assertEquals(42, last.sourceCount)
    }

    @Test fun aSyncSeenTwiceOrALongPauseStartsTheNextRun() {
        val reports = listOf(
            r("a", "d-tidal", 0), r("b", "d-tidal", 5_000),
            r("c", "d-deezer", 5_000 + 30 * min),
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), NwayHistory.batches(reports, members).map { b -> b.map { it.id } })
        // At most ten, the newest.
        val many = (0 until 14).map { r("m$it", "d-tidal", it * 60 * min) }
        assertEquals((4 until 14).map { "group:m$it" }, NwayHistory.runs(many, members).map { it.id })
    }

    /** The search ("Search N syncs") and the filter's line ("1 of N syncs") count the same syncs: a multi sync once. */
    @Test fun theListCountsAMultiSyncOnce() {
        val group = com.xlollx.songport.shared.SharedGroup("g1", "Mix", "k", "k", "", transport = com.xlollx.songport.shared.Invite.LOCAL, jobId = "d-spotify", twinId = "u-spotify")
        val d = StoreData(jobs = jobs, groups = listOf(group))
        assertEquals(listOf("d-spotify", "other"), Nway.listedSyncs(d).map { it.id })
        assertEquals(jobs.size, Nway.listedSyncs(d.copy(groups = emptyList())).size)
    }

    /** 2.1.4: a run's songs left for the next run (a service's limit) stay in the group's run. */
    @Test fun songsNotAddedAddUpInTheGroupsRun() {
        val run = NwayHistory.sum(listOf(
            r("b1", "u-spotify", 0, added = 2),
            r("b4", "d-deezer", 2_000, added = 321).copy(notAdded = (1..1776).map { "Song $it" }),
        ))
        assertEquals(1776, run.notAdded.size)
        assertEquals(323, run.added)
        assertEquals(0, run.unmatched.size)
    }
}

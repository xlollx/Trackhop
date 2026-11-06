package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.SyncFilter.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFilterTest {
    private fun job(
        id: String, name: String, src: String = "spotify", dst: String = "ytm",
        srcList: String = "", dstList: String = "", enabled: Boolean = true, color: String? = null, report: String? = null,
    ) = SyncJob(
        id = id, name = name,
        source = PlaylistRef(src, "p-$id", srcList), target = PlaylistRef(dst, null, dstList),
        enabled = enabled, color = color, lastReportId = report,
    )

    private fun report(id: String, jobId: String, error: String? = null) =
        SyncReport(id = id, jobId = jobId, jobName = jobId, startedEpoch = 0, durationMs = 0, error = error)

    private val names = mapOf("spotify" to "Spotify", "ytm" to "YouTube Music", "tidal" to "TIDAL", "plex" to "Plex · Home")

    private val jobs = listOf(
        job("a", "Morning run", srcList = "Café Classics", dstList = "Café", color = "teal", report = "r1"),
        job("b", "Workout", src = "tidal", dst = "spotify", srcList = "Gym", color = "red", report = "r2"),
        job("c", "Old stuff", enabled = false, srcList = "Oldies", report = "r3"),
        job("d", "Discover", src = "ytm", dst = "plex", srcList = "Discover Weekly", color = "teal"),
        job("e", "Road trip", src = "plex", dst = "tidal", srcList = "Drive"),
    )
    private val reports = listOf(report("r1", "a"), report("r2", "b", error = "token expired"), report("r3", "c"), report("old", "a", error = "x"))

    private fun ids(f: SyncFilter, running: Set<String> = emptySet()) =
        f.select(jobs, reports, running) { names[it] ?: it }.joinToString("") { it.id }

    @Test fun emptyFilterKeepsEverythingInOrder() {
        val f = SyncFilter()
        assertFalse(f.isActive)
        assertSame(jobs, f.select(jobs, reports, emptySet()))
        assertFalse(SyncFilter(query = "   ").isActive)
    }

    @Test fun searchMatchesNamesPlaylistsAndServicesWithoutCaseOrAccents() {
        assertEquals("a", ids(SyncFilter(query = "morning")))
        assertEquals("a", ids(SyncFilter(query = "CAFE")))
        assertEquals("d", ids(SyncFilter(query = "weekly")))
        // The service's name on screen, the account name included.
        assertEquals("acd", ids(SyncFilter(query = "youtube")))
        assertEquals("de", ids(SyncFilter(query = "home")))
        // Every word must match, in any field.
        assertEquals("b", ids(SyncFilter(query = "  tidal   gym ")))
        assertEquals("", ids(SyncFilter(query = "tidal oldies")))
    }

    @Test fun statusesWidenEachOther() {
        assertEquals("b", ids(SyncFilter(statuses = setOf(Status.ERROR))))
        assertEquals("c", ids(SyncFilter(statuses = setOf(Status.DISABLED))))
        assertEquals("de", ids(SyncFilter(statuses = setOf(Status.RUNNING)), running = setOf("d", "e", "gone")))
        assertEquals("bcd", ids(SyncFilter(statuses = setOf(Status.ERROR, Status.DISABLED, Status.RUNNING)), running = setOf("d")))
    }

    @Test fun anOldFailureDoesNotCountOnlyTheLastRun() {
        // Report "old" failed, but sync a's last run (r1) went fine.
        assertFalse("a" in ids(SyncFilter(statuses = setOf(Status.ERROR))))
    }

    @Test fun servicesMatchSourceOrTarget() {
        assertEquals("be", ids(SyncFilter(services = setOf("tidal"))))
        assertEquals("bde", ids(SyncFilter(services = setOf("tidal", "plex"))))
    }

    /** A colour of one's own (#RRGGBB) is a colour like the others: listed in use, and filtered on. */
    @Test fun ownColoursAreFilteredToo() {
        val mine = jobs + job("f", "Mine", color = "#FF8800")
        assertEquals(setOf("teal", "red", "#FF8800"), SyncFilter.colorsInUse(mine))
        assertEquals("f", SyncFilter(colors = setOf("#FF8800")).select(mine, reports, emptySet()) { names[it] ?: it }.joinToString("") { it.id })
    }

    @Test fun colorsKeepOnlyThoseColours() {
        assertEquals("ad", ids(SyncFilter(colors = setOf("teal"))))
        assertEquals("abd", ids(SyncFilter(colors = setOf("teal", "red"))))
    }

    @Test fun groupsNarrowEachOther() {
        assertEquals("d", ids(SyncFilter(colors = setOf("teal"), services = setOf("plex"))))
        assertEquals("", ids(SyncFilter(colors = setOf("teal"), statuses = setOf(Status.ERROR))))
        assertEquals("a", ids(SyncFilter(query = "cafe", colors = setOf("teal"), services = setOf("spotify"))))
    }

    @Test fun togglesAddAndRemove() {
        val f = SyncFilter().toggle(Status.ERROR).toggleService("plex").toggleColor("red")
        assertEquals(setOf(Status.ERROR), f.statuses)
        assertEquals(setOf("plex"), f.services)
        assertEquals(setOf("red"), f.colors)
        assertTrue(f.isActive)
        assertFalse(f.toggle(Status.ERROR).toggleService("plex").toggleColor("red").isActive)
    }

    @Test fun whatIsInUse() {
        assertEquals(listOf("spotify", "ytm", "tidal", "plex"), SyncFilter.servicesInUse(jobs))
        assertEquals(setOf("teal", "red"), SyncFilter.colorsInUse(jobs))
    }

    @Test fun choicesNoSyncUsesAnyMoreAreDropped() {
        val f = SyncFilter(services = setOf("plex", "deezer"), colors = setOf("pink", "red"), statuses = setOf(Status.ERROR))
        val p = f.pruned(jobs)
        assertEquals(setOf("plex"), p.services)
        assertEquals(setOf("red"), p.colors)
        assertEquals(setOf(Status.ERROR), p.statuses)
        val kept = SyncFilter(services = setOf("plex"))
        assertSame(kept, kept.pruned(jobs))
    }

    @Test fun termsFoldCaseAccentsAndSpaces() {
        assertEquals(listOf("cafe", "mix"), SyncFilter.terms("  Café\tMIX "))
        assertTrue(SyncFilter.terms("   ").isEmpty())
    }

    @Test fun statusesByTheLastRunWithCounts() {
        val js = listOf(
            job("ok", "Fine", report = "rok"),
            job("nf", "Some missing", report = "rnf"),
            job("hb", "Held back", report = "rhb"),
            job("er", "Broken", report = "rer"),
            job("nv", "New one"),
            job("off", "Off", enabled = false, report = "rok2"),
        )
        val rs = listOf(
            report("rok", "ok"), report("rok2", "off"), report("rer", "er", error = "boom"),
            report("rnf", "nf").copy(unmatched = listOf("x")),
            report("rhb", "hb").copy(heldBack = 3),
        )
        fun sel(vararg s: Status) = SyncFilter(statuses = s.toSet()).select(js, rs, setOf("nv")).joinToString(",") { it.id }
        assertEquals("ok,off", sel(Status.UP_TO_DATE))
        assertEquals("nf,hb", sel(Status.NEEDS_FIX))
        assertEquals("er", sel(Status.ERROR))
        assertEquals("nv", sel(Status.NEVER_RUN))
        assertEquals("nv", sel(Status.RUNNING))
        assertEquals("off", sel(Status.DISABLED))
        val counts = SyncFilter.counts(js, rs, setOf("nv"))
        assertEquals(2, counts[Status.UP_TO_DATE]); assertEquals(2, counts[Status.NEEDS_FIX]); assertEquals(1, counts[Status.ERROR])
        assertEquals(1, counts[Status.NEVER_RUN]); assertEquals(1, counts[Status.RUNNING]); assertEquals(1, counts[Status.DISABLED])
    }
}

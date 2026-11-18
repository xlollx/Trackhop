package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A multi sync in the Syncs list is one card, on the sync of its source: selected, filtered and
 * counted as one, and what is changed on it (on or off, the schedule, a deletion) reaches every
 * sync of the group.
 */
class MultiSyncBulkTest {
    private val hub = PlaylistRef(SharedProvider.ID, "g1", "Mix")
    private fun down(s: String, up: Boolean = false) = SyncJob("d-$s", "Mix · $s", hub, PlaylistRef(s, "p", "Mix"), linkedJobId = if (up) "u-$s" else null, twoWayRemovals = up)
    private fun up(s: String) = SyncJob("u-$s", "Mix · $s ↔", PlaylistRef(s, "p", "Mix"), hub, linkedJobId = "d-$s", twin = true)
    private val plain = SyncJob("plain", "Liked", PlaylistRef("spotify", "l"), PlaylistRef("deezer", "l"))
    // A shared playlist with other people: its syncs are changed from its group, never here.
    private val friends = SyncJob("f", "Friends", PlaylistRef(SharedProvider.ID, "g2", "Friends"), PlaylistRef("spotify", "fp"), linkedJobId = "f2")
    private val group = SharedGroup("g1", "Mix", "k", "k", "", jobId = "d-spotify", twinId = "u-spotify", transport = Invite.LOCAL)
    private val shared = SharedGroup("g2", "Friends", "k", "k", "", jobId = "f", twinId = "f2", transport = Invite.FOLDER)
    private val d = StoreData(
        jobs = listOf(plain, down("spotify", up = true), up("spotify"), down("tidal"), down("qobuz"), friends),
        groups = listOf(group, shared),
    )
    private val all = setOf("d-spotify", "u-spotify", "d-tidal", "d-qobuz")
    private val standsFor: (SyncJob) -> List<SyncJob> = { j -> if (j.id == "d-spotify") Nway.jobsOf(d, "g1") else listOf(j) }

    @Test fun theCardIsSelectedAndReachesEverySyncOfItsGroup() {
        assertTrue(BulkEdit.selectable(d, d.jobs[1]))
        assertFalse("another sync of the group is not a card", BulkEdit.selectable(d, d.jobs[3]))
        assertFalse("a shared playlist's sync is changed from its group", BulkEdit.selectable(d, friends))
        assertEquals(all, BulkEdit.reach(d, setOf("d-spotify")))
        assertEquals(setOf("g1"), BulkEdit.groups(d, setOf("d-spotify", "plain")))
        assertEquals(setOf("plain"), BulkEdit.reach(d, setOf("plain", "d-tidal", "f")))
    }

    @Test fun onOrOffAndTheScheduleReachEveryService() {
        val off = BulkEdit.enabled(d, setOf("d-spotify"), false)
        assertEquals(all, off.jobs.filter { !it.enabled }.mapTo(HashSet()) { it.id })
        val daily = BulkEdit.schedule(d, setOf("d-spotify"), BulkEdit.ScheduleChange(every = 1440, at = "07:30", wifiOnly = true), weekday = 3)
        val timed = daily.jobs.filter { it.id in all }
        assertEquals(1, timed.map { Schedules.specOf(it) }.distinct().size)
        assertTrue(timed.all { it.wifiOnly && Schedules.specOf(it).at == "07:30" })
        assertEquals("the others stay", plain, daily.jobs.first { it.id == "plain" })
        assertEquals(all, BulkEdit.rescheduled(d, daily).toSet())
    }

    @Test fun theColourIsTheCardsOwn() {
        val teal = BulkEdit.color(d, setOf("d-spotify", "plain"), "teal")
        assertEquals(setOf("d-spotify", "plain"), teal.jobs.filter { it.color != null }.mapTo(HashSet()) { it.id })
    }

    @Test fun deletedItGoesWholeWithItsGroupAsOneChange() {
        val store = FileStore(Files.createTempDirectory("multi-bulk").toFile())
        store.update { d }
        assertTrue(store.deleteJobs(BulkEdit.reach(store.data, setOf("d-spotify")), BulkEdit.groups(store.data, setOf("d-spotify"))))
        assertEquals(listOf("plain", "f"), store.data.jobs.map { it.id })
        assertEquals(listOf("g2"), store.data.groups.map { it.id })
    }

    @Test fun theFiltersAndTheCountsReadTheCardAsAllItsServices() {
        val failed = SyncReport("r", "d-qobuz", "Mix · qobuz", 0, 0, error = "Signed out")
        val fine = SyncReport("ok", "d-spotify", "Mix · spotify", 0, 0)
        val data = d.copy(jobs = d.jobs.map { when (it.id) { "d-qobuz" -> it.copy(lastReportId = "r"); "d-spotify" -> it.copy(lastReportId = "ok"); else -> it } }, reports = listOf(failed, fine))
        val cards = data.jobs.filter { it.id == "plain" || it.id == "d-spotify" }
        val of: (SyncJob) -> List<SyncJob> = { j -> if (j.id == "d-spotify") Nway.jobsOf(data, "g1") else listOf(j) }
        fun ids(f: SyncFilter, running: Set<String> = emptySet()) = f.select(cards, data.reports, running, standsFor = of).map { it.id }
        // One of its services failed: the card is among the errors, not among the up to date, though its own sync ran fine.
        assertEquals(listOf("d-spotify"), ids(SyncFilter(statuses = setOf(SyncFilter.Status.ERROR))))
        assertEquals(emptyList<String>(), ids(SyncFilter(statuses = setOf(SyncFilter.Status.UP_TO_DATE))))
        assertEquals(1, SyncFilter.counts(cards, data.reports, emptySet(), of)[SyncFilter.Status.ERROR])
        // Running while any of its services is.
        assertEquals(listOf("d-spotify"), ids(SyncFilter(statuses = setOf(SyncFilter.Status.RUNNING)), setOf("d-tidal")))
        // Found by a service only another of its syncs names, in the chips and in the search.
        assertEquals(listOf("d-spotify"), ids(SyncFilter(services = setOf("tidal"))))
        assertEquals(listOf("d-spotify"), ids(SyncFilter(query = "qobuz")))
        assertTrue("tidal" in SyncFilter.servicesInUse(cards, of))
        assertEquals("its services' chips are not dropped as unused", setOf("tidal"), SyncFilter(services = setOf("tidal")).pruned(cards, of).services)
        // Without it a card is one sync, as ever.
        assertEquals(emptyList<String>(), SyncFilter(services = setOf("tidal")).select(cards, data.reports, emptySet()).map { it.id })
    }
}

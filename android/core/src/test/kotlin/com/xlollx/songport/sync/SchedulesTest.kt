package com.xlollx.songport.sync

import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.EncryptedBackup
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

/** Saved schedules (Settings › Schedules): linked to their syncs, as the preview approved on 17/11 says. */
class SchedulesTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun job(id: String, every: Int = 0, at: String? = null, days: List<Int> = emptyList(), scheduleId: String? = null, lastRun: Long = 0) =
        Timing.apply(SyncJob(id = id, name = id, source = PlaylistRef("spotify", "s$id"), target = PlaylistRef("ytm", "t$id"), lastRunEpoch = lastRun), every, at, days).copy(scheduleId = scheduleId)

    private val morning = SavedSchedule("m", "Morning refresh", 1440, "07:30")
    private val six = SavedSchedule("six", "", 360)
    private fun data(vararg jobs: SyncJob, schedules: List<SavedSchedule> = listOf(morning, six)) =
        StoreData(jobs = jobs.toList(), settings = Settings(savedSchedules = schedules))

    // ------------------------------------------------------------ what a schedule is

    @Test fun manualOnlyIsNotASchedule() {
        assertNull(Schedules.make("Never", 0, null, emptyList()))
        assertNotNull(Schedules.make("", 60, null, emptyList()))
        val d = data()
        assertSame(d, Schedules.save(d, SavedSchedule("x", "Never", 0)))
        // One that slipped into a file is dropped, and its sync keeps what it has.
        val bad = Schedules.normalized(null, data(job("a", 0, scheduleId = "x"), schedules = listOf(SavedSchedule("x", "Never", 0))))
        assertTrue(bad.settings.savedSchedules.isEmpty())
        assertNull(bad.jobs[0].scheduleId)
    }

    @Test fun aScheduleHoldsEverythingTheEditorCanSet() {
        val weekend = Schedules.make("Weekend", 1440, "10:00", listOf(7, 6))!!
        assertEquals(listOf(6, 7), weekend.days)
        assertEquals("10:00", weekend.at)
        // Every day of the week is daily; hours have no time of day; the name is trimmed.
        assertEquals(Schedules.Spec(1440, "08:00", emptyList()), Schedules.specOf(Schedules.make("", 60, "08:00", (1..7).toList())!!))
        assertNull(Schedules.make("  Often ", 180, "08:00", emptyList())!!.at)
        assertEquals("Often", Schedules.make("  Often ", 180, null, emptyList())!!.name)
    }

    @Test fun aScheduleWithNoNameIsListedByItsTiming() {
        assertEquals("Morning refresh", Schedules.title(morning))
        assertEquals("every 6 h", Schedules.title(six))
        assertEquals("Sat, Sun at 10:00", Schedules.title(SavedSchedule("w", "", 1440, "10:00", listOf(6, 7))))
    }

    // ------------------------------------------------------------ linked

    @Test fun aLinkedSyncTakesTheSchedulesTiming() {
        val j = Schedules.link(job("a", 60), morning)
        assertEquals("m", j.scheduleId)
        assertEquals(1440, j.everyMinutes); assertEquals("07:30", j.at); assertEquals(Schedule.DAILY, j.schedule)
        assertTrue(Schedules.matches(j, morning))
    }

    @Test fun editingAScheduleChangesEverySyncThatUsesIt() {
        val d = data(job("a", 1440, "07:30", scheduleId = "m"), job("b", 1440, "07:30", scheduleId = "m"), job("c", 1440, "07:30"), job("d", 360, scheduleId = "six"))
        val next = Schedules.save(d, morning.copy(everyMinutes = 1440, at = "06:15", days = listOf(1, 3, 5)))
        // The two linked syncs follow, and stay linked.
        for (id in listOf("a", "b")) next.jobs.first { it.id == id }.let {
            assertEquals("06:15", it.at); assertEquals(listOf(1, 3, 5), it.days); assertEquals("m", it.scheduleId)
        }
        // The one set by hand to the same time, and the one on another schedule, are not touched.
        assertSame(d.jobs[2], next.jobs[2])
        assertSame(d.jobs[3], next.jobs[3])
        assertEquals(2, Schedules.usedBy(next, "m").size)
    }

    @Test fun renamingAScheduleTouchesNoSync() {
        val d = data(job("a", 1440, "07:30", scheduleId = "m"))
        val next = Schedules.save(d, morning.copy(name = "Early"))
        assertEquals("Early", Schedules.find(next, "m")!!.name)
        assertSame(d.jobs, next.jobs)
    }

    @Test fun theStoreKeepsLinkedSyncsInLineWhoeverWrites() {
        val store = FileStore(Files.createTempDirectory("sched").toFile())
        store.update { data(job("a", 60), job("b", 1440, "07:30", scheduleId = "m")) }
        // Linked by setting the id alone: the timing comes with it.
        store.upsertJob(store.job("a")!!.copy(scheduleId = "six"))
        assertEquals(360, store.job("a")!!.everyMinutes)
        // The schedule changed through the settings: its syncs follow in the same change.
        store.updateSettings { s -> s.copy(savedSchedules = s.savedSchedules.map { if (it.id == "six") it.copy(everyMinutes = 120) else it }) }
        assertEquals(120, store.job("a")!!.everyMinutes)
        assertEquals("six", store.job("a")!!.scheduleId)
        // Read again from disk: as it was left.
        store.flush()
    }

    // ------------------------------------------------------------ detach

    @Test fun detachGivesTheSyncItsOwnCopy() {
        val d = data(job("a", 1440, "07:30", scheduleId = "m"), job("b", 1440, "07:30", scheduleId = "m"))
        val detached = d.copy(jobs = d.jobs.map { if (it.id == "a") Schedules.detach(it) else it })
        // Same timing, set by hand; no longer following.
        detached.jobs[0].let { assertNull(it.scheduleId); assertEquals("07:30", it.at); assertEquals(1440, it.everyMinutes) }
        val next = Schedules.save(detached, morning.copy(at = "05:00"))
        assertEquals("07:30", next.jobs[0].at)
        assertEquals("05:00", next.jobs[1].at)
        assertEquals(listOf("b"), Schedules.usedBy(next, "m").map { it.id })
    }

    @Test fun aLinkedSyncGivenATimingOfItsOwnIsDetached() {
        // The editor's "Set by hand", the spreading of a crowded hour, the server's page: whoever
        // changes a linked sync's timing leaves it with that timing, as its own.
        val store = FileStore(Files.createTempDirectory("sched").toFile())
        store.update { data(job("a", 1440, "07:30", scheduleId = "m"), job("b", 1440, "07:30", scheduleId = "m")) }
        store.upsertJob(store.job("a")!!.copy(at = "07:45"))
        assertNull(store.job("a")!!.scheduleId)
        assertEquals("07:45", store.job("a")!!.at)
        assertEquals("m", store.job("b")!!.scheduleId)
        // What is not timing leaves the link alone.
        store.upsertJob(store.job("b")!!.copy(wifiOnly = true, name = "B"))
        assertEquals("m", store.job("b")!!.scheduleId)
    }

    @Test fun aRadioSyncIsNeverLinked() {
        val radio = job("r", 0).copy(radio = RadioSource("Radio", "http://r"), scheduleId = "m")
        assertNull(Schedules.normalized(null, data(radio)).jobs[0].scheduleId)
    }

    // ------------------------------------------------------------ delete

    @Test fun deletingAScheduleLeavesTheSyncsTheirTiming() {
        val d = data(job("a", 1440, "07:30", scheduleId = "m"), job("b", 360, scheduleId = "six"))
        val next = Schedules.delete(d, "m")
        assertEquals(listOf("six"), next.settings.savedSchedules.map { it.id })
        // Still daily at 07:30, as its own schedule: never "manual only".
        next.jobs[0].let {
            assertNull(it.scheduleId); assertEquals(1440, it.everyMinutes); assertEquals("07:30", it.at)
            assertTrue(Timing.scheduled(it))
            assertEquals(Timing.nextRun(d.jobs[0], at(18, 6), zone), Timing.nextRun(it, at(18, 6), zone))
        }
        assertEquals("six", next.jobs[1].scheduleId)
    }

    @Test fun aLinkToAScheduleThatIsGoneIsDropped() {
        val d = Schedules.normalized(null, data(job("a", 1440, "09:00", scheduleId = "gone")))
        assertNull(d.jobs[0].scheduleId)
        assertEquals("09:00", d.jobs[0].at)
    }

    // ------------------------------------------------------------ intervals: each sync from its own last run

    @Test fun anIntervalScheduleCountsFromEachSyncsOwnLastRun() {
        val a = Schedules.link(job("a", lastRun = at(18, 8)), six)
        val b = Schedules.link(job("b", lastRun = at(18, 11, 30)), six)
        val never = Schedules.link(job("c"), six)
        val now = at(18, 12)
        assertEquals(at(18, 14), Timing.nextRun(a, now, zone))
        assertEquals(at(18, 17, 30), Timing.nextRun(b, now, zone))
        // Each within six hours of now ("Within 6 hours"), never at one shared time.
        for (j in listOf(a, b)) assertTrue(Timing.nextRun(j, now, zone)!! - now <= 6 * 3_600_000L)
        // One that never ran is due at once.
        assertEquals(now, Timing.nextRun(never, now, zone))
        // The schedule itself has no clock time to show.
        assertNull(Schedules.nextRun(six, now, zone))
        assertNull(Schedules.nextRun(SavedSchedule("d", "", 2880, "09:00"), now, zone))
    }

    @Test fun aTimedScheduleHasANextClockTime() {
        // Wednesday 18/11/2026.
        assertEquals(at(18, 7, 30), Schedules.nextRun(morning, at(18, 6), zone))
        assertEquals(at(19, 7, 30), Schedules.nextRun(morning, at(18, 7, 30), zone))
        val weekend = SavedSchedule("w", "Weekend", 1440, "10:00", listOf(6, 7))
        assertEquals(at(21, 10), Schedules.nextRun(weekend, at(18, 12), zone))
        assertEquals(at(22, 10), Schedules.nextRun(weekend, at(21, 10), zone))
    }

    // ------------------------------------------------------------ used by, pairs

    @Test fun aTwoWayPairCountsOnce() {
        val a = job("a", 1440, "07:30", scheduleId = "m").copy(linkedJobId = "a2")
        val a2 = job("a2", 1440, "07:30", scheduleId = "m").copy(linkedJobId = "a", twin = true)
        val d = data(a, a2, job("b", 1440, "07:30", scheduleId = "m"))
        assertEquals(listOf("a", "b"), Schedules.usedBy(d, "m").map { it.id })
        // Both halves follow the schedule.
        val next = Schedules.save(d, morning.copy(at = "08:00"))
        assertEquals(listOf("08:00", "08:00", "08:00"), next.jobs.map { it.at })
    }

    // ------------------------------------------------------------ used by, cards (2.2)

    /** A multi sync "Rap" on [services] (the first main, a two-way pair with the hub; the others follow), each sync linked to "m". */
    private fun rap(vararg services: String): Pair<SharedGroup, List<SyncJob>> {
        val hub = PlaylistRef(SharedProvider.ID, "g-rap", "Rap")
        val jobs = services.flatMapIndexed { i, s ->
            val down = job("d-$s", 1440, "07:30", scheduleId = "m").copy(name = "Rap · $s", source = hub, target = PlaylistRef(s, "p-$s", "Rap"), linkedJobId = if (i == 0) "u-$s" else null)
            if (i == 0) listOf(down, job("u-$s", 1440, "07:30", scheduleId = "m").copy(name = "Rap · $s ↔", source = PlaylistRef(s, "p-$s", "Rap"), target = hub, linkedJobId = "d-$s", twin = true))
            else listOf(down)
        }
        return SharedGroup("g-rap", "Rap", "k", "k", "", jobId = "d-${services[0]}", twinId = "u-${services[0]}", transport = Invite.LOCAL) to jobs
    }

    @Test fun aMultiSyncIsOneCardWithItsServices() {
        val (g, jobs) = rap("spotify", "ytm", "tidal", "qobuz", "deezer", "applemusic")
        val d = data(*jobs.toTypedArray()).copy(groups = listOf(g))
        val cards = Schedules.cards(d, "m")
        assertEquals(1, cards.size)
        val c = cards.single()
        assertEquals("Rap", c.name)
        assertSame(g, c.group)
        assertEquals("d-spotify", c.job.id)
        assertEquals(6, c.members.size)
        assertTrue("every sync of the group is behind the card", jobs.all { c.has(it.id) })
        // The member syncs are still what saving reaches.
        assertEquals(6, Schedules.usedBy(d, "m").size)
        val next = Schedules.save(d, morning.copy(at = "08:00"))
        assertTrue(next.jobs.all { it.at == "08:00" })
    }

    @Test fun classicSyncsAndAMultiSyncAreCountedByCard() {
        val (g, jobs) = rap("spotify", "ytm", "tidal")
        val a = job("a", 1440, "07:30", scheduleId = "m").copy(linkedJobId = "a2")
        val a2 = job("a2", 1440, "07:30", scheduleId = "m").copy(linkedJobId = "a", twin = true)
        val d = data(a, a2, *jobs.toTypedArray(), job("b", 1440, "07:30", scheduleId = "m"), job("c", 360, scheduleId = "six")).copy(groups = listOf(g))
        assertEquals(listOf("a", "Rap", "b"), Schedules.cards(d, "m").map { it.name })
        assertEquals(listOf(null, g, null), Schedules.cards(d, "m").map { it.group })
        assertEquals(listOf("c"), Schedules.cards(d, "six").map { it.name })
        assertTrue(Schedules.cards(d, "none").isEmpty())
        // Only some services of the group linked: still the one card.
        val some = d.copy(jobs = d.jobs.map { if (it.id == "d-tidal") it.copy(scheduleId = null) else it })
        assertEquals(3, Schedules.cards(some, "m").size)
    }

    @Test fun aSharedPlaylistWithOthersIsNotAMultiSync() {
        val friends = job("f", 1440, "07:30", scheduleId = "m").copy(name = "", source = PlaylistRef(SharedProvider.ID, "g2", "Friends"))
        val d = data(friends, job("b", 1440, "07:30", scheduleId = "m")).copy(groups = listOf(SharedGroup("g2", "Friends", "k", "k", "", jobId = "f", transport = Invite.FOLDER)))
        assertEquals(listOf("Friends", "b"), Schedules.cards(d, "m").map { it.name })
        assertTrue(Schedules.cards(d, "m").all { it.group == null })
    }

    // ------------------------------------------------------------ the files

    @Test fun savedSchedulesTravelInTheConfigurationFileAndTheEncryptedBackup() {
        val env = com.xlollx.songport.contract.ContractEnv()
        env.store.update { data(job("a", 1440, "07:30", scheduleId = "m"), job("b", 60)) }
        val file = ConfigBackup.export(env, "test")
        val other = com.xlollx.songport.contract.ContractEnv()
        // The other device has a schedule of its own, and its own idea of "m".
        other.store.update { data(job("z", 1440, "05:00", scheduleId = "m"), job("y", 120, scheduleId = "mine"), schedules = listOf(morning.copy(at = "05:00"), SavedSchedule("mine", "Mine", 120))) }
        ConfigBackup.import(other, ConfigBackup.read(file), replacesOther = true)
        val d = other.store.data
        // The file's schedules win by id, this device's others stay.
        assertEquals(setOf("m", "six", "mine"), d.settings.savedSchedules.map { it.id }.toSet())
        assertEquals("07:30", Schedules.find(d, "m")!!.at)
        assertEquals("m", d.jobs.first { it.id == "a" }.scheduleId)
        // The sync that was here on "m" follows the schedule the file brought.
        d.jobs.first { it.id == "z" }.let { assertEquals("m", it.scheduleId); assertEquals("07:30", it.at) }
        assertEquals("mine", d.jobs.first { it.id == "y" }.scheduleId)
        // The encrypted full backup carries the same configuration.
        val sealed = EncryptedBackup.seal(file, "a long passphrase".toCharArray())
        val opened = ConfigBackup.read(EncryptedBackup.open(sealed, "a long passphrase".toCharArray()))
        assertEquals(listOf("m", "six"), opened.settings.savedSchedules.map { it.id })
        assertEquals("m", opened.jobs.first { it.id == "a" }.scheduleId)
    }

    @Test fun aStoreFileOfBeforeSchedulesStillReads() {
        val text = """{"jobs":[{"id":"a","name":"A","source":{"provider":"spotify","playlistId":"1"},"target":{"provider":"ytm"},"schedule":"DAILY"}],"settings":{}}"""
        val d = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), text)
        assertTrue(d.settings.savedSchedules.isEmpty())
        assertNull(d.jobs[0].scheduleId)
        assertSame(d, Schedules.normalized(null, d))
        assertFalse(Schedules.matches(d.jobs[0], morning))
    }
}

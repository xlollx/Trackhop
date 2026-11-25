package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Label
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Several syncs changed together (#68): what was not touched stays as each sync has it. */
class BulkEditTest {
    private fun job(id: String, every: Int = 0, at: String? = null, days: List<Int> = emptyList(), mirror: Boolean = false, enabled: Boolean = true, wifi: Boolean = false) =
        Timing.apply(SyncJob(id = id, name = id, source = PlaylistRef("spotify", "s$id"), target = PlaylistRef("ytm", "t$id"), mirrorRemovals = mirror, enabled = enabled, wifiOnly = wifi), every, at, days)

    private val morning = SavedSchedule("m", "Morning refresh", 1440, "07:30")
    // The preview's selection: Night Drive (daily 07:30), Sunday Slow Cooking (weekly, Sunday 09:00), Kitchen Radio (daily 18:00).
    private val night = job("night", 1440, "07:30", mirror = true, wifi = true)
    private val sunday = job("sunday", 10080, "09:00", listOf(7))
    private val kitchen = job("kitchen", 1440, "18:00", enabled = false)
    private val garage = job("garage", 0, enabled = false)
    private val d = StoreData(jobs = listOf(night, sunday, garage, kitchen), settings = Settings(savedSchedules = listOf(morning)))
    private val three = setOf("night", "sunday", "kitchen")
    private fun StoreData.j(id: String) = jobs.first { it.id == id }

    // ------------------------------------------------------------ "Different values"

    @Test fun whatTheSelectedSyncsShareAndWhereTheyDiffer() {
        val sel = BulkEdit.selected(d, three)
        assertEquals(listOf("night", "sunday", "kitchen"), sel.map { it.id })
        assertNull(BulkEdit.common(sel) { it.at.orEmpty() })
        assertNull(BulkEdit.common(sel) { it.mirrorRemovals })
        assertEquals(1440, BulkEdit.common(sel.filter { it.id != "sunday" }) { Timing.interval(it) })
        // "Was: Daily in two, Weekly in one."
        assertEquals(listOf(1440 to 2, 10080 to 1), BulkEdit.tally(sel) { Timing.interval(it) })
    }

    // ------------------------------------------------------------ schedule

    @Test fun oneFieldChangedTheOthersLeftAlone() {
        // How often: every 6 hours. Time of day and Wi-Fi not touched.
        val next = BulkEdit.schedule(d, three, BulkEdit.ScheduleChange(every = 360), weekday = 3)
        for (id in three) assertEquals(360, next.j(id).everyMinutes)
        assertEquals(listOf(true, false, false), three.map { next.j(it).wifiOnly })
        assertSame(d.j("garage"), next.j("garage"))
        // Daily for all: each sync keeps its own time.
        val daily = BulkEdit.schedule(d, three, BulkEdit.ScheduleChange(every = 1440), weekday = 3)
        assertEquals(listOf("07:30", "09:00", "18:00"), three.map { daily.j(it).at })
        assertTrue(daily.j("sunday").days.isEmpty())
        // The two that were daily already are the very same syncs.
        assertSame(d.j("night"), daily.j("night"))
    }

    @Test fun aTimeForAllKeepsEachSyncsFrequencyAndDays() {
        val next = BulkEdit.schedule(d, three + "garage", BulkEdit.ScheduleChange(at = "06:00"), weekday = 3)
        assertEquals(listOf("06:00", "06:00", "06:00"), three.map { next.j(it).at })
        assertEquals(listOf(7), next.j("sunday").days)
        assertEquals(1440, next.j("kitchen").everyMinutes)
        // A manual sync has no time of day: it stays as it is.
        assertSame(d.j("garage"), next.j("garage"))
    }

    @Test fun weeklyKeepsADayOfItsOwnAndGivesOneToTheOthers() {
        val next = BulkEdit.schedule(d, three, BulkEdit.ScheduleChange(every = 10080), weekday = 3)
        assertEquals(listOf(7), next.j("sunday").days)
        assertEquals(listOf(3), next.j("night").days)
        assertEquals("18:00", next.j("kitchen").at)
    }

    @Test fun wifiAloneLeavesEveryTimingAndLink() {
        val linked = d.copy(jobs = d.jobs.map { if (it.id == "night") it.copy(scheduleId = "m") else it })
        val next = BulkEdit.schedule(linked, three, BulkEdit.ScheduleChange(wifiOnly = false), weekday = 3)
        assertEquals(listOf(false, false, false), three.map { next.j(it).wifiOnly })
        assertEquals("m", next.j("night").scheduleId)
        assertEquals(listOf("07:30", "09:00", "18:00"), three.map { next.j(it).at })
        // Nothing touched: nothing changed.
        assertSame(d, BulkEdit.schedule(d, three, BulkEdit.ScheduleChange(), weekday = 3))
    }

    @Test fun aSavedScheduleLinksEverySelectedSync() {
        val next = BulkEdit.schedule(d, three + "garage", BulkEdit.ScheduleChange(scheduleId = "m"), weekday = 3)
        for (id in three + "garage") next.j(id).let { assertEquals("m", it.scheduleId); assertEquals("07:30", it.at); assertEquals(1440, it.everyMinutes) }
        assertEquals(4, Schedules.usedBy(next, "m").size)
        // On or off is not the schedule's: each keeps its own.
        assertFalse(next.j("garage").enabled)
    }

    @Test fun aFieldChangedAfterPickingASavedScheduleGivesTheSyncsTheirOwnTiming() {
        val next = BulkEdit.schedule(d, three, BulkEdit.ScheduleChange(scheduleId = "m", at = "08:15"), weekday = 3)
        for (id in three) next.j(id).let { assertNull(it.scheduleId); assertEquals("08:15", it.at); assertEquals(1440, it.everyMinutes); assertTrue(it.days.isEmpty()) }
    }

    @Test fun aLinkedSyncChangedByHandIsDetached() {
        val linked = d.copy(jobs = d.jobs.map { if (it.id == "night") it.copy(scheduleId = "m") else it })
        val next = BulkEdit.schedule(linked, three, BulkEdit.ScheduleChange(every = 60), weekday = 3)
        assertNull(next.j("night").scheduleId)
        assertEquals(60, next.j("night").everyMinutes)
    }

    @Test fun aRadioSyncKeepsItsWindowAndTakesTheWifi() {
        val radio = job("radio").copy(radio = RadioSource("R", "http://r"))
        val with = d.copy(jobs = d.jobs + radio)
        val next = BulkEdit.schedule(with, setOf("radio", "night"), BulkEdit.ScheduleChange(scheduleId = "m", wifiOnly = true), weekday = 3)
        next.j("radio").let { assertNull(it.scheduleId); assertEquals(0, it.everyMinutes); assertTrue(it.wifiOnly) }
        assertEquals("m", next.j("night").scheduleId)
    }

    @Test fun theOtherHalfOfAPairFollows() {
        val a = job("a", 60).copy(linkedJobId = "a2")
        val a2 = job("a2", 60).copy(linkedJobId = "a", twin = true)
        val pair = d.copy(jobs = d.jobs + a + a2)
        val next = BulkEdit.schedule(pair, setOf("a"), BulkEdit.ScheduleChange(scheduleId = "m", wifiOnly = true), weekday = 3)
        next.j("a2").let { assertEquals("m", it.scheduleId); assertEquals("07:30", it.at); assertTrue(it.wifiOnly) }
        val off = BulkEdit.enabled(next, setOf("a2"), false)
        assertEquals(listOf(false, false), listOf(off.j("a").enabled, off.j("a2").enabled))
        assertEquals(setOf("a", "a2", "night"), BulkEdit.reach(pair, setOf("a", "night")))
    }

    // ------------------------------------------------------------ removals, on/off, colour

    @Test fun removalsForAllButTheSyncsThatNeverTakeThem() {
        val pair = job("p").copy(linkedJobId = "p2")
        val archive = job("arch").copy(archive = true)
        val follows = job("f", mirror = true).copy(followsChanges = true)
        val all = d.copy(jobs = d.jobs + pair + archive + follows)
        val ids = three + setOf("p", "arch", "f")
        val mirror = BulkEdit.removals(all, ids, true)
        assertEquals(listOf(true, true, true), three.map { mirror.j(it).mirrorRemovals })
        assertFalse(mirror.j("p").mirrorRemovals); assertFalse(mirror.j("arch").mirrorRemovals)
        assertSame(all.j("night"), mirror.j("night"))
        val add = BulkEdit.removals(all, ids, false)
        assertEquals(listOf(false, false, false), three.map { add.j(it).mirrorRemovals })
        // "Changes by itself" only means something with Mirror.
        add.j("f").let { assertFalse(it.mirrorRemovals); assertFalse(it.followsChanges) }
        assertEquals(listOf(false, false, true), listOf(pair, archive, follows).map { BulkEdit.takesRemovals(it) })
    }

    @Test fun onOrOffForAll() {
        val on = BulkEdit.enabled(d.copy(jobs = d.jobs.map { if (it.id == "kitchen") it.copy(scheduledOn = "Pixel") else it }), three, true)
        assertEquals(listOf(true, true, true), three.map { on.j(it).enabled })
        // Turned on here: this device schedules it from now on.
        assertNull(on.j("kitchen").scheduledOn)
        assertFalse(on.j("garage").enabled)
        val off = BulkEdit.enabled(d, three, false)
        assertEquals(listOf(false, false, false), three.map { off.j(it).enabled })
        assertSame(d.j("kitchen"), off.j("kitchen"))
    }

    @Test fun aColourOrALabelForAll() {
        val named = d.copy(settings = d.settings.copy(labels = listOf(Label("teal", "teal", "Gym"), Label("l-1", "#FF8800", "Road"))))
        val teal = BulkEdit.color(named, three, "teal")
        assertEquals(listOf("teal", "teal", "teal"), three.map { teal.j(it).label })
        // The names of the labels are what they were.
        assertEquals(setOf("Gym", "Road"), teal.settings.labels.map { it.name }.toSet())
        val road = BulkEdit.color(teal, three, "l-1")
        assertEquals(listOf("#FF8800", "#FF8800", "#FF8800"), three.map { road.j(it).color })
        assertEquals(setOf("Gym", "Road"), road.settings.labels.map { it.name }.toSet())
        val none = BulkEdit.color(road, three, null)
        assertEquals(listOf(null, null, null), three.map { none.j(it).color })
        assertNull(none.j("garage").color)
    }

    @Test fun sharedAndHandedOverSyncsAreNotSelected() {
        val shared = job("sh").copy(target = PlaylistRef(com.xlollx.songport.shared.SharedProvider.ID, "g1"))
        val all = d.copy(jobs = d.jobs + shared, handedOver = mapOf("garage" to HandedOver("srv", 1L)))
        assertEquals(listOf("night"), BulkEdit.selected(all, setOf("night", "sh", "garage")).map { it.id })
        assertSame(all, BulkEdit.enabled(all, setOf("sh", "garage"), true))
    }

    // ------------------------------------------------------------ all or none

    @Test fun aBulkChangeReachesAllTheSyncsOrNone() {
        val dir = Files.createTempDirectory("bulk").toFile()
        val store = FileStore(dir)
        store.update { d }
        store.flush()
        // The disk fails: nothing is changed, in memory either, and the caller is told.
        store.failWrites = true
        assertFalse(store.commit { BulkEdit.schedule(it, three, BulkEdit.ScheduleChange(scheduleId = "m"), weekday = 3) })
        assertEquals(d.jobs, store.data.jobs)
        assertFalse(store.deleteJobs(three))
        assertEquals(4, store.data.jobs.size)
        assertFalse(store.commit { Schedules.save(it, morning.copy(at = "05:00")) })
        assertEquals("07:30", Schedules.find(store.data, "m")!!.at)
        // The disk is back: the change is on it before commit answers, whole.
        store.failWrites = false
        assertTrue(store.commit { BulkEdit.schedule(it, three, BulkEdit.ScheduleChange(scheduleId = "m"), weekday = 3) })
        val onDisk = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), java.io.File(dir, "store.json").readText())
        assertEquals(listOf("m", "m", null, "m"), onDisk.jobs.map { it.scheduleId })
        // A schedule edited for all its syncs, and several syncs deleted, the same way.
        assertTrue(store.commit { Schedules.save(it, morning.copy(at = "05:00")) })
        assertEquals(listOf("05:00", "05:00", "05:00"), three.map { store.job(it)!!.at })
        assertTrue(store.deleteJobs(setOf("night", "sunday")))
        assertEquals(listOf("garage", "kitchen"), FileStore(dir).data.jobs.map { it.id })
        // Nothing to change is no failure.
        assertTrue(store.commit { it })
    }

    @Test fun whichSyncsNeedTheirWorkBroughtInLine() {
        val next = BulkEdit.schedule(d, three, BulkEdit.ScheduleChange(every = 1440, wifiOnly = true), weekday = 3)
        // Night Drive was daily on Wi-Fi already; the other two changed.
        assertEquals(listOf("sunday", "kitchen"), BulkEdit.rescheduled(d, next))
        assertEquals(listOf("night"), BulkEdit.rescheduled(d, BulkEdit.enabled(d, setOf("night"), false)))
    }
    @Test fun syncsWritingToADeletedPlaylistAreTurnedOffAndNothingElse() {
        // Night Drive writes to ytm tnight; its two-way twin reads it. Sunday reads tnight only; Garage writes to it but is off.
        val twin = SyncJob(id = "back", name = "back", source = PlaylistRef("ytm", "tnight"), target = PlaylistRef("spotify", "snight"), linkedJobId = "night")
        val reader = SyncJob(id = "reader", name = "reader", source = PlaylistRef("ytm", "tnight"), target = PlaylistRef("spotify", "x"))
        val otherService = SyncJob(id = "deezer", name = "deezer", source = PlaylistRef("spotify", "a"), target = PlaylistRef("deezer", "tnight"))
        val shared = SyncJob(id = "hub", name = "hub", source = PlaylistRef(com.xlollx.songport.shared.SharedProvider.ID, "g1"), target = PlaylistRef("ytm", "tnight"))
        val handed = SyncJob(id = "server", name = "server", source = PlaylistRef("spotify", "b"), target = PlaylistRef("ytm", "tnight"))
        val once = SyncJob(id = "once", name = "once", source = PlaylistRef("spotify", "c"), target = PlaylistRef("ytm", "tnight"), oneOff = true)
        val start = d.copy(
            jobs = listOf(night.copy(linkedJobId = "back"), twin, reader, otherService, shared, handed, once, garage.copy(target = PlaylistRef("ytm", "tnight")), kitchen),
            handedOver = mapOf("server" to HandedOver("srv", 1)),
        )
        assertEquals(listOf("night", "garage"), BulkEdit.writingTo(start, "ytm", setOf("tnight")).map { it.id })
        val next = BulkEdit.offForDeleted(start, "ytm", setOf("tnight", "nothing"))
        assertEquals(setOf("night", "back", "garage", "kitchen"), next.jobs.filter { !it.enabled }.map { it.id }.toSet())
        // The rest is as it was: the reader, the same id on another service, a shared playlist's, the server's, a one-off.
        listOf("reader", "deezer", "hub", "server", "once").forEach { assertTrue(it, next.j(it).enabled) }
        assertEquals(start.jobs.map { it.copy(enabled = true) }, next.jobs.map { it.copy(enabled = true) })
        // No sync writes there: the data itself, nothing to save.
        assertSame(start, BulkEdit.offForDeleted(start, "ytm", setOf("nothing")))
        assertSame(start, BulkEdit.offForDeleted(start, "ytm", emptySet()))
    }
}

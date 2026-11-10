package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The same sync on two devices (a phone and a server, say): each would add what is missing, twice.
 * Three guards: a configuration file written elsewhere brings its scheduled syncs turned off; a
 * target read long before the additions is looked at again first; and a track the target ends up
 * holding twice, the source once, loses its extra copy on the next run, where the service can take
 * out one entry.
 */
class TwoDevicesTest {
    /** A target another device writes to while this run searches, with entry ids and one-entry removals when [entries]. */
    private class Target(id: String, private val entries: Boolean = true) : MemoryService(id) {
        var onSearch: (() -> Unit)? = null
        var reads = 0
        override val removesSingleEntry: Boolean get() = entries
        override suspend fun search(ctx: Env, track: Track): List<Track> { onSearch?.invoke(); onSearch = null; return super.search(ctx, track) }
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> { reads++; return super.tracks(ctx, playlistId) }
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            if (!entries) return super.removeTracks(ctx, playlistId, tracks)
            val items = tracks.mapNotNull { it.itemId }.toSet()
            lists[playlistId]?.removeAll { it.itemId in items }
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: Target
    private lateinit var engine: SyncEngine
    private val reread = rereadAfterMs

    private fun t(id: String, title: String, item: String? = null) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000, itemId = item)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = Target("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList(); rereadAfterMs = reread }

    private fun job(mirror: Boolean = false, archive: Boolean = false) =
        SyncJob(id = "j", name = "test", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"), mirrorRemovals = mirror, archive = archive)
            .also { env.store.upsertJob(it) }

    // ------------------------------------------------------------ 1. a configuration from another device

    private fun exported(): String {
        val phone = ContractEnv()
        fun ref(id: String) = PlaylistRef("msrc", id, id)
        phone.store.upsertJob(SyncJob("daily", "Daily", ref("a"), ref("b"), schedule = Schedule.DAILY))
        phone.store.upsertJob(SyncJob("days", "On Mondays", ref("a"), ref("c"), days = listOf(1), at = "08:00"))
        phone.store.upsertJob(SyncJob("manual", "By hand", ref("a"), ref("d")))
        phone.store.upsertJob(SyncJob("off", "Off already", ref("a"), ref("e"), schedule = Schedule.HOURLY, enabled = false))
        phone.store.upsertJob(SyncJob("radio", "Radio", ref("r"), ref("f"), radio = RadioSource("Station", "http://example.org/stream")))
        return ConfigBackup.export(phone, "Trackhop 1.17 (Android)", deviceName = "Pixel 8")
    }

    @Test fun scheduledSyncsFromAnotherDeviceArriveTurnedOff() {
        val s = ConfigBackup.import(env, ConfigBackup.read(exported()))
        assertEquals(3, s.paused)
        assertEquals("Pixel 8", s.from)
        for (id in listOf("daily", "days", "radio")) env.store.job(id)!!.let {
            assertFalse(id, it.enabled)
            assertEquals(id, "Pixel 8", it.scheduledOn)
        }
        // A sync run by hand runs on no device by itself; one already off says nothing new.
        env.store.job("manual")!!.let { assertTrue(it.enabled); assertNull(it.scheduledOn) }
        env.store.job("off")!!.let { assertFalse(it.enabled); assertNull(it.scheduledOn) }
        // Turned on here, by whatever screen: this device schedules it from now on.
        env.store.upsertJob(env.store.job("daily")!!.copy(enabled = true))
        assertNull(env.store.job("daily")!!.scheduledOn)
        // The same file again: what is on here stays on, the rest stays off.
        assertEquals(2, ConfigBackup.import(env, ConfigBackup.read(exported())).paused)
        assertTrue(env.store.job("daily")!!.enabled)
    }

    @Test fun aFileThisDeviceWroteComesBackAsItIs() {
        env.store.upsertJob(SyncJob("daily", "Daily", PlaylistRef("msrc", "a", "a"), PlaylistRef("msrc", "b", "b"), schedule = Schedule.DAILY))
        val file = ConfigBackup.export(env, "Trackhop server")
        env.store.deleteJob("daily")
        val s = ConfigBackup.import(env, ConfigBackup.read(file))
        assertEquals(0, s.paused)
        assertTrue(env.store.job("daily")!!.enabled)
        assertFalse("the id stays out of the portable settings", file.contains("\"deviceId\": \"" + env.store.data.settings.deviceId))
    }

    @Test fun anOlderFileWithoutADeviceCountsAsAnotherDevices() {
        val old = exported().replace(Regex("\"device\": \"[^\"]*\""), "\"device\": \"\"").replace("\"deviceName\": \"Pixel 8\"", "\"deviceName\": \"\"")
        val s = ConfigBackup.import(env, ConfigBackup.read(old))
        assertEquals(3, s.paused)
        assertEquals("Trackhop 1.17 (Android)", env.store.job("daily")!!.scheduledOn)
    }

    // ------------------------------------------------------------ 2. the target read again before adding

    private fun anotherDeviceAddsWhileSearching() {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = mutableListOf()
        // The other device finishes first: Alpha is on the target by the time this run adds.
        dst.onSearch = { dst.lists["t"]!! += t("da", "Alpha", "other-1") }
    }

    @Test fun anOldReadIsCheckedAgainBeforeAdding() = runBlocking {
        anotherDeviceAddsWhileSearching()
        rereadAfterMs = -1
        val r = engine.run(job())
        assertEquals(1, r.added)
        assertEquals(listOf("da", "db"), dst.lists["t"]!!.map { it.id })
        assertEquals("read once for the plan, once before adding", 2, dst.reads)
    }

    @Test fun aRecentReadIsNotCheckedAgain() = runBlocking {
        anotherDeviceAddsWhileSearching()
        val r = engine.run(job())
        assertEquals(2, r.added)
        assertEquals(1, dst.reads)
    }

    @Test fun anUnchangedVersionSparesTheSecondRead() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf()
        dst.versions["t"] = "v1"
        rereadAfterMs = -1
        assertEquals(1, engine.run(job()).added)
        assertEquals(1, dst.reads)
    }

    @Test fun aChangedVersionReadsTheTargetAgain() = runBlocking {
        anotherDeviceAddsWhileSearching()
        dst.versions["t"] = "v1"
        val add = dst.onSearch!!
        dst.onSearch = { add(); dst.versions["t"] = "v2" }
        rereadAfterMs = -1
        assertEquals(1, engine.run(job()).added)
        assertEquals(2, dst.reads)
        assertEquals(1, dst.lists["t"]!!.count { it.id == "da" })
    }

    @Test fun nothingToAddNothingReadAgain() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf(t("da", "Alpha", "i1"))
        rereadAfterMs = -1
        assertEquals(0, engine.run(job()).added)
        assertEquals(1, dst.reads)
    }

    // ------------------------------------------------------------ 3. a duplicate repaired

    private fun twice() {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"), t("c", "Gamma"), t("d", "Delta"), t("e", "Epsilon"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"), t("dc", "Gamma"), t("dd", "Delta"), t("de", "Epsilon"))
        dst.lists["t"] = mutableListOf(t("da", "Alpha", "i1"), t("db", "Beta", "i2"), t("dc", "Gamma", "i3"), t("dd", "Delta", "i4"), t("de", "Epsilon", "i5"), t("da", "Alpha", "i6"))
    }

    @Test fun aTrackTwiceOnTheTargetAndOnceInTheSourceLosesItsExtraCopy() = runBlocking {
        twice()
        val r = engine.run(job())
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5"), dst.lists["t"]!!.map { it.itemId })
        assertTrue(r.notes.toString(), "note_duplicates_repaired(1)" in r.notes)
        assertEquals("not a mirror removal", 0, r.removed)
        assertFalse(engine.run(job()).notes.any { it.startsWith("note_duplicates_repaired") })
    }

    @Test fun twiceInTheSourceTooItStaysTwice() = runBlocking {
        twice()
        src.lists["p"]!! += t("a", "Alpha")
        engine.run(job(mirror = true))
        assertEquals(6, dst.lists["t"]!!.size)
    }

    @Test fun aRunThatRemovesOtherTracksLeavesTheDuplicateForTheNextOne() = runBlocking {
        twice()
        dst.lists["t"]!! += t("dx", "Not in the source", "i7")
        val r = engine.run(job(mirror = true))
        assertEquals(1, r.removed)
        assertEquals(6, dst.lists["t"]!!.size)
        engine.run(job(mirror = true))
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5"), dst.lists["t"]!!.map { it.itemId })
    }

    @Test fun aServiceThatRemovesEveryCopyIsLeftAlone() = runBlocking {
        dst = Target("mdst", entries = false)
        Providers.testDoubles = listOf(src, dst)
        twice()
        val r = engine.run(job(mirror = true))
        assertEquals(6, dst.lists["t"]!!.size)
        assertFalse(r.notes.any { it.startsWith("note_duplicates_repaired") })
    }

    @Test fun copiesWithTheSameEntryIdOrNoneAreLeftAlone() = runBlocking {
        twice()
        // A page the service sent twice: the same entry, not two.
        dst.lists["t"]!![5] = t("da", "Alpha", "i1")
        dst.lists["t"]!! += t("db", "Beta", null)
        engine.run(job())
        assertEquals(7, dst.lists["t"]!!.size)
    }

    @Test fun anArchiveAndThePreviewRemoveNoDuplicate() = runBlocking {
        twice()
        engine.run(job(archive = true))
        assertEquals(6, dst.lists["t"]!!.size)
        assertTrue(engine.plan(job(), createTarget = false, interactive = true).duplicates.isEmpty())
    }

    @Test fun aTargetThatIsMostlyDuplicatesIsLeftAlone() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = mutableListOf(t("da", "Alpha", "i1"), t("db", "Beta", "i2"), t("da", "Alpha", "i3"), t("db", "Beta", "i4"))
        engine.run(job())
        assertEquals(4, dst.lists["t"]!!.size)
    }
}

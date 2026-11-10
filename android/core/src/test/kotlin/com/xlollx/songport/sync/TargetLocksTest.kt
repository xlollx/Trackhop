package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One device at a time writes to a playlist: reservations made through the server's API by another
 * device (a phone), with an expiry, against this process's own runs; and the engine asking the
 * server it coordinates with.
 */
class TargetLocksTest {
    private class Target(id: String) : MemoryService(id) {
        var onSearch: (() -> Unit)? = null
        override suspend fun search(ctx: Env, track: Track): List<Track> { onSearch?.invoke(); onSearch = null; return super.search(ctx, track) }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: Target
    private var now = 1_000_000L
    private val leases = ArrayList<String>()

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("lsrc")
        dst = Target("ldst")
        Providers.testDoubles = listOf(src, dst)
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf()
        TargetLocks.clock = { now }
    }

    @After fun tearDown() {
        leases.forEach { TargetLocks.release(it) }
        TargetLocks.clock = System::currentTimeMillis
        Providers.testDoubles = emptyList()
    }

    private fun take(target: String, holder: String, ttl: Long = TargetLocks.DEFAULT_TTL_MS) =
        TargetLocks.take(target, holder, ttl).also { if (it is TargetLocks.Answer.Taken) leases += it.lease.id }

    private fun job(id: String = "j", linked: String? = null) =
        SyncJob(id = id, name = "test", source = PlaylistRef("lsrc", "p", "p"), target = PlaylistRef("ldst", "t", "t"), linkedJobId = linked)
            .also { env.store.upsertJob(it) }

    @Test fun aReservationIsTakenRenewedGivenBackAndExpires() {
        val a = take("svc|one", "Pixel 8") as TargetLocks.Answer.Taken
        assertEquals(now + TargetLocks.DEFAULT_TTL_MS, a.lease.until)
        // Someone else meanwhile: who holds it, and until when.
        assertEquals(TargetLocks.Answer.Busy("Pixel 8", a.lease.until), take("svc|one", "Laptop"))
        now += 60_000
        assertEquals(now + 120_000, TargetLocks.renew(a.lease.id, 120_000)!!.until)
        assertTrue(TargetLocks.release(a.lease.id))
        assertTrue("given back: anyone may take it", take("svc|one", "Laptop") is TargetLocks.Answer.Taken)

        val b = take("svc|two", "Pixel 8", 30_000) as TargetLocks.Answer.Taken
        now += 30_001
        // A device that died: its reservation ends by itself, cannot be renewed, and the playlist is free.
        assertNull(TargetLocks.renew(b.lease.id))
        assertTrue(take("svc|two", "Laptop") is TargetLocks.Answer.Taken)
        // What a caller may ask is bounded.
        val c = take("svc|three", "x", 1) as TargetLocks.Answer.Taken
        assertEquals(now + TargetLocks.MIN_TTL_MS, c.lease.until)
        val d = take("svc|four", "x", Long.MAX_VALUE / 2) as TargetLocks.Answer.Taken
        assertEquals(now + TargetLocks.MAX_TTL_MS, d.lease.until)
    }

    @Test fun twoHoldersRacingGetOneReservation() {
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val answers = (1..16).map { i -> pool.submit<TargetLocks.Answer> { start.await(); take("svc|race", "device $i") } }
        start.countDown()
        val got = answers.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        val taken = got.filterIsInstance<TargetLocks.Answer.Taken>()
        assertEquals(1, taken.size)
        assertTrue(got.filterIsInstance<TargetLocks.Answer.Busy>().all { it.holder == taken.single().lease.holder })
    }

    @Test fun aRunSkipsAPlaylistReservedElsewhereWithANoteAndNoError() = runBlocking {
        val lease = (take("ldst|t", "Pixel 8") as TargetLocks.Answer.Taken).lease
        val r = SyncEngine(env).run(job())
        assertNull(r.error)
        assertEquals(listOf("note_running_elsewhere(Pixel 8)"), r.notes)
        assertTrue("nothing written", dst.lists["t"]!!.isEmpty())
        assertEquals(r.id, env.store.job("j")!!.lastReportId)
        // Given back: the next run writes.
        TargetLocks.release(lease.id)
        assertEquals(1, SyncEngine(env).run(job()).added)
    }

    @Test fun anExpiredReservationNoLongerStopsARun() = runBlocking {
        take("ldst|t", "Pixel 8", TargetLocks.MIN_TTL_MS)
        now += TargetLocks.MIN_TTL_MS + 1
        assertEquals(1, SyncEngine(env).run(job()).added)
    }

    @Test fun aReservationIsRefusedWhileARunOfThisProcessWrites() = runBlocking {
        var during: TargetLocks.Answer? = null
        dst.onSearch = { during = take("ldst|t", "Pixel 8") }
        assertEquals(1, SyncEngine(env).run(job()).added)
        // Busy with no holder: the API names this server.
        assertEquals(TargetLocks.Answer.Busy(null, now + TargetLocks.DEFAULT_TTL_MS), during)
        assertTrue("free again after the run", take("ldst|t", "Pixel 8") is TargetLocks.Answer.Taken)
    }

    @Test fun aTwoWayHalfHoldsBothPlaylists() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        // The other half writes to this half's source.
        env.store.upsertJob(SyncJob(id = "back", name = "back", source = PlaylistRef("ldst", "t", "t"), target = PlaylistRef("lsrc", "p", "p"), linkedJobId = "j"))
        take("lsrc|p", "Pixel 8")
        val r = SyncEngine(env).run(job(linked = "back"))
        assertEquals(listOf("note_running_elsewhere(Pixel 8)"), r.notes)
        assertTrue(dst.lists["t"]!!.isEmpty())
        assertEquals(listOf("ldst|t", "lsrc|p"), TargetLocks.keys(job(linked = "back"), env.store.job("back")))
    }

    @Test fun theServerSaysAnotherDeviceWritesThere() = runBlocking {
        var asked: List<String>? = null
        env.coordinator = object : TargetCoordinator {
            override suspend fun <T> holding(targets: List<String>, block: suspend () -> T): T { asked = targets; throw TargetBusyException("Trackhop server", now + 60_000) }
        }
        val r = SyncEngine(env).run(job())
        assertEquals(listOf("ldst|t"), asked)
        assertNull(r.error)
        assertEquals(listOf("note_running_elsewhere(Trackhop server)"), r.notes)
        assertTrue(dst.lists["t"]!!.isEmpty())
        // Nothing of this process stays held: a reservation from elsewhere is taken at once.
        assertTrue(take("ldst|t", "Pixel 8") is TargetLocks.Answer.Taken)
    }

    @Test fun aRunWritesWhileTheServerHoldsItForThisDevice() = runBlocking {
        var held = false
        env.coordinator = object : TargetCoordinator {
            override suspend fun <T> holding(targets: List<String>, block: suspend () -> T): T { held = true; return block().also { held = false } }
        }
        dst.onSearch = { assertTrue(held) }
        assertEquals(1, SyncEngine(env).run(job()).added)
        assertTrue(!held)
    }

    @Test fun theKeyIsTheServiceNotTheRouteOrTheAccount() {
        assertEquals("spotify|abc", TargetLocks.key(PlaylistRef("spotify_bridge", "abc")))
        assertEquals("spotify|abc", TargetLocks.key(PlaylistRef("spotify@2", "abc")))
        assertEquals("youtube|PL1", TargetLocks.key(PlaylistRef("ytm", "PL1")))
        assertNull(TargetLocks.key(PlaylistRef("tidal", null)))
        assertNotNull(TargetLocks.key(PlaylistRef("csv", "Gym")))
        assertTrue(!TargetLocks.shared("csv|Gym"))
    }
}

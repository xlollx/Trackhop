package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.NetError
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
import java.net.UnknownHostException

/**
 * A run that loses the internet: a listing that could not be read is never taken for an empty one
 * (a mirror would remove every track, a two-way pair would remove on the other side), and a run cut
 * off while writing stops where it is, keeps what it added, removes nothing and says so in plain words.
 */
class OfflineRunTest {
    /** A service whose reads, adds or removals fail as a phone without internet does. */
    private class Flaky(id: String) : MemoryService(id) {
        var readsOffline = false
        /** Adds that go through before the line drops (null: never drops). */
        var addsBeforeDrop: Int? = null
        var removesOffline = false
        var removeCalls = 0
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
            if (readsOffline) throw UnknownHostException("api.$serviceId.example")
            return super.tracks(ctx, playlistId)
        }
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            addsBeforeDrop?.let { if (addCalls >= it) { addCalls++; throw UnknownHostException("api.$serviceId.example") } }
            super.addTracks(ctx, playlistId, tracks)
        }
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            removeCalls++
            if (removesOffline) throw UnknownHostException("api.$serviceId.example")
            super.removeTracks(ctx, playlistId, tracks)
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: Flaky
    private lateinit var dst: Flaky
    private lateinit var engine: SyncEngine
    private val retries = NETWORK_RETRY_SECONDS

    private fun t(prefix: String, n: Int) = Track(id = "$prefix$n", title = "Song number $n", artists = listOf("Band"), album = "Album", durationMs = 200_000)
    /** A track on the target that matches nothing of the source. */
    private fun other(n: Int) = Track(id = "x$n", title = "Unrelated tune $n", artists = listOf("Other band"), album = "Elsewhere", durationMs = 100_000)

    @Before fun setUp() {
        // No waiting between the engine's network retries: the line stays down in these tests.
        NETWORK_RETRY_SECONDS = intArrayOf()
        env = ContractEnv()
        src = Flaky("osrc")
        dst = Flaky("odst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.catalogue += (1..80).map { t("s", it) }
        dst.catalogue += (1..80).map { t("d", it) }
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        NETWORK_RETRY_SECONDS = retries
        NetError.networkUp = { null }
    }

    private fun mirror(id: String = "m") = SyncJob(
        id = id, name = "mirror", source = PlaylistRef("osrc", "p", "p"), target = PlaylistRef("odst", "t", "t"),
        mirrorRemovals = true, followsChanges = true,
    ).also { env.store.upsertJob(it) }

    @Test fun aSourceThatCannotBeReadIsNotAnEmptySource() = runBlocking {
        src.lists["p"] = (1..5).map { t("s", it) }.toMutableList()
        dst.lists["t"] = (1..5).map { t("d", it) }.toMutableList()
        src.readsOffline = true
        val r = engine.run(mirror())
        assertFalse(r.ok)
        assertTrue(r.offline)
        assertEquals("report_stopped_offline", r.error)
        assertEquals(0, r.removed)
        assertEquals(0, dst.removeCalls)
        assertEquals(5, dst.lists["t"]!!.size)
    }

    @Test fun aTargetThatCannotBeReadIsNotAnEmptyTarget() = runBlocking {
        src.lists["p"] = (1..5).map { t("s", it) }.toMutableList()
        dst.lists["t"] = (1..5).map { t("d", it) }.toMutableList()
        dst.readsOffline = true
        val r = engine.run(mirror())
        assertTrue(r.offline)
        assertEquals(0, r.added)
        assertEquals(0, dst.addCalls)
        assertEquals(0, dst.removeCalls)
        assertEquals(5, dst.lists["t"]!!.size)
    }

    @Test fun aTwoWayPairRemovesNothingWhenOneSideCannotBeRead() = runBlocking {
        src.lists["p"] = (1..6).map { t("s", it) }.toMutableList()
        dst.lists["t"] = (1..6).map { t("d", it) }.toMutableList()
        env.store.upsertJob(SyncJob(id = "ab", name = "pair", source = PlaylistRef("osrc", "p", "p"), target = PlaylistRef("odst", "t", "t"), linkedJobId = "ba", twoWayRemovals = true))
        env.store.upsertJob(SyncJob(id = "ba", name = "pair ↔", source = PlaylistRef("odst", "t", "t"), target = PlaylistRef("osrc", "p", "p"), linkedJobId = "ab", twoWayRemovals = true, twin = true))
        engine.run(env.store.job("ab")!!); engine.run(env.store.job("ba")!!)
        val before = env.store.pairState(env.store.job("ab")!!)!!
        assertEquals(6, before.links.size)

        // One side's reads fail: neither half removes anything, and the pair's state stays as it was.
        src.readsOffline = true
        val ab = engine.run(env.store.job("ab")!!)
        val ba = engine.run(env.store.job("ba")!!)
        assertTrue(ab.offline); assertTrue(ba.offline)
        assertEquals(0, ab.removed + ba.removed)
        assertEquals(0, src.removeCalls + dst.removeCalls)
        assertEquals(6, src.lists["p"]!!.size); assertEquals(6, dst.lists["t"]!!.size)
        assertEquals(before.links, env.store.pairState(env.store.job("ab")!!)!!.links)
    }

    @Test fun theLineDroppingWhileAddingStopsTheRunAndKeepsWhatWasAdded() = runBlocking {
        // 60 tracks to add, in two blocks of 50; the target also holds 2 tracks the source does not.
        src.lists["p"] = (1..60).map { t("s", it) }.toMutableList()
        dst.lists["t"] = mutableListOf(other(1), other(2))
        dst.addsBeforeDrop = 1
        val r = engine.run(mirror())
        assertTrue(r.offline)
        assertEquals("report_stopped_offline", r.error)
        assertEquals(50, r.added)
        // Not every track named as failed with the line's text, and no removal on a run cut short.
        assertTrue(r.unmatched.toString(), r.unmatched.none { it.contains("api.odst") })
        assertEquals(0, r.removed)
        assertEquals(0, dst.removeCalls)
        assertEquals(52, dst.lists["t"]!!.size)
    }

    @Test fun theLineDroppingWhileRemovingKeepsTheAdditionsAndSaysSo() = runBlocking {
        src.lists["p"] = (1..3).map { t("s", it) }.toMutableList()
        dst.lists["t"] = mutableListOf(t("d", 1), other(9))
        dst.removesOffline = true
        val r = engine.run(mirror())
        assertTrue(r.offline)
        assertEquals(2, r.added)
        assertEquals(0, r.removed)
        assertTrue(r.removedTracks.isEmpty())
    }

    @Test fun onAServerTheTextSaysItIsTheServersConnection() = runBlocking {
        val serverEnv = object : Env by env { override val onServer = true }
        src.lists["p"] = mutableListOf(t("s", 1))
        dst.lists["t"] = mutableListOf()
        src.readsOffline = true
        val r = SyncEngine(serverEnv).run(mirror("srv"))
        assertEquals("report_stopped_offline_server", r.error)
        assertTrue(r.offline)
    }

    @Test fun aServiceErrorIsNotTakenForTheConnection() = runBlocking {
        src.lists["p"] = mutableListOf(t("s", 1))
        dst.lists["t"] = mutableListOf()
        src.failure = com.xlollx.songport.model.ProviderException("osrc API 500: internal error")
        val r = engine.run(mirror())
        assertFalse(r.ok)
        assertFalse(r.offline)
        assertNull(r.netError)
        assertEquals("osrc API 500: internal error", r.error)
    }
}

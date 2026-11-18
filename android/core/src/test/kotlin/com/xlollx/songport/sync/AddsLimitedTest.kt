package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2.1.4: Deezer (web) limited the likes after 321 of 2097 matched songs; the other 1776 ended as
 * "not found" (nf), tried one by one, and the card said "Up to date". Matched songs the target did not
 * take are "not added this time": never not found, never tried track by track against a limit, and
 * added by the next run.
 */
class AddsLimitedTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        val tracks = (1..120).map { Track(id = "s$it", title = "Song $it", artists = listOf("Band"), durationMs = 200_000) }
        src.lists["p"] = tracks.toMutableList()
        dst.lists["t"] = ArrayList()
        tracks.forEach { dst.catalogue += it.copy(id = "d${it.id}") }
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job() = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }

    @Test fun aLimitPartWayLeavesTheRestForTheNextRunAndNeverAsNotFound() = runBlocking {
        dst.limitAfter = 70
        val r = SyncEngine(env).run(job())
        assertNull(r.error)
        assertEquals(70, r.added)
        assertEquals(70, dst.lists["t"]!!.size)
        // Matched, not added: their own list, nf unchanged.
        assertEquals(emptyList<String>(), r.unmatched)
        assertEquals(emptyList<Track>(), r.unmatchedTracks)
        assertEquals(50, r.notAdded.size)
        assertTrue(r.notes.toString(), "note_not_added_limited(50,mdst)" in r.notes)
        // Block 1 whole, block 2 stopped by the limit; block 3 never asked, nothing tried one by one.
        assertEquals(2, dst.addCalls)
        // The service's reason is in the log.
        assertTrue(env.logs.toString(), env.logs.any { it.contains("limited the requests: mdst: too many requests") && it.contains("50 tracks left for the next run") })
        assertEquals("+70 -0 nf=0 src=120 dst=0 held=0 interrupted=false net=- notAdded=50", SyncLog.end(r))

        // The next run, the limit gone, adds the rest, once each.
        dst.limitAfter = null
        val r2 = SyncEngine(env).run(job())
        assertNull(r2.error)
        assertEquals(50, r2.added)
        assertEquals(emptyList<String>(), r2.notAdded)
        assertEquals(120, dst.lists["t"]!!.map { it.id }.distinct().size)
        assertEquals(120, dst.lists["t"]!!.size)
    }

    @Test fun anOlderConnectorsLimitErrorStopsAddingInsteadOfTryingEachTrack() = runBlocking {
        dst.limitAfter = 50
        dst.limitAnswer = { ProviderException("Deezer (web): Deezer asks to slow down (429): try again in a few minutes") }
        val r = SyncEngine(env).run(job())
        assertNull(r.error)
        assertEquals(50, r.added)
        assertEquals(emptyList<String>(), r.unmatched)
        assertEquals(70, r.notAdded.size)
        // One call per block until the limit, then none: no hammering track by track.
        assertEquals(2, dst.addCalls)
    }

    @Test fun refusalsInARowAfterSomeWentInStopTheRunWithItsAnswer() = runBlocking {
        // The block fails, and so does every track tried alone (a session that went bad mid-run).
        dst.limitAfter = 50
        dst.limitAnswer = { ProviderException("Deezer (web): the Deezer web session has expired") }
        val r = SyncEngine(env).run(job())
        // An error to fix (sign in again), not songs to add next run.
        assertTrue(r.error.toString(), r.error?.contains("session has expired") == true)
        assertEquals(50, r.added)
        // The 20 refused one by one are listed as before; those never asked are left over.
        assertEquals(20, r.unmatched.size)
        assertEquals(50, r.notAdded.size)
        // The block, then 20 one by one, then nothing more.
        assertEquals(2 + 20, dst.addCalls)
    }
}

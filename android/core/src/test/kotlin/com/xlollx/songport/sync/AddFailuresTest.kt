package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #34: a target refusing every addition (Spotify's closed /me/tracks, 403) ended the run with
 * every track counted as not found and no error. Now adding stops early and the run says why.
 */
class AddFailuresTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        val tracks = (1..45).map { Track(id = "s$it", title = "Song $it", artists = listOf("Band"), durationMs = 200_000) }
        src.lists["p"] = tracks.toMutableList()
        dst.lists["t"] = ArrayList()
        tracks.forEach { dst.catalogue += it.copy(id = "d${it.id}") }
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job() = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }

    @Test fun everyAdditionRefusedFailsTheRunInsteadOfNotFound() = runBlocking {
        dst.addFailure = ProviderException("rejected the request (HTTP 403)")
        val r = SyncEngine(env).run(job())
        assertTrue(r.error.toString(), r.error?.contains("HTTP 403") == true)
        assertEquals(0, r.added)
        // One call for the first block, then five one by one: not one per track.
        assertEquals(6, dst.addCalls)
    }

    @Test fun additionsThatWorkAreNotStopped() = runBlocking {
        val r = SyncEngine(env).run(job())
        assertEquals(null, r.error)
        assertEquals(45, r.added)
    }
}

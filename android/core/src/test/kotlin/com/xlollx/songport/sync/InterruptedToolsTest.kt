package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * Tools stopped half way. Removing duplicates on a service that removes by id takes every copy
 * away and puts one back: stopped or cut off between the two, the song must not be lost quietly.
 * Making a playlist that is stopped ends there, without every track left listed as failed.
 */
class InterruptedToolsTest {
    /** Removes by id (every copy goes, as on Spotify or Deezer); its adds can fail or be stopped. */
    private class ById : MemoryService("byid") {
        var addsFailing = 0
        var stopOnRemove = false
        var stopAtAdd: Int? = null
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            super.removeTracks(ctx, playlistId, tracks)
            // The page is left right now: whoever asked is no longer there.
            if (stopOnRemove) coroutineContext[Job]?.cancel()
        }
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            if (addCalls + 1 == stopAtAdd) { addCalls++; throw CancellationException("stopped") }
            if (addsFailing > 0) { addsFailing--; addCalls++; throw UnknownHostException("api.byid.example") }
            super.addTracks(ctx, playlistId, tracks)
        }
    }

    private val env = ContractEnv()
    private val svc = ById()
    private val waits = LibraryTools.PUT_BACK_WAITS_MS
    private fun t(n: Int) = Track(id = "s$n", title = "Song number $n", artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        LibraryTools.PUT_BACK_WAITS_MS = longArrayOf(0, 0)
        svc.lists["p"] = mutableListOf(t(1), t(2), t(1), t(3))
    }

    @After fun tearDown() { LibraryTools.PUT_BACK_WAITS_MS = waits }

    @Test fun theCopyPutBackIsTriedAgainWhenTheConnectionDrops() = runBlocking {
        svc.addsFailing = 2
        assertEquals(1, LibraryTools.removeDuplicates(env, svc, "p"))
        assertEquals("one copy of each, none lost", listOf("s1", "s2", "s3"), svc.lists["p"]!!.map { it.id }.sorted())
    }

    @Test fun aSongThatCouldNotBePutBackIsNamed() = runBlocking {
        svc.addsFailing = 99
        val e = runCatching { LibraryTools.removeDuplicates(env, svc, "p") }.exceptionOrNull()
        assertTrue("$e", e is ProviderException)
        assertEquals("tools_dedupe_not_put_back(Band – Song number 1)", e!!.message)
        assertTrue(env.logs.any { "not put back" in it })
    }

    @Test fun leavingThePageBetweenTheTwoStepsDoesNotLoseTheSong() = runBlocking {
        svc.stopOnRemove = true
        supervisorScope {
            val run = async { LibraryTools.removeDuplicates(env, svc, "p") }
            runCatching { run.await() }
        }
        assertEquals("the copy was put back although the run was stopped", listOf("s1", "s2", "s3"), svc.lists["p"]!!.map { it.id }.sorted())
    }

    @Test fun makingAPlaylistThatIsStoppedEndsThere() = runBlocking {
        svc.stopAtAdd = 2
        val e = runCatching { LibraryTools.createWith(env, svc, "New", "", (1..120).map { t(it) }) }.exceptionOrNull()
        assertTrue("$e", e is CancellationException)
        assertEquals("no track tried by itself after the stop", 2, svc.addCalls)
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.UnknownHostException

/**
 * Tools cut in two by something nothing can hold back (the process killed, the phone off): what
 * they were doing is on the disk before the step, and the next start finishes it or offers to.
 * "Remove duplicates" puts back the song whose copies it had all taken out; a new playlist filled
 * half way gets only its missing tracks with "Continue".
 */
class UnfinishedToolsTest {
    /** Removes by id (every copy goes, as on Spotify or Deezer); its adds can fail or be stopped. */
    private class ById : MemoryService("ubyid") {
        var addsFailing = 0
        var stopAtAdd: Int? = null
        var onRemove: (() -> Unit)? = null
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            onRemove?.invoke()
            super.removeTracks(ctx, playlistId, tracks)
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
        LibraryTools.PUT_BACK_WAITS_MS = longArrayOf(0)
        Providers.testDoubles = listOf(svc)
        svc.lists["p"] = mutableListOf(t(1), t(2), t(1), t(3))
    }

    @After fun tearDown() { LibraryTools.PUT_BACK_WAITS_MS = waits; Providers.testDoubles = emptyList() }

    @Test fun theSongToPutBackIsOnTheDiskBeforeItsCopiesGoAndCrossedOutOnceBack() = runBlocking {
        val atRemoval = ArrayList<String>()
        svc.onRemove = { atRemoval += Unfinished.putBacks.read(env).map { "${it.provider}|${it.playlistId}|${it.track.id}" } }
        assertEquals(1, LibraryTools.removeDuplicates(env, svc, "p"))
        assertEquals("written before the removal", listOf("ubyid|p|s1"), atRemoval)
        assertTrue("on the disk, not in memory", File(env.dataDir, "unfinished/put-back.json").exists())
        assertTrue("nothing left to put back", Unfinished.putBacks.read(env).isEmpty())
        assertEquals(LibraryTools.PutBacks(emptyList(), emptyList()), LibraryTools.resumePutBacks(env))
    }

    @Test fun aSongLeftOutWhenTheRunWasCutIsPutBackAtTheNextStartOnce() = runBlocking {
        // Every copy went and nothing could be added after it: what a killed process leaves.
        svc.addsFailing = 99
        assertTrue(runCatching { LibraryTools.removeDuplicates(env, svc, "p") }.exceptionOrNull() is ProviderException)
        assertEquals("the song is out of the playlist", listOf("s2", "s3"), svc.lists["p"]!!.map { it.id })
        assertEquals(listOf("s1"), Unfinished.putBacks.read(env).map { it.track.id })

        // The next start, still without a connection: it stays written.
        val offline = LibraryTools.resumePutBacks(env)
        assertEquals(listOf("s1"), offline.waiting.map { it.id })
        assertTrue(offline.back.isEmpty())

        // The start after: put back, said, crossed out.
        svc.addsFailing = 0
        val done = LibraryTools.resumePutBacks(env)
        assertEquals(listOf("s1"), done.back.map { it.id })
        assertEquals(listOf("s1", "s2", "s3"), svc.lists["p"]!!.map { it.id }.sorted())
        assertTrue(Unfinished.putBacks.read(env).isEmpty())
        assertTrue(env.logs.toString(), env.logs.any { "put back in p" in it })

        // And nothing is added a second time.
        assertEquals(LibraryTools.PutBacks(emptyList(), emptyList()), LibraryTools.resumePutBacks(env))
        assertEquals(3, svc.lists["p"]!!.size)
    }

    @Test fun aSongThePersonPutBackByHandIsNotAddedAgain() = runBlocking {
        svc.addsFailing = 99
        runCatching { LibraryTools.removeDuplicates(env, svc, "p") }
        svc.addsFailing = 0
        svc.lists["p"]!! += t(1)
        val r = LibraryTools.resumePutBacks(env)
        assertTrue(r.back.isEmpty() && r.waiting.isEmpty())
        assertEquals(1, svc.lists["p"]!!.count { it.id == "s1" })
        assertTrue(Unfinished.putBacks.read(env).isEmpty())
    }

    @Test fun aPlaylistFilledHalfWayIsOfferedAndContinueAddsOnlyWhatIsMissing() = runBlocking {
        val meant = (1..120).map { t(it) }
        svc.stopAtAdd = 3
        assertTrue(runCatching { LibraryTools.createWith(env, svc, "New", "", meant) }.exceptionOrNull() is CancellationException)
        assertEquals(100, svc.lists["New"]!!.size)
        val entry = LibraryTools.halfMade(env).single()
        assertEquals("New", entry.name)
        assertEquals("ubyid", entry.provider)
        assertEquals(120, entry.tracks.size)
        assertTrue("on the disk, not in memory", File(env.dataDir, "unfinished/half-made.json").exists())

        svc.stopAtAdd = null
        val made = LibraryTools.continueHalfMade(env, entry)
        assertEquals("only the missing ones", 20, made.added)
        assertEquals(meant.map { it.id }, svc.lists["New"]!!.map { it.id })
        assertTrue("no longer offered", LibraryTools.halfMade(env).isEmpty())

        // Continued again (a second tap, an entry of before): nothing is added twice.
        assertEquals(0, LibraryTools.continueHalfMade(env, entry).added)
        assertEquals(120, svc.lists["New"]!!.size)
    }

    @Test fun aPlaylistFilledToTheEndIsNotOfferedAndOneDismissedIsForgotten() = runBlocking {
        LibraryTools.createWith(env, svc, "Whole", "", (1..60).map { t(it) })
        assertTrue(LibraryTools.halfMade(env).isEmpty())
        svc.stopAtAdd = svc.addCalls + 1
        runCatching { LibraryTools.createWith(env, svc, "Half", "", (1..60).map { t(it) }) }
        val entry = LibraryTools.halfMade(env).single()
        LibraryTools.forgetHalfMade(env, entry)
        assertTrue(LibraryTools.halfMade(env).isEmpty())
        assertTrue("the playlist stays as it is", svc.lists.containsKey("Half"))
    }
}

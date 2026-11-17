package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The playlist a run makes is made once. A service that makes it and whose reply is lost (a timeout)
 * used to be asked again, and made a second one with the same name: now the playlists are read
 * again first, and the one this run made is taken. One that was already there with that name is
 * someone's own and is left alone.
 */
class CreateOnceTest {
    /** A service whose playlists have ids of their own (two may share a name), and whose reply to a creation can be lost. */
    private class Flaky(id: String) : MemoryService(id) {
        /** How many creations lose their reply after the playlist was made. */
        var lostReplies = 0
        /** How many creations fail before anything is made. */
        var refused = 0
        var createCalls = 0
        private var next = 1
        override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
            createCalls++
            if (refused > 0) { refused--; throw ProviderException("$serviceId: timeout") }
            val id = "pl${next++}"
            lists[id] = ArrayList()
            names[id] = name
            if (lostReplies > 0) { lostReplies--; throw java.net.SocketTimeoutException("timeout") }
            return Playlist(id, name, 0)
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: Flaky
    private val retries = NETWORK_RETRY_SECONDS

    private fun t(prefix: String, n: Int) = Track(id = "$prefix$n", title = "Song number $n", artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("osrc")
        dst = Flaky("odst")
        Providers.testDoubles = listOf(src, dst)
        src.catalogue += (1..3).map { t("s", it) }
        dst.catalogue += (1..3).map { t("d", it) }
        src.lists["p"] = (1..3).map { t("s", it) }.toMutableList()
        NETWORK_RETRY_SECONDS = intArrayOf(0, 0, 0)
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        NETWORK_RETRY_SECONDS = retries
    }

    private fun job() = SyncJob(id = "n", name = "new", source = PlaylistRef("osrc", "p", "p"), target = PlaylistRef("odst", null, "Road trip")).also { env.store.upsertJob(it) }

    private fun named(name: String) = dst.lists.keys.filter { dst.names[it] == name }

    @Test fun aCreationWhoseReplyIsLostIsNotAskedTwice() = runBlocking {
        dst.lostReplies = 1
        val r = SyncEngine(env).run(job())
        assertTrue(r.error, r.ok)
        assertEquals("asked once: the playlist it made was found", 1, dst.createCalls)
        assertEquals("one playlist with that name", listOf("pl1"), named("Road trip"))
        assertEquals("pl1", env.store.job("n")!!.target.playlistId)
        assertEquals(3, dst.lists["pl1"]!!.size)
    }

    @Test fun aPlaylistThatWasAlreadyThereWithThatNameIsNotTaken() = runBlocking {
        // The person's own "Road trip", empty or not: not this run's.
        dst.lists["mine"] = ArrayList(); dst.names["mine"] = "Road trip"
        dst.lists["full"] = arrayListOf(t("d", 9)); dst.names["full"] = "Road trip"
        dst.refused = 1
        val r = SyncEngine(env).run(job())
        assertTrue(r.error, r.ok)
        assertEquals("nothing was made the first time: asked again", 2, dst.createCalls)
        assertEquals(listOf("mine", "full", "pl1"), named("Road trip"))
        assertEquals("pl1", env.store.job("n")!!.target.playlistId)
        assertTrue(dst.lists["mine"]!!.isEmpty())
        assertEquals(1, dst.lists["full"]!!.size)
    }

    @Test fun aCreationThatGoesThroughIsOneCall() = runBlocking {
        val r = SyncEngine(env).run(job())
        assertTrue(r.error, r.ok)
        assertEquals(1, dst.createCalls)
        assertEquals(listOf("pl1"), named("Road trip"))
    }
}

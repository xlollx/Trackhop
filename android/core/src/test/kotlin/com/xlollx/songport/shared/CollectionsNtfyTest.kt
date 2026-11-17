package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.MemoryService
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A collection on an encrypted ntfy channel: the one invite holds the key of the list only; each
 * playlist's own key travels inside the list, encrypted with it, and each playlist has its own topic.
 */
class CollectionsNtfyTest {
    private var now = 1_800_000_000_000L
    private val ntfy = FakeNtfy({ now })
    private val server = MockWebServer().apply { dispatcher = ntfy; start() }
    private val address = server.url("/").toString()
    private val giulia = ContractEnv()
    private val leo = ContractEnv()
    private val gs = MemoryService("ngs")
    private val ln = MemoryService("nln")

    private fun song(prefix: String, n: Int) = Track("$prefix$n", "Song $n", listOf("Band"), "Album", 200_000, isrc = "QZCNT26" + n.toString().padStart(5, '0'))

    @Before fun setUp() {
        Features.build = true
        SigCache.clear()
        SharedGroups.clock = { now }
        gs.catalogue += (1..10).map { song("g", it) }
        ln.catalogue += (1..10).map { song("n", it) }
        gs.lists["Road Trip"] = (1..3).map { song("g", it) }.toMutableList()
        gs.lists["Jazz"] = (4..5).map { song("g", it) }.toMutableList()
        Providers.testDoubles = listOf(gs, ln)
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        SharedGroups.clock = { System.currentTimeMillis() }
        Features.build = false
        server.shutdown()
    }

    private fun run(env: ContractEnv, id: String) = runBlocking {
        repeat(2) {
            for (j in Collections.order(env, Collections.get(env, id)!!)) SyncEngine(env).run(env.store.job(j)!!).let { assertTrue("${it.jobName}: ${it.error}", it.ok) }
        }
    }

    @Test fun oneInviteCarriesEveryPlaylistOverTheEncryptedChannel() {
        val (c, made) = runBlocking {
            Collections.create(giulia, "Giulia's playlists", "Giulia", "ngs", gs.lists.map { Playlist(it.key, it.key, it.value.size) }, address, NtfyServer.hostOf(address), Invite.NTFY)
        }
        assertTrue(made.failed.toString(), made.complete)
        run(giulia, c.id)
        val link = Collections.invite(giulia, c).link()
        // The link holds the list's key; the playlists' own keys are not in it, nor readable on the server.
        val keys = giulia.store.data.groups.map { SharedTransports.groupKey(giulia, it)!! }
        assertEquals(2, keys.toSet().size)
        assertNotEquals(keys[0], SharedTransports.groupKey(giulia, c.list))
        assertTrue(keys.none { it in link })
        assertTrue(ntfy.messages.none { m -> keys.any { it in m.text } })
        assertEquals(3, ntfy.topics().size)

        val invite = Invite.parse(link)!!
        assertTrue(invite.collection && invite.transport == Invite.NTFY)
        val joined = Collections.join(leo, invite, "", "", "Leo")
        assertEquals(listOf("Jazz", "Road Trip"), Collections.entries(leo, joined).map { it.name }.sorted())
        val settled = runBlocking { Collections.choose(leo, c.id, SharedCollection.ALL, listOf("nln"), true, emptySet()) }
        assertTrue(settled.failed.toString(), settled.complete)
        assertEquals(keys.toSet(), leo.store.data.groups.map { SharedTransports.groupKey(leo, it)!! }.toSet())
        run(leo, c.id)
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), ln.lists["Road Trip"]!!.map { it.title }.sorted())
        assertEquals(listOf("Song 4", "Song 5"), ln.lists["Jazz"]!!.map { it.title }.sorted())
    }
}

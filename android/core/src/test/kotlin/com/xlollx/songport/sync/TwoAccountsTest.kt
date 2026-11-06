package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two accounts of one service (ytm and ytm@2): where the service's ids are the same for every
 * account, a track goes from one to the other as itself, with no search (2 000 liked songs in
 * seconds, not an hour). Two servers of one's own (jellyfin, jellyfin@2) number tracks each their
 * own way: there tracks are still searched.
 */
class TwoAccountsTest {
    private val env = ContractEnv()
    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    private class Second(service: String) : MemoryService(service) { override val slot: String = "2" }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun twoAccountsOfOneCatalogueCopyTracksAsThemselves() = runBlocking {
        val a = MemoryService("ytm"); val b = Second("ytm")
        Providers.testDoubles = listOf(a, b)
        a.lists["LM"] = mutableListOf(t("v1", "One"), t("v2", "Two"))
        b.lists["LM"] = mutableListOf(t("v1", "One"))
        // Nothing to find by searching: only the ids themselves can match.
        val j = SyncJob("j", "Liked", PlaylistRef("ytm", "LM", "Liked"), PlaylistRef("ytm@2", "LM", "Liked")).also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(j)
        assertTrue(r.error, r.ok)
        assertEquals(1, r.added)
        assertTrue(r.unmatched.isEmpty())
        assertEquals(listOf("v1", "v2"), b.lists["LM"]!!.map { it.id })
    }

    @Test fun twoServersOfOnesOwnStillSearch() = runBlocking {
        val a = MemoryService("jellyfin"); val b = Second("jellyfin")
        Providers.testDoubles = listOf(a, b)
        a.lists["p"] = mutableListOf(t("x1", "One"))
        b.lists["q"] = mutableListOf()
        b.catalogue += t("y9", "One")
        val j = SyncJob("k", "Home to friend", PlaylistRef("jellyfin", "p", "P"), PlaylistRef("jellyfin@2", "q", "Q")).also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(j)
        assertTrue(r.error, r.ok)
        assertEquals(listOf("y9"), b.lists["q"]!!.map { it.id })
    }
}

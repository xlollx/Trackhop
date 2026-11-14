package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A match remembered from before the rules knew a concert (Spotify's "SING" on YouTube Music's
 * "SING (iTunes Festival '11)", owner 14/11): asked about in the review, never taken off by itself,
 * and left alone once the user kept it.
 */
class LiveRecheckTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private val sing = Track("s", "SING", listOf("My Chemical Romance"), "Danger Days", 299_000)
    private val live = Track("l", "SING (iTunes Festival '11) [2026 Remaster]", listOf("My Chemical Romance"), "iTunes Festival: London 2011", 290_000)
    private val other = Track("o", "Na Na Na", listOf("My Chemical Romance"), "Danger Days", 205_000)
    private val otherThere = Track("do", "Na Na Na", listOf("My Chemical Romance"), "Danger Days", 205_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(sing, other)
        dst.catalogue += listOf(live, otherThere)
        dst.lists["t"] = mutableListOf(live, otherThere)
        env.store.putMatches(mapOf(FileStore.cacheKey("msrc", "s", "mdst") to "l", FileStore.cacheKey("msrc", "o", "mdst") to "do"))
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))

    @Test fun aRememberedLiveMatchIsAskedAboutAndStays() = runBlocking {
        env.store.upsertJob(job)
        val r = engine.run(job)
        assertEquals(listOf("s"), r.reviewTracks.map { it.source.id })
        assertEquals("l", r.reviewTracks.single().chosen.id)
        // Nothing leaves the target, nothing is added twice.
        assertEquals(listOf("l", "do"), dst.lists["t"]!!.map { it.id })
        assertEquals(0, r.added)
        assertEquals(0, r.removed)

        // Kept by the user: never asked again.
        engine.confirmMatches(r.id, r.reviewTracks)
        val again = engine.run(env.store.job("j")!!)
        assertTrue(again.reviewTracks.isEmpty())
        assertEquals(listOf("l", "do"), dst.lists["t"]!!.map { it.id })
    }
}

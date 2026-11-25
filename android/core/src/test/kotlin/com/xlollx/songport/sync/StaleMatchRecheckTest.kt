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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A match the engine found under an older matching is checked by the current one (2.2): one it no
 * longer finds sure is asked about in the review, never removed; a good one is noted under the
 * current revision. The user's own choices and the matches saved before 2.2 are never asked about.
 */
class StaleMatchRecheckTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    // Two remixes by different people: the older matching took one for the other.
    private val wanted = Track("s", "Levitating (The Blessed Madonna Remix)", listOf("Dua Lipa"), "Club Future Nostalgia", 260_000)
    private val wrong = Track("w", "Levitating (Moon Boots Remix)", listOf("Dua Lipa"), "Club Future Nostalgia", 260_000)
    private val good = Track("g", "Physical", listOf("Dua Lipa"), "Future Nostalgia", 193_000)
    private val goodThere = Track("dg", "Physical", listOf("Dua Lipa"), "Future Nostalgia", 193_000)
    private val wantedKey = FileStore.cacheKey("msrc", "s", "mdst")
    private val goodKey = FileStore.cacheKey("msrc", "g", "mdst")

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(wanted, good)
        dst.catalogue += listOf(wrong, goodThere)
        dst.lists["t"] = mutableListOf(wrong, goodThere)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))

    private fun remember(auto: Boolean, revision: Int = Matcher.REVISION - 1) {
        env.store.putMatches(mapOf(wantedKey to "w", goodKey to "dg"), auto = auto)
        if (auto) env.store.update { d -> d.copy(autoMatches = d.autoMatches + (wantedKey to "$revision|w") + (goodKey to "$revision|dg")) }
    }

    @Test fun anOlderEnginesMatchTheCurrentOneDoubtsIsAskedAboutNeverRemoved() = runBlocking {
        remember(auto = true)
        env.store.upsertJob(job)
        val r = engine.run(job)
        assertEquals(listOf("s"), r.reviewTracks.map { it.source.id })
        assertEquals("w", r.reviewTracks.single().chosen.id)
        assertEquals(listOf("w", "dg"), dst.lists["t"]!!.map { it.id })
        assertEquals(0, r.added)
        assertEquals(0, r.removed)
        // The good one is noted under this matching; the doubtful one is asked about until the user decides.
        assertEquals(Matcher.REVISION, env.store.autoRevision(goodKey))
        assertEquals(Matcher.REVISION - 1, env.store.autoRevision(wantedKey))
        val second = engine.run(env.store.job("j")!!)
        assertEquals(listOf("s"), second.reviewTracks.map { it.source.id })
        engine.confirmMatches(second.id, second.reviewTracks)
        assertTrue(engine.run(env.store.job("j")!!).reviewTracks.isEmpty())
    }

    @Test fun theUsersChoicesAndMatchesSavedBefore22AreNeverAsked() = runBlocking {
        remember(auto = false)
        env.store.upsertJob(job)
        assertTrue(engine.run(job).reviewTracks.isEmpty())
        assertNull(env.store.autoRevision(wantedKey))
        assertEquals(listOf("w", "dg"), dst.lists["t"]!!.map { it.id })
    }

    @Test fun aMatchOfTheCurrentRevisionIsNotCheckedAgain() = runBlocking {
        remember(auto = true, revision = Matcher.REVISION)
        env.store.upsertJob(job)
        assertTrue(engine.run(job).reviewTracks.isEmpty())
    }
}

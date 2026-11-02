package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A long playlist often holds the same song twice. Not found on the target, it used to appear twice
 * among the songs to fix, and the review screen, one row per song id, closed the app (issue #24).
 */
class DuplicateSourceTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private val here = Track(id = "s1", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val missing = Track(id = "s2", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(here, missing, here, missing, missing)
        dst.catalogue += Track(id = "d1", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(): SyncJob =
        SyncJob(id = "job-" + System.nanoTime(), name = "test", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))
            .also { env.store.upsertJob(it) }

    @Test fun aSongTwiceInTheSourceIsOneSongToFix() = runBlocking {
        val plan = engine.plan(job(), createTarget = false, interactive = true)
        assertEquals(listOf("s2"), plan.unmatched.map { it.id })
        assertEquals(listOf("d1"), plan.toAdd.map { it.id })
        assertEquals(5, plan.sourceCount)
    }

    @Test fun theRunsReportListsItOnce() = runBlocking {
        val report = engine.run(job())
        assertEquals(listOf("s2"), report.unmatchedTracks.map { it.id })
        assertEquals(1, report.added)
    }
}

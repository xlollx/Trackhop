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

/** "Up to date" only once the source is checked now against the version kept at the last run. */
class FreshnessTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("fsrc")
        dst = MemoryService("fdst")
        Providers.testDoubles = listOf(src, dst)
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += listOf(t("A", "Alpha"))
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job() = SyncJob(id = "j", name = "J", source = PlaylistRef("fsrc", "p", "P"), target = PlaylistRef("fdst", "t", "T"))

    @Test fun aManualRunIsUpToDateOnlyWhileItsSourceIsUnchanged() = runBlocking {
        src.versions["p"] = "v1"
        env.store.upsertJob(job())
        // Never run: nothing to compare with.
        assertEquals(Freshness.State.UNKNOWN, Freshness.check(env, env.store.job("j")!!))
        SyncEngine(env).run(env.store.job("j")!!)
        assertEquals("v1", env.store.job("j")!!.sourceVersion)
        assertEquals(Freshness.State.CURRENT, Freshness.check(env, env.store.job("j")!!))
        // Someone adds a song to the source after the run: no longer up to date.
        src.versions["p"] = "v2"
        assertEquals(Freshness.State.CHANGED, Freshness.check(env, env.store.job("j")!!))
    }

    @Test fun aServiceWithoutVersionsIsNeverCalledUpToDate() = runBlocking {
        env.store.upsertJob(job())
        SyncEngine(env).run(env.store.job("j")!!)
        assertEquals(null, env.store.job("j")!!.sourceVersion)
        assertEquals(Freshness.State.UNKNOWN, Freshness.check(env, env.store.job("j")!!))
    }
}

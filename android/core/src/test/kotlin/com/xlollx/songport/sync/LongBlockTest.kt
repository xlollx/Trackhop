package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Apple's web backend answers a long run of searches with 429 "Request is forbidden" and keeps
 * refusing for an hour or more (#46, #47). The sync stops at once and says it is the service's limit,
 * instead of climbing the 10–120 s wait ladder for nothing.
 */
class LongBlockTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun appleRefusingSearchesStopsAtOnceAndSaysItIsTheServicesLimit() = runBlocking {
        src.lists["p"] = (1..20).map { Track(id = "s$it", title = "Song $it", artists = listOf("Band"), durationMs = 200_000) }.toMutableList()
        dst.lists["t"] = ArrayList()
        dst.searchError = "Apple Music (web) API 429: Request is forbidden"
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }
        // Well under the first 10 s step of the wait ladder.
        val outcome = withTimeout(8_000) { runCatching { SyncEngine(env).run(job) } }
        val said = outcome.exceptionOrNull()?.message ?: outcome.getOrNull()?.let { it.error + " " + it.notes } ?: ""
        assertTrue(said, said.contains("service_search_limit"))
        assertFalse(env.logs.toString(), env.logs.any { it.contains("is throttling") })
        assertTrue(env.logs.toString(), env.logs.any { it.contains("refuses searches for a while") })
    }
}

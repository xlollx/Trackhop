package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spotify can ask for a pause of hours on a first big sync (#34). The sync used to wait it out in
 * 30-minute rounds and looked stuck; now it stops at once and says until when.
 */
class LongPauseTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun aPauseOfHoursStopsTheSyncAndSaysUntilWhen() = runBlocking {
        src.lists["p"] = (1..20).map { Track(id = "s$it", title = "Song $it", artists = listOf("Band"), durationMs = 200_000) }.toMutableList()
        dst.lists["t"] = ArrayList()
        dst.searchError = "Spotify is asking to slow down (429, retry in 10800 s)"
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }
        // No waiting: well under the first 30-minute round.
        val outcome = withTimeout(20_000) { runCatching { SyncEngine(env).run(job) } }
        val said = outcome.exceptionOrNull()?.message ?: outcome.getOrNull()?.let { it.error + " " + it.notes } ?: ""
        assertTrue(said, said.contains("service_pause_until"))
        assertTrue(env.logs.toString(), env.logs.any { it.contains("asks for a pause of 10800s") })
    }
}

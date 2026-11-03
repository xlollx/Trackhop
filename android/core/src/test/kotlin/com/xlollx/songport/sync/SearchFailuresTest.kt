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
 * A target that refuses most searches, with songs remembered from earlier runs answered in between:
 * the failures used to pass as "not found", hundreds of them, without a word. Now the search stops,
 * says why, and nothing is counted as missing that was never really searched.
 */
class SearchFailuresTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun manyFailedSearchesStopTheSearchInsteadOfBecomingNotFound() = runBlocking {
        val tracks = (1..60).map { Track(id = "s$it", title = "Song $it", artists = listOf("Band"), durationMs = 200_000) }
        src.lists["p"] = tracks.toMutableList()
        dst.lists["t"] = ArrayList()
        // Every third song is remembered from an earlier run; the others' searches all fail.
        tracks.forEachIndexed { i, t ->
            if (i % 3 == 0) { dst.catalogue += t.copy(id = "d${t.id}"); env.store.putMatches(mapOf(FileStore.cacheKey("msrc", t.id, "mdst") to "d${t.id}")) }
            else dst.failingSearches += t.title
        }
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(job)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("note_search_interrupted") })
        assertTrue("not found: ${r.unmatched.size}", r.unmatched.size < 10)
        assertTrue(env.logs.toString(), env.logs.any { it.contains("search failed for") })
        assertTrue(env.logs.toString(), env.logs.any { it.contains("search stopped after") })
    }
}

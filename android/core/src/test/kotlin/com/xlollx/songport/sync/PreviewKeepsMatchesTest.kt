package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The preview of a new sync (its target not made yet) left half way: the songs it had already
 * found stay in the match cache, so the sync started after it does not search them again.
 */
class PreviewKeepsMatchesTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("psrc")
        // Leaving the preview: the third search is where the user goes back.
        dst = object : MemoryService("pdst") {
            var searches = 0
            override suspend fun search(ctx: Env, track: Track): List<Track> {
                if (++searches == 3) throw kotlinx.coroutines.CancellationException("left the preview")
                return super.search(ctx, track)
            }
        }
        Providers.testDoubles = listOf(src, dst)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun aPreviewLeftHalfWayKeepsWhatItFound() = runBlocking {
        src.lists["mix"] = mutableListOf(t("s1", "Alpha"), t("s2", "Beta"), t("s3", "Gamma"), t("s4", "Delta"))
        dst.catalogue += listOf(t("d1", "Alpha"), t("d2", "Beta"), t("d3", "Gamma"), t("d4", "Delta"))
        val job = SyncJob(id = "new", name = "Mix", source = PlaylistRef("psrc", "mix", "Mix"), target = PlaylistRef("pdst", null, "Mix"))
        env.store.upsertJob(job)
        runCatching { SyncEngine(env).plan(job, createTarget = false) }
        assertEquals("d1", env.store.cachedMatch("psrc", "s1", "pdst"))
        assertEquals("d2", env.store.cachedMatch("psrc", "s2", "pdst"))
        assertNull(env.store.cachedMatch("psrc", "s4", "pdst"))
    }
}

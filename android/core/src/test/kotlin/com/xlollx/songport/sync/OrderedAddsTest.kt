package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Liked songs saved one a second only for a job that keeps the source order; the others go in
 * batches at full speed (#34).
 */
class OrderedAddsTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        val songs = (1..5).map { Track(id = "s$it", title = "Song $it", artists = listOf("Band"), durationMs = 200_000) }
        src.lists["p"] = songs.toMutableList()
        dst.catalogue += songs.map { it.copy(id = "d" + it.id) }
        dst.lists[MusicProvider.LIKED_ID] = ArrayList()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun run(keepOrder: Boolean) = runBlocking {
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", MusicProvider.LIKED_ID, "Liked"), keepOrder = keepOrder)
            .also { env.store.upsertJob(it) }
        SyncEngine(env).run(job)
    }

    @Test fun keepingTheOrderAddsInOrder() {
        run(keepOrder = true)
        assertEquals(5, dst.lists[MusicProvider.LIKED_ID]!!.size)
        assertTrue(dst.addsInOrder.isNotEmpty() && dst.addsInOrder.all { it })
    }

    @Test fun otherwiseInOneBatch() {
        run(keepOrder = false)
        assertEquals(5, dst.lists[MusicProvider.LIKED_ID]!!.size)
        assertEquals(listOf(false), dst.addsInOrder)
    }
}

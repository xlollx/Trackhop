package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The search is told where the tracks go ([MusicProvider.searchFor]): Amazon Music looks for an album
 * among albums only for its saved albums, as a playlist there takes songs only.
 */
class SearchTargetTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: Targets

    /** A target that remembers which playlist each search was for. */
    class Targets : MemoryService("mdst") {
        val asked = ArrayList<String?>()
        override val supportsAlbums: Boolean get() = true
        override suspend fun searchFor(ctx: Env, track: Track, playlistId: String?): List<Track> {
            asked += playlistId
            return search(ctx, track)
        }
    }

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = Targets()
        Providers.testDoubles = listOf(src, dst)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun album(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), kind = Track.KIND_ALBUM)

    private fun job(id: String, source: String, target: String?) =
        SyncJob(id = id, name = id, source = PlaylistRef("msrc", source, source), target = PlaylistRef("mdst", target, target ?: "New"))
            .also { env.store.upsertJob(it) }

    @Test fun savedAlbumsIntoAPlaylistIntoSavedAlbumsAndIntoANewOne() = runBlocking {
        src.lists["a"] = mutableListOf(album("s1", "Alpha"))
        src.lists["b"] = mutableListOf(album("s2", "Beta"))
        src.lists["c"] = mutableListOf(album("s3", "Gamma"))
        dst.catalogue += listOf(album("d1", "Alpha"), album("d2", "Beta"), album("d3", "Gamma"))
        dst.lists["PL1"] = ArrayList()
        dst.lists[MusicProvider.ALBUMS_ID] = ArrayList()

        SyncEngine(env).run(job("into-playlist", "a", "PL1"))
        assertEquals(listOf<String?>("PL1"), dst.asked.distinct())

        dst.asked.clear()
        SyncEngine(env).run(job("into-albums", "b", MusicProvider.ALBUMS_ID))
        assertEquals(listOf<String?>(MusicProvider.ALBUMS_ID), dst.asked.distinct())
        assertEquals(listOf("d2"), dst.lists[MusicProvider.ALBUMS_ID]!!.map { it.id })

        // The preview of a playlist still to be made searches for no target yet.
        dst.asked.clear()
        SyncEngine(env).plan(job("preview", "c", null), createTarget = false)
        assertEquals(listOf<String?>(null), dst.asked.distinct())
    }
}

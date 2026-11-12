package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** Like Amazon Music: an album row is an album for the saved albums, its songs for a playlist. */
    class SongsOrAlbums : MemoryService("mdst") {
        val songs = ArrayList<Track>()
        val searches = ArrayList<String?>()
        override val supportsAlbums: Boolean get() = true
        override suspend fun searchFor(ctx: Env, track: Track, playlistId: String?): List<Track> {
            searches += playlistId
            val wanted = Matcher.normalizeTitle(track.title)
            val pool = if (playlistId == MusicProvider.ALBUMS_ID) catalogue else songs
            return pool.filter { Matcher.normalizeTitle(it.title) == wanted }
        }
    }

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

    @Test fun aSongMatchedForAPlaylistIsNeverTakenForTheSavedAlbumsNorTheOtherWayRound() = runBlocking {
        val amazon = SongsOrAlbums()
        Providers.testDoubles = listOf(src, amazon)
        src.lists["albums"] = mutableListOf(album("s1", "Alpha"))
        amazon.songs += Track(id = "song1", title = "Alpha", artists = listOf("Band"))
        amazon.catalogue += album("alb1", "Alpha")
        amazon.lists["PL1"] = ArrayList()
        amazon.lists[MusicProvider.ALBUMS_ID] = ArrayList()

        SyncEngine(env).run(job("to-playlist", "albums", "PL1"))
        assertEquals(listOf("song1"), amazon.lists["PL1"]!!.map { it.id })
        // The playlist's match is kept under the key it always had: older entries keep working for playlists.
        assertEquals("song1", env.store.data.matchCache[FileStore.cacheKey("msrc", "s1", "mdst")])

        // Into the saved albums the row is searched again, among albums: the song is not an album.
        amazon.searches.clear()
        SyncEngine(env).run(job("to-albums", "albums", MusicProvider.ALBUMS_ID))
        assertEquals(listOf<String?>(MusicProvider.ALBUMS_ID), amazon.searches.distinct())
        assertEquals(listOf("alb1"), amazon.lists[MusicProvider.ALBUMS_ID]!!.map { it.id })
        assertEquals("alb1", env.store.cachedMatch("msrc", "s1", FileStore.matchTarget("mdst", MusicProvider.ALBUMS_ID)))
        assertEquals("song1", env.store.cachedMatch("msrc", "s1", "mdst"))

        // And the album's match never reaches a playlist: a new playlist sync finds the cached song, not the album.
        amazon.searches.clear()
        amazon.lists["PL2"] = ArrayList()
        SyncEngine(env).run(job("to-playlist-2", "albums", "PL2"))
        assertEquals(listOf("song1"), amazon.lists["PL2"]!!.map { it.id })
        assertEquals(emptyList<String?>(), amazon.searches)
    }

    @Test fun anAlbumOnlyMatchedForTheSavedAlbumsIsSearchedAsASongForAPlaylist() = runBlocking {
        val amazon = SongsOrAlbums()
        Providers.testDoubles = listOf(src, amazon)
        src.lists["albums"] = mutableListOf(album("s2", "Beta"))
        amazon.songs += Track(id = "song2", title = "Beta", artists = listOf("Band"))
        amazon.catalogue += album("alb2", "Beta")
        amazon.lists[MusicProvider.ALBUMS_ID] = ArrayList()
        amazon.lists["PL3"] = ArrayList()
        SyncEngine(env).run(job("albums-first", "albums", MusicProvider.ALBUMS_ID))
        assertNull(env.store.cachedMatch("msrc", "s2", "mdst"))
        SyncEngine(env).run(job("playlist-after", "albums", "PL3"))
        assertEquals(listOf("song2"), amazon.lists["PL3"]!!.map { it.id })
    }

    @Test fun theKindStaysWithTheServiceFamily() {
        assertEquals("spotify", FileStore.matchTarget("spotify_bridge", "PL1").let(FileStore::family))
        assertEquals("spotify#album", FileStore.family(FileStore.matchTarget("spotify_bridge@2", MusicProvider.ALBUMS_ID)))
        assertEquals("amazon#artist", FileStore.family(FileStore.matchTarget("amazon", MusicProvider.ARTISTS_ID)))
        assertEquals("amazon", FileStore.matchTarget("amazon", MusicProvider.LIKED_ID))
        assertEquals("amazon", FileStore.matchTarget("amazon", null))
    }
}

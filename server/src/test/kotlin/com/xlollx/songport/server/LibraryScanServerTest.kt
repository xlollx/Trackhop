package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The library scan tells playlists apart by id, not by name. */
class LibraryScanServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Lib(val lists: Map<Playlist, List<Track>>, val liked: List<Track>) : MusicProvider {
        override val serviceId = "lib"
        override val displayName = "Lib"
        override val brandColor = 0xFF336699
        override val noteName = ""
        override val requiresAuth = false
        override val supportsLikedSongs = true
        override fun isConfigured(ctx: Env) = true
        override fun isConnected(ctx: Env) = true
        override fun accountName(ctx: Env) = "listener"
        override fun startAuth(ctx: Env) {}
        override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
        override fun disconnect(ctx: Env) {}
        override suspend fun playlists(ctx: Env): List<Playlist> = lists.keys.toList()
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
            if (playlistId == MusicProvider.LIKED_ID) liked else lists.entries.single { it.key.id == playlistId }.value.map { it.copy() }
        override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
        override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = Playlist(name, name, 0)
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
    }

    private fun song(n: String) = Track(id = n, title = n, artists = listOf("Band"), durationMs = 200_000)

    @Test fun twoPlaylistsOfOneNameAreTwoAndOneNamedLikedSongsIsAPlaylist() = runBlocking {
        val env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "https://songport.example.org")
        val p = Lib(
            mapOf(
                Playlist("m1", "Mix", 1) to listOf(song("Both")),
                Playlist("m2", "Mix", 1) to listOf(song("Both")),
                Playlist("l", LibraryScanServer.LIKED, 1) to listOf(song("Liked one")),
            ),
            liked = listOf(song("Liked one")),
        )
        val res = LibraryScanServer.run(env, p)
        assertEquals(listOf("Both"), res.repeats.map { it.track.title })
        assertEquals(listOf("Mix", "Mix"), res.repeats.single().playlists)
        // In the playlist called Liked songs: not a liked song in no playlist.
        assertEquals(emptyList<Track>(), res.orphanLiked)
    }

    @Test fun aPlaylistNotReadMakesTheLikedInNoPlaylistUnsure() = runBlocking {
        val env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "https://songport.example.org")
        val read = Lib(mapOf(Playlist("a", "A", 1) to listOf(song("Other"))), liked = listOf(song("Liked one")))
        assertEquals(false, LibraryScanServer.run(env, read).playlistsUnread)
        val broken = object : MusicProvider by read {
            override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
                if (playlistId == "a") throw IllegalStateException("down") else read.tracks(ctx, playlistId)
        }
        val res = LibraryScanServer.run(env, broken)
        assertEquals(true, res.playlistsUnread)
        assertEquals(listOf("Liked one"), res.orphanLiked.map { it.title })
    }
}

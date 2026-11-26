package com.xlollx.songport.providers

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Reconnect" and the keep-alive's words: what renews, what signs out, what waits for the network. */
class KeepAliveTest {
    private class Server(var connected: Boolean = true, var fail: Exception? = null) : MusicProvider {
        var listed = 0
        override val serviceId = "ka_server"
        override val displayName = "Server"
        override val brandColor = 0xFF000000
        override val noteName = ""
        override fun isConfigured(ctx: Env) = true
        override fun isConnected(ctx: Env) = connected
        override fun accountName(ctx: Env) = null
        override fun startAuth(ctx: Env) {}
        override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
        override fun disconnect(ctx: Env) { connected = false }
        override suspend fun playlists(ctx: Env): List<Playlist> { listed++; fail?.let { throw it }; return emptyList() }
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = emptyList()
        override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
        override suspend fun createPlaylist(ctx: Env, name: String, description: String) = Playlist(name, name, 0)
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
    }

    private val env = ContractEnv()

    @Test fun aConnectorWithoutARenewalOfItsOwnMakesOneSignedInCall() = runBlocking {
        val s = Server()
        assertEquals(KeepAlive.RENEWED, KeepAlive.renewNow(env, s))
        assertEquals(1, s.listed)
        s.fail = com.xlollx.songport.model.ProviderException("Server API 401: unauthorized")
        val refused = KeepAlive.renewNow(env, s)
        assertEquals(KeepAlive.State.FAILED, refused.state)
        assertTrue(KeepAlive.needsSignIn(refused))
        s.connected = false
        assertEquals(KeepAlive.NOT_SIGNED_IN, KeepAlive.renewNow(env, s))
    }

    @Test fun reconnectSignsOutOnlyWhenTheRenewalCannotWork() {
        assertFalse(KeepAlive.needsSignIn(KeepAlive.RENEWED))
        assertTrue(KeepAlive.needsSignIn(KeepAlive.EXPIRED))
        assertTrue(KeepAlive.needsSignIn(KeepAlive.NOT_SIGNED_IN))
        assertTrue(KeepAlive.needsSignIn(KeepAlive.failed(IllegalStateException("the page changed"))))
        // Offline: nothing is signed out.
        assertFalse(KeepAlive.needsSignIn(KeepAlive.failed(java.net.UnknownHostException("api.example.org"))))
    }

    @Test fun aWebSessionGoneIsExpiredAndAnOutageIsNot() = runBlocking {
        val s = Server(fail = com.xlollx.songport.model.ProviderException("Qobuz: the Qobuz session has ended: sign in again"))
        assertEquals(KeepAlive.EXPIRED, KeepAlive.probe(env, s) { s.playlists(env) })
        s.fail = java.net.SocketTimeoutException("timeout")
        assertEquals(KeepAlive.State.FAILED, KeepAlive.probe(env, s) { s.playlists(env) }.state)
        listOf(
            "the cookies have expired (open.spotify.com answers as a signed-out visitor): paste fresh ones",
            "Deezer (web): the web session has expired: paste fresh cookies",
            "Amazon Music served the player to a signed-out visitor",
        ).forEach { assertTrue(it, KeepAlive.sessionGone(it)) }
        assertFalse(KeepAlive.sessionGone("HTTP 503"))
    }
}

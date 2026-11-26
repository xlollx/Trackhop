package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.notify.NotifyHistory
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.KeepAlive
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The server's twice-daily keep-alive ([SessionsKeepAlive]), as the app's: each signed-in account
 * once, one after the other; a session the service ended told once ("sign in again") until it works
 * again; one log line per account, never a token.
 */
class SessionsKeepAliveTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: ServerEnv

    private class Account(override val serviceId: String, val connected: Boolean, var outcome: KeepAlive?) : MusicProvider {
        var asked = 0
        override val displayName = serviceId.uppercase()
        override val brandColor = 0xFF336699
        override val noteName = ""
        override fun isConfigured(ctx: Env) = true
        override fun isConnected(ctx: Env) = connected
        override fun accountName(ctx: Env) = "listener"
        override fun startAuth(ctx: Env) {}
        override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
        var disconnected = 0
        override fun disconnect(ctx: Env) { disconnected++ }
        override suspend fun keepAlive(ctx: Env): KeepAlive? { asked++; return outcome }
        override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = emptyList()
        override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
        override suspend fun createPlaylist(ctx: Env, name: String, description: String) = Playlist(name, name, 0)
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
    }

    private val renewed = Account("ka_renewed", true, KeepAlive.RENEWED)
    private val ended = Account("ka_ended", true, KeepAlive.EXPIRED)
    private val signedOut = Account("ka_out", false, KeepAlive.NOT_SIGNED_IN)
    private val down = Account("ka_down", true, KeepAlive(KeepAlive.State.FAILED, "timeout"))
    private val nothing = Account("ka_link", true, null)

    @Before fun start() {
        Log.reset()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
        Providers.testDoubles = listOf(renewed, ended, signedOut, down, nothing)
        env.store.updateSettings { it.copy(connectors = listOf("ka_renewed", "ka_ended", "ka_out", "ka_down", "ka_link", "ka_renewed")) }
    }

    @After fun stop() { Providers.testDoubles = emptyList() }

    @Test fun eachSignedInAccountOnceOneAfterTheOther() = runBlocking {
        var pauses = 0
        val told = ArrayList<String>()
        val lines = SessionsKeepAlive.run(env, pause = { pauses++ }, tell = { told += it.id })
        assertEquals(
            listOf("ka_renewed: renewed", "ka_ended: the service ended the session: sign in again", "ka_down: not renewed this time (timeout)"),
            lines,
        )
        // An account not signed in is not asked; one with nothing to keep is asked and says nothing.
        assertEquals(0, signedOut.asked)
        assertEquals(1, renewed.asked)
        assertEquals(1, nothing.asked)
        assertEquals(3, pauses)
        assertEquals(listOf("ka_ended"), told)
        assertTrue(Log.recent().toString(), Log.recent().any { it.contains("[keepalive] ka_ended: the service ended the session") })
    }

    @Test fun aSessionGoneIsToldOnceUntilItWorksAgain() = runBlocking {
        val told = ArrayList<String>()
        repeat(2) { SessionsKeepAlive.run(env, pause = {}, tell = { told += it.id }) }
        assertEquals(listOf("ka_ended"), told)
        ended.outcome = KeepAlive.RENEWED
        SessionsKeepAlive.run(env, pause = {}, tell = { told += it.id })
        ended.outcome = KeepAlive.EXPIRED
        SessionsKeepAlive.run(env, pause = {}, tell = { told += it.id })
        assertEquals(listOf("ka_ended", "ka_ended"), told)
    }

    @Test fun theNoticeGoesToTheHistoryWithoutChannels() = runBlocking {
        SessionsKeepAlive.run(env, pause = {})
        val h = NotifyHistory.of(env)
        h.settle()
        val entries = h.entries.value
        assertEquals(1, entries.count { it.kind == "signin" })
    }

    @Test fun dueTwiceADay() {
        val now = System.currentTimeMillis()
        assertTrue(SessionsKeepAlive.isDue(env, now))
        runBlocking { SessionsKeepAlive.run(env, accounts = emptyList()) }
        assertFalse(SessionsKeepAlive.isDue(env, System.currentTimeMillis() + 60_000))
        assertTrue(SessionsKeepAlive.isDue(env, System.currentTimeMillis() + SessionsKeepAlive.EVERY_MS))
    }

    @Test fun reconnectRenewsOrSignsOutForANewSignIn() = runBlocking {
        val ok = SessionsKeepAlive.reconnect(env, renewed)
        assertTrue(ok.renewed); assertFalse(ok.signedOut)
        assertEquals("KA_RENEWED: session renewed, nothing else to do.", ok.message)
        assertEquals(0, renewed.disconnected)
        val gone = SessionsKeepAlive.reconnect(env, ended)
        assertFalse(gone.renewed); assertTrue(gone.signedOut)
        assertEquals(1, ended.disconnected)
        // Offline: nothing is signed out.
        val offline = Account("ka_offline", true, KeepAlive.failed(java.net.UnknownHostException("api.example.org")))
        val r = SessionsKeepAlive.reconnect(env, offline)
        assertFalse(r.renewed); assertFalse(r.signedOut)
        assertEquals(0, offline.disconnected)
        // A server error (Plex answering 500): the saved credentials stay.
        val plex = Account("ka_plex", true, KeepAlive.failed(com.xlollx.songport.model.ProviderException("Plex API 500: Internal Server Error")))
        val p5 = SessionsKeepAlive.reconnect(env, plex)
        assertFalse(p5.renewed); assertFalse(p5.signedOut)
        assertEquals(0, plex.disconnected)
    }
}

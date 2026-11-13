package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.NetError
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * A server that loses its internet: the run stops without touching the playlist it could not compare,
 * the report and the pages say it in plain words ("The server has no internet connection"), and the
 * notice goes once a run reaches the services again. The page in the browser watches the server too.
 */
class OfflineServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var runner: Runner
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    /** A music service on the internet, out of reach while [down]. */
    private class Remote : MusicProvider {
        @Volatile var down = true
        override val serviceId = "remote"
        override val displayName = "Remote"
        override val brandColor = 0xFF336699
        override val noteName = ""
        override val requiresAuth = false
        override fun isConfigured(ctx: Env) = true
        override fun isConnected(ctx: Env) = true
        override fun accountName(ctx: Env) = "listener"
        override fun startAuth(ctx: Env) {}
        override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
        override fun disconnect(ctx: Env) {}
        private fun line() { if (down) throw UnknownHostException("api.remote.example") }
        override suspend fun playlists(ctx: Env): List<Playlist> { line(); return listOf(Playlist("mix", "Mix", 1)) }
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
            line(); return listOf(Track(id = "r1", title = "Kept", artists = listOf("Band")))
        }
        override suspend fun search(ctx: Env, track: Track): List<Track> { line(); return emptyList() }
        override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist { line(); return Playlist(name, name, 0) }
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) { line() }
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) { line() }
    }

    private val remote = Remote()

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        Providers.testDoubles = listOf(remote)
        // The server knows it is offline: no waiting out the engine's retries in a test.
        NetError.networkUp = { if (remote.down) false else null }
        env = ServerEnv(data, Strings.load(null), "https://songport.example.org")
        runner = Runner(env)
        server = Web(env, runner, Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        File(data, "csv").mkdirs()
        // The target holds three songs the source (out of reach) cannot be compared with: a mirror must keep them.
        File(data, "csv/Copy.csv").writeText("title,artist\nOne,Band\nTwo,Band\nThree,Band\n")
    }

    @After fun stop() {
        server.stop(0)
        Providers.testDoubles = emptyList()
        NetError.networkUp = { null }
    }

    private fun get(path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun mirror() = SyncJob(
        "m", "Mirror", PlaylistRef("remote", "mix", "Mix"), PlaylistRef("csv", "Copy", "Copy"), mirrorRemovals = true, followsChanges = true,
    ).also { env.store.upsertJob(it) }

    private fun runAndWait(job: SyncJob) {
        val before = env.store.job(job.id)?.lastReportId
        runner.start(job)
        val until = System.currentTimeMillis() + 20_000
        while ((env.store.job(job.id)?.lastReportId == before || runner.isRunning(job.id)) && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    @Test fun theReportSaysTheServerHasNoInternetAndNothingIsRemoved() = runBlocking {
        val r = SyncEngine(env).run(mirror())
        assertTrue(r.offline)
        assertEquals(env.text("report_stopped_offline_server"), r.error)
        assertTrue(r.error!!, r.error!!.contains("server has no internet connection"))
        assertEquals(0, r.removed)
        assertEquals("title,artist\nOne,Band\nTwo,Band\nThree,Band\n", File(data, "csv/Copy.csv").readText())
    }

    @Test fun thePagesSayItCalmlyAndTheNoticeGoesWhenTheServicesAnswerAgain() {
        val job = mirror()
        runAndWait(job)
        val page = get("/").body()
        assertTrue(page, page.contains("class=\"net-note\""))
        assertTrue(page.contains(Html.h(env.text("net_offline_server"))))
        // The card's line: the plain words, not in the error's red, and no exception's name.
        assertTrue(page.contains("<span class=\"net\">"))
        assertFalse(page.contains("UnknownHostException"))
        assertTrue(get("/syncs/m").body().contains("<p class=\"net\">"))
        assertEquals(3, File(data, "csv/Copy.csv").readLines().size - 1)

        remote.down = false
        runAndWait(job)
        assertTrue(env.store.report(env.store.job("m")!!.lastReportId!!)!!.ok)
        assertFalse(get("/").body().contains("class=\"net-note\""))
    }

    @Test fun thePageWatchesTheServerAndRefreshesOnlyWhenItAnswers() {
        val page = get("/").body()
        assertTrue(page.contains("id=\"link-note\""))
        assertTrue(page.contains("fetch('/health'"))
        // Without scripts the plain refresh still works; with them the page asks the server first.
        val refreshing = Html.page("Running", "", "test", refreshSeconds = 3)
        assertTrue(refreshing.contains("<noscript><meta http-equiv=\"refresh\" content=\"3\"></noscript>"))
        assertTrue(refreshing.contains("refresh=3"))
    }
}

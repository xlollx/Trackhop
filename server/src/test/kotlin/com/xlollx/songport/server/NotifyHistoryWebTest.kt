package com.xlollx.songport.server

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.notify.NotifTarget
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.notify.NotifyHistory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** The Notifications page: what was sent to the channels, with where each goes; its API and command. */
class NotifyHistoryWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var mock: MockWebServer
    private lateinit var env: ServerEnv
    private lateinit var url: String

    @Before fun start() {
        mock = MockWebServer().apply { start() }
        url = mock.url("/").toString().trimEnd('/')
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
    }

    @After fun stop() { mock.shutdown() }

    private fun channel(name: String) = Notify.build(Notify.newId(), Notify.Kind.GOTIFY, name, true, Notify.Event.entries.toSet(), mapOf("server" to url, "token" to "tok-$name"), null).getOrThrow()
        .also { Notify.put(env, it) }

    @Test fun whatWasSentIsListedWithItsChannelsAndWhereItLeads() {
        installServerConnectors()
        val home = channel("Home")
        val work = channel("Work")
        val history = NotifyHistory.of(env)
        // One message, two channels: one took it, one refused it.
        mock.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        mock.enqueue(MockResponse().setResponseCode(401).setBody("""{"errorDescription":"bad token"}"""))
        val sent = Notify.sendAll(env, listOf(home, work), Notify.Message("40 tracks no longer play", "Tools › Unplayable tracks"), "unplayable", NotifTarget.Unplayable(null))
        assertEquals(1, sent.count { it.error == null })
        history.settle()
        val e = history.entries.value.single()
        assertEquals("40 tracks no longer play", e.title)
        assertEquals(listOf("Home", "Work"), e.sent.map { it.channel })
        assertTrue(e.sent[1].error!!.contains("401"))
        assertEquals("/tools/unplayable", NotifyPages.url(e.target))

        // A run's message: kept once every channel answered, leading to its review.
        mock.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        mock.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val job = SyncJob("j1", "Liked", PlaylistRef("spotify", "p", "Liked"), PlaylistRef("deezer", "q", "Liked"))
        Notify.afterRun(env, job, SyncReport("r1", "j1", "Liked", 0, 0, added = 3, unmatchedTracks = listOf(Track("t", "Lost song"))))
        val deadline = System.currentTimeMillis() + 10_000
        while (history.entries.value.size < 2 && System.currentTimeMillis() < deadline) { Thread.sleep(50); history.settle() }
        assertEquals(2, history.entries.value.size)
        assertEquals("/syncs/j1/review/r1", NotifyPages.url(history.entries.value.first { it.kind == "review" }.target))

        val server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        val base = "http://127.0.0.1:${server.address.port}"
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
        fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString())
        fun post(body: String) = client.send(
            HttpRequest.newBuilder(URI("$base/notifications")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        try {
            val page = get("/notifications").body()
            assertTrue(page, page.contains("40 tracks no longer play") && page.contains("href=\"/tools/unplayable\"") && page.contains("href=\"/syncs/j1/review/r1\""))
            assertTrue(page, page.contains("Home ✓") && page.contains("✗"))
            assertTrue(page, page.contains("href=\"/notifications\" class=\"on\""))
            assertFalse("no channel's token on the page", page.contains("tok-Home"))
            assertEquals(303, post("action=read&id=${e.id}").statusCode())
            history.settle()
            assertEquals(1, NotifyHistory.unread(history.entries.value))

            // The command line, through the API, as on the server's own computer.
            LocalKey.write(env.dataDir, server.address.port)
            fun cli(vararg args: String): Pair<Int, String> {
                val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
                val code = Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { env.dataDir }
                return code to out.toString("UTF-8") + err.toString("UTF-8")
            }
            val (code, text) = cli("notifications")
            assertEquals(text, 0, code)
            assertTrue(text, text.contains("40 tracks no longer play") && text.contains("Work (failed: HTTP 401") && text.contains("Open: /tools/unplayable") && text.contains("1 unread"))
            val json = cli("--json", "notifications").second
            assertTrue(json, json.contains("\"url\": \"/syncs/j1/review/r1\"") && json.contains("\"unread\": 1"))
            // GET /api/v1/notifications is still the channels.
            assertTrue(cli("notify").second.contains("Home"))
            // A body that is not JSON is refused, not taken as "mark all read".
            val bad = client.send(
                HttpRequest.newBuilder(URI("$base/api/v1/notifications/history/read")).header("Authorization", "Bearer ${Api.newToken(env)}")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"id\":")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(bad.body(), 400, bad.statusCode())
            history.settle()
            assertEquals(1, NotifyHistory.unread(history.entries.value))
            assertEquals(0, cli("notifications", "read").first)
            history.settle()
            assertEquals(0, NotifyHistory.unread(history.entries.value))
            assertEquals(0, cli("notifications", "clear").first)
            history.settle()
            assertTrue(history.entries.value.isEmpty())
            assertEquals(2, Notify.channels(env).size)
            assertTrue(get("/notifications").body().contains("Nothing yet"))
        } finally {
            server.stop(0)
            LocalKey.reset()
        }
    }
}

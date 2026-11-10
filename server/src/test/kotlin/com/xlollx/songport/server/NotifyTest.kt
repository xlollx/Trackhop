package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Notifications on the server: the settings page, the API and the command line (the channels themselves: core's NotifyTest). */
class NotifyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var mock: MockWebServer
    private lateinit var env: ServerEnv
    private lateinit var url: String

    @Before fun start() {
        mock = MockWebServer().apply { start() }
        url = mock.url("/").toString().trimEnd('/')
        Notify.telegramApi = url
        Notify.pushoverApi = url
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
    }

    @After fun stop() {
        mock.shutdown()
        Notify.telegramApi = "https://api.telegram.org"
        Notify.pushoverApi = "https://api.pushover.net"
    }

    private fun body(r: RecordedRequest) = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    private fun kotlinx.serialization.json.JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun thePageTheApiAndTheCommandLine() {
        installServerConnectors()
        val server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        val base = "http://127.0.0.1:${server.address.port}"
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
        fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofString())
        fun post(body: String) = client.send(
            HttpRequest.newBuilder(URI("$base/settings/notifications")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        try {
            assertTrue(get("/settings").body().contains("/settings/notifications"))
            assertTrue(get("/settings/notifications?add=gotify").body().contains("name=\"f_token\""))
            val saved = post("action=save&kind=gotify&id=&name=Home&f_server=${enc(url)}&f_token=GotifySecret999&event=failed&event=review&enabled=on")
            assertEquals(303, saved.statusCode())
            val c = Notify.channels(env).single()
            assertEquals(setOf(Notify.Event.FAILED, Notify.Event.REVIEW), c.events)
            val page = get("/settings/notifications").body()
            assertTrue(page, page.contains("Home") && page.contains("••••t999"))
            assertFalse(page, page.contains("GotifySecret999"))
            assertFalse(get("/settings/notifications?edit=${c.id}").body().contains("GotifySecret999"))
            // Send test: the answer on the page.
            mock.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val ok = post("action=test&id=${c.id}").headers().firstValue("Location").orElse("")
            assertTrue(ok, ok.contains("ok="))
            assertEquals("Trackhop: test", body(mock.takeRequest(5, TimeUnit.SECONDS)!!).s("title"))
            mock.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","errorDescription":"you need to provide a valid access token"}"""))
            assertTrue(post("action=test&id=${c.id}").headers().firstValue("Location").orElse("").contains("err="))
            // The command line, through the API, as on the server's own computer.
            LocalKey.write(env.dataDir, server.address.port)
            fun cli(vararg args: String): Pair<Int, String> {
                val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
                val code = Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { env.dataDir }
                return code to out.toString("UTF-8") + err.toString("UTF-8")
            }
            val (listed, text) = cli("notify")
            assertEquals(0, listed)
            assertTrue(text, text.contains("Home  (${c.id})  Gotify  [on]") && text.contains("failed, review"))
            val json = cli("--json", "notify").second
            assertTrue(json, json.contains("\"token\": \"••••t999\"") && !json.contains("GotifySecret999"))
            mock.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val (tested, said) = cli("notify", "test", "home")
            assertEquals(said, 0, tested)
            assertTrue(said, said.contains("Sent a test to Home"))
            mock.enqueue(MockResponse().setResponseCode(500))
            val (failed, why) = cli("notify", "test", c.id)
            assertEquals(why, 1, failed)
            assertTrue(why, why.contains("HTTP 500"))
            assertEquals(2, cli("notify", "test").first)
            // Removed.
            assertEquals(303, post("action=remove&id=${c.id}").statusCode())
            assertTrue(Notify.channels(env).isEmpty())
        } finally {
            server.stop(0)
            LocalKey.reset()
        }
    }
}

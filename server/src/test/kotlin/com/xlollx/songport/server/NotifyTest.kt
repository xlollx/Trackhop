package com.xlollx.songport.server

import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Notifications: what each channel sends, which runs they hear of, secrets masked and kept out of backups. */
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

    private val msg = Notify.Message("Trackhop: Città", "+3 −1")

    private fun channel(kind: Notify.Kind, vararg values: Pair<String, String>, events: Set<Notify.Event> = Notify.Event.entries.toSet()) =
        Notify.Channel(Notify.newId(), kind, kind.label, true, events, values.toMap())

    /** Sends [m] through [c] to the stand-in, which answers [code]; returns what it got. */
    private fun sent(c: Notify.Channel, code: Int = 200, m: Notify.Message = msg): RecordedRequest {
        mock.enqueue(MockResponse().setResponseCode(code).apply { if (code != 204) setBody("{}") })
        assertNull(Notify.send(c, m))
        return mock.takeRequest(5, TimeUnit.SECONDS)!!
    }

    private fun body(r: RecordedRequest) = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    private fun kotlinx.serialization.json.JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun telegramPostsToTheBotsSendMessage() {
        val r = sent(channel(Notify.Kind.TELEGRAM, "token" to "123456:SECRETbot", "chat" to "42"))
        assertEquals("POST", r.method)
        assertEquals("/bot123456:SECRETbot/sendMessage", r.path)
        val b = body(r)
        assertEquals("42", b.s("chat_id"))
        assertEquals("Trackhop: Città\n+3 −1", b.s("text"))
    }

    @Test fun ntfyPublishesJsonWithItsTopicAndToken() {
        val r = sent(channel(Notify.Kind.NTFY, "server" to url, "topic" to "th-topic", "token" to "tk_secret"))
        assertEquals("POST", r.method)
        assertEquals("/", r.path)
        assertEquals("Bearer tk_secret", r.getHeader("Authorization"))
        val b = body(r)
        assertEquals("th-topic", b.s("topic")); assertEquals("Trackhop: Città", b.s("title")); assertEquals("+3 −1", b.s("message"))
        // No token: no Authorization header.
        assertNull(sent(channel(Notify.Kind.NTFY, "server" to "$url/", "topic" to "t")).getHeader("Authorization"))
    }

    @Test fun gotifyPostsToMessageWithItsAppToken() {
        val r = sent(channel(Notify.Kind.GOTIFY, "server" to "$url/gotify/", "token" to "AppTok"))
        assertEquals("POST", r.method)
        assertEquals("/gotify/message", r.path)
        assertEquals("AppTok", r.getHeader("X-Gotify-Key"))
        val b = body(r)
        assertEquals("Trackhop: Città", b.s("title")); assertEquals("+3 −1", b.s("message"))
    }

    @Test fun discordAndSlackPostToTheirWebhook() {
        val d = sent(channel(Notify.Kind.DISCORD, "url" to "$url/api/webhooks/1/abc"), code = 204)
        assertEquals("POST", d.method)
        assertEquals("/api/webhooks/1/abc", d.path)
        val db = body(d)
        assertEquals("**Trackhop: Città**\n+3 −1", db.s("content"))
        assertTrue(db["allowed_mentions"]!!.jsonObject["parse"]!!.jsonArray.isEmpty())
        val s = sent(channel(Notify.Kind.SLACK, "url" to "$url/services/T/B/x"), m = Notify.Message("Trackhop: <a&b>", "+1 −0"))
        assertEquals("/services/T/B/x", s.path)
        assertEquals("*Trackhop: &lt;a&amp;b&gt;*\n+1 −0", body(s).s("text"))
    }

    @Test fun matrixPutsAnEventInTheRoom() {
        val c = channel(Notify.Kind.MATRIX, "server" to url, "token" to "syt_secret", "room" to "!room:example.org")
        val r = sent(c)
        assertEquals("PUT", r.method)
        assertTrue(r.path, r.path!!.startsWith("/_matrix/client/v3/rooms/!room:example.org/send/m.room.message/trackhop"))
        assertEquals("Bearer syt_secret", r.getHeader("Authorization"))
        val b = body(r)
        assertEquals("m.text", b.s("msgtype")); assertEquals("Trackhop: Città\n+3 −1", b.s("body"))
        // Each message its own transaction id, or the homeserver drops the second as a repeat.
        assertFalse(r.path == sent(c).path)
    }

    @Test fun pushoverPostsAForm() {
        val r = sent(channel(Notify.Kind.PUSHOVER, "token" to "apptoken", "user" to "userkey"))
        assertEquals("POST", r.method)
        assertEquals("/1/messages.json", r.path)
        val form = r.body.readUtf8().split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("apptoken", form["token"]); assertEquals("userkey", form["user"])
        assertEquals("Trackhop: Città", form["title"]); assertEquals("+3 −1", form["message"])
    }

    @Test fun aRefusalSaysWhyWithoutTheSecret() {
        mock.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"description":"Unauthorized: bot 999:TOKENTOKEN"}"""))
        val error = Notify.send(channel(Notify.Kind.TELEGRAM, "token" to "999:TOKENTOKEN", "chat" to "1"), msg)!!
        assertTrue(error, error.startsWith("HTTP 401: Unauthorized"))
        assertFalse(error, error.contains("TOKENTOKEN"))
        // A channel that lacks its token is not tried.
        assertEquals("Bot token is missing.", Notify.send(channel(Notify.Kind.TELEGRAM, "chat" to "1"), msg))
    }

    private fun report(added: Int = 0, removed: Int = 0, error: String? = null, review: Int = 0, heldBack: Int = 0) = SyncReport(
        "r1", "j1", "Road trip", 0, 0, added = added, removed = removed, error = error, heldBack = heldBack,
        reviewTracks = List(review) { MatchReview(Track("s$it", "Song"), Track("t$it", "Song"), 0.7) },
    )

    @Test fun eachChannelHearsOnlyOfTheEventsItPicked() {
        assertNull("nothing happened", Notify.message("Road trip", report(), Notify.Event.entries.toSet()))
        assertEquals(Notify.Message("Trackhop: Road trip", "+3 −1"), Notify.message("Road trip", report(added = 3, removed = 1), Notify.Event.entries.toSet()))
        assertNull(Notify.message("Road trip", report(added = 3), setOf(Notify.Event.FAILED)))
        val failed = Notify.message("Road trip", report(error = "Sign-in expired at https://accounts.example.org/x?token=abc\nmore"), setOf(Notify.Event.FAILED, Notify.Event.CHANGES))!!
        assertEquals("Failed: Sign-in expired at …", failed.text)
        val all = Notify.message("Road trip", report(added = 1, review = 2, heldBack = 5), Notify.Event.entries.toSet())!!
        assertEquals("+1 −0\n2 tracks to review\n5 removals wait for your confirmation", all.text)
        assertEquals("1 track to review", Notify.message("x", report(review = 1, added = 4), setOf(Notify.Event.REVIEW))!!.text)
        // After a run: only the enabled channels whose events happened are called.
        val wants = channel(Notify.Kind.DISCORD, "url" to "$url/wants", events = setOf(Notify.Event.CHANGES))
        val other = channel(Notify.Kind.DISCORD, "url" to "$url/other", events = setOf(Notify.Event.FAILED))
        val off = channel(Notify.Kind.DISCORD, "url" to "$url/off").copy(enabled = false)
        listOf(wants, other, off).forEach { Notify.put(env, it) }
        mock.enqueue(MockResponse().setResponseCode(204))
        Notify.afterRun(env, SyncJob("j1", "Road trip", PlaylistRef("csv", "a", "a"), PlaylistRef("csv", null, "b")), report(added = 2))
        assertEquals("/wants", mock.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertNull(mock.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test fun secretsAreMaskedAndKeptOnEdit() {
        assertEquals("", Notify.mask(""))
        assertEquals("••••", Notify.mask("short"))
        assertEquals("••••wxyz", Notify.mask("abcdefghijklmnopqrstuvwxyz"))
        val c = Notify.build("c1", Notify.Kind.GOTIFY, "Home", true, setOf(Notify.Event.FAILED), mapOf("server" to url, "token" to "GotifyAppToken123"), null).getOrThrow()
        Notify.put(env, c)
        val shown = Notify.shown(c).toString()
        assertFalse(shown, shown.contains("GotifyAppToken123"))
        assertTrue(shown, shown.contains("••••n123") && shown.contains(url))
        // An edit that leaves the secret empty keeps it; a bad address or a missing field is refused.
        val edited = Notify.build("c1", Notify.Kind.GOTIFY, "Home 2", true, emptySet(), mapOf("server" to url, "token" to ""), c).getOrThrow()
        assertEquals("GotifyAppToken123", edited.value("token"))
        assertTrue(Notify.build("c1", Notify.Kind.GOTIFY, "x", true, emptySet(), mapOf("server" to "ftp://x", "token" to "t"), null).isFailure)
        assertTrue(Notify.build("c1", Notify.Kind.GOTIFY, "x", true, emptySet(), mapOf("server" to url), null).isFailure)
        // Stored as given, readable again.
        assertEquals(c, Notify.channel(env, "c1"))
    }

    @Test fun backupsCarryTheChannelsWithoutTheirSecrets() {
        Notify.put(env, Notify.Channel("n1", Notify.Kind.NTFY, "Phone", true, setOf(Notify.Event.FAILED), mapOf("server" to "https://ntfy.sh", "topic" to "th-x", "token" to "tk_verysecret")))
        Notify.put(env, Notify.Channel("t1", Notify.Kind.TELEGRAM, "Chat", true, setOf(Notify.Event.CHANGES), mapOf("token" to "1:BOTSECRET", "chat" to "7")))
        val text = Notify.intoBackup(env, ConfigBackup.export(env, "test"))
        assertFalse(text, text.contains("tk_verysecret") || text.contains("BOTSECRET"))
        assertTrue(text, text.contains("th-x"))
        // The engine (and the app) still read it as before.
        ConfigBackup.read(text)
        val incoming = Notify.inBackup(text)
        assertEquals(listOf("n1", "t1"), incoming.map { it.id })
        // Into another Trackhop: the channels come, off where a secret is needed again.
        val env2 = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://127.0.0.1")
        assertEquals(2, Notify.restore(env2, incoming))
        val n = Notify.channel(env2, "n1")!!; val t = Notify.channel(env2, "t1")!!
        assertTrue("the token of ntfy is optional", n.enabled)
        assertEquals("th-x", n.value("topic")); assertEquals(setOf(Notify.Event.FAILED), n.events)
        assertFalse(t.enabled); assertEquals("Bot token", t.missing().single().label)
        // Into the same one: the secrets it has stay.
        Notify.restore(env, incoming)
        assertEquals("1:BOTSECRET", Notify.channel(env, "t1")!!.value("token"))
        assertEquals("tk_verysecret", Notify.channel(env, "n1")!!.value("token"))
        // Without channels the file is the engine's own, untouched.
        val plain = ConfigBackup.export(env2, "x", now = 1)
        Notify.channels(env2).forEach { Notify.remove(env2, it.id) }
        assertEquals(plain, Notify.intoBackup(env2, plain))
    }

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

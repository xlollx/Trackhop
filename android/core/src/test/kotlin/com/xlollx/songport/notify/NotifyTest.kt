package com.xlollx.songport.notify

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.NewReleasesProvider
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
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Notifications, shared by the server and the app: the request each channel sends, which runs they
 * hear of, secrets masked, kept apart in the app and out of backups.
 */
/** A made-up Telegram bot token, built at run time so secret scanners never see one in the source. */
private val FAKE_BOT = "1".repeat(9) + ":" + "Fake" + "x".repeat(31)

class NotifyTest {
    private lateinit var mock: MockWebServer
    private lateinit var env: ContractEnv
    private lateinit var url: String

    @Before fun start() {
        mock = MockWebServer().apply { start() }
        url = mock.url("/").toString().trimEnd('/')
        Notify.telegramApi = url
        Notify.pushoverApi = url
        env = ContractEnv()
    }

    @After fun stop() {
        mock.shutdown()
        Notify.telegramApi = "https://api.telegram.org"
        Notify.pushoverApi = "https://api.pushover.net"
        Notify.secretsInVault = false
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

    @Test fun aRedirectIsNotFollowedWithTheKey() {
        val other = MockWebServer().apply { start() }
        try {
            mock.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/stolen").toString()))
            val error = Notify.send(channel(Notify.Kind.GOTIFY, "server" to url, "token" to "AppTok"), msg)
            assertEquals("HTTP 307", error)
            assertEquals(1, mock.requestCount)
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
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
        // The ntfy topic is a secret too: whoever knows it on ntfy.sh reads the messages.
        assertFalse(text, text.contains("tk_verysecret") || text.contains("BOTSECRET") || text.contains("th-x"))
        assertTrue(text, text.contains("https://ntfy.sh"))
        // The engine (and the app) still read it as before.
        ConfigBackup.read(text)
        val incoming = Notify.inBackup(text)
        assertEquals(listOf("n1", "t1"), incoming.map { it.id })
        // Into another Trackhop: the channels come, off where a secret is needed again.
        val env2 = ContractEnv()
        assertEquals(2, Notify.restore(env2, incoming))
        val n = Notify.channel(env2, "n1")!!; val t = Notify.channel(env2, "t1")!!
        assertFalse("a channel that came with a file waits to be turned on", n.enabled)
        assertEquals("Topic", n.missing().single().label); assertEquals(setOf(Notify.Event.FAILED), n.events)
        assertFalse(t.enabled); assertEquals("Bot token", t.missing().single().label)
        // Into the same one: the secrets it has stay.
        Notify.restore(env, incoming)
        assertEquals("1:BOTSECRET", Notify.channel(env, "t1")!!.value("token"))
        assertEquals("tk_verysecret", Notify.channel(env, "n1")!!.value("token"))
        assertTrue("already here, the same: stays on", Notify.channel(env, "t1")!!.enabled)
        // Without channels the file is the engine's own, untouched.
        val plain = ConfigBackup.export(env2, "x", now = 1)
        Notify.channels(env2).forEach { Notify.remove(env2, it.id) }
        assertEquals(plain, Notify.intoBackup(env2, plain))
    }

    @Test fun anImportedChannelNeverSendsByItself() {
        // A shared file with a channel that needs nothing more: it arrives, turned off.
        val shared = Notify.Channel("evil", Notify.Kind.DISCORD, "Looks fine", true, Notify.Event.entries.toSet(), mapOf("url" to "https://attacker.example/api/webhooks/1/x"))
        assertEquals(1, Notify.restore(env, listOf(shared)))
        assertFalse(Notify.channel(env, "evil")!!.enabled)
        assertTrue(Notify.deliveries(env, SyncJob("j", "x", PlaylistRef("csv", "a", "a"), PlaylistRef("csv", null, "b")), report(added = 1)).isEmpty())
    }

    @Test fun aNewAddressAsksForTheSecretAgain() {
        val c = Notify.build("g1", Notify.Kind.GOTIFY, "Home", true, Notify.Event.entries.toSet(), mapOf("server" to "https://gotify.example.org", "token" to "GotifyAppToken123"), null).getOrThrow()
        Notify.put(env, c)
        // The same server (a trailing slash aside): the token stays.
        assertEquals("GotifyAppToken123", Notify.build("g1", Notify.Kind.GOTIFY, "Home", true, emptySet(), mapOf("server" to "https://gotify.example.org/", "token" to ""), c).getOrThrow().value("token"))
        // Another server: the saved token is not sent there; it must be typed again.
        assertTrue(Notify.build("g1", Notify.Kind.GOTIFY, "Home", true, emptySet(), mapOf("server" to "https://evil.example", "token" to ""), c).isFailure)
        // A tampered file pointing the same id elsewhere: no secret follows it, and it is off.
        Notify.restore(env, listOf(c.copy(values = mapOf("server" to "https://evil.example", "token" to ""))))
        val r = Notify.channel(env, "g1")!!
        assertEquals("", r.value("token")); assertFalse(r.enabled)
    }

    @Test fun aFailureLineKeepsNoAddressOrToken() {
        val line = Notify.message("x", report(error = "Sign-in of me@example.org failed at 192.168.1.20:4533 with bot" + FAKE_BOT + ""), setOf(Notify.Event.FAILED))!!.text
        assertFalse(line, line.contains("me@example.org") || line.contains("192.168.1.20") || line.contains(FAKE_BOT.substringAfter(":")))
    }

    @Test fun reportsHideTheChannelsKeys() {
        val t = com.xlollx.songport.data.Redact.text(
            "POST https://discord.com/api/webhooks/123456/AbC-def_ghi\nhttps://hooks.slack.com/services/T0/B0/xyz\n" +
                "bot" + FAKE_BOT + "\nX-Gotify-Key: AppTokSecret\ntoken=a&user=uQiRzpo4DXghDmr9QzzfQu27cmVRsG",
        )
        listOf("AbC-def_ghi", "T0/B0/xyz", FAKE_BOT.substringAfter(":"), "AppTokSecret", "uQiRzpo4DX").forEach { assertFalse(t, t.contains(it)) }
        assertTrue(t, t.contains("discord.com/api/webhooks/123456/") && t.contains("hooks.slack.com/services/"))
    }

    @Test fun aWeeklyNewReleasesSyncTellsOfItsNews() {
        val job = SyncJob("j2", "New releases from Gym", PlaylistRef(NewReleasesProvider.SERVICE, "7:spotify:x", "x"), PlaylistRef("csv", null, "b"))
        val wants = channel(Notify.Kind.DISCORD, "url" to "$url/news", events = setOf(Notify.Event.RELEASES))
        val changes = channel(Notify.Kind.DISCORD, "url" to "$url/changes", events = setOf(Notify.Event.CHANGES))
        listOf(wants, changes).forEach { Notify.put(env, it) }
        val d = Notify.deliveries(env, job, report(added = 4))
        assertEquals(listOf(wants.id), d.map { it.first.id })
        assertEquals(Notify.Message("Trackhop: New releases from Gym", "New releases: 4 tracks added"), d.single().second)
        // A week without news says nothing; the same additions by another sync are changes.
        assertTrue(Notify.deliveries(env, job, report()).isEmpty())
        assertEquals(listOf(changes.id), Notify.deliveries(env, job.copy(source = PlaylistRef("csv", "a", "a")), report(added = 4)).map { it.first.id })
    }

    @Test fun theAppKeepsSecretsInItsEncryptedStoreAndNeverInTheExport() {
        Notify.secretsInVault = true
        Notify.put(env, Notify.Channel("p1", Notify.Kind.PUSHOVER, "Phone", true, setOf(Notify.Event.FAILED), mapOf("token" to "APPTOKENSECRET", "user" to "USERKEYSECRET")))
        Notify.put(env, Notify.Channel("n1", Notify.Kind.NTFY, "ntfy", true, setOf(Notify.Event.RELEASES), mapOf("server" to "https://ntfy.sh", "topic" to "th-x", "token" to "")))
        // With the channels: name, kind, events and plain settings; the secrets only in the vault.
        val kept = env.keyValues("notify")["channels"]!!
        assertFalse(kept, kept.contains("APPTOKENSECRET") || kept.contains("USERKEYSECRET"))
        assertEquals("APPTOKENSECRET", env.tokens.get("notify:p1")!!.extra["token"])
        // Read back whole.
        val p = Notify.channel(env, "p1")!!
        assertEquals("USERKEYSECRET", p.value("user"))
        assertTrue(p.missing().isEmpty())
        // The configuration export carries the channel, never its secrets.
        val text = Notify.intoBackup(env, ConfigBackup.export(env, "test"))
        assertFalse(text, text.contains("APPTOKENSECRET") || text.contains("USERKEYSECRET"))
        assertTrue(text, text.contains("\"p1\"") && !text.contains("th-x"))
        // A removed channel takes its secrets with it.
        Notify.remove(env, "p1")
        assertNull(env.tokens.get("notify:p1"))
        assertEquals(listOf("n1"), Notify.channels(env).map { it.id })
    }

    /** "Find my chat id": the chats that wrote to the bot, newest first, groups with their negative id, the token never in an error. */
    @Test fun findMyChatIdReadsTheChatsThatWroteToTheBot() {
        mock.enqueue(MockResponse().setBody("""{"ok":true,"result":[
            {"update_id":1,"message":{"chat":{"id":555,"first_name":"Alex","type":"private"},"text":"hi"}},
            {"update_id":2,"message":{"chat":{"id":-100777,"title":"Band night","type":"supergroup"},"text":"/start"}},
            {"update_id":3,"message":{"chat":{"id":555,"first_name":"Alex","type":"private"},"text":"again"}}]}"""))
        val chats = Notify.telegramChats("42:TOKENPART").getOrThrow()
        assertEquals(listOf("555", "-100777"), chats.map { it.id })
        assertEquals("Band night", chats[1].name)
        assertTrue(mock.takeRequest().path!!.endsWith("/bot42:TOKENPART/getUpdates"))
        mock.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"description":"Unauthorized"}"""))
        val e = Notify.telegramChats("42:TOKENPART").exceptionOrNull()!!.message!!
        assertTrue(e, "401" in e && "TOKENPART" !in e)
    }

    @Test fun theBotsOwnIdAsChatIdAndAnUnstartedBotAreSaidPlainly() {
        val c = Notify.Channel("t", Notify.Kind.TELEGRAM, "Chat", true, Notify.Event.entries.toSet(), mapOf("token" to "123456:ABC", "chat" to "123456"))
        assertEquals(Notify.TelegramHint.BOT_ID, Notify.telegramHint(c))
        val ok = c.copy(values = mapOf("token" to "123456:ABC", "chat" to "999"))
        assertEquals(null, Notify.telegramHint(ok))
        assertEquals(Notify.TelegramHint.START_BOT, Notify.telegramHint(ok, "HTTP 400: Bad Request: chat not found"))
        assertEquals(Notify.TelegramHint.BOT_ID, Notify.telegramHint(ok, "HTTP 403: Forbidden: the bot can't send messages to the bot"))
    }
}

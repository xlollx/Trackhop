package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.EncryptedBackup
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * The encrypted full backup on Desktop and Docker (1.18, behind Features.v2): the page, the API and
 * what an older path says of such a file. Fake values only.
 */
class FullBackupWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
    private val password = "correct-horse-1"

    @Before fun start() {
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        env.store.upsertJob(SyncJob("daily", "Daily", PlaylistRef("csv", "a", "a"), PlaylistRef("csv", null, "b"), schedule = Schedule.DAILY))
        env.store.updateSettings { it.copy(clientSecrets = mapOf("youtube" to "secret-a")) }
        env.tokens.set("lastfm", Tokens(accessToken = "token-a", userName = "user-a"))
        env.tokens.set("ytm", Tokens(accessToken = "", extra = mapOf("cookies" to "cookie-a")))
    }

    @After fun stop() { server.stop(0); Features.v2 = false }

    private fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", base)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun upload(text: String): HttpResponse<String> {
        val boundary = "----th"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"action\"\r\n\r\nread\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.trackhop\"\r\n\r\n$text\r\n--$boundary--\r\n"
        return client.send(
            HttpRequest.newBuilder(URI("$base/tools/config")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun api(method: String, path: String, body: String?, vararg headers: String) = client.send(
        HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token")
            .apply { if (headers.isNotEmpty()) headers(*headers) }
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun location(r: HttpResponse<String>) = URLDecoder.decode(r.headers().firstValue("Location").orElse(""), Charsets.UTF_8)

    private fun sealed(): String {
        Features.v2 = true
        return post("/tools/config", "action=encrypted&password=$password&password2=$password").body()
    }

    @Test fun offTheSwitchNothingIsOfferedAndAnEncryptedFileGetsAClearWord() {
        val text = sealed()
        Features.v2 = false
        assertFalse(get("/tools/backup").body().contains("action\" value=\"encrypted\""))
        assertEquals(303, post("/tools/config", "action=encrypted&password=$password&password2=$password").statusCode())
        assertEquals(404, api("POST", "config/encrypted", """{"password":"$password"}""").statusCode())
        val read = upload(text)
        assertEquals(303, read.statusCode())
        assertTrue(location(read), location(read).contains("newer Trackhop feature"))
        val viaApi = api("POST", "config", text, "X-Backup-Password", password)
        assertEquals(400, viaApi.statusCode())
        assertTrue(viaApi.body().contains("newer Trackhop feature"))
        // The plain file is as it was.
        val plain = get("/tools/config?download=1").body()
        assertTrue(plain.contains("\"format\": \"trackhop-config\"") && !plain.contains("token-a") && !plain.contains("secret-a"))
    }

    @Test fun thePageSavesAndRestoresTheSignIns() {
        Features.v2 = true
        assertTrue(get("/tools/backup").body().contains("Include sign-ins and schedules (encrypted)"))
        assertTrue(location(post("/tools/config", "action=encrypted&password=short&password2=short")).contains("at least 10"))
        assertTrue(location(post("/tools/config", "action=encrypted&password=$password&password2=other-horse-1")).contains("not the same"))
        val text = sealed()
        assertTrue(EncryptedBackup.isEncrypted(text))
        for (secret in listOf("token-a", "secret-a", "cookie-a", "Daily")) assertFalse(secret, text.contains(secret))
        val opened = EncryptedBackup.unwrap(text, password.toCharArray())
        assertEquals("token-a", opened.secrets.vault["lastfm"]?.accessToken)
        // The pasted cookies travel as the server's web sign-ins, not as an ordinary sign-in.
        assertNull(opened.secrets.vault["ytm"])
        assertEquals(EncryptedBackup.WEB_SERVER, opened.secrets.web?.platform)

        // Into this same server, after its sign-in changed: the password first.
        env.tokens.set("lastfm", Tokens(accessToken = "token-b"))
        env.store.update { it.copy(jobs = emptyList()) }
        val ask = upload(text)
        assertEquals(200, ask.statusCode())
        val locked = Regex("""name="pending" value="([^"]+)"""").find(ask.body())!!.groupValues[1]
        val wrong = post("/tools/config", "action=unlock&pending=$locked&password=wrong-horse-1")
        assertEquals(200, wrong.statusCode())
        assertTrue(wrong.body().contains("Wrong password"))
        val again = Regex("""name="pending" value="([^"]+)"""").find(wrong.body())!!.groupValues[1]
        val summary = post("/tools/config", "action=unlock&pending=$again&password=$password")
        assertEquals(200, summary.statusCode())
        assertTrue(summary.body(), summary.body().contains("replacesDevice") && summary.body().contains("replaceSignIns") && summary.body().contains("Last.fm"))
        val pending = Regex("""name="pending" value="([^"]+)"""").find(summary.body())!!.groupValues[1]
        // Not ticked: this server's own sign-in stays.
        val done = post("/tools/config", "action=import&pending=$pending")
        assertEquals(303, done.statusCode())
        assertEquals("token-b", env.tokens.get("lastfm")?.accessToken)
        assertTrue(env.store.job("daily")!!.enabled)
        assertTrue(location(done), location(done).contains("Sign-ins restored"))
    }

    @Test fun anotherServerTakesThemAndTheOtherDeviceItReplaces() {
        val text = sealed()
        val other = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://127.0.0.1")
        val o = FullBackupServer.open(other, text, password.toCharArray())
        val kept = FullBackupServer.import(other, o, replacesDevice = false, replaceSignIns = false, channels = true)
        assertEquals(1, kept.summary.paused)
        assertFalse(other.store.job("daily")!!.enabled)
        assertEquals("token-a", other.tokens.get("lastfm")?.accessToken)
        assertEquals("cookie-a", other.tokens.get("ytm")?.extra?.get("cookies"))
        assertEquals("secret-a", other.store.data.settings.clientSecrets["youtube"])

        val third = ServerEnv(tmp.newFolder("third"), Strings(emptyMap()), "http://127.0.0.1")
        val replaced = FullBackupServer.import(third, FullBackupServer.open(third, text, password.toCharArray()), replacesDevice = true, replaceSignIns = false, channels = true)
        assertEquals(0, replaced.summary.paused)
        assertTrue(third.store.job("daily")!!.enabled)
        assertTrue(FullBackupServer.notes(replaced, replacesDevice = true).contains("turn off or uninstall"))

        // A phone's file: its Bridge sessions are not used here, the rest comes in.
        val phone = EncryptedBackup.wrap(
            ConfigBackup.export(env, "Trackhop test (Android)"),
            EncryptedBackup.Secrets(vault = mapOf("spotify" to Tokens(accessToken = "token-c")), web = EncryptedBackup.WebSessions(EncryptedBackup.WEB_BRIDGE, "{}", listOf("Spotify (web)"))),
            password.toCharArray(),
        )
        val fourth = ServerEnv(tmp.newFolder("fourth"), Strings(emptyMap()), "http://127.0.0.1")
        val fromPhone = FullBackupServer.import(fourth, FullBackupServer.open(fourth, phone, password.toCharArray()), replacesDevice = false, replaceSignIns = false, channels = true)
        assertTrue(fromPhone.restored.webSkipped)
        assertEquals("token-c", fourth.tokens.get("spotify")?.accessToken)
    }

    @Test fun theApiExportsAndImportsWithThePasswordInAHeader() {
        Features.v2 = true
        assertEquals(400, api("POST", "config/encrypted", """{"password":"short"}""").statusCode())
        val file = api("POST", "config/encrypted", """{"password":"$password"}""")
        assertEquals(200, file.statusCode())
        assertTrue(file.headers().firstValue("Content-Disposition").orElse("").contains(".trackhop"))
        val text = file.body()
        assertTrue(EncryptedBackup.isEncrypted(text))

        assertTrue(api("POST", "config", text).body().contains("X-Backup-Password"))
        val wrong = api("POST", "config", text, "X-Backup-Password", "wrong-horse-1")
        assertEquals(400, wrong.statusCode())
        assertTrue(wrong.body().contains("Wrong password"))

        env.tokens.set("lastfm", Tokens(accessToken = "token-b"))
        val dry = Json.parseToJsonElement(api("POST", "config?dryRun=true", text, "X-Backup-Password", password).body()).jsonObject
        assertEquals("false", dry["imported"]!!.jsonPrimitive.content)
        assertEquals("true", dry["encrypted"]!!.jsonPrimitive.content)
        assertEquals(listOf("Last.fm"), dry["conflicts"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("token-b", env.tokens.get("lastfm")?.accessToken)

        val done = Json.parseToJsonElement(api("POST", "config?replaceSignIns=true&replacesDevice=true", text, "X-Backup-Password", password).body()).jsonObject
        assertEquals("true", done["imported"]!!.jsonPrimitive.content)
        assertEquals("token-a", env.tokens.get("lastfm")?.accessToken)
        // The documentation lists the call only with the feature.
        assertTrue(api("GET", "openapi.json", null).body().contains("/config/encrypted"))
        Features.v2 = false
        assertFalse(api("GET", "openapi.json", null).body().contains("/config/encrypted"))
    }

    @Test fun theCommandLineAsksThePasswordAndSendsItInAHeader() {
        Features.v2 = true
        val dir = tmp.newFolder("cli")
        val out = java.io.ByteArrayOutputStream()
        val path = File(dir, "set-up.trackhop").path
        val cliEnv = mapOf("TRACKHOP_URL" to base, "TRACKHOP_TOKEN" to token, "TRACKHOP_BACKUP_PASSWORD" to password)
        assertEquals(0, Cli.run(listOf("config", "--encrypted", path), cliEnv, java.io.PrintStream(out), java.io.PrintStream(java.io.ByteArrayOutputStream())))
        assertTrue(EncryptedBackup.isEncrypted(File(path).readText()))
        env.tokens.set("lastfm", Tokens(accessToken = "token-b"))
        out.reset()
        assertEquals(0, Cli.run(listOf("config", "import", path, "--dry-run"), cliEnv, java.io.PrintStream(out), java.io.PrintStream(java.io.ByteArrayOutputStream())))
        assertTrue(out.toString(), out.toString().contains("Nothing changed") && out.toString().contains("Last.fm"))
        out.reset()
        assertEquals(0, Cli.run(listOf("config", "import", path, "--replace-sign-ins"), cliEnv, java.io.PrintStream(out), java.io.PrintStream(java.io.ByteArrayOutputStream())))
        assertEquals("token-a", env.tokens.get("lastfm")?.accessToken)
    }
}

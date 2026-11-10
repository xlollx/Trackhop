package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Timing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * The report for asking for help and the issue made ready (Diagnostics.kt, Issues.kt), and the
 * crowded schedules (Crowds.kt): through the API, the command line, the MCP tools and the page.
 */
class DiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
    private var github: MockWebServer? = null

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "1.16.0").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        LocalKey.write(data, server.address.port)
    }

    @After fun stop() {
        server.stop(0)
        LocalKey.reset()
        github?.shutdown()
        Issues.github = "https://api.github.com"
    }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
    }

    private fun call(method: String, path: String, body: String? = null): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun page(path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", base).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        return code to (out.toString("UTF-8") + err.toString("UTF-8")).replace("\r\n", "\n")
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    /** Four daily syncs reading Spotify at 09:00 (Spotify turns bursts away), one every 3 hours to File. */
    private fun crowd() {
        listOf("Morning", "Gym", "Focus", "Road trip").forEachIndexed { i, n ->
            env.store.upsertJob(Timing.apply(SyncJob("c$i", n, PlaylistRef("spotify", "p$i", "$n list"), PlaylistRef("csv", null, "$n copy")), 1440, "09:00", emptyList()))
        }
        env.store.upsertJob(Timing.apply(SyncJob("f", "Files", PlaylistRef("csv", "x", "X"), PlaylistRef("csv", null, "Y")), 180, null, emptyList()))
    }

    private fun failed(error: String, name: String = "Secret Mix") {
        env.store.upsertJob(SyncJob("s1", name, PlaylistRef("spotify", "pl", "My Private List"), PlaylistRef("csv", null, "Hidden Copy")))
        env.store.addReport(SyncReport("r1", "s1", name, System.currentTimeMillis() - 60_000, 1000, error = error,
            unmatchedTracks = listOf(Track("t1", "Moonlit Velvet", listOf("Quiet Harbour")))))
    }

    // ------------------------------------------------------------------ redaction

    @Test fun theReportHasNoSecretsAndNoNamesUnlessAsked() {
        env.tokens.set("jellyfin", Tokens("tok", extra = mapOf("url" to "https://music.lorenzo.example.net")))
        failed("Secret Mix: Spotify said 401 for Moonlit Velvet by Quiet Harbour")
        Log.add("spotify", "GET https://api.spotify.com/v1/me Authorization: Bearer BQ-very-long-access-token-0123456789 -> 401")
        Log.add("lyrics", "/home/lorenzo/Music/Quiet Harbour/a.flac unreadable, mail lorenzo@example.com, server https://music.lorenzo.example.net/rest")
        Log.add("sync", "end Secret Mix: +0 -0 nf=1")

        val plain = call("GET", "diagnostics?since=2h").also { assertEquals(it.text, 200, it.code) }.obj
        val report = plain.s("report")!!
        for (secret in listOf("BQ-very-long", "lorenzo", "music.lorenzo.example.net", "Secret Mix", "My Private List", "Hidden Copy", "Moonlit Velvet", "Quiet Harbour")) {
            assertFalse("$secret still in:\n$report", report.contains(secret))
        }
        assertTrue(report, report.startsWith("Trackhop server 1.16.0"))
        assertTrue(report, report.contains("[sync 1]: spotify -> csv"))
        assertTrue(report, report.contains("end [sync 1]: +0 -0 nf=1"))
        assertTrue(report, report.contains("/home/[user]/Music/[artist]/a.flac"))
        assertTrue(report, report.contains("Spotify") && report.contains("-> 401"))
        assertTrue(plain.s("error")!!, plain.s("error")!!.startsWith("[sync 1]: Spotify said 401 for [track]"))
        assertEquals(Diagnostics.signature(plain.s("error")!!), plain.s("signature"))

        // With names=true the names stay; the secrets never.
        val named = call("GET", "diagnostics?since=2h&names=true").obj
        val text = named.s("report")!!
        assertTrue(text, text.contains("Secret Mix") && text.contains("Quiet Harbour"))
        for (secret in listOf("BQ-very-long", "lorenzo@example.com", "/home/lorenzo", "music.lorenzo.example.net")) assertFalse("$secret in:\n$text", text.contains(secret))
        // And the signature is the same either way.
        assertEquals(plain.s("signature"), named.s("signature"))

        assertEquals(400, call("GET", "diagnostics?since=yesterday").code)
    }

    // ------------------------------------------------------------------ signature

    @Test fun theSignatureStaysTheSameForTheSameError() {
        val a = Diagnostics.signature("[sync 3]: Spotify refused \"Road trip\" (429), try again in 30 s, id 4uLU6hMCjMI75M1A2tKUQC")
        val b = Diagnostics.signature("[sync 12]:  spotify refused \"Gym\" (429), try again in 1800 s, id 0aB3x9QpLmN7rT2vW5yZ1c")
        val c = Diagnostics.signature("[playlist]: Spotify refused 'Other' (503), try again in 5 s, id abcdef0123456789")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(12, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, Diagnostics.signature("[sync 3]: Deezer refused \"Road trip\" (429), try again in 30 s"))
        assertNotEquals(a, Diagnostics.signature("[sync 3]: Spotify session expired"))
        // Links and times in it do not change it either.
        assertEquals(Diagnostics.signature("GET https://api.example.org/a?x=1 timed out after 10 s"), Diagnostics.signature("GET https://other.example.org/b timed out after 31 s"))
    }

    // ------------------------------------------------------------------ the issue and GitHub's search

    /** A stand-in for GitHub: the search finds [items] (as JSON), the releases are [releases]. */
    private fun fakeGitHub(items: String, releases: String = "[]") {
        github = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/search/issues") -> MockResponse().setBody("""{"total_count":1,"items":$items}""")
                    request.path!!.startsWith("/repos/xlollx/Trackhop/releases") -> MockResponse().setBody(releases)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        Issues.github = github!!.url("/").toString().trimEnd('/')
    }

    private fun prepare(): JsonObject = Issues.prepare(env, "Trackhop server", "1.16.0", System.currentTimeMillis() - 3_600_000, false, null, null)

    private fun sig(): String = Diagnostics.signature(Diagnostics.lastError(env, System.currentTimeMillis() - 3_600_000, false)!!)

    private fun item(n: Int, state: String, sig: String, closed: String? = null) =
        """{"number":$n,"title":"Spotify 401","html_url":"https://github.com/xlollx/Trackhop/issues/$n","state":"$state","closed_at":${closed?.let { "\"$it\"" } ?: "null"},"body":"text\n<!-- trackhop-sig: $sig -->"}"""

    @Test fun anOpenIssueWithTheSameErrorGetsACommentInstead() {
        failed("Spotify said 401")
        fakeGitHub("[${item(7, "closed", sig(), "2026-10-01T10:00:00Z")}, ${item(9, "open", sig())}]")
        val r = prepare()
        assertEquals("comment", r.s("suggestion"))
        assertEquals("true", r.s("searched"))
        assertEquals("9", r["issue"]!!.jsonObject.s("number"))
        assertEquals("https://github.com/xlollx/Trackhop/issues/9#new_comment_field", r.s("commentUrl"))
        assertTrue(r.s("comment")!!, r.s("comment")!!.startsWith("Happens to me too, on Trackhop server 1.16.0."))
        // The search asked GitHub for the signature, in this repository's issues.
        val asked = URLDecoder.decode(github!!.takeRequest().path!!, Charsets.UTF_8)
        assertTrue(asked, asked.contains("\"${sig()}\"") && asked.contains("repo:xlollx/Trackhop") && asked.contains("is:issue"))
        // The new issue is still there, with the signature hidden in its text.
        assertTrue(r.s("body")!!, r.s("body")!!.contains("<!-- trackhop-sig: ${sig()} -->"))
        assertTrue(r.s("newIssueUrl")!!.startsWith("https://github.com/xlollx/Trackhop/issues/new?title="))
    }

    @Test fun aClosedIssueFixedInANewerVersionSaysToUpdate() {
        failed("Spotify said 401")
        fakeGitHub("[${item(5, "closed", sig(), "2026-10-01T10:00:00Z")}]",
            """[{"tag_name":"server-v1.17.0","html_url":"u","published_at":"2026-10-08T21:00:00Z","draft":false},{"tag_name":"v1.17.0","published_at":"2026-10-08T21:00:00Z"}]""")
        val r = prepare()
        assertEquals("update", r.s("suggestion"))
        assertEquals("1.17.0", r.s("latest"))
        assertTrue(r.s("message")!!, r.s("message")!!.contains("update to Trackhop server 1.17.0"))
        assertTrue(r["comment"] is kotlinx.serialization.json.JsonNull)
    }

    @Test fun aClosedIssueThatStillHappensOnTheNewestGetsACommentOnIt() {
        failed("Spotify said 401")
        // The newest release is this one: the fix, if any, is not out, or it did not fix it.
        fakeGitHub("[${item(5, "closed", sig(), "2026-10-01T10:00:00Z")}]",
            """[{"tag_name":"server-v1.16.0","html_url":"u","published_at":"2026-11-10T21:00:00Z","draft":false}]""")
        val r = prepare()
        assertEquals("comment_closed", r.s("suggestion"))
        assertEquals("https://github.com/xlollx/Trackhop/issues/5#new_comment_field", r.s("commentUrl"))
        // A newer release out before the issue was closed did not carry the fix either.
        github!!.shutdown()
        fakeGitHub("[${item(5, "closed", sig(), "2026-10-01T10:00:00Z")}]",
            """[{"tag_name":"server-v1.16.5","html_url":"u","published_at":"2026-09-20T21:00:00Z","draft":false}]""")
        assertEquals("comment_closed", prepare().s("suggestion"))
    }

    @Test fun anotherSignatureOrNoNetworkIsJustANewIssue() {
        failed("Spotify said 401")
        // Found by the words, but its signature is another error's.
        fakeGitHub("[${item(3, "open", "0123456789ab")}]")
        assertEquals("new", prepare().s("suggestion"))
        github!!.shutdown(); github = null
        Issues.github = "http://127.0.0.1:1"
        val r = call("POST", "report", """{"since":"2h","title":"Spotify fails","description":"My syncs to Spotify fail since this morning."}""")
        assertEquals(r.text, 200, r.code)
        val o = r.obj
        assertEquals("new", o.s("suggestion"))
        assertEquals("false", o.s("searched"))
        assertEquals("Spotify fails", o.s("title"))
        assertTrue(o.s("body")!!, o.s("body")!!.contains("My syncs to Spotify fail since this morning.") && o.s("body")!!.contains("Trackhop server 1.16.0"))
        assertTrue(o.s("message")!!, o.s("message")!!.contains("Nothing was sent"))
        val link = URLDecoder.decode(o.s("newIssueUrl")!!, Charsets.UTF_8)
        assertTrue(link, link.contains("title=Spotify fails") && link.contains("trackhop-sig: ${o.s("signature")}"))
    }

    @Test fun aLongLogIsCutToItsNewestLinesForTheLink() {
        val report = "Trackhop server 1.16.0\n\nLog:\n" + (1..2000).joinToString("\n") { "  2026-11-10 10:00:00 [engine] line number $it of the log" }
        val cut = Issues.forLink(report, 5_500)
        assertTrue(java.net.URLEncoder.encode(cut, Charsets.UTF_8).length <= 5_500)
        assertTrue(cut, cut.startsWith("Trackhop server 1.16.0") && cut.contains("line number 2000 ") && cut.contains("older lines left out"))
        assertFalse(cut.contains("line number 1 "))
    }

    // ------------------------------------------------------------------ crowded schedules: API

    @Test fun theApiFindsACrowdAndSpreadsItOnlyWhenAsked() {
        assertEquals("false", call("GET", "syncs/crowded").obj.s("crowded"))
        crowd()
        val c = call("GET", "syncs/crowded").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals("true", c.s("crowded"))
        assertEquals("spotify", c.s("service"))
        assertEquals("09:00", c.s("at"))
        assertEquals("true", c.s("canSpread"))
        assertEquals("false", c.s("dismissed"))
        assertEquals(listOf("c0", "c1", "c2", "c3"), c["syncs"]!!.jsonArray.map { it.jsonObject.s("id") })

        // A dry run by default: the new times, nothing saved.
        val dry = call("POST", "syncs/spread").obj
        assertEquals("false", dry.s("applied"))
        val changes = dry["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("c1" to "09:15", "c2" to "09:30", "c3" to "09:45"), changes.map { it.s("id") to it.s("to") })
        assertTrue(changes.all { it.s("from") == "09:00" })
        assertEquals(listOf("c0", "c1", "c2", "c3").map { "09:00" }, listOf("c0", "c1", "c2", "c3").map { env.store.job(it)!!.at })

        val done = call("POST", "syncs/spread?apply=true").obj
        assertEquals("true", done.s("applied"))
        assertEquals(listOf("09:00", "09:15", "09:30", "09:45"), listOf("c0", "c1", "c2", "c3").map { env.store.job(it)!!.at })
        assertEquals(1440, Timing.interval(env.store.job("c1")!!))
        assertEquals("false", call("GET", "syncs/crowded").obj.s("crowded"))
        assertEquals("false", call("POST", "syncs/spread", """{"apply":true}""").obj.s("applied"))
    }

    // ------------------------------------------------------------------ crowded schedules: the page

    @Test fun thePageShowsTheCardAndSpreadsAfterConfirming() {
        crowd()
        val home = page("/").body()
        assertTrue(home, home.contains("Syncs with Spotify all start around 09:00"))
        assertTrue(home, home.contains("""href="/syncs/spread">Spread them"""))
        assertTrue(home, home.contains("Got it"))
        // Show them: only those syncs.
        val shown = page("/?ids=c0%2Cc1%2Cc2%2Cc3").body()
        assertTrue(shown, shown.contains("Morning") && shown.contains("Road trip") && !shown.contains("""href="/syncs/f""""))

        val confirm = page("/syncs/spread").body()
        assertTrue(confirm, confirm.contains("Spread the syncs with Spotify?"))
        assertTrue(confirm, confirm.contains("""<s class="muted">09:00</s> → <b>09:15</b>"""))
        assertTrue(confirm, confirm.contains("Confirm") && confirm.contains("Cancel"))
        // Nothing changes until Confirm, and only for the group the page showed.
        assertEquals("09:00", env.store.job("c3")!!.at)
        assertEquals("/syncs/spread", post("/syncs/spread", "key=spotify%3Ac0").headers().firstValue("Location").orElse(""))
        assertEquals("09:00", env.store.job("c3")!!.at)
        val key = java.net.URLEncoder.encode(Crowds.key(Crowds.find(env)!!), Charsets.UTF_8)
        val saved = post("/syncs/spread", "key=$key")
        assertEquals(303, saved.statusCode())
        assertTrue(saved.headers().firstValue("Location").get().startsWith("/?ok="))
        assertEquals(listOf("09:00", "09:15", "09:30", "09:45"), listOf("c0", "c1", "c2", "c3").map { env.store.job(it)!!.at })
        assertFalse(page("/").body().contains("all start around"))
    }

    @Test fun gotItHidesTheCardUntilTheGroupChanges() {
        crowd()
        val key = java.net.URLEncoder.encode(Crowds.key(Crowds.find(env)!!), Charsets.UTF_8)
        assertEquals(303, post("/syncs/crowd", "key=$key").statusCode())
        assertFalse(page("/").body().contains("all start around"))
        assertEquals("true", call("GET", "syncs/crowded").obj.s("dismissed"))
        // One more sync in the crowd: a new group, told again.
        env.store.upsertJob(Timing.apply(SyncJob("c9", "Late", PlaylistRef("spotify", "p9", "Late list"), PlaylistRef("csv", null, "Late copy")), 1440, "09:10", emptyList()))
        assertTrue(page("/").body().contains("Syncs with Spotify all start around 09:00"))
    }

    // ------------------------------------------------------------------ command line and MCP

    @Test fun theCommandLineReadsTheCrowdSpreadsAndWritesTheReport() {
        crowd()
        val (code, text) = cli("crowded")
        assertEquals(text, 0, code)
        assertTrue(text, text.contains("Syncs with Spotify all start around 09:00 (4): Morning, Gym, Focus, Road trip") && text.contains("trackhop spread"))
        val dry = cli("spread")
        assertTrue(dry.second, dry.second.contains("nothing saved yet") && dry.second.contains("09:00 -> 09:15") && dry.second.contains("trackhop spread --apply"))
        assertEquals("09:00", env.store.job("c1")!!.at)
        val applied = cli("spread", "--apply")
        assertEquals(applied.second, 0, applied.first)
        assertTrue(applied.second, applied.second.contains("New times saved"))
        assertEquals("09:15", env.store.job("c1")!!.at)
        assertTrue(cli("spread").second.contains("Nothing to spread."))

        failed("Spotify said 401")
        val file = File(tmp.root, "report.txt")
        val (dcode, dtext) = cli("diagnostics", "--since=2h", "--out", file.path)
        assertEquals(dtext, 0, dcode)
        assertTrue(file.readText().startsWith("Trackhop server 1.16.0") && !file.readText().contains("Secret Mix"))
        assertTrue(cli("diagnostics", "--names").second.contains("Secret Mix"))
        val wrong = cli("diagnostics", "--since", "soon")
        assertEquals(1, wrong.first)
        assertTrue(wrong.second, wrong.second.contains("\"since\""))

        Issues.github = "http://127.0.0.1:1"
        val (rcode, rtext) = cli("report", "--title", "Spotify fails")
        assertEquals(rtext, 0, rcode)
        assertTrue(rtext, rtext.contains("Nothing was sent") && rtext.contains("Title: Spotify fails") && rtext.contains("Open: https://github.com/xlollx/Trackhop/issues/new?"))
        // With a run's id, report is still one run.
        assertTrue(cli("report", "r1").second.contains("Secret Mix"))
    }

    @Test fun theMcpToolsAreTheCommands() {
        val spread = Mcp.callFor("spread_syncs", Json.parseToJsonElement("""{"apply":true}""").jsonObject)
        assertEquals("spread", spread.command)
        assertTrue(spread.opts.has("apply"))
        assertFalse(Mcp.callFor("spread_syncs", JsonObject(emptyMap())).opts.has("apply"))
        val diag = Mcp.callFor("get_diagnostics", Json.parseToJsonElement("""{"since":"7d","names":true}""").jsonObject)
        assertEquals("diagnostics", diag.command)
        assertEquals("7d", diag.opts.value("since"))
        assertTrue(diag.opts.has("names"))
        val issue = Mcp.callFor("prepare_issue", Json.parseToJsonElement("""{"description":"It fails"}""").jsonObject)
        assertEquals("report", issue.command)
        assertTrue(issue.params.isEmpty())
        assertEquals("It fails", issue.opts.value("description"))
        assertEquals("crowded", Mcp.callFor("get_crowded_syncs", JsonObject(emptyMap())).command)
    }
}

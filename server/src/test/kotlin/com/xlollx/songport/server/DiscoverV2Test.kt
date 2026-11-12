package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.Track
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.sync.ArtistAlerts
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.SpotifyConcerts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The next release's features on the server (Features.v2): Wrapped from real listening, the
 * playlist poster, the followed artists' alerts and Spotify (web) as a Concerts source, through the
 * page, the API, the command line and MCP. The listening services and the artists' sources are
 * stood in for: nothing leaves the machine.
 */
class DiscoverV2Test {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        BuildDefaults.values = emptyMap()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        LocalKey.write(data, server.address.port)
        File(data, "csv").mkdirs()
        File(data, "csv/Mix.csv").writeText("title,artist\nOne,Band A\nTwo,Band A\nThree,Band B\nFour,Band C\nFive,Band B\nSix,Band D\n")
        ServerWrapped.details = { null }
    }

    @After fun stop() {
        server.stop(0)
        LocalKey.reset()
        ServerWrapped.fake = null
        Features.v2 = true
    }

    private class Res(val code: Int, val text: String, val bytes: ByteArray = ByteArray(0)) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
    }

    private fun api(method: String, path: String, body: String? = null): Res {
        val r = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        return Res(r.statusCode(), String(r.body(), Charsets.UTF_8), r.body())
    }

    private fun page(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, form: Map<String, String>) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", base).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" })).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        return code to (out.toString("UTF-8") + err.toString("UTF-8"))
    }

    private fun mcp(tool: String, args: JsonObject): Pair<Boolean, String> = Mcp.run(Cli.Client(base, token), Mcp.callFor(tool, args))

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun play(title: String, artist: String, plays: Int, year: Int = 2001) =
        Listening.Play(Track("$title|$artist", title, listOf(artist), year = year), plays, "https://img.example/$title.jpg")

    /** Last.fm connected, as far as the server can tell, with these plays. */
    private fun lastFm(plays: List<Listening.Play>) {
        ServerWrapped.fake = ServerWrapped.Fake(listOf(Listening.Source.LASTFM to LastFmProvider())) { _, _ ->
            Listening.fromCounts(plays).copy(totalPlays = plays.sumOf { it.plays })
        }
    }

    // ------------------------------------------------------------------ Wrapped

    @Test fun withoutAListeningServiceTheWrappedSaysWhatToConnect() {
        val r = api("GET", "wrapped")
        assertEquals(r.text, 200, r.code)
        assertEquals(0, r.obj["sources"]!!.jsonArray.size)
        assertEquals(0, r.obj["wrapped"]!!.jsonArray.size)
        val made = api("POST", "wrapped", "{}")
        assertEquals(409, made.code)
        assertTrue(made.text, made.text.contains("Connect a listening service"))
        assertEquals(404, api("GET", "wrapped/latest").code)
        assertTrue(page("/tools/wrapped").body().contains("Connect a listening service"))
        val (code, text) = cli("wrapped")
        assertEquals(text, 0, code)
        assertTrue(text, text.contains("No listening service connected"))
    }

    @Test fun aWrappedIsMadeFromThePlaysKeptAndDrawnAsACard() {
        lastFm((1..8).map { play("Song $it", if (it % 2 == 0) "Band A" else "Band B", 100 - it, 1990 + it) })
        assertEquals("lastfm", api("GET", "wrapped").obj["sources"]!!.jsonArray.single().jsonObject.s("source"))
        assertEquals(400, api("POST", "wrapped", """{"period":"decade"}""").code)
        assertEquals(400, api("POST", "wrapped", """{"source":"tidal"}""").code)
        val r = api("POST", "wrapped", """{"source":"lastfm","period":"half_year"}""")
        assertEquals(r.text, 200, r.code)
        val w = r.obj
        assertEquals("half_year", w.s("period"))
        assertEquals("My last six months", w.s("eyebrow"))
        assertEquals("From your listening on Last.fm", w.s("from"))
        assertEquals("Song 1", w["topTracks"]!!.jsonArray[0].jsonObject.s("title"))
        assertEquals("Band B", w["topArtists"]!!.jsonArray[0].jsonObject.s("name"))
        assertNotNull(w.s("age"))
        val id = w.s("id")!!
        assertEquals(id, api("GET", "wrapped/latest").obj.s("id"))
        assertEquals(id, api("GET", "wrapped").obj["wrapped"]!!.jsonArray.single().jsonObject.s("id"))
        // The card: a PNG.
        val card = api("GET", "wrapped/$id/card")
        assertEquals(200, card.code)
        assertTrue(card.bytes.size > 1000 && card.bytes[1] == 'P'.code.toByte() && card.bytes[2] == 'N'.code.toByte())
        assertEquals(404, api("GET", "wrapped/nope/card").code)
        // The page: the form, the history, one Wrapped with its card.
        assertTrue(page("/tools/wrapped").body().contains("Make my Wrapped"))
        val one = page("/tools/wrapped/$id").body()
        assertTrue(one.contains("Band B") && one.contains("card.png"))
        assertEquals(200, page("/tools/wrapped/$id/card.png").statusCode())
        // Kept in the user's own store, as the app keeps it.
        assertEquals(id, env.store.data.wrapped.first().id)
    }

    @Test fun theCommandLineAndTheAssistantMakeAndReadTheWrapped() {
        lastFm(listOf(play("Hit", "Band A", 40), play("Other", "Band B", 10)))
        val (code, text) = cli("wrapped", "new", "--period", "month")
        assertEquals(text, 0, code)
        assertTrue(text, text.contains("My last month") && text.contains("Band A - Hit") && text.contains("40 plays"))
        val file = File(tmp.root, "card.png")
        val (c2, t2) = cli("wrapped", "card", file.path)
        assertEquals(t2, 0, c2)
        assertTrue(file.length() > 1000)
        assertEquals(0, cli("wrapped", "list").first)
        assertTrue(cli("help", "wrapped").second.contains("wrapped card <file.png>"))
        assertTrue("get_wrapped" in Mcp.toolNames && "make_wrapped" in Mcp.toolNames)
        val (ok, json) = mcp("get_wrapped", buildJsonObject { put("wrapped", "latest") })
        assertTrue(json, ok && json.contains("\"Hit\""))
        val (made, _) = mcp("make_wrapped", buildJsonObject { put("source", "lastfm") })
        assertTrue(made)
        assertEquals(2, env.store.data.wrapped.size)
    }

    @Test fun tidalTellsTheArtistsAndAnotherServiceTheSongs() {
        ServerWrapped.fake = ServerWrapped.Fake(listOf(Listening.Source.LASTFM to LastFmProvider(), Listening.Source.TIDAL to TidalProvider())) { s, _ ->
            if (s == Listening.Source.TIDAL) Listening.Report(emptyList(), listOf(Listening.ArtistPlays("Band T", 500)))
            else Listening.fromCounts(listOf(play("Hit", "Band A", 40)))
        }
        val w = api("POST", "wrapped", """{"source":"tidal"}""").obj
        assertEquals("Band T", w["topArtists"]!!.jsonArray[0].jsonObject.s("name"))
        assertEquals("Hit", w["topTracks"]!!.jsonArray[0].jsonObject.s("title"))
    }

    // ------------------------------------------------------------------ poster

    @Test fun thePosterListsTheMostPresentArtistsWithoutPlays() {
        val r = api("GET", "poster?service=csv&playlist=Mix&layout=bold")
        assertEquals(r.text, 200, r.code)
        val o = r.obj
        assertEquals("Mix", o.s("title")); assertEquals("bold", o.s("layout")); assertEquals("0", o.s("fromPlays"))
        assertEquals(6, o["tracks"]!!.jsonArray.size)
        assertEquals("Band A", o["lineup"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(o["palette"]!!.jsonObject.s("ground")!!.matches(Regex("#[0-9A-F]{6}")))
        assertTrue(o.s("note")!!.contains("Connect a listening service"))
        assertTrue(o.s("coverPrompt")!!.contains("\"Mix\""))
        assertEquals(400, api("GET", "poster?service=csv&playlist=Mix&layout=huge").code)
        assertEquals(404, api("GET", "poster?service=csv&playlist=Nope").code)
        assertEquals(400, api("GET", "poster?service=csv").code)
        val p = page("/tools/poster?service=csv&playlist=Mix&layout=minimal").body()
        assertTrue(p.contains("class=\"poster\"") && p.contains("Print or save as PDF"))
    }

    @Test fun thePosterPutsTheSongsPlayedMostFirst() {
        lastFm(listOf(play("Six", "Band D", 90), play("Three", "Band B", 50), play("Not here", "Band Z", 99)))
        val o = api("GET", "poster?service=csv&playlist=Mix").obj
        assertEquals("2", o.s("fromPlays"))
        assertEquals("Last.fm", o.s("playsFrom"))
        val tracks = o["tracks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Six", "Three"), tracks.take(2).map { it.s("title") })
        assertEquals("true", tracks[0].s("fromPlays")); assertEquals("false", tracks[2].s("fromPlays"))
        val (code, text) = cli("poster", "csv", "Mix", "--layout", "classic")
        assertEquals(text, 0, code)
        assertTrue(text, text.contains("1. Band D - Six  *"))
        assertTrue("get_playlist_poster" in Mcp.toolNames)
        val (ok, json) = mcp("get_playlist_poster", buildJsonObject { put("service", "csv"); put("playlist", "Mix") })
        assertTrue(json, ok && json.contains("\"playsFrom\": \"Last.fm\""))
    }

    // ------------------------------------------------------------------ followed artists

    @Test fun artistsAreFollowedMutedAndUnfollowedThroughTheApi() {
        assertEquals(0, api("GET", "followed").obj["artists"]!!.jsonArray.size)
        assertEquals(409, api("POST", "followed/check").code)
        val f = api("POST", "followed", """{"name":"Muse","merch":false}""")
        assertEquals(f.text, 201, f.code)
        assertEquals("false", f.obj["artist"]!!.jsonObject["kinds"]!!.jsonObject.s("merch"))
        assertEquals(200, api("POST", "followed", """{"name":"muse"}""").code)
        assertEquals(400, api("POST", "followed", """{"name":"X","releases":false,"concerts":false,"merch":false,"records":false}""").code)
        assertEquals(201, api("POST", "followed", """{"name":"AC/DC"}""").code)
        val muted = api("PATCH", "followed/Muse", """{"muted":true,"records":false}""").obj
        assertEquals("true", muted.s("muted")); assertEquals("false", muted["kinds"]!!.jsonObject.s("records"))
        val state = api("PATCH", "followed", """{"every":"week","enabled":false}""").obj
        assertEquals("week", state.s("every")); assertEquals("false", state.s("enabled"))
        assertEquals(listOf("AC/DC", "Muse"), state["artists"]!!.jsonArray.map { it.jsonObject.s("name") })
        assertEquals(400, api("PATCH", "followed", """{"every":"hour"}""").code)
        assertEquals(200, api("DELETE", "followed/AC%2FDC").code)
        assertEquals(404, api("DELETE", "followed/Nobody").code)
        // Kept in the settings, as the app keeps them: a set-up file carries them.
        assertEquals(listOf("Muse"), env.store.data.settings.followedArtists.map { it.name })
        assertTrue(env.store.data.settings.artistAlertsWeekly)
    }

    @Test fun theCommandLineThePageAndTheAssistantFollow() {
        val (code, text) = cli("follow", "Architects", "--kinds", "releases,concerts")
        assertEquals(text, 0, code)
        assertTrue(text, text.contains("Following Architects (releases, concerts)"))
        assertEquals(2, cli("follow", "X", "--kinds", "gigs").first)
        assertTrue(cli("followed").second.contains("Architects  (releases, concerts)"))
        assertEquals(0, cli("followed", "mute", "Architects").first)
        assertTrue(cli("followed").second.contains("muted"))
        assertEquals(0, cli("followed", "weekly").first)
        assertEquals(0, cli("unfollow", "architects").first)
        assertEquals(1, cli("unfollow", "architects").first)
        assertEquals(303, post("/tools/alerts", mapOf("action" to "follow", "artist" to "Bring Me The Horizon", "releases" to "on")).statusCode())
        assertTrue(page("/tools/alerts").body().contains("Bring Me The Horizon"))
        for (t in listOf("list_followed_artists", "follow_artist", "unfollow_artist", "check_followed_artists")) assertTrue(t, t in Mcp.toolNames)
        assertTrue(mcp("follow_artist", buildJsonObject { put("artist", "Sleep Token"); put("kinds", "merch") }).first)
        assertTrue(mcp("list_followed_artists", JsonObject(emptyMap())).second.contains("Sleep Token"))
        assertTrue(mcp("unfollow_artist", buildJsonObject { put("artist", "Sleep Token") }).first)
    }

    @Test fun theLookTakesNoteFirstThenTellsTheNewsThroughTheChannels() {
        val mock = MockWebServer().apply { start() }
        try {
            mock.enqueue(MockResponse().setBody("{}"))
            val c = Notify.build(Notify.newId(), Notify.Kind.GOTIFY, "Phone", true, setOf(Notify.Event.RELEASES), mapOf("server" to mock.url("/").toString().trimEnd('/'), "token" to "tok-1234"), null).getOrThrow()
            Notify.put(env, c)
            ServerAlerts.follow(env, "Muse")
            var releases = listOf("Old")
            val lookup = ArtistAlerts.Lookup { _, f -> ArtistAlerts.Found(releases = releases.map { ArtistAlerts.News(f.name, ArtistAlerts.Kind.RELEASE, "r|$it", it, "ALBUM", "https://example.org/$it") }) }
            val first = runBlocking { ServerAlerts.check(env, manual = true, lookup = lookup, gapMs = 0) }
            assertEquals(1, first.checked); assertTrue(first.notify.isEmpty())
            releases = listOf("Old", "New")
            val second = runBlocking { ServerAlerts.check(env, manual = true, lookup = lookup, gapMs = 0) }
            assertEquals(listOf("New"), second.notify.map { it.title })
            val sent = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(sent.body.readUtf8().contains("https://example.org/New"))
            assertEquals("New", api("GET", "followed").obj["latest"]!!.jsonArray.single().jsonObject.s("title"))
            // The scheduler waits before looking again.
            assertFalse(ServerAlerts.isDue(env, System.currentTimeMillis()))
            assertTrue(ServerAlerts.isDue(env, System.currentTimeMillis() + 7 * 3_600_000L))
        } finally {
            mock.shutdown()
        }
    }

    @Test fun eachPersonFollowsTheirOwnArtists() {
        val other = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://127.0.0.1")
        ServerAlerts.follow(env, "Muse")
        assertTrue(ServerAlerts.followed(other).isEmpty())
        ServerAlerts.follow(other, "Blur")
        assertEquals(listOf("Muse"), ServerAlerts.followed(env).map { it.name })
        assertEquals(listOf("Blur"), ServerAlerts.followed(other).map { it.name })
    }

    // ------------------------------------------------------------------ Spotify (web) concerts

    @Test fun spotifyIsAskedOnlyForAPersonWithTheirOwnSignIn() {
        // Without a Spotify (web) account connected nothing is asked, inside a search or outside one.
        assertNull(runBlocking { SpotifyConcertsServer.asking(env) { SpotifyConcerts.reader?.dates("Muse") } })
        assertNull(runBlocking { SpotifyConcerts.reader?.dates("Muse") })
        val e = Concerts.Event("Muse", "Muse", "2026-12-01", "21:00", "Milano", "Forum", "IT", SpotifyConcerts.pageUrl("spotify:concert:abc"), Concerts.Source.SPOTIFY)
        val r = Concerts.Result("IT", listOf(Concerts.ArtistDates("Muse", listOf(e), emptyList())), total = 1, ticketmaster = false, ownKey = false, quotaHit = false, keyRefused = false)
        val o = concertsJson(r)
        assertTrue(o.s("attribution")!!.endsWith(ConcertTexts.SPOTIFY))
        assertFalse(o.s("attribution")!!.contains("Vivaticket"))
        assertEquals("On Spotify (unofficial)", ConcertTexts.tickets(Concerts.Source.SPOTIFY))
    }

    // ------------------------------------------------------------------ the switch

    @Test fun withTheSwitchOffNothingOfItIsThere() {
        assertTrue(api("GET", "openapi.json").text.contains("/wrapped"))
        assertTrue(page("/tools").body().contains("/tools/wrapped"))
        Features.v2 = false
        assertTrue(api("GET", "wrapped").text.contains("Unknown call"))
        assertTrue(api("GET", "followed").text.contains("Unknown call"))
        assertFalse(api("GET", "openapi.json").text.contains("/wrapped"))
        assertFalse(page("/tools").body().contains("/tools/wrapped"))
        assertEquals(404, page("/tools/alerts").statusCode())
        assertEquals(2, cli("wrapped").first)
        assertFalse("get_wrapped" in Mcp.toolNames)
        assertFalse(ServerAlerts.isDue(env.also { ServerAlerts.follow(it, "Muse") }, Long.MAX_VALUE))
        // What was there before still answers.
        assertEquals(200, api("GET", "status").code)
    }
}

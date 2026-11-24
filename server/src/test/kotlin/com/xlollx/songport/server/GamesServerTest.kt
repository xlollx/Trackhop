package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.providers.Providers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** The games on Desktop and Docker: which connectors each package has, the song folder, the osu! key, the truck games' radio API. */
class GamesServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings.load("en"), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() { server.stop(0); installServerConnectors() }

    private fun get(path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String) =
        client.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", base).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun api(method: String, path: String, body: String? = null): HttpResponse<String> {
        val token = Api.newToken(env)
        return client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    @Test fun everyPackageHasTheOfficialGamesOnlyTheFullOneFortnite() {
        installServerConnectors(officialOnly = true)
        listOf("osu", "beatsaber", "gamefolder", "musicbrainz").forEach { assertNotNull(it, Providers.byId(it)) }
        assertNull(Providers.byId("fortnite"))
        installServerConnectors()
        assertNotNull(Providers.byId("fortnite"))
    }

    @Test fun aRhythmGameFolderBecomesPlaylists() {
        val songs = tmp.newFolder("Songs")
        File(songs, "Pack/One").apply { mkdirs() }.resolve("song.ini").writeText("[song]\nname=One (TV Size)\nartist=Band\n")
        File(songs, "Two").apply { mkdirs() }.resolve("song.ini").writeText("[song]\nname=Two\nartist=Other\n")
        assertEquals(303, post("/connectors/add", "service=gamefolder").statusCode())
        val page = get("/connectors/gamefolder").body()
        assertTrue(page, page.contains("""<input type="text" name="url" autocomplete="off" required>"""))
        val done = post("/connectors/gamefolder/connect", "url=" + URLEncoder.encode(songs.path, "UTF-8"))
        assertTrue(done.headers().firstValue("Location").orElse(""), done.headers().firstValue("Location").orElse("").contains("ok="))
        val lists = Json.parseToJsonElement(api("GET", "services/gamefolder/playlists").body()).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("All songs", "Pack"), lists)
        val tracks = Json.parseToJsonElement(api("GET", "services/gamefolder/playlists/pack:Pack/tracks").body()).jsonArray
        assertEquals("One", tracks.single().jsonObject["title"]!!.jsonPrimitive.content)
        // A folder that is not there is said in words.
        val missing = post("/connectors/gamefolder/connect", "url=" + URLEncoder.encode(File(songs, "nope").path, "UTF-8"))
        assertTrue(missing.headers().firstValue("Location").orElse("").contains("err="))
    }

    @Test fun osuAsksForTheUsersOwnKeyFirst() {
        assertEquals(303, post("/connectors/add", "service=osu").statusCode())
        val page = get("/connectors/osu").body()
        assertTrue(page, page.contains("""action="/connectors/osu/settings""""))
        assertTrue(page.contains("Sign in at osu.ppy.sh"))
        assertTrue(page.contains("""name="clientSecret""""))
        assertEquals(303, post("/connectors/osu/settings", "clientId=1&clientSecret=s").statusCode())
        val after = get("/connectors/osu").body()
        assertTrue(after, after.contains("""name="user""""))
    }

    @Test fun truckRadioThroughTheApi() {
        val game = File(data, "Euro Truck Simulator 2").apply { mkdirs() }
        val sii = File(game, "live_streams.sii")
        sii.writeText("SiiNunit\n{\nlive_stream_def : _nameless.1 {\n stream_data: 1\n stream_data[0]: \"http://one.example/|One|Rock|EN|128|0\"\n}\n}\n")
        val list = Json.parseToJsonElement(api("GET", "truck-radio").body()).jsonArray
        val mine = list.map { it.jsonObject }.single { it["file"]!!.jsonPrimitive.content == sii.path }
        assertEquals("Euro Truck Simulator 2", mine["game"]!!.jsonPrimitive.content)
        assertEquals("One", mine["stations"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(400, api("POST", "truck-radio", """{"stream":"ftp://x","name":"X"}""").statusCode())
        val added = api("POST", "truck-radio", """{"stream":"https://two.example/","name":"Two","game":"Euro Truck Simulator 2"}""")
        assertEquals(added.body(), 200, added.statusCode())
        assertTrue(sii.readText().contains("stream_data[1]: \"https://two.example/|Two|||128|0\""))
        assertTrue(File(game, "live_streams.sii.bak").isFile)
    }
}

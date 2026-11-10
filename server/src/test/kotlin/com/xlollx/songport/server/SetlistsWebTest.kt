package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.sync.Setlists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Setlists on the server: the API's questions and answer, the command line, the MCP tool, a
 * setlist saved as a File playlist from the page, and the setlist.fm key in Settings. The lookups
 * themselves are in :core's SetlistsTest; here there is no key or the setlist is already kept, so
 * nothing leaves the machine.
 */
class SetlistsWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

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
        // Tracks without an artist: no artist to look up, so no request to Ticketmaster or MusicBrainz.
        File(data, "csv/Quiet.csv").writeText("title,artist\nIntro,\nOutro,\n")
    }

    @After fun stop() {
        server.stop(0)
        LocalKey.reset()
    }

    private fun api(path: String) = client.send(
        HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
    private fun post(path: String, body: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", base)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun getPage(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private val concert = Setlists.Setlist(
        "63de4613", "Muse", "2026-11-08", "Forum", "Milano", "IT", "Big Tour", "https://www.setlist.fm/setlist/muse/2026/forum-63de4613.html",
        listOf(
            Setlists.Song("Intro", tape = true), Setlists.Song("Hysteria"), Setlists.Song("Feeling Good", by = "Nina Simone"),
            Setlists.Song("Knights of Cydonia", encore = 1),
        ),
    )

    @Test fun theApiAsksForAPlaylistAndSaysWhenThereIsNoKey() {
        assertEquals(400, api("setlists?service=csv").statusCode())
        assertEquals(404, api("setlists?service=csv&playlist=Nope").statusCode())
        assertEquals(404, api("setlists?service=nope&playlist=Quiet").statusCode())
        val r = api("setlists?service=csv&playlist=quiet")
        assertEquals(r.body(), 200, r.statusCode())
        val o = Json.parseToJsonElement(r.body()).jsonObject
        assertEquals("false", o.s("key"))
        assertEquals(0, o["artists"]!!.jsonArray.size)
        assertEquals(listOf(SetlistTexts.NO_KEY), o["notes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(o.s("attribution")!!.contains("setlist.fm"))
        assertTrue(api("openapi.json").body().contains("/setlists"))
    }

    @Test fun aResultCarriesTheSongsTheTracksAndTheLink() {
        val r = Setlists.Result(listOf(Setlists.ArtistSetlists("Muse", listOf(concert)), Setlists.ArtistSetlists("Nobody", emptyList())), total = 60, hasKey = true, ownKey = false, quotaHit = true, keyRefused = false)
        val o = setlistsJson(r)
        val s = o["artists"]!!.jsonArray[0].jsonObject["setlists"]!!.jsonArray[0].jsonObject
        assertEquals(concert.url, s.s("url"))
        assertEquals(listOf("Muse - Hysteria", "Nina Simone - Feeling Good", "Muse - Knights of Cydonia"), s["tracks"]!!.jsonArray.map { it.jsonPrimitive.content })
        val songs = s["songs"]!!.jsonArray.map { it.jsonObject }
        assertEquals("true", songs[0].s("tape")); assertEquals("Nina Simone", songs[2].s("coverOf")); assertEquals("1", songs[3].s("encore"))
        val notes = o["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(SetlistTexts.QUOTA, notes.first())
        assertTrue(notes.toString(), notes.any { it.contains("all 60") })
    }

    @Test fun theCommandLineAndTheAssistantAsk() {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(listOf("setlists", "csv", "Quiet"), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        val text = out.toString("UTF-8")
        assertEquals(err.toString("UTF-8"), 0, code)
        assertTrue(text, text.contains(SetlistTexts.NO_KEY) && text.contains("setlist.fm"))
        assertTrue("find_setlists" in Mcp.toolNames)
    }

    @Test fun aSetlistPickedOnThePageBecomesAFilePlaylist() {
        Setlists.saveKey(env, "MINE")
        // Kept from the search that listed it: no call to setlist.fm.
        env.keyValues("setlists")["s|${concert.id}"] = "${System.currentTimeMillis()}|" + Json.encodeToString(Setlists.Setlist.serializer(), concert)
        val r = post("/tools/setlists", "action=file&setlist=${concert.id}")
        assertEquals(303, r.statusCode())
        val task = r.headers().firstValue("Location").get()
        var page = ""
        for (i in 0 until 50) {
            page = getPage(task).body()
            if (page.contains("File playlist")) break
            Thread.sleep(100)
        }
        assertTrue(page, page.contains("3 songs saved as the File playlist"))
        assertTrue(page, page.contains("Setlists from setlist.fm"))
        val file = File(data, "csv").listFiles()!!.single { it.name.startsWith("Muse") }
        val text = file.readText()
        assertTrue(text, text.contains("Feeling Good") && text.contains("Nina Simone") && !text.contains("Intro"))
    }

    @Test fun theKeyIsSavedInSettingsAndNeverShown() {
        assertEquals(303, post("/settings", "action=setlistfm&key=+MySetlistKey+").statusCode())
        assertEquals("MySetlistKey", Setlists.ownKey(env))
        val page = getPage("/settings").body()
        assertTrue(page.contains("setlist.fm key"))
        assertFalse(page.contains("MySetlistKey"))
        post("/settings", "action=setlistfm&key=")
        assertEquals("MySetlistKey", Setlists.ownKey(env))
        post("/settings", "action=setlistfm&key=&remove=1")
        assertEquals(null, Setlists.ownKey(env))
    }
}

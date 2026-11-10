package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.sync.Concerts
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
 * Concerts on the server: the API's questions and answer, the command line, the MCP tool and the
 * Ticketmaster key in Settings. The lookups themselves are in :core's ConcertsTest; here the
 * playlist has no artist, so nothing leaves the machine.
 */
class ConcertsWebTest {
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

    @Test fun theApiAsksForAPlaylistAndACountryAndAnswersWithNotesAndAttribution() {
        assertEquals(400, api("concerts?service=csv").statusCode())
        assertEquals(400, api("concerts?service=csv&playlist=Quiet&country=Italy").statusCode())
        assertEquals(404, api("concerts?service=csv&playlist=Nope&country=IT").statusCode())
        assertEquals(404, api("concerts?service=nope&playlist=Quiet").statusCode())
        val r = api("concerts?service=csv&playlist=quiet&country=it")
        assertEquals(r.body(), 200, r.statusCode())
        val o = Json.parseToJsonElement(r.body()).jsonObject
        assertEquals("IT", o.s("country"))
        assertEquals("false", o.s("ticketmaster"))
        assertEquals(0, o["artists"]!!.jsonArray.size)
        val notes = o["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(notes.toString(), ConcertTexts.NO_KEY in notes && ConcertTexts.GAP_IT in notes)
        assertEquals("Concert data from MusicBrainz.", o.s("attribution"))
        // The call is documented.
        assertTrue(api("openapi.json").body().contains("/concerts"))
    }

    @Test fun aResultCarriesEachDateItsSourceAndTheSearchesOfArtistsWithout() {
        val e = Concerts.Event("Muse", "Muse live", "2026-12-01", "21:00", "Milano", "Forum", "IT", "https://www.ticketmaster.it/event/1", Concerts.Source.TICKETMASTER)
        val r = Concerts.Result(
            "IT", listOf(Concerts.ArtistDates("Muse", listOf(e), emptyList()), Concerts.ArtistDates("Nobody", emptyList(), Concerts.searches("Nobody", "IT"))),
            total = 90, ticketmaster = true, ownKey = false, quotaHit = true, keyRefused = false,
        )
        val o = concertsJson(r)
        val muse = o["artists"]!!.jsonArray[0].jsonObject["events"]!!.jsonArray[0].jsonObject
        assertEquals("Ticketmaster", muse.s("source")); assertEquals("Milano", muse.s("city")); assertEquals("21:00", muse.s("time"))
        val sites = o["artists"]!!.jsonArray[1].jsonObject["searches"]!!.jsonArray.map { it.jsonObject.s("site") }
        assertEquals(listOf("TicketOne", "Vivaticket", "Official site"), sites)
        val notes = o["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(ConcertTexts.QUOTA, notes.first())
        assertTrue(notes.toString(), notes.any { it.contains("all 90") })
        assertEquals("Concert data from Ticketmaster and MusicBrainz.", o.s("attribution"))
    }

    @Test fun theCommandLineAndTheAssistantAsk() {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(listOf("concerts", "csv", "Quiet", "--country", "IT"), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        val text = out.toString("UTF-8")
        assertEquals(err.toString("UTF-8"), 0, code)
        assertTrue(text, text.contains("No upcoming dates found in IT.") && text.contains(ConcertTexts.GAP_IT) && text.contains("Concert data from MusicBrainz."))
        assertTrue("find_concerts" in Mcp.toolNames)
    }

    @Test fun theKeyIsSavedInSettingsAndNeverShown() {
        val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
        fun post(body: String) = client.send(
            HttpRequest.newBuilder(URI("$base/settings")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(303, post("action=ticketmaster&key=+MyConsumerKey123+").statusCode())
        assertEquals("MyConsumerKey123", Concerts.ownKey(env))
        val page = client.send(HttpRequest.newBuilder(URI("$base/settings")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(page.contains("Your own key is saved."))
        assertFalse(page.contains("MyConsumerKey123"))
        // Kept with the sign-ins: never in the set-up file the Config page exports.
        assertFalse(com.xlollx.songport.data.ConfigBackup.export(env, "test").contains("MyConsumerKey123"))
        // Saving with the field empty keeps it; Remove takes it away.
        post("action=ticketmaster&key=")
        assertEquals("MyConsumerKey123", Concerts.ownKey(env))
        post("action=ticketmaster&key=&remove=1")
        assertEquals(null, Concerts.ownKey(env))
    }
}

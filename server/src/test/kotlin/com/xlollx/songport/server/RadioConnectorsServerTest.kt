package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Radio stations and Shazam as connectors on Desktop/Docker (2.2): the radio syncs a server kept
 * are brought to their station's connector at start, and the API and the command line add a
 * station and make a sync from it, as the app does.
 */
class RadioConnectorsServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var server: HttpServer
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()

    private val virgin = "http://icecast.unitedradio.it/Virgin.mp3"
    private val r105 = "https://icy.unitedradio.it/Radio105.mp3"

    private fun boot(store: String? = null) {
        data = tmp.newFolder("data")
        store?.let { File(data, "store.json").writeText(it) }
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        token = Api.newToken(env)
        LocalKey.write(data, server.address.port)
        File(data, "csv").mkdirs()
        File(data, "csv/Heard.csv").writeText("title,artist\n")
    }

    @After fun stop() {
        if (::server.isInitialized) server.stop(0)
        LocalKey.reset()
    }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
        val arr: JsonArray get() = Json.parseToJsonElement(text).jsonArray
    }

    private fun call(method: String, path: String, body: String? = null): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/api/v1/$path")).header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        return code to (out.toString("UTF-8") + err.toString("UTF-8")).replace("\r\n", "\n")
    }

    @Test fun theServersRadioAndShazamSyncsAreMovedToTheirConnectorsAtStart() {
        val old = """{"jobs":[
 {"id":"r1","name":"Virgin","source":{"provider":"csv","playlistId":"Radio - Virgin","playlistName":"Radio - Virgin"},"target":{"provider":"csv","playlistName":"V"},
  "radio":{"station":"Virgin Radio","stream":"$virgin","days":[1,2,3],"startMinute":420,"endMinute":540},"ignoredSourceIds":["a"],"color":"teal"},
 {"id":"s1","name":"Shazams","source":{"provider":"spotify","playlistId":"shz","playlistName":"My Shazam Tracks"},"target":{"provider":"csv","playlistName":"S"},"schedule":"HOURLY","shazam":true}
],"settings":{"connectors":["spotify","csv"],"connectorsInit":true}}"""
        boot(old)
        val r1 = env.store.job("r1")!!
        assertEquals("radio", r1.via)
        assertEquals(RadioSource("Virgin Radio", virgin, null, listOf(1, 2, 3), 420, 540), r1.radio)
        assertEquals(PlaylistRef("csv", "Radio - Virgin", "Radio - Virgin"), r1.source)
        assertEquals(listOf("a"), r1.ignoredSourceIds)
        assertEquals("shazam", env.store.job("s1")!!.via)
        // On the Services list and in the API, with the station; the syncs say which connector they come through.
        val services = call("GET", "services").arr.map { it.jsonObject }
        val radio = services.first { it.s("id") == "radio" }
        assertEquals("Virgin Radio", radio.s("name"))
        assertEquals(virgin, radio.s("stream"))
        assertEquals("1", radio.s("syncs"))
        assertNotNull(services.firstOrNull { it.s("id") == "shazam" })
        assertEquals("radio", call("GET", "syncs/r1").obj.s("via"))
        // In use: not taken away.
        assertEquals(409, call("DELETE", "services/radio").code)
        assertEquals(409, call("DELETE", "services/shazam").code)
    }

    @Test fun aStationIsAddedAndASyncMadeFromItThroughTheApi() {
        boot()
        val added = call("POST", "services/radio", """{"station":"Radio 105","stream":"$r105","name":"105","color":"red"}""")
        assertEquals(added.text, 201, added.code)
        assertEquals("radio", added.obj.s("id"))
        assertEquals("105", added.obj.s("name"))
        // The same stream again is the same connector; another station another one.
        assertEquals(200, call("POST", "services/radio", """{"station":"105","stream":"$r105/"}""").code)
        val second = call("POST", "services/radio", """{"station":"Virgin Radio","stream":"$virgin"}""")
        assertEquals("radio@2", second.obj.s("id"))
        assertEquals(400, call("POST", "services/radio", """{"station":"X","stream":"ftp://x"}""").code)
        val made = call("POST", "syncs", """{"source":{"service":"radio@2"},"target":{"service":"csv","playlist":"Heard"},"radio":{"days":[6,7],"start":"22:00","end":"02:00"}}""")
        assertEquals(made.text, 201, made.code)
        val job = env.store.job(made.obj.s("id")!!)!!
        assertEquals("radio@2", job.via)
        assertEquals(RadioSource("Virgin Radio", virgin, null, listOf(6, 7), 22 * 60, 2 * 60), job.radio)
        assertEquals(LocalFilesProvider.id, job.source.provider)
        assertTrue(File(data, "csv/${job.source.playlistId}.csv").exists())
        assertEquals(PlaylistRef("csv", "Heard", "Heard"), job.target)
        assertEquals("radio@2", made.obj.s("via"))
        // Its window changes through PATCH, as on its page.
        val patched = call("PATCH", "syncs/${job.id}", """{"radio":{"start":"06:00"}}""")
        assertEquals(patched.text, 200, patched.code)
        assertEquals(6 * 60, env.store.job(job.id)!!.radio!!.startMinute)
        // The unused one goes, with its station; the used one stays.
        assertEquals(409, call("DELETE", "services/radio@2").code)
        assertEquals(200, call("DELETE", "services/radio").code)
        assertNull(env.store.data.settings.radioStations["radio"])
        // Shazam: one connector, added once.
        assertEquals(201, call("POST", "services/shazam").code)
        assertEquals(200, call("POST", "services/shazam").code)
        assertEquals(1, env.store.data.settings.connectors.count { it == "shazam" })
        // With no Spotify signed in, a Shazam sync says what to do.
        val shazam = call("POST", "syncs", """{"source":{"service":"shazam"},"target":{"service":"csv"}}""")
        assertEquals(409, shazam.code)
        assertTrue(shazam.text, shazam.text.contains("Spotify"))
    }

    @Test fun aStationsSiteIsALinkOnlyForAnHttpAddress() {
        assertEquals(""" · <a href="https://radio.example/" target="_blank" rel="noopener">site</a>""", radioSiteLink("https://radio.example/"))
        assertEquals(""" · <a href="http://radio.example/a?b=1&amp;c=2" target="_blank" rel="noopener">site</a>""", radioSiteLink("http://radio.example/a?b=1&c=2"))
        assertEquals("", radioSiteLink("javascript:alert(1)"))
        assertEquals("", radioSiteLink(" JavaScript:alert(1)"))
        assertEquals("", radioSiteLink("data:text/html,x"))
        assertEquals("", radioSiteLink(""))
        assertEquals("", radioSiteLink(null))
    }

    @Test fun anAddedStationsStreamAddressIsNotWrittenToTheLog() {
        boot()
        Log.reset()
        val added = call("POST", "services/radio", """{"station":"Secret FM","stream":"https://radio.example/live?token=s3cr3t"}""")
        assertEquals(added.text, 201, added.code)
        val lines = Log.recent()
        assertTrue(lines.toString(), lines.any { it.contains("added radio") })
        assertTrue(lines.toString(), lines.none { it.contains("s3cr3t") })
    }

    @Test fun theCommandLineAddsAStationAndMakesASyncFromIt() {
        boot()
        val (code, out) = cli("services", "add", "radio", "--stream", r105, "--station", "Radio 105")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("Radio 105 (radio)"))
        val listed = cli("services")
        assertTrue(listed.second, listed.second.contains("radio: Radio 105"))
        val made = cli("new", "radio", "csv", "Heard", "--days", "mon,tue", "--from", "07:00", "--to", "09:30")
        assertEquals(made.second, 0, made.first)
        val job = env.store.data.jobs.single { it.via == "radio" }
        assertEquals(listOf(1, 2), job.radio!!.days)
        assertEquals(7 * 60, job.radio!!.startMinute)
        assertEquals(9 * 60 + 30, job.radio!!.endMinute)
        assertEquals(PlaylistRef("csv", "Heard", "Heard"), job.target)
        // In use: refused; the sync deleted, it goes.
        assertTrue(cli("services", "remove", "radio").first != 0)
        env.store.update { d -> d.copy(jobs = d.jobs.filter { it.id != job.id }) }
        assertEquals(0, cli("services", "remove", "radio").first)
        assertTrue("radio" !in env.store.data.settings.connectors)
    }

    @Test fun aRadioSyncMadeOnTheOldPageIsOnItsConnectorToo() {
        boot()
        val log = kotlinx.coroutines.runBlocking { LocalFilesProvider.createPlaylist(env, "Radio - Test FM", "") }
        env.store.upsertJob(SyncJob("t1", "Test", PlaylistRef(LocalFilesProvider.id, log.id, log.name), PlaylistRef("csv", null, "T"), radio = RadioSource("Test FM", "http://127.0.0.1:9/stream")))
        assertEquals("radio", env.store.job("t1")!!.via)
        assertEquals("Test FM", env.store.data.settings.radioStations["radio"]!!.station)
    }
}

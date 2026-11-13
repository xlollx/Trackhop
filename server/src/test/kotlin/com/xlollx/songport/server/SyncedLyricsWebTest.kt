package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.LrcLib
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
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections

/**
 * Tools › Synced lyrics on the server (1.18): hidden while the release switch is off; on, the report,
 * the look-up on LRCLIB and the save into the mapped music folder, against a stand-in Navidrome and
 * LRCLIB. Artists and songs are made up.
 */
class SyncedLyricsWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var fake: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()
    private val lrclibAsked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val savedApi = LrcLib.api

    @Before fun start() {
        val data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        fake = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                val body = when {
                    path.endsWith("/search3") -> sub(""""searchResult3":{"song":[
                        {"id":"a","title":"Paper Lanterns","artist":"The Velvet Orchard","album":"Night Garden","duration":214,"path":"/music/Velvet Orchard/01 Paper Lanterns.flac"},
                        {"id":"b","title":"Glass Harbour","artist":"The Velvet Orchard","album":"Night Garden","duration":190,"path":"/music/Velvet Orchard/02 Glass Harbour.flac"}]}""")
                    path.endsWith("/getOpenSubsonicExtensions") -> sub(""""openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]""")
                    path.endsWith("/getLyricsBySongId") -> if (ex.requestURI.query.contains("id=a"))
                        sub(""""lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"Already here"}]}]}""")
                    else sub(""""lyricsList":{"structuredLyrics":[]}""")
                    path == "/api/get" -> {
                        lrclibAsked += (ex.requestHeaders.getFirst("User-Agent") ?: "") + " " + ex.requestURI.rawQuery
                        """{"id":1,"trackName":"Glass Harbour","artistName":"The Velvet Orchard","duration":190,"instrumental":false,"plainLyrics":"Tide comes in","syncedLyrics":"[00:05.00] Tide comes in"}"""
                    }
                    else -> sub("")
                }.toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        LrcLib.api = "http://127.0.0.1:${fake.address.port}"
    }

    @After fun stop() {
        server.stop(0); fake.stop(0)
        LrcLib.api = savedApi
        Features.v2 = false
    }

    private fun sub(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1"${if (inner.isEmpty()) "" else ",$inner"}}}"""

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
    }

    private fun call(method: String, path: String): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token")
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun connectNavidrome() {
        env.tokens.set("subsonic", com.xlollx.songport.data.Tokens(accessToken = "pw", userName = "me", extra = mapOf("url" to "http://127.0.0.1:${fake.address.port}")))
        env.store.updateSettings { it.copy(connectors = it.connectors + "subsonic") }
    }

    @Test fun hiddenWhileTheReleaseSwitchIsOff() {
        Features.v2 = false
        connectNavidrome()
        assertEquals(404, call("GET", "tools/nolyrics").code)
        assertEquals("", v2CleanUpCards())
        assertFalse(Cli.COMMANDS.any { it.name == "nolyrics" })
        assertFalse(McpV2.tools.any { it.name == "find_songs_without_lyrics" })
        assertFalse(ApiDocs.CALLS.any { it.path.startsWith("/tools/nolyrics") })
    }

    @Test fun reportLookUpThenSaveIntoTheMappedFolder() {
        Features.v2 = true
        assertTrue(v2CleanUpCards().contains("/tools/nolyrics"))
        assertTrue(Cli.COMMANDS.any { it.name == "nolyrics" })
        assertTrue(McpV2.tools.any { it.name == "find_songs_without_lyrics" })
        assertEquals(400, call("GET", "tools/nolyrics").code)
        connectNavidrome()

        val report = call("GET", "tools/nolyrics")
        assertEquals(report.text, 200, report.code)
        assertEquals("2", report.obj.s("songs"))
        assertEquals("1", report.obj.s("withoutLyrics"))
        assertEquals("files", report.obj.s("support"))
        assertEquals("false", report.obj.s("canAdd"))
        val t = report.obj["tracks"]!!.jsonArray.single().jsonObject
        assertEquals("Glass Harbour", t.s("title"))
        assertEquals("/music/Velvet Orchard/02 Glass Harbour.flac", t.s("path"))

        // Nothing to save before a look-up, and the look-up writes nothing.
        assertEquals(409, call("POST", "tools/nolyrics/save").code)
        val found = call("POST", "tools/nolyrics/lookup?limit=10")
        assertEquals(found.text, 200, found.code)
        assertEquals("synced", found.obj["found"]!!.jsonArray.single().jsonObject.s("lyrics"))
        assertEquals(1, lrclibAsked.size)
        assertTrue(lrclibAsked[0], lrclibAsked[0].startsWith("Trackhop") && lrclibAsked[0].contains("https://github.com/xlollx/Trackhop"))
        assertTrue(lrclibAsked[0].contains("duration=190"))

        // No music folder yet: nothing can be written.
        assertEquals(409, call("POST", "tools/nolyrics/save").code)

        val music = tmp.newFolder("music")
        File(music, "Velvet Orchard").mkdirs()
        File(music, "Velvet Orchard/01 Paper Lanterns.flac").writeBytes(ByteArray(4))
        File(music, "Velvet Orchard/02 Glass Harbour.flac").writeBytes(ByteArray(4))
        assertTrue(ServerLyrics.folderProblem(File(music, "nowhere").path) != null)
        ServerLyrics.setFolder(env, Providers.byId("subsonic")!!, "/music", music.path)
        val saved = call("POST", "tools/nolyrics/save")
        assertEquals(saved.text, 200, saved.code)
        assertEquals("1", saved.obj.s("saved"))
        assertEquals("[00:05.00] Tide comes in\n", File(music, "Velvet Orchard/02 Glass Harbour.lrc").readText())
        assertFalse(File(music, "Velvet Orchard/01 Paper Lanterns.lrc").exists())
        // Once saved, it is not saved again without a new look-up.
        assertEquals(409, call("POST", "tools/nolyrics/save").code)
    }
}

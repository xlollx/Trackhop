package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

/** Trackhop's MCP tools, over stdin/stdout (`trackhop mcp`) and over HTTP (POST /mcp), against a real server. */
class McpTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var data: File
    private lateinit var env: ServerEnv

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        LocalKey.write(data, server.address.port)
        File(data, "csv").mkdirs()
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong One,Band A\nSong Two,Band B\n")
        env.store.upsertJob(SyncJob("j1", "Road trip", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Road trip copy")))
    }

    @After fun stop() {
        server.stop(0)
        LocalKey.reset()
    }

    private fun stdio(vararg messages: String): List<JsonObject> {
        val out = ByteArrayOutputStream()
        val (url, key) = LocalKey.read(data)!!
        Mcp.serveStdio(messages.joinToString("\n").byteInputStream().bufferedReader(), PrintStream(out, true, "UTF-8"), "test") { Cli.Client(url, key) }
        return out.toString("UTF-8").lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun call(id: Int, tool: String, args: String = "{}") =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""

    private fun JsonObject.text() = this["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.failed() = this["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean

    @Test fun anAssistantStartsListsAndCallsTools() {
        val answers = stdio(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            call(3, "list_syncs"),
            call(4, "run_sync", """{"sync":"road trip","wait":true}"""),
            call(5, "get_playlist_tracks", """{"service":"Nowhere","playlist":"Road trip copy"}"""),
            call(6, "get_sync", """{"sync":"Jazz"}"""),
            call(7, "remove_tracks", """{"service":"File","playlist":"Road trip copy"}"""),
        )
        // The notification gets no answer: seven answers for seven requests.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), answers.map { it["id"]!!.jsonPrimitive.content.toInt() })
        val init = answers[0]["result"]!!.jsonObject
        assertEquals("2025-06-18", init["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("trackhop", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val tools = answers[1]["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(Mcp.toolNames, tools.map { it["name"]!!.jsonPrimitive.content })
        val remove = tools.first { it["name"]!!.jsonPrimitive.content == "remove_tracks" }
        assertTrue(remove["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.boolean)
        assertTrue(answers[2].text(), answers[2].text().contains("\"id\": \"j1\""))
        assertFalse(answers[3].text(), answers[3].failed())
        assertTrue(answers[3].text(), answers[3].text().contains("\"added\": 2"))
        assertTrue(File(data, "csv/Road trip copy.csv").readText().contains("Song Two"))
        assertTrue(answers[4].failed())
        assertTrue(answers[4].text(), answers[4].text().contains("no service"))
        assertTrue(answers[5].failed())
        assertTrue(answers[5].text(), answers[5].text().contains("no sync called"))
        assertTrue(answers[6].failed())
        assertTrue(answers[6].text(), answers[6].text().contains("tracks"))
    }

    @Test fun anUnknownMethodOrToolIsAProtocolError() {
        val answers = stdio(
            """{"jsonrpc":"2.0","id":1,"method":"resources/list"}""",
            call(2, "format_disk"),
            "not json",
        )
        assertEquals(-32601, answers[0]["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(-32602, answers[1]["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(-32700, answers[2]["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    private fun post(body: String, auth: String?, origin: String? = null): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/mcp"))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .apply { auth?.let { header("Authorization", it) }; origin?.let { header("Origin", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test fun overHttpWithTheApiTokenOnly() {
        assertEquals(403, post(call(1, "list_syncs"), null).statusCode())
        val token = Api.newToken(env)
        assertEquals(401, post(call(1, "list_syncs"), "Bearer wrong").statusCode())
        val ok = post(call(1, "list_syncs"), "Bearer $token")
        assertEquals(200, ok.statusCode())
        val answer = Json.parseToJsonElement(ok.body()).jsonObject
        assertTrue(answer.text(), answer.text().contains("Road trip"))
        // A notification alone: accepted, no answer.
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", "Bearer $token").statusCode())
        // A page of another site cannot use a browser to reach it.
        assertEquals(403, post(call(2, "list_syncs"), "Bearer $token", origin = "https://evil.example").statusCode())
    }
}

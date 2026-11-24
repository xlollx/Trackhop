package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
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

/** The app's features brought to Desktop and Docker in 2.2, through the API, against a real server with File playlists. */
class ParityApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\nOther Song,Other Band\nSong,Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\n")
    }

    @After fun stop() { server.stop(0); Features.v2 = Features.START }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
        val arr: JsonArray get() = Json.parseToJsonElement(text).jsonArray
    }

    private fun call(method: String, path: String, body: String? = null): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    private fun newSync(extra: String = ""): JsonObject =
        call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"}$extra}""").also { assertEquals(it.text, 201, it.code) }.obj

    @Test fun matchingChoicesAndTheSourceOrderAreSetAndShown() {
        val made = newSync(""","policy":{"strictness":"strict","studioOnly":true},"keepOrder":true""")
        assertEquals("strict", made.o("policy").s("strictness"))
        assertEquals("either", made.o("policy").s("explicit"))
        assertEquals("true", made.o("policy").s("studioOnly"))
        assertEquals("true", made.s("keepOrder"))
        val id = made.s("id")!!
        assertEquals(1, env.store.job(id)!!.policy.strictness)
        // Only what is named changes.
        val edited = call("PATCH", "syncs/$id", """{"policy":{"explicit":"clean","strictness":-1},"keepOrder":false}""").obj
        assertEquals("lenient", edited.o("policy").s("strictness"))
        assertEquals("clean", edited.o("policy").s("explicit"))
        assertEquals("true", edited.o("policy").s("studioOnly"))
        assertEquals("false", edited.s("keepOrder"))
        assertEquals(400, call("PATCH", "syncs/$id", """{"policy":{"strictness":"very"}}""").code)
        assertEquals(400, call("PATCH", "syncs/$id", """{"policy":"strict"}""").code)
        assertTrue(env.store.job(id)!!.policy.explicit == -1)
    }
}

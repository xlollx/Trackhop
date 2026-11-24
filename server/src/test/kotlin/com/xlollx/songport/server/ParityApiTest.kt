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

    @Test fun theServicesListFindsAndFilters() {
        env.store.updateSettings { it.copy(connectors = listOf("csv", "jellyfin", "deezer_bridge")) }
        val all = call("GET", "services").arr.map { it.jsonObject.s("id") }
        assertEquals(listOf("csv", "jellyfin", "deezer_bridge"), all)
        assertEquals(listOf("deezer_bridge"), call("GET", "services?kind=web").arr.map { it.jsonObject.s("id") })
        assertEquals(listOf("csv", "jellyfin"), call("GET", "services?kind=official").arr.map { it.jsonObject.s("id") })
        assertEquals(listOf("jellyfin"), call("GET", "services?q=JELLY").arr.map { it.jsonObject.s("id") })
        assertEquals(400, call("GET", "services?kind=nope").code)
    }

    @Test fun theWeeklyUnplayableCheckIsTurnedOnRunAndDue() {
        assertEquals("false", call("GET", "tools/unplayable/weekly").obj.s("enabled"))
        assertTrue(!UnplayableWeekly.isDue(env, System.currentTimeMillis()))
        assertEquals("true", call("PATCH", "tools/unplayable/weekly", """{"enabled":true}""").obj.s("enabled"))
        assertTrue(env.store.data.settings.unplayableCheck)
        assertTrue(UnplayableWeekly.isDue(env, System.currentTimeMillis()))
        val task = call("POST", "tools/unplayable/weekly/run").also { assertEquals(it.text, 202, it.code) }.obj.s("task")!!
        val deadline = System.currentTimeMillis() + 20_000
        while (call("GET", "tasks/$task").obj.s("done") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val last = call("GET", "tools/unplayable/weekly").obj.o("last")
        assertEquals("No connected service can tell unplayable tracks apart.", last.s("summary"))
        // Looked now: due again in a week.
        assertTrue(!UnplayableWeekly.isDue(env, System.currentTimeMillis()))
        assertTrue(UnplayableWeekly.isDue(env, System.currentTimeMillis() + UnplayableWeekly.EVERY_MS))
        assertEquals(400, call("PATCH", "tools/unplayable/weekly", "{}").code)
    }

    @Test fun toFixListsOnceAndFixesEverywhere() {
        val missing = com.xlollx.songport.model.Track("x1", "Lost Song", listOf("Nobody"))
        val loose = com.xlollx.songport.model.Track("x2", "Song", listOf("Band"))
        val chosen = com.xlollx.songport.model.Track("Band - Song (Live)", "Song (Live)", listOf("Band"))
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("j1", "Mine", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out")))
        env.store.addReport(com.xlollx.songport.model.SyncReport("r1", "j1", "Mine", 1_780_000_000_000, 10, unmatched = listOf(missing.toString()), unmatchedTracks = listOf(missing),
            reviewTracks = listOf(com.xlollx.songport.model.MatchReview(loose, chosen, 0.74))))
        val list = call("GET", "tofix").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals("2", list.s("count"))
        val rows = list["groups"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        val lost = rows.first { it.o("track").s("title") == "Lost Song" }.s("key")!!
        val check = rows.first { it.o("track").s("title") == "Song" }
        assertEquals("to_check", check.s("kind"))
        assertEquals("74", check.s("score"))
        assertEquals("1", call("GET", "tofix?by=service").obj["groups"]!!.jsonArray.size.toString())
        // Later, then back.
        assertEquals("1", call("POST", "tofix", """{"action":"later","keys":["$lost"]}""").obj.s("left"))
        assertEquals(1, call("GET", "tofix").obj["later"]!!.jsonArray.size)
        assertEquals("2", call("POST", "tofix", """{"action":"back","keys":"all"}""").obj.s("left"))
        // Ignored: not searched for again by this sync.
        assertEquals("1", call("POST", "tofix", """{"action":"ignore","keys":["$lost"]}""").obj.s("left"))
        assertTrue("x1" in env.store.job("j1")!!.ignoredSourceIds)
        // The suggestion kept: nothing left.
        assertEquals("0", call("POST", "tofix", """{"action":"use","keys":"all"}""").obj.s("left"))
        assertEquals(400, call("POST", "tofix", """{"action":"use","keys":["nope"]}""").code)
        assertEquals(400, call("POST", "tofix", """{"action":"frob","keys":"all"}""").code)
        assertEquals(400, call("GET", "tofix?by=year").code)
    }
}

package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.TargetLocks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reservations of a playlist through /api/v1/locks, for a phone set to coordinate with this server:
 * the calls, the server's own syncs skipping a playlist reserved elsewhere until the reservation is
 * given back or expires, a reservation refused while a sync of the server writes there, and two
 * devices asking at once.
 */
class LocksTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()
    private var now = 5_000_000L
    private val ids = ArrayList<String>()

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings.load(null), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\n")
        TargetLocks.clock = { now }
    }

    @After fun stop() {
        ids.forEach { TargetLocks.release(it) }
        TargetLocks.clock = System::currentTimeMillis
        server.stop(0)
    }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
        fun s(k: String) = obj[k]?.jsonPrimitive?.content
    }

    private fun call(method: String, path: String, body: String? = null): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body()).also { a -> if (method == "POST" && a.code == 200) ids += a.s("id")!! }
    }

    private fun reserve(target: String, holder: String, ttl: Int? = null) =
        call("POST", "locks", """{"target":"$target","holder":"$holder"${ttl?.let { ",\"ttl\":$it" } ?: ""}}""")

    private fun job() = SyncJob(id = "j", name = "In to Out", source = PlaylistRef("csv", "In", "In"), target = PlaylistRef("csv", "Out", "Out"))
        .also { env.store.upsertJob(it) }

    private fun outRows() = File(data, "csv/Out.csv").readLines().drop(1).filter { it.isNotBlank() }

    @Test fun reserveRenewAndGiveBack() {
        val a = reserve("spotify|abc", "Pixel 8", 120)
        assertEquals(a.text, 200, a.code)
        assertEquals("spotify|abc", a.s("target")); assertEquals("Pixel 8", a.s("holder"))
        assertEquals((now + 120_000).toString(), a.s("until"))
        val id = a.s("id")!!

        val busy = reserve("spotify|abc", "Laptop")
        assertEquals(busy.text, 409, busy.code)
        assertEquals("Pixel 8", busy.s("holder"))
        assertEquals((now + 120_000).toString(), busy.s("until"))

        now += 60_000
        val renewed = call("PUT", "locks/$id", """{"ttl":300}""")
        assertEquals(renewed.text, 200, renewed.code)
        assertEquals((now + 300_000).toString(), renewed.s("until"))
        assertEquals(200, call("DELETE", "locks/$id").code)
        assertEquals(404, call("DELETE", "locks/$id").code)
        assertEquals("ended: take it again", 404, call("PUT", "locks/$id").code)
        assertEquals(200, reserve("spotify|abc", "Laptop").code)

        assertEquals(400, call("POST", "locks", """{"holder":"x"}""").code)
        assertEquals(400, call("POST", "locks", """{"target":"spotify|x"}""").code)
        assertEquals(400, call("POST", "locks", """{"target":"nobar","holder":"x"}""").code)
        assertEquals(400, call("POST", "locks", "not json").code)
    }

    @Test fun theServersSyncSkipsAPlaylistReservedElsewhereUntilItIsFree() = runBlocking {
        val id = reserve("csv|Out", "Pixel 8").s("id")!!
        val r = SyncEngine(env).run(job(), unattended = true)
        assertEquals(null, r.error)
        assertEquals(listOf("Running on Pixel 8: skipped"), r.notes)
        assertTrue("nothing written", outRows().isEmpty())
        assertEquals(200, call("DELETE", "locks/$id").code)
        assertEquals(1, SyncEngine(env).run(job()).added)
        assertEquals(1, outRows().size)
    }

    @Test fun aReservationOfADeviceThatDiedEndsByItself() = runBlocking {
        assertEquals(200, reserve("csv|Out", "Pixel 8", 30).code)
        now += 30_001
        assertEquals(1, SyncEngine(env).run(job()).added)
    }

    @Test fun aReservationIsRefusedWhileTheServerWritesThere() = runBlocking {
        assertEquals(null, TargetLocks.lockRun(listOf("spotify|busy")))
        try {
            val a = reserve("spotify|busy", "Pixel 8")
            assertEquals(a.text, 409, a.code)
            assertEquals("Trackhop server", a.s("holder"))
            assertEquals("Running on Trackhop server.", a.s("error"))
        } finally {
            TargetLocks.unlockRun(listOf("spotify|busy"))
        }
        assertEquals(200, reserve("spotify|busy", "Pixel 8").code)
    }

    @Test fun twoDevicesAskingAtOnceGetOneReservation() {
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val answers = (1..8).map { i -> pool.submit<Answer> { go.await(); reserve("deezer|race", "Device $i") } }
        go.countDown()
        val got = answers.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(got.map { it.code }.toString(), 1, got.count { it.code == 200 })
        assertEquals(7, got.count { it.code == 409 })
        val winner = got.single { it.code == 200 }.s("holder")
        assertTrue(got.filter { it.code == 409 }.all { it.s("holder") == winner })
    }
}

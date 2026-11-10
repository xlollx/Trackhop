package com.xlollx.songport.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections

/** "Coordinate with my server", the phone's side: the calls to the server's /api/v1/locks, against a stand-in server. */
class ServerLocksTest {
    private lateinit var server: MockWebServer
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** method + path of each call, in order. */
    private val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** What each call answers; 200 with a new id by default. */
    private var answer: (RecordedRequest) -> MockResponse = { ok(it) }
    private var ids = 0

    private fun ok(r: RecordedRequest) = MockResponse().setResponseCode(200)
        .setBody(if (r.method == "POST") """{"id":"L${++ids}","target":"x","holder":"Pixel 8","until":1}""" else """{"released":true}""")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += "${request.method} ${request.path}"
                bodies += request.body.readUtf8()
                return answer(request)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun locks(renewEvery: Long = 60_000) =
        ServerLocks(server.url("/").toString(), "th_token", "Pixel 8", { logs += it }, ttlMs = 600_000, renewEveryMs = renewEvery)

    @Test fun aRunHoldsItsPlaylistsAndGivesThemBack() = runBlocking {
        val result = locks().holding(listOf("spotify|a", "tidal|b")) { assertEquals(listOf("POST /api/v1/locks", "POST /api/v1/locks"), calls.toList()); "done" }
        assertEquals("done", result)
        assertEquals(listOf("POST /api/v1/locks", "POST /api/v1/locks", "DELETE /api/v1/locks/L1", "DELETE /api/v1/locks/L2"), calls.toList())
        assertEquals("""{"target":"spotify|a","holder":"Pixel 8","ttl":600}""", bodies[0])
        assertEquals("Bearer th_token", server.takeRequest().getHeader("Authorization"))
        assertTrue(logs.isEmpty())
    }

    @Test fun aPlaylistHeldElsewhereSkipsTheRunAndGivesBackWhatWasTaken() = runBlocking {
        answer = { r -> if (bodies.last().contains("tidal")) MockResponse().setResponseCode(409).setBody("""{"error":"Running on Trackhop server.","holder":"Trackhop server","until":1234}""") else ok(r) }
        var ran = false
        try {
            locks().holding(listOf("spotify|a", "tidal|b")) { ran = true }
            fail("busy")
        } catch (e: TargetBusyException) {
            assertEquals("Trackhop server", e.holder)
            assertEquals(1234L, e.until)
        }
        assertFalse(ran)
        assertEquals(listOf("POST /api/v1/locks", "POST /api/v1/locks", "DELETE /api/v1/locks/L1"), calls.toList())
    }

    @Test fun anUnreachableServerLetsTheRunGoOnAndSaysSoInTheLog() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        var ran = false
        ServerLocks("127.0.0.1:$closed", "th_token", "Pixel 8", { logs += it }).holding(listOf("spotify|a")) { ran = true }
        assertTrue(ran)
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().contains("not reachable") && logs.single().endsWith("syncing without it"))
    }

    @Test fun aRefusedTokenLetsTheRunGoOnToo() = runBlocking {
        answer = { MockResponse().setResponseCode(401).setBody("""{"error":"Send the API token as: Authorization: Bearer <token>"}""") }
        var ran = false
        locks().holding(listOf("spotify|a")) { ran = true }
        assertTrue(ran)
        assertTrue(logs.single(), logs.single().contains("answered 401"))
    }

    @Test fun aLongRunRenewsAndTakesAgainAReservationThatExpired() = runBlocking {
        var renewals = 0
        answer = { r ->
            when {
                // The server forgot the first one (restarted, or it expired): 404, then it is taken again.
                r.method == "PUT" && r.path!!.endsWith("/L1") -> MockResponse().setResponseCode(404).setBody("""{"error":"No such reservation"}""")
                r.method == "PUT" -> { renewals++; MockResponse().setResponseCode(200).setBody("""{"id":"L2","until":2}""") }
                else -> ok(r)
            }
        }
        locks(renewEvery = 50).holding(listOf("spotify|a")) { delay(400) }
        assertTrue(calls.toString(), calls.contains("PUT /api/v1/locks/L1"))
        assertTrue(calls.toString(), renewals >= 1)
        assertEquals("""{"ttl":600}""", bodies[calls.indexOf("PUT /api/v1/locks/L1")])
        assertEquals("the one held at the end is given back", "DELETE /api/v1/locks/L2", calls.last())
        assertFalse(calls.contains("DELETE /api/v1/locks/L1"))
    }

    @Test fun aReservationTakenByAnotherDeviceAfterExpiringIsSaidInTheLog() = runBlocking {
        answer = { r ->
            when {
                r.method == "PUT" -> MockResponse().setResponseCode(404)
                r.method == "POST" && ids >= 1 -> MockResponse().setResponseCode(409).setBody("""{"holder":"Laptop","until":5}""")
                else -> ok(r)
            }
        }
        locks(renewEvery = 50).holding(listOf("spotify|a")) { delay(150) }
        assertTrue(logs.toString(), logs.any { it.contains("spotify|a is now reserved by Laptop") })
        assertFalse("not ours any more: not given back", calls.any { it.startsWith("DELETE") })
    }

    @Test fun filePlaylistsAreEachDevicesOwn() = runBlocking {
        var ran = false
        locks().holding(listOf("csv|Gym")) { ran = true }
        assertTrue(ran)
        assertTrue(calls.isEmpty())
    }

    @Test fun theTestSaysWhatIsWrong() = runBlocking {
        assertEquals(ServerLocks.Check.Ok, locks().check())
        assertTrue(bodies[0], bodies[0].contains("\"ttl\":30"))
        assertEquals("DELETE /api/v1/locks/L1", calls.last())
        answer = { MockResponse().setResponseCode(401) }
        assertEquals(ServerLocks.Check.Token, locks().check())
        answer = { MockResponse().setResponseCode(404).setBody("""{"error":"Unknown call."}""") }
        assertEquals(ServerLocks.Check.Old, locks().check())
        answer = { MockResponse().setResponseCode(403).setBody("""{"error":"The API is off: make a token on the page, Tools › API."}""") }
        assertEquals("The API is off: make a token on the page, Tools › API.", (locks().check() as ServerLocks.Check.Refused).why)
        val closed = ServerSocket(0).use { it.localPort }
        assertTrue(ServerLocks("http://127.0.0.1:$closed", "t", "p", {}).check() is ServerLocks.Check.Unreachable)
    }

    @Test fun anAddressIsReadTheWayPeopleWriteIt() {
        assertEquals("http://nas:8080", ServerLocks.normalize(" nas:8080/ "))
        assertEquals("https://music.example.org", ServerLocks.normalize("https://music.example.org/api/v1/"))
        assertEquals("http://192.168.1.10:8080", ServerLocks.normalize("http://192.168.1.10:8080"))
    }
}

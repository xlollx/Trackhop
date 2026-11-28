package com.xlollx.songport.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 2.2: a personal server at home and away. */
class ServerAddressesTest {
    private val server = MockWebServer()

    @Before fun start() { ServerAddresses.reset(); server.start() }
    @After fun stop() { ServerAddresses.reset(); server.shutdown() }

    /** Nothing listens on port 1: the home address does not answer. */
    private val home = "http://127.0.0.1:1"
    private val away get() = server.url("/").toString().trimEnd('/')

    @Test fun firstChoiceFollowsTheNetwork() {
        ServerAddresses.register(home, "https://music.example.org/")
        assertEquals(home, ServerAddresses.current(home))
        ServerAddresses.onWifi = { false }
        assertEquals("https://music.example.org", ServerAddresses.current(home))
        assertEquals("https://music.example.org/rest/ping?x=1", ServerAddresses.route("$home/rest/ping?x=1"))
        ServerAddresses.onWifi = { true }
        assertEquals("$home/rest/ping", ServerAddresses.route("$home/rest/ping"))
        // Another server, or a longer name that only starts the same, is never rewritten.
        assertEquals("http://127.0.0.1:12/x", ServerAddresses.route("http://127.0.0.1:12/x"))
    }

    @Test fun anUnreachableAddressFailsOverAndTheOtherIsRemembered() = runBlocking {
        ServerAddresses.register(home, away)
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val r = Http.send("POST", "$home/rest/createPlaylist?name=a", body = Http.jsonBody("{}"))
        assertEquals("one", r.body)
        assertEquals("/rest/createPlaylist?name=a", server.takeRequest().path)
        // Next time straight to the one that answered.
        assertEquals(away, ServerAddresses.current(home))
        assertEquals("two", Http.send("GET", "$home/rest/ping").body)
        assertEquals(2, server.requestCount)
    }

    @Test fun withoutASecondAddressTheErrorIsTheCallers() = runBlocking {
        try { Http.send("GET", "$home/x"); throw AssertionError("expected a failure") } catch (e: java.io.IOException) { }
        assertNull(ServerAddresses.failover("$home/x", java.net.ConnectException()))
    }

    @Test fun aTimeoutAfterSendingIsNotSentAgainElsewhere() {
        ServerAddresses.register(home, away)
        assertNull(ServerAddresses.failover("$home/x", java.net.SocketTimeoutException("timeout")))
        assertEquals("$away/x", ServerAddresses.failover("$home/x", java.net.SocketTimeoutException("connect timed out")))
    }

    @Test fun aNewAwayAddressReplacesTheOneBeforeAndWhatWasRememberedOfIt() {
        ServerAddresses.register(home, "https://old.example.org")
        ServerAddresses.answered("https://old.example.org/rest/ping")
        assertEquals("https://old.example.org", ServerAddresses.current(home))
        // The same pair again keeps the one that worked.
        ServerAddresses.register(home, "https://old.example.org")
        assertEquals("https://old.example.org", ServerAddresses.current(home))
        ServerAddresses.register(home, "https://new.example.org")
        assertEquals(home, ServerAddresses.current(home))
        assertEquals("https://new.example.org", ServerAddresses.awayOf(home))
        assertEquals("https://new.example.org/x", ServerAddresses.failover("$home/x", java.net.ConnectException()))
        // The old one is no address of this server any more.
        assertEquals("https://old.example.org/x", ServerAddresses.route("https://old.example.org/x"))
        ServerAddresses.answered("https://old.example.org/x")
        assertEquals(home, ServerAddresses.current(home))
    }

    @Test fun aSignInThatFailsLeavesNoAwayAddressBehind() = runBlocking {
        val p = com.xlollx.songport.providers.SubsonicProvider()
        val env = com.xlollx.songport.contract.ContractEnv()
        // Nothing answers at either address: the sign-in fails.
        val failed = runCatching { p.loginWithAddresses(env, home, "http://127.0.0.1:2", "me", "secret") }
        assertEquals(true, failed.isFailure)
        assertNull(ServerAddresses.awayOf(home))
        assertEquals("$home/rest/ping", ServerAddresses.route("$home/rest/ping"))
        // One registered before stays as it was.
        ServerAddresses.register(home, away)
        runCatching { p.loginWithAddresses(env, home, "http://127.0.0.1:2", "me", "secret") }
        assertEquals(away, ServerAddresses.awayOf(home))
    }

    @Test fun aPostAnsweredWithARedirectThatFailsIsNotSentAgainElsewhere() = runBlocking {
        val first = MockWebServer().apply { start() }
        try {
            val h = first.url("/").toString().trimEnd('/')
            ServerAddresses.register(h, away)
            // The server applied the POST and points elsewhere; that address does not answer.
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/done"))
            val r = runCatching { Http.send("POST", "$h/rest/createPlaylist?name=a", body = Http.jsonBody("{}")) }
            assertEquals(true, r.isFailure)
            assertEquals(0, server.requestCount)
        } finally { first.shutdown() }
    }

    @Test fun homeNetworkAndVpnAddressesGetAShortConnection() {
        for (h in listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "172.31.255.1", "100.70.12.3", "100.64.0.1", "127.0.0.1", "localhost", "nas.local", "fd12::3", "::1")) {
            assertEquals(h, true, ServerAddresses.isLocal(h))
        }
        for (h in listOf("music.example.org", "172.32.0.1", "100.128.0.1", "100.63.255.255", "8.8.8.8", "192.169.0.1", "", null)) {
            assertEquals(h.toString(), false, ServerAddresses.isLocal(h))
        }
        assertEquals(ServerAddresses.LOCAL_CONNECT_MS, ServerAddresses.connectTimeoutMs("http://192.168.1.20:8096/Items"))
        assertEquals(ServerAddresses.LOCAL_CONNECT_MS, ServerAddresses.connectTimeoutMs("http://100.70.1.2:8096"))
        assertNull(ServerAddresses.connectTimeoutMs("https://jellyfin.example.org/Items"))
    }

    @Test fun theAddressThatAnsweredLastIsTheOneShownNotTheNetworksGuess() = runBlocking {
        ServerAddresses.onWifi = { true }
        ServerAddresses.register(home, away)
        // On Wi-Fi the home one is tried first, but nothing has answered yet: no "at home".
        assertEquals(home, ServerAddresses.current(home))
        assertNull(ServerAddresses.answeredLast(home))
        server.enqueue(MockResponse().setBody("ok"))
        Http.send("GET", "$home/System/Info")
        assertEquals(away, ServerAddresses.answeredLast(home))
    }

    @Test fun neitherAddressAnsweringSaysBothAndLogsBothTries() = runBlocking {
        val lines = ArrayList<String>()
        ServerAddresses.log = { lines += it }
        ServerAddresses.register(home, "http://127.0.0.1:2", "Jellyfin")
        ServerAddresses.answered("$home/x")
        val e = runCatching { Http.send("GET", "$home/Items") }.exceptionOrNull()
        val down = NetError.bothDown(e)
        assertEquals("Jellyfin", down?.server)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("home 127.0… no answer") && lines[0].contains("away 127.0… no answer"))
        // Neither answered: the line no longer says where the server was.
        assertNull(ServerAddresses.answeredLast(home))
    }

    @Test fun unregisterForgetsThePair() {
        ServerAddresses.register(home, away)
        ServerAddresses.unregister(home)
        assertEquals("$home/x", ServerAddresses.route("$home/x"))
    }
}

package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.KeepAlive
import com.xlollx.songport.providers.TidalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The refresh race that signed TIDAL out on its own (25/11): several syncs at once renewed the same
 * expiring access with the same refresh token; TIDAL rotates refresh tokens and refuses the second
 * use of one (400), and the connector then cleared the tokens the first renewal had just saved.
 *
 * The fake token endpoint here rotates as TIDAL does: each refresh token works once, a second use
 * is a 400 ("invalid_grant").
 */
class OAuthRefreshRaceTest : ContractTest() {
    private val td = TidalProvider()
    private val refreshes = AtomicInteger()
    private val used = java.util.Collections.synchronizedSet(HashSet<String>())
    /** The access tokens the API takes; any other is a 401. */
    private val valid = java.util.Collections.synchronizedSet(HashSet<String>())
    /** What the token endpoint does with a refresh token (default: rotate). */
    private var onRefresh: (String) -> MockResponse = { rt -> rotate(rt) }

    private fun rotate(rt: String): MockResponse {
        if (!used.add(rt)) return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        val n = refreshes.get()
        valid += "AT$n"
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"access_token":"AT$n","refresh_token":"RT$n","expires_in":3600}""")
            // Slow enough for the other callers to arrive while this one is renewing.
            .setBodyDelay(150, TimeUnit.MILLISECONDS)
    }

    @Before fun signIn() {
        TidalProvider.clearCaches()
        BuildDefaults.values = mapOf("TIDAL_CLIENT_ID" to "tcid")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8()
                if (body.contains("grant_type=refresh_token")) {
                    refreshes.incrementAndGet()
                    val rt = java.net.URLDecoder.decode(body.substringAfter("refresh_token=").substringBefore('&'), "UTF-8")
                    return onRefresh(rt)
                }
                val auth = request.getHeader("Authorization")?.removePrefix("Bearer ")
                if (auth != null && auth !in valid) return MockResponse().setResponseCode(401).setBody("{}")
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/vnd.api+json").setBody("""{"data":[]}""")
            }
        }
    }

    private fun stored() = env.tokens.get("tidal")

    @Test fun syncsRenewingAtOnceRefreshOnceAndStayConnected() = runBlocking {
        // An access about to expire: every sync starting now wants to renew it.
        env.tokens.set("tidal", Tokens("OLD", "RT0", expiresAt = System.currentTimeMillis() + 1_000, userId = "u1", userName = "me", extra = mapOf("country" to "IT")))
        (1..4).map { async(Dispatchers.IO) { td.playlists(env) } }.awaitAll()
        assertEquals("one call to the token endpoint", 1, refreshes.get())
        assertTrue(td.isConnected(env))
        assertEquals("RT1", stored()?.refreshToken)
        assertEquals("AT1", stored()?.accessToken)
    }

    @Test fun refusedAccessRenewedOnceForCallsRefusedTogether() = runBlocking {
        // An access the clock still trusts but the service refuses (401): each call renews on its own.
        env.tokens.set("tidal", Tokens("OLD", "RT0", expiresAt = 0, userId = "u1", userName = "me", extra = mapOf("country" to "IT")))
        (1..3).map { async(Dispatchers.IO) { td.playlists(env) } }.awaitAll()
        assertEquals(1, refreshes.get())
        assertTrue(td.isConnected(env))
        assertEquals("AT1", stored()?.accessToken)
    }

    @Test fun keepAliveAtOnceWithASyncRenewsOnce() = runBlocking {
        env.tokens.set("tidal", Tokens("OLD", "RT0", expiresAt = System.currentTimeMillis() + 1_000, userId = "u1", userName = "me", extra = mapOf("country" to "IT")))
        val outcomes = listOf(
            async(Dispatchers.IO) { td.keepAlive(env) },
            async(Dispatchers.IO) { td.keepAlive(env) },
        ).awaitAll()
        outcomes.forEach { assertEquals(KeepAlive.RENEWED, it) }
        assertEquals(1, refreshes.get())
        assertTrue(td.isConnected(env))
    }

    @Test fun aRealRevocationStillSignsOut() = runBlocking {
        env.tokens.set("tidal", Tokens("OLD", "RT0", expiresAt = System.currentTimeMillis() + 1_000, userId = "u1"))
        used += "RT0" // revoked: the service refuses it
        assertEquals(KeepAlive.EXPIRED, td.keepAlive(env))
        assertNull(stored())
        assertTrue(env.logs.any { it.contains("signed out, a new sign-in is needed") })
    }

    @Test fun aRefusalOfAReplacedRefreshTokenKeepsTheStoredOne() = runBlocking {
        env.tokens.set("tidal", Tokens("OLD", "RT0", expiresAt = System.currentTimeMillis() + 1_000, userId = "u1"))
        // A new sign-in lands while the old refresh token is being refused.
        valid += "NEW"
        onRefresh = { _ ->
            env.tokens.set("tidal", Tokens("NEW", "RTN", expiresAt = System.currentTimeMillis() + 3_600_000, userId = "u1"))
            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        }
        assertEquals(KeepAlive.RENEWED, td.keepAlive(env))
        assertEquals("NEW", stored()?.accessToken)
    }

    @Test fun anOutageKeepsTheSignIn() = runBlocking {
        env.tokens.set("tidal", Tokens("OLD", "RT0", expiresAt = System.currentTimeMillis() + 1_000, userId = "u1"))
        onRefresh = { MockResponse().setResponseCode(503).setBody("down") }
        val out = td.keepAlive(env)
        assertEquals(KeepAlive.State.FAILED, out.state)
        assertNotNull(stored())
        // Logs say the status, never a token.
        assertTrue(env.logs.none { it.contains("RT0") || it.contains("OLD") })
    }

    @Test fun nothingToRenewWithoutASignIn() = runBlocking {
        assertEquals(KeepAlive.NOT_SIGNED_IN, td.keepAlive(env))
        assertEquals(0, refreshes.get())
    }

    @Test fun deezerWithoutRefreshTokenMakesOneSignedInCall() = runBlocking {
        val dz = DeezerProvider()
        env.tokens.set("deezer", Tokens("DZ", userId = "1"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("""{"id":1,"name":"me"}""")
        }
        assertEquals(KeepAlive.RENEWED, dz.keepAlive(env))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("""{"error":{"type":"OAuthException","message":"Invalid OAuth access token.","code":300}}""")
        }
        assertEquals(KeepAlive.EXPIRED, dz.keepAlive(env))
        assertNull(env.tokens.get("deezer"))
    }
}

package com.xlollx.songport.ytmbridge

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The client token is optional: pathfinder answers the bearer alone. A grant Spotify refuses (the
 * 11/2026 reports: HTTP 400, empty body) is asked for once per process, not before every call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpotifyClientTokenTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var web: SpotifyWebClient
    private var grant: () -> MockResponse = { MockResponse().setResponseCode(400) }

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            when (r.getHeader(BridgeMock.ORIGINAL_HOST)) {
                "open.spotify.com" -> MockResponse().setBody("""<html><script id="session" data-testid="session" type="application/json">
                    {"accessToken":"BQ-web-token","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":false,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script>
                    <script>var v="1.3.10.1628.g8ce1b310ea0e";</script></html>""")
                "raw.githubusercontent.com" -> MockResponse().setResponseCode(404)
                "clienttoken.spotify.com" -> grant()
                else -> null
            }
        }
        app = RuntimeEnvironment.getApplication()
        SpotifyBridge.session.clear(app)
        SpotifyBridge.session.save(app, "sp_dc=dc-cookie; sp_t=device-1; sp_key=k", "spuser")
        SpotifyWebClient.dropPage()
        SpotifyWebClient.resetClientToken()
        web = SpotifyWebClient(app)
    }

    @After fun tearDown() { BridgeMock.stop(); SpotifyWebClient.resetClientToken(); SpotifyWebClient.dropPage() }

    private fun grants() = BridgeMock.all.count { it.getHeader(BridgeMock.ORIGINAL_HOST) == "clienttoken.spotify.com" }
    private fun added() = BridgeMock.answer("""{"data":{"addToLibrary":{"__typename":"AddToLibraryResponse"}}}""")

    @Test fun aRefusedGrantIsAskedOnceAndTheCallsGoWithoutIt() {
        repeat(3) { added(); web.addToLibrary(listOf("spotify:track:$it")) }
        assertEquals(1, grants())
        val sent = BridgeMock.calls.filter { it.path!!.contains("pathfinder") }
        assertEquals(3, sent.size)
        sent.forEach { assertNull(it.getHeader("client-token")); assertEquals("Bearer BQ-web-token", it.getHeader("Authorization")) }
    }

    @Test fun aBusyServerIsAskedAgainLaterNotForever() {
        grant = { MockResponse().setResponseCode(503) }
        added(); web.addToLibrary(listOf("spotify:track:1"))
        // Within the minute: not asked again.
        added(); web.addToLibrary(listOf("spotify:track:2"))
        assertEquals(1, grants())
        // After the pause a granted token is used.
        SpotifyWebClient.resetClientToken()
        grant = { MockResponse().setBody("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"CT-granted","expires_after_seconds":1209600,"refresh_after_seconds":1209600}}""") }
        added(); web.addToLibrary(listOf("spotify:track:3"))
        assertEquals(2, grants())
        assertEquals("CT-granted", BridgeMock.calls.last { it.path!!.contains("pathfinder") }.getHeader("client-token"))
    }

    @Test fun aSlowPlayerDoesNotAskToSignInAgain() {
        val slow = SpotifyWebClient.tokenFailure(null)
        assertTrue(slow, slow.contains("timed out")) // the engine retries it as a network hiccup
        assertFalse(slow, slow.contains("sign in", true))
        val failed = SpotifyWebClient.tokenFailure("TypeError: Failed to fetch")
        assertFalse(failed, failed.contains("sign in", true))
    }

    @Test fun anExpiredSessionStillAsksToSignInAgain() {
        val expired = SpotifyWebClient.tokenFailure("the Spotify web session has expired: sign in again")
        assertTrue(expired, expired.contains("sign in again"))
    }
}

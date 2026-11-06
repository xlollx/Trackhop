package com.xlollx.songport.ytmbridge

import android.app.Application
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #34: liked songs failed with "no query hash for addToLibrary". Where a hash comes from, in order:
 * our own list (refreshed hourly from the live player), the public registry, the hashes shipped
 * with the app; and when Spotify refuses one, the player's bundle read right away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpotifyWebHashesTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var web: SpotifyWebClient
    private val reg = "a".repeat(64)
    private val add = "b".repeat(64)
    private var ours: String? = null
    private val pack = "https://open.spotifycdn.com/cdn/build/web-player/web-player.d8d10126.js"

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            when (r.getHeader(BridgeMock.ORIGINAL_HOST)) {
                "open.spotify.com" -> MockResponse().setBody("""<html><script id="session" data-testid="session" type="application/json">
                    {"accessToken":"BQ-web-token","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":false,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script>
                    <script src="$pack"></script><script>var v="1.2.75.312.g5c3ea2d9";</script></html>""")
                "raw.githubusercontent.com" ->
                    if (r.path!!.contains("xlollx")) ours?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    else MockResponse().setBody("""{"operations":{"removeFromLibrary":{"hash":"$reg"},"libraryV3":{"hash":"$reg"}}}""")
                "open.spotifycdn.com" -> MockResponse().setBody("""x={a:["addToLibrary","mutation","$add"]}""")
                "www.spotify.com" -> MockResponse().setBody("""{"profile":{"username":"spuser","name":"Me"}}""")
                "clienttoken.spotify.com" -> MockResponse().setBody("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"CT-granted","expires_after_seconds":1209600,"refresh_after_seconds":1209600}}""")
                else -> null
            }
        }
        app = RuntimeEnvironment.getApplication()
        SpotifyBridge.session.clear(app)
        SpotifyBridge.session.save(app, "sp_dc=dc-cookie; sp_t=device-1; sp_key=k", "spuser")
        SpotifyWebClient.dropPage()
        web = SpotifyWebClient(app)
        web.rememberClientToken("CT-1", System.currentTimeMillis() + 86_400_000)
    }

    @After fun tearDown() { BridgeMock.stop() }

    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())

    private fun sentHash(r: RecordedRequest) = r.json()["extensions"]["persistedQuery"]["sha256Hash"].str

    @Test fun withNoListAnsweringLikedSongsUseTheHashShippedWithTheApp() {
        BridgeMock.answer("""{"data":{"addToLibrary":{"__typename":"AddToLibraryResponse"}}}""")
        web.addToLibrary(listOf("spotify:track:1"))
        assertEquals(SpotifyWebClient.SHIPPED_HASHES["addToLibrary"], sentHash(BridgeMock.calls.last()))
    }

    @Test fun ourHourlyListWinsOverTheShippedHash() {
        val fresh = "c".repeat(64)
        ours = """{"operations":{"addToLibrary":{"hash":"$fresh"}}}"""
        BridgeMock.answer("""{"data":{"addToLibrary":{"__typename":"AddToLibraryResponse"}}}""")
        web.addToLibrary(listOf("spotify:track:1"))
        assertEquals(fresh, sentHash(BridgeMock.calls.last()))
    }

    @Test fun aRefusedHashIsReplacedByTheBundlesRightAway() {
        BridgeMock.answer("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
        BridgeMock.answer("""{"data":{"addToLibrary":{"__typename":"AddToLibraryResponse"}}}""")
        web.addToLibrary(listOf("spotify:track:1"))
        val sent = BridgeMock.calls.filter { it.path!!.contains("pathfinder") }
        assertEquals(SpotifyWebClient.SHIPPED_HASHES["addToLibrary"], sentHash(sent[0]))
        assertEquals(add, sentHash(sent[1]))
        // The next add goes straight out with the good one: the refused hash is not taken back from a list.
        BridgeMock.answer("""{"data":{"addToLibrary":{"__typename":"AddToLibraryResponse"}}}""")
        web.addToLibrary(listOf("spotify:track:2"))
        assertEquals(add, sentHash(BridgeMock.calls.filter { it.path!!.contains("pathfinder") }.last()))
    }

    @Test fun anAddAnsweredWithAServerErrorIsNotSentAgain() {
        // #42: the add may have gone through before the 500; sent again (or by the other route) the
        // same tracks would be there twice. The next sync adds only what is missing.
        BridgeMock.answer("""{"error":"internal"}""", 500)
        val e = runCatching { web.addToPlaylist("pl1", listOf("spotify:track:1", "spotify:track:2")) }.exceptionOrNull()
        org.junit.Assert.assertTrue(e?.message, e?.message?.contains("outcome unknown") == true)
        assertEquals(1, BridgeMock.calls.count { it.path!!.contains("pathfinder") })
        assertEquals(0, BridgeMock.calls.count { it.path!!.contains("/playlist/v2/") })
    }
}

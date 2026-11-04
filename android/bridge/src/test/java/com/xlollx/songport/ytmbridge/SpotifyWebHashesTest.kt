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
 * #34: liked songs failed with "no query hash for addToLibrary" right after a sync had used the
 * registry's hashes: saving those started the half-hour pause on reading the player's bundle, and
 * addToLibrary (not in the registry) was never looked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpotifyWebHashesTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var web: SpotifyWebClient
    private val reg = "a".repeat(64)
    private val add = "b".repeat(64)
    private val pack = "https://open.spotifycdn.com/cdn/build/web-player/web-player.d8d10126.js"

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            when (r.getHeader(BridgeMock.ORIGINAL_HOST)) {
                "open.spotify.com" -> MockResponse().setBody("""<html><script id="session" data-testid="session" type="application/json">
                    {"accessToken":"BQ-web-token","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":false,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script>
                    <script src="$pack"></script><script>var v="1.2.75.312.g5c3ea2d9";</script></html>""")
                // The registry has the queries and one library mutation, not addToLibrary.
                "raw.githubusercontent.com" -> MockResponse().setBody("""{"operations":{"removeFromLibrary":{"hash":"$reg"},"libraryV3":{"hash":"$reg"}}}""")
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

    @Test fun likedSongsFindTheirMutationRightAfterTheRegistryWasUsed() {
        // A registry query first (as a sync's reads do), then liked songs.
        BridgeMock.answer("""{"data":{"removeFromLibrary":{"__typename":"RemoveFromLibraryResponse"}}}""")
        web.deletePlaylist("A")
        BridgeMock.answer("""{"data":{"addToLibrary":{"__typename":"AddToLibraryResponse"}}}""")
        web.addToLibrary(listOf("spotify:track:1"))
        val g = BridgeMock.calls.last()
        assertEquals("addToLibrary", g.json()["operationName"].str)
        assertEquals(add, g.json()["extensions"]["persistedQuery"]["sha256Hash"].str)
    }
}

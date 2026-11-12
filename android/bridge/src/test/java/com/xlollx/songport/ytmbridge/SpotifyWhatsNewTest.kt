package com.xlollx.songport.ytmbridge

import android.app.Application
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The player's "What's new" (queryWhatsNewFeed), in the shape of the player's traffic with made-up
 * releases: what is sent (v1 first, v2 when v1 refuses it, never the operation that marks items as
 * seen, never the player's bundle) and what is read from the answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpotifyWhatsNewTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var web: SpotifyWebClient

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            when (r.getHeader(BridgeMock.ORIGINAL_HOST)) {
                "open.spotify.com" -> MockResponse().setBody("""<html><script id="session" data-testid="session" type="application/json">
                    {"accessToken":"BQ-web-token","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":false,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script>
                    <script>var v="1.2.75.312.g5c3ea2d9";</script></html>""")
                "raw.githubusercontent.com" -> MockResponse().setResponseCode(404)
                "www.spotify.com" -> MockResponse().setBody("""{"profile":{"username":"spuser","name":"Me"}}""")
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
    private fun gqlCalls() = BridgeMock.calls.filter { it.getHeader(BridgeMock.ORIGINAL_HOST) == "api-partner.spotify.com" }

    private fun album(uri: String, name: String, type: String, day: String, vararg artists: String) = """{"content":{"__typename":"AlbumResponseWrapper",
        "data":{"__typename":"Album","artists":{"items":[${artists.joinToString(",") { """{"profile":{"name":"$it"},"uri":"spotify:artist:a${it.length}"}""" }}]},
        "coverArt":{"sources":[{"height":300,"url":"https://i.example/c.jpg","width":300}]},"date":{"isoString":"${day}T00:00:00Z","precision":"DAY"},
        "name":"$name","albumType":"$type","uri":"$uri"}},"id":"item-$name","state":{"state":"NEW","timestamp":{"isoString":"${day}T08:00:00Z"}},
        "timestamp":{"isoString":"${day}T08:00:00Z"}}"""

    private val feed = """{"data":{"whatsNewFeedItems":{"items":[
        ${album("spotify:album:al1", "Paper Lanterns", "SINGLE", "2030-03-14", "Neon Harbour")},
        {"content":{"__typename":"EpisodeResponseWrapper","data":{"__typename":"Episode","uri":"spotify:episode:e1","name":"Talk"}},"id":"item-ep"},
        ${album("spotify:album:al2", "Low Tide", "EP", "2030-03-10", "Ölfa", "Neon Harbour")},
        ${album("spotify:album:al1", "Paper Lanterns", "SINGLE", "2030-03-14", "Neon Harbour")}],
        "pagingInfo":{"limit":50,"nextOffset":null,"offset":0},"totalCount":4}}}"""

    @Test fun theFeedIsAskedAsThePlayerAsksItAndItsAlbumsAreRead() {
        BridgeMock.answer(feed)
        val r = web.whatsNew()
        assertEquals(listOf("spotify:album:al1", "spotify:album:al2"), r.map { it.uri })
        assertEquals("Paper Lanterns", r[0].name)
        assertEquals("SINGLE", r[0].type)
        assertEquals("2030-03-14", r[0].date)
        assertEquals(listOf("Ölfa", "Neon Harbour"), r[1].artists)
        val sent = gqlCalls().single()
        assertEquals("/pathfinder/v1/query", sent.requestUrl!!.encodedPath)
        val b = sent.json()
        assertEquals("queryWhatsNewFeed", b["operationName"].str)
        assertEquals(SpotifyWebClient.SHIPPED_HASHES["queryWhatsNewFeed"], b["extensions"]["persistedQuery"]["sha256Hash"].str)
        val v = b["variables"]
        assertEquals(0L, v["offset"].long)
        assertEquals(50L, v["limit"].long)
        assertEquals("false", v["onlyUnPlayedItems"].toString())
        assertEquals("[]", v["includedContentTypes"].toString())
        assertEquals("true", v["includeEpisodeContentRatingsV2"].toString())
        assertEquals("Bearer BQ-web-token", sent.getHeader("Authorization"))
    }

    @Test fun whenV1RefusesItTheSameCallGoesToV2AndTheBundleIsNeverRead() {
        BridgeMock.answer("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
        BridgeMock.answer(feed)
        assertEquals(2, web.whatsNew().size)
        val (first, second) = gqlCalls()
        assertEquals("/pathfinder/v1/query", first.requestUrl!!.encodedPath)
        assertEquals("/pathfinder/v2/query", second.requestUrl!!.encodedPath)
        assertEquals(first.body.clone().readUtf8(), second.body.clone().readUtf8())
        assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
        assertEquals(first.getHeader("client-token"), second.getHeader("client-token"))
        assertTrue(BridgeMock.all.none { it.getHeader(BridgeMock.ORIGINAL_HOST) == "open.spotifycdn.com" })
        assertTrue(BridgeMock.all.none { it.body.clone().readUtf8().contains("SetItemsStateInWhatsNewFeed") })
    }

    @Test fun anAnswerWithoutTheFeedIsAnErrorAndSoIsARefusalOnBothGateways() {
        BridgeMock.answer("""{"data":{"somethingElse":{}}}""")
        try { web.whatsNew(); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("queryWhatsNewFeed")) }
        BridgeMock.answer("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
        BridgeMock.answer("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
        try { web.whatsNew(); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("PersistedQueryNotFound")) }
        // A request to slow down is not sent again elsewhere.
        val before = gqlCalls().size
        BridgeMock.answer("{}", 429, "Retry-After" to "30")
        try { web.whatsNew(); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("slow down")) }
        assertEquals(before + 1, gqlCalls().size)
    }

    @Test fun theBridgeAnswersWithTheReleases() {
        BridgeMock.answer(feed)
        val b = BridgeCore.call(app, "spotify.whatsNew", null, null)
        assertEquals(null, b.getString("error"))
        val j = parseJson(b.getString("json")!!)
        assertEquals("spotify:album:al1", j.arr[0]["uri"].str)
        assertEquals("EP", j.arr[1]["type"].str)
        assertEquals(listOf("Ölfa", "Neon Harbour"), j.arr[1]["artists"].arr.map { it.str })
    }
}

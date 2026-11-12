package com.xlollx.songport.ytmbridge

import android.app.Application
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Amazon Music's purchased music (LibraryPurchasedPageQuery) and renaming on the GraphQL player
 * (updatePlaylistDetails), in the shapes of the player's traffic, with made-up tracks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AmazonPurchasedTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var amazon: AmazonClient
    private val cookies = "session-id=262-1234567-7654321; ubid-main=133-1; at-main=Atza|" + "x".repeat(40)

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            if (r.getHeader(BridgeMock.ORIGINAL_HOST) == "music.amazon.it" && r.requestUrl!!.encodedPath == "/") MockResponse().setBody("""<html><script>
                amznMusic.appConfig = {"accessToken":"Atna|tok","customerId":"A1CUSTOMER","deviceId":"13700000000000000","deviceType":"A16ZV8BU3SN1N3",
                  "csrf":{"token":"gCSRF+tok=","ts":1700000000,"rnd":"424242"}};
                </script></html>""") else null
        }
        app = RuntimeEnvironment.getApplication()
        AmazonSession.clear(app)
        AmazonSession.save(app, cookies, "music.amazon.it", null)
        AmazonClient.forgetConfig()
        amazon = AmazonClient(app)
    }

    @After fun tearDown() { AmazonSession.clear(app); BridgeMock.stop() }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())

    private fun bought(i: Int, purchased: Boolean = true) = """{"id":"edge-$i","node":{"id":"B0BUY${i.toString().padStart(5, '0')}","__typename":"Track",
        "shortTitle":"Tune $i","title":"Tune $i (Remastered)","duration":${180 + i},"mediaType":"AUDIO","uri":"amzn:track:$i",
        "contributingArtists":{"edges":[{"role":"PRIMARY","node":{"name":"Neon Harbour","__typename":"Artist","id":"ART1"},"__typename":"ArtistEdge"}],"__typename":"ArtistConnection"},
        "album":{"id":"ALB1","__typename":"Album","shortTitle":"Low Tide","parentalSettings":{"hasExplicitLanguage":false}},
        "parentalSettings":{"hasExplicitLanguage":false,"__typename":"ParentalSettings"},
        "purchaseContext":{"isPurchased":$purchased,"__typename":"PurchaseContext","id":"pc-$i"}},"__typename":"UserPurchasedTrackEdge"}"""

    private fun page(range: IntRange, next: Boolean, token: String? = "page-token", extra: String = "") = """{"data":{"user":{"purchased":{
        "edges":[${(range.map { bought(it) } + listOfNotNull(extra.ifEmpty { null })).joinToString(",")}],
        "pageInfo":{"hasNextPage":$next,"endCursor":null,"token":${token?.let { "\"$it\"" } ?: "null"},"hasPreviousPage":null,"startCursor":null,"__typename":"PageInfo"},
        "__typename":"UserPurchasedTrackConnection"},"__typename":"User"}}}"""

    @Test fun purchasedMusicIsReadPageByPageAsThePlayerAsksIt() {
        answer(page(1..50, next = true, token = "tok-1", extra = bought(99, purchased = false)))
        answer(page(51..60, next = false, token = "tok-2"))
        val r = amazon.purchasedTracks()
        assertEquals(60, r.size)
        assertEquals("B0BUY00001", r[0].id)
        assertEquals("Tune 1", r[0].title)
        assertEquals(listOf("Neon Harbour"), r[0].artists)
        assertEquals("Low Tide", r[0].album)
        assertEquals(181_000L, r[0].durationMs)
        val first = call(0).json()
        assertEquals("LibraryPurchasedPageQuery", first["operationName"].str)
        assertEquals("5d7705c2ee5fc52f598f745f00b2698b77fd50d7e65b7ccc3584ff04cfd96b56", first["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals(50L, first["variables"]["firstLimit"].long)
        assertEquals("true", first["variables"]["isAuthenticated"].toString())
        assertNull(first["variables"]["afterCursor"])
        assertEquals("tok-1", call(1).json()["variables"]["afterCursor"].str)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun aContinuationAmazonIgnoresStopsTheListing() {
        val same = page(1..50, next = true, token = "tok-a")
        answer(same); answer(same.replace("tok-a", "tok-b")); answer(same.replace("tok-a", "tok-c"))
        assertEquals(50, amazon.purchasedTracks().size)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun theBridgeSaysWhetherThereIsPurchasedMusicAndListsIt() {
        answer(page(1..3, next = false))
        val probe = BridgeCore.call(app, "amazon.purchased", null, null)
        assertNull(probe.getString("error"))
        assertEquals(3, probe.getInt("count"))
        answer(page(1..3, next = false))
        val listed = BridgeCore.call(app, "amazon.tracks", BridgeCore.PURCHASED, null)
        assertNull(listed.getString("error"))
        assertEquals(listOf("B0BUY00001", "B0BUY00002", "B0BUY00003"), parseJson(listed.getString("json")!!).arr.map { it["id"].str })
        // An answer without the purchased music: an error, so Trackhop does not offer it.
        answer("""{"data":{"user":{"__typename":"User"}}}""")
        assertTrue(BridgeCore.call(app, "amazon.purchased", null, null).getString("error") != null)
    }

    @Test fun aPlaylistIsRenamedWithUpdatePlaylistDetails() {
        answer("""{"data":{"batchUpdatePlaylist":{"playlist":{"id":"PL1","__typename":"Playlist","title":"Night drive","visibility":"PRIVATE"},"__typename":"BatchUpdatePlaylistResponse"}}}""")
        amazon.renamePlaylist("PL1", "Night drive")
        val b = call(0).json()
        assertEquals("updatePlaylistDetails", b["operationName"].str)
        assertEquals("8629205629c61ef70ed4a28876506bf90369a5d9a2cf1684319a1d33fb5304ac", b["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals("PL1", b["variables"]["input"]["id"].str)
        assertEquals("""[{"editTitle":{"title":"Night drive"}}]""", b["variables"]["input"]["updates"].toString())
        // Another title in the answer, or none: not renamed.
        answer("""{"data":{"batchUpdatePlaylist":{"playlist":{"id":"PL1","title":"Old name"}}}}""")
        try { amazon.renamePlaylist("PL1", "Night drive"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("did not rename")) }
        answer("""{"data":{"batchUpdatePlaylist":null},"errors":[{"message":"Not allowed","extensions":{"code":"FORBIDDEN_PLAYLIST"}}]}""", 294)
        try { amazon.renamePlaylist("PL1", "Night drive"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("FORBIDDEN_PLAYLIST")) }
    }

    private fun answer(body: String, code: Int = 200) = BridgeMock.answer(body, code)
}

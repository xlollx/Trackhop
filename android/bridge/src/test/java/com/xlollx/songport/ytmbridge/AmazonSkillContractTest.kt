package com.xlollx.songport.ytmbridge

import android.app.Application
import android.os.Bundle
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.RecordedRequest
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
 * The Bridge's Amazon calls for an account whose player uses the skill API (issue #35), against a
 * local mock server answering with the trimmed real answers of resources/amazon-skill; and the
 * GraphQL accounts left exactly where they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AmazonSkillContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private val cookies = "session-id=262-1234567-7654321; ubid-main=133-1; at-main=Atza|" + "x".repeat(40)
    private val hd = """{"level":"HD_MEMBER"}"""
    private val test = "087f7086-7db8-462b-9b1d-7ed7822e5a8a"
    private val eu = "eu.web.skill.music.a2z.com"
    private val playerHeaders = jsonObj(
        "x-amzn-authentication" to """{"interface":"ClientAuthenticationInterface.v1_0.ClientTokenElement","accessToken":"Atna|secret-token-value"}""",
        "x-amzn-csrf" to """{"interface":"CSRFInterface.v1_0.CSRFHeaderElement","token":"csrf-secret","timestamp":"1","rndNonce":"2"}""",
        "x-amzn-device-id" to "13700000000000000",
        "x-amzn-music-domain" to "music.amazon.com",
    ).toString()
    private val logs = ArrayList<String>()

    @Before fun setUp() {
        BridgeMock.start()
        app = RuntimeEnvironment.getApplication()
        // The session object outlives one test: nothing of the last one's route or skill host stays.
        AmazonSession.clear(app)
        AmazonSession.save(app, cookies, "music.amazon.com", null)
        AmazonClient.forgetConfig()
        AmazonSkillClient.removePauseMs = 0
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
    }

    @After fun tearDown() { BridgeNet.logSink = null; AmazonSession.clear(app); BridgeMock.stop() }

    private fun resource(name: String) = javaClass.getResource("/amazon-skill/$name")!!.readText()
    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())
    private fun RecordedRequest.without(vararg keys: String): Map<String, String?> =
        (json() as kotlinx.serialization.json.JsonObject).filterKeys { it !in keys && it != "headers" }.mapValues { it.value.str }

    /** The Dutch player as the hook reports it: showHome on na., its library on eu., no GraphQL. */
    private fun playerSeenOnSkill() {
        assertFalse(AmazonBridge.rememberSkill(app, "na.web.skill.music.a2z.com", "showHome", "", hd, "UA", pageSawGql = false))
        // showHome on na. moves nothing and is not the host.
        assertNull(AmazonSession.skillHost(app))
        assertNull(AmazonSession.api(app))
        assertTrue(AmazonBridge.rememberSkill(app, eu, "showLibraryPlaylists", playerHeaders, hd, "UA", pageSawGql = false))
    }

    @Test fun theHookSeesTheSkillPlayerAndTheAccountMovesOnce() {
        playerSeenOnSkill()
        assertEquals(AmazonRoute.SKILL, AmazonSession.api(app))
        assertEquals(eu, AmazonSession.skillHost(app))
        assertEquals(hd, AmazonSession.skillUserHash(app))
        AmazonBridge.rememberSkill(app, eu, "showSearch", playerHeaders, hd, "UA", pageSawGql = false)
        val moved = logs.filter { it.contains("uses the European skill API") }
        assertEquals(listOf("[amazon] Amazon Music: this account uses the European skill API (eu.web.skill.music.a2z.com)"), moved)
        // Names and lengths of the headers only: never a token or CSRF value.
        assertTrue(logs.toString(), logs.any { it.contains("x-amzn-authentication(") })
        assertTrue(logs.toString(), logs.none { it.contains("secret") })
    }

    @Test fun libraryTracksAndSearchOnThePlayersSkillHost() {
        playerSeenOnSkill()
        BridgeMock.answer(resource("library-playlists.json"))
        BridgeMock.answer(resource("library-playlist.json"))
        BridgeMock.answer(resource("search.json"))
        val lists = parseJson(BridgeCore.call(app, "amazon.playlists", null, null).getString("json")!!)
        assertEquals(listOf(test to "Test"), lists.arr.map { it["id"].str to it["name"].str })
        val tracks = parseJson(BridgeCore.call(app, "amazon.tracks", test, null).getString("json")!!)
        assertEquals("a64b54d9-c63c-46c1-a921-33e1eed66364", tracks[0]["setVideoId"].str)
        val found = parseJson(BridgeCore.call(app, "amazon.search", "sam fender", null).getString("json")!!)
        assertEquals(listOf("B0HNNQZGGG"), found.arr.map { it["id"].str })

        assertEquals(3, BridgeMock.calls.size)
        assertEquals(listOf("/api/showLibraryPlaylists", "/api/showLibraryPlaylist", "/api/showSearch"), BridgeMock.calls.map { it.requestUrl!!.encodedPath })
        BridgeMock.calls.forEach { assertEquals(eu, it.getHeader(BridgeMock.ORIGINAL_HOST)); assertEquals("POST", it.method) }
        assertEquals(mapOf("userHash" to hd), call(0).without())
        assertEquals(mapOf("id" to test, "userHash" to hd), call(1).without())
        assertEquals("sam fender", call(2).json()["suggestedKeyword"].str)
        // The player's own headers, inside the body.
        val h = parseJson(call(0).json()["headers"].str!!)
        assertTrue(h["x-amzn-authentication"].str!!.contains("Atna|secret-token-value"))
        assertEquals("13700000000000000", h["x-amzn-device-id"].str)
        assertEquals("https://music.amazon.com/my/playlists", h["x-amzn-page-url"].str)
        assertNull(call(0).getHeader("Cookie"))
        assertEquals("https://music.amazon.com", call(0).getHeader("Origin"))
    }

    @Test fun addingFollowsTheDialogsLinkWithThePlaylistsVersion() {
        playerSeenOnSkill()
        BridgeMock.answer(resource("add-dialog.json"))
        BridgeMock.answer(resource("add-answer.json"))
        val b = BridgeCore.call(app, "amazon.add", test, Bundle().apply {
            putStringArray("ids", arrayOf("B07QGX8GNT"))
            putStringArray("titles", arrayOf("Hypersonic Missiles"))
        })
        assertNull(b.getString("error"))
        assertEquals(emptyList<String>(), b.getStringArray("duplicates")!!.toList())
        assertEquals("/api/showAddTrackToPlaylistDialog", call(0).requestUrl!!.encodedPath)
        assertEquals(mapOf("trackId" to "B07QGX8GNT", "trackTitle" to "Hypersonic Missiles", "userHash" to hd), call(0).without())
        // Sent to the player's host, not to the zaz.mesk host the link names; its query is the body.
        assertEquals(eu, call(1).getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/api/addTrackToPlaylist", call(1).requestUrl!!.encodedPath)
        assertNull(call(1).requestUrl!!.query)
        assertEquals(mapOf(
            "isTrackInLibrary" to "false", "playlistId" to test, "playlistTitle" to "Test", "rejectDuplicate" to "true",
            "shouldReplaceAddedTrack" to "false", "trackId" to "B07QGX8GNT", "trackTitle" to "Hypersonic Missiles", "userHash" to hd, "version" to "3",
        ), call(1).without())
    }

    @Test fun removingByTrackEntryIdAndCheckingThePlaylist() {
        playerSeenOnSkill()
        BridgeMock.answer(resource("remove-answer.json"))
        BridgeMock.answer("""{"methods":[{"interface":"TemplateListInterface.v1_0.BindTemplateMethod","template":{"widgets":[{"interface":"Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.VisualTableWidgetElement","items":[]}]}}]}""")
        val b = BridgeCore.call(app, "amazon.remove", test, Bundle().apply {
            putStringArray("ids", arrayOf("B0FWKPNTJ7"))
            putStringArray("entryIds", arrayOf("a64b54d9-c63c-46c1-a921-33e1eed66364"))
        })
        assertNull(b.getString("error"))
        assertEquals(emptyList<String>(), b.getStringArray("kept")!!.toList())
        assertEquals(mapOf("playlistId" to test, "trackEntryId" to "a64b54d9-c63c-46c1-a921-33e1eed66364", "trackId" to "B0FWKPNTJ7", "userHash" to hd), call(0).without())
        assertEquals("/api/showLibraryPlaylist", call(1).requestUrl!!.encodedPath)
    }

    @Test fun aGraphQlCursorIsLookedUpAndAnEntryStillListedIsKept() {
        playerSeenOnSkill()
        BridgeMock.answer(resource("library-playlist.json"))
        BridgeMock.answer(resource("remove-answer.json"))
        BridgeMock.answer(resource("library-playlist.json"))
        val b = BridgeCore.call(app, "amazon.remove", test, Bundle().apply {
            putStringArray("ids", arrayOf("B0FWKPNTJ7"))
            putStringArray("entryIds", arrayOf("0:4f1c-cursor-of-graphql"))
        })
        assertEquals("a64b54d9-c63c-46c1-a921-33e1eed66364", call(1).json()["trackEntryId"].str)
        assertEquals(listOf("B0FWKPNTJ7"), b.getStringArray("kept")!!.toList())
    }

    private fun playerPage() {
        BridgeMock.fixed = { r ->
            if (r.getHeader(BridgeMock.ORIGINAL_HOST) == "music.amazon.com" && r.requestUrl!!.encodedPath == "/") okhttp3.mockwebserver.MockResponse().setBody("""<html><script>
                amznMusic.appConfig = {"accessToken":"Atna|tok","customerId":"A1CUSTOMER","deviceId":"1","deviceType":"A16ZV8BU3SN1N3","csrf":{"token":"t","ts":1,"rnd":"2"}};
                </script></html>""") else null
        }
    }

    @Test fun aGraphQlAccountStaysOnGraphQl() {
        // Never seen on the skill API: GraphQL, as before, on the domain's gql host.
        playerPage()
        BridgeMock.answer("""{"data":{"user":{"playlists":{"edges":[{"node":{"id":"P1","title":"Uno","trackCount":12}}]}}}}""")
        val lists = parseJson(BridgeCore.call(app, "amazon.playlists", null, null).getString("json")!!)
        assertEquals("P1", lists[0]["id"].str)
        assertEquals("gql.music.amazon.com", call(0).getHeader(BridgeMock.ORIGINAL_HOST))
        assertNull(AmazonSession.api(app))
        // Its player's GraphQL calls move nothing either; a rename goes through the GraphQL player's updatePlaylistDetails.
        assertNull(AmazonRoute.afterPlayerCall(AmazonSession.api(app), skill = false, method = "", pageSawGql = true))
        BridgeMock.answer("""{"data":{"batchUpdatePlaylist":{"playlist":{"id":"P1","__typename":"Playlist","title":"x","visibility":"PRIVATE"},"__typename":"BatchUpdatePlaylistResponse"}}}""")
        assertNull(BridgeCore.call(app, "amazon.rename", "P1", Bundle().apply { putString("name", "x") }).getString("error"))
        assertEquals("updatePlaylistDetails", call(1).json()["operationName"].str)
        assertEquals("gql.music.amazon.com", call(1).getHeader(BridgeMock.ORIGINAL_HOST))
        assertTrue(logs.none { it.contains("skill API") })
    }

    @Test fun aSkillAccountStillRenamesOnTheSkillApi() {
        playerSeenOnSkill()
        BridgeMock.answer("""{"methods":[]}""")
        assertNull(BridgeCore.call(app, "amazon.rename", test, Bundle().apply { putString("name", "Renamed") }).getString("error"))
        assertEquals(1, BridgeMock.calls.size)
        assertEquals(eu, call(0).getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/api/renamePlaylist", call(0).requestUrl!!.encodedPath)
    }

    @Test fun aSkillFailureOfAPlayerAlsoSeenOnGraphQlTriesGraphQlAndKeepsIt() {
        playerSeenOnSkill()
        AmazonSession.saveGqlHost(app, "gql.music.amazon.com")
        playerPage()
        BridgeMock.answer("<html>Internal Server Error</html>", 500)
        BridgeMock.answer("""{"data":{"user":{"playlists":{"edges":[{"node":{"id":"P1","title":"Uno","trackCount":12}}]}}}}""")
        val lists = parseJson(BridgeCore.call(app, "amazon.playlists", null, null).getString("json")!!)
        assertEquals("P1", lists[0]["id"].str)
        assertEquals(eu, call(0).getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("gql.music.amazon.com", call(1).getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals(AmazonRoute.GQL, AmazonSession.api(app))
    }

    @Test fun aSkillFailureOfAPlayerNeverSeenOnGraphQlIsSaidAsItIs() {
        playerSeenOnSkill()
        BridgeMock.answer("<html>Internal Server Error</html>", 500)
        val b = BridgeCore.call(app, "amazon.playlists", null, null)
        assertTrue(b.getString("error"), b.getString("error")!!.contains("500 on showLibraryPlaylists"))
        assertEquals(1, BridgeMock.calls.size)
        assertEquals(AmazonRoute.SKILL, AmazonSession.api(app))
    }

    @Test fun anAccountOnTheSkillApiWhosePlayerMovedBackGoesToGraphQl() {
        playerSeenOnSkill()
        AmazonSession.setApi(app, AmazonRoute.GQL)
        assertEquals(AmazonRoute.GQL, AmazonSession.api(app))
        assertTrue(logs.toString(), logs.any { it.contains("uses GraphQL again") })
    }
}

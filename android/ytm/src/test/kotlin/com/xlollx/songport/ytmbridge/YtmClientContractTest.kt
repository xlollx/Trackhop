package com.xlollx.songport.ytmbridge

import okhttp3.Interceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.Collections

/**
 * The YouTube Music web client against a local mock server: the [YtmHost]'s interceptor sends
 * every request to 127.0.0.1, so nothing reaches music.youtube.com. Answers are shaped like the
 * player's innertube responses (unofficial and undocumented: the shapes follow what ytmusicapi and
 * the existing parser read, not a reference). The client paces its calls (1.5 s between signed-in
 * calls), so these tests are kept few.
 */
class YtmClientContractTest {
    private lateinit var server: MockWebServer
    private val queue = ArrayDeque<MockResponse>()
    private val calls: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    private val verification = object : YtmVerificationStore {
        override fun until() = 0L
        override fun seen() = 0L
        override fun level() = 0
        override fun html(): String? = null
        override fun save(until: Long, seen: Long, level: Int, html: String) {}
        override fun clear() {}
    }
    private lateinit var client: YtmClient

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // The player page (visitor id, client version) is read at most once an hour, whatever the test.
                if (request.method == "GET" && request.requestUrl!!.encodedPath == "/") return MockResponse().setBody(
                    """<script>ytcfg.set({"VISITOR_DATA":"CgtWSVNJVE9SX0lE","INNERTUBE_CLIENT_VERSION":"1.20251020.01.00"});</script>""")
                calls += request
                return synchronized(queue) { queue.pollFirst() } ?: MockResponse().setResponseCode(404).setBody("not queued")
            }
        }
        server.start()
        val base = server.url("/")
        val host = object : YtmHost {
            override fun cookies() = "SAPISID=sapi-123; SID=sid; __Secure-3PAPISID=sapi-123"
            override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0"
            override val interceptor = Interceptor { chain ->
                val u = chain.request().url
                chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host(base.host).port(base.port).build())
                    .header("X-Test-Original-Host", u.host).build())
            }
            override val web: YtmWebTransport? = null
            override val verification = this@YtmClientContractTest.verification
            override val signInAgain = "sign in again"
            override val blockedHint: String? = null
        }
        client = YtmClient(host)
    }

    @After fun stop() { server.shutdown() }

    private fun answer(body: String, code: Int = 200) { synchronized(queue) { queue.add(MockResponse().setResponseCode(code).setBody(body)) } }
    private fun take(i: Int): RecordedRequest = calls[i]
    private fun RecordedRequest.json() = parseJson(body.clone().readUtf8())

    private fun row(v: String, set: String?, title: String = "Song $v", grey: Boolean = false) = """{"musicResponsiveListItemRenderer":{
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":${q(title)},"navigationEndpoint":{"watchEndpoint":{"videoId":"$v"}}}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Måneskin","navigationEndpoint":{"browseEndpoint":{"browseId":"UCx"}}},{"text":" & "},{"text":"Beyoncé","navigationEndpoint":{"browseEndpoint":{"browseId":"UCy"}}}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb1"}}}]}}}],
        "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:35"}]}}}],
        ${if (grey) "\"musicItemRendererDisplayPolicy\":\"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\"," else ""}
        "playlistItemData":{${set?.let { "\"playlistSetVideoId\":\"$it\"," } ?: ""}"videoId":"$v"}}}"""
    private fun more(token: String) = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}"""

    private fun header(count: String) = """"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Liked Music"}]},"secondSubtitle":{"runs":[{"text":"$count songs"},{"text":" • "},{"text":"120+ hours"}]}}},"""

    @Test fun aListingFarShorterThanItsHeaderIsRefused() {
        answer("""{${header("2,084")}"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"playlistId":"LM",
            "contents":[${(0 until 100).joinToString(",") { row("v$it", "S$it") }},${more("T1")}]}}]}}}}}""")
        answer("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""")
        try { client.tracks(YtmClient.LIKED); fail() } catch (e: BridgeException) { assertTrue(e.message, e.message!!.contains("100 of the 2084")) }
    }

    @Test fun aListingCloseToItsHeaderPasses() {
        answer("""{${header("110")}"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"playlistId":"LM",
            "contents":[${(0 until 100).joinToString(",") { row("v$it", "S$it") }}]}}]}}}}}""")
        assertEquals(100, client.tracks(YtmClient.LIKED).size)
    }

    @Test fun playlistReadAcrossContinuationsWithDuplicatesAndGreyedEntries() {
        answer("""{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"playlistId":"PL1",
            "contents":[${(0 until 100).joinToString(",") { row("v$it", "S$it") }},${more("T1")}]}}]}}}}}""")
        answer("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
            ${row("v0", "S-dup")},${row("v100", "S100", title = "Ñandú 👩🏽‍🎤 日本語", grey = true)},
            {"musicResponsiveListItemRenderer":{"flexColumns":[]}}]}}]}""")
        val ts = client.tracks("PL1")
        assertEquals(102, ts.size)
        assertEquals("S-dup", ts[100].setVideoId)
        assertEquals("v0", ts[100].id)
        assertEquals(listOf("Måneskin", "Beyoncé"), ts[0].artists)
        assertEquals(215_000L, ts[0].durationMs)
        assertEquals(false, ts[101].available)
        assertEquals("Ñandú 👩🏽‍🎤 日本語", ts[101].title)
        val first = take(0)
        assertEquals("POST", first.method)
        assertEquals("music.youtube.com", first.getHeader("X-Test-Original-Host"))
        assertEquals("/youtubei/v1/browse", first.requestUrl!!.encodedPath)
        assertEquals("false", first.requestUrl!!.queryParameter("prettyPrint"))
        assertTrue(first.getHeader("Authorization")!!.startsWith("SAPISIDHASH "))
        assertEquals("0", first.getHeader("X-Goog-AuthUser"))
        assertEquals("67", first.getHeader("X-YouTube-Client-Name"))
        assertEquals("https://music.youtube.com", first.getHeader("Origin"))
        assertEquals("WEB_REMIX", first.json()["context"]["client"]["clientName"].str)
        assertEquals("VLPL1", first.json()["browseId"].str)
        assertEquals("T1", take(1).json()["continuation"].str)
        assertEquals(2, calls.size)
    }

    @Test fun addInFiftiesInOrderAndConfirmed() {
        repeat(3) { answer("""{"status":"STATUS_SUCCEEDED","playlistEditResults":[]}""") }
        val ids = (0 until 120).map { "vid$it" }
        client.addTracks("VLPL1", ids)
        val sent = (0 until 3).flatMap { i ->
            val b = take(i).json()
            assertEquals("/youtubei/v1/browse/edit_playlist", take(i).requestUrl!!.encodedPath)
            assertEquals("PL1", b["playlistId"].str)
            b["actions"].arr.map { a -> assertEquals("ACTION_ADD_VIDEO", a["action"].str); a["addedVideoId"].str }
        }
        assertEquals(ids, sent)
    }

    @Test fun unconfirmedEditIsAnError() {
        answer("""{"status":"STATUS_FAILED"}""")
        try { client.addTracks("PL1", listOf("a")); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("STATUS_FAILED")) }
    }

    @Test fun removeBySetIdKeepsTheOtherCopy() {
        answer("""{"status":"STATUS_SUCCEEDED"}""")
        client.removeTracks("PL1", listOf(RemoveItem("v0", "S-dup")))
        val a = take(0).json()["actions"].arr.single()
        assertEquals("ACTION_REMOVE_VIDEO", a["action"].str)
        assertEquals("v0", a["removedVideoId"].str)
        assertEquals("S-dup", a["setVideoId"].str)
    }

    @Test fun createRenamePrivacyMoveDelete() {
        answer("""{"playlistId":"PLnew"}""")
        repeat(3) { answer("""{"status":"STATUS_SUCCEEDED"}""") }
        answer("""{}""")
        assertEquals("PLnew", client.createPlaylist("Ñandú 🎧", "d").id)
        client.renamePlaylist("VLPLnew", "x".repeat(300))
        client.setPrivacy("PLnew", true)
        client.moveBefore("PLnew", listOf("S2" to "S1"))
        client.deletePlaylist("PLnew")
        val c = take(0)
        assertEquals("/youtubei/v1/playlist/create", c.requestUrl!!.encodedPath)
        assertEquals("Ñandú 🎧", c.json()["title"].str)
        assertEquals("PRIVATE", c.json()["privacyStatus"].str)
        assertEquals("ACTION_SET_PLAYLIST_NAME", take(1).json()["actions"][0]["action"].str)
        assertEquals("PLnew", take(1).json()["playlistId"].str)
        assertEquals("PUBLIC", take(2).json()["actions"][0]["playlistPrivacy"].str)
        val m = take(3).json()["actions"][0]
        assertEquals("ACTION_MOVE_VIDEO_BEFORE", m["action"].str)
        assertEquals("S2", m["setVideoId"].str); assertEquals("S1", m["movedSetVideoIdSuccessor"].str)
        assertEquals("/youtubei/v1/playlist/delete", take(4).requestUrl!!.encodedPath)
    }

    @Test fun deadSessionIsNotRetried() {
        answer("""{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}""", 401)
        try { client.tracks("PL1"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("(401)")) }
        assertEquals(1, calls.size)
    }

    @Test fun throttledSignedInCallIsRetriedOnceThenReported() {
        answer("""{"error":{"code":429,"message":"Too many requests"}}""", 429)
        answer("""{"error":{"code":429,"message":"Too many requests"}}""", 429)
        try { client.tracks("PL1"); fail() } catch (e: YtmClient.Throttled) { assertTrue(e.message, e.message!!.contains("[Too many requests]")) }
        assertEquals(2, calls.size)
    }

    @Test fun likesAreOneCallEachAndOnlyAnAnswerNamingLikedVideosIsReportedAsElsewhere() {
        // As YouTube answers a like as far as has been seen: nothing but the response context.
        answer("""{"responseContext":{"visitorData":"x"}}""")
        // A toast that says where the item went: Liked videos, not Liked music.
        answer("""{"responseContext":{},"actions":[{"addToToastAction":{"item":{"notificationTextRenderer":{"successResponseText":{"runs":[{"text":"Added to Liked videos"}]}}}}}]}""")
        answer("""{"responseContext":{},"actions":[{"addToToastAction":{"item":{"notificationTextRenderer":{"successResponseText":{"runs":[{"text":"Saved to your Liked music"}]}}}}}]}""")
        val elsewhere = client.addTracks(YtmClient.LIKED, listOf("song1", "video2", "song3"))
        assertEquals(setOf("video2"), elsewhere)
        assertEquals((0..2).map { "/youtubei/v1/like/like" }, (0..2).map { take(it).requestUrl!!.encodedPath })
        assertEquals("video2", take(1).json()["target"]["videoId"].str)
    }

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
}

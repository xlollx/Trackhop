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
    /** The session the host holds, what it renews to, and whether it can (the server cannot: the default). */
    private var jar = "SAPISID=sapi-123; SID=sid; __Secure-3PAPISID=sapi-123"
    private var renewable = false
    private var renewTo: String? = null
    private var pageId: String? = null
    /** The browser engine the host offers; none by default (the server's case). */
    private var transport: YtmWebTransport? = null
    private val renewals = Collections.synchronizedList(ArrayList<String>())
    private val logs = Collections.synchronizedList(ArrayList<String>())

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
            override fun cookies() = jar
            override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0"
            override val interceptor = Interceptor { chain ->
                val u = chain.request().url
                chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host(base.host).port(base.port).build())
                    .header("X-Test-Original-Host", u.host).build())
            }
            override val web: YtmWebTransport? get() = transport
            override val verification = this@YtmClientContractTest.verification
            override val signInAgain = "sign in again"
            override val blockedHint: String? = null
            override val channel: String? get() = pageId
            override fun log(message: String) { logs += message }
            override val canRenew: Boolean get() = renewable
            override fun renewSession(refused: String): String? {
                renewals += refused
                return renewTo?.also { jar = it }
            }
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

    @Test fun anEmptyVisitorSearchIsAskedAgainWithTheSession() {
        // #34: YouTube answers a visitor's song search with "No results"; the session finds it.
        answer("""{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"messageRenderer":{"text":{"runs":[{"text":"No results for "}]}}}]}}]}}}}]}}}""")
        answer("""{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${row("fYdwe3ArFWA", null, "Bohemian Rhapsody")}]}}]}}}}]}}}""")
        val r = client.search("Queen Bohemian Rhapsody")
        assertEquals(listOf("fYdwe3ArFWA"), r.map { it.id })
        assertEquals(null, take(0).getHeader("Authorization"))
        assertTrue(take(1).getHeader("Authorization") != null)
        // The next search goes straight through the session.
        answer("""{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${row("v2", null)}]}}]}}}}]}}}""")
        client.search("Daft Punk One More Time")
        assertTrue(take(2).getHeader("Authorization") != null)
    }

    @Test fun deadSessionIsNotRetried() {
        answer("""{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}""", 401)
        try { client.tracks("PL1"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("(401)")) }
        assertEquals(1, calls.size)
    }

    private val unauthenticated = """{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}"""
    private fun shelf(vararg rows: String) = """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"playlistId":"PL1",
            "contents":[${rows.joinToString(",")}]}}]}}}}}"""

    @Test fun aRefusedSessionIsRenewedOnceAndTheCallMadeAgainWithItsCookies() {
        renewable = true
        pageId = "112233445566778899"
        val old = jar
        renewTo = "SAPISID=sapi-456; SID=sid2; __Secure-3PAPISID=sapi-456; __Secure-3PSIDTS=ts2"
        answer(unauthenticated, 401)
        answer(shelf(row("v1", "S1")))
        assertEquals(listOf("v1"), client.tracks("PL1").map { it.id })
        assertEquals(listOf(old), renewals)
        assertEquals(2, calls.size)
        // The same call again: the renewed cookies, a SAPISIDHASH made of them, the same channel.
        val retry = take(1)
        assertEquals(renewTo, retry.getHeader("Cookie"))
        val auth = retry.getHeader("Authorization")!!
        val ts = auth.removePrefix("SAPISIDHASH ").substringBefore('_').toLong()
        assertEquals(YtmAuth.authorization(renewTo!!, ts), auth)
        assertTrue(auth != take(0).getHeader("Authorization"))
        assertEquals(take(0).body.clone().readUtf8(), retry.body.clone().readUtf8())
        assertEquals("112233445566778899", retry.getHeader("X-Goog-PageId"))
        assertEquals("112233445566778899", retry.json()["context"]["user"]["onBehalfOfUser"].str)
        assertEquals(listOf("session refused (401); renewed and retried: ok"), logs)
        assertTrue(logs.none { it.contains("sapi-") || it.contains("sid") })
    }

    @Test fun aRenewedSessionRefusedAgainFailsAsBeforeWithoutALoop() {
        renewable = true
        renewTo = "SAPISID=sapi-456; SID=sid2"
        answer(unauthenticated, 401)
        answer(unauthenticated, 401)
        answer(unauthenticated, 401)
        try { client.tracks("PL1"); fail() } catch (e: BridgeException) {
            assertEquals("YouTube Music rejected the session (401): sign in again", e.message)
        }
        assertEquals(1, renewals.size)
        assertEquals(2, calls.size)
        assertEquals(listOf("session refused (401); renewed and retried: failed"), logs)
    }

    @Test fun aSessionThatCouldNotBeRenewedFailsAtOnce() {
        renewable = true
        renewTo = null
        answer(unauthenticated, 401)
        try { client.tracks("PL1"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("(401)")) }
        assertEquals(1, renewals.size)
        assertEquals(1, calls.size)
        // Renewed cookies without the signing cookie are no session: not retried with.
        renewTo = "SID=only"
        answer(unauthenticated, 401)
        try { client.tracks("PL1"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("(401)")) }
        assertEquals(2, calls.size)
    }

    @Test fun eachCallIsRenewedAtMostOnceAndOnlyWhereTheHostCan() {
        // The server's pasted cookies: never renewed (deadSessionIsNotRetried), the call fails at once.
        answer(unauthenticated, 401)
        try { client.tracks("PL1"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("(401)")) }
        assertTrue(renewals.isEmpty())
        assertTrue(logs.isEmpty())
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

    // ------------------------------------------------------------------ a playlist's picture

    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
    private val uploadAt = "https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail?upload_id=made-up-upload&upload_protocol=resumable"

    @Test fun aCoverGoesUpByGooglesResumableUploadThenBecomesTheCustomThumbnail() {
        synchronized(queue) {
            queue.add(MockResponse().setHeader("X-Goog-Upload-URL", uploadAt).setHeader("X-Goog-Upload-Status", "active"))
            queue.add(MockResponse().setHeader("X-Goog-Upload-Status", "final").setBody("""{"encryptedBlobId":"made-up-blob"}"""))
        }
        answer("""{"status":"STATUS_SUCCEEDED"}""")
        client.setCover("VLPLmadeup", jpeg)

        val start = take(0)
        assertEquals("POST", start.method)
        assertEquals("music.youtube.com", start.getHeader("X-Test-Original-Host"))
        assertEquals("/playlist_image_upload/playlist_custom_thumbnail", start.requestUrl!!.encodedPath)
        assertEquals("start", start.getHeader("X-Goog-Upload-Command"))
        assertEquals("resumable", start.getHeader("X-Goog-Upload-Protocol"))
        assertEquals("7", start.getHeader("X-Goog-Upload-Header-Content-Length"))
        assertEquals(0L, start.bodySize)
        assertTrue(start.getHeader("Authorization")!!.startsWith("SAPISIDHASH "))
        assertTrue(start.getHeader("Cookie")!!.contains("SAPISID="))

        val up = take(1)
        assertEquals("POST", up.method)
        assertEquals("/playlist_image_upload/playlist_custom_thumbnail", up.requestUrl!!.encodedPath)
        assertEquals("made-up-upload", up.requestUrl!!.queryParameter("upload_id"))
        assertEquals("upload, finalize", up.getHeader("X-Goog-Upload-Command"))
        assertEquals("0", up.getHeader("X-Goog-Upload-Offset"))
        assertEquals(jpeg.toList(), up.body.clone().readByteArray().toList())

        val edit = take(2)
        assertEquals("/youtubei/v1/browse/edit_playlist", edit.requestUrl!!.encodedPath)
        val b = edit.json()
        assertEquals("PLmadeup", b["playlistId"].str)
        val a = b["actions"][0]
        assertEquals("ACTION_SET_CUSTOM_THUMBNAIL", a["action"].str)
        assertEquals("PLAYLIST_IMAGE_TYPE_CUSTOM_THUMBNAIL", a["addedCustomThumbnail"]["imageKey"]["type"].str)
        assertEquals("studio_square_thumbnail", a["addedCustomThumbnail"]["imageKey"]["name"].str)
        assertEquals("made-up-blob", a["addedCustomThumbnail"]["playlistScottyEncryptedBlobId"].str)
        assertEquals(3, calls.size)
    }

    @Test fun anUploadAddressOutsideTheSiteIsNotFollowed() {
        synchronized(queue) { queue.add(MockResponse().setHeader("X-Goog-Upload-URL", "https://elsewhere.example.com/up?upload_id=x")) }
        try { client.setCover("PLmadeup", jpeg); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("outside the site")) }
        assertEquals(1, calls.size)
    }

    @Test fun aRefusedUploadSetsNothing() {
        answer("", 401)
        try { client.setCover("PLmadeup", jpeg); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("401")) }
        assertEquals(1, calls.size)
    }

    @Test fun theBrowserEngineUploadsWhereThereIsOne() {
        val uploads = ArrayList<Pair<String, Int>>()
        transport = object : YtmWebTransport {
            override fun usable() = true
            // The innertube calls fall back to plain HTTP (the mock server) here.
            override fun post(path: String, body: String, headers: Map<String, String>, credentials: Boolean): YtmWebTransport.Answer = throw IllegalStateException("not in this test")
            override fun upload(path: String, bytes: ByteArray, headers: Map<String, String>, contentType: String): YtmWebTransport.Answer {
                uploads += path to bytes.size
                assertTrue(headers.getValue("Authorization").startsWith("SAPISIDHASH "))
                return YtmWebTransport.Answer(200, """{"encryptedBlobId":"made-up-blob"}""")
            }
        }
        answer("""{"status":"STATUS_SUCCEEDED"}""")
        client.setCover("PLmadeup", jpeg)
        assertEquals(listOf("/playlist_image_upload/playlist_custom_thumbnail" to 7), uploads)
        assertEquals(1, calls.size)
        assertEquals("made-up-blob", take(0).json()["actions"][0]["addedCustomThumbnail"]["playlistScottyEncryptedBlobId"].str)
    }
}

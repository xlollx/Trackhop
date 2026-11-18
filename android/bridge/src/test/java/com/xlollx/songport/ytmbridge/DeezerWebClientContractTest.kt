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
 * Deezer through the web player's gateway (gw-light.php) and the public catalogue API, against a
 * local mock server. The answers below are built from the formats open-source gateway clients
 * document (deemix, deezer-py: `results`/`error`, SNG_ID, SNG_TITLE, ISRC, checkForm...) and from
 * developers.deezer.com for the public API. They pin what this client sends and what it makes of
 * each answer; they have NOT been checked against a live Deezer account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeezerWebClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var dz: DeezerWebClient
    private var userData = """{"error":[],"results":{"USER":{"USER_ID":1234567,"BLOG_NAME":"dzuser","LOVEDTRACKS_ID":"9001"},"checkForm":"tok-1","SESSION_ID":"s1"}}"""
    private var userDataCookie: String? = null

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            if (r.requestUrl?.queryParameter("method") == "deezer.getUserData") {
                MockResponse().setBody(userData).apply { userDataCookie?.let { setHeader("Set-Cookie", it) } }
            } else null
        }
        app = RuntimeEnvironment.getApplication()
        DeezerBridge.session.clear(app)
        DeezerBridge.session.save(app, "arl=arl-cookie; dzr_uniq_id=u1", "dzuser")
        DeezerWebClient.forget()
        DeezerWebClient.quotaWaitMs = 1
        DeezerWebClient.pauseMs = 0
        dz = DeezerWebClient(app)
    }

    @After fun tearDown() { BridgeMock.stop() }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())
    private fun RecordedRequest.host() = getHeader(BridgeMock.ORIGINAL_HOST)
    private fun RecordedRequest.method() = requestUrl!!.queryParameter("method")
    private fun answer(body: String, code: Int = 200) = BridgeMock.answer(body, code)
    private fun ok(results: String) = answer("""{"error":[],"results":$results}""")
    private fun userDataCalls() = BridgeMock.all.count { it.requestUrl?.queryParameter("method") == "deezer.getUserData" }

    private fun song(id: String, title: String, artist: String = "Daft Punk", version: String = "", artists: String = """[{"ART_ID":"27","ART_NAME":"$artist"}]""", extra: String = "") =
        """{"SNG_ID":"$id","SNG_TITLE":"$title","VERSION":"$version","ART_NAME":"$artist","ARTISTS":$artists,
            "ALB_TITLE":"Discovery","DURATION":"224","ISRC":"GBDUW00000$id","EXPLICIT_LYRICS":"0","DATE_ADD":1700000000,"__TYPE__":"song"$extra}"""

    private fun assertGateway(r: RecordedRequest, method: String) {
        assertEquals("POST", r.method)
        assertEquals("www.deezer.com", r.host())
        assertEquals("/ajax/gw-light.php", r.requestUrl!!.encodedPath)
        assertEquals(method, r.method())
        assertEquals("tok-1", r.requestUrl!!.queryParameter("api_token"))
        assertEquals("3", r.requestUrl!!.queryParameter("input"))
        assertEquals("1.0", r.requestUrl!!.queryParameter("api_version"))
        assertTrue(r.getHeader("Cookie")!!.contains("arl=arl-cookie"))
        assertEquals("https://www.deezer.com", r.getHeader("Origin"))
        assertEquals("application/json", r.getHeader("Content-Type")!!.substringBefore(';'))
    }

    // ------------------------------------------------------------------ session

    @Test fun theTokenComesFromGetUserDataWithANullToken() {
        ok("""{"TAB":{"playlists":{"data":[],"count":0,"total":0}}}""")
        dz.libraryPlaylists()
        val first = BridgeMock.all.first()
        assertEquals("deezer.getUserData", first.requestUrl!!.queryParameter("method"))
        assertEquals("null", first.requestUrl!!.queryParameter("api_token"))
        assertGateway(call(0), "deezer.pageProfile")
    }

    @Test fun theGatewaySidTravelsWithTheToken() {
        userDataCookie = "sid=fr0123456789abcdef; path=/; domain=.deezer.com; secure; HttpOnly"
        ok("""{"TAB":{"playlists":{"data":[]}}}""")
        dz.libraryPlaylists()
        val cookie = call(0).getHeader("Cookie")!!
        assertTrue(cookie, cookie.contains("sid=fr0123456789abcdef"))
        assertTrue(cookie.contains("arl=arl-cookie"))
    }

    @Test fun aStaleTokenIsRenewedOnceAndTheCallRepeated() {
        answer("""{"error":{"VALID_TOKEN_REQUIRED":"Invalid CSRF token"},"results":{}}""")
        ok("987654")
        val p = dz.createPlaylist("New", "")
        assertEquals("987654", p.id)
        assertEquals(2, userDataCalls())
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun aRefusedSessionIsRenewedOnceFromTheSignInThenNeedsASignIn() {
        DeezerBridge.session.put(app, "sid", "fr-stale")
        answer("", code = 401)
        ok("987654")
        assertEquals("987654", dz.createPlaylist("New", "").id)
        assertEquals(2, userDataCalls())
        // The renewed call goes without the refused short session; the sign-in's cookies stay.
        assertTrue(!(call(1).getHeader("Cookie") ?: "").contains("fr-stale"))
        assertTrue(DeezerBridge.session.isConnected(app))
        // Refused again: "sign in again", without a loop.
        answer("", code = 403)
        answer("", code = 403)
        try { dz.createPlaylist("New", ""); fail() } catch (e: BridgeException) { assertEquals(DeezerWebClient.EXPIRED, e.message) }
        assertEquals(4, BridgeMock.calls.size)
        assertTrue(DeezerBridge.session.isConnected(app))
    }

    @Test fun aSignedOutSessionSaysWhatToDoAndNamesNoApp() {
        userData = """{"error":[],"results":{"USER":{"USER_ID":0},"checkForm":"visitor"}}"""
        try { dz.libraryPlaylists(); fail() } catch (e: BridgeException) {
            assertEquals(DeezerWebClient.EXPIRED, e.message)
            assertTrue(e.message!!.contains("sign out of Deezer and sign in again"))
            assertTrue(!e.message!!.contains("Trackhop") && !e.message!!.contains("Bridge"))
        }
        assertEquals(0, BridgeMock.calls.size)
    }

    @Test fun anAuthErrorFromAnyMethodIsTheExpiredSession() {
        answer("""{"error":{"NEED_USER_AUTH_REQUIRED":"User must be authenticated"},"results":{}}""")
        try { dz.playlistTracks("111"); fail() } catch (e: BridgeException) { assertEquals(DeezerWebClient.EXPIRED, e.message) }
    }

    @Test fun otherGatewayErrorsAreReportedWithTheMethod() {
        answer("""{"error":{"DATA_ERROR":"playlist does not exist"},"results":{}}""")
        try { dz.deletePlaylist("5"); fail() } catch (e: BridgeException) { assertEquals("Deezer playlist.delete: DATA_ERROR: playlist does not exist", e.message) }
    }

    // ------------------------------------------------------------------ reads

    @Test fun libraryListsPlaylistsWithoutLovedTracksAndKnowsWhichAreOwned() {
        ok("""{"TAB":{"playlists":{"data":[
            {"PLAYLIST_ID":"9001","TITLE":"Loved Tracks","NB_SONG":"3","PARENT_USER_ID":"1234567"},
            {"PLAYLIST_ID":"111","TITLE":"Mine – 日本語","NB_SONG":"2","PARENT_USER_ID":"1234567","DESCRIPTION":"d"},
            {"PLAYLIST_ID":222,"TITLE":"Followed","NB_SONG":40,"PARENT_USER_ID":"5"}],"count":3,"total":3}}}""")
        val lists = dz.libraryPlaylists()
        assertEquals(listOf("111", "222"), lists.map { it.id })
        assertEquals("Mine – 日本語", lists[0].name)
        assertEquals(2, lists[0].count)
        assertEquals(true, lists[0].owned)
        assertEquals(false, lists[1].owned)
        val body = call(0).json()
        assertEquals("1234567", body["USER_ID"].str)
        assertEquals("playlists", body["tab"].str)
    }

    @Test fun playlistTracksCarryIsrcVersionArtistsAndSkipUploads() {
        ok("""{"data":[
            ${song("3135556", "Harder, Better, Faster, Stronger")},
            ${song("3135557", "One More Time", version = "(Live)", artists = """[{"ART_NAME":"Daft Punk"},{"ART_NAME":"Romanthony"}]""", extra = ""","EXPLICIT_TRACK_CONTENT":{"EXPLICIT_LYRICS_STATUS":1}""")},
            {"SNG_ID":"-12345","SNG_TITLE":"my upload.mp3","DURATION":"100","__TYPE__":"song"}],"count":3,"total":3}""")
        val t = dz.playlistTracks("111")
        assertEquals(2, t.size)
        assertEquals("3135556", t[0].id)
        assertEquals("GBDUW000003135556", t[0].isrc)
        assertEquals(224_000L, t[0].durationMs)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals("Discovery", t[0].album)
        assertEquals(false, t[0].explicit)
        assertEquals(1_700_000_000_000L, t[0].addedAt)
        assertEquals("One More Time (Live)", t[1].title)
        assertEquals(listOf("Daft Punk", "Romanthony"), t[1].artists)
        assertEquals(true, t[1].explicit)
        assertNull(t[1].setVideoId) // removal is by track id
        assertGateway(call(0), "playlist.getSongs")
        assertEquals("111", call(0).json()["PLAYLIST_ID"].str)
        assertEquals(-1L, call(0).json()["nb"].long)
    }

    @Test fun aListingThatCannotBeReadIsAnErrorNotAnEmptyPlaylist() {
        val broken = (1..12).joinToString(",") { """{"SNG_ID":"$it","TITLE_MOVED":"x","__TYPE__":"song"}""" }
        ok("""{"data":[$broken],"total":12}""")
        try { dz.playlistTracks("111"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("12 of 12")) }
    }

    @Test fun anEmptyAnswerForANonEmptyPlaylistIsAnError() {
        ok("""{"data":[],"total":5}""")
        try { dz.playlistTracks("111"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("nothing readable")) }
    }

    @Test fun likedTracksAreTheFavouriteIdsInOrderWithTheirDetails() {
        ok("""{"data":[{"SNG_ID":"3135557","DATE_ADD":1710000000},{"SNG_ID":"3135556","DATE_ADD":1700000000}],"total":2,"checksum":"c1"}""")
        ok("""{"data":[${song("3135556", "Harder")},${song("3135557", "One More Time")}],"count":2}""")
        val t = dz.likedTracks()
        assertEquals(listOf("3135557", "3135556"), t.map { it.id })
        assertEquals(1_710_000_000_000L, t[0].addedAt)
        assertGateway(call(0), "song.getFavoriteIds")
        assertGateway(call(1), "song.getListData")
        assertEquals(listOf("3135557", "3135556"), call(1).json()["SNG_IDS"].arr.map { it.str })
    }

    @Test fun noLikedTracksIsAnEmptyList() {
        ok("""{"data":[],"total":0,"checksum":"c0"}""")
        assertEquals(0, dz.likedTracks().size)
    }

    @Test fun versionIsThePlaylistChecksum() {
        ok("""{"DATA":{"PLAYLIST_ID":"111","TITLE":"Mine","NB_SONG":2,"PARENT_USER_ID":"1234567","CHECKSUM":"abc123"},"SONGS":{"data":[]}}""")
        assertEquals("abc123", dz.version("111"))
        assertGateway(call(0), "deezer.pagePlaylist")
        ok("""{"data":[{"SNG_ID":"1"}],"total":7,"checksum":"fav9"}""")
        assertEquals("fav9", dz.version("__liked__"))
    }

    @Test fun playlistInfoReadsThePageHeader() {
        ok("""{"DATA":{"PLAYLIST_ID":"111","TITLE":"Mine","NB_SONG":"2","PARENT_USER_ID":"1234567","DESCRIPTION":"desc"}}""")
        val p = dz.playlistInfo("111")
        assertEquals("Mine", p.name); assertEquals(2, p.count); assertEquals(true, p.owned); assertEquals("desc", p.description)
    }

    // ------------------------------------------------------------------ writes

    @Test fun createIsPrivateAndEmpty() {
        ok("987654")
        val p = dz.createPlaylist("Synced – ✓", "Synced with Trackhop")
        assertEquals("987654", p.id); assertEquals(true, p.owned)
        assertGateway(call(0), "playlist.create")
        val b = call(0).json()
        assertEquals("Synced – ✓", b["title"].str)
        assertEquals(1L, b["status"].long)
        assertEquals(0, b["songs"].arr.size)
    }

    @Test fun addAndRemoveSendSongPairsAsNumbers() {
        ok("true"); ok("true")
        dz.addToPlaylist("111", listOf("3135556", "3135557"))
        dz.removeFromPlaylist("111", listOf("3135556", "3135556"))
        assertGateway(call(0), "playlist.addSongs")
        val add = call(0).json()
        assertEquals(111L, add["playlist_id"].long)
        assertEquals(-1L, add["offset"].long)
        assertEquals("[[3135556,0],[3135557,0]]", add["songs"].toString())
        assertGateway(call(1), "playlist.deleteSongs")
        assertEquals("[[3135556,0]]", call(1).json()["songs"].toString())
    }

    @Test fun addInBatchesOfAHundred() {
        repeat(3) { ok("true") }
        dz.addToPlaylist("111", (1..250).map { it.toString() })
        assertEquals(listOf(100, 100, 50), BridgeMock.calls.map { parseJson(it.body.clone().readUtf8())["songs"].arr.size })
    }

    @Test fun renameAndDelete() {
        ok("true"); ok("true")
        dz.renamePlaylist("111", "New name")
        dz.deletePlaylist("111")
        assertGateway(call(0), "playlist.update")
        assertEquals("New name", call(0).json()["title"].str)
        assertEquals(111L, call(0).json()["playlist_id"].long)
        assertNull(call(0).json()["songs"]) // a rename never touches the tracks
        assertGateway(call(1), "playlist.delete")
    }

    @Test fun likingIsOneFavouriteCallPerTrack() {
        ok("true"); ok("true"); ok("true")
        dz.like(listOf("1", "2"), on = true)
        dz.like(listOf("3"), on = false)
        assertEquals(listOf("favorite_song.add", "favorite_song.add", "favorite_song.remove"), BridgeMock.calls.map { it.method() })
        assertEquals(1L, call(0).json()["SNG_ID"].long)
    }

    // ------------------------------------------------------------------ public catalogue

    @Test fun searchGoesByIsrcFirstWithoutTheSession() {
        answer("""{"id":3135556,"type":"track","title":"Harder, Better, Faster, Stronger","isrc":"GBDUW0000059","duration":224,"explicit_lyrics":false,"readable":true,
            "release_date":"2001-03-07","artist":{"id":27,"name":"Daft Punk"},"contributors":[{"id":27,"name":"Daft Punk","role":"Main"}],"album":{"id":302127,"title":"Discovery"}}""")
        val r = dz.search("Daft Punk Harder", "gbduw0000059")
        assertEquals(1, r.size)
        assertEquals("GBDUW0000059", r[0].isrc)
        assertEquals(2001, r[0].year)
        val c = call(0)
        assertEquals("api.deezer.com", c.host())
        assertEquals("/track/isrc:GBDUW0000059", c.requestUrl!!.encodedPath)
        assertNull(c.getHeader("Cookie"))
        assertEquals(0, userDataCalls())
    }

    @Test fun anUnknownIsrcFallsBackToTextSearch() {
        answer("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        answer("""{"data":[{"id":1,"type":"track","title":"Ñandú","duration":200,"artist":{"name":"Él"},"album":{"title":"A"}}],"total":1}""")
        val r = dz.search("Él Ñandú", "XX0000000000")
        assertEquals("Ñandú", r.single().title)
        assertEquals("/search", call(1).requestUrl!!.encodedPath)
        assertEquals("Él Ñandú", call(1).requestUrl!!.queryParameter("q"))
        assertEquals("10", call(1).requestUrl!!.queryParameter("limit"))
    }

    @Test fun theCatalogueQuotaIsWaitedOut() {
        answer("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")
        answer("""{"data":[],"total":0}""")
        assertEquals(0, dz.search("x", null).size)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun trackLookupOfAMissingIdIsNull() {
        answer("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        assertNull(dz.track("42"))
    }

    @Test fun gatewayErrorShapes() {
        assertNull(DeezerWebClient.errorOf(parseJson("[]")))
        assertNull(DeezerWebClient.errorOf(parseJson("{}")))
        assertEquals("A: b", DeezerWebClient.errorOf(parseJson("""{"A":"b"}""")))
        assertEquals("arl=x; sid=new", DeezerWebClient.withCookie("arl=x; sid=old", "sid", "new"))
    }
}

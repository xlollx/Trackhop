package com.xlollx.songport.ytmbridge

import android.app.Application
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * SoundCloud through api-v2 (the web app's interface), against a local mock server. The answers
 * are built from what soundcloud.com's web app receives (track and playlist objects, `collection`
 * plus `next_href` paging, stubs in long sets); the writes follow what the web app sends. They pin
 * this client's behaviour; they have NOT been checked against a live SoundCloud account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SoundCloudWebClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var sc: SoundCloudWebClient
    private val cid = "a".repeat(32)
    private val fresh = "B".repeat(32)

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            when (r.getHeader(BridgeMock.ORIGINAL_HOST)) {
                "soundcloud.com" -> MockResponse().setBody("""<html><script crossorigin src="https://a-v2.sndcdn.com/assets/0-aaa.js"></script>
                    <script crossorigin src="https://a-v2.sndcdn.com/assets/49-bbb.js"></script></html>""")
                "a-v2.sndcdn.com" -> MockResponse().setBody(if (r.path!!.contains("49-")) """({client_id:"$fresh",env:"production"})""" else "var x=1;")
                else -> if (r.requestUrl!!.encodedPath == "/me") MockResponse().setBody("""{"id":123,"kind":"user","username":"scuser","permalink":"scuser"}""") else null
            }
        }
        app = RuntimeEnvironment.getApplication()
        SoundCloudBridge.session.clear(app)
        SoundCloudBridge.session.save(app, "oauth_token=2-290000-123-AbCdEf; sc_anonymous_id=x", "scuser")
        SoundCloudBridge.session.put(app, "clientId", cid)
        SoundCloudWebClient.pauseMs = 0
        // No WebView in a JVM test: the hidden page is played by each test (none renews by default).
        pages.clear()
        SoundCloudRenewal.forget()
        SoundCloudRenewal.hiddenPage = { _, old -> pages += old; pageAnswer() }
        sc = SoundCloudWebClient(app)
    }

    @After fun tearDown() { BridgeMock.stop(); SoundCloudRenewal.hiddenPage = realPage; BridgeNet.logSink = null }

    private val realPage = SoundCloudRenewal.hiddenPage
    /** The old token each hidden page was opened to replace. */
    private val pages = ArrayList<String?>()
    private var pageAnswer: () -> SoundCloudRenewal.Page = { SoundCloudRenewal.Page(null, null, "no page in a test") }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())
    private fun RecordedRequest.host() = getHeader(BridgeMock.ORIGINAL_HOST)
    private fun answer(body: String, code: Int = 200) = BridgeMock.answer(body, code)

    private fun track(id: Long, title: String = "Daft Punk - Around the World", meta: String = """{"id":$id,"urn":"soundcloud:tracks:$id","artist":"Daft Punk","album_title":"Homework","isrc":"GBDUW9600011","explicit":false,"contains_music":true}""") =
        """{"id":$id,"kind":"track","title":"$title","duration":238000,"full_duration":238000,"permalink_url":"https://soundcloud.com/daftpunk/atw-$id",
            "policy":"ALLOW","release_date":"1997-01-17T00:00:00Z","created_at":"2019-05-01T10:00:00Z","artwork_url":"https://i1.sndcdn.com/x-large.jpg",
            "user":{"id":5,"kind":"user","username":"channel","avatar_url":"https://i1.sndcdn.com/a.jpg"},"publisher_metadata":$meta,
            "media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:$id/x/stream/hls","preset":"mp3_0_0"}]},"waveform_url":"https://wave.sndcdn.com/w.json"}"""

    private fun stub(id: Long) = """{"id":$id,"kind":"track","monetization_model":"NOT_APPLICABLE","policy":"ALLOW"}"""

    private fun set(id: Long, vararg tracks: String, extra: String = "") =
        """{"id":$id,"kind":"playlist","title":"Mine","track_count":${tracks.size},"user_id":123,"sharing":"private","last_modified":"2026-10-01T10:00:00Z","tracks":[${tracks.joinToString(",")}]$extra}"""

    private fun assertApi(r: RecordedRequest, method: String, path: String) {
        assertEquals(method, r.method)
        assertEquals("api-v2.soundcloud.com", r.host())
        assertEquals(path, r.requestUrl!!.encodedPath)
        assertEquals(cid, r.requestUrl!!.queryParameter("client_id"))
        assertEquals("OAuth 2-290000-123-AbCdEf", r.getHeader("Authorization"))
        assertEquals("https://soundcloud.com", r.getHeader("Origin"))
    }

    // ------------------------------------------------------------------ reads

    @Test fun libraryListsOwnSetsThenLikedOnes() {
        answer("""{"collection":[{"id":1001,"kind":"playlist","title":"Mine – 日本語","track_count":2,"user_id":123,"description":null}],
            "next_href":"https://api-v2.soundcloud.com/users/123/playlists_without_albums?offset=1&limit=50"}""")
        answer("""{"collection":[{"id":1002,"kind":"playlist","title":"Second","track_count":0,"user":{"id":123}}],"next_href":null}""")
        answer("""{"collection":[{"created_at":"2026-01-01T00:00:00Z","kind":"like","playlist":{"id":2002,"title":"Theirs","track_count":5,"user_id":999}}],"next_href":null}""")
        val lists = sc.libraryPlaylists()
        assertEquals(listOf("1001", "1002", "2002"), lists.map { it.id })
        assertEquals(listOf(true, true, false), lists.map { it.owned })
        assertEquals("Mine – 日本語", lists[0].name)
        assertApi(call(0), "GET", "/users/123/playlists_without_albums")
        // The next page keeps its own query and gets the client id again.
        assertEquals("1", call(1).requestUrl!!.queryParameter("offset"))
        assertEquals(cid, call(1).requestUrl!!.queryParameter("client_id"))
        assertApi(call(2), "GET", "/users/123/playlist_likes")
    }

    @Test fun likedSetsFailingDoNotCostTheOwnOnes() {
        answer("""{"collection":[{"id":1001,"title":"Mine","track_count":2,"user_id":123}],"next_href":null}""")
        answer("gone", 500)
        assertEquals(listOf("1001"), sc.libraryPlaylists().map { it.id })
    }

    @Test fun setTracksFillTheStubsAndAreTrimmed() {
        answer(set(1001, track(301), stub(303), stub(304)))
        answer("[${track(303, "Pañuelo – live")},${track(304)}]")
        val t = sc.tracks("1001")
        assertEquals(listOf(301L, 303L, 304L), t.map { it["id"].long })
        assertApi(call(0), "GET", "/playlists/1001")
        assertEquals("full", call(0).requestUrl!!.queryParameter("representation"))
        assertApi(call(1), "GET", "/tracks")
        assertEquals("303,304", call(1).requestUrl!!.queryParameter("ids"))
        val first = t[0]
        // What Trackhop's parser reads is kept; the heavy rest is not.
        assertEquals("Daft Punk - Around the World", first["title"].str)
        assertEquals("channel", first["user"]["username"].str)
        assertEquals("Daft Punk", first["publisher_metadata"]["artist"].str)
        assertEquals("GBDUW9600011", first["publisher_metadata"]["isrc"].str)
        assertEquals(238000L, first["full_duration"].long)
        assertEquals("ALLOW", first["policy"].str)
        assertNull(first["media"]); assertNull(first["waveform_url"]); assertNull(first["artwork_url"])
        assertNull(first["publisher_metadata"]["urn"])
        assertNull(first["user"]["avatar_url"])
        assertTrue(first.toString().length < 700)
        assertEquals("Pañuelo – live", t[1]["title"].str)
    }

    @Test fun likedTracksFollowNextHrefAndCarryTheLikeDate() {
        answer("""{"collection":[{"created_at":"2026-05-01T10:00:00Z","kind":"like","track":${track(301)}},{"created_at":"2026-04-01T10:00:00Z","kind":"like","playlist":{"id":9}}],
            "next_href":"https://api-v2.soundcloud.com/users/123/track_likes?offset=1714557600000&limit=200"}""")
        answer("""{"collection":[{"created_at":"2026-03-01T10:00:00Z","kind":"like","track":${track(302)}}],"next_href":null}""")
        val t = sc.tracks("__liked__")
        assertEquals(listOf(301L, 302L), t.map { it["id"].long })
        assertEquals("2026-05-01T10:00:00Z", t[0]["liked_at"].str)
        assertApi(call(0), "GET", "/users/123/track_likes")
        assertEquals("1714557600000", call(1).requestUrl!!.queryParameter("offset"))
    }

    @Test fun aPublicPageIsResolved() {
        answer(set(77, track(301)))
        val t = sc.tracks("daftpunk/sets/homework")
        assertEquals(1, t.size)
        assertApi(call(0), "GET", "/resolve")
        assertEquals("https://soundcloud.com/daftpunk/sets/homework", call(0).requestUrl!!.queryParameter("url"))
        answer("not found", 404)
        try { sc.playlistInfo("someone/sets/private"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.NOT_PUBLIC, e.message) }
    }

    @Test fun searchReturnsTrimmedTracks() {
        answer("""{"collection":[${track(301)},{"id":9,"kind":"playlist","title":"not a track"}],"total_results":2,"next_href":null}""")
        val r = sc.search("Daft Punk Around the World")
        assertEquals(listOf(301L), r.map { it["id"].long })
        assertApi(call(0), "GET", "/search/tracks")
        assertEquals("Daft Punk Around the World", call(0).requestUrl!!.queryParameter("q"))
    }

    @Test fun versionIsLastModifiedAndSize() {
        answer(set(1001, stub(1), stub(2)))
        assertEquals("2026-10-01T10:00:00Z|2", sc.version("1001"))
    }

    @Test fun likesVersionIsTheirCountAndNewestLike() {
        answer("""{"collection":[{"created_at":"2026-05-01T10:00:00Z","kind":"like","track":${track(301)}}],"next_href":"https://api-v2.soundcloud.com/users/123/track_likes?offset=1&limit=1"}""")
        assertEquals("2026-05-01T10:00:00Z|301", sc.version("__liked__"))
        val likes = BridgeMock.calls.last()
        assertApi(likes, "GET", "/users/123/track_likes")
        assertEquals("1", likes.requestUrl!!.queryParameter("limit"))
        // A public likes page: the user's count too, so an unlike shows.
        answer("""{"id":5,"kind":"user","username":"someone","likes_count":42}""")
        answer("""{"collection":[{"created_at":"2026-06-01T10:00:00Z","kind":"like","track":${track(302)}}],"next_href":null}""")
        assertEquals("42|2026-06-01T10:00:00Z|302", sc.version("someone/likes"))
        answer("""{"id":5,"kind":"user","username":"someone","likes_count":0}""")
        answer("""{"collection":[],"next_href":null}""")
        assertEquals("0", sc.version("someone/likes"))
    }

    // ------------------------------------------------------------------ session and client id

    @Test fun aRefusedClientIdIsReadAgainFromTheSiteOnce() {
        answer("""{"error":"invalid client"}""", 401)
        answer(set(1001))
        sc.tracks("1001")
        assertEquals(fresh, call(1).requestUrl!!.queryParameter("client_id"))
        assertEquals(fresh, SoundCloudBridge.session.get(app, "clientId"))
        // The last script is read first: the id lives in the last bundles.
        val scripts = BridgeMock.all.filter { it.getHeader(BridgeMock.ORIGINAL_HOST) == "a-v2.sndcdn.com" }.map { it.path!! }
        assertEquals("/assets/49-bbb.js", scripts.first())
    }

    @Test fun aSecondRefusalIsTheExpiredSessionAndNamesNoApp() {
        answer("", 401); answer("", 401)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) {
            assertEquals(SoundCloudWebClient.EXPIRED, e.message)
            assertTrue(e.message!!.contains("sign out of SoundCloud and sign in again"))
            assertFalse(e.message!!.contains("Trackhop") || e.message!!.contains("Bridge"))
        }
    }

    @Test fun clientIdPatterns() {
        assertEquals(fresh, SoundCloudWebClient.clientIdIn("""e={client_id:"$fresh",x:1}"""))
        assertNull(SoundCloudWebClient.clientIdIn("""client_id:"short""""))
        SoundCloudBridge.rememberClientId(app, "https://api-v2.soundcloud.com/me?client_id=$fresh&app_version=1")
        assertEquals(fresh, SoundCloudBridge.session.get(app, "clientId"))
    }

    // ------------------------------------------------------------------ the sign-in's identity and DataDome

    @Test fun apiCallsCarryTheSignInsUserAgentAndDataDomeCookie() {
        val ua = "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        SoundCloudBridge.session.save(app, "oauth_token=2-290000-123-AbCdEf; datadome=k~Xy_1; sc_anonymous_id=x", "scuser")
        SoundCloudBridge.rememberUserAgent(app, ua)
        answer(set(1001))
        sc.tracks("1001")
        assertApi(call(0), "GET", "/playlists/1001")
        assertEquals(ua, call(0).getHeader("User-Agent"))
        // Only DataDome's cookie goes along: the account travels in the Authorization header.
        assertEquals("datadome=k~Xy_1", call(0).getHeader("Cookie"))
    }

    @Test fun aSessionFromBeforeKeepsItsDesktopIdentityAndSendsNoCookie() {
        answer(set(1001))
        sc.tracks("1001")
        assertEquals(BridgeUa.desktop(app), call(0).getHeader("User-Agent"))
        assertNull(call(0).getHeader("Cookie"))
    }

    @Test fun aDataDomeRefusalIsSaidAtOnceAndNamesNoApp() {
        answer("""{"url":"https://geo.captcha-delivery.com/captcha/?initialCid=AHrl&hash=05B3&cid=k~x&t=bv&referer=x&s=1"}""", 403)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) {
            assertEquals(SoundCloudBridge.BLOCKED, e.message)
            assertFalse(e.message!!.contains("Trackhop") || e.message!!.contains("Bridge"))
        }
        // No second try and no fresh client id: asking again only adds to what DataDome counts.
        assertEquals(1, BridgeMock.all.size)
    }

    @Test fun anOrdinary403StillRefreshesTheClientIdOnce() {
        answer("""{"error":"forbidden"}""", 403)
        answer(set(1001))
        sc.tracks("1001")
        assertEquals(fresh, call(1).requestUrl!!.queryParameter("client_id"))
    }

    // ------------------------------------------------------------------ a token from the mobile site's requests

    private val headerToken = "2-294035-123-HeAdErToK"
    private val mobiCid = "M".repeat(32)

    /** A session as the sign-in keeps it when the token came from the page's own Authorization header. */
    private fun signedInByHeader() {
        SoundCloudBridge.session.save(app, "sc_anonymous_id=x; datadome=k~1", "scuser")
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(headerToken, "header:api-mobi.soundcloud.com"))
        SoundCloudBridge.session.put(app, "clientIdMobi", mobiCid)
    }

    @Test fun aTokenFromTheHeaderIsSentToApiV2WithoutAnyCookieToken() {
        signedInByHeader()
        answer(set(1001))
        sc.tracks("1001")
        assertEquals("api-v2.soundcloud.com", call(0).host())
        assertEquals("OAuth $headerToken", call(0).getHeader("Authorization"))
        assertEquals(cid, call(0).requestUrl!!.queryParameter("client_id"))
        assertEquals("datadome=k~1", call(0).getHeader("Cookie"))
        assertNull(SoundCloudBridge.session.get(app, "apiHost"))
    }

    @Test fun theKeptTokenWinsOverAnOlderCookie() {
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(headerToken, "cookie:m.soundcloud.com"))
        answer(set(1001))
        sc.tracks("1001")
        assertEquals("OAuth $headerToken", call(0).getHeader("Authorization"))
    }

    @Test fun aMobileTokenApiV2RefusesGoesToApiMobiFromThenOn() {
        signedInByHeader()
        answer("", 401); answer("", 401)
        answer(set(1001))
        sc.tracks("1001")
        // api-v2 twice (the second with a client id read again), then the same call on api-mobi.
        assertEquals(listOf("api-v2.soundcloud.com", "api-v2.soundcloud.com", "api-mobi.soundcloud.com"), (0..2).map { call(it).host() })
        assertEquals("/playlists/1001", call(2).requestUrl!!.encodedPath)
        assertEquals(mobiCid, call(2).requestUrl!!.queryParameter("client_id"))
        assertEquals("OAuth $headerToken", call(2).getHeader("Authorization"))
        assertEquals("https://m.soundcloud.com", call(2).getHeader("Origin"))
        assertEquals(SoundCloudBridge.API_MOBI, SoundCloudBridge.session.get(app, "apiHost"))
        // Kept: the next call goes there directly, its next page as well.
        answer("""{"collection":[{"id":1001,"title":"Mine","track_count":2,"user_id":123}],"next_href":"https://api-v2.soundcloud.com/users/123/playlists_without_albums?offset=1"}""")
        answer("""{"collection":[],"next_href":null}""")
        answer("""{"collection":[],"next_href":null}""")
        sc.libraryPlaylists()
        assertEquals("api-mobi.soundcloud.com", call(3).host())
        assertEquals("api-mobi.soundcloud.com", call(4).host())
        assertEquals("1", call(4).requestUrl!!.queryParameter("offset"))
    }

    @Test fun refusedOnBothIsTheExpiredSession() {
        signedInByHeader()
        answer("", 401); answer("", 401); answer("", 401)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) }
        assertEquals(3, BridgeMock.calls.size)
        assertNull(SoundCloudBridge.session.get(app, "apiHost"))
    }

    @Test fun theMobileSitesClientIdIsKeptApart() {
        SoundCloudBridge.rememberClientId(app, "https://api-mobi.soundcloud.com/me?client_id=$mobiCid")
        assertEquals(mobiCid, SoundCloudBridge.session.get(app, "clientIdMobi"))
        // The desktop one already kept stays.
        assertEquals(cid, SoundCloudBridge.session.get(app, "clientId"))
        SoundCloudBridge.session.put(app, "clientId", null)
        SoundCloudBridge.rememberClientId(app, "https://api-mobi.soundcloud.com/me?client_id=$mobiCid")
        assertEquals(mobiCid, SoundCloudBridge.session.get(app, "clientId"))
        // A request elsewhere names nothing.
        SoundCloudBridge.rememberClientId(app, "https://m.soundcloud.com/?client_id=$fresh")
        assertEquals(mobiCid, SoundCloudBridge.session.get(app, "clientId"))
    }

    // ------------------------------------------------------------------ the token's hour (1.3.3 report)

    private val oldToken = "2-294035-123-OldOldOld1"
    private val newToken = "2-294035-123-NewNewNew2"

    /** As the owner's session: the mobile site's cookie token, which api-v2 took until it expired. */
    private fun signedInByMobileCookie() {
        SoundCloudBridge.session.save(app, "oauth_token=$oldToken; datadome=k~1", "scuser")
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(oldToken, "cookie:m.soundcloud.com"))
        SoundCloudBridge.session.put(app, "clientIdMobi", mobiCid)
    }

    private fun logs(): MutableList<String> = ArrayList<String>().also { l -> BridgeNet.logSink = { tag, m -> l += "[$tag] $m" } }

    @Test fun anExpiredTokenIsRenewedInAHiddenPageAndTheCallMadeOnceMore() {
        signedInByMobileCookie()
        val logs = logs()
        pageAnswer = { SoundCloudRenewal.Page(SoundCloudBridge.Found(newToken, "cookie:m.soundcloud.com"), "oauth_token=$newToken; datadome=k~2", null) }
        // The owner's log: refused on api-v2, again with a fresh client id, and on api-mobi.
        answer("", 401); answer("", 401); answer("", 401)
        answer(set(1001))
        sc.tracks("1001")
        assertEquals(listOf<String?>(oldToken), pages)
        assertEquals(4, BridgeMock.calls.size)
        assertEquals("api-v2.soundcloud.com", call(3).host())
        assertEquals("OAuth $newToken", call(3).getHeader("Authorization"))
        assertEquals("datadome=k~2", call(3).getHeader("Cookie"))
        assertEquals(newToken, SoundCloudBridge.token(app))
        assertTrue(System.currentTimeMillis() - SoundCloudBridge.tokenAt(app)!! < 60_000)
        assertNull(SoundCloudBridge.session.get(app, "apiHost"))
        assertTrue(logs.toString(), logs.any { it.startsWith("[soundcloud] renewing the session in a hidden page (the token was refused with HTTP 401 on api-mobi.soundcloud.com)") })
        assertTrue(logs.toString(), logs.any { it.startsWith("[soundcloud] session renewed in a hidden page: new token from cookie:m.soundcloud.com") })
        assertTrue(logs.toString(), logs.none { it.contains(oldToken) || it.contains(newToken) })
    }

    @Test fun aRenewedTokenThatIsRefusedTooIsTheExpiredSession() {
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(oldToken, "cookie:soundcloud.com"))
        pageAnswer = { SoundCloudRenewal.Page(SoundCloudBridge.Found(newToken, "header:api-v2.soundcloud.com"), null, null) }
        answer("", 401); answer("", 401); answer("", 401)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) }
        // Renewed once, the call made once more, then said as it is.
        assertEquals(1, pages.size)
        assertEquals(3, BridgeMock.calls.size)
        assertEquals("OAuth $newToken", call(2).getHeader("Authorization"))
    }

    @Test fun aDataDomePuzzleInTheHiddenPageEndsInSignInAgainAndTheLogSaysWhy() {
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(oldToken, "cookie:m.soundcloud.com"))
        val logs = logs()
        pageAnswer = { SoundCloudRenewal.Page(null, "datadome=k~3", SoundCloudRenewal.dataDomeProblem(DataDome.Kind.CAPTCHA)) }
        answer("", 401); answer("", 401)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) }
        assertEquals(oldToken, SoundCloudBridge.token(app))
        assertTrue(logs.toString(), logs.any { it.contains("could not be renewed") && it.contains("DataDome") && it.contains("puzzle") })
    }

    @Test fun aTokenNearItsHourIsRenewedBeforeTheCallOnce() {
        signedInByMobileCookie()
        SoundCloudBridge.session.put(app, "tokenAt", (System.currentTimeMillis() - 55 * 60_000L).toString())
        pageAnswer = { SoundCloudRenewal.Page(SoundCloudBridge.Found(newToken, "header:api-mobi.soundcloud.com"), null, null) }
        answer(set(1001)); answer(set(1001))
        sc.tracks("1001")
        sc.tracks("1002")
        assertEquals(1, pages.size)
        assertEquals("OAuth $newToken", call(0).getHeader("Authorization"))
        assertEquals("OAuth $newToken", call(1).getHeader("Authorization"))
    }

    @Test fun aTokenOfUnknownAgeIsNotRenewedAhead() {
        // A session from before the token's time was kept: nothing to go by.
        answer(set(1001))
        sc.tracks("1001")
        assertTrue(pages.isEmpty())
        assertNull(SoundCloudBridge.tokenAt(app))
    }

    // ------------------------------------------------------------------ an expired site session (4 idle days)

    @Test fun aPageThatRenewsNothingOnceIsNotReopenedForAWhileButEndsNothing() {
        signedInByMobileCookie()
        pageAnswer = { SoundCloudRenewal.Page(null, null, SoundCloudRenewal.RENEWED_NOTHING) }
        repeat(9) { answer("", 401) }
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) }
        assertEquals(1, pages.size)
        assertNull("one page proves nothing", SoundCloudRenewal.expiredAt(app))
        // The next calls within 15 minutes open no page (the owner's log had six in two minutes).
        repeat(2) { try { sc.tracks("1002"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) } }
        assertEquals(1, pages.size)
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    @Test fun aSecondPageThatRenewsNothingAnHourLaterEndsTheSessionOnce() {
        signedInByMobileCookie()
        val logs = logs()
        SoundCloudRenewal.strike(app, System.currentTimeMillis() - 2 * 60 * 60_000L)
        assertNull(SoundCloudRenewal.expiredAt(app))
        pageAnswer = { SoundCloudRenewal.Page(null, null, SoundCloudRenewal.RENEWED_NOTHING) }
        answer("", 401); answer("", 401); answer("", 401)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) }
        assertEquals(1, pages.size)
        assertTrue(SoundCloudRenewal.expiredAt(app) != null)
        // From then on said at once: no request, no hidden page, until the next sign-in.
        val calls = BridgeMock.calls.size
        repeat(3) { try { sc.tracks("1002"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) } }
        assertEquals(calls, BridgeMock.calls.size)
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertEquals(1, pages.size)
        assertEquals(logs.toString(), 1, logs.count { it.contains("the session has expired") })
    }

    @Test fun aSecondPageWithinTheHourEndsNothing() {
        signedInByMobileCookie()
        val now = System.currentTimeMillis()
        SoundCloudRenewal.strike(app, now - 30 * 60_000L)
        SoundCloudRenewal.strike(app, now)
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    @Test fun aRenewalClearsTheStrikes() {
        signedInByMobileCookie()
        tokenAged(12 * 60)
        SoundCloudRenewal.strike(app, System.currentTimeMillis() - 2 * 60 * 60_000L)
        pageAnswer = { SoundCloudRenewal.Page(SoundCloudBridge.Found(newToken, "cookie:m.soundcloud.com"), null, null) }
        assertTrue(SoundCloudRenewal.keepAlive(app))
        // A later page that renews nothing is a first strike again.
        SoundCloudRenewal.strike(app)
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    @Test fun aPageThatFailedWithAnHttpErrorNeverEndsTheSession() {
        signedInByMobileCookie()
        pageAnswer = { SoundCloudRenewal.Page(null, null, "the page did not load (HTTP 503)") }
        repeat(6) { answer("", 401) }
        repeat(2) { try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) } }
        // Not a strike: tried again at the next call.
        assertEquals(2, pages.size)
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    @Test fun aTokenOfUnknownAgeIsNotTakenAsOver() {
        // A session from before the token's time was kept: the keep-alive's page renewing nothing counts for nothing.
        SoundCloudRenewal.strike(app, System.currentTimeMillis() - 2 * 60 * 60_000L)
        SoundCloudBridge.session.put(app, "renewedNothingLastAt", null)
        assertNull(SoundCloudBridge.tokenAt(app))
        pageAnswer = { SoundCloudRenewal.Page(null, null, SoundCloudRenewal.RENEWED_NOTHING) }
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertEquals(1, pages.size)
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    @Test fun aSignInAgainLetsRenewalsRunAgain() {
        signedInByMobileCookie()
        SoundCloudRenewal.markExpired(app, "test")
        val at = SoundCloudRenewal.expiredAt(app)
        SoundCloudRenewal.markExpired(app, "again")
        assertEquals("marked once: the first time stays", at, SoundCloudRenewal.expiredAt(app))
        SoundCloudRenewal.strike(app, System.currentTimeMillis() - 2 * 60 * 60_000L)
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(newToken, "cookie:m.soundcloud.com"))
        assertNull(SoundCloudRenewal.expiredAt(app))
        assertNull(SoundCloudBridge.session.get(app, "renewedNothing"))
    }

    @Test fun aRenewedTokenRefusedTooIsSaidButEndsNothing() {
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(oldToken, "cookie:soundcloud.com"))
        pageAnswer = { SoundCloudRenewal.Page(SoundCloudBridge.Found(newToken, "header:api-v2.soundcloud.com"), null, null) }
        answer("", 401); answer("", 401); answer("", 401)
        try { sc.tracks("1001"); fail() } catch (e: BridgeException) { assertEquals(SoundCloudWebClient.EXPIRED, e.message) }
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    private fun tokenAged(minutes: Long) = SoundCloudBridge.session.put(app, "tokenAt", (System.currentTimeMillis() - minutes * 60_000L).toString())

    @Test fun aPageThatCouldNotRenewForAnotherReasonIsTriedAgainLater() {
        SoundCloudBridge.rememberToken(app, SoundCloudBridge.Found(oldToken, "cookie:m.soundcloud.com"))
        tokenAged(13 * 60)
        pageAnswer = { SoundCloudRenewal.Page(null, null, SoundCloudRenewal.dataDomeProblem(DataDome.Kind.CAPTCHA)) }
        assertFalse(SoundCloudRenewal.keepAlive(app))
        pageAnswer = { SoundCloudRenewal.Page(null, null, "the page did not load (error -2)") }
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertNull(SoundCloudRenewal.expiredAt(app))
        assertEquals(2, pages.size)
    }

    @Test fun theKeepAliveRenewsAnOldTokenInOneLogLine() {
        signedInByMobileCookie()
        tokenAged(12 * 60)
        val logs = logs()
        pageAnswer = { SoundCloudRenewal.Page(SoundCloudBridge.Found(newToken, "cookie:m.soundcloud.com"), "oauth_token=$newToken; datadome=k~2", null) }
        assertTrue(SoundCloudRenewal.keepAlive(app))
        assertEquals(listOf<String?>(oldToken), pages)
        assertEquals(newToken, SoundCloudBridge.token(app))
        assertEquals(logs.toString(), 1, logs.size)
        assertTrue(logs.toString(), logs[0].startsWith("[soundcloud] session renewed in a hidden page (keep-alive)"))
        assertTrue(BridgeMock.calls.isEmpty())
    }

    @Test fun theKeepAliveLeavesATokenOfTheLastHourAlone() {
        signedInByMobileCookie()
        tokenAged(20)
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertTrue(pages.isEmpty())
    }

    @Test fun anIdleSessionWhosePageRenewsNothingTwiceIsMarkedExpiredByTheKeepAlive() {
        signedInByMobileCookie()
        tokenAged(4 * 24 * 60)
        pageAnswer = { SoundCloudRenewal.Page(null, null, SoundCloudRenewal.RENEWED_NOTHING) }
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertNull(SoundCloudRenewal.expiredAt(app))
        // Twelve hours later, the next keep-alive.
        SoundCloudBridge.session.put(app, "renewedNothingFirstAt", (System.currentTimeMillis() - 12 * 60 * 60_000L).toString())
        SoundCloudBridge.session.put(app, "renewedNothingLastAt", (System.currentTimeMillis() - 12 * 60 * 60_000L).toString())
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertEquals(2, pages.size)
        assertTrue(SoundCloudRenewal.expiredAt(app) != null)
    }

    @Test fun aTokenStillValidThatThePageDoesNotRenewYetEndsNothing() {
        // Renewed ahead at 55 minutes: the site may keep a token that still works for a few minutes.
        signedInByMobileCookie()
        tokenAged(55)
        pageAnswer = { SoundCloudRenewal.Page(null, null, SoundCloudRenewal.RENEWED_NOTHING) }
        answer(set(1001))
        sc.tracks("1001")
        assertEquals(1, pages.size)
        assertNull(SoundCloudRenewal.expiredAt(app))
    }

    @Test fun theKeepAliveDoesNothingSignedOut() {
        SoundCloudBridge.session.clear(app)
        assertFalse(SoundCloudRenewal.keepAlive(app))
        assertTrue(pages.isEmpty())
    }

    @Test fun aRenewalTakesOnlyATokenOtherThanTheOldOne() {
        val jar = mapOf(SoundCloudBridge.SITE to "oauth_token=$oldToken", SoundCloudBridge.MOBILE_SITE to "oauth_token=$newToken", "https://secure.soundcloud.com" to null)
        assertEquals(newToken, SoundCloudRenewal.newToken(null, jar, oldToken)?.token)
        assertEquals("cookie:m.soundcloud.com", SoundCloudRenewal.newToken(null, jar, oldToken)?.source)
        assertEquals("header:api-mobi.soundcloud.com", SoundCloudRenewal.newToken(SoundCloudBridge.Found("2-1-2-HeAdEr3", "header:api-mobi.soundcloud.com"), jar, oldToken)?.source)
        assertEquals(newToken, SoundCloudRenewal.newToken(SoundCloudBridge.Found(oldToken, "header:api-mobi.soundcloud.com"), jar, oldToken)?.token)
        assertNull(SoundCloudRenewal.newToken(SoundCloudBridge.Found(oldToken, "header:x"), mapOf(SoundCloudBridge.SITE to "oauth_token=$oldToken"), oldToken))
        assertNull(SoundCloudRenewal.dataDomeProblem(DataDome.Kind.CHECK))
        assertTrue(SoundCloudRenewal.dataDomeProblem(DataDome.Kind.BLOCKED)!!.contains("blocked"))
    }

    // ------------------------------------------------------------------ writes

    @Test fun createIsAPrivateEmptySet() {
        answer("""{"id":5005,"kind":"playlist","title":"Synced – ✓","track_count":0,"tracks":[]}""")
        val p = sc.createPlaylist("Synced – ✓", "Synced with Trackhop")
        assertEquals("5005", p.id); assertTrue(p.owned)
        assertApi(call(0), "POST", "/playlists")
        val b = call(0).json()["playlist"]
        assertEquals("Synced – ✓", b["title"].str)
        assertEquals("private", b["sharing"].str)
        assertEquals(0, b["tracks"].arr.size)
        assertEquals("application/json", call(0).getHeader("Content-Type")!!.substringBefore(';'))
    }

    @Test fun addAppendsToTheWholeListAndPutsItBack() {
        answer(set(1001, track(301), stub(303)))
        answer(set(1001))
        sc.add("1001", listOf("401", "402"))
        assertApi(call(1), "PUT", "/playlists/1001")
        assertEquals("[301,303,401,402]", call(1).json()["playlist"]["tracks"].toString())
        assertNull(call(1).json()["playlist"]["title"])
    }

    @Test fun removeTakesEveryCopyOfTheTrack() {
        answer(set(1001, stub(301), stub(303), stub(301)))
        answer(set(1001))
        sc.remove("1001", listOf("301"))
        assertEquals("[303]", call(1).json()["playlist"]["tracks"].toString())
    }

    @Test fun removingWhatIsNotThereSendsNothing() {
        answer(set(1001, stub(303)))
        sc.remove("1001", listOf("999"))
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun aSetHoldsFiveHundredTracksAtMost() {
        answer(set(1001, *(1L..499L).map { stub(it) }.toTypedArray()))
        try { sc.add("1001", listOf("600", "601")); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("500")) }
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun reorderSendsTheSameTracksInTheNewOrder() {
        answer(set(1001, stub(1), stub(2), stub(3)))
        answer(set(1001))
        sc.reorder("1001", listOf("3", "1", "2"))
        assertEquals("[3,1,2]", call(1).json()["playlist"]["tracks"].toString())
    }

    @Test fun reorderOfAChangedPlaylistMovesNothing() {
        answer(set(1001, stub(1), stub(2), stub(4)))
        try { sc.reorder("1001", listOf("3", "1", "2")); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("nothing was moved")) }
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun renameAndVisibilityKeepTheTracks() {
        answer(set(1001, stub(1), stub(2))); answer(set(1001))
        sc.rename("1001", "New – name")
        val b = call(1).json()["playlist"]
        assertEquals("New – name", b["title"].str)
        assertEquals("[1,2]", b["tracks"].toString())
        answer(set(1001, stub(1), stub(2))); answer(set(1001))
        sc.setPublic("1001", true)
        assertEquals("public", call(3).json()["playlist"]["sharing"].str)
        assertEquals("[1,2]", call(3).json()["playlist"]["tracks"].toString())
    }

    @Test fun artworkIsTheBase64OfTheJpegOnTheSetsArtworkPath() {
        answer("""{"artwork_url":"https://i1.sndcdn.com/artworks-made-up-large.jpg"}""")
        sc.setArtwork("1001", byteArrayOf(-1, -40, -1, -32, 1, 2, 3))
        val r = call(0)
        assertApi(r, "PUT", "/playlists/1001/artwork")
        assertEquals("application/json", r.getHeader("Content-Type")!!.substringBefore(';'))
        assertEquals("/9j/4AECAw==", r.json()["image_data"].str)
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun noArtworkOnAPublicPage() {
        try { sc.setArtwork("someone/sets/x", byteArrayOf(1)); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("read-only")) }
        assertEquals(0, BridgeMock.calls.size)
    }

    @Test fun deleteIsADelete() {
        answer("")
        sc.delete("1001")
        assertApi(call(0), "DELETE", "/playlists/1001")
    }

    @Test fun likesArePutsAndDeletesOnTheUsersTrackLikes() {
        answer(""); answer(""); answer("", 404)
        sc.add("__liked__", listOf("401", "402"))
        sc.remove("__liked__", listOf("403"))
        assertApi(call(0), "PUT", "/users/123/track_likes/401")
        assertApi(call(1), "PUT", "/users/123/track_likes/402")
        assertApi(call(2), "DELETE", "/users/123/track_likes/403")
    }

    @Test fun aPublicPageIsReadOnly() {
        try { sc.add("someone/sets/x", listOf("1")); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("read-only")) }
        assertEquals(0, BridgeMock.calls.size)
    }

    @Test fun trimDropsWhatIsNotATrack() {
        assertNull(SoundCloudWebClient.trim(parseJson("""{"id":1,"kind":"playlist","title":"x"}""")))
        assertNull(SoundCloudWebClient.trim(parseJson("""{"kind":"track","title":"x"}""")))
        assertEquals(1L, SoundCloudWebClient.trim(parseJson(stub(1)))!!["id"].long)
    }
}

package com.xlollx.songport.ytmbridge

import android.app.Application
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
 * Qobuz through play.qobuz.com's API, against a local mock server. The addresses, headers and
 * answers follow a signed-in session of the web player recorded on 04/11/2026 (search and album
 * answers trimmed from the real ones in resources/qobuz; playlists made up). The POST bodies were
 * not recorded: the form tests pin the field names of [QobuzWebClient.Form], not what Qobuz accepts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class QobuzWebClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var c: QobuzWebClient
    private val token = "Ab3_" + "x".repeat(80) + "-Z"

    @Before fun setUp() {
        BridgeMock.start()
        app = RuntimeEnvironment.getApplication()
        assertNull(QobuzBridge.keep(app, token, "798273057"))
        c = QobuzWebClient(app)
    }

    @After fun tearDown() { BridgeMock.stop() }

    private fun resource(name: String) = javaClass.getResource("/qobuz/$name")!!.readText()
    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.q(name: String) = requestUrl!!.queryParameter(name)
    private fun RecordedRequest.form(): Map<String, String> = body.clone().readUtf8().split('&').filter { it.isNotEmpty() }
        .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private val playlists = """{"user":{"id":1001,"login":"listener"},"playlists":{"offset":0,"limit":500,"total":2,"items":[
        {"id":7001,"name":"Road trip","description":"for the car","tracks_count":3,"is_public":false,"owner":{"id":1001,"name":"listener"}},
        {"id":7002,"name":"Qobuz picks","tracks_count":40,"is_public":true,"owner":{"id":2,"name":"Qobuz"}}]}}"""

    private fun entry(id: Int, entry: Int, title: String, version: String? = null) =
        """{"id":$id,"title":"$title","version":${version?.let { "\"$it\"" } ?: "null"},"duration":200,"isrc":"USAAA00000$id","parental_warning":true,
           "performer":{"name":"Artist $id"},"album":{"title":"Album $id","artist":{"name":"Album artist"},"release_date_original":"2019-05-01"},
           "playlist_track_id":$entry,"streamable":true}"""

    @Test fun playlistsCarryTheSessionAndTellFollowedOnesApart() {
        BridgeMock.answer(playlists)
        val list = c.playlists()
        assertEquals(listOf("7001", "7002"), list.map { it.id })
        assertEquals(listOf(true, false), list.map { it.owned })
        assertEquals(3, list[0].count)
        assertEquals("for the car", list[0].description)
        val r = call(0)
        assertEquals("GET", r.method)
        assertEquals("www.qobuz.com", r.getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/api.json/0.2/playlist/getUserPlaylists", r.requestUrl!!.encodedPath)
        assertEquals("0", r.q("offset"))
        assertEquals("500", r.q("limit"))
        assertEquals(token, r.getHeader("X-User-Auth-Token"))
        assertEquals("798273057", r.getHeader("X-App-Id"))
        assertEquals("https://play.qobuz.com", r.getHeader("Origin"))
        assertEquals("https://play.qobuz.com/", r.getHeader("Referer"))
    }

    @Test fun theAccountIsNamedFromThePlaylistsAnswer() {
        BridgeMock.answer(playlists)
        assertEquals("listener", c.probe())
        assertEquals("listener", QobuzWebClient.accountIn(parseJson("""{"user":{"id":5},"playlists":{"items":[{"owner":{"id":9,"name":"x"}},{"owner":{"id":5,"name":"listener"}}]}}""")))
        assertNull(QobuzWebClient.accountIn(parseJson("""{"playlists":{"items":[]}}""")))
    }

    @Test fun aPlaylistIsReadPageByPageWithItsEntries() {
        BridgeMock.answer("""{"id":7001,"name":"Road trip","tracks_count":3,"tracks":{"offset":0,"limit":2,"total":3,"items":[${entry(11, 901, "One")},${entry(12, 902, "Two", "Live")}]}}""")
        BridgeMock.answer("""{"id":7001,"name":"Road trip","tracks_count":3,"tracks":{"offset":2,"limit":2,"total":3,"items":[${entry(13, 903, "Three (Remix)", "Remix")}]}}""")
        val t = c.tracks("7001")
        assertEquals(listOf("11", "12", "13"), t.map { it.id })
        assertEquals(listOf("901", "902", "903"), t.map { it.setVideoId })
        assertEquals("Two (Live)", t[1].title)
        // A version the title already names is not repeated.
        assertEquals("Three (Remix)", t[2].title)
        assertEquals(listOf("Artist 11"), t[0].artists)
        assertEquals("Album 11", t[0].album)
        assertEquals(200_000L, t[0].durationMs)
        assertEquals("USAAA0000011", t[0].isrc)
        assertEquals(true, t[0].explicit)
        assertEquals(2019, t[0].year)
        assertEquals("/api.json/0.2/playlist/get", call(0).requestUrl!!.encodedPath)
        assertEquals("7001", call(0).q("playlist_id"))
        assertEquals("tracks", call(0).q("extra"))
        assertEquals("2", call(1).q("offset"))
    }

    @Test fun likedSongsAreTheFavouriteTracks() {
        BridgeMock.answer("""{"user":{"id":1001},"tracks":{"offset":0,"limit":500,"total":1,"items":[${entry(21, 0, "Fav")}]}}""")
        assertEquals(listOf("21"), c.liked().map { it.id })
        assertEquals("/api.json/0.2/favorite/getUserFavorites", call(0).requestUrl!!.encodedPath)
        assertEquals("tracks", call(0).q("type"))
    }

    @Test fun searchReadsTheCataloguesTracks() {
        BridgeMock.answer(resource("search.json"))
        val r = c.search("Bring me")
        assertEquals(listOf("17795395"), r.take(1).map { it.id })
        assertEquals(3, r.size)
        assertEquals("Bring Me To Life", r[0].title)
        assertEquals(listOf("Evanescence"), r[0].artists)
        assertEquals("Fallen", r[0].album)
        assertEquals(235_000L, r[0].durationMs)
        assertEquals("USWU30200093", r[0].isrc)
        assertEquals(2003, r[0].year)
        assertEquals("/api.json/0.2/catalog/search", call(0).requestUrl!!.encodedPath)
        assertEquals("Bring me", call(0).q("query"))
        assertEquals("10", call(0).q("limit"))
    }

    @Test fun searchByIsrcKeepsOnlyThatRecordingThenFallsBackToText() {
        BridgeMock.answer(resource("search.json"))
        assertEquals(listOf("17795395"), c.search("ignored", "uswu30200093").map { it.id })
        assertEquals("USWU30200093", call(0).q("query"))
        // No result with the code: the text search follows.
        BridgeMock.answer("""{"query":"GBXXX0000001","tracks":{"limit":10,"offset":0,"total":0,"items":[]}}""")
        BridgeMock.answer(resource("search.json"))
        assertEquals(3, c.search("Bring me", "GBXXX0000001").size)
        assertEquals("GBXXX0000001", call(1).q("query"))
        assertEquals("Bring me", call(2).q("query"))
    }

    @Test fun anAlbumsTracksTakeTheAlbumsTitle() {
        BridgeMock.answer(resource("album-get.json"))
        val t = c.album("bwmuu6rf7umge")
        assertEquals(listOf("440805159", "440805160"), t.map { it.id })
        assertEquals("Perfecth", t[0].album)
        assertEquals(listOf("Queens Of The Stone Age"), t[0].artists)
        assertEquals(187_000L, t[0].durationMs)
        assertEquals("track_ids", call(0).q("extra"))
        assertEquals("bwmuu6rf7umge", call(0).q("album_id"))
    }

    @Test fun writesPostForms() {
        BridgeMock.answer("""{"id":7100,"name":"New","tracks_count":0,"is_public":false}""")
        val p = c.create("New", "made by Trackhop")
        assertEquals("7100", p.id)
        BridgeMock.answer("""{"id":7100}"""); c.add("7100", listOf("11", "12"))
        BridgeMock.answer("""{"id":7100}"""); c.remove("7100", listOf("11", "12"), listOf("901", "902"))
        BridgeMock.answer("""{"status":"success"}"""); c.like(listOf("11"), on = true)
        BridgeMock.answer("""{"status":"success"}"""); c.like(listOf("11"), on = false)
        BridgeMock.answer("""{"id":7100}"""); c.rename("7100", "Renamed")
        BridgeMock.answer("""{"id":7100}"""); c.setPublic("7100", true)
        BridgeMock.answer("""{"status":"success"}"""); c.delete("7100")
        BridgeMock.calls.forEach {
            assertEquals("POST", it.method)
            assertTrue(it.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            assertEquals(token, it.getHeader("X-User-Auth-Token"))
            assertEquals("798273057", it.getHeader("X-App-Id"))
        }
        val paths = BridgeMock.calls.map { it.requestUrl!!.encodedPath.removePrefix("/api.json/0.2/") }
        assertEquals(listOf("playlist/create", "playlist/addTracks", "playlist/deleteTracks", "favorite/create", "favorite/delete",
            "playlist/update", "playlist/update", "playlist/delete"), paths)
        val forms = BridgeMock.calls.map { it.form() }
        assertEquals(mapOf("name" to "New", "description" to "made by Trackhop", "is_public" to "false", "is_collaborative" to "false"), forms[0])
        assertEquals(mapOf("playlist_id" to "7100", "track_ids" to "11,12", "no_duplicate" to "true"), forms[1])
        assertEquals(mapOf("playlist_id" to "7100", "playlist_track_ids" to "901,902"), forms[2])
        assertEquals(mapOf("track_ids" to "11"), forms[3])
        assertEquals(mapOf("track_ids" to "11"), forms[4])
        assertEquals(mapOf("playlist_id" to "7100", "name" to "Renamed"), forms[5])
        assertEquals(mapOf("playlist_id" to "7100", "is_public" to "true"), forms[6])
        assertEquals(mapOf("playlist_id" to "7100"), forms[7])
    }

    @Test fun aRemovalWithoutEntryIdsReadsThemFromThePlaylist() {
        BridgeMock.answer("""{"id":7001,"tracks":{"offset":0,"limit":500,"total":3,"items":[${entry(11, 901, "One")},${entry(12, 902, "Two")},${entry(11, 905, "One")}]}}""")
        BridgeMock.answer("""{"id":7001}""")
        c.remove("7001", listOf("11"))
        assertEquals("/api.json/0.2/playlist/get", call(0).requestUrl!!.encodedPath)
        assertEquals(mapOf("playlist_id" to "7001", "playlist_track_ids" to "901,905"), call(1).form())
    }

    @Test fun anEndedSessionSaysSo() {
        BridgeMock.answer("""{"status":"error","code":401,"message":"User authentication is required."}""", code = 401)
        try { c.playlists(); fail() } catch (e: BridgeException) { assertEquals(QobuzWebClient.EXPIRED, e.message) }
        BridgeMock.answer("""{"status":"error","code":401,"message":"User authentication is required."}""")
        try { c.playlists(); fail() } catch (e: BridgeException) { assertEquals(QobuzWebClient.EXPIRED, e.message) }
    }

    @Test fun throttlingAndRefusalsSaySo() {
        BridgeMock.answer("", code = 429)
        try { c.liked(); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("429")) }
        BridgeMock.answer("""{"status":"error","code":400,"message":"Invalid or missing playlist_id parameter"}""", code = 400)
        try { c.tracks("x"); fail() } catch (e: BridgeException) { assertEquals("Qobuz: Invalid or missing playlist_id parameter", e.message) }
    }

    @Test fun theSessionIsFoundInWhatIsPastedAndInThePlayersRequests() {
        val curl = "curl 'https://www.qobuz.com/api.json/0.2/playlist/getUserPlaylists?offset=0&limit=500' \\\n  -H 'accept: */*' \\\n" +
            "  -H 'x-app-id: 123456789' \\\n  -H 'x-user-auth-token: $token'"
        assertEquals(token to "123456789", QobuzWebClient.sessionIn(curl))
        assertEquals(token to "123456789", QobuzWebClient.sessionIn("X-App-Id: 123456789\nX-User-Auth-Token: $token"))
        assertEquals(token to null, QobuzWebClient.sessionIn("  $token "))
        assertEquals(token to "42424242", QobuzWebClient.sessionIn("https://www.qobuz.com/api.json/0.2/user/get?app_id=42424242&user_auth_token=$token"))
        assertNull(QobuzWebClient.sessionIn("x-app-id: 123456789"))
        assertNull(QobuzWebClient.sessionIn("short-token"))
        val headers = mapOf("x-user-auth-token" to token, "X-App-Id" to "555666777")
        assertEquals(token to "555666777", QobuzBridge.fromRequest("https://www.qobuz.com/api.json/0.2/playlist/getUserPlaylists?offset=0", headers))
        assertNull(QobuzBridge.fromRequest("https://www.qobuz.com/api.json/0.2/user/login", mapOf("X-App-Id" to "555666777")))
        assertNull(QobuzBridge.fromRequest("https://example.com/api.json/0.2/x", headers))
        assertFalse(QobuzBridge.fromRequest("https://play.qobuz.com/login", headers) != null)
    }

    @Test fun theDefaultAppIdStandsInUntilOneIsSeen() {
        assertNull(QobuzBridge.keep(app, token))
        BridgeMock.answer(playlists)
        QobuzWebClient(app).playlists()
        assertEquals(QobuzWebClient.DEFAULT_APP_ID, call(0).getHeader("X-App-Id"))
    }
}

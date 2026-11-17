package com.xlollx.songport.ytmbridge

import android.app.Application
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
 * Gaana through gaana.com's API, against a local mock server. The addresses and the shapes of the
 * public answers (playlist/detail, the search, "Login Needed!") are those api.gaana.com gave on
 * 15/11/2026, with made-up songs; the signed calls follow the site's scripts and a session
 * recorded on 07/11/2026, and were never tried: their tests pin what [GaanaWebClient.Wire] sends,
 * not what Gaana accepts. No real account, token or song here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GaanaWebClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var c: GaanaWebClient
    private val token = "tk_" + "a1B2".repeat(12)

    @Before fun setUp() {
        BridgeMock.start()
        GaanaWebClient.gapMs = 0
        GaanaWebClient.retryWaitMs = 1
        app = RuntimeEnvironment.getApplication()
        assertNull(GaanaBridge.keep(app, token, deviceId = "dev-123", country = "IN"))
        c = GaanaWebClient(app)
    }

    @After fun tearDown() { BridgeMock.stop(); GaanaBridge.session.clear(app) }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.q(name: String) = requestUrl!!.queryParameter(name)
    private fun RecordedRequest.form(): Map<String, String> = body.clone().readUtf8().split('&').filter { it.isNotEmpty() }
        .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private val myPlaylists = """{"playlist":[
        {"playlist_id":"5550001","seokey":"road-trip-u1","title":"Road trip","trackids":"9000001,9000002,9000003","trackcount":3,"is_public":0},
        {"playlist_id":"5550002","seokey":"empty-u1","title":"Empty","trackids":"","trackcount":0,"is_public":1}],"user_token_status":"1","status":1}"""

    private fun song(id: Int, title: String, isrc: String = "INT00260%04d".format(id % 10000)) =
        """{"track_id":"$id","seokey":"song-$id","track_title":"$title","album_title":"Album $id","artist":[{"name":"Artist $id"},{"name":"Guest"}],
           "duration":"200","isrc":"$isrc","release_date":"2021-06-01","parental_warning":0}"""

    private fun detail(vararg songs: String, public: Int = 0) =
        """{"status":1,"error":"SUCCESS","count":"${songs.size}","user_token_status":"1","tracks":[${songs.joinToString(",")}],
           "playlist":{"playlist_id":"5550001","seokey":"road-trip-u1","title":"Road trip","trackids":"9000001,9000002","trackcount":2,"is_public":$public}}"""

    @Test fun playlistsCarryTheTokenAndTheSitesHeaders() {
        BridgeMock.answer(myPlaylists)
        val list = c.playlists()
        assertEquals(listOf("5550001", "5550002"), list.map { it.id })
        assertEquals(listOf("Road trip", "Empty"), list.map { it.name })
        assertEquals(listOf(3, 0), list.map { it.count })
        val r = call(0)
        assertEquals("GET", r.method)
        assertEquals("api.gaana.com", r.getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/user.php", r.requestUrl!!.encodedPath)
        assertEquals("myplaylists", r.q("type"))
        assertEquals("myplaylists", r.q("subtype"))
        assertEquals(token, r.q("token"))
        assertEquals("GaanaWapApp", r.getHeader("deviceType"))
        assertEquals("dev-123", r.getHeader("deviceId"))
        assertEquals("IN", r.getHeader("COUNTRY"))
        assertEquals("V6", r.getHeader("appVersion"))
        assertTrue(r.getHeader("gaanaAppVersion")!!.startsWith("gaanaAndroid-"))
        assertEquals("https://gaana.com", r.getHeader("Origin"))
    }

    @Test fun aPlaylistIsReadByItsIdAndGivesTheIsrc() {
        BridgeMock.answer(detail(song(9000001, "One"), song(9000002, "Two", isrc = ""), """{"track_title":"no id"}"""))
        val t = c.tracks("5550001")
        assertEquals(listOf("9000001", "9000002"), t.map { it.id })
        assertEquals("One", t[0].title)
        assertEquals(listOf("Artist 9000001", "Guest"), t[0].artists)
        assertEquals("Album 9000001", t[0].album)
        assertEquals(200_000L, t[0].durationMs)
        assertEquals("INT002600001", t[0].isrc)
        assertEquals(2021, t[0].year)
        assertNull(t[1].isrc)
        val r = call(0)
        assertEquals("/playlist/detail", r.requestUrl!!.encodedPath)
        assertEquals("5550001", r.q("playlist_id"))
        assertNull(r.q("seokey"))
        assertEquals("playlist", r.q("request_type"))
        assertEquals(token, r.q("token"))
    }

    @Test fun aPlaylistNamedByItsLinkIsReadBySeokey() {
        BridgeMock.answer(detail(song(9000001, "One")))
        c.tracks("road-trip-u1")
        assertEquals("road-trip-u1", call(0).q("seokey"))
        assertNull(call(0).q("playlist_id"))
    }

    @Test fun favouritesAreReadPageByPageUntilNothingNew() {
        fun entity(id: Int) = """{"entity_id":"$id","name":"Fav $id","seokey":"fav-$id","language":"Hindi",
            "entity_map":{"duration":"181","album":[{"name":"Album $id","album_seokey":"a-$id"}],"artist":[{"name":"Singer $id"}]}}"""
        BridgeMock.answer("""{"status":1,"entities":[${entity(1)},${entity(2)}]}""")
        BridgeMock.answer("""{"status":1,"entities":[${entity(3)}]}""")
        BridgeMock.answer("""{"status":1,"entities":[]}""")
        val t = c.liked()
        assertEquals(listOf("1", "2", "3"), t.map { it.id })
        assertEquals("Fav 1", t[0].title)
        assertEquals(listOf("Singer 1"), t[0].artists)
        assertEquals("Album 1", t[0].album)
        assertEquals(181_000L, t[0].durationMs)
        assertEquals("/mymusic/entities/TRACK", call(0).requestUrl!!.encodedPath)
        assertEquals(listOf("1", "2", "3"), (0..2).map { call(it).q("page") })
        assertEquals(token, call(0).q("token"))
    }

    @Test fun favouritesStopWhereTheAnswerRepeats() {
        val page = """{"status":1,"entities":[{"entity_id":"1","name":"Fav","entity_map":{}}]}"""
        BridgeMock.answer(page); BridgeMock.answer(page)
        assertEquals(listOf("1"), c.liked().map { it.id })
        assertEquals(2, BridgeMock.calls.size)
    }

    private val results = """{"topFacet":"Mix","gr":[
        {"ty":"Mix","gd":[
          {"iid":"100","id":100,"ti":"Tune","sti":"Singer A,Singer B","seo":"tune","ty":"Track","language":"Hindi","isrc":"INT001300031","pw":0},
          {"iid":"200","id":200,"ti":"Tune (the album)","sti":"Singer A","seo":"tune-album","ty":"Album"}]},
        {"ty":"Track","gd":[
          {"iid":"100","id":100,"ti":"Tune","sti":"Singer A,Singer B","seo":"tune","ty":"Track","isrc":"INT001300031"},
          {"iid":"101","id":101,"ti":"Tune (Live)","sti":"Singer A","seo":"tune-live","ty":"Track","isrc":"INT001900099","pw":1}]}]}"""

    @Test fun searchNeedsNoTokenAndKeepsOnlyTheSongs() {
        BridgeMock.answer(results)
        val t = c.search("tune singer a")
        assertEquals(listOf("100", "101"), t.map { it.id })
        assertEquals(listOf("Singer A", "Singer B"), t[0].artists)
        assertEquals("INT001300031", t[0].isrc)
        assertEquals("https://gaana.com/song/tune", t[0].uri)
        assertEquals(true, t[1].explicit)
        val r = call(0)
        assertEquals("gsearch.gaana.com", r.getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/vichitih/go/v2/", r.requestUrl!!.encodedPath)
        assertEquals("tune singer a", r.q("query"))
        assertEquals("2", r.q("content_filter"))
        assertEquals("web", r.q("rType"))
        assertEquals("Hindi,English", r.q("usrLang"))
        assertNull(r.q("token"))
        assertEquals("GaanaWapApp", r.getHeader("deviceType"))
    }

    @Test fun anIsrcPicksItsOwnResultAndOtherwiseKeepsThemAll() {
        BridgeMock.answer(results)
        assertEquals(listOf("101"), c.search("tune", isrc = "int001900099").map { it.id })
        BridgeMock.answer(results)
        assertEquals(listOf("100", "101"), c.search("tune", isrc = "USXXX0000001").map { it.id })
        // The code is never the text of the search.
        assertEquals("tune", call(1).q("query"))
    }

    @Test fun aSearchWorksWithoutASession() {
        GaanaBridge.session.clear(app)
        BridgeMock.answer(results)
        assertEquals(2, GaanaWebClient(app).search("tune").size)
        assertEquals("website", call(0).getHeader("deviceId"))
    }

    @Test fun createTakesTheNewIdFromTheAnswer() {
        BridgeMock.answer("""{"Status":1,"Success":5550009}""")
        val p = c.create("New one")
        assertEquals("5550009", p.id)
        assertEquals("New one", p.name)
        val r = call(0)
        assertEquals("GET", r.method)
        assertEquals("/user.php", r.requestUrl!!.encodedPath)
        assertEquals("createplaylist", r.q("type"))
        assertEquals("New one", r.q("title"))
        assertEquals("0", r.q("is_public"))
        assertEquals(token, r.q("token"))
    }

    @Test fun createLooksTheNewPlaylistUpWhenTheAnswerOnlySaysDone() {
        BridgeMock.answer("""{"Status":1,"Success":"Playlist created successfully"}""")
        BridgeMock.answer("""{"playlist":[{"playlist_id":"5550001","title":"Old"},{"playlist_id":"5550010","title":"New one"}]}""")
        assertEquals("5550010", c.create("New one").id)
    }

    @Test fun aRefusedCreateSaysWhy() {
        BridgeMock.answer("""{"Status":0,"Error":"Playlist title already exists"}""")
        try { c.create("Dup"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("Playlist title already exists")) }
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun addSendsTheIdsOnce() {
        BridgeMock.answer("""{"Status":1,"Success":"Added"}""")
        c.add("5550001", listOf("9000001", "9000002"))
        val r = call(0)
        assertEquals("GET", r.method)
        assertEquals("addtoplaylist", r.q("type"))
        assertEquals("5550001", r.q("id"))
        assertEquals("9000001,9000002", r.q("trackids"))
        assertEquals(token, r.q("token"))
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun removeEditsThePlaylistKeepingItsTitleAndVisibility() {
        BridgeMock.answer(detail(song(9000001, "One"), song(9000002, "Two"), public = 1))
        BridgeMock.answer("""{"Status":1,"Success":"Updated"}""")
        c.remove("5550001", listOf("9000002"))
        val r = call(1)
        assertEquals("POST", r.method)
        assertEquals("/user.php", r.requestUrl!!.encodedPath)
        assertEquals("addeditplaylist", r.q("type"))
        assertEquals(token, r.q("token"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            """{"playlists":[{"pid":"5550001","is_public":1,"playlist_title":"Road trip","del_items":[{"tid":"9000002","is_local":0}]}]}""",
            r.form()["playlist_json"],
        )
    }

    @Test fun renameAndDelete() {
        BridgeMock.answer(detail(song(9000001, "One")))
        BridgeMock.answer("""{"Status":1}""")
        c.rename("5550001", "Renamed")
        assertEquals("""{"playlists":[{"pid":"5550001","is_public":0,"playlist_title":"Renamed"}]}""", call(1).form()["playlist_json"])
        BridgeMock.answer("""{"Status":1}""")
        c.delete("5550001")
        val r = call(2)
        assertEquals("POST", r.method)
        assertEquals("myplaylists", r.q("type"))
        assertEquals("delete_playlist", r.q("subtype"))
        assertEquals("5550001", r.q("id"))
    }

    @Test fun likesGoAsEntities() {
        BridgeMock.answer("""{"status":1}"""); BridgeMock.answer("""{"status":1}""")
        c.like(listOf("9000001", "9000002"), on = true)
        c.like(listOf("9000001"), on = false)
        assertEquals("/mymusic/set-like-dislike-entities", call(0).requestUrl!!.encodedPath)
        assertEquals("POST", call(0).method)
        assertEquals(token, call(0).q("token"))
        assertEquals("""{"track":[{"entity_id":"9000001","entity_status":2},{"entity_id":"9000002","entity_status":2}]}""", call(0).form()["json_post"])
        assertEquals("""{"track":[{"entity_id":"9000001","entity_status":0}]}""", call(1).form()["json_post"])
    }

    @Test fun aSessionGaanaNoLongerKnowsAsksToSignInAgain() {
        BridgeMock.answer("""{"msg":"Login Needed!","user_token_status":0,"user-token-status":0}""")
        try { c.playlists(); fail() } catch (e: BridgeException) { assertEquals(GaanaWebClient.EXPIRED, e.message) }
        BridgeMock.answer("""{"status":0,"message":"Login needed","user_token_status":"0"}""")
        try { c.liked(); fail() } catch (e: BridgeException) { assertEquals(GaanaWebClient.EXPIRED, e.message) }
    }

    @Test fun withoutASessionNothingSignedIsSent() {
        GaanaBridge.session.clear(app)
        try { GaanaWebClient(app).playlists(); fail() } catch (e: BridgeException) { assertEquals("not connected", e.message) }
        assertTrue(BridgeMock.calls.isEmpty())
    }

    @Test fun theSessionIsFoundInWhatIsPastedAndInThePagesCalls() {
        val curl = "curl 'https://api.gaana.com/user.php?type=myplaylists&subtype=myplaylists&token=$token' -H 'deviceId: dev-9' -H 'COUNTRY: IN' -H 'deviceType: GaanaWapApp'"
        val s = GaanaWebClient.sessionIn(curl)!!
        assertEquals(token, s.token)
        assertEquals("dev-9", s.deviceId)
        assertEquals("IN", s.country)
        assertEquals(token, GaanaWebClient.sessionIn("  $token ")!!.token)
        assertEquals(token, GaanaWebClient.sessionIn("\"$token\"")!!.token)
        assertEquals(token, GaanaWebClient.sessionIn("uToken: \"$token\"")!!.token)
        assertNull(GaanaWebClient.sessionIn("https://gaana.com/playlist/gaana-dj-bollywood-top-50-1"))
        assertNull(GaanaWebClient.sessionIn("deviceId: dev-9"))
        assertNull(GaanaWebClient.sessionIn(""))

        val seen = GaanaWebClient.sessionOf("https://api.gaana.com/mymusic/entities/TRACK?order=reverse&token=$token", mapOf("deviceid" to "dev-7"))!!
        assertEquals(token, seen.token)
        assertEquals("dev-7", seen.deviceId)
        // Not Gaana's, not signed, or a page that has no token to give.
        assertNull(GaanaWebClient.sessionOf("https://example.com/x?token=$token", null))
        assertNull(GaanaWebClient.sessionOf("https://notgaana.com/x?token=$token", null))
        assertNull(GaanaWebClient.sessionOf("https://api.gaana.com/playlist/detail?seokey=x", null))
        assertNull(GaanaWebClient.sessionOf("https://api.gaana.com/user.php?type=profile&token=undefined", null))
        assertNull(GaanaWebClient.sessionOf("http://api.gaana.com/user.php?token=$token", null))
    }

    @Test fun theBridgeAnswersGaanasMethods() {
        fun bridge(method: String, arg: String? = null, extras: android.os.Bundle? = null) = BridgeCore.call(app, method, arg, extras)
        assertTrue(bridge("gaana.status").getBoolean("connected"))
        assertEquals("Gaana", bridge("gaana.status").getString("account"))
        BridgeMock.answer(myPlaylists)
        assertTrue(bridge("gaana.playlists").getString("json")!!.contains("\"5550001\""))
        BridgeMock.answer(results)
        val found = bridge("gaana.search", "tune", android.os.Bundle().apply { putString("isrc", "INT001900099") }).getString("json")!!
        assertTrue(found.contains("\"101\"") && !found.contains("\"100\""))
        BridgeMock.answer("""{"msg":"Login Needed!","user_token_status":0}""")
        assertEquals(GaanaWebClient.EXPIRED, bridge("gaana.playlists").getString("error"))
        bridge("gaana.disconnect")
        assertTrue(!bridge("gaana.status").getBoolean("connected"))
    }
}

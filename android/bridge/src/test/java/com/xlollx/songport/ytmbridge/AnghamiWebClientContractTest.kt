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
 * Anghami through play.anghami.com's gateway, against a local mock server. The requests and answers
 * follow a signed-in session of the web player recorded on 03/11/2026 (ids and names replaced).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AnghamiWebClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var c: AnghamiWebClient
    private val sid = "i7:" + "abcdefgh" + ":" + "Q".repeat(126)

    @Before fun setUp() {
        BridgeMock.start()
        app = RuntimeEnvironment.getApplication()
        assertNull(AnghamiBridge.keep(app, sid, "6efce375-48b6-412e-b449-6cdd93178190"))
        c = AnghamiWebClient(app)
    }

    @After fun tearDown() { BridgeMock.stop() }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.q(name: String) = requestUrl!!.queryParameter(name)
    private fun RecordedRequest.form(): Map<String, String> = body.clone().readUtf8().split('&').filter { it.isNotEmpty() }
        .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private val playlists = """{"angtime":1793719876,"status":"ok","version":"1.0.0","responsetype":"playlists","sections":[
        {"type":"playlist","data":[
          {"id":26,"name":"${'$'}1234567890LIKED#","count":2,"public":"1"},
          {"id":"298799886","name":"${'$'}1234567890DOWNLOADED#","public":"true","count":0},
          {"id":"298799901","name":"Road trip","public":true,"count":3}]},
        {"type":"playlist","data":[]}]}"""

    private val buffer = "EroDCgoxMjg4MTgzNzI4EqsDCgoxMjg4MTgzNzI4EiTZitiy2YYgLSDYp9mE2K3ZhNmC2Kkg2KfZhNir2KfZhtmK2KkaJdio2YjYr9mD2KfYs9iqINmF2YTYp9mF2K0g2YXZhiDYo9ir2LEiCjEwOTQ4MDI3MTcqKdmK2LLZhiDYr9iu2YrZhCDYp9mE2YTZhyDYp9mE2LLZh9ix2KfZhtmKMggyOTE1MDMzOTgCQgQyMDI2Ta5HFkRSCTI2Nzk5MjkyMGIHUG9kY2FzdGoJMjY3OTkyOTIwciTZitiy2YYgLSDYp9mE2K3ZhNmC2Kkg2KfZhNir2KfZhtmK2KlyKdmK2LLZhiDYr9iu2YrZhCDYp9mE2YTZhyDYp9mE2LLZh9ix2KfZhtmKciXYqNmI2K/Zg9in2LPYqiDZhdmE2KfZhditINmF2YYg2KPYq9ixeAGCAQI2NJgBAaoBByNiNGExODfyAgYzMjA2NzP6AgkyNjg2NTk5OTeCAxMyMDI2LTA3LTE1IDA1OjAwOjAw2AMB4AMB6gMJMjY3OTkyOTIwogQAqAQB0gQTMDAwMS0wMS0wMSAwMDowMDowMA=="

    @Test fun playlistsLeaveOutThePlayersOwnListsAndSignEveryCall() {
        BridgeMock.answer(playlists)
        val list = c.playlists()
        assertEquals(listOf("298799901"), list.map { it.id })
        assertEquals("Road trip", list[0].name)
        assertEquals(3, list[0].count)
        val r = call(0)
        assertEquals("GET", r.method)
        assertEquals("coussa.anghami.com", r.getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/gateway.php", r.requestUrl!!.encodedPath)
        assertEquals("GETplaylists", r.q("type"))
        assertEquals("GETplaylists", r.q("angh_type"))
        assertEquals(sid, r.q("sid"))
        assertEquals("6efce375-48b6-412e-b449-6cdd93178190", r.q("fingerprint"))
        assertEquals("jsonhp", r.q("output"))
        assertEquals("https://play.anghami.com", r.getHeader("Origin"))
    }

    @Test fun likedSongsAreTheLikedListsSongs() {
        BridgeMock.answer(playlists)
        BridgeMock.answer("""{"status":"ok","PlaylistCount":"2","songorder":"2,1","sections":[{"type":"song","data":[
            {"id":"1","title":"One","artist":"A","album":"X","albumID":"9","duration":"200.5","year":"2019"},
            {"id":"2","title":"Two","artist":"B","album":"Y","albumID":"8","duration":181}]}]}""")
        val t = c.liked()
        assertEquals(listOf("2", "1"), t.map { it.id })
        assertEquals(200_500L, t[1].durationMs)
        assertEquals(2019, t[1].year)
        assertEquals(listOf("A"), t[1].artists)
        assertEquals("26", call(1).q("playlistid"))
        assertEquals("GETplaylistdata", call(1).q("type"))
    }

    @Test fun songBuffersAreRead() {
        BridgeMock.answer("""{"status":"ok","PlaylistCount":1,"songorder":"1288183728","sections":[{"type":"song","data":[]}],"songbuffers":["$buffer"]}""")
        val t = c.tracks("298799901")
        assertEquals(1, t.size)
        assertEquals("1288183728", t[0].id)
        assertTrue(t[0].title.isNotBlank())
        assertTrue(t[0].durationMs > 0)
        assertEquals(2026, t[0].year)
    }

    @Test fun writesPostTheFormsThePlayerPosts() {
        BridgeMock.answer("""{"playlist":{"id":"298799950","name":"New","Public":false},"status":"ok"}""")
        assertEquals("298799950", c.create("New").id)
        BridgeMock.answer("""{"status":"ok"}"""); c.add("298799950", listOf("47087954"))
        BridgeMock.answer("""{"status":"ok"}"""); c.remove("298799950", listOf("47087954"))
        BridgeMock.answer("""{"status":"ok"}"""); c.like(listOf("60574693"), on = true)
        BridgeMock.answer("""{"status":"ok"}"""); c.like(listOf("60574693"), on = false)
        BridgeMock.answer("""{"status":"ok"}"""); c.rename("298799950", "Renamed")
        BridgeMock.answer("""{"status":"ok"}"""); c.delete("298799950")
        val forms = BridgeMock.calls.map { it.form() }
        BridgeMock.calls.forEach {
            assertEquals("POST", it.method)
            assertNull(it.q("type"))
            assertEquals(sid, it.q("sid"))
            assertEquals(it.form()["type"], it.q("angh_type"))
        }
        assertEquals(mapOf("type" to "PUTplaylist", "name" to "New", "public" to "false"), forms[0])
        assertEquals(mapOf("type" to "PUTplaylist", "playlistid" to "298799950", "action" to "append", "songID" to "47087954"), forms[1])
        assertEquals(mapOf("type" to "PUTplaylist", "playlistid" to "298799950", "action" to "delete", "songid" to "47087954"), forms[2])
        assertEquals(mapOf("type" to "PUTplaylist", "name" to "\$1234567890LIKED#", "action" to "append", "songid" to "60574693"), forms[3])
        assertEquals("delete", forms[4]["action"])
        assertEquals(mapOf("type" to "UPDATEplaylist", "playlistid" to "298799950", "action" to "update", "newname" to "Renamed"), forms[5])
        assertEquals(mapOf("type" to "UPDATEplaylist", "playlistid" to "298799950", "action" to "delete"), forms[6])
    }

    @Test fun searchKeepsSongsOnly() {
        BridgeMock.answer("""{"status":"ok","sections":[{"type":"genericitem","group":"search_top","data":[
            {"id":"1748632"},
            {"id":"47087954","title":"Play","artist":"Jax Jones","album":"Palo Santo","albumID":"6200514","duration":186.46,"year":2018},
            {"id":"1018634044","title":"Plan B","artist":"Someone","year":"2021"}]}]}""")
        val r = c.search("play")
        assertEquals(listOf("47087954"), r.map { it.id })
        assertEquals(186_460L, r[0].durationMs)
        assertEquals("/rest/v2/GETSearchResults.view", call(0).requestUrl!!.encodedPath)
        assertEquals("play", call(0).q("query"))
    }

    @Test fun anEndedSessionSaysSo() {
        BridgeMock.answer("""{"_attributes":{"status":"failed"},"error":{"_attributes":{"code":"111","message":"Please login again to continue using the app.","logoff":"true"}}}""")
        try { c.playlists(); fail() } catch (e: BridgeException) { assertEquals(AnghamiWebClient.EXPIRED, e.message) }
    }

    @Test fun theSessionIsFoundInWhatIsPasted() {
        val url = "https://coussa.anghami.com/gateway.php?type=GETplaylists&lang=en&fingerprint=fp-1&web2=true&sid=" + java.net.URLEncoder.encode(sid, "UTF-8") + "&angh_type=GETplaylists"
        assertEquals(sid to "fp-1", AnghamiWebClient.sessionIn(url))
        assertEquals(sid to null, AnghamiWebClient.sessionIn("  $sid "))
        assertNull(AnghamiWebClient.sessionIn("emptysid-1793719859-oyRcdrr7"))
        assertEquals(sid to "fp-1", AnghamiBridge.fromRequest(url))
        assertNull(AnghamiBridge.fromRequest("https://example.com/x?sid=" + sid))
    }

    // #53: signed in with Google, the player's sid had another shape: a long value in the request's
    // sid is taken (and tried), the anonymous one never.
    @Test fun aSidOfAnotherShapeInTheRequestIsTaken() {
        val other = "Zq81kLmN0pQrStUvWxYz.0123456789abcdefABCDEF"
        val url = "https://coussa.anghami.com/gateway.php?type=GETplaylists&fingerprint=fp-2&sid=" + java.net.URLEncoder.encode(other, "UTF-8")
        assertEquals(other to "fp-2", AnghamiWebClient.sessionIn(url))
        assertEquals(other to "fp-2", AnghamiBridge.fromRequest(url))
        assertNull(AnghamiBridge.fromRequest("https://coussa.anghami.com/gateway.php?type=GETtelcos&sid=emptysid-1793719859-oyRcdrr7"))
        assertNull(AnghamiWebClient.sidValue("short"))
        assertEquals("anonymous", AnghamiWebClient.sidShape("emptysid-1-x"))
        assertEquals("2:6:40 chars", AnghamiWebClient.sidShape("ab:cdefgh:" + "x".repeat(40)))
    }
}

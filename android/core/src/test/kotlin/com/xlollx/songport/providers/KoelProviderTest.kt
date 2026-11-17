package com.xlollx.songport.providers

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.it.TestEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.Wanted
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Koel on the Subsonic connector, against a stand-in server that answers as Koel's source does
 * (routes/subsonic.php, SubsonicResponse, PlaylistResource, SongResource): the envelope names
 * `type: koel`, a playlist's owner is the account's email, `public` is always false.
 */
class KoelProviderTest {
    private class Req(val path: String, val query: Map<String, List<String>>)

    private val requests = mutableListOf<Req>()
    private val email = "ada@example.com"
    private val key = "9b2f6c1e-4d7a-4e0b-9c3d-5a1f7e2b8c44"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map { kv ->
                URLDecoder.decode(kv.substringBefore('='), "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            }.groupBy({ it.first }, { it.second })
            val req = Req(ex.requestURI.path, q)
            val body = synchronized(requests) { requests += req; answer(req) }.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val ctx = TestEnv()

    @After fun stop() = server.stop(0)

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ok(extra: String = "") = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"koel","serverVersion":"v8.2.0","openSubsonic":true$extra}}"""
    private fun failed(code: Int, message: String) = """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"koel","serverVersion":"v8.2.0","openSubsonic":true,"error":{"code":$code,"message":"$message"}}}"""

    private val song1 = """{"id":"0195c0de-7a11-7c3e-9b1a-2f4d6e8a0c11","parent":"0195c0de-6f00-7a2b-8c4d-1e3f5a7b9d20","isDir":false,"title":"Paranoid Android","album":"OK Computer","artist":"Radiohead","track":2,"year":1997,"genre":"Alternative","size":15483921,"contentType":"audio/flac","suffix":"flac","duration":387,"created":"2026-03-02T18:21:44+00:00","albumId":"0195c0de-6f00-7a2b-8c4d-1e3f5a7b9d20","artistId":"0195c0de-6e10-7d5f-a1b2-3c4d5e6f7a80","type":"music","discNumber":1,"isVideo":false,"musicBrainzId":"8a2c5b5e-4f8d-4a7e-9d3b-6b2f0f8e1c55"}"""
    private val song2 = """{"id":"0195c0de-7a12-7e90-8d2c-4b6a8c0e2f33","parent":"0195c0de-6f01-7b3c-9d5e-2f4a6b8c0e31","isDir":false,"title":"Teardrop","album":"Mezzanine","artist":"Massive Attack","track":3,"year":1998,"duration":330,"created":"2026-03-02T18:25:09+00:00","albumId":"0195c0de-6f01-7b3c-9d5e-2f4a6b8c0e31","artistId":"0195c0de-6e11-7e60-b2c3-4d5e6f7a8b91","type":"music","isVideo":false}"""
    private val playlists = ""","playlists":{"playlist":[
        {"id":"0195c0e0-11aa-7f01-8e2d-3a5c7e9b1d42","name":"Late night","comment":"","owner":"$email","public":false,"songCount":2,"duration":717,"created":"2026-04-11T21:03:10+00:00","changed":"2026-10-30T09:12:00+00:00"},
        {"id":"0195c0e0-11ab-7a12-9f3e-4b6d8f0a2c53","name":"Shared by Grace","comment":"road trip","owner":"grace@example.com","public":false,"songCount":41,"duration":9870,"created":"2026-05-01T08:00:00+00:00","changed":"2026-05-02T08:00:00+00:00"}]}"""

    /** Koel's token sign-in: the email, and md5(Subsonic API key + salt). Anything else is error 40. */
    private fun signedIn(r: Req): Boolean {
        val salt = r.query["s"]?.single() ?: return false
        return r.query["u"]?.single() == email && r.query["t"]?.single() == md5(key + salt)
    }

    private var answer: (Req) -> String = { r ->
        when {
            !signedIn(r) -> failed(40, "Wrong username or password.")
            r.path.endsWith("/getPlaylists") -> ok(playlists)
            r.path.endsWith("/getPlaylist") -> ok(""","playlist":{"id":"${r.query["id"]?.single()}","name":"Late night","comment":"","owner":"$email","public":false,"songCount":2,"duration":717,"created":"2026-04-11T21:03:10+00:00","changed":"2026-10-30T09:12:00+00:00","entry":[$song1,$song2]}""")
            r.path.endsWith("/createPlaylist") -> ok(""","playlist":{"id":"0195c0e0-11ac-7b23-a04f-5c7e9a1b3d64","name":"${r.query["name"]?.single()}","comment":"","owner":"$email","public":false,"songCount":0,"duration":0,"created":"2026-11-17T10:00:00+00:00","changed":"2026-11-17T10:00:00+00:00","entry":[]}""")
            else -> ok()
        }
    }

    @Test fun signsInWithTheEmailAndTheSubsonicKeyAsASaltedToken() = runBlocking {
        val p = KoelProvider()
        p.login(ctx, url, " $email ", " $key\n")
        val t = ctx.tokens.get(p.id)!!
        assertEquals(email, t.userName)
        assertEquals("koel", t.extra[SubsonicProvider.TYPE])
        val ping = requests.single()
        assertTrue(ping.path.endsWith("/rest/ping"))
        // The key itself never travels: no apiKey, no password parameter.
        assertNull(ping.query["apiKey"]); assertNull(ping.query["p"])
        assertFalse(ping.query.values.flatten().any { it == key })
    }

    @Test fun theAccountPasswordInPlaceOfTheKeyIsExplained() = runBlocking {
        val e = runCatching { KoelProvider().login(ctx, url, email, "my-koel-password") }.exceptionOrNull()
        assertEquals("koel_signin_refused", e?.message)
        assertNull(ctx.tokens.get(KoelProvider().id))
        assertEquals(1, requests.size)
    }

    @Test fun readsPlaylistsAndTheirSongs() = runBlocking {
        val p = KoelProvider()
        p.login(ctx, url, email, key)
        val lists = p.playlists(ctx)
        assertEquals(listOf("Late night", "Shared by Grace"), lists.map { it.name })
        // The owner is an email, as the account's user is: another user's shared playlist is not mine.
        assertEquals(listOf(true, false), lists.map { it.ownedByMe })
        assertEquals(listOf(2, 41), lists.map { it.trackCount })
        val tracks = p.tracks(ctx, lists[0].id)
        assertEquals(listOf("Paranoid Android", "Teardrop"), tracks.map { it.title })
        assertEquals(listOf("Radiohead"), tracks[0].artists)
        assertEquals("OK Computer", tracks[0].album)
        assertEquals(387_000L, tracks[0].durationMs)
        assertEquals(listOf("8a2c5b5e-4f8d-4a7e-9d3b-6b2f0f8e1c55"), tracks[0].mbids)
        assertEquals(listOf("0", "1"), tracks.map { it.itemId })
    }

    @Test fun writesPlaylists() = runBlocking {
        val p = KoelProvider()
        p.login(ctx, url, email, key)
        val made = p.createPlaylist(ctx, "From Spotify", "")
        assertEquals("0195c0e0-11ac-7b23-a04f-5c7e9a1b3d64", made.id)
        p.addTracks(ctx, made.id, listOf(Track(id = "a1", title = "A"), Track(id = "b2", title = "B")))
        p.removeTracks(ctx, made.id, listOf(Track(id = "a1", title = "A", itemId = "0")))
        p.renamePlaylist(ctx, made.id, "From Spotify 2")
        p.deletePlaylist(ctx, made.id)
        val updates = requests.filter { it.path.endsWith("/updatePlaylist") }
        assertEquals(listOf("a1", "b2"), updates[0].query["songIdToAdd"])
        assertEquals(listOf("00"), updates[1].query["songIndexToRemove"])
        assertEquals(listOf("From Spotify 2"), updates[2].query["name"])
        assertEquals(listOf(made.id), requests.single { it.path.endsWith("/deletePlaylist") }.query["id"])
    }

    @Test fun whatKoelDoesNotOffer() = runBlocking {
        val p = KoelProvider()
        // Koel keeps `public` false whatever a client sends: the switch is not shown.
        assertFalse(p.canSetVisibility)
        assertTrue(runCatching { p.setPlaylistVisibility(ctx, "x", true) }.isFailure)
        assertEquals(LyricsSupport.REPORT_ONLY, p.lyricsSupport)
        assertFalse(p.loginForm.offersApiKey)
        assertTrue(p.beta)
    }

    @Test fun isOneOfTheUsersOwnServers() {
        assertEquals("koel", KoelProvider().serviceId)
        assertEquals("koel@2", KoelProvider("2").id)
        assertTrue(Wanted.isServer("koel"))
        assertEquals("Koel", ServiceBrand.of("koel", KoelProvider().familyName))
        assertEquals("https://koel.example.com", PlaylistLinks.webUrl("koel", "0195c0e0", base = "https://koel.example.com/"))
        assertTrue(Providers.engineFactories().any { it.service == "koel" })
    }
}

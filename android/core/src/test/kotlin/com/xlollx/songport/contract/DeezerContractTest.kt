package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Deezer API (developers.deezer.com/api): the token goes as a query parameter, and errors come as
 * an `error` object with HTTP 200. Shapes as documented; Deezer's own app registration is closed,
 * so none of this could be checked against the live service.
 */
class DeezerContractTest : ContractTest() {
    private val dz = DeezerProvider()
    private val api = "api.deezer.com"

    @Before fun signIn() {
        env.tokens.set("deezer", Tokens(accessToken = "DZTOKEN", userId = "42", userName = "Me"))
    }

    private fun track(i: Int, readable: Boolean = true, title: String = "Song $i", album: String = "{\"id\":$i,\"title\":\"Album $i\"}") =
        """{"id":${1000 + i},"readable":$readable,"title":${q(title)},"title_short":${q(title)},"isrc":"FRZ0${i.toString().padStart(8, '0')}",
           "link":"https://www.deezer.com/track/${1000 + i}","duration":${180 + i % 60},"rank":500000,"explicit_lyrics":${i % 2 == 0},
           "time_add":${1_700_000_000 + i},"artist":{"id":27,"name":"Artist $i","type":"artist"},"album":$album,"type":"track"}"""

    private fun page(items: List<String>, total: Int, next: String?) =
        """{"data":[${items.joinToString(",")}],"checksum":"c$total","total":$total${next?.let { ",\"next\":${q(it)}" } ?: ""}}"""

    @Test fun playlistTracksPageByIndexAndStopAtAFullLastPage() = runBlocking {
        json(page((0 until 100).map { track(it) }, 200, "https://api.deezer.com/playlist/9/tracks?limit=100&index=100"))
        json(page((100 until 200).map { track(it) }, 200, null))
        val ts = dz.tracks(env, "9")
        assertEquals(200, ts.size)
        assertEquals((0 until 200).map { (1000 + it).toString() }, ts.map { it.id })
        assertEquals(1_700_000_000_000L, ts[0].addedAt)
        assertEquals(180_000L, ts[0].durationMs)
        val r1 = next()
        assertCall(r1, "GET", api, "/playlist/9/tracks")
        assertEquals("100", r1.query("limit"))
        assertEquals(listOf("DZTOKEN"), r1.queryAll("access_token"))
        val r2 = next()
        assertEquals("100", r2.query("index"))
        assertEquals("DZTOKEN", r2.query("access_token"))
        noMoreRequests()
    }

    @Test fun unreadableNullAlbumUnicodeAndDuplicates() = runBlocking {
        json(page(listOf(track(1), track(2, readable = false), track(3, album = "null", title = UNICODE), track(1), """{"id":null,"title":"x"}"""), 5, null))
        val ts = dz.tracks(env, "9")
        assertEquals(listOf("1001", "1002", "1003", "1001"), ts.map { it.id })
        assertEquals(false, ts[1].available)
        assertEquals("", ts[2].album)
        assertEquals(UNICODE, ts[2].title)
    }

    @Test fun publicPlaylistIsReadWithoutAToken() = runBlocking {
        env.tokens.clear("deezer")
        json(page(listOf(track(1)), 1, null))
        assertEquals(1, dz.tracks(env, "9").size)
        assertNull(next().query("access_token"))
    }

    @Test fun playlistsSkipLovedTracksAndReadOwnership() = runBlocking {
        json("""{"data":[{"id":1,"title":"Loved Tracks","is_loved_track":true,"nb_tracks":10,"creator":{"id":42}},
            {"id":2,"title":${q(UNICODE)},"nb_tracks":3,"public":true,"picture_xl":"https://e-cdns-images.dzcdn.net/x.jpg","creator":{"id":42}},
            {"id":3,"title":"Theirs","nb_tracks":5,"public":false,"creator":{"id":7}}],"total":3}""")
        val ls = dz.playlists(env)
        assertEquals(listOf("2", "3"), ls.map { it.id })
        assertEquals(listOf(true, false), ls.map { it.ownedByMe })
        assertEquals(UNICODE, ls[0].name)
        assertCall(next(), "GET", api, "/user/me/playlists")
    }

    @Test fun errorWithStatus200IsAnError() = runBlocking {
        json("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        try { dz.tracks(env, "9"); fail() } catch (e: ProviderException) { assertEquals("Deezer: no data", e.message) }
        assertTrue(env.tokens.get("deezer") != null)
    }

    @Test fun invalidTokenClearsTheSession() = runBlocking {
        json("""{"error":{"type":"OAuthException","message":"Invalid OAuth access token.","code":300}}""")
        try { dz.playlists(env); fail() } catch (e: ProviderException) { assertEquals("Deezer: Invalid OAuth access token.", e.message) }
        assertNull(env.tokens.get("deezer"))
    }

    /**
     * Regression: Deezer allows 50 requests per 5 seconds and says so with error 4 "Quota limit
     * exceeded" (HTTP 200). Liked songs go one request each, so a few hundred of them hit it: the
     * call now waits out the window and goes on instead of failing the sync halfway.
     */
    @Test fun quotaErrorWaitsTheWindowAndRetries() = runBlocking {
        json("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")
        json("true")
        dz.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("1001", "a")))
        assertEquals(listOf(5000L), pauses)
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("1001", next().query("track_id")) }
    }

    @Test fun quotaErrorGivesUpAfterAFewWindows() = runBlocking {
        repeat(4) { json("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""") }
        try { dz.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("1001", "a"))); fail() } catch (e: ProviderException) {
            assertEquals("Deezer: Quota limit exceeded", e.message)
        }
        assertEquals(listOf(5000L, 5000L, 5000L), pauses)
        assertEquals(4, server.requestCount)
    }

    @Test fun addInBatchesOfFiftyInOrder() = runBlocking {
        val tracks = (0 until 1000).map { Track((5000 + it).toString(), "S") }
        repeat(20) { json("true") }
        dz.addTracks(env, "9", tracks)
        val sent = (0 until 20).flatMap {
            val r = next()
            assertCall(r, "POST", api, "/playlist/9/tracks")
            assertEquals("DZTOKEN", r.query("access_token"))
            r.query("songs")!!.split(',')
        }
        assertEquals(tracks.map { it.id }, sent)
        noMoreRequests()
    }

    @Test fun removeInBatchesOfFifty() = runBlocking {
        repeat(2) { json("true") }
        dz.removeTracks(env, "9", (0 until 60).map { Track("$it", "S") })
        assertEquals(50, next().also { assertEquals("DELETE", it.method) }.query("songs")!!.split(',').size)
        assertEquals(10, next().query("songs")!!.split(',').size)
    }

    @Test fun likedOneRequestPerTrack() = runBlocking {
        repeat(3) { json("true") }
        dz.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("1", "a"), Track("2", "b")))
        dz.removeTracks(env, MusicProvider.LIKED_ID, listOf(Track("1", "a")))
        listOf("POST" to "1", "POST" to "2", "DELETE" to "1").forEach { (m, id) ->
            val r = next()
            assertCall(r, m, api, "/user/me/tracks")
            assertEquals(id, r.query("track_id"))
        }
    }

    @Test fun createRenameVisibilityReorderDelete() = runBlocking {
        json("""{"id":908622995}""")
        repeat(4) { json("true") }
        assertEquals("908622995", dz.createPlaylist(env, UNICODE, "d").id)
        dz.renamePlaylist(env, "908622995", LONG)
        dz.setPlaylistVisibility(env, "908622995", true)
        dz.reorderTracks(env, "908622995", emptyList(), listOf(Track("3", "c"), Track("1", "a"), Track("2", "b")))
        dz.deletePlaylist(env, "908622995")
        val c = next()
        assertCall(c, "POST", api, "/user/me/playlists")
        assertEquals(UNICODE, c.query("title"))
        assertEquals(LONG, next().query("title"))
        assertEquals("true", next().query("public"))
        val o = next()
        assertCall(o, "POST", api, "/playlist/908622995/tracks")
        assertEquals("3,1,2", o.query("order"))
        assertCall(next(), "DELETE", api, "/playlist/908622995")
    }

    @Test fun searchByIsrcThenFieldedQuery() = runBlocking {
        json("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        json("""{"data":[${track(7, title = UNICODE)}],"total":1}""")
        val r = dz.search(env, Track("x", UNICODE, listOf("Ñandú"), isrc = "frz000000007"))
        assertEquals(listOf("1007"), r.map { it.id })
        assertEquals("/track/isrc:FRZ000000007", next().pathOnly())
        val s = next()
        assertCall(s, "GET", api, "/search/track")
        val q = s.query("q")!!
        assertTrue(q.startsWith("track:\""))
        // Deezer's artist:"…" filter answers nothing (#46): the artist goes as plain words.
        assertTrue(q, !q.contains("artist:") && q.endsWith("\" nandu"))
    }

    @Test fun httpErrorIsAClearError() = runBlocking {
        text("<html>Bad gateway</html>", 502); text("<html>Bad gateway</html>", 502)
        try { dz.tracks(env, "9"); fail() } catch (e: ProviderException) { assertTrue(e.message!!.startsWith("Deezer API 502")) }
        assertEquals(listOf(1500L), pauses)
    }

    @Test fun likedVersionAndChecksum() = runBlocking {
        json("""{"data":[${track(1)}],"total":77}""")
        assertEquals("77|1001", dz.playlistVersion(env, MusicProvider.LIKED_ID))
        json("""{"id":9,"title":"T","checksum":"3f1c","nb_tracks":2}""")
        assertEquals("3f1c", dz.playlistVersion(env, "9"))
    }
}

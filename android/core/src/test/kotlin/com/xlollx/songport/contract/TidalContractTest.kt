package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.TidalProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TIDAL Open API v2 (developer.tidal.com, JSON:API). Shapes per the reference; the API is young and
 * changes, and none of it could be checked against the live service here.
 */
class TidalContractTest : ContractTest() {
    private val td = TidalProvider()
    private val api = "openapi.tidal.com"
    private val vnd = "application/vnd.api+json"

    @Before fun signIn() {
        BuildDefaults.values = mapOf("TIDAL_CLIENT_ID" to "tcid")
        env.tokens.set("tidal", Tokens("TT1", "TR1", userId = "u1", userName = "me", extra = mapOf("country" to "IT")))
    }

    private fun ref(i: Int, item: String = "it-$i") = """{"id":"${100 + i}","type":"tracks","meta":{"itemId":"$item","addedAt":"2024-01-01T00:00:00Z"}}"""
    private fun inc(i: Int, title: String = "Song $i", availability: String = "[\"STREAM\",\"DJ\"]", artists: String = "[{\"id\":\"${7 + i % 3}\",\"type\":\"artists\"}]") =
        """{"id":"${100 + i}","type":"tracks","attributes":{"title":${q(title)},"isrc":"GBAAA${i.toString().padStart(7, '0')}","duration":"PT3M${i % 60}S",
           "explicit":false,"availability":$availability,"popularity":0.5},"relationships":{"artists":{"data":$artists,"links":{"self":"/tracks/${100 + i}/relationships/artists"}}}}"""
    private val artists = """{"data":[{"id":"7","type":"artists","attributes":{"name":"Måneskin"}},{"id":"8","type":"artists","attributes":{"name":"Beyoncé"}},{"id":"9","type":"artists","attributes":{"name":"Ñ"}}]}"""

    @Test fun includedTracksWithoutArtistIdsAreAskedForAgain() = runBlocking {
        // TIDAL's included tracks may carry only a link to their artists: they are fetched again with them.
        val bare = """{"id":"100","type":"tracks","attributes":{"title":"Song 0","isrc":"GBAAA0000000","duration":"PT3M0S"},"relationships":{"artists":{"links":{"self":"/tracks/100/relationships/artists"}}}}"""
        json("""{"data":[${ref(0)}],"included":[$bare],"links":{"self":"x"}}""")
        json("""{"data":[${inc(0)}],"included":[{"id":"7","type":"artists","attributes":{"name":"Måneskin"}}]}""")
        json(artists)
        val ts = td.tracks(env, "P")
        assertEquals(listOf("Måneskin"), ts.single().artists)
        next()
        val again = next()
        assertCall(again, "GET", api, "/v2/tracks")
        assertEquals("100", again.query("filter[id]"))
        assertEquals("artists", again.query("include"))
        next()
        noMoreRequests()
    }

    @Test fun itemsFollowRelativeNextAndKeepItemIds() = runBlocking {
        json("""{"data":[${(0 until 20).joinToString(",") { ref(it) }}],"included":[${(0 until 20).joinToString(",") { inc(it) }}],
            "links":{"self":"/playlists/P/relationships/items?countryCode=IT&include=items","next":"/playlists/P/relationships/items?countryCode=IT&include=items&page%5Bcursor%5D=c2"}}""")
        // Last page: a video (skipped), a duplicate of track 0 with its own item id, a track that is not streamable.
        json("""{"data":[{"id":"v1","type":"videos","meta":{"itemId":"iv"}},${ref(0, "it-dup")},${ref(20)}],
            "included":[{"id":"v1","type":"videos","attributes":{"title":"Clip"}},${inc(0)},${inc(20, title = UNICODE, availability = "[\"DJ\"]")}],"links":{"self":"x"}}""")
        json(artists)
        val ts = td.tracks(env, "P")
        assertEquals(22, ts.size)
        assertEquals("it-dup", ts[20].itemId)
        assertEquals(ts[0].id, ts[20].id)
        assertEquals(UNICODE, ts[21].title)
        assertEquals(false, ts[21].available)
        assertEquals(true, ts[0].available)
        assertEquals(listOf("Måneskin"), ts[0].artists)
        assertEquals(183_000L, ts[3].durationMs)
        val r1 = next()
        assertCall(r1, "GET", api, "/v2/playlists/P/relationships/items")
        assertEquals("IT", r1.query("countryCode"))
        assertEquals("items", r1.query("include"))
        assertEquals("Bearer TT1", r1.getHeader("Authorization"))
        assertEquals(vnd, r1.getHeader("Accept"))
        assertEquals("c2", next().query("page[cursor]"))
        val a = next()
        assertCall(a, "GET", api, "/v2/artists")
        assertEquals("7,8,9", a.query("filter[id]"))
        noMoreRequests()
    }

    @Test fun artistLookupsInTwenties() = runBlocking {
        val many = (0 until 45).joinToString(",") { inc(it, artists = "[{\"id\":\"a$it\",\"type\":\"artists\"}]") }
        json("""{"data":[${(0 until 45).joinToString(",") { ref(it) }}],"included":[$many],"links":{}}""")
        repeat(3) { json("""{"data":[]}""") }
        assertEquals(45, td.tracks(env, "P").size)
        next()
        assertEquals(listOf(20, 20, 5), (0 until 3).map { next().query("filter[id]")!!.split(',').size })
    }

    @Test fun emptyPlaylist() = runBlocking {
        json("""{"data":[],"links":{"self":"/playlists/P/relationships/items"}}""")
        assertEquals(emptyList<Track>(), td.tracks(env, "P"))
        next(); noMoreRequests()
    }

    @Test fun playlistsOfTheOwner() = runBlocking {
        json("""{"data":[{"id":"p1","type":"playlists","attributes":{"name":${q(UNICODE)},"numberOfItems":3,"accessType":"PUBLIC","lastModifiedAt":"2024-01-01T00:00:00Z"}},
            {"id":"p2","type":"playlists","attributes":{"name":"B","numberOfItems":0,"accessType":"UNLISTED"}}],"links":{"next":"/playlists?countryCode=IT&page%5Bcursor%5D=n"}}""")
        json("""{"data":[],"links":{}}""")
        val ls = td.playlists(env)
        assertEquals(listOf(true, false), ls.map { it.isPublic })
        assertEquals(UNICODE, ls[0].name)
        val r = next()
        assertCall(r, "GET", api, "/v2/playlists")
        assertEquals("u1", r.query("filter[r.owners.id]"))
        assertEquals("n", next().query("page[cursor]"))
    }

    @Test fun addInTwentiesAsJsonApi() = runBlocking {
        repeat(3) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(201)) }
        val tracks = (0 until 45).map { Track("${100 + it}", "S") }
        td.addTracks(env, "P", tracks)
        val sent = (0 until 3).flatMap {
            val r = next()
            assertCall(r, "POST", api, "/v2/playlists/P/relationships/items")
            assertEquals(vnd, r.getHeader("Content-Type")!!.substringBefore(';'))
            r.jsonBody()["data"].arr.map { d -> assertEquals("tracks", d["type"].str); d["id"].str!! }
        }
        assertEquals(tracks.map { it.id }, sent)
    }

    @Test fun removeByItemIdSkipsUnknownEntries() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        td.removeTracks(env, "P", listOf(Track("100", "a", itemId = "it-0"), Track("101", "b"), Track("100", "a", itemId = "it-dup")))
        val r = next()
        assertCall(r, "DELETE", api, "/v2/playlists/P/relationships/items")
        assertEquals(listOf("it-0", "it-dup"), r.jsonBody()["data"].arr.map { it["meta"]["itemId"].str })
        noMoreRequests()
    }

    @Test fun createRenameVisibilityDelete() = runBlocking {
        json("""{"data":{"id":"new-uuid","type":"playlists","attributes":{"name":${q(UNICODE)}}}}""", 201)
        repeat(3) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204)) }
        assertEquals("new-uuid", td.createPlaylist(env, UNICODE, LONG).id)
        td.renamePlaylist(env, "new-uuid", LONG)
        td.setPlaylistVisibility(env, "new-uuid", true)
        td.deletePlaylist(env, "new-uuid")
        val c = next()
        assertCall(c, "POST", api, "/v2/playlists")
        val a = c.jsonBody()["data"]["attributes"]
        assertEquals(UNICODE, a["name"].str); assertEquals(LONG, a["description"].str); assertEquals("UNLISTED", a["accessType"].str)
        val rn = next()
        assertCall(rn, "PATCH", api, "/v2/playlists/new-uuid")
        assertEquals(LONG, rn.jsonBody()["data"]["attributes"]["name"].str)
        assertEquals("new-uuid", rn.jsonBody()["data"]["id"].str)
        assertEquals("PUBLIC", next().jsonBody()["data"]["attributes"]["accessType"].str)
        assertCall(next(), "DELETE", api, "/v2/playlists/new-uuid")
    }

    @Test fun albumsCollectionEditsInTwenties() = runBlocking {
        repeat(2) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204)) }
        td.addTracks(env, MusicProvider.ALBUMS_ID, (0 until 21).map { Track("al$it", "A", kind = Track.KIND_ALBUM) })
        val r = next()
        assertCall(r, "POST", api, "/v2/userCollections/u1/relationships/albums")
        assertEquals(20, r.jsonBody()["data"].arr.size)
        assertEquals(1, next().jsonBody()["data"].arr.size)
    }

    @Test fun refreshOn401() = runBlocking {
        json("""{"errors":[{"status":"401","code":"UNAUTHORIZED","detail":"Token expired"}]}""", 401)
        json("""{"access_token":"TT2","token_type":"Bearer","expires_in":86400,"user_id":1}""")
        json("""{"data":[],"links":{}}""")
        td.tracks(env, "P")
        next()
        val t = next()
        assertCall(t, "POST", "auth.tidal.com", "/v1/oauth2/token")
        assertEquals("refresh_token", t.form()["grant_type"])
        assertEquals("tcid", t.form()["client_id"])
        assertEquals("Bearer TT2", next().getHeader("Authorization"))
    }

    @Test fun jsonApiErrorDetailIsShown() = runBlocking {
        json("""{"errors":[{"id":"x","status":"404","code":"NOT_FOUND","detail":"Playlist not found"}]}""", 404)
        try { td.tracks(env, "P"); fail() } catch (e: ProviderException) { assertEquals("TIDAL API 404: Playlist not found", e.message); assertEquals(404, e.code) }
    }

    /** Regression: an error page that is not JSON (a proxy's 502, a plain-text 429) ends as a clear TIDAL error, not a JSON parser crash. */
    @Test fun nonJsonErrorBodyIsAClearError() = runBlocking {
        text("<html><body>502 Bad Gateway</body></html>", 502); text("<html><body>502 Bad Gateway</body></html>", 502)
        try { td.tracks(env, "P"); fail() } catch (e: ProviderException) {
            assertEquals(502, e.code)
            assertTrue(e.message, e.message!!.startsWith("TIDAL API 502: <html>"))
        }
    }

    @Test fun rateLimitWaitsRetryAfter() = runBlocking {
        json("""{"errors":[{"status":"429","detail":"Too many requests"}]}""", 429, "Retry-After" to "2")
        json("""{"data":[],"links":{}}""")
        td.tracks(env, "P")
        assertEquals(listOf(2000L), pauses)
    }

    @Test fun searchByIsrcFilter() = runBlocking {
        json("""{"data":[${inc(1)}]}""")
        json(artists)
        val r = td.search(env, Track("x", "Song 1", listOf("Beyoncé"), isrc = "gbaaa0000001"))
        assertEquals(listOf("101"), r.map { it.id })
        val s = next()
        assertCall(s, "GET", api, "/v2/tracks")
        assertEquals("GBAAA0000001", s.query("filter[isrc]"))
    }

    /** Regression (01/11/2026): the text goes in filter[query]; a query in the path is "Invalid resource ID". */
    @Test fun searchByQueryFilterWithTheTracksIncluded() = runBlocking {
        json("""{"data":[{"id":"sr-1","type":"searchResults","relationships":{"tracks":{"data":[{"id":"101","type":"tracks"}]}}}],"included":[${inc(1)}]}""")
        json(artists)
        val r = td.search(env, Track("x", "Song 1", listOf("Beyoncé")))
        assertEquals(listOf("101"), r.map { it.id })
        val s = next()
        assertCall(s, "GET", api, "/v2/searchResults")
        assertEquals("beyonce song 1", s.query("filter[query]"))
        assertEquals("tracks", s.query("include"))
    }

    @Test fun searchReadsTheRelationshipWhenTheTracksAreNotIncluded() = runBlocking {
        json("""{"data":{"id":"sr-1","type":"searchResults"}}""")
        json("""{"data":[{"id":"101","type":"tracks"}],"included":[${inc(1)}]}""")
        json(artists)
        val r = td.search(env, Track("x", "Song 1", listOf("Beyoncé")))
        assertEquals(listOf("101"), r.map { it.id })
        next()
        assertCall(next(), "GET", api, "/v2/searchResults/sr-1/relationships/tracks")
    }
}

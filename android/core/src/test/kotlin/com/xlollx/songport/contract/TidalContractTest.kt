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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        TidalProvider.clearCaches()
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

    @Test fun aSecondReadAsksOnlyForThePlaylistItself() = runBlocking {
        // The tracks asked for again and the artists' names are kept: read again, only the pages come.
        val bare = """{"id":"100","type":"tracks","attributes":{"title":"Song 0","isrc":"GBAAA0000000","duration":"PT3M0S"},"relationships":{"artists":{"links":{"self":"/tracks/100/relationships/artists"}}}}"""
        json("""{"data":[${ref(0)}],"included":[$bare],"links":{"self":"x"}}""")
        json("""{"data":[${inc(0)}],"included":[{"id":"7","type":"artists","attributes":{"name":"Måneskin"}}]}""")
        json(artists)
        assertEquals(listOf("Måneskin"), td.tracks(env, "P").single().artists)
        repeat(3) { next() }
        json("""{"data":[${ref(0)}],"included":[$bare],"links":{"self":"x"}}""")
        assertEquals(listOf("Måneskin"), td.tracks(env, "P").single().artists)
        assertCall(next(), "GET", api, "/v2/playlists/P/relationships/items")
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
        // The documented filter (spec 1.10.251), with "me" for the signed-in user.
        assertEquals("me", r.query("filter[owners.id]"))
        assertNull(r.query("filter[r.owners.id]"))
        assertEquals("n", next().query("page[cursor]"))
        noMoreRequests()
    }

    @Test fun playlistsFallBackToTheOlderOwnerFilterOn400() = runBlocking {
        json("""{"errors":[{"status":"400","code":"INVALID_FILTER","detail":"Unknown filter owners.id"}]}""", 400)
        json("""{"data":[{"id":"p1","type":"playlists","attributes":{"name":"A","numberOfItems":1,"accessType":"PUBLIC"}}],"links":{}}""")
        assertEquals(listOf("p1"), td.playlists(env).map { it.id })
        assertEquals("me", next().query("filter[owners.id]"))
        val old = next()
        assertCall(old, "GET", api, "/v2/playlists")
        assertEquals("u1", old.query("filter[r.owners.id]"))
        assertNull(old.query("filter[owners.id]"))
        noMoreRequests()
    }

    @Test fun playlistsTryTheOlderOwnerFilterWhenTheNewOneListsNothing() = runBlocking {
        json("""{"data":[],"links":{}}""")
        json("""{"data":[{"id":"p9","type":"playlists","attributes":{"name":"Z","numberOfItems":2,"accessType":"UNLISTED"}}],"links":{}}""")
        assertEquals(listOf("p9"), td.playlists(env).map { it.id })
        next()
        assertEquals("u1", next().query("filter[r.owners.id]"))
    }

    @Test fun noPlaylistsStaysEmptyWhenTheOlderFilterIsRefused() = runBlocking {
        json("""{"data":[],"links":{}}""")
        json("""{"errors":[{"status":"400","detail":"Unknown filter"}]}""", 400)
        assertEquals(emptyList<String>(), td.playlists(env).map { it.id })
        next(); next(); noMoreRequests()
    }

    /** A service out of reach on the second read is not "no playlists" (a sync would take its target as gone). */
    @Test fun aLostConnectionOnTheOlderFilterIsNotAnEmptyLibrary() = runBlocking {
        json("""{"data":[],"links":{}}""")
        val mock = com.xlollx.songport.net.Http.rewrite!!
        // The second request goes to a port nothing listens on: the connection is refused.
        com.xlollx.songport.net.Http.rewrite = { req ->
            if (req.url.queryParameter("filter[r.owners.id]") != null) req.newBuilder().url(req.url.newBuilder().scheme("http").host("127.0.0.1").port(1).build()).build()
            else mock(req)
        }
        try {
            td.playlists(env)
            fail("an unreachable service listed no playlists")
        } catch (e: Exception) {
            assertTrue(e.toString(), com.xlollx.songport.net.NetError.isNetwork(e))
        }
        next(); noMoreRequests()
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

    /** Regression (11/11/2026): /userCollections/{userId}/relationships/albums answered 404; the collection is /userCollectionAlbums/me. */
    @Test fun albumsCollectionEditsInTwenties() = runBlocking {
        repeat(3) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204)) }
        td.addTracks(env, MusicProvider.ALBUMS_ID, (0 until 21).map { Track("al$it", "A", kind = Track.KIND_ALBUM) })
        val r = next()
        assertCall(r, "POST", api, "/v2/userCollectionAlbums/me/relationships/items")
        assertEquals(20, r.jsonBody()["data"].arr.size)
        assertEquals("albums", r.jsonBody()["data"][0]["type"].str)
        assertEquals(1, next().jsonBody()["data"].arr.size)
        td.removeTracks(env, MusicProvider.ALBUMS_ID, listOf(Track("al0", "A", kind = Track.KIND_ALBUM)))
        val d = next()
        assertCall(d, "DELETE", api, "/v2/userCollectionAlbums/me/relationships/items")
        assertEquals(listOf("al0"), d.jsonBody()["data"].arr.map { it["id"].str })
        noMoreRequests()
    }

    @Test fun followedArtistsReadAndEdited() = runBlocking {
        json("""{"data":[{"id":"7","type":"artists","meta":{"addedAt":"2024-01-01T00:00:00Z"}}],"included":[{"id":"7","type":"artists","attributes":{"name":"Måneskin"}}],
            "links":{"self":"x","next":"/userCollectionArtists/me/relationships/items?include=items&page%5Bcursor%5D=c2"}}""")
        json("""{"data":[{"id":"8","type":"artists","meta":{"addedAt":"2024-01-02T00:00:00Z"}}],"included":[{"id":"8","type":"artists","attributes":{"name":"Beyoncé"}}],"links":{"self":"x"}}""")
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        val ts = td.tracks(env, MusicProvider.ARTISTS_ID)
        assertEquals(listOf("Måneskin", "Beyoncé"), ts.map { it.title })
        assertEquals(Track.KIND_ARTIST, ts[0].kind)
        val r = next()
        assertCall(r, "GET", api, "/v2/userCollectionArtists/me/relationships/items")
        assertEquals("items", r.query("include"))
        assertEquals(null, r.query("countryCode"))
        assertEquals("c2", next().query("page[cursor]"))
        td.addTracks(env, MusicProvider.ARTISTS_ID, listOf(Track("9", "Ñ", kind = Track.KIND_ARTIST)))
        val a = next()
        assertCall(a, "POST", api, "/v2/userCollectionArtists/me/relationships/items")
        assertEquals("artists", a.jsonBody()["data"][0]["type"].str)
        noMoreRequests()
    }

    @Test fun savedAlbumsWithoutArtistIdsAreAskedForAgain() = runBlocking {
        val bare = """{"id":"al1","type":"albums","attributes":{"title":"Album","barcodeId":"0000000000017","releaseDate":"2020-05-01"},"relationships":{"artists":{"links":{"self":"/albums/al1/relationships/artists"}}}}"""
        json("""{"data":[{"id":"al1","type":"albums","meta":{"addedAt":"2024-01-01T00:00:00Z"}}],"included":[$bare],"links":{"self":"x"}}""")
        val full = """{"id":"al1","type":"albums","attributes":{"title":"Album","barcodeId":"0000000000017","releaseDate":"2020-05-01"},"relationships":{"artists":{"data":[{"id":"7","type":"artists"}]}}}"""
        json("""{"data":[$full]}""")
        json(artists)
        val al = td.tracks(env, MusicProvider.ALBUMS_ID).single()
        assertEquals("Album", al.title)
        assertEquals(listOf("Måneskin"), al.artists)
        assertEquals(Track.KIND_ALBUM, al.kind)
        assertCall(next(), "GET", api, "/v2/userCollectionAlbums/me/relationships/items")
        val again = next()
        assertCall(again, "GET", api, "/v2/albums")
        assertEquals("al1", again.query("filter[id]"))
        assertEquals("artists", again.query("include"))
        assertCall(next(), "GET", api, "/v2/artists")
        noMoreRequests()
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

    // ------------------------------------------------------------------ a playlist's picture

    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    @Test fun aCoverIsAnArtworkUploadedThenThePlaylistsCoverArt() = runBlocking {
        TidalProvider.coverRefused = false
        json("""{"data":{"id":"made-up-artwork","type":"artworks","attributes":{"mediaType":"IMAGE","sourceFile":{"md5Hash":"x","size":7,
            "uploadLink":{"href":"https://artwork-source-files.s3.amazonaws.com/made/up?X-Amz-Signature=made-up","meta":{"method":"PUT","headers":{"content-length":"7","content-md5":"made-up-md5"}}},
            "status":{"technicalFileStatus":"UPLOAD_REQUESTED"}},"files":[]}}}""", 201)
        text("", 200)
        json("""{"links":{"self":"/playlists/P/relationships/coverArt"},"meta":{}}""")
        td.setPlaylistCover(env, "P", jpeg)

        val create = next()
        assertCall(create, "POST", api, "/v2/artworks")
        assertEquals(vnd, create.getHeader("Content-Type")!!.substringBefore(';'))
        assertEquals("Bearer TT1", create.getHeader("Authorization"))
        assertTrue(create.getHeader("Idempotency-Key")!!.isNotBlank())
        val a = create.jsonBody()["data"]
        assertEquals("artworks", a["type"].str)
        assertEquals("IMAGE", a["attributes"]["mediaType"].str)
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(jpeg).joinToString("") { "%02x".format(it) }
        assertEquals(md5, a["attributes"]["sourceFile"]["md5Hash"].str)
        assertEquals("7", a["attributes"]["sourceFile"]["size"].toString())

        val put = next()
        assertCall(put, "PUT", "artwork-source-files.s3.amazonaws.com", "/made/up")
        assertEquals("made-up", put.query("X-Amz-Signature"))
        assertEquals("made-up-md5", put.getHeader("content-md5"))
        assertEquals("image/jpeg", put.getHeader("Content-Type"))
        assertEquals(null, put.getHeader("Authorization"))
        assertEquals(jpeg.toList(), put.body.clone().readByteArray().toList())

        val link = next()
        assertCall(link, "PATCH", api, "/v2/playlists/P/relationships/coverArt")
        assertEquals("made-up-artwork", link.jsonBody()["data"][0]["id"].str)
        assertEquals("artworks", link.jsonBody()["data"][0]["type"].str)
        noMoreRequests()
    }

    @Test fun aRefusedArtworkIsNotAskedAgain() = runBlocking {
        TidalProvider.coverRefused = false
        json("""{"errors":[{"status":"403","code":"FORBIDDEN","detail":"missing scope"}]}""", 403)
        assertTrue(td.canSetCover)
        try { td.setPlaylistCover(env, "P", jpeg); fail() } catch (e: ProviderException) { assertTrue(e.message!!.contains("403")) }
        assertFalse(td.canSetCover)
        next(); noMoreRequests()
        TidalProvider.coverRefused = false
    }
}

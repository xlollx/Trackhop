package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.SpotifyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Spotify Web API (developer.spotify.com/documentation/web-api), and through it the OAuth base
 * every token connector shares: answers shaped as the reference documents them. The /items paths
 * and the "item" key are the code's reading of Spotify's 2026 migration: not checked against the
 * live service.
 */
class SpotifyContractTest : ContractTest() {
    private val sp = SpotifyProvider()
    private val api = "api.spotify.com"

    @Before fun signIn() {
        BuildDefaults.values = mapOf("SPOTIFY_CLIENT_ID" to "cid123")
        env.tokens.set("spotify", Tokens(accessToken = "AT1", refreshToken = "RT1", userId = "me_user", userName = "Me"))
    }

    private fun id(i: Int) = "trk" + i.toString().padStart(19, '0')

    private fun track(i: Int, name: String = "Song $i", album: String? = "\"album\":{\"name\":\"Album $i\"},", wrap: String = "item") =
        """{"added_at":"2024-03-0${i % 9 + 1}T10:00:00Z","added_by":{"id":"me_user"},"is_local":false,
           "$wrap":{"id":"${id(i)}","name":${q(name)},"uri":"spotify:track:${id(i)}","duration_ms":${200000 + i},"is_local":false,
           "external_ids":{"isrc":"USRC1${i.toString().padStart(7, '0')}"},"artists":[{"name":"Artist $i"}],$album "type":"track"}}"""

    private fun page(items: List<String>, next: String?, total: Int, offset: Int = 0, limit: Int = 100) =
        """{"href":"https://api.spotify.com/v1/playlists/PL/items?offset=$offset&limit=$limit","items":[${items.joinToString(",")}],
           "limit":$limit,"next":${next?.let { q(it) } ?: "null"},"offset":$offset,"previous":null,"total":$total}"""

    private fun ok(body: String = """{"snapshot_id":"snap"}""") = json(body, 200)

    // ------------------------------------------------------------------ reading

    @Test fun playlistsFollowNextAndReadOwnership() = runBlocking {
        val first = (0 until 50).map { i ->
            """{"id":"p$i","name":"List $i","collaborative":${i == 3},"public":${i % 2 == 0},"owner":{"id":"${if (i < 2) "me_user" else "someone"}"},
               "images":${if (i == 0) "null" else "[{\"url\":\"https://i.scdn.co/image/$i\",\"height\":640,\"width\":640}]"},"tracks":{"href":"x","total":$i}}"""
        }
        json("""{"items":[${first.joinToString(",")}],"next":"https://api.spotify.com/v1/me/playlists?offset=50&limit=50","total":51}""")
        json("""{"items":[{"id":"p50","name":${q(UNICODE)},"public":null,"owner":{"id":"me_user"},"images":[],"items":{"total":7}}],"next":null,"total":51}""")
        val lists = sp.playlists(env)
        assertEquals(51, lists.size)
        assertEquals(listOf(true, true, false, true), lists.take(4).map { it.ownedByMe })
        assertNull(lists[0].imageUrl)
        assertEquals("https://i.scdn.co/image/1", lists[1].imageUrl)
        assertEquals(UNICODE, lists[50].name)
        assertEquals(7, lists[50].trackCount)
        assertNull(lists[50].isPublic)
        val r1 = next()
        assertCall(r1, "GET", api, "/v1/me/playlists")
        assertEquals("50", r1.query("limit"))
        assertEquals("Bearer AT1", r1.getHeader("Authorization"))
        assertEquals("application/json", r1.getHeader("Accept"))
        val r2 = next()
        assertEquals("50", r2.query("offset"))
        noMoreRequests()
    }

    @Test fun lastPageExactlyAtPageSizeStopsOnNullNext() = runBlocking {
        json(page((0 until 100).map { track(it) }, "https://api.spotify.com/v1/playlists/PL/items?offset=100&limit=100", 200))
        json(page((100 until 200).map { track(it) }, null, 200, offset = 100))
        val ts = sp.tracks(env, "PL")
        assertEquals(200, ts.size)
        assertEquals((0 until 200).map { id(it) }, ts.map { it.id })
        val r = next()
        assertCall(r, "GET", api, "/v1/playlists/PL/items")
        assertEquals("100", r.query("limit"))
        assertTrue(r.query("fields")!!.contains("item(id,name,uri"))
        assertEquals("100", next().query("offset"))
        noMoreRequests()
    }

    @Test fun emptyPlaylistIsOneRequest() = runBlocking {
        json(page(emptyList(), null, 0))
        assertEquals(emptyList<Track>(), sp.tracks(env, "PL"))
        next(); noMoreRequests()
    }

    @Test fun tenThousandTracksReadInOrder() = runBlocking {
        for (p in 0 until 100) {
            val nextUrl = if (p < 99) "https://api.spotify.com/v1/playlists/PL/items?offset=${(p + 1) * 100}&limit=100" else null
            json(page((p * 100 until p * 100 + 100).map { track(it) }, nextUrl, 10_000, offset = p * 100))
        }
        val ts = sp.tracks(env, "PL")
        assertEquals(10_000, ts.size)
        assertEquals((0 until 10_000).map { id(it) }, ts.map { it.id })
        assertEquals(100, server.requestCount)
    }

    @Test fun localFilesNullsEpisodesAndPartialObjects() = runBlocking {
        val items = listOf(
            track(1),
            // A local file: no id, uri spotify:local:...
            """{"added_at":"2024-01-01T00:00:00Z","is_local":true,"item":{"id":null,"name":"My demo","uri":"spotify:local:Me:Demos:My+demo:180","is_local":true,"artists":[{"name":"Me"}],"album":{"name":"Demos"},"duration_ms":180000}}""",
            // A track removed from the catalogue, listed as null.
            """{"added_at":"2024-01-01T00:00:00Z","item":null,"track":null}""",
            // Album null, no external ids, no artists array: still a track.
            track(2, album = "\"album\":null,"),
            """{"added_at":null,"item":{"id":"${id(3)}","name":"Bare","uri":"spotify:track:${id(3)}"}}""",
            // An episode, as the API lists it in a playlist: kept with its own uri, so removal and
            // ordering line up with the playlist; the sync then reports it as not found elsewhere.
            """{"added_at":"2024-01-01T00:00:00Z","item":{"id":"ep1","name":"Episode 12","uri":"spotify:episode:ep1","duration_ms":3600000,"type":"episode","is_local":false}}""",
            // Older answer shape: "track" instead of "item".
            track(4, wrap = "track"),
            // Duplicate entry of track 1.
            track(1),
            track(5, name = LONG),
        )
        json(page(items, null, items.size))
        val ts = sp.tracks(env, "PL")
        assertEquals(listOf(id(1), id(2), id(3), "ep1", id(4), id(1), id(5)), ts.map { it.id })
        assertEquals("", ts[1].album)
        assertEquals(emptyList<String>(), ts[2].artists)
        assertEquals(0L, ts[2].durationMs)
        assertEquals(0L, ts[2].addedAt)
        assertEquals("spotify:episode:ep1", ts[3].uri)
        assertEquals("USRC10000004", ts[4].isrc)
        assertEquals(LONG, ts[6].title)
    }

    @Test fun likedSongsPageByFifty() = runBlocking {
        val items = (0 until 50).map { track(it, wrap = "track") }
        json("""{"items":[${items.joinToString(",")}],"next":"https://api.spotify.com/v1/me/tracks?offset=50&limit=50","total":51}""")
        json("""{"items":[${track(50, wrap = "track")}],"next":null,"total":51}""")
        assertEquals(51, sp.tracks(env, MusicProvider.LIKED_ID).size)
        val r = next()
        assertCall(r, "GET", api, "/v1/me/tracks")
        assertEquals("50", r.query("limit"))
        next(); noMoreRequests()
    }

    @Test fun unplayableEntriesAndRelinkedOnes() = runBlocking {
        json(page(listOf(
            """{"item":{"id":"a","name":"Gone","uri":"spotify:track:a","is_playable":false,"restrictions":{"reason":"market"},"artists":[]}}""",
            """{"item":null}""",
            """{"item":{"id":"new","name":"Moved","uri":"spotify:track:new","is_playable":true,"linked_from":{"id":"old","uri":"spotify:track:old"}}}""",
            """{"item":{"id":"c","name":"Fine","uri":"spotify:track:c","is_playable":true}}""",
        ), null, 4))
        val u = sp.unplayable(env, "PL")
        assertEquals(listOf("a" to 0, "old" to 1), u.map { it.track.id to it.index })
        assertEquals("market", u[0].reason)
        assertEquals("new", u[1].relinked!!.id)
        assertEquals("from_token", next().query("market"))
        // linked_from was served (Extended Quota apps): no second read.
        noMoreRequests()
    }

    /**
     * Development Mode since February 2026: Track has no linked_from any more. A relinked entry is
     * then found by comparing the market read with the plain one, position by position.
     */
    @Test fun relinkedEntriesFoundWithoutLinkedFrom() = runBlocking {
        json(page(listOf(
            """{"item":{"id":"a","name":"Gone","uri":"spotify:track:a","is_playable":false,"restrictions":{"reason":"market"}}}""",
            """{"item":{"id":"c","name":"Fine","uri":"spotify:track:c","is_playable":true}}""",
            """{"item":null}""",
            """{"item":{"id":"new","name":"Moved","uri":"spotify:track:new","is_playable":true}}""",
        ), null, 4))
        json(page(listOf(
            """{"item":{"id":"a","name":"Gone","uri":"spotify:track:a"}}""",
            """{"item":{"id":"c","name":"Fine","uri":"spotify:track:c"}}""",
            """{"item":null}""",
            """{"item":{"id":"old","name":"Moved","uri":"spotify:track:old"}}""",
        ), null, 4))
        val u = sp.unplayable(env, "PL")
        assertEquals(listOf("a" to 0, "old" to 2), u.map { it.track.id to it.index })
        assertEquals("spotify:track:old", u[1].track.uri)
        assertEquals(false, u[1].track.available)
        assertEquals("market", u[1].reason)
        assertEquals("new", u[1].relinked!!.id)
        val market = next()
        assertEquals("from_token", market.query("market"))
        val plain = next()
        assertCall(plain, "GET", api, "/v1/playlists/PL/items")
        assertNull(plain.query("market"))
        noMoreRequests()
    }

    @Test fun nothingRelinkedWithoutLinkedFromReportsNothingMore() = runBlocking {
        json(page(listOf("""{"item":{"id":"c","name":"Fine","uri":"spotify:track:c","is_playable":true}}"""), null, 1))
        json(page(listOf("""{"item":{"id":"c","name":"Fine","uri":"spotify:track:c"}}"""), null, 1))
        assertEquals(emptyList<Any>(), sp.unplayable(env, "PL"))
        next(); next(); noMoreRequests()
    }

    @Test fun relinksNotGuessedWhenThePlaylistChangedBetweenTheReads() = runBlocking {
        json(page(listOf("""{"item":{"id":"new","name":"Moved","is_playable":true}}"""), null, 1))
        json(page(listOf("""{"item":{"id":"x","name":"X"}}""", """{"item":{"id":"old","name":"Moved"}}"""), null, 2))
        assertEquals(emptyList<Any>(), sp.unplayable(env, "PL"))
    }

    @Test fun likedRelinksFoundWithoutLinkedFrom() = runBlocking {
        json("""{"items":[{"added_at":"2024-01-01T00:00:00Z","track":{"id":"new","name":"Moved","is_playable":true}}],"next":null,"total":1}""")
        json("""{"items":[{"added_at":"2024-01-01T00:00:00Z","track":{"id":"old","name":"Moved"}}],"next":null,"total":1}""")
        val u = sp.unplayable(env, MusicProvider.LIKED_ID)
        assertEquals(listOf("old"), u.map { it.track.id })
        assertEquals("from_token", next().query("market"))
        val plain = next()
        assertCall(plain, "GET", api, "/v1/me/tracks")
        assertNull(plain.query("market"))
    }

    @Test fun itemsForbiddenFallsBackToTracksPath() = runBlocking {
        json("""{"error":{"status":403,"message":"Forbidden"}}""", 403)
        json(page(listOf(track(1, wrap = "track")), null, 1))
        assertEquals(1, sp.tracks(env, "PL").size)
        assertEquals("/v1/playlists/PL/items", next().pathOnly())
        assertEquals("/v1/playlists/PL/tracks", next().pathOnly())
    }

    /** Development Mode: /tracks is gone too, so the error shown is the one of the documented /items. */
    @Test fun itemsAndTracksForbiddenReportsTheItemsError() = runBlocking {
        json("""{"error":{"status":403,"message":"Not your playlist"}}""", 403)
        json("""{"error":{"status":403,"message":"Endpoint removed"}}""", 403)
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) {
            assertEquals(403, e.code)
            assertTrue(e.message, e.message!!.contains("Not your playlist"))
        }
        assertEquals("/v1/playlists/PL/items", next().pathOnly())
        assertEquals("/v1/playlists/PL/tracks", next().pathOnly())
        noMoreRequests()
    }

    @Test fun aServerErrorOnTheOlderPathIsReportedAsItIs() = runBlocking {
        json("""{"error":{"status":403,"message":"Forbidden"}}""", 403)
        json("""{"error":{"status":500,"message":"Server error"}}""", 500)
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) { assertEquals(500, e.code) }
    }

    // ------------------------------------------------------------------ writing

    @Test fun addTenThousandInBatchesOfAHundredInOrderWithDuplicates() = runBlocking {
        val tracks = (0 until 10_000).map { i -> val k = if (i % 1000 == 999) i - 1 else i; Track(id(k), "S$k", uri = "spotify:track:${id(k)}") }
        repeat(100) { json("""{"snapshot_id":"s$it"}""", 201) }
        sp.addTracks(env, "PL", tracks)
        val sent = ArrayList<String>()
        repeat(100) {
            val r = next()
            assertCall(r, "POST", api, "/v1/playlists/PL/items")
            assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
            val uris = r.jsonBody()["uris"].arr.map { it.str!! }
            assertEquals(100, uris.size)
            sent += uris
        }
        assertEquals(tracks.map { it.uri }, sent)
        noMoreRequests()
    }

    @Test fun removeInBatchesOfAHundredByUri() = runBlocking {
        val tracks = (0 until 250).map { Track(id(it), "S", uri = "spotify:track:${id(it)}") }
        repeat(3) { ok() }
        sp.removeTracks(env, "PL", tracks)
        val sizes = (0 until 3).map {
            val r = next()
            assertCall(r, "DELETE", api, "/v1/playlists/PL/items")
            val items = r.jsonBody()["items"].arr
            assertEquals("spotify:track:${id(it * 100)}", items[0]["uri"].str)
            items.size
        }
        assertEquals(listOf(100, 100, 50), sizes)
    }

    @Test fun likedTracksAreSavedOneByOneAndRemovedInForties() = runBlocking {
        sp.librarySpacingMs = 0
        val tracks = (0 until 90).map { Track(id(it), "S") }
        repeat(93) { ok("") }
        kotlinx.coroutines.withContext(MusicProvider.OrderedAdds) { sp.addTracks(env, MusicProvider.LIKED_ID, tracks) }
        sp.removeTracks(env, MusicProvider.LIKED_ID, tracks)
        // Saved one per request, in the order given, so each track gets its own second (issue #34).
        val saves = (0 until 90).map { val r = next(); assertCall(r, "PUT", api, "/v1/me/library"); r.query("uris") }
        assertEquals(tracks.map { "spotify:track:${it.id}" }, saves)
        val removals = (0 until 3).map { val r = next(); Triple(r.method, r.pathOnly(), r.query("uris")!!.split(",").size) }
        assertEquals(listOf(Triple("DELETE", "/v1/me/library", 40), Triple("DELETE", "/v1/me/library", 40), Triple("DELETE", "/v1/me/library", 10)), removals)
        noMoreRequests()
    }

    @Test fun likedTracksGoInFortiesWhenTheJobDoesNotKeepTheOrder() = runBlocking {
        sp.librarySpacingMs = 1_000
        val tracks = (0 until 90).map { Track(id(it), "S") }
        repeat(3) { ok("") }
        val start = System.currentTimeMillis()
        sp.addTracks(env, MusicProvider.LIKED_ID, tracks)
        assert(System.currentTimeMillis() - start < 1_000) { "unordered saves were spaced" }
        val sizes = (0 until 3).map { val r = next(); assertCall(r, "PUT", api, "/v1/me/library"); r.query("uris")!!.split(",").size }
        assertEquals(listOf(40, 40, 10), sizes)
        noMoreRequests()
    }

    @Test fun likedSavesAreSpacedSoEachHasItsOwnSecond() = runBlocking {
        sp.librarySpacingMs = 300
        repeat(3) { ok("") }
        val start = System.currentTimeMillis()
        kotlinx.coroutines.withContext(MusicProvider.OrderedAdds) { sp.addTracks(env, MusicProvider.LIKED_ID, (0 until 3).map { Track(id(it), "S") }) }
        assert(System.currentTimeMillis() - start >= 550) { "the saves were not spaced" }
        repeat(3) { next() }
    }

    @Test fun likedUrisNameTheTracks() = runBlocking {
        sp.librarySpacingMs = 0
        ok(""); ok("")
        kotlinx.coroutines.withContext(MusicProvider.OrderedAdds) { sp.addTracks(env, MusicProvider.LIKED_ID, listOf(Track(id(1), "S"), Track(id(2), "T"))) }
        assertEquals("spotify:track:${id(1)}", next().query("uris"))
        assertEquals("spotify:track:${id(2)}", next().query("uris"))
    }

    @Test fun likedFallsBackToTheOlderPathWhenTheLibraryPathIsMissing() = runBlocking {
        json("""{"error":{"status":404,"message":"Not found."}}""", 404)
        repeat(2) { ok("") }
        sp.addTracks(env, MusicProvider.LIKED_ID, (0 until 60).map { Track(id(it), "S") })
        next()
        val a = next(); assertCall(a, "PUT", api, "/v1/me/tracks"); assertEquals(50, a.jsonBody()["ids"].arr.size)
        assertEquals(10, next().jsonBody()["ids"].arr.size)
    }

    /**
     * Development Mode: /me/library refuses an artist URI (the reference lists none) and the older
     * /me/following is gone too: the /me/library error is the one reported.
     */
    @Test fun artistFollowRefusedOnBothPathsReportsTheLibraryError() = runBlocking {
        json("""{"error":{"status":400,"message":"Unsupported uri kind"}}""", 400)
        json("""{"error":{"status":403,"message":"Forbidden"}}""", 403)
        try { sp.addTracks(env, MusicProvider.ARTISTS_ID, listOf(Track("ar1", "A", kind = Track.KIND_ARTIST))); fail() } catch (e: ProviderException) {
            assertEquals(400, e.code)
            assertTrue(e.message, e.message!!.contains("Unsupported uri kind"))
        }
        val lib = next()
        assertCall(lib, "PUT", api, "/v1/me/library")
        assertEquals("spotify:artist:ar1", lib.query("uris"))
        assertCall(next(), "PUT", api, "/v1/me/following")
        noMoreRequests()
    }

    /** Extended Quota: the older /me/following still answers, and is used when /me/library refuses artists. */
    @Test fun artistFollowFallsBackToFollowingForExtendedQuotaApps() = runBlocking {
        json("""{"error":{"status":400,"message":"Unsupported uri kind"}}""", 400)
        ok("")
        sp.addTracks(env, MusicProvider.ARTISTS_ID, listOf(Track("ar1", "A", kind = Track.KIND_ARTIST)))
        next()
        val f = next()
        assertCall(f, "PUT", api, "/v1/me/following")
        assertEquals("artist", f.query("type"))
        assertEquals("ar1", f.jsonBody()["ids"].arr[0].str)
    }

    @Test fun reorderFallsBackToTracksForExtendedQuotaAppsAndStaysThere() = runBlocking {
        json(page(listOf("a", "b", "c").map { """{"item":{"id":"$it","is_local":false}}""" }, null, 3))
        json("""{"error":{"status":403,"message":"Forbidden"}}""", 403)
        ok(); ok()
        sp.reorderTracks(env, "PL", listOf("a", "b", "c").map { Track(it, it) }, listOf("c", "b", "a").map { Track(it, it) })
        next()
        assertEquals("/v1/playlists/PL/items", next().pathOnly())
        assertEquals("/v1/playlists/PL/tracks", next().pathOnly())
        assertEquals("/v1/playlists/PL/tracks", next().pathOnly())
        noMoreRequests()
    }

    @Test fun albumsAndShowsThroughTheLibrary() = runBlocking {
        repeat(2) { ok("") }
        sp.addTracks(env, MusicProvider.ALBUMS_ID, listOf(Track("al1", "A", kind = Track.KIND_ALBUM)))
        sp.addTracks(env, MusicProvider.PODCASTS_ID, listOf(Track("sh1", "S"), Track("sh2", "T")))
        assertEquals("spotify:album:al1", next().query("uris"))
        val r = next()
        assertCall(r, "PUT", api, "/v1/me/library")
        assertEquals("spotify:show:sh1,spotify:show:sh2", r.query("uris"))
    }

    @Test fun createRenameVisibilityDeleteWithUnicode() = runBlocking {
        json("""{"id":"NEW","name":${q(UNICODE)},"public":false,"collaborative":false}""", 201)
        ok(); ok(); ok("")
        val p = sp.createPlaylist(env, UNICODE, LONG)
        assertEquals("NEW", p.id)
        sp.renamePlaylist(env, "NEW", LONG)
        sp.setPlaylistVisibility(env, "NEW", true)
        sp.deletePlaylist(env, "NEW")
        val c = next()
        assertCall(c, "POST", api, "/v1/me/playlists")
        assertEquals(UNICODE, c.jsonBody()["name"].str)
        assertEquals(LONG, c.jsonBody()["description"].str)
        assertEquals("false", c.jsonBody()["public"].toString())
        val rn = next()
        assertCall(rn, "PUT", api, "/v1/playlists/NEW")
        assertEquals("""{"name":${q(LONG)}}""", rn.bodyText())
        val v = next()
        assertCall(v, "PUT", api, "/v1/playlists/NEW")
        assertEquals("""{"public":true}""", v.bodyText())
        val d = next()
        assertCall(d, "DELETE", api, "/v1/me/library")
        assertEquals("spotify:playlist:NEW", d.query("uris"))
        noMoreRequests()
    }

    @Test fun createFallsBackToTheUserPathOn404() = runBlocking {
        json("""{"error":{"status":404,"message":"Not found."}}""", 404)
        json("""{"id":"NEW2"}""", 201)
        assertEquals("NEW2", sp.createPlaylist(env, "x", "").id)
        next()
        assertCall(next(), "POST", api, "/v1/users/me_user/playlists")
    }

    @Test fun reorderSendsRangeMoves() = runBlocking {
        val cur = listOf("a", "b", "c").map { Track(it, it) }
        // The playlist as the API lists it (one read, ids only) before the moves.
        json(page(listOf("a", "b", "c").map { """{"item":{"id":"$it","is_local":false}}""" }, null, 3))
        ok()
        sp.reorderTracks(env, "PL", cur, listOf("c", "a", "b").map { Track(it, it) })
        next()
        val r = next()
        assertCall(r, "PUT", api, "/v1/playlists/PL/items")
        assertEquals(2, r.jsonBody()["range_start"].int)
        assertEquals(0, r.jsonBody()["insert_before"].int)
        assertEquals(1, r.jsonBody()["range_length"].int)
        noMoreRequests()
    }

    /**
     * Regression: a local file (or an entry the catalogue lost) is in the playlist but not in the
     * listing Trackhop reads, so the listing's indices are not the playlist's positions. The move
     * must name the playlist's own positions, or "keep order" moves the wrong tracks.
     */
    @Test fun reorderCountsEntriesTheListingSkips() = runBlocking {
        val cur = listOf("a", "b", "c").map { Track(it, it) }
        json(page(listOf(
            """{"item":{"id":"a","is_local":false}}""",
            """{"item":{"id":null,"is_local":true}}""",
            """{"item":{"id":"b","is_local":false}}""",
            """{"item":null}""",
            """{"item":{"id":"c","is_local":false}}""",
        ), null, 5))
        ok()
        sp.reorderTracks(env, "PL", cur, listOf("c", "a", "b").map { Track(it, it) })
        val read = next()
        assertCall(read, "GET", api, "/v1/playlists/PL/items")
        val r = next()
        assertEquals(4, r.jsonBody()["range_start"].int)
        assertEquals(0, r.jsonBody()["insert_before"].int)
        noMoreRequests()
    }

    @Test fun reorderForwardMoveAcrossASkippedEntry() = runBlocking {
        // Playlist: a, (local), b, c. Wanted: b, c, a -> a goes after c: insert_before counts the local file too.
        json(page(listOf(
            """{"item":{"id":"a"}}""", """{"item":{"id":null,"is_local":true}}""", """{"item":{"id":"b"}}""", """{"item":{"id":"c"}}""",
        ), null, 4))
        ok()
        sp.reorderTracks(env, "PL", listOf("a", "b", "c").map { Track(it, it) }, listOf("b", "c", "a").map { Track(it, it) })
        next()
        val r = next().jsonBody()
        assertEquals(0, r["range_start"].int)
        assertEquals(4, r["insert_before"].int)
    }

    @Test fun reorderRefusesWhenThePlaylistChangedMeanwhile() = runBlocking {
        json(page(listOf("""{"item":{"id":"a"}}""", """{"item":{"id":"x"}}"""), null, 2))
        try {
            sp.reorderTracks(env, "PL", listOf("a", "b").map { Track(it, it) }, listOf("b", "a").map { Track(it, it) })
            fail("a changed playlist must not be reordered blindly")
        } catch (e: ProviderException) { }
        next(); noMoreRequests()
    }

    @Test fun coverGoesUpAsBase64Jpeg() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(202))
        sp.setPlaylistCover(env, "PL", byteArrayOf(-1, -40, -1, -32, 1, 2, 3))
        val r = next()
        assertCall(r, "PUT", api, "/v1/playlists/PL/images")
        assertEquals("image/jpeg", r.getHeader("Content-Type")!!.substringBefore(';'))
        assertEquals("/9j/4AECAw==", r.bodyText())
    }

    // ------------------------------------------------------------------ OAuth: refresh and errors

    @Test fun expiredTokenIsRefreshedOnceAndTheCallRetried() = runBlocking {
        json("""{"error":{"status":401,"message":"The access token expired"}}""", 401)
        json("""{"access_token":"AT2","token_type":"Bearer","scope":"playlist-read-private","expires_in":3600}""")
        json(page(listOf(track(1)), null, 1))
        assertEquals(1, sp.tracks(env, "PL").size)
        assertEquals("Bearer AT1", next().getHeader("Authorization"))
        val t = next()
        assertCall(t, "POST", "accounts.spotify.com", "/api/token")
        assertTrue(t.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "RT1", "client_id" to "cid123"), t.form())
        assertEquals("Bearer AT2", next().getHeader("Authorization"))
        noMoreRequests()
        val saved = env.tokens.get("spotify")!!
        assertEquals("AT2", saved.accessToken)
        assertEquals("RT1", saved.refreshToken) // Spotify may omit a new refresh token: the old one stays.
        assertTrue(saved.expiresAt > System.currentTimeMillis())
    }

    @Test fun rotatedRefreshTokenIsKept() = runBlocking {
        json("", 401)
        json("""{"access_token":"AT2","refresh_token":"RT2","expires_in":3600}""")
        json(page(emptyList(), null, 0))
        sp.tracks(env, "PL")
        assertEquals("RT2", env.tokens.get("spotify")!!.refreshToken)
    }

    @Test fun refreshRefusedClearsTheSessionWithAClearError() = runBlocking {
        json("""{"error":{"status":401,"message":"The access token expired"}}""", 401)
        json("""{"error":"invalid_grant","error_description":"Refresh token revoked"}""", 400)
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) {
            assertTrue(e.message, e.message!!.contains("session expired, please reconnect (400)"))
        }
        assertNull(env.tokens.get("spotify"))
        next(); next(); noMoreRequests()
    }

    @Test fun secondUnauthorizedAfterRefreshIsNotRetriedAgain() = runBlocking {
        json("", 401)
        json("""{"access_token":"AT2","expires_in":3600}""")
        json("""{"error":{"status":401,"message":"Invalid access token"}}""", 401)
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("Invalid access token"))
        }
        repeat(3) { next() }
        noMoreRequests()
    }

    @Test fun tokenAboutToExpireIsRefreshedBeforeTheCall() = runBlocking {
        env.tokens.set("spotify", Tokens("AT1", "RT1", expiresAt = System.currentTimeMillis() + 10_000, userId = "me_user"))
        json("""{"access_token":"AT3","expires_in":3600}""")
        json(page(emptyList(), null, 0))
        sp.tracks(env, "PL")
        assertEquals("/api/token", next().pathOnly())
        assertEquals("Bearer AT3", next().getHeader("Authorization"))
    }

    @Test fun shortRetryAfterIsWaitedOnceThenTheCallGoesOn() = runBlocking {
        json("""{"error":{"status":429,"message":"API rate limit exceeded"}}""", 429, "Retry-After" to "3")
        json(page(listOf(track(1)), null, 1))
        assertEquals(1, sp.tracks(env, "PL").size)
        assertEquals(listOf(3000L), pauses)
        assertEquals(2, server.requestCount)
    }

    @Test fun longRetryAfterIsHandedToTheCallerAtOnce() = runBlocking {
        json("""{"error":{"status":429,"message":"API rate limit exceeded"}}""", 429, "Retry-After" to "7200")
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) {
            assertEquals(429, e.code)
            assertTrue(e.message, e.message!!.endsWith("(429, retry in 7200 s)"))
            assertTrue(e.message!!.startsWith("spotify_rate_limited(2 h)"))
        }
        assertEquals(emptyList<Long>(), pauses)
        assertEquals(1, server.requestCount)
    }

    @Test fun repeated429WithoutRetryAfterBacksOffThenGivesUp() = runBlocking {
        repeat(4) { json("{}", 429) }
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) { assertEquals(429, e.code) }
        assertEquals(listOf(2000L, 4000L, 6000L), pauses)
        assertEquals(4, server.requestCount)
    }

    @Test fun gatewayErrorIsRetriedOnceOnly() = runBlocking {
        text("<html>502 Bad Gateway</html>", 502)
        json(page(emptyList(), null, 0))
        sp.tracks(env, "PL")
        assertEquals(listOf(1500L), pauses)
        pauses.clear()
        text("<html>503</html>", 503); text("<html>503</html>", 503)
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) { assertEquals(503, e.code) }
        assertEquals(listOf(1500L), pauses)
        assertEquals(4, server.requestCount)
    }

    @Test fun internalErrorIsNotRetried() = runBlocking {
        json("""{"error":{"status":500,"message":"Server error"}}""", 500)
        try { sp.tracks(env, "PL"); fail() } catch (e: ProviderException) { assertEquals("Spotify API 500: Server error", e.message) }
        assertEquals(1, server.requestCount)
    }

    @Test fun plainTextRefusalBecomesTheFriendlyPremiumMessage() = runBlocking {
        text("Active premium subscription required for the owner of the app.", 403, "text/plain")
        try { sp.playlists(env); fail() } catch (e: ProviderException) { assertEquals("spotify_premium_required", e.message) }
    }

    @Test fun truncatedAnswerFailsInsteadOfReturningAShorterList() = runBlocking {
        json("""{"items":[{"item":{"id":"a","name":"A","uri":"spotify:track:a"}},""")
        try { sp.tracks(env, "PL"); fail("a cut answer must not read as a complete playlist") } catch (e: Exception) { }
    }

    @Test fun searchByIsrcThenByFields() = runBlocking {
        json("""{"tracks":{"items":[],"total":0}}""")
        json("""{"tracks":{"items":[{"id":"x1","name":${q(UNICODE)},"uri":"spotify:track:x1","artists":[{"name":"Ñandú"}],"album":null,"explicit":true}]}}""")
        val r = sp.search(env, Track("src", UNICODE, listOf("Ñandú"), isrc = "usrc17607839"))
        assertEquals(listOf("x1"), r.map { it.id })
        assertEquals(true, r[0].explicit)
        val a = next()
        assertCall(a, "GET", api, "/v1/search")
        assertEquals("isrc:USRC17607839", a.query("q"))
        assertEquals("track", a.query("type"))
        assertTrue(next().query("q")!!.startsWith("track:"))
    }

    @Test fun playlistCountFromBothShapes() = runBlocking {
        json("""{"name":"N","description":null,"images":[],"tracks":{"total":12}}""")
        assertEquals(12, sp.playlistInfo(env, "PL").trackCount)
        assertTrue(next().query("fields")!!.contains("tracks(total)"))
    }

    @Test fun likedVersionUsesTotalAndNewest() = runBlocking {
        json("""{"items":[{"added_at":"2024-05-01T00:00:00Z","track":{"id":"a"}}],"total":321}""")
        assertEquals("321|2024-05-01T00:00:00Z", sp.playlistVersion(env, MusicProvider.LIKED_ID))
        json("""{"snapshot_id":"MTY4,abc"}""")
        assertEquals("MTY4,abc", sp.playlistVersion(env, "PL"))
        next()
        assertEquals("snapshot_id", next().query("fields"))
    }
}

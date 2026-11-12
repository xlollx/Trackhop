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
 * Spotify through the web player's backend (pathfinder GraphQL and the spclient playlist store),
 * against a local mock server. None of this is a documented interface: the shapes are the ones the
 * client already reads and writes, so these tests pin its behaviour (what it sends, and what it does
 * with each kind of answer), not Spotify's. Whether Spotify accepts these exact bodies can only be
 * seen against the live service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpotifyWebClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var web: SpotifyWebClient
    private val hash = { op: String -> op.hashCode().toUInt().toString(16).padStart(8, '0').repeat(8) }
    private val unicode = "Ñandú – 日本語 👩🏽‍🎤 \"live\""

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            val host = r.getHeader(BridgeMock.ORIGINAL_HOST)
            when {
                host == "open.spotify.com" -> MockResponse().setBody("""<html><script id="session" data-testid="session" type="application/json">
                    {"accessToken":"BQ-web-token","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":false,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script>
                    <script>var v="1.2.75.312.g5c3ea2d9";</script></html>""")
                host == "raw.githubusercontent.com" -> MockResponse().setBody("""{"operations":{${
                    listOf("addToPlaylist", "removeFromPlaylist", "removeFromLibrary", "addToLibrary", "fetchPlaylist", "fetchLibraryTracks", "libraryV3")
                        .joinToString(",") { "\"$it\":{\"hash\":\"${hash(it)}\"}" }}}}""")
                host == "www.spotify.com" -> MockResponse().setBody("""{"profile":{"username":"spuser","name":"Me"}}""")
                host == "clienttoken.spotify.com" -> MockResponse().setBody("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"CT-granted","expires_after_seconds":1209600,"refresh_after_seconds":1209600}}""")
                else -> null
            }
        }
        app = RuntimeEnvironment.getApplication()
        SpotifyBridge.session.clear(app)
        SpotifyBridge.session.save(app, "sp_dc=dc-cookie; sp_t=device-1; sp_key=k", "spuser")
        web = SpotifyWebClient(app)
        web.rememberClientToken("CT-1", System.currentTimeMillis() + 86_400_000)
    }

    @After fun tearDown() { BridgeMock.stop() }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())
    private fun RecordedRequest.host() = getHeader(BridgeMock.ORIGINAL_HOST)
    private fun answer(body: String, code: Int = 200, vararg h: Pair<String, String>) = BridgeMock.answer(body, code, *h)

    private val rootlist = """{"revision":"AAAAEQAAAAAbH6wrK8vY7Q==","length":3,"timestamp":"1700000000000","contents":{"pos":0,"truncated":false,
        "items":[{"uri":"spotify:playlist:A","attributes":{"timestamp":"1"}},{"uri":"spotify:playlist:B","attributes":{"timestamp":"2"}},
                 {"uri":"spotify:start-group:f:Folder","attributes":{}},{"uri":"spotify:playlist:C","attributes":{}}],"metaItems":[]},"ownerUsername":"spuser"}"""

    private fun assertRootlistRead(r: RecordedRequest) {
        assertEquals("GET", r.method)
        assertEquals("spclient.wg.spotify.com", r.host())
        assertEquals("/playlist/v2/user/spuser/rootlist", r.requestUrl!!.encodedPath)
        assertTrue(r.requestUrl!!.queryParameter("decorate")!!.contains("revision"))
        assertEquals("Bearer BQ-web-token", r.getHeader("Authorization"))
        assertEquals("CT-1", r.getHeader("client-token"))
        assertEquals("WebPlayer", r.getHeader("app-platform"))
        assertEquals("1.2.75.312.g5c3ea2d9", r.getHeader("spotify-app-version"))
    }

    private fun op(r: RecordedRequest): JsonElement? {
        val d = r.json()["deltas"]
        assertEquals(1, d.arr.size)
        assertEquals(5L, d[0]["info"]["source"]["client"].long)
        return d[0]["ops"].arr.single()
    }

    // ------------------------------------------------------------------ rootlist changes

    /** Regression of 1.0.73: a rootlist change without baseRevision is refused with HTTP 400 and an empty body. */
    @Test fun setPublicIsAKind5ChangeOnTheRevisionJustRead() {
        answer(rootlist)
        answer("""{"revision":"AAAAEgAAAAA="}""")
        web.setPublic("C", true)
        assertRootlistRead(call(0))
        val w = call(1)
        assertEquals("POST", w.method)
        assertEquals("/playlist/v2/user/spuser/rootlist/changes", w.requestUrl!!.encodedPath)
        assertEquals("application/json", w.getHeader("Content-Type")!!.substringBefore(';'))
        assertEquals("AAAAEQAAAAAbH6wrK8vY7Q==", w.json()["baseRevision"].str)
        val o = op(w)
        assertEquals(5L, o["kind"].long)
        val u = o["updateItemAttributes"]
        assertEquals(3L, u["index"].long) // the position in the rootlist, folder markers included
        assertEquals("true", u["newAttributes"]["values"]["public"].toString())
        assertEquals(0, u["newAttributes"]["noValue"].arr.size)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun setPrivateToo() {
        answer(rootlist); answer("{}")
        web.setPublic("A", false)
        val u = op(call(1))["updateItemAttributes"]
        assertEquals(0L, u["index"].long)
        assertEquals("false", u["newAttributes"]["values"]["public"].toString())
    }

    @Test fun setPublicOfAPlaylistNotInTheLibrarySendsNothing() {
        answer(rootlist)
        try { web.setPublic("ZZZ", true); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("not in your library")) }
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun rootlistWithoutARevisionSendsTheChangeWithoutOne() {
        answer("""{"length":1,"contents":{"items":[{"uri":"spotify:playlist:A"}]}}"""); answer("{}")
        web.setPublic("A", true)
        assertNull(call(1).json()["baseRevision"])
    }

    @Test fun refusedRootlistChangeIsAnError() {
        answer(rootlist); answer("", 400)
        try { web.setPublic("A", true); fail() } catch (e: BridgeException) {
            assertEquals("Spotify playlist store: HTTP 400 on /playlist/v2/user/spuser/rootlist/changes (no reason given)", e.message)
        }
    }

    @Test fun createAddsTheNewPlaylistAtTheTopOfTheRootlist() {
        answer("""{"uri":"spotify:playlist:NEW1","revision":"AAAAAQ=="}""")
        answer(rootlist)
        answer("{}")
        val p = web.createPlaylist(unicode, "Synced with Trackhop")
        assertEquals("NEW1", p.id)
        val c = call(0)
        assertEquals("POST", c.method)
        assertEquals("/playlist/v2/playlist", c.requestUrl!!.encodedPath)
        val attrs = c.json()["ops"][0]
        assertEquals(6L, attrs["kind"].long)
        assertEquals(unicode, attrs["updateListAttributes"]["newAttributes"]["values"]["name"].str)
        assertEquals("Synced with Trackhop", attrs["updateListAttributes"]["newAttributes"]["values"]["description"].str)
        assertRootlistRead(call(1))
        val w = call(2)
        assertEquals("AAAAEQAAAAAbH6wrK8vY7Q==", w.json()["baseRevision"].str)
        val o = op(w)
        assertEquals(2L, o["kind"].long)
        assertEquals("spotify:playlist:NEW1", o["add"]["items"][0]["uri"].str)
        assertEquals("true", o["add"]["addFirst"].toString())
    }

    @Test fun createStillSucceedsWhenTheRootlistAddIsRefused() {
        answer("""{"uri":"spotify:playlist:NEW2"}"""); answer(rootlist); answer("", 400)
        assertEquals("NEW2", web.createPlaylist("x", "").id)
        assertEquals(3, BridgeMock.calls.size)
    }

    @Test fun createWithoutAUriIsAnError() {
        answer("""{"revision":"x"}""")
        try { web.createPlaylist("x", ""); fail() } catch (e: BridgeException) { assertEquals("Spotify: playlist not created", e.message) }
    }

    @Test fun deleteGoesThroughTheLibraryMutation() {
        answer("""{"data":{"removeFromLibrary":{"__typename":"RemoveFromLibraryResponse"}}}""")
        web.deletePlaylist("A")
        val g = call(0)
        assertEquals("api-partner.spotify.com", g.host())
        assertEquals("/pathfinder/v1/query", g.requestUrl!!.encodedPath)
        assertEquals("removeFromLibrary", g.json()["operationName"].str)
        assertEquals(hash("removeFromLibrary"), g.json()["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals("spotify:playlist:A", g.json()["variables"]["uris"][0].str)
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun deleteFallsBackToARootlistRemovalOnTheRevision() {
        answer("""{"errors":[{"message":"Something went wrong"}],"data":null}""")
        answer(rootlist)
        answer("{}")
        web.deletePlaylist("B")
        assertRootlistRead(call(1))
        val w = call(2)
        assertEquals("/playlist/v2/user/spuser/rootlist/changes", w.requestUrl!!.encodedPath)
        assertEquals("AAAAEQAAAAAbH6wrK8vY7Q==", w.json()["baseRevision"].str)
        val o = op(w)
        assertEquals(3L, o["kind"].long)
        assertEquals("spotify:playlist:B", o["rem"]["items"][0]["uri"].str)
        assertEquals("true", o["rem"]["itemsAsKey"].toString())
    }

    @Test fun deleteRateLimitedDoesNotFallBack() {
        answer("""{"error":"rate limited"}""", 429, "Retry-After" to "120")
        try { web.deletePlaylist("B"); fail() } catch (e: BridgeException) { assertEquals("Spotify asks to slow down (429, retry in 120 s)", e.message) }
        assertEquals(1, BridgeMock.calls.size)
    }

    // ------------------------------------------------------------------ other writes and reads

    @Test fun refusedBearerIsRecapturedOnceAndTheCallRetried() {
        answer(rootlist, 401)
        answer(rootlist)
        answer("{}")
        SpotifyBridge.token(app)
        val pagesBefore = BridgeMock.all.count { it.host() == "open.spotify.com" }
        web.setPublic("A", true)
        assertEquals(3, BridgeMock.calls.size)
        assertEquals(pagesBefore + 1, BridgeMock.all.count { it.host() == "open.spotify.com" })
    }

    @Test fun addInHundredsThroughTheMutationInOrder() {
        repeat(3) { answer("""{"data":{"addItemsToPlaylist":{"__typename":"AddItemsToPlaylistPayload"}}}""") }
        val uris = (0 until 250).map { "spotify:track:t$it" }
        web.addToPlaylist("A", uris)
        val sent = (0 until 3).flatMap { i ->
            val v = call(i).json()
            assertEquals("addToPlaylist", v["operationName"].str)
            assertEquals("spotify:playlist:A", v["variables"]["playlistUri"].str)
            assertEquals("BOTTOM_OF_PLAYLIST", v["variables"]["newPosition"]["moveType"].str)
            v["variables"]["uris"].arr.map { it.str }
        }
        assertEquals(uris, sent)
    }

    @Test fun addFallsBackToTheStoreWhenTheMutationFails() {
        answer("""{"data":{"addItemsToPlaylist":{"__typename":"AddItemsToPlaylistError"}}}""")
        answer("{}")
        web.addToPlaylist("A", listOf("spotify:track:x"))
        val w = call(1)
        assertEquals("/playlist/v2/playlist/A/changes", w.requestUrl!!.encodedPath)
        val o = op(w)
        assertEquals(2L, o["kind"].long)
        assertEquals("true", o["add"]["addLast"].toString())
        assertNull(w.json()["baseRevision"]) // a playlist's own changes need no revision
    }

    @Test fun moveAndRenameGoToThePlaylistStore() {
        answer("{}"); answer("{}")
        web.moveItems("A", listOf(3 to 0, 0 to 5))
        web.renamePlaylist("A", "x".repeat(400))
        val ops = call(0).json()["deltas"][0]["ops"].arr
        assertEquals(listOf(4L, 4L), ops.map { it["kind"].long })
        assertEquals(3L, ops[0]["mov"]["fromIndex"].long); assertEquals(0L, ops[0]["mov"]["toIndex"].long)
        val r = op(call(1))
        assertEquals(6L, r["kind"].long)
        assertEquals("x".repeat(400), r["updateListAttributes"]["newAttributes"]["values"]["name"].str)
    }

    private fun entry(i: Int, uri: String = "spotify:track:t$i", typename: String = "Track") = """{"uid":"u$i","addedAt":{"isoString":"2024-01-0${i % 9 + 1}T00:00:00Z"},
        "itemV2":{"_uri":"$uri","data":{"__typename":"$typename","uri":"$uri","name":"Song $i","trackDuration":{"totalMilliseconds":${200000 + i}},
        "artists":{"items":[{"profile":{"name":"Artist $i"}}]},"albumOfTrack":{"name":"Album","date":{"year":2020}},"playability":{"playable":${i != 7}},
        "contentRating":{"label":"NONE"}}}}"""

    @Test fun playlistReadByHundredsStopsAtAFullLastPage() {
        answer("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":200,"items":[${(0 until 100).joinToString(",") { entry(it) }}]}}}}""")
        answer("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":200,"items":[
            ${(100 until 197).joinToString(",") { entry(it) }},${entry(197, "spotify:episode:e1", "Episode")},${entry(198, "spotify:local:a:b:c:180", "LocalTrack")},${entry(1)}]}}}}""")
        val ts = web.playlistTracks("A")
        assertEquals(198, ts.size)
        assertEquals("u1", ts[1].setVideoId)
        assertEquals("t1", ts[197].id)
        assertEquals(false, ts[7].available)
        assertEquals(listOf(0L, 100L), (0 until 2).map { call(it).json()["variables"]["offset"].long })
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun anUnreadablePageIsAnErrorNotAnEmptyPlaylist() {
        answer("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":3,"items":[{"uid":"a","itemV2":{"data":{"__typename":"Mystery"}}}]}}}}""")
        try { web.playlistTracks("A"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("nothing readable")) }
    }

    /** The shape of the player's own answer (a capture of the profile page, 11/2026), with made-up names and ids. */
    @Test fun topSongsAreAskedAsTheProfilePageAsksThem() {
        val cover = """{"sources":[{"height":300,"url":"https://i.example/c300","width":300},{"height":64,"url":"https://i.example/c64","width":64}]}"""
        answer("""{"data":{"me":{"profile":{
            "topArtists":{"__typename":"ArtistPageV2","totalCount":2,"items":[
              {"data":{"__typename":"Artist","profile":{"name":"Neon Harbour"},"uri":"spotify:artist:a1","visuals":{"avatarImage":$cover}}},
              {"data":{"__typename":"Artist","profile":{"name":"Ölfa"},"uri":"spotify:artist:a2","visuals":{"avatarImage":$cover}}}]},
            "topTracks":{"__typename":"TrackPageV2","totalCount":340,"items":[
              {"data":{"__typename":"Track","albumOfTrack":{"coverArt":$cover,"name":"Low Tide","uri":"spotify:album:al1"},
                "artists":{"items":[{"profile":{"name":"Neon Harbour"},"uri":"spotify:artist:a1"}]},
                "associationsV3":{"audioAssociations":{"totalCount":0},"videoAssociations":{"totalCount":1}},
                "contentRating":{"label":"EXPLICIT"},"duration":{"totalMilliseconds":201000},"mediaType":"AUDIO","name":"Pier Lights","saved":true,
                "uri":"spotify:track:t1","visualIdentity":{"sixteenByNineCoverImage":{"image":{"data":{"__typename":"ImageV2","sources":[]}}}}}},
              {"data":{"__typename":"Episode","uri":"spotify:episode:e1","name":"Talk"}},
              {"data":{"__typename":"Track","albumOfTrack":{"coverArt":$cover,"name":"Ölfa","uri":"spotify:album:al2"},
                "artists":{"items":[{"profile":{"name":"Ölfa"},"uri":"spotify:artist:a2"},{"profile":{"name":"Neon Harbour"},"uri":"spotify:artist:a1"}]},
                "contentRating":{"label":"NONE"},"duration":{"totalMilliseconds":187500},"mediaType":"AUDIO","name":"Kettle","saved":false,"uri":"spotify:track:t2"}}]}}}}}""")
        val top = web.top("MID_TERM")
        assertEquals(listOf("t1", "t2"), top.tracks.map { it.id })
        assertEquals(listOf("Neon Harbour"), top.tracks[0].artists)
        assertEquals(listOf("Ölfa", "Neon Harbour"), top.tracks[1].artists)
        assertEquals("Low Tide", top.tracks[0].album)
        assertEquals(201_000L, top.tracks[0].durationMs)
        assertEquals(true, top.tracks[0].explicit)
        assertEquals("spotify:track:t1", top.tracks[0].uri)
        assertEquals(listOf("Neon Harbour", "Ölfa"), top.artists)
        val req = BridgeMock.calls.last { it.host() == "api-partner.spotify.com" }
        val sent = req.json()
        assertEquals("userTopContent", sent["operationName"].str)
        assertEquals(SpotifyWebClient.SHIPPED_HASHES["userTopContent"], sent["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals(1L, sent["extensions"]["persistedQuery"]["version"].long)
        val v = sent["variables"]
        assertEquals("true", v["includeTopTracks"].toString())
        assertEquals("true", v["includeTopArtists"].toString())
        for (input in listOf(v["topTracksInput"], v["topArtistsInput"])) {
            assertEquals("MID_TERM", input["timeRange"].str)
            assertEquals("AFFINITY", input["sortBy"].str)
            // The player's "Show all" pages ask 100.
            assertEquals(100L, input["limit"].long)
            assertEquals(0L, input["offset"].long)
        }
    }

    /** An artist's tour dates: the player's search for the artist, its `ArtistConcerts`, then a concert's own page. */
    @Test fun concertsAreAskedAsTheArtistAndConcertPagesAskThem() {
        answer("""{"data":{"searchV2":{"artists":{"items":[
            {"data":{"__typename":"Artist","uri":"spotify:artist:x0","profile":{"name":"Neon Harbour Tribute"}}},
            {"data":{"__typename":"Artist","uri":"spotify:artist:a1","profile":{"name":"Neón  harbour"}}}]}}}}""")
        answer("""{"data":{"artistUnion":{"profile":{"name":"Neon Harbour"},"uri":"spotify:artist:a1"},
            "concerts":{"concerts":{"items":[
              {"data":{"artists":{"items":[{"data":{"profile":{"name":"Neon Harbour"}}}]},"location":{"city":"Lyon"},"startDateIsoString":"2030-03-14T20:00+01:00","title":"Neon Harbour – Spring","uri":"spotify:concert:c1"}},
              {"data":{"artists":{"items":[]},"location":{"city":"Austin"},"startDateIsoString":"2030-04-02T19:30-05:00","title":"Harbour Fest","uri":"spotify:concert:c2"}},
              {"data":{"title":"broken"}}]}}}}""")
        val uri = web.artistUri("Neon Harbour")
        assertEquals("spotify:artist:a1", uri)
        val dates = web.concerts(uri!!)
        assertEquals(listOf("spotify:concert:c1", "spotify:concert:c2"), dates.map { it.uri })
        assertEquals("Lyon", dates[0].city)
        assertEquals("2030-03-14T20:00+01:00", dates[0].start)
        assertEquals("", dates[0].country)
        val list = BridgeMock.calls.last { it.host() == "api-partner.spotify.com" }.json()
        assertEquals("ArtistConcerts", list["operationName"].str)
        assertEquals(SpotifyWebClient.SHIPPED_HASHES["ArtistConcerts"], list["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals("spotify:artist:a1", list["variables"]["artistUri"].str)
        assertEquals("null", list["variables"]["geoHash"].toString())
        assertEquals("false", list["variables"]["includeNearby"].toString())

        answer("""{"data":{"concert":{"__typename":"ConcertV2","ageRestriction":"","festival":false,"doorsOpenTimeIsoString":null,
            "location":{"attribution":null,"city":"Lyon","coordinates":{"latitude":45.7,"longitude":4.8},"country":"fr","metroAreaLocation":null,"name":"Salle du Quai","region":null},
            "offers":{"items":[{"availability":"UNKNOWN","providerName":"Tickets Co","saleType":"on-sale","url":"https://tickets.example/e/1","urlType":"TICKET_PAGE"}],"totalCount":1},
            "startDateIsoString":"2030-03-14T20:00+01:00","status":"UNKNOWN","title":"Neon Harbour – Spring","uri":"spotify:concert:c1"},
            "me":{"profile":{"location":{"name":"Somewhere"}}}}}""")
        val one = web.concert("spotify:concert:c1")!!
        assertEquals("Salle du Quai", one.venue)
        assertEquals("FR", one.country)
        assertEquals("Lyon", one.city)
        val page = BridgeMock.calls.last { it.host() == "api-partner.spotify.com" }.json()
        assertEquals("concert", page["operationName"].str)
        assertEquals(SpotifyWebClient.SHIPPED_HASHES["concert"], page["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals("spotify:concert:c1", page["variables"]["uri"].str)
        assertEquals("true", page["variables"]["authenticated"].toString())
    }

    @Test fun anArtistTheSearchDoesNotKnowHasNoUri() {
        answer("""{"data":{"searchV2":{"artists":{"items":[{"data":{"__typename":"Artist","uri":"spotify:artist:x0","profile":{"name":"Someone Else"}}}]}}}}""")
        assertNull(web.artistUri("Neon Harbour"))
    }

    @Test fun concertsInAnAnswerOfAnotherShapeAreAnErrorNotAnEmptyList() {
        answer("""{"data":{"concerts":{"concerts":{"items":[{"data":{"id":"renamed"}}]}}}}""")
        try { web.concerts("spotify:artist:a1"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("ArtistConcerts")) }
    }

    @Test fun topSongsInAnAnswerOfAnotherShapeAreAnErrorNotAnEmptyList() {
        answer("""{"data":{"me":{"profile":{"topTracks":{"__typename":"TrackPageV2","items":[{"data":{"__typename":"Track","title":"renamed field"}}]}}}}}""")
        try { web.top("SHORT_TERM"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("userTopContent")) }
    }
}

package com.xlollx.songport.ytmbridge

import android.app.Application
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Amazon Music's web player GraphQL endpoint, against a local mock server. Unofficial and
 * undocumented: the shapes are the ones the client reads (captured from the player, per its own
 * comments), so these tests pin the client's behaviour, not Amazon's. Refusals (401/403, auth
 * error codes) make the client run the real player in a WebView, which a JVM test cannot do: those
 * paths are left to the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AmazonClientContractTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private lateinit var amazon: AmazonClient
    private val cookies = "session-id=262-1234567-7654321; ubid-main=133-1; at-main=Atza|" + "x".repeat(40)

    @Before fun setUp() {
        BridgeMock.start()
        BridgeMock.fixed = { r ->
            if (r.getHeader(BridgeMock.ORIGINAL_HOST) == "music.amazon.it" && r.requestUrl!!.encodedPath == "/") MockResponse().setBody("""<html><script>
                window.amznMusic = window.amznMusic || {}; var x = "amznMusic.appConfig is set below";
                amznMusic.appConfig = {"accessToken":"Atna|tok","customerId":"A1CUSTOMER","deviceId":"13700000000000000","deviceType":"A16ZV8BU3SN1N3",
                  "csrf":{"token":"gCSRF+tok=","ts":1700000000,"rnd":"424242"},"displayName":"Ñandú {braces} \"q\""};
                </script></html>""") else null
        }
        app = RuntimeEnvironment.getApplication()
        AmazonSession.save(app, cookies, "music.amazon.it", null)
        AmazonClient.forgetConfig()
        amazon = AmazonClient(app)
    }

    @After fun tearDown() { BridgeMock.stop() }

    private fun call(i: Int): RecordedRequest = BridgeMock.calls[i]
    private fun RecordedRequest.json(): JsonElement = parseJson(body.clone().readUtf8())
    private fun answer(body: String, code: Int = 200) = BridgeMock.answer(body, code)

    private fun node(i: Int, title: String = "Song $i") = """{"__typename":"Track","id":"B0ASIN${i.toString().padStart(4, '0')}","title":${q("$title (Remastered)")},"shortTitle":${q(title)},
        "duration":${200 + i},"album":{"title":"Album (Deluxe)","shortTitle":"Album"},
        "contributingArtists":{"edges":[{"role":"FEATURED","node":{"name":"Guest"}},{"role":"PRIMARY","node":{"name":"Main $i"}}]},"parentalSettings":{"hasExplicitLanguage":${i % 2 == 0}}}"""
    private fun page(range: IntRange, next: String?) = """{"data":{"playlist":{"id":"PL1","title":"T","tracks":{"edges":[${range.joinToString(",") { """{"cursor":"$it:uuid-$it","node":${node(it)}}""" }}],
        "pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { q(it) } ?: "null"}}}}}}"""

    @Test fun aDutchAccountOnTheComDomainSendsItsOwnTerritory() {
        BridgeMock.fixed = { r ->
            if (r.getHeader(BridgeMock.ORIGINAL_HOST) == "music.amazon.com" && r.requestUrl!!.encodedPath == "/") MockResponse().setBody("""<html><script>
                amznMusic.appConfig = {"accessToken":"Atna|tok","customerId":"A1CUSTOMER","deviceId":"1","deviceType":"A16ZV8BU3SN1N3",
                  "marketplaceId":"A1805IZSGTT6HS","csrf":{"token":"t","ts":1,"rnd":"2"}};
                </script></html>""") else null
        }
        AmazonSession.save(app, cookies, "music.amazon.com", null)
        AmazonClient.forgetConfig()
        answer(page(0..0, null))
        AmazonClient(app).playlistTracks("PL1")
        assertEquals("NL", call(0).getHeader("music-territory"))
    }

    @Test fun playlistReadByCursorWithThePlayersHeaders() {
        answer(page(0..99, "99:uuid-99"))
        answer(page(100..100, null))
        val ts = amazon.playlistTracks("PL1")
        assertEquals(101, ts.size)
        assertEquals("100:uuid-100", ts[100].setVideoId)
        assertEquals(listOf("Main 0", "Guest"), ts[0].artists)
        assertEquals("Song 0", ts[0].title)
        assertEquals(200_000L, ts[0].durationMs)
        assertEquals(true, ts[0].explicit)
        val r = call(0)
        assertEquals("POST", r.method)
        assertEquals("gql.music.amazon.it", r.getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("amzn1.application.4ff5579ca2e3407aba989a1f5dbdaf69", r.getHeader("x-api-key"))
        assertEquals("gCSRF+tok=", r.getHeader("csrf-token"))
        assertEquals("1700000000", r.getHeader("csrf-ts"))
        assertEquals("424242", r.getHeader("csrf-rnd"))
        assertEquals("262-1234567-7654321", r.getHeader("x-amzn-session-id"))
        assertEquals("IT", r.getHeader("music-territory"))
        assertEquals("it-IT", r.getHeader("content-language"))
        assertEquals("https://music.amazon.it", r.getHeader("Origin"))
        assertTrue(r.getHeader("Cookie")!!.contains("at-main="))
        val b = r.json()
        assertEquals("playlistDetail", b["operationName"].str)
        assertEquals(64, b["extensions"]["persistedQuery"]["sha256Hash"].str!!.length)
        assertEquals("PL1", b["variables"]["id"].str)
        assertEquals("99:uuid-99", call(1).json()["variables"]["afterCursor"].str)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun duplicateIsNotAFailureButAnotherRefusalIs() {
        answer("""{"data":{"appendTracks":null},"errors":[{"message":"Track already in playlist","extensions":{"code":"DUPLICATE_TRACK"}}]}""", 294)
        // Not a failure, and not an addition either: the caller is told.
        assertEquals(false, amazon.addTrack("PL1", "B0ASIN0001"))
        answer("""{"data":{"appendTracks":{"id":"PL1"}}}""")
        assertEquals(true, amazon.addTrack("PL1", "B0ASIN0003"))
        answer("""{"data":{"appendTracks":null},"errors":[{"message":"Playlist is full","extensions":{"code":"PLAYLIST_LIMIT_EXCEEDED"}}]}""", 294)
        try { amazon.addTrack("PL1", "B0ASIN0002"); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("PLAYLIST_LIMIT_EXCEEDED")) }
        val v = call(0).json()["variables"]
        assertEquals("B0ASIN0001", v["trackId"].str)
        assertEquals("true", v["rejectDuplicateTracks"].toString())
    }

    @Test fun createPrivateRemoveEntryAndDelete() {
        answer("""{"data":{"createPlaylist":{"id":"PLNEW","title":"Ñandú 🎧"}}}""")
        answer("""{"data":{"removeTracks":{"id":"PLNEW"}}}""")
        answer("""{"data":{"deletePlaylist":{"isDeleted":true}}}""")
        assertEquals("PLNEW", amazon.createPlaylist("Ñandú 🎧").id)
        amazon.removeTrack("PLNEW", "3:uuid-3")
        amazon.removePlaylist("PLNEW")
        assertEquals("PRIVATE", call(0).json()["variables"]["visibility"].str)
        assertEquals("Ñandú 🎧", call(0).json()["variables"]["title"].str)
        assertEquals("3:uuid-3", call(1).json()["variables"]["cursor"].str)
        assertEquals("deletePlaylist", call(2).json()["operationName"].str)
    }

    @Test fun deleteNotConfirmedIsAnError() {
        answer("""{"data":{"deletePlaylist":{"isDeleted":false}}}""")
        try { amazon.removePlaylist("X"); fail() } catch (e: BridgeException) { }
    }

    @Test fun unknownPersistedQueryAsksForAnUpdate() {
        answer("""{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}""")
        try { amazon.libraryPlaylists(); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("needs an update")) }
        assertEquals(1, BridgeMock.calls.size)
    }

    /** Regression (11/11/2026): SpecSearchPage answered PERSISTED_QUERY_NOT_FOUND on every search. */
    @Test fun searchByHashThenWithItsTextWhenAmazonForgotIt() {
        val notFound = """{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}"""
        val found = """{"data":{"searchTracks":{"edgeCount":3,"edges":[{"node":${node(1)}},{"node":${node(1)}},{"node":${node(2, "Ñandú – Ünïcode")}}],"pageInfo":{"hasNextPage":true,"token":"t2"}}}}"""
        answer(notFound)
        answer(found)
        answer(found)
        val r = amazon.searchTracks("Metallica Metallica")
        // The same recording twice (two editions): listed once.
        assertEquals(listOf("Song 1", "Ñandú – Ünïcode"), r.map { it.title })
        assertEquals(listOf("Main 1", "Guest"), r[0].artists)
        assertEquals(201_000L, r[0].durationMs)
        val first = call(0).json()
        assertEquals("searchTracksResult", first["operationName"].str)
        assertEquals("Metallica Metallica", first["variables"]["query"].str)
        assertEquals(AmazonQueries.SEARCH_TRACKS_RESULT_HASH, first["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals(null, first["query"].str)
        val second = call(1).json()
        assertEquals(AmazonQueries.SEARCH_TRACKS_RESULT, second["query"].str)
        assertEquals(AmazonQueries.SEARCH_TRACKS_RESULT_HASH, second["extensions"]["persistedQuery"]["sha256Hash"].str)
        // From then on the text goes with the hash at once.
        amazon.searchTracks("Electric Callboy")
        assertEquals(3, BridgeMock.calls.size)
        assertEquals(AmazonQueries.SEARCH_TRACKS_RESULT, call(2).json()["query"].str)
    }

    @Test fun searchTextRefusedTooAsksForAnUpdate() {
        val notFound = """{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}"""
        answer(notFound)
        answer(notFound)
        try { amazon.searchTracks("x"); fail() } catch (e: BridgeException) { assertTrue(e.message, e.message!!.contains("needs an update")) }
        assertEquals(2, BridgeMock.calls.size)
    }

    /** The hash Amazon is sent is the SHA-256 of the text, or Amazon refuses the pair. */
    @Test fun searchHashIsTheTextsSha256() {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(AmazonQueries.SEARCH_TRACKS_RESULT.toByteArray())
        assertEquals(AmazonQueries.SEARCH_TRACKS_RESULT_HASH, sha.joinToString("") { "%02x".format(it) })
        assertTrue(AmazonQueries.SEARCH_TRACKS_RESULT.startsWith("query searchTracksResult(\$query: String!, \$nextPageToken: String) {\n"))
        assertTrue("@localOnly" !in AmazonQueries.SEARCH_TRACKS_RESULT)
    }

    // ---- Saved albums (11/2026): "Lo-files – Bring Me The Horizon" was proposed as the song "Lo Files"
    // by another artist, because an album was searched among songs.

    private fun album(id: String, title: String, artist: String, date: String) = """{"__typename":"Album","id":"$id","uri":"amzn:album:$id","title":${q(title)},
        "releaseDate":$date,"format":"ALBUM","parentalSettings":{"hasExplicitLanguage":false},
        "contributingArtists":{"concatenatedName":${q(artist)},"edges":[{"role":"PRIMARY","node":{"id":"A1","name":${q(artist)}}}]}}"""

    @Test fun albumsAreSearchedAmongAlbumsByHashThenWithTheText() {
        val notFound = """{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}"""
        answer(notFound)
        answer("""{"data":{"searchAlbums":{"edgeCount":4,"edges":[
            {"node":${album("B0ALB00001", "Lo-files", "Bring Me The Horizon", q("2025-05-23"))}},
            {"node":${album("B0ALB00001", "Lo-files", "Bring Me The Horizon", q("2025-05-23"))}},
            {"node":${node(9, "Lo Files")}},
            {"node":${album("B0ALB00002", "Vessels", "STARSET", "1484870400000")}}],
            "pageInfo":{"hasNextPage":false,"token":null}}}}""")
        val r = amazon.searchAlbums("Bring Me The Horizon Lo-files")
        // Albums only, each once: the song that shares the name is not one.
        assertEquals(listOf("B0ALB00001", "B0ALB00002"), r.map { it.id })
        assertEquals(listOf("album", "album"), r.map { it.kind })
        assertEquals(listOf("Bring Me The Horizon"), r[0].artists)
        assertEquals(2025, r[0].year)
        assertEquals(2017, r[1].year)
        val first = call(0).json()
        assertEquals("searchAlbumsResult", first["operationName"].str)
        assertEquals("Bring Me The Horizon Lo-files", first["variables"]["query"].str)
        assertEquals(AmazonQueries.SEARCH_ALBUMS_RESULT_HASH, first["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals(null, first["query"].str)
        assertEquals(AmazonQueries.SEARCH_ALBUMS_RESULT, call(1).json()["query"].str)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun theBridgeSearchesAlbumsForAnAlbumAndSongsOtherwise() {
        answer("""{"data":{"searchAlbums":{"edges":[{"node":${album("B0ALB00002", "Vessels", "STARSET", q("2017"))}}]}}}""")
        val albums = BridgeCore.call(app, "amazon.search", "STARSET Vessels", android.os.Bundle().apply { putString("kind", "album") })
        assertEquals(null, albums.getString("error"))
        val j = parseJson(albums.getString("json")!!)
        assertEquals("B0ALB00002", j.arr[0]["id"].str)
        assertEquals("album", j.arr[0]["kind"].str)
        assertEquals(2017L, j.arr[0]["year"].long)
        assertEquals("searchAlbumsResult", call(0).json()["operationName"].str)
        answer("""{"data":{"searchTracks":{"edges":[{"node":${node(1)}}]}}}""")
        BridgeCore.call(app, "amazon.search", "STARSET Vessels", null)
        assertEquals("searchTracksResult", call(1).json()["operationName"].str)
    }

    @Test fun releaseYearFromADateOrEpochTime() {
        fun y(s: String) = yearOf(parseJson(s))
        assertEquals(2025, y("\"2025-05-23\""))
        assertEquals(2017, y("1484870400000"))
        assertEquals(2017, y("1484870400"))
        assertEquals(2017, y("2017"))
        assertEquals(null, y("\"soon\""))
        assertEquals(null, y("null"))
    }

    /** The hash is the SHA-256 of the text; the @client fields are not in it, as Apollo does not send them. */
    @Test fun albumSearchHashIsTheTextsSha256() {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(AmazonQueries.SEARCH_ALBUMS_RESULT.toByteArray())
        assertEquals(AmazonQueries.SEARCH_ALBUMS_RESULT_HASH, sha.joinToString("") { "%02x".format(it) })
        assertTrue(AmazonQueries.SEARCH_ALBUMS_RESULT.startsWith("query searchAlbumsResult(\$query: String!, \$nextPageToken: String) {\n"))
        listOf("@client", "downloadStatus", "isInLibrary", "likeState", "itemAlbumDownload ", "itemConverterAlbumLibraryState").forEach {
            assertTrue(it, it !in AmazonQueries.SEARCH_ALBUMS_RESULT)
        }
    }

    @Test fun serverErrorIsAClearErrorWithoutRetrying() {
        answer("<html>Internal Server Error</html>", 500)
        try { amazon.libraryPlaylists(); fail() } catch (e: BridgeException) { assertTrue(e.message, e.message!!.startsWith("Amazon Music 500 on AddToPlaylistModalUserPlaylists")) }
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun libraryPlaylistsAndTheAccountName() {
        answer("""{"data":{"user":{"playlists":{"edges":[{"node":{"id":"P1","title":"Uno","trackCount":12}},{"node":{"id":"P2","title":null}},{"node":{}}]}}}}""")
        val ls = amazon.libraryPlaylists()
        assertEquals(listOf("P1" to 12, "P2" to -1), ls.map { it.id to it.count })
        assertEquals("P2", ls[1].name)
        assertEquals(500L, call(0).json()["variables"]["firstLimit"].long)
        assertEquals("Ñandú {braces} \"q\"", amazon.accountName())
    }

    // ---- The "Metal & More" report (1.2.7): two tracks added again on every run, "+2" each time,
    // the target count never growing. Amazon held them, but its listing gave them in a shape the
    // client dropped, so the sync took them for missing; Amazon answered DUPLICATE_TRACK, and that
    // was counted as added.

    /** Entries the player's listing describes only in part, or in another shape. */
    private val untitled = """{"__typename":"Track","id":"B0RCD00001","title":null,"shortTitle":null,"duration":null,"album":null,"contributingArtists":null}"""
    private val wrapped = """{"__typename":"PlaylistEntry","id":"entry-77","track":{"__typename":"Track","id":"B0RCD00002","title":"Bury a Lie",
        "contributingArtists":{"edges":[{"role":"PRIMARY","node":{"name":"Rain City Drive"}}]}}}"""
    private val asinOnly = """{"__typename":"UnavailableTrack","asin":"B0RCD00003"}"""

    private fun listing(vararg edges: Pair<String, String?>, next: String? = null, errors: String = "") =
        """{"data":{"playlist":{"id":"PL1","title":"Metal & More","tracks":{"edges":[${edges.joinToString(",") { (cursor, node) -> """{"cursor":"$cursor","node":${node ?: "null"}}""" }}],
        "pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { q(it) } ?: "null"}}}}}$errors}"""

    @Test fun entriesWithoutTitleOrInAnotherShapeAreListedById() {
        val logs = ArrayList<String>()
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
        try {
            // The library counts 6 tracks in the playlist, as the sync reads it just before the listing.
            answer("""{"data":{"user":{"playlists":{"edges":[{"node":{"id":"PL1","title":"Metal & More","trackCount":6}}]}}}}""")
            amazon.libraryPlaylists()
            answer(listing(
                "0:u0" to node(0), "1:u1" to untitled, "2:u2" to wrapped, "3:u3" to asinOnly, "4:u4" to null, "5:u5" to node(5),
                errors = ""","errors":[{"message":"Track not available","path":["playlist","tracks","edges",4,"node"],"extensions":{"code":"NOT_FOUND"}}]""",
            ))
            val ts = amazon.playlistTracks("PL1")
            assertEquals(listOf("B0ASIN0000", "B0RCD00001", "B0RCD00002", "B0RCD00003", "B0ASIN0005"), ts.map { it.id })
            // Untitled entries are there by id, with nothing a match by name could pick up.
            assertEquals("", ts[1].title)
            assertEquals("1:u1", ts[1].setVideoId)
            assertEquals("Bury a Lie", ts[2].title)
            assertEquals(listOf("Rain City Drive"), ts[2].artists)
            assertEquals("2:u2", ts[2].setVideoId)
            assertEquals("", ts[3].title)
            // What still does not add up is in the diagnostics log.
            val line = logs.single { it.startsWith("[amazon] playlist") }
            assertTrue(line, line.contains("5 entries listed, the library counts 6"))
            assertTrue(line, line.contains("2 without a title (kept by id)"))
            assertTrue(line, line.contains("1 without an id (left out)"))
            assertTrue(line, line.contains("NOT_FOUND"))
        } finally { BridgeNet.logSink = null }
    }

    // ---- The "Metal & More" report (1.3.3): "-1" on every run, the target staying at 1141. Amazon
    // answers removeTrackFromPlaylist with the playlist's id whether or not the entry went.

    private val removed = """{"data":{"removeTracks":{"id":"PL1"}}}"""

    @Test fun aRemovalIsCheckedAgainstThePlaylistAndAnEntryStillListedIsReported() {
        AmazonClient.removePauseMs = 0
        val logs = ArrayList<String>()
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
        try {
            answer(removed)
            // Listed again with the same cursor: removing did nothing.
            answer(listing("0:u0" to node(0), "1:u1" to node(1, "Stuck")))
            val kept = amazon.removeEntries("PL1", listOf("B0ASIN0001"), listOf("1:u1"))
            assertEquals(setOf("B0ASIN0001"), kept)
            assertEquals("1:u1", call(0).json()["variables"]["cursor"].str)
            assertEquals("playlistDetail", call(1).json()["operationName"].str)
            assertEquals(2, BridgeMock.calls.size)
            val line = logs.single { it.contains("still listed:") }
            assertTrue(line, line.contains("1 of 1 entries still listed: Main 1 – Stuck (removed with cursor index 1, now cursor index 1 at position 1)"))
            assertTrue(line, !line.contains("B0ASIN0001"))
        } finally { BridgeNet.logSink = null }
    }

    @Test fun anEntryStillListedUnderAnotherCursorIsRemovedOnceMoreWithIt() {
        AmazonClient.removePauseMs = 0
        answer(removed)
        answer(listing("0:u0" to node(0), "7:fresh" to node(1, "Stuck")))
        answer(removed)
        answer(listing("0:u0" to node(0)))
        val kept = amazon.removeEntries("PL1", listOf("B0ASIN0001"), listOf("1:stale"))
        assertTrue(kept.toString(), kept.isEmpty())
        assertEquals("1:stale", call(0).json()["variables"]["cursor"].str)
        assertEquals("7:fresh", call(2).json()["variables"]["cursor"].str)
        assertEquals(4, BridgeMock.calls.size)
    }

    @Test fun aRemovalThatTookIsReadOnceAndReportsNothing() {
        AmazonClient.removePauseMs = 0
        answer(removed); answer(removed)
        answer(listing("0:u0" to node(0)))
        val kept = amazon.removeEntries("PL1", listOf("B0ASIN0002", "B0ASIN0005"), listOf("2:u2", "5:u5"))
        assertTrue(kept.isEmpty())
        // Highest index first.
        assertEquals("5:u5", call(0).json()["variables"]["cursor"].str)
        assertEquals("2:u2", call(1).json()["variables"]["cursor"].str)
        assertEquals(3, BridgeMock.calls.size)
    }

    @Test fun aWellFormedListingThatMatchesTheLibraryLogsNothing() {
        val logs = ArrayList<String>()
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
        try {
            answer("""{"data":{"user":{"playlists":{"edges":[{"node":{"id":"PL1","title":"T","trackCount":2}}]}}}}""")
            amazon.libraryPlaylists()
            answer(page(0..1, null))
            assertEquals(2, amazon.playlistTracks("PL1").size)
            assertTrue(logs.toString(), logs.none { it.startsWith("[amazon] playlist") })
        } finally { BridgeNet.logSink = null }
    }

    @Test fun aPageWithoutEndCursorIsFollowedFromItsLastEntry() {
        answer("""{"data":{"playlist":{"id":"PL1","tracks":{"edges":[${(0..99).joinToString(",") { """{"cursor":"$it:uuid-$it","node":${node(it)}}""" }}],
            "pageInfo":{"hasNextPage":true,"endCursor":null}}}}}""")
        answer(page(100..101, null))
        val ts = amazon.playlistTracks("PL1")
        assertEquals(102, ts.size)
        assertEquals("99:uuid-99", call(1).json()["variables"]["afterCursor"].str)
    }

    @Test fun theBridgeSaysWhichTracksAmazonAlreadyHad() {
        // The playlist holds B0RCD00001 and B0RCD00002 but lists them in a shape the client dropped:
        // the sync asks to add them again, and Amazon refuses them as duplicates.
        answer("""{"data":{"appendTracks":null},"errors":[{"message":"Track already in playlist","extensions":{"code":"DUPLICATE_TRACK"}}]}""", 294)
        answer("""{"data":{"appendTracks":{"id":"PL1"}}}""")
        answer("""{"data":{"appendTracks":null},"errors":[{"message":"Track already in playlist","extensions":{"code":"DUPLICATE_TRACK"}}]}""", 294)
        val b = BridgeCore.call(app, "amazon.add", "PL1", android.os.Bundle().apply {
            putStringArray("ids", arrayOf("B0RCD00001", "B0NEW00001", "B0RCD00002"))
            putStringArray("titles", arrayOf("Talk to a Friend", "New", "Bury a Lie"))
        })
        assertEquals(null, b.getString("error"))
        assertEquals(listOf("B0RCD00001", "B0RCD00002"), b.getStringArray("duplicates")!!.toList())
        assertEquals(3, BridgeMock.calls.size)
    }

    // ---- saved albums: LikedAlbumsPage and setAlbumLikeState, in the shapes of the player's traffic (made-up albums)

    /** A saved album as LikedAlbumsPage lists it; [state] null: no likesContext at all. */
    private fun liked(id: String, title: String, artist: String, state: String? = "LIKE") = """{"node":{"id":"$id","__typename":"Album",
        "contributingArtists":{"edges":[{"role":"PRIMARY","node":{"name":${q(artist)},"__typename":"Artist","id":"ART$id"}}],"__typename":"ArtistConnection"},
        "title":${q(title)},"shortTitle":${q(title)},"uri":"amzn:album:$id","parentalSettings":{"hasExplicitLanguage":true},
        "likesContext":${state?.let { """{"state":"$it","__typename":"LikesContext"}""" } ?: "null"}},"__typename":"UserAlbumLikeEdge"}"""

    private fun likedPage(edges: List<String>, next: Boolean, token: String? = "page-token") = """{"data":{"user":{"id":"U1","likedAlbums":{
        "edges":[${edges.joinToString(",")}],
        "pageInfo":{"hasNextPage":$next,"endCursor":null,"token":${token?.let { q(it) } ?: "null"},"hasPreviousPage":null,"startCursor":null},
        "__typename":"UserAlbumLikeConnection"},"__typename":"User"}}}"""

    private fun likeAnswer(id: String, state: String) = """{"data":{"setAlbumLikeState":{"edges":[{"node":{"id":"$id","__typename":"Album",
        "uri":"amzn:album:$id","likesContext":{"state":"$state"}}}],"__typename":"SetAlbumLikeStateResponse"}}}"""

    @Test fun savedAlbumsAreTheLikedOnesAsTheLibraryListsThem() {
        answer(likedPage(listOf(
            liked("B0ALB00011", "Unliked a moment ago", "Band", state = "NEUTRAL"),
            liked("B0ALB00012", "Lo-files", "Bring Me The Horizon"),
            """{"node":null,"__typename":"UserAlbumLikeEdge"}""",
            liked("B0ALB00013", "Vessels", "STARSET", state = null),
        ), next = false))
        val r = amazon.savedAlbums()
        assertEquals(listOf("B0ALB00012", "B0ALB00013"), r.map { it.id })
        assertEquals(listOf("album", "album"), r.map { it.kind })
        assertEquals("Lo-files", r[0].title)
        assertEquals(listOf("Bring Me The Horizon"), r[0].artists)
        assertEquals(true, r[0].explicit)
        val b = call(0).json()
        assertEquals("LikedAlbumsPage", b["operationName"].str)
        assertEquals("ac0f3868a56af3b23191e0f173701c3bf0ffa71df79001cfbc9d00dcd144cc97", b["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals(null, b["query"].str)
        val v = b["variables"]
        assertEquals("true", v["isAuthenticated"].toString())
        assertEquals(100L, v["firstLimit"].long)
        // The first page as the player asks for it: no continuation.
        assertEquals(null, v["afterCursor"])
        assertEquals(1, BridgeMock.calls.size)
    }

    @Test fun savedAlbumsPageByPageWithTheTokenAsAfterCursor() {
        answer(likedPage((1..100).map { liked("B0ALB1%04d".format(it), "Album $it", "Band") }, next = true, token = "tok-1"))
        answer(likedPage((101..120).map { liked("B0ALB1%04d".format(it), "Album $it", "Band") }, next = false, token = "tok-2"))
        val r = amazon.savedAlbums()
        assertEquals(120, r.size)
        assertEquals("B0ALB10120", r.last().id)
        assertEquals("tok-1", call(1).json()["variables"]["afterCursor"].str)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun aContinuationAmazonIgnoresStopsTheListingInsteadOfLooping() {
        val first = likedPage((1..100).map { liked("B0ALB2%04d".format(it), "Album $it", "Band") }, next = true, token = "tok-a")
        answer(first)
        answer(first.replace("tok-a", "tok-b"))
        answer(first.replace("tok-a", "tok-c"))
        val r = amazon.savedAlbums()
        assertEquals(100, r.size)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun anAlbumIsSavedAndRemovedWithItsLikeState() {
        answer(likeAnswer("B0ALB00021", "LIKE"))
        answer(likeAnswer("B0ALB00021", "NEUTRAL"))
        assertEquals(true, amazon.setAlbumSaved("B0ALB00021", saved = true))
        assertEquals(true, amazon.setAlbumSaved("B0ALB00021", saved = false))
        val save = call(0).json()
        assertEquals("setAlbumLikeState", save["operationName"].str)
        assertEquals("fa2452606c04ef941d892c890d4dc9b0025071dd0801b55026a3981b13140de6", save["extensions"]["persistedQuery"]["sha256Hash"].str)
        assertEquals("B0ALB00021", save["variables"]["albumId"].str)
        assertEquals("POSITIVE", save["variables"]["likeState"].str)
        assertEquals("true", save["variables"]["isAuthenticated"].toString())
        assertEquals("NEUTRAL", call(1).json()["variables"]["likeState"].str)
        // Another state in the answer: not done. No album in it: an error.
        answer(likeAnswer("B0ALB00022", "NEUTRAL"))
        assertEquals(false, amazon.setAlbumSaved("B0ALB00022", saved = true))
        answer("""{"data":{"setAlbumLikeState":null},"errors":[{"message":"Not an album","extensions":{"code":"INVALID_INPUT"}}]}""", 294)
        try { amazon.setAlbumSaved("B0TRK00001", saved = true); fail() } catch (e: BridgeException) { assertTrue(e.message!!.contains("INVALID_INPUT")) }
    }

    @Test fun theBridgeListsSavesAndRemovesSavedAlbums() {
        val albums = BridgeCore.ALBUMS
        answer(likedPage(listOf(liked("B0ALB00031", "Vessels", "STARSET")), next = false))
        val listed = BridgeCore.call(app, "amazon.tracks", albums, null)
        assertEquals(null, listed.getString("error"))
        val j = parseJson(listed.getString("json")!!)
        assertEquals("B0ALB00031", j.arr[0]["id"].str)
        assertEquals("album", j.arr[0]["kind"].str)

        answer(likeAnswer("B0ALB00032", "LIKE"))
        answer(likeAnswer("B0ALB00033", "LIKE"))
        val added = BridgeCore.call(app, "amazon.add", albums, android.os.Bundle().apply {
            putStringArray("ids", arrayOf("B0ALB00032", "B0ALB00033"))
            putStringArray("titles", arrayOf("Lo-files", "Vessels"))
        })
        assertEquals(null, added.getString("error"))
        assertEquals(emptyList<String>(), added.getStringArray("duplicates")!!.toList())
        assertEquals(listOf("POSITIVE", "POSITIVE"), (1..2).map { call(it).json()["variables"]["likeState"].str })

        // One Amazon leaves liked comes back as kept: the sync counts it as not removed.
        answer(likeAnswer("B0ALB00032", "NEUTRAL"))
        answer(likeAnswer("B0ALB00033", "LIKE"))
        val removed = BridgeCore.call(app, "amazon.remove", albums, android.os.Bundle().apply {
            putStringArray("ids", arrayOf("B0ALB00032", "B0ALB00033"))
            putStringArray("entryIds", arrayOf("", ""))
        })
        assertEquals(null, removed.getString("error"))
        assertEquals(listOf("B0ALB00033"), removed.getStringArray("kept")!!.toList())
        assertEquals(listOf("NEUTRAL", "NEUTRAL"), (3..4).map { call(it).json()["variables"]["likeState"].str })

        // An album Amazon does not save fails the call: never counted as added.
        answer(likeAnswer("B0ALB00034", "NEUTRAL"))
        val refused = BridgeCore.call(app, "amazon.add", albums, android.os.Bundle().apply { putStringArray("ids", arrayOf("B0ALB00034")) })
        assertTrue(refused.getString("error")!!.contains("did not save 1 of 1"))
        assertEquals(6, BridgeMock.calls.size)
    }

    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
}

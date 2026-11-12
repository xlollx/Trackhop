package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.sync.Reorder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TIDAL's liked tracks (My Collection › Tracks: userCollectionTracks) and its playlist moves (PATCH
 * /playlists/{id}/relationships/items), shapes per the Open API spec 1.10.251; not checked against
 * the live service here.
 */
class TidalLikedContractTest : ContractTest() {
    private val td = TidalProvider()
    private val api = "openapi.tidal.com"
    private val vnd = "application/vnd.api+json"
    private val liked = "/v2/userCollectionTracks/me/relationships/items"

    @org.junit.After fun v2Off() { com.xlollx.songport.Features.build = false }

    @Before fun signIn() {
        BuildDefaults.values = mapOf("TIDAL_CLIENT_ID" to "tcid")
        env.tokens.set("tidal", Tokens("TT1", "TR1", userId = "u1", userName = "me", extra = mapOf("country" to "IT")))
    }

    private fun inc(i: Int) =
        """{"id":"${100 + i}","type":"tracks","attributes":{"title":"Song $i","isrc":"GBAAA${i.toString().padStart(7, '0')}","duration":"PT3M0S","availability":["STREAM"]},
           "relationships":{"artists":{"data":[{"id":"7","type":"artists"}]}}}"""

    @Test fun likedIsASourceAndATarget() {
        // TIDAL's liked tracks are 1.18's (Features): not offered without it, still read and written for a sync that has them.
        com.xlollx.songport.Features.build = false
        assertTrue(td.libraryEntries(env, asTarget = false).none { it.id == MusicProvider.LIKED_ID })
        assertTrue(td.libraryEntries(env, asTarget = true).none { it.id == MusicProvider.LIKED_ID })
        assertTrue(td.supportsLibrary(MusicProvider.LIKED_ID, asTarget = true))
        com.xlollx.songport.Features.build = true
        assertTrue(td.libraryEntries(env, asTarget = false).any { it.id == MusicProvider.LIKED_ID })
        assertTrue(td.libraryEntries(env, asTarget = true).any { it.id == MusicProvider.LIKED_ID })
    }

    @Test fun likedTracksWithTheirLikeDatesPageAfterPage() = runBlocking {
        json("""{"data":[{"id":"101","type":"tracks","meta":{"addedAt":"2026-01-02T03:04:05Z"}},{"id":"v9","type":"videos","meta":{"addedAt":"2026-01-01T00:00:00Z"}}],
            "included":[${inc(1)}],"links":{"self":"x","next":"/userCollectionTracks/me/relationships/items?include=items&page%5Bcursor%5D=c2"}}""")
        json("""{"data":[{"id":"102","type":"tracks","meta":{"addedAt":"2025-01-01T00:00:00Z"}}],"included":[${inc(2)}],"links":{"self":"x"}}""")
        json("""{"data":[{"id":"7","type":"artists","attributes":{"name":"Måneskin"}}]}""")
        val ts = td.tracks(env, MusicProvider.LIKED_ID)
        assertEquals(listOf("101", "102"), ts.map { it.id })
        assertEquals(java.time.Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), ts[0].addedAt)
        assertEquals(listOf("Måneskin"), ts[1].artists)
        val r = next()
        assertCall(r, "GET", api, liked)
        // The collection paths take no countryCode (the API refuses a parameter it does not list).
        assertNull(r.query("countryCode"))
        assertEquals("items", r.query("include"))
        assertEquals("Bearer TT1", r.getHeader("Authorization"))
        assertEquals("c2", next().query("page[cursor]"))
        assertCall(next(), "GET", api, "/v2/artists")
        noMoreRequests()
    }

    @Test fun likedVersionIsTheCountAndTheLastChange() = runBlocking {
        json("""{"data":{"id":"u1","type":"userCollectionTracks","attributes":{"numberOfItems":12,"lastModifiedAt":"2026-11-01T00:00:00Z"}},"links":{"self":"x"}}""")
        assertEquals("12|2026-11-01T00:00:00Z", td.playlistVersion(env, MusicProvider.LIKED_ID))
        assertCall(next(), "GET", api, "/v2/userCollectionTracks/me")
    }

    @Test fun likedVersionNotAnsweredIsUnknown() = runBlocking {
        json("""{"errors":[{"status":"500","detail":"boom"}]}""", 500)
        assertNull(td.playlistVersion(env, MusicProvider.LIKED_ID))
    }

    @Test fun likesCarryTheSourceDatesAndReportTheOnesAlreadyLiked() = runBlocking {
        json("""{"data":[{"id":"1","type":"tracks","meta":{"addedAt":"2024-05-01T12:00:00Z"}}],"links":{"self":"x"},
            "meta":{"skipped":[{"id":"2","type":"tracks","reason":"ALREADY_PRESENT"},{"id":"3","type":"tracks","reason":"NOT_FOUND"}]}}""", 201)
        val at = java.time.Instant.parse("2024-05-01T12:00:00.123Z").toEpochMilli()
        val present = td.addTracksReportingPresent(env, MusicProvider.LIKED_ID, listOf(Track("1", "a", addedAt = at), Track("2", "b"), Track("3", "c")))
        assertEquals(setOf("2"), present)
        val r = next()
        assertCall(r, "POST", api, liked)
        assertNull(r.query("countryCode"))
        assertEquals(vnd, r.getHeader("Content-Type")?.substringBefore(';'))
        val d = r.jsonBody()["data"].arr
        assertEquals(listOf("1", "2", "3"), d.map { it["id"].str })
        assertTrue(d.all { it["type"].str == "tracks" })
        assertEquals("2024-05-01T12:00:00Z", d[0]["meta"]["addedAt"].str)
        assertNull(d[1]["meta"])
        noMoreRequests()
    }

    @Test fun likesGoTwentyARequest() = runBlocking {
        repeat(2) { json("""{"data":[],"links":{"self":"x"}}""", 201) }
        td.addTracks(env, MusicProvider.LIKED_ID, (1..21).map { Track("$it", "t") })
        assertEquals(20, next().jsonBody()["data"].arr.size)
        assertEquals(1, next().jsonBody()["data"].arr.size)
        noMoreRequests()
    }

    @Test fun likeDatesRefusedLikeWithoutThem() = runBlocking {
        json("""{"errors":[{"status":"400","code":"VALUE_REGEX_MISMATCH","detail":"addedAt"}]}""", 400)
        json("""{"data":[],"links":{"self":"x"}}""", 201)
        json("""{"data":[],"links":{"self":"x"}}""", 201)
        td.addTracks(env, MusicProvider.LIKED_ID, (1..21).map { Track("$it", "t", addedAt = 1_700_000_000_000) })
        assertTrue(next().jsonBody()["data"][0]["meta"] != null)
        assertNull(next().jsonBody()["data"][0]["meta"])
        // Once refused, the dates are not sent again in this run.
        assertNull(next().jsonBody()["data"][0]["meta"])
        assertEquals(1, env.logs.count { "like dates" in it })
        noMoreRequests()
    }

    @Test fun unlikeByTrackId() = runBlocking {
        text("", 204, vnd)
        td.removeTracks(env, MusicProvider.LIKED_ID, listOf(Track("1", "a"), Track("2", "b")))
        val r = next()
        assertCall(r, "DELETE", api, liked)
        assertEquals(listOf("1", "2"), r.jsonBody()["data"].arr.map { it["id"].str })
        assertNull(r.jsonBody()["data"][0]["meta"])
    }

    // ---- moves

    private val cur = listOf(Track("1", "a", itemId = "i1"), Track("2", "b", itemId = "i2"), Track("3", "c", itemId = "i3"), Track("1", "a", itemId = "i4"))

    /** Applies the PATCH requests made, as TIDAL would: each named entry goes before positionBefore, or to the end. */
    private fun replay(start: List<String>, moves: Int): List<String> {
        val list = start.toMutableList()
        repeat(moves) {
            val r = next()
            assertCall(r, "PATCH", api, "/v2/playlists/P/relationships/items")
            val b = r.jsonBody()
            val data = b["data"].arr.single()
            assertEquals("tracks", data["type"].str)
            val item = data["meta"]["itemId"].str!!
            assertEquals(cur.first { it.itemId == item }.id, data["id"].str)
            val before = b["meta"]["positionBefore"].str
            list.remove(item)
            if (before == null) list.add(item) else list.add(list.indexOf(before), item)
        }
        return list
    }

    @Test fun movesPutTheEntriesInOrderWithTheFewestRequests() = runBlocking {
        repeat(2) { text("", 202, vnd) }
        val wanted = listOf(cur[2], cur[0], cur[3], cur[1])
        withContext(MusicProvider.OrderedAdds) { td.reorderTracks(env, "P", cur, wanted) }
        assertEquals(wanted.map { it.itemId }, replay(cur.map { it.itemId!! }, 2))
        noMoreRequests()
    }

    @Test fun aMoveToTheEndHasNoPosition() = runBlocking {
        text("", 202, vnd)
        val wanted = listOf(cur[1], cur[2], cur[3], cur[0])
        withContext(MusicProvider.OrderedAdds) { td.reorderTracks(env, "P", cur, wanted) }
        val r = next()
        assertNull(r.jsonBody()["meta"])
        noMoreRequests()
    }

    @Test fun noMoveOutsideASyncThatKeepsTheOrder() = runBlocking {
        try { td.reorderTracks(env, "P", cur, cur.reversed()); fail() } catch (_: Reorder.Skipped) {}
        noMoreRequests()
    }

    @Test fun aRefusedMoveLeavesTheOrderAsItIs() = runBlocking {
        json("""{"errors":[{"status":"403","detail":"not yours"}]}""", 403)
        try {
            withContext(MusicProvider.OrderedAdds) { td.reorderTracks(env, "P", cur, cur.reversed()) }
            fail()
        } catch (e: Reorder.Skipped) {
            assertTrue(e.message, e.message!!.contains("not yours"))
        }
    }
}

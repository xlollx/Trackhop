package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.PlexProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plex (2.2): five stars as liked songs with a register, sonic analysis, copies for the server's users. */
class PlexContractTest : ContractTest() {
    private val plex = PlexProvider()
    private val host = "plex.lan"

    @Before fun signIn() {
        env.tokens.set("plex", Tokens("OWNER", userName = "plex.lan:32400", extra = mapOf("url" to "https://plex.lan:32400", "machine" to "MACH")))
    }

    private fun track(k: Int, rating: Int? = null) =
        """{"ratingKey":"$k","title":"Song $k","grandparentTitle":"Band","parentTitle":"Album","duration":200000${rating?.let { ",\"userRating\":$it" } ?: ""}}"""

    @Test fun likedSongsAreTheFiveStarOnes() = runBlocking {
        json("""{"MediaContainer":{"Directory":[{"key":"3","type":"artist"},{"key":"4","type":"movie"}]}}""")
        json("""{"MediaContainer":{"Metadata":[${track(1, 10)},${track(2, 10)}]}}""")
        assertEquals(listOf("1", "2"), plex.tracks(env, MusicProvider.LIKED_ID).map { it.id })
        next()
        val r = next()
        assertCall(r, "GET", host, "/library/sections/3/all")
        assertEquals("10", r.query("type")); assertEquals("10", r.query("userRating"))
        noMoreRequests()
    }

    /** Liking rates five stars and is registered; unliking clears only what Trackhop rated. */
    @Test fun unlikingClearsOnlyTheStarsTrackhopGave() = runBlocking {
        json("{}")
        plex.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("7", "Song 7")))
        val rate = next()
        assertCall(rate, "PUT", host, "/:/rate")
        assertEquals("7", rate.query("key")); assertEquals("10", rate.query("rating"))
        json("{}")
        val kept = plex.removeTracksReportingKept(env, MusicProvider.LIKED_ID, listOf(Track("7", "Song 7"), Track("8", "Rated by hand")))
        assertEquals(setOf("8"), kept)
        assertEquals("-1", next().query("rating"))
        noMoreRequests()
        // Gone from the register: a second unlike does nothing.
        assertEquals(setOf("7"), plex.removeTracksReportingKept(env, MusicProvider.LIKED_ID, listOf(Track("7", "Song 7"))))
        noMoreRequests()
    }

    @Test fun soundsLikeAndSonicAdventure() = runBlocking {
        json("""{"MediaContainer":{"Metadata":[${track(1)},${track(5)},${track(6)}]}}""")
        assertEquals(listOf("5", "6"), plex.soundsLike(env, "1", limit = 10).map { it.id })
        val r = next()
        assertCall(r, "GET", host, "/library/metadata/1/nearest")
        assertEquals("10", r.query("limit"))
        json("""{"MediaContainer":{"Metadata":[{"ratingKey":"1","librarySectionID":3}]}}""")
        json("""{"MediaContainer":{"Metadata":[${track(1)},${track(4)},${track(9)}]}}""")
        assertEquals(listOf("1", "4", "9"), plex.sonicAdventure(env, "1", "9").map { it.id })
        next()
        val p = next()
        assertCall(p, "GET", host, "/library/sections/3/computePath")
        assertEquals("1", p.query("startID")); assertEquals("9", p.query("endID"))
    }

    @Test fun theServersUsersAreReadFromPlexTv() {
        val xml = """<?xml version="1.0"?><MediaContainer><SharedServer id="11" username="giulia" email="g@x" userID="501" accessToken="TOKG" name="Giulia &amp; co"/>
            <SharedServer id="12" username="" userID="502" accessToken="TOKK" name="Kids"></SharedServer><SharedServer id="13" userID="503" name="No token"/></MediaContainer>"""
        val users = PlexProvider.sharedServers(xml)
        assertEquals(listOf("501" to "Giulia & co", "502" to "Kids"), users.map { it.id to it.name })
    }

    /** A copy for a user is made with the token that acts as them, with the same songs. */
    @Test fun aPlaylistIsCopiedAsTheUser() = runBlocking {
        text("""<MediaContainer><SharedServer userID="502" accessToken="TOKK" name="Kids"/></MediaContainer>""", 200, "application/xml")
        json("""{"MediaContainer":{"Metadata":[{"ratingKey":"40","title":"Road trip","leafCount":2}]}}""")
        json("""{"MediaContainer":{"Metadata":[${track(1)},${track(2)}]}}""")
        json("""{"MediaContainer":{"Metadata":[{"ratingKey":"90"}]}}""")
        json("{}")
        val made = plex.copyToUser(env, "40", "502")
        assertEquals("90", made.id)
        val tv = next()
        assertCall(tv, "GET", "plex.tv", "/api/servers/MACH/shared_servers")
        assertEquals("OWNER", tv.getHeader("X-Plex-Token"))
        next(); next()
        val create = next()
        assertCall(create, "POST", host, "/playlists")
        assertEquals("TOKK", create.getHeader("X-Plex-Token"))
        assertEquals("Road trip", create.query("title"))
        val add = next()
        assertCall(add, "PUT", host, "/playlists/90/items")
        assertEquals("TOKK", add.getHeader("X-Plex-Token"))
        assertTrue(add.query("uri")!!.endsWith("/library/metadata/1,2"))
        noMoreRequests()
    }
}

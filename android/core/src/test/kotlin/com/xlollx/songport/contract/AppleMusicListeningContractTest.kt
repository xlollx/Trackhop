package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Listening
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Apple Music's favorites (POST /v1/me/favorites), recently played (/v1/me/recent/played/tracks) and
 * Replay (/v1/me/music-summaries), shapes per developer.apple.com/documentation/applemusicapi.
 */
class AppleMusicListeningContractTest : ContractTest() {
    private val am = AppleMusicProvider()
    private val api = "api.music.apple.com"

    @Before fun signIn() {
        BuildDefaults.values = mapOf("APPLE_DEVELOPER_TOKEN" to "DEVJWT")
        env.tokens.set("apple", Tokens("MUT", userName = "Apple Music", extra = mapOf("storefront" to "it")))
    }

    private fun song(i: Int, name: String = "Song $i") =
        """{"id":"${900 + i}","type":"songs","attributes":{"name":${q(name)},"artistName":"Artist ${i % 2}","albumName":"Album","durationInMillis":200000,
           "isrc":"USAAA${i.toString().padStart(7, '0')}","releaseDate":"2020-01-01","artwork":{"url":"https://is1.mzstatic.com/a$i/{w}x{h}bb.jpg"}}}"""
    private fun lib(i: Int) =
        """{"id":"i.lib$i","type":"library-songs","attributes":{"name":"Lib $i","artistName":"Artist L","playParams":{"id":"i.lib$i","kind":"song","catalogId":"${700 + i}"}}}"""

    @Test fun favoritesAreALikedTargetOnly() {
        assertTrue(am.libraryEntries(env, asTarget = true).any { it.id == MusicProvider.LIKED_ID })
        assertFalse(am.libraryEntries(env, asTarget = false).any { it.id == MusicProvider.LIKED_ID })
        assertTrue(am.libraryEntries(env, asTarget = false).any { it.id == MusicProvider.RECENT_ID })
    }

    @Test fun favoritesAreAddedTwentyFiveARequestAndKeptOnTheDevice() = runBlocking {
        repeat(2) { text("", 202, "application/json") }
        am.addTracks(env, MusicProvider.LIKED_ID, (1..26).map { Track("$it", "Song $it", listOf("A", "B")) })
        val r = next()
        assertCall(r, "POST", api, "/v1/me/favorites")
        assertEquals((1..25).joinToString(","), r.query("ids[songs]"))
        assertEquals("Bearer DEVJWT", r.getHeader("Authorization"))
        assertEquals("MUT", r.getHeader("Music-User-Token"))
        assertEquals("26", next().query("ids[songs]"))
        noMoreRequests()
        // Apple lists no favorites: the target reads what this device sent, the latest first.
        val listed = am.tracks(env, MusicProvider.LIKED_ID)
        assertEquals((26 downTo 1).map { "$it" }, listed.map { it.id })
        assertEquals(listOf("A", "B"), listed.first().artists)
        assertEquals("Song 26", listed.first().title)
        noMoreRequests()
        // Sent again: kept once.
        text("", 202, "application/json")
        am.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("3", "Song 3")))
        next()
        assertEquals(26, am.tracks(env, MusicProvider.LIKED_ID).size)
        assertEquals(emptyList<Any>(), am.unplayable(env, MusicProvider.LIKED_ID))
    }

    @Test fun recentlyPlayedLatestFirstOnceEach() = runBlocking {
        json("""{"next":"/v1/me/recent/played/tracks?offset=30","data":[${song(1)},${lib(2)},${song(1)}]}""")
        json("""{"data":[${song(3)}]}""")
        val ts = am.tracks(env, MusicProvider.RECENT_ID)
        assertEquals(listOf("901", "702", "903"), ts.map { it.id })
        val r = next()
        assertCall(r, "GET", api, "/v1/me/recent/played/tracks")
        assertEquals("songs,library-songs", r.query("types"))
        assertEquals("30", r.query("limit"))
        assertEquals("MUT", r.getHeader("Music-User-Token"))
        assertEquals("30", next().query("offset"))
        noMoreRequests()
    }

    @Test fun recentPlaysForTheWrappedWithTheirCovers() = runBlocking {
        json("""{"data":[${song(1)},${song(2)}]}""")
        val r = am.listening(env, Listening.Period.RECENT)
        assertEquals(listOf("Song 1", "Song 2"), r.tracks.map { it.track.title })
        assertEquals("https://is1.mzstatic.com/a1/300x300bb.jpg", r.tracks[0].cover)
        assertEquals(listOf("Artist 1", "Artist 0"), r.artists.map { it.name })
    }

    @Test fun replayRanksTheLatestYear() = runBlocking {
        json("""{"data":[{"id":"ms-2026","type":"music-summaries","attributes":{"period":"year","year":2026},"views":{
            "top-songs":{"href":"x","data":[
                {"id":"s1","type":"song-period-summaries","relationships":{"song":{"data":[${song(5, "Best")}]}}},
                {"id":"s2","type":"song-period-summaries","relationships":{"song":{"data":[{"id":"906","type":"songs"}]}}}]},
            "top-artists":{"href":"y","data":[{"id":"a1","type":"artist-period-summaries","relationships":{"artist":{"data":[{"id":"1","type":"artists","attributes":{"name":"Top Artist"}}]}}}]}}}]}""")
        // A song named without its attributes: read from the catalogue by id.
        json("""{"data":[${song(6, "Second")}]}""")
        val r = am.listening(env, Listening.Period.YEAR)
        assertEquals(listOf("Best", "Second"), r.tracks.map { it.track.title })
        assertEquals(listOf("Top Artist"), r.artists.map { it.name })
        assertEquals(2020, r.tracks[0].track.year)
        val q = next()
        assertCall(q, "GET", api, "/v1/me/music-summaries")
        assertEquals("latest", q.query("filter[year]"))
        assertEquals("top-songs,top-artists", q.query("views"))
        val c = next()
        assertCall(c, "GET", api, "/v1/catalog/it/songs")
        assertEquals("906", c.query("ids"))
        noMoreRequests()
    }

    @Test fun replayRefusedTheRecentPlaysInstead() = runBlocking {
        json("""{"errors":[{"status":"404","detail":"No summaries"}]}""", 404)
        json("""{"data":[${song(1)}]}""")
        val r = am.listening(env, Listening.Period.YEAR)
        assertEquals(listOf("Song 1"), r.tracks.map { it.track.title })
        assertEquals(1, env.logs.count { "Replay not read" in it })
    }

    @Test fun nothingAnsweredIsUnavailableAndLoggedOnce() = runBlocking {
        json("""{"errors":[{"status":"500","detail":"down"}]}""", 500)
        try { am.listening(env, Listening.Period.RECENT); fail() } catch (_: Listening.Unavailable) {}
        assertEquals(1, env.logs.count { "recent plays" in it })
    }

    @Test fun appleIsOfferedButNeverChosenOnItsOwn() {
        assertNull(Listening.choose(listOf(Listening.Source.APPLE), null))
        assertEquals(Listening.Source.APPLE, Listening.choose(listOf(Listening.Source.APPLE), Listening.Source.APPLE))
        assertEquals(Listening.Source.YTM, Listening.choose(listOf(Listening.Source.APPLE, Listening.Source.YTM), null))
    }
}

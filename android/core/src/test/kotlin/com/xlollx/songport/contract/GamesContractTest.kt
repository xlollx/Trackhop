package com.xlollx.songport.contract

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.providers.BeatSaberProvider
import com.xlollx.songport.providers.OsuProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** osu! (API v2, client credentials) and Beat Saber (BeatLeader, ScoreSaber) as read-only sources. */
class GamesContractTest : ContractTest() {
    private val osu = OsuProvider()
    private val bs = BeatSaberProvider()

    @org.junit.Before fun forget() = OsuProvider.forgetTokens()

    private fun osuKey() = env.store.updateSettings { it.copy(clientIds = it.clientIds + ("osu" to "123"), clientSecrets = it.clientSecrets + ("osu" to "sec")) }

    private fun set(id: Int, title: String, artist: String, titleU: String? = null) =
        """{"id":$id,"title":"$title","title_unicode":${titleU?.let { "\"$it\"" } ?: "null"},"artist":"$artist","artist_unicode":"$artist","creator":"mapper"}"""

    @Test fun osuNeedsTheUsersOwnApplication() {
        assertFalse(osu.isConfigured(env))
        osuKey()
        assertTrue(osu.isConfigured(env))
    }

    @Test fun osuSignsInByNameAndReadsFavouritesInPages() = runBlocking {
        osuKey()
        json("""{"access_token":"GUEST","expires_in":86400,"token_type":"Bearer"}""")
        json("""{"id":2,"username":"peppy","favourite_beatmapset_count":101}""")
        osu.login(env, "", "https://osu.ppy.sh/users/peppy", "")
        val tok = next()
        assertCall(tok, "POST", "osu.ppy.sh", "/oauth/token")
        assertEquals(mapOf("client_id" to "123", "client_secret" to "sec", "grant_type" to "client_credentials", "scope" to "public"), tok.form())
        val who = next()
        assertCall(who, "GET", "osu.ppy.sh", "/api/v2/users/@peppy")
        assertEquals("Bearer GUEST", who.getHeader("Authorization"))
        assertEquals("peppy", osu.accountName(env))

        json("[" + (1..100).joinToString(",") { set(it, "Song $it (TV Size)", "Artist") } + "]")
        json("[" + set(101, "Romaji", "LiSA", "紅蓮華") + "," + set(1, "Song 1", "Artist") + "]")
        val ts = osu.tracks(env, OsuProvider.FAVOURITES)
        assertEquals(101, ts.size)
        assertEquals("Song 1", ts[0].title)
        assertEquals("紅蓮華", ts[100].title)
        assertEquals("set:101", ts[100].id)
        assertEquals(0L, ts[0].durationMs)
        val p1 = next()
        assertCall(p1, "GET", "osu.ppy.sh", "/api/v2/users/2/beatmapsets/favourite")
        assertEquals("100", p1.query("limit"))
        assertEquals("0", p1.query("offset"))
        assertEquals("100", next().query("offset"))
        // A second apart, as the API asks; the guest token is reused.
        assertEquals(listOf(1000L), pauses)
        noMoreRequests()
    }

    @Test fun osuMostPlayedIsOneSongPerSet() = runBlocking {
        osuKey()
        env.tokens.set("osu", com.xlollx.songport.data.Tokens("2", userId = "2", userName = "peppy"))
        json("""{"access_token":"G","expires_in":86400}""")
        json("""[{"beatmap_id":10,"count":50,"beatmapset":${set(7, "Song", "A")}},{"beatmap_id":11,"count":40,"beatmapset":${set(7, "Song", "A")}},{"beatmap_id":12,"count":3,"beatmapset":${set(8, "Other", "B ft. C")}}]""")
        val ts = osu.tracks(env, OsuProvider.MOST_PLAYED)
        assertEquals(listOf("set:7", "set:8"), ts.map { it.id })
        assertEquals(listOf("B", "C"), ts[1].artists)
        next()
        assertCall(next(), "GET", "osu.ppy.sh", "/api/v2/users/2/beatmapsets/most_played")
    }

    @Test fun osuListsFeaturedArtistPacks() = runBlocking {
        osuKey()
        env.tokens.set("osu", com.xlollx.songport.data.Tokens("2", userId = "2", userName = "peppy"))
        json("""{"access_token":"G","expires_in":86400}""")
        json("""{"id":2,"favourite_beatmapset_count":3}""")
        json("""{"beatmap_packs":[{"tag":"F123","name":"Featured Artist: Camellia"}],"cursor_string":null}""")
        val pls = osu.playlists(env)
        assertEquals(listOf("favourite", "most_played", "pack:F123"), pls.map { it.id })
        assertEquals("Featured Artist: Camellia", pls[2].name)
        assertEquals(3, pls[0].trackCount)
        next(); next()
        val packs = next()
        assertCall(packs, "GET", "osu.ppy.sh", "/api/v2/beatmaps/packs")
        assertEquals("featured", packs.query("type"))
        json("""{"tag":"F123","beatmapsets":[${set(5, "Song (Cut Ver.)", "Camellia")}]}""")
        assertEquals(listOf("Song"), osu.tracks(env, "pack:F123").map { it.title })
        assertCall(next(), "GET", "osu.ppy.sh", "/api/v2/beatmaps/packs/F123")
    }

    @Test fun osuSaysABadKeyInWords() = runBlocking {
        osuKey()
        json("""{"error":"invalid_client"}""", 401)
        try { osu.login(env, "", "peppy", ""); fail() } catch (e: ProviderException) { assertEquals("osu_bad_key", e.message) }
    }

    @Test fun beatSaberPlayerIds() {
        assertEquals("76561198000000000", BeatSaberProvider.playerId("https://www.beatleader.com/u/76561198000000000?page=2"))
        assertEquals("1922350521131465", BeatSaberProvider.playerId("https://scoresaber.com/u/1922350521131465/"))
        assertEquals("1922350521131465", BeatSaberProvider.playerId(" 1922350521131465 "))
        assertEquals(null, BeatSaberProvider.playerId("not a player"))
    }

    private fun blScore(hash: String, name: String, sub: String, author: String) =
        """{"leaderboard":{"song":{"hash":"$hash","name":"$name","subName":"$sub","author":"$author","mapper":"m","duration":200}}}"""

    @Test fun beatSaberOnBeatLeader() = runBlocking {
        json("""{"id":"76561198000000000","name":"Player"}""")
        bs.login(env, "", "76561198000000000", "")
        assertCall(next(), "GET", "api.beatleader.com", "/player/76561198000000000")
        assertEquals(listOf("most_played", "recent", "best"), bs.playlists(env).map { it.id })
        json("""{"metadata":{"total":3},"data":[${blScore("ABC", "Song", "(TV Size)", "Artist")},${blScore("abc", "Song", "", "Artist")},${blScore("def", "Other", "", "X ft. Y")}]}""")
        val ts = bs.tracks(env, BeatSaberProvider.MOST_PLAYED)
        assertEquals(listOf("bs:abc", "bs:def"), ts.map { it.id })
        assertEquals("Song", ts[0].title)
        assertEquals(listOf("X", "Y"), ts[1].artists)
        val r = next()
        assertCall(r, "GET", "api.beatleader.com", "/player/76561198000000000/scores")
        assertEquals("playCount", r.query("sortBy"))
        noMoreRequests()
    }

    @Test fun beatSaberFallsBackToScoreSaber() = runBlocking {
        json("""{"message":"not found"}""", 404)
        json("""{"id":"1922350521131465","name":"oermer"}""")
        bs.login(env, "", "scoresaber.com/u/1922350521131465", "")
        next()
        assertCall(next(), "GET", "scoresaber.com", "/api/player/1922350521131465/basic")
        assertEquals(listOf("recent", "best"), bs.playlists(env).map { it.id })
        json("""{"playerScores":[{"leaderboard":{"songHash":"4996F9","songName":"Blackmagik Blazing","songSubName":"","songAuthorName":"Camellia","levelAuthorName":"yabje"}}],"metadata":{"total":1}}""")
        val ts = bs.tracks(env, BeatSaberProvider.BEST)
        assertEquals("bs:4996f9", ts.single().id)
        assertEquals(listOf("Camellia"), ts.single().artists)
        val r = next()
        assertCall(r, "GET", "scoresaber.com", "/api/player/1922350521131465/scores")
        assertEquals("top", r.query("sort"))
    }

    @Test fun beatSaberUnknownPlayer() = runBlocking {
        json("{}", 404)
        json("""{"errorMessage":"Connection not found"}""", 404)
        try { bs.login(env, "", "999", ""); fail() } catch (e: ProviderException) { assertEquals("beatsaber_not_found", e.message) }
    }
}

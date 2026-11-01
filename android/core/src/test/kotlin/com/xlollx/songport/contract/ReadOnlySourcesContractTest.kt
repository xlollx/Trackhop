package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Last.fm (last.fm/api, JSON format) as a read-only source, shapes per the reference. */
class LastFmContractTest : ContractTest() {
    private val lf = LastFmProvider()

    private fun signIn() {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY")
        env.tokens.set("lastfm", Tokens("rj", userName = "rj"))
    }

    private fun loved(i: Int, name: String = "Song $i", mbid: String = "") =
        """{"artist":{"url":"https://www.last.fm/music/Artist+$i","name":"Artist $i","mbid":""},"date":{"uts":"${1_700_000_000 + i}","#text":"x"},
           "mbid":${q(mbid)},"url":"https://www.last.fm/music/Artist+$i/_/Song+$i","name":${q(name)},"image":[],"streamable":{"fulltrack":"0","#text":"0"}}"""

    private fun page(tracks: String, page: Int, pages: Int, total: Int) =
        """{"lovedtracks":{"track":$tracks,"@attr":{"user":"rj","totalPages":"$pages","page":"$page","perPage":"200","total":"$total"}}}"""

    @Test fun lovedTracksPageByTotalPages() = runBlocking {
        signIn()
        json(page("[${(0 until 200).joinToString(",") { loved(it) }}]", 1, 2, 201))
        json(page("[${loved(200, name = UNICODE, mbid = "b1a9c0e9-d987-4042-ae91-78d6a3267d69")}]", 2, 2, 201))
        val ts = lf.tracks(env, MusicProvider.LIKED_ID)
        assertEquals(201, ts.size)
        assertEquals("https://www.last.fm/music/Artist+0/_/Song+0", ts[0].id)
        assertEquals("b1a9c0e9-d987-4042-ae91-78d6a3267d69", ts[200].id)
        assertEquals(UNICODE, ts[200].title)
        val r = next()
        assertCall(r, "GET", "ws.audioscrobbler.com", "/2.0/")
        assertEquals(mapOf("method" to "user.getlovedtracks", "user" to "rj", "limit" to "200", "page" to "1", "api_key" to "LFKEY", "format" to "json"),
            r.requestUrl!!.queryParameterNames.associateWith { r.query(it)!! })
        assertEquals("2", next().query("page"))
        noMoreRequests()
    }

    /**
     * Regression: Last.fm's JSON is converted from its XML, and a list of one comes back as a single
     * object instead of an array (seen in the field and handled by the common client libraries; not
     * in the reference). One loved track used to read as none.
     */
    @Test fun aSingleLovedTrackComesAsAnObject() = runBlocking {
        signIn()
        json(page(loved(1), 1, 1, 1))
        assertEquals(listOf("Song 1"), lf.tracks(env, MusicProvider.LIKED_ID).map { it.title })
    }

    @Test fun noLovedTracks() = runBlocking {
        signIn()
        json(page("[]", 1, 0, 0))
        assertEquals(emptyList<Track>(), lf.tracks(env, MusicProvider.LIKED_ID))
        assertEquals(1, server.requestCount)
    }

    @Test fun recentSkipsNowPlayingAndRepeats() = runBlocking {
        signIn()
        json("""{"recenttracks":{"track":[
            {"artist":{"mbid":"","#text":"Daft Punk"},"mbid":"","album":{"mbid":"","#text":"RAM"},"name":"Get Lucky","@attr":{"nowplaying":"true"},"url":"u1"},
            {"artist":{"mbid":"","#text":"Daft Punk"},"mbid":"","album":{"mbid":"","#text":"RAM"},"name":"Get Lucky","url":"u1","date":{"uts":"1700000100","#text":""}},
            {"artist":{"mbid":"","#text":"Daft Punk"},"mbid":"","album":{"mbid":"","#text":"RAM"},"name":"Get Lucky","url":"u1","date":{"uts":"1700000000","#text":""}},
            {"artist":{"mbid":"","#text":"Måneskin"},"mbid":"","album":{"#text":""},"name":"Zitti e buoni","url":"u2","date":{"uts":"1699999000","#text":""}}],
            "@attr":{"user":"rj","totalPages":"1","page":"1","perPage":"200","total":"3"}}}""")
        val ts = lf.tracks(env, MusicProvider.RECENT_ID)
        assertEquals(listOf("Get Lucky", "Zitti e buoni"), ts.map { it.title })
        assertEquals(1_700_000_100_000L, ts[0].addedAt)
        assertEquals("user.getrecenttracks", next().query("method"))
    }

    @Test fun apiErrorsAreSaidAsLastFmSaysThem() = runBlocking {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY")
        json("""{"error":6,"message":"User not found","links":[]}""", 404)
        try { lf.login(env, "", "nobody", ""); fail() } catch (e: ProviderException) { assertEquals("Last.fm: User not found", e.message) }
        signIn()
        json("""{"error":29,"message":"Rate Limit Exceeded - Your IP has made too many requests in a short period"}""")
        try { lf.tracks(env, MusicProvider.LIKED_ID); fail() } catch (e: ProviderException) { assertTrue(e.message!!.startsWith("Last.fm: Rate Limit Exceeded")) }
    }

    /** Regression: a non-JSON error page (an outage) ends as "Last.fm: HTTP 503", not a JSON parser crash. */
    @Test fun nonJsonErrorPageIsAClearError() = runBlocking {
        signIn()
        text("<html><body>Service Temporarily Unavailable</body></html>", 503); text("<html><body>Service Temporarily Unavailable</body></html>", 503)
        try { lf.tracks(env, MusicProvider.LIKED_ID); fail() } catch (e: ProviderException) { assertEquals("Last.fm: HTTP 503", e.message) }
    }

    @Test fun loginSavesTheCanonicalName() = runBlocking {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY")
        json("""{"user":{"name":"RJ","realname":"Richard","url":"https://www.last.fm/user/RJ","playcount":"150316"}}""")
        lf.login(env, "", "  rj ", "")
        assertEquals("RJ", env.tokens.get("lastfm")!!.userName)
        assertEquals("rj", next().query("user"))
    }

    @Test fun writesAreRefusedWithoutARequest() = runBlocking {
        signIn()
        try { lf.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("a", "b"))); fail() } catch (e: ProviderException) { }
        noMoreRequests()
    }
}

/** ListenBrainz (listenbrainz.readthedocs.io), shapes per the reference. */
class ListenBrainzContractTest : ContractTest() {
    private val lb = ListenBrainzProvider()
    private val host = "api.listenbrainz.org"
    private fun mbid(i: Int) = "00000000-0000-4000-8000-" + i.toString().padStart(12, '0')

    private fun signIn(token: String? = "LBTOKEN") = env.tokens.set("listenbrainz", Tokens("rj", userName = "rj ü", extra = token?.let { mapOf("token" to it) } ?: emptyMap()))

    private fun plEntry(i: Int) = """{"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(i)}","title":"List $i","creator":"rj","date":"2024-01-01T00:00:00+00:00",
        "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"creator":"rj","public":true,"last_modified_at":"2024-01-02T00:00:00+00:00"}},"track":[]}}"""

    @Test fun playlistsPageByOffsetEvenWhenTheLastPageIsFull() = runBlocking {
        signIn()
        json("""{"count":50,"offset":0,"playlist_count":50,"playlists":[${(0 until 50).joinToString(",") { plEntry(it) }}]}""")
        json("""{"count":0,"offset":50,"playlist_count":50,"playlists":[]}""")
        assertEquals(50, lb.playlists(env).size)
        val r = next()
        assertCall(r, "GET", host, "/1/user/rj%20%C3%BC/playlists")
        assertEquals("50", r.query("count")); assertEquals("0", r.query("offset"))
        assertEquals("50", next().query("offset"))
        noMoreRequests()
    }

    @Test fun jspfTracksWithBothIdentifierShapes() = runBlocking {
        signIn()
        json("""{"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(1)}","title":"T","track":[
            {"identifier":["https://musicbrainz.org/recording/${mbid(10)}"],"title":${q(UNICODE)},"creator":"A & B","album":"Al","duration":181000,
             "extension":{"https://musicbrainz.org/doc/jspf#track":{"additional_metadata":{"artists":[{"artist_credit_name":"A","join_phrase":" & "},{"artist_credit_name":"B","join_phrase":""}]}}}},
            {"identifier":"https://musicbrainz.org/recording/${mbid(11)}","title":"Two","creator":"C"},
            {"title":"No id","creator":"D"},
            {"identifier":"https://musicbrainz.org/recording/${mbid(10)}","title":"Dup","creator":"A"}]}}""")
        val ts = lb.tracks(env, mbid(1))
        assertEquals(listOf(mbid(10), mbid(11), "No id|D", mbid(10)), ts.map { it.id })
        assertEquals(listOf("A", "B"), ts[0].artists)
        assertEquals(listOf("C"), ts[1].artists)
        assertCall(next(), "GET", host, "/1/playlist/${mbid(1)}")
    }

    @Test fun lovedFeedbackPagesByHundred() = runBlocking {
        signIn()
        fun fb(i: Int) = """{"created":1700000000,"recording_mbid":"${mbid(i)}","recording_msid":null,"score":1,"user_id":"rj",
            "track_metadata":{"artist_name":"Artist $i","release_name":"R","track_name":"Song $i","additional_info":{"duration_ms":1000}}}"""
        json("""{"count":100,"feedback":[${(0 until 100).joinToString(",") { fb(it) }}],"offset":0,"total_count":101}""")
        json("""{"count":1,"feedback":[${fb(100)},{"created":1,"recording_mbid":"x","score":1,"track_metadata":null}],"offset":100,"total_count":101}""")
        assertEquals(101, lb.tracks(env, MusicProvider.LIKED_ID).size)
        val r = next()
        assertCall(r, "GET", host, "/1/feedback/user/rj%20%C3%BC/get-feedback")
        assertEquals("1", r.query("score")); assertEquals("true", r.query("metadata"))
        assertEquals("100", next().query("offset"))
    }

    @Test fun loveAndUnloveWithTheUserToken() = runBlocking {
        signIn()
        repeat(2) { json("""{"status":"ok"}""") }
        lb.addTracks(env, MusicProvider.LIKED_ID, listOf(Track(mbid(1), "a"), Track("not-an-mbid", "b")))
        lb.removeTracks(env, MusicProvider.LIKED_ID, listOf(Track(mbid(1), "a")))
        val a = next()
        assertCall(a, "POST", host, "/1/feedback/recording-feedback")
        assertEquals("Token LBTOKEN", a.getHeader("Authorization"))
        assertEquals(mbid(1), a.jsonBody()["recording_mbid"].str)
        assertEquals(1, a.jsonBody()["score"].int)
        assertEquals(0, next().jsonBody()["score"].int)
        noMoreRequests()
    }

    @Test fun noTokenNoWrite() = runBlocking {
        signIn(token = null)
        try { lb.addTracks(env, MusicProvider.LIKED_ID, listOf(Track(mbid(1), "a"))); fail() } catch (e: ProviderException) { assertEquals("listenbrainz_token_needed", e.message) }
        noMoreRequests()
    }

    @Test fun apiErrorMessage() = runBlocking {
        signIn()
        json("""{"code":404,"error":"Cannot find playlist: x"}""", 404)
        try { lb.tracks(env, "x"); fail() } catch (e: ProviderException) { assertEquals("ListenBrainz API 404: Cannot find playlist: x", e.message) }
    }

    /** Regression: a non-JSON error page (the proxy's 502) ends as a clear error, not a JSON parser crash. */
    @Test fun nonJsonErrorPageIsAClearError() = runBlocking {
        signIn()
        text("<html><center>502 Bad Gateway</center>nginx</html>", 502); text("<html><center>502 Bad Gateway</center>nginx</html>", 502)
        try { lb.tracks(env, "x"); fail() } catch (e: ProviderException) { assertTrue(e.message, e.message!!.startsWith("ListenBrainz API 502: <html>")) }
    }

    @Test fun rateLimitWithoutRetryAfterBacksOffBriefly() = runBlocking {
        signIn()
        json("""{"code":429,"error":"Too many requests"}""", 429, "X-RateLimit-Reset-In" to "4")
        json("""{"playlist":{"track":[]}}""")
        lb.tracks(env, "x")
        assertEquals(listOf(2000L), pauses)
    }

    /** Regression (01/11/2026): the lookup wants the token and the path with its final slash; without either it was a 308/401 per track. */
    @Test fun searchLooksUpWithTheTokenAndWaitsWhenTheWindowIsSpent() = runBlocking {
        signIn()
        json("""{"artist_credit_name":"Daft Punk","recording_mbid":"${mbid(7)}","recording_name":"One More Time","release_name":"Discovery"}""",
            200, "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset-In" to "3")
        val found = lb.search(env, Track("x", "One More Time", artists = listOf("Daft Punk")))
        assertEquals(listOf(mbid(7)), found.map { it.id })
        val r = next()
        assertCall(r, "GET", host, "/1/metadata/lookup/")
        assertEquals("Daft Punk", r.query("artist_name"))
        assertEquals("Token LBTOKEN", r.getHeader("Authorization"))
        assertEquals(listOf(3250L), pauses)
    }

    @Test fun noTokenNoSearch() = runBlocking {
        signIn(token = null)
        try { lb.search(env, Track("x", "One More Time", artists = listOf("Daft Punk"))); fail() } catch (e: ProviderException) { assertEquals("listenbrainz_token_needed", e.message) }
        noMoreRequests()
    }

    @Test fun loginChecksTheToken() = runBlocking {
        json("""{"count":0,"offset":0,"playlist_count":0,"playlists":[]}""")
        json("""{"code":200,"message":"Token invalid.","valid":false}""")
        try { lb.login(env, "", "rj", "bad"); fail() } catch (e: ProviderException) { assertEquals("listenbrainz_token_invalid", e.message) }
        next()
        assertEquals("Token bad", next().getHeader("Authorization"))
    }
}

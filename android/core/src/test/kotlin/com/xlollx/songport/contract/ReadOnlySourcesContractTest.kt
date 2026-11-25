package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Signed in with the user name only (as before #51): loving asks for the password, without a request. */
    @Test fun writesAreRefusedWithoutASession() = runBlocking {
        signIn()
        try { lf.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("a", "b"))); fail() } catch (e: ProviderException) { assertEquals("lastfm_needs_password", e.message) }
        assertEquals(false, lf.canLove(env))
        assertEquals("lastfm_needs_password", lf.libraryEntries(env, asTarget = true).single().description)
        noMoreRequests()
    }

    // ---------------------------------------------------------------- loving (#51)

    private fun posted(r: okhttp3.mockwebserver.RecordedRequest): Map<String, String> =
        r.body.readUtf8().split("&").associate { kv -> kv.substringBefore("=").let { java.net.URLDecoder.decode(it, "UTF-8") } to java.net.URLDecoder.decode(kv.substringAfter("="), "UTF-8") }

    private fun withSession() {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY", "LASTFM_SHARED_SECRET" to "SECRET")
        env.tokens.set("lastfm", Tokens("RJ", userName = "RJ", extra = mapOf("sk" to "SK1")))
    }

    /** The reference's rule, checked against a digest worked out by hand: md5("api_keyxxmethodauth.getSessiontokenyyilovecher"). */
    @Test fun signatureFollowsTheReference() {
        val sig = LastFmProvider.signature(mapOf("method" to "auth.getSession", "token" to "yy", "api_key" to "xx", "format" to "json"), "ilovecher")
        val expected = java.security.MessageDigest.getInstance("MD5").digest("api_keyxxmethodauth.getSessiontokenyyilovecher".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, sig)
        assertEquals(32, sig.length)
    }

    @Test fun passwordGetsASessionAndIsNotKept() = runBlocking {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY", "LASTFM_SHARED_SECRET" to "SECRET")
        json("""{"user":{"name":"RJ"}}""")
        json("""{"session":{"name":"RJ","key":"d580d57f32848f5dcf574d1ce18d78b2","subscriber":0}}""")
        lf.login(env, "", "rj", "hunter2")
        val t = env.tokens.get("lastfm")!!
        assertEquals("RJ", t.userName)
        assertEquals("d580d57f32848f5dcf574d1ce18d78b2", t.extra["sk"])
        assertTrue("password kept: $t", !t.toString().contains("hunter2"))
        assertTrue(lf.canLove(env))
        assertEquals("user.getinfo", next().query("method"))
        val r = next()
        assertCall(r, "POST", "ws.audioscrobbler.com", "/2.0/")
        val f = posted(r)
        assertEquals("auth.getMobileSession", f["method"]); assertEquals("rj", f["username"]); assertEquals("hunter2", f["password"])
        assertEquals("json", f["format"]); assertEquals("LFKEY", f["api_key"])
        assertEquals(LastFmProvider.signature(f - "api_sig", "SECRET"), f["api_sig"])
        noMoreRequests()
    }

    @Test fun wrongPasswordIsSaidAndNothingIsSaved() = runBlocking {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY", "LASTFM_SHARED_SECRET" to "SECRET")
        json("""{"user":{"name":"RJ"}}""")
        json("""{"error":4,"message":"Authentication Failed - You do not have permissions to access the service"}""", 403)
        try { lf.login(env, "", "rj", "nope"); fail() } catch (e: ProviderException) { assertTrue(e.message!!.startsWith("Last.fm: Authentication Failed")) }
        assertEquals(null, env.tokens.get("lastfm"))
    }

    @Test fun noSharedSecretNoSessionCall() = runBlocking {
        BuildDefaults.values = mapOf("LASTFM_API_KEY" to "LFKEY")
        json("""{"user":{"name":"RJ"}}""")
        try { lf.login(env, "", "rj", "hunter2"); fail() } catch (e: ProviderException) { assertEquals("lastfm_no_secret", e.message) }
        next(); noMoreRequests()
    }

    @Test fun lovesAndUnlovesEachTrackSigned() = runBlocking {
        withSession()
        json("{}"); json("{}"); json("{}")
        lf.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("x", "Get Lucky", listOf("Daft Punk", "Pharrell")), Track("y", UNICODE, listOf("Måneskin"))))
        lf.removeTracks(env, MusicProvider.LIKED_ID, listOf(Track("z", "Zitti e buoni", listOf("Måneskin"))))
        val a = posted(next()); val b = posted(next()); val c = posted(next())
        assertEquals(listOf("track.love", "Get Lucky", "Daft Punk", "SK1"), listOf(a["method"], a["track"], a["artist"], a["sk"]))
        assertEquals(LastFmProvider.signature(a - "api_sig", "SECRET"), a["api_sig"])
        assertEquals(UNICODE, b["track"])
        assertEquals(LastFmProvider.signature(b - "api_sig", "SECRET"), b["api_sig"])
        assertEquals(listOf("track.unlove", "Zitti e buoni"), listOf(c["method"], c["track"]))
        assertEquals(listOf(300L), pauses)
        noMoreRequests()
    }

    @Test fun onlyLovedTracksCanBeWritten() = runBlocking {
        withSession()
        try { lf.addTracks(env, LastFmProvider.TOP_MONTH_ID, listOf(Track("a", "b"))); fail() } catch (e: ProviderException) { }
        try { lf.createPlaylist(env, "Mine", ""); fail() } catch (e: ProviderException) { }
        assertEquals(false, lf.playlists(env).single().ownedByMe)
        assertEquals("lastfm_loved_target_desc", lf.libraryEntries(env, asTarget = true).single().description)
        noMoreRequests()
    }

    /** A session revoked on last.fm (error 9): dropped, so the account is read-only again and says how to love. */
    @Test fun anEndedSessionIsDropped() = runBlocking {
        withSession()
        json("""{"error":9,"message":"Invalid session key - Please re-authenticate"}""", 403)
        try { lf.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("a", "b", listOf("c")))); fail() } catch (e: ProviderException) { assertEquals("lastfm_session_expired", e.message) }
        assertEquals(false, lf.canLove(env))
        assertEquals("RJ", env.tokens.get("lastfm")!!.userName)
    }

    @Test fun aRateLimitIsWaitedOutOnce() = runBlocking {
        withSession()
        json("""{"error":29,"message":"Rate Limit Exceeded"}""")
        json("{}")
        lf.addTracks(env, MusicProvider.LIKED_ID, listOf(Track("a", "b", listOf("c"))))
        assertEquals(listOf(5_000L), pauses)
        next(); next(); noMoreRequests()
    }

    @Test fun searchGivesLastFmsSpellingWithTheLovedListsIds() = runBlocking {
        signIn()
        json("""{"results":{"opensearch:totalResults":"2","trackmatches":{"track":[
            {"name":"Get Lucky (feat. Pharrell Williams)","artist":"Daft Punk","url":"https://www.last.fm/music/Daft+Punk/_/Get+Lucky","listeners":"1","mbid":""},
            {"name":"Get Lucky","artist":"Daft Punk","url":"https://www.last.fm/music/Daft+Punk/_/Get+Lucky+(Radio+Edit)","listeners":"1","mbid":"7b2c8d6e-1111-4222-8333-944455556666"}]}}}""")
        val hits = lf.search(env, Track("s", "Get Lucky", listOf("Daft Punk", "Pharrell Williams")))
        assertEquals(listOf("https://www.last.fm/music/Daft+Punk/_/Get+Lucky", "7b2c8d6e-1111-4222-8333-944455556666"), hits.map { it.id })
        assertEquals(listOf("Daft Punk"), hits[0].artists)
        val r = next()
        assertEquals(listOf("track.search", "Get Lucky", "Daft Punk"), listOf(r.query("method"), r.query("track"), r.query("artist")))
    }

    /** Mark as listened: 50 scrobbles a call at most, signed, with the session; what Last.fm accepted is counted. */
    @Test fun scrobblesGoFiftyAtATimeSigned() = runBlocking {
        withSession()
        json("""{"scrobbles":{"@attr":{"accepted":50,"ignored":0}}}""")
        json("""{"scrobbles":{"@attr":{"accepted":1,"ignored":1}}}""")
        val listens = (0 until 52).map { com.xlollx.songport.sync.Scrobbles.Listen(Track("t$it", "Song $it", listOf("Fleetwood Mac", "Other"), album = "Rumours", durationMs = 200_000), 1_700_000_000_000L + it * 200_000L) }
        assertEquals(51, lf.scrobble(env, listens))
        val a = posted(next())
        assertEquals("track.scrobble", a["method"]); assertEquals("SK1", a["sk"])
        assertEquals("Fleetwood Mac", a["artist[0]"]); assertEquals("Song 0", a["track[0]"]); assertEquals("1700000000", a["timestamp[0]"])
        assertEquals("Rumours", a["album[0]"]); assertEquals("200", a["duration[0]"]); assertEquals("Song 49", a["track[49]"])
        assertEquals(LastFmProvider.signature(a - "api_sig", "SECRET"), a["api_sig"])
        val b = posted(next())
        assertEquals(listOf("Song 50", "Song 51"), listOf(b["track[0]"], b["track[1]"]))
        noMoreRequests()
    }

    @Test fun noSessionNoScrobble() = runBlocking {
        signIn()
        try { lf.scrobble(env, listOf(com.xlollx.songport.sync.Scrobbles.Listen(Track("a", "b", listOf("c")), 1))); fail() } catch (e: ProviderException) { assertEquals("lastfm_needs_password", e.message) }
        noMoreRequests()
    }

    /** One match comes as an object (XML-born JSON), as in the loved list. */
    @Test fun searchWithOneMatch() = runBlocking {
        signIn()
        json("""{"results":{"trackmatches":{"track":{"name":"Zitti e buoni","artist":"Måneskin","url":"u","mbid":""}}}}""")
        assertEquals(listOf("Zitti e buoni"), lf.search(env, Track("s", "Zitti e buoni", listOf("Måneskin"))).map { it.title })
    }
}

/** ListenBrainz (listenbrainz.readthedocs.io), shapes per the reference. */
class ListenBrainzContractTest : ContractTest() {
    private val lb = ListenBrainzProvider()

    // The playlists made for the user are 1.18's (Features): on here, off in withoutTheSwitch….
    @org.junit.Before fun v2On() { com.xlollx.songport.Features.build = true }
    @org.junit.After fun v2Off() { com.xlollx.songport.Features.build = false }
    private val host = "api.listenbrainz.org"
    private fun mbid(i: Int) = "00000000-0000-4000-8000-" + i.toString().padStart(12, '0')

    private fun signIn(token: String? = "LBTOKEN") = env.tokens.set("listenbrainz", Tokens("rj", userName = "rj ü", extra = token?.let { mapOf("token" to it) } ?: emptyMap()))

    private fun plEntry(i: Int) = """{"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(i)}","title":"List $i","creator":"rj","date":"2024-01-01T00:00:00+00:00",
        "extension":{"https://musicbrainz.org/doc/jspf#playlist":{"creator":"rj","public":true,"last_modified_at":"2024-01-02T00:00:00+00:00"}},"track":[]}}"""

    @Test fun playlistsPageByOffsetEvenWhenTheLastPageIsFull() = runBlocking {
        signIn()
        json("""{"count":50,"offset":0,"playlist_count":50,"playlists":[${(0 until 50).joinToString(",") { plEntry(it) }}]}""")
        json("""{"count":0,"offset":50,"playlist_count":50,"playlists":[]}""")
        json("""{"count":0,"offset":0,"playlist_count":0,"playlists":[]}""")
        assertEquals(50, lb.playlists(env).size)
        val r = next()
        assertCall(r, "GET", host, "/1/user/rj%20%C3%BC/playlists")
        assertEquals("50", r.query("count")); assertEquals("0", r.query("offset"))
        assertEquals("50", next().query("offset"))
        assertCall(next(), "GET", host, "/1/user/rj%20%C3%BC/playlists/createdfor")
        noMoreRequests()
    }

    /** A playlist made for rj: [patch] its kind, [date] when it was made, [expires] when it goes. */
    private fun madeFor(i: Int, title: String, patch: String?, date: String, expires: String = "2099-01-01T00:00:00.000000"): String {
        val algorithm = patch?.let { ",\"algorithm_metadata\":{\"source_patch\":\"$it\"}" } ?: ""
        return "{\"playlist\":{\"identifier\":\"https://listenbrainz.org/playlist/${mbid(i)}\",\"title\":${q(title)},\"creator\":\"listenbrainz\",\"date\":\"$date\"," +
            "\"annotation\":\"<p>Fresh <b>music</b> for you</p>\",\"extension\":{\"https://musicbrainz.org/doc/jspf#playlist\":{\"additional_metadata\":{\"expires_at\":\"$expires\"$algorithm}," +
            "\"created_for\":\"rj\",\"public\":true}},\"track\":[]}}"
    }

    @Test fun playlistsMadeForTheUserAreListedOncePerKindTheLatest() = runBlocking {
        signIn()
        json("""{"count":1,"offset":0,"playlist_count":1,"playlists":[${plEntry(0)}]}""")
        json("""{"count":5,"offset":0,"playlist_count":5,"playlists":[
            ${madeFor(1, "Weekly Jams for rj, week of 2026-11-03 Mon", "weekly-jams", "2026-11-03T05:00:00+00:00")},
            ${madeFor(2, "Weekly Jams for rj, week of 2026-11-10 Mon", "weekly-jams", "2026-11-10T05:00:00+00:00")},
            ${madeFor(3, "Weekly Exploration for rj, week of 2026-11-10 Mon", "weekly-exploration", "2026-11-10T04:00:00+00:00")},
            ${madeFor(4, "Top Discoveries of 2019 for rj", "top-discoveries", "2020-01-01T00:00:00+00:00", expires = "2020-02-01T00:00:00.000000")},
            ${madeFor(5, "Daily Jams for rj, 2026-11-12 Thu", null, "2026-11-12T05:00:00+00:00")}]}""")
        val ps = lb.playlists(env)
        assertEquals(listOf(mbid(0), "createdfor:daily-jams", "createdfor:weekly-jams", "createdfor:weekly-exploration"), ps.map { it.id })
        assertEquals(listOf("List 0", "Daily Jams", "Weekly Jams", "Weekly Exploration"), ps.map { it.name })
        assertEquals(false, ps[2].ownedByMe)
        assertEquals("Fresh music for you", ps[2].description)
        next()
        val r = next()
        assertCall(r, "GET", host, "/1/user/rj%20%C3%BC/playlists/createdfor")
        assertEquals(null, r.getHeader("Authorization"))
        noMoreRequests()
    }

    @Test fun withoutTheSwitchThePlaylistsMadeForTheUserAreNotListedButStillRead() = runBlocking {
        com.xlollx.songport.Features.build = false
        signIn()
        json("""{"count":1,"offset":0,"playlist_count":1,"playlists":[${plEntry(0)}]}""")
        assertEquals(listOf(mbid(0)), lb.playlists(env).map { it.id })
        next()
        noMoreRequests()
        // A sync made on a test build goes on reading its kind.
        json("""{"count":1,"offset":0,"playlist_count":1,"playlists":[${madeFor(2, "Weekly Jams for rj, week of 2026-11-10 Mon", "weekly-jams", "2026-11-10T05:00:00+00:00")}]}""")
        json("""{"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(2)}","title":"Weekly Jams","track":[{"identifier":["https://musicbrainz.org/recording/${mbid(10)}"],"title":"One","creator":"A"}]}}""")
        assertEquals(listOf(mbid(10)), lb.tracks(env, "createdfor:weekly-jams").map { it.id })
    }

    @Test fun aKindMadeForTheUserReadsItsLatestPlaylist() = runBlocking {
        signIn()
        json("""{"count":2,"offset":0,"playlist_count":2,"playlists":[
            ${madeFor(1, "Weekly Jams for rj, week of 2026-11-03 Mon", "weekly-jams", "2026-11-03T05:00:00+00:00")},
            ${madeFor(2, "Weekly Jams for rj, week of 2026-11-10 Mon", "weekly-jams", "2026-11-10T05:00:00+00:00")}]}""")
        json("""{"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(2)}","title":"Weekly Jams","track":[
            {"identifier":["https://musicbrainz.org/recording/${mbid(10)}"],"title":"One","creator":"A"}]}}""")
        val ts = lb.tracks(env, "createdfor:weekly-jams")
        assertEquals(listOf(mbid(10)), ts.map { it.id })
        assertCall(next(), "GET", host, "/1/user/rj%20%C3%BC/playlists/createdfor")
        assertCall(next(), "GET", host, "/1/playlist/${mbid(2)}")
        noMoreRequests()
    }

    @Test fun aKindNoLongerMadeIsAnErrorNotAnEmptySource() = runBlocking {
        signIn()
        json("""{"count":0,"offset":0,"playlist_count":0,"playlists":[]}""")
        try { lb.tracks(env, "createdfor:weekly-jams"); fail() } catch (e: ProviderException) { assertTrue(e.message, e.message!!.contains("weekly-jams")) }
    }

    @Test fun playlistsMadeForTheUserNotAnsweringLeaveTheListAsItWas() = runBlocking {
        signIn()
        json("""{"count":1,"offset":0,"playlist_count":1,"playlists":[${plEntry(0)}]}""")
        json("""{"error":"down"}""", 503)
        assertEquals(listOf(mbid(0)), lb.playlists(env).map { it.id })
        assertEquals(1, env.logs.count { "made for the user not read" in it || "created for the user not read" in it })
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

    @Test fun rateLimitWaitsForTheWindowToReset() = runBlocking {
        signIn()
        json("""{"code":429,"error":"Too many requests"}""", 429, "X-RateLimit-Reset-In" to "4")
        json("""{"playlist":{"track":[]}}""")
        lb.tracks(env, "x")
        assertEquals(listOf(4000L), pauses)
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

    /** Mark as listened: an import with the user token, each listen at its own time. */
    @Test fun listensAreSubmittedAsAnImport() = runBlocking {
        signIn()
        json("""{"status":"ok"}""")
        val t = Track("x", "Dreams", listOf("Fleetwood Mac"), album = "Rumours", durationMs = 257_000)
        assertEquals(1, lb.submit(env, listOf(com.xlollx.songport.sync.Scrobbles.Listen(t, 1_700_000_000_000L))))
        val r = next()
        assertCall(r, "POST", host, "/1/submit-listens")
        assertEquals("Token LBTOKEN", r.getHeader("Authorization"))
        val body = r.jsonBody()
        assertEquals("import", body["listen_type"].str)
        val l = body["payload"][0]
        assertEquals(1_700_000_000L, l["listened_at"].long)
        assertEquals("Dreams", l["track_metadata"]["track_name"].str)
        assertEquals("Fleetwood Mac", l["track_metadata"]["artist_name"].str)
        assertEquals("Rumours", l["track_metadata"]["release_name"].str)
        assertEquals(257_000L, l["track_metadata"]["additional_info"]["duration_ms"].long)
        noMoreRequests()
    }

    @Test fun noTokenNoListens() = runBlocking {
        signIn(token = null)
        assertFalse(lb.canSubmit(env))
        try { lb.submit(env, listOf(com.xlollx.songport.sync.Scrobbles.Listen(Track("a", "b", listOf("c")), 1))); fail() } catch (e: ProviderException) { assertEquals("listenbrainz_token_needed", e.message) }
        noMoreRequests()
    }

    /** 2.2: a destination. A new playlist is private JSPF, made with the user token. */
    @Test fun aPlaylistIsMadeWithTheToken() = runBlocking {
        signIn()
        json("""{"playlist_mbid":"${mbid(5)}","status":"ok"}""")
        val p = lb.createPlaylist(env, "Road trip", "From Trackhop")
        assertEquals(mbid(5), p.id)
        val r = next()
        assertCall(r, "POST", host, "/1/playlist/create")
        assertEquals("Token LBTOKEN", r.getHeader("Authorization"))
        val pl = r.jsonBody()["playlist"]
        assertEquals("Road trip", pl["title"].str)
        assertEquals("From Trackhop", pl["annotation"].str)
        assertEquals(false, (pl["extension"]["https://musicbrainz.org/doc/jspf#playlist"]["public"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
        noMoreRequests()
    }

    @Test fun noTokenNoPlaylist() = runBlocking {
        signIn(token = null)
        try { lb.createPlaylist(env, "x", ""); fail() } catch (e: ProviderException) { assertEquals("listenbrainz_token_needed", e.message) }
        noMoreRequests()
    }

    /** Recordings go in by MBID, a hundred a request; a track without one cannot go in. */
    @Test fun tracksAreAddedByRecordingAHundredAtATime() = runBlocking {
        signIn()
        repeat(2) { json("""{"status":"ok"}""") }
        lb.addTracks(env, mbid(5), (0 until 101).map { Track(mbid(100 + it), "t$it") } + Track("Title|Artist", "x"))
        val a = next()
        assertCall(a, "POST", host, "/1/playlist/${mbid(5)}/item/add")
        assertEquals("Token LBTOKEN", a.getHeader("Authorization"))
        val first = a.jsonBody()["playlist"]["track"] as kotlinx.serialization.json.JsonArray
        assertEquals(100, first.size)
        assertEquals("https://musicbrainz.org/recording/${mbid(100)}", first[0]["identifier"].str)
        assertEquals(1, (next().jsonBody()["playlist"]["track"] as kotlinx.serialization.json.JsonArray).size)
        noMoreRequests()
    }

    /** A removal reads the playlist again: an entry that moved is found by its recording, the last goes first. */
    @Test fun removalsAreByPositionCheckedAgainstThePlaylistNow() = runBlocking {
        signIn()
        // Read by the sync: A at 0, B at 1, C at 2. Since then someone put X first.
        json("""{"playlist":{"track":[
            {"identifier":"https://musicbrainz.org/recording/${mbid(9)}","title":"X","creator":"x"},
            {"identifier":"https://musicbrainz.org/recording/${mbid(1)}","title":"A","creator":"a"},
            {"identifier":"https://musicbrainz.org/recording/${mbid(2)}","title":"B","creator":"b"},
            {"identifier":"https://musicbrainz.org/recording/${mbid(3)}","title":"C","creator":"c"}]}}""")
        repeat(2) { json("""{"status":"ok"}""") }
        lb.removeTracks(env, mbid(5), listOf(Track(mbid(1), "A", itemId = "0"), Track(mbid(3), "C", itemId = "2"), Track(mbid(4), "gone", itemId = "3")))
        assertCall(next(), "GET", host, "/1/playlist/${mbid(5)}")
        val d1 = next()
        assertCall(d1, "POST", host, "/1/playlist/${mbid(5)}/item/delete")
        assertEquals(3, d1.jsonBody()["index"].int)
        assertEquals(1, d1.jsonBody()["count"].int)
        assertEquals(1, next().jsonBody()["index"].int)
        noMoreRequests()
    }

    /** An entry without a title is not read, but it holds its place: the one after it is removed, not it. */
    @Test fun anUntitledEntryKeepsItsPlaceForRemovals() = runBlocking {
        signIn()
        json("""{"playlist":{"track":[
            {"identifier":"https://musicbrainz.org/recording/${mbid(9)}","creator":"x"},
            {"identifier":"https://musicbrainz.org/recording/${mbid(2)}","title":"B","creator":"b"}]}}""")
        json("""{"status":"ok"}""")
        lb.removeTracks(env, mbid(5), listOf(Track(mbid(2), "B", itemId = "1")))
        assertCall(next(), "GET", host, "/1/playlist/${mbid(5)}")
        assertEquals(1, next().jsonBody()["index"].int)
        noMoreRequests()
    }

    @Test fun entryPositionsKeepTheirOwnPlaceWhenItHolds() {
        val now = listOf("a", "b", "a", "c")
        assertEquals(setOf(2), ListenBrainzProvider.entryPositions(now, listOf(Track("a", "", itemId = "2"))).toSet())
        assertEquals(setOf(0, 2), ListenBrainzProvider.entryPositions(now, listOf(Track("a", "", itemId = "2"), Track("a", "", itemId = "2"))).toSet())
        assertEquals(emptySet<Int>(), ListenBrainzProvider.entryPositions(now, listOf(Track("z", "", itemId = "0"))).toSet())
    }

    @Test fun renameAndDeleteOwnPlaylistsButNotTheOnesMadeForTheUser() = runBlocking {
        signIn()
        repeat(2) { json("""{"status":"ok"}""") }
        lb.renamePlaylist(env, mbid(5), "New name")
        lb.deletePlaylist(env, mbid(5))
        val r = next()
        assertCall(r, "POST", host, "/1/playlist/edit/${mbid(5)}")
        assertEquals("New name", r.jsonBody()["playlist"]["title"].str)
        assertCall(next(), "POST", host, "/1/playlist/${mbid(5)}/delete")
        try { lb.addTracks(env, "createdfor:weekly-jams", listOf(Track(mbid(1), "a"))); fail() } catch (e: ProviderException) { }
        noMoreRequests()
    }

    @Test fun ownPlaylistsAreMarkedAndListedWithTheToken() = runBlocking {
        signIn()
        json("""{"count":2,"offset":0,"playlist_count":2,"playlists":[${plEntry(0).replace("\"creator\":\"rj\",\"date", "\"creator\":\"RJ Ü\",\"date")},
            {"playlist":{"identifier":"https://listenbrainz.org/playlist/${mbid(1)}","title":"Shared","creator":"someone","extension":{},"track":[]}}]}""")
        json("""{"count":0,"offset":0,"playlist_count":0,"playlists":[]}""")
        val ps = lb.playlists(env)
        assertEquals(listOf(true, false), ps.map { it.ownedByMe })
        assertEquals("Token LBTOKEN", next().getHeader("Authorization"))
    }

    @Test fun loginChecksTheToken() = runBlocking {
        json("""{"count":0,"offset":0,"playlist_count":0,"playlists":[]}""")
        json("""{"code":200,"message":"Token invalid.","valid":false}""")
        try { lb.login(env, "", "rj", "bad"); fail() } catch (e: ProviderException) { assertEquals("listenbrainz_token_invalid", e.message) }
        next()
        assertEquals("Token bad", next().getHeader("Authorization"))
    }
}

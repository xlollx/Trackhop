package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.QuotaMeter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** YouTube Data API v3 (developers.google.com/youtube/v3/docs), shapes per the reference. */
class YouTubeContractTest : ContractTest() {
    private val yt = YouTubeProvider()
    private val api = "www.googleapis.com"

    @Before fun signIn() {
        BuildDefaults.values = mapOf("GOOGLE_CLIENT_ID" to "gcid.apps.googleusercontent.com")
        env.tokens.set("youtube", Tokens("YA1", "YR1", userId = "UCme", userName = "Me"))
    }

    private fun vid(i: Int) = "vid" + i.toString().padStart(8, '0')
    private fun item(i: Int, title: String = "Artist $i - Song $i (Official Audio)", owner: String? = "Artist $i - Topic") =
        """{"kind":"youtube#playlistItem","etag":"e$i","id":"PLI$i","snippet":{"publishedAt":"2024-01-01T00:00:00Z","title":${q(title)},"description":"",
           "playlistId":"PL1","position":$i,"resourceId":{"kind":"youtube#video","videoId":"${vid(i)}"}${owner?.let { ",\"videoOwnerChannelTitle\":${q(it)}" } ?: ""}},
           "contentDetails":{"videoId":"${vid(i)}","videoPublishedAt":"2020-01-01T00:00:00Z"}}"""
    private fun page(items: List<String>, next: String?) =
        """{"kind":"youtube#playlistItemListResponse","etag":"x",${next?.let { "\"nextPageToken\":${q(it)}," } ?: ""}"items":[${items.joinToString(",")}],"pageInfo":{"totalResults":120,"resultsPerPage":50}}"""
    private fun videos(ids: List<Int>) = """{"items":[${ids.joinToString(",") { """{"id":"${vid(it)}","contentDetails":{"duration":"PT3M${it % 60}S"}}""" }}]}"""

    @Test fun pagesByTokenSkipsPlaceholdersAndBatchesDurations() = runBlocking {
        json(page((0 until 50).map { item(it) }, "CDIQAA"))
        json(page((50 until 100).map { item(it) }, "CGQQAA"))
        json(page((100 until 117).map { item(it) } + listOf(
            """{"id":"PLIp","snippet":{"title":"Private video","description":"This video is private.","resourceId":{"videoId":"priv"}},"contentDetails":{"videoId":"priv"}}""",
            """{"id":"PLId","snippet":{"title":"Deleted video","description":"This video is unavailable.","resourceId":{"videoId":"del"}},"contentDetails":{"videoId":"del"}}""",
            item(0).replace("\"PLI0\"", "\"PLI0dup\""),
            item(117, title = UNICODE, owner = null),
        ), null))
        json(videos((0 until 50).toList())); json(videos((50 until 100).toList())); json(videos((100 until 118).toList()))
        val ts = yt.tracks(env, "PL1")
        assertEquals(119, ts.size)
        assertEquals("PLI0dup", ts[117].itemId)
        assertEquals(vid(0), ts[117].id)
        assertEquals(183_000L, ts[3].durationMs)
        val r1 = next()
        assertCall(r1, "GET", api, "/youtube/v3/playlistItems")
        assertEquals("snippet,contentDetails", r1.query("part"))
        assertEquals("50", r1.query("maxResults"))
        assertEquals("PL1", r1.query("playlistId"))
        assertNull(r1.query("pageToken"))
        assertEquals("Bearer YA1", r1.getHeader("Authorization"))
        assertEquals("CDIQAA", next().query("pageToken"))
        assertEquals("CGQQAA", next().query("pageToken"))
        val v = next()
        assertCall(v, "GET", api, "/youtube/v3/videos")
        assertEquals(50, v.query("id")!!.split(',').size)
        next(); assertEquals(18, next().query("id")!!.split(',').size) // distinct ids: the duplicate is not asked twice
        noMoreRequests()
        assertEquals(6L, QuotaMeter.used(env.store.data, "youtube"))
    }

    @Test fun emptyPlaylistReportedAsNotFound() = runBlocking {
        json("""{"error":{"code":404,"message":"The playlist identified with the request's <code>playlistId</code> parameter cannot be found.","errors":[{"reason":"playlistNotFound","domain":"youtube.playlistItem"}]}}""", 404)
        json("""{"items":[{"id":"PL1","contentDetails":{"itemCount":0}}]}""")
        assertEquals(emptyList<Track>(), yt.tracks(env, "PL1"))
        next()
        assertEquals("contentDetails", next().query("part"))
    }

    @Test fun addOneInsertPerVideoInOrder() = runBlocking {
        repeat(3) { json("""{"kind":"youtube#playlistItem","id":"new"}""") }
        yt.addTracks(env, "PL1", listOf(Track(vid(2), "b"), Track(vid(1), "a"), Track(vid(2), "b")))
        val sent = (0 until 3).map {
            val r = next()
            assertCall(r, "POST", api, "/youtube/v3/playlistItems")
            assertEquals("snippet", r.query("part"))
            val s = r.jsonBody()["snippet"]
            assertEquals("PL1", s["playlistId"].str)
            assertEquals("youtube#video", s["resourceId"]["kind"].str)
            s["resourceId"]["videoId"].str
        }
        assertEquals(listOf(vid(2), vid(1), vid(2)), sent)
        assertEquals(150L, QuotaMeter.used(env.store.data, "youtube"))
    }

    /**
     * Since 2026-06-01 search.list has its own bucket of 100 calls a day and takes no units from the
     * 10,000 (developers.google.com/youtube/v3/determine_quota_cost): a search counts one search and
     * no units; the videos.list that follows it costs its one unit as before.
     */
    @Test fun aSearchCountsInItsOwnBucketNotInTheUnits() = runBlocking {
        json("""{"items":[{"id":{"kind":"youtube#video","videoId":"${vid(1)}"}}]}""")
        json("""{"items":[{"id":"${vid(1)}","snippet":{"title":"Artist - Song","channelTitle":"Artist"},"contentDetails":{"duration":"PT3M"}}]}""")
        json("""{"items":[]}""")
        yt.search(env, Track("x", "Song", listOf("Artist")))
        yt.search(env, Track("y", "Other", listOf("Artist")))
        val s = next()
        assertCall(s, "GET", api, "/youtube/v3/search")
        assertCall(next(), "GET", api, "/youtube/v3/videos")
        assertCall(next(), "GET", api, "/youtube/v3/search")
        noMoreRequests()
        assertEquals(2L, QuotaMeter.searchesUsed(env.store.data, "youtube"))
        assertEquals(1L, QuotaMeter.used(env.store.data, "youtube"))
        assertEquals(100L, QuotaMeter.YOUTUBE_SEARCH_DAILY)
        assertEquals(10_000L, QuotaMeter.YOUTUBE_DAILY)
    }

    @Test fun aFailedSearchIsNotCounted() = runBlocking {
        json("""{"error":{"code":403,"message":"quota","errors":[{"reason":"quotaExceeded"}]}}""", 403)
        try { yt.search(env, Track("x", "Song", listOf("Artist"))); fail() } catch (e: ProviderException) {
            assertTrue(e.message, e.message!!.contains("quota"))
        }
        assertEquals(0L, QuotaMeter.searchesUsed(env.store.data, "youtube"))
    }

    @Test fun removeByPlaylistItemId() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        yt.removeTracks(env, "PL1", listOf(Track(vid(0), "a", itemId = "PLI0dup"), Track(vid(1), "b")))
        val r = next()
        assertCall(r, "DELETE", api, "/youtube/v3/playlists".replace("playlists", "playlistItems"))
        assertEquals("PLI0dup", r.query("id"))
        noMoreRequests()
    }

    @Test fun createPrivateWithUnicode() = runBlocking {
        json("""{"kind":"youtube#playlist","id":"PLnew","snippet":{"title":${q(UNICODE)}},"status":{"privacyStatus":"private"}}""")
        assertEquals("PLnew", yt.createPlaylist(env, UNICODE, LONG).id)
        val r = next()
        assertCall(r, "POST", api, "/youtube/v3/playlists")
        assertEquals("snippet,status", r.query("part"))
        assertEquals(UNICODE, r.jsonBody()["snippet"]["title"].str)
        assertEquals(LONG, r.jsonBody()["snippet"]["description"].str)
        assertEquals("private", r.jsonBody()["status"]["privacyStatus"].str)
    }

    private val current = """{"items":[{"kind":"youtube#playlist","id":"PL1","snippet":{"title":"Old","description":"Synced with Trackhop","defaultLanguage":"it",
        "tags":["a","b"],"channelId":"UCme","thumbnails":{}},"status":{"privacyStatus":"private"}}]}"""

    /**
     * Regression: playlists.update replaces the whole part it is given ("a property that already
     * has a value and is not in the request is deleted", per the reference), so a rename that sent
     * only the title wiped the description. The current snippet is read and carried over.
     */
    @Test fun renameKeepsTheDescription() = runBlocking {
        json(current); json("""{"id":"PL1"}""")
        yt.renamePlaylist(env, "PL1", UNICODE)
        val g = next()
        assertCall(g, "GET", api, "/youtube/v3/playlists")
        assertEquals("snippet", g.query("part")); assertEquals("PL1", g.query("id"))
        val p = next()
        assertCall(p, "PUT", api, "/youtube/v3/playlists")
        assertEquals("snippet", p.query("part"))
        val s = p.jsonBody()["snippet"]
        assertEquals(UNICODE, s["title"].str)
        assertEquals("Synced with Trackhop", s["description"].str)
        assertEquals("it", s["defaultLanguage"].str)
        assertEquals("PL1", p.jsonBody()["id"].str)
    }

    @Test fun visibilityKeepsTitleAndDescription() = runBlocking {
        json(current); json("""{"id":"PL1"}""")
        yt.setPlaylistVisibility(env, "PL1", true)
        next()
        val p = next()
        assertEquals("snippet,status", p.query("part"))
        assertEquals("Old", p.jsonBody()["snippet"]["title"].str)
        assertEquals("Synced with Trackhop", p.jsonBody()["snippet"]["description"].str)
        assertEquals("public", p.jsonBody()["status"]["privacyStatus"].str)
    }

    @Test fun deleteById() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        yt.deletePlaylist(env, "PL1")
        val r = next()
        assertCall(r, "DELETE", api, "/youtube/v3/playlists")
        assertEquals("PL1", r.query("id"))
    }

    @Test fun quotaExceededIsSaidPlainly() = runBlocking {
        json("""{"error":{"code":403,"message":"The request cannot be completed because you have exceeded your <a href=\"/youtube/v3/getting-started#quota\">quota</a>.","errors":[{"message":"x","domain":"youtube.quota","reason":"quotaExceeded"}]}}""", 403)
        try { yt.playlists(env); fail() } catch (e: ProviderException) { assertEquals("YouTube Music (Google API) API 403: the daily YouTube API quota (a limit set by Google, not by Trackhop) is used up; it resets at midnight Pacific time", e.message) }
        assertEquals(1, server.requestCount)
    }

    /** Regression: a non-JSON error page ends as a clear error, not a JSON parser crash. */
    @Test fun nonJsonErrorBodyIsAClearError() = runBlocking {
        text("<html><title>Error 500 (Server Error)!!1</title></html>", 500)
        try { yt.playlists(env); fail() } catch (e: ProviderException) { assertTrue(e.message, e.message!!.contains("API 500: <html>")) }
    }

    @Test fun refreshWithOwnClientSecret() = runBlocking {
        env.store.updateSettings { it.copy(clientIds = mapOf("youtube" to "own.apps.googleusercontent.com"), clientSecrets = mapOf("youtube" to "GOCSPX-own")) }
        json("""{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}""", 401)
        json("""{"access_token":"YA2","expires_in":3599,"scope":"https://www.googleapis.com/auth/youtube","token_type":"Bearer"}""")
        json("""{"items":[]}""")
        yt.playlists(env)
        next()
        val t = next()
        assertCall(t, "POST", "oauth2.googleapis.com", "/token")
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "YR1", "client_id" to "own.apps.googleusercontent.com", "client_secret" to "GOCSPX-own"), t.form())
        assertEquals("Bearer YA2", next().getHeader("Authorization"))
    }

    @Test fun refreshRevokedClearsTheSession() = runBlocking {
        json("", 401)
        json("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", 400)
        try { yt.playlists(env); fail() } catch (e: ProviderException) { assertTrue(e.message!!.contains("session expired, please reconnect (400)")) }
        assertNull(env.tokens.get("youtube"))
    }

    // ------------------------------------------------------------------ a playlist's picture

    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    @Test fun aCoverIsThePlaylistsHeroImageInAMultipartUpload() = runBlocking {
        json("""{"kind":"youtube#playlistImageListResponse","items":[]}""")
        json("""{"kind":"youtube#playlistImage","id":"made-up-image","snippet":{"playlistId":"PL1","type":"hero"}}""")
        yt.setPlaylistCover(env, "PL1", jpeg)

        val list = next()
        assertCall(list, "GET", api, "/youtube/v3/playlistImages")
        assertEquals("PL1", list.query("parent"))
        val up = next()
        assertCall(up, "POST", api, "/upload/youtube/v3/playlistImages")
        assertEquals("multipart", up.query("uploadType"))
        assertEquals("snippet", up.query("part"))
        assertEquals("Bearer YA1", up.getHeader("Authorization"))
        assertTrue(up.getHeader("Content-Type")!!.startsWith("multipart/related; boundary="))
        val body = up.body.clone().readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("\"playlistId\":\"PL1\""))
        assertTrue(text.contains("\"type\":\"hero\""))
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue(text.contains(String(jpeg, Charsets.ISO_8859_1)))
        noMoreRequests()
        assertEquals(QuotaMeter.COST_LIST + QuotaMeter.COST_WRITE, QuotaMeter.used(env.store.data, "youtube"))
    }

    @Test fun aPlaylistWithAnImageHasItReplaced() = runBlocking {
        json("""{"items":[{"kind":"youtube#playlistImage","id":"made-up-image","snippet":{"playlistId":"PL1","type":"hero"}}]}""")
        json("""{"kind":"youtube#playlistImage","id":"made-up-image"}""")
        yt.setPlaylistCover(env, "PL1", jpeg)
        next()
        val up = next()
        assertCall(up, "PUT", api, "/upload/youtube/v3/playlistImages")
        assertTrue(String(up.body.clone().readByteArray(), Charsets.ISO_8859_1).contains("\"id\":\"made-up-image\""))
    }

    @Test fun noCoverWhenTheDaysQuotaIsMostlyUsed() = runBlocking {
        QuotaMeter.add(env, "youtube", QuotaMeter.YOUTUBE_DAILY - 100)
        try { yt.setPlaylistCover(env, "PL1", jpeg); fail() } catch (e: ProviderException) { assertTrue(e.message!!.contains("quota")) }
        noMoreRequests()
    }
}

package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.SpotifyProvider
import com.xlollx.songport.sync.Podcasts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Followed podcasts on each connector that has them, offline: Spotify's /me/shows (and /me/library
 * for shows), Deezer's /user/me/podcasts, and Apple's public podcast directory (iTunes Search API,
 * search and lookup). Shapes as each reference documents them; none checked against a live account.
 */
class PodcastsContractTest : ContractTest() {
    private val sp = SpotifyProvider()
    private val dz = DeezerProvider()

    @Before fun signIn() {
        BuildDefaults.values = mapOf("SPOTIFY_CLIENT_ID" to "cid123")
        env.tokens.set("spotify", Tokens(accessToken = "AT1", refreshToken = "RT1", userId = "me_user", userName = "Me"))
        env.tokens.set("deezer", Tokens(accessToken = "DZTOKEN", userId = "42", userName = "Me"))
        Podcasts.reset()
        Podcasts.pace = Pacer(0)
    }

    @After fun restorePace() {
        Podcasts.pace = Pacer(3_000)
        Podcasts.reset()
    }

    private fun show(id: String, name: String, publisher: String? = "Pub $id") =
        """{"id":"$id","name":${q(name)},"publisher":${publisher?.let { q(it) } ?: "null"},"uri":"spotify:show:$id","type":"show","total_episodes":12}"""

    // ------------------------------------------------------------------ Spotify

    @Test fun spotifyFollowedShowsReadPageByPage() = runBlocking {
        json("""{"items":[{"added_at":"2024-01-01T00:00:00Z","show":${show("sh1", "The Daily")}},{"added_at":"2024-01-02T00:00:00Z","show":null},
                {"added_at":"2024-01-03T00:00:00Z","show":${show("sh2", "Ñandú 🎧", publisher = null)}}],
                "next":"https://api.spotify.com/v1/me/shows?offset=50&limit=50","total":3}""")
        json("""{"items":[{"added_at":"2024-01-04T00:00:00Z","show":${show("sh3", "Radiolab")}}],"next":null,"total":3}""")
        val shows = sp.tracks(env, MusicProvider.PODCASTS_ID)
        assertEquals(listOf("sh1", "sh2", "sh3"), shows.map { it.id })
        assertTrue(shows.all { it.kind == Track.KIND_PODCAST })
        assertEquals(listOf("Pub sh1"), shows[0].artists)
        assertEquals(emptyList<String>(), shows[1].artists)
        assertEquals("spotify:show:sh3", shows[2].uri)
        val r1 = next()
        assertCall(r1, "GET", "api.spotify.com", "/v1/me/shows")
        assertEquals("50", r1.query("limit"))
        assertEquals("Bearer AT1", r1.getHeader("Authorization"))
        assertEquals("50", next().query("offset"))
        noMoreRequests()
    }

    @Test fun spotifyShowsVersionIsTotalAndNewest() = runBlocking {
        json("""{"items":[{"added_at":"2024-05-01T00:00:00Z","show":${show("sh1", "A")}}],"total":7}""")
        assertEquals("7|2024-05-01T00:00:00Z", sp.playlistVersion(env, MusicProvider.PODCASTS_ID))
        assertEquals("1", next().query("limit"))
    }

    @Test fun spotifySearchesShowsByTheirName() = runBlocking {
        json("""{"shows":{"items":[${show("sh9", "The Daily", "The New York Times")},null,{"name":"no id"}]}}""")
        val found = sp.search(env, Track(id = "", title = "The Daily", artists = listOf("NYT"), kind = Track.KIND_PODCAST))
        assertEquals(listOf("sh9"), found.map { it.id })
        assertEquals(listOf("The New York Times"), found[0].artists)
        assertEquals(Track.KIND_PODCAST, found[0].kind)
        val r = next()
        assertCall(r, "GET", "api.spotify.com", "/v1/search")
        assertEquals("show", r.query("type"))
        assertEquals("The Daily", r.query("q"))
        noMoreRequests()
    }

    @Test fun spotifyFollowAndUnfollowThroughTheLibraryThenTheOlderPath() = runBlocking {
        // /me/library takes shows by URI.
        json("")
        sp.addTracks(env, MusicProvider.PODCASTS_ID, listOf(Track("sh1", "A", kind = Track.KIND_PODCAST)))
        val a = next()
        assertCall(a, "PUT", "api.spotify.com", "/v1/me/library")
        assertEquals("spotify:show:sh1", a.query("uris"))
        // Where /me/library is missing (404), /me/shows?ids=, fifty at a time.
        json("""{"error":{"status":404,"message":"Not found."}}""", 404)
        json(""); json("")
        sp.removeTracks(env, MusicProvider.PODCASTS_ID, (0 until 60).map { Track("s$it", "S", kind = Track.KIND_PODCAST) })
        assertCall(next(), "DELETE", "api.spotify.com", "/v1/me/library")
        val d1 = next()
        assertCall(d1, "DELETE", "api.spotify.com", "/v1/me/shows")
        assertEquals(50, d1.query("ids")!!.split(',').size)
        assertEquals(10, next().query("ids")!!.split(',').size)
        noMoreRequests()
    }

    // ------------------------------------------------------------------ Deezer

    private fun podcast(id: Long, title: String) =
        """{"id":$id,"title":${q(title)},"description":"d","available":true,"fans":10,"link":"https://www.deezer.com/show/$id","picture":"p","type":"podcast"}"""

    @Test fun deezerFollowedPodcastsReadPageByPage() = runBlocking {
        json("""{"data":[${podcast(11, "The Daily")},{"id":null,"title":"x"},${podcast(12, UNICODE)}],"total":3,"next":"https://api.deezer.com/user/me/podcasts?limit=100&index=100"}""")
        json("""{"data":[${podcast(13, "Radiolab")}],"total":3}""")
        val shows = dz.tracks(env, MusicProvider.PODCASTS_ID)
        assertEquals(listOf("11", "12", "13"), shows.map { it.id })
        assertEquals(UNICODE, shows[1].title)
        assertTrue(shows.all { it.kind == Track.KIND_PODCAST && it.artists.isEmpty() })
        val r = next()
        assertCall(r, "GET", "api.deezer.com", "/user/me/podcasts")
        assertEquals("DZTOKEN", r.query("access_token"))
        assertEquals("100", next().query("index"))
        noMoreRequests()
    }

    @Test fun deezerSearchFollowAndUnfollowOnePodcastAtATime() = runBlocking {
        json("""{"data":[${podcast(21, "The Daily")}],"total":1}""")
        val found = dz.search(env, Track(id = "", title = "The Daily", kind = Track.KIND_PODCAST))
        assertEquals(listOf("21"), found.map { it.id })
        val s = next()
        assertCall(s, "GET", "api.deezer.com", "/search/podcast")
        assertEquals("The Daily", s.query("q"))

        json("true"); json("true")
        dz.addTracks(env, MusicProvider.PODCASTS_ID, listOf(Track("21", "A"), Track("22", "B")))
        for (id in listOf("21", "22")) {
            val r = next()
            assertCall(r, "POST", "api.deezer.com", "/user/me/podcasts")
            assertEquals(id, r.query("podcast_id") ?: r.form()["podcast_id"])
        }
        json("true")
        dz.removeTracks(env, MusicProvider.PODCASTS_ID, listOf(Track("21", "A")))
        val d = next()
        assertCall(d, "DELETE", "api.deezer.com", "/user/me/podcasts")
        assertEquals("21", d.query("podcast_id"))
        noMoreRequests()
    }

    // ------------------------------------------------------------------ Apple's directory (iTunes Search API)

    private fun result(id: Long, name: String, artist: String?, feed: String?) =
        """{"wrapperType":"track","kind":"podcast","collectionId":$id,"trackId":$id,"artistName":${artist?.let { q(it) } ?: "null"},
           "collectionName":${q(name)},"trackName":${q(name)},"feedUrl":${feed?.let { q(it) } ?: "null"},"collectionViewUrl":"https://podcasts.apple.com/us/podcast/x/id$id"}"""

    private fun results(vararg r: String) = """{"resultCount":${r.size},"results":[${r.joinToString(",")}]}"""

    @Test fun directorySearchIsNamedPacedAndRemembered() = runBlocking {
        json(results(result(1200361736, "The Daily", "The New York Times", "https://feeds.simplecast.com/54nAGcIl"),
            """{"wrapperType":"track","kind":"podcast-episode","trackId":5,"trackName":"An episode"}""",
            result(2, "The Daily Show", "Comedy Central", null)))
        val found = Podcasts.search(env, "The Daily")
        assertEquals(listOf("1200361736", "2"), found.map { it.itunesId })
        assertEquals("https://feeds.simplecast.com/54nAGcIl", found[0].feedUrl)
        assertEquals(listOf("The New York Times"), found[0].artists)
        assertNull(found[1].feedUrl)
        val r = next()
        assertCall(r, "GET", "itunes.apple.com", "/search")
        assertEquals("podcast", r.query("media"))
        assertEquals("podcast", r.query("entity"))
        assertEquals("The Daily", r.query("term"))
        assertTrue(r.getHeader("User-Agent")!!.startsWith("Trackhop"))
        // Asked again (another spelling of the same title): from memory, no request.
        assertEquals(found, Podcasts.search(env, "the daily"))
        // And from the store once memory is gone.
        Podcasts.reset()
        assertEquals(found.map { it.itunesId }, Podcasts.search(env, "THE DAILY").map { it.itunesId })
        noMoreRequests()
    }

    @Test fun directoryLookupByAppleId() = runBlocking {
        json(results(result(788236947, "Song Exploder", "Hrishikesh Hirway", "https://feeds.example.com/songexploder")))
        val s = Podcasts.lookup(env, "https://podcasts.apple.com/us/podcast/song-exploder/id788236947")
        assertEquals("Song Exploder", s?.title)
        val r = next()
        assertCall(r, "GET", "itunes.apple.com", "/lookup")
        assertEquals("788236947", r.query("id"))
        assertEquals("podcast", r.query("entity"))
        assertNull(Podcasts.lookup(env, "not an id"))
        noMoreRequests()
    }

    @Test fun aSilentDirectoryIsLeftAloneAfterAFewFailuresAndNeverFailsTheCaller() = runBlocking {
        repeat(3) { text("Forbidden", 403, "text/plain") }
        for (term in listOf("A", "B", "C")) assertEquals(emptyList<Track>(), Podcasts.search(env, term))
        repeat(3) { next() }
        // Resting: nothing asked, nothing remembered as "not found".
        assertEquals(emptyList<Track>(), Podcasts.search(env, "D"))
        noMoreRequests()
        assertTrue(env.logs.any { it.contains("not answering") })
    }

    @Test fun anOpmlExportFindsTheFeedsTheServiceDoesNotGive() = runBlocking {
        val spotify = listOf(
            Track("sh1", "The Daily", listOf("The New York Times"), kind = Track.KIND_PODCAST),
            Track("sh2", "Known feed", kind = Track.KIND_PODCAST, feedUrl = "https://k.example/f"),
            Track("sh3", "Obscure Show", listOf("Nobody"), kind = Track.KIND_PODCAST),
        )
        json(results(result(2, "The Daily Show", "Comedy Central", "https://cc.example/f"), result(1200361736, "The Daily", "The New York Times", "https://feeds.simplecast.com/54nAGcIl")))
        // Only a namesake by another publisher: not taken, a wrong feed would follow another show.
        json(results(result(9, "Obscure Show Two", "Somebody Else", "https://o.example/f")))
        val progress = ArrayList<Pair<Int, Int>>()
        val (text, missing) = LocalFilesProvider.encodeLooked(env, LocalFilesProvider.Export.OPML, "Followed podcasts", spotify) { d, t -> progress += d to t }
        assertEquals(1, missing)
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        assertTrue(text.contains("""xmlUrl="https://feeds.simplecast.com/54nAGcIl""""))
        assertTrue(text.contains("""xmlUrl="https://k.example/f""""))
        assertTrue(text.contains("""<outline text="Obscure Show" title="Obscure Show"/>"""))
        assertEquals("The Daily", next().query("term"))
        assertEquals("Obscure Show", next().query("term"))
        noMoreRequests()
    }

    @Test fun aShowFromAnOpmlFileGetsItsPublisherByItsFeed() = runBlocking {
        val fromFile = Track("f1", "The Daily", kind = Track.KIND_PODCAST, feedUrl = "http://feeds.simplecast.com/54nAGcIl/")
        json(results(result(2, "The Daily", "Someone Else", "https://other.example/daily"), result(1200361736, "The Daily", "The New York Times", "https://feeds.simplecast.com/54nAGcIl")))
        val e = Podcasts.enrich(env, fromFile)
        assertEquals(listOf("The New York Times"), e.artists)
        assertEquals("1200361736", e.itunesId)
        assertEquals(fromFile.feedUrl, e.feedUrl)
        next()
        // A show known by name alone is left as it is, with no request: the directory would only guess.
        val byName = Track("s", "The Daily", kind = Track.KIND_PODCAST)
        assertEquals(byName, Podcasts.enrich(env, byName))
        // A song is never looked up.
        val song = Track("t", "Song", feedUrl = "https://x.example/f")
        assertEquals(song, Podcasts.enrich(env, song))
        noMoreRequests()
    }
}

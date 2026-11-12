package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.BuildDefaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * The exact artist ids ([ArtistIds]) where the app knows them: New releases browsed by MusicBrainz
 * id, setlist.fm asked by MusicBrainz id, Ticketmaster asked by attraction id; each with the name
 * search as it was when the id is unknown or not answered.
 */
class ArtistIdsTest {
    private val mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** Path (with the decoded query) to status and body; 404 when nothing fits. */
    private var answer: (String) -> Pair<Int, String> = { 404 to "{}" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val asked = ex.requestURI.path + "?" + URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += asked
            val (code, body) = answer(asked)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val env = ContractEnv()
    private val today = LocalDate.of(2026, 11, 10)
    private val clock = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() {
        NewReleases.deezer = "$base/dz"
        NewReleases.musicBrainz = "$base/ws/2"
        NewReleases.now = { clock }
        NewReleases.deezerPace = Pacer(0)
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
        Setlists.base = "$base/rest"
        Setlists.now = { clock }
        Setlists.pace = Pacer(0)
        Concerts.tmBase = "$base/tm"
        Concerts.now = { clock }
        Concerts.tmPace = Pacer(0)
        BuildDefaults.values = mapOf(Setlists.BUILD_KEY to "SF", Concerts.BUILD_KEY to "TM")
    }

    @After fun tearDown() {
        server.stop(0)
        NewReleases.deezer = "https://api.deezer.com"
        NewReleases.musicBrainz = "https://musicbrainz.org/ws/2"
        NewReleases.now = { System.currentTimeMillis() }
        NewReleases.deezerPace = Pacer(150)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
        Setlists.base = "https://api.setlist.fm/rest/1.0"
        Setlists.now = { System.currentTimeMillis() }
        Setlists.pace = Pacer(550)
        Concerts.tmBase = "https://app.ticketmaster.com/discovery/v2"
        Concerts.now = { System.currentTimeMillis() }
        Concerts.tmPace = Pacer(220)
        BuildDefaults.values = emptyMap()
    }

    private fun follow(name: String, id: String?) =
        env.store.updateSettings { it.copy(followedArtists = listOf(ArtistAlerts.Followed(name, mbid = id))) }

    // ------------------------------------------------------------------ what is known

    @Test fun aFollowedArtistsIdIsKnownWhateverTheSpelling() {
        assertNull(ArtistIds.mbid(env, "Muse"))
        follow("MUSE", mbid.uppercase())
        assertEquals(mbid, ArtistIds.mbid(env, "Muse"))
        assertNull(ArtistIds.mbid(env, "Blur"))
    }

    @Test fun anIdIsRememberedOnlyFromAnAnswerWithASingleArtistOfTheName() {
        val one = """{"artists":[{"id":"$mbid","name":"Muse","score":100},{"id":"b74b1b7f-71a5-4011-9441-d0b5e4122711","name":"Muse & Friends","score":95}]}"""
        val two = """{"artists":[{"id":"$mbid","name":"Muse","score":100},{"id":"b74b1b7f-71a5-4011-9441-d0b5e4122711","name":"Muse","score":98}]}"""
        assertEquals(mbid, ArtistIds.singleMbArtist(one, "Muse"))
        assertNull(ArtistIds.singleMbArtist(two, "Muse"))
        ArtistIds.rememberMbid(env, "Muse", ArtistIds.singleMbArtist(one, "Muse"))
        assertEquals(mbid, ArtistIds.mbid(env, "muse"))
    }

    @Test fun theAttractionOfAnAnswerIsTheOneWithTheArtistsMusicBrainzIdOrTheOnlyOneOfTheName() {
        fun body(vararg attractions: String) = """{"_embedded":{"events":[{"name":"x","_embedded":{"attractions":[${attractions.joinToString(",")}]}}]}}"""
        val muse = """{"id":"K1","name":"Muse","externalLinks":{"musicbrainz":[{"id":"$mbid"}]}}"""
        val other = """{"id":"K2","name":"Muse"}"""
        val tribute = """{"id":"K3","name":"Muse Tribute"}"""
        assertEquals("K1", ArtistIds.ticketmasterAttraction(body(other, muse), "Muse", mbid))
        assertNull(ArtistIds.ticketmasterAttraction(body(other, muse), "Muse", null))
        assertEquals("K2", ArtistIds.ticketmasterAttraction(body(other, tribute), "Muse", null))
        assertNull(ArtistIds.ticketmasterAttraction("not json", "Muse", null))
    }

    // ------------------------------------------------------------------ New releases

    private fun group(title: String, date: String, type: String = "Album", credited: String = "Muse") =
        """{"id":"${title.hashCode().toUInt()}0000-0000-4000-8000-000000000000","title":"$title","primary-type":"$type","secondary-types":[],"first-release-date":"$date",
           "artist-credit":[{"name":"$credited","artist":{"name":"$credited"}}]}"""

    @Test fun newReleasesBrowseTheGroupsOfAKnownArtist() = runBlocking {
        follow("Muse", mbid)
        answer = { a ->
            when {
                a.startsWith("/ws/2/release-group") -> 200 to """{"release-group-count":3,"release-groups":[${group("Will of the People", "2022-08-26")},
                    ${group("Unravelling", "2026-11-05", "Single", credited = "MUSE")},${group("Live Thing", "2026-11-06")}]}"""
                a.startsWith("/dz/search/artist") -> 200 to """{"data":[]}"""
                else -> 404 to "{}"
            }
        }
        val found = NewReleases.ofArtist(env, "Muse")!!
        // Browsed by id: every group is theirs, the one credited under another spelling too; the old one is out of the window.
        assertEquals(setOf("Unravelling", "Live Thing"), found.map { it.title }.toSet())
        val browse = hits.single { it.startsWith("/ws/2/release-group") }
        assertTrue(browse, browse.contains("artist=$mbid"))
        assertTrue(browse, browse.contains("type=album|ep|single"))
        assertTrue(hits.none { it.contains("query=") })
    }

    @Test fun newReleasesSearchTheNameWhenTheBrowseDoesNotAnswer() = runBlocking {
        follow("Muse", mbid)
        answer = { a ->
            when {
                a.startsWith("/ws/2/release-group?artist=") -> 503 to "{}"
                a.startsWith("/ws/2/release-group?query=") -> 200 to """{"release-groups":[${group("Unravelling", "2026-11-05", "Single")}]}"""
                a.startsWith("/dz/search/artist") -> 200 to """{"data":[]}"""
                else -> 404 to "{}"
            }
        }
        assertEquals(listOf("Unravelling"), NewReleases.ofArtist(env, "Muse")!!.map { it.title })
        assertEquals(2, hits.count { it.startsWith("/ws/2/release-group") })
    }

    @Test fun newReleasesOfAnUnknownArtistAreSearchedByNameAsBefore() = runBlocking {
        answer = { a ->
            when {
                a.startsWith("/ws/2/release-group?query=") -> 200 to """{"release-groups":[]}"""
                a.startsWith("/dz/search/artist") -> 200 to """{"data":[]}"""
                else -> 404 to "{}"
            }
        }
        NewReleases.ofArtist(env, "Muse")
        assertTrue(hits.none { it.startsWith("/ws/2/release-group?artist=") })
    }

    // ------------------------------------------------------------------ setlist.fm

    private fun setlist(id: String, artist: String) = """{"id":"$id","eventDate":"08-11-2026","artist":{"mbid":"$mbid","name":"$artist"},
        "venue":{"name":"Forum","city":{"name":"Milano","country":{"code":"IT"}}},"url":"https://www.setlist.fm/setlist/x/forum-$id.html",
        "sets":{"set":[{"song":[{"name":"Hysteria"}]}]}}"""

    @Test fun setlistsOfAKnownArtistAreAskedByItsId() = runBlocking {
        follow("Muse", mbid)
        answer = { a -> if (a.startsWith("/rest/artist/$mbid/setlists")) 200 to """{"setlist":[${setlist("1a", "MUSE (UK)")}]}""" else 404 to "{}" }
        val r = Setlists.find(env, listOf("Muse"))
        assertEquals(listOf("1a"), r.artists.single().setlists.map { it.id })
        assertTrue(hits.none { it.startsWith("/rest/search") })
    }

    @Test fun setlistsFallBackToTheNameWhenSetlistFmDoesNotKnowTheId() = runBlocking {
        follow("Muse", mbid)
        answer = { a ->
            when {
                a.startsWith("/rest/search/setlists") -> 200 to """{"setlist":[${setlist("2b", "Muse")}]}"""
                else -> 404 to """{"code":404}"""
            }
        }
        val r = Setlists.find(env, listOf("Muse"))
        assertEquals(listOf("2b"), r.artists.single().setlists.map { it.id })
        assertEquals(listOf("/rest/artist/$mbid/setlists", "/rest/search/setlists"), hits.map { it.substringBefore('?') })
    }

    // ------------------------------------------------------------------ Ticketmaster

    private fun tmEvent(date: String, attraction: String, id: String) = """
        {"name":"$attraction live","url":"https://www.ticketmaster.com/event/$date","dates":{"start":{"localDate":"$date"},"status":{"code":"onsale"}},
         "_embedded":{"venues":[{"name":"Arena","city":{"name":"Boston"},"country":{"countryCode":"US"}}],"attractions":[{"id":"$id","name":"$attraction"}]}}"""

    @Test fun concertsLearnTheAttractionAndAskByItNextTime() = runBlocking {
        answer = { a ->
            when {
                a.startsWith("/tm/events.json") && "keyword=Muse" in a -> 200 to """{"_embedded":{"events":[${tmEvent("2026-12-01", "Muse", "K1")},${tmEvent("2026-12-02", "Muse Tribute", "K9")}]}}"""
                a.startsWith("/tm/events.json") && "attractionId=K1" in a -> 200 to """{"_embedded":{"events":[${tmEvent("2026-12-03", "MUSE", "K1")}]}}"""
                else -> 404 to "{}"
            }
        }
        val first = Concerts.find(env, listOf("Muse"), "US")
        assertEquals(listOf("2026-12-01"), first.artists.single().events.filter { it.source == Concerts.Source.TICKETMASTER }.map { it.date })
        assertEquals("K1", ArtistIds.attraction(env, "Muse"))
        // The day's answer kept, then asked again: by the attraction this time.
        Concerts.now = { clock + Concerts.TTL_MS + 1 }
        hits.clear()
        val again = Concerts.find(env, listOf("Muse"), "US")
        assertEquals(listOf("2026-12-03"), again.artists.single().events.filter { it.source == Concerts.Source.TICKETMASTER }.map { it.date })
        assertTrue(hits.filter { it.startsWith("/tm/") }.single().contains("attractionId=K1"))
    }

    @Test fun concertsSearchTheNameWhenTheAttractionDoesNotAnswer() = runBlocking {
        ArtistIds.rememberAttraction(env, "Muse", "K1")
        answer = { a ->
            when {
                "attractionId=K1" in a -> 500 to "{}"
                "keyword=Muse" in a -> 200 to """{"_embedded":{"events":[${tmEvent("2026-12-01", "Muse", "K1")}]}}"""
                else -> 404 to "{}"
            }
        }
        val r = Concerts.find(env, listOf("Muse"), "US")
        assertEquals(listOf("2026-12-01"), r.artists.single().events.filter { it.source == Concerts.Source.TICKETMASTER }.map { it.date })
        assertEquals(2, hits.count { it.startsWith("/tm/") })
    }
}

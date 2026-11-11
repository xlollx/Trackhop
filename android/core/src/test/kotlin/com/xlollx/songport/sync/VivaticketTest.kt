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
import java.util.Collections

/**
 * Vivaticket as an unofficial source of Italian concert dates: the answers its web pages read,
 * recorded in test/resources/concerts/vivaticket (trimmed), and the search with a stand-in site.
 */
class VivaticketTest {
    private fun fixture(name: String) = javaClass.getResource("/concerts/vivaticket/$name")!!.readText()
    private val today = LocalDate.parse("2026-11-10")

    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** The stand-in gateway: path (with its query) to status and body. */
    private var site: (String) -> Pair<Int, String> = { route(it) }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path + "?" + URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += path
            val (code, body) = when {
                path.startsWith("/vt/") -> site(path.removePrefix("/vt/"))
                path.startsWith("/ws/2/event") -> 200 to """{"events":[]}"""
                path.startsWith("/tm/") -> 200 to """{"page":{"totalElements":0}}"""
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            if (code != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    private fun route(p: String): Pair<Int, String> = when {
        p.startsWith("Home/Search/it/it-IT?q=Cesare Cremonini") -> 200 to fixture("search-cremonini.json")
        p.startsWith("Home/Search/") -> 200 to """{"shows":{"items":[],"itemsCount":0},"artists":{"items":[],"itemsCount":0}}"""
        p.startsWith("Artist/66874/") -> 200 to fixture("artist-cremonini.json")
        p.startsWith("Event/313707/") -> 200 to fixture("event-cremonini-bologna.json")
        p.startsWith("Event/") -> 204 to ""
        else -> 404 to "{}"
    }

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Vivaticket.base = "$base/vt"
        Vivaticket.pace = Pacer(0)
        Vivaticket.enabled = true
        Vivaticket.stoppedUntil = 0
        Concerts.tmBase = "$base/tm"
        Concerts.now = { clock }
        Concerts.zone = { java.time.ZoneId.of("Europe/Rome") }
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
        BuildDefaults.values = emptyMap()
    }

    @After fun tearDown() {
        server.stop(0)
        Vivaticket.base = "https://apigatewayb2cstore.vivaticket.com/api"
        Vivaticket.pace = Pacer(1_100)
        Vivaticket.enabled = false
        Vivaticket.stoppedUntil = 0
        Concerts.tmBase = "https://app.ticketmaster.com/discovery/v2"
        Concerts.now = { System.currentTimeMillis() }
        Concerts.zone = { java.time.ZoneId.systemDefault() }
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
    }

    // ------------------------------------------------------------------ reading the answers

    @Test fun theSearchFindsTheArtistByTheExactNameOnly() {
        val body = fixture("search-cremonini.json")
        assertEquals("66874", Vivaticket.artistId(body, "Cesare Cremonini"))
        assertEquals("66874", Vivaticket.artistId(body, "cesare  cremonini"))
        assertNull("a show naming them is not their page", Vivaticket.artistId(body, "Cremonini"))
    }

    @Test fun theArtistsPageKeepsConcertsToComeNotMerchandise() {
        val vasco = Vivaticket.parseArtist(fixture("artist-vasco.json"), today)
        // The three "online exclusive" products (a record, a book, a film) have no city.
        assertEquals(listOf("307081"), vasco.map { it.id })
        val p = vasco.single()
        assertEquals("ROMA", p.city); assertEquals("Stadio Olimpico", p.venue); assertTrue(p.severalDays)
        assertEquals("https://www.vivaticket.com/it/ticket/vasco-residency-show/307081", p.url)
        assertTrue(p.image, p.image.startsWith("https://res.cloudinary.com/vivaticket/"))
        assertEquals(p.image, Vivaticket.toEvents("Vasco Rossi", p, null, today).single().image)
        assertEquals(3, Vivaticket.parseArtist(fixture("artist-cremonini.json"), today).size)
        // Once its last night is over, a product is gone.
        assertTrue(Vivaticket.parseArtist(fixture("artist-vasco.json"), LocalDate.parse("2027-06-26")).isEmpty())
    }

    @Test fun aProductOfSeveralNightsBecomesOneDateANight() {
        val p = Vivaticket.parseArtist(fixture("artist-cremonini.json"), today).first { it.id == "313707" }
        val days = Vivaticket.parseDates(fixture("event-cremonini-bologna.json"))
        assertEquals(listOf("2026-11-27", "2026-11-28", "2026-11-30", "2026-12-02", "2026-12-04", "2026-12-05", "2026-12-06"), days.map { it.first })
        val events = Vivaticket.toEvents("Cesare Cremonini", p, days, today)
        assertEquals(7, events.size)
        val e = events.first()
        assertEquals("21:00", e.time); assertEquals("Casalecchio di Reno", e.city); assertEquals("Unipol Arena", e.venue)
        assertEquals("IT", e.country); assertEquals(Concerts.Source.VIVATICKET, e.source)
        assertEquals("https://www.vivaticket.com/it/ticket/cesare-cremonini/313707", e.url)
        // Nights already past are left out.
        assertEquals(2, Vivaticket.toEvents("Cesare Cremonini", p, days, LocalDate.parse("2026-12-05")).size)
    }

    @Test fun aPageThatRepeatsTheFirstNightKeepsTheFirstNightOnly() {
        val p = Vivaticket.parseArtist(fixture("artist-vasco.json"), today).single()
        val days = Vivaticket.parseDates(fixture("event-vasco-roma.json"))
        assertEquals(listOf("2027-06-06"), Vivaticket.toEvents("Vasco Rossi", p, days, today).map { it.date })
        // Without its page (not opened, or no content): its first night, the one date known for sure.
        assertEquals(listOf("2027-06-06"), Vivaticket.toEvents("Vasco Rossi", p, null, today).map { it.date })
    }

    @Test fun anAnswerOfAnotherShapeStops() {
        for (body in listOf("""{"results":[]}""", "[]", "<html>queue</html>")) {
            assertTrue(body, runCatching { Vivaticket.artistId(body, "A") }.exceptionOrNull() is Vivaticket.Stop)
        }
        assertTrue(runCatching { Vivaticket.parseArtist("""{"items":[]}""", today) }.exceptionOrNull() is Vivaticket.Stop)
        assertTrue(runCatching { Vivaticket.parseDates("""{"infoEvent":{}}""") }.exceptionOrNull() is Vivaticket.Stop)
    }

    @Test fun citiesInCapitalsAreWrittenAsNames() {
        assertEquals("Casalecchio di Reno", Vivaticket.cityName("CASALECCHIO DI RENO"))
        assertEquals("L'Aquila", Vivaticket.cityName("L'AQUILA"))
        assertEquals("Reggio nell'Emilia", Vivaticket.cityName("Reggio nell'Emilia"))
    }

    // ------------------------------------------------------------------ merged with the others

    @Test fun theSameDateOnTicketmasterKeepsBothTicketLinks() {
        val tm = Concerts.Event("Cesare Cremonini", "Cremonini", "2026-11-27", "21:00", "Casalecchio di Reno", "Unipol Arena", "IT", "https://www.ticketmaster.it/x", Concerts.Source.TICKETMASTER)
        val mb = Concerts.Event("Cesare Cremonini", "Cremonini", "2026-11-28", null, "Casalecchio di Reno", "Unipol Arena", "IT", "https://musicbrainz.org/event/x", Concerts.Source.MUSICBRAINZ)
        val p = Vivaticket.parseArtist(fixture("artist-cremonini.json"), today).first { it.id == "313707" }
        val vt = Vivaticket.toEvents("Cesare Cremonini", p, Vivaticket.parseDates(fixture("event-cremonini-bologna.json")), today)
        val merged = Concerts.merge(listOf(tm), listOf(mb), vt)
        assertEquals(7, merged.size)
        val first = merged.first()
        assertEquals(Concerts.Source.TICKETMASTER, first.source)
        assertEquals(listOf(Concerts.TicketLink(Concerts.Source.VIVATICKET, "https://www.vivaticket.com/it/ticket/cesare-cremonini/313707")), first.alsoOn)
        // MusicBrainz's 28 November is Vivaticket's, shown once with its ticket link.
        assertEquals(Concerts.Source.VIVATICKET, merged[1].source)
    }

    // ------------------------------------------------------------------ the search

    @Test fun inItalyItsDatesJoinTheSearchAndAreKeptADay() = runBlocking {
        val r = Concerts.find(env, listOf("Cesare Cremonini", "Nobody Plays"), "IT")
        val cremonini = r.artists.first()
        // Bologna's seven nights, Turin's and Milan's first (their pages had no content).
        assertEquals(9, cremonini.events.size)
        assertTrue(cremonini.events.all { it.source == Concerts.Source.VIVATICKET })
        assertTrue(r.artists.last().events.isEmpty())
        val asked = hits.count { it.startsWith("/vt/") }
        // Search, artist page, three products' pages; Nobody Plays: the search only.
        assertEquals(6, asked)
        Concerts.find(env, listOf("Cesare Cremonini", "Nobody Plays"), "IT")
        assertEquals("asked again within the day", asked, hits.count { it.startsWith("/vt/") })
        clock += Concerts.TTL_MS + 1
        Concerts.find(env, listOf("Cesare Cremonini"), "IT")
        assertTrue("asked again after a day", hits.count { it.startsWith("/vt/") } > asked)
    }

    @Test fun theTimeRangeLimitsItsDatesAndWhatIsOpened() = runBlocking {
        // A month from 10 November: Bologna's seven nights and Turin's first; Assago's (15 December) not opened.
        val r = Concerts.find(env, listOf("Cesare Cremonini"), "IT", Concerts.Range.MONTH)
        assertEquals(8, r.artists.single().events.size)
        assertTrue(r.artists.single().events.all { it.date <= "2026-12-10" })
        assertTrue(hits.none { it.startsWith("/vt/Event/313710") })
    }

    @Test fun elsewhereOrWhereNotAllowedItIsNeverAsked() = runBlocking {
        Concerts.find(env, listOf("Cesare Cremonini"), "FR")
        assertTrue(hits.none { it.startsWith("/vt/") })
        Vivaticket.enabled = false
        assertTrue(Concerts.find(env, listOf("Cesare Cremonini"), "IT").artists.single().events.isEmpty())
        assertTrue(hits.none { it.startsWith("/vt/") })
    }

    @Test fun anErrorStopsItForAnHourWithoutStoppingTheSearch() = runBlocking {
        site = { 403 to "<html>blocked</html>" }
        val r = Concerts.find(env, listOf("A", "B", "C"), "IT")
        assertEquals(3, r.artists.size)
        assertEquals("one refused call, no more for the other artists", 1, hits.count { it.startsWith("/vt/") })
        // Not remembered as "no dates": asked again once the hour is over.
        site = { route(it) }
        clock += Vivaticket.STOP_MS + 1
        assertEquals(9, Concerts.find(env, listOf("Cesare Cremonini"), "IT").artists.single().events.size)
    }

    @Test fun aChangedAnswerStopsItToo() = runBlocking {
        site = { 200 to """{"somethingElse":true}""" }
        Concerts.find(env, listOf("A", "B"), "IT")
        assertEquals(1, hits.count { it.startsWith("/vt/") })
        assertTrue(Vivaticket.stoppedUntil > clock)
    }
}

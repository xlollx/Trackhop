package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.platform.BuildDefaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.Collections

/**
 * TicketOne as an unofficial source of Italian concert dates: what is read out of what its pages
 * show (the browser engine's collector answer), and how the reading is asked, paced, kept and
 * stopped. The fixtures in test/resources/concerts/ticketone are written from how the pages are
 * assumed to look (no browser could load the real site here): the JSON-LD of an artist's page, and
 * a search page's event links as the collector script gathers them.
 */
class TicketOneTest {
    private fun fixture(name: String) = javaClass.getResource("/concerts/ticketone/$name")!!.readText()
    private val today = LocalDate.parse("2026-11-10")

    /** What the collector script answers for an HTML page: its JSON-LD scripts' texts (the rest left empty). */
    private fun collected(html: String, url: String) = jsonObj(
        "v" to 1, "state" to "ready", "url" to url,
        "ld" to jsonArr(LD.findAll(html).map { it.groupValues[1] }.toList()),
        "cards" to jsonArr(emptyList()), "artists" to jsonArr(emptyList()),
    ).toString()

    private val pages: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** The stand-in page reader: address to the collector's answer. */
    private var site: (String) -> String? = { url ->
        when {
            url.contains("/search/") -> fixture("search-artist-only.json")
            url == "https://www.ticketone.it/artist/vasco-rossi/" -> collected(fixture("artist-vasco.html"), url)
            else -> """{"state":"empty","ld":[],"cards":[],"artists":[]}"""
        }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = (if (ex.requestURI.path.startsWith("/ws/2/event")) """{"events":[]}""" else "{}").toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        TicketOne.reader = { url -> pages += url; site(url) }
        TicketOne.gate = { true }
        TicketOne.stoppedUntil = 0
        Concerts.now = { clock }
        Concerts.zone = { java.time.ZoneId.of("Europe/Rome") }
        MusicBrainz.base = "http://127.0.0.1:${server.address.port}/ws/2"
        MusicBrainz.pace = Pacer(0)
        BuildDefaults.values = emptyMap()
    }

    @After fun tearDown() {
        server.stop(0)
        TicketOne.reader = null
        TicketOne.gate = { true }
        TicketOne.stoppedUntil = 0
        Vivaticket.enabled = false
        Concerts.now = { System.currentTimeMillis() }
        Concerts.zone = { java.time.ZoneId.systemDefault() }
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
    }

    // ------------------------------------------------------------------ reading a page

    @Test fun theArtistsOwnDatesInItalyFromThePagesJsonLd() {
        val page = TicketOne.parsePage(collected(fixture("artist-vasco.html"), "https://www.ticketone.it/artist/vasco-rossi/"), "Vasco Rossi", today)
        // Not: Lugano (Switzerland), the cancelled Rome date, a tribute band's night, last June's date.
        assertEquals(listOf("2027-06-04", "2027-06-05", "2027-06-12", "2027-07-01"), page.events.map { it.date })
        val milano = page.events[0]
        assertEquals("21:00", milano.time)
        assertEquals("Milano", milano.city) // the site's capitals, as a city is written
        assertEquals("Stadio San Siro", milano.venue)
        assertEquals("IT", milano.country)
        assertEquals("https://www.ticketone.it/event/vasco-live-2027-stadio-san-siro-20001001/", milano.url)
        assertEquals("https://www.ticketone.it/obj/media/IT-eventim/teaser/222x222/2026/vasco.jpg", milano.image)
        assertEquals(Concerts.Source.TICKETONE, milano.source)
        // A date without its hour, a picture as an ImageObject.
        val bari = page.events[2]
        assertNull(bari.time)
        assertEquals("https://www.ticketone.it/obj/media/IT-eventim/teaser/222x222/2026/vasco-bari.jpg", bari.image)
        // A link that is no web page of the site is left out, the date kept.
        assertEquals("", page.events[3].url)
    }

    @Test fun withoutJsonLdTheEventLinksAndTheTextAroundThem() {
        val page = TicketOne.parsePage(fixture("search-vasco.json"), "Vasco Rossi", today)
        assertEquals(listOf("2027-06-04" to "21:00", "2027-06-12" to "21:00", "2027-06-19" to "20:45"), page.events.map { it.date to it.time })
        assertEquals(listOf("Stadio San Siro", "Stadio San Nicola", "Stadio del Conero"), page.events.map { it.venue })
        assertEquals(listOf("Milano", "Bari", "Ancona"), page.events.map { it.city })
        assertEquals("https://www.ticketone.it/obj/media/IT-eventim/teaser/222x222/2026/vasco.jpg", page.events[0].image)
        assertEquals("https://www.ticketone.it/artist/vasco-rossi/", page.artistPage)
    }

    @Test fun onlyTheArtistsOwnPageIsFollowed() {
        val page = TicketOne.parsePage(fixture("search-artist-only.json"), "Vasco Rossi", today)
        assertTrue(page.events.isEmpty())
        assertEquals("https://www.ticketone.it/artist/vasco-rossi/", page.artistPage)
        assertNull(TicketOne.parsePage(fixture("search-artist-only.json"), "Ligabue", today).artistPage)
    }

    @Test fun theDatesAsPagesWriteThem() {
        assertEquals("2027-06-04" to "21:00", TicketOne.dateOf("ven, 04/06/2027 - 21:00"))
        assertEquals("2027-06-04" to null, TicketOne.dateOf("04.06.2027"))
        assertEquals("2027-03-09" to "20:30", TicketOne.dateOf("9 marzo 2027, ore 20.30"))
        assertEquals("2027-12-31" to null, TicketOne.dateOf("31 dic 2027"))
        assertEquals("2027-06-04" to "21:00", TicketOne.dateOf("2027-06-04T21:00:00+02:00"))
        assertEquals("2027-06-04" to null, TicketOne.dateOf("2027-06-04T00:00:00"))
        assertNull(TicketOne.dateOf("31/02/2027"))
        assertNull(TicketOne.dateOf("VASCO LIVE 2027"))
    }

    @Test fun aPageThatIsNoResultStopsTheReading() {
        for ((state, why) in listOf("challenge" to "captcha", "consent" to "necessary only", "waiting" to "in time", "error" to "could not be read")) {
            val e = runCatching { TicketOne.parsePage("""{"state":"$state","ld":[],"cards":[],"artists":[]}""", "Vasco Rossi", today) }.exceptionOrNull()
            assertTrue("$state: $e", e is TicketOne.Stop && e.message!!.contains(why))
        }
        assertTrue(runCatching { TicketOne.parsePage("<html>", "Vasco Rossi", today) }.exceptionOrNull() is TicketOne.Stop)
        // A search with no results is an answer: no dates.
        assertTrue(TicketOne.parsePage("""{"state":"empty","ld":[],"cards":[],"artists":[]}""", "Vasco Rossi", today).events.isEmpty())
    }

    // ------------------------------------------------------------------ asking

    @Test fun theSearchThenTheArtistsPageOnceThenADayFromTheCache() = runBlocking {
        val first = TicketOne.dates(env, "Vasco Rossi", "IT")!!
        assertEquals(4, first.size)
        assertEquals(listOf("https://www.ticketone.it/search/?searchterm=Vasco%20Rossi", "https://www.ticketone.it/artist/vasco-rossi/"), pages)
        assertEquals(first, TicketOne.dates(env, "Vasco Rossi", "IT"))
        assertEquals(2, pages.size)
        clock += Concerts.TTL_MS
        TicketOne.dates(env, "Vasco Rossi", "IT")
        assertEquals(4, pages.size)
    }

    @Test fun notAskedOutsideItalyNorWithoutABrowserEngine() = runBlocking {
        assertEquals(emptyList<Concerts.Event>(), TicketOne.dates(env, "Vasco Rossi", "DE"))
        TicketOne.gate = { false }
        assertEquals(emptyList<Concerts.Event>(), TicketOne.dates(env, "Vasco Rossi", "IT"))
        TicketOne.reader = null
        assertEquals(emptyList<Concerts.Event>(), TicketOne.dates(env, "Vasco Rossi", "IT"))
        assertTrue(pages.isEmpty())
    }

    @Test fun aCheckPageLeavesTheSiteAloneForAnHour() = runBlocking {
        site = { """{"state":"challenge","ld":[],"cards":[],"artists":[]}""" }
        assertNull(TicketOne.dates(env, "Vasco Rossi", "IT"))
        assertNull(TicketOne.dates(env, "Ligabue", "IT"))
        assertEquals(1, pages.size)
        assertTrue(env.logs.any { it.contains("TicketOne") && it.contains("captcha") })
        clock += TicketOne.STOP_MS
        site = { """{"state":"empty","ld":[],"cards":[],"artists":[]}""" }
        assertEquals(emptyList<Concerts.Event>(), TicketOne.dates(env, "Ligabue", "IT"))
        // A reader that cannot be asked (a plugin too old for it): no dates, no stop.
        site = { null }
        assertNull(TicketOne.dates(env, "Elisa", "IT"))
        assertTrue(TicketOne.stoppedUntil <= clock)
    }

    @Test fun inConcertsTheSameNightOnVivaticketAndTicketOneIsOneDateWithBothLinks() = runBlocking {
        val r = Concerts.find(env, listOf("Vasco Rossi"), "IT", Concerts.Range.ANY)
        assertTrue(r.ticketOneRead)
        val events = r.artists.single().events
        assertEquals(4, events.size)
        assertTrue(events.all { it.source == Concerts.Source.TICKETONE })
        val merged = Concerts.merge(
            emptyList(), emptyList(),
            listOf(Concerts.Event("Vasco Rossi", "VASCO", "2027-06-04", "21:00", "Milano", "San Siro", "IT", "https://www.vivaticket.com/it/ticket/vasco/1", Concerts.Source.VIVATICKET)) + events,
        )
        assertEquals(listOf(Concerts.TicketLink(Concerts.Source.TICKETONE, "https://www.ticketone.it/event/vasco-live-2027-stadio-san-siro-20001001/")), merged.first().alsoOn)
    }

    @Test fun theTimeRangeKeepsTicketOnesDatesWithinIt() = runBlocking {
        clock = java.time.Instant.parse("2027-05-20T12:00:00Z").toEpochMilli()
        val r = Concerts.find(env, listOf("Vasco Rossi"), "IT", Concerts.Range.MONTH)
        assertEquals(listOf("2027-06-04", "2027-06-05", "2027-06-12"), r.artists.single().events.map { it.date })
    }

    @Test fun aStoppedReadingIsSaidInTheResult() = runBlocking {
        site = { """{"state":"consent","ld":[],"cards":[],"artists":[]}""" }
        val r = Concerts.find(env, listOf("Vasco Rossi"), "IT", Concerts.Range.ANY)
        assertFalse(r.ticketOneRead)
        // The search buttons stay, TicketOne's first.
        assertEquals("TicketOne", r.artists.single().searches.first().site)
        TicketOne.reader = null
        assertFalse(Concerts.find(env, listOf("Ligabue"), "IT").ticketOneRead)
    }

    private companion object {
        val LD = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}

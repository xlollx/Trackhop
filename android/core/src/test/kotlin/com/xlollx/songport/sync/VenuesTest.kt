package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/**
 * Venues searched, followed and looked at, against stand-ins for Ticketmaster's Discovery API and
 * MusicBrainz (answers written by hand from their documented shapes).
 */
class VenuesTest {
    private fun res(name: String) = javaClass.getResource("/venues/$name")!!.readText()
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var tmEvents: (String) -> Pair<Int, String> = { 200 to res("tm-venue-events.json") }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += "$path?$query"
            val (code, body) = when (path) {
                "/tm/venues.json" -> 200 to res("tm-venues.json")
                "/tm/events.json" -> tmEvents(query)
                "/ws/place" -> 200 to res("mb-places.json")
                "/ws/event" -> 200 to res("mb-place-events.json")
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private val clock = java.time.Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val alcatraz = Venues.Venue("tm:Z198xZG2Za7e", "Alcatraz", "Milano", "IT")
    private val magnolia = Venues.Venue("mb:478558f9-a951-4067-ad91-e83f6ba63e74", "Circolo Magnolia", "Segrate")

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Concerts.tmBase = "$base/tm"; Concerts.now = { clock }; Concerts.zone = { java.time.ZoneId.of("Europe/Rome") }; Concerts.tmPace = Pacer(0)
        MusicBrainz.base = "$base/ws"; MusicBrainz.pace = Pacer(0)
        Concerts.saveKey(env, "MYKEY")
    }

    @After fun tearDown() {
        server.stop(0)
        Concerts.tmBase = "https://app.ticketmaster.com/discovery/v2"; Concerts.now = { System.currentTimeMillis() }
        Concerts.zone = { java.time.ZoneId.systemDefault() }; Concerts.tmPace = Pacer(550)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"; MusicBrainz.pace = Pacer(1100)
    }

    @Test fun `a search finds Ticketmaster's venues, then MusicBrainz's places that are somewhere to play`() = runBlocking {
        val found = Venues.search(env, "magnolia", "IT")
        assertEquals(listOf(alcatraz, Venues.Venue("tm:Z198xZG2ZaFk", "Fabrique", "Milano", "IT"), magnolia), found)
        assertTrue(hits.any { it.startsWith("/tm/venues.json?") && "keyword=magnolia" in it && "countryCode=IT" in it && "apikey=MYKEY" in it })
        // Without a key: MusicBrainz only, and Ticketmaster is not asked.
        Concerts.saveKey(env, ""); hits.clear()
        assertEquals(listOf(magnolia), Venues.search(env, "magnolia", "IT"))
        assertTrue(hits.none { it.startsWith("/tm/") })
        assertTrue(Venues.search(env, "m", "IT").isEmpty())
    }

    @Test fun `following and unfollowing a venue`() {
        val one = Venues.toggle(emptyList(), alcatraz)
        assertTrue(Venues.isFollowed(one, alcatraz))
        assertEquals(listOf(alcatraz, magnolia), Venues.toggle(one, magnolia))
        assertTrue(Venues.toggle(one, alcatraz).isEmpty())
    }

    @Test fun `a venue's events say who plays, the sales and what was called off`() {
        val events = Venues.parseTicketmasterEvents(res("tm-venue-events.json"))
        assertEquals(listOf("Wet Leg", "Indie Night", "Arlo Parks"), events.map { it.artist })
        val e = events[0]
        assertEquals(listOf("Wet Leg", "Faux Real"), e.lineup)
        assertEquals("Z698xZG2Z17a1", e.id); assertEquals("2026-11-22", e.date); assertEquals("21:00", e.time); assertEquals("Alcatraz", e.venue)
        assertEquals("2026-11-20T09:00:00Z", e.onSale)
        assertEquals("2026-11-18T09:00:00Z", e.presale) // the first presale to open
        assertEquals("", e.status)
        assertNull(events[1].onSale)
        assertEquals(Concerts.CANCELLED, events[2].status)
    }

    @Test fun `the nights to come at the venues and in the city, each once, cancelled ones left out, kept a day`() = runBlocking {
        val up = Venues.upcoming(env, listOf(alcatraz, magnolia), "Milano", "IT")
        // Alcatraz's two (the cancelled one is not shown), Magnolia's one; the city's are the same events: once.
        assertEquals(listOf("2026-11-22" to "Wet Leg", "2026-11-29" to "Indie Night", "2026-12-05" to "Arlo Parks"), up.nights.map { it.event.date to it.event.artist })
        assertEquals(alcatraz, up.nights[0].venue); assertEquals(magnolia, up.nights[2].venue)
        assertEquals("Segrate", up.nights[2].event.city)
        assertEquals(0, up.unanswered); assertTrue(up.ticketmaster)
        assertEquals(listOf("Wet Leg", "Faux Real", "Indie Night", "Arlo Parks"), Venues.artists(up.nights))
        val venue = hits.first { it.startsWith("/tm/events.json?") }
        assertTrue(venue, "venueId=Z198xZG2Za7e" in venue && "classificationName=music" in venue && "endDateTime=2026-12-14T00:00:00Z" in venue)
        assertTrue(hits.any { it.startsWith("/tm/events.json?") && "city=Milano" in it && "countryCode=IT" in it })
        assertTrue(hits.any { it.startsWith("/ws/event?") && "pid:478558f9-a951-4067-ad91-e83f6ba63e74 AND begin:[2026-11-15 TO 2026-12-13]" in it })
        val n = hits.size
        Venues.upcoming(env, listOf(alcatraz, magnolia), "Milano", "IT")
        assertEquals(n, hits.size)
    }

    @Test fun `a refused key or no key leaves Ticketmaster's venues unanswered, MusicBrainz's still there`() = runBlocking {
        tmEvents = { 401 to """{"fault":{"faultstring":"Invalid ApiKey"}}""" }
        val up = Venues.upcoming(env, listOf(magnolia, alcatraz), "", "IT")
        assertTrue(up.keyRefused); assertEquals(1, up.unanswered)
        assertEquals(listOf("Arlo Parks"), up.nights.map { it.event.artist })
        // In the background only the user's own key is used: here there is one.
        Concerts.saveKey(env, "")
        assertNull(Venues.events(env, alcatraz))
        assertNull(Venues.cityEvents(env, "Milano", "IT"))
    }

    @Test fun `concerts near you needs somewhere to look and an answer`() {
        assertTrue(runCatching { runBlocking { Venues.nearbyTracks(env) } }.exceptionOrNull()?.message == "venues_empty")
        env.store.updateSettings { it.copy(followedVenues = listOf(alcatraz)) }
        tmEvents = { 500 to "" }
        assertTrue(runCatching { runBlocking { Venues.nearbyTracks(env) } }.exceptionOrNull()?.message == "venues_unanswered")
    }
}

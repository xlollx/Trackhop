package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.BuildDefaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale

/**
 * Concerts of a playlist's artists, against stand-ins for Ticketmaster's Discovery API and for
 * MusicBrainz: what is read from each answer, the country, one date from both sources shown once,
 * the day's quota running out, and the day-long cache.
 */
class ConcertsTest {
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** The Ticketmaster answer: status, body, headers. */
    private var tm: (String) -> Triple<Int, String, Map<String, String>> = { Triple(200, "{}", emptyMap()) }
    private var mbEvents = """{"events":[]}"""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += "$path?$query"
            val (code, body, headers) = when {
                path.startsWith("/tm/") -> tm(query)
                path == "/ws/2/event" -> Triple(200, mbEvents, emptyMap())
                path == "/ws/2/place/p-milano" -> Triple(200, """{"id":"p-milano","name":"Forum","area":{"id":"a-milano","name":"Milano"}}""", emptyMap())
                path == "/ws/2/place/p-paris" -> Triple(200, """{"id":"p-paris","name":"Zenith","area":{"id":"a-paris","name":"Paris"}}""", emptyMap())
                path == "/ws/2/area/a-milano" -> Triple(200, """{"name":"Milano","relations":[{"type":"part of","direction":"backward","area":{"id":"a-mi","name":"Milano","iso-3166-2-codes":["IT-MI"]}}]}""", emptyMap())
                path == "/ws/2/area/a-paris" -> Triple(200, """{"name":"Paris","relations":[{"type":"part of","direction":"backward","area":{"id":"a-idf","name":"Île-de-France"}}]}""", emptyMap())
                path == "/ws/2/area/a-idf" -> Triple(200, """{"name":"Île-de-France","relations":[{"type":"part of","direction":"backward","area":{"id":"a-fr","name":"France","iso-3166-1-codes":["FR"]}}]}""", emptyMap())
                else -> Triple(404, "{}", emptyMap())
            }
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Concerts.tmBase = "$base/tm"
        Concerts.now = { clock }
        Concerts.tmPace = Pacer(0)
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
        BuildDefaults.values = mapOf(Concerts.BUILD_KEY to "SHARED")
    }

    @After fun tearDown() {
        server.stop(0)
        Concerts.tmBase = "https://app.ticketmaster.com/discovery/v2"
        Concerts.now = { System.currentTimeMillis() }
        Concerts.tmPace = Pacer(220)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
        BuildDefaults.values = emptyMap()
    }

    private fun tmEvent(name: String, date: String, city: String, country: String, attraction: String, status: String = "onsale") = """
        {"name":"$name","url":"https://www.ticketmaster.it/event/$date","dates":{"start":{"localDate":"$date","localTime":"21:00:00"},"status":{"code":"$status"}},
         "_embedded":{"venues":[{"name":"Arena $city","city":{"name":"$city"},"country":{"countryCode":"$country"}}],"attractions":[{"name":"$attraction"}]}}"""

    private fun tmPage(vararg events: String) = """{"_embedded":{"events":[${events.joinToString(",")}]},"page":{"totalElements":${events.size}}}"""

    private fun mbEvent(id: String, artist: String, date: String, place: String, cancelled: Boolean = false) = """
        {"id":"$id","type":"Concert","name":"$artist at $place","cancelled":$cancelled,"life-span":{"begin":"$date","end":"$date"},
         "relations":[{"type":"main performer","direction":"backward","artist":{"id":"x","name":"$artist"}},
                      {"type":"held at","direction":"backward","place":{"id":"$place","name":"Venue $place"}}]}"""

    // ------------------------------------------------------------------ reading the answers

    @Test fun ticketmasterKeepsTheArtistsOwnDatesInTheCountry() {
        val body = tmPage(
            tmEvent("Måneskin - Rush! Tour", "2026-12-01", "Milano", "IT", "Måneskin"),
            tmEvent("Måneskin Tribute Night", "2026-12-02", "Roma", "IT", "Maneskin Tribute Band"),
            tmEvent("Måneskin", "2026-12-03", "Paris", "FR", "Måneskin"),
            tmEvent("Måneskin", "2026-12-04", "Torino", "IT", "Måneskin", status = "cancelled"),
        )
        val got = Concerts.parseTicketmaster(body, "Maneskin", "IT")
        assertEquals(1, got.size)
        val e = got.single()
        assertEquals("2026-12-01", e.date); assertEquals("21:00", e.time); assertEquals("Milano", e.city)
        assertEquals("Arena Milano", e.venue); assertEquals("IT", e.country); assertEquals(Concerts.Source.TICKETMASTER, e.source)
        assertEquals("https://www.ticketmaster.it/event/2026-12-01", e.url)
        // No events at all: an answer without "_embedded".
        assertTrue(Concerts.parseTicketmaster("""{"page":{"totalElements":0}}""", "Maneskin", "IT").isEmpty())
    }

    @Test fun musicBrainzKeepsPerformedNotCancelledEvents() {
        val body = """{"events":[${mbEvent("e1", "Ray Wilson", "2026-11-12", "p1")},${mbEvent("e2", "Someone Else", "2026-11-13", "p2")},${mbEvent("e3", "Ray Wilson", "2026-11-14", "p3", cancelled = true)}]}"""
        val got = Concerts.parseMusicBrainz(body, "Ray Wilson")
        assertEquals(1, got.size)
        val (e, place) = got.single()
        assertEquals("2026-11-12", e.date); assertEquals("Venue p1", e.venue); assertEquals("p1", place)
        assertEquals("https://musicbrainz.org/event/e1", e.url); assertEquals(Concerts.Source.MUSICBRAINZ, e.source)
    }

    // ------------------------------------------------------------------ the search

    @Test fun bothSourcesInTheCountryAndTheSameDateShownOnce() = runBlocking {
        tm = { Triple(200, tmPage(tmEvent("Coldplay", "2026-12-01", "Milano", "IT", "Coldplay")), emptyMap()) }
        mbEvents = """{"events":[${mbEvent("same", "Coldplay", "2026-12-01", "p-milano")},${mbEvent("extra", "Coldplay", "2027-01-15", "p-milano")},${mbEvent("fr", "Coldplay", "2027-02-01", "p-paris")}]}"""
        val r = Concerts.find(env, listOf("Coldplay", "Nobody Plays"), "it")
        assertEquals("IT", r.country)
        val coldplay = r.artists.first()
        assertEquals("Coldplay", coldplay.artist)
        assertEquals(listOf("2026-12-01" to Concerts.Source.TICKETMASTER, "2027-01-15" to Concerts.Source.MUSICBRAINZ), coldplay.events.map { it.date to it.source })
        assertEquals("Milano", coldplay.events[1].city)
        assertTrue(coldplay.searches.isEmpty())
        // No date: the Italian ticket sellers and the official site, as searches.
        val nobody = r.artists.last()
        assertTrue(nobody.events.isEmpty())
        assertEquals(listOf("TicketOne", "Vivaticket", null), nobody.searches.map { it.site })
        assertTrue(nobody.searches[0].url, nobody.searches[0].url.startsWith("https://www.ticketone.it/search/?searchterm=Nobody%20Plays"))
        // The Ticketmaster call names the country and the build's key; never a secret.
        val call = hits.first { it.startsWith("/tm/") }
        assertTrue(call, call.contains("countryCode=IT") && call.contains("apikey=SHARED") && call.contains("keyword=Coldplay"))
        assertFalse(r.ownKey)
    }

    @Test fun answersAreKeptADayPerArtistAndCountry() = runBlocking {
        tm = { Triple(200, tmPage(tmEvent("Muse", "2026-12-01", "Milano", "IT", "Muse")), emptyMap()) }
        Concerts.find(env, listOf("Muse"), "IT")
        val first = hits.size
        Concerts.find(env, listOf("Muse"), "IT")
        assertEquals("asked again within the day", first, hits.size)
        // Another country is another question.
        Concerts.find(env, listOf("Muse"), "FR")
        assertTrue(hits.size > first)
        val afterFr = hits.size
        clock += Concerts.TTL_MS + 1
        Concerts.find(env, listOf("Muse"), "IT")
        assertTrue("asked again after a day", hits.size > afterFr)
    }

    @Test fun theDaysQuotaStopsTicketmasterUntilItsReset() = runBlocking {
        var calls = 0
        tm = {
            calls++
            Triple(429, """{"fault":{"faultstring":"Rate limit quota violation. Quota limit exceeded.","detail":{"errorcode":"policies.ratelimit.QuotaViolation"}}}""",
                mapOf("Rate-Limit-Reset" to (clock + 3_600_000).toString()))
        }
        val r = Concerts.find(env, listOf("A", "B", "C"), "IT")
        assertTrue(r.quotaHit)
        assertEquals("one refused call, no more for the other artists", 1, calls)
        assertEquals(3, r.artists.size)
        // Still gone for the next search, not asked again.
        assertTrue(Concerts.find(env, listOf("D"), "IT").quotaHit)
        assertEquals(1, calls)
        // After the reset it is asked again; nothing of the refusal was cached as "no dates".
        clock += 3_600_001
        tm = { calls++; Triple(200, tmPage(), emptyMap()) }
        assertFalse(Concerts.find(env, listOf("A"), "IT").quotaHit)
        assertEquals(2, calls)
    }

    @Test fun ownKeyIsUsedAndTheSharedOneLooksUpFewerArtists() = runBlocking {
        tm = { Triple(200, tmPage(), emptyMap()) }
        val many = (1..60).map { "Artist $it" }
        assertEquals(Concerts.MAX_SHARED, Concerts.find(env, many, "IT").artists.size)
        Concerts.saveKey(env, "  MINE ")
        val r = Concerts.find(env, many, "IT")
        assertTrue(r.ownKey)
        assertEquals(60, r.artists.size)
        assertTrue(hits.any { it.contains("apikey=MINE") })
        Concerts.saveKey(env, "")
        assertEquals(null, Concerts.ownKey(env))
    }

    @Test fun withoutAnyKeyOnlyMusicBrainzIsAsked() = runBlocking {
        BuildDefaults.values = emptyMap()
        mbEvents = """{"events":[${mbEvent("e", "Muse", "2027-03-01", "p-milano")}]}"""
        val r = Concerts.find(env, listOf("Muse"), "IT")
        assertFalse(r.ticketmaster)
        assertTrue(hits.none { it.startsWith("/tm/") })
        assertEquals(1, r.artists.single().events.size)
    }

    @Test fun aRefusedKeyIsSaid() = runBlocking {
        tm = { Triple(401, """{"fault":{"faultstring":"Invalid ApiKey"}}""", emptyMap()) }
        val r = Concerts.find(env, listOf("A", "B"), "IT")
        assertTrue(r.keyRefused)
        assertEquals(1, hits.count { it.startsWith("/tm/") })
    }

    // ------------------------------------------------------------------ helpers

    @Test fun theDefaultCountryNeedsNoLocation() {
        assertEquals("IT", Concerts.defaultCountry("it", "de", Locale.US))
        assertEquals("DE", Concerts.defaultCountry("", "de", Locale.US))
        assertEquals("BR", Concerts.defaultCountry(null, null, Locale("pt", "BR")))
        assertEquals("US", Concerts.defaultCountry(null, "", Locale("en")))
    }

    @Test fun theArtistsOfAPlaylistMostPlayedFirst() {
        val tracks = listOf(
            Track("1", "a", listOf("Muse")), Track("2", "b", listOf("Coldplay")),
            Track("3", "c", listOf("coldplay", "Rihanna")), Track("4", "d", emptyList()),
        )
        assertEquals(listOf("Coldplay", "Muse"), Concerts.artistsOf(tracks))
    }
}

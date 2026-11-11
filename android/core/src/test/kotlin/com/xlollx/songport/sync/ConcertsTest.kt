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
                path == "/ws/2/place/p-busy" -> Triple(200, """{"id":"p-busy","name":"Somewhere","area":{"id":"a-busy","name":"Somewhere"}}""", emptyMap())
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
        Concerts.zone = { java.time.ZoneId.systemDefault() }
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

    @Test fun aLinkThatIsNoWebPageIsLeftOut() {
        val body = tmPage(tmEvent("Muse", "2026-12-01", "Milano", "IT", "Muse").replace("https://www.ticketmaster.it/event/2026-12-01", "javascript:alert(1)"))
        assertEquals("", Concerts.parseTicketmaster(body, "Muse", "IT").single().url)
        assertEquals("", webUrl(" intent://x#Intent;end"))
        assertEquals("https://a.example/b", webUrl(" https://a.example/b"))
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

    @Test fun aPlaceMusicBrainzDidNotAnswerForIsNotRememberedForADay() = runBlocking {
        BuildDefaults.values = emptyMap()
        mbEvents = """{"events":[${mbEvent("a", "Muse", "2027-03-01", "p-milano")},${mbEvent("b", "Muse", "2027-03-02", "p-busy")}]}"""
        assertEquals(1, Concerts.find(env, listOf("Muse"), "IT").artists.single().events.size)
        val before = hits.count { it.startsWith("/ws/2/event") }
        Concerts.find(env, listOf("Muse"), "IT")
        assertEquals("asked again: the first answer missed a place", before + 1, hits.count { it.startsWith("/ws/2/event") })
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

    @Test fun aSecondBurstLeavesTheArtistWithoutDatesNotTheDayGone() = runBlocking {
        var calls = 0
        tm = { calls++; Triple(429, """{"fault":{"faultstring":"Spike arrest violation.","detail":{"errorcode":"policies.ratelimit.SpikeArrestViolation"}}}""", emptyMap()) }
        val r = Concerts.find(env, listOf("A"), "IT")
        assertFalse(r.quotaHit)
        assertEquals("one retry after a second", 2, calls)
        // Not cached as "no dates", and the next search asks Ticketmaster again.
        tm = { calls++; Triple(200, tmPage(tmEvent("A", "2026-12-01", "Milano", "IT", "A")), emptyMap()) }
        val again = Concerts.find(env, listOf("A"), "IT")
        assertFalse(again.quotaHit)
        assertEquals(3, calls)
        assertEquals(1, again.artists.single().events.size)
    }

    @Test fun theOwnKeyIsKeptWithTheSignInsNotInTheSettings() {
        Concerts.saveKey(env, "MINE")
        assertEquals("MINE", Concerts.ownKey(env))
        assertFalse(Concerts.KEY_ID in env.store.data.settings.clientIds)
        // A key an earlier build left in the settings moves into the vault.
        Concerts.saveKey(env, "")
        env.store.updateSettings { it.copy(clientIds = it.clientIds + (Concerts.KEY_ID to "OLD")) }
        assertEquals("OLD", Concerts.ownKey(env))
        assertFalse(Concerts.KEY_ID in env.store.data.settings.clientIds)
        assertEquals("OLD", Concerts.ownKey(env))
    }

    @Test fun tonightsConcertCountsInTheUsersDayNotUtcs() = runBlocking {
        // 19:00 in Los Angeles on 10 November is already 11 November in UTC.
        clock = java.time.Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        Concerts.zone = { java.time.ZoneId.of("America/Los_Angeles") }
        BuildDefaults.values = emptyMap()
        mbEvents = """{"events":[${mbEvent("tonight", "Muse", "2026-11-10", "p-milano")}]}"""
        val r = Concerts.find(env, listOf("Muse"), "IT")
        assertEquals(listOf("2026-11-10"), r.artists.single().events.map { it.date })
        assertTrue(hits.any { it.startsWith("/ws/2/event") && it.contains("begin:[2026-11-10 TO") })
    }

    @Test fun ownKeyIsUsedAndWithoutAnyKeyFewerArtistsAreLookedUp() = runBlocking {
        tm = { Triple(200, tmPage(), emptyMap()) }
        val many = (1..60).map { "Artist $it" }
        BuildDefaults.values = emptyMap()
        assertEquals(Concerts.MAX_NO_KEY, Concerts.find(env, many, "IT").artists.size)
        Concerts.saveKey(env, "  MINE ")
        val r = Concerts.find(env, many, "IT")
        assertTrue(r.ownKey)
        assertEquals(60, r.artists.size)
        assertTrue(hits.any { it.contains("apikey=MINE") })
        Concerts.saveKey(env, "")
        assertEquals(null, Concerts.ownKey(env))
        // A server's own key (its environment) looks them all up too.
        BuildDefaults.values = mapOf(Concerts.BUILD_KEY to "SERVER")
        assertEquals(60, Concerts.find(env, many, "IT").artists.size)
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

    @Test fun theTimeRangeEndsBothSearchesAndItsDates() = runBlocking {
        tm = { Triple(200, tmPage(tmEvent("Muse", "2026-11-20", "Milano", "IT", "Muse"), tmEvent("Muse", "2027-01-20", "Roma", "IT", "Muse")), emptyMap()) }
        mbEvents = """{"events":[${mbEvent("soon", "Muse", "2026-12-01", "p-milano")},${mbEvent("late", "Muse", "2027-05-01", "p-milano")}]}"""
        val r = Concerts.find(env, listOf("Muse"), "IT", Concerts.Range.MONTH)
        assertEquals(listOf("2026-11-20", "2026-12-01"), r.artists.single().events.map { it.date })
        assertEquals(Concerts.Range.MONTH, r.range)
        assertTrue(hits.any { it.startsWith("/tm/") && it.contains("endDateTime=2026-12-11T00:00:00Z") })
        assertTrue(hits.any { it.startsWith("/ws/2/event") && it.contains("begin:[2026-11-10 TO 2026-12-10]") })
        // Any date: no end, and not the month's cached answer.
        val all = Concerts.find(env, listOf("Muse"), "IT", Concerts.Range.ANY)
        assertEquals(4, all.artists.single().events.size)
        assertTrue(hits.any { it.startsWith("/tm/") && !it.contains("endDateTime") })
    }

    @Test fun theRangeIsRememberedAndReadBack() {
        assertEquals(Concerts.Range.ANY, Concerts.savedRange(env))
        Concerts.saveRange(env, Concerts.Range.SIX_MONTHS)
        assertEquals(Concerts.Range.SIX_MONTHS, Concerts.savedRange(env))
        assertEquals(Concerts.Range.ANY, Concerts.Range.of("nonsense"))
        val today = java.time.LocalDate.parse("2026-01-31")
        assertEquals("2026-02-28", Concerts.Range.MONTH.end(today).toString())
        assertEquals(null, Concerts.Range.ANY.end(today))
        val e = Concerts.Event("A", "A", "2026-02-28", source = Concerts.Source.MUSICBRAINZ)
        assertTrue(Concerts.inRange(e, today, Concerts.Range.MONTH.end(today)))
        assertFalse(Concerts.inRange(e.copy(date = "2026-03-01"), today, Concerts.Range.MONTH.end(today)))
        assertFalse(Concerts.inRange(e.copy(date = "2026-01-30"), today, null))
    }

    @Test fun theArtistsWithoutDatesComeInAlphabeticalOrder() = runBlocking {
        BuildDefaults.values = emptyMap()
        val r = Concerts.find(env, listOf("zucchero", "Ásgeir", "Muse", "arctic Monkeys", "Azealia Banks"), "IT")
        assertEquals(listOf("arctic Monkeys", "Ásgeir", "Azealia Banks", "Muse", "zucchero"), r.artists.map { it.artist })
    }

    @Test fun aCalendarEntryInTheEventsOwnTimeZone() {
        val e = Concerts.Event(
            "Muse", "Muse Live", "2027-06-18", "21:00", "Milano", "Ippodromo SNAI San Siro", "IT",
            "https://www.ticketmaster.it/event/1", Concerts.Source.TICKETMASTER, timeZone = "Europe/Rome",
        )
        val c = Concerts.calendarEntry(e, java.time.ZoneId.of("America/New_York"))
        assertEquals("Muse – Ippodromo SNAI San Siro", c.title)
        assertEquals(java.time.Instant.parse("2027-06-18T19:00:00Z").toEpochMilli(), c.begin)
        assertEquals(c.begin + 3 * 3_600_000L, c.end)
        assertFalse(c.allDay)
        assertEquals("Europe/Rome", c.timeZone)
        assertEquals("Ippodromo SNAI San Siro, Milano", c.location)
        assertEquals("https://www.ticketmaster.it/event/1", c.description)
        // No zone said: the fallback one. No time: a whole day, at UTC midnight as calendars want it.
        assertEquals(java.time.Instant.parse("2027-06-19T01:00:00Z").toEpochMilli(), Concerts.calendarEntry(e.copy(timeZone = null), java.time.ZoneId.of("America/New_York")).begin)
        val day = Concerts.calendarEntry(e.copy(time = null, venue = ""), java.time.ZoneId.of("Europe/Rome"))
        assertTrue(day.allDay)
        assertEquals(java.time.Instant.parse("2027-06-18T00:00:00Z").toEpochMilli(), day.begin)
        assertEquals("Muse – Muse Live", day.title)
        assertEquals("Milano", day.location)
    }

    @Test fun ticketmastersTimeZoneIsKept() {
        val body = tmPage(tmEvent("Muse", "2026-12-01", "Milano", "IT", "Muse").replace("\"status\"", "\"timezone\":\"Europe/Rome\",\"status\""))
        assertEquals("Europe/Rome", Concerts.parseTicketmaster(body, "Muse", "IT").single().timeZone)
    }

    @Test fun aRecordedMusicBrainzAnswerIsRead() {
        // musicbrainz.org's answer on 11 November 2026 for artist:"Muse" from that day on: two festivals, Muse among 50 acts.
        val body = javaClass.getResource("/concerts/musicbrainz-muse-upcoming.json")!!.readText()
        val got = Concerts.parseMusicBrainz(body, "Muse")
        assertEquals(listOf("Southside 2027", "Hurricane Festival 2027"), got.map { it.event.name })
        assertTrue(got.all { it.event.date == "2027-06-18" && it.placeId != null })
        assertEquals(listOf("Flugplatz Neuhausen ob Eck", "Eichenring"), got.map { it.event.venue })
        assertTrue(Concerts.parseMusicBrainz(body, "Muse Maya").isEmpty())
    }

    @Test fun anEventHeldInACityWithNoPlaceIsKept() = runBlocking {
        BuildDefaults.values = emptyMap()
        mbEvents = """{"events":[{"id":"e9","type":"Concert","name":"Muse in Milan","life-span":{"begin":"2027-01-10"},
            "relations":[{"type":"main performer","direction":"backward","artist":{"id":"x","name":"Muse"}},
                         {"type":"held in","direction":"backward","area":{"id":"a-milano","name":"Milano"}}]}]}"""
        val e = Concerts.find(env, listOf("Muse"), "IT").artists.single().events.single()
        assertEquals("Milano", e.city); assertEquals("IT", e.country)
    }

    @Test fun aPlaceWhoseCountryCouldNotBeReadIsAskedAgain() = runBlocking {
        BuildDefaults.values = emptyMap()
        // p-nowhere's area answers 404 (MusicBrainz busy): not remembered as "no country" for months.
        mbEvents = """{"events":[${mbEvent("e", "Muse", "2027-03-01", "p-busy")}]}"""
        Concerts.find(env, listOf("Muse"), "IT")
        Concerts.find(env, listOf("Muse"), "IT")
        assertEquals(2, hits.count { it.startsWith("/ws/2/place/p-busy") })
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

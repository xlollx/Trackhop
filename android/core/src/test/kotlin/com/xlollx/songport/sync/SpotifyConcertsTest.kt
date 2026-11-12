package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * Spotify's tour dates as a source of Concerts: which dates' pages are asked (the country's UTC
 * offsets that day, the range, a cap), what is kept, and that nothing is asked without a reader.
 * Made-up artists, places and uris.
 */
class SpotifyConcertsTest {
    private val env = ContractEnv()
    private val today = LocalDate.parse("2030-03-01")
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var listed = listOf(
        SpotifyConcerts.Date("spotify:concert:c1", "Spring", "2030-03-14T20:00+01:00", "Lyon"),
        SpotifyConcerts.Date("spotify:concert:c2", "Fest", "2030-04-02T19:30-05:00", "Austin"),
        SpotifyConcerts.Date("spotify:concert:c3", "Spring", "2030-03-16T21:00+01:00", "Bern"),
        SpotifyConcerts.Date("spotify:concert:c4", "Past", "2030-02-20T20:00+01:00", "Paris"),
        SpotifyConcerts.Date("spotify:concert:c5", "No time", "2030-03-20T00:00Z", "Nantes"),
    )
    private val pages = mapOf(
        "spotify:concert:c1" to SpotifyConcerts.Date("spotify:concert:c1", "Spring", "", "Lyon", "Salle du Quai", "FR"),
        "spotify:concert:c2" to SpotifyConcerts.Date("spotify:concert:c2", "Fest", "", "Austin", "Big Field", "US"),
        "spotify:concert:c3" to SpotifyConcerts.Date("spotify:concert:c3", "Spring", "", "Bern", "Halle", "CH"),
        "spotify:concert:c5" to SpotifyConcerts.Date("spotify:concert:c5", "No time", "", "Nantes", "Le Hangar", "FR"),
    )
    private var failDetails = false

    @Before fun setUp() {
        Concerts.now = { java.time.Instant.parse("2030-03-01T12:00:00Z").toEpochMilli() }
        SpotifyConcerts.reader = object : SpotifyConcerts.Reader {
            override suspend fun dates(artist: String) = listed.also { asked += "list:$artist" }
            override suspend fun details(uri: String): SpotifyConcerts.Date? {
                asked += uri
                if (failDetails) throw IllegalStateException("429")
                return pages[uri]
            }
        }
        SpotifyConcerts.zones = { c -> if (c == "FR") listOf(ZoneId.of("Europe/Paris")) else emptyList() }
    }

    @After fun tearDown() {
        SpotifyConcerts.reader = null
        SpotifyConcerts.zones = { emptyList() }
        Concerts.now = { System.currentTimeMillis() }
    }

    @Test fun onlyTheDatesWithTheCountrysOffsetAreAskedAndPlaced() = runBlocking {
        val events = SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null)!!
        // Austin's offset is not France's: never asked. Bern's is: asked, and left out (CH). The past date is not asked.
        assertEquals(listOf("list:Neon Harbour", "spotify:concert:c1", "spotify:concert:c3", "spotify:concert:c5"), asked)
        assertEquals(listOf("2030-03-14", "2030-03-20"), events.map { it.date })
        val e = events[0]
        assertEquals("Salle du Quai", e.venue)
        assertEquals("Lyon", e.city)
        assertEquals("FR", e.country)
        assertEquals("20:00", e.time)
        assertEquals("Europe/Paris", e.timeZone)
        assertEquals("https://open.spotify.com/concert/c1", e.url)
        assertEquals(Concerts.Source.SPOTIFY, e.source)
        // A time given in UTC is no time at all.
        assertNull(events[1].time)
    }

    @Test fun theListIsKeptADayAndThePlacesLonger() = runBlocking {
        SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null)
        asked.clear()
        SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null)
        assertEquals(emptyList<String>(), asked)
        // Another range: the list again, the places remembered.
        val r = SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, today.plusMonths(1))!!
        assertEquals(listOf("list:Neon Harbour"), asked)
        assertEquals(listOf("2030-03-14", "2030-03-20"), r.map { it.date })
    }

    @Test fun withoutTheCountrysZonesEveryDateIsAskedUpToTheCap() = runBlocking {
        listed = (1..30).map { SpotifyConcerts.Date("spotify:concert:n$it", "Tour", "2030-05-${it.toString().padStart(2, '0')}T20:00-05:00", "Town") }
        val place = { uri: String -> SpotifyConcerts.Date(uri, "Tour", "", "Town", "Hall", "US") }
        SpotifyConcerts.reader = object : SpotifyConcerts.Reader {
            override suspend fun dates(artist: String) = listed.also { asked += "list:$artist" }
            override suspend fun details(uri: String): SpotifyConcerts.Date? = place(uri).also { asked += uri }
        }
        val first = SpotifyConcerts.dates(env, "Neon Harbour", "US", today, null)!!
        assertEquals(SpotifyConcerts.MAX_DETAILS, asked.count { it.startsWith("spotify:concert:") })
        assertEquals(SpotifyConcerts.MAX_DETAILS, first.size)
        asked.clear()
        // Not kept: the dates past the cap are asked next time, the others' places remembered.
        val second = SpotifyConcerts.dates(env, "Neon Harbour", "US", today, null)!!
        assertEquals(listOf("list:Neon Harbour") + (21..30).map { "spotify:concert:n$it" }, asked)
        assertEquals(30, second.size)
    }

    @Test fun aFailedPageStopsTheAskingAndIsNotKept() = runBlocking {
        failDetails = true
        val events = SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null)!!
        assertEquals(listOf("list:Neon Harbour", "spotify:concert:c1"), asked)
        assertEquals(0, events.size)
        failDetails = false
        asked.clear()
        assertEquals(2, SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null)!!.size)
    }

    @Test fun aFailedListIsNullAndNoReaderAsksNothing() = runBlocking {
        SpotifyConcerts.reader = object : SpotifyConcerts.Reader {
            override suspend fun dates(artist: String): List<SpotifyConcerts.Date>? = throw IllegalStateException("down")
            override suspend fun details(uri: String): SpotifyConcerts.Date? = null
        }
        assertNull(SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null))
        SpotifyConcerts.reader = null
        assertEquals(emptyList<Concerts.Event>(), SpotifyConcerts.dates(env, "Neon Harbour", "FR", today, null))
    }

    @Test fun aSpotifyDateJoinsTheSameDateOfATicketSiteAsOneMoreLink() {
        val tm = Concerts.Event("A", "A live", "2030-03-14", city = "Lyon", venue = "Salle", country = "FR", url = "https://tm.example/1", source = Concerts.Source.TICKETMASTER)
        val sp = Concerts.Event("A", "Spring", "2030-03-14", city = "Lyon", venue = "Salle du Quai", country = "FR", url = "https://open.spotify.com/concert/c1", source = Concerts.Source.SPOTIFY)
        val alone = sp.copy(date = "2030-03-20", url = "https://open.spotify.com/concert/c5")
        val merged = Concerts.merge(listOf(tm), emptyList(), listOf(sp, alone))
        assertEquals(2, merged.size)
        assertEquals(listOf(Concerts.TicketLink(Concerts.Source.SPOTIFY, sp.url)), merged[0].alsoOn)
        assertEquals(Concerts.Source.SPOTIFY, merged[1].source)
    }
}

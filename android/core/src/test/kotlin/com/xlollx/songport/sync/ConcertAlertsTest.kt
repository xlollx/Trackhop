package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** What the ticket and date alerts tell, and the calendar a server hands out. */
class ConcertAlertsTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val env = ContractEnv()

    @Before fun setUp() { Concerts.now = { now }; Concerts.zone = { ZoneId.of("Europe/Rome") } }
    @After fun tearDown() { Concerts.now = { System.currentTimeMillis() }; Concerts.zone = { ZoneId.systemDefault() } }

    private fun ev(id: String, date: String = "2027-06-18", status: String = "", onSale: String? = null, presale: String? = null, artist: String = "Radiohead") =
        Concerts.Event(artist = artist, name = artist, date = date, city = "Milano", venue = "San Siro", country = "IT", url = "https://www.ticketmaster.it/event/$id",
            source = Concerts.Source.TICKETMASTER, timeZone = "Europe/Rome", id = id, status = status, onSale = onSale, presale = presale)

    private fun kinds(a: List<ConcertAlerts.Alert>) = a.map { it.kind }

    @Test fun `the first look takes note, then a sale to come, a date moved and a date called off are told once`() {
        val a = ev("a"); val b = ev("b", "2027-03-03", artist = "Massive Attack")
        val (s1, n1) = ConcertAlerts.diff(null, listOf(a, b), now, newDates = false)
        assertTrue(n1.isEmpty())
        // The sale's opening announced (with a presale before it): told; the same again: not.
        val sale = a.copy(onSale = "2026-11-20T09:00:00Z", presale = "2026-11-19T09:00:00Z")
        val (s2, n2) = ConcertAlerts.diff(s1, listOf(sale, b), now, false)
        assertEquals(listOf(ConcertAlerts.Kind.ON_SALE), kinds(n2))
        assertTrue(ConcertAlerts.diff(s2, listOf(sale, b), now, false).second.isEmpty())
        // A sale already open when first said is not news.
        assertTrue(ConcertAlerts.diff(s1, listOf(a.copy(onSale = "2026-11-01T09:00:00Z"), b), now, false).second.isEmpty())
        // Moved: the same event on another day, with the day it had.
        val (s3, n3) = ConcertAlerts.diff(s2, listOf(sale, b.copy(date = "2027-03-10", status = "rescheduled")), now, false)
        assertEquals(listOf(ConcertAlerts.Kind.MOVED), kinds(n3)); assertEquals("2027-03-03", n3[0].oldDate); assertEquals("2027-03-10", n3[0].event.date)
        // Postponed with no new day, then called off: each once.
        val (s4, n4) = ConcertAlerts.diff(s3, listOf(sale.copy(status = "postponed"), b.copy(date = "2027-03-10")), now, false)
        assertEquals(listOf(ConcertAlerts.Kind.POSTPONED), kinds(n4))
        val off = listOf(sale.copy(status = Concerts.CANCELLED), b.copy(date = "2027-03-10"))
        val (s5, n5) = ConcertAlerts.diff(s4, off, now, false)
        assertEquals(listOf(ConcertAlerts.Kind.CANCELLED), kinds(n5))
        assertTrue(ConcertAlerts.diff(s5, off, now, false).second.isEmpty())
        // An event gone from the answer says nothing; one with no id (another source) cannot be compared.
        assertTrue(ConcertAlerts.diff(s5, listOf(ev("")), now, true).second.isEmpty())
    }

    @Test fun `a venue's new date is told, an artist's is left to the artists' own news, a cancelled one never seen is not`() {
        val (s, _) = ConcertAlerts.diff(null, listOf(ev("a")), now, newDates = true)
        val fresh = ev("n", "2026-11-22", artist = "Wet Leg", onSale = "2026-11-20T09:00:00Z")
        assertEquals(listOf(ConcertAlerts.Kind.NEW_DATE, ConcertAlerts.Kind.ON_SALE), kinds(ConcertAlerts.diff(s, listOf(ev("a"), fresh), now, true).second))
        assertEquals(listOf(ConcertAlerts.Kind.ON_SALE), kinds(ConcertAlerts.diff(s, listOf(ev("a"), fresh), now, false).second))
        assertTrue(ConcertAlerts.diff(s, listOf(ev("a"), ev("x", status = Concerts.CANCELLED)), now, true).second.isEmpty())
    }

    @Test fun `each kind of alert can be turned off`() {
        val all = ConcertAlerts.Kind.entries.map { ConcertAlerts.Alert(it, ev("a")) }
        assertEquals(all, ConcertAlerts.wanted(all, ConcertAlerts.Kinds()))
        assertEquals(listOf(ConcertAlerts.Kind.ON_SALE), kinds(ConcertAlerts.wanted(all, ConcertAlerts.Kinds(newDates = false, changes = false))))
        assertEquals(listOf(ConcertAlerts.Kind.NEW_DATE, ConcertAlerts.Kind.MOVED, ConcertAlerts.Kind.POSTPONED, ConcertAlerts.Kind.CANCELLED), kinds(ConcertAlerts.wanted(all, ConcertAlerts.Kinds(sales = false))))
        assertFalse(ConcertAlerts.Kinds(false, false, false).any)
    }

    @Test fun `what is said - the sale in the event's own time, the days moved`() {
        val sale = ConcertAlerts.Alert(ConcertAlerts.Kind.ON_SALE, ev("a", onSale = "2026-11-20T09:00:00Z", presale = "2026-11-19T09:00:00Z"))
        // 09:00 UTC is 10:00 in Rome.
        val t = ConcertAlerts.title(env, sale)
        assertTrue(t, t.startsWith("concert_alert_sale(Radiohead,") && t.contains("20") && t.contains("10:00"))
        val x = ConcertAlerts.text(env, sale)
        assertTrue(x, x.startsWith("Milano, ") && x.contains("concert_alert_presale_from(") && x.contains("19"))
        // Only the presale still to come: the title says the presale.
        val pre = ConcertAlerts.Alert(ConcertAlerts.Kind.ON_SALE, ev("a", onSale = "2026-11-01T09:00:00Z", presale = "2026-11-19T09:00:00Z"))
        assertTrue(ConcertAlerts.title(env, pre).startsWith("concert_alert_presale(Radiohead,"))
        val moved = ConcertAlerts.Alert(ConcertAlerts.Kind.MOVED, ev("b", "2027-03-10", artist = "Massive Attack"), oldDate = "2027-03-03")
        assertEquals("concert_alert_moved(Massive Attack)", ConcertAlerts.title(env, moved))
        assertTrue(ConcertAlerts.text(env, moved).startsWith("concert_alert_moved_text(Milano,"))
        assertEquals("concert_alert_cancelled(Radiohead)", ConcertAlerts.title(env, ConcertAlerts.Alert(ConcertAlerts.Kind.CANCELLED, ev("a"))))
        val new = ConcertAlerts.Alert(ConcertAlerts.Kind.NEW_DATE, ev("n", artist = "Wet Leg").copy(lineup = listOf("Wet Leg", "Faux Real")), venue = "Alcatraz")
        assertTrue(ConcertAlerts.title(env, new).startsWith("concert_alert_new(Wet Leg, Faux Real,Alcatraz,"))
    }

    @Test fun `nothing followed asks nothing, and the daily run is planned for venues only with 1_18's switch`() {
        val s = com.xlollx.songport.data.Settings(followedVenues = listOf(Venues.Venue("mb:x", "Magnolia")))
        assertTrue(kotlinx.coroutines.runBlocking { ConcertAlerts.check(env, com.xlollx.songport.data.Settings(), "IT", Concerts.Range.ANY) }.isEmpty())
        assertEquals(null, ArtistAlerts.plan(s))
        assertEquals(ArtistAlerts.Plan(1, false), ArtistAlerts.plan(s.copy(preview2 = true)))
    }

    @Test fun `the calendar is RFC 5545 - CRLF, folded lines, escaped text, timed and whole-day events, a cancelled one marked`() {
        val timed = ev("Z698xZG2Z17a1").copy(time = "21:00", artist = "Wet Leg; Faux, Real", venue = "Alcatraz")
        val allDay = Concerts.Event(artist = "Arlo Parks", name = "Arlo Parks at a venue with a very long name that goes on and on beyond one line", date = "2026-12-05",
            venue = "Circolo Magnolia — Parco dell'Idroscalo, ingresso dal lato della tangenziale est", city = "Segrate", source = Concerts.Source.MUSICBRAINZ, url = "https://musicbrainz.org/event/1")
        val ics = Ics.calendar(listOf(timed, allDay, ev("off", status = Concerts.CANCELLED)), "Trackhop: concerts", now, ZoneId.of("UTC"))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Trackhop//Concerts//EN\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertEquals(ics.replace("\r\n", "").length, ics.replace("\r", "").replace("\n", "").length) // no bare CR or LF
        assertFalse(ics.replace("\r\n", "").contains('\n'))
        val lines = ics.split("\r\n")
        assertTrue(lines.all { it.toByteArray().size <= 75 })
        val unfolded = ics.replace("\r\n ", "").split("\r\n")
        assertEquals(3, unfolded.count { it == "BEGIN:VEVENT" }); assertEquals(3, unfolded.count { it == "END:VEVENT" })
        assertTrue(unfolded.contains("UID:Z698xZG2Z17a1@trackhop"))
        assertTrue(unfolded.contains("DTSTAMP:20261115T120000Z"))
        // 21:00 in Rome in June is 19:00 UTC; three hours long.
        assertTrue(unfolded.contains("DTSTART:20270618T190000Z")); assertTrue(unfolded.contains("DTEND:20270618T220000Z"))
        assertTrue(unfolded.contains("SUMMARY:Wet Leg\\; Faux\\, Real – Alcatraz"))
        assertTrue(unfolded.contains("LOCATION:Alcatraz\\, Milano"))
        assertTrue(unfolded.contains("DTSTART;VALUE=DATE:20261205")); assertTrue(unfolded.contains("DTEND;VALUE=DATE:20261206"))
        assertTrue(unfolded.any { it.startsWith("LOCATION:Circolo Magnolia — Parco dell'Idroscalo\\, ingresso") && it.endsWith("\\, Segrate") })
        assertTrue(unfolded.contains("URL:https://musicbrainz.org/event/1"))
        assertEquals(1, unfolded.count { it == "STATUS:CANCELLED" }); assertEquals(2, unfolded.count { it == "STATUS:CONFIRMED" })
        assertEquals("a\\\\b\\;c\\,d\\ne", Ics.escape("a\\b;c,d\ne"))
    }
}

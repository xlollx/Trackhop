package com.xlollx.songport.sync

import com.xlollx.songport.ai.AiRoast
import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pure logic behind the Discover tab: universal links, the Wrapped figures, library overlap, vanished songs, the roast's summary. */
class DiscoverTest {
    private fun t(id: String, title: String, artist: String, isrc: String? = null, year: Int = 0, ms: Long = 200_000) =
        Track(id = id, title = title, artists = artist.split(", "), durationMs = ms, isrc = isrc, year = year)

    // ------------------------------------------------------------ universal link

    @Test fun universalLinkRoundTrips() {
        val tracks = listOf(
            t("1", "Master of Puppets", "Metallica", "USEE10000001"),
            t("2", "Città vuota", "Mina"),
            t("3", "Tab\there\nand line", "A, B"),
            t("4", "夜に駆ける", "YOASOBI"),
        )
        val built = UniversalLink.build("Road Trip 🚗", tracks)
        assertTrue(built.url.startsWith("https://trackhop.org/open#"))
        assertFalse(built.capped)
        val back = UniversalLink.parse(built.url)!!
        assertEquals("Road Trip 🚗", back.name)
        assertEquals(4, back.total)
        assertFalse(back.single)
        assertEquals(UniversalLink.Item("Master of Puppets", "Metallica", "USEE10000001"), back.items[0])
        assertEquals(UniversalLink.Item("Città vuota", "Mina", null), back.items[1])
        // Tabs and line breaks inside a field cannot break the format.
        assertEquals(UniversalLink.Item("Tab here and line", "A, B", null), back.items[2])
        assertEquals("夜に駆ける", back.items[3].title)
        // The fragment alone parses too; garbage does not.
        assertEquals(back, UniversalLink.parse(built.url.substringAfter('#')))
        assertNull(UniversalLink.parse("https://trackhop.org/open#not-a-link"))
        // The fragment is URL-safe as it is.
        assertTrue(built.url.substringAfter('#').all { it.isLetterOrDigit() || it == '-' || it == '_' })
        // A fixture for the page's own test (docs/open.html, in a browser).
        File("build").mkdirs()
        val roadTrip = listOf(
            "Bohemian Rhapsody" to "Queen", "Africa" to "Toto", "Mr. Brightside" to "The Killers", "Take On Me" to "a-ha",
            "Dreams" to "Fleetwood Mac", "Seven Nation Army" to "The White Stripes", "Superstition" to "Stevie Wonder",
            "Il cielo in una stanza" to "Mina", "Smells Like Teen Spirit" to "Nirvana", "Blinding Lights" to "The Weeknd",
            "Don't Stop Me Now" to "Queen", "Rolling in the Deep" to "Adele",
        ).mapIndexed { i, (title, artist) -> t("r$i", title, artist) }
        File("build/universal-link-fixture.txt").writeText(
            built.url + "\n" + UniversalLink.buildTrack(tracks[0]).url + "\n" + UniversalLink.build("Road Trip 2026", roadTrip).url + "\n",
        )
    }

    @Test fun bigPlaylistIsCappedAndSaysSo() {
        val tracks = (1..2000).map { t("$it", "Song number $it with a fairly long title", "Artist $it, Guest ${it * 7}", "QZ" + it.toString().padStart(10, '0')) }
        val built = UniversalLink.build("Everything", tracks)
        assertTrue(built.capped)
        assertEquals(2000, built.total)
        assertTrue(built.included in 1..UniversalLink.MAX_TRACKS)
        assertTrue(built.url.substringAfter('#').length <= UniversalLink.MAX_FRAGMENT)
        val back = UniversalLink.parse(built.url)!!
        assertEquals(2000, back.total)
        assertEquals(built.included, back.items.size)
        assertTrue(back.capped)
        assertEquals("Song number 1 with a fairly long title", back.items.first().title)
    }

    @Test fun singleTrackLink() {
        val built = UniversalLink.buildTrack(t("1", "Bohemian Rhapsody", "Queen", "GBUM71029604"))
        val back = UniversalLink.parse(built.url)!!
        assertTrue(back.single)
        assertEquals(1, back.items.size)
        assertEquals("Queen", back.items[0].artist)
    }

    @Test fun albumsAndArtistsDoNotTravel() {
        val built = UniversalLink.build("Mix", listOf(t("1", "Song", "X"), Track("a", "An album", listOf("X"), kind = Track.KIND_ALBUM)))
        assertEquals(1, UniversalLink.parse(built.url)!!.items.size)
    }

    @Test fun searchAddressesAreEncoded() {
        val spotify = ServiceSearch.SITES.first { it.serviceId == "spotify" }
        assertEquals("https://open.spotify.com/search/AC%20DC%20Back%20in%20Black", spotify.url(ServiceSearch.query("Back in Black", "AC/DC")))
        assertEquals("Metallica One", ServiceSearch.query("One", "Metallica, Lars"))
        assertEquals(6, ServiceSearch.SITES.size)
    }

    // ------------------------------------------------------------ wrapped

    @Test fun musicalAgeFollowsTheMedianYear() {
        assertEquals(1975 to 51, Wrapped.musicalAge(1992, 2026))
        // A median in the future (bad data) is taken as this year.
        assertEquals(2009 to 17, Wrapped.musicalAge(2030, 2026))
        assertEquals(2000, Wrapped.median(listOf(2010, 1990, 2000)))
        assertEquals(1990, Wrapped.median(listOf(2010, 1990)))
        assertNull(Wrapped.median(emptyList()))
    }

    @Test fun wrappedSummary() {
        val tracks = listOf(
            t("1", "One", "Metallica", year = 1988),
            t("2", "Fuel", "Metallica", year = 1997),
            t("3", "Enter Sandman", "Metallica", year = 1991),
            t("4", "Teardrop", "Massive Attack", year = 1998),
            t("5", "Song 2", "Blur", year = 1997),
            // The same song in another playlist counts once.
            t("6", "One (Remastered)", "Metallica", year = 1988),
            t("7", "Untitled", "Various Artists"),
        )
        val s = Wrapped.compute(tracks, 2026)
        assertEquals("Metallica", s.topArtist)
        assertEquals(3, s.topArtistTracks)
        assertEquals(1990, s.topDecade)
        assertEquals(6, s.trackCount)
        assertEquals(1997, s.medianYear)
        assertEquals(1980, s.bornIn)
        assertEquals(46, s.age)
    }

    @Test fun fewDatedTracksTellNoAge() {
        val s = Wrapped.compute(listOf(t("1", "A", "X", year = 2001), t("2", "B", "Y")), 2026)
        assertNull(s.age)
        assertNull(s.bornIn)
        assertEquals(2000, s.topDecade)
    }

    // ------------------------------------------------------------ overlap

    @Test fun overlapCountsEachSongOnceAndUsesTheCache() {
        val a = listOf(
            t("a1", "Master of Puppets", "Metallica", "USEE1"),
            t("a1", "Master of Puppets", "Metallica", "USEE1"), // liked and in a playlist
            t("a2", "Hail to the King", "Avenged Sevenfold"),
            t("a3", "Weird title nobody matches", "Obscure Band"),
            t("a4", "Only here", "Somebody"),
        )
        val b = listOf(
            t("b1", "Master Of Puppets (Remastered)", "Metallica", "USEE1"),
            t("b2", "Hail to the King", "Avenged Sevenfold"),
            t("b3", "Something else entirely", "Another"),
            t("b9", "Totally different name", "Other Name"),
        )
        // An earlier sync matched a3 to b9: it counts though the names differ.
        val r = LibraryOverlap.compute(a, b) { if (it.id == "a3") "b9" else null }
        assertEquals(4, r.sizeA)
        assertEquals(4, r.sizeB)
        assertEquals(3, r.common.size)
        assertEquals(listOf("a4"), r.onlyA.map { it.id })
        assertEquals(listOf("b3"), r.onlyB.map { it.id })
        assertEquals(5, r.union)
        assertEquals(60, r.percent)
    }

    @Test fun overlapOfNothing() {
        val r = LibraryOverlap.compute(emptyList(), emptyList())
        assertEquals(0, r.percent)
    }

    @Test fun pricesAsPeopleTypeThem() {
        assertEquals(10.99, LibraryOverlap.parsePrice("10,99")!!, 0.001)
        assertEquals(9.99, LibraryOverlap.parsePrice("€ 9.99")!!, 0.001)
        assertEquals(11.0, LibraryOverlap.parsePrice("11")!!, 0.001)
        assertNull(LibraryOverlap.parsePrice("free"))
    }

    // ------------------------------------------------------------ vanished

    @Test fun vanishedAreInAnOlderCopyAndNotInTheNewest() {
        val v1 = Vanished.Version(1_000, listOf(t("1", "A", "X"), t("2", "B", "Y"), t("3", "C", "Z")))
        val v2 = Vanished.Version(2_000, listOf(t("1", "A", "X"), t("3", "C", "Z")))
        val v3 = Vanished.Version(3_000, listOf(t("1", "A", "X"), t("4", "D", "W")))
        val gone = Vanished.between("Road Trip", "Spotify", listOf(v3, v1, v2))
        assertEquals(listOf("C", "B"), gone.map { it.track.title })
        assertEquals(2_000L, gone[0].lastSeen)
        assertEquals(1_000L, gone[1].lastSeen)
        assertEquals("Road Trip", gone[0].playlist)
        assertTrue(Vanished.between("x", null, listOf(v1)).isEmpty())
    }

    // ------------------------------------------------------------ roast

    @Test fun roastSummaryIsCompact() {
        val tracks = (1..200).map { t("$it", "Song $it", if (it % 2 == 0) "Bring Me The Horizon" else "Artist $it", year = 2000 + it % 20) }
        val s = AiRoast.summary("Metal & More", tracks)
        assertTrue(s.contains("Playlist name: Metal & More"))
        assertTrue(s.contains("Tracks: 200"))
        assertTrue(s.contains("Bring Me The Horizon (100)"))
        assertTrue(s.contains("2000s ("))
        assertEquals(AiRoast.SAMPLE, s.lines().count { it.startsWith("- ") })
    }

    @Test fun roastAnswerIsUnwrapped() {
        assertEquals("You love it.", AiRoast.parse("```json\n{\"roast\": \"You love it.\"}\n```"))
        assertEquals("Plain text.", AiRoast.parse("Plain text."))
    }
}

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

    @Test fun wrappedRanksFiveArtistsAndDecadeShares() {
        val tracks = listOf(
            t("1", "One", "Metallica", year = 1988, ms = 446_000),
            t("2", "Fuel", "Metallica", year = 1997),
            t("3", "Enter Sandman", "Metallica", year = 1991),
            t("4", "Teardrop", "Massive Attack", year = 1998),
            t("5", "Angel", "Massive Attack", year = 1998),
            t("6", "Song 2", "Blur", year = 1997),
            t("7", "Hey Jude", "The Beatles", year = 1968),
            t("8", "Bad Guy", "Billie Eilish", year = 2019),
            t("9", "Yellow", "Coldplay", year = 2000),
            t("10", "Untitled", "Various Artists"),
            // An album saved is not a song.
            Track(id = "a", title = "Ride the Lightning", artists = listOf("Metallica"), kind = Track.KIND_ALBUM, year = 1984),
        )
        val s = Wrapped.compute(tracks, 2026)
        assertEquals(listOf("Metallica" to 3, "Massive Attack" to 2), s.topArtists.take(2).map { it.name to it.tracks })
        assertEquals(5, s.topArtists.size)
        // Ties in alphabetical order of the normalized name ("The Beatles" is "beatles"), spelled as met.
        assertEquals(listOf("The Beatles", "Billie Eilish", "Blur"), s.topArtists.drop(2).map { it.name })
        assertEquals(6, s.artistCount)
        // 9 dated songs: five of the nineties (56 %), then one each, the earlier decades first; the 2010s do not make the top four.
        assertEquals(listOf(1990 to 56, 1960 to 11, 1980 to 11, 2000 to 11), s.decades.map { it.decade to it.percent })
        assertEquals(1990, s.topDecade)
        assertEquals(Wrapped.Song("Hey Jude", "The Beatles", 1968), s.oldest)
        assertEquals(446_000L + 9 * 200_000L, s.durationMs)
        assertNull(s.newest)
    }

    @Test fun wrappedNewestIsTheLastAdded() {
        val tracks = listOf(
            Track(id = "1", title = "Old Love", artists = listOf("A"), addedAt = 1_000),
            Track(id = "2", title = "New Love", artists = listOf("B", "C"), addedAt = 5_000, year = 2026),
        )
        val s = Wrapped.compute(tracks, 2026)
        assertEquals(Wrapped.Song("New Love", "B", 2026, 5_000), s.newest)
    }

    @Test fun wrappedServicesOncePerBrand() {
        fun c(id: String, service: String, name: String, family: String = name) = Wrapped.Connector(id, service, name, family, 0xFF000000 + id.length)
        val connectors = listOf(
            c("ytm", "ytm", "YouTube Music"), c("amazon", "amazon", "Amazon Music"),
            c("spotify_bridge", "spotify_bridge", "Spotify (web)", "Spotify"),
            c("soundcloud_web", "soundcloud", "SoundCloud (web)", "SoundCloud"),
            c("tidal", "tidal", "TIDAL"),
            c("youtube", "youtube", "YouTube Music (Google API)", "YouTube Music"),
            c("spotify", "spotify", "Spotify"), c("spotify@2", "spotify", "Spotify"),
            // A connector whose family name still has its route: the brackets go all the same.
            c("qobuz_web", "qobuz_web", "Qobuz (web)"),
        )
        val s = Wrapped.services(connectors)
        assertEquals(listOf("YouTube Music", "Amazon Music", "Spotify", "SoundCloud", "TIDAL", "Qobuz"), s.map { it.name })
        // The music app's own glyph: YouTube Music's, Spotify's official route.
        assertEquals("ytm", s[0].serviceId)
        assertEquals("spotify", s[2].serviceId)
        assertEquals("Spotify", Wrapped.brandName("Spotify (plugin)"))
        assertEquals("Subsonic / Navidrome", Wrapped.brandName("Subsonic / Navidrome"))
        assertEquals("(web)", Wrapped.brandName("(web)"))

        val labels = Wrapped.chipLabels(connectors)
        assertEquals("YouTube Music", labels["ytm"])
        assertEquals("YouTube Music (Google API)", labels["youtube"])
        assertEquals("Spotify (web)", labels["spotify_bridge"])
        assertEquals("Spotify", labels["spotify"])
        assertEquals("Spotify 2", labels["spotify@2"])
        assertEquals("TIDAL", labels["tidal"])
        assertEquals("Qobuz", labels["qobuz_web"])
    }

    @Test fun wrappedChosenNeverEmpty() {
        assertEquals(listOf("a", "c"), Wrapped.chosen(listOf("a", "b", "c"), setOf("b", "gone")))
        assertEquals(listOf("a", "b"), Wrapped.chosen(listOf("a", "b"), setOf("a", "b")))
    }

    @Test fun wrappedNamesPackWholeInTwoLines() {
        val measure = { s: String -> s.length.toFloat() }
        val names = listOf("YouTube Music", "Amazon Music", "Spotify", "SoundCloud", "TIDAL", "Qobuz", "Deezer", "Apple Music")
        val lines = Wrapped.packNames(names, 30f, 2, measure)
        assertEquals(listOf("YouTube Music · Amazon Music", "Spotify · SoundCloud · +4"), lines)
        lines.forEach { assertTrue(it, it.length <= 30) }
        // Everything fits: no count.
        assertEquals(listOf("Spotify · TIDAL"), Wrapped.packNames(listOf("Spotify", "TIDAL"), 30f, 2, measure))
        // A name longer than a line stays whole on its own line.
        assertEquals(listOf("A very very long service name here", "B"), Wrapped.packNames(listOf("A very very long service name here", "B"), 10f, 2, measure))
        // One line, the count takes a name's place when needed.
        assertEquals(listOf("Spotify · +2"), Wrapped.packNames(listOf("Spotify", "SoundCloud", "TIDAL"), 18f, 1, measure))
        assertTrue(Wrapped.packNames(emptyList(), 10f, 2, measure).isEmpty())
    }

    @Test fun wrappedHistoryKeepsTheNewestTwenty() {
        val s = Wrapped.compute(listOf(t("1", "One", "Metallica", year = 1988)), 2026)
        val svc = listOf(Wrapped.Service("spotify", "Spotify", 0xFF1DB954))
        var h = emptyList<Wrapped.Saved>()
        for (i in 1..25) h = Wrapped.remember(h, Wrapped.save(s, svc, 2026, madeAt = i * 1000L))
        assertEquals(Wrapped.MAX_HISTORY, h.size)
        assertEquals(25_000L, h.first().madeAt)
        assertEquals(6_000L, h.last().madeAt)
        // The same one again is not doubled; an older one goes in its place by date.
        h = Wrapped.remember(h, h[3])
        assertEquals(Wrapped.MAX_HISTORY, h.size)
        assertEquals(h.map { it.madeAt }.sortedDescending(), h.map { it.madeAt })
        val saved = h.first()
        assertEquals("Metallica", saved.topArtist)
        assertEquals(svc, saved.services)
        // Kept as JSON in the store: it reads back the same, and an older file without it reads as empty.
        val text = com.xlollx.songport.net.json.encodeToString(com.xlollx.songport.data.StoreData.serializer(), com.xlollx.songport.data.StoreData(wrapped = h))
        assertFalse(text.contains("\"tracks\":[{"))
        assertEquals(h, com.xlollx.songport.net.json.decodeFromString(com.xlollx.songport.data.StoreData.serializer(), text).wrapped)
        assertTrue(com.xlollx.songport.net.json.decodeFromString(com.xlollx.songport.data.StoreData.serializer(), "{}").wrapped.isEmpty())
    }

    @Test fun wrappedHistoryTravelsWithTheRunHistoryOnly() {
        val a = com.xlollx.songport.contract.ContractEnv()
        val w = Wrapped.Saved(id = "w1", madeAt = 10, year = 2026, trackCount = 42, age = 30, bornIn = 1996, topArtists = listOf(Wrapped.Artist("Mina", 7)))
        a.store.update { it.copy(wrapped = listOf(w)) }
        assertTrue(com.xlollx.songport.data.ConfigBackup.read(com.xlollx.songport.data.ConfigBackup.export(a, "test")).wrapped.isEmpty())
        val snap = com.xlollx.songport.data.ConfigBackup.read(com.xlollx.songport.data.ConfigBackup.export(a, "test", history = true))
        assertEquals(listOf(w), snap.wrapped)
        val b = com.xlollx.songport.contract.ContractEnv()
        val mine = w.copy(id = "w2", madeAt = 20)
        b.store.update { it.copy(wrapped = listOf(mine)) }
        com.xlollx.songport.data.ConfigBackup.import(b, snap)
        assertEquals(listOf("w2", "w1"), b.store.data.wrapped.map { it.id })
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

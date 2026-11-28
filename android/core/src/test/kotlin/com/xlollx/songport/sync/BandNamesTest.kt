package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bands whose name holds an "&" are one artist (2.2.7, item 9e): "Of Mice & Men" came out of an
 * export as "Of Mice, Men" and was searched on the server as "Of Mice". Several artists stay several.
 */
class BandNamesTest {
    private val bands = listOf("Of Mice & Men", "Simon & Garfunkel", "Mumford & Sons", "Dimitri Vegas & Like Mike")

    @Test fun aBandWithAnAmpersandIsOneArtistWhereverANameIsSplit() {
        for (b in bands) {
            assertEquals(b, listOf(b), CsvCodec.splitArtists(b))
            assertEquals(b, listOf(b), TitleParser.splitArtists(b))
            assertEquals(b, listOf(b), RadioTitles.track("$b - Some Song")!!.artists)
            assertNull(b, Matcher.primaryArtist(b))
            assertTrue(b, Matcher.oneAct(b))
        }
        assertEquals(listOf("Of Mice & Men", "Bring Me the Horizon"), CsvCodec.splitArtists("Of Mice & Men, Bring Me the Horizon"))
        assertEquals(listOf("Bring Me the Horizon", "Of Mice & Men"), CsvCodec.splitArtists("Bring Me the Horizon & Of Mice & Men"))
        assertEquals("Of Mice & Men", Matcher.primaryArtist("Of Mice & Men, Bring Me the Horizon"))
    }

    @Test fun severalArtistsAreStillSeveral() {
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), CsvCodec.splitArtists("Daft Punk & Pharrell Williams"))
        assertEquals(listOf("Mahmood", "Blanco"), TitleParser.splitArtists("Mahmood & Blanco"))
        assertEquals("Calvin Harris", Matcher.primaryArtist("Calvin Harris, Dua Lipa"))
        assertEquals(listOf("A", "B", "C"), Matcher.splitArtists("A, B & C", Regex("""\s*,\s*|\s+&\s+""")))
    }

    /** The export of what is missing on the server writes the band as the source named it. */
    @Test fun theMissingListKeepsTheBandWhole() {
        val rows = CsvCodec.parse("title,artist,album\nThe Depths,Of Mice & Men,The Flood\nThe Boxer,Simon & Garfunkel,Bridge over Troubled Water\n")
        assertEquals(listOf(listOf("Of Mice & Men"), listOf("Simon & Garfunkel")), rows.map { it.artists })
        val want = Wanted.Want("x", "The Depths", rows[0].artists, "The Flood", 0, null, null, emptyList(), emptyList(), 0, false)
        assertTrue(Wanted.Format.CSV.encode(listOf(want)).contains("The Depths,Of Mice & Men,The Flood"))
        // A source that lists the band among others keeps it whole too, and matches the library's copy.
        val src = Track("s", "Something", listOf("Of Mice & Men", "Bring Me the Horizon"), durationMs = 200_000)
        val lib = Track("l", "Something", listOf("Of Mice & Men"), durationMs = 200_000)
        assertTrue(Matcher.score(src, lib) >= Matcher.REVIEW_THRESHOLD)
    }

    /** 9c: several artists in the source, the library's file by the first of them (or all in one field). */
    @Test fun aSongOfSeveralArtistsMatchesTheLibrarysCopyByTheFirst() {
        val src = Track("s", "Something On My Mind", listOf("Purple Disco Machine", "Duke Dumont", "Nothing But Thieves"), album = "Exotica", durationMs = 214_000)
        for (artists in listOf(
            listOf("Purple Disco Machine"),
            listOf("Purple Disco Machine, Duke Dumont, Nothing But Thieves"),
            listOf("Purple Disco Machine; Duke Dumont; Nothing But Thieves"),
            listOf("Purple Disco Machine", "Duke Dumont", "Nothing But Thieves"),
        )) {
            val lib = Track("l", "Something On My Mind", artists, album = "Exotica", durationMs = 214_000)
            assertTrue(artists.toString(), Matcher.score(src, lib, Matcher.SERVER_POLICY) >= Matcher.REVIEW_THRESHOLD)
        }
        // The library holds the original, the source a remix: not the same song (left out on purpose).
        val remix = Track("r", "Bad Habits (MEDUZA Remix)", listOf("Ed Sheeran"), durationMs = 180_000)
        val original = Track("o", "Bad Habits", listOf("Ed Sheeran"), durationMs = 231_000)
        assertTrue(Matcher.score(remix, original, Matcher.SERVER_POLICY) < Matcher.DEFAULT_THRESHOLD)
    }
}

package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistPickTest {
    private val names = listOf("Zucchero", "Ásgeir", "muse", "Arctic Monkeys", "Björk", "Beyoncé")

    @Test fun alphabeticalIgnoringCaseAndAccents() {
        assertEquals(listOf("Arctic Monkeys", "Ásgeir", "Beyoncé", "Björk", "muse", "Zucchero"), ArtistPick.byName(names) { it })
    }

    @Test fun theNameFilterIgnoresCaseAndAccents() {
        assertEquals(listOf("Björk"), ArtistPick.named(names, "BJORK") { it })
        assertEquals(listOf("Ásgeir", "Beyoncé"), ArtistPick.named(names, "e") { it }.filter { it != "muse" && it != "Zucchero" && it != "Arctic Monkeys" })
        assertEquals(listOf("Ásgeir"), ArtistPick.named(names, " asg ") { it })
        assertEquals(names, ArtistPick.named(names, "  ") { it })
    }

    @Test fun theChosenArtistsOrTheWholePlaylist() {
        val all = listOf("Muse", "Coldplay", "Björk")
        assertEquals(all, ArtistPick.chosen(all, emptySet(), " "))
        // The ticked ones in the playlist's order (most played first), then one typed.
        assertEquals(listOf("Muse", "Björk"), ArtistPick.chosen(all, setOf("Björk", "Muse")))
        assertEquals(listOf("Coldplay", "Ultimo"), ArtistPick.chosen(all, setOf("Coldplay"), " Ultimo "))
        assertEquals(listOf("Ultimo"), ArtistPick.chosen(all, emptySet(), "Ultimo"))
        // Typed but already ticked: once.
        assertEquals(listOf("Björk"), ArtistPick.chosen(all, setOf("Björk"), "bjork"))
        // A ticked name the playlist no longer has is ignored.
        assertEquals(all, ArtistPick.chosen(all, setOf("Gone"), ""))
    }

    @Test fun theCapsFitEachServicesLimitsAndOnlyCutTheWholePlaylist() {
        // setlist.fm: 1440 calls a day, one per artist: a whole playlist many times a day.
        assertTrue(1440 / LookupCaps.SETLISTS >= 20)
        // Discogs: 25 calls a minute without a token, 60 with one; about a minute a search.
        assertTrue(LookupCaps.RECORDS_ANON <= 25 && LookupCaps.RECORDS_TOKEN <= 60)
        // MusicBrainz, one a second: a search of about a minute.
        assertTrue(LookupCaps.CONCERTS <= 60)
        val all = (1..80).map { "A$it" }
        assertEquals(LookupCaps.CONCERTS, LookupCaps.cut(all, LookupCaps.CONCERTS, whole = true).size)
        assertEquals(80, LookupCaps.cut(all, LookupCaps.CONCERTS, whole = false).size)
        assertTrue(ArtistPick.isWhole(all, emptySet(), " "))
        assertTrue(ArtistPick.isWhole(all, setOf("gone"), ""))
        assertFalse(ArtistPick.isWhole(all, setOf("A3"), ""))
        assertFalse(ArtistPick.isWhole(all, emptySet(), "Muse"))
    }
}

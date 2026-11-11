package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(all, ArtistPick.chosen(all, null))
        // The picked ones in the playlist's order (most played first), then the ones added by name.
        assertEquals(listOf("Muse", "Björk"), ArtistPick.chosen(all, setOf("Björk", "Muse")))
        assertEquals(listOf("Coldplay", "Abba", "Ultimo"), ArtistPick.chosen(all, setOf("Ultimo", "Coldplay", "Abba")))
        // Chosen mode with nothing picked looks up nothing (the page asks to pick some first).
        assertEquals(emptyList<String>(), ArtistPick.chosen(all, emptySet()))
    }

    @Test fun howManyOneSearchLooksUp() {
        val all = (1..80).map { "A$it" }
        assertEquals(50, ArtistPick.count(all, null, 50))
        assertEquals(20, ArtistPick.count(all.take(20), null, 50))
        // Picked by hand: all of them, past the cap too.
        assertEquals(60, ArtistPick.count(all, all.take(59).toSet() + "Extra", 50))
    }

    @Test fun theListShowsThePlaylistAndTheAddedOnesFilteredAlphabetically() {
        val all = listOf("Muse", "Bring Me The Horizon", "Björk")
        assertEquals(listOf("Björk", "Bring Me The Horizon", "Muse", "Ultimo"), ArtistPick.rows(all, setOf("Muse", "Ultimo"), ""))
        assertEquals(listOf("Bring Me The Horizon"), ArtistPick.rows(all, emptySet(), "BRING"))
        assertEquals(listOf("Björk", "Bring Me The Horizon"), ArtistPick.rows(all, emptySet(), "b"))
        assertEquals(listOf("Björk"), ArtistPick.rows(all, emptySet(), "bjö"))
        assertEquals(listOf("Ultimo"), ArtistPick.rows(all, setOf("Ultimo"), "ult"))
    }

    @Test fun aNameIsOfferedToAddOnlyWhenNotThereAlready() {
        val all = listOf("Muse", "Björk")
        assertEquals("Ultimo", ArtistPick.addable(all, emptySet(), "  Ultimo "))
        assertEquals("Bring Me", ArtistPick.addable(all, emptySet(), "Bring   Me"))
        // A partial match still offers it: the list shows the match, the row adds what was typed.
        assertEquals("Mus", ArtistPick.addable(all, emptySet(), "Mus"))
        assertNull(ArtistPick.addable(all, emptySet(), "bjork"))
        assertNull(ArtistPick.addable(all, setOf("Ultimo"), "ULTIMO"))
        assertNull(ArtistPick.addable(all, emptySet(), "   "))
    }

    @Test fun addingKeepsThePlaylistsSpellingAndNoDoubles() {
        val all = listOf("Muse", "Björk")
        assertEquals(setOf("Björk"), ArtistPick.add(all, emptySet(), "bjork"))
        assertEquals(setOf("Muse", "Ultimo"), ArtistPick.add(all, setOf("Muse"), " Ultimo "))
        assertEquals(setOf("Ultimo"), ArtistPick.add(all, setOf("Ultimo"), "ultimo"))
        assertEquals(emptySet<String>(), ArtistPick.add(all, emptySet(), " "))
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
    }

    @Test fun everyPageLooksUpTheFifteenPlayedMostByDefault() {
        assertEquals(15, LookupCaps.DEFAULT)
        listOf(LookupCaps.CONCERTS, LookupCaps.SETLISTS, LookupCaps.RECORDS_TOKEN, LookupCaps.RECORDS_ANON).forEach { assertEquals(15, it) }
        val all = (1..40).map { "A$it" }
        assertEquals(15, ArtistPick.count(all, null, LookupCaps.CONCERTS))
        assertEquals(all.take(15), LookupCaps.cut(all, LookupCaps.RECORDS_ANON, whole = true))
        // The ones chosen by hand are all looked up, past the default.
        assertEquals(20, ArtistPick.count(all, all.take(20).toSet(), LookupCaps.SETLISTS))
    }
}

package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
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
}

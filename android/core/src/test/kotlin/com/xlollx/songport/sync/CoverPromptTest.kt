package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPromptTest {
    private fun t(artist: String, title: String = "x") = Track(id = "$artist$title", title = title, artists = listOf(artist), album = "", durationMs = 0)

    @Test
    fun topArtistsByFrequencyThenFirstSeen() {
        val tracks = listOf(t("Metallica"), t("Avenged Sevenfold"), t("avenged sevenfold"), t("Linkin Park"), t("Metallica"), t("Avenged Sevenfold"), t("Dope"))
        assertEquals(listOf("Avenged Sevenfold", "Metallica", "Linkin Park", "Dope"), CoverPrompt.topArtists(tracks, 5))
        assertEquals(listOf("Avenged Sevenfold", "Metallica"), CoverPrompt.topArtists(tracks, 2))
    }

    @Test
    fun posterListsTheTopArtistsSongsTwoEachThenTheNextArtists() {
        val tracks = listOf(t("Solo", "s1"), t("A", "a1"), t("B", "b1"), t("A", "a2"), t("A", "a3"), t("B", "b2"), t("C", "c1"), t("C", "c2"), t("D", "d1"), t("E", "e1"), t("F", "f1"), t("G", "g1"))
        val got = CoverPrompt.posterTracks(tracks, n = 9, artists = 3, perArtist = 2).map { it.title }
        // A (3), B (2), C (2) two each, grouped; then one each of the rest by first appearance.
        assertEquals(listOf("a1", "a2", "b1", "b2", "c1", "c2", "s1", "d1", "e1"), got)
        assertEquals(listOf("a1", "b1"), CoverPrompt.posterTracks(listOf(t("A", "a1"), t("B", "b1")), n = 9).map { it.title })
    }

    @Test
    fun promptCarriesTitleArtistsDescriptionAndTheRules() {
        val p = CoverPrompt.build("Metal & More", listOf("Metallica", "Avenged Sevenfold"), "  late night\n drive  ")
        assertTrue(p.contains("\"Metal & More\""))
        assertTrue(p.contains("Metallica, Avenged Sevenfold"))
        assertTrue(p.contains("Mood and theme: late night drive."))
        assertTrue(p.contains("No text"))
        assertTrue(p.contains("no real people's faces"))
        assertTrue(p.startsWith("Square album cover art"))
    }

    @Test
    fun noDescriptionNoArtistsStillValid() {
        val p = CoverPrompt.build("Chill", emptyList())
        assertFalse(p.contains("sounds like"))
        assertFalse(p.contains("Mood and theme"))
    }
}

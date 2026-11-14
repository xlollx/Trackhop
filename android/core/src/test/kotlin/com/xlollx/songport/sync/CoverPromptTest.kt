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
        // The name as a theme, never quoted: image models write out quoted text.
        assertFalse(p.contains("\""))
        assertTrue(p.contains("inspired by: metal & more."))
        assertTrue(p.contains("Metallica, Avenged Sevenfold"))
        assertTrue(p.contains("Mood: late night drive."))
        assertTrue(p.contains("without any typography"))
        assertTrue(p.contains("no real people's faces"))
        assertTrue(p.startsWith("A wordless square illustration"))
    }

    @Test
    fun noDescriptionNoArtistsStillValid() {
        val p = CoverPrompt.build("Chill", emptyList())
        assertFalse(p.contains("sounds like"))
        assertFalse(p.contains("Mood:"))
    }

    // ------------------------------------------------------------------ most played

    private fun song(id: String, title: String, artist: String, isrc: String? = null) = Track(id = id, title = title, artists = listOf(artist), isrc = isrc)
    private fun play(title: String, artist: String, plays: Int = 0, isrc: String? = null) = Listening.Play(Track(id = "p$title", title = title, artists = listOf(artist), isrc = isrc), plays)

    private val playlist = listOf(
        song("1", "Intro", "A", "ISRC1"), song("2", "Second", "A", "ISRC2"), song("3", "Third", "B", "ISRC3"),
        song("4", "Fourth", "B"), song("5", "Fifth", "C"), song("6", "Sixth", "D"),
    )

    @Test
    fun mostPlayedMatchesByIsrc() {
        // Another title on the service, same recording: the ISRC says so.
        val got = CoverPrompt.mostPlayed(playlist, listOf(play("Third (Remastered 2011)", "Someone else", 40, isrc = "isrc3")), n = 3)
        assertEquals(1, got.fromPlays)
        assertEquals("3", got.tracks.first().id)
    }

    @Test
    fun mostPlayedMatchesByTitleAndFirstArtist() {
        val got = CoverPrompt.mostPlayed(playlist, listOf(play("FIFTH", "c", 12), play("Fifth", "Not C", 50)), n = 3)
        assertEquals(1, got.fromPlays)
        assertEquals("5", got.tracks.first().id)
    }

    @Test
    fun mostPlayedFollowsPlaysThenTheServicesRank() {
        val counted = CoverPrompt.mostPlayed(playlist, listOf(play("Sixth", "D", 3), play("Fourth", "B", 30), play("Missing", "Z", 99)), n = 9)
        assertEquals(listOf("4", "6"), counted.tracks.take(2).map { it.id })
        // Spotify ranks without counts: its order is kept.
        val ranked = CoverPrompt.mostPlayed(playlist, listOf(play("Sixth", "D"), play("Fourth", "B")), n = 9)
        assertEquals(listOf("6", "4"), ranked.tracks.take(2).map { it.id })
    }

    @Test
    fun mostPlayedFillsUpWithTheUsualPicksWithoutRepeats() {
        val got = CoverPrompt.mostPlayed(playlist, listOf(play("Sixth", "D", 5)), n = 4)
        assertEquals(4, got.tracks.size)
        assertEquals("6", got.tracks.first().id)
        assertEquals(got.tracks.size, got.tracks.toSet().size)
        assertEquals(CoverPrompt.posterTracks(playlist.filter { it.id != "6" }, 3), got.tracks.drop(1))
    }

    @Test
    fun mostPlayedTakesAtMostTwoSongsOfOneArtist() {
        val band = playlist + song("7", "Seventh", "A") + song("8", "Eighth", "A")
        val got = CoverPrompt.mostPlayed(band, listOf(play("Seventh", "A", 90), play("Intro", "A", 80), play("Eighth", "A", 70), play("Fifth", "C", 10)), n = 4)
        // The third song of A is left for later: C's song comes before it.
        assertEquals(listOf("7", "1", "5"), got.tracks.take(3).map { it.id })
        assertEquals(3, got.fromPlays)
    }

    @Test
    fun withoutPlaysTheListIsUnchanged() {
        val got = CoverPrompt.mostPlayed(playlist, emptyList())
        assertEquals(0, got.fromPlays)
        assertEquals(CoverPrompt.posterTracks(playlist), got.tracks)
        // Plays that match nothing: the usual list too.
        assertEquals(CoverPrompt.posterTracks(playlist), CoverPrompt.mostPlayed(playlist, listOf(play("Nope", "Z", 9))).tracks)
    }

    @Test fun thePromptTellsWhatIsInThePlaylist() {
        fun t(title: String, artist: String, year: Int) = Track(title, title, listOf(artist), year = year)
        val tracks = listOf(t("Helena", "My Chemical Romance", 2004), t("Sing", "My Chemical Romance", 2010), t("Chop Suey!", "System of a Down", 2001),
            t("Numb (Live)", "Linkin Park", 2003), t("Old One", "Elvis", 1956), t("Toxicity", "System of a Down", 2001))
        assertEquals("2000s–2010s", CoverPrompt.decades(tracks.filter { it.year > 2000 }))
        val p = CoverPrompt.build("Metal & More", CoverPrompt.topArtists(tracks), "", CoverPrompt.sampleSongs(tracks), CoverPrompt.decades(tracks))
        assertTrue(p, p.contains("Its songs include helena"))
        assertTrue(p, p.contains("numb,") || p.contains("numb ("))
        assertFalse(p, p.contains("(live)"))
        assertTrue(p, p.contains("Era of the music:"))
        assertFalse(p.contains("\""))
    }
}

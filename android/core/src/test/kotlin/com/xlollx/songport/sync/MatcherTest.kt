package com.xlollx.songport.sync

import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private fun t(title: String, vararg artists: String, dur: Long = 0, isrc: String? = null, id: String = "x") =
        Track(id = id, title = title, artists = artists.toList(), durationMs = dur, isrc = isrc)

    @Test fun normalizeStripsVersionsAndFeat() {
        assertEquals("bohemian rhapsody", Matcher.normalizeTitle("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("shape of you", Matcher.normalizeTitle("Shape of You (feat. Someone) [Official Video]"))
        assertEquals("despacito", Matcher.normalizeTitle("Despacito feat. Justin Bieber"))
        assertEquals("cafe del mar", Matcher.normalizeTitle("Café del Mar"))
        assertEquals("rock and roll", Matcher.normalizeTitle("Rock & Roll"))
    }

    @Test fun creditsAndLocalisedVersionsLeaveTheTitle() {
        assertEquals("stay", Matcher.searchTitle("Stay (with Justin Bieber)"))
        assertEquals("tuta gold", Matcher.searchTitle("Tuta gold (con Bresh)"))
        assertEquals("song", Matcher.searchTitle("Song (prod. Metro Boomin)"))
        assertEquals("mockingbird", Matcher.searchTitle("Mockingbird - Prod. by Eminem"))
        assertEquals("song", Matcher.searchTitle("Song w/ Artist"))
        assertEquals("song", Matcher.searchTitle("Song - Remasterizzato 2012"))
        assertEquals("song", Matcher.searchTitle("Song - Versione Remasterizzata"))
        assertEquals("smells like teen spirit", Matcher.searchTitle("Smells Like Teen Spirit - Nevermind Version"))
        // A subtitle that only contains "with" is part of the title.
        assertEquals("dancing with myself", Matcher.searchTitle("(Dancing with Myself)"))
        // What changes the recording stays.
        assertEquals("song acoustic", Matcher.searchTitle("Song - Acoustic"))
        assertEquals("song remix", Matcher.searchTitle("Song - Remix"))
    }

    @Test fun apostrophesJoinTheWord() {
        assertEquals("dont stop me now", Matcher.searchTitle("Don't Stop Me Now - Remastered 2011"))
        assertEquals("dont stop me now", Matcher.searchTitle("Don’t Stop Me Now"))
        assertEquals("Don't Stop Me Now", Matcher.plainTitle("Don't Stop Me Now - Remastered 2011"))
        assertEquals("I Don't Care", Matcher.plainTitle("I Don't Care (with Justin Bieber)"))
        assertEquals("Mockingbird", Matcher.plainTitle("Mockingbird - Prod. by Eminem"))
    }

    @Test fun primaryArtistOnlyForSeveralNames() {
        assertEquals("Calvin Harris", Matcher.primaryArtist("Calvin Harris, Dua Lipa"))
        assertEquals("Mahmood", Matcher.primaryArtist("Mahmood & Blanco"))
        assertEquals("Sfera Ebbasta", Matcher.primaryArtist("Sfera Ebbasta feat. Rvssian"))
        assertNull(Matcher.primaryArtist("Lil Nas X"))
        assertNull(Matcher.primaryArtist("Queen"))
    }

    @Test fun creditedTitleMatchesThePlainOne() {
        val src = t("I Don't Care (with Justin Bieber)", "Ed Sheeran", dur = 219_000)
        val cand = t("I Don't Care", "Ed Sheeran", "Justin Bieber", dur = 220_000)
        assertTrue(Matcher.score(src, cand) >= Matcher.REVIEW_THRESHOLD)
    }

    @Test fun remixIsKept() {
        assertTrue(Matcher.normalizeTitle("Song (Artist Remix)").contains("remix"))
        assertEquals("song", Matcher.normalizeTitle("Song (Extended Mix)"))
    }

    @Test fun isrcWins() {
        val a = t("Whatever", "Nobody", isrc = "USRC17607839")
        val b = t("Totally different", "Someone else", isrc = "usrc17607839")
        assertEquals(1.0, Matcher.score(a, b), 0.0)
    }

    @Test fun sameSongDifferentVersionMatches() {
        val a = t("Hotel California", "Eagles", dur = 391_000)
        val b = t("Hotel California - 2013 Remaster", "Eagles", dur = 390_500)
        assertTrue(Matcher.score(a, b) > 0.9)
    }

    @Test fun differentSongDoesNotMatch() {
        val a = t("Hotel California", "Eagles", dur = 391_000)
        val b = t("Take It Easy", "Eagles", dur = 211_000)
        assertTrue(Matcher.score(a, b) < Matcher.DEFAULT_THRESHOLD)
    }

    @Test fun bestPicksClosestDuration() {
        val src = t("Blue Monday", "New Order", dur = 448_000)
        val cands = listOf(
            t("Blue Monday - Radio Edit", "New Order", dur = 245_000, id = "short"),
            t("Blue Monday", "New Order", dur = 447_000, id = "full"),
            t("Blue Monday '88", "New Order", dur = 250_000, id = "88"),
        )
        assertEquals("full", Matcher.best(src, cands)?.id)
    }

    @Test fun unknownArtistsStillMatchOnTitle() {
        val src = t("Smells Like Teen Spirit", "Nirvana", dur = 301_000)
        val cand = t("Smells Like Teen Spirit", dur = 302_000) // es. da YouTube senza artista
        assertTrue(Matcher.score(src, cand) >= Matcher.DEFAULT_THRESHOLD)
    }

    @Test fun noCandidateAboveThreshold() {
        val src = t("Imagine", "John Lennon", dur = 183_000)
        assertNull(Matcher.best(src, listOf(t("Imagine Dragons - Believer", "Imagine Dragons", dur = 204_000))))
    }

    @Test fun artistFeatIgnored() {
        assertTrue(Matcher.artistScore(listOf("Daft Punk feat. Pharrell Williams"), listOf("Daft Punk")) >= 0.9)
        assertTrue(Matcher.artistScore(listOf("The Beatles"), listOf("Beatles")) >= 0.9)
    }
}

class TrackIndexTest {
    private fun t(id: String, title: String, artist: String, dur: Long, isrc: String? = null) =
        Track(id = id, title = title, artists = listOf(artist), durationMs = dur, isrc = isrc)

    @Test fun findsExistingByIsrcTitleOrNothing() {
        val idx = Matcher.TrackIndex(listOf(
            t("1", "Hotel California - 2013 Remaster", "Eagles", 391_000, "USEE10001993"),
            t("2", "Take It Easy", "Eagles", 211_000),
            t("3", "Blue Monday", "New Order", 447_000),
        ))
        assertEquals("1", idx.best(t("x", "whatever", "who", 0, "usee10001993"))?.id)
        assertEquals("1", idx.best(t("x", "Hotel California", "Eagles", 390_000))?.id)
        assertEquals("3", idx.best(t("x", "Blue Monday", "New Order", 448_000))?.id)
        assertNull(idx.best(t("x", "Imagine", "John Lennon", 183_000)))
    }
}

class VersionPenaltyTest {
    private fun t(title: String, artist: String = "Band", dur: Long = 200_000) =
        Track(id = title, title = title, artists = listOf(artist), durationMs = dur)

    @Test fun liveOrKaraokeCandidateLosesToStudio() {
        val src = t("Wonderwall", "Oasis")
        val studio = t("Wonderwall - Remastered", "Oasis")
        val live = t("Wonderwall (Live at Knebworth)", "Oasis")
        val karaoke = t("Wonderwall (Karaoke Version)", "Karaoke Band")
        assertEquals("Wonderwall - Remastered", Matcher.best(src, listOf(live, karaoke, studio))?.id)
        assertTrue(Matcher.score(src, live) < Matcher.score(src, studio))
        assertTrue(Matcher.score(src, karaoke) < Matcher.DEFAULT_THRESHOLD)
    }

    @Test fun remasterIsNeutral() {
        assertEquals(1.0, Matcher.versionFactor("Song", "Song (2011 Remaster)"), 0.0)
        assertEquals(1.0, Matcher.versionFactor("Song (Mono)", "Song (Stereo)"), 0.0)
    }

    @Test fun liveSourceAcceptsLiveCandidate() {
        val src = t("Hotel California (Live)", "Eagles")
        val live = t("Hotel California - Live", "Eagles")
        val studio = t("Hotel California", "Eagles")
        assertTrue(Matcher.score(src, live) > Matcher.score(src, studio))
    }

    @Test fun sameAlbumWinsTheTieAndOtherAlbumsCostLittle() {
        val src = Track("s", "Get Lucky", listOf("Daft Punk"), "Random Access Memories", 369000)
        val album = Track("a", "Get Lucky", listOf("Daft Punk"), "Random Access Memories (Deluxe)", 369000)
        val single = Track("b", "Get Lucky", listOf("Daft Punk"), "Get Lucky", 369000)
        assertEquals(1.0, Matcher.albumFactor(src.album, album.album), 0.0)
        assertEquals(0.97, Matcher.albumFactor(src.album, single.album), 0.0)
        assertEquals(1.0, Matcher.albumFactor("", single.album), 0.0)
        assertEquals("a", Matcher.best(src, listOf(single, album))?.id)
        // A different album alone never pushes a clear match below the acceptance threshold.
        assertTrue(Matcher.score(src, single) >= Matcher.REVIEW_THRESHOLD)
    }

    @Test fun policyPrefersExplicitAndExcludesLiveWhenAsked() {
        val src = Track("s", "Get Lucky", listOf("Daft Punk"), durationMs = 369000)
        val clean = Track("c", "Get Lucky", listOf("Daft Punk"), durationMs = 369000, explicit = false)
        val explicit = Track("e", "Get Lucky", listOf("Daft Punk"), durationMs = 369000, explicit = true)
        val live = Track("l", "Get Lucky (Live)", listOf("Daft Punk"), durationMs = 369000)
        assertEquals("e", Matcher.best(src, listOf(clean, explicit), policy = MatchPolicy(explicit = 1))?.id)
        assertEquals("c", Matcher.best(src, listOf(clean, explicit), policy = MatchPolicy(explicit = -1))?.id)
        // Without a preference the two are equal; the first stays first.
        assertEquals("c", Matcher.best(src, listOf(clean, explicit))?.id)
        // A live version alone still passes normally, and never with "studio only".
        assertTrue(Matcher.score(src, live) < Matcher.score(src, clean))
        assertNull(Matcher.best(src, listOf(live), policy = MatchPolicy(studioOnly = true)))
        assertTrue(MatchPolicy(strictness = 1).acceptThreshold > MatchPolicy().acceptThreshold)
        assertTrue(MatchPolicy(strictness = -1).reviewThreshold < MatchPolicy().reviewThreshold)
    }
}

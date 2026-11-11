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

    @Test fun numberedPartsAreOneTitleHoweverWrittenAndDifferentPartsAreNot() {
        // #57
        assertEquals("oxygene part 4", Matcher.normalizeTitle("Oxygene, Pt. 4"))
        assertEquals("oxygene part 4", Matcher.normalizeTitle("Oxygène (Part IV)"))
        assertEquals("equinoxe part 5", Matcher.normalizeTitle("Equinoxe Pt 5"))
        assertEquals("Oxygene", Matcher.bareTitle("Oxygene, Pt. 4"))
        assertEquals(null, Matcher.bareTitle("Oxygene"))
        // "I" alone, or words that only look like numerals, stay as they are.
        assertEquals("i want it", Matcher.normalizeTitle("I Want It"))
        assertEquals("part civil", Matcher.normalizeTitle("Part Civil"))
        val src = Track(id = "s", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"))
        val four = Track(id = "4", title = "Oxygene (Part IV)", artists = listOf("Jean-Michel Jarre"))
        val five = Track(id = "5", title = "Oxygene (Part V)", artists = listOf("Jean-Michel Jarre"))
        assertTrue(Matcher.score(src, four) >= 0.9)
        assertTrue(Matcher.score(src, five) < 0.7)
    }

    @Test fun theReportedJarreTitlesMatchTheirOwnPartOnly() {
        // #57, as the reporter's playlist and library name them: the album accented on one side only.
        fun jarre(id: String, title: String, album: String, sec: Int) = Track(id = id, title = title, artists = listOf("Jean-Michel Jarre"), album = album, durationMs = sec * 1000L)
        val parts = listOf("I" to 460, "II" to 488, "III" to 174, "IV" to 254, "V" to 623, "VI" to 400)
            .map { (n, sec) -> jarre("o$n", "Oxygene (Part $n)", "Oxygene", sec) }
        val four = Matcher.bestScored(jarre("s", "Oxygene, Pt. 4", "Oxygène", 255), parts)!!
        assertEquals("oIV", four.track.id)
        assertTrue(four.score.toString(), four.score >= Matcher.REVIEW_THRESHOLD)
        assertEquals("oII", Matcher.best(jarre("s", "Oxygene, Pt. 2", "Oxygène", 488), parts)?.id)
        // Part 4 is never Part 5, even when Part 4 is missing and Part 5 is all there is.
        assertNull(Matcher.best(jarre("s", "Oxygene, Pt. 4", "Oxygène", 254), parts.filter { it.id != "oIV" }))
        assertNull(Matcher.best(jarre("s", "Oxygene, Pt. 4", "Oxygène", 254), listOf(jarre("e", "Equinoxe (Part IV)", "Equinoxe", 254))))
        assertEquals("chronology part 4", Matcher.normalizeTitle("Chronology, Pt. 4 - Remastered"))
        assertTrue(Matcher.score(jarre("s", "Chronology, Pt. 4 - Remastered", "Chronology", 200), jarre("c", "Chronology (Part 4)", "Chronology", 200)) >= Matcher.REVIEW_THRESHOLD)
        assertTrue(Matcher.score(jarre("s", "Equinoxe, Pt. 5", "Equinoxe", 230), jarre("c", "Equinoxe (Part V)", "Equinoxe", 230)) >= Matcher.REVIEW_THRESHOLD)
    }

    @Test fun theBareTitleDropsPartSubtitleAndVersionAsWritten() {
        assertEquals("Oxygene", Matcher.bareTitle("Oxygene, Pt. 4"))
        assertEquals("Oxygène", Matcher.bareTitle("Oxygène (Part IV)"))
        assertEquals("Chronology", Matcher.bareTitle("Chronology, Pt. 4 - Remastered"))
        assertEquals("Magnetic Fields", Matcher.bareTitle("Magnetic Fields, Pt. 1"))
        assertEquals("Equinoxe", Matcher.bareTitle("Equinoxe Teil 5"))
        assertEquals("Song", Matcher.bareTitle("Song (Something Else) - Mono"))
        // Nothing to drop, or nothing left worth a search.
        assertNull(Matcher.bareTitle("Oxygene"))
        assertNull(Matcher.bareTitle("Up (Live)"))
        assertNull(Matcher.bareTitle("Part 2"))
        // Each form once, folded first.
        assertEquals(listOf("oxygene part 4", "Oxygene, Pt. 4", "oxygene", "Oxygene"), Matcher.titleTerms("Oxygene, Pt. 4"))
    }

    @Test fun initialsWithDotsAreOneWord() {
        assertEquals("usa", Matcher.normalizeTitle("U.S.A"))
        assertEquals("usa", Matcher.normalizeTitle("U.S.A."))
        assertEquals("la woman", Matcher.normalizeTitle("L.A. Woman"))
        assertEquals("rem", Matcher.normalizeArtist("R.E.M."))
        assertEquals(Matcher.normalizeArtist("REM"), Matcher.normalizeArtist("R.E.M."))
        // Not initials: a word before the dot, or a part number.
        assertEquals("mr brightside", Matcher.normalizeTitle("Mr. Brightside"))
        assertEquals("oxygene part 4", Matcher.normalizeTitle("Oxygene, Pt. 4"))
        val src = Track(id = "s", title = "U.S.A", artists = listOf("The Exploited"))
        assertTrue(Matcher.score(src, Track(id = "c", title = "USA", artists = listOf("Exploited"))) >= Matcher.REVIEW_THRESHOLD)
        assertTrue(Matcher.score(Track(id = "s", title = "Losing My Religion", artists = listOf("R.E.M.")), Track(id = "c", title = "Losing My Religion", artists = listOf("REM"))) >= Matcher.REVIEW_THRESHOLD)
    }

    @Test fun aSearchByTitlesGoesOnWhileOnlyOtherSongsCameBack() = kotlinx.coroutines.runBlocking {
        val src = Track(id = "s", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"))
        val asked = ArrayList<String>()
        val found = Matcher.searchByTitles(src) { term ->
            asked += term
            when (term) {
                "oxygene part 4" -> listOf(Track(id = "x", title = "Part of Me", artists = listOf("Katy Perry")))
                "oxygene" -> listOf(Track(id = "o5", title = "Oxygene (Part V)", artists = listOf("Jean-Michel Jarre")), Track(id = "o4", title = "Oxygene (Part IV)", artists = listOf("Jean-Michel Jarre")))
                else -> emptyList()
            }
        }
        assertEquals(listOf("x", "o5", "o4"), found.map { it.id })
        // Stops once the track is there: "Oxygene" as written is never asked.
        assertEquals(listOf("oxygene part 4", "Oxygene, Pt. 4", "oxygene"), asked)
        asked.clear()
        Matcher.searchByTitles(src) { asked += it; listOf(Track(id = "o4", title = "Oxygene (Part IV)", artists = listOf("Jean-Michel Jarre"))) }
        assertEquals(listOf("oxygene part 4"), asked)
    }

    @Test fun aRangeOfPartsWrittenTwoWaysIsOnePieceAndOnlyDifferentNumbersArePenalised() {
        fun t(title: String, artist: String) = Track(id = title, title = title, artists = listOf(artist))
        val shine = Matcher.score(t("Shine On You Crazy Diamond (Pts. 1-5)", "Pink Floyd"), t("Shine On You Crazy Diamond (Parts I-V)", "Pink Floyd"))
        assertTrue(shine >= MatchPolicy().acceptThreshold)
        // One set of numbers inside the other is no conflict; different numbers are.
        val bach = Matcher.score(t("BWV 1048: I. Allegro", "Bach"), t("BWV 1048: 1. Allegro", "Bach"))
        assertTrue(bach >= MatchPolicy().acceptThreshold)
        assertTrue(Matcher.score(t("Echoes, Part 4", "Pink Floyd"), t("Echoes, Part 5", "Pink Floyd")) < 0.7)
        assertTrue(Matcher.score(t("BWV 1048: 1. Allegro", "Bach"), t("BWV 1049: 1. Allegro", "Bach")) < 0.7)
    }
}

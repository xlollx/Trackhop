package com.xlollx.songport.sync

import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2.2 matching rules against titles and artists as the services really write them: Spotify and
 * TIDAL list every artist, YouTube Music and Apple Music join them in one name, Deezer puts the guest
 * in the title, Navidrome keeps the file's tag ("A feat. B", "A; B").
 */
class MatcherRealWorldTest {
    private fun t(title: String, vararg artists: String, dur: Long = 0, album: String = "", explicit: Boolean? = null) =
        Track(id = title + artists.joinToString(), title = title, artists = artists.toList(), durationMs = dur, album = album, explicit = explicit)

    private val accept = MatchPolicy().acceptThreshold
    private val review = MatchPolicy().reviewThreshold

    private fun sure(a: Track, b: Track, policy: MatchPolicy = MatchPolicy()) =
        Matcher.score(a, b, policy).let { assertTrue("${a.title}/${b.title}: $it", it >= policy.reviewThreshold) }
    private fun toCheck(a: Track, b: Track, policy: MatchPolicy = MatchPolicy()) =
        Matcher.score(a, b, policy).let { assertTrue("${a.title}/${b.title}: $it", it >= policy.acceptThreshold && it < policy.reviewThreshold) }
    private fun refused(a: Track, b: Track, policy: MatchPolicy = MatchPolicy()) =
        Matcher.score(a, b, policy).let { assertTrue("${a.title}/${b.title}: $it", it < policy.acceptThreshold) }

    @After fun forget() = ArtistAliases.reset()

    // ------------------------------------------------------------------ guests on both sides

    @Test fun guestsWrittenInEveryServicesPlaceAreTheSameSong() {
        val spotify = t("STAY (with Justin Bieber)", "The Kid LAROI", "Justin Bieber", dur = 141_805)
        val ytm = t("STAY", "The Kid LAROI & Justin Bieber", dur = 141_000)
        val apple = t("STAY", "The Kid LAROI & Justin Bieber", dur = 141_806)
        val deezer = t("STAY (with Justin Bieber)", "The Kid LAROI", dur = 141_000)
        val tidal = t("STAY", "The Kid LAROI", "Justin Bieber", dur = 141_000)
        val navidrome = t("STAY", "The Kid LAROI feat. Justin Bieber", dur = 141_000)
        val navidromeSemicolon = t("Stay", "The Kid LAROI; Justin Bieber", dur = 142_000)
        val all = listOf(spotify, ytm, apple, deezer, tidal, navidrome, navidromeSemicolon)
        for (a in all) for (b in all) if (a !== b) sure(a, b)
    }

    @Test fun featInTheTitleOrInTheArtistField() {
        sure(t("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk", "Pharrell Williams", "Nile Rodgers", dur = 369_000),
            t("Get Lucky", "Daft Punk feat. Pharrell Williams", dur = 369_600))
        sure(t("One Kiss (with Dua Lipa)", "Calvin Harris", dur = 214_000), t("One Kiss", "Calvin Harris, Dua Lipa", dur = 214_800))
        sure(t("Señorita", "Shawn Mendes", "Camila Cabello", dur = 190_800), t("Señorita", "Shawn Mendes & Camila Cabello", dur = 191_000))
        sure(t("Tuta gold (con Bresh)", "Mahmood", dur = 180_000), t("Tuta gold", "Mahmood", "Bresh", dur = 180_500))
        sure(t("Unholy (feat. Kim Petras)", "Sam Smith", dur = 156_000), t("Unholy", "Sam Smith & Kim Petras", dur = 156_900))
        sure(t("Te Felicito", "Shakira", "Rauw Alejandro", dur = 172_000), t("Te Felicito (feat. Rauw Alejandro)", "Shakira", dur = 172_000))
        sure(t("Envolver - Remix", "Anitta", "Justin Quiles", dur = 205_000), t("Envolver (Remix) [part. Justin Quiles]", "Anitta", dur = 205_000))
        sure(t("Under Pressure - Remastered 2011", "Queen", "David Bowie", dur = 248_000), t("Under Pressure", "David Bowie & Queen", dur = 248_000))
    }

    @Test fun differentGuestsOnBothSidesGoToTheReviewNeverAway() {
        // The "Old Town Road" remixes: the same song by Lil Nas X with another guest.
        toCheck(t("Old Town Road (feat. Billy Ray Cyrus) - Remix", "Lil Nas X", dur = 157_000),
            t("Old Town Road (feat. Young Thug & Mason Ramsey) - Remix", "Lil Nas X", dur = 157_000))
        toCheck(t("Beautiful People", "Ed Sheeran", "Khalid", dur = 197_000), t("Beautiful People (feat. Cardi B)", "Ed Sheeran", dur = 197_000))
        // A check, never a reason to refuse: a match that was sure stays accepted.
        assertTrue(Matcher.score(t("Song (feat. A)", "Main", dur = 200_000), t("Song (feat. B)", "Main", dur = 200_000)) >= accept)
    }

    @Test fun oneSideWithoutGuestsSaysNothing() {
        sure(t("I Don't Care (with Justin Bieber)", "Ed Sheeran", dur = 219_000), t("I Don't Care", "Ed Sheeran", dur = 220_000))
        sure(t("Despacito", "Luis Fonsi", dur = 229_000), t("Despacito (feat. Daddy Yankee)", "Luis Fonsi", dur = 229_000))
    }

    @Test fun bandNamesAreNotSplitIntoGuests() {
        assertEquals(listOf("simon and garfunkel"), Matcher.people(t("The Boxer", "Simon & Garfunkel")))
        assertEquals(listOf("earth wind and fire"), Matcher.people(t("September", "Earth, Wind & Fire")))
        assertEquals(listOf("bob marley and the wailers"), Matcher.people(t("Is This Love", "Bob Marley & The Wailers")))
        assertEquals(listOf("tyler the creator"), Matcher.people(t("EARFQUAKE", "Tyler, The Creator")))
        assertEquals(listOf("kid laroi", "justin bieber"), Matcher.people(t("STAY", "The Kid LAROI & Justin Bieber")))
        assertEquals(listOf("calvin harris", "dua lipa"), Matcher.people(t("One Kiss (with Dua Lipa)", "Calvin Harris")))
        // A producer is no guest, nor a part.
        assertEquals(listOf("main"), Matcher.people(t("Song (prod. Metro Boomin)", "Main")))
        assertEquals(listOf("jean michel jarre"), Matcher.people(t("Oxygene (Part. 4)", "Jean-Michel Jarre")))
        // A duo written as one name on one side, two on the other, with a guest: the same song.
        sure(t("Litoral (feat. Guest)", "Jorge e Mateus", dur = 200_000), t("Litoral", "Jorge & Mateus", "Guest", dur = 200_000))
        sure(t("Duo Song", "Jorge e Mateus", "Ana Castela", dur = 200_000), t("Duo Song", "Jorge & Mateus", dur = 200_000))
    }

    // ------------------------------------------------------------------ versions

    @Test fun remixesByDifferentPeopleAreDifferentVersions() {
        assertEquals(setOf("tiesto"), Matcher.remixers("Ritual (Tiësto Remix)"))
        assertEquals(emptySet<String>(), Matcher.remixers("Ritual (Radio Edit)"))
        assertEquals(emptySet<String>(), Matcher.remixers("Ritual - Extended Mix"))
        assertEquals(setOf("kygo"), Matcher.remixers("Ritual [Kygo Edit]"))
        refused(t("Levitating (The Blessed Madonna Remix)", "Dua Lipa", dur = 260_000), t("Levitating (Moon Boots Remix)", "Dua Lipa", dur = 260_000))
        sure(t("Levitating (The Blessed Madonna Remix)", "Dua Lipa", dur = 260_000), t("Levitating - The Blessed Madonna Remix", "Dua Lipa", dur = 260_000))
        // A remix and a plain "Remix": maybe the same, not refused for it.
        assertTrue(Matcher.score(t("Song (Kygo Remix)", "A", dur = 200_000), t("Song - Remix", "A", dur = 200_000)) >= accept)
    }

    @Test fun versionWordsInManyLanguagesAreOneVersion() {
        assertEquals(setOf("acoustic"), Matcher.versionMarkers("Canción (Acústico)"))
        assertEquals(setOf("acoustic"), Matcher.versionMarkers("Lied - Akustik Version"))
        assertEquals(setOf("acoustic"), Matcher.versionMarkers("Canzone (Versione Acustica)"))
        assertEquals(setOf("acoustic"), Matcher.versionMarkers("Chanson (Version acoustique)"))
        assertEquals(setOf("remix"), Matcher.versionMarkers("Song (DJ Rmx)"))
        assertEquals(setOf("remix"), Matcher.versionMarkers("Canción (Remezcla)"))
        assertEquals(setOf("live"), Matcher.versionMarkers("Şarkı (Canlı)"))
        assertEquals(setOf("live"), Matcher.versionMarkers("Piosenka (Na żywo)"))
        assertEquals(setOf("live"), Matcher.versionMarkers("曲 (ライブ)"))
        assertEquals(setOf("live"), Matcher.versionMarkers("노래 (라이브)"))
        assertEquals(setOf("live"), Matcher.versionMarkers("歌 (现场)"))
        assertEquals(setOf("karaoke"), Matcher.versionMarkers("曲 (カラオケ)"))
        assertEquals(setOf("instrumental"), Matcher.versionMarkers("Song (Instrumentale)"))
        assertEquals(setOf("spedup"), Matcher.versionMarkers("Song (Sped Up)"))
        assertEquals(setOf("slowed", "reverb"), Matcher.versionMarkers("Song (Slowed + Reverb)"))
        assertEquals(setOf("karaoke"), Matcher.versionMarkers("Song (In the Style of Adele)"))
        assertEquals(setOf("remaster"), Matcher.versionMarkers("Canção - Remasterizado"))
        // The same version in two languages is the same; a version only on the candidate is not.
        assertEquals(1.0, Matcher.versionFactor("Song (Acoustic)", "Song (Acústico)"), 0.0)
        assertEquals(1.0, Matcher.versionFactor("Song (Remix)", "Song (RMX)"), 0.0)
        assertEquals(0.55, Matcher.versionFactor("Song", "Song (Canlı)"), 0.0)
        assertEquals(0.55, Matcher.versionFactor("Song", "Song (Sped Up)"), 0.0)
        // "Live Forever" is a title, and both have it.
        assertEquals(1.0, Matcher.versionFactor("Live Forever", "Live Forever - Remastered"), 0.0)
    }

    @Test fun aReRecordingIsAnotherRecording() {
        assertEquals(setOf("rerecord"), Matcher.versionMarkers("Love Story (Taylor's Version)"))
        refused(t("Love Story", "Taylor Swift", dur = 235_000), t("Love Story (Taylor’s Version)", "Taylor Swift", dur = 235_500))
        // The source asks for the new one and only the old one is there: a guess, to check.
        toCheck(t("Love Story (Taylor's Version)", "Taylor Swift", dur = 235_500), t("Love Story", "Taylor Swift", dur = 235_000))
        sure(t("Love Story (Taylor's Version)", "Taylor Swift", dur = 235_500), t("Love Story (Taylor’s Version)", "Taylor Swift", dur = 235_000))
    }

    @Test fun tributeAndKaraokeActsAreNeverTheArtist() {
        refused(t("Bohemian Rhapsody", "Queen", dur = 354_000), t("Bohemian Rhapsody", "Queen Tribute Band", dur = 354_000))
        refused(t("Rolling in the Deep", "Adele", dur = 228_000), t("Rolling in the Deep", "Sing King Karaoke", dur = 228_000))
        refused(t("Someone Like You", "Adele", dur = 285_000), t("Someone Like You (Made Famous by Adele)", "Ameritz Karaoke", dur = 285_000))
        refused(t("Creep", "Radiohead", dur = 238_000), t("Creep", "Vitamin String Quartet", dur = 238_000))
        refused(t("Yellow", "Coldplay", dur = 266_000), t("Yellow", "Rockabye Baby!", dur = 266_000))
        // A tribute act's own song, on both services, is itself.
        sure(t("Bohemian Rhapsody", "Queen Tribute Band", dur = 354_000), t("Bohemian Rhapsody", "Queen Tribute Band", dur = 354_000))
    }

    @Test fun fromAMovieAndBonusTracksAreTheSameSong() {
        sure(t("Let It Go (From \"Frozen\"/Soundtrack Version)", "Idina Menzel", dur = 224_000), t("Let It Go", "Idina Menzel", dur = 224_500))
        sure(t("Shallow (From A Star Is Born)", "Lady Gaga", "Bradley Cooper", dur = 215_000), t("Shallow", "Lady Gaga & Bradley Cooper", dur = 216_000))
        sure(t("Song - From \"The Movie\"", "Artist", dur = 200_000), t("Song", "Artist", dur = 200_000))
        sure(t("Fade Away [Bonus Track]", "Artist", dur = 200_000), t("Fade Away", "Artist", dur = 201_000))
        sure(t("Fade Away - Bonus Track", "Artist", dur = 200_000), t("Fade Away", "Artist", dur = 201_000))
        sure(t("Fade Away (Japan Bonus Track)", "Artist", dur = 200_000), t("Fade Away", "Artist", dur = 201_000))
    }

    // ------------------------------------------------------------------ artist names

    @Test fun artistQuirks() {
        assertEquals(1.0, Matcher.artistScore(listOf("AC/DC"), listOf("ACDC")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("A\$AP Rocky"), listOf("ASAP Rocky")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("Ke\$ha"), listOf("Kesha")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("JAY-Z"), listOf("Jay Z")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("Beatles, The"), listOf("The Beatles")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("Adele - Topic"), listOf("Adele")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("AdeleVEVO"), listOf("Adele")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("Daft Punk Music"), listOf("Daft Punk")), 0.0)
        assertEquals(1.0, Matcher.artistScore(listOf("Royal Blood Official"), listOf("Royal Blood")), 0.0)
        sure(t("Highway to Hell", "AC/DC", dur = 208_000), t("Highway To Hell", "ACDC", dur = 208_000))
        // Not every pair of names is one.
        assertTrue(Matcher.artistScore(listOf("Adele"), listOf("Adam")) < 0.9)
    }

    // ------------------------------------------------------------------ lengths

    @Test fun lengthsWithin2_5sOr5sForARemasterOrAnotherEdition() {
        assertEquals(1.0, Matcher.durationFactor(200_000, 202_500), 0.0)
        assertEquals(0.93, Matcher.durationFactor(200_000, 204_000), 0.0)
        assertEquals(1.0, Matcher.durationFactor(200_000, 204_900, loose = true), 0.0)
        assertEquals(0.93, Matcher.durationFactor(200_000, 205_100, loose = true), 0.0)
        // A remaster on one side: 4 s apart is the same length.
        assertEquals(Matcher.score(t("Song", "A", dur = 200_000), t("Song", "A", dur = 200_000)),
            Matcher.score(t("Song - 2009 Remaster", "A", dur = 200_000), t("Song", "A", dur = 204_000)), 0.0)
        // A clean and an explicit edition.
        assertEquals(Matcher.score(t("Song", "A", dur = 200_000), t("Song", "A", dur = 200_000)),
            Matcher.score(t("Song", "A", dur = 200_000, explicit = true), t("Song", "A", dur = 204_000, explicit = false)), 0.0)
        // Without either, 4 s apart counts a little.
        assertTrue(Matcher.score(t("Song", "A", dur = 200_000), t("Song", "A", dur = 204_000)) < 1.0)
    }

    // ------------------------------------------------------------------ albums and editions

    @Test fun anAlbumsEditionsAreTheSameAlbum() {
        assertTrue(Matcher.sameAlbum("Abbey Road", "Abbey Road (Super Deluxe Edition)"))
        assertTrue(Matcher.sameAlbum("Rumours", "Rumours - Expanded Edition"))
        assertTrue(Matcher.sameAlbum("Nevermind (Remastered)", "Nevermind"))
        assertTrue(Matcher.sameAlbum("1989", "1989 (Deluxe)"))
        assertTrue(Matcher.sameAlbum("Thriller", "Thriller 25 (Super Deluxe Edition)"))
        assertTrue(Matcher.sameAlbum("OK Computer", "OK Computer OKNOTOK 1997 2017"))
        assertTrue(Matcher.sameAlbum("Random Access Memories", "Random Access Memories (10th Anniversary Edition)"))
        assertTrue(Matcher.sameAlbum("Back in Black", "Back In Black (Expanded)"))
        assertFalse(Matcher.sameAlbum("Abbey Road", "Let It Be"))
        assertEquals(1.0, Matcher.albumFactor("Abbey Road", "Abbey Road (2019 Mix) [Super Deluxe]", MatchPolicy(sameAlbum = true)), 0.0)
        assertEquals("葉惠美".let { Matcher.normalizeAlbum(it) }, Matcher.normalizeAlbum("叶惠美"))
    }

    // ------------------------------------------------------------------ #81: Chinese names

    @Test fun traditionalAndSimplifiedChineseAreOne() {
        assertEquals("周杰伦", Hanzi.simplified("周杰倫"))
        assertEquals("说好不哭", Hanzi.simplified("說好不哭"))
        assertEquals("Jay Chou", Hanzi.simplified("Jay Chou"))
        // A character that is also its own simplified form stays.
        assertEquals("乾", Hanzi.simplified("乾"))
        sure(t("說好不哭", "周杰倫", "五月天阿信", dur = 222_000), t("说好不哭", "周杰伦", "五月天阿信", dur = 222_000))
        sure(t("晴天", "周杰倫", dur = 269_000, album = "葉惠美"), t("晴天", "周杰伦", dur = 269_000, album = "叶惠美"))
        // Only for comparing: what is searched is the name as written.
        assertEquals("說好不哭", Matcher.searchTitle("說好不哭"))
    }

    @Test fun artistsInTwoScriptsAreMatchedByTitleAlbumAndLengthToCheck() {
        // YouTube Music lists 周杰倫, Spotify Jay Chou: same title, album and length.
        toCheck(t("晴天", "周杰倫", dur = 269_000, album = "葉惠美"), t("晴天", "Jay Chou", dur = 269_500, album = "叶惠美"))
        toCheck(t("晴天", "Jay Chou", dur = 269_000, album = "Ye Hui Mei"), t("晴天", "周杰倫", dur = 270_500, album = "Ye Hui Mei"))
        // Lengths more than 2 s apart, another album, no album, no length: not enough.
        refused(t("晴天", "周杰倫", dur = 269_000, album = "葉惠美"), t("晴天", "Jay Chou", dur = 272_000, album = "葉惠美"))
        refused(t("晴天", "周杰倫", dur = 269_000, album = "葉惠美"), t("晴天", "Jay Chou", dur = 269_000, album = "Greatest Works of Art"))
        refused(t("晴天", "周杰倫", dur = 269_000), t("晴天", "Jay Chou", dur = 269_000))
        refused(t("晴天", "周杰倫", album = "葉惠美"), t("晴天", "Jay Chou", album = "葉惠美"))
        // Two Latin names that differ are two artists, whatever the album says.
        refused(t("Dynamite", "Taio Cruz", dur = 202_000, album = "Dynamite"), t("Dynamite", "BTS", dur = 202_000, album = "Dynamite"))
    }

    @Test fun musicBrainzNamesMakeTheArtistsOne() {
        refused(t("晴天", "周杰倫", dur = 269_000), t("晴天", "Jay Chou", dur = 269_000))
        ArtistAliases.teach(listOf("Jay Chou", "周杰倫", "周杰伦", "Chou Chieh-lun"))
        sure(t("晴天", "周杰倫", dur = 269_000), t("晴天", "Jay Chou", dur = 269_000))
        sure(t("晴天", "Jay Chou", dur = 269_000), t("晴天", "周杰伦", dur = 269_000))
        // Another artist is still another one.
        refused(t("晴天", "Jay Chou", dur = 269_000), t("晴天", "林俊傑", dur = 269_000))
        assertEquals("Jay Chou", ArtistAliases.namesOf("周杰倫").first())
    }

    @Test fun romanisationMakesNoFalseFriends() {
        // A kana or Hangul title read in Latin letters meets a Latin title only with the same artist.
        refused(t("さくら", "森山直太朗", dur = 300_000), t("Sakura", "Ikimono-gakari", dur = 300_000))
        refused(t("사랑", "아이유", dur = 200_000), t("Sarang", "Sarang Band", dur = 200_000))
        refused(t("Кино", "Ария", dur = 200_000), t("Kino", "Kino", dur = 200_000))
        refused(t("ロマンス", "Ado", dur = 200_000), t("Romance", "Ado Tribute", dur = 200_000))
        refused(t("Αγάπη", "Νίκος Παπάζογλου", dur = 200_000), t("Agapi", "Sakis Rouvas", dur = 200_000))
        // The same artist read across scripts still meets.
        com.xlollx.songport.Features.build = true
        try {
            assertTrue(Matcher.score(t("Группа крови", "Кино", dur = 285_000), t("Gruppa krovi", "Kino", dur = 285_000)) >= accept)
            refused(t("Кино", "Ария", dur = 200_000), t("Kino", "Kino", dur = 200_000))
            refused(t("사랑", "아이유", dur = 200_000), t("Sarang", "Sarang Band", dur = 200_000))
        } finally {
            com.xlollx.songport.Features.build = false
        }
    }

    @Test fun artistNamesCanDifferWhenTheSyncSaysSo() {
        val p = MatchPolicy(artistsMayDiffer = true)
        // The same title, album and length: accepted, not even to check.
        sure(t("晴天", "周董", dur = 269_000, album = "葉惠美"), t("晴天", "Jay Chou", dur = 270_000, album = "叶惠美"), p)
        sure(t("Song", "Artist Name", dur = 200_000, album = "Album"), t("Song", "Other Spelling", dur = 202_500, album = "Album (Deluxe)"), p)
        // No album to compare: to check.
        toCheck(t("Song", "Artist Name", dur = 200_000), t("Song", "Other Spelling", dur = 200_000), p)
        // Another album, another length, another title: no.
        refused(t("Song", "Artist Name", dur = 200_000, album = "Album"), t("Song", "Other Spelling", dur = 200_000, album = "Other"), p)
        refused(t("Song", "Artist Name", dur = 200_000, album = "Album"), t("Song", "Other Spelling", dur = 204_000, album = "Album"), p)
        refused(t("Song Two", "Artist Name", dur = 200_000, album = "Album"), t("Song", "Other Spelling", dur = 200_000, album = "Album"), p)
        // A live take or a karaoke version is no other spelling.
        refused(t("Song", "Artist Name", dur = 200_000, album = "Album"), t("Song (Live)", "Other Spelling", dur = 200_000, album = "Album"), p)
        // Off (the default): the same pair is not taken.
        refused(t("Song", "Artist Name", dur = 200_000, album = "Album"), t("Song", "Other Spelling", dur = 200_000, album = "Album"))
        assertEquals(review, MatchPolicy().reviewThreshold, 0.0)
    }
}

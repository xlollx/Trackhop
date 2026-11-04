package com.xlollx.songport.ai

import com.xlollx.songport.ai.ArtistMix.Artist
import com.xlollx.songport.ai.ArtistMix.Leaning
import com.xlollx.songport.ai.ArtistMix.Pool
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The keyless generator's reading and mixing, on answers saved from MusicBrainz and ListenBrainz (November 2026). */
class ArtistMixTest {
    private fun fixture(name: String) = parseJson(javaClass.getResource("/artistmix/$name")!!.readText())
    private val radiohead = "a74b1b7f-71a5-4011-9441-d0b5e4122711"

    @Test fun musicBrainzSearchGivesTheFirstConvincingArtist() {
        val a = ArtistMix.parseArtistSearch(fixture("musicbrainz-artist-radiohead.json"))!!
        assertEquals(radiohead, a.mbid)
        assertEquals("Radiohead", a.name)
        assertNull(ArtistMix.parseArtistSearch(parseJson("""{"artists":[{"id":"x","name":"Radiohed Tribute","score":41}]}""")))
        assertNull(ArtistMix.parseArtistSearch(parseJson("""{"created":"2026","count":0,"artists":[]}""")))
    }

    @Test fun similarArtistsComeMostSimilarFirstWithoutTheSeed() {
        val similar = ArtistMix.parseSimilar(fixture("similar-artists-radiohead.json"), radiohead)
        assertEquals(40, similar.size)
        assertEquals("Nirvana", similar.first().name)
        assertTrue(similar.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(similar.none { it.mbid == radiohead })
    }

    @Test fun topRecordingsAreTheArtistsOwnEachSongOnce() {
        val tracks = ArtistMix.parseTopRecordings(fixture("top-recordings-radiohead.json"), radiohead)
        assertEquals("Karma Police", tracks.first().title)
        assertEquals(listOf("Radiohead"), tracks.first().artists)
        assertEquals("OK Computer", tracks.first().album)
        assertTrue(tracks.first().durationMs > 200_000)
        assertEquals(tracks.size, tracks.map { it.title.lowercase() }.distinct().size)
        // A live take of a song already listed, and a recording of someone else, are left out.
        val extra = parseJson(
            """[{"artist_mbids":["$radiohead"],"artist_name":"Radiohead","recording_mbid":"r1","recording_name":"Creep","release_name":"Pablo Honey","length":238000},
               {"artist_mbids":["$radiohead"],"artist_name":"Radiohead","recording_mbid":"r2","recording_name":"Creep (live)","length":250000},
               {"artist_mbids":["someone-else"],"artist_name":"Other","recording_mbid":"r3","recording_name":"Other song"},
               {"artist_mbids":["$radiohead"],"artist_name":"Radiohead","recording_mbid":"r4","recording_name":"Creep - 2009 Remaster"}]""",
        )
        assertEquals(listOf("r1"), ArtistMix.parseTopRecordings(extra, radiohead).map { it.id })
        assertTrue(ArtistMix.isVariant("Molten (Gui Boratto's Rework)"))
        assertTrue(ArtistMix.isVariant("Destiny (Extended Mix)"))
        assertTrue(ArtistMix.isVariant("Creep - Acoustic"))
        assertFalse(ArtistMix.isVariant("Death or Glory (Remastered)"))
        assertFalse(ArtistMix.isVariant("Live Forever"))
        assertFalse(ArtistMix.isVariant("Demons"))
        assertTrue(ArtistMix.parseTopRecordings(parseJson("""{"code":401,"error":"Auth token"}"""), radiohead).isEmpty())
    }

    @Test fun deezerIsTheFallbackForTopTracks() {
        assertEquals(1069L, ArtistMix.parseDeezerArtist(fixture("deezer-search-artist-portishead.json"), "portishead"))
        assertNull(ArtistMix.parseDeezerArtist(fixture("deezer-search-artist-portishead.json"), "Massive Attack"))
        val top = ArtistMix.parseDeezerTop(fixture("deezer-artist-top-portishead.json"), "Portishead")
        assertEquals("Glory Box", top.first().title)
        assertEquals("deezer:", top.first().id.take(7))
        assertTrue(top.first().durationMs > 60_000)
        // "Glory Box (Live)" is the same song as "Glory Box".
        assertEquals(1, top.count { it.title.startsWith("Glory Box") })
        assertTrue(ArtistMix.parseDeezerTop(fixture("deezer-artist-top-portishead.json"), "Beth Gibbons").isEmpty())
    }

    @Test fun seedsAreTypedOrTakenFromAPlaylist() {
        assertEquals(listOf("Radiohead", "Björk", "Muse"), ArtistMix.parseSeeds("Radiohead, Björk\n\nMuse ;muse; radiohead"))
        val playlist = listOf(
            Track("1", "a", listOf("Muse")), Track("2", "b", listOf("Radiohead")), Track("3", "c", listOf("The Radiohead")),
            Track("4", "d", listOf("Björk")), Track("5", "e", listOf("muse")), Track("6", "f", listOf("Radiohead")), Track("7", "g"),
        )
        assertEquals(listOf("Radiohead", "Muse"), ArtistMix.topArtists(playlist, 2))
        assertEquals(listOf("Radiohead", "Muse", "Björk"), ArtistMix.topArtists(playlist))
    }

    @Test fun similarListsOfSeveralSeedsAddUp() {
        val a = listOf(Artist("x", "X", 1000.0), Artist("y", "Y", 500.0), Artist("s2", "Seed two", 900.0))
        val b = listOf(Artist("y", "Y", 10.0), Artist("z", "Z", 8.0))
        val ranked = ArtistMix.rankSimilar(listOf(a, b), setOf("s1", "s2"))
        assertEquals(listOf("y", "x", "z"), ranked.map { it.mbid })
    }

    @Test fun leaningChoosesCloseOrFartherArtists() {
        val ranked = (0 until 60).map { Artist("a$it", "Artist $it", 1000.0 - it) }
        assertEquals((0 until 10).map { "a$it" }, ArtistMix.pickArtists(ranked, 10, Leaning.FAMILIAR, Random(1)).map { it.mbid })
        val discovery = ArtistMix.pickArtists(ranked, 10, Leaning.DISCOVERY, Random(1))
        assertEquals(10, discovery.size)
        assertTrue(discovery.none { it.mbid.removePrefix("a").toInt() < Leaning.DISCOVERY.skipTop })
        val balanced = ArtistMix.pickArtists(ranked, 10, Leaning.BALANCED, Random(1))
        assertEquals(10, balanced.map { it.mbid }.distinct().size)
        assertTrue(balanced.all { it.mbid.removePrefix("a").toInt() < 20 })
        // Rank order is kept, and asking for more than there is gives them all.
        assertEquals(balanced.sortedBy { it.mbid.removePrefix("a").toInt() }, balanced)
        assertEquals(60, ArtistMix.pickArtists(ranked, 80, Leaning.DISCOVERY, Random(1)).size)
    }

    private fun pool(name: String, n: Int) = Pool(Artist("id-$name", name), (1..n).map { Track("$name-$it", "$name song $it", listOf(name)) })

    @Test fun theMixCapsEachArtistAndSpreadsThem() {
        val seed = pool("Seed", 30)
        val similar = (1..30).map { pool("Sim$it", 20) }
        for (leaning in Leaning.entries) {
            val out = ArtistMix.mix(listOf(seed), similar, 50, leaning, Random(7))
            assertEquals(leaning.name, 50, out.size)
            assertEquals(50, out.map { AiMatch.key(it) }.distinct().size)
            val perArtist = out.groupingBy { it.artists.first() }.eachCount()
            assertTrue(leaning.name, perArtist.filterKeys { it != "Seed" }.values.all { it <= leaning.similarCap })
            assertTrue(leaning.name, (perArtist["Seed"] ?: 0) in 1..leaning.seedCap)
            assertFalse(leaning.name, out.zipWithNext().any { (a, b) -> a.artists == b.artists })
        }
        // Familiar keeps each artist's biggest songs; discovery skips them.
        val familiar = ArtistMix.mix(listOf(seed), similar, 50, Leaning.FAMILIAR, Random(7))
        assertTrue(familiar.filter { it.artists.first() == "Sim1" }.all { it.id in setOf("Sim1-1", "Sim1-2", "Sim1-3") })
        val discovery = ArtistMix.mix(listOf(seed), similar, 50, Leaning.DISCOVERY, Random(7))
        assertTrue(discovery.none { it.id.endsWith("-1") || it.id.endsWith("-2") })
    }

    @Test fun fewArtistsGetMoreTracksEachAndNothingRepeatsWhatIsExcluded() {
        val seed = pool("Seed", 10)
        val similar = listOf(pool("A", 15), pool("B", 15))
        val exclude = listOf(Track("x", "A song 1", listOf("A")), Track("y", "seed song 1", listOf("Seed")))
        val out = ArtistMix.mix(listOf(seed), similar, 20, Leaning.FAMILIAR, Random(3), exclude)
        assertEquals(20, out.size)
        assertTrue(out.none { it.id == "A-1" || it.id == "Seed-1" })
        // Only seeds (no similar artist answered): the seeds fill the list.
        assertEquals(10, ArtistMix.mix(listOf(seed), emptyList(), 25, Leaning.BALANCED, Random(3)).size)
        assertTrue(ArtistMix.mix(emptyList(), emptyList(), 25, Leaning.BALANCED, Random(3)).isEmpty())
    }

    @Test fun anyFirstPartOfTheMixKeepsItsVariety() {
        val out = ArtistMix.mix(listOf(pool("Seed", 30)), (1..10).map { pool("Sim$it", 20) }, 30, Leaning.BALANCED, Random(11))
        val firstHalf = out.take(15).map { it.artists.first() }.distinct()
        assertTrue(firstHalf.size >= 8)
    }

    @Test fun fetchesEnoughArtists() {
        assertTrue(ArtistMix.similarNeeded(75, 1, Leaning.BALANCED) * Leaning.BALANCED.similarCap >= 75 - 15)
        assertTrue(ArtistMix.similarNeeded(38, 2, Leaning.FAMILIAR) >= 10)
    }

    @Test fun searchTermsAreEscaped() {
        assertEquals("""AC\/DC""", ArtistMixSource.escape("AC/DC"))
        assertEquals("""daft~ punk~""", ArtistMixSource.fuzzy(" daft  punk "))
        assertEquals(Leaning.DISCOVERY, Leaning.of("discovery"))
        assertEquals(Leaning.BALANCED, Leaning.of(null))
    }
}

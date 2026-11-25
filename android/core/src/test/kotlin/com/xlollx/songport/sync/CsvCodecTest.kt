package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CsvCodecTest {
    @Test fun roundTrip() {
        val tracks = listOf(
            Track("1", "Hello, World", listOf("A", "B"), "Album \"X\"", 200_000, "ISRC1"),
            Track("2", "Multi\nline", listOf("C"), "", 0, null),
        )
        val parsed = CsvCodec.parse(CsvCodec.encode(tracks))
        assertEquals(2, parsed.size)
        assertEquals("Hello, World", parsed[0].title)
        assertEquals(listOf("A", "B"), parsed[0].artists)
        assertEquals("Album \"X\"", parsed[0].album)
        assertEquals(200_000L, parsed[0].durationMs)
        assertEquals("ISRC1", parsed[0].isrc)
        assertEquals("Multi\nline", parsed[1].title)
        assertEquals(0L, parsed[1].durationMs)
    }

    @Test fun exportifyHeaders() {
        val csv = "Track URI,Track Name,Album Name,Artist Name(s),Release Date,Duration (ms),ISRC\n" +
            "spotify:track:1,Get Lucky,Random Access Memories,\"Daft Punk, Pharrell Williams\",2013,369626,GBTDF1300003\n"
        val p = CsvCodec.parse(csv)
        assertEquals(1, p.size)
        assertEquals("Get Lucky", p[0].title)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), p[0].artists)
        assertEquals(369_626L, p[0].durationMs)
        assertEquals("GBTDF1300003", p[0].isrc)
    }

    @Test fun semicolonAndMmSs() {
        val csv = "Titolo;Artista;Durata\nVolare;Domenico Modugno;3:31\n"
        val p = CsvCodec.parse(csv)
        assertEquals("Volare", p[0].title)
        assertEquals(211_000L, p[0].durationMs)
    }

    @Test fun stableIds() {
        val a = CsvCodec.withStableId(Track("", "Song (Remastered)", listOf("Band")))
        val b = CsvCodec.withStableId(Track("zzz", "song", listOf("The Band")))
        val c = CsvCodec.withStableId(Track("", "Other", listOf("Band")))
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
    }

    @Test fun safeNames() {
        assertEquals("My_ Playlist", CsvCodec.safeName("My/ Playlist"))
        assertEquals("playlist", CsvCodec.safeName("   "))
    }

    @Test fun shortTracksKeepTheirMilliseconds() {
        // An 8-second interlude in our own file is 8000 ms, not 8000 seconds (it then matched nothing).
        val parsed = CsvCodec.parse(CsvCodec.encode(listOf(Track("1", "Intro", listOf("A"), "", 8_000))))
        assertEquals(8_000L, parsed[0].durationMs)
        assertEquals(4_500L, CsvCodec.parse("Track Name,Artist Name(s),Duration (ms)\nIntro,A,4500\n")[0].durationMs)
        // Without a unit in the header the old guess stays: small numbers are seconds.
        assertEquals(240_000L, CsvCodec.parse("title,artist,duration\nX,A,240\n")[0].durationMs)
    }

    @Test fun withoutRatingsTheFileIsAsBefore() {
        val tracks = listOf(Track("1", "Volare", listOf("Domenico Modugno"), "Nel blu", 200_000, "ITA1"))
        assertEquals("title,artist,album,duration_ms,isrc\r\nVolare,Domenico Modugno,Nel blu,200000,ITA1\r\n", CsvCodec.encode(tracks))
    }

    @Test fun ratingsTravelInTheirOwnColumns() {
        val tracks = listOf(
            Track("1", "Volare", listOf("Domenico Modugno"), "Nel blu", 200_000, rating = 4, albumRating = 5),
            Track("2", "Azzurro", listOf("Paolo Conte"), durationMs = 180_000),
            Track("3", "Caruso", listOf("Lucio Dalla"), albumRating = 3),
        )
        val text = CsvCodec.encode(tracks)
        assertEquals("title,artist,album,duration_ms,isrc,rating,album_rating", text.lineSequence().first())
        val back = CsvCodec.parse(text)
        assertEquals(listOf(4, 0, 0), back.map { it.rating })
        assertEquals(listOf(5, 0, 3), back.map { it.albumRating })
        // The same song with or without stars keeps its id: matches saved before still hold.
        assertEquals(CsvCodec.parse(CsvCodec.encode(tracks.map { it.copy(rating = 0, albumRating = 0) })).map { it.id }, back.map { it.id })
    }

    @Test fun ratingsWithPodcastColumnsToo() {
        val tracks = listOf(Track("1", "Show", listOf("Pub"), feedUrl = "https://f.example/rss", rating = 2))
        val text = CsvCodec.encode(tracks)
        assertEquals("title,artist,album,duration_ms,isrc,feed_url,itunes_id,rating,album_rating", text.lineSequence().first())
        assertEquals(2, CsvCodec.parse(text).single().rating)
    }

    @Test fun aRatingColumnInAnotherFileIsNotRead() {
        val csv = "Title,Artist,Rating,Album Rating\nVolare,Domenico Modugno,5,4\n"
        val p = CsvCodec.parse(csv).single()
        assertEquals(0, p.rating)
        assertEquals(0, p.albumRating)
        assertEquals("Volare", p.title)
    }
}

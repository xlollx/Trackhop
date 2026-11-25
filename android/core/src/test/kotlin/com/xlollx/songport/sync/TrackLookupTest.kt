package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackLookupTest {
    @Test fun deezer() {
        val t = TrackLookup.parseDeezer("""{"id":3135556,"title":"Harder, Better, Faster, Stronger","isrc":"GBDUW0000059","duration":224,"artist":{"name":"Daft Punk"},"album":{"title":"Discovery"}}""")!!
        assertEquals("3135556", t.id)
        assertEquals("Harder, Better, Faster, Stronger", t.title)
        assertEquals(listOf("Daft Punk"), t.artists)
        assertEquals("Discovery", t.album)
        assertEquals(224_000L, t.durationMs)
        assertEquals("GBDUW0000059", t.isrc)
        assertNull(TrackLookup.parseDeezer("""{"error":{"type":"DataException","message":"no data"}}"""))
    }

    @Test fun itunes() {
        val t = TrackLookup.parseItunes("""{"resultCount":1,"results":[{"wrapperType":"track","trackId":1440857781,"artistName":"Nightwish","collectionName":"Endless Forms Most Beautiful","trackName":"Noise","trackTimeMillis":336000}]}""")!!
        assertEquals("1440857781", t.id)
        assertEquals("Noise", t.title)
        assertEquals(listOf("Nightwish"), t.artists)
        assertEquals("Endless Forms Most Beautiful", t.album)
        assertEquals(336_000L, t.durationMs)
        assertNull(TrackLookup.parseItunes("""{"resultCount":0,"results":[]}"""))
    }

    @Test fun oembedYouTubeSplitsArtistAndDropsTopic() {
        val t = TrackLookup.parseOEmbed("""{"title":"Metallica - Master Of Puppets (Official Video)","author_name":"Metallica - Topic"}""", "v1", "youtube")!!
        assertEquals(listOf("Metallica"), t.artists)
        assertEquals("Master Of Puppets (Official Video)", t.title)
        val onlyChannel = TrackLookup.parseOEmbed("""{"title":"Master Of Puppets","author_name":"MetallicaVEVO"}""", "v1", "youtube")!!
        assertEquals(listOf("Metallica"), onlyChannel.artists)
        assertEquals("Master Of Puppets", onlyChannel.title)
    }

    @Test fun oembedSpotifyTitleOnly() {
        val t = TrackLookup.parseOEmbed("""{"title":"Shepherd of Fire","author_name":"Spotify"}""", "s1", "spotify")!!
        assertEquals("Shepherd of Fire", t.title)
        assertEquals(emptyList<String>(), t.artists)
    }

    @Test fun openGraphSpotify() {
        val html = """<html><head><meta property="og:title" content="Shepherd of Fire"/><meta property="og:description" content="Avenged Sevenfold · Hail to the King · Song · 2013"/></head></html>"""
        val t = TrackLookup.parseOpenGraph(html, "x")!!
        assertEquals("Shepherd of Fire", t.title)
        assertEquals(listOf("Avenged Sevenfold"), t.artists)
    }

    @Test fun openGraphTidalAndEntities() {
        val html = """<meta content="Listen to Carry On by Avenged Sevenfold on TIDAL" name="og:description"><meta content="Carry On &amp; More" property="og:title">"""
        val t = TrackLookup.parseOpenGraph(html, "x")!!
        assertEquals("Carry On & More", t.title)
        assertEquals(listOf("Avenged Sevenfold"), t.artists)
        assertNull(TrackLookup.parseOpenGraph("<html></html>", "x"))
    }

    @Test fun platformSuffixDropped() {
        val t = TrackLookup.fromTitle("y", "Faint | Linkin Park - YouTube Music", null)
        assertEquals("Faint", t.title)
        assertEquals(listOf("Linkin Park"), t.artists)
    }

    @Test fun onlyAmazonMusicsOwnAddressIsFetchedAsPasted() {
        assertEquals(true, TrackLookup.amazonMusicPage("https://music.amazon.it/tracks/B012345678"))
        assertEquals(true, TrackLookup.amazonMusicPage("https://music.amazon.co.uk/albums/B0?trackAsin=B012345678"))
        assertEquals(false, TrackLookup.amazonMusicPage("http://127.0.0.1:8080/?x=music.amazon.com/tracks/B012345678"))
        assertEquals(false, TrackLookup.amazonMusicPage("https://music.amazon.com.example.org/tracks/B012345678"))
        assertEquals(false, TrackLookup.amazonMusicPage("https://music.amazon.bad.co/tracks/B012345678"))
        assertEquals(false, TrackLookup.amazonMusicPage("https://music.amazon.evil.de/tracks/B012345678"))
        assertEquals(true, TrackLookup.amazonMusicPage("https://music.amazon.com.au/tracks/B012345678"))
        assertEquals(true, TrackLookup.amazonMusicPage("https://MUSIC.amazon.de/tracks/B012345678"))
    }
}

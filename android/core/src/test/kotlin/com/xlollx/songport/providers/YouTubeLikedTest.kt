package com.xlollx.songport.providers

import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #30: Liked songs through the Google API, the music videos among the liked ones. */
class YouTubeLikedTest {
    @Test fun onlyTheMusicLikesAreKept() {
        val page = parseJson("""{"items":[
            {"id":"a1","snippet":{"title":"Espresso","channelTitle":"Sabrina Carpenter - Topic","categoryId":"10"},"contentDetails":{"duration":"PT2M55S"}},
            {"id":"b2","snippet":{"title":"How to bake bread","channelTitle":"Cooking","categoryId":"26"},"contentDetails":{"duration":"PT12M"}},
            {"id":"c3","snippet":{"title":"Daft Punk - Get Lucky (Official Video)","channelTitle":"Daft Punk","categoryId":"10"},"contentDetails":{"duration":"PT6M9S"}}
        ]}""")
        val t = YouTubeProvider.likedFrom(page)
        assertEquals(listOf("a1", "c3"), t.map { it.id })
        assertEquals(175_000L, t[0].durationMs)
        assertEquals("Espresso", t[0].title)
    }

    @Test fun videosAboutASongAreLeftOut() {
        assertEquals(false, YouTubeProvider.looksLikeASong("Equal Vision Records - We Came As Romans \"Stay Inspired\" Track Inspiration", 180_000))
        assertEquals(false, YouTubeProvider.looksLikeASong("Billie Eilish - Interview at the Grammys", 300_000))
        assertEquals(false, YouTubeProvider.looksLikeASong("Pink Floyd - Live at Pompeii (Full Concert)", 3_600_000))
        assertEquals(false, YouTubeProvider.looksLikeASong("Dua Lipa - Houdini #shorts", 20_000))
        assertEquals(true, YouTubeProvider.looksLikeASong("Daft Punk - Get Lucky (Official Video)", 369_000))
        assertEquals(true, YouTubeProvider.looksLikeASong("Espresso", 175_000))
        assertEquals(true, YouTubeProvider.looksLikeASong("Some Song (Live)", 240_000))
    }
}

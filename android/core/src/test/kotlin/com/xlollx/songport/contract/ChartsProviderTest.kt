package com.xlollx.songport.contract

import com.xlollx.songport.providers.ChartsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The charts source: Apple's RSS feed and Deezer's chart playlists, as saved from their public answers. */
class ChartsProviderTest {
    private fun res(name: String) = javaClass.getResource("/charts/$name")!!.readText()

    @Test fun appleTop100IsReadWithTitleAndArtist() {
        val ts = ChartsProvider.parseApple(res("apple-it.json"))
        assertEquals(100, ts.size)
        assertTrue(ts.all { it.title.isNotBlank() && it.artists.isNotEmpty() && it.id.startsWith("am:") })
        assertEquals(ts.size, ts.map { it.id }.toSet().size)
    }

    @Test fun deezerChartIsReadWithAlbumAndDuration() {
        val ts = ChartsProvider.parseDeezer(res("deezer-italy.json"))
        assertEquals(30, ts.size)
        assertTrue(ts.all { it.title.isNotBlank() && it.artists.isNotEmpty() && it.id.startsWith("dz:") })
        assertTrue(ts.all { it.durationMs > 30_000 && it.album.isNotBlank() })
    }

    @Test fun readOnlyAndNeedsNoAccount() {
        assertFalse(ChartsProvider.requiresAuth)
        assertFalse(ChartsProvider.canWrite)
        assertFalse(ChartsProvider.canCreatePlaylists)
    }
}

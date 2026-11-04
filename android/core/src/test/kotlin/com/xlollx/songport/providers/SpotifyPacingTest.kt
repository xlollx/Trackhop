package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A developer app's searches go one at a time, spaced: a burst gets it blocked for a day (#34). */
class SpotifyPacingTest {
    @Test fun spotifySearchesOneAtATimeAndSpaced() {
        val p = SpotifyProvider()
        assertEquals(1, p.searchParallelism)
        assertTrue(p.searchSpacingMs >= 1000)
    }
}

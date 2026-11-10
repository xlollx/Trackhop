package com.xlollx.songport.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cards' size as chosen in Settings, and as a low window (a phone on its side) caps it. */
class CardSizeTest {
    private val upright = WindowLayout.of(411, 891)
    private val onItsSide = WindowLayout.of(891, 411)

    @Test fun unknownOrMissingIsTheLargeCardOfBefore() {
        assertEquals(CardSize.LARGE, CardSize.of(null))
        assertEquals(CardSize.LARGE, CardSize.of(""))
        assertEquals(CardSize.LARGE, CardSize.of("huge"))
        for (s in CardSize.entries) assertEquals(s, CardSize.of(s.key))
        // The default in Settings is the card as it always was.
        assertEquals(CardSize.LARGE, CardSize.of(com.xlollx.songport.data.Settings().cardSize))
    }

    @Test fun uprightTheChoiceIsKept() {
        for (s in CardSize.entries) assertEquals(s, CardSize.shown(s.key, upright))
        for (s in CardSize.entries) assertEquals(s, CardSize.shown(s.key, WindowLayout.of(1280, 800)))
    }

    @Test fun onItsSideTheCardsAreSmallSoThatTwoFit() {
        for (window in listOf(onItsSide, WindowLayout.of(568, 320), WindowLayout.of(780, 360))) {
            assertEquals(CardSize.SMALL, window.largestCard)
            for (s in CardSize.entries) assertEquals("${s.key} in $window", CardSize.SMALL, CardSize.shown(s.key, window))
        }
    }

    @Test fun theUsersSmallerChoiceAlwaysWins() {
        for (window in listOf(upright, onItsSide)) assertEquals(CardSize.SMALL, CardSize.shown("small", window))
    }

    @Test fun sizesAreDeclaredLargestFirst() {
        assertEquals(listOf(CardSize.LARGE, CardSize.MEDIUM, CardSize.SMALL), CardSize.entries.sortedBy { it.ordinal })
        assertEquals(CardSize.SMALL, maxOf(CardSize.MEDIUM, CardSize.SMALL))
    }

    @Test fun tilesTwoColumnsOnAPhoneMoreWhereThereIsRoom() {
        for (w in listOf(320, 360, 411, 480)) assertEquals("$w dp", 2, WindowLayout.tileColumns(w))
        // A phone on its side (the rail takes about 80 dp), a tablet's readable width.
        assertEquals(4, WindowLayout.tileColumns(891 - 80))
        assertEquals(3, WindowLayout.tileColumns(600))
        assertEquals(6, WindowLayout.tileColumns(1200))
        // Every tile is at least as wide as asked.
        for (w in 320..1200 step 7) {
            val n = WindowLayout.tileColumns(w)
            val tile = (w - 32 - 12 * (n - 1)) / n.toFloat()
            assertEquals("$w dp: $n columns of $tile dp", true, n == 2 || tile >= WindowLayout.TILE_MIN_DP)
        }
    }
}

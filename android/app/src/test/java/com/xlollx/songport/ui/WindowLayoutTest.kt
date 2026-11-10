package com.xlollx.songport.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window's shape from its size in dp: where the tabs go and how many columns of sync cards fit. */
class WindowLayoutTest {
    @Test fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(360))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(1280))
    }

    @Test fun aPhoneUprightIsAsItAlwaysWas() {
        for ((w, h) in listOf(320 to 568, 360 to 640, 411 to 891, 480 to 960)) {
            assertEquals("$w x $h", WindowLayout.Phone, WindowLayout.of(w, h))
        }
        assertFalse(WindowLayout.Phone.rail)
        assertEquals(1, WindowLayout.Phone.syncColumns)
    }

    @Test fun aPhoneOnItsSideGetsTheRail() {
        // A small phone on its side is still narrower than 600 dp: the rail all the same, the bar would take a fifth of its height.
        val small = WindowLayout.of(568, 320)
        assertEquals(WidthClass.COMPACT, small.width)
        assertTrue(small.rail)
        assertTrue(small.short)
        assertEquals(1, small.syncColumns)
        val usual = WindowLayout.of(780, 360)
        assertEquals(WidthClass.MEDIUM, usual.width)
        assertTrue(usual.rail)
        assertTrue(usual.short)
        // The usual phone on its side: two columns (1.17 report, "non vedo le due colonne"), its cards Small.
        assertEquals(2, usual.syncColumns)
        // A large phone on its side is as wide as a tablet: two columns of cards.
        val large = WindowLayout.of(891, 411)
        assertTrue(large.rail)
        assertTrue(large.short)
        assertEquals(2, large.syncColumns)
    }

    @Test fun tabletsGetTheRailAndTheWideOnesTwoColumns() {
        val sevenInchUpright = WindowLayout.of(600, 960)
        assertTrue(sevenInchUpright.rail)
        assertFalse(sevenInchUpright.short)
        assertEquals(1, sevenInchUpright.syncColumns)
        val tenInch = WindowLayout.of(1280, 800)
        assertTrue(tenInch.rail)
        assertFalse(tenInch.short)
        assertEquals(3, tenInch.syncColumns)
        // Half the screen of a tablet in split screen is a phone again.
        assertEquals(WindowLayout.Phone, WindowLayout.of(400, 800))
    }

    @Test fun columnsFollowTheWidthLeftForTheCards() {
        // Upright: one column, however wide the phone.
        for (w in listOf(320, 360, 411, 480)) assertEquals(1, WindowLayout.of(w, 900).syncColumns)
        // On its side: two from about 600 dp of window on (the rail takes 80).
        for (w in listOf(640, 690, 732, 780, 800, 840, 915)) assertEquals("$w dp on its side", 2, WindowLayout.of(w, 360).syncColumns)
        // Elsewhere as many cards of 360 dp as fit, at most three.
        assertEquals(1, WindowLayout.cardColumns(700, short = false))
        assertEquals(2, WindowLayout.cardColumns(800, short = false))
        assertEquals(3, WindowLayout.cardColumns(1200, short = false))
        assertEquals(3, WindowLayout.cardColumns(2400, short = false))
        for (w in 300..2400 step 10) for (short in listOf(true, false)) {
            val n = WindowLayout.cardColumns(w, short)
            val card = (minOf(w, 1200) - 32 - 12 * (n - 1)) / n
            assertTrue("$w dp: $n columns of $card dp", n == 1 || card >= WindowLayout.CARD_MIN_DP || (short && card >= 230))
        }
    }

    @Test fun contentIsWiderOnlyForTwoColumns() {
        val tablet = WindowLayout.of(1280, 800)
        assertEquals(WindowLayout.READABLE_CONTENT, tablet.maxContentWidth())
        assertEquals(WindowLayout.WIDE_CONTENT, tablet.maxContentWidth(twoColumns = true))
        assertEquals(WindowLayout.READABLE_CONTENT, WindowLayout.of(700, 1000).maxContentWidth(twoColumns = true))
    }
}

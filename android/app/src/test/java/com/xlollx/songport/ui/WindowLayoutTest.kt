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
        assertEquals(1, usual.syncColumns)
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
        assertEquals(2, tenInch.syncColumns)
        // Half the screen of a tablet in split screen is a phone again.
        assertEquals(WindowLayout.Phone, WindowLayout.of(400, 800))
    }

    @Test fun contentIsWiderOnlyForTwoColumns() {
        val tablet = WindowLayout.of(1280, 800)
        assertEquals(WindowLayout.READABLE_CONTENT, tablet.maxContentWidth())
        assertEquals(WindowLayout.WIDE_CONTENT, tablet.maxContentWidth(twoColumns = true))
        assertEquals(WindowLayout.READABLE_CONTENT, WindowLayout.of(700, 1000).maxContentWidth(twoColumns = true))
    }
}

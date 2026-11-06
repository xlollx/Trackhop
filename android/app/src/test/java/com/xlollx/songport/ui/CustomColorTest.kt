package com.xlollx.songport.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomColorTest {
    @Test fun aColourOfOneSOwnRoundTrips() {
        val c = CustomColor.parse("#FF8800")!!
        assertEquals("#FF8800", CustomColor.key(c))
        assertNull(CustomColor.parse("teal"))
        assertNull(CustomColor.parse("#GG0000"))
        assertNull(CustomColor.parse(null))
    }

    @Test fun aDarkColourIsLiftedForDarkMode() {
        val navy = Color(0xFF101040)
        assertTrue(CustomColor.luminance(CustomColor.forDark(navy)) >= 0.28f)
        val pale = Color(0xFFFFF4C0)
        assertEquals(pale, CustomColor.forDark(pale))
    }
}

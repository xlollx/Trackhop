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
        val navy = CustomColor.forDark(Color(0xFF101040))
        assertTrue(CustomColor.luminance(navy) > 0.12f)
        assertTrue(navy.blue > navy.red + 0.3f)
    }

    /** A very dark or very pale choice still makes a card of that colour: same hue, a usable tone. */
    @Test fun extremeChoicesKeepTheirHueAtAUsableTone() {
        for (dark in listOf(false, true)) {
            val navy = CustomColor.tone(Color(0xFF000460), dark)
            assertTrue("navy stays blue", navy.blue > navy.red + 0.2f && navy.blue > navy.green + 0.2f)
            assertTrue("navy is not near black", CustomColor.luminance(navy) > 0.04f)
            val pale = CustomColor.tone(Color(0xFFFFE9A0), dark)
            assertTrue("pale yellow stays yellow", pale.red > pale.blue + 0.2f && pale.green > pale.blue + 0.2f)
            assertTrue("pale yellow is not near white", CustomColor.luminance(pale) < 0.75f)
        }
        // A grey stays a grey.
        val grey = CustomColor.tone(Color(0xFFEBECF5), dark = false)
        assertTrue(kotlin.math.abs(grey.red - grey.blue) < 0.1f)
    }
}

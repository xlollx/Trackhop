package com.xlollx.songport.ui

import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The initial of a service without a mark (Amazon Music, Qobuz…): centred in its circle at every size, and never a punctuation mark. */
class MonogramTest {
    @Test fun theInitialIsALetterOrADigit() {
        assertEquals("A", monogram("Amazon Music"))
        assertEquals("Q", monogram("qobuz"))
        assertEquals("7", monogram("7digital"))
        assertEquals("L", monogram(", LiveOne"))
        assertEquals("Ñ", monogram("  ñandú"))
        assertNull(monogram(""))
        assertNull(monogram(", ·"))
    }

    @Test fun oneLineAsTallAsTheTypeTrimmedAndCentredAtSmallSizes() {
        for (size in listOf(16, 20, 24, 28, 36)) {
            val s = monogramStyle(size.dp)
            assertEquals("type in proportion at ${size}dp", size * 0.46f, s.fontSize.value, 0.001f)
            assertEquals("line as tall as the type at ${size}dp", s.fontSize, s.lineHeight)
            assertEquals(LineHeightStyle.Alignment.Center, s.lineHeightStyle!!.alignment)
            assertEquals(LineHeightStyle.Trim.Both, s.lineHeightStyle!!.trim)
            assertFalse("no font padding at ${size}dp", s.platformStyle!!.paragraphStyle!!.includeFontPadding)
        }
    }
}

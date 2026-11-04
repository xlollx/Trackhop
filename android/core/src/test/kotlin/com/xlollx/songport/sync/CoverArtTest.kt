package com.xlollx.songport.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtTest {
    /** A monospaced stand-in for a font: each character is 0.6 of the size wide. */
    private val mono: (String, Float) -> Float = { s, size -> s.length * size * 0.6f }

    @Test
    fun seedIsStableAndVaries() {
        assertEquals(CoverArt.seed("Road Trip"), CoverArt.seed("  road trip "))
        assertNotEquals(CoverArt.seed("Road Trip"), CoverArt.seed("Road Trip", 1))
        assertNotEquals(CoverArt.seed("Road Trip"), CoverArt.seed("Road Trap"))
        // Fixed across runs and devices: FNV-1a of the name.
        assertEquals(-0x340d631b7bdddcdbL, CoverArt.seed(""))
    }

    @Test
    fun namePaletteIsDeterministicAndWhiteReadsOnItsBackground() {
        assertArrayEquals(CoverArt.namePalette("Chill"), CoverArt.namePalette("Chill"))
        assertFalse(CoverArt.namePalette("Chill").contentEquals(CoverArt.namePalette("Chill", 1)))
        for (name in listOf("Chill", "Metal", "", "日本の歌", "a".repeat(200))) {
            val p = CoverArt.namePalette(name)
            assertEquals(5, p.size)
            assertTrue("$name: white on the background", CoverArt.contrast(CoverArt.WHITE, p[0]) >= 4.5)
        }
    }

    @Test
    fun dominantFindsTheColourfulPartFirst() {
        val white = CoverArt.argb(250, 250, 250)
        val red = CoverArt.argb(220, 20, 30)
        // Mostly white with a red logo: red counts more for being colourful, white still comes.
        val px = IntArray(1000) { if (it < 450) red else white }
        val d = CoverArt.dominant(px, 3)
        assertEquals(2, d.size)
        assertTrue(CoverArt.red(d[0]) > 200 && CoverArt.green(d[0]) < 40)
        // Transparent pixels are ignored; nothing at all gives nothing.
        assertEquals(0, CoverArt.dominant(IntArray(10) { 0x00FF0000 }).size)
        assertEquals(0, CoverArt.dominant(IntArray(0)).size)
    }

    @Test
    fun paletteFromCoversHasADeepBaseAndFallsBackToTheName() {
        val sampled = intArrayOf(CoverArt.argb(240, 230, 120), CoverArt.argb(30, 90, 200))
        val p = CoverArt.palette(sampled, "x")
        assertEquals(5, p.size)
        assertTrue(CoverArt.contrast(CoverArt.WHITE, p[0]) >= 4.5)
        assertArrayEquals(CoverArt.namePalette("x"), CoverArt.palette(IntArray(0), "x"))
        assertFalse(p.contentEquals(CoverArt.palette(sampled, "x", 1)))
    }

    @Test
    fun textColourIsTheOneThatReads() {
        assertEquals(CoverArt.INK, CoverArt.textOn(CoverArt.argb(255, 230, 80)))
        assertEquals(CoverArt.WHITE, CoverArt.textOn(CoverArt.argb(20, 20, 60)))
        assertEquals(21.0, CoverArt.contrast(CoverArt.WHITE, CoverArt.argb(0, 0, 0)), 0.01)
    }

    @Test
    fun hslRoundTrips() {
        val c = CoverArt.hsl(210f, 0.6f, 0.4f)
        val back = CoverArt.toHsl(c)
        assertEquals(210f, back[0], 2f); assertEquals(0.6f, back[1], 0.02f); assertEquals(0.4f, back[2], 0.02f)
    }

    @Test
    fun aShortTitleIsBigOnOneLine() {
        val f = CoverArt.fitTitle("Chill", 900f, 400f, 3, 200f, 40f, measure = mono)
        assertEquals(listOf("Chill"), f.lines)
        assertEquals(200f, f.size, 0.01f)
        assertFalse(f.cut)
    }

    @Test
    fun aLongTitleWrapsSmallerAndStaysInsideTheBox() {
        val title = "Songs to listen to on a very long night drive across the country"
        val f = CoverArt.fitTitle(title, 900f, 400f, 3, 200f, 40f, measure = mono)
        assertTrue(f.lines.size in 2..3)
        assertFalse(f.cut)
        assertTrue(f.lines.all { mono(it, f.size) <= 900f })
        assertTrue(f.lines.size * f.size * 1.08f <= 400f)
        assertEquals(title, f.lines.joinToString(" "))
    }

    @Test
    fun aTitleTooLongForTheBoxEndsInAnEllipsisNeverOutside() {
        val title = (1..60).joinToString(" ") { "word$it" }
        val f = CoverArt.fitTitle(title, 600f, 200f, 3, 120f, 40f, measure = mono)
        assertTrue(f.cut)
        assertTrue(f.lines.size <= 3)
        assertTrue(f.lines.last().endsWith("…"))
        assertTrue(f.lines.all { mono(it, f.size) <= 600f })
        assertTrue(f.lines.size * f.size * 1.08f <= 200f + 0.01f)
    }

    @Test
    fun aWordWiderThanTheLineIsSplit() {
        val lines = CoverArt.wrap("Supercalifragilisticexpialidocious yes", 10 * 6f) { it.length * 6f }
        assertTrue(lines.all { it.length <= 10 })
        assertEquals("Supercalifragilisticexpialidociousyes", lines.joinToString("").replace(" ", ""))
    }
}

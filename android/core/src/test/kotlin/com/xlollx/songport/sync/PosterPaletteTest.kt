package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterPaletteTest {
    private fun palettes(): List<IntArray> {
        val rnd = java.util.Random(7)
        val sampled = (0 until 60).map { IntArray(1 + rnd.nextInt(5)) { CoverArt.argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) } } +
            listOf(intArrayOf(CoverArt.WHITE), intArrayOf(CoverArt.argb(0, 0, 0)), intArrayOf(CoverArt.argb(128, 128, 128)), intArrayOf(CoverArt.argb(255, 235, 0)), IntArray(0))
        return sampled
    }

    @Test
    fun textAndAccentReadOnEveryGroundWhateverTheCover() {
        for (pal in palettes()) for (layout in PosterPalette.Layout.entries) {
            val t = PosterPalette.tones(pal, "Road Trip", layout)
            val what = "$layout ${pal.joinToString { Integer.toHexString(it) }}"
            assertTrue("$what: text", CoverArt.contrast(t.ink, t.ground) >= 4.5)
            assertTrue("$what: text at the bottom", CoverArt.contrast(t.ink, t.groundEnd) >= 4.5)
            assertTrue("$what: accent", CoverArt.contrast(t.accent, t.ground) >= 3.0)
        }
    }

    @Test
    fun theGroundFollowsTheCoverNotAFixedColour() {
        val red = PosterPalette.tones(intArrayOf(CoverArt.argb(200, 30, 30)), "x", PosterPalette.Layout.CLASSIC)
        val teal = PosterPalette.tones(intArrayOf(CoverArt.argb(20, 150, 140)), "x", PosterPalette.Layout.CLASSIC)
        assertNotEquals(red.ground, teal.ground)
        assertTrue(CoverArt.red(red.ground) > CoverArt.blue(red.ground))
        assertTrue(CoverArt.green(teal.ground) > CoverArt.red(teal.ground))
        // A grey cover prints on a neutral ground.
        val grey = PosterPalette.tones(intArrayOf(CoverArt.argb(120, 120, 120)), "x", PosterPalette.Layout.CLASSIC)
        assertEquals(CoverArt.red(grey.ground), CoverArt.blue(grey.ground))
        // The accent is the cover's own colour, not one made up from the name.
        val orange = PosterPalette.tones(intArrayOf(CoverArt.argb(60, 30, 20), CoverArt.argb(233, 160, 59)), "x", PosterPalette.Layout.CLASSIC)
        assertTrue(CoverArt.red(orange.accent) > CoverArt.blue(orange.accent) + 60)
        // No cover at all: the name's colours, the same every time.
        assertEquals(PosterPalette.tones(IntArray(0), "Chill", PosterPalette.Layout.BOLD).ground, PosterPalette.tones(IntArray(0), "Chill", PosterPalette.Layout.BOLD).ground)
    }

    @Test
    fun standOutLeavesAGoodColourAloneAndMovesAPoorOne() {
        val ink = CoverArt.INK
        val yellow = CoverArt.argb(255, 220, 0)
        assertEquals(yellow, PosterPalette.standOut(yellow, ink, 4.5))
        val navy = CoverArt.argb(20, 20, 90)
        val moved = PosterPalette.standOut(navy, ink, 4.5)
        assertTrue(CoverArt.contrast(moved, ink) >= 4.5)
        assertTrue(CoverArt.luminance(moved) > CoverArt.luminance(navy))
    }
}

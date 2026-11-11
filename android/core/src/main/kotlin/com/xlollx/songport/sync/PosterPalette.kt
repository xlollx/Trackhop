package com.xlollx.songport.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colours of a playlist poster, taken from its art rather than fixed: a quiet ground of the art's own hue,
 * an ink that reads on it, and one accent. Three ways to print it ([Layout]).
 */
object PosterPalette {
    /** Classic: a dark print in the art's hue. Minimal: ink on paper. Bold: two inks, the art's brightest colour as the ground. */
    enum class Layout { CLASSIC, MINIMAL, BOLD }

    /** [ground] to [groundEnd] down the sheet, [ink] for the text, [accent] for the small marks. */
    class Tones(val ground: Int, val groundEnd: Int, val ink: Int, val accent: Int)

    const val PAPER = -0xb101a // 0xFFF4EFE6
    private const val PAPER_INK = -0xe4e7ea // 0xFF1B1816

    /**
     * The poster's colours from [sampled], the main colours of its art ([CoverArt.dominant], most
     * present first); from [name]'s own palette when there are none. The ground takes the hue of
     * the most present one, the accent is the most colourful one; an art without colour (a black
     * and white photo) prints in neutral tones, with paper white for an accent.
     */
    fun tones(sampled: IntArray, name: String, layout: Layout): Tones {
        val colours = if (sampled.isEmpty()) CoverArt.namePalette(name).let { intArrayOf(it[0], it[3]) } else sampled
        val base = CoverArt.toHsl(colours[0])
        val hv = CoverArt.toHsl(colours.maxByOrNull { val h = CoverArt.toHsl(it); h[1] * (1f - abs(h[2] - 0.55f)) } ?: colours[0])
        val vivid = if (hv[1] < 0.15f) CoverArt.hsl(40f, 0.3f, 0.86f) else CoverArt.hsl(hv[0], max(hv[1], 0.5f), hv[2].coerceIn(0.45f, 0.65f))
        return when (layout) {
            Layout.CLASSIC -> {
                val ground = CoverArt.hsl(base[0], min(base[1], 0.42f), 0.17f)
                Tones(ground, CoverArt.hsl(base[0], min(base[1], 0.38f), 0.07f), CoverArt.WHITE, standOut(vivid, ground, 4.5))
            }
            Layout.MINIMAL -> {
                val ground = CoverArt.mix(PAPER, vivid, 0.05f)
                Tones(ground, ground, PAPER_INK, standOut(vivid, ground, 3.0))
            }
            Layout.BOLD -> {
                // The ground is moved away from mid-tones until one ink (white or near-black) reads well on it.
                val ink = CoverArt.textOn(vivid)
                val ground = standOut(vivid, ink, 5.0)
                Tones(ground, ground, ink, ink)
            }
        }
    }

    /** [c], made lighter or darker (same hue), away from [against], until the two reach [ratio] contrast. */
    fun standOut(c: Int, against: Int, ratio: Double): Int {
        val h = CoverArt.toHsl(c)
        val up = CoverArt.luminance(against) < 0.18
        var l = h[2]
        var out = c
        while (CoverArt.contrast(out, against) < ratio && (if (up) l < 0.97f else l > 0.03f)) {
            l += if (up) 0.02f else -0.02f
            out = CoverArt.hsl(h[0], h[1], l)
        }
        return out
    }
}

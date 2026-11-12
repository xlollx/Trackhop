package com.xlollx.songport.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xlollx.songport.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page glow never costs legibility: measured at its brightest point (the top of the page, where
 * the bar's title and the status line are), not on the plain surface.
 */
class PageBackdropTest {
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()); val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private val tabs = mapOf(
        MainActivity.TAB_SYNCS to "Syncs", MainActivity.TAB_ACCOUNTS to "Accounts", MainActivity.TAB_DISCOVER to "Discover",
        MainActivity.TAB_TOOLS to "Tools", MainActivity.TAB_SETTINGS to "Settings",
    )

    private fun measure(name: String, dark: Boolean, scheme: ColorScheme) {
        val glows = PageBackdrop.all(dark, scheme.primary, scheme.outline)
        assertEquals("a glow for each of the five tabs", tabs.keys, glows.keys)
        val failures = ArrayList<String>()
        for ((tab, glow) in glows) {
            val at = PageBackdrop.peakOver(scheme.surface, glow)
            val primaryText = contrast(scheme.onSurface, at)
            val secondaryText = contrast(scheme.onSurfaceVariant, at)
            val link = contrast(scheme.primary, at)
            val divider = contrast(scheme.outlineVariant, at)
            val dividerPlain = contrast(scheme.outlineVariant, scheme.surface)
            val card = contrast(scheme.surfaceContainerHigh, at)
            println("%s %-8s peak %.3f over #%06X -> #%06X: text %.2f (plain %.2f), secondary %.2f (plain %.2f), link %.2f, divider %.2f (plain %.2f), card edge %.2f".format(
                name, tabs[tab], glow.peak, scheme.surface.value.shr(32).toInt() and 0xFFFFFF, at.value.shr(32).toInt() and 0xFFFFFF,
                primaryText, contrast(scheme.onSurface, scheme.surface), secondaryText, contrast(scheme.onSurfaceVariant, scheme.surface), link, divider, dividerPlain, card,
            ))
            // Primary text at AAA, everything read as text at AA or better.
            assertTrue("$name ${tabs[tab]}: primary text $primaryText", primaryText >= 7f)
            assertTrue("$name ${tabs[tab]}: secondary text $secondaryText", secondaryText >= 4.5f)
            assertTrue("$name ${tabs[tab]}: links in the primary colour $link", link >= 4.5f)
            // A divider keeps nearly all of the little contrast it has on the plain surface.
            if (divider < dividerPlain * 0.85f) failures += "$name ${tabs[tab]}: divider $divider against $dividerPlain"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun textReadsAtTheBrightestPointInDark() = measure("dark ", true, DarkScheme)
    @Test fun textReadsAtTheBrightestPointInLight() = measure("light", false, LightScheme)

    /** Light as an accent, never a fill: a tenth at most on dark, under 4% on light, Settings quieter still. */
    @Test fun theGlowStaysFaint() {
        assertTrue(PageBackdrop.DARK_PEAK <= 0.10f)
        assertTrue(PageBackdrop.LIGHT_PEAK <= 0.04f)
        val dark = PageBackdrop.all(true, DarkScheme.primary, DarkScheme.outline)
        assertTrue(dark.getValue(MainActivity.TAB_SETTINGS).peak < dark.getValue(MainActivity.TAB_SYNCS).peak)
    }

    /** No hot spot and no edge: full at the centre, nothing at the rim, never rising on the way out. */
    @Test fun theFalloffIsSmooth() {
        assertEquals(1f, PageBackdrop.falloff(0f), 0f)
        assertEquals(0f, PageBackdrop.falloff(1f), 0f)
        var last = 1f
        for (i in 1..100) { val v = PageBackdrop.falloff(i / 100f); assertTrue(v <= last); last = v }
        // Neighbouring steps of the 8-bit alpha differ by one level at most over the glow's 300dp.
        val steps = (0..300).map { (PageBackdrop.DARK_PEAK * PageBackdrop.falloff(it / 300f) * 255f).toInt() }
        assertTrue(steps.zipWithNext().all { (a, b) -> a - b in 0..1 })
    }

    /** The picture itself: the accent alone, brightest at the top, nothing along the bottom, and no step between neighbours. */
    @Test fun thePaintedGlowIsTheAccentFadingToNothing() {
        val glow = PageBackdrop.all(true, DarkScheme.primary, DarkScheme.outline).getValue(MainActivity.TAB_SYNCS)
        val w = 180; val h = 130
        val px = glowPixels(w, h, glow)
        val top = Math.round(glow.peak * 255f)
        assertTrue("only the accent", px.all { it and 0xFFFFFF == 0xC8BFFF })
        assertTrue("never above the peak", px.all { (it ushr 24) <= top + 1 })
        assertTrue("the peak at the top", (px[(w * 0.35f).toInt()] ushr 24) >= top - 1)
        assertTrue("nothing left along the bottom", (0 until w).all { px[(h - 1) * w + it] ushr 24 <= 1 })
        // Neighbours differ by the noise alone (one level), never by a band's step.
        for (y in 0 until h) for (x in 1 until w) assertTrue(kotlin.math.abs((px[y * w + x] ushr 24) - (px[y * w + x - 1] ushr 24)) <= 1)
    }
}

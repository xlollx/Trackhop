package com.xlollx.songport.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.xlollx.songport.MainActivity

/**
 * The ambient glow behind a main tab: a soft light in the tab's own colour at the top of the page,
 * gone into the plain surface within [PageBackdrop.REACH]. The whole look is here: the colour of
 * each tab ([of]), how strong it is ([PageBackdrop.DARK_PEAK], [PageBackdrop.LIGHT_PEAK]) and how it
 * is painted ([pageBackdrop]); to drop it, [of] returns null.
 *
 * Light, never a fill: the surface under it is the theme's own, and at its brightest point (the
 * top, where the bar and the status line are) the text still reads as on the plain surface
 * (PageBackdropTest measures it, for every tab in both themes).
 */
@Immutable
data class PageBackdrop(val accent: Color, val peak: Float) {
    companion object {
        /** How far down the glow reaches, and the most of a low window (a phone on its side) it may take. */
        val REACH = 300.dp
        const val REACH_OF_HEIGHT = 0.6f

        /** The glow's alpha at its brightest point, on a dark and on a light surface; Settings takes [QUIET] of it. */
        const val DARK_PEAK = 0.08f
        const val LIGHT_PEAK = 0.035f
        const val QUIET = 0.5f

        /** How many times a glow has been painted (once per size and colour): the UI tests check a scroll adds none. */
        @Volatile var paints = 0

        /**
         * The glow of [tab]: Syncs in the theme's primary (the brand's, or the wallpaper's with dynamic
         * colours), Accounts teal, Discover the pink of its header, Tools the orange of its sections
         * (the [JobColor]s the pages already wear, each in its light or dark shade), Settings neutral.
         */
        @Composable
        fun of(tab: Int): PageBackdrop? = all(LocalAppDark.current, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outline)[tab]

        /**
         * How much of the peak is left at [t] of the way out (0 at the centre, 1 at the edge): a
         * smooth fall with no slope at either end, so the glow has neither a hot spot nor an edge.
         */
        fun falloff(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return 1f - x * x * (3f - 2f * x)
        }

        /**
         * The colour over [surface] at the glow's brightest point, as the screen composes it (the accent
         * at [peak] alpha over the opaque surface): what the bar's and the status line's text is read against.
         */
        fun peakOver(surface: Color, backdrop: PageBackdrop): Color {
            val a = backdrop.peak
            fun mix(s: Float, c: Float) = s * (1f - a) + c * a
            return Color(mix(surface.red, backdrop.accent.red), mix(surface.green, backdrop.accent.green), mix(surface.blue, backdrop.accent.blue))
        }

        /**
         * The glows by tab, on a dark or a light surface, with [primary] as the theme's primary and
         * [neutral] its outline: what [of] reads from the theme, for the tests to measure.
         */
        fun all(dark: Boolean, primary: Color, neutral: Color): Map<Int, PageBackdrop> {
            val peak = if (dark) DARK_PEAK else LIGHT_PEAK
            fun job(c: JobColor) = PageBackdrop(if (dark) c.dark else c.light, peak)
            return mapOf(
                MainActivity.TAB_SYNCS to PageBackdrop(primary, peak),
                MainActivity.TAB_ACCOUNTS to job(JobColor.TEAL),
                MainActivity.TAB_DISCOVER to job(JobColor.PINK),
                MainActivity.TAB_TOOLS to job(JobColor.ORANGE),
                MainActivity.TAB_SETTINGS to PageBackdrop(neutral, peak * QUIET),
            )
        }
    }
}

/** The glow is one soft gradient: at half resolution it is the same picture, and a quarter of the memory. */
private const val GLOW_SCALE = 0.5f

/**
 * Paints [backdrop] behind the content: one wide ellipse of light centred a little left of the
 * middle of the top edge, falling off smoothly ([PageBackdrop.falloff]).
 *
 * Painted once per size and colour on the CPU ([glowPixels]), then drawn as a plain image, as the
 * brand's beam is (a dithered gradient shader through the GPU took the API 34 test emulators down).
 * Nothing here reads state: a list scrolling over it neither recomposes nor repaints it
 * ([PageBackdrop.paints] counts, for the tests). Keep the modifier in a `remember`, so a
 * recomposition of the caller does not make a new one.
 */
fun Modifier.pageBackdrop(backdrop: PageBackdrop?): Modifier = if (backdrop == null) this else drawWithCache {
    val reach = minOf(PageBackdrop.REACH.toPx(), size.height * PageBackdrop.REACH_OF_HEIGHT)
    val w = (size.width * GLOW_SCALE).toInt().coerceAtLeast(1)
    val h = (reach * GLOW_SCALE).toInt().coerceAtLeast(1)
    val image = android.graphics.Bitmap.createBitmap(glowPixels(w, h, backdrop), w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    PageBackdrop.paints++
    val dst = IntSize(size.width.toInt().coerceAtLeast(1), reach.toInt().coerceAtLeast(1))
    onDrawBehind { drawImage(image, dstSize = dst, filterQuality = FilterQuality.Low) }
}

/**
 * The glow as [w]×[h] pixels (ARGB, not premultiplied): the accent at an alpha that is
 * [PageBackdrop.peak] at the centre (35% of the width, on the top edge) and nothing at the rim of
 * an ellipse [h] tall and 0.9 of the width (at least [h]) across. The alpha has 8 bits and the glow
 * a few dozen of its levels: each pixel is rounded up or down by a fixed noise, so the levels mix
 * instead of showing as bands.
 */
internal fun glowPixels(w: Int, h: Int, backdrop: PageBackdrop): IntArray {
    val rgb = backdrop.accent.toArgb() and 0xFFFFFF
    val cx = w * 0.35f
    val rx = maxOf(w * 0.9f, h.toFloat())
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val dy = y / h.toFloat()
        for (x in 0 until w) {
            val dx = (x - cx) / rx
            val level = backdrop.peak * PageBackdrop.falloff(kotlin.math.sqrt(dx * dx + dy * dy)) * 255f
            // Interleaved gradient noise: even, without a pattern the eye picks up.
            val n = 52.982918f * ((0.06711056f * x + 0.00583715f * y) % 1f)
            out[y * w + x] = ((level + (n - kotlin.math.floor(n))).toInt().coerceIn(0, 255) shl 24) or rgb
        }
    }
    return out
}

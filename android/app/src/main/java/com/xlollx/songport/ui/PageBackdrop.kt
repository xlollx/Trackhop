package com.xlollx.songport.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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

private const val STOPS = 13

/**
 * Paints [backdrop] behind the content: one wide ellipse of light centred a little left of the
 * middle of the top edge, falling off smoothly ([PageBackdrop.falloff]) and dithered, so there is
 * no banding. The shader is built once per size and colour and only drawn after that: nothing here
 * reads state, so a list scrolling over it neither recomposes nor rebuilds anything.
 */
fun Modifier.pageBackdrop(backdrop: PageBackdrop?): Modifier = if (backdrop == null) this else drawWithCache {
    val reach = minOf(PageBackdrop.REACH.toPx(), size.height * PageBackdrop.REACH_OF_HEIGHT)
    val argb = backdrop.accent.toArgb() and 0xFFFFFF
    val colors = IntArray(STOPS) { i -> (((backdrop.peak * PageBackdrop.falloff(i / (STOPS - 1f))) * 255f + 0.5f).toInt() shl 24) or argb }
    val positions = FloatArray(STOPS) { i -> i / (STOPS - 1f) }
    // A circle of radius [reach], stretched sideways to reach past both sides of the page.
    val stretch = (size.width * 0.9f / reach).coerceAtLeast(1f)
    val shader = android.graphics.RadialGradient(0f, 0f, reach, colors, positions, android.graphics.Shader.TileMode.CLAMP).apply {
        setLocalMatrix(android.graphics.Matrix().apply { setScale(stretch, 1f); postTranslate(size.width * 0.35f, 0f) })
    }
    val paint = android.graphics.Paint().apply { isDither = true; this.shader = shader }
    onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, reach, paint) } }
}

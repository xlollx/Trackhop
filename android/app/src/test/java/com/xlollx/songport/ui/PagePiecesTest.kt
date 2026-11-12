package com.xlollx.songport.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pieces the review, the log, the preview, the accounts and the welcome are made of
 * (PagePieces.kt): every text on them reads at 4.5:1 or more, in both themes.
 */
class PagePiecesTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = maxOf(a.luminance(), b.luminance()) to minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun measure(name: String, dark: Boolean, s: ColorScheme) {
        // A page's card: a raised surface in dark mode, white in light mode.
        val card = if (dark) s.surfaceContainer else s.surfaceContainerLowest
        val success = if (dark) Color(0xFF1F4D2E) else Color(0xFFDDF5E3)
        val onSuccess = if (dark) Color(0xFFB9EDC6) else Color(0xFF0F5132)
        val pairs = buildList {
            add(Triple("title on a card", s.onSurface, card))
            add(Triple("second line on a card", s.onSurfaceVariant, card))
            add(Triple("warning word on a card", s.tertiary, card))
            add(Triple("failure on a card", s.error, card))
            add(Triple("button word on a card", s.primary, card))
            add(Triple("picked row", s.onSecondaryContainer, s.secondaryContainer))
            add(Triple("note", s.onSurfaceVariant, s.surfaceContainerHighest))
            add(Triple("inset track", s.onSurface, s.surfaceContainerHighest))
            add(Triple("accent note", s.onPrimaryContainer, s.primaryContainer))
            add(Triple("warning note and score", s.onTertiaryContainer, s.tertiaryContainer))
            add(Triple("error note", s.onErrorContainer, s.errorContainer))
            add(Triple("done mark", onSuccess, success))
            for (c in listOf(JobColor.PURPLE, JobColor.PINK)) {
                add(Triple("section title ${c.key}", sectionInk(c, dark, s.onSurface), s.surface))
                add(Triple("proposal label ${c.key}", sectionInk(c, dark, s.onSurface), s.surfaceContainerHighest))
            }
        }
        val low = pairs.map { (what, fg, bg) -> what to contrast(fg, bg) }
        println(low.joinToString("\n") { (what, c) -> "$name %-28s %.2f".format(what, c) })
        low.forEach { (what, c) -> assertTrue("$name $what: $c", c >= 4.5f) }
    }

    @Test fun everyTextReadsInDark() = measure("dark ", true, DarkScheme)
    @Test fun everyTextReadsInLight() = measure("light", false, LightScheme)
}

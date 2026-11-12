package com.xlollx.songport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The pieces of a page that is not a list of settings (the review, the log, the preview, the
// accounts): the same card, section title, note and round mark everywhere, so these pages read as
// the Settings and Tools sections and the Discover tools do.
// ---------------------------------------------------------------------------------------------

/** The fill of a page's card, as a [PrefSection]'s: a raised surface in dark mode, white in light mode. */
@Composable
fun pageCardColor(): Color = if (LocalAppDark.current) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest

/**
 * A card of a page: filled, with no outline (a soft shadow in light mode, where the fill is white).
 * [selected] turns it to the selection's colour; [edge] draws a line around it where a state must
 * show at a glance (a failed run, a result).
 */
@Composable
fun PageCard(
    modifier: Modifier = Modifier,
    shape: Shape = ToolCardShape,
    selected: Boolean = false,
    edge: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = LocalAppDark.current
    Surface(
        modifier.fillMaxWidth(),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else pageCardColor(),
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = edge?.let { BorderStroke(1.dp, it) },
        shadowElevation = if (dark || selected) 0.dp else 1.dp,
        content = content,
    )
}

/** The colour of a section's title in [accent] (the theme's primary without one): a little deeper in light mode, so it reads. */
@Composable
fun sectionTitleColor(accent: JobColor?): Color {
    val dark = LocalAppDark.current
    return sectionInk(accent ?: return MaterialTheme.colorScheme.primary, dark, MaterialTheme.colorScheme.onSurface)
}

/** [accent] as small text: its dark shade as it is on a dark page, its light shade drawn towards [onSurface] on a light one (PagePiecesTest measures both). */
internal fun sectionInk(accent: JobColor, dark: Boolean, onSurface: Color): Color = if (dark) accent.dark else lerp(accent.light, onSurface, 0.45f)

/** The title of a part of a page: a bar in the part's colour and its name, as a [PrefSection]'s, with what is at its end. */
@Composable
fun PageSectionTitle(title: String, modifier: Modifier = Modifier, accent: JobColor? = null, trailing: (@Composable () -> Unit)? = null) {
    val bar = accent?.let { if (LocalAppDark.current) it.dark else it.light } ?: MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(bar))
        Spacer(Modifier.width(8.dp))
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = sectionTitleColor(accent),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/**
 * A word of explanation on a page: a quiet row with an "i"; a warning or an error wears its tone
 * and its own sign, never the colour alone.
 */
@Composable
fun PageNote(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    val fg = toneContent(tone)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(toneBackground(tone)).padding(start = 12.dp, end = if (trailing == null) 12.dp else 0.dp).heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon ?: when (tone) { Tone.Warning -> Icons.Filled.Warning; Tone.Error -> Icons.Filled.ErrorOutline; else -> Icons.Outlined.Info },
            null, Modifier.size(18.dp), tint = fg,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        trailing?.invoke()
    }
}

/** A round mark that says a state before the words do: an icon on its tone. Decorative: the words next to it say the same. */
@Composable
fun StatusMark(icon: ImageVector, tone: Tone, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(toneBackground(tone)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = toneContent(tone), modifier = Modifier.size(size * 0.5f))
    }
}

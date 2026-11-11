package com.xlollx.songport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

/**
 * What makes a Discover tool its own: an icon, an accent colour and the gradient of its header.
 * Everything else (the header's shape, the sections, the buttons, the states) is the same for all
 * of them, drawn by the pieces in this file.
 */
@Immutable
internal class ToolIdentity(val icon: ImageVector, val accent: Color, val gradient: List<Color>) {
    constructor(icon: ImageVector, accent: Long, vararg gradient: Long) : this(icon, Color(accent), gradient.map { Color(it) })

    /** The header's background: the tool's gradient, or its accent from dark to full. */
    val brush: Brush get() = Brush.linearGradient(gradient.ifEmpty { listOf(lerp(accent, Color.Black, 0.45f), accent) })

    /** Text and icons on a surface filled with [accent]. */
    val onAccent: Color get() = if (accent.luminance() > 0.4f) Color(0xFF14121A) else Color.White

    /** The accent for text, icons and lines on the page: lighter on a dark theme, so it still reads. */
    @Composable
    fun tint(): Color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) lerp(accent, Color.White, 0.5f) else lerp(accent, Color.Black, 0.15f)

    /** A quiet fill in the tool's colour (a badge, the result card). */
    @Composable
    fun container(): Color = accent.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.28f else 0.12f)
}

/** The identity of the tool whose page is on screen ([DiscoverToolScaffold] sets it). */
internal val LocalToolIdentity = staticCompositionLocalOf { ToolIdentity(Icons.Filled.Refresh, 0xFF6B4BD8) }

/** The corners of a tool's cards, and of its header. */
internal val ToolCardShape = RoundedCornerShape(20.dp)
private val ToolHeaderShape = RoundedCornerShape(24.dp)

/**
 * The page of a Discover tool: its header (the tool's icon on its gradient, with one line of what
 * it is for) and, under it, the tool's own sections 12 dp apart, in a column no wider than
 * [maxWidth] and centred in a wide window.
 */
@Composable
internal fun DiscoverToolScaffold(
    identity: ToolIdentity,
    purpose: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 840.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalToolIdentity provides identity) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolHeader(identity, purpose)
                content()
            }
        }
    }
}

@Composable
private fun ToolHeader(identity: ToolIdentity, purpose: String) {
    Row(
        Modifier.fillMaxWidth().clip(ToolHeaderShape).background(identity.brush)
            // A soft dark wash: the white line reads on the gradient's brightest end too.
            .background(Color.Black.copy(alpha = 0.18f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(identity.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(purpose, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
    }
}

/** The longer word on how the tool works, under the header. */
@Composable
internal fun ToolIntro(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The number of a step, on the tool's colour. */
@Composable
private fun StepBadge(step: Int) {
    val id = LocalToolIdentity.current
    Box(Modifier.size(28.dp).clip(CircleShape).background(id.accent), contentAlignment = Alignment.Center) {
        Text("$step", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = id.onAccent)
    }
}

/** The title of a section: the step's number (when it is one), the name, and what is at its end. */
@Composable
internal fun ToolSectionTitle(title: String, step: Int? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (step != null) { StepBadge(step); Spacer(Modifier.width(10.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** One section of a tool on a card: a step to fill in, or a part of the result. */
@Composable
internal fun ToolSection(
    title: String,
    modifier: Modifier = Modifier,
    step: Int? = null,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolSectionTitle(title, step, trailing = trailing)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** The card of a tool's result: in the tool's colour, with a line of it around. */
@Composable
internal fun ToolResultCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val id = LocalToolIdentity.current
    Surface(
        modifier.fillMaxWidth(), shape = ToolHeaderShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, id.tint().copy(alpha = 0.55f)),
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** The tool's main button: as wide as its column, in the tool's colour, with a wheel while it works. */
@Composable
internal fun ToolPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, busy: Boolean = false) {
    val id = LocalToolIdentity.current
    Button(
        onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = id.accent, contentColor = id.onAccent),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        else if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        if (busy || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, textAlign = TextAlign.Center)
    }
}

/** The tool at work: a card with what it is doing and a bar ([progress] 0–1 when it is known). */
@Composable
internal fun ToolLoading(text: String, modifier: Modifier = Modifier, progress: Float? = null) {
    val id = LocalToolIdentity.current
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(id.icon, null, tint = id.tint(), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            val track = id.container()
            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = id.tint(), trackColor = track, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            else LinearProgressIndicator(color = id.tint(), trackColor = track, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Nothing to show (yet): the tool's icon, faded, a line or two, and what to do about it. */
@Composable
internal fun ToolEmpty(title: String, modifier: Modifier = Modifier, body: String? = null, icon: ImageVector? = null, action: (@Composable () -> Unit)? = null) {
    val id = LocalToolIdentity.current
    Surface(modifier.fillMaxWidth(), shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(id.container()).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Icon(icon ?: id.icon, null, tint = id.tint(), modifier = Modifier.size(32.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

/** Something failed: said on the page (not only in a passing snackbar), with a way to try again. */
@Composable
internal fun ToolError(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = ToolCardShape, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer); Spacer(Modifier.width(10.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            }
            if (onRetry != null) OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.artists_retry))
            }
        }
    }
}

/**
 * What goes in and what comes out, side by side in a wide window (a tablet, a phone on its side)
 * and one under the other on a phone held upright.
 */
@Composable
internal fun ToolSplit(start: @Composable ColumnScope.() -> Unit, end: @Composable ColumnScope.() -> Unit) {
    if (LocalWindowLayout.current.width == WidthClass.COMPACT) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { start(); end() }
    } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = start)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = end)
    }
}

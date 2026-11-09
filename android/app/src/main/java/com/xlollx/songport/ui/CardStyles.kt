package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.R
import com.xlollx.songport.model.SyncReport

/** How a coloured sync wears its colour on its card: chosen in Settings, and per sync in the editor. */
enum class CardAccent(val key: String, val label: Int) {
    CLASSIC("classic", R.string.accent_classic),
    FADE("fade", R.string.accent_fade),
    OUTLINE("outline", R.string.accent_outline),
    CRESCENT("crescent", R.string.accent_crescent),
    PILL("pill", R.string.accent_pill),
    GLOW("glow", R.string.accent_glow),
    WASH("wash", R.string.accent_wash);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
        /** The sync's own choice, else the one in Settings, else the classic band. */
        fun resolve(job: String?, settings: String?) = of(job) ?: of(settings) ?: CLASSIC
    }
}

/** How the last runs show under a card's figures (Settings). */
enum class RunHistoryStyle(val key: String, val label: Int) {
    BARS("bars", R.string.history_bars),
    CHART("chart", R.string.history_chart),
    DOTS("dots", R.string.history_dots),
    TEXT("text", R.string.history_text);

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BARS }
}

/** The card's colour drawn behind its content in the chosen [style]; the card clips it to its shape. */
fun Modifier.cardAccent(style: CardAccent, accent: Color?): Modifier = if (accent == null) this else drawBehind {
    val r = 28.dp.toPx() // MaterialTheme.shapes.large, the card's corners
    val rtl = layoutDirection == LayoutDirection.Rtl
    val w = 6.dp.toPx()
    fun x(v: Float) = if (rtl) size.width - v else v
    when (style) {
        CardAccent.CLASSIC -> drawRect(accent, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
        // The card's outline on the leading side, as thick in the corners, fading out towards top and bottom.
        CardAccent.FADE -> clipRect(left = if (rtl) size.width - r else 0f, right = if (rtl) size.width else r) {
            drawRoundRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.3f to accent, 0.7f to accent, 1f to Color.Transparent),
                cornerRadius = CornerRadius(r), style = Stroke(w * 2),
            )
        }
        // Half of the stroke falls outside the card and is clipped: 2 dp all round.
        CardAccent.OUTLINE -> drawRoundRect(accent, cornerRadius = CornerRadius(r), style = Stroke(4.dp.toPx()))
        // Thick in the middle of the leading edge, thinning to nothing in the corners.
        CardAccent.CRESCENT -> {
            val h = size.height
            val c = (8 * w - 2 * r) / 6 // the inner curve's control points, so that it reaches w at mid height
            val p = Path().apply {
                moveTo(x(r), 0f)
                quadraticTo(x(0f), 0f, x(0f), r)
                lineTo(x(0f), h - r)
                quadraticTo(x(0f), h, x(r), h)
                cubicTo(x(c), h * 0.75f, x(c), h * 0.25f, x(r), 0f)
                close()
            }
            drawPath(p, accent)
        }
        // A rounded bar inside the card, clear of the corners.
        CardAccent.PILL -> {
            val pw = 5.dp.toPx()
            drawRoundRect(accent, topLeft = Offset(if (rtl) size.width - 9.dp.toPx() - pw else 9.dp.toPx(), r), size = Size(pw, (size.height - 2 * r).coerceAtLeast(pw)), cornerRadius = CornerRadius(pw / 2))
        }
        CardAccent.GLOW -> drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.38f), Color.Transparent), center = Offset(x(0f), size.height / 2), radius = size.width * 0.55f))
        CardAccent.WASH -> drawRect(
            if (rtl) Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.40f)), startX = size.width * 0.55f, endX = size.width)
            else Brush.horizontalGradient(listOf(accent.copy(alpha = 0.40f), Color.Transparent), startX = 0f, endX = size.width * 0.45f),
        )
    }
}

/** The last runs, oldest first, in the chosen [style]; [ink] is the sync's colour or the theme's. */
@Composable
fun RunHistory(history: List<SyncReport>, style: RunHistoryStyle, ink: Color, label: String) {
    val err = MaterialTheme.colorScheme.error
    val failed = history.count { !it.ok }
    val changed = history.count { it.ok && it.added + it.removed > 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {}) {
        when (style) {
            RunHistoryStyle.BARS -> history.forEach { h ->
                Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (h.ok) ink.copy(alpha = if (h.added + h.removed > 0) 1f else 0.4f) else err))
            }
            // Height: how many tracks moved, against the busiest run shown; a stub when nothing changed.
            RunHistoryStyle.CHART -> Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                val most = history.maxOfOrNull { it.added + it.removed }?.coerceAtLeast(1) ?: 1
                history.forEach { h ->
                    val n = h.added + h.removed
                    val height = when { !h.ok -> 22.dp; n == 0 -> 3.dp; else -> 6.dp + 16.dp * (n.toFloat() / most) }
                    Box(Modifier.size(width = 12.dp, height = height).clip(RoundedCornerShape(3.dp))
                        .background(if (!h.ok) err else if (n == 0) ink.copy(alpha = 0.35f) else ink), contentAlignment = Alignment.Center) {
                        if (!h.ok) Text("✕", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                    }
                }
            }
            // A sign in each mark: + changed something, – nothing to do, ! failed. Readable without colour.
            RunHistoryStyle.DOTS -> history.forEach { h ->
                val n = h.added + h.removed
                val mod = Modifier.size(18.dp).clip(CircleShape)
                Box(
                    when { !h.ok -> mod.background(err); n > 0 -> mod.background(ink); else -> mod.border(1.5.dp, ink.copy(alpha = 0.6f), CircleShape) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (!h.ok) "!" else if (n > 0) "+" else "–", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = when { !h.ok -> MaterialTheme.colorScheme.onError; n > 0 -> MaterialTheme.colorScheme.surface; else -> ink })
                }
            }
            RunHistoryStyle.TEXT -> {}
        }
        if (style != RunHistoryStyle.TEXT) Spacer(Modifier.width(6.dp))
        val text = if (style == RunHistoryStyle.TEXT)
            androidx.compose.ui.res.stringResource(R.string.run_history_text, changed, history.size - changed - failed, failed)
        else if (style == RunHistoryStyle.CHART) {
            val add = history.filter { it.ok }.sumOf { it.added }; val rem = history.filter { it.ok }.sumOf { it.removed }
            listOfNotNull(if (add > 0) "+$add" else null, if (rem > 0) "−$rem" else null).joinToString(" ").let { if (it.isEmpty()) label else "$it · $label" }
        } else label
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The card menu's "Style": the sync's colour and how its card wears it, with a small preview of the
 * card as it will look. Saved together; the style choice shows only for a coloured sync.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun JobStyleDialog(name: String, color: String?, accentStyle: String?, settingsAccent: String, onDismiss: () -> Unit, onSave: (String?, String?) -> Unit) {
    var c by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(color) }
    var st by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(accentStyle) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.job_style)) },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The card in small, as the list will show it.
                androidx.compose.material3.Card(
                    Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = jobContainer(c)),
                ) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.cardAccent(CardAccent.resolve(st, settingsAccent), jobAccent(c)).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val brush = jobControlBrush(c)
                            listOf("+3", "0", "0").forEachIndexed { i, v ->
                                Box(
                                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                                        .let { if (brush != null) it.background(brush) else it.background(MaterialTheme.colorScheme.surfaceContainerHigh) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(v, fontWeight = FontWeight.Bold, color = if (i == 0) jobInk(c) ?: MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    }
                }
                Text(androidx.compose.ui.res.stringResource(R.string.job_color), style = MaterialTheme.typography.labelLarge)
                JobColorPicker(c) { c = it }
                if (c != null) {
                    Text(androidx.compose.ui.res.stringResource(R.string.job_accent_style), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(st == null, onClick = { st = null }, label = { Text(androidx.compose.ui.res.stringResource(R.string.accent_default)) })
                        CardAccent.entries.forEach { a ->
                            androidx.compose.material3.FilterChip(st == a.key, onClick = { st = a.key }, label = { Text(androidx.compose.ui.res.stringResource(a.label)) })
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(c, st); onDismiss() }) { Text(androidx.compose.ui.res.stringResource(R.string.save)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
    )
}

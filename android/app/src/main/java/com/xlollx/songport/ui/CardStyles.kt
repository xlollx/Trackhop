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
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.R
import com.xlollx.songport.model.SyncReport

/**
 * How a coloured sync wears its colour on its card: chosen in Settings, and per sync in the editor.
 * [reach] is how far in from the card's leading edge it draws (see [cardAccent]).
 */
enum class CardAccent(val key: String, val label: Int, val reach: Dp) {
    CLASSIC("classic", R.string.accent_classic, ACCENT_BAND),
    FADE("fade", R.string.accent_fade, ACCENT_BAND),
    OUTLINE("outline", R.string.accent_outline, 2.dp),
    CRESCENT("crescent", R.string.accent_crescent, 8.dp), // TileAccent.of(CRESCENT): it peaks at its width
    PILL("pill", R.string.accent_pill, PILL_INSET + PILL_WIDTH),
    WASH("wash", R.string.accent_wash, 0.dp);

    /**
     * The card's padding on the leading side: its usual [base], or more when the accent reaches
     * that far, so that the logos and the text keep [ACCENT_GAP] from it (the pill sat 2 dp from
     * the logos of a small card). Null [accent]: an uncoloured card, which draws none.
     */
    fun startPadding(base: Dp, accent: Color?): Dp = if (accent == null) base else maxOf(base, reach + ACCENT_GAP)

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
        /** The sync's own choice, else the one in Settings, else the classic band. */
        fun resolve(job: String?, settings: String?) = of(job) ?: of(settings) ?: CLASSIC
    }
}

private val ACCENT_BAND = 6.dp
private val PILL_INSET = 9.dp
private val PILL_WIDTH = 5.dp
/** The least room between a card's accent and its content. */
private val ACCENT_GAP = 10.dp

/** How the last runs show under a card's figures (Settings). */
enum class RunHistoryStyle(val key: String, val label: Int) {
    BARS("bars", R.string.history_bars),
    CHART("chart", R.string.history_chart),
    DOTS("dots", R.string.history_dots),
    TEXT("text", R.string.history_text);

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BARS }
}

/**
 * How big the sync cards are (Settings): [LARGE] is the card as it always was; [MEDIUM] the same
 * card with tighter spacing and shorter figures; [SMALL] its figures in one row of small marks and
 * its status, schedule and buttons on one line. Declared largest first: a later entry is smaller.
 */
enum class CardSize(val key: String, val label: Int) {
    LARGE("large", R.string.card_size_large),
    MEDIUM("medium", R.string.card_size_medium),
    SMALL("small", R.string.card_size_small);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: LARGE

        /**
         * The size the list shows: the one chosen, but no larger than the window allows
         * ([WindowLayout.largestCard]: a phone on its side), so that two cards fit one above the other.
         * A smaller choice is always kept.
         */
        fun shown(chosen: String?, layout: WindowLayout): CardSize = maxOf(of(chosen), layout.largestCard)
    }
}

/**
 * A style along the leading edge as a tile of the minimal view draws it: the card's own shapes are
 * made for a tall card (on a tile the crescent swallowed the text and the fade showed as arcs in the
 * corners), so each is drawn here thin and short enough for a tile and stays itself. [width] is the
 * widest it gets and [inset] its distance from the edge: together they stay within [TILE_CLEAR] of
 * the edge, half of the tile's 16 dp text padding. [length] is the share of the tile's height it
 * runs along; [tapers]: it thins to nothing towards the corners; [fades]: it fades out towards them.
 * Null for the outline and the wash, which a tile wears as the card does.
 */
data class TileAccent(val width: Dp, val inset: Dp = 0.dp, val length: Float = 1f, val tapers: Boolean = false, val fades: Boolean = false) {
    companion object {
        val TILE_CLEAR = 8.dp
        fun of(style: CardAccent): TileAccent? = when (style) {
            CardAccent.CLASSIC -> TileAccent(6.dp)
            CardAccent.FADE -> TileAccent(5.dp, fades = true)
            CardAccent.CRESCENT -> TileAccent(8.dp, tapers = true)
            CardAccent.PILL -> TileAccent(4.dp, inset = 4.dp, length = 0.4f)
            CardAccent.OUTLINE, CardAccent.WASH -> null
        }
    }
}

/**
 * The card's colour drawn behind its content in the chosen [style]; the card clips it to its shape.
 * On a [tile] of the minimal view the styles along the edge take their tile's shape ([TileAccent]).
 */
fun Modifier.cardAccent(style: CardAccent, accent: Color?, tile: Boolean = false): Modifier = if (accent == null) this else drawBehind {
    // The crescent is the tile's on the full card too: the user liked it better than the wide one.
    val t = if (tile || style == CardAccent.CRESCENT) TileAccent.of(style) else null
    if (t != null) return@drawBehind drawTileAccent(t, accent)
    val r = 28.dp.toPx() // MaterialTheme.shapes.large, the card's corners
    val rtl = layoutDirection == LayoutDirection.Rtl
    val w = ACCENT_BAND.toPx()
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
        CardAccent.CRESCENT -> Unit // drawn as on a tile, above
        // A rounded bar inside the card, clear of the corners.
        CardAccent.PILL -> {
            val pw = PILL_WIDTH.toPx()
            val inset = PILL_INSET.toPx()
            drawRoundRect(accent, topLeft = Offset(if (rtl) size.width - inset - pw else inset, r), size = Size(pw, (size.height - 2 * r).coerceAtLeast(pw)), cornerRadius = CornerRadius(pw / 2))
        }
        CardAccent.WASH -> drawRect(
            if (rtl) Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.40f)), startX = size.width * 0.55f, endX = size.width)
            else Brush.horizontalGradient(listOf(accent.copy(alpha = 0.40f), Color.Transparent), startX = 0f, endX = size.width * 0.45f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTileAccent(t: TileAccent, accent: Color) {
    val rtl = layoutDirection == LayoutDirection.Rtl
    val w = t.width.toPx()
    val inset = t.inset.toPx()
    val h = size.height
    fun x(v: Float) = if (rtl) size.width - v else v
    when {
        // Widest at mid height, nothing at the ends: the card's rounded corners cut it to nothing before them.
        t.tapers -> drawPath(Path().apply {
            moveTo(x(0f), 0f)
            quadraticTo(x(2 * w), h / 2, x(0f), h) // a quadratic peaks at half its control point's distance
            close()
        }, accent)
        else -> {
            val len = h * t.length
            val top = (h - len) / 2
            val left = if (rtl) size.width - inset - w else inset
            val brush = if (t.fades) Brush.verticalGradient(0f to Color.Transparent, 0.3f to accent, 0.7f to accent, 1f to Color.Transparent, startY = top, endY = top + len) else null
            val corner = if (t.inset > 0.dp) CornerRadius(w / 2) else CornerRadius.Zero
            if (brush != null) drawRoundRect(brush, Offset(left, top), Size(w, len), corner)
            else drawRoundRect(accent, Offset(left, top), Size(w, len), corner)
        }
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
 * card as it will look, and the colour's name (its label). Saved together; the style choice and the
 * name show only for a coloured sync. [onSave] gets what the picker holds (see [JobColorPicker]),
 * the style, the name for that colour and, for a saved label given another colour, the new colour.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun JobStyleDialog(
    name: String, pick: String?, accentStyle: String?, settingsAccent: String, labels: List<com.xlollx.songport.model.Label>,
    onDismiss: () -> Unit,
    routeGlow: Boolean? = null, settingsGlow: Boolean = true, sourceBrand: Long? = null, targetBrand: Long? = null,
    onSave: (String?, String?, String, String?, Boolean?) -> Unit,
) {
    var rg by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(routeGlow) }
    var p by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(pick) }
    var st by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(accentStyle) }
    fun nameOf(x: String?) = labels.firstOrNull { it.id == x }?.name.orEmpty()
    var labelName by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(nameOf(pick)) }
    // A saved label given another colour here: every sync wearing it follows on Save.
    var recolor by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var recolorOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val saved = labels.firstOrNull { it.id == p && JobColor.of(it.id) == null }
    val c = (if (saved != null) recolor else null) ?: pickedColor(p, labels)
    // The card in small, as the list will show it.
    val preview: @Composable () -> Unit = {
        androidx.compose.material3.Card(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = jobContainer(c)),
        ) {
            androidx.compose.foundation.layout.Column(
                CardAccent.resolve(st, settingsAccent).let { a ->
                    Modifier.then(if (rg ?: (c == null && settingsGlow)) Modifier.routeGlow(sourceBrand, targetBrand, LocalAppDark.current) else Modifier)
                        .cardAccent(a, jobAccent(c)).padding(start = a.startPadding(20.dp, jobAccent(c)), end = 20.dp, top = 16.dp, bottom = 16.dp)
                },
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
    }
    val choices: @Composable () -> Unit = {
        Text(androidx.compose.ui.res.stringResource(R.string.job_color), style = MaterialTheme.typography.labelLarge)
        JobColorPicker(p, labels) { p = it; labelName = nameOf(it); recolor = null }
        if (p != null) {
            androidx.compose.material3.OutlinedTextField(
                value = labelName, onValueChange = { labelName = it.take(com.xlollx.songport.sync.Labels.MAX_NAME) },
                label = { Text(androidx.compose.ui.res.stringResource(R.string.label_name)) },
                supportingText = { Text(androidx.compose.ui.res.stringResource(R.string.label_name_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag(LABEL_NAME_TAG),
            )
            if (saved != null) androidx.compose.material3.TextButton(onClick = { recolorOpen = true }) {
                Text(androidx.compose.ui.res.stringResource(R.string.label_change_color))
            }
            Text(androidx.compose.ui.res.stringResource(R.string.job_accent_style), style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(st == null, onClick = { st = null }, label = { Text(androidx.compose.ui.res.stringResource(R.string.accent_default)) })
                CardAccent.entries.forEach { a ->
                    androidx.compose.material3.FilterChip(st == a.key, onClick = { st = a.key }, label = { Text(androidx.compose.ui.res.stringResource(a.label)) })
                }
            }
        }
        // The route's glow, for this card: as in Settings, or on or off whatever Settings say.
        Text(androidx.compose.ui.res.stringResource(R.string.job_route_glow), style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(rg == null, onClick = { rg = null }, label = { Text(androidx.compose.ui.res.stringResource(R.string.accent_default)) })
            androidx.compose.material3.FilterChip(rg == true, onClick = { rg = true }, label = { Text(androidx.compose.ui.res.stringResource(R.string.route_glow_on)) })
            androidx.compose.material3.FilterChip(rg == false, onClick = { rg = false }, label = { Text(androidx.compose.ui.res.stringResource(R.string.route_glow_off)) })
        }
    }
    val title: @Composable () -> Unit = { Text(androidx.compose.ui.res.stringResource(R.string.job_style)) }
    val save = { onSave(p, st, labelName, recolor.takeIf { saved != null }, rg); onDismiss() }
    val buttons: @Composable () -> Unit = {
        androidx.compose.material3.TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) }
        androidx.compose.material3.TextButton(onClick = save) { Text(androidx.compose.ui.res.stringResource(R.string.save)) }
    }
    // A phone on its side: the dialog as wide and as tall as the window allows, the title and the
    // preview on the left, the choices on the right scrolling on their own between the title and
    // the buttons, which stay in view. Material's dialog kept its text to a short box there and cut
    // the label's name in half. Upright, Material's dialog with one column as ever.
    if (LocalWindowLayout.current.short) androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = WideDialog.properties) {
        androidx.compose.material3.Surface(
            WideDialog.modifier.padding(vertical = 12.dp), shape = androidx.compose.material3.AlertDialogDefaults.shape,
            color = androidx.compose.material3.AlertDialogDefaults.containerColor,
            tonalElevation = androidx.compose.material3.AlertDialogDefaults.TonalElevation,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp)) {
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.headlineSmall) { title() }
                        preview()
                    }
                    androidx.compose.foundation.layout.Column(
                        Modifier.weight(0.6f).verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag(STYLE_CHOICES_TAG),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { choices() }
                }
                Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    } else androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                preview()
                choices()
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = save) { Text(androidx.compose.ui.res.stringResource(R.string.save)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
    )
    if (recolorOpen) CustomColorDialog(CustomColor.parse(c) ?: JobColor.BLUE.light, onDismiss = { recolorOpen = false }) { recolorOpen = false; recolor = CustomColor.key(it) }
}

const val LABEL_NAME_TAG = "label-name"
const val STYLE_CHOICES_TAG = "style-choices"

/**
 * A sync list in miniature for Settings › Card size: a phone's screen with as many cards of [cardSize]
 * as fit on it, each with its logo, its name and its figures (boxes, or one line when Small). Decorative.
 */
@Composable
fun CardSizePreview(cardSize: CardSize) {
    val card = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val accent = MaterialTheme.colorScheme.primary
    val frame = MaterialTheme.colorScheme.outline
    androidx.compose.foundation.Canvas(Modifier.size(width = 34.dp, height = 50.dp)) {
        val px = 1.dp.toPx()
        drawRoundRect(frame, cornerRadius = CornerRadius(5 * px), style = Stroke(1.5f * px))
        val pad = 3.5f * px
        val w = size.width - 2 * pad
        val h = when (cardSize) { CardSize.LARGE -> 27; CardSize.MEDIUM -> 19; CardSize.SMALL -> 13 } * px
        clipRect(pad, pad, size.width - pad, size.height - pad) {
            var y = pad
            while (y < size.height - pad) {
                drawRoundRect(card, Offset(pad, y), Size(w, h), CornerRadius(3 * px))
                drawCircle(accent, radius = 2 * px, center = Offset(pad + 4 * px, y + 4 * px))
                drawRoundRect(ink, Offset(pad + 8 * px, y + 3 * px), Size(w * 0.55f, 2 * px), CornerRadius(px))
                if (cardSize == CardSize.SMALL) {
                    drawRoundRect(ink, Offset(pad + 3 * px, y + 8 * px), Size(w * 0.7f, 2 * px), CornerRadius(px))
                } else {
                    val top = y + 9 * px
                    val bh = if (cardSize == CardSize.LARGE) 8 * px else 5 * px
                    val bw = (w - 6 * px - 4 * px) / 3
                    repeat(3) { i -> drawRoundRect(ink, Offset(pad + 3 * px + i * (bw + 2 * px), top), Size(bw, bh), CornerRadius(1.5f * px)) }
                    if (cardSize == CardSize.LARGE) drawRoundRect(ink, Offset(pad + 3 * px, top + bh + 3 * px), Size(w * 0.4f, 2 * px), CornerRadius(px))
                }
                y += h + 2.5f * px
            }
        }
    }
}

/**
 * The default look of a tile with no colour of its own: a soft glow of the source's brand colour in
 * the corner of the logos and one of the target's in the opposite corner, the middle left plain, so
 * the route reads at a glance without the two colours mixing into a muddy one. Nothing when a
 * service has no brand colour, and none in the store screenshots (no other brand's colour there).
 */
fun Modifier.routeGlow(source: Long?, target: Long?, dark: Boolean): Modifier =
    if (LogoMode.neutral || (source == null && target == null)) this else drawBehind {
        val alpha = if (dark) 0.26f else 0.16f
        val rtl = layoutDirection == LayoutDirection.Rtl
        val radius = size.maxDimension * 0.75f
        fun glow(argb: Long?, x: Float, y: Float) {
            val c = Color(argb ?: return)
            drawRect(Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), center = Offset(x, y), radius = radius))
        }
        val start = if (rtl) size.width else 0f
        val end = if (rtl) 0f else size.width
        glow(source, start, 0f)
        glow(target, end, size.height)
    }

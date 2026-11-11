package com.xlollx.songport.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.brand
import com.xlollx.songport.providers.label
import com.xlollx.songport.sync.LibraryOverlap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Currency

private fun pricePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("discover_prices", Context.MODE_PRIVATE)

/** The comparison under way and the last one, for as long as the app runs: the screen turned, it goes on. */
private object SubscriptionsKept {
    var busy by mutableStateOf(false)
    /** Per service being read: (playlists read, playlists to read). */
    var reading by mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap())
    /** The service being read now; null once both are read and the songs are matched. */
    var current by mutableStateOf<String?>(null)
    var pair by mutableStateOf<Pair<MusicProvider, MusicProvider>?>(null)
    var result by mutableStateOf<LibraryOverlap.Result?>(null)
    var error by mutableStateOf<String?>(null)
    var bitmaps by mutableStateOf<Map<Cards.Format, Bitmap>?>(null)
}

/** The phone's currency (null where the language names no country) and how an amount is written with it. */
private class Money(ctx: Context) {
    private val locale = discoverLocale(ctx)
    private val currency: Currency? = runCatching { Currency.getInstance(locale) }.getOrNull()
    val symbol: String? = currency?.getSymbol(locale)
    private val format: NumberFormat =
        if (currency != null) NumberFormat.getCurrencyInstance(locale)
        else NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    fun of(amount: Double): String = format.format(amount)
}

/**
 * "Two subscriptions?": how much of the music on two services overlaps ([LibraryOverlap]) and what
 * leaving one would save, at the prices the user types (kept on the phone, never ours). Three
 * steps: the two services, what they cost, the result: the two libraries as two circles, the three
 * counts, a sentence on what they are to each other and what keeping only one would mean.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionsPage(snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val services = remember { LibraryReader.services(ctx) }
    ToolIntro(stringResource(R.string.discover_subs_intro))
    if (services.size < 2) { NoServices(stringResource(R.string.discover_subs_need_two), onGoToAccounts); return }
    val kept = SubscriptionsKept
    // The two chosen, in the order they were picked: the last comparison's, else the first two connected.
    var chosen by rememberSaveable {
        val last = kept.pair?.let { listOf(it.first.id, it.second.id) }?.takeIf { ids -> ids.all { id -> services.any { it.id == id } } }
        mutableStateOf(last ?: services.take(2).map { it.id })
    }
    val picked = chosen.mapNotNull { id -> services.firstOrNull { it.id == id } }
    val a = picked.getOrNull(0); val b = picked.getOrNull(1)
    val prefs = remember { pricePrefs(ctx) }
    // What is typed, per service: the price and whether it is a year's.
    var prices by remember { mutableStateOf(services.associate { it.serviceId to prefs.getString(it.serviceId, "").orEmpty() }) }
    var yearly by remember { mutableStateOf(services.associate { it.serviceId to prefs.getBoolean(it.serviceId + ".yearly", false) }) }
    fun monthlyOf(p: MusicProvider): Double? =
        LibraryOverlap.parsePrice(prices[p.serviceId].orEmpty())?.let { LibraryOverlap.monthly(it, yearly[p.serviceId] == true) }
    val money = remember { Money(ctx) }

    fun run() {
        val pa = a ?: return; val pb = b ?: return
        kept.busy = true; kept.result = null; kept.error = null; kept.bitmaps = null
        kept.pair = pa to pb; kept.reading = emptyMap(); kept.current = pa.id
        AppWork.scope.launch {
            try {
                val ta = LibraryReader.read(ctx, pa) { d, t -> kept.reading = kept.reading + (pa.id to (d to t)) }
                // Read a moment ago and kept: no progress came, it is read all the same.
                kept.reading = kept.reading + (pa.id to (kept.reading[pa.id] ?: (1 to 1)))
                kept.current = pb.id
                val tb = LibraryReader.read(ctx, pb) { d, t -> kept.reading = kept.reading + (pb.id to (d to t)) }
                kept.current = null
                val store = Store.get(ctx)
                val r = withContext(Dispatchers.Default) { LibraryOverlap.compute(ta, tb) { t -> store.cachedMatch(pa.id, t.id, pb.id) } }
                kept.result = r
                if (LibraryOverlap.verdict(r) != null) {
                    val card = overlapCard(ctx, pa, pb, r)
                    kept.bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.overlap(ctx, card, it) } }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                kept.error = discoverErrorText(ctx, e)
            }
            kept.busy = false
        }
    }

    fun toggle(id: String) {
        if (kept.busy) return
        chosen = when {
            id in chosen -> chosen - id
            chosen.size < 2 -> chosen + id
            // A third one takes the place of the one picked first.
            else -> chosen.drop(1) + id
        }
        kept.result = null; kept.error = null; kept.bitmaps = null
    }

    val shownPair = kept.pair
    val result = kept.result
    val hasOutcome = kept.busy || kept.error != null || (result != null && shownPair != null)

    val setup: @Composable () -> Unit = {
        ToolSection(stringResource(R.string.discover_subs_step_services), step = 1, hint = if (services.size > 2 || picked.size < 2) stringResource(R.string.discover_subs_pick_two) else null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                services.forEach { p -> ServiceChoice(p, p.id in chosen, enabled = !kept.busy) { toggle(p.id) } }
            }
        }
        ToolSection(stringResource(R.string.discover_subs_step_prices), step = 2, hint = stringResource(R.string.discover_subs_cost_note)) {
            // One service through two routes (Spotify and Spotify (web)) is one subscription: one price.
            picked.distinctBy { it.serviceId }.forEach { p ->
                PriceField(
                    p, money.symbol, prices[p.serviceId].orEmpty(), yearly[p.serviceId] == true,
                    onPrice = { v -> prices = prices + (p.serviceId to v); prefs.edit().putString(p.serviceId, v).apply() },
                    onYearly = { y -> yearly = yearly + (p.serviceId to y); prefs.edit().putBoolean(p.serviceId + ".yearly", y).apply() },
                )
            }
        }
        ToolPrimaryButton(
            stringResource(R.string.discover_subs_run), onClick = { run() }, icon = Icons.Filled.CompareArrows,
            enabled = a != null && b != null, busy = kept.busy,
        )
    }
    val outcome: @Composable () -> Unit = {
        val err = kept.error
        when {
            kept.busy && shownPair != null -> ReadingCard(shownPair, kept.reading, kept.current)
            err != null -> ToolError(err, onRetry = if (a != null && b != null) ({ run() }) else null)
            result != null && shownPair != null -> OverlapResult(shownPair.first, shownPair.second, result, monthlyOf(shownPair.first), monthlyOf(shownPair.second), money, kept.bitmaps)
        }
    }
    if (hasOutcome) ToolSplit(start = { setup() }, end = { outcome() }) else setup()
}

/** A connected service to tick: its logo and name, a line around it and a tick when it is one of the two. */
@Composable
private fun ServiceChoice(p: MusicProvider, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val id = LocalToolIdentity.current
    Surface(
        checked = on, onCheckedChange = { onToggle() }, enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (on) id.container() else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) id.tint() else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceLogo(p, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(p.label(ctx), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Icon(
                if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (on) id.tint() else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What a service costs: the amount with the phone's currency in front, and whether it is a month's or a year's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriceField(p: MusicProvider, symbol: String?, price: String, yearly: Boolean, onPrice: (String) -> Unit, onYearly: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = price, onValueChange = { v -> if (v.length <= 12) onPrice(v) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(p.brand) },
            leadingIcon = { ServiceLogo(p, size = 24.dp) },
            prefix = symbol?.let { s -> { Text("$s ") } },
            isError = price.isNotBlank() && LibraryOverlap.parsePrice(price) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip(stringResource(R.string.discover_subs_per_month), !yearly) { onYearly(false) }
            PeriodChip(stringResource(R.string.discover_subs_per_year), yearly) { onYearly(true) }
        }
    }
}

@Composable
private fun PeriodChip(text: String, on: Boolean, onClick: () -> Unit) = FilterChip(
    selected = on, onClick = onClick, label = { Text(text) },
    leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
)

/** The two libraries being read: a row each with its bar, a tick once read, then the matching. */
@Composable
private fun ReadingCard(pair: Pair<MusicProvider, MusicProvider>, reading: Map<String, Pair<Int, Int>>, current: String?) {
    val ctx = LocalContext.current
    val id = LocalToolIdentity.current
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            pair.toList().distinctBy { it.id }.forEach { p ->
                val r = reading[p.id]
                val isCurrent = current == p.id
                val done = !isCurrent && (r != null || current == null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceLogo(p, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when {
                                isCurrent && r != null && r.second > 0 -> stringResource(R.string.discover_reading, p.label(ctx), r.first, r.second)
                                done || isCurrent -> p.label(ctx)
                                else -> p.label(ctx) + " · " + stringResource(R.string.discover_wrapped_waiting)
                            },
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrent) {
                            if (r != null && r.second > 0) LinearProgressIndicator(progress = { r.first.toFloat() / r.second }, color = id.tint(), trackColor = id.container(), modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                            else LinearProgressIndicator(color = id.tint(), trackColor = id.container(), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (done) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.CheckCircle, null, tint = id.tint()) }
                }
            }
            if (current == null) {
                Text(stringResource(R.string.discover_subs_matching), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(color = id.tint(), trackColor = id.container(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun overlapStats(ctx: Context, nameA: String, nameB: String, r: LibraryOverlap.Result): List<Pair<String, String>> {
    val nf = NumberFormat.getIntegerInstance(discoverLocale(ctx))
    return listOf(
        nf.format(r.onlyA.size) to ctx.getString(R.string.discover_subs_only, nameA),
        nf.format(r.common.size) to ctx.getString(R.string.discover_subs_legend_both),
        nf.format(r.onlyB.size) to ctx.getString(R.string.discover_subs_only, nameB),
    )
}

/** The card to share: the services by their plain names, never the connectors' labels, and no prices. */
private fun overlapCard(ctx: Context, a: MusicProvider, b: MusicProvider, r: LibraryOverlap.Result) = Cards.OverlapCard(
    eyebrow = ctx.getString(R.string.discover_subs_title),
    nameA = a.brand, nameB = b.brand, percent = r.percent,
    caption = ctx.getString(R.string.discover_subs_overlap),
    venn = LibraryOverlap.venn(r.sizeA, r.sizeB, r.common.size),
    stats = overlapStats(ctx, a.brand, b.brand, r),
)

/** The result: the circles and the counts, the sentence, what keeping only one means, the songs on one side only, the card. */
@Composable
private fun OverlapResult(a: MusicProvider, b: MusicProvider, r: LibraryOverlap.Result, priceA: Double?, priceB: Double?, money: Money, bitmaps: Map<Cards.Format, Bitmap>?) {
    val ctx = LocalContext.current
    val id = LocalToolIdentity.current
    val nameA = a.label(ctx); val nameB = b.label(ctx)
    val verdict = LibraryOverlap.verdict(r)
    if (verdict == null) {
        ToolEmpty(stringResource(R.string.discover_subs_empty, if (r.sizeA == 0) nameA else nameB))
        return
    }
    val nf = remember { NumberFormat.getIntegerInstance(discoverLocale(ctx)) }
    val colorA = id.tint()
    val colorB = MaterialTheme.colorScheme.tertiary
    val summary = "${r.percent}% " + stringResource(R.string.discover_subs_overlap) + ". " +
        stringResource(R.string.discover_subs_counts, nf.format(r.common.size), nf.format(r.onlyA.size), nameA, nf.format(r.onlyB.size), nameB)
    ToolSectionTitle(stringResource(R.string.discover_result), step = 3)
    ToolResultCard {
        val venn = remember(r) { LibraryOverlap.venn(r.sizeA, r.sizeB, r.common.size) }
        VennDrawing(venn, colorA, colorB, Modifier.fillMaxWidth().height(150.dp).clearAndSetSemantics { contentDescription = summary })
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${r.percent}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = colorA)
            Text(stringResource(R.string.discover_subs_overlap), style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        val (shareA, shareBoth, shareB) = r.shares
        LegendRow(Swatch.A, colorA, colorB, stringResource(R.string.discover_subs_only, nameA), nf.format(r.onlyA.size), shareA) { ServiceLogo(a, size = 20.dp) }
        LegendRow(Swatch.BOTH, colorA, colorB, stringResource(R.string.discover_subs_legend_both), nf.format(r.common.size), shareBoth, null)
        LegendRow(Swatch.B, colorA, colorB, stringResource(R.string.discover_subs_only, nameB), nf.format(r.onlyB.size), shareB) { ServiceLogo(b, size = 20.dp) }
    }
    Surface(Modifier.fillMaxWidth(), shape = ToolCardShape, color = id.container()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lightbulb, null, tint = id.tint()); Spacer(Modifier.width(12.dp))
            Text(
                when (verdict) {
                    LibraryOverlap.Verdict.SAME -> stringResource(R.string.discover_subs_verdict_same)
                    LibraryOverlap.Verdict.A_IN_B -> stringResource(R.string.discover_subs_verdict_covered, nameA, nameB)
                    LibraryOverlap.Verdict.B_IN_A -> stringResource(R.string.discover_subs_verdict_covered, nameB, nameA)
                    LibraryOverlap.Verdict.PARTIAL -> stringResource(R.string.discover_subs_verdict_partial)
                    LibraryOverlap.Verdict.APART -> stringResource(R.string.discover_subs_verdict_apart)
                },
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            )
        }
    }
    // The same service through two routes is one subscription: nothing to save by "leaving one".
    if (a.serviceId != b.serviceId) ToolSection(stringResource(R.string.discover_subs_keep_title)) {
        KeepOnly(a, nameA, nf.format(r.onlyB.size), priceB, money)
        HorizontalDivider()
        KeepOnly(b, nameB, nf.format(r.onlyA.size), priceA, money)
        if (priceA != null && priceB != null) {
            HorizontalDivider()
            Text(
                stringResource(R.string.discover_subs_together, money.of(priceA + priceB), money.of((priceA + priceB) * 12)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    OnlyOn(stringResource(R.string.discover_subs_only, nameA), r.onlyA, nf)
    OnlyOn(stringResource(R.string.discover_subs_only, nameB), r.onlyB, nf)
    ToolShare(bitmaps, "trackhop-two-subscriptions", summary)
}

/** Keeping only [keep]: the songs that would stay behind on the other, and what the other costs. */
@Composable
private fun KeepOnly(keep: MusicProvider, name: String, left: String, saved: Double?, money: Money) {
    Row(verticalAlignment = Alignment.Top) {
        ServiceLogo(keep, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.discover_subs_keep_only, name), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.discover_subs_left_behind, left), style = MaterialTheme.typography.bodyMedium)
            if (saved != null && saved > 0) Text(
                stringResource(R.string.discover_subs_save, money.of(saved), money.of(saved * 12)),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = LocalToolIdentity.current.tint(),
            )
        }
    }
}

private enum class Swatch { A, BOTH, B }

/** One of the three counts: its mark (as the circles are drawn), what it counts, the figure and its share. */
@Composable
private fun LegendRow(swatch: Swatch, colorA: Color, colorB: Color, label: String, count: String, share: Int, logo: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2 - 1.5.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))
            if (swatch != Swatch.B) drawCircle(colorA.copy(alpha = 0.45f), r)
            if (swatch != Swatch.A) drawCircle(colorB.copy(alpha = 0.45f), r)
            if (swatch != Swatch.B) drawCircle(colorA, r, style = Stroke(1.5.dp.toPx()))
            if (swatch != Swatch.A) drawCircle(colorB, r, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
        }
        Spacer(Modifier.width(10.dp))
        if (logo != null) { logo(); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(count, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("$share%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Two libraries as two circles ([LibraryOverlap.venn]): the first with a whole line around it, the
 * second with a dashed one, so they are told apart without their colours.
 */
@Composable
internal fun VennDrawing(venn: LibraryOverlap.Venn, colorA: Color, colorB: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val k = minOf((size.width - 2 * stroke) / venn.span, (size.height - 2 * stroke) / (2 * maxOf(venn.rA, venn.rB))).toFloat()
        val ca = Offset(center.x + (venn.centreA * k).toFloat(), center.y)
        val cb = Offset(center.x + (venn.centreB * k).toFloat(), center.y)
        val ra = (venn.rA * k).toFloat(); val rb = (venn.rB * k).toFloat()
        drawCircle(colorA.copy(alpha = 0.4f), ra, ca)
        drawCircle(colorB.copy(alpha = 0.4f), rb, cb)
        drawCircle(colorA, ra, ca, style = Stroke(stroke))
        drawCircle(colorB, rb, cb, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }
}

/** The songs on one side only: the first few, the rest on request. */
@Composable
private fun OnlyOn(title: String, tracks: List<Track>, nf: NumberFormat) {
    if (tracks.isEmpty()) return
    ToolSection(title, trailing = { Text(nf.format(tracks.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
        ToolTrackList(tracks)
    }
}

/** Which circle is whose, under a [VennDrawing]: the whole line and the dashed one, each with its name. */
@Composable
internal fun VennKey(nameA: String, nameB: String, colorA: Color, colorB: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Triple(nameA, colorA, false), Triple(nameB, colorB, true)).forEach { (name, color, dashed) ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (dashed) Arrangement.End else Arrangement.Start) {
                Canvas(Modifier.size(16.dp)) {
                    val r = size.minDimension / 2 - 1.5.dp.toPx()
                    drawCircle(color.copy(alpha = 0.4f), r)
                    drawCircle(color, r, style = Stroke(1.5.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx())) else null))
                }
                Spacer(Modifier.width(6.dp))
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

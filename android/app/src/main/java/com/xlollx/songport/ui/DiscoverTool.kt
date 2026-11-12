package com.xlollx.songport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.sync.withPermit
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /** The tool's second colour (the far end of its gradient), for the other half of a pair. */
    @Composable
    fun second(): Color = (gradient.lastOrNull() ?: accent).let { if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) lerp(it, Color.White, 0.45f) else it }

    /** A quiet fill in the tool's colour (a badge, the result card). */
    @Composable
    fun container(): Color = accent.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.28f else 0.12f)
}

/** The identity of the tool whose page is on screen ([DiscoverToolScaffold] sets it). */
internal val LocalToolIdentity = staticCompositionLocalOf { ToolIdentity(Icons.Filled.Refresh, 0xFF6B4BD8) }

/** The corners of a tool's cards, and of the card that carries its result. */
internal val ToolCardShape = RoundedCornerShape(20.dp)
internal val ToolHeroShape = RoundedCornerShape(28.dp)

/** The scroll of the tool's page ([DiscoverScreen] owns it), so a result that has just come can be brought to its top. */
internal val LocalToolScroll = staticCompositionLocalOf<androidx.compose.foundation.ScrollState?> { null }

/** A result goes first on its page: when [result] comes (or changes), the page goes back to its top to show it. */
@Composable
internal fun ToolResultOnTop(result: Any?) {
    val scroll = LocalToolScroll.current ?: return
    androidx.compose.runtime.LaunchedEffect(result) { if (result != null && scroll.value > 0) scroll.animateScrollTo(0) }
}

/**
 * A part of a page that changes place (what the tool asks goes under its answer once there is one)
 * and must keep what it holds while it does: the playlist chosen, a field half typed. Call the
 * slot with the part's content wherever it goes.
 */
@Composable
internal fun rememberToolSlot(): @Composable (@Composable () -> Unit) -> Unit =
    androidx.compose.runtime.remember { androidx.compose.runtime.movableContentOf { content: @Composable () -> Unit -> content() } }

/** The longer word on how the tool works, said by the page ([ToolIntro]) and shown from the header's "how it works". */
private val LocalToolIntro = staticCompositionLocalOf<androidx.compose.runtime.MutableState<String?>> { androidx.compose.runtime.mutableStateOf(null) }

/**
 * The page of a Discover tool: one slim line of what it is for (the tool's icon in its colours,
 * and "how it works" for the longer word) and, under it, the tool's own content 12 dp apart, in a
 * column no wider than [maxWidth] and centred in a wide window. The page is the content: the
 * tool's colours live in its result, not in a banner above it.
 */
@Composable
internal fun DiscoverToolScaffold(
    identity: ToolIdentity,
    purpose: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 840.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val intro = androidx.compose.runtime.remember(identity.icon) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalToolIdentity provides identity, LocalToolIntro provides intro) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolHeader(identity, purpose, intro.value)
                content()
            }
        }
    }
}

@Composable
private fun ToolHeader(identity: ToolIdentity, purpose: String, intro: String?) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(identity.brush).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Icon(identity.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(purpose, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (intro != null) androidx.compose.material3.IconButton(onClick = { open = !open }) {
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Outlined.Info, stringResource(R.string.shared_how),
                    tint = if (open) identity.tint() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(open && intro != null) {
            Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(16.dp), color = identity.container()) {
                Text(intro.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

/** The longer word on how the tool works: kept behind the header's "how it works", so the page opens on what matters. */
@Composable
internal fun ToolIntro(text: String) {
    val holder = LocalToolIntro.current
    androidx.compose.runtime.SideEffect { if (holder.value != text) holder.value = text }
}

/** The title of a section: its name, and what is at its end. */
@Composable
internal fun ToolSectionTitle(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** One section of a tool on a card: something to fill in, or a part of the result. */
@Composable
internal fun ToolSection(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolSectionTitle(title, trailing = trailing)
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
        modifier.fillMaxWidth(), shape = ToolHeroShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, id.tint().copy(alpha = 0.55f)),
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** A word of explanation under a result: a quiet row with an "i", not a paragraph left on its own. */
@Composable
internal fun ToolNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
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

/**
 * A choice of a few, side by side on one pill: the chosen one filled with the tool's colour, or
 * ([quiet]) only tinted with it, where the choice stands next to a button that is already filled.
 */
@Composable
internal fun ToolSegmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: Boolean = true, quiet: Boolean = false, onSelect: (Int) -> Unit) {
    val id = LocalToolIdentity.current
    val fill = if (quiet) id.container() else id.accent
    val onFill = if (quiet) id.tint() else id.onAccent
    Row(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                    .selectable(selected = on, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onSelect(i) })
                    .padding(4.dp).clip(CircleShape).background(if (on) fill else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center,
                    color = if (on) onFill else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The card to share, drawn already: its preview (never taller than half a phone, so the buttons
 * stay in reach), the story/square switch, Share and Save. [description] is what TalkBack reads
 * for the preview; [matte] puts the preview on a sheet of that colour, as a print: the same margin
 * all round, a soft shadow under the sheet. Only Share is filled with the tool's colour; the
 * format switch and Save are tinted with it.
 */
@Composable
internal fun ToolShare(bitmaps: Map<Cards.Format, android.graphics.Bitmap>?, name: String, description: String?, busy: Boolean = bitmaps == null, formats: List<Cards.Format> = Cards.Format.entries, matte: Color? = null, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val id = LocalToolIdentity.current
    var format by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(formats.first()) }
    val bmp = if (busy) null else bitmaps?.get(format)
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && bmp != null) Cards.save(ctx, bmp, uri)
    }
    Surface(Modifier.fillMaxWidth(), shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // While the card is drawn its place is kept (a blank of its shape, the buttons greyed):
            // appearing late, it pushed everything below it down under a finger already on its way.
            val ratio = format.width.toFloat() / format.height
            val shape = RoundedCornerShape(if (matte != null) 2.dp else 18.dp)
            val picture = Modifier.heightIn(max = if (matte != null) 372.dp else 400.dp).aspectRatio(ratio).clip(shape)
            // A tap shows the card at full screen, to see it as it will look; a tap, ✕ or Back closes it.
            var full by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            if (full && bmp != null) FullScreenPicture(bmp, description) { full = false }
            val framed: @Composable () -> Unit = {
                if (bmp != null) androidx.compose.foundation.Image(
                    bmp.asImageBitmap(), contentDescription = description,
                    modifier = picture.clickable(onClickLabel = stringResource(R.string.card_view_full)) { full = true },
                )
                else Box(picture.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = id.tint()) }
            }
            if (matte != null) Surface(shape = RoundedCornerShape(4.dp), color = matte, shadowElevation = 8.dp) {
                // A hairline round the print, so a poster on paper does not melt into the sheet.
                Box(Modifier.padding(14.dp).border(0.5.dp, Color.Black.copy(alpha = 0.14f), shape)) { framed() }
            } else framed()
            if (formats.size > 1) ToolSegmented(
                listOf(stringResource(R.string.card_format_story), stringResource(R.string.card_format_square)),
                selected = if (format == Cards.Format.STORY) 0 else 1, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(), quiet = true,
            ) { format = if (it == 0) Cards.Format.STORY else Cards.Format.SQUARE }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = bmp != null, onClick = { bmp?.let { Cards.share(ctx, it, name) } }, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = id.accent, contentColor = id.onAccent),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.card_share), textAlign = TextAlign.Center)
                }
                androidx.compose.material3.FilledTonalButton(
                    enabled = bmp != null, onClick = { save.launch("$name.png") }, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = id.container(), contentColor = id.tint()),
                ) {
                    Icon(Icons.Filled.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.card_save), textAlign = TextAlign.Center)
                }
            }
            if (extra != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), content = extra)
        }
    }
}

/**
 * The small covers the tools' lists show, by song (the same song on two services is one cover):
 * a few dozen kept at thumbnail size, looked up four at a time, only where Discover shows pictures.
 */
internal object ToolCovers {
    private const val KEEP = 160
    private const val SIDE = 168
    private val kept = object : LinkedHashMap<String, android.graphics.Bitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap?>?) = size > KEEP
    }
    private val gate = kotlinx.coroutines.sync.Semaphore(4)

    private fun key(t: com.xlollx.songport.model.Track) = com.xlollx.songport.sync.Wrapped.key(t)
    fun peek(t: com.xlollx.songport.model.Track): android.graphics.Bitmap? = synchronized(kept) { kept[key(t)] }
    fun put(t: com.xlollx.songport.model.Track, bmp: android.graphics.Bitmap?) { synchronized(kept) { kept[key(t)] = bmp?.let(::small) } }
    private fun small(b: android.graphics.Bitmap) = if (b.width <= SIDE) b else android.graphics.Bitmap.createScaledBitmap(b, SIDE, SIDE * b.height / b.width, true)

    suspend fun load(t: com.xlollx.songport.model.Track, serviceId: String): android.graphics.Bitmap? {
        val k = key(t)
        synchronized(kept) { if (kept.containsKey(k)) return kept[k] }
        val found = gate.withPermit { runCatching { Cards.trackCover(t, serviceId) }.getOrNull() }
        put(t, found)
        return peek(t)
    }
}

/** A song's own two colours, from its album (or its title): what stands for a cover that is not there. */
private fun coverBrush(t: com.xlollx.songport.model.Track): Brush {
    val h = ((t.album.ifBlank { t.title }.hashCode() % 360) + 360) % 360
    return Brush.linearGradient(listOf(Color.hsv(h.toFloat(), 0.55f, 0.62f), Color.hsv(((h + 48) % 360).toFloat(), 0.7f, 0.32f)))
}

/**
 * A song's cover, [size] wide: looked up when Discover shows pictures ([discoverImagesOn]) and
 * [load] says so, else (and until it comes) a tile in the song's own colours with a note. [gone]:
 * grey and faded, for a song that is no longer there.
 */
@Composable
internal fun TrackCover(t: com.xlollx.songport.model.Track, serviceId: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp, gone: Boolean = false, load: Boolean = true) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by androidx.compose.runtime.remember(t.id, t.title) { androidx.compose.runtime.mutableStateOf(ToolCovers.peek(t)) }
    androidx.compose.runtime.LaunchedEffect(t.id, t.title, load) {
        if (bmp == null && load && serviceId != null && discoverImagesOn(ctx)) bmp = ToolCovers.load(t, serviceId)
    }
    Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(coverBrush(t)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(
            b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            colorFilter = if (gone) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) else null,
        ) else Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(size * 0.42f))
        if (gone) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = if (b != null) 0.22f else 0.5f)))
    }
}

/** A playlist as a picture: four covers of different albums in a square, or one when it has fewer. */
@Composable
internal fun CoverMosaic(tracks: List<com.xlollx.songport.model.Track>, serviceId: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    val picks = androidx.compose.runtime.remember(tracks) { tracks.filter { it.kind.isEmpty() }.distinctBy { it.album.ifBlank { it.title } }.take(4) }
    Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clearAndSetSemantics {}) {
        when {
            picks.size >= 4 -> Column {
                for (r in 0..1) Row { for (c in 0..1) TrackCover(picks[r * 2 + c], serviceId, size / 2, corner = 0.dp) }
            }
            picks.isNotEmpty() -> TrackCover(picks[0], serviceId, size, corner = 0.dp)
            else -> Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(size * 0.4f))
        }
    }
}

/** A row of a song with its cover: title and artist, and what is at its end. */
@Composable
internal fun ToolTrackRow(t: com.xlollx.songport.model.Track, serviceId: String?, modifier: Modifier = Modifier, load: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackCover(t, serviceId, 44.dp, load = load)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (t.artistLine.isNotBlank()) Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** A list of songs in a section, each with its cover ([serviceId]: where they are read from): the first [first] and the rest on request. */
@Composable
internal fun ToolTrackList(tracks: List<com.xlollx.songport.model.Track>, first: Int = 5, max: Int = 200, serviceId: String? = "") {
    var all by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val shown = if (all) tracks.take(max) else tracks.take(first)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Covers for the first few dozen only: a long list does not look up hundreds of them.
        shown.forEachIndexed { i, t -> ToolTrackRow(t, serviceId, load = i < 30) }
    }
    if (!all && tracks.size > first) androidx.compose.material3.TextButton(onClick = { all = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.preview_more, tracks.size - first)) }
    else if (all && tracks.size > max) Text(stringResource(R.string.preview_more, tracks.size - max), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A figure said big, with what it counts under it. */
@Composable
internal fun ToolStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, valueColor: Color = MaterialTheme.colorScheme.onSurface, top: (@Composable () -> Unit)? = null) {
    Surface(modifier.semantics(mergeDescendants = true) {}, shape = RoundedCornerShape(18.dp), color = color) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            top?.invoke()
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** [bmp] on a black screen, fitted whole; a tap anywhere, the ✕ or Back closes it. */
@Composable
private fun FullScreenPicture(bmp: android.graphics.Bitmap, description: String?, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = stringResource(R.string.ai_done_close), onClick = onClose)) {
            androidx.compose.foundation.Image(
                bmp.asImageBitmap(), contentDescription = description,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
            androidx.compose.material3.IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
            ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, stringResource(R.string.ai_done_close), tint = Color.White) }
        }
    }
}

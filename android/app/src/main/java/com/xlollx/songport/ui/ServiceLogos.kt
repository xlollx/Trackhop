package com.xlollx.songport.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.providers.*

/**
 * The services' marks, drawn as a white glyph on a circle in the service's colour.
 *
 * Glyph paths are in [ServiceMarks] (Simple Icons, CC0), shared with the server's web page. The marks
 * are the services' trademarks and are used only to show which service a playlist or an account belongs to.
 * Services Simple Icons does not carry (Amazon Music, LiveOne, Anghami, Qobuz, ListenBrainz) keep their initial;
 * personal servers without a mark of their own (Subsonic/Navidrome) and files get a generic icon.
 */
object ServiceLogos {
    private val cache = HashMap<String, ImageVector>()

    /** The glyph's SVG path (24×24 viewport) for a service id, or null: the shareable cards draw it on a Canvas. */
    fun pathData(serviceId: String?): String? = ServiceMarks.pathData(serviceId)

    /** The brand glyph for a service id (bridge and web variants share their brand's), or null. */
    fun glyph(serviceId: String?): ImageVector? {
        val path = pathData(serviceId) ?: return null
        return synchronized(cache) {
            cache.getOrPut(serviceId!!) {
                ImageVector.Builder(serviceId, 24.dp, 24.dp, 24f, 24f)
                    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
                    .build()
            }
        }
    }
}

/**
 * Shazam's mark (Simple Icons, CC0): on a Shazam sync's card and in the editor's "Shazam" source,
 * as the sparkles mark a playlist written by the user's AI.
 */
val ShazamGlyph: ImageVector by lazy {
    ImageVector.Builder("shazam", 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(ServiceMarks.SHAZAM), fill = SolidColor(Color.White))
        .build()
}

/** Shazam's blue, a little lighter on a dark theme so the mark reads on dark cards. */
@Composable
fun shazamTint(): Color = if (LocalAppDark.current) Color(0xFF4DA8FF) else Color(ServiceMarks.SHAZAM_COLOR)

/**
 * The service's mark: its glyph in white on a circle of its colour; a generic icon for files and
 * personal servers without a mark; the initial otherwise. TalkBack reads the service's name.
 */
@Composable
fun ServiceLogo(p: MusicProvider?, size: Dp = 36.dp, modifier: Modifier = Modifier) =
    ServiceLogo(p?.serviceId, p?.brandColor, p?.displayName.orEmpty(), size, modifier)

/** The same mark from a service's id, colour and name: for a service this build does not carry. */
/**
 * Store listings' pictures (the Play screenshots) show no other brand's mark: with [neutral] on,
 * every service is its initial on a plain circle. Set only by the screenshot test.
 */
object LogoMode {
    @Volatile var neutral = false
}

@Composable
fun ServiceLogo(serviceId: String?, brandColor: Long?, name: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val neutral = LogoMode.neutral
    val bg = if (brandColor != null && !neutral) Color(brandColor) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else if (bg.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White
    // A near-black mark (TIDAL) on a dark surface needs an edge to be seen.
    val edge = bg.luminance() < 0.03f
    Box(
        modifier.size(size).clip(CircleShape).background(bg)
            .then(if (edge) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier)
            .semantics { if (name.isNotEmpty()) contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = if (neutral) null else ServiceLogos.glyph(serviceId)
        val generic = when (serviceId) { "csv" -> Icons.Filled.Folder; "subsonic" -> Icons.Filled.Dns; "charts" -> Icons.Filled.BarChart; "releases" -> Icons.Filled.NewReleases; com.xlollx.songport.shared.SharedProvider.ID -> Icons.Filled.Groups; else -> null }
        when {
            glyph != null -> Image(glyph, null, Modifier.size(size * 0.56f), colorFilter = ColorFilter.tint(fg))
            generic != null -> Icon(generic, null, tint = fg, modifier = Modifier.size(size * 0.56f))
            else -> Text(
                name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.46f).sp,
            )
        }
    }
}

/**
 * One choice in [ServiceTiles]: a service, or an extra entry such as "Link" with its own icon. With
 * a [brandColor] the icon is a brand's round mark (Shazam), drawn whole in that colour. An [action]
 * is not a choice but a way out ("Connect"): a dashed ring, never selected.
 */
class ServiceTile(
    val key: String, val label: String, val provider: MusicProvider? = null, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val detail: String? = null, val brandColor: Long? = null, val action: Boolean = false,
)

/**
 * The service picker: a strip of round marks with the name under each; the selected one wears a
 * ring and a check and its name is bolder, so the choice never rests on colour alone. Scrolls when
 * the services do not fit, instead of wrapping into uneven rows.
 *
 * When they do not fit, it shows that the row goes on: the cells are sized from the width so the
 * last one in view is cut about a third of the way ([tileLayout]), the row fades out at the edge
 * it continues past, and TalkBack hears "N services, scroll for more". The selected one is brought
 * into view, so a sync on the fifth account opens with it shown.
 */
@Composable
fun ServiceTiles(tiles: List<ServiceTile>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val layout = tileLayout(maxWidth, tiles.size)
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        val stepPx = with(density) { (layout.width + TILE_GAP).toPx() }
        val tilePx = with(density) { layout.width.toPx() }
        val viewPx = with(density) { maxWidth.toPx() }
        val contentPx = tiles.size * stepPx - with(density) { TILE_GAP.toPx() }
        val overflows = contentPx > viewPx + 1f
        // The selected tile wholly in view: at once when the picker opens, smoothly after a tap on a cut one.
        val index = tiles.indexOfFirst { it.key == selected }
        var placed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(index, layout.width, overflows) {
            if (index < 0 || !overflows) { placed = true; return@LaunchedEffect }
            val pos = state.firstVisibleItemIndex * stepPx + state.firstVisibleItemScrollOffset
            val start = index * stepPx
            val target = when {
                start < pos -> start
                start + tilePx > pos + viewPx -> start + tilePx - viewPx
                else -> null
            }
            if (target != null) {
                val first = (target / stepPx).toInt().coerceIn(0, tiles.size - 1)
                val offset = (target - first * stepPx).toInt().coerceAtLeast(0)
                if (placed) state.animateScrollToItem(first, offset) else state.scrollToItem(first, offset)
            }
            placed = true
        }
        val hint = androidx.compose.ui.res.pluralStringResource(com.xlollx.songport.R.plurals.service_tiles_scroll, tiles.size, tiles.size)
        // Every cell as tall as a mark and two lines of name, so the row keeps its height as it scrolls.
        val line = with(density) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
        val cellHeight = MARK_BOX + 4.dp + line * 2 + 8.dp
        val fade = with(density) { 16.dp.toPx() }
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.fillMaxWidth()
                .then(if (overflows) Modifier.semantics { contentDescription = hint } else Modifier)
                // The row fades out where it goes on: at the end until the last cell shows, at the start once scrolled.
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (!overflows) return@drawWithContent
                    val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                    val atLeft = if (rtl) state.canScrollForward else state.canScrollBackward
                    val atRight = if (rtl) state.canScrollBackward else state.canScrollForward
                    if (atLeft) drawRect(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade),
                        size = androidx.compose.ui.geometry.Size(fade, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                    if (atRight) drawRect(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fade, size.width),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                        size = androidx.compose.ui.geometry.Size(fade, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
            state = state,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TILE_GAP),
        ) {
            items(tiles.size, key = { tiles[it].key }) { i -> Tile(tiles[i], tiles[i].key == selected, layout.width, cellHeight, onSelect) }
        }
    }
}

private val TILE_GAP = 4.dp
private val TILE_WIDTH = 76.dp
private val TILE_MIN = 64.dp
private val TILE_MAX = 92.dp
/** The mark and the room its ring takes around it. */
private val MARK = 48.dp
private val MARK_BOX = 60.dp
/** The share of the last tile in view left showing when the row goes on. */
private const val PEEK = 0.35f

internal class TileLayout(val width: Dp, val inView: Float)

/**
 * The tiles' width for a row [available] wide: the usual width when all [count] fit; otherwise the
 * width closest to it that leaves a whole number of tiles in view plus [PEEK] of the next, so the
 * row never ends on a clean edge that hides the rest.
 */
internal fun tileLayout(available: Dp, count: Int): TileLayout {
    val gap = TILE_GAP.value
    val all = count * TILE_WIDTH.value + (count - 1).coerceAtLeast(0) * gap
    if (count <= 1 || all <= available.value) return TileLayout(TILE_WIDTH, count.toFloat())
    val best = (1 until count).map { whole ->
        // whole tiles and their gaps, then a gap and the peek of the next.
        whole to (available.value - whole * gap) / (whole + PEEK)
    }.filter { (_, w) -> w > 0f }.minByOrNull { (_, w) -> kotlin.math.abs(w - TILE_WIDTH.value) }
        ?: return TileLayout(TILE_WIDTH, available.value / (TILE_WIDTH.value + gap))
    val w = best.second.coerceIn(TILE_MIN.value, TILE_MAX.value)
    return TileLayout(w.dp, best.first + PEEK)
}

/**
 * A tile's words: the service's name, and under it what tells it from its namesakes: the route in
 * brackets ("Spotify (web)" is "Spotify" over "web", also where that is the name the connector was
 * given) and a further account's name ("Spotify · anna").
 */
internal fun tileWords(label: String, provider: MusicProvider?, detail: String?): Pair<String, String?> {
    if (detail != null || provider == null) return label to detail
    val named = Providers.customName(provider.id) != null
    val account = if (named) null else label.substringAfter(" · ", "").takeIf { it.isNotBlank() }
    val head = if (named) label else label.substringBefore(" · ")
    val route = Regex("^(.+?) \\(([^()]+)\\)$").find(head)
    val name = route?.groupValues?.get(1) ?: head
    return name to listOfNotNull(route?.groupValues?.get(2), account).joinToString(" · ").takeIf { it.isNotEmpty() }
}

@Composable
private fun Tile(t: ServiceTile, on: Boolean, width: Dp, height: Dp, onSelect: (String) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val (name, detail) = tileWords(t.label, t.provider, t.detail)
    // A further account of a service wears its number on the mark.
    val account = t.provider?.slot?.takeIf { it.isNotEmpty() && it.length <= 2 }
    androidx.compose.foundation.layout.Column(
        Modifier.semantics { this.selected = on }.size(width = width, height = height)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable { if (!on) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onSelect(t.key) }
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(MARK_BOX), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(MARK_BOX - 2.dp).border(2.dp, scheme.primary, CircleShape))
            when {
                t.action -> {
                    val dash = scheme.outline
                    Box(
                        Modifier.size(MARK).drawBehind {
                            drawCircle(dash, radius = size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(
                                1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                            ))
                        },
                        contentAlignment = Alignment.Center,
                    ) { t.icon?.let { Icon(it, null, tint = scheme.primary, modifier = Modifier.size(22.dp)) } }
                }
                t.provider != null -> ServiceLogo(t.provider, MARK, Modifier.clearAndSetSemantics {})
                t.icon != null && t.brandColor != null -> Icon(t.icon, null, tint = Color(t.brandColor), modifier = Modifier.size(MARK))
                else -> Box(Modifier.size(MARK).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    t.icon?.let { Icon(it, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
                }
            }
            if (on) Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp)) }
            if (account != null) Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(scheme.inverseSurface),
                contentAlignment = Alignment.Center,
            ) { Text(account, color = scheme.inverseOnSurface, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold) }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
        Text(
            name, style = MaterialTheme.typography.labelMedium, maxLines = if (detail == null) 2 else 1,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
        )
        detail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = scheme.onSurfaceVariant)
        }
    }
}

/** The usual service tiles for a list of connected providers. */
@Composable
fun ServicePicker(providers: List<MusicProvider>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ServiceTiles(providers.map { ServiceTile(it.id, it.label(ctx), it) }, selectedId, onSelect, modifier)
}


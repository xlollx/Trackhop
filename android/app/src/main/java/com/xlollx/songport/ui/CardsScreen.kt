@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xlollx.songport.ui

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import com.xlollx.songport.ai.config
import com.xlollx.songport.providers.*
import android.graphics.Bitmap
import com.xlollx.songport.sync.CoverPrompt
import com.xlollx.songport.ai.AiClient
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.JoinInner
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.xlollx.songport.providers.brand
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Compatibility
import com.xlollx.songport.sync.LinkResolver
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.PosterPalette
import androidx.compose.foundation.selection.toggleable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A playlist chosen for a card: the service it lives on and what is known about it. */
class CardPick(val provider: MusicProvider, val playlist: Playlist, val pastedLink: String? = null)

/**
 * The playlist last chosen on each page that names a [PlaylistSource] memory, for as long as the app
 * runs: going to another tab and back left the page on "Link" with nothing chosen.
 */
internal object PickMemory {
    private val picks = HashMap<String, CardPick>()
    @Synchronized fun get(key: String?): CardPick? = key?.let { picks[it] }
    @Synchronized fun set(key: String?, pick: CardPick?) { if (key == null) return; if (pick == null) picks.remove(key) else picks[key] = pick }
}

/**
 * Where a card's playlist comes from: a pasted public link, or one of the user's own playlists on
 * a connected service. [initialLink] pre-fills and resolves a link handed in from the share sheet.
 * [memory]: the page's key in [PickMemory], so the choice is still there when the page comes back.
 * [compact]: once a playlist is chosen the picker folds into one row (its picture, its name, where
 * it lives, "Change"), so the page goes on to what it makes of it.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistSource(label: String, initialLink: String?, snackbar: SnackbarHostState, memory: String? = null, compact: Boolean = false, onPick: (CardPick?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = remember { Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" } }
    // A choice kept from before, unless a link came in from the share sheet: that one wins.
    val kept = remember { if (initialLink.isNullOrBlank()) PickMemory.get(memory) else null }
    var mode by remember { mutableStateOf(kept?.takeIf { it.pastedLink == null }?.provider?.id?.takeIf { id -> connected.any { it.id == id } }) } // null = link, else provider id
    var link by remember { mutableStateOf(initialLink ?: kept?.pastedLink.orEmpty()) }
    var picked by remember { mutableStateOf(kept?.takeIf { it.pastedLink != null || mode != null }) }
    var busy by remember { mutableStateOf(false) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    // The picker's sheet, and what went wrong in it (the page's snackbar would show under the sheet).
    var sheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    suspend fun say(message: String) { if (sheet) sheetError = message else snackbar.showSnackbar(message) }
    fun choose(p: CardPick?) { picked = p; if (p != null) sheet = false; PickMemory.set(memory, p); onPick(p) }
    LaunchedEffect(Unit) { picked?.let(onPick) }

    suspend fun resolve(text: String) {
        busy = true
        sheetError = null
        val ref = LinkResolver.resolve(ctx, text)
        val named = ref?.let { com.xlollx.songport.providers.linkProvider(ctx, it.providerId) }
        val provider = ref?.let { r ->
            named?.takeIf { it.canRead(ctx, r.playlistId) }
                ?: Providers.connectors().firstOrNull { it.familyName == named?.familyName && it.canRead(ctx, r.playlistId) }
        }
        when {
            ref == null -> say(ctx.getString(R.string.link_invalid))
            provider == null -> say(ctx.getString(R.string.link_not_connected, named?.displayName ?: ref.providerId))
            else -> {
                val got = runCatching { provider.playlistInfo(ctx, ref.playlistId) }
                val failed = got.exceptionOrNull()?.takeIf { provider.reportsLinkErrors(ref.playlistId) && it !is kotlinx.coroutines.CancellationException }
                if (failed != null) say(failed.message ?: ctx.getString(R.string.error_generic))
                else choose(CardPick(provider, got.getOrElse { Playlist(ref.playlistId, ctx.getString(R.string.card_unnamed_playlist), ownedByMe = false) }, text.trim()))
            }
        }
        busy = false
    }

    LaunchedEffect(initialLink) { if (!initialLink.isNullOrBlank()) resolve(initialLink) }
    LaunchedEffect(mode) {
        val id = mode ?: return@LaunchedEffect
        val p = connected.firstOrNull { it.id == id } ?: return@LaunchedEffect
        lists = null
        sheetError = null
        lists = try { p.libraryEntries(ctx, asTarget = false) + p.playlists(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            say(e.message ?: ctx.getString(R.string.error_generic)); emptyList()
        }
    }

    // One field: the playlist chosen (its picture, its name, where it lives, "Change"), or the invitation
    // to choose one. Its sheet has the link and the services, each with its playlists.
    val chosen = picked
    PlaylistField(
        label, chosen?.playlist?.name?.ifBlank { stringResource(R.string.card_unnamed_playlist) }, stringResource(R.string.editor_choose_playlist),
        onClick = { sheet = true }, subtitle = chosen?.let { playlistWhere(it.provider, it.playlist) }, provider = chosen?.provider,
        shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer,
        thumb = { if (chosen != null) PlaylistArt(chosen, 48.dp) else PlaylistThumb(null, null, 48.dp) },
    )
    if (sheet) PickerSheet(label, onDismiss = { sheet = false }) {
        val linkLabel = stringResource(R.string.card_source_link)
        val provider = connected.firstOrNull { it.id == mode }
        PlaylistRows(
            lists, sheetError.takeIf { mode != null }, provider, chosen?.takeIf { it.provider.id == mode }?.playlist?.id,
            onPick = { p -> provider?.let { choose(CardPick(it, p)) } },
            recentIds = remember(mode) { recentPlaylistIds(ctx, mode) },
            header = {
                ServiceTiles(
                    listOf(ServiceTile(LINK_TILE, linkLabel, icon = Icons.Filled.Link)) + connected.map { ServiceTile(it.id, it.label(ctx), it) },
                    selected = mode ?: LINK_TILE,
                    onSelect = { key -> mode = if (key == LINK_TILE) null else key; sheetError = null; choose(null) },
                )
            },
            other = if (mode == null) ({
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = link, onValueChange = { link = it; sheetError = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.card_source_link_hint)) },
                        trailingIcon = {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                            else TextButton(enabled = link.isNotBlank(), onClick = { scope.launch { resolve(link) } }) { Text(stringResource(R.string.link_use)) }
                        },
                    )
                    sheetError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }) else null,
        )
    }
}

/** A playlist's picture: its own where the service lists one, else a tile in its name's colours with the service's mark. */
@Composable
internal fun PlaylistArt(pick: CardPick, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val url = pick.playlist.imageUrl?.takeIf { it.isNotBlank() && !MusicProvider.isLibrary(pick.playlist.id) }
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { if (url != null && discoverImagesOn(ctx)) bmp = Cards.bitmap(url, 300) }
    val h = ((pick.playlist.name.hashCode() % 360) + 360) % 360
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.hsv(h.toFloat(), 0.5f, 0.6f), androidx.compose.ui.graphics.Color.hsv(((h + 50) % 360).toFloat(), 0.65f, 0.3f)))
    androidx.compose.foundation.layout.Box(modifier.size(size).clip(RoundedCornerShape(size / 5)).background(brush).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else ServiceLogo(pick.provider, size = size * 0.5f)
    }
}

/**
 * Share and save buttons for a rendered card, with the story/square switch. [onRender] null: the
 * card draws itself (no "make the card" button). [previewDescription] is what TalkBack reads for
 * the preview. [matte]: the colour of the sheet the preview lies on, for a card that is a print.
 */
@Composable
internal fun CardActions(bitmaps: Map<Cards.Format, Bitmap>?, name: String, busy: Boolean, onRender: (() -> Unit)?, renderLabel: String, previewDescription: String? = null, formats: List<Cards.Format> = Cards.Format.entries, enabled: Boolean = true, matte: androidx.compose.ui.graphics.Color? = null) {
    val ctx = LocalContext.current
    var format by remember { mutableStateOf(formats.first()) }
    val bmp = bitmaps?.get(format)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && bmp != null) Cards.save(ctx, bmp, uri)
    }
    if (onRender != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
        Button(enabled = !busy && enabled, onClick = onRender) { Text(renderLabel) }
    } else if (busy) CircularProgressIndicator(Modifier.size(18.dp))
    // While a card is being drawn its place is kept (chips, a blank of the card's shape, the buttons
    // greyed): appearing late, it pushed everything below it down under a finger already on its way.
    if (bmp != null || (busy && onRender == null)) {
        if (formats.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = format == Cards.Format.STORY, onClick = { format = Cards.Format.STORY }, label = { Text(stringResource(R.string.card_format_story)) })
            FilterChip(selected = format == Cards.Format.SQUARE, onClick = { format = Cards.Format.SQUARE }, label = { Text(stringResource(R.string.card_format_square)) })
        }
        // On a matte the card lies on a sheet of paper, as a print: square corners, a margin all round.
        val shape = if (matte != null) Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(matte).padding(14.dp).aspectRatio(format.width.toFloat() / format.height)
        else Modifier.fillMaxWidth().aspectRatio(format.width.toFloat() / format.height).clip(RoundedCornerShape(16.dp))
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = previewDescription, modifier = shape)
        else androidx.compose.foundation.layout.Box(shape.background(MaterialTheme.colorScheme.surfaceVariant))
        // Wraps at a large font size instead of squeezing a button into a column of letters.
        // Until the card is there the buttons keep their room but are not shown nor offered.
        val hidden = if (bmp == null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
        androidx.compose.foundation.layout.FlowRow(hidden, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val tall = Modifier.heightIn(min = 48.dp)
            Button(enabled = bmp != null, onClick = { bmp?.let { Cards.share(ctx, it, name) } }, modifier = tall) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.card_share))
            }
            OutlinedButton(enabled = bmp != null, onClick = { save.launch("$name.png") }, modifier = tall) { Text(stringResource(R.string.card_save)) }
        }
    }
}

/** The playlist's web address for a QR: the service's page, or the user's own server. */
private fun qrUrl(ctx: android.content.Context, pick: CardPick): String? {
    pick.pastedLink?.let { l -> Regex("""https?://\S+""").find(l)?.value?.let { return it } }
    val tokens = ctx.env.tokens.get(pick.provider.id)
    val base = tokens?.extra?.get("url")
    val region = when (pick.provider.serviceId) {
        "apple" -> tokens?.extra?.get("storefront")
        "amazon" -> pick.provider.webSearchUrl(ctx, "")?.let { Regex("""music\.amazon\.([a-z.]+)/""").find(it)?.groupValues?.get(1) }
        else -> null
    }
    return PlaylistLinks.webUrl(pick.provider.id, pick.playlist.id, base, region)
}

/** "How compatible are we?": two playlists in, a score and a card out. */
@Composable
fun CompatibilityCard(snackbar: SnackbarHostState, initialLinks: Pair<String, String>? = null, onMakeShared: ((name: String, tracks: List<Track>) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf<CardPick?>(null) }
    var b by remember { mutableStateOf<CardPick?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Compatibility.Result?>(null) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    // What went wrong, said on the page with a way to try again.
    var error by remember { mutableStateOf<String?>(null) }
    // The songs of each, for the two pictures of the result.
    var tracksA by remember { mutableStateOf<List<Track>>(emptyList()) }
    var tracksB by remember { mutableStateOf<List<Track>>(emptyList()) }

    fun run() {
        val pa = a ?: return; val pb = b ?: return
        busy = true; result = null; bitmaps = null; error = null
        scope.launch {
            try {
                val (ta, tb) = coroutineScope {
                    val da = async { pa.provider.tracks(ctx, pa.playlist.id) }
                    val db = async { pb.provider.tracks(ctx, pb.playlist.id) }
                    da.await() to db.await()
                }
                val r = withContext(Dispatchers.Default) { Compatibility.compare(ta, tb) }
                tracksA = ta; tracksB = tb
                result = r
                val covers = withContext(Dispatchers.IO) {
                    coroutineScope { r.common.take(5).map { c -> async { c.a.id to Cards.trackCover(c.a, pa.provider.serviceId) } }.map { it.await() } }.toMap()
                }
                val card = Cards.CompatibilityCard(pa.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }, pb.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }, r, covers)
                bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.compatibility(ctx, card, it) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.error_generic)
            }
            busy = false
        }
    }

    // Two links shared together: compared as soon as both resolve.
    var autoRan by remember { mutableStateOf(false) }
    LaunchedEffect(a, b) { if (initialLinks != null && !autoRan && a != null && b != null) { autoRan = true; run() } }

    val r = result
    ToolResultOnTop(r)
    // The two playlists side by side in a wide window, as the pair they are.
    val pair: @Composable () -> Unit = {
        ToolSplit(
            start = { PlaylistSource(stringResource(R.string.card_compat_first), initialLinks?.first, snackbar, compact = true) { a = it } },
            end = { PlaylistSource(stringResource(R.string.card_compat_second), initialLinks?.second, snackbar, compact = true) { b = it } },
        )
        ToolPrimaryButton(
            stringResource(if (r == null) R.string.card_compat_run else R.string.card_again), onClick = { run() },
            icon = Icons.Filled.JoinInner, enabled = a != null && b != null, busy = busy,
        )
        if (busy && r == null) ToolLoading(stringResource(R.string.loading))
        error?.let { ToolError(it, onRetry = if (a != null && b != null) ({ run() }) else null) }
    }
    // The pickers keep their playlists when they move under the answer.
    val pairSlot = rememberToolSlot()
    if (r == null) { pairSlot(pair); return }
    val nameA = a?.playlist?.name.orEmpty().ifBlank { stringResource(R.string.card_unnamed_playlist) }
    val nameB = b?.playlist?.name.orEmpty().ifBlank { stringResource(R.string.card_unnamed_playlist) }
    val serviceA = a?.provider?.serviceId.orEmpty()
    val serviceB = b?.provider?.serviceId.orEmpty()
    val subtitle = stringResource(R.string.card_compat_subtitle, r.common.size, r.sharedArtists.size)
    // The answer first: the two playlists facing each other with the score between them.
    CompatHero(nameA, nameB, tracksA, tracksB, serviceA, serviceB, r.score, subtitle, stringResource(R.string.card_compat_common_pct, r.commonPercent))
    ToolSplit(
        start = {
            if (r.sharedArtists.isNotEmpty()) ToolSection(stringResource(R.string.card_compat_artists), trailing = { CountPill(r.sharedArtists.size) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.sharedArtists.take(12).forEach { ArtistChip(it) }
                }
            }
            if (bitmaps != null || busy) ToolShare(bitmaps, "trackhop-compatibility", "${r.score}%. $subtitle")
        },
        end = {
            if (r.common.isNotEmpty()) ToolSection(stringResource(R.string.card_compat_in_common), trailing = { CountPill(r.common.size) }) {
                ToolTrackList(r.common.map { it.a }, first = 5, serviceId = serviceA)
                // The songs in common as a playlist of their own, synced wherever the user chooses.
                if (onMakeShared != null) {
                    Text(stringResource(R.string.discover_compat_shared_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.FilledTonalButton(onClick = {
                        onMakeShared(ctx.getString(R.string.discover_compat_shared_name, a?.playlist?.name.orEmpty(), b?.playlist?.name.orEmpty()), r.common.map { it.a })
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.discover_compat_shared), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
            // What each could take from the other: the songs come from the other's playlist.
            Suggestions(stringResource(R.string.card_compat_for, nameA), r.forA, serviceB)
            Suggestions(stringResource(R.string.card_compat_for, nameB), r.forB, serviceA)
        },
    )
    pairSlot(pair)
}

/** The two playlists facing each other, each as four of its covers, with the score between them. */
@Composable
private fun CompatHero(nameA: String, nameB: String, tracksA: List<Track>, tracksB: List<Track>, serviceA: String, serviceB: String, score: Int, subtitle: String, detail: String) {
    val id = LocalToolIdentity.current
    val glow = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(id.accent.copy(alpha = 0.38f), id.second().copy(alpha = 0.10f)))
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().clip(ToolHeroShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).background(glow)
            .semantics(mergeDescendants = true) {}.padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        val cover = ((maxWidth - 156.dp) / 2).coerceIn(56.dp, 128.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CoverMosaic(tracksA, serviceA, cover, Modifier.graphicsLayer { rotationZ = -4f })
                    Text(nameA, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(
                    "$score%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = id.tint(), maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 28.dp),
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CoverMosaic(tracksB, serviceB, cover, Modifier.graphicsLayer { rotationZ = 4f })
                    Text(nameB, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(subtitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** How many there are, at the end of a section's title. */
@Composable
internal fun CountPill(count: Int) {
    val id = LocalToolIdentity.current
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = id.container()) {
        Text("$count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = id.tint(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
    }
}

/** An artist on a pill: the round picture and the name. */
@Composable
internal fun ArtistChip(name: String) {
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtistAvatar(name, 36.dp)
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Suggestions(title: String, tracks: List<Track>, serviceId: String) {
    if (tracks.isEmpty()) return
    ToolSection(title) { ToolTrackList(tracks.take(5), first = 5, serviceId = serviceId) }
}

/** Covers the user's AI drew in this session, by playlist: "make the card again" does not pay twice. */
private val aiCovers = HashMap<String, Bitmap>()

/** Track covers picked for a playlist in this session (playlist, variant, how many): changing style does not look them up again. */
private val pickedCovers = LinkedHashMap<String, List<Bitmap>>()

/**
 * What the poster's art is made of: the playlist's own covers, a cover drawn on the phone in one
 * of [GeneratedCovers.Style], or the user's AI.
 */
internal enum class PosterArt(val label: Int, val style: GeneratedCovers.Style? = null) {
    COVERS(R.string.card_cover_covers),
    MOSAIC(R.string.card_cover_mosaic, GeneratedCovers.Style.MOSAIC),
    GRADIENT(R.string.card_cover_gradient, GeneratedCovers.Style.GRADIENT),
    GEOMETRIC(R.string.card_cover_geometric, GeneratedCovers.Style.GEOMETRIC),
    TYPOGRAPHIC(R.string.card_cover_typographic, GeneratedCovers.Style.TYPOGRAPHIC),
    VINYL(R.string.card_cover_vinyl, GeneratedCovers.Style.VINYL),
    AI(R.string.card_cover_ai),
}

/** How the poster is laid out ([PosterPalette.Layout]), with its name. */
private val posterLayouts = listOf(
    PosterPalette.Layout.CLASSIC to R.string.card_layout_classic,
    PosterPalette.Layout.MINIMAL to R.string.card_layout_minimal,
    PosterPalette.Layout.BOLD to R.string.card_layout_bold,
)

private fun posterPrefs(ctx: android.content.Context) = ctx.applicationContext.getSharedPreferences("poster", android.content.Context.MODE_PRIVATE)

/** The playlist poster: covers, title, numbers and a QR to open it. */
@Composable
fun PosterCard(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var busy by remember { mutableStateOf(false) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }
    // The AI of the Playlist generator, if any, and whether it can draw.
    val ai = remember { AiClient.config(ctx)?.takeIf { it.complete } }
    val aiSupport = ai?.let { AiClient.imageSupport(it) }
    val aiOk = ai != null && aiSupport == AiClient.ImageSupport.YES
    // The art chosen last time; the AI only while it can still draw.
    var art by remember {
        val saved = runCatching { PosterArt.valueOf(posterPrefs(ctx).getString("art", null) ?: "") }.getOrNull() ?: PosterArt.COVERS
        mutableStateOf(if (saved == PosterArt.AI && !aiOk) PosterArt.COVERS else saved)
    }
    var layout by remember { mutableStateOf(runCatching { PosterPalette.Layout.valueOf(posterPrefs(ctx).getString("layout", null) ?: "") }.getOrNull() ?: PosterPalette.Layout.CLASSIC) }
    // The poster is signed only when asked: an account's name is often an e-mail address.
    var showName by remember { mutableStateOf(posterPrefs(ctx).getBoolean("name", false)) }
    // What the poster on show is made of, and the name of the account when the playlist is the user's own.
    var printed by remember { mutableStateOf<Cards.PosterCard?>(null) }
    var ownerName by remember { mutableStateOf("") }
    // "Make it again" and "another version" turn this: other picks, another drawing.
    var variant by remember { mutableStateOf(0) }
    var drawing by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveCover = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val b = drawn
        if (uri != null && b != null) Cards.save(ctx, b, uri)
    }

    suspend fun draw(card: Cards.PosterCard): Map<Cards.Format, Bitmap> {
        val shown = card.with(layout, if (showName) ownerName else "")
        return withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.poster(ctx, shown, it) } }
    }
    // Another layout, the name on or off: the same poster printed again, nothing fetched.
    fun redraw() {
        val card = printed ?: return
        if (busy) return
        scope.launch { val again = draw(card); if (printed === card) bitmaps = again }
    }

    fun run(nextVariant: Boolean = false, newCover: Boolean = false) {
        val p = pick ?: return
        if (nextVariant) variant++
        val v = variant
        val chosen = art
        busy = true; bitmaps = null; printed = null; error = null
        scope.launch {
            try {
                val tracks = p.provider.tracks(ctx, p.playlist.id)
                val info = if (p.playlist.trackCount >= 0 && p.playlist.name.isNotBlank()) p.playlist else runCatching { p.provider.playlistInfo(ctx, p.playlist.id) }.getOrDefault(p.playlist)
                val key = p.provider.id + "|" + p.playlist.id
                val title = info.name.ifBlank { p.playlist.name }.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }
                val artists = CoverPrompt.topArtists(tracks)
                if (newCover) aiCovers.remove(key)
                // The covers of the artists that appear most, looked up once per playlist and variant.
                suspend fun picks(want: Int): List<Bitmap> {
                    val k = "$key|$v|$want"
                    synchronized(pickedCovers) { pickedCovers[k] }?.let { return it }
                    val got = Cards.distinctCovers(tracks, p.provider.serviceId, want, maxLookups = if (want > 4) 24 else 16, variant = v)
                    synchronized(pickedCovers) { if (pickedCovers.size >= 8) pickedCovers.remove(pickedCovers.keys.first()); pickedCovers[k] = got }
                    return got
                }
                suspend fun generated(style: GeneratedCovers.Style): Bitmap {
                    val covers = picks(if (style == GeneratedCovers.Style.MOSAIC) 9 else 4)
                    return withContext(Dispatchers.Default) { GeneratedCovers.draw(style, GeneratedCovers.Input(title, artists, covers), v) }
                }
                // A cover drawn on the phone carries the title: the sheet does not print it again.
                var titled = chosen.style != null
                val covers: List<Bitmap?> = withContext(Dispatchers.IO) {
                    drawn = null
                    when {
                        chosen == PosterArt.AI && ai != null && aiOk -> {
                            // Only the title, the main artists and the description go to the user's AI.
                            // When the AI can't draw (no image quota on the key, a refusal), the poster is
                            // still made, with a cover drawn on the phone, and the reason is said once.
                            val drawnByAi = aiCovers[key] ?: try {
                                drawing = true
                                val prompt = CoverPrompt.build(title, artists, info.description)
                                val bytes = AiClient.generateImage(ai, prompt)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { aiCovers[key] = it }
                                    ?: throw com.xlollx.songport.model.ProviderException(ctx.getString(R.string.error_generic))
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                val why = e.message ?: ctx.getString(R.string.error_generic)
                                scope.launch { snackbar.showSnackbar(ctx.getString(R.string.card_ai_cover_failed_drawn, why), withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long) }
                                null
                            } finally { drawing = false }
                            titled = drawnByAi == null
                            val shown = drawnByAi ?: generated(if (picks(4).size >= 4) GeneratedCovers.Style.MOSAIC else GeneratedCovers.Style.GRADIENT)
                            drawn = shown
                            listOf(shown)
                        }
                        chosen.style != null -> generated(chosen.style).also { drawn = it }.let { listOf(it) }
                        else -> {
                            // The playlist's own picture, else the covers of its most present artists,
                            // else (nothing found) a cover drawn from its title.
                            info.imageUrl?.let { url -> Cards.bitmap(url, 900)?.let { return@withContext listOf(it) } }
                            picks(4).ifEmpty { titled = true; listOf(generated(GeneratedCovers.Style.GRADIENT)) }
                        }
                    }
                }
                qr = qrUrl(ctx, p)
                ownerName = if (info.ownedByMe) p.provider.accountName(ctx).orEmpty().trim() else ""
                val card = Cards.PosterCard(
                    title = title, owner = "",
                    // The service by its plain name: the caption goes on a wall, not the connector's label.
                    service = p.provider.brand,
                    trackCount = tracks.size, durationMs = tracks.sumOf { it.durationMs },
                    covers = covers, qr = qr, artists = artists,
                    // The songs of the artists that appear most, not the playlist's first page.
                    tracks = CoverPrompt.posterTracks(tracks).map { it.title to it.artists.firstOrNull().orEmpty() }, titledArt = titled,
                )
                bitmaps = draw(card)
                printed = card
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.error_generic)
            }
            busy = false
        }
    }

    ToolResultOnTop(bitmaps)
    val setup: @Composable () -> Unit = {
        PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "poster", compact = true) { pick = it; bitmaps = null; printed = null; drawn = null; variant = 0; error = null }
        ToolSection(stringResource(R.string.card_cover_title)) {
            CoverArtChoice(art, pick?.playlist?.name.orEmpty(), ai, aiSupport, enabled = !busy) {
                art = it; bitmaps = null; printed = null; drawn = null
                runCatching { posterPrefs(ctx).edit().putString("art", it.name).apply() }
            }
        }
        ToolSection(stringResource(R.string.card_layout_title)) {
            LayoutChoice(layout, pick?.playlist?.name.orEmpty(), enabled = !busy) {
                layout = it; redraw()
                runCatching { posterPrefs(ctx).edit().putString("layout", it.name).apply() }
            }
        }
        ToolPrimaryButton(
            stringResource(if (bitmaps == null) R.string.card_poster_run else R.string.card_again), onClick = { run(nextVariant = bitmaps != null) },
            icon = Icons.Filled.Brush, enabled = pick != null, busy = busy,
        )
    }
    val made: @Composable () -> Unit = {
        if (busy) ToolLoading(stringResource(if (drawing) R.string.card_ai_drawing else R.string.loading))
        error?.let { ToolError(it, onRetry = if (pick != null) ({ run() }) else null) }
        // The poster on a sheet of paper, as it would hang on a wall.
        if (bitmaps != null) ToolShare(bitmaps, "trackhop-poster", pick?.playlist?.name, matte = androidx.compose.ui.graphics.Color(0xFFF4EFE6)) {
            // The user's own playlist: their account's name goes on the poster only if they say so.
            if (ownerName.isNotBlank()) {
                val id = LocalToolIdentity.current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
                        .toggleable(showName, role = androidx.compose.ui.semantics.Role.Switch) { on ->
                            showName = on; redraw()
                            runCatching { posterPrefs(ctx).edit().putBoolean("name", on).apply() }
                        }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.card_poster_show_name), style = MaterialTheme.typography.bodyMedium)
                        Text(ownerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    androidx.compose.material3.Switch(
                        checked = showName, onCheckedChange = null,
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = id.accent, checkedThumbColor = id.onAccent),
                    )
                }
            }
            if (qr == null) ToolNote(stringResource(R.string.card_poster_no_qr))
            if (art != PosterArt.COVERS && drawn != null) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (art == PosterArt.AI) OutlinedButton(enabled = !busy, onClick = { run(newCover = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_ai_again)) }
                    else OutlinedButton(enabled = !busy, onClick = { run(nextVariant = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_cover_another)) }
                    TextButton(onClick = { saveCover.launch("trackhop-cover.png") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_ai_save)) }
                }
            }
        }
    }
    // The poster first once there is one (or one on its way), what it is made from under it.
    // (The picker keeps its playlist when it moves under the poster.)
    val setupSlot = rememberToolSlot()
    if (busy || error != null || bitmaps != null) ToolSplit(start = { made() }, end = { setupSlot(setup) }) else setupSlot(setup)
}

/**
 * The poster's art: the playlist's covers, a cover drawn on the phone (five styles), or the
 * user's AI, each as a small picture of what it looks like (the drawn ones made from [title], as
 * the poster's will be). "Your AI" is on only when the configured AI can make images; the line
 * under them says what the choice does, and why the AI is off when it is.
 */
@Composable
private fun CoverArtChoice(selected: PosterArt, title: String, ai: AiClient.Config?, support: AiClient.ImageSupport?, enabled: Boolean, onChange: (PosterArt) -> Unit) {
    val aiOk = ai != null && support == AiClient.ImageSupport.YES
    val aiNote = when {
        ai == null -> stringResource(R.string.card_ai_cover_none)
        support == AiClient.ImageSupport.YES -> stringResource(R.string.card_ai_cover_yes, ai.vendor.label)
        support == AiClient.ImageSupport.NEEDS_IMAGE_MODEL -> stringResource(R.string.card_ai_cover_model)
        ai.vendor == AiClient.Vendor.ANTHROPIC -> stringResource(R.string.card_ai_cover_claude)
        else -> stringResource(R.string.card_ai_cover_no_images, ai.vendor.label)
    }
    val name = title.ifBlank { stringResource(R.string.app_name) }
    val thumbs by androidx.compose.runtime.produceState(emptyMap<GeneratedCovers.Style, androidx.compose.ui.graphics.ImageBitmap>(), name) {
        value = withContext(Dispatchers.Default) {
            GeneratedCovers.Style.entries.associateWith { runCatching { GeneratedCovers.draw(it, GeneratedCovers.Input(name, emptyList(), emptyList()), 0, 240).asImageBitmap() }.getOrNull() }
                .filterValues { it != null }.mapValues { it.value!! }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PosterArt.entries.forEach { a ->
                ArtTile(a, a == selected, enabled && (a != PosterArt.AI || aiOk), a.style?.let { thumbs[it] }) { onChange(a) }
            }
        }
        val note = when {
            selected == PosterArt.COVERS -> stringResource(R.string.card_cover_covers_note)
            selected == PosterArt.AI -> aiNote
            else -> stringResource(R.string.card_cover_drawn_note)
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!aiOk && selected != PosterArt.AI) Text(aiNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The poster's layout: Classic, Minimal or Bold, each as a small picture of a poster laid out that
 * way (four plain covers, [title] for a name).
 */
@Composable
private fun LayoutChoice(selected: PosterPalette.Layout, title: String, enabled: Boolean, onChange: (PosterPalette.Layout) -> Unit) {
    val ctx = LocalContext.current
    val name = title.ifBlank { stringResource(R.string.app_name) }
    val thumbs by androidx.compose.runtime.produceState(emptyMap<PosterPalette.Layout, androidx.compose.ui.graphics.ImageBitmap>(), name) {
        value = withContext(Dispatchers.Default) {
            val covers = listOf(0xFFB23A48, 0xFF2C5364, 0xFFF7971E, 0xFF5B3FD9).map { c -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(c.toInt()) } }
            posterLayouts.associate { (l, _) ->
                l to runCatching {
                    val full = Cards.poster(ctx, Cards.PosterCard(name, "", "", 0, 0, covers, null, layout = l), Cards.Format.SQUARE)
                    Bitmap.createScaledBitmap(full, 240, 240, true).also { if (it !== full) full.recycle() }.asImageBitmap()
                }.getOrNull()
            }.filterValues { it != null }.mapValues { it.value!! }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        posterLayouts.forEach { (l, label) ->
            ChoiceTile(stringResource(label), l == selected, enabled, { onChange(l) }) {
                thumbs[l]?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            }
        }
    }
}

/** One art to choose: a small picture of it with its name under. */
@Composable
private fun ArtTile(art: PosterArt, on: Boolean, enabled: Boolean, thumb: androidx.compose.ui.graphics.ImageBitmap?, onClick: () -> Unit) {
    ChoiceTile(stringResource(art.label), on, enabled, onClick) {
        when {
            thumb != null -> Image(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            // The playlist's own covers: four squares of four colours.
            art == PosterArt.COVERS -> Column(Modifier.fillMaxSize()) {
                val quads = listOf(0xFFB23A48, 0xFF2C5364, 0xFFF7971E, 0xFF5B3FD9)
                for (r in 0..1) Row(Modifier.weight(1f)) { for (c in 0..1) androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxSize().background(androidx.compose.ui.graphics.Color(quads[r * 2 + c]))) }
            }
            // The AI's tile takes the tool's own colours, as its header.
            art == PosterArt.AI -> androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(LocalToolIdentity.current.brush), contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(30.dp)) }
        }
    }
}

/** One of a few looks to choose from: a small picture ([content]) with its name under; the chosen one framed in the tool's colour. */
@Composable
private fun ChoiceTile(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val id = LocalToolIdentity.current
    val side = 76.dp
    Column(
        Modifier.width(side + 8.dp).clip(RoundedCornerShape(20.dp)).alpha(if (enabled) 1f else 0.4f)
            .selectable(selected = on, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(side).then(if (on) Modifier.border(3.dp, id.tint(), RoundedCornerShape(18.dp)) else Modifier).padding(if (on) 6.dp else 0.dp)
                .clip(RoundedCornerShape(if (on) 12.dp else 16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { content() }
        Text(
            label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) id.tint() else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The "paste a link" choice next to the services in a source picker. */
private const val LINK_TILE = "link:"

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
 * [step]: its number among the page's steps, when the page counts them.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistSource(label: String, initialLink: String?, snackbar: SnackbarHostState, memory: String? = null, step: Int? = null, onPick: (CardPick?) -> Unit) {
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
    var expanded by remember { mutableStateOf(false) }

    fun choose(p: CardPick?) { picked = p; PickMemory.set(memory, p); onPick(p) }
    LaunchedEffect(Unit) { picked?.let(onPick) }

    suspend fun resolve(text: String) {
        busy = true
        val ref = LinkResolver.resolve(ctx, text)
        val named = ref?.let { com.xlollx.songport.providers.linkProvider(ctx, it.providerId) }
        val provider = ref?.let { r ->
            named?.takeIf { it.canRead(ctx, r.playlistId) }
                ?: Providers.connectors().firstOrNull { it.familyName == named?.familyName && it.canRead(ctx, r.playlistId) }
        }
        when {
            ref == null -> snackbar.showSnackbar(ctx.getString(R.string.link_invalid))
            provider == null -> snackbar.showSnackbar(ctx.getString(R.string.link_not_connected, named?.displayName ?: ref.providerId))
            else -> {
                val got = runCatching { provider.playlistInfo(ctx, ref.playlistId) }
                val failed = got.exceptionOrNull()?.takeIf { provider.reportsLinkErrors(ref.playlistId) && it !is kotlinx.coroutines.CancellationException }
                if (failed != null) snackbar.showSnackbar(failed.message ?: ctx.getString(R.string.error_generic))
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
        lists = try { p.libraryEntries(ctx, asTarget = false) + p.playlists(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)); emptyList()
        }
    }

    ToolSection(label, step = step) {
        run {
            val linkLabel = stringResource(R.string.card_source_link)
            ServiceTiles(
                listOf(ServiceTile(LINK_TILE, linkLabel, icon = Icons.Filled.Link)) + connected.map { ServiceTile(it.id, it.label(ctx), it) },
                selected = mode ?: LINK_TILE,
                onSelect = { key -> mode = if (key == LINK_TILE) null else key; choose(null) },
            )
            if (mode == null) {
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.card_source_link_hint)) },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                        else TextButton(enabled = link.isNotBlank(), onClick = { scope.launch { resolve(link) } }) { Text(stringResource(R.string.link_use)) }
                    },
                )
            } else {
                val items = lists
                if (items == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading)) }
                else ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = picked?.playlist?.name ?: "", onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(R.string.editor_playlist)) }, placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                expanded = false
                                connected.firstOrNull { it.id == mode }?.let { choose(CardPick(it, p)) }
                            })
                        }
                    }
                }
            }
            picked?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderDot(p.provider); Spacer(Modifier.width(6.dp))
                    Text(p.playlist.name.ifBlank { stringResource(R.string.card_unnamed_playlist) } + (if (p.playlist.trackCount >= 0) "  ·  ${p.playlist.trackCount}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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

    // The two playlists side by side in a wide window, as the pair they are.
    ToolSplit(
        start = { PlaylistSource(stringResource(R.string.card_compat_first), initialLinks?.first, snackbar, step = 1) { a = it } },
        end = { PlaylistSource(stringResource(R.string.card_compat_second), initialLinks?.second, snackbar, step = 2) { b = it } },
    )
    ToolPrimaryButton(
        stringResource(if (result == null) R.string.card_compat_run else R.string.card_again), onClick = { run() },
        icon = Icons.Filled.JoinInner, enabled = a != null && b != null, busy = busy,
    )
    if (busy && result == null) ToolLoading(stringResource(R.string.loading))
    error?.let { ToolError(it, onRetry = if (a != null && b != null) ({ run() }) else null) }
    val r = result ?: return
    val id = LocalToolIdentity.current
    val nameA = a?.playlist?.name.orEmpty().ifBlank { stringResource(R.string.card_unnamed_playlist) }
    val nameB = b?.playlist?.name.orEmpty().ifBlank { stringResource(R.string.card_unnamed_playlist) }
    val subtitle = stringResource(R.string.card_compat_subtitle, r.common.size, r.sharedArtists.size)
    ToolSectionTitle(stringResource(R.string.discover_result), step = 3)
    ToolSplit(
        start = {
            // The pair as two circles: the first playlist's with a whole line, the second's dashed.
            ToolResultCard {
                val venn = remember(r) { com.xlollx.songport.sync.LibraryOverlap.venn(r.sizeA, r.sizeB, r.common.size) }
                VennDrawing(venn, id.tint(), id.second(), Modifier.fillMaxWidth().height(140.dp).clearAndSetSemantics {})
                VennKey(nameA, nameB, id.tint(), id.second())
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${r.score}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = id.tint())
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(stringResource(R.string.card_compat_common_pct, r.commonPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (bitmaps != null || busy) ToolShare(bitmaps, "trackhop-compatibility", "${r.score}%. $subtitle")
        },
        end = {
            if (r.common.isNotEmpty()) ToolSection(stringResource(R.string.card_compat_in_common), trailing = { Text("${r.common.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                ToolTrackList(r.common.map { it.a }, first = 8)
                // The songs in common as a playlist of their own, synced wherever the user chooses.
                if (onMakeShared != null) {
                    Text(stringResource(R.string.discover_compat_shared_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        onMakeShared(ctx.getString(R.string.discover_compat_shared_name, a?.playlist?.name.orEmpty(), b?.playlist?.name.orEmpty()), r.common.map { it.a })
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.discover_compat_shared)) }
                }
            }
            if (r.sharedArtists.isNotEmpty()) ToolSection(stringResource(R.string.card_compat_artists)) {
                Text(r.sharedArtists.take(12).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            Suggestions(stringResource(R.string.card_compat_for, nameA), r.forA)
            Suggestions(stringResource(R.string.card_compat_for, nameB), r.forB)
        },
    )
}

@Composable
private fun Suggestions(title: String, tracks: List<Track>) {
    if (tracks.isEmpty()) return
    ToolSection(title) { ToolTrackList(tracks.take(5), first = 5) }
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
    // "Make it again" and "another version" turn this: other picks, another drawing.
    var variant by remember { mutableStateOf(0) }
    var drawing by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveCover = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val b = drawn
        if (uri != null && b != null) Cards.save(ctx, b, uri)
    }

    fun run(nextVariant: Boolean = false, newCover: Boolean = false) {
        val p = pick ?: return
        if (nextVariant) variant++
        val v = variant
        val chosen = art
        busy = true; bitmaps = null; error = null
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
                            val shown = drawnByAi ?: generated(if (picks(4).size >= 4) GeneratedCovers.Style.MOSAIC else GeneratedCovers.Style.GRADIENT)
                            drawn = shown
                            listOf(shown)
                        }
                        chosen.style != null -> generated(chosen.style).also { drawn = it }.let { listOf(it) }
                        else -> {
                            // The playlist's own picture, else the covers of its most present artists,
                            // else (nothing found) a cover drawn from its title.
                            info.imageUrl?.let { url -> Cards.bitmap(url, 900)?.let { return@withContext listOf(it) } }
                            picks(4).ifEmpty { listOf(generated(GeneratedCovers.Style.GRADIENT)) }
                        }
                    }
                }
                qr = qrUrl(ctx, p)
                val card = Cards.PosterCard(
                    title = title,
                    owner = if (info.ownedByMe) p.provider.accountName(ctx).orEmpty() else "",
                    // The service by its plain name: the caption goes on a wall, not the connector's label.
                    service = p.provider.brand,
                    trackCount = tracks.size, durationMs = tracks.sumOf { it.durationMs },
                    covers = covers, qr = qr,
                )
                bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.poster(ctx, card, it) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.error_generic)
            }
            busy = false
        }
    }

    ToolSplit(
        start = {
            PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "poster", step = 1) { pick = it; bitmaps = null; drawn = null; variant = 0; error = null }
            ToolSection(stringResource(R.string.card_cover_title), step = 2) {
                CoverArtChoice(art, ai, aiSupport, enabled = !busy) {
                    art = it; bitmaps = null; drawn = null
                    runCatching { posterPrefs(ctx).edit().putString("art", it.name).apply() }
                }
            }
            ToolPrimaryButton(
                stringResource(if (bitmaps == null) R.string.card_poster_run else R.string.card_again), onClick = { run(nextVariant = bitmaps != null) },
                icon = Icons.Filled.Brush, enabled = pick != null, busy = busy,
            )
        },
        end = {
            if (busy) ToolLoading(stringResource(if (drawing) R.string.card_ai_drawing else R.string.loading))
            error?.let { ToolError(it, onRetry = if (pick != null) ({ run() }) else null) }
            // The poster on a sheet of paper, as it would hang on a wall.
            if (bitmaps != null) ToolShare(bitmaps, "trackhop-poster", pick?.playlist?.name, matte = androidx.compose.ui.graphics.Color(0xFFF4EFE6)) {
                if (qr == null) Text(stringResource(R.string.card_poster_no_qr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (art != PosterArt.COVERS && drawn != null) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (art == PosterArt.AI) OutlinedButton(enabled = !busy, onClick = { run(newCover = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_ai_again)) }
                        else OutlinedButton(enabled = !busy, onClick = { run(nextVariant = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_cover_another)) }
                        TextButton(onClick = { saveCover.launch("trackhop-cover.png") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_ai_save)) }
                    }
                }
            }
        },
    )
}

/**
 * The poster's art: the playlist's covers, a cover drawn on the phone (five styles), or the
 * user's AI, as chips. "Your AI" is on only when the configured AI can make images; the line under
 * the chips says what the choice does, and why the AI is off when it is.
 */
@Composable
private fun CoverArtChoice(selected: PosterArt, ai: AiClient.Config?, support: AiClient.ImageSupport?, enabled: Boolean, onChange: (PosterArt) -> Unit) {
    val aiOk = ai != null && support == AiClient.ImageSupport.YES
    val aiNote = when {
        ai == null -> stringResource(R.string.card_ai_cover_none)
        support == AiClient.ImageSupport.YES -> stringResource(R.string.card_ai_cover_yes, ai.vendor.label)
        support == AiClient.ImageSupport.NEEDS_IMAGE_MODEL -> stringResource(R.string.card_ai_cover_model)
        ai.vendor == AiClient.Vendor.ANTHROPIC -> stringResource(R.string.card_ai_cover_claude)
        else -> stringResource(R.string.card_ai_cover_no_images, ai.vendor.label)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PosterArt.entries.forEach { a ->
                FilterChip(
                    selected = a == selected, enabled = enabled && (a != PosterArt.AI || aiOk),
                    onClick = { onChange(a) }, label = { Text(stringResource(a.label)) },
                )
            }
        }
        val note = when {
            selected == PosterArt.COVERS -> stringResource(R.string.card_cover_covers_note)
            selected == PosterArt.AI -> aiNote
            else -> stringResource(R.string.card_cover_drawn_note)
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!aiOk) Text(aiNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "paste a link" choice next to the services in a source picker. */
private const val LINK_TILE = "link:"

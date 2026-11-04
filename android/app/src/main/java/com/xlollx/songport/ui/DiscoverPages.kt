@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xlollx.songport.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.AiRoast
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.entries
import com.xlollx.songport.providers.isConnected
import com.xlollx.songport.providers.libraryEntries
import com.xlollx.songport.providers.playlists
import com.xlollx.songport.providers.tracks
import com.xlollx.songport.sync.LibraryOverlap
import com.xlollx.songport.sync.ServiceSearch
import com.xlollx.songport.sync.UniversalLink
import com.xlollx.songport.sync.Vanished
import com.xlollx.songport.sync.Wrapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The user's music on one service as Discover reads it: liked songs and their own playlists, read
 * from the service like a backup reads them and kept in memory for a quarter of an hour, so the
 * Wrapped and "Two subscriptions?" do not read everything twice. Nothing is written anywhere.
 */
internal object LibraryReader {
    /** Playlists read per service at most: enough for a portrait, without hours of reading. */
    private const val MAX_PLAYLISTS = 60
    private const val KEEP_MS = 15 * 60_000L
    private val cache = HashMap<String, Pair<Long, List<Track>>>()

    /** The connected music services (not the scrobblers, not files). */
    fun services(ctx: Context): List<MusicProvider> =
        Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" }

    /** [onProgress] gets (playlists read, playlists to read). A playlist that cannot be read is skipped. */
    suspend fun read(ctx: Context, p: MusicProvider, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Track> {
        synchronized(cache) { cache[p.id]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second } }
        val liked = p.libraryEntries(ctx, asTarget = false).filter { it.id == MusicProvider.LIKED_ID }
        val lists = (liked + p.playlists(ctx).filter { it.ownedByMe }).take(MAX_PLAYLISTS)
        val out = ArrayList<Track>()
        lists.forEachIndexed { i, pl ->
            onProgress(i, lists.size)
            try { out += p.tracks(ctx, pl.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        onProgress(lists.size, lists.size)
        synchronized(cache) { cache[p.id] = System.currentTimeMillis() to out }
        return out
    }
}

private fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]

private fun errorText(ctx: Context, e: Exception) = e.message ?: ctx.getString(R.string.error_generic)

@Composable
private fun Intro(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NoServices(text: String, onGoToAccounts: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onGoToAccounts, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.tools_go_accounts))
    }
}

// ---------------------------------------------------------------------------------------- Wrapped

/** "Your Wrapped, whenever you want": the figures of [Wrapped] on a 9:16 card for the stories. */
@Composable
internal fun WrappedPage(snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { LibraryReader.services(ctx) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var card by remember { mutableStateOf<Cards.WrappedCard?>(null) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    var noAge by remember { mutableStateOf(false) }
    // The reading, service by service, for the loading view: (playlists read, playlists to read, songs).
    var reading by remember { mutableStateOf<Map<String, Triple<Int, Int, Int>>>(emptyMap()) }
    var current by remember { mutableStateOf<String?>(null) }

    fun run() {
        busy = true; bitmaps = null; card = null; reading = emptyMap(); current = null
        scope.launch {
            try {
                val all = ArrayList<Track>()
                val used = ArrayList<String>()
                for (p in services) {
                    current = p.id
                    val got = LibraryReader.read(ctx, p) { done, total ->
                        step = ctx.getString(R.string.discover_reading, p.displayName, done, total)
                        reading = reading + (p.id to Triple(done, total, reading[p.id]?.third ?: 0))
                    }
                    reading = reading + (p.id to Triple(reading[p.id]?.second ?: 1, reading[p.id]?.second ?: 1, got.size))
                    if (got.isNotEmpty()) { all += got; if (p.displayName !in used) used += p.displayName }
                }
                current = null
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val s = withContext(Dispatchers.Default) { Wrapped.compute(all, year) }
                if (s.trackCount == 0) { snackbar.showSnackbar(ctx.getString(R.string.discover_wrapped_empty)); busy = false; return@launch }
                val nf = NumberFormat.getIntegerInstance(locale(ctx))
                val rows = buildList {
                    s.topArtist?.let { add(ctx.getString(R.string.discover_wrapped_top_artist) to it) }
                    s.topDecade?.let { add(ctx.getString(R.string.discover_wrapped_decade) to ctx.getString(R.string.discover_wrapped_decade_value, it)) }
                    // No genre row: the services do not give genres with the tracks the app reads.
                    add(ctx.getString(R.string.discover_wrapped_tracks) to nf.format(s.trackCount))
                    if (used.isNotEmpty()) add(ctx.getString(R.string.discover_wrapped_services) to used.joinToString(" · "))
                }
                noAge = s.age == null
                val c = Cards.WrappedCard(
                    eyebrow = ctx.getString(R.string.discover_wrapped_eyebrow, year),
                    headline = s.age?.let { ctx.getString(R.string.discover_wrapped_age, it) }
                        ?: ctx.resources.getQuantityString(R.plurals.card_tracks, s.trackCount, s.trackCount),
                    subline = s.bornIn?.let { ctx.getString(R.string.discover_wrapped_born, it) },
                    rows = rows,
                )
                card = c
                bitmaps = withContext(Dispatchers.Default) { mapOf(Cards.Format.STORY to Cards.wrapped(ctx, c, Cards.Format.STORY)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(errorText(ctx, e))
            }
            busy = false
        }
    }

    Intro(stringResource(R.string.discover_wrapped_intro))
    if (services.isEmpty()) { NoServices(stringResource(R.string.discover_no_services), onGoToAccounts); return }
    if (busy) WrappedLoading(services, reading, current)
    card?.let { c ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                c.subline?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                c.rows.forEach { (l, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(l, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (noAge) Text(stringResource(R.string.discover_wrapped_no_age), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!busy) CardActions(
        bitmaps, "trackhop-wrapped", busy, onRender = { run() },
        renderLabel = stringResource(if (card == null) R.string.discover_wrapped_button else R.string.card_again),
        previewDescription = card?.let { listOf(it.headline, it.subline.orEmpty()).joinToString(". ") },
        formats = listOf(Cards.Format.STORY),
    )
}

// ---------------------------------------------------------------------------------- Universal link

/** A playlist (or one of its songs) as a trackhop.org/open link that opens on anyone's service. */
@Composable
internal fun UniversalLinkPage(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var built by remember { mutableStateOf<UniversalLink.Built?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(pick) {
        tracks = null; built = null
        val p = pick ?: return@LaunchedEffect
        busy = true
        try {
            val t = p.provider.tracks(ctx, p.playlist.id)
            tracks = t
            built = withContext(Dispatchers.Default) { UniversalLink.build(p.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }, t) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            snackbar.showSnackbar(errorText(ctx, e))
        }
        busy = false
    }

    Intro(stringResource(R.string.discover_link_intro))
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar) { pick = it }
    if (busy) Busy(stringResource(R.string.loading))
    val b = built
    val name = pick?.playlist?.name.orEmpty()
    if (b != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (b.capped) stringResource(R.string.discover_link_capped, b.included, b.total)
                    else stringResource(R.string.discover_link_ready, b.included),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { shareText(ctx, ctx.getString(R.string.discover_link_message, name, b.url)) }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.discover_link_share))
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(b.url))
                        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.discover_link_copied)) }
                    }) { Text(stringResource(R.string.discover_link_copy)) }
                }
            }
        }
    }
    val list = tracks.orEmpty().filter { it.kind.isEmpty() }
    if (list.isNotEmpty()) {
        Text(stringResource(R.string.discover_link_one_song), style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                list.take(50).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { shareText(ctx, ctx.getString(R.string.discover_link_message, t.toString(), UniversalLink.buildTrack(t).url)) }) {
                            Icon(Icons.Filled.Share, stringResource(R.string.discover_link_share_song, t.title))
                        }
                    }
                }
                if (list.size > 50) Text(stringResource(R.string.preview_more, list.size - 50), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 6.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Roast

/** "Roast of a playlist": the user's AI teases a playlist from a short summary of it ([AiRoast]). */
@Composable
internal fun RoastPage(snackbar: SnackbarHostState, ai: AiClient.Config?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var busy by remember { mutableStateOf(false) }
    var roast by rememberSaveable { mutableStateOf<String?>(null) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }

    if (ai == null) { Intro(stringResource(R.string.discover_roast_no_ai)); return }

    fun run() {
        val p = pick ?: return
        busy = true; bitmaps = null
        scope.launch {
            try {
                val tracks = p.provider.tracks(ctx, p.playlist.id)
                val name = p.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }
                val language = locale(ctx).getDisplayLanguage(Locale.ENGLISH)
                val text = AiRoast.roast(ai, name, tracks, language)
                roast = text
                val eyebrow = ctx.getString(R.string.discover_roast_title)
                bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.roast(ctx, eyebrow, name, text, it) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(errorText(ctx, e))
            }
            busy = false
        }
    }

    Intro(stringResource(R.string.discover_roast_intro, ai.vendor.label))
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar) { pick = it; roast = null; bitmaps = null }
    roast?.let { text ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔥 " + (pick?.playlist?.name.orEmpty()), style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { shareText(ctx, text + "\n\ntrackhop.org") }) { Text(stringResource(R.string.discover_roast_share_text)) }
            }
        }
    }
    CardActions(
        bitmaps, "trackhop-roast", busy || pick == null, onRender = { run() },
        renderLabel = stringResource(if (roast == null) R.string.discover_roast_run else R.string.discover_roast_again),
        previewDescription = roast,
    )
}

// --------------------------------------------------------------------------------- Vanished songs

/** A song gone from a playlist, with where it was and when it was last there. */
private class GoneRow(val track: Track, val where: String, val at: Long, val bySync: Boolean)

/**
 * "Vanished songs": what left the user's playlists, from what the phone already keeps: the dated
 * backup copies of each playlist ([Vanished]) and the tracks mirror syncs removed (their reports).
 * Each one can be searched on the big services' sites.
 */
@Composable
internal fun VanishedPage(onBackup: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<GoneRow>?>(null) }
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val out = ArrayList<GoneRow>()
            val groups = runCatching { LocalFilesProvider.entries(ctx) }.getOrDefault(emptyList())
                .filter { it.service != null && it.stamped }.groupBy { it.base }
            for ((_, versions) in groups) {
                if (versions.size < 2) continue
                val copies = versions.mapNotNull { v -> runCatching { Vanished.Version(v.writtenAt, LocalFilesProvider.tracks(ctx, v.id)) }.getOrNull() }
                val first = versions.first()
                Vanished.between(first.title, first.service, copies).forEach { g ->
                    out += GoneRow(g.track, listOfNotNull(g.playlist, g.service).joinToString(" · "), g.lastSeen, bySync = false)
                }
            }
            // Mirror syncs' removals: the source lost those songs, so the sync took them off its target too.
            for (r in Store.get(ctx).data.reports) for (t in r.removedTracks) out += GoneRow(t, r.jobName, r.startedEpoch, bySync = true)
            out.distinctBy { Wrapped.key(it.track) + "|" + it.where }.sortedByDescending { it.at }.take(300)
        }
    }

    Intro(stringResource(R.string.discover_vanished_intro))
    val list = rows
    when {
        list == null -> Busy(stringResource(R.string.loading))
        list.isEmpty() -> {
            Text(stringResource(R.string.discover_vanished_none), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBackup) { Text(stringResource(R.string.discover_vanished_backup)) }
        }
        else -> {
            Text(ctx.resources.getQuantityString(R.plurals.card_tracks, list.size, list.size), style = MaterialTheme.typography.titleSmall)
            val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, locale(ctx)) }
            Card(Modifier.fillMaxWidth()) {
                Column {
                    list.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(g.track.artistLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val date = df.format(Date(g.at))
                                Text(
                                    if (g.bySync) stringResource(R.string.discover_vanished_by_sync, g.where, date) else stringResource(R.string.discover_vanished_from, g.where, date),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SearchOnMenu(g.track)
                        }
                    }
                }
            }
        }
    }
}

/** "Search on…": the track on one of the big services' search pages. */
@Composable
private fun SearchOnMenu(t: Track) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.discover_search_on)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val q = ServiceSearch.query(t.title, t.artistLine)
            ServiceSearch.SITES.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { open = false; openUrl(ctx, s.url(q)) })
            }
        }
    }
}

// ---------------------------------------------------------------------------- Two subscriptions?

private fun pricePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("discover_prices", Context.MODE_PRIVATE)

/**
 * "Two subscriptions?": how much of the music on two services overlaps ([LibraryOverlap]), and
 * what both cost at the prices the user types (kept on the phone, never ours). It measures; it
 * never says which one to keep.
 */
@Composable
internal fun SubscriptionsPage(snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { LibraryReader.services(ctx) }
    if (services.size < 2) { Intro(stringResource(R.string.discover_subs_intro)); NoServices(stringResource(R.string.discover_subs_need_two), onGoToAccounts); return }
    var aId by rememberSaveable { mutableStateOf(services[0].id) }
    var bId by rememberSaveable { mutableStateOf(services[1].id) }
    val a = services.firstOrNull { it.id == aId } ?: services[0]
    val b = services.firstOrNull { it.id == bId } ?: services[1]
    var priceA by remember(a.serviceId) { mutableStateOf(pricePrefs(ctx).getString(a.serviceId, "").orEmpty()) }
    var priceB by remember(b.serviceId) { mutableStateOf(pricePrefs(ctx).getString(b.serviceId, "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Triple<MusicProvider, MusicProvider, LibraryOverlap.Result>?>(null) }

    fun run() {
        busy = true; result = null
        scope.launch {
            try {
                val ta = LibraryReader.read(ctx, a) { d, t -> step = ctx.getString(R.string.discover_reading, a.displayName, d, t) }
                val tb = LibraryReader.read(ctx, b) { d, t -> step = ctx.getString(R.string.discover_reading, b.displayName, d, t) }
                val store = Store.get(ctx)
                val r = withContext(Dispatchers.Default) { LibraryOverlap.compute(ta, tb) { t -> store.cachedMatch(a.id, t.id, b.id) } }
                result = Triple(a, b, r)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(errorText(ctx, e))
            }
            busy = false
        }
    }

    Intro(stringResource(R.string.discover_subs_intro))
    @Composable
    fun Side(label: String, selected: MusicProvider, onSelect: (String) -> Unit, price: String, onPrice: (String) -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                ServicePicker(services, selected.id, onSelect)
                OutlinedTextField(
                    value = price, onValueChange = { v -> onPrice(v); pricePrefs(ctx).edit().putString(selected.serviceId, v).apply() },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discover_subs_price, selected.displayName)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
    }
    Side(stringResource(R.string.discover_subs_first), a, { aId = it; result = null }, priceA) { priceA = it }
    Side(stringResource(R.string.discover_subs_second), b, { bId = it; result = null }, priceB) { priceB = it }
    val same = a.id == b.id
    if (same) Text(stringResource(R.string.discover_subs_same), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !busy && !same, onClick = { run() }) { Text(stringResource(R.string.discover_subs_run)) }
        if (busy) { Spacer(Modifier.width(12.dp)); Busy(step) }
    }
    result?.let { (pa, pb, r) ->
        val nf = remember { NumberFormat.getIntegerInstance(locale(ctx)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${r.percent}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.discover_subs_overlap), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.discover_subs_counts, nf.format(r.common.size), nf.format(r.onlyA.size), pa.displayName, nf.format(r.onlyB.size), pb.displayName),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val pA = LibraryOverlap.parsePrice(priceA)
                val pB = LibraryOverlap.parsePrice(priceB)
                if (pA != null && pB != null) {
                    val money = NumberFormat.getNumberInstance(locale(ctx)).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
                    Text(stringResource(R.string.discover_subs_cost, money.format(pA + pB)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.discover_subs_cost_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OnlyOn(stringResource(R.string.discover_subs_only, pa.displayName), r.onlyA)
                OnlyOn(stringResource(R.string.discover_subs_only, pb.displayName), r.onlyB)
            }
        }
    }
}

@Composable
private fun OnlyOn(title: String, tracks: List<Track>) {
    if (tracks.isEmpty()) return
    var all by remember { mutableStateOf(false) }
    Spacer(Modifier.size(4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    val shown = if (all) tracks.take(200) else tracks.take(8)
    shown.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (!all && tracks.size > 8) TextButton(onClick = { all = true }) { Text(stringResource(R.string.preview_more, tracks.size - 8)) }
}


/**
 * The Wrapped being made: a card in the story's colours with a pulsing spark, a line that changes
 * every couple of seconds, one row per service (its logo, a bar while it is read, a tick and the songs
 * found once done) and the songs found so far.
 */
@Composable
private fun WrappedLoading(services: List<MusicProvider>, reading: Map<String, Triple<Int, Int, Int>>, current: String?) {
    val lines = androidx.compose.ui.res.stringArrayResource(R.array.discover_wrapped_loading_lines)
    var line by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2200); line = (line + 1) % lines.size } }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "spark")
    val scale by pulse.animateFloat(0.85f, 1.15f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "scale")
    val songs = reading.values.sumOf { it.third }
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(
        androidx.compose.ui.graphics.Color(0xFF5B3FD9), androidx.compose.ui.graphics.Color(0xFFC2185B), androidx.compose.ui.graphics.Color(0xFFFF7043)))
    val white = androidx.compose.ui.graphics.Color.White
    Box(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(brush).padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = white, modifier = Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                Spacer(Modifier.width(12.dp))
                androidx.compose.animation.AnimatedContent(targetState = line, label = "line") { i ->
                    Text(lines[i], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = white)
                }
            }
            services.forEach { p ->
                val r = reading[p.id]
                val done = r != null && current != p.id && (r.first >= r.second)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceLogo(p, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, style = MaterialTheme.typography.bodyMedium, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            done -> Text(pluralStringResource(R.plurals.card_tracks, r!!.third, r.third), style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.8f))
                            current == p.id && r != null && r.second > 0 -> androidx.compose.material3.LinearProgressIndicator(
                                progress = { r.first.toFloat() / r.second }, color = white, trackColor = white.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            current == p.id -> androidx.compose.material3.LinearProgressIndicator(color = white, trackColor = white.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            else -> Text(stringResource(R.string.discover_wrapped_waiting), style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.6f))
                        }
                    }
                    if (done) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.CheckCircle, null, tint = white) }
                }
            }
            if (songs > 0) Text(stringResource(R.string.discover_wrapped_found, songs), style = MaterialTheme.typography.labelLarge, color = white)
        }
    }
}

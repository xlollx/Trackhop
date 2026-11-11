@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.xlollx.songport.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Flag
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
import com.xlollx.songport.providers.playlistInfo
import com.xlollx.songport.providers.playlists
import com.xlollx.songport.providers.tracks
import com.xlollx.songport.sync.ServiceSearch
import com.xlollx.songport.sync.UniversalLink
import com.xlollx.songport.sync.Vanished
import com.xlollx.songport.sync.Wrapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

internal fun discoverLocale(ctx: Context): Locale = ctx.resources.configuration.locales[0]
private fun locale(ctx: Context): Locale = discoverLocale(ctx)

internal fun discoverErrorText(ctx: Context, e: Exception) = e.message ?: ctx.getString(R.string.error_generic)
private fun errorText(ctx: Context, e: Exception) = discoverErrorText(ctx, e)

/** No service to read from: said in the tool's empty state, with the way to Accounts. */
@Composable
internal fun NoServices(text: String, onGoToAccounts: () -> Unit) {
    ToolEmpty(text, icon = Icons.Filled.AccountCircle) {
        Button(onClick = onGoToAccounts, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tools_go_accounts))
        }
    }
}

// ---------------------------------------------------------------------------------------- Wrapped

/** A connected service as [Wrapped] knows it. */
private fun connector(p: MusicProvider) = Wrapped.Connector(p.id, p.serviceId, p.displayName, p.familyName, p.brandColor)

private fun songLine(s: Wrapped.Song, withYear: Boolean) = buildString {
    append(s.title)
    if (s.artist.isNotBlank()) append(" – ").append(s.artist)
    if (withYear && s.year > 0) append(" (").append(s.year).append(')')
}

/** The headline of [w]: the musical age, or the songs when the services did not say when they came out. */
private fun wrappedHeadline(ctx: Context, w: Wrapped.Saved): String =
    w.age?.let { ctx.getString(R.string.discover_wrapped_age, it) }
        ?: ctx.resources.getQuantityString(R.plurals.card_tracks, w.trackCount, w.trackCount)
            .replace(w.trackCount.toString(), NumberFormat.getIntegerInstance(locale(ctx)).format(w.trackCount))

/** [w] as the card and the page show it, in the phone's language. */
internal fun wrappedCard(ctx: Context, w: Wrapped.Saved): Cards.WrappedCard {
    val nf = NumberFormat.getIntegerInstance(locale(ctx))
    val res = ctx.resources
    val tiles = buildList {
        add(nf.format(w.trackCount) to ctx.getString(R.string.discover_wrapped_tracks))
        if (w.artistCount > 0) add(nf.format(w.artistCount) to ctx.getString(R.string.discover_wrapped_artists))
        val hours = w.durationMs / 3_600_000
        when {
            hours >= 1 -> add(nf.format(hours) to ctx.getString(R.string.discover_wrapped_hours))
            w.durationMs >= 60_000 -> add(nf.format(w.durationMs / 60_000) to ctx.getString(R.string.discover_wrapped_minutes))
        }
    }
    val facts = buildList {
        w.oldest?.let { add(ctx.getString(R.string.discover_wrapped_oldest) to songLine(it, withYear = true)) }
        w.newest?.takeIf { n -> w.oldest?.let { it.title == n.title && it.artist == n.artist } != true }
            ?.let { add(ctx.getString(R.string.discover_wrapped_newest) to songLine(it, withYear = false)) }
    }
    return Cards.WrappedCard(
        eyebrow = ctx.getString(R.string.discover_wrapped_eyebrow, w.year),
        headline = wrappedHeadline(ctx, w),
        subline = w.bornIn?.let { ctx.getString(R.string.discover_wrapped_born, it) },
        artistsTitle = ctx.getString(R.string.discover_wrapped_top_artists),
        artists = w.topArtists.map { it.name to res.getQuantityString(R.plurals.card_tracks, it.tracks, it.tracks) },
        decadesTitle = ctx.getString(R.string.discover_wrapped_decades),
        decades = w.decades.map { ctx.getString(R.string.discover_wrapped_decade_label, it.decade) to it.percent },
        tiles = tiles,
        facts = facts,
        servicesTitle = ctx.getString(R.string.discover_wrapped_services),
        services = w.services.map { Cards.Mark(it.name, it.color.toInt(), it.serviceId) },
    )
}

/**
 * "Your Wrapped, whenever you want": the figures of [Wrapped] on a card for the stories. The page
 * opens on the last one made (kept in the store with the others, the newest first), lets the user
 * choose which connected services are read for a new one, and lists the older ones to see, share
 * or delete.
 */
@Composable
internal fun WrappedPage(snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val history = data.wrapped
    val services = remember { LibraryReader.services(ctx) }
    val labels = remember(services) { Wrapped.chipLabels(services.map(::connector)) }
    val skipped = data.settings.wrappedSkipped
    val chosenIds = Wrapped.chosen(services.map { it.id }, skipped).toSet()
    // The reading lives in [WrappedRun], not in this page: leaving the page (or the tab) while it
    // reads does not stop it, and the new Wrapped is there when the page is opened again.
    val busy = WrappedRun.busy
    val reading = WrappedRun.reading
    val readingFrom = WrappedRun.readingFrom
    val current = WrappedRun.current
    // A failure or an empty result stays on the page (with Try again) until the next one is made.
    val failed = WrappedRun.error
    val empty = WrappedRun.empty
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = history.firstOrNull { it.id == shownId } ?: history.firstOrNull()
    val card = remember(shown) { shown?.let { wrappedCard(ctx, it) } }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    // The card's art: a cover drawn on the phone from the Wrapped's covers (loaded again from their
    // addresses), in the style chosen; null until the user picks one = mosaic with four covers or
    // more, else the gradient. "Another version" turns the variant.
    var covers by remember { mutableStateOf<List<Bitmap>?>(null) }
    var style by remember(shown?.id) { mutableStateOf<GeneratedCovers.Style?>(null) }
    var variant by remember(shown?.id) { mutableStateOf(0) }
    var rendering by remember { mutableStateOf(false) }
    LaunchedEffect(shown?.id) {
        bitmaps = null; covers = null
        val urls = shown?.covers ?: return@LaunchedEffect
        covers = kotlinx.coroutines.coroutineScope { urls.map { async { Cards.bitmap(it, 400) } }.mapNotNull { it.await() } }
    }
    val artStyle = style ?: if ((covers?.size ?: 0) >= 4) GeneratedCovers.Style.MOSAIC else GeneratedCovers.Style.GRADIENT
    LaunchedEffect(card, covers, artStyle, variant) {
        val c = card ?: return@LaunchedEffect
        val w = shown ?: return@LaunchedEffect
        val got = covers ?: return@LaunchedEffect
        rendering = true
        val art = Cards.WrappedArt(artStyle, GeneratedCovers.Input(c.eyebrow, w.topArtists.map { it.name }, got), variant)
        bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.wrapped(ctx, c, it, art) } }
        rendering = false
    }

    fun run() {
        WrappedRun.error = null; WrappedRun.empty = false
        WrappedRun.start(ctx.applicationContext, services.filter { it.id in chosenIds }) { saved ->
            store.update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, saved)) }
            shownId = saved.id
        }
    }

    fun delete(w: Wrapped.Saved) {
        store.update { d -> d.copy(wrapped = d.wrapped.filter { it.id != w.id }) }
        if (shownId == w.id) shownId = null
        scope.launch {
            val r = snackbar.showSnackbar(ctx.getString(R.string.discover_wrapped_deleted), actionLabel = ctx.getString(R.string.unplayable_undo), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) store.update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, w)) }
        }
    }

    val id = LocalToolIdentity.current
    ToolIntro(stringResource(R.string.discover_wrapped_intro))
    if (services.isEmpty() && history.isEmpty()) { NoServices(stringResource(R.string.discover_no_services), onGoToAccounts); return }
    val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, locale(ctx)) }

    // The Wrapped on show (or the one being made): its figures, the card's art, the card to share.
    val result: @Composable () -> Unit = {
        if (busy) WrappedLoading(readingFrom, reading, current, WrappedRun.coverSearch)
        else if (shown != null && card != null) {
            ToolSectionTitle(stringResource(R.string.discover_wrapped_made_on, df.format(Date(shown.madeAt))))
            WrappedSummary(card, shown)
            // The card's art, as the poster's drawn covers.
            ToolSection(stringResource(R.string.card_cover_title)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PosterArt.entries.forEach { a ->
                        val st = a.style ?: return@forEach
                        FilterChip(selected = st == artStyle, enabled = covers != null, onClick = { style = st }, label = { Text(stringResource(a.label)) })
                    }
                }
                // Always there (greyed while the card is drawn): appearing late, it pushed what is below it down
                // under a finger already on its way.
                OutlinedButton(enabled = bitmaps != null && !rendering, onClick = { variant++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.card_cover_another)) }
            }
            ToolShare(
                bitmaps, "trackhop-wrapped", listOf(card.headline, card.subline.orEmpty()).joinToString(". "),
                busy = bitmaps == null || rendering, formats = listOf(Cards.Format.STORY, Cards.Format.SQUARE),
            )
        }
    }

    // A new one: the services to read, chosen every time (the last choice comes back ticked), then the older ones.
    val make: @Composable () -> Unit = {
        if (services.isEmpty()) NoServices(stringResource(R.string.discover_no_services), onGoToAccounts)
        else {
            ToolSection(stringResource(R.string.discover_wrapped_pick), hint = stringResource(R.string.discover_wrapped_pick_hint)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    services.forEach { p ->
                        val on = p.id in chosenIds
                        FilterChip(
                            selected = on, enabled = !busy,
                            onClick = {
                                if (on && chosenIds.size == 1) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.discover_wrapped_keep_one)) }
                                else store.updateSettings { st ->
                                    // Ids of services no longer connected stay as they were; the others follow the chips.
                                    val others = st.wrappedSkipped - services.map { it.id }.toSet()
                                    val off = services.map { it.id }.filter { it !in chosenIds }.toSet()
                                    st.copy(wrappedSkipped = others + if (on) off + p.id else off - p.id)
                                }
                            },
                            label = { Text(labels[p.id] ?: p.displayName) },
                            leadingIcon = { ServiceLogo(p, size = 20.dp) },
                            // Ticked is said with a tick too, not only with the chip's colour.
                            trailingIcon = if (on) ({ Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)) }) else null,
                        )
                    }
                }
            }
            ToolPrimaryButton(
                stringResource(if (history.isEmpty()) R.string.discover_wrapped_button else R.string.discover_wrapped_new),
                onClick = { run() }, icon = Icons.Filled.AutoAwesome, busy = busy,
            )
            if (failed != null) ToolError(failed, onRetry = if (!busy) ({ run() }) else null)
            else if (empty) ToolError(stringResource(R.string.discover_wrapped_empty), onRetry = if (!busy) ({ run() }) else null)
        }
        if (history.isNotEmpty()) ToolSection(stringResource(R.string.discover_wrapped_history)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                history.forEach { w ->
                    val selected = !busy && w.id == shown?.id
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .background(if (selected) id.container() else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected = selected, enabled = !busy, onClick = { shownId = w.id })
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selected) { Icon(Icons.Filled.CheckCircle, null, tint = id.tint(), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                                Text(df.format(Date(w.madeAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(wrappedHeadline(ctx, w), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            w.topArtist?.let { Text("♪ $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (w.services.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                w.services.forEach { sv ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ServiceLogo(sv.serviceId, sv.color, sv.name, 16.dp); Spacer(Modifier.width(4.dp))
                                        Text(sv.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { delete(w) }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.discover_wrapped_delete))
                        }
                    }
                }
            }
        }
    }

    if (busy || (shown != null && card != null)) ToolSplit(start = { result() }, end = { make() }) else make()
}

/** The Wrapped on the page: the same figures as the card, laid out for reading. */
@Composable
private fun WrappedSummary(card: Cards.WrappedCard, w: Wrapped.Saved) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val id = LocalToolIdentity.current
    ToolResultCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = muted)
                Text(card.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = id.tint())
                card.subline?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (w.age == null) Text(stringResource(R.string.discover_wrapped_no_age), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (card.artists.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.artistsTitle, style = MaterialTheme.typography.titleSmall)
                card.artists.forEachIndexed { i, (name, count) ->
                    val style = if (i == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = style, color = muted, modifier = Modifier.widthIn(min = 28.dp))
                        Text(name, style = style, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(count, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                    }
                }
            }
            if (card.decades.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.decadesTitle, style = MaterialTheme.typography.titleSmall)
                card.decades.forEach { (label, share) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 72.dp))
                        Spacer(Modifier.width(8.dp))
                        LinearProgressIndicator(progress = { share / 100f }, color = id.tint(), trackColor = id.container(), modifier = Modifier.weight(1f).height(8.dp), drawStopIndicator = {})
                        Spacer(Modifier.width(8.dp))
                        Text("$share%", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp))
                    }
                }
            }
            if (card.tiles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                card.tiles.forEach { (value, label) ->
                    Surface(color = id.container(), shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            card.facts.forEach { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (w.services.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.servicesTitle, style = MaterialTheme.typography.labelMedium, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.services.forEach { sv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ServiceLogo(sv.serviceId, sv.color, sv.name, 24.dp); Spacer(Modifier.width(6.dp))
                            Text(sv.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
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
    // One link, or several for a long playlist (each one gets through chat apps on its own).
    var built by remember { mutableStateOf<List<UniversalLink.Built>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    // Reading the playlist failed: said on the page, and "Try again" (which counts up [reads]) reads it again.
    var error by remember { mutableStateOf<String?>(null) }
    var reads by remember { mutableStateOf(0) }

    LaunchedEffect(pick, reads) {
        tracks = null; built = emptyList(); error = null
        val p = pick ?: return@LaunchedEffect
        busy = true
        try {
            val t = p.provider.tracks(ctx, p.playlist.id)
            tracks = t
            built = withContext(Dispatchers.Default) { UniversalLink.buildParts(p.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }, t) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = errorText(ctx, e)
        }
        busy = false
    }

    val id = LocalToolIdentity.current
    val parts = built
    val name = pick?.playlist?.name.orEmpty()
    val list = tracks.orEmpty().filter { it.kind.isEmpty() }
    val tall = Modifier.heightIn(min = 48.dp)
    val link: @Composable () -> Unit = {
        PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "link", step = 1) { pick = it }
        if (busy) ToolLoading(stringResource(R.string.loading))
        error?.let { ToolError(it, onRetry = { reads++ }) }
        if (parts.isNotEmpty()) {
            ToolSectionTitle(stringResource(R.string.discover_link_share), step = 2)
            ToolResultCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(id.icon, null, tint = id.tint()); Spacer(Modifier.width(12.dp))
                    Text(
                        if (parts.size > 1) stringResource(R.string.discover_link_parts, parts.size)
                        else stringResource(R.string.discover_link_ready, parts[0].included),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    )
                }
                parts.forEach { b ->
                    if (parts.size > 1) Text(
                        stringResource(R.string.discover_link_part, b.part, b.parts, b.first, b.last),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp),
                    )
                    val label = if (parts.size > 1) stringResource(R.string.discover_link_part_name, name, b.part, b.parts) else name
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { shareText(ctx, ctx.getString(R.string.discover_link_message, label, b.url)) }, modifier = tall,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = id.accent, contentColor = id.onAccent),
                        ) {
                            Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.discover_link_share))
                        }
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(b.url))
                            scope.launch { snackbar.showSnackbar(ctx.getString(R.string.discover_link_copied)) }
                        }, modifier = tall) { Text(stringResource(R.string.discover_link_copy)) }
                    }
                }
            }
        }
    }
    val songs: @Composable () -> Unit = {
        ToolSection(stringResource(R.string.discover_link_one_song)) {
            Column {
                list.take(50).forEach { t ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { shareText(ctx, ctx.getString(R.string.discover_link_message, t.toString(), UniversalLink.buildTrack(t).url)) }) {
                            Icon(Icons.Filled.Share, stringResource(R.string.discover_link_share_song, t.title), tint = id.tint())
                        }
                    }
                }
            }
            if (list.size > 50) Text(stringResource(R.string.preview_more, list.size - 50), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ToolIntro(stringResource(R.string.discover_link_intro))
    if (list.isNotEmpty()) ToolSplit(start = { link() }, end = { songs() }) else link()
}

// ------------------------------------------------------------------------------------------ Roast

private fun roastReportUrl(ai: AiClient.Config): String {
    val vendor = if (ai.local) "${ai.model} (on device)" else "${ai.vendor.label} (${ai.model})"
    val body = "Roast report\n\nAI: $vendor\nApp: ${versionLine()}\n\n" +
        "<!-- What was offensive about it? Please leave out personal details. -->\n"
    return "https://github.com/xlollx/Trackhop/issues/new?title=" + android.net.Uri.encode("Roast report") + "&body=" + android.net.Uri.encode(body)
}

/** The last roast, its playlist and its card, kept in memory while the app runs. */
private object RoastKept {
    var text by mutableStateOf<String?>(null)
    @Volatile var playlistId: String? = null
    var bitmaps by mutableStateOf<Map<Cards.Format, Bitmap>?>(null)
    /** A roast being written, in the app's scope ([AppWork]): the screen turned, it goes on. */
    var busy by mutableStateOf(false)
    var coverSearch by mutableStateOf(false)
    /** What the last run has to say (refused, failed), not shown yet. */
    var notice by mutableStateOf<String?>(null)
}

/** How long the roast's covers are looked up at most. */
private const val ROAST_COVER_WAIT_MS = 20_000L
private const val ROAST_COVERS = 6

/**
 * "Roast of a playlist": the user's AI (or Gemini Nano on the phone) teases a playlist from a short
 * summary of it ([AiRoast]), at the level chosen. The card takes the playlist's cover and the covers
 * of its top artists, looked up after the roast (best effort: without them, the app's purple).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RoastPage(snackbar: SnackbarHostState, ai: AiClient.Config?, onSetUpAi: () -> Unit = {}) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val settings by store.state.collectAsState()
    var pick by remember { mutableStateOf<CardPick?>(null) }
    // The roast being written, the last one and its card live outside the page for as long as the
    // app runs: another tab and back, or the screen turned, keeps them (and a run goes on).
    var busy by RoastKept::busy
    var coverSearch by RoastKept::coverSearch
    var roast by RoastKept::text
    var bitmaps by RoastKept::bitmaps
    // What the last run had to say (refused, failed) stays on the page until the next one.
    fun forget() { roast = null; bitmaps = null; RoastKept.text = null; RoastKept.bitmaps = null; RoastKept.playlistId = null; RoastKept.notice = null }
    // One roast, as it always was: the levels and the explicit switch were tried and dropped.
    val level = AiRoast.Level.DEFAULT
    val explicit = false
    var askReport by remember { mutableStateOf(false) }
    // Only the Google Play build has the report (its AI-content policy); the full build shows nothing.
    val reportable = com.xlollx.songport.BuildConfig.STORE_BUILD

    // No AI at all: Oli judges the playlists with a look, and the button leads to an AI for words.
    if (ai == null) { OliRoast(onSetUpAi); return }

    fun run() {
        val p = pick ?: return
        val lv = level; val ex = explicit
        busy = true; bitmaps = null; RoastKept.notice = null
        AppWork.scope.launch {
            try {
                val tracks = p.provider.tracks(ctx, p.playlist.id)
                val name = p.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }
                val language = locale(ctx).getDisplayLanguage(Locale.ENGLISH)
                val text = AiRoast.roast(ai, name, tracks, language, lv, ex)
                roast = text
                RoastKept.text = text; RoastKept.playlistId = p.playlist.id
                // The covers: best effort and short, the card is drawn without them too.
                val urls = ArrayList<String>()
                var own: Bitmap? = null
                coverSearch = true
                try {
                    kotlinx.coroutines.withTimeoutOrNull(ROAST_COVER_WAIT_MS) {
                        // Liked songs and the other library lists have no picture: one is drawn instead.
                        if (!MusicProvider.isLibrary(p.playlist.id)) {
                            val url = p.playlist.imageUrl
                                ?: runCatching { p.provider.playlistInfo(ctx, p.playlist.id).imageUrl }.getOrNull()
                            own = url?.takeIf { it.isNotBlank() }?.let { Cards.bitmap(it, 720) }
                        }
                        Cards.distinctCoverUrls(Wrapped.coverCandidates(tracks).map { it to p.provider.serviceId }, ROAST_COVERS, urls)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { coverSearch = false }
                val covers = synchronized(urls) { urls.toList() }.mapNotNull { Cards.bitmap(it, 400) }
                val card = Cards.RoastCard(
                    eyebrow = ctx.getString(R.string.discover_roast_title),
                    badge = null,
                    title = name, text = text, cover = own, covers = covers,
                    artists = com.xlollx.songport.sync.CoverPrompt.topArtists(tracks),
                )
                bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.roast(ctx, card, it) } }
                if (RoastKept.playlistId == p.playlist.id) RoastKept.bitmaps = bitmaps
            } catch (e: CancellationException) { throw e } catch (e: AiRoast.Refused) {
                // A refusal or an empty answer: a friendly word, a lower level when there is one.
                RoastKept.notice = ctx.getString(R.string.discover_roast_refused)
            } catch (e: Exception) {
                RoastKept.notice = errorText(ctx, e)
            } catch (e: Throwable) {
                // An Error (out of memory, a library's own) would close the app: logged and said instead.
                com.xlollx.songport.data.Diagnostics.log(ctx, "roast", com.xlollx.songport.data.CrashLog.describe(e))
                RoastKept.notice = ctx.getString(R.string.error_generic) + " (" + e.javaClass.simpleName + ")"
            }
            busy = false
        }
    }

    val id = LocalToolIdentity.current
    ToolIntro(stringResource(R.string.discover_roast_intro, ai.label))
    val ready = !ai.local || NanoStatus(onUseKey = onSetUpAi)
    if (ai.local) Text(stringResource(R.string.ai_nano_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (ai.local) NanoSmallNote()
    val ask: @Composable () -> Unit = {
        PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "roast", step = 1) {
            // The same playlist coming back (from the memory) keeps its roast; another one clears it.
            if (it == null || it.playlist.id != RoastKept.playlistId) forget()
            pick = it
        }
        // Spinning only while it works: waiting for a playlist or for Nano just greys the button.
        ToolPrimaryButton(
            stringResource(if (roast == null) R.string.discover_roast_run else R.string.discover_roast_again), onClick = { run() },
            icon = id.icon, enabled = pick != null && ready, busy = busy,
        )
        if (busy) ToolLoading(stringResource(if (coverSearch) R.string.discover_wrapped_covers else R.string.discover_roast_writing))
        RoastKept.notice?.let { n -> ToolError(n, onRetry = if (pick != null && ready && !busy) ({ run() }) else null) }
    }
    val verdict: @Composable () -> Unit = {
        roast?.let { text ->
            ToolResultCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(id.icon, null, tint = id.tint()); Spacer(Modifier.width(10.dp))
                    Text(pick?.playlist?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (reportable) IconButton(onClick = { askReport = true }) {
                        Icon(Icons.Outlined.Flag, stringResource(R.string.discover_roast_report), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(text, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { shareText(ctx, text + "\n\ntrackhop.org") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.discover_roast_share_text)) }
            }
            if (askReport) FitDialog(
                onDismissRequest = { askReport = false },
                title = { Text(stringResource(R.string.discover_roast_report_title)) },
                text = { Text(stringResource(R.string.discover_roast_report_body)) },
                confirmButton = { TextButton(onClick = {
                    askReport = false
                    // The roast goes from the screen, and the prefilled report opens.
                    forget()
                    openUrl(ctx, roastReportUrl(ai))
                }) { Text(stringResource(R.string.discover_roast_report_send)) } },
                dismissButton = { TextButton(onClick = { askReport = false }) { Text(stringResource(R.string.cancel)) } },
            )
            // The card: its place is kept while it is drawn, it is not offered when drawing it failed.
            if (bitmaps != null || busy) ToolShare(bitmaps, "trackhop-roast", text)
        }
    }
    if (roast != null) ToolSplit(start = { ask() }, end = { verdict() }) else ask()
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
            // Not those of a playlist that changes by itself (a daylist, Discover Weekly): there songs
            // come and go every day by design, nothing has vanished.
            val data = Store.get(ctx).data
            val rotating = data.jobs.filter { it.followsChanges }.map { it.id }.toSet()
            for (r in data.reports) if (r.jobId !in rotating) for (t in r.removedTracks) out += GoneRow(t, r.jobName, r.startedEpoch, bySync = true)
            out.distinctBy { Wrapped.key(it.track) + "|" + it.where }.sortedByDescending { it.at }.take(300)
        }
    }

    ToolIntro(stringResource(R.string.discover_vanished_intro))
    val list = rows
    val id = LocalToolIdentity.current
    when {
        list == null -> ToolLoading(stringResource(R.string.loading))
        list.isEmpty() -> ToolEmpty(stringResource(R.string.discover_vanished_none)) {
            Button(
                onClick = onBackup, modifier = Modifier.heightIn(min = 48.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = id.accent, contentColor = id.onAccent),
            ) { Text(stringResource(R.string.discover_vanished_backup)) }
        }
        else -> {
            ToolSectionTitle(ctx.resources.getQuantityString(R.plurals.card_tracks, list.size, list.size))
            val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM, locale(ctx)) }
            // Two columns of them in a wide window; each song struck through and faded: it is gone.
            val columns = if (LocalWindowLayout.current.width == WidthClass.COMPACT) 1 else 2
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                val per = (list.size + columns - 1) / columns
                list.chunked(per.coerceAtLeast(1)).forEach { chunk ->
                    Surface(Modifier.weight(1f), shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column {
                            chunk.forEachIndexed { i, g ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                GoneSong(g, df.format(Date(g.at)))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A song that left: its title struck through, faded, with where it was last seen and where to look for it. */
@Composable
private fun GoneSong(g: GoneRow, date: String) {
    val id = LocalToolIdentity.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(id.icon, null, tint = id.tint().copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                g.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Text(g.track.artistLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
            Text(
                if (g.bySync) stringResource(R.string.discover_vanished_by_sync, g.where, date) else stringResource(R.string.discover_vanished_from, g.where, date),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SearchOnMenu(g.track)
    }
}

/** "Search on…": the track on one of the big services' search pages. */
@Composable
private fun SearchOnMenu(t: Track) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.discover_search_on)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val q = ServiceSearch.query(t.title, t.artistLine)
            ServiceSearch.SITES.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { open = false; openUrl(ctx, s.url(q)) })
            }
        }
    }
}

/**
 * The Wrapped being made: a card in the story's colours with a pulsing spark, a line that changes
 * every couple of seconds, one row per service (its logo, a bar while it is read, a tick and the songs
 * found once done) and the songs found so far.
 */
@Composable
private fun WrappedLoading(services: List<MusicProvider>, reading: Map<String, Triple<Int, Int, Int>>, current: String?, coverSearch: Boolean = false) {
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
            if (coverSearch) Column {
                Text(stringResource(R.string.discover_wrapped_covers), style = MaterialTheme.typography.bodyMedium, color = white)
                androidx.compose.material3.LinearProgressIndicator(color = white, trackColor = white.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}


/** The Wrapped being made, outside any page: it goes on when the page is left, and says how far it is. */
internal object WrappedRun {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    var busy by mutableStateOf(false); private set
    /** Per service: (playlists read, playlists to read, songs). */
    var reading by mutableStateOf<Map<String, Triple<Int, Int, Int>>>(emptyMap()); private set
    var readingFrom by mutableStateOf<List<MusicProvider>>(emptyList()); private set
    var current by mutableStateOf<String?>(null); private set
    /** True while the covers of the top artists are looked up, after the reading. */
    var coverSearch by mutableStateOf(false); private set
    /** A failure or an empty result not yet told to the person (the page says it when it shows). */
    var error by mutableStateOf<String?>(null)
    var empty by mutableStateOf(false)

    /** How long the covers are looked up at most, after the reading. */
    private const val COVER_WAIT_MS = 25_000L

    fun start(ctx: Context, read: List<MusicProvider>, onDone: (Wrapped.Saved) -> Unit) {
        if (busy) return
        busy = true; reading = emptyMap(); current = null; coverSearch = false; readingFrom = read
        scope.launch {
            try {
                val all = ArrayList<Track>()
                val used = ArrayList<MusicProvider>()
                // Which service each song was read from: its covers are looked up there first (YouTube's own pictures).
                val origin = HashMap<Track, String>()
                for (p in read) {
                    current = p.id
                    val got = LibraryReader.read(ctx, p) { done, total ->
                        reading = reading + (p.id to Triple(done, total, reading[p.id]?.third ?: 0))
                    }
                    reading = reading + (p.id to Triple(reading[p.id]?.second ?: 1, reading[p.id]?.second ?: 1, got.size))
                    if (got.isNotEmpty()) { all += got; used += p; for (t in got) origin.putIfAbsent(t, p.serviceId) }
                }
                current = null
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val s = withContext(Dispatchers.Default) { Wrapped.compute(all, year) }
                if (s.trackCount == 0) empty = true
                else {
                    // A few covers of the top artists, for the card's art and colours: best effort and
                    // short, a Wrapped without them is drawn from its title's colours.
                    val covers = ArrayList<String>()
                    coverSearch = true
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(COVER_WAIT_MS) {
                            Cards.distinctCoverUrls(Wrapped.coverCandidates(all).map { it to (origin[it] ?: "") }, Wrapped.MAX_COVERS, covers)
                        }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { coverSearch = false }
                    val saved = Wrapped.save(s, Wrapped.services(used.map(::connector)), year, System.currentTimeMillis(), covers = synchronized(covers) { covers.toList() })
                    // Kept even if the page is gone: the store is the app's, not the page's.
                    Store.get(ctx).update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, saved)) }
                    runCatching { onDone(saved) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = errorText(ctx, e)
            }
            busy = false
        }
    }
}

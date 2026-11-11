package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.ArtistPick
import com.xlollx.songport.sync.LookupCaps
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.Merch
import com.xlollx.songport.sync.Records
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.util.Currency

/**
 * The search going on and the last one found, kept while the app runs: another tab and back, or
 * the screen turned, keeps it (the search goes on and its list shows when it ends).
 */
private object RecordsKept {
    val work = KeptWork<Records.Result>()

    /** Discogs' terms: nothing shown more than six hours older than on Discogs, even with the app left open. */
    fun fresh(): Records.Result? = work.result?.takeIf { System.currentTimeMillis() - work.at < Records.DISCOGS_TTL_MS }
}

/** What the page shows and looks up (All / CD & vinyl / Merch), kept between visits. */
private fun lookPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("records", Context.MODE_PRIVATE)
private fun savedLook(ctx: Context) = Records.Look.of(lookPrefs(ctx).getString("look", null)) ?: Records.Look.ALL

/**
 * Discover › Records and merch: a playlist, then for each of its artists the records on Discogs
 * (CD, vinyl, cassette; with the user's own token, the copies for sale), the merch in their own
 * shops ([Merch]), the official pages MusicBrainz knows (site, shop, Bandcamp, social) and searches
 * on shops. All / CD & vinyl / Merch picks what is shown and looked up. Links only, no affiliate
 * tag; asked only when the user taps.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RecordsPage(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val k = RecordsKept.work
    var pick by remember { mutableStateOf<CardPick?>(null) }
    k.error?.let { e -> LaunchedEffect(e) { snackbar.showSnackbar(e.ifBlank { ctx.getString(R.string.error_generic) }); k.error = null } }
    var tokenOpen by rememberSaveable { mutableStateOf(false) }
    if (tokenOpen) DiscogsTokenDialog { tokenOpen = false }
    // The playlist's artists, read when it is picked: the ones played most, or some chosen.
    var artists by remember { mutableStateOf<List<String>?>(null) }
    val choice = rememberArtistChoice()
    var look by remember { mutableStateOf(savedLook(ctx)) }
    var formats by rememberSaveable { mutableStateOf(emptyList<Records.Format>()) }
    LaunchedEffect(pick?.provider?.id, pick?.playlist?.id) {
        artists = null
        val p = pick ?: return@LaunchedEffect
        choice.forPlaylist(p.provider.id + "|" + p.playlist.id)
        artists = try { Concerts.artistsOf(p.provider.tracks(ctx.env, p.playlist.id)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    // Outside the page (RecordsKept): the screen turned or another tab opened, the search goes on.
    fun run(want: Records.Look = look) {
        val p = pick ?: return
        if (!choice.ready) return
        val env = ctx.env
        val known = artists; val picked = choice.pickedOrNull
        val currency = Records.currencyFor(ctx.resources.configuration.locales[0])
        k.start(p.playlist.id) { progress ->
            val all = known ?: Concerts.artistsOf(p.provider.tracks(env, p.playlist.id))
            Records.find(env, ArtistPick.chosen(all, picked), currency, picked == null, want, progress)
        }
    }

    // Only what is shown is looked up: a part not found yet is looked up when it is picked (the rest comes from the cache).
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val looks = Records.Look.entries
        looks.forEachIndexed { i, l ->
            SegmentedButton(
                selected = look == l, shape = SegmentedButtonDefaults.itemShape(i, looks.size),
                onClick = {
                    look = l
                    lookPrefs(ctx).edit().putString("look", l.name).apply()
                    val shown = RecordsKept.fresh()?.takeIf { pick != null && k.key == pick?.playlist?.id }
                    if (shown != null && !k.running && !shown.look.covers(l)) run(Records.Look.ALL)
                },
            ) { Text(stringResource(when (l) { Records.Look.ALL -> R.string.records_kind_all; Records.Look.RECORDS -> R.string.records_kind_records; Records.Look.MERCH -> R.string.records_kind_merch }), maxLines = 1) }
        }
    }

    Text(stringResource(R.string.records_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "records") {
        if (it == null || it.playlist.id != k.key) k.clear()
        pick = it
    }
    val cap = remember(tokenOpen) { if (Records.token(ctx.env) != null) LookupCaps.RECORDS_TOKEN else LookupCaps.RECORDS_ANON }
    if (pick != null) ArtistChooser(artists, choice, cap)
    val busy = k.running
    val progress = k.progress
    Button(onClick = { run() }, enabled = pick != null && !busy && choice.ready, modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(
            if (pick == null) stringResource(if (look == Records.Look.MERCH) R.string.records_find_merch else R.string.records_find)
            else if (look == Records.Look.MERCH) artistsAction(artists, choice, cap, R.string.records_find_merch, R.plurals.records_find_merch_for, R.string.records_find_merch_top)
            else artistsAction(artists, choice, cap, R.string.records_find, R.plurals.records_find_for, R.string.records_find_top),
        )
    }
    progress?.takeIf { it.second > 0 }?.let { (done, total) ->
        Text(stringResource(R.string.concerts_progress, (done + 1).coerceAtMost(total), total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val r = RecordsKept.fresh()?.takeIf { pick == null || k.key == pick?.playlist?.id }
    if (r != null && !busy) {
        val discogs = look.records && r.look.records
        val notes = buildList {
            if (discogs && r.slowedDown) add(stringResource(R.string.records_slowed))
            if (discogs && r.tokenRefused) add(stringResource(R.string.records_token_refused))
            if (discogs && !r.token) add(stringResource(R.string.records_no_token))
            if (r.total > r.artists.size) add(stringResource(R.string.artists_capped, r.artists.size, r.total))
        }
        if (notes.isNotEmpty()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                if (discogs) TextButton(onClick = { tokenOpen = true }) { Text(stringResource(R.string.records_token_title)) }
            }
        }
        if (discogs) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Records.Format.entries.forEach { f ->
                FilterChip(selected = f in formats, onClick = { formats = if (f in formats) formats - f else formats + f }, label = { Text(stringResource(formatLabel(f))) })
            }
        }
        var query by rememberSaveable { mutableStateOf("") }
        if (r.artists.size > 5) ArtistSearchField(query) { query = it }
        ArtistPick.named(r.artists, query) { it.artist }.forEach { a -> ArtistRecordsCard(a, r.slowedDown, discogs, formats, look.merch) }
    }
    if (r == null && !busy && look.records) TextButton(onClick = { tokenOpen = true }) { Text(stringResource(R.string.records_token_title)) }
}

private fun formatLabel(f: Records.Format) = when (f) {
    Records.Format.VINYL -> R.string.records_format_vinyl
    Records.Format.CD -> R.string.records_format_cd
    Records.Format.CASSETTE -> R.string.records_format_cassette
}

private fun price(value: Double, currency: String?): String = runCatching {
    NumberFormat.getCurrencyInstance().apply { this.currency = Currency.getInstance(currency ?: "EUR") }.format(value)
}.getOrDefault("$value ${currency.orEmpty()}")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistRecordsCard(a: Records.ArtistRecords, slowed: Boolean, showRecords: Boolean, formats: List<Records.Format>, showMerch: Boolean) {
    val ctx = LocalContext.current
    val images = remember { discoverImagesOn(ctx) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.artist, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // The formats ticked (none: all of them).
            val records = a.records?.filter { rec -> formats.isEmpty() || rec.formats.any { it in formats } }
            if (showRecords) when {
                records == null -> Text(stringResource(if (slowed) R.string.records_not_asked else R.string.records_discogs_down), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                records.isEmpty() -> Text(stringResource(R.string.records_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    records.forEachIndexed { i, rec ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth().clickable { openUrl(ctx, rec.url) }.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Discogs gives no picture without a token: the album's cover on Deezer, when it has it.
                        if (images) DiscoverImage(rec.thumb, 56.dp, 56.dp) { DeezerCatalog.albumCover(a.artist, rec.title) }
                        Column(Modifier.weight(1f)) {
                            Text(rec.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            val formats = rec.formats.map { stringResource(formatLabel(it)) }
                            Text(listOfNotNull(rec.year?.toString()).plus(formats).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            val n = rec.forSale
                            if (n != null) {
                                val text = if (n > 0 && rec.lowestPrice != null) pluralStringResource(R.plurals.records_for_sale, n, n, price(rec.lowestPrice!!, rec.currency))
                                    else stringResource(R.string.records_not_for_sale)
                                Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(enabled = n > 0) { openUrl(ctx, rec.sellUrl) })
                            }
                        }
                        }
                    }
                }
            }
            // Discogs' terms: its data shown with this line, linking to it.
            if (showRecords && !records.isNullOrEmpty()) Text(
                Records.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(ctx, a.discogsPage) },
            )
            val merch = a.merch
            if (showMerch && merch != null) {
                if (showRecords) HorizontalDivider()
                Text(stringResource(R.string.records_kind_merch), style = MaterialTheme.typography.labelLarge)
                if (merch.isEmpty()) Text(stringResource(R.string.records_merch_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(merch) { MerchCard(it, images) }
                }
            }
            if (a.links.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.records_links), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.links.forEach { l ->
                        OutlinedButton(onClick = { openUrl(ctx, l.url) }) {
                            Text(when (l.kind) {
                                Records.Kind.OFFICIAL -> stringResource(R.string.concerts_official_site)
                                Records.Kind.SHOP -> stringResource(R.string.records_shop)
                                else -> l.label
                            })
                        }
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.records_search), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                a.searches.forEach { s -> TextButton(onClick = { openUrl(ctx, s.url) }) { Text(s.site ?: stringResource(R.string.records_search_web)) } }
            }
        }
    }
}

/** One thing on sale in the artist's shop: picture (when Discover shows them), title, price, where; a tap opens its page. */
@Composable
private fun MerchCard(item: Merch.Item, images: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.width(128.dp).clickable { openUrl(ctx, item.url) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (images) DiscoverImage(item.image, 128.dp, 128.dp)
        Text(item.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.price?.let { p ->
            Text(if (item.currency != null) price(p, item.currency) else NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(p),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            listOf(item.type, if (item.source == Merch.Source.BANDCAMP) "Bandcamp" else stringResource(R.string.records_shop)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The user's own Discogs personal access token: optional, adds the marketplace and more artists. */
@Composable
internal fun DiscogsTokenDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saved = Records.token(ctx.env) != null
    var token by remember { mutableStateOf("") }
    val close = rememberDiscardGuard(token.isNotBlank(), onClose = onDismiss)
    FitDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.records_token_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.records_token_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.records_token_steps), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(discogsTokenState(ctx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(ctx, Records.TOKEN_URL) }) { Text(stringResource(R.string.records_token_get)) }
                // The token is never shown again once saved: an empty field keeps it.
                OutlinedTextField(
                    value = token, onValueChange = { token = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.records_token_field)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { token = it } }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.lastfm_key_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (token.isNotBlank()) Records.saveToken(ctx.env, token); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (saved) TextButton(onClick = { Records.saveToken(ctx.env, ""); onDismiss() }) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

internal fun discogsTokenState(ctx: Context): Int = if (Records.token(ctx.env) != null) R.string.records_token_own else R.string.records_token_none

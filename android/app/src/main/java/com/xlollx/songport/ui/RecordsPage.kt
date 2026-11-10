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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Records
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency

/** The last search, kept while the app runs: another tab and back keeps the list. */
private object RecordsKept {
    @Volatile var result: Records.Result? = null
    @Volatile var playlistId: String? = null
}

/**
 * Discover › Records and merch: a playlist, then for each of its artists the records on Discogs
 * (CD, vinyl, cassette; with the user's own token, the copies for sale), the official pages
 * MusicBrainz knows (site, shop, Bandcamp, social) and searches on shops. Links only, no
 * affiliate tag; asked only when the user taps.
 */
@Composable
internal fun RecordsPage(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var result by remember { mutableStateOf(RecordsKept.result) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var tokenOpen by rememberSaveable { mutableStateOf(false) }
    if (tokenOpen) DiscogsTokenDialog { tokenOpen = false }

    fun run() {
        val p = pick ?: return
        progress = 0 to 0
        scope.launch {
            try {
                val artists = Concerts.artistsOf(p.provider.tracks(ctx.env, p.playlist.id))
                val currency = Records.currencyFor(ctx.resources.configuration.locales[0])
                val r = Records.find(ctx.env, artists, currency) { done, total -> progress = done to total }
                result = r; RecordsKept.result = r; RecordsKept.playlistId = p.playlist.id
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            } finally { progress = null }
        }
    }

    Text(stringResource(R.string.records_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "records") {
        if (it == null || it.playlist.id != RecordsKept.playlistId) { result = null; RecordsKept.result = null }
        pick = it
    }
    val busy = progress != null
    Button(onClick = { run() }, enabled = pick != null && !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(stringResource(R.string.records_find))
    }
    progress?.takeIf { it.second > 0 }?.let { (done, total) ->
        Text(stringResource(R.string.concerts_progress, (done + 1).coerceAtMost(total), total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val r = result
    if (r != null && !busy) {
        val notes = buildList {
            if (r.slowedDown) add(stringResource(R.string.records_slowed))
            if (r.tokenRefused) add(stringResource(R.string.records_token_refused))
            if (!r.token) add(stringResource(R.string.records_no_token))
            if (r.total > r.artists.size) add(stringResource(R.string.records_limited, r.artists.size, r.total))
        }
        if (notes.isNotEmpty()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                TextButton(onClick = { tokenOpen = true }) { Text(stringResource(R.string.records_token_title)) }
            }
        }
        r.artists.forEach { a -> ArtistRecordsCard(a, r.slowedDown) }
    }
    if (r == null && !busy) TextButton(onClick = { tokenOpen = true }) { Text(stringResource(R.string.records_token_title)) }
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
private fun ArtistRecordsCard(a: Records.ArtistRecords, slowed: Boolean) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.artist, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val records = a.records
            when {
                records == null -> Text(stringResource(if (slowed) R.string.records_not_asked else R.string.records_discogs_down), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                records.isEmpty() -> Text(stringResource(R.string.records_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    records.forEachIndexed { i, rec ->
                        if (i > 0) HorizontalDivider()
                        Column(Modifier.fillMaxWidth().clickable { openUrl(ctx, rec.url) }.padding(vertical = 2.dp)) {
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
            // Discogs' terms: its data shown with this line, linking to it.
            if (!records.isNullOrEmpty()) Text(
                Records.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(ctx, a.discogsPage) },
            )
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

/** The user's own Discogs personal access token: optional, adds the marketplace and more artists. */
@Composable
internal fun DiscogsTokenDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saved = Records.token(ctx.env) != null
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.records_token_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.records_token_body), style = MaterialTheme.typography.bodySmall)
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

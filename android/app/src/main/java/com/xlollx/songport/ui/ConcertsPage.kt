package com.xlollx.songport.ui

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The search going on and the last one found, kept while the app runs: another tab and back, or the screen turned, keeps it. */
private object ConcertsKept {
    val work = KeptWork<Concerts.Result>()
}

/** The country to start from: the mobile network's, the SIM's, then the language's. No location permission. */
internal fun defaultConcertCountry(ctx: Context): String {
    val tm = runCatching { ctx.getSystemService(TelephonyManager::class.java) }.getOrNull()
    return Concerts.defaultCountry(runCatching { tm?.networkCountryIso }.getOrNull(), runCatching { tm?.simCountryIso }.getOrNull(), ctx.resources.configuration.locales[0])
}

/**
 * Discover › Concerts: a playlist and a country, then the upcoming dates of its artists from
 * Ticketmaster and MusicBrainz ([Concerts]); the artists with none get searches on the country's
 * ticket sites. Asked when the user taps; in the background only for the artists followed ([com.xlollx.songport.sync.ArtistAlerts]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConcertsPage(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val k = ConcertsKept.work
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var country by rememberSaveable { mutableStateOf(defaultConcertCountry(ctx)) }
    k.error?.let { e -> LaunchedEffect(e) { snackbar.showSnackbar(e.ifBlank { ctx.getString(R.string.error_generic) }); k.error = null } }
    var range by remember { mutableStateOf(Concerts.savedRange(ctx.env)) }
    // The playlist's artists, read when it is picked: the ones played most, or some chosen.
    var artists by remember { mutableStateOf<List<String>?>(null) }
    // Reading them failed (shown with Try again, which counts up [reads]).
    var artistsFailed by remember { mutableStateOf(false) }
    var reads by remember { mutableStateOf(0) }
    val choice = rememberArtistChoice()
    LaunchedEffect(pick?.provider?.id, pick?.playlist?.id, reads) {
        artists = null; artistsFailed = false
        val p = pick ?: return@LaunchedEffect
        choice.forPlaylist(p.provider.id + "|" + p.playlist.id)
        artists = try { Concerts.artistsOf(p.provider.tracks(ctx.env, p.playlist.id)).also { com.xlollx.songport.sync.ArtistSuggest.note(ctx.env, it) } } catch (e: CancellationException) { throw e } catch (_: Exception) { artistsFailed = true; null }
    }
    var keyOpen by rememberSaveable { mutableStateOf(false) }
    if (keyOpen) TicketmasterKeyDialog { keyOpen = false }

    // Outside the page (ConcertsKept): the screen turned or another tab opened, the search goes on.
    fun run() {
        val p = pick ?: return
        val env = ctx.env
        val known = artists; val picked = choice.pickedOrNull; val c = country; val rg = range
        // The followed artists' concerts are looked up in the country last searched.
        Concerts.saveCountry(env, c)
        k.start(p.playlist.id) { progress ->
            val all = known ?: Concerts.artistsOf(p.provider.tracks(env, p.playlist.id))
            Concerts.find(env, ArtistPick.chosen(all, picked), c, rg, picked == null, progress, partial = k::keep)
        }
    }

    Text(stringResource(R.string.concerts_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "concerts") {
        if (it == null || it.playlist.id != k.key) k.clear()
        pick = it
    }
    if (pick != null) ArtistChooser(artists, choice, LookupCaps.CONCERTS, artistsFailed) { reads++ }
    CountryPicker(country) { country = it; k.clear() }
    RangePicker(range) { range = it; Concerts.saveRange(ctx.env, it); k.clear() }
    val busy = k.running
    val progress = k.progress
    Button(onClick = { run() }, enabled = pick != null && !busy && choice.ready, modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (pick == null) stringResource(R.string.concerts_find) else artistsAction(artists, choice, LookupCaps.CONCERTS, R.string.concerts_find, R.plurals.concerts_find_for, R.string.concerts_find_top))
    }
    if (busy) SearchRunning(progress) { k.stop() }

    val r = k.result?.takeIf { pick == null || k.key == pick?.playlist?.id }
    if (r != null && !busy) {
        SearchStopped(k.stopped)
        val notes = buildList {
            if (r.quotaHit) add(stringResource(R.string.concerts_quota))
            if (r.keyRefused) add(stringResource(R.string.concerts_key_refused))
            if (!r.ticketmaster) add(stringResource(R.string.concerts_no_key))
            if (r.total > r.artists.size) add(stringResource(R.string.artists_capped, r.artists.size, r.total))
            add(stringResource(if (Concerts.ticketOneGap(r.country) && !r.ticketOneRead) R.string.concerts_coverage_it else R.string.concerts_coverage))
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                if (r.quotaHit || r.keyRefused || !r.ticketmaster) {
                    TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.concerts_key_title)) }
                }
            }
        }
        val dated = r.artists.filter { it.events.isNotEmpty() }
        if (dated.isEmpty()) Text(stringResource(R.string.concerts_none_found), style = MaterialTheme.typography.bodyMedium)
        // A long list can be searched by name, both the artists with dates and those without.
        var query by rememberSaveable { mutableStateOf("") }
        if (r.artists.size > 5) ArtistSearchField(query) { query = it }
        ArtistPick.named(dated, query) { it.artist }.forEach { a -> ArtistDatesCard(a, snackbar) }
        val without = ArtistPick.named(r.artists.filter { it.events.isEmpty() }, query) { it.artist }
        if (without.isNotEmpty()) NoDatesCard(without)
        // The sources, as their terms ask: the dates are theirs.
        Text(
            stringResource(if (r.ticketmaster) R.string.concerts_sources_both else R.string.concerts_sources_mb),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UnofficialSourcesNote(r)
    }
    if (r == null && !busy) TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.concerts_key_title)) }
}

@Composable
private fun CountryPicker(country: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val here = ctx.resources.configuration.locales[0]
    val all = remember(here) { Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(here) }.sortedBy { it.second } }
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.concerts_country) + ": " + Locale("", country).getDisplayCountry(here), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(code) }) }
        }
    }
}

private val RANGES = listOf(
    Concerts.Range.MONTH to R.string.concerts_range_1m, Concerts.Range.THREE_MONTHS to R.string.concerts_range_3m,
    Concerts.Range.SIX_MONTHS to R.string.concerts_range_6m, Concerts.Range.YEAR to R.string.concerts_range_1y,
    Concerts.Range.ANY to R.string.concerts_range_any,
)

@Composable
private fun RangePicker(range: Concerts.Range, onPick: (Concerts.Range) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.concerts_range) + ": " + stringResource(RANGES.first { it.first == range }.second), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RANGES.forEach { (r, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; onPick(r) }) }
        }
    }
}

private fun dateText(e: Concerts.Event): String {
    val d = runCatching { LocalDate.parse(e.date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(e.date)
    return if (e.time != null) "$d · ${e.time}" else d
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistDatesCard(a: Concerts.ArtistDates, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { discoverImagesOn(ctx) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtistTitle(a.artist)
            a.events.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider()
                Column(Modifier.fillMaxWidth().clickable(enabled = e.url.isNotBlank()) { openUrl(ctx, e.url) }.padding(vertical = 2.dp)) {
                    if (images && e.image.isNotBlank()) {
                        DiscoverImage(e.image, 160.dp, 90.dp)
                        if (e.imageCredit.isNotBlank()) Text(e.imageCredit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(dateText(e), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(listOf(e.city, e.venue).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    // One button per ticket site and the calendar, wrapping onto new lines: a row of them
                    // squeezed the first label to one letter a line (11/11 report).
                    FlowRow(verticalArrangement = Arrangement.Center) {
                        // Each site's own event page, as it comes: the tickets are there.
                        if (e.url.isNotBlank()) TextButton(onClick = { openUrl(ctx, e.url) }) {
                            Text(ticketsLabel(e.source, e.url), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                        AlsoOnLinks(e)
                        TextButton(onClick = {
                            if (!addToCalendar(ctx, e)) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.concerts_no_calendar)) }
                        }) {
                            Icon(Icons.Filled.Event, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.concerts_add_calendar), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoDatesCard(artists: List<Concerts.ArtistDates>) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.concerts_no_dates, artists.size, artists.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.concerts_no_dates_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) artists.forEach { a ->
                HorizontalDivider()
                ArtistTitle(a.artist, small = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    a.searches.forEach { l ->
                        TextButton(onClick = { openUrl(ctx, l.url) }) { Text(l.site ?: stringResource(R.string.concerts_official_site)) }
                    }
                }
            }
        }
    }
}

/** The user's own Ticketmaster Consumer Key: without one, MusicBrainz's dates only. */
@Composable
internal fun TicketmasterKeyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saved = remember { Concerts.ownKey(ctx.env).orEmpty() }
    var key by remember { mutableStateOf(saved) }
    val close = rememberDiscardGuard(key != saved, onClose = onDismiss)
    FitDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.concerts_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.concerts_key_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.concerts_key_steps), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(ticketmasterKeyState(ctx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(ctx, Concerts.KEY_URL) }) { Text(stringResource(R.string.concerts_key_get)) }
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.concerts_key_field)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.lastfm_key_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { Concerts.saveKey(ctx.env, key); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (Concerts.ownKey(ctx.env) != null) TextButton(onClick = { Concerts.saveKey(ctx.env, ""); onDismiss() }) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Whether the concerts have the user's own Ticketmaster key (the app carries none). */
internal fun ticketmasterKeyState(ctx: Context): Int =
    if (Concerts.ownKey(ctx.env) != null) R.string.concerts_key_own else R.string.concerts_key_none

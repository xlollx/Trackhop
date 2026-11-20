package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.DiscoverSource
import com.xlollx.songport.sync.Discogs
import com.xlollx.songport.sync.Records
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.CancellationException

/** The collection and the wantlist being read and last read, kept while the app runs. */
internal object MyRecordsKept {
    val collection = KeptWork<Discogs.Collection>()
    val wants = KeptWork<Discogs.Wantlist>()
}

/**
 * Back on the page after a while away (Discogs opened in the browser, a record added or removed
 * there): read anew, so the change shows without a tap on Refresh. Not within a minute of the last
 * read, so switching apps back and forth does not spend Discogs' 60 calls a minute.
 */
@Composable
private fun BackFromDiscogs(k: KeptWork<*>, read: () -> Unit) {
    // A plain holder, not state: leaving the app must not draw the page again.
    val leftAt = remember { longArrayOf(0L) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val away = leftAt[0] > 0 && android.os.SystemClock.elapsedRealtime() - leftAt[0] >= BACK_REREAD_MS
        if (away && !k.running) read()
        onPauseOrDispose { leftAt[0] = android.os.SystemClock.elapsedRealtime() }
    }
}

/** Away at least this long, the page reads Discogs again on its return. */
private const val BACK_REREAD_MS = 60_000L

/** Test tag of the alert button of a wanted record. */
const val WANT_ALERT_TAG = "wantAlert"

/** The records shown before "Show all". */
private const val FIRST_RECORDS = 30

/**
 * Discover › My records: the user's Discogs collection (covers, Discogs' own estimate of its value,
 * "My records as a playlist") and wantlist (what is for sale and from which price, and an alert per
 * record), read with their personal token ([Discogs]). A record opens its page on Discogs.
 */
@Composable
internal fun MyRecordsPage(snackbar: SnackbarHostState, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    var tokenOpen by rememberSaveable { mutableStateOf(false) }
    if (tokenOpen) DiscogsTokenDialog { tokenOpen = false }
    val hasToken = remember(tokenOpen) { Records.token(ctx.env) != null }
    if (!hasToken) {
        ToolEmpty(stringResource(R.string.myrecords_need_token)) { OutlinedButton(onClick = { tokenOpen = true }) { Text(stringResource(R.string.records_token_title)) } }
        return
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    ToolSegmented(listOf(stringResource(R.string.myrecords_tab_collection), stringResource(R.string.myrecords_tab_wantlist)), tab) { tab = it }
    if (tab == 0) CollectionTab(onNewSync) else WantlistTab()
    // Discogs' terms: its data with this line, linking to it.
    Text(
        Records.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { openUrl(ctx, "https://www.discogs.com/") }.padding(vertical = 4.dp),
    )
    Text(stringResource(R.string.myrecords_no_buy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { tokenOpen = true }) { Text(stringResource(R.string.records_token_title)) }
}

/** Reads with [work], saying a failure in the user's words. */
private fun <T> readDiscogs(ctx: android.content.Context, work: KeptWork<T>, read: suspend ((Int, Int) -> Unit) -> T) {
    work.start("discogs") { progress ->
        try { read(progress) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw Exception(e.userMessage(ctx)) }
    }
}

@Composable
private fun <T> ReadState(work: KeptWork<T>, onRetry: () -> Unit) {
    if (work.running) ToolLoading(stringResource(R.string.myrecords_reading), progress = work.progress?.takeIf { it.second > 1 }?.let { it.first.toFloat() / it.second })
    work.error?.let { ToolError(it.ifBlank { stringResource(R.string.error_generic) }, onRetry = { work.error = null; onRetry() }) }
}

@Composable
private fun CollectionTab(onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val k = MyRecordsKept.collection
    fun read(refresh: Boolean) = readDiscogs(ctx, k) { Discogs.collection(env, refresh, it) }
    // Opened again: what was kept at once, then read anew only when older than Discogs allows.
    LaunchedEffect(Unit) {
        if (k.running) return@LaunchedEffect
        if (k.result == null) Discogs.keptCollection(env)?.let { k.show("discogs", it) }
        read(false)
    }
    ReadState(k) { read(true) }
    val c = k.result ?: return
    BackFromDiscogs(k) { read(true) }
    if (c.items.isEmpty()) { ToolEmpty(stringResource(R.string.myrecords_empty_collection), action = { if (!k.running) TextButton(onClick = { read(true) }) { Text(stringResource(R.string.diary_refresh)) } }); return }
    c.value?.let { v ->
        Surface(Modifier.fillMaxWidth(), shape = ToolCardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.myrecords_value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v.minimum + " – " + v.maximum, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (v.median.isNotBlank()) Text(stringResource(R.string.myrecords_value_median, v.median), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    var all by rememberSaveable { mutableStateOf(false) }
    val images = remember { discoverImagesOn(ctx) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth > 560.dp) 5 else 3
        val gap = 8.dp
        val side = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (if (all) c.items else c.items.take(FIRST_RECORDS)).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { RecordTile(it, side, images) } }
            }
        }
    }
    if (!all && c.items.size > FIRST_RECORDS) TextButton(onClick = { all = true }) { Text(stringResource(R.string.myrecords_show_all, c.items.size)) }
    Text(
        pluralStringResource(R.plurals.myrecords_count, c.items.size, c.items.size) + " · " + stringResource(R.string.myrecords_prices_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val name = stringResource(R.string.discover_myrecords_title)
    ToolPrimaryButton(stringResource(R.string.myrecords_playlist), onClick = {
        onNewSync { copy(name = name, source = PlaylistRef(DiscoverSource.SERVICE, DiscoverSource.RECORDS, name), schedule = Schedule.DAILY) }
    })
    Text(stringResource(R.string.myrecords_playlist_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!k.running) TextButton(onClick = { read(true) }) { Text(stringResource(R.string.diary_refresh)) }
}

/** A record's cover (a plain tile where pictures are off or it has none), its title and artist; a tap opens it on Discogs. */
@Composable
private fun RecordTile(item: Discogs.Item, side: Dp, images: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.width(side).clickable { openUrl(ctx, item.url) }) {
        if (images && item.thumb.isNotBlank()) DiscoverImage(item.thumb, side, side)
        else Box(Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("💿") }
        Text(item.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WantlistTab() {
    val ctx = LocalContext.current
    val env = ctx.env
    val store = Store.get(ctx)
    val alerts = store.state.collectAsState().value.settings.discogsWantAlerts
    val k = MyRecordsKept.wants
    val currency = remember { Records.currencyFor(ctx.resources.configuration.locales[0]) }
    fun read(refresh: Boolean) = readDiscogs(ctx, k) { Discogs.wantlist(env, currency, store.data.settings.discogsWantAlerts.keys, refresh, it) }
    LaunchedEffect(Unit) {
        if (k.running) return@LaunchedEffect
        if (k.result == null) Discogs.keptWantlist(env)?.let { k.show("discogs", it) }
        read(false)
    }
    // The record whose alert is being set, by its id: kept when the activity is made anew.
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    ReadState(k) { read(true) }
    val w = k.result ?: return
    BackFromDiscogs(k) { read(true) }
    if (w.items.isEmpty()) { ToolEmpty(stringResource(R.string.myrecords_empty_wantlist), action = { if (!k.running) TextButton(onClick = { read(true) }) { Text(stringResource(R.string.diary_refresh)) } }); return }
    val images = remember { discoverImagesOn(ctx) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            w.items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider()
                val alert = alerts[item.id.toString()]
                val m = w.markets[item.id.toString()]
                Row(Modifier.fillMaxWidth().clickable { openUrl(ctx, item.sellUrl) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (images && item.thumb.isNotBlank()) DiscoverImage(item.thumb, 48.dp, 48.dp)
                    else Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("💿") }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listOf(item.title, item.artist).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        val lines = listOfNotNull(
                            m?.let { if (it.forSale > 0 && it.lowest != null) stringResource(R.string.myrecords_from, price(it.lowest!!, it.currency), it.forSale) else stringResource(R.string.records_not_for_sale) },
                            alert?.below?.let { stringResource(R.string.myrecords_alert_below_line, price(it, currency)) },
                            stringResource(R.string.myrecords_alert_new_line).takeIf { alert?.newCopy == true },
                        )
                        if (lines.isNotEmpty()) Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = item.id }, modifier = Modifier.testTag(WANT_ALERT_TAG)) {
                        Icon(if (alert?.any == true) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone, stringResource(R.string.myrecords_alert_title),
                            tint = if (alert?.any == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (w.items.size > w.markets.size) Text(stringResource(R.string.myrecords_market_some, Discogs.MARKET_SHOWN), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!k.running) TextButton(onClick = { read(true) }) { Text(stringResource(R.string.diary_refresh)) }
    w.items.firstOrNull { it.id == editing }?.let { item ->
        WantAlertDialog(item, alerts[item.id.toString()], currency, onDismiss = { editing = null }) { alert ->
            store.updateSettings { s -> s.copy(discogsWantAlerts = if (alert.any) s.discogsWantAlerts + (item.id.toString() to alert) else s.discogsWantAlerts - item.id.toString()) }
            // The daily look runs for the wantlist too, with nobody followed.
            Scheduler.applyArtistAlerts(ctx)
            editing = null
        }
    }
}

/** A wanted record's alert: a new copy for sale, a price below an amount. Saved with none of the two, the alert is off. */
@Composable
private fun WantAlertDialog(item: Discogs.Item, current: Discogs.WantAlert?, currency: String, onDismiss: () -> Unit, onSave: (Discogs.WantAlert) -> Unit) {
    val ctx = LocalContext.current
    var newCopy by rememberSaveable(item.id) { mutableStateOf(current?.newCopy == true) }
    var below by rememberSaveable(item.id) { mutableStateOf(current?.below?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.myrecords_alert_title) + " · " + item.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().clickable { newCopy = !newCopy }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.myrecords_alert_new), modifier = Modifier.weight(1f))
                    Switch(checked = newCopy, onCheckedChange = { newCopy = it })
                }
                Text(stringResource(R.string.myrecords_alert_below))
                OutlinedTextField(
                    value = below, onValueChange = { v -> below = v.filter { it.isDigit() || it == '.' || it == ',' }.take(9) }, singleLine = true,
                    label = { Text(stringResource(R.string.myrecords_alert_amount, currency)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.myrecords_alert_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(ctx, item.sellUrl) }) { Text(stringResource(R.string.myrecords_open)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Discogs.WantAlert(newCopy, below.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 })) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

package com.xlollx.songport.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.PlaylistFiles
import com.xlollx.songport.sync.Scrobbles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/** The songs read for the page (an album, a playlist, a file) and the albums found: kept for the process, the form keeps what they were read for. */
internal object ListenedKept {
    @Volatile var tracks: List<Track>? = null
    @Volatile var from: String = ""
    @Volatile var albums: List<DeezerCatalog.Album> = emptyList()
}

/** One sending at a time, in the app's own scope: leaving the page does not stop it half way. */
private val sending = com.xlollx.songport.sync.AppRun<Scrobbles.Sent>()

private enum class ListenedFrom(val label: Int) { ALBUM(R.string.listened_from_album), PLAYLIST(R.string.listened_from_playlist), FILE(R.string.listened_from_file), TEXT(R.string.listened_from_text) }

/**
 * Tools › Mark as listened (1.18, [Scrobbles]): what was listened to (an album of Deezer's catalogue,
 * a playlist of a connected service, a file, a pasted list), where to mark it (Last.fm, ListenBrainz,
 * with the accounts already connected) and when; then the list, to untick what was skipped, and send.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MarkListenedPage(connected: List<MusicProvider>, snackbar: SnackbarHostState, onGoToAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by rememberSaveable { mutableStateOf(ListenedFrom.ALBUM) }
    var albumQuery by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf<DeezerCatalog.Album?>(null) }
    var albums by remember { mutableStateOf(ListenedKept.albums) }
    val services = connected.filter { it !is LastFmProvider && it !is ListenBrainzProvider }
    var serviceId by rememberSaveable { mutableStateOf(services.firstOrNull()?.id) }
    val service = services.firstOrNull { it.id == serviceId } ?: services.firstOrNull()
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    val playlist = playlistId?.let { Playlist(it, playlistName) }
    var lists by remember(service?.id) { mutableStateOf<List<Playlist>?>(null) }
    var fileName by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    val lastFm = connected.filterIsInstance<LastFmProvider>().firstOrNull()
    val listenBrainz = connected.filterIsInstance<ListenBrainzProvider>().firstOrNull()
    val lastFmOk = lastFm?.canLove(ctx.env) == true
    val listenBrainzOk = listenBrainz?.canSubmit(ctx.env) == true
    var toLastFmAsked by rememberSaveable { mutableStateOf(true) }
    var toListenBrainzAsked by rememberSaveable { mutableStateOf(true) }
    val toLastFm = toLastFmAsked && lastFmOk
    val toListenBrainz = toListenBrainzAsked && listenBrainzOk
    var time by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() / 60_000 * 60_000) }
    var finished by rememberSaveable { mutableStateOf(true) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var skipped by rememberSaveable { mutableStateOf(ArrayList<Int>()) }
    var reading by remember { mutableStateOf(false) }
    val sendingNow by sending.running.collectAsState()
    val sent by sending.finished.collectAsState()
    val busy = reading || sendingNow
    var loaded by remember { mutableStateOf(ListenedKept.tracks) }

    // What the songs are read from now: those read before serve only while it is the same.
    val key = when (from) {
        ListenedFrom.ALBUM -> "a:" + album?.id
        ListenedFrom.PLAYLIST -> "p:${service?.id}:${playlist?.id}"
        ListenedFrom.FILE -> "f:$fileName"
        ListenedFrom.TEXT -> "t"
    }
    fun fail(e: Exception) { scope.launch { snackbar.showSnackbar(e.userMessage(ctx)) } }
    fun keep(k: String, tracks: List<Track>) { ListenedKept.tracks = tracks; ListenedKept.from = k; loaded = tracks }
    // The outcome of a sending, said once it finished: also when the page was left and opened again meanwhile.
    LaunchedEffect(sent) {
        val r = sent ?: return@LaunchedEffect
        sending.finished.value = null
        r.onSuccess { done ->
            val parts = listOfNotNull(
                done.lastFm?.let { ctx.getString(R.string.listened_done_lastfm, it) },
                done.listenBrainz?.let { ctx.getString(R.string.listened_done_listenbrainz, it) },
            )
            // One run, one listen per song: what was sent is cleared, it cannot go twice.
            reviewing = false; skipped = ArrayList()
            album = null; playlistId = null; fileName = null; text = ""
            ListenedKept.tracks = null; ListenedKept.from = ""; loaded = null
            // In the screen's scope: clearing [finished] restarts this effect, which would cancel the snackbar.
            scope.launch { snackbar.showSnackbar(ctx.getString(R.string.listened_done, parts.joinToString(", "))) }
        }.onFailure { e -> fail(e as? Exception ?: Exception(e)) }
    }
    LaunchedEffect(key) {
        if (ListenedKept.from == key) return@LaunchedEffect
        val a = album
        val pl = playlist
        val tracks = try {
            when {
                from == ListenedFrom.ALBUM && a != null -> DeezerCatalog.albumTracks(a)
                from == ListenedFrom.PLAYLIST && service != null && pl != null -> service.tracks(ctx.env, pl.id)
                else -> return@LaunchedEffect
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e); return@LaunchedEffect }
        keep(key, tracks)
    }
    LaunchedEffect(service?.id, from) {
        if (from != ListenedFrom.PLAYLIST || service == null || lists != null) return@LaunchedEffect
        lists = try { service.libraryEntries(ctx.env, asTarget = false).filter { it.id != MusicProvider.RECENT_ID } + service.playlists(ctx.env) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e); emptyList() }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val name = displayName(ctx, uri)
                val raw = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { PlaylistFiles.decode(it.readBytes()) }.orEmpty() }
                fileName = name
                keep("f:$name", PlaylistFiles.parse(name, raw))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    val songs = remember(key, loaded, text) {
        Scrobbles.songs(if (from == ListenedFrom.TEXT) PlaylistFiles.parse("pasted.txt", text) else loaded.takeIf { ListenedKept.from == key }.orEmpty())
    }
    val now = System.currentTimeMillis()
    val kept = songs.filterIndexed { i, _ -> i !in skipped }
    val listens = Scrobbles.lay(kept, time, finished)
    val inFuture = listens.any { it.at > now }
    val tooOld = if (toLastFm) listens.count { it.at <= now && !Scrobbles.lastFmTakes(it.at, now) } else 0
    val sendable = if (inFuture) 0 else listens.count { (toLastFm && Scrobbles.lastFmTakes(it.at, now)) || toListenBrainz }

    if (reviewing) {
        BackHandler { reviewing = false }
        Text(pluralStringResource(R.plurals.listened_review_title, kept.size, kept.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.listened_review_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
        val at = listens.associateBy { it.track }
        PageCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                songs.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val on = i !in skipped
                    Row(
                        Modifier.fillMaxWidth().clickable { skipped = ArrayList(if (on) skipped + i else skipped - i) }.padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(at[t]?.let { timeFormat.format(java.util.Date(it.at)) } ?: "—", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 48.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyMedium, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (on) listOfNotNull(t.artistLine.ifBlank { null }, t.album.ifBlank { null }).joinToString(" · ") else stringResource(R.string.listened_skipped),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (toLastFm) Text(stringResource(R.string.listened_album_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tooOld > 0) Text(pluralStringResource(R.plurals.listened_too_old, tooOld, tooOld), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (inFuture) Text(stringResource(R.string.listened_future), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp))
            OutlinedButton(onClick = { reviewing = false }, enabled = !busy) { Text(stringResource(R.string.nav_back)) }
            Button(enabled = !busy && sendable > 0, onClick = {
                val env = ctx.applicationContext.env
                val toLf = lastFm?.takeIf { toLastFm }
                val toLb = listenBrainz?.takeIf { toListenBrainz }
                sending.start { Scrobbles.send(env, listens, toLf, toLb) }
            }) { Text(pluralStringResource(R.plurals.listened_send, sendable, sendable)) }
        }
        return
    }

    ToolSectionTitle(stringResource(R.string.listened_what))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ListenedFrom.entries.forEachIndexed { i, f ->
            SegmentedButton(selected = from == f, onClick = { from = f; skipped = ArrayList() }, shape = SegmentedButtonDefaults.itemShape(i, ListenedFrom.entries.size)) {
                Text(stringResource(f.label), maxLines = 1)
            }
        }
    }
    when (from) {
        ListenedFrom.ALBUM -> {
            val a = album
            if (a != null) ChosenField("${a.title} · ${a.artist}") { album = null }
            else {
                OutlinedTextField(albumQuery, { albumQuery = it }, singleLine = true, label = { Text(stringResource(R.string.listened_album_search)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                    FilledTonalButton(enabled = !busy && albumQuery.isNotBlank(), onClick = {
                        reading = true
                        scope.launch {
                            try { albums = DeezerCatalog.albums(albumQuery); ListenedKept.albums = albums } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
                            reading = false
                        }
                    }) { Text(stringResource(R.string.unmatched_search)) }
                }
                albums.forEach { found ->
                    Row(Modifier.fillMaxWidth().clickable { album = found; skipped = ArrayList() }.heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(found.title, style = MaterialTheme.typography.bodyMedium)
                            Text(found.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ListenedFrom.PLAYLIST -> {
            if (service == null) Text(stringResource(R.string.tools_none), style = MaterialTheme.typography.bodyMedium)
            else {
                if (services.size > 1) ServicePicker(services, service.id, { serviceId = it; playlistId = null; skipped = ArrayList() })
                PlaylistChoice(stringResource(R.string.editor_playlist), lists, playlist, service, onPick = { playlistId = it.id; playlistName = it.name; skipped = ArrayList() })
            }
        }
        ListenedFrom.FILE -> {
            val name = fileName
            if (name != null) ChosenField(name) { pickFile.launch(arrayOf("*/*")) }
            else OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.listened_file_pick)) }
        }
        ListenedFrom.TEXT -> OutlinedTextField(
            text, { text = it; skipped = ArrayList() }, minLines = 4, label = { Text(stringResource(R.string.listened_text_hint)) }, modifier = Modifier.fillMaxWidth(),
        )
    }
    if (songs.isNotEmpty() && from != ListenedFrom.TEXT) Text(pluralStringResource(R.plurals.card_tracks, songs.size, songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    ToolSectionTitle(stringResource(R.string.listened_where))
    if (lastFm == null && listenBrainz == null) {
        Text(stringResource(R.string.listened_none_connected), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onGoToAccounts) { Text(stringResource(R.string.tools_go_accounts)) }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (lastFm != null) FilterChip(selected = toLastFm, enabled = lastFmOk, onClick = { toLastFmAsked = !toLastFmAsked },
                label = { Text("Last.fm") }, leadingIcon = { ServiceLogo(lastFm, 18.dp) })
            if (listenBrainz != null) FilterChip(selected = toListenBrainz, enabled = listenBrainzOk, onClick = { toListenBrainzAsked = !toListenBrainzAsked },
                label = { Text("ListenBrainz") }, leadingIcon = { ServiceLogo(listenBrainz, 18.dp) })
        }
        if (lastFm != null && !lastFmOk) Text(stringResource(R.string.listened_lastfm_needs_password), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (listenBrainz != null && !listenBrainzOk) Text(stringResource(R.string.listened_listenbrainz_needs_token), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if ((lastFm != null && !lastFmOk) || (listenBrainz != null && !listenBrainzOk)) TextButton(onClick = onGoToAccounts) { Text(stringResource(R.string.tools_go_accounts)) }
    }

    ToolSectionTitle(stringResource(R.string.listened_when))
    val day = DateUtils.getRelativeTimeSpanString(time, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_SHOW_DATE).toString()
    val clock = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(time))
    ChosenField(stringResource(if (finished) R.string.listened_finished_at else R.string.listened_started_at, day, clock)) { pickingDate = true }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = finished, onClick = { finished = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.listened_mode_finished), maxLines = 1) }
        SegmentedButton(selected = !finished, onClick = { finished = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.listened_mode_started), maxLines = 1) }
    }
    if (toLastFm) Text(stringResource(R.string.listened_lastfm_14), style = MaterialTheme.typography.bodySmall, color = if (tooOld > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        Button(enabled = songs.isNotEmpty(), onClick = { reviewing = true }) { Text(pluralStringResource(R.plurals.listened_see, songs.size, songs.size)) }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = utcDay(time))
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { time = withDay(time, it) }
                    pickingDate = false; pickingTime = true
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    if (pickingTime) {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        RadioTimeDialog(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), onDismiss = { pickingTime = false }) { minute ->
            time = Calendar.getInstance().apply { timeInMillis = time; set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            pickingTime = false
        }
    }
}

/** What was chosen, with the way to change it, as the preview draws it: the value, and "Change" at the end. */
@Composable
private fun ChosenField(value: String, onChange: () -> Unit) {
    PageCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onChange).heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.review_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The date picker works on UTC midnights: the local day of [time] as one. */
private fun utcDay(time: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = time }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** [time] moved to the day [utcMidnight] names, at the same hour. */
private fun withDay(time: Long, utcMidnight: Long): Long {
    val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return Calendar.getInstance().apply {
        timeInMillis = time
        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

package com.xlollx.songport.ui

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.importTracks
import com.xlollx.songport.sync.ItunesLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * An Apple Music / iTunes library picked in Account › File › Import (#67): read a piece at a time
 * outside the screen (turning the phone or leaving the app does not stop it), then its playlists to
 * tick, each saved as a file. A file cut short, a read that fails or one stopped by the person adds
 * nothing; adding stops cleanly and says what was added (each file is written whole).
 */
object LibraryImport {
    sealed interface State {
        data object Idle : State
        data class Reading(val name: String, val read: Long, val total: Long) : State
        /** [token] tells one library from the next, for the ticks kept across a turned screen. */
        class Picking(val token: Long, val name: String, val library: ItunesLibrary) : State
        class Adding(val name: String) : State
        /** Done: what was added ([single] when it was one list, to send on to a service). */
        class Done(val added: List<Pair<String, Int>>, val single: Playlist?, val error: String?) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    /** Goes up after every import, so File's list reads itself again. */
    val imported = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** True when [uri] starts like an Apple plist (only its first 4 KB are read). */
    fun isLibrary(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ItunesLibrary.looksLike(String(it.readNBytes(4096), Charsets.UTF_8)) }
    }.getOrNull() == true

    fun start(ctx: Context, uri: Uri, name: String) {
        val app = ctx.applicationContext
        val total = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
        job?.cancel()
        state.value = State.Reading(name, 0, total)
        job = scope.launch {
            val lib = try {
                val input = app.contentResolver.openInputStream(uri) ?: error(app.getString(R.string.library_cannot_open))
                input.use { s ->
                    val reader = s.reader(Charsets.UTF_8).buffered(64 * 1024)
                    reader.mark(1); if (reader.read() != '﻿'.code) reader.reset()
                    ItunesLibrary.read(reader, progress = { state.value = State.Reading(name, it, total) }, cancelled = { !isActive })
                }
            } catch (e: java.util.concurrent.CancellationException) {
                return@launch
            } catch (e: ItunesLibrary.Incomplete) {
                state.value = State.Done(emptyList(), null, app.getString(R.string.library_incomplete)); return@launch
            } catch (e: Throwable) {
                state.value = State.Done(emptyList(), null, app.getString(R.string.library_failed, e.message ?: e.javaClass.simpleName)); return@launch
            }
            if (lib.worthPicking) state.value = State.Picking(System.nanoTime(), name, lib)
            else add(app, name, lib, emptyList(), whole = true)
        }
    }

    /** Stops a reading under way: nothing is added. */
    fun stop() {
        job?.cancel(); job = null
        state.value = State.Idle
    }

    fun pick(ctx: Context, p: State.Picking, picked: List<ItunesLibrary.Playlist>, whole: Boolean) {
        val app = ctx.applicationContext
        state.value = State.Adding(p.name)
        job = scope.launch { add(app, p.name, p.library, picked, whole) }
    }

    private fun add(ctx: Context, fileName: String, lib: ItunesLibrary, picked: List<ItunesLibrary.Playlist>, whole: Boolean) {
        val added = ArrayList<Pair<String, Int>>()
        val error = runCatching {
            for (p in picked) {
                val tracks = lib.tracksOf(p).takeIf { it.isNotEmpty() } ?: continue
                added += LocalFilesProvider.importTracks(ctx, p.name, tracks) to tracks.size
            }
            if (whole) lib.asOneList().takeIf { it.isNotEmpty() }?.let {
                val base = fileName.substringBeforeLast('.').ifBlank { "Library" }
                added += LocalFilesProvider.importTracks(ctx, base, it) to it.size
            }
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        imported.value++
        val single = added.singleOrNull()?.takeIf { error == null && picked.isEmpty() }?.let { (id, n) -> Playlist(id, id, n) }
        state.value = State.Done(added, single, error)
    }

    fun clear() { state.value = State.Idle }
}

/**
 * Where [LibraryImport] shows: the reading's progress, the playlists to tick, and what was added.
 * Hosted by the main screen, so it is there wherever the person is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryImportHost(snackbar: SnackbarHostState, onSendTo: (id: String, name: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by LibraryImport.state.collectAsState()
    when (val s = state) {
        is LibraryImport.State.Reading -> ReadingDialog(s)
        is LibraryImport.State.Adding -> FitDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.library_adding)) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) },
        )
        is LibraryImport.State.Picking -> PickDialog(s)
        is LibraryImport.State.Done -> LaunchedEffect(s) {
            LibraryImport.clear()
            val names = s.added.joinToString(", ") { it.first }
            val text = when {
                s.error != null && s.added.isEmpty() -> s.error
                s.error != null -> ctx.getString(R.string.library_partial, names, s.error)
                s.single != null -> ctx.getString(R.string.csv_imported, s.single.trackCount)
                else -> ctx.resources.getQuantityString(R.plurals.library_added, s.added.size, s.added.size, names)
            }
            scope.launch {
                val single = s.single
                val res = snackbar.showSnackbar(
                    message = text,
                    actionLabel = if (single != null) ctx.getString(R.string.files_send_to) else null,
                    duration = SnackbarDuration.Long,
                )
                if (single != null && res == SnackbarResult.ActionPerformed) onSendTo(single.id, single.name)
            }
        }
        LibraryImport.State.Idle -> Unit
    }
}

@Composable
private fun ReadingDialog(s: LibraryImport.State.Reading) {
    val ctx = LocalContext.current
    // Hidden, not stopped: the reading goes on and the playlists show when it ends.
    var hidden by rememberSaveable(s.name) { mutableStateOf(false) }
    if (hidden) return
    FitDialog(
        onDismissRequest = { hidden = true },
        confirmButton = { TextButton(onClick = { hidden = true }) { Text(stringResource(R.string.library_hide)) } },
        dismissButton = { TextButton(onClick = { LibraryImport.stop() }) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.library_reading)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.total > 0) LinearProgressIndicator(progress = { (s.read.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (s.total > 0) stringResource(R.string.library_read_of, Formatter.formatShortFileSize(ctx, s.read.coerceAtMost(s.total)), Formatter.formatShortFileSize(ctx, s.total))
                    else stringResource(R.string.library_read, Formatter.formatShortFileSize(ctx, s.read)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.library_reading_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDialog(p: LibraryImport.State.Picking) {
    val ctx = LocalContext.current
    val lib = p.library
    val choices = lib.choices
    val numbers = remember { NumberFormat.getIntegerInstance() }
    // Ticked at first: the person's own playlists; smart ones and the whole library are left to choose.
    val initial = choices.indices.filter { choices[it].kind == ItunesLibrary.Kind.USER }.joinToString(",")
    var tickedText by rememberSaveable(p.token) { mutableStateOf(initial) }
    var whole by rememberSaveable(p.token) { mutableStateOf(false) }
    var query by rememberSaveable(p.token) { mutableStateOf("") }
    val ticked = tickedText.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    fun set(i: Int, on: Boolean) { tickedText = (if (on) ticked + i else ticked - i).sorted().joinToString(",") }
    val count = ticked.size + if (whole) 1 else 0

    // The rows to show: folder headings, then the smart ones, then "all at once".
    data class Row(val heading: String? = null, val index: Int = -1, val isWhole: Boolean = false)
    val shown = choices.indices.filter { query.isBlank() || choices[it].name.contains(query.trim(), ignoreCase = true) }
    val rows = buildList {
        var folder: String? = null
        shown.filter { choices[it].kind == ItunesLibrary.Kind.USER }.forEach { i ->
            val f = choices[i].folder
            if (f != folder) { folder = f; if (f != null) add(Row(heading = ctx.getString(R.string.library_folder, f))) }
            add(Row(index = i))
        }
        val smart = shown.filter { choices[it].kind == ItunesLibrary.Kind.SMART }
        if (smart.isNotEmpty()) { add(Row(heading = ctx.getString(R.string.library_smart))); smart.forEach { add(Row(index = it)) } }
        if (query.isBlank()) { add(Row(heading = ctx.getString(R.string.library_all_at_once))); add(Row(isWhole = true)) }
    }

    Dialog(onDismissRequest = { LibraryImport.clear() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("library-pick")) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.library_title)) },
                        navigationIcon = { IconButton(onClick = { LibraryImport.clear() }) { Icon(Icons.Filled.Close, stringResource(android.R.string.cancel)) } },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 2.dp) {
                        Button(
                            onClick = { LibraryImport.pick(ctx, p, ticked.sorted().map { choices[it] }, whole) },
                            enabled = count > 0,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text(pluralStringResource(R.plurals.library_import, count, count)) }
                    }
                },
            ) { padding ->
                LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                    item(key = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(
                                stringResource(R.string.library_summary, numbers.format(lib.trackCount), numbers.format(choices.size)),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (choices.size > 6) OutlinedTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                placeholder = { Text(stringResource(R.string.library_pick_search)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { tickedText = shown.joinToString(",") }) { Text(stringResource(R.string.library_select_all)) }
                                TextButton(onClick = { tickedText = ""; whole = false }) { Text(stringResource(R.string.library_select_none)) }
                            }
                        }
                    }
                    items(rows, key = { r -> r.heading?.let { "h:$it" } ?: if (r.isWhole) "whole" else "p:${r.index}" }) { r ->
                        when {
                            r.heading != null -> Text(r.heading.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                            r.isWhole -> CheckRow(whole, stringResource(R.string.library_whole), pluralStringResource(R.plurals.library_songs_as_before, lib.trackCount, numbers.format(lib.trackCount))) { whole = it }
                            else -> {
                                val pl = choices[r.index]
                                val songs = pluralStringResource(R.plurals.library_songs, pl.size, numbers.format(pl.size))
                                CheckRow(r.index in ticked, pl.name, if (pl.kind == ItunesLibrary.Kind.SMART) stringResource(R.string.library_smart_songs, songs) else songs) { set(r.index, it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, title: String, sub: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

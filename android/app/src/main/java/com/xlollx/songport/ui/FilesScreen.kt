package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The file playlists (imports and backups), on their own screen: a search field, one section per
 * origin ("Spotify - Rap" sits under "Spotify" as "Rap"), one compact row per playlist and a menu
 * with send, export and delete. Dozens of backups stay browsable instead of stretching a card for pages.
 * A long press (or Select in a row's menu) starts a selection: whole groups or everything shown can
 * be ticked, then deleted at once or exported into a folder.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(onClose: () -> Unit, onSendTo: (id: String, name: String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lists by remember { mutableStateOf<List<LocalFilesProvider.Entry>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openVersions by remember { mutableStateOf<Set<String>>(emptySet()) }
    // File ids ticked; not empty = selection mode (back clears it before leaving the screen).
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var folderFormat by remember { mutableStateOf<LocalFilesProvider.Export?>(null) }
    var choosingFolderFormat by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        lists = withContext(Dispatchers.IO) { LocalFilesProvider.entries(ctx) }
        selected = selected.filter { id -> lists.any { it.id == id } }.toSet()
    }
    ScreenBackHandler { if (selected.isNotEmpty()) selected = emptySet() else onClose() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }

    // The ticked files into a folder, each in the chosen format; a name already there gets " (2)".
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val format = folderFormat
        folderFormat = null
        if (tree == null || format == null) return@rememberLauncherForActivityResult
        val ids = selected.toList()
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = ctx.contentResolver
                    val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    ids.count { id ->
                        val name = id.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + format.extension
                        val doc = DocumentsContract.createDocument(resolver, dirUri, format.mime, name) ?: return@count false
                        resolver.openOutputStream(doc)?.bufferedWriter()?.use { it.write(LocalFilesProvider.exportText(ctx, id, format)) } != null
                    }
                }
            }
            written.onSuccess { n -> selected = emptySet(); snackbar.showSnackbar(ctx.resources.getQuantityString(R.plurals.files_exported_n, n, n)) }
                .onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.error_generic)) }
        }
    }
    if (choosingFolderFormat) ExportFormatDialog(onDismiss = { choosingFolderFormat = false }) { fmt ->
        choosingFolderFormat = false
        folderFormat = fmt
        pickFolder.launch(null)
    }
    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.files_delete_n, n, n)) },
            text = { Text(stringResource(R.string.files_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val ids = selected
                    scope.launch {
                        withContext(Dispatchers.IO) { ids.forEach { LocalFilesProvider.delete(ctx, it) } }
                        selected = emptySet(); version++
                        snackbar.showSnackbar(ctx.resources.getQuantityString(R.plurals.files_deleted_n, ids.size, ids.size))
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // One launcher per format: CreateDocument fixes the MIME type when it is built.
    var exporting by remember { mutableStateOf<String?>(null) }
    fun writeExport(uri: Uri?, format: LocalFilesProvider.Export) {
        val id = exporting
        exporting = null
        if (uri == null || id == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(LocalFilesProvider.exportText(ctx, id, format)) }
            }
            snackbar.showSnackbar(ctx.getString(R.string.csv_exported))
        }
    }
    val export = rememberPlaylistExporter { uri, format -> writeExport(uri, format) }

    val filtered = lists.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    // Backups group under their service, then by playlist: the newest version is the row, the
    // older ones fold under it. Imports and other files sit under a plain heading.
    val groups = filtered.groupBy { it.service ?: "" }
        .mapValues { (_, items) -> items.groupBy { it.base }.values.map { v -> v.sortedByDescending { it.writtenAt } }.sortedBy { it.first().title.lowercase() } }
        .toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
    val total = lists.sumOf { it.trackCount.coerceAtLeast(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                title = { Text(pluralStringResource(R.plurals.files_selected, selected.size, selected.size)) },
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                actions = {
                    val shownIds = filtered.map { it.id }
                    val allShown = shownIds.isNotEmpty() && shownIds.all { it in selected }
                    TextButton(onClick = { selected = if (allShown) selected - shownIds.toSet() else selected + shownIds }) {
                        Text(stringResource(if (allShown) R.string.tracks_unselect_shown else R.string.tracks_select_shown))
                    }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.files_export_folder)) }, onClick = { more = false; choosingFolderFormat = true })
                            DropdownMenuItem(
                                text = { Text(pluralStringResource(R.plurals.files_delete_n, selected.size, selected.size), color = MaterialTheme.colorScheme.error) },
                                onClick = { more = false; confirmDelete = true },
                            )
                        }
                    }
                },
            ) else TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.files_title))
                        Text(stringResource(R.string.files_summary, lists.size, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                actions = { HelpButton(Helps.files) },
            )
        },
    ) { padding ->
        if (lists.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.csv_empty), textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lists.size > 6) item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.files_search)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            groups.forEach { (group, playlists) ->
                item(key = "g-$group") {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // While selecting, a group's box ticks all its files, older versions too.
                        if (selected.isNotEmpty()) {
                            val ids = playlists.flatten().map { it.id }
                            val all = ids.all { it in selected }
                            Checkbox(checked = all, onCheckedChange = { selected = if (all) selected - ids.toSet() else selected + ids })
                        }
                        Text(
                            (group.ifEmpty { stringResource(R.string.files_group_other) }) + " · " + playlists.size,
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(playlists, key = { "p-" + it.first().base }) { versions ->
                    val latest = versions.first()
                    val key = latest.base
                    val open = key in openVersions
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column {
                            FileRow(
                                title = latest.title, entry = latest,
                                selecting = selected.isNotEmpty(), checked = latest.id in selected, onToggle = { toggle(latest.id) },
                                onSend = { onSendTo(latest.id, latest.title) },
                                onExport = { format -> exporting = latest.id; export(format, latest.name) },
                                onDelete = { LocalFilesProvider.delete(ctx, latest.id); version++ },
                            )
                            if (versions.size > 1) {
                                TextButton(onClick = { openVersions = if (open) openVersions - key else openVersions + key }, modifier = Modifier.padding(start = 4.dp)) {
                                    Text(
                                        if (open) stringResource(R.string.files_hide_versions)
                                        else pluralStringResource(R.plurals.files_versions, versions.size - 1, versions.size - 1),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (open) versions.drop(1).forEach { v ->
                                    FileRow(
                                        title = null, entry = v,
                                        selecting = selected.isNotEmpty(), checked = v.id in selected, onToggle = { toggle(v.id) },
                                        onSend = { onSendTo(v.id, v.name) },
                                        onExport = { format -> exporting = v.id; export(format, v.name) },
                                        onDelete = { LocalFilesProvider.delete(ctx, v.id); version++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One file: its name (or just the date for an older version), track count and when it was written,
 * with the menu; while [selecting], a box instead of the menu and a tap ticks it. A long press, or
 * Select in the menu, starts the selection with this file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    title: String?, entry: LocalFilesProvider.Entry,
    selecting: Boolean, checked: Boolean, onToggle: () -> Unit,
    onSend: () -> Unit,
    onExport: (LocalFilesProvider.Export) -> Unit, onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onToggle, onClickLabel = if (selecting) stringResource(R.string.files_select) else null)
            .padding(start = if (selecting) 0.dp else 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.tracks_count, entry.trackCount) + " · " + formatDate(entry.writtenAt),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selecting) return@Row
        var menu by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        if (choosing) ExportFormatDialog(onDismiss = { choosing = false }) { choosing = false; onExport(it) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.csv_export)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // The way from an imported list to a service: the editor opens with this file as source.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_send_to)) },
                    onClick = { menu = false; onSend() },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.files_select)) }, onClick = { menu = false; onToggle() })
                ExportMenuItem { menu = false; choosing = true }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

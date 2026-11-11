package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import com.xlollx.songport.sync.BackupExport
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.Tools
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which tool is open in its own page; null = the list. The names are ids: keep them stable (a
 * notification asks for a tool by name, see MainActivity.TOOL_UNPLAYABLE). [descRes] is the one
 * line under the tool's name in the list; each tool's page explains itself at more length.
 */
private enum class Tool(val icon: androidx.compose.ui.graphics.vector.ImageVector, val titleRes: Int, val descRes: Int, val needsService: Boolean) {
    FILES(Icons.Filled.Folder, R.string.files_title, R.string.tools_row_files, false),
    AI(Icons.Filled.AutoAwesome, R.string.ai_title, R.string.lb_row_desc, false),
    MANAGE(Icons.Filled.Edit, R.string.tools_manage_title, R.string.tools_row_manage, true),
    EXPORT(Icons.Filled.Download, R.string.tools_export_title, R.string.tools_row_export, true),
    MAKE(Icons.Filled.PlaylistAdd, R.string.tools_make_title, R.string.tools_row_make, true),
    TRANSFER(Icons.Filled.SwapHoriz, R.string.tools_transfer_title, R.string.tools_row_transfer, true),
    BACKUP(Icons.Filled.Backup, R.string.backup_title, R.string.backup_row_desc, false),
    DEDUPE(Icons.Filled.CleaningServices, R.string.tools_dedupe_title, R.string.tools_row_dedupe, true),
    SCAN(Icons.Filled.ContentCopy, R.string.tools_scan_title, R.string.tools_row_scan, true),
    STATS(Icons.Filled.Insights, R.string.stats_title, R.string.stats_row_desc, false),
    UNPLAYABLE(Icons.Filled.MusicOff, R.string.tools_unplayable_title, R.string.tools_row_unplayable, true),
    MBTAGS(Icons.Filled.Sell, R.string.tools_mbtags_title, R.string.tools_row_mbtags, true),
    WANTED(Icons.Filled.Checklist, R.string.tools_wanted_title, R.string.tools_row_wanted, false),
    SERVER(Icons.Filled.Cloud, R.string.server_title, R.string.server_row_desc, false),
    SHARED(Icons.Filled.Groups, R.string.shared_title, R.string.shared_row_desc, false),
    RECENT(Icons.Filled.History, R.string.recent_transfers_title, R.string.recent_transfers_row, false);

    /** Works on the service chosen at the top of the list (Transfer picks its own two). */
    val usesSelectedService: Boolean get() = needsService && this != TRANSFER

    /** False when the chosen service cannot do what the tool needs. */
    fun availableOn(p: MusicProvider): Boolean = when (this) {
        MAKE -> p.canCreatePlaylists
        DEDUPE -> p.canRemoveTracks
        UNPLAYABLE -> p.detectsUnplayable
        MBTAGS -> p.matchesByMbid
        else -> true
    }
}

/**
 * Strumenti, grouped by what you want to do: see your music, move and copy it, clean it up, share
 * it, keep it safe. Most tools work on one service, picked once at the top (its logo then marks
 * the tools that use it); with no service connected those tools say so and point to Accounts.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(snackbar: SnackbarHostState, onManageFiles: () -> Unit = {}, onSendTo: (id: String, name: String) -> Unit = { _, _ -> }, onTransfer: () -> Unit = {}, onSyncStarted: () -> Unit = {}, onManage: (MusicProvider) -> Unit = {}, onScan: (MusicProvider) -> Unit = {}, onUnplayable: (MusicProvider) -> Unit = {}, onSubpage: (Boolean) -> Unit = {}, onGoToAccounts: () -> Unit = {}, onStats: () -> Unit = {}, onBackup: () -> Unit = {}, openDedupe: String? = null, onDedupeOpened: () -> Unit = {}, openAiSetup: Boolean = false, onAiSetupOpened: () -> Unit = {}, onReview: (reportId: String) -> Unit = {}) {
    val ctx = LocalContext.current
    val connected = Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) }
    var selectedId by rememberSaveable { mutableStateOf(connected.firstOrNull()?.id) }
    val provider = connected.firstOrNull { it.id == selectedId } ?: connected.firstOrNull()
    // Saved: a rotation or a fold keeps the open tool on screen.
    var open by rememberSaveable { mutableStateOf<Tool?>(null) }
    // "Clean up duplicates" from an idea on the Syncs tab: the tool, on that service.
    LaunchedEffect(openDedupe) {
        val id = openDedupe ?: return@LaunchedEffect
        if (connected.any { it.id == id }) { selectedId = id; open = Tool.DEDUPE }
        onDedupeOpened()
    }
    // The Roast without an AI: the generator, on its AI setup.
    LaunchedEffect(openAiSetup) {
        if (!openAiSetup) return@LaunchedEffect
        AiDraft.setupAsked = true; open = Tool.AI
        onAiSetupOpened()
    }
    val storeData = remember { com.xlollx.songport.data.Store.get(ctx) }.state.collectAsState().value
    val hasOneOffs = storeData.jobs.any { it.oneOff }
    val hasServerSync = storeData.syncs.any { com.xlollx.songport.sync.Wanted.isServer(it.target.provider) }
    val serverOn = remember { com.xlollx.songport.sync.ServerLink.isOn(ctx) }
    // The list's position, kept while a tool's page is open: back lands on the tool just used.
    val listScroll = rememberScrollState()

    val current = open
    fun close() { open = null }
    // A tool's page has its own top bar with the way back: the tab's "Tools" header steps aside,
    // so there is one header, not two stacked.
    DisposableEffect(current != null) {
        onSubpage(current != null)
        onDispose { onSubpage(false) }
    }
    if (current != null) {
        // The tool's own page: its card, full width, with a snackbar of its own (the tab's is behind us).
        val local = remember { SnackbarHostState() }
        BackHandler { close() }
        // Nested in the tab, which keeps the status-bar inset (no second gap); the end of the page
        // clears the glass bottom bar.
        Scaffold(
            // Above the glass bottom bar, which the page runs under: a snackbar there would be hidden by it.
            snackbarHost = { SnackbarHost(local, Modifier.padding(bottom = LocalBottomBarInset.current)) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.titleRes)) },
                    navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current.needsService && provider != null && connected.size > 1) {
                    ServicePicker(connected, provider.id, { selectedId = it })
                }
                when (current) {
                    Tool.FILES -> FilesCard(local, onManage = onManageFiles, onSendTo = onSendTo)
                    Tool.AI -> AiPlaylistCard(local, onSyncStarted)
                    Tool.EXPORT -> provider?.let { ExportCard(it, local) }
                    Tool.MAKE -> provider?.let { MakePlaylistCard(it, local) }
                    Tool.BACKUP -> Unit
                    Tool.DEDUPE -> provider?.let { DedupeCard(it, local) }
                    Tool.MBTAGS -> provider?.let { MbTagsCard(it, local) }
                    Tool.WANTED -> WantedCard(local)
                    Tool.SERVER -> ServerSyncsPage(local)
                    Tool.RECENT -> RecentTransfersPage(onReview)
                    else -> {}
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(listScroll)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // The service the playlist tools work on, chosen once, in plain sight.
        if (provider == null) {
            PrefSection(stringResource(R.string.tools_service_title)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.empty_tools_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.empty_tools_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onGoToAccounts, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tools_go_accounts))
                    }
                }
            }
        } else {
            PrefSection(stringResource(R.string.tools_service_title), description = stringResource(R.string.tools_service_hint)) {
                ServicePicker(connected, provider.id, { selectedId = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        PrefSection(stringResource(R.string.tools_section_music), accent = JobColor.BLUE, description = stringResource(R.string.tools_section_music_desc)) {
            ToolRow(Tool.STATS, provider) { onStats() }
            ToolRow(Tool.MANAGE, provider) { onManage(it!!) }
            ToolRow(Tool.SCAN, provider) { onScan(it!!) }
            // Only for who syncs to a server of their own: the songs it still lacks.
            if (hasServerSync) ToolRow(Tool.WANTED, provider) { open = Tool.WANTED }
            // Only with "Coordinate with my server" set: the server's syncs, run and reviewed from here.
            if (serverOn) ToolRow(Tool.SERVER, provider) { open = Tool.SERVER }
        }

        PrefSection(stringResource(R.string.tools_section_move), accent = JobColor.TEAL, description = stringResource(R.string.tools_section_move_desc)) {
            ToolRow(Tool.TRANSFER, provider) { onTransfer() }
            ToolRow(Tool.EXPORT, provider) { open = Tool.EXPORT }
            ToolRow(Tool.MAKE, provider) { open = Tool.MAKE }
            ToolRow(Tool.FILES, provider) { open = Tool.FILES }
            // The one-off transfers (a setlist to a playlist): not syncs, kept a week for their review.
            if (hasOneOffs) ToolRow(Tool.RECENT, provider) { open = Tool.RECENT }
        }

        PrefSection(stringResource(R.string.tools_section_clean), accent = JobColor.ORANGE, description = stringResource(R.string.tools_section_clean_desc)) {
            ToolRow(Tool.DEDUPE, provider) { open = Tool.DEDUPE }
            ToolRow(Tool.UNPLAYABLE, provider) { onUnplayable(it!!) }
            // Only for who has a personal server: nobody else has files to tag.
            if (connected.any { it.matchesByMbid }) ToolRow(Tool.MBTAGS, provider) { open = Tool.MBTAGS }
        }

        PrefSection(stringResource(R.string.tools_section_share), accent = JobColor.PINK, description = stringResource(R.string.tools_section_share_desc)) {
            ToolRow(Tool.AI, provider, beta = true) { open = Tool.AI }
            ToolRow(Tool.SHARED, provider) { SharedUi.request.value = SharedUi.Request.List }
        }

        PrefSection(stringResource(R.string.tools_section_safety), accent = JobColor.GREEN, description = stringResource(R.string.tools_section_safety_desc)) {
            ToolRow(Tool.BACKUP, provider) { onBackup() }
        }
    }
}

/**
 * One tool in the list: icon, name, one line of what it does. A tool that works on the chosen
 * service ends with that service's logo; with no service, or one that cannot do it, the row is
 * dimmed and says why instead. [onClick] gets the chosen service (null only for tools without one).
 */
@Composable
private fun ToolRow(tool: Tool, provider: MusicProvider?, beta: Boolean = false, onClick: (MusicProvider?) -> Unit) {
    val reason: String? = when {
        !tool.needsService -> null
        provider == null -> stringResource(R.string.tools_none)
        !tool.availableOn(provider) -> stringResource(R.string.tools_unavailable_on, provider.displayName)
        else -> null
    }
    val showService = reason == null && provider != null && tool.usesSelectedService
    PrefRow(
        tool.icon,
        stringResource(tool.titleRes),
        reason ?: stringResource(tool.descRes),
        enabled = reason == null,
        badge = if (beta) { { StatusPill(stringResource(R.string.beta), Tone.Neutral) } } else null,
        trailing = if (showService) { {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(provider, 24.dp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } else null,
    ) { onClick(provider) }
}

/** Una playlist qualsiasi del servizio diventa un file CSV o M3U scelto dal selettore di sistema. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember(provider.id) { mutableStateOf<List<Playlist>?>(null) }
    var selected by remember(provider.id) { mutableStateOf<Playlist?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf<LocalFilesProvider.Export?>(null) }

    LaunchedEffect(provider.id) {
        lists = try {
            val base = provider.playlists(ctx)
            provider.libraryEntries(ctx, asTarget = false) + base
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            emptyList()
        }
    }

    fun write(uri: Uri?) {
        val pl = selected
        val fmt = pendingFormat
        pendingFormat = null
        if (uri == null || pl == null || fmt == null) return
        busy = true
        scope.launch {
            try {
                val tracks = provider.tracks(ctx, pl.id)
                val text = fmt.encode(pl.name, tracks)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                }
                snackbar.showSnackbar(ctx.getString(R.string.tools_export_done, tracks.size))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            }
            busy = false
        }
    }
    val export = rememberPlaylistExporter { uri, _ -> write(uri) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tools_export_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                HelpButton(Helps.export)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_export_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val items = lists
            if (items == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading)) }
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.editor_playlist)) },
                        placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { selected = p; expanded = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                val pl = selected
                var formats by remember { mutableStateOf(false) }
                Box {
                    Button(enabled = !busy && pl != null, onClick = { formats = true }) { Text(stringResource(R.string.tools_export_save)) }
                    DropdownMenu(expanded = formats, onDismissRequest = { formats = false }) {
                        ExportMenuItems { fmt -> formats = false; pendingFormat = fmt; export(fmt, pl!!.name) }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupeCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember(provider.id) { mutableStateOf<List<Playlist>?>(null) }
    var selected by remember(provider.id) { mutableStateOf<Playlist?>(null) }
    var groups by remember(provider.id) { mutableStateOf<List<List<Track>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(provider.id) {
        lists = try { provider.playlists(ctx).filter { it.ownedByMe } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tools_dedupe_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                HelpButton(Helps.dedupe)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_dedupe_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!provider.canRemoveTracks) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.error_no_removals, provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Spacer(Modifier.height(10.dp))
            val items = lists
            if (items == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading)) }
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.editor_playlist)) },
                        placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { selected = p; groups = null; expanded = false })
                        }
                    }
                }
            }
            val g = groups
            if (g != null) {
                Spacer(Modifier.height(8.dp))
                if (g.isEmpty()) Text(stringResource(R.string.tools_dedupe_none))
                else {
                    val extras = g.sumOf { it.size - 1 }
                    Text(stringResource(R.string.tools_dedupe_found, g.size, extras), style = MaterialTheme.typography.bodyMedium)
                    g.take(10).forEach { grp -> Text("• ${grp.first()} ×${grp.size}", style = MaterialTheme.typography.bodySmall) }
                    if (g.size > 10) Text(stringResource(R.string.preview_more, g.size - 10), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    progress?.let { Text(stringResource(R.string.tools_progress_dedupe, it.done, it.total), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp)) }
                }
                val pl = selected
                OutlinedButton(enabled = !busy && pl != null, onClick = {
                    busy = true
                    scope.launch {
                        groups = try { Tools.findDuplicates(ctx, provider, pl!!.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)); null
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.tools_dedupe_scan)) }
                val extras = g?.sumOf { it.size - 1 } ?: 0
                if (extras > 0) {
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && pl != null, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val n = Tools.removeDuplicates(ctx, provider, pl!!.id) { progress = it }
                                snackbar.showSnackbar(ctx.getString(R.string.tools_dedupe_done, n))
                                groups = null
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
                            }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.tools_dedupe_remove, extras)) }
                }
            }
        }
    }
}

/**
 * The songs of a personal server whose files carry no MusicBrainz tag: a sync finds those by title
 * only. A count, the first of them, and where to learn tagging with Picard; the files are never touched.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MbTagsCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(provider.id) { mutableStateOf<LibraryTools.MbidTags?>(null) }
    var busy by remember { mutableStateOf(false) }
    var read by remember { mutableStateOf(0) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tools_mbtags_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_mbtags_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!provider.matchesByMbid) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.tools_unavailable_on, provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val r = result
            if (r != null) {
                Spacer(Modifier.height(8.dp))
                when {
                    r.total == 0 -> Text(stringResource(R.string.tools_mbtags_empty))
                    r.untagged.isEmpty() -> Text(stringResource(R.string.tools_mbtags_all, r.total))
                    else -> {
                        Text(stringResource(R.string.tools_mbtags_result, r.untagged.size, r.total), style = MaterialTheme.typography.bodyMedium)
                        r.untagged.take(30).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        if (r.untagged.size > 30) Text(stringResource(R.string.preview_more, r.untagged.size - 30), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tools_mbtags_progress, read), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { openUrl(ctx, LibraryTools.PICARD_GUIDE) }) { Text(stringResource(R.string.tools_mbtags_picard)) }
                Button(enabled = !busy, onClick = {
                    busy = true; read = 0
                    scope.launch {
                        result = try { Tools.untaggedSongs(ctx, provider) { read = it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)); null
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.tools_mbtags_scan)) }
            }
        }
    }
}

/**
 * Tools › Recent transfers: the one-off transfers ([com.xlollx.songport.sync.OneOffs]), the newest
 * first: where they went, when, what they did, the review of the songs not found, and Delete (the
 * playlist made or added to stays as it is). Each goes by itself once reviewed or after a week.
 */
@Composable
private fun RecentTransfersPage(onReview: (String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { com.xlollx.songport.data.Store.get(ctx) }
    LaunchedEffect(Unit) { store.dropOldOneOffs() }
    val data by store.state.collectAsState()
    val list = com.xlollx.songport.sync.OneOffs.recent(data)
    Text(stringResource(R.string.recent_transfers_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (list.isEmpty()) Text(stringResource(R.string.recent_transfers_none), style = MaterialTheme.typography.bodyMedium)
    list.forEach { job ->
        val report = job.lastReportId?.let { r -> data.reports.firstOrNull { it.id == r } }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(job.name, style = MaterialTheme.typography.titleSmall)
                val where = Providers.byId(job.target.provider)?.label(ctx) ?: job.target.provider
                val at = android.text.format.DateUtils.getRelativeTimeSpanString(job.createdEpoch, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                Text("$where · $at", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (report == null) Text(stringResource(R.string.setlists_adding, where), style = MaterialTheme.typography.bodySmall)
                else {
                    val sum = com.xlollx.songport.sync.Setlists.summary(report)
                    report.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text(stringResource(R.string.setlists_summary, sum.added, sum.alreadyThere, sum.notFound), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    val toFix = report?.let { com.xlollx.songport.sync.OneOffs.toReview(it) } ?: 0
                    TextButton(onClick = { store.deleteJob(job.id) }) { Text(stringResource(R.string.delete)) }
                    if (toFix > 0) TextButton(onClick = { onReview(report!!.id) }) { Text(stringResource(R.string.review_now, toFix)) }
                }
            }
        }
    }
}

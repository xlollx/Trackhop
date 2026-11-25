package com.xlollx.songport.ui

import com.xlollx.songport.ai.config
import com.xlollx.songport.providers.*
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.BulkEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Le playlist dell'utente su un servizio, con le azioni che il servizio permette: rinominare,
 * eliminare, esportare. Solo le proprie (quelle seguite di altri non si toccano). Eliminare una
 * playlist usata da una sync fa fallire la sync finche' non la si modifica: lo si dice prima. Le
 * sync che scrivono in una playlist eliminata si spengono ([BulkEdit.offForDeleted]). Una pressione
 * lunga avvia la selezione, per eliminarne diverse insieme.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(provider: MusicProvider, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { Store.get(ctx) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    // The playlist open, being renamed or being extended is still so after a rotation.
    var renaming by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(null) }
    var copyingToUser by remember { mutableStateOf<Playlist?>(null) }
    var expanding by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(null) }
    val aiReady = remember { com.xlollx.songport.ai.AiClient.config(ctx)?.complete == true } || com.xlollx.songport.ai.NanoAi.state.offered
    expanding?.let { pl ->
        AiExpandPlaylistDialog(provider, pl, onClose = { expanding = null }) { added, missing, alreadyThere ->
            scope.launch {
                val parts = listOfNotNull(
                    ctx.getString(R.string.ai_expand_done, added, pl.name),
                    if (missing > 0) ctx.getString(R.string.ai_expand_missing, missing, provider.label(ctx)) else null,
                    if (alreadyThere > 0) ctx.getString(R.string.ai_expand_already, alreadyThere) else null,
                )
                snackbar.showSnackbar(parts.joinToString(" · "))
            }
            version++
        }
    }
    var deleting by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(null) }
    var busy by remember { mutableStateOf(false) }
    var viewing by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(null) }
    var choosingFormat by remember { mutableStateOf<Playlist?>(null) }
    viewing?.let { pl ->
        PlaylistTracksScreen(provider, pl, onClose = { viewing = null }, onChanged = { version++ })
        return
    }
    ScreenBackHandler(onClose)

    LaunchedEffect(version) {
        lists = null; error = null
        // Liked songs, saved albums, followed artists and podcasts first, where the service can write them; then the own playlists.
        lists = try { provider.libraryEntries(ctx, asTarget = true).filter { it.id in MANAGED_LIBRARY } + provider.playlists(ctx).filter { it.ownedByMe } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.userMessage(ctx); emptyList() }
    }
    fun fail(e: Exception) { scope.launch { snackbar.showSnackbar(e.userMessage(ctx)) } }
    /** The syncs that wrote to [ids], deleted just now, turned off (a pair's other half too): how many were on. */
    fun turnOffSyncsOf(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val n = BulkEdit.writingTo(store.data, provider.id, ids).count { it.enabled }
        if (n == 0) return 0
        val outcome = ManyChange.apply(ctx) { BulkEdit.offForDeleted(it, provider.id, ids) }
        ManyChange.problem(ctx, outcome)?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
        return if (outcome == ManyChange.Outcome.NOT_SAVED) 0 else n
    }
    fun turnedOff(n: Int) = if (n > 0) ctx.resources.getQuantityString(R.plurals.manage_syncs_turned_off, n, n) else null
    /** A playlist this screen can delete: an own one, where the service lets apps delete. */
    fun deletable(pl: Playlist) = provider.canDeletePlaylists && !MusicProvider.isLibrary(pl.id)

    // Playlists ticked to delete together (a long press starts it); those gone meanwhile drop out.
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirmMany by remember { mutableStateOf(false) }
    val deletableIds = lists.orEmpty().filter { deletable(it) }.map { it.id }
    val selection = deletableIds.filterTo(LinkedHashSet()) { it in picked }
    fun tick(pl: Playlist) { picked = if (pl.id in selection) selection - pl.id else selection + pl.id }
    val selectionBar = if (selection.isEmpty()) null else ToolSelection(
        count = selection.size, onClear = { picked = emptySet() },
        onSelectAll = { picked = deletableIds.toSet() }.takeIf { selection.size < deletableIds.size },
        actions = { TextButton(enabled = !busy, onClick = { confirmMany = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
    )
    OfferToolSelection(selectionBar)
    if (confirmMany) {
        val chosen = lists.orEmpty().filter { it.id in selection }
        val n = chosen.size
        FitDialog(
            onDismissRequest = { confirmMany = false },
            title = { Text(pluralStringResource(R.plurals.manage_delete_n_title, n, n, provider.label(ctx))) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(chosen.joinToString("\n") { "• " + it.name }, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.manage_delete_n_body, provider.label(ctx)))
                }
            },
            confirmButton = {
                TextButton(enabled = n > 0, onClick = {
                    confirmMany = false; picked = emptySet(); busy = true
                    // One after another; one that fails does not stop the others.
                    scope.launch {
                        val gone = LinkedHashSet<String>()
                        var failed = 0
                        var reason: String? = null
                        var off = 0
                        try {
                            for (pl in chosen) try { provider.deletePlaylist(ctx, pl.id); gone += pl.id } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                failed++; if (reason == null) reason = e.userMessage(ctx)
                            }
                        } finally {
                            // Off the main thread (it writes the store), and done even when the screen goes meanwhile.
                            off = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { turnOffSyncsOf(gone) }
                            busy = false
                        }
                        version++
                        val parts = listOfNotNull(
                            if (gone.isNotEmpty()) ctx.resources.getQuantityString(R.plurals.manage_deleted_n, gone.size, gone.size) else null,
                            if (failed > 0) ctx.resources.getQuantityString(R.plurals.manage_delete_failed_n, failed, failed) + (reason?.let { ": $it" } ?: "") else null,
                            turnedOff(off),
                        )
                        snackbar.showSnackbar(parts.joinToString(" · "), duration = if (failed > 0) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short)
                    }
                }) { Text(pluralStringResource(R.plurals.manage_delete_n, n, n), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmMany = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // Export: the format is asked in a dialog, the tracks are read when the file destination is chosen.
    var exporting by remember { mutableStateOf<Playlist?>(null) }
    val export = rememberPlaylistExporter { uri: Uri?, fmt ->
        val pl = exporting
        exporting = null
        if (uri == null || pl == null) return@rememberPlaylistExporter
        scope.launch {
            try {
                val tracks = provider.tracks(ctx, pl.id)
                val text = LocalFilesProvider.encodeLooked(ctx.env, fmt, pl.name, tracks).first
                withContext(Dispatchers.IO) { com.xlollx.songport.data.DocumentWrite.text(ctx, uri, text) }
                snackbar.showSnackbar(ctx.getString(R.string.tools_export_done, tracks.size))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    choosingFormat?.let { pl ->
        ExportFormatDialog(onDismiss = { choosingFormat = null }, formats = LocalFilesProvider.Export.forShows(LocalFilesProvider.Export.isShows(pl.id))) { fmt -> choosingFormat = null; exporting = pl; export(fmt, pl.name) }
    }

    // Plex (2.2): a copy of the playlist in the library of a user the server is shared with.
    copyingToUser?.let { pl ->
        PlexCopyDialog(provider as PlexProvider, pl, onDismiss = { copyingToUser = null }) { msg -> copyingToUser = null; scope.launch { snackbar.showSnackbar(msg) } }
    }

    renaming?.let { pl ->
        var name by rememberSaveable(pl.id) { mutableStateOf(pl.name) }
        val close = rememberDiscardGuard(name.trim() != pl.name) { renaming = null }
        FitDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.manage_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.manage_rename_title)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() != pl.name, onClick = {
                    renaming = null; busy = true
                    scope.launch {
                        try {
                            provider.renamePlaylist(ctx, pl.id, name.trim())
                            // Syncs show the playlist by the name they saved: keep it current.
                            store.data.syncs.filter { j -> (j.source.provider == provider.id && j.source.playlistId == pl.id) || (j.target.provider == provider.id && j.target.playlistId == pl.id) }
                                .forEach { j ->
                                    val srcHit = j.source.provider == provider.id && j.source.playlistId == pl.id
                                    val dstHit = j.target.provider == provider.id && j.target.playlistId == pl.id
                                    // The file provider renames its id too and updates the jobs itself.
                                    if (provider.id != LocalFilesProvider.id) store.upsertJob(j.copy(
                                        source = if (srcHit) j.source.copy(playlistName = name.trim()) else j.source,
                                        target = if (dstHit) j.target.copy(playlistName = name.trim()) else j.target,
                                    ))
                                }
                            snackbar.showSnackbar(ctx.getString(R.string.manage_renamed))
                            version++
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { pl ->
        // The syncs writing to it are turned off; the others that use it (reading it) fail until edited.
        val writers = BulkEdit.writingTo(store.data, provider.id, setOf(pl.id))
        val writerIds = writers.flatMapTo(HashSet()) { listOfNotNull(it.id, it.linkedJobId) }
        val used = store.data.syncs.count { j -> j.id !in writerIds && ((j.source.provider == provider.id && j.source.playlistId == pl.id) || (j.target.provider == provider.id && j.target.playlistId == pl.id)) }
        FitDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.manage_delete_confirm, pl.name, provider.label(ctx)))
                    if (writers.isNotEmpty()) {
                        Spacer(Modifier.padding(4.dp))
                        Text(pluralStringResource(R.plurals.manage_delete_turns_off, writers.size, writers.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (used > 0) {
                        Spacer(Modifier.padding(4.dp))
                        Text(pluralStringResource(R.plurals.manage_delete_used, used, used), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null; busy = true
                    scope.launch {
                        try {
                            provider.deletePlaylist(ctx, pl.id)
                            val off = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { turnOffSyncsOf(setOf(pl.id)) }
                            version++
                            snackbar.showSnackbar(listOfNotNull(ctx.getString(R.string.manage_deleted), turnedOff(off)).joinToString(" · "))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.yes), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.no)) } },
        )
    }

    fun setVisibility(pl: Playlist, public: Boolean) {
        busy = true
        scope.launch {
            try {
                provider.setPlaylistVisibility(ctx, pl.id, public)
                snackbar.showSnackbar(ctx.getString(if (public) R.string.manage_now_public else R.string.manage_now_private))
                version++
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
            busy = false
        }
    }

    val rich = richMotion()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionBar != null) SelectionTopBar(selectionBar)
            else TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderBadge(provider, 28.dp, accountRing(provider.id))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.manage_title, provider.label(ctx)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                actions = { if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).width(20.dp)) },
            )
        },
    ) { padding ->
        val all = lists
        when {
            all == null -> SkeletonRows(12, leading = 44.dp, modifier = Modifier.padding(padding).padding(top = 8.dp))
            error != null && isNetText(ctx, error) -> NetErrorState(error, onRetry = { version++ }, modifier = Modifier.padding(padding))
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(24.dp))
            all.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.manage_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(all, key = { it.id }) { pl ->
                    val library = MusicProvider.isLibrary(pl.id)
                    val removable = provider.canRemoveTracks && provider.canWrite
                    val selecting = selection.isNotEmpty()
                    val canTick = deletable(pl)
                    val ticked = pl.id in selection
                    Card(
                        (if (rich) Modifier.animateItem() else Modifier).fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (ticked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            // A library list opens on a tap: removing from it is all there is to do there. A long
                            // press on a playlist that can be deleted starts the selection; while selecting, a tap ticks.
                            when {
                                selecting && canTick -> Modifier.combinedClickable(enabled = !busy, onClickLabel = stringResource(R.string.files_select), onClick = { tick(pl) }, onLongClick = { tick(pl) })
                                selecting -> Modifier
                                canTick -> Modifier.combinedClickable(enabled = !busy, onLongClickLabel = stringResource(R.string.files_select), onClick = {}, onLongClick = { tick(pl) })
                                library && removable -> Modifier.combinedClickable(enabled = !busy, onClick = { viewing = pl })
                                else -> Modifier
                            }.padding(start = if (selecting && canTick) 0.dp else 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting && canTick) Checkbox(checked = ticked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            if (library) {
                                Icon(libraryIcon(pl.id), null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(
                                    pl.trackCount.takeIf { it >= 0 }?.let { stringResource(R.string.tracks_count, it) },
                                    pl.isPublic?.let { stringResource(if (it) R.string.playlist_public else R.string.playlist_private) },
                                )
                                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            var menu by remember { mutableStateOf(false) }
                            if (!selecting) Box {
                                IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (library) {
                                        if (removable) DropdownMenuItem(text = { Text(stringResource(R.string.remove) + "…") }, onClick = { menu = false; viewing = pl })
                                        ExportMenuItem { menu = false; choosingFormat = pl }
                                        return@DropdownMenu
                                    }
                                    if (provider.canRenamePlaylists) DropdownMenuItem(text = { Text(stringResource(R.string.manage_rename)) }, onClick = { menu = false; renaming = pl })
                                    // Both entries when the listing does not say which state the playlist is in.
                                    if (provider.canSetVisibility && pl.isPublic != true) DropdownMenuItem(text = { Text(stringResource(R.string.manage_make_public)) }, onClick = { menu = false; setVisibility(pl, true) })
                                    if (provider.canSetVisibility && pl.isPublic != false) DropdownMenuItem(text = { Text(stringResource(R.string.manage_make_private)) }, onClick = { menu = false; setVisibility(pl, false) })
                                    if (provider.canRemoveTracks && provider.canWrite) DropdownMenuItem(text = { Text(stringResource(R.string.manage_tracks)) }, onClick = { menu = false; viewing = pl })
                                    if (aiReady && provider.canWrite) DropdownMenuItem(text = { Text(stringResource(R.string.ai_expand)) }, onClick = { menu = false; expanding = pl })
                                    ExportMenuItem { menu = false; choosingFormat = pl }
                                    if (provider is PlexProvider) DropdownMenuItem(text = { Text(stringResource(R.string.plex_copy_menu)) }, onClick = { menu = false; copyingToUser = pl })
                                    if (provider.canDeletePlaylists) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; deleting = pl },
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

/** The library lists this screen manages: liked songs and what is saved or followed, not plays. */
private val MANAGED_LIBRARY = setOf(MusicProvider.LIKED_ID, MusicProvider.ALBUMS_ID, MusicProvider.ARTISTS_ID, MusicProvider.PODCASTS_ID)

private fun libraryIcon(id: String) = when (id) {
    MusicProvider.LIKED_ID -> Icons.Filled.Favorite
    MusicProvider.ALBUMS_ID -> Icons.Filled.Album
    MusicProvider.ARTISTS_ID -> Icons.Filled.Person
    else -> Icons.Filled.Podcasts
}

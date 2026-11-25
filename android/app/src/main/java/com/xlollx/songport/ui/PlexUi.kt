package com.xlollx.songport.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.launch

/** The Plex servers connected now: the "Sounds like" card shows only with one. */
internal fun plexServers(ctx: android.content.Context): List<PlexProvider> =
    Providers.connectors().filterIsInstance<PlexProvider>().filter { runCatching { it.isConnected(ctx.env) }.getOrDefault(false) }

/**
 * Discover › Sounds like (Plex), 2.2: songs that sound like one picked on the server, or a journey
 * from one song to another (Plex's sonic analysis, Plex Pass), saved on Plex or sent to any service.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SonicPage(snackbar: SnackbarHostState, onNewSyncFromFile: (name: String, fileId: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = remember { plexServers(ctx) }
    if (servers.isEmpty()) { ToolNote(stringResource(R.string.sonic_none_connected)); return }
    var serverId by rememberSaveable { mutableStateOf(servers.first().id) }
    val plex = servers.firstOrNull { it.id == serverId } ?: servers.first()
    var adventure by rememberSaveable { mutableStateOf(false) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var from by remember { mutableStateOf<Track?>(null) }
    var to by remember { mutableStateOf<Track?>(null) }
    var result by remember { mutableStateOf<List<Track>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (servers.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Another server: the songs picked on this one are not its songs (the same ids are others there).
        servers.forEach { s -> FilterChip(selected = s.id == plex.id, onClick = { if (s.id != plex.id) { from = null; to = null }; serverId = s.id; result = null }, label = { Text(s.label(ctx.env)) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !adventure, onClick = { adventure = false; result = null }, label = { Text(stringResource(R.string.sonic_like)) })
        FilterChip(selected = adventure, onClick = { adventure = true; result = null }, label = { Text(stringResource(R.string.sonic_adventure)) })
    }
    OutlinedTextField(value = fromText, onValueChange = { fromText = it; from = null }, singleLine = true, enabled = !busy,
        label = { Text(stringResource(R.string.sonic_from)) }, modifier = Modifier.fillMaxWidth())
    from?.let { Text("${it.artistLine} — ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (adventure) {
        OutlinedTextField(value = toText, onValueChange = { toText = it; to = null }, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.sonic_to)) }, modifier = Modifier.fillMaxWidth())
        to?.let { Text("${it.artistLine} — ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    ToolPrimaryButton(stringResource(R.string.sonic_find), busy = busy, enabled = !busy && fromText.isNotBlank() && (!adventure || toText.isNotBlank()), onClick = {
        busy = true; error = null; result = null
        scope.launch {
            try {
                val env = ctx.env
                val a = from ?: plex.findSongs(env, fromText.trim()).firstOrNull() ?: throw IllegalStateException(ctx.getString(R.string.sonic_not_found, fromText.trim()))
                from = a
                result = if (adventure) {
                    val b = to ?: plex.findSongs(env, toText.trim()).firstOrNull() ?: throw IllegalStateException(ctx.getString(R.string.sonic_not_found, toText.trim()))
                    to = b
                    plex.sonicAdventure(env, a.id, b.id)
                } else plex.soundsLike(env, a.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "" }
            busy = false
        }
    })
    error?.let { ToolError(it.ifBlank { stringResource(R.string.error_generic) }) }
    val list = result ?: return
    if (list.isEmpty()) { ToolNote(stringResource(R.string.sonic_empty)); return }
    list.forEach { TrackLine(it, Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
    val name = if (adventure) stringResource(R.string.sonic_name_adventure, from?.title.orEmpty(), to?.title.orEmpty()) else stringResource(R.string.sonic_name_like, from?.title.orEmpty())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        // Any service: the songs as a File playlist, the source of a new sync.
        OutlinedButton(enabled = !busy, onClick = {
            val id = LocalFilesProvider.importTracks(ctx.env, name, list)
            onNewSyncFromFile(name, id)
        }) { Text(stringResource(R.string.sonic_send)) }
        ToolPrimaryButton(stringResource(R.string.sonic_save_plex), busy = busy, enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val made = plex.createPlaylist(ctx.env, name, "")
                    plex.addTracks(ctx.env, made.id, list)
                    snackbar.showSnackbar(ctx.getString(R.string.sonic_saved, name))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "" }
                busy = false
            }
        })
    }
}

/** Copies [playlist] to a user the Plex server is shared with (Plex Home): their names, one to pick, then the copy. */
@Composable
internal fun PlexCopyDialog(plex: PlexProvider, playlist: Playlist, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<PlexProvider.HomeUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(plex.id) {
        try { users = plex.homeUsers(ctx.env) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ""; users = emptyList() }
    }
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plex_copy_title, playlist.name)) },
        text = {
            Column {
                val list = users
                when {
                    list == null -> CircularProgressIndicator()
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    list.isEmpty() -> Text(stringResource(R.string.plex_copy_nobody))
                    else -> list.forEach { u ->
                        Row(Modifier.fillMaxWidth().clickable { picked = u.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = picked == u.id, onClick = { picked = u.id })
                            Spacer(Modifier.width(6.dp))
                            Text(u.name)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.plex_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && picked != null, onClick = {
                val user = users?.firstOrNull { it.id == picked } ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        plex.copyToUser(ctx.env, playlist.id, user.id)
                        onDone(ctx.getString(R.string.plex_copy_done, playlist.name, user.name))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ""; busy = false }
                }
            }) { Text(stringResource(R.string.plex_copy_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

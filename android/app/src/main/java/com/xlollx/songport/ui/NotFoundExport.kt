package com.xlollx.songport.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.MusicBrainz
import com.xlollx.songport.sync.Wanted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many songs not found there are, on every service, each once: the figure on the entry points. */
internal fun notFoundCount(data: StoreData): Int = Wanted.list(data, filter = Wanted.Filter(scope = Wanted.Scope.ALL)).size

/**
 * "Export the songs not found" (issue #73): the songs the syncs' last runs could not find, on every
 * service or on one, as a file (the formats of Missing on my server) or as a new File playlist to
 * sync on anywhere; never a playlist on the service they are missing on. Opened from Saved matches ›
 * To fix and from Tools. Always composed by its caller, shown or not: the file pickers' answers and
 * the messages after a save arrive here even once the sheet is closed. What it shows (the service
 * chosen, the name typed) is saved, so a rotation or the activity made anew keeps it.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NotFoundExport(show: Boolean, onDismiss: () -> Unit, snackbar: SnackbarHostState, onOpenFiles: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    // "" = every service.
    var on by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    fun list(): List<Wanted.Want> = Wanted.list(data, { MusicBrainz.known(ctx.env, it) }, Wanted.Filter(scope = Wanted.Scope.ALL, on = on.ifEmpty { null }))
    // A target's id ("ytm", "spotify_bridge"…) by its family's name: the web connectors' ids are no family of their own.
    fun serviceName(id: String) = providerOf(com.xlollx.songport.data.FileStore.family(id), ctx = ctx)?.displayName
        ?: com.xlollx.songport.providers.Providers.byId(id)?.displayName ?: com.xlollx.songport.ui.serviceName(id, ctx)
    val defaultName = if (on.isEmpty()) stringResource(R.string.not_found_default_name) else stringResource(R.string.not_found_default_name_on, serviceName(on))

    val formats = listOf(Wanted.Format.CSV, Wanted.Format.M3U, Wanted.Format.TEXT, Wanted.Format.JSON, Wanted.Format.LIDARR)
    val launchers = formats.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val wants = list()
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { com.xlollx.songport.data.DocumentWrite.text(ctx, uri, f.encode(wants)) }
                    snackbar.showSnackbar(ctx.getString(R.string.tools_export_done, wants.size))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    snackbar.showSnackbar(e.userMessage(ctx))
                }
            }
        }
    }

    fun save(playlistName: String) {
        val wants = list()
        saving = true
        onDismiss()
        scope.launch {
            try {
                val pl = withContext(Dispatchers.IO) { Wanted.saveAsFilePlaylist(ctx.env, playlistName, wants) }
                name = null
                val r = snackbar.showSnackbar(
                    ctx.resources.getQuantityString(R.plurals.not_found_saved, wants.size, wants.size, pl.name),
                    actionLabel = onOpenFiles?.let { ctx.getString(R.string.not_found_open) }, duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) onOpenFiles?.invoke()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage(ctx))
            } finally { saving = false }
        }
    }

    if (!show) return
    val services = remember(data.jobs, data.reports) { Wanted.missingOn(data) }
    // A service chosen before may have nothing left: back to all.
    if (on.isNotEmpty() && services.none { it.first == on }) on = ""
    val wants = remember(data, on) { list() }
    val all = remember(data) { notFoundCount(data) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("not_found_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.not_found_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.not_found_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.not_found_missing_on), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = on.isEmpty(), onClick = { on = ""; name = null }, label = { Text(stringResource(R.string.not_found_all_services) + " · " + all) })
                services.forEach { (id, n) ->
                    FilterChip(selected = on == id, onClick = { on = id; name = null }, label = { Text(serviceName(id) + " · " + n) })
                }
            }

            Text(stringResource(R.string.not_found_as_file), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            formats.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider()
                val (label, hint) = when (f) {
                    Wanted.Format.CSV -> "CSV" to R.string.not_found_format_csv
                    Wanted.Format.M3U -> "M3U" to R.string.not_found_format_m3u
                    Wanted.Format.TEXT -> stringResource(R.string.format_text) to R.string.not_found_format_text
                    Wanted.Format.JSON -> "JSON" to R.string.not_found_format_json
                    Wanted.Format.LIDARR -> stringResource(R.string.tools_wanted_lidarr) to R.string.not_found_format_lidarr
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = wants.isNotEmpty(), role = Role.Button) {
                        val base = if (on.isEmpty()) "songs-not-found" else "songs-not-found-on-$on"
                        launchers.getValue(f).launch(if (f == Wanted.Format.LIDARR) "$base-lidarr.json" else "$base.${f.ext}")
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(stringResource(R.string.not_found_as_playlist), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val typed = name ?: defaultName
            OutlinedTextField(
                value = typed, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.not_found_playlist_name)) },
                modifier = Modifier.fillMaxWidth().testTag("not_found_name"),
            )
            Text(pluralStringResource(R.plurals.not_found_playlist_hint, wants.size, wants.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { save(typed.trim()) }, enabled = wants.isNotEmpty() && typed.isNotBlank() && !saving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.not_found_save_playlist)) }
        }
    }
}

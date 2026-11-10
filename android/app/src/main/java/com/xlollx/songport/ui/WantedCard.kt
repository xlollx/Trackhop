package com.xlollx.songport.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.MusicBrainz
import com.xlollx.songport.sync.Wanted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The first entries shown: a long list is for exporting, the page stays quick. */
private const val SHOWN = 300

/**
 * Tools › Missing on my server ([Wanted]): the songs of the user's playlists that their own server
 * lacks, from what the syncs to it could not find, each once. Ticked = got it: it leaves the list
 * (shown again with "Show what I got"). By album or one line per song; exported as a file.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WantedCard(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var byAlbum by rememberSaveable { mutableStateOf(false) }
    var showGot by rememberSaveable { mutableStateOf(false) }
    val list = remember(data, showGot) { Wanted.list(data, { MusicBrainz.known(ctx.env, it) }, Wanted.Filter(withGot = showGot)) }
    val hasServer = data.jobs.any { Wanted.isServer(it.target.provider) }

    var pending by remember { mutableStateOf<Wanted.Format?>(null) }
    val formats = listOf(Wanted.Format.CSV, Wanted.Format.M3U, Wanted.Format.TEXT, Wanted.Format.JSON, Wanted.Format.LIDARR)
    val launchers = formats.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri ->
            val wants = list.filter { !it.got }
            if (uri != null) scope.launch {
                try {
                    withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(f.encode(wants)) } }
                    snackbar.showSnackbar(ctx.getString(R.string.tools_export_done, wants.size))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
                }
            }
            pending = null
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tools_wanted_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_wanted_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (!hasServer) {
                Text(stringResource(R.string.tools_wanted_no_server), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = byAlbum, onClick = { byAlbum = !byAlbum }, label = { Text(stringResource(R.string.tools_wanted_by_album)) })
                FilterChip(selected = showGot, onClick = { showGot = !showGot }, label = { Text(stringResource(R.string.tools_wanted_show_got)) })
            }
            Spacer(Modifier.height(8.dp))
            val open = list.count { !it.got }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (open == 0) stringResource(R.string.tools_wanted_empty) else pluralStringResource(R.plurals.tools_wanted_count, open, open),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                var menu by remember { mutableStateOf(false) }
                Box {
                    Button(enabled = open > 0 && pending == null, onClick = { menu = true }) { Text(stringResource(R.string.tools_wanted_export)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        formats.forEach { f ->
                            val label = when (f) {
                                Wanted.Format.TEXT -> stringResource(R.string.format_text)
                                Wanted.Format.LIDARR -> stringResource(R.string.tools_wanted_lidarr)
                                else -> f.name
                            }
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                menu = false; pending = f
                                launchers.getValue(f).launch(if (f == Wanted.Format.LIDARR) "missing-on-my-server-lidarr.json" else "missing-on-my-server.${f.ext}")
                            })
                        }
                    }
                }
            }
            val shown = list.take(SHOWN)
            if (byAlbum) Wanted.albums(shown).forEach { a ->
                Spacer(Modifier.height(10.dp))
                Text(
                    a.artist + " — " + a.album.ifBlank { stringResource(R.string.tools_wanted_no_album) },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                a.wants.forEach { WantedRow(it, withAlbum = false) { got -> Wanted.mark(store, listOf(it.id), got) } }
            } else shown.forEach { w -> WantedRow(w, withAlbum = true) { got -> Wanted.mark(store, listOf(w.id), got) } }
            if (list.size > SHOWN) Text(stringResource(R.string.preview_more, list.size - SHOWN), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One song: tick to say it is got; under it the album, where it comes from and since when. */
@Composable
private fun WantedRow(w: Wanted.Want, withAlbum: Boolean, onTick: (Boolean) -> Unit) {
    val since = remember(w.firstSeen) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(w.firstSeen)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = w.got, onCheckedChange = onTick)
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(w.toString(), style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(w.album.takeIf { withAlbum && it.isNotBlank() }, stringResource(R.string.tools_wanted_from, w.syncs.joinToString(", ") { it.syncName }, since))
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

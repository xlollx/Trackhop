package com.xlollx.songport.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Progress
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.PlaylistTwins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The groups found, by connector: kept for the process, so coming back (or Android making the screen anew) does not read every playlist again. */
internal object TwinsKept {
    val groups = ConcurrentHashMap<String, List<PlaylistTwins.Group>>()
}

/**
 * Tools › a service › Duplicate playlists (1.18, [PlaylistTwins]): the copies of a playlist, grouped,
 * with the one to keep ticked. Where the service lets Trackhop delete playlists, the others go after a
 * confirmation, each saved in Files first; elsewhere the groups are shown, to delete in the service's app.
 */
@Composable
fun PlaylistTwinsPage(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember(provider.id) { mutableStateOf(TwinsKept.groups[provider.id]) }
    var progress by remember(provider.id) { mutableStateOf<Progress?>(null) }
    var busy by remember(provider.id) { mutableStateOf(false) }
    // The one kept in each group, by the group's key, where the user chose another than proposed.
    var keep by rememberSaveable(provider.id) { mutableStateOf(HashMap<String, String>()) }
    var confirming by rememberSaveable(provider.id) { mutableStateOf(false) }
    val canDelete = provider.canDeletePlaylists

    fun read() {
        busy = true
        scope.launch {
            groups = try { PlaylistTwins.find(ctx.env, provider) { progress = it } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage(ctx)); null
            }
            groups?.let { TwinsKept.groups[provider.id] = it }
            progress = null
            busy = false
        }
    }
    LaunchedEffect(provider.id) { if (groups == null) read() }

    // Per group: the copy kept, and the ones to delete (never one a sync uses).
    val plan = groups.orEmpty().map { g ->
        val keptId = keep[g.key]?.takeIf { id -> g.copies.any { it.playlist.id == id } } ?: g.copies[g.keep].playlist.id
        val kept = g.copies.first { it.playlist.id == keptId }
        Triple(g, kept, g.copies.filter { it !== kept && !it.inSync })
    }
    val toDelete = plan.sumOf { it.third.size }

    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(progress?.let { stringResource(R.string.twins_reading, it.done, it.total) } ?: stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
    }
    val g = groups ?: return
    if (g.isEmpty()) Text(stringResource(R.string.twins_none, provider.label(ctx.env)), style = MaterialTheme.typography.bodyMedium)
    else {
        Text(
            if (canDelete) pluralStringResource(R.plurals.twins_tip, g.size, g.size)
            else pluralStringResource(R.plurals.twins_groups, g.size, g.size) + " " + stringResource(R.string.twins_cannot_delete, provider.displayName),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plan.forEach { (group, kept, _) ->
            PageCard {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(kept.playlist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pluralStringResource(if (group.sameName) R.plurals.twins_same_name else R.plurals.twins_same_songs, group.copies.size, group.copies.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    group.copies.forEach { c ->
                        val isKept = c === kept
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .then(if (canDelete) Modifier.selectable(selected = isKept, role = Role.RadioButton) { keep = HashMap(keep).apply { put(group.key, c.playlist.id) } } else Modifier)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (canDelete) RadioButton(selected = isKept, onClick = null, modifier = Modifier.padding(horizontal = 12.dp)) else Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 4.dp)) {
                                Text(c.playlist.name, style = MaterialTheme.typography.bodyMedium)
                                Text(copyLine(c, kept, isKept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(enabled = !busy, onClick = { read() }) { Text(stringResource(R.string.twins_look_again)) }
        if (canDelete && toDelete > 0) Button(
            enabled = !busy, onClick = { confirming = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text(pluralStringResource(R.plurals.twins_delete_n, toDelete, toDelete)) }
    }
    if (confirming && toDelete > 0) ConfirmTwins(provider, plan.map { it.second to it.third }.filter { it.second.isNotEmpty() }, onDismiss = { confirming = false }) { addMissing ->
        confirming = false
        busy = true
        scope.launch {
            val gone = HashSet<String>()
            try {
                for ((_, kept, copies) in plan) if (copies.isNotEmpty())
                    gone += PlaylistTwins.delete(ctx.env, provider, kept, copies, addMissing) { ctx.getString(R.string.twins_deleted_copy, it) }
                snackbar.showSnackbar(ctx.resources.getQuantityString(R.plurals.twins_done, gone.size, gone.size))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage(ctx))
            }
            // What is left of each group: one copy alone is no longer a group.
            groups = groups?.map { it.copy(copies = it.copies.filter { c -> c.playlist.id !in gone }) }?.filter { it.copies.size > 1 }
                ?.map { it.copy(keep = PlaylistTwins.suggested(it.copies)) }
            groups?.let { TwinsKept.groups[provider.id] = it }
            busy = false
        }
    }
}

/** "68 tracks · changed yesterday · used by 1 sync" for the one kept; how alike the others are. */
@Composable
private fun copyLine(c: PlaylistTwins.Copy, kept: PlaylistTwins.Copy, isKept: Boolean): String {
    val parts = ArrayList<String>()
    parts += pluralStringResource(R.plurals.card_tracks, c.tracks.size, c.tracks.size)
    if (isKept) {
        if (c.changedAt > 0) parts += stringResource(R.string.twins_changed, DateUtils.getRelativeTimeSpanString(c.changedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString().replaceFirstChar { it.lowercase() })
    } else {
        parts += stringResource(R.string.twins_same_percent, PlaylistTwins.same(kept.tracks, c.tracks))
        val diff = kept.tracks.size - c.tracks.size
        if (diff > 0) parts += stringResource(R.string.twins_fewer, diff) else if (diff < 0) parts += stringResource(R.string.twins_more, -diff)
    }
    if (c.inSync) parts += pluralStringResource(R.plurals.twins_used_by, c.syncs.size, c.syncs.size)
    if (c.inSync && !isKept) parts += stringResource(R.string.twins_stays)
    return parts.joinToString(" · ")
}

/** Before deleting: the copies, what each has that the kept one lacks, and the way to add it first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmTwins(provider: MusicProvider, plan: List<Pair<PlaylistTwins.Copy, List<PlaylistTwins.Copy>>>, onDismiss: () -> Unit, onDelete: (addMissing: Boolean) -> Unit) {
    val n = plan.sumOf { it.second.size }
    val missingTotal = plan.sumOf { (kept, gone) -> gone.sumOf { PlaylistTwins.missing(kept.tracks, it.tracks).size } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pluralStringResource(R.plurals.twins_confirm_title, n, n, provider.displayName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            plan.forEach { (kept, gone) ->
                gone.forEach { c ->
                    val missing = PlaylistTwins.missing(kept.tracks, c.tracks).size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.playlist.name, style = MaterialTheme.typography.bodyMedium)
                            val what = if (missing == 0 && c.tracks.size == kept.tracks.size) stringResource(R.string.twins_identical)
                                else pluralStringResource(R.plurals.twins_missing_line, missing, missing)
                            Text(pluralStringResource(R.plurals.card_tracks, c.tracks.size, c.tracks.size) + " · " + what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider()
            if (missingTotal > 0) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    Text(pluralStringResource(R.plurals.twins_missing_tip, missingTotal, missingTotal), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDelete(true) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.twins_add_then_delete)) }
                }
            }
            Text(stringResource(R.string.twins_saved_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onDelete(false) }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

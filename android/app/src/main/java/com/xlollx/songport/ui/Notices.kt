package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Progress
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.label
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.sync.AppRun
import com.xlollx.songport.sync.AppTasks
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.Tools
import com.xlollx.songport.sync.Unfinished
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A notice in the colours of something that went wrong, with its buttons under the text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeBox(title: String?, text: String, error: Boolean, buttons: @Composable () -> Unit) {
    val ground = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val ink = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(ground).padding(14.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
            Spacer(Modifier.height(4.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ink)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
    }
}

/**
 * At the top of Settings (where the notification about it opens the app): the saved syncs and
 * settings could not be read and the app started empty, with the last good copy to go back to when
 * there is one. Restoring happens only on the person's tap, after a confirmation: a sync deleted
 * since the copy would run again. Also what the app finished by itself at this start (a song put
 * back after "Remove duplicates" was cut short) and a shared playlist whose state could not be read.
 */
@Composable
fun StateNotices(store: Store, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notice by store.notice.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    notice?.let { n ->
        val at = n.goodCopyAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        NoticeBox(
            title = stringResource(R.string.store_damaged_title),
            text = listOfNotNull(
                if (n.damaged || at == null) stringResource(R.string.store_damaged_text) else null,
                at?.let { stringResource(R.string.store_good_copy_text, it) },
            ).joinToString("\n\n"),
            error = true,
        ) {
            if (at != null) Button(enabled = !busy, onClick = { confirm = true }) { Text(stringResource(R.string.store_good_copy_restore)) }
            TextButton(enabled = !busy, onClick = { store.dismissNotice() }) { Text(stringResource(R.string.notice_dismiss)) }
        }
        if (confirm && at != null) FitDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.store_good_copy_restore)) },
            text = { Text(stringResource(R.string.store_good_copy_confirm, at)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    val app = ctx.applicationContext
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { store.restoreGoodCopy() }.getOrDefault(false) }
                        if (ok) {
                            // The syncs that came back get their schedules again, as at a start of the app.
                            runCatching { Scheduler.applyAll(app) }
                            snackbar.showSnackbar(app.getString(R.string.store_good_copy_restored, store.data.jobs.size))
                        } else snackbar.showSnackbar(app.getString(R.string.store_good_copy_failed))
                        busy = false
                    }
                }) { Text(stringResource(R.string.backup_config_restore_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val putBack by AppTasks.putBack.collectAsState()
    putBack?.let { text ->
        NoticeBox(title = stringResource(R.string.tools_dedupe_title), text = text, error = false) {
            TextButton(onClick = { AppTasks.putBack.value = null }) { Text(stringResource(R.string.ok)) }
        }
    }

    // Shared playlists whose saved state could not be read (kept aside, read again from the group).
    var seen by remember { mutableIntStateOf(0) }
    val unreadable = remember(seen) { SharedGroups.unreadable.keys.toList() }
    for (id in unreadable) {
        val name = store.data.groups.firstOrNull { it.id == id }?.name ?: id
        NoticeBox(title = null, text = stringResource(R.string.shared_state_unreadable, name), error = true) {
            TextButton(onClick = { SharedGroups.unreadable.remove(id); seen++ }) { Text(stringResource(R.string.ok)) }
        }
    }
}


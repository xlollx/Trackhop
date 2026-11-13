package com.xlollx.songport.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
import com.xlollx.songport.providers.*
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Il registro delle sincronizzazioni, aperto dalle Opzioni: esiti, dettagli e il rapporto tecnico da
 * allegare a una segnalazione. Non e' una scheda principale perche' non serve nell'uso quotidiano.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(data: StoreData, onClose: () -> Unit, onResolve: (String) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    ScreenBackHandler(onClose)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_log)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                actions = { HelpButton(Helps.notes, Helps.restore, label = stringResource(R.string.settings_log)) },
            )
        },
    ) { padding ->
        // A readable column, centred in a wide window (a tablet, a phone on its side).
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = WindowLayout.READABLE_CONTENT).fillMaxSize()) { LogList(data, snackbar, onResolve) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LogList(data: StoreData, snackbar: SnackbarHostState, onResolve: (String) -> Unit) {
    if (data.reports.isEmpty()) {
        EmptyState(Icons.Filled.History, stringResource(R.string.empty_log_title), stringResource(R.string.empty_log_body))
        return
    }
    val ctx = LocalContext.current
    var exportOpen by remember { mutableStateOf(false) }
    // The export runs in this screen's scope: the dialog's own ends when it closes, and with it the export.
    val exportScope = rememberCoroutineScope()
    if (exportOpen) ExportLogDialog(onDismiss = { exportOpen = false }, onPick = { span ->
        exportOpen = false
        exportScope.launch { exportLog(ctx, span) }
    })
    // The technical details as text, of the time chosen: the basics (versions, connectors, syncs,
    // last runs) always, then the log lines of that span, the newest ones when there are many.
    var shareOpen by remember { mutableStateOf(false) }
    if (shareOpen) ExportLogDialog(title = R.string.diag_share, body = null, onDismiss = { shareOpen = false }, onPick = { span ->
        shareOpen = false
        exportScope.launch {
            val text = withContext(Dispatchers.IO) {
                Diagnostics.report(ctx, logLines = SHARE_LINES, sinceMs = span?.let { System.currentTimeMillis() - it })
            }
            val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.app_name) + " diagnostics") }
            runCatching { ctx.startActivity(Intent.createChooser(share, ctx.getString(R.string.diag_share))) }
        }
    })
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "diag") {
            // What to do with the log, each a button with its sign: side by side where they fit, wrapped where they do not.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                // Apre una nuova issue su GitHub gia' compilata con il rapporto: l'utente la legge,
                // toglie quel che vuole e la invia con il proprio account. Nulla parte da solo.
                LogAction(Icons.Filled.BugReport, stringResource(R.string.report_issue)) {
                    val full = Diagnostics.report(ctx)
                    // A link carries only so much: the summary and the newest log lines go in it,
                    // the whole report to the clipboard, to paste when it doesn't fit.
                    val fits = Diagnostics.forLink(full, 5500)
                    val note = if (fits.length < full.length) {
                        runCatching {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Trackhop report", full))
                        }
                        "\n\n<!-- The full report is in your clipboard: paste it below if you like. -->\n"
                    } else ""
                    val url = "https://github.com/xlollx/Trackhop/issues/new?title=" + Uri.encode("[Sync error] ") +
                        "&body=" + Uri.encode("<!-- Describe what you were doing. Remove anything you prefer not to share. -->\n\n```\n$fits\n```$note")
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
                // The whole log, or the part of it a time span holds, as a text file: nothing cut.
                LogAction(Icons.Filled.SaveAlt, stringResource(R.string.diag_export)) { exportOpen = true }
                if (data.settings.developerOptions) LogAction(Icons.Filled.Share, stringResource(R.string.diag_share)) { shareOpen = true }
            }
        }
        items(data.reports, key = { it.id }) { r -> ReportRow(r, data.jobs.firstOrNull { it.id == r.jobId }, snackbar, onResolve) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReportRow(r: SyncReport, job: SyncJob?, snackbar: SnackbarHostState, onResolve: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = r.unmatched.isNotEmpty()
    // A sync into "File" lands in the app's private folder: offer to save it as a real document.
    val fileTarget = job?.takeIf { it.target.provider == LocalFilesProvider.serviceId }?.target?.playlistId
    val canSave = r.ok && fileTarget != null
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.CSV.mime)) { uri ->
        if (uri != null && fileTarget != null) scope.launch {
            try {
                val text = LocalFilesProvider.exportText(ctx, fileTarget, LocalFilesProvider.Export.CSV)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
                snackbar.showSnackbar(ctx.getString(R.string.csv_exported))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage(ctx))
            }
        }
    }
    val hasButtons = hasDetails || canSave || (job != null && (r.removedTracks.isNotEmpty() || r.unmatchedTracks.isNotEmpty() || r.reviewTracks.isNotEmpty()))
    PageCard(edge = if (r.ok) null else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) {
        Row(Modifier.clickable(enabled = hasDetails, onClickLabel = stringResource(if (expanded) R.string.hide_details else R.string.show_details)) { expanded = !expanded }.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = if (hasButtons) 6.dp else 14.dp), verticalAlignment = Alignment.Top) {
            // The outcome before the words: a tick on green, or an exclamation mark on red (the summary says which).
            StatusMark(if (r.ok) Icons.Filled.Check else Icons.Filled.PriorityHigh, if (r.ok) Tone.Ok else Tone.Error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // The name and when, side by side; one over the other where the date would leave the name a word a line
                // (a narrow phone, a large font).
                if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / androidx.compose.ui.platform.LocalDensity.current.fontScale < 300f) {
                    Text(r.jobName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(formatDate(r.startedEpoch), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.jobName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(formatDate(r.startedEpoch), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    Notifications.summary(ctx, r) + " · " + stringResource(R.string.log_duration, r.durationMs / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (r.notes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { r.notes.forEach { PageNote(it) } }
                }
                if (expanded && hasDetails) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.log_unmatched_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 2.dp))
                        r.unmatched.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (r.unmatched.size > 5) Text(stringResource(R.string.preview_more, r.unmatched.size - 5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val canRestore = job != null && r.removedTracks.isNotEmpty()
                val canReview = job != null && (r.unmatchedTracks.isNotEmpty() || r.reviewTracks.isNotEmpty())
                if (hasDetails || canRestore || canReview || canSave) {
                    // Wraps where the buttons do not fit on one line (a narrow phone, a large font, long words).
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        if (hasDetails) {
                            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(if (expanded) R.string.hide_details else R.string.show_details))
                            }
                        }
                        if (canRestore && job != null) {
                            TextButton(enabled = !restoring, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                restoring = true
                                scope.launch {
                                    try {
                                        SyncEngine(ctx).restoreRemoved(job, r)
                                        snackbar.showSnackbar(ctx.getString(R.string.restored_done, r.removedTracks.size))
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                        snackbar.showSnackbar(e.userMessage(ctx))
                                    }
                                    restoring = false
                                }
                            }) { Text(stringResource(R.string.restore_removed, r.removedTracks.size)) }
                        }
                        if (canSave) {
                            TextButton(onClick = { saveLauncher.launch("$fileTarget.csv") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.log_save_file)) }
                        }
                        if (canReview) {
                            FilledTonalButton(onClick = { onResolve(r.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.review_open)) }
                        }
                    }
                }
            }
        }
    }
}

/** One thing to do with the whole log: its sign and its name on an outlined button, a full touch target. */
@Composable
private fun LogAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The log since [span] ago (null: all of it) as a text file, handed to the share sheet. */
private suspend fun exportLog(ctx: android.content.Context, span: Long?) {
    val file = withContext(Dispatchers.IO) { Diagnostics.exportFile(ctx, span?.let { System.currentTimeMillis() - it }) }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".cards", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.app_name) + " log")
        clipData = android.content.ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(share, ctx.getString(R.string.diag_export)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(chooser) }.onFailure { Diagnostics.log(ctx, "log", "export not shared: ${it.message}") }
}

/** Shared as text, a span keeps at most this many log lines: a share's text must stay small. */
private const val SHARE_LINES = 1500

/** Which part of the log goes into the exported file: the last hour, day, week, or all of it. */
@Composable
private fun ExportLogDialog(title: Int = R.string.diag_export_title, body: Int? = R.string.diag_export_body, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val hour = 3_600_000L
    val ranges = listOf(
        R.string.diag_range_hour to hour,
        R.string.diag_range_day to 24 * hour,
        R.string.diag_range_week to 7 * 24 * hour,
        R.string.diag_range_all to null,
    )
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            // Scrolls where the dialog is lower than its content (a phone on its side).
            Column {
                body?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                ranges.forEach { (label, span) ->
                    TextButton(onClick = { onPick(span) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(label), modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

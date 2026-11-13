package com.xlollx.songport.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.EncryptedBackup
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.BackupExport
import com.xlollx.songport.sync.FullBackup
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & restore, one place for both kinds (from Settings and from Tools): the set-up as one
 * .trackhop file (ConfigBackup: syncs, settings, chosen matches, File playlists; never sign-ins), to
 * keep or to take to another phone, Docker or Trackhop Desktop; the playlists themselves as dated
 * files, now or on a schedule; and a folder outside the phone where both are copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val settings = data.settings
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    ScreenBackHandler(onClose)

    // ---- the set-up as a file
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // With the notification channels, never their secrets (Notify.intoBackup).
                    val text = com.xlollx.songport.notify.Notify.intoBackup(ctx.env, ConfigBackup.export(ctx.env, "Trackhop ${BuildConfig.VERSION_NAME} (Android)", deviceName = android.os.Build.MODEL.orEmpty()))
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
                }.isSuccess
            }
            snackbar.showSnackbar(ctx.getString(if (ok) R.string.backup_config_saved else R.string.error_generic))
        }
    }
    var pending by remember { mutableStateOf<ConfigBackup.Snapshot?>(null) }
    // The notification channels the file holds (without secrets): brought in with the rest.
    var pendingChannels by remember { mutableStateOf<List<com.xlollx.songport.notify.Notify.Channel>>(emptyList()) }
    // An encrypted full backup (1.18): its text until the password opens it.
    var pendingLocked by remember { mutableStateOf<String?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            var locked: String? = null
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                    if (EncryptedBackup.isEncrypted(text)) { locked = text; null } else {
                        pendingChannels = com.xlollx.songport.notify.Notify.inBackup(text)
                        ConfigBackup.read(text)
                    }
                }
            }
            val sealed = locked
            when {
                // Behind the release switch: off, an encrypted file is said to be a newer feature's, never misread.
                sealed != null -> if (com.xlollx.songport.Features.v2(store.data.settings)) pendingLocked = sealed
                    else snackbar.showSnackbar(ctx.getString(R.string.backup_full_newer), duration = androidx.compose.material3.SnackbarDuration.Long)
                else -> read.onSuccess { pending = it }.onFailure { snackbar.showSnackbar(ctx.getString(R.string.backup_config_invalid)) }
            }
        }
    }
    FullBackupDialogs(
        snackbar = snackbar,
        locked = pendingLocked,
        onLockedDone = { pendingLocked = null },
    )
    pending?.let { snap ->
        val s = ConfigBackup.summary(snap)
        FitDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_config_confirm_title)) },
            text = {
                val channels = pendingChannels.size
                Text(
                    stringResource(R.string.backup_config_confirm_body, s.syncs, s.matches, s.files, s.writtenBy.ifBlank { "Trackhop" }) +
                        // Channels that came with the file stay off until turned on here: a shared file must not send anywhere by itself.
                        if (channels > 0) "\n\n" + androidx.compose.ui.res.pluralStringResource(R.plurals.backup_config_channels, channels, channels) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val channels = pendingChannels
                        val done = withContext(Dispatchers.IO) { runCatching { ConfigBackup.import(ctx.env, snap).also { com.xlollx.songport.notify.Notify.restore(ctx.env, channels) } } }
                        done.onSuccess { r ->
                            // The imported syncs' schedules start here as they would have where they came from.
                            store.data.jobs.forEach { Scheduler.apply(ctx, it) }
                            Scheduler.applyKeepAlive(ctx)
                            // Scheduled syncs from another device arrive turned off: said here, and on their cards.
                            val paused = if (r.paused > 0) "\n" + ctx.getString(R.string.backup_config_paused, r.paused, r.from.ifBlank { ctx.getString(R.string.backup_device_other) }) else ""
                            snackbar.showSnackbar(
                                ctx.getString(R.string.backup_config_restored, r.syncs) + paused,
                                duration = if (r.paused > 0) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
                            )
                        }.onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.error_generic)) }
                    }
                }) { Text(stringResource(R.string.backup_config_restore_action)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // ---- the folder outside the phone
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            store.updateSettings { it.copy(backupFolder = uri.toString()) }
            Scheduler.applyBackup(ctx)
        }
    }
    val running by BackupExport.running.collectAsState()
    val step by BackupExport.step.collectAsState()
    var stateVersion by remember { mutableIntStateOf(0) }
    val state = remember(stateVersion, settings.backupFolder) { BackupExport.state(ctx) }
    // A run started here (or by the schedule) ends whenever the screen is showing: its outcome once.
    val done by BackupExport.finished.collectAsState()
    LaunchedEffect(done) {
        val r = done ?: return@LaunchedEffect
        BackupExport.finished.value = null
        stateVersion++
        // In the screen's scope: clearing [finished] restarts this effect, which would cancel the
        // snackbar the moment it appeared.
        scope.launch { snackbar.showSnackbar(
            if (r.folderMissing) r.failed.first()
            else ctx.getString(R.string.backup_now_done, r.services) +
                (if (r.copied > 0) " · " + ctx.getString(R.string.backup_folder_copied, r.copied) else "") +
                (if (r.failed.isNotEmpty()) " · " + r.failed.first() else ""),
        ) }
    }
    var scheduleOpen by remember { mutableStateOf(false) }
    if (scheduleOpen) ChoiceDialog(
        stringResource(R.string.editor_schedule),
        listOf(Schedule.MANUAL, Schedule.DAILY, Schedule.WEEKLY).map { it to scheduleLabel(it) },
        settings.backupSchedule,
        onDismiss = { scheduleOpen = false },
    ) { s ->
        scheduleOpen = false
        store.updateSettings { it.copy(backupSchedule = s) }; Scheduler.applyBackup(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The same margins and gaps as Settings and Tools: the sections stand apart, off the screen's edges.
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PrefSection(stringResource(R.string.backup_config_section), accent = JobColor.BLUE, description = stringResource(R.string.backup_config_desc)) {
                PrefRow(Icons.Filled.Save, stringResource(R.string.backup_config_save), null, chevron = false) { save.launch(ConfigBackup.fileName()) }
                // 1.18, behind the release switch: the same file with the sign-ins, locked with a password.
                if (com.xlollx.songport.Features.v2(settings)) FullBackupSaveRow(snackbar)
                PrefRow(Icons.Filled.Restore, stringResource(R.string.backup_config_restore), null, chevron = false) { open.launch(arrayOf("*/*")) }
            }
            PrefSection(stringResource(R.string.backup_playlists_section), accent = JobColor.GREEN, description = stringResource(R.string.tools_backup_desc, LocalFilesProvider.KEEP_VERSIONS)) {
                state.lastError?.let { PrefBlock { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                    ?: state.lastOk.takeIf { it > 0 }?.let { PrefBlock { Text(stringResource(R.string.backup_last_run, formatDate(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                PrefRow(
                    Icons.Filled.Backup, stringResource(R.string.backup_now),
                    if (running && step.isNotBlank()) step else stringResource(R.string.backup_now_desc),
                    enabled = !running, chevron = false,
                    trailing = if (running) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null,
                ) { BackupExport.start(ctx) }
            }
            val folder = settings.backupFolder
            PrefSection(stringResource(R.string.backup_auto_section), accent = JobColor.TEAL, description = stringResource(R.string.backup_auto_desc), help = { HelpButton(Helps.backups) }) {
                PrefRow(Icons.Filled.Schedule, stringResource(R.string.editor_schedule), scheduleLabel(settings.backupSchedule)) { scheduleOpen = true }
                PrefRow(
                    Icons.Filled.Folder, stringResource(R.string.backup_folder_title),
                    if (folder.isBlank()) stringResource(R.string.backup_folder_none) else BackupExport.folderLabel(folder),
                    trailing = if (folder.isBlank()) { { TextButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.backup_folder_pick)) } } } else null,
                ) { pickFolder.launch(null) }
                if (folder.isNotBlank()) {
                    PrefRow(Icons.Filled.LinkOff, stringResource(R.string.backup_folder_stop), null, enabled = !running, danger = true, chevron = false) {
                        store.updateSettings { it.copy(backupFolder = "") }; Scheduler.applyBackup(ctx)
                    }
                }
            }
        }
    }
}


/**
 * "Include sign-ins and schedules (encrypted)": the password asked twice, with what losing it means,
 * then where to save; the file is written off the main thread ([FullBackup.export]).
 */
@Composable
private fun FullBackupSaveRow(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    // The password between the dialog and the file picker's answer; cleared once used.
    var chosen by remember { mutableStateOf<CharArray?>(null) }
    val saveFull = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = chosen ?: return@rememberLauncherForActivityResult
        chosen = null
        if (uri == null) { password.fill('\u0000'); return@rememberLauncherForActivityResult }
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val out = FullBackup.export(ctx, password)
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(out.text.toByteArray()) } ?: error("no stream")
                    out
                }.also { password.fill('\u0000') }
            }
            done.onSuccess { out ->
                snackbar.showSnackbar(
                    ctx.getString(R.string.backup_full_saved, out.signIns + out.web) + when (out.webGap) {
                        FullBackup.WebGap.NONE -> ""
                        FullBackup.WebGap.NOT_HANDED -> "\n" + ctx.getString(R.string.backup_full_web_missing)
                        FullBackup.WebGap.TOO_LARGE -> "\n" + ctx.getString(R.string.backup_full_web_too_large)
                    },
                    duration = if (out.webGap != FullBackup.WebGap.NONE) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
                )
            }.onFailure { snackbar.showSnackbar(ctx.getString(R.string.error_generic)) }
        }
    }
    PrefRow(Icons.Filled.Lock, stringResource(R.string.backup_full_save), stringResource(R.string.backup_full_save_desc), chevron = false) { first = ""; again = ""; asking = true }
    if (asking) {
        val problem = EncryptedBackup.passwordProblem(first.toCharArray(), again.toCharArray())
        FitDialog(
            onDismissRequest = { asking = false; first = ""; again = "" },
            title = { Text(stringResource(R.string.backup_full_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_full_password_warning, EncryptedBackup.MIN_PASSWORD), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.backup_full_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(first, { first = it }, stringResource(R.string.backup_full_password))
                    PasswordField(again, { again = it }, stringResource(R.string.backup_full_password_again))
                    val hint = when {
                        first.isEmpty() -> null
                        problem == EncryptedBackup.PasswordProblem.SHORT -> stringResource(R.string.backup_full_password_short, EncryptedBackup.MIN_PASSWORD)
                        problem == EncryptedBackup.PasswordProblem.MISMATCH && again.isNotEmpty() -> stringResource(R.string.backup_full_password_mismatch)
                        else -> null
                    }
                    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = problem == null, onClick = {
                    chosen?.fill('\u0000')
                    chosen = first.toCharArray()
                    first = ""; again = ""; asking = false
                    saveFull.launch(ConfigBackup.fileName())
                }) { Text(stringResource(R.string.backup_full_continue)) }
            },
            dismissButton = { TextButton(onClick = { asking = false; first = ""; again = "" }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** An encrypted file opened: what it holds, and the two questions asked before it comes in. */
private class FullPending(
    val snapshot: ConfigBackup.Snapshot,
    val channels: List<com.xlollx.songport.notify.Notify.Channel>,
    val secrets: EncryptedBackup.Secrets,
    /** This device's own sign-ins the file would replace, and its web services (replaced only where this device has them), as people read them. */
    val conflicts: List<String>,
)

/**
 * The import of an encrypted full backup ([locked], its text): the password, then what it holds with
 * the two choices (this device replaces the other one; replace the sign-ins already here), both off
 * until ticked, then the import ([ConfigBackup.import], the channels, [EncryptedBackup.restore], the
 * web sessions through the connectors).
 */
@Composable
private fun FullBackupDialogs(snackbar: SnackbarHostState, locked: String?, onLockedDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store.get(ctx) }
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<FullPending?>(null) }
    if (locked != null) {
        FitDialog(
            onDismissRequest = { password = ""; wrong = false; onLockedDone() },
            title = { Text(stringResource(R.string.backup_full_unlock_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_full_unlock_body))
                    PasswordField(password, { password = it; wrong = false }, stringResource(R.string.backup_full_password))
                    if (wrong) Text(stringResource(R.string.backup_full_wrong_password), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !opening, onClick = {
                    val pw = password.toCharArray()
                    password = ""
                    opening = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val opened = EncryptedBackup.unwrap(locked, pw)
                                val snap = ConfigBackup.read(opened.plain)
                                val web = opened.secrets.web
                                // Nothing is sent to the connectors before the person confirms: the web services are named as the file has them.
                                val webHere = if (web?.platform == EncryptedBackup.WEB_BRIDGE) FullBackup.webServices(web) else emptyList()
                                FullPending(
                                    snap, com.xlollx.songport.notify.Notify.inBackup(opened.plain), opened.secrets,
                                    EncryptedBackup.conflicts(ctx.env, opened.secrets, EncryptedBackup.WEB_BRIDGE).map(FullBackup::signInName) + webHere,
                                )
                            }.also { pw.fill('\u0000') }
                        }
                        opening = false
                        r.onSuccess { full = it; wrong = false; onLockedDone() }
                            .onFailure { e ->
                                if (e is EncryptedBackup.WrongPassword) wrong = true
                                else { onLockedDone(); snackbar.showSnackbar(e.message ?: ctx.getString(R.string.backup_config_invalid)) }
                            }
                    }
                }) { Text(stringResource(R.string.backup_full_unlock)) }
            },
            dismissButton = { TextButton(onClick = { password = ""; wrong = false; onLockedDone() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    full?.let { f ->
        val s = ConfigBackup.summary(f.snapshot)
        var replacesDevice by remember(f) { mutableStateOf(false) }
        var replaceSignIns by remember(f) { mutableStateOf(false) }
        val webCount = f.secrets.web?.accounts?.size ?: 0
        FitDialog(
            onDismissRequest = { full = null },
            title = { Text(stringResource(R.string.backup_config_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_full_confirm_body, s.syncs, s.matches, s.files, s.writtenBy.ifBlank { "Trackhop" }))
                    Text(stringResource(R.string.backup_full_contents, f.secrets.vault.size + f.secrets.clientSecrets.size, webCount))
                    Text(stringResource(R.string.backup_full_adds))
                    if (f.channels.isNotEmpty()) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.backup_config_channels, f.channels.size, f.channels.size))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replacesDevice, onCheckedChange = { replacesDevice = it })
                        Text(stringResource(R.string.backup_full_replaces_device), modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.backup_full_replaces_device_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (f.conflicts.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replaceSignIns, onCheckedChange = { replaceSignIns = it })
                        Text(stringResource(R.string.backup_full_replace_signins, f.conflicts.joinToString(", ")), modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.backup_full_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    full = null
                    val replaceDevice = replacesDevice
                    val replace = replaceSignIns
                    scope.launch {
                        val done = withContext(Dispatchers.IO) {
                            runCatching {
                                val env = ctx.env
                                val summary = ConfigBackup.import(env, f.snapshot, replacesOther = replaceDevice)
                                com.xlollx.songport.notify.Notify.restore(env, f.channels)
                                val restored = EncryptedBackup.restore(env, f.secrets, replace, EncryptedBackup.WEB_BRIDGE)
                                val webOk = restored.web?.let { FullBackup.restoreWeb(ctx, it, replace) } ?: true
                                Triple(summary, restored, webOk)
                            }
                        }
                        done.onSuccess { (r, restored, webOk) ->
                            store.data.jobs.forEach { Scheduler.apply(ctx, it) }
                            Scheduler.applyKeepAlive(ctx)
                            val lines = buildList {
                                add(ctx.getString(R.string.backup_config_restored, r.syncs))
                                add(ctx.getString(R.string.backup_full_restored, restored.signIns))
                                if (restored.kept.isNotEmpty()) add(ctx.getString(R.string.backup_full_kept, restored.kept.size))
                                if (restored.webSkipped) add(ctx.getString(R.string.backup_full_web_skipped))
                                if (!webOk) add(ctx.getString(R.string.backup_full_web_failed))
                                if (r.paused > 0) add(ctx.getString(R.string.backup_config_paused, r.paused, r.from.ifBlank { ctx.getString(R.string.backup_device_other) }))
                                if (replaceDevice) add(ctx.getString(R.string.backup_full_reminder))
                            }
                            snackbar.showSnackbar(lines.joinToString("\n"), duration = androidx.compose.material3.SnackbarDuration.Long, withDismissAction = true)
                        }.onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.error_generic)) }
                    }
                }) { Text(stringResource(R.string.backup_config_restore_action)) }
            },
            dismissButton = { TextButton(onClick = { full = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

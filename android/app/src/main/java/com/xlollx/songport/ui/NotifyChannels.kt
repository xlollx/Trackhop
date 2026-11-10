package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xlollx.songport.R
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.platform.env
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's words for the channels' events, kinds and settings (Notify's own are English, for the server's page). */
private val EVENT_RES = mapOf(
    Notify.Event.CHANGES to R.string.notify_event_changes, Notify.Event.FAILED to R.string.notify_event_failed,
    Notify.Event.REVIEW to R.string.notify_event_review, Notify.Event.REMOVALS to R.string.notify_event_removals,
    Notify.Event.RELEASES to R.string.notify_event_releases,
)
private val HELP_RES = mapOf(
    Notify.Kind.TELEGRAM to R.string.notify_help_telegram, Notify.Kind.NTFY to R.string.notify_help_ntfy, Notify.Kind.GOTIFY to R.string.notify_help_gotify,
    Notify.Kind.DISCORD to R.string.notify_help_discord, Notify.Kind.SLACK to R.string.notify_help_slack, Notify.Kind.MATRIX to R.string.notify_help_matrix,
    Notify.Kind.PUSHOVER to R.string.notify_help_pushover,
)
private val FIELD_RES = mapOf(
    "telegram_token" to R.string.notify_field_telegram_token,
    "telegram_chat" to R.string.notify_field_telegram_chat,
    "ntfy_server" to R.string.notify_field_ntfy_server,
    "ntfy_topic" to R.string.notify_field_ntfy_topic,
    "ntfy_token" to R.string.notify_field_ntfy_token,
    "gotify_server" to R.string.notify_field_gotify_server,
    "gotify_token" to R.string.notify_field_gotify_token,
    "discord_url" to R.string.notify_field_discord_url,
    "slack_url" to R.string.notify_field_slack_url,
    "matrix_server" to R.string.notify_field_matrix_server,
    "matrix_token" to R.string.notify_field_matrix_token,
    "matrix_room" to R.string.notify_field_matrix_room,
    "pushover_token" to R.string.notify_field_pushover_token,
    "pushover_user" to R.string.notify_field_pushover_user,
)

internal fun eventName(ctx: Context, e: Notify.Event) = EVENT_RES[e]?.let { ctx.getString(it) } ?: e.label
private fun helpText(ctx: Context, k: Notify.Kind) = HELP_RES[k]?.let { ctx.getString(it) } ?: k.help
private fun fieldName(ctx: Context, k: Notify.Kind, f: Notify.Field) = FIELD_RES["${k.id}_${f.key}"]?.let { ctx.getString(it) } ?: f.label

/**
 * Settings › Notification channels: the same channels as Docker and Desktop (core's [Notify]):
 * Telegram, ntfy, Gotify, Discord, Slack, Matrix and Pushover, each with the events it wants and a
 * "Send test". Their secrets are kept with the sign-ins, encrypted with the Android Keystore, shown
 * back only masked and never put in the exported set-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyChannelsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var channels by remember { mutableStateOf(Notify.channels(ctx.env)) }
    // The channel being edited: a new one has no id among [channels].
    var editing by remember { mutableStateOf<Notify.Channel?>(null) }
    var addOpen by remember { mutableStateOf(false) }

    fun test(c: Notify.Channel) = scope.launch {
        val error = withContext(Dispatchers.IO) { Notify.test(c) }
        snackbar.showSnackbar(if (error == null) ctx.getString(R.string.notify_channel_test_ok, c.name) else ctx.getString(R.string.notify_channel_test_failed, c.name, error))
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.notify_channels_title)) },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.notify_channels_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (channels.isEmpty()) Text(stringResource(R.string.notify_channels_empty), style = MaterialTheme.typography.bodyMedium)
                    channels.forEach { c ->
                        Card(onClick = { editing = c }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                                        Text(c.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = c.enabled, enabled = c.missing().isEmpty(), onCheckedChange = { on ->
                                        Notify.put(ctx.env, c.copy(enabled = on)); channels = Notify.channels(ctx.env)
                                    })
                                }
                                val missing = c.missing()
                                Text(
                                    if (missing.isNotEmpty()) ctx.getString(R.string.notify_channel_missing, fieldName(ctx, c.kind, missing.first()))
                                    else Notify.Event.entries.filter { it in c.events }.joinToString(", ") { eventName(ctx, it) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (missing.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (missing.isEmpty()) TextButton(onClick = { test(c) }) { Text(stringResource(R.string.notify_channel_test)) }
                            }
                        }
                    }
                    Row {
                        Button(onClick = { addOpen = true }) {
                            Icon(Icons.Filled.Add, null); Text(stringResource(R.string.notify_channel_add), Modifier.padding(start = 6.dp))
                        }
                        DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                            Notify.Kind.entries.forEach { k ->
                                DropdownMenuItem(text = { Text(k.label) }, onClick = {
                                    addOpen = false
                                    editing = Notify.Channel(Notify.newId(), k, k.label, true, Notify.Event.entries.toSet(), k.fields.associate { it.key to it.default })
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { c ->
        val old = channels.firstOrNull { it.id == c.id }
        ChannelEditor(
            c, old,
            onDismiss = { editing = null },
            onSave = { saved -> Notify.put(ctx.env, saved); channels = Notify.channels(ctx.env); editing = null },
            onDelete = if (old != null) ({ Notify.remove(ctx.env, c.id); channels = Notify.channels(ctx.env); editing = null }) else null,
            onTest = { test(it) },
        )
    }
}

/** The settings of one channel. A saved secret is never shown: left empty, it stays as it was. */
@Composable
private fun ChannelEditor(
    c: Notify.Channel, old: Notify.Channel?, onDismiss: () -> Unit,
    onSave: (Notify.Channel) -> Unit, onDelete: (() -> Unit)?, onTest: (Notify.Channel) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(c.name) }
    var enabled by remember { mutableStateOf(c.enabled) }
    var events by remember { mutableStateOf(c.events) }
    // Secrets start empty: what is saved stays unless something new is typed.
    var values by remember { mutableStateOf(c.kind.fields.associate { f -> f.key to if (f.secret) "" else c.value(f.key) }) }
    var problem by remember { mutableStateOf<String?>(null) }

    /** The channel as typed, or why it cannot be saved yet (in the user's words, not Notify's English). */
    fun built(): Notify.Channel? {
        for (f in c.kind.fields) {
            val v = values[f.key].orEmpty().trim()
            val kept = if (f.secret && v.isEmpty()) old?.value(f.key).orEmpty() else v
            if (kept.isEmpty() && !f.optional) { problem = ctx.getString(R.string.notify_channel_needed, fieldName(ctx, c.kind, f)); return null }
            if (f.url && kept.isNotEmpty() && !kept.startsWith("http://") && !kept.startsWith("https://")) {
                problem = ctx.getString(R.string.notify_channel_address, fieldName(ctx, c.kind, f)); return null
            }
        }
        return Notify.build(c.id, c.kind, name, enabled, events, values, old).getOrElse { problem = it.message; null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.kind.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(helpText(ctx, c.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.notify_channel_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                c.kind.fields.forEach { f ->
                    val saved = f.secret && old?.value(f.key)?.isNotEmpty() == true
                    OutlinedTextField(
                        values[f.key].orEmpty(), { v -> values = values + (f.key to v); problem = null },
                        label = { Text(fieldName(ctx, c.kind, f)) },
                        placeholder = { Text(if (saved) Notify.mask(old!!.value(f.key)) + " · " + stringResource(R.string.notify_channel_secret_keep) else f.placeholder) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.secret) KeyboardType.Password else if (f.url) KeyboardType.Uri else KeyboardType.Text),
                    )
                }
                Text(stringResource(R.string.notify_channel_events), style = MaterialTheme.typography.titleSmall)
                Notify.Event.entries.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = e in events, onCheckedChange = { on -> events = if (on) events + e else events - e })
                        Text(eventName(ctx, e), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notify_channel_enabled), Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { built()?.let(onTest) }) { Text(stringResource(R.string.notify_channel_test)) }
                    onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { built()?.let(onSave) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

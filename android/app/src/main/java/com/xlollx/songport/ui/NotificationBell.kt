package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.R
import com.xlollx.songport.notify.NotifyHistory
import com.xlollx.songport.update.AppUpdates
import com.xlollx.songport.update.UpdateState
import kotlinx.coroutines.launch

/** The bell in the Syncs bar (for the UI tests); with a new version it carries [BAR_UPDATE_TAG], the arrow's old tag. */
const val BAR_BELL_TAG = "bar-bell"
const val BELL_SHEET_TAG = "bell-sheet"

/**
 * The bell in the Syncs bar (2.2): the notices Trackhop gave (NotifyHistory), with the number not
 * read yet on its corner, or a small up arrow when a new version of the app is out, in place of the
 * update button that used to stand here. A tap opens the history; an entry opens what its notice
 * opened ([onOpen], a NotifTarget encoded), the update its build's own flow.
 */
@Composable
fun NotificationBell(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val history = remember { NotifyHistory.of(ctx.filesDir) }
    val entries by history.entries.collectAsState()
    val available by UpdateState.available.collectAsState()
    val scope = rememberCoroutineScope()
    // As the arrow did: the update looked up each time the app comes to the front.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val check = scope.launch { AppUpdates.check(ctx) }
        onPauseOrDispose { check.cancel() }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val unread = NotifyHistory.unread(entries)
    val badge = NotifyHistory.badge(unread, available != null)
    val label = when {
        available != null -> stringResource(R.string.bell_desc_update)
        unread > 0 -> pluralStringResource(R.plurals.bell_desc_unread, unread, unread)
        else -> stringResource(R.string.bell_title)
    }
    BarRoundButton(
        label, MaterialTheme.colorScheme.primaryContainer,
        Modifier.testTag(if (available != null) BAR_UPDATE_TAG else BAR_BELL_TAG), onClick = { open = true },
    ) {
        Icon(Icons.Filled.Notifications, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        if (badge != null) {
            val up = available != null
            val bg = if (up) androidx.compose.ui.graphics.Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
            val fg = if (up) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onError
            // Ringed in the bar's colour so it reads on the button's edge.
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).heightIn(min = 18.dp).widthIn(min = 18.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                if (up) Icon(Icons.Filled.ArrowUpward, null, Modifier.size(12.dp), tint = fg)
                else Text(badge, color = fg, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp))
            }
        }
    }
    if (open) NotificationSheet(history, entries, available, onDismiss = { open = false }) { target ->
        open = false
        onOpen(target)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationSheet(
    history: NotifyHistory, entries: List<NotifyHistory.Entry>, available: UpdateState.Available?,
    onDismiss: () -> Unit, onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    // Android's notifications off for Trackhop: said here, since this is where they land then.
    val posting = remember { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(BELL_SHEET_TAG)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bell_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    if (entries.any { !it.read }) TextButton(onClick = { history.markAllRead() }) { Text(stringResource(R.string.bell_mark_all_read)) }
                    if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.bell_clear)) }
                }
            }
            if (available != null) item { UpdateRow(available) }
            if (!posting) item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.bell_notifications_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                        }
                    }) { Text(stringResource(R.string.bell_turn_on)) }
                }
            }
            if (entries.isEmpty()) item {
                Text(
                    stringResource(R.string.bell_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            val today = java.time.LocalDate.now()
            var lastDay: java.time.LocalDate? = null
            for (e in entries) {
                val day = java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { DayHeader(day, today) }
                }
                item(key = e.id) {
                    EntryRow(e) {
                        history.markRead(e.id)
                        e.target?.let(onOpen)
                    }
                }
            }
            if (entries.isNotEmpty()) item {
                Text(
                    stringResource(R.string.bell_kept, NotifyHistory.KEEP_DAYS, NotifyHistory.MAX), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(R.string.bell_clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; history.clear() }) { Text(stringResource(R.string.bell_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The new version, on top: Download (GitHub) or Update (Google Play's flow). */
@Composable
private fun UpdateRow(a: UpdateState.Available) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.ArrowUpward, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(Modifier.weight(1f)) {
            Text(
                a.versionName?.let { stringResource(R.string.notif_update_title, it) } ?: stringResource(R.string.bell_update_ready),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.bell_update_have, com.xlollx.songport.BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        FilledTonalButton(onClick = { AppUpdates.open(ctx) }) {
            Text(stringResource(if (a.versionName != null) R.string.bell_update_download else R.string.bell_update_update))
        }
    }
}

@Composable
private fun DayHeader(day: java.time.LocalDate, today: java.time.LocalDate) {
    val text = when (day) {
        today -> stringResource(R.string.bell_today)
        today.minusDays(1) -> stringResource(R.string.bell_yesterday)
        else -> java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).format(day)
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

private fun iconOf(kind: String): ImageVector = when (kind) {
    "sync" -> Icons.Filled.CheckCircle
    "failed" -> Icons.Filled.Error
    "review" -> Icons.Filled.RateReview
    "unplayable" -> Icons.Filled.MusicOff
    "alerts" -> Icons.Filled.Person
    "concert" -> Icons.Filled.Event
    "record" -> Icons.Filled.Album
    "signin" -> Icons.Filled.Key
    else -> Icons.Filled.Info
}

@Composable
private fun EntryRow(e: NotifyHistory.Entry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val time = remember(e.at) { android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(e.at)) }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(iconOf(e.kind), null, Modifier.size(18.dp), tint = if (e.kind == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (e.read) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!e.read) Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

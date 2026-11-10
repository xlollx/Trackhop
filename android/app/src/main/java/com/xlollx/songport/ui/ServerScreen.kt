package com.xlollx.songport.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.HandOver
import com.xlollx.songport.sync.ServerLink
import com.xlollx.songport.sync.ServerSyncs
import kotlinx.coroutines.launch

/**
 * Tools › My server: the syncs of the user's own Trackhop server ("Coordinate with my server"),
 * each with how it stands and its last run, a button to run it there and one to review what its
 * last run left. Reading the list also exchanges the review's choices of the syncs both know.
 * Nothing here changes the server's syncs themselves: that is the server's page.
 */
@Composable
fun ServerSyncsPage(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ServerSyncs.Sync>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var reviewing by remember { mutableStateOf<String?>(null) }
    val data = Store.get(ctx).state.collectAsState().value
    LaunchedEffect(reload) {
        loading = true
        try {
            list = ServerLink.refresh(ctx); error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ServerLink.say(ctx, e)
        }
        loading = false
    }
    reviewing?.let { ServerReviewSheet(it) { reviewing = null; reload++ } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ServerLink.api(ctx)?.base?.removePrefix("http://")?.removePrefix("https://").orEmpty(),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            else IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, stringResource(R.string.server_refresh)) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        val shown = list
        if (shown != null && shown.isEmpty()) Text(stringResource(R.string.server_empty), style = MaterialTheme.typography.bodyMedium)
        shown?.forEach { s ->
            val mine = data.jobs.firstOrNull { j -> data.handedOver[j.id]?.serverId == s.id }
            // The same sync on this phone: its review choices are shared once the user says so.
            val same = if (mine != null) null else data.jobs.firstOrNull { j -> data.handedOver[j.id] == null && HandOver.sameAs(j, s) }
            val paired = same != null && data.serverPairs[same.id] == s.id
            ServerSyncCard(s, fromPhone = mine != null, alsoHere = same != null, paired = paired, onPair = same?.let { j -> {
                ServerLink.pair(ctx, j, if (paired) null else s.id)
                if (!paired) reload++
            } }, onRun = {
                scope.launch {
                    val o = ServerLink.run(ctx, s.id)
                    snackbar.showSnackbar(o.message)
                    if (o.ok) reload++
                }
            }, onReview = { s.last?.let { reviewing = it.id } })
        }
    }
}

/** What a server's sync did last, in a few words; null when it never ran. */
@Composable
private fun lastRunText(s: ServerSyncs.Sync): String? {
    val r = s.last ?: return null
    if (!r.ok) return stringResource(R.string.server_last_failed, r.error.orEmpty())
    return stringResource(R.string.server_last_result, relativeTime(r.started.takeIf { it > 0 } ?: s.lastRun ?: 0), r.added, r.removed)
}

@Composable
private fun ServerSyncCard(
    s: ServerSyncs.Sync, fromPhone: Boolean, alsoHere: Boolean, paired: Boolean, onPair: (() -> Unit)?,
    onRun: () -> Unit, onReview: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(Providers.byId(s.source.service), 28.dp)
                Spacer(Modifier.width(4.dp))
                ProviderBadge(Providers.byId(s.target.service), 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.source.serviceName} · ${s.source.playlist}  →  ${s.target.serviceName} · ${s.target.playlist}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(onClick = onRun, enabled = !s.running, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.run_now))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    s.running -> StatusPill(stringResource(R.string.pill_running), Tone.Accent)
                    !s.enabled -> StatusPill(stringResource(R.string.disabled), Tone.Neutral)
                    s.last == null -> StatusPill(stringResource(R.string.pill_never), Tone.Neutral)
                    s.last?.ok == false -> StatusPill(stringResource(R.string.pill_error), Tone.Error)
                    else -> StatusPill(stringResource(R.string.pill_ok), Tone.Ok)
                }
                if (fromPhone) StatusPill(stringResource(R.string.server_from_phone), Tone.Accent, Icons.Filled.Cloud)
                else if (paired) StatusPill(stringResource(R.string.server_paired), Tone.Accent)
                else if (alsoHere) StatusPill(stringResource(R.string.server_also_here), Tone.Neutral)
                Spacer(Modifier.weight(1f))
                onPair?.let { TextButton(onClick = it) { Text(stringResource(if (paired) R.string.server_unpair else R.string.server_pair)) } }
            }
            if (s.timing.isNotBlank()) Text(s.timing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lastRunText(s)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (s.last?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            val toFix = s.last?.let { it.notFound + it.toCheck } ?: 0
            if (toFix > 0) FilledTonalButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Build, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.review_now, toFix))
            }
        }
    }
}

/**
 * A run of the server, reviewed from the phone: the tracks it did not find (Ignore) and the matches
 * to check (Looks right). Choosing another track is done on the server's page, where its accounts are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerReviewSheet(runId: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var review by remember { mutableStateOf<ServerSyncs.Review?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(runId) {
        try {
            review = ServerLink.api(ctx)?.review(runId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ServerLink.say(ctx, e)
        }
    }
    fun act(ignore: Boolean, id: String) {
        busy = true
        scope.launch {
            try {
                review = ServerLink.api(ctx)?.act(runId, ignore, listOf(id)); error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ServerLink.say(ctx, e)
            }
            busy = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.server_review_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.server_review_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val r = review
            if (r == null && error == null) CircularProgressIndicator(Modifier.size(28.dp))
            if (r != null) {
                if (r.notFound.isEmpty() && r.toCheck.isEmpty()) Text(stringResource(R.string.unmatched_none), style = MaterialTheme.typography.bodyMedium)
                if (r.notFound.isNotEmpty()) {
                    Text(stringResource(R.string.review_section_unmatched, r.notFound.size), style = MaterialTheme.typography.titleSmall)
                    r.notFound.forEach { t ->
                        ReviewRow("${t.artist} - ${t.title}", null) { TextButton(enabled = !busy, onClick = { act(true, t.id) }) { Text(stringResource(R.string.unmatched_ignore)) } }
                    }
                }
                if (r.toCheck.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.review_section_uncertain, r.toCheck.size), style = MaterialTheme.typography.titleSmall)
                    r.toCheck.forEach { m ->
                        ReviewRow("${m.source.artist} - ${m.source.title}", "→ ${m.match.artist} - ${m.match.title}") {
                            OutlinedButton(enabled = !busy, onClick = { act(false, m.source.id) }) { Text(stringResource(R.string.review_keep)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(title: String, sub: String?, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        action()
    }
}

/**
 * "Hand over to the server" ([back] false) or "Bring back to this phone" ([back] true) for [job]:
 * what it means, then the answer; nothing changes on the phone until the server has done its part.
 */
@Composable
fun HandOverDialog(job: SyncJob, back: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<ServerLink.Outcome?>(null) }
    FitDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (back) R.string.server_bring_back_title else R.string.server_handover_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (back) R.string.server_bring_back_body else R.string.server_handover_body), style = MaterialTheme.typography.bodyMedium)
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                answer?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (answer?.ok == true) TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            else TextButton(enabled = !busy, onClick = {
                busy = true; answer = null
                scope.launch {
                    answer = if (back) ServerLink.bringBack(ctx, job) else ServerLink.handOver(ctx, job)
                    busy = false
                }
            }) { Text(stringResource(if (back) R.string.server_bring_back else R.string.server_handover)) }
        },
        dismissButton = { if (answer?.ok != true) TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

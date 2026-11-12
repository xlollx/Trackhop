package com.xlollx.songport.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.ui.text.font.FontWeight
import com.xlollx.songport.providers.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncPlan
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.sync.ServiceReads
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.launch

/**
 * Cosa farebbe la sync, prima di farla: gia' presenti, da aggiungere, da rimuovere, non trovati,
 * e gli abbinamenti incerti da confermare o cambiare sul posto. Per YouTube mostra anche quanta
 * quota giornaliera costerebbe. Si esegue solo dopo aver visto tutto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(job: SyncJob, onClose: () -> Unit, onRun: (SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val engine = remember { SyncEngine(ctx) }
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<SyncPlan?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemovals by remember { mutableStateOf(false) }
    var confirmShrink by remember { mutableStateOf(false) }
    // Another sync reading the same connector, which lists one playlist at a time: said, not a silent spinner.
    var waitingFor by remember { mutableStateOf<ServiceReads.Reader?>(null) }
    ScreenBackHandler(onClose)

    LaunchedEffect(job.id) {
        try { plan = engine.plan(job, { progress = it }, createTarget = false, interactive = true, onWait = { waitingFor = it }) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.error_generic) }
    }

    val dst = Providers.byId(job.target.provider)
    val dstName = dst?.displayName ?: job.target.provider
    val p = plan
    val hasWork = p != null && (p.toAdd.isNotEmpty() || p.toRemove.isNotEmpty() || job.target.playlistId == null)
    // Into Liked songs a removal is an unlike: the counts and the confirmation say so.
    val unlikes = job.target.playlistId == MusicProvider.LIKED_ID

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title, job.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                actions = { HelpButton(Helps.preview, Helps.previewRun, label = stringResource(R.string.preview)) },
            )
        },
        // Run now stays in reach under a long list; the confirmations it depends on sit above it.
        bottomBar = {
            if (p != null && hasWork) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        if (p.removalsNeedConfirmation && confirmRemovals) store.confirmRemovals(job.id, p.toRemove.map { it.id })
                        if (p.targetShrunkFrom > 0 && confirmShrink) store.confirmTargetShrink(job.id)
                        onRun(job)
                    },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.run_now))
                }
            }
        },
    ) { padding ->
        // A readable column, centred in a wide window (a tablet, a phone on its side).
        Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = WindowLayout.READABLE_CONTENT).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RouteCard(job, newTarget = job.target.playlistId == null)
            when {
                error != null -> Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = ToolCardShape, color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    }
                }
                p == null -> PlanSteps(progress, waitingFor)
                else -> {
                    // The plan in figures, as on the card after a run.
                    val figures = buildList<@Composable (Modifier) -> Unit> {
                        add { m -> Stat("+${p.toAdd.size}", stringResource(R.string.preview_tile_add), m, tint = MaterialTheme.colorScheme.primary) }
                        if (job.mirrorRemovals || job.twoWayRemovals) add { m ->
                            Stat(
                                "−${p.toRemove.size}", stringResource(if (unlikes) R.string.preview_tile_unlike else R.string.preview_tile_remove), m,
                                tint = if (p.removalsNeedConfirmation) MaterialTheme.colorScheme.error else null,
                            )
                        }
                        add { m -> Stat("${p.alreadyPresent}", stringResource(R.string.preview_tile_present), m) }
                        add { m -> Stat("${p.unmatched.size}", stringResource(R.string.stat_not_found), m, tint = if (p.unmatched.isNotEmpty()) MaterialTheme.colorScheme.tertiary else null) }
                    }
                    // With a large font four tiles in a row cut their words short: two rows of two then.
                    val perRow = if (LocalDensity.current.fontScale > 1.15f) 2 else figures.size
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        figures.chunked(perRow).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { it(Modifier.weight(1f)) }
                                // An odd one out keeps the width of its fellows.
                                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (!hasWork) PageCard {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusMark(Icons.Filled.Check, Tone.Ok)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.preview_nothing), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                    // What else is worth knowing before running: each on its own line, the warnings first.
                    val infos = buildList {
                        if (p.removalsNeedConfirmation) add(stringResource(if (unlikes) R.string.preview_unlike_warning else R.string.preview_remove_warning, p.toRemove.size, p.targetCount) to true)
                        if (p.uncertain.isNotEmpty()) add(stringResource(R.string.preview_uncertain, p.uncertain.size) to false)
                        if (p.ignored > 0) add(stringResource(R.string.preview_ignored, p.ignored) to false)
                        p.notes.forEach { add(it to false) }
                    }
                    if (infos.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        infos.forEach { (text, warn) -> PageNote(text, tone = if (warn) Tone.Error else Tone.Neutral, icon = if (warn) Icons.Filled.Warning else null) }
                    }
                    if (dst?.serviceId == YouTubeProvider.SERVICE) {
                        PageCard {
                            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)) {
                                QuotaCard(
                                    used = QuotaMeter.used(data, YouTubeProvider.SERVICE),
                                    searches = QuotaMeter.searchesUsed(data, YouTubeProvider.SERVICE),
                                    sharedCredentials = !dst.usesOwnCredentials(ctx),
                                    estimate = QuotaMeter.estimateAdd(p.toAdd.size),
                                )
                            }
                        }
                    }
                    if (hasWork && (p.removalsNeedConfirmation || p.targetShrunkFrom > 0)) PageCard(edge = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                        // The one place large removals are confirmed: a deliberate tick, then Run now.
                        // Unticked, the run adds as usual and holds the removals back again.
                        if (p.removalsNeedConfirmation) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = confirmRemovals, role = Role.Checkbox, onValueChange = { confirmRemovals = it }).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = confirmRemovals, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(if (unlikes) R.string.preview_unlike_confirm else R.string.preview_remove_confirm, p.toRemove.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                HelpButton(Helps.removals)
                            }
                        }
                        // The target lists far fewer tracks than the last run left (see the note above): a
                        // run stops unless this is ticked, as a listing read in part would add everything again.
                        if (p.targetShrunkFrom > 0) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = confirmShrink, role = Role.Checkbox, onValueChange = { confirmShrink = it }).padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = confirmShrink, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.preview_shrunk_confirm, p.targetCount, p.targetShrunkFrom), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    }
                    if (p.uncertain.isNotEmpty()) {
                        PageSectionTitle(stringResource(R.string.review_section_uncertain, p.uncertain.size), accent = JobColor.PURPLE) { HelpButton(Helps.uncertain) }
                        var openReview by remember { mutableStateOf<String?>(null) }
                        val webSearch = remember(dst) { dst?.webSearch(ctx) }
                        p.uncertain.forEach { review ->
                            ReviewRow(
                                review = review, dstName = dstName,
                                expanded = openReview == review.source.id,
                                onToggle = { openReview = if (openReview == review.source.id) null else review.source.id },
                                onKeep = {
                                    // Settled here: remembered, so the run does not ask again.
                                    engine.rematchInPlan(job, review, review.chosen)
                                    plan = p.copy(uncertain = p.uncertain - review)
                                },
                                search = { q -> engine.searchOnTarget(job, q, review.source) },
                                webSearchUrl = webSearch,
                                onReplace = { chosen ->
                                    engine.rematchInPlan(job, review, chosen)
                                    plan = p.copy(
                                        uncertain = p.uncertain - review,
                                        toAdd = p.toAdd.filter { it.id != review.chosen.id } + chosen,
                                    )
                                },
                                onError = { m -> scope.launch { snackbar.showSnackbar(m.ifBlank { ctx.getString(R.string.error_generic) }) } },
                            )
                        }
                    }
                    TrackList(stringResource(R.string.preview_add, p.toAdd.size), p.toAdd, MaterialTheme.colorScheme.primary)
                    TrackList(stringResource(if (unlikes) R.string.preview_unlike else R.string.preview_remove, p.toRemove.size), p.toRemove, MaterialTheme.colorScheme.error)
                    TrackList(stringResource(R.string.preview_unmatched, p.unmatched.size), p.unmatched, MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

/** From where to where: each side's service mark and playlist; a target still to make says so. */
@Composable
private fun RouteCard(job: SyncJob, newTarget: Boolean) {
    val ctx = LocalContext.current
    PageCard(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(job.source, job.target).forEachIndexed { i, ref ->
                val provider = Providers.byId(ref.provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderBadge(provider, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ref.playlistName.ifBlank { job.name }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(provider?.label(ctx) ?: ref.provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (i == 0) Icon(Icons.Filled.ArrowDownward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp).size(20.dp))
            }
            if (newTarget) PageNote(stringResource(R.string.preview_target_new), tone = Tone.Accent)
        }
    }
}

/**
 * What the preview is doing while it works it out: reading the source, reading the target, matching
 * the tracks; the step under way turns, the ones done are ticked, matching fills a bar as it goes.
 */
@Composable
private fun PlanSteps(progress: Progress?, waitingFor: ServiceReads.Reader? = null) {
    val step = when (progress?.step) {
        null, Progress.Step.FETCH_SOURCE -> 0
        Progress.Step.CREATE_TARGET, Progress.Step.FETCH_TARGET -> 1
        Progress.Step.WAITING -> -1
        else -> 2
    }
    // A pause asked by the service keeps the step it came in.
    var last by remember { mutableStateOf(0) }
    val current = if (step < 0) last else step.also { last = it }
    PageCard {
        Column(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(
                stringResource(R.string.progress_fetch_source),
                stringResource(R.string.progress_fetch_target),
                if (current == 2 && progress != null && progress.total > 0) stringResource(R.string.progress_matching, progress.done, progress.total) else stringResource(R.string.preview_step_match),
            ).forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            i < current -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            i == current -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            else -> Box(Modifier.size(18.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label, style = MaterialTheme.typography.bodyMedium,
                        color = if (i > current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (i == 2 && current == 2) progress?.percent?.let { pc ->
                    LinearProgressIndicator(progress = { pc / 100f }, modifier = Modifier.fillMaxWidth().padding(start = 36.dp))
                }
            }
            if (waitingFor != null) PageNote(stringResource(R.string.preview_waiting_read, waitingFor.jobName, waitingFor.service), tone = Tone.Accent)
            if (progress?.step == Progress.Step.WAITING) Text(
                stringResource(R.string.progress_waiting, (progress.total - progress.done).coerceAtLeast(0)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun progressLabel(p: Progress): String = when (p.step) {
    Progress.Step.MATCHING -> stringResource(R.string.progress_matching, p.done, p.total)
    Progress.Step.FETCH_TARGET -> stringResource(R.string.progress_fetch_target)
    else -> stringResource(R.string.progress_fetch_source)
}

/** One list of the plan in a card: a coloured mark, title and artist per track; the first few, then all on a tap. */
@Composable
private fun TrackList(title: String, tracks: List<Track>, accent: Color, first: Int = 8) {
    if (tracks.isEmpty()) return
    var all by remember(tracks) { mutableStateOf(false) }
    PageCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(6.dp))
            (if (all) tracks.take(500) else tracks.take(first)).forEach { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).semantics(mergeDescendants = true) {}) {
                    Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(t.artistLine, t.album).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!all && tracks.size > first) TextButton(onClick = { all = true }, modifier = Modifier.padding(start = 4.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.preview_more, tracks.size - first))
            }
        }
    }
}

package com.xlollx.songport.ui

import com.xlollx.songport.ai.config
import com.xlollx.songport.providers.*
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Da rivedere dopo (o durante) una sync: gli abbinamenti incerti, da confermare o cambiare, e i
 * brani non trovati, da cercare a mano o ignorare. Ogni scelta finisce in cache e vale per le sync
 * future.
 *
 * Una riga per brano, chiusa: titolo e artista, niente altro. Toccandola si apre, una sola alla
 * volta, con la ricerca sulla destinazione gia' avviata e i candidati da scegliere. Cosi' anche
 * cento brani restano una lista leggibile, non un muro di campi di testo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnmatchedScreen(reportId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val report = data.reports.firstOrNull { it.id == reportId }
    val job = report?.let { r -> data.jobs.firstOrNull { it.id == r.jobId } }
    val engine = remember { SyncEngine(ctx) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    // Rows picked to act on together ("r-" uncertain match, "u-" not found): a long press starts it.
    var picked by remember { mutableStateOf(setOf<String>()) }
    // What the remove dialog is about to take off the target.
    var discarding by remember { mutableStateOf<List<MatchReview>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val reviewKeys = report?.reviewTracks.orEmpty().map { "r-" + it.source.id }.toSet()
    val unmatchedKeys = report?.unmatchedTracks.orEmpty().map { "u-" + it.id }.toSet()
    // Rows done meanwhile drop out of the selection.
    val selection = picked.filter { it in reviewKeys || it in unmatchedKeys }.toSet()
    val selecting = selection.isNotEmpty()
    ScreenBackHandler(onClose)
    BackHandler(enabled = selecting) { picked = emptySet() }
    // The target's entries read ahead while the review is open: removing a wrong match then needs
    // no read of its own. Stops with the screen.
    LaunchedEffect(job?.id, report?.id) {
        val j = job ?: return@LaunchedEffect
        val chosen = report?.reviewTracks.orEmpty().map { it.chosen }
        if (chosen.isNotEmpty() && (report?.targetId == null || report.targetId == j.target.playlistId)) {
            withContext(Dispatchers.IO) { engine.prefetchEntries(j, chosen) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) TopAppBar(
                title = { Text(stringResource(R.string.review_selected, selection.size), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { picked = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.review_clear_selection)) } },
                actions = {
                    if (selection.size < reviewKeys.size + unmatchedKeys.size) {
                        IconButton(onClick = { picked = reviewKeys + unmatchedKeys }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.review_select_all)) }
                    }
                },
            ) else TopAppBar(
                title = { Text(stringResource(R.string.review_title, report?.jobName ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                actions = {
                    HelpButton(Helps.change, Helps.proposal, Helps.ignore, label = stringResource(R.string.help_review_title))
                    // The list as text, to keep or send: what was not found and what was matched loosely.
                    if (report != null && (report.unmatchedTracks.isNotEmpty() || report.reviewTracks.isNotEmpty())) {
                        IconButton(onClick = {
                            val text = buildString {
                                append(ctx.getString(R.string.review_title, report.jobName)).append('\n')
                                append(ctx.getString(R.string.review_summary, report.unmatchedTracks.size, report.reviewTracks.size)).append("\n\n")
                                if (report.unmatchedTracks.isNotEmpty()) {
                                    append(ctx.getString(R.string.review_section_unmatched, report.unmatchedTracks.size)).append('\n')
                                    report.unmatchedTracks.forEach { t -> append("- ").append(t.toString()); if (t.album.isNotBlank()) append(" · ").append(t.album); append('\n') }
                                    append('\n')
                                }
                                if (report.reviewTracks.isNotEmpty()) {
                                    append(ctx.getString(R.string.review_section_uncertain, report.reviewTracks.size)).append('\n')
                                    report.reviewTracks.forEach { r -> append("- ").append(r.source.toString()).append(" → ").append(r.chosen.toString()).append(" (").append((r.score * 100).toInt()).append("%)\n") }
                                }
                            }
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.review_title, report.jobName))
                            runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                        }) { Icon(Icons.Filled.Share, stringResource(R.string.review_share)) }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting && report != null && job != null) {
                val pickedReviews = report.reviewTracks.filter { "r-" + it.source.id in selection }
                val pickedTracks = report.unmatchedTracks.filter { "u-" + it.id in selection }
                // What fits the rows picked, on one line where there is room and on two where there is not
                // (a narrow phone, a large font): never cut, each button a full target.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    FlowRow(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (pickedTracks.isNotEmpty()) {
                            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { engine.ignore(job, reportId, pickedTracks); picked = picked - pickedTracks.map { "u-" + it.id }.toSet() }) {
                                Text(stringResource(R.string.unmatched_ignore))
                            }
                        }
                        if (pickedReviews.isNotEmpty()) {
                            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { discarding = pickedReviews }) { Text(stringResource(R.string.review_discard)) }
                            FilledTonalButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { engine.confirmMatches(reportId, pickedReviews); picked = picked - pickedReviews.map { "r-" + it.source.id }.toSet() }) {
                                Text(stringResource(R.string.review_keep))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        // Once each: a report saved before 1.6.1 can hold the same song twice, and two rows with one
        // key close the app (issue #24).
        val reviews = report?.reviewTracks.orEmpty().distinctBy { it.source.id }
        val tracks = report?.unmatchedTracks.orEmpty().distinctBy { it.id }
        if (report == null || job == null || (tracks.isEmpty() && reviews.isEmpty())) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                StatusMark(Icons.Filled.Check, Tone.Ok, size = 72.dp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.unmatched_none), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        val dst = Providers.byId(job.target.provider)
        val dstName = dst?.displayName ?: job.target.provider
        // Only when the service has a web search: Subsonic servers other than Navidrome, and Plex, have none.
        val webSearch: ((String) -> String?)? = remember(dst) { dst?.webSearch(ctx) }
        // A candidate opened on the target service, in whatever app the phone opens its links with (#31).
        val openTrack: ((Track) -> String?)? = remember(dst) { dst?.let { p -> { t: Track -> p.trackUrl(ctx, t.id) } } }
        // The user's AI, when they connected one for the generator: the same key helps here.
        val ai = remember { com.xlollx.songport.ai.AiClient.config(ctx)?.takeIf { it.complete } }
        val language = remember { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH) }
        fun askFor(source: Track): (suspend (List<Track>) -> com.xlollx.songport.ai.AiClient.Suggestion)? =
            ai?.let { cfg -> { cands -> com.xlollx.songport.ai.AiClient.suggestMatch(cfg, source, cands, dstName, language) } }
        fun fail(msg: String) { scope.launch { snackbar.showSnackbar(msg.ifBlank { ctx.getString(R.string.error_generic) }) } }
        fun toggle(id: String) { expanded = if (expanded == id) null else id }
        fun pick(key: String) { picked = if (key in selection) selection - key else selection + key }
        // A tap picks while selecting, and opens the row otherwise.
        fun tap(key: String) { if (selecting) pick(key) else toggle(key) }

        discarding?.let { list ->
            val removes = dst?.canRemoveTracks != false
            FitDialog(
                onDismissRequest = { if (!busy) discarding = null },
                title = { Text(stringResource(R.string.review_discard_title, dstName)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (removes) R.string.review_discard_body else R.string.review_discard_body_kept, dstName))
                        // The removal may first read the target playlist (a search result has no entry id) when
                        // the last sync's read is not enough: seconds on a long one, so it says what it is doing.
                        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.review_discard_busy, dstName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val n = try {
                                engine.discardMatches(job, reportId, list)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e.message ?: ""); null }
                            finally { busy = false; discarding = null }
                            picked = picked - list.map { "r-" + it.source.id }.toSet()
                            // Shown after: the message stays a few seconds and the bar must work meanwhile.
                            if (n != null && removes) snackbar.showSnackbar(ctx.getString(R.string.review_discarded, dstName, n))
                        }
                    }) { Text(stringResource(R.string.review_discard)) }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { discarding = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        // A readable column, centred in a wide window (a tablet, a phone on its side).
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = WindowLayout.READABLE_CONTENT).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "summary") {
                PageCard(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // From where to where, then what is left to do.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                                ServiceLogo(Providers.byId(job.source.provider), 36.dp)
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                ServiceLogo(dst, 36.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.review_summary, tracks.size, reviews.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                        }
                        Text(stringResource(R.string.review_tap_hint, dstName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (reviews.size + tracks.size > 1) PageNote(stringResource(R.string.review_select_hint), icon = Icons.Filled.TouchApp)
                        if (report.partial) PageNote(stringResource(R.string.review_partial_banner), tone = Tone.Accent, icon = Icons.Filled.Sync)
                    }
                }
            }
            if (reviews.isNotEmpty()) {
                item(key = "h-review") {
                    SectionTitle(stringResource(R.string.review_section_uncertain, reviews.size), JobColor.PURPLE, onSelectAll = { picked = selection + reviews.map { "r-" + it.source.id } }.takeIf { reviews.size > 1 })
                }
                items(reviews, key = { "r-" + it.source.id }) { review ->
                    ReviewRow(
                        review = review, dstName = dstName,
                        expanded = !selecting && expanded == "r-" + review.source.id, onToggle = { tap("r-" + review.source.id) },
                        selected = ("r-" + review.source.id in selection).takeIf { selecting }, onLongPress = { pick("r-" + review.source.id) },
                        onKeep = { engine.confirmMatch(reportId, review) },
                        onDiscard = { discarding = listOf(review) },
                        search = { q -> engine.searchOnTarget(job, q, review.source) },
                        webSearchUrl = webSearch, openTrack = openTrack, askAi = askFor(review.source),
                        onReplace = { chosen -> engine.replaceMatch(job, reportId, review, chosen) },
                        onError = ::fail,
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                item(key = "h-unmatched") {
                    SectionTitle(stringResource(R.string.review_section_unmatched, tracks.size), JobColor.PINK, onSelectAll = { picked = selection + tracks.map { "u-" + it.id } }.takeIf { tracks.size > 1 })
                }
                // What the service has no trace of goes last: there is little to do about it by hand.
                items(tracks.sortedBy { it.id in report.absent }, key = { "u-" + it.id }) { track ->
                    UnmatchedRow(
                        track = track, dstName = dstName, suggestion = report.suggestions[track.id], absent = track.id in report.absent,
                        expanded = !selecting && expanded == "u-" + track.id, onToggle = { tap("u-" + track.id) },
                        selected = ("u-" + track.id in selection).takeIf { selecting }, onLongPress = { pick("u-" + track.id) },
                        search = { q -> engine.searchOnTarget(job, q, track) },
                        webSearchUrl = webSearch, openTrack = openTrack, askAi = askFor(track),
                        onPick = { c -> engine.resolveManually(job, reportId, track, c) },
                        onIgnore = { engine.ignore(job, reportId, track) },
                        onError = ::fail,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun SectionTitle(text: String, accent: JobColor, onSelectAll: (() -> Unit)? = null) {
    PageSectionTitle(text, Modifier.padding(top = 4.dp), accent = accent) {
        if (onSelectAll != null) TextButton(onClick = onSelectAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.review_select_all)) }
    }
}

/**
 * Riga chiusa: un segno ([leading]), titolo e artista, freccia a destra. Aperta: le azioni.
 * A card of the page ([PageCard]); picked, it turns to the selection's colour and its mark to a tick box.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun CollapsibleRow(
    track: Track, expanded: Boolean, onToggle: () -> Unit, absent: Boolean = false,
    trailing: @Composable () -> Unit = {}, below: (@Composable () -> Unit)? = null,
    /** Picked or not while rows are being selected; null when they are not. */
    selected: Boolean? = null, onLongPress: (() -> Unit)? = null,
    /** What stands before the title when rows are not being selected: the row's state as a mark. */
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tap = if (onLongPress != null) Modifier.combinedClickable(onClick = onToggle, onLongClick = onLongPress) else Modifier.clickable(onClick = onToggle)
    PageCard(selected = selected == true) {
        Column(Modifier.then(tap).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    // The row owns the tap: the box only shows the state.
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Checkbox(checked = selected, onCheckedChange = null) }
                    Spacer(Modifier.width(12.dp))
                } else if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(track.artistLine.ifBlank { null }, track.album.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = if (selected == true) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (absent) Text(stringResource(R.string.review_absent), style = MaterialTheme.typography.labelMedium, color = if (selected == true) LocalContentColor.current else MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.width(8.dp))
                trailing()
                if (selected == null) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected == null) below?.invoke()
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp)) { content() }
            }
        }
    }
}

/** A track proposed or chosen for another, set into its row: quiet, a step back from the row's own words. */
@Composable
private fun InsetTrack(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

/** Origine -> scelto, con il punteggio; aperta: "Va bene" o ricerca di un sostituto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewRow(
    review: MatchReview,
    dstName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onKeep: () -> Unit,
    search: suspend (String) -> TargetSearch,
    onReplace: suspend (Track) -> Unit,
    onError: (String) -> Unit,
    webSearchUrl: ((String) -> String?)? = null,
    openTrack: ((Track) -> String?)? = null,
    askAi: (suspend (List<Track>) -> com.xlollx.songport.ai.AiClient.Suggestion)? = null,
    /** The match is wrong and no other is wanted (issue #26); null where nothing was written yet. */
    onDiscard: (() -> Unit)? = null,
    selected: Boolean? = null,
    onLongPress: (() -> Unit)? = null,
) {
    var changing by remember { mutableStateOf(false) }
    val score = stringResource(R.string.review_score, (review.score * 100).toInt())
    CollapsibleRow(
        track = review.source, expanded = expanded, onToggle = onToggle, selected = selected, onLongPress = onLongPress,
        // How sure the match is, as the row's mark: the figure on the warning tone, read out in full.
        leading = {
            Box(
                Modifier.heightIn(min = 40.dp).widthIn(min = 40.dp).clip(CircleShape).background(toneBackground(Tone.Warning)).padding(horizontal = 6.dp)
                    .semantics { contentDescription = score },
                contentAlignment = Alignment.Center,
            ) {
                Text("${(review.score * 100).toInt()}%", style = MaterialTheme.typography.labelMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold, color = toneContent(Tone.Warning), maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
        },
        // While rows are picked the tick box takes the mark's place: the figure moves to the end.
        trailing = {
            if (selected != null) { StatusPill(score, Tone.Warning); Spacer(Modifier.width(2.dp)) }
        },
    ) {
        InsetTrack {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                TrackLine(review.chosen, Modifier.weight(1f))
            }
        }
        if (!changing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                if (onDiscard != null) TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.review_discard)) }
                TextButton(onClick = { changing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.review_change)) }
                FilledTonalButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.review_keep)) }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            val initial = if (review.source.artists.isEmpty()) review.source.title else "${review.source.artistLine} - ${review.source.title}"
            MatchSearch(
                initialQuery = initial, targetName = dstName, pickLabel = stringResource(R.string.review_use_this),
                search = search, onPick = onReplace, onError = onError, autoSearch = true, webSearchUrl = webSearchUrl, openTrack = openTrack, askAi = askAi,
                extraActions = { TextButton(onClick = { changing = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun UnmatchedRow(
    track: Track,
    dstName: String,
    suggestion: Track?,
    absent: Boolean = false,
    expanded: Boolean,
    onToggle: () -> Unit,
    search: suspend (String) -> TargetSearch,
    onPick: suspend (Track) -> Unit,
    onIgnore: () -> Unit,
    onError: (String) -> Unit,
    webSearchUrl: ((String) -> String?)? = null,
    openTrack: ((Track) -> String?)? = null,
    askAi: (suspend (List<Track>) -> com.xlollx.songport.ai.AiClient.Suggestion)? = null,
    selected: Boolean? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var accepting by remember { mutableStateOf(false) }
    // The sync's own runner-up, when it had one: accepted with a tap, no search to read through.
    val proposal: (@Composable () -> Unit)? = if (suggestion != null && !expanded) {
        {
            InsetTrack(Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.search_suggested), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = sectionTitleColor(JobColor.PINK))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackLine(suggestion, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(enabled = !accepting, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        accepting = true
                        scope.launch {
                            try { onPick(suggestion) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "") }
                            accepting = false
                        }
                    }) { Text(stringResource(R.string.unmatched_add)) }
                }
            }
        }
    } else null
    CollapsibleRow(
        track = track, expanded = expanded, onToggle = onToggle, absent = absent, below = proposal, selected = selected, onLongPress = onLongPress,
        leading = { StatusMark(if (absent) Icons.Filled.Block else Icons.Filled.SearchOff, if (absent) Tone.Warning else Tone.Neutral) },
    ) {
        MatchSearch(
            initialQuery = if (track.artists.isEmpty()) track.title else "${track.artistLine} - ${track.title}",
            targetName = dstName,
            pickLabel = stringResource(R.string.unmatched_add),
            search = search, onPick = onPick, onError = onError, autoSearch = true, webSearchUrl = webSearchUrl, openTrack = openTrack, askAi = askAi,
            extraActions = { busy -> TextButton(enabled = !busy, onClick = onIgnore) { Text(stringResource(R.string.unmatched_ignore)) } },
        )
    }
}

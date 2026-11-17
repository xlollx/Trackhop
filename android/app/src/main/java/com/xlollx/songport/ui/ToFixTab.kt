package com.xlollx.songport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.config
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.trackUrl
import com.xlollx.songport.providers.webSearch
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.ToFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** The tag of a row of the To fix list, before its [ToFix.Item.key]: for the flow tests' swipes. */
const val TO_FIX_ROW_TAG = "to-fix-row:"

/**
 * A choice is saved at once, then each sync's playlist is written: that goes on here when the page
 * is left meanwhile (a playlist not reached is added by the sync's next run anyway).
 */
private val toFixWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Saved matches › To fix (2.1, issue #69): every track not found or matched loosely, from all the
 * syncs, in one list ([ToFix]), grouped by sync or by service. Accept, Later and Search by hand are
 * buttons on each row; swiping right and left is a shortcut for the first two. A choice holds for
 * every sync that has the track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToFixTab(items: List<ToFix.Item>, snackbar: SnackbarHostState, onSeeSaved: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val engine = remember { SyncEngine(ctx) }
    val scope = rememberCoroutineScope()
    var byService by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(TO_FIX_PAGE) }
    var searching by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMark(Icons.Filled.Check, Tone.Ok, size = 88.dp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.to_fix_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.to_fix_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onSeeSaved, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.to_fix_see_saved)) }
        }
        return
    }

    fun say(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun nameOf(family: String) = providerOf(family, ctx = ctx)?.displayName ?: family

    /** The write goes on if the page is left; what it did is said here while the page is still open. */
    fun apply(item: ToFix.Item, ignored: Boolean, work: suspend () -> ToFix.Outcome) {
        val result = toFixWrites.async { work() }
        scope.launch {
            val outcome = try { result.await() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message?.ifBlank { null } ?: ctx.getString(R.string.error_generic)); return@launch
            }
            val failed = outcome.failed.distinct().joinToString(", ")
            when {
                failed.isNotEmpty() -> snackbar.showSnackbar(ctx.getString(if (ignored) R.string.to_fix_ignore_partial else R.string.to_fix_partial, failed), duration = SnackbarDuration.Long)
                ignored -> snackbar.showSnackbar(ctx.getString(R.string.saved_matches_marked_absent, nameOf(item.targetService)))
                outcome.syncs.size > 1 -> snackbar.showSnackbar(ctx.resources.getQuantityString(R.plurals.to_fix_done, outcome.syncs.size, outcome.syncs.size))
            }
        }
    }
    fun fix(item: ToFix.Item, chosen: Track) = apply(item, ignored = false) { engine.fixEverywhere(item, chosen) }
    fun ignore(item: ToFix.Item) = apply(item, ignored = true) { engine.ignoreEverywhere(item) }
    fun later(item: ToFix.Item) {
        ToFix.later(store, item)
        scope.launch {
            val undo = snackbar.showSnackbar(ctx.getString(R.string.to_fix_later_done), actionLabel = ctx.getString(R.string.unplayable_undo), duration = SnackbarDuration.Short)
            if (undo == SnackbarResult.ActionPerformed) ToFix.undoLater(store, item)
        }
    }

    // What the list is about, then how it is grouped.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append(stringResource(R.string.to_fix_once)) }
                    append(" ")
                    append(stringResource(R.string.to_fix_once_body))
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.to_fix_swipe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.to_fix_group), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SegmentedButton(selected = !byService, onClick = { byService = false; shown = TO_FIX_PAGE }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.to_fix_by_sync), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SegmentedButton(selected = byService, onClick = { byService = true; shown = TO_FIX_PAGE }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.to_fix_by_service), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    val groups = if (byService) ToFix.byService(items, ::nameOf) else ToFix.bySync(items)
    var left = shown
    for (group in groups) {
        if (left <= 0) break
        val rows = group.items.take(left)
        left -= rows.size
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (byService) ServiceLogo(providerOf(group.id, ctx = ctx), 24.dp)
            else data.jobs.firstOrNull { it.id == group.id }?.let { job ->
                Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                    ServiceLogo(Providers.byId(job.source.provider), 24.dp)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 2.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    ServiceLogo(Providers.byId(job.target.provider), 24.dp)
                }
            }
            Text(
                if (byService) stringResource(R.string.to_fix_on_service, group.name) else group.name,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(pluralStringResource(R.plurals.card_tracks, group.items.size, group.items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        for (item in rows) {
            androidx.compose.runtime.key(item.key) {
                ToFixRow(
                    item = item, sourceProvider = providerOf(item.source, ctx = ctx), targetName = nameOf(item.targetService), showSyncs = byService,
                    onAccept = { item.suggestion?.let { fix(item, it) } }, onLater = { later(item) }, onSearch = { searching = item.key },
                    onIgnore = { if (item.kind == ToFix.Kind.UNCERTAIN) removing = item.key else ignore(item) },
                )
            }
        }
    }
    if (items.size > shown) TextButton(onClick = { shown += TO_FIX_PAGE }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.saved_matches_more) + " · " + stringResource(R.string.preview_more, items.size - shown))
    }

    // Search by hand, over the list, on the service the track is missing on.
    items.firstOrNull { it.key == searching }?.let { item ->
        val job = data.jobs.firstOrNull { it.id == item.places.first().jobId }
        val dst = job?.let { Providers.byId(it.target.provider) }
        val dstName = dst?.displayName ?: nameOf(item.targetService)
        val ai = remember { AiClient.config(ctx)?.takeIf { it.complete } }
        val language = remember { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH) }
        ModalBottomSheet(onDismissRequest = { searching = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.to_fix_search_on, dstName), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    listOfNotNull(item.track.title, item.track.artistLine.ifBlank { null }, stringResource(R.string.to_fix_from, item.places.joinToString(" · ") { it.jobName })).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (job == null || dst == null) Text(stringResource(R.string.error_generic), style = MaterialTheme.typography.bodyMedium)
                else MatchSearch(
                    initialQuery = if (item.track.artists.isEmpty()) item.track.title else "${item.track.artistLine} - ${item.track.title}",
                    targetName = dstName, pickLabel = stringResource(R.string.review_use_this),
                    search = { q -> engine.searchOnTarget(job, q, item.track) },
                    onPick = { t -> searching = null; fix(item, t) },
                    onError = { m -> say(m.ifBlank { ctx.getString(R.string.error_generic) }) }, autoSearch = true,
                    webSearchUrl = remember(dst) { dst.webSearch(ctx) }, openTrack = { t -> dst.trackUrl(ctx, t.id) },
                    askAi = ai?.let { cfg -> { cands -> AiClient.suggestMatch(cfg, item.track, cands, dstName, language) } },
                    extraActions = { busy ->
                        if (item.kind == ToFix.Kind.NOT_FOUND) TextButton(enabled = !busy, onClick = { searching = null; ignore(item) }) { Text(stringResource(R.string.to_fix_ignore)) }
                        TextButton(onClick = { searching = null }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
        }
    }

    // A wrong match with no other wanted: it leaves the playlists, so it is asked first (as in a sync's own review).
    items.firstOrNull { it.key == removing }?.let { item ->
        val targets = item.places.mapNotNull { p -> data.jobs.firstOrNull { it.id == p.jobId }?.let { Providers.byId(it.target.provider) } }
        val dstName = targets.firstOrNull()?.displayName ?: nameOf(item.targetService)
        val removes = targets.any { it.canRemoveTracks }
        FitDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.review_discard_title, dstName)) },
            text = { Text(stringResource(if (removes) R.string.review_discard_body else R.string.review_discard_body_kept, dstName)) },
            confirmButton = { TextButton(onClick = { removing = null; ignore(item) }) { Text(stringResource(R.string.review_discard)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Rows drawn at a time, as in the Saved tab. */
private const val TO_FIX_PAGE = 60

/**
 * One track: what it is, where it comes from, the song proposed for it (or that none was found),
 * and the three things to do. Swiping the card right accepts (opens the search where nothing is
 * proposed), left sets it aside: the same as the buttons, which are always there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToFixRow(
    item: ToFix.Item, sourceProvider: MusicProvider?, targetName: String, showSyncs: Boolean,
    onAccept: () -> Unit, onLater: () -> Unit, onSearch: () -> Unit, onIgnore: () -> Unit,
) {
    val suggestion = item.suggestion
    val right by rememberUpdatedState(if (suggestion != null) onAccept else onSearch)
    val left by rememberUpdatedState(onLater)
    // The card always comes back: an accepted or set-aside row leaves the list by itself, and one that
    // opened the search stays under it.
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { to ->
        when (to) {
            SwipeToDismissBoxValue.StartToEnd -> right()
            SwipeToDismissBoxValue.EndToStart -> left()
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false
    })
    SwipeToDismissBox(
        state = swipe, modifier = Modifier.fillMaxWidth().clip(ToolCardShape).testTag(TO_FIX_ROW_TAG + item.key),
        // What the swipe does, in an icon and a word under the card: not colour alone.
        backgroundContent = {
            val accepting = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val later = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(if (accepting) toneBackground(Tone.Ok) else MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 22.dp).clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, if (later) Alignment.End else Alignment.Start),
            ) {
                val fg = if (accepting) toneContent(Tone.Ok) else MaterialTheme.colorScheme.onSurface
                if (accepting) {
                    Icon(if (suggestion != null) Icons.Filled.Check else Icons.Filled.Search, null, tint = fg)
                    Text(stringResource(if (suggestion != null) R.string.to_fix_accept else R.string.to_fix_search_by_hand), color = fg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                } else if (later) {
                    Text(stringResource(R.string.to_fix_later), color = fg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Icon(Icons.Filled.Schedule, null, tint = fg)
                }
            }
        },
    ) {
        PageCard {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackTile()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ServiceLogo(sourceProvider, 14.dp, Modifier.clearAndSetSemantics {})
                            Spacer(Modifier.width(6.dp))
                            Text(item.track.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (item.kind == ToFix.Kind.UNCERTAIN) R.string.to_fix_remove_match else R.string.to_fix_ignore)) },
                                onClick = { menu = false; onIgnore() },
                            )
                        }
                    }
                }
                Column(Modifier.padding(end = 8.dp)) {
                    // In several syncs, and which ones where the list is not grouped by them.
                    if (item.places.size > 1 || showSyncs) FlowRow(
                        Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.places.size > 1) Row(
                            Modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.Layers, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(pluralStringResource(R.plurals.to_fix_in_syncs, item.places.size, item.places.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (showSyncs || item.places.size > 1) Text(
                            stringResource(R.string.to_fix_from, item.places.joinToString(" · ") { it.jobName }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (suggestion != null) {
                        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.ArrowDownward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.to_fix_suggested_on, targetName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrackTile()
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(suggestion.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(suggestion.artistLine.ifBlank { null }, suggestion.album.ifBlank { null }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusPill(stringResource(R.string.review_score, (item.score * 100).toInt()), Tone.Warning)
                        }
                    } else {
                        Row(Modifier.padding(top = 6.dp, bottom = 6.dp)) { StatusPill(stringResource(R.string.to_fix_not_found_on, targetName), Tone.Neutral, Icons.Filled.SearchOff) }
                        if (item.absent) Text(stringResource(R.string.review_absent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        if (suggestion != null) {
                            TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.to_fix_search_by_hand)) }
                            TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.to_fix_later)) }
                            FilledTonalButton(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.to_fix_accept))
                            }
                        } else {
                            TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.to_fix_later)) }
                            FilledTonalButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.to_fix_search_by_hand))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A track's mark where there is no cover to show. */
@Composable
private fun TrackTile() {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MusicNote, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How many tracks wait, beside the tab's name; nothing when none does. */
@Composable
internal fun ToFixCount(n: Int) {
    if (n <= 0) return
    Box(
        Modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(java.text.NumberFormat.getIntegerInstance().format(n), style = MaterialTheme.typography.labelMedium.merge(TabularNumbers), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
    }
}

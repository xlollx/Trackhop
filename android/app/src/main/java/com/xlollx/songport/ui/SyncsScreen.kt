package com.xlollx.songport.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Freshness
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.Notifications
import com.xlollx.songport.sync.ShazamLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Delete
import com.xlollx.songport.R
import com.xlollx.songport.data.StoreData
import androidx.compose.material.icons.filled.Favorite
import com.xlollx.songport.sync.SupportPrompt
import com.xlollx.songport.data.Store
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncState
import java.util.UUID
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.xlollx.songport.sync.SyncFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** From this many syncs on, the ideas' small card is no longer shown: the person has found their way. */
private const val IDEAS_STRIP_MAX_SYNCS = 4

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SyncsScreen(
    data: StoreData,
    onEdit: (SyncJob) -> Unit,
    onPreview: (SyncJob) -> Unit,
    onRun: (SyncJob) -> Unit,
    onRunAll: () -> Unit,
    onDelete: (SyncJob) -> Unit,
    onGoToAccounts: () -> Unit,
    onReview: (String) -> Unit = {},
    onOpenTarget: (SyncJob) -> Unit = {},
    onStats: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onTour: (() -> Unit)? = null,
    onToggleEnabled: (SyncJob) -> Unit = {},
    /** An idea for one service tapped (ServiceIdeas): its page, filled in. */
    onIdea: (Idea) -> Unit = {},
    /** The "Did you know?" pill tapped: the widgets, shortcuts and automation page. */
    onTips: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val running by SyncState.running.collectAsState()
    // Pause asked for, pause reached (the engine waiting), stop asked for: what each running card shows.
    val pausedSet by SyncState.paused.collectAsState()
    val parkedSet by SyncState.parked.collectAsState()
    val stoppingSet by SyncState.stopping.collectAsState()
    // Find and filter: kept across rotation; only in use from FIND_MIN_SYNCS syncs on.
    var filter by rememberSaveable(stateSaver = SyncFilterSaver) { mutableStateOf(SyncFilter()) }
    if (data.syncs.isEmpty()) {
        val anyConnected = remember { Providers.connectors().any { it.requiresAuth && it.isConnected(ctx) } }
        // With a service connected: what one service alone can do, a tap away, under the usual words.
        val primary = remember { ServiceIdeas.connected(ctx).firstOrNull() }
        if (anyConnected && primary != null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 100.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Sync, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.empty_syncs_title), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(
                    stringResource(R.string.empty_syncs_body_connected), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                ServiceIdeasCard(primary, onIdea = onIdea)
                if (onTour != null) TextButton(onClick = onTour, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.tour_offer)) }
            }
        } else if (anyConnected) {
            EmptyState(
                Icons.Filled.Sync, stringResource(R.string.empty_syncs_title), stringResource(R.string.empty_syncs_body_connected),
                secondaryLabel = stringResource(R.string.tour_offer), onSecondary = onTour,
            )
        } else {
            EmptyState(
                Icons.Filled.Sync, stringResource(R.string.empty_syncs_title), stringResource(R.string.empty_syncs_body),
                stringResource(R.string.empty_syncs_cta_accounts), onGoToAccounts,
                secondaryLabel = stringResource(R.string.tour_offer), onSecondary = onTour,
            )
        }
        return
    }
    val lastRun = data.syncs.maxOfOrNull { it.lastRunEpoch } ?: 0L
    // One column on a phone, as ever; two or three side by side in a wider window, and always two on
    // a phone on its side. A grid either way, so the order, the dragging and the scrolling are the same code.
    // The minimal view: tiles in as many columns as fit (two on a phone). The cards are as big as
    // Settings says, but no bigger than the window allows (a phone on its side: two cards fit).
    val window = LocalWindowLayout.current
    val minimal = data.settings.minimalSyncs
    val cardSize = CardSize.shown(data.settings.cardSize, window)
    // How many columns the grid has now: set where its width is known, read by the drag below.
    val gridColumns = remember { androidx.compose.runtime.mutableIntStateOf(window.syncColumns) }
    val wholeRow: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Moving a card (its menu's Move up/down): the list keeps its scroll position, so only the two
    // cards trade places. Left alone, the list would follow the card at its top by key, and a card
    // moved above it would make the whole list jump.
    val moveAnchor = remember { arrayOfNulls<Pair<Int, Int>>(1) }
    // The order as shown: starred syncs first (unless turned off in Settings › Appearance). Positions
    // (a drag, "Move to") are places in this order.
    val onTop = data.settings.favoritesOnTop
    val order = remember(data.syncs, onTop) { com.xlollx.songport.sync.Labels.favoritesFirst(data.syncs, onTop).map { it.id } }
    val lastOrder = remember { arrayOf(order) }
    SideEffect {
        if (order != lastOrder[0]) {
            moveAnchor[0]?.let { (i, o) -> listState.requestScrollToItem(i, o) }
            moveAnchor[0] = null
            lastOrder[0] = order
        }
    }
    val onMove: (SyncJob, Int, Boolean) -> Unit = { job, to, follow ->
        val from = order.indexOf(job.id)
        val here = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == job.id }?.index
        // A card moved to a position by number, to the top of the list, or up from the top of the
        // screen is followed there; otherwise the screen stays where it is.
        val followIt = here != null && from >= 0 && (follow || (from > to && (to == 0 || here == listState.firstVisibleItemIndex)))
        moveAnchor[0] = if (followIt) (here!! + (to - from)).coerceAtLeast(0) to 0
        else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        Store.get(ctx).moveJob(job.id, com.xlollx.songport.sync.Labels.storeIndex(data.jobs, job.id, to, onTop))
    }
    // Hold a card and drag it: the order while dragging lives here, and goes to the store when the
    // card is let go (kept until the store's list shows it, so the cards never jump back meanwhile).
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    // The stretch the card held down stays in (starred or not, Labels.dragStretch): the cards of the other
    // kind do not make way for it. Let them, and a starred tile dragged to the row below showed there, then
    // jumped back to the last starred place on release, the tiles it had passed with it (10/11 report).
    var dragStretch by remember { mutableStateOf<Boolean?>(null) }
    // Whether the card held down has changed places yet, and whether the finger holding it has moved
    // (past the touch slop, on screen): a tile let go with neither opens its menu. Swaps alone were
    // not enough: a tile dragged where it cannot go (an unstarred one over the starred) never swaps,
    // and its menu opened on release, where the finger had left the tile (11/11 report).
    val swapped = remember { booleanArrayOf(false) }
    val fingerMoved = remember { booleanArrayOf(false) }
    // The tile held down in the gesture under way: its release is the end of a hold, not a tap.
    val heldTile = remember { arrayOfNulls<String>(1) }
    // The tile whose menu is open (held down), and the one opened as a whole card (tapped).
    var tileMenu by remember { mutableStateOf<String?>(null) }
    var tileOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The store's order as it is now: the drag callbacks below can outlive the composition they were
    // made in, and an order captured then is stale once a card has moved since (a drop back to that
    // old order was taken for "no change" and the card snapped back).
    val orderNow = { Store.get(ctx).data.let { d -> com.xlollx.songport.sync.Labels.favoritesFirst(d.syncs, d.settings.favoritesOnTop).map { it.id } } }
    val reorder = rememberReorderableLazyGridState(listState) { from, to ->
        val o = (dragOrder ?: orderNow()).toMutableList()
        val a = o.indexOf(from.key as? String); val b = o.indexOf(to.key as? String)
        if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("Reorder", "move ${from.key}@${from.index} -> ${to.key}@${to.index} ($a -> $b)")
        if (a >= 0 && b >= 0) {
            // A lazy list keeps its first visible item in view by key: when that item is one of the two
            // swapping (the card half under the pill at the end of the list), the list would follow it,
            // the card under the finger would jump and the drop could land back where it started.
            // Held by position instead, the list stays still and only the cards change places.
            val first = listState.firstVisibleItemIndex
            val firstRow = first until first + gridColumns.intValue
            if (from.index in firstRow || to.index in firstRow) listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
            o.add(b, o.removeAt(a)); dragOrder = o
            swapped[0] = true
        }
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
    }
    // Any change of order while nothing is dragged (a move from the menu, the store's own list after a
    // drop) ends the drag's own order: the list is the store's again.
    LaunchedEffect(order) { if (!reorder.isAnyItemDragging) dragOrder = null }
    // A sync tapped in a widget (JobFocus): scrolled to and lit up for a moment.
    val focusAsked by JobFocus.request.collectAsState()
    var flashing by remember { mutableStateOf<String?>(null) }
    // "Up to date" is checked, not assumed: each sync's source against its last run, when the list
    // shows and again after a run (FreshnessChecks).
    val toCheck = FreshnessChecks.worthChecking(data.syncs)
    LifecycleResumeEffect(toCheck.map { Triple(it.id, it.lastRunEpoch, it.sourceVersion) }) {
        FreshnessChecks.keepOnly(data.syncs.mapTo(HashSet()) { it.id })
        val check = scope.launch { FreshnessChecks.refresh(ctx.env, toCheck) }
        onPauseOrDispose { check.cancel() }
    }
    val support = SupportPrompt.pending(Store.get(ctx))
    // One open in ten: a small card that goes away by itself at the next open.
    val nudge = support == null && data.settings.appOpens > 0 && SupportPrompt.nudge(Store.get(ctx))
    // Also with fewer syncs while the crowded-schedule card's "Show them" is on: its bar turns it off.
    val finder = data.syncs.size >= FIND_MIN_SYNCS || filter.ids.isNotEmpty()
    // A colour or a service no sync uses any more has no chip to turn it off: it is dropped.
    val effective = if (finder) filter.pruned(data.syncs) else SyncFilter()
    val runningIds = running.keys
    val shown = remember(data.syncs, data.reports, runningIds, effective, onTop) {
        // Starred syncs first, then the others, each in the list's own order.
        com.xlollx.songport.sync.Labels.favoritesFirst(effective.select(data.syncs, data.reports, runningIds, data.settings.labels) { id -> Providers.byId(id)?.let { it.label(ctx) + " " + it.displayName } ?: id }, onTop)
    }
    // Pull to refresh = sync all. The indicator stays only while the gesture and the start are under
    // way: the cards show the progress. While syncs run, the pull is off (the pill says how many).
    val pullHaptic = rememberConfirmHaptic()
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(starting) {
        if (!starting) return@LaunchedEffect
        delay(400)
        withTimeoutOrNull(1500) { SyncState.running.first { it.isNotEmpty() } }
        starting = false
    }
    val pullOff = running.isNotEmpty() && !starting
    // The search field: closed until the header's magnifier is tapped, open (with what was typed, kept in
    // the filter) across a turn of the phone. Words typed are never hidden: the field shows while there are any.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchShown = finder && (searchOpen || effective.query.isNotEmpty())
    // The cursor goes in the field at a tap on the magnifier, not when the page is built again.
    var searchFocus by remember { mutableStateOf(false) }
    // Cards or tiles: its button is at the end of the row of the pill.
    val viewToggle = data.syncs.size >= 2 || minimal
    // The row under the header: the pill (two syncs or more), the filter chips, the cards-or-tiles button.
    val runAllRow = data.syncs.isNotEmpty() || finder || viewToggle
    val filterLine = finder && effective.isActive
    // The pill's place in the list: right after the hero (the search field is part of the hero's item).
    val runAllIndex = 1
    // Index of the first card: hero, the pill's row, the filter's line, then the support card and the
    // crowded-schedule card when there are any. With the filter cleared, its line is not there.
    val firstCardUnfiltered = (if (runAllRow) 2 else 1) + (if (support != null || nudge) 1 else 0)
    var pillHeight by remember { mutableIntStateOf(0) }
    // Pinned once the list has scrolled past its place: the cards are then passing beneath it.
    val pillPinned by remember(runAllIndex) {
        derivedStateOf { listState.firstVisibleItemIndex > runAllIndex || (listState.firstVisibleItemIndex == runAllIndex && listState.firstVisibleItemScrollOffset > 0) }
    }
    val pullGuard = remember { PullGuard() }
    SideEffect { pullGuard.blocked = pullOff }
    PullToRefreshBox(
        isRefreshing = starting,
        onRefresh = { if (!pullOff) { pullHaptic(); starting = true; onRunAll() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LaunchedEffect(focusAsked, shown) {
            val id = focusAsked ?: return@LaunchedEffect
            if (data.syncs.none { it.id == id }) { JobFocus.request.value = null; return@LaunchedEffect }
            // Hidden by the search or a filter: the whole list again, then the card.
            if (shown.none { it.id == id }) { filter = SyncFilter(); return@LaunchedEffect }
            kotlinx.coroutines.delay(150)
            // The syncs are the list's last items: the ones before them (summary, notes) come first.
            val index = listState.layoutInfo.totalItemsCount - shown.size + shown.indexOfFirst { it.id == id }
            runCatching { listState.animateScrollToItem(index.coerceAtLeast(0)) }
            JobFocus.request.value = null
            flashing = id
        }
        val crowdPrefs = remember { ctx.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE) }
        var crowdSeen by remember { mutableStateOf(crowdPrefs.getString("seen", null)) }
        val crowd = remember(data.syncs, crowdSeen) { com.xlollx.songport.sync.Timing.crowded(data.syncs, sensitive = ::spreads)?.takeIf { crowdKey(it) != crowdSeen } }
        var spreading by remember { mutableStateOf<com.xlollx.songport.sync.Timing.Crowd?>(null) }
        spreading?.let { c -> SpreadDialog(data.syncs, c) { spreading = null } }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Measured here, the rail, the cutout and the system's bars already left out.
        val columns = if (minimal) WindowLayout.tileColumns(maxWidth.value.toInt())
            else if (window.rail) WindowLayout.cardColumns(maxWidth.value.toInt(), window.short) else 1
        SideEffect { gridColumns.intValue = columns }
        // A small card as wide as a phone on its side takes two lines: its figures go beside its name.
        val wideCards = (maxWidth - 32.dp - 12.dp * (columns - 1)) / columns >= 560.dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(pullGuard),
            // The last card clears the bar and the floating button, and the keyboard while a search is typed.
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = maxOf(100.dp + LocalBottomBarInset.current, WindowInsets.ime.asPaddingValues().calculateBottomPadding() + 16.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero", span = wholeRow) {
                val n = data.syncs.size
                val last = if (lastRun > 0) " · " + stringResource(R.string.hero_last, formatDate(lastRun)) else ""
                // "Your numbers" once a sync has completed: before that there is nothing to count.
                val anyRun = data.reports.any { it.ok && !it.partial }
                // A few syncs and one service: the ideas for it, small, under the header (in the same
                // item, so the list's positions stay as they are), until closed.
                val single = remember(n) { ServiceIdeas.connected(ctx).singleOrNull() }
                var stripHidden by remember { mutableStateOf(ServiceIdeas.stripDismissed(ctx)) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroHeader(
                        stringResource(R.string.hero_title), ctx.resources.getQuantityString(R.plurals.hero_jobs, n, n) + last,
                        link = if (anyRun) stringResource(R.string.stats_hero_link) else null, onLink = onStats,
                        // The search, at a tap on its magnifier: the field opens under the header, and closes
                        // (its words dropped, so nothing stays hidden by them) at a second tap.
                        action = if (finder) ({
                            IconButton(
                                onClick = {
                                    if (searchShown) { searchOpen = false; filter = filter.copy(query = "") }
                                    else { searchFocus = true; searchOpen = true }
                                },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = if (searchShown) Color.White.copy(alpha = 0.22f) else Color.Transparent),
                            ) {
                                Icon(Icons.Filled.Search, stringResource(if (searchShown) R.string.syncs_find_close else R.string.syncs_find_hint), tint = Color.White)
                            }
                        }) else null,
                    )
                    // In the same item as the header, so the list's positions stay as they are.
                    if (searchShown) SyncFinderField(effective, focus = searchFocus, onFocused = { searchFocus = false }) { filter = it }
                    if (single != null && n < IDEAS_STRIP_MAX_SYNCS && !stripHidden) {
                        ServiceIdeasStrip(single, onDismiss = { ServiceIdeas.dismissStrip(ctx); stripHidden = true }, onIdea = onIdea)
                    }
                    // After the first completed sync: what Trackhop does outside the app. In the same item too.
                    if (Tips.pillShown(data)) {
                        TipsPill(onOpen = onTips, onDismiss = { Store.get(ctx).updateSettings { it.copy(tipsPillDismissed = true) } })
                    }
                }
            }
            // One row under the header: "Sync all", a pill that stays at the top while the list scrolls
            // (while syncs run it says how many, and a tap brings the first running card into view), then
            // the filter chips and, at the row's end, cards or tiles. Pinned, only the pill is left.
            if (runAllRow) stickyHeader(key = "runall") {
                val firstCardNoFilter = firstCardUnfiltered + (if (crowd != null) 1 else 0)
                val firstCard = firstCardNoFilter + (if (filterLine) 1 else 0)
                // A card brought into view lands just under the pinned pill, not beneath it.
                val tally = SyncState.tally(running.keys, pausedSet, parkedSet, stoppingSet)
                val counts = remember(data.syncs, data.reports, runningIds) { SyncFilter.counts(data.syncs, data.reports, runningIds) }
                RunAllRow(
                    tally.takeIf { data.syncs.size >= 2 }, pinned = pillPinned, modifier = Modifier.onSizeChanged { pillHeight = it.height },
                    chips = if (finder) ({ SyncFinderChips(data.syncs, effective, counts, data.settings.labels) { filter = it } }) else null,
                    // Cards or tiles, at a tap; remembered (also in Settings).
                    trailing = if (viewToggle) ({
                        IconButton(onClick = { Store.get(ctx).updateSettings { it.copy(minimalSyncs = !minimal) } }) {
                            Icon(
                                if (minimal) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                stringResource(if (minimal) R.string.syncs_view_cards else R.string.syncs_view_tiles), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }) else null,
                ) {
                    // Every running sync paused: the pill is the way to resume them all.
                    if (tally.allPaused) {
                        running.keys.forEach { SyncState.resume(it) }
                        return@RunAllRow
                    }
                    val firstShown = shown.indexOfFirst { running.containsKey(it.id) }
                    val firstAll = data.syncs.indexOfFirst { running.containsKey(it.id) }
                    when {
                        firstShown >= 0 -> scope.launch { listState.animateScrollToItem(firstCard + firstShown, -pillHeight) }
                        // Running, but hidden by the filter: show every card again, then go to it.
                        firstAll >= 0 -> {
                            filter = SyncFilter()
                            scope.launch {
                                withTimeoutOrNull(1000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > firstCardNoFilter + firstAll } }
                                listState.animateScrollToItem(firstCardNoFilter + firstAll, -pillHeight)
                            }
                        }
                        else -> onRunAll()
                    }
                }
            }
            // With a filter on: how many cards are shown, and a way to clear it all. Under the row, with the list.
            if (filterLine) item(key = "filterline", span = wholeRow) { SyncFinderSummary(data.syncs.size, shown.size) { filter = SyncFilter() } }
            // Una volta sola, dopo la prima sync grande: il momento in cui si vede quanto tempo ha fatto risparmiare.
            if (support != null) item(key = "support", span = wholeRow) { SupportCard(support) }
            else if (nudge) item(key = "nudge", span = wholeRow) { CoffeeCard() }
            // Many syncs reaching one service at once (issue #56): said once, until the group changes.
            crowd?.let { c ->
                item(key = "crowd", span = wholeRow) {
                    CrowdCard(
                        c,
                        onDismiss = { crowdPrefs.edit().putString("seen", crowdKey(c)).apply(); crowdSeen = crowdKey(c) },
                        onShow = { filter = SyncFilter(ids = c.jobIds.toSet()) },
                        // Nothing to move (syncs every few hours only): no button that opens an empty list.
                        onSpread = if (com.xlollx.songport.sync.Timing.spread(data.syncs, c, sensitive = ::spreads).isEmpty()) null else ({ spreading = c }),
                    )
                }
            }
            if (shown.isEmpty()) item(key = "nomatch", span = wholeRow) { SyncFinderEmpty { filter = SyncFilter() } }
            // Dragging works on the whole list only: with a filter, the cards between are hidden.
            val canDrag = !effective.isActive && shown.size >= 2
            val listed = dragOrder?.let { o -> shown.sortedBy { o.indexOf(it.id) } } ?: shown
            items(listed, key = { it.id }) { job -> ReorderableItem(reorder, key = job.id, enabled = canDrag && (dragStretch ?: job.favorite) == job.favorite) { isDragging ->
                val flash = remember(job.id) { androidx.compose.animation.core.Animatable(0f) }
                // Where this tile is laid out, to tell where a finger on it is on screen.
                val tileCoords = remember(job.id) { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
                LaunchedEffect(flashing) {
                    if (flashing == job.id) {
                        repeat(2) {
                            flash.animateTo(1f, androidx.compose.animation.core.tween(220))
                            flash.animateTo(0f, androidx.compose.animation.core.tween(520))
                        }
                        flashing = null
                    }
                }
                val lift by androidx.compose.animation.core.animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "lift")
                Box(Modifier.then(
                    if (flash.value > 0f) Modifier.border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = flash.value), MaterialTheme.shapes.large) else Modifier,
                ).graphicsLayer {
                    shadowElevation = lift.toPx(); shape = RoundedCornerShape(24.dp); clip = false
                    val k = 1f + 0.03f * (lift.value / 10f); scaleX = k; scaleY = k
                }.onGloballyPositioned { tileCoords[0] = it }.pointerInput(job.id, canDrag) {
                    // A card held while partly above the list's top (the one half under the pill at the
                    // end of the list) is brought into view while the finger is still, before the hold
                    // becomes a drag. Left to the drag library, the list is scrolled under the card as
                    // the drag starts and the card stays where the finger is, over the card above: the
                    // first move swapped the two, and the card dragged down came back where it was.
                    if (!canDrag) return@pointerInput
                    // Where the finger is on screen: the tile follows it while dragged, so its own
                    // coordinates hardly change.
                    fun onScreen(p: Offset) = tileCoords[0]?.takeIf { it.isAttached }?.localToRoot(p) ?: p
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val from = onScreen(down.position)
                        fingerMoved[0] = false
                        var released = false
                        // Watched in the Initial pass: known before the drag's own release handler runs.
                        val still = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis * 3 / 4) {
                            while (true) {
                                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed) { released = true; break }
                                if ((onScreen(c.position) - from).getDistance() > viewConfiguration.touchSlop) { fingerMoved[0] = true; break }
                            }
                        } == null
                        if (still) listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == job.id }?.offset?.y?.takeIf { it < 0 }?.let { listState.dispatchRawDelta(it.toFloat()) }
                        // Followed to the release, measured from where it went down.
                        while (!released) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (!fingerMoved[0] && (onScreen(c.position) - from).getDistance() > viewConfiguration.touchSlop) fingerMoved[0] = true
                        }
                    }
                }.longPressDraggableHandle(
                    enabled = canDrag,
                    onDragStarted = {
                        swapped[0] = false
                        heldTile[0] = job.id
                        dragStretch = Store.get(ctx).data.let { d -> com.xlollx.songport.sync.Labels.dragStretch(d.jobs, job.id, d.settings.favoritesOnTop) }
                        if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("Reorder", "start ${job.id} at $it")
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    },
                    onDragStopped = {
                        // A tile held and let go without moving it: its menu, as a long press elsewhere.
                        // Moved (a drag), never: the menu would show on release, where the finger left it.
                        if (minimal && !swapped[0] && !fingerMoved[0]) tileMenu = job.id
                        dragStretch = null
                        // Cleared once this release has been handled everywhere (the tile's own tap included).
                        scope.launch { heldTile[0] = null }
                        val dropped = dragOrder
                        val to = dropped?.indexOf(job.id) ?: -1
                        if (com.xlollx.songport.BuildConfig.DEBUG) android.util.Log.d("Reorder", "stop ${job.id}: to=$to dropped=$dropped now=${orderNow()}")
                        if (to >= 0 && dropped != orderNow()) {
                            Store.get(ctx).data.let { d -> Store.get(ctx).moveJob(job.id, com.xlollx.songport.sync.Labels.storeIndex(d.jobs, job.id, to, d.settings.favoritesOnTop)) }
                            // The store's list takes over as soon as it shows the move; at the latest, now.
                            scope.launch { kotlinx.coroutines.delay(400); if (!reorder.isAnyItemDragging) dragOrder = null }
                        } else dragOrder = null
                    },
                )) {
                    val run = if (running.containsKey(job.id)) SyncState.runOf(job.id, pausedSet, parkedSet, stoppingSet) else null
                    if (minimal) JobTile(
                        job, data, running[job.id], run, menuOpen = tileMenu == job.id, onMenu = { tileMenu = if (it) job.id else null },
                        canDrag = canDrag, onOpen = { if (heldTile[0] != job.id) tileOpen = job.id },
                        onEdit, onPreview, onRun, onDelete, onReview, onOpenLog, onToggleEnabled, onMove,
                    )
                    else JobCard(job, data, running[job.id], run, onEdit, onPreview, onRun, onDelete, onReview, onOpenTarget, onOpenLog, onToggleEnabled, onMove, size = cardSize, wide = wideCards)
                }
            } }
        }
        }
    }
    // A tile tapped: its whole card, as the list of cards shows it, in a sheet. Whatever leads to
    // another page closes the sheet first; a run, a pause or a style change stay on it.
    data.syncs.firstOrNull { it.id == tileOpen }?.takeIf { minimal }?.let { job ->
        val close = { tileOpen = null }
        ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                val run = if (running.containsKey(job.id)) SyncState.runOf(job.id, pausedSet, parkedSet, stoppingSet) else null
                JobCard(
                    job, data, running[job.id], run,
                    onEdit = { close(); onEdit(it) }, onPreview = { close(); onPreview(it) }, onRun = onRun,
                    onDelete = { close(); onDelete(it) }, onReview = { close(); onReview(it) }, onOpenTarget = { close(); onOpenTarget(it) },
                    onOpenLog = { close(); onOpenLog() }, onToggleEnabled = onToggleEnabled, onMove = onMove, size = cardSize,
                )
            }
        }
    }
}

/** What a sync card's pill says. */
internal enum class CardStatus { RUNNING, PAUSING, PAUSED, DISABLED, NEVER, ERROR, HELD_BACK, INCOMPLETE, NOT_FOUND, EMPTY_SOURCE, OK }

/**
 * The pill for a card, the most important first. Removals on hold come before "Not found: N": a few
 * songs to look up by hand matter less than a target the sync would empty, which waits for the user.
 * A running sync says where it stands ([run]): "Pausing" while its step finishes, "Paused" once the
 * engine waits; a stop in progress still reads as running, the card's text says it is stopping.
 */
internal fun cardStatus(isRunning: Boolean, enabled: Boolean, report: SyncReport?, run: SyncState.Run? = null, radio: Boolean = false): CardStatus = when {
    isRunning && run == SyncState.Run.PAUSED -> CardStatus.PAUSED
    isRunning && run == SyncState.Run.PAUSING -> CardStatus.PAUSING
    isRunning -> CardStatus.RUNNING
    !enabled -> CardStatus.DISABLED
    report == null -> CardStatus.NEVER
    !report.ok -> CardStatus.ERROR
    report.heldBack > 0 -> CardStatus.HELD_BACK
    report.searchInterrupted -> CardStatus.INCOMPLETE
    report.unmatchedTracks.isNotEmpty() -> CardStatus.NOT_FOUND
    // The source listed nothing at all (no loved tracks yet, an emptied playlist): said, not a silent "+0".
    // A radio sync with nothing new on air is normal.
    !radio && report.sourceEmpty -> CardStatus.EMPTY_SOURCE
    else -> CardStatus.OK
}

/**
 * Turns the pull off while syncs run: it takes what the list leaves of a downward drag at its top,
 * so the pull-to-refresh around it never sees it. Everything else passes through untouched.
 */
private class PullGuard : NestedScrollConnection {
    var blocked = false
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (blocked && source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, available.y) else Offset.Zero
}

/**
 * The row under the header: the "sync all" pill first (filled and inviting when idle, a quiet progress
 * state while syncs run; [tally] null with a single sync, which has no pill), then the filter [chips],
 * all in one line that scrolls sideways, and [trailing] (cards or tiles) at its end.
 *
 * The pill floats over the list: pinned, the cards scroll beneath it and show around it. A band behind it
 * (1.3.2–1.4.0, and again briefly in 1.17) was dropped both times on the owner's request. The pill
 * keeps its shadow, and the "?" beside it sits on a small raised circle of its own, so both read
 * over any card. The chips and the button have nothing of the kind: pinned, they fade away and come
 * back with the top of the list, where they are part of the page.
 */
/** The "?" beside Sync all: the pill, then what a sync card shows (moved here from the top bar, 12/11). */
private val CARD_HELP = arrayOf(Helps.syncAll, Helps.status, Helps.route, Helps.figures, Helps.toFix, Helps.runNow)

@Composable
private fun RunAllRow(
    tally: SyncState.Tally?, pinned: Boolean, modifier: Modifier = Modifier,
    chips: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val haptic = rememberConfirmHaptic()
    val helpShadow by animateDpAsState(if (pinned) 4.dp else 0.dp, label = "runall-help")
    Row(
        // As wide as the window, over the list's own side padding: the chips scroll from edge to edge,
        // and the pill's shadow is not cut at the scroller's side.
        modifier
            .layout { measurable, constraints ->
                val side = LIST_SIDE.roundToPx()
                val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * side, maxWidth = constraints.maxWidth + 2 * side))
                layout(constraints.maxWidth, p.height) { p.place(-side, 0) }
            }
            // Pinned, it floats over the cards: no band behind it (the owner's choice, again on 11/11).
            .then(if (pinned) Modifier.testTag(RUN_ALL_BAND_TAG) else Modifier)
            .padding(end = if (trailing != null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val scroll = rememberScrollState()
        // Pinned, the pill (with its "?") slides to the middle of the window; back in the list it leads the row.
        var boxW by remember { mutableIntStateOf(0) }
        var pillW by remember { mutableIntStateOf(0) }
        val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { LIST_SIDE.roundToPx() }
        // Read only where the row is placed (below), so the slide moves pixels without recomposing the row.
        val shift = androidx.compose.animation.core.animateIntAsState(
            if (pinned && boxW > 0 && pillW > 0) ((boxW - pillW) / 2 - sidePx).coerceAtLeast(0) else 0,
            androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "runall-center",
        )
        LaunchedEffect(pinned) { if (pinned) scroll.animateScrollTo(0) }
        // More chips past an edge: the row fades out there, so it reads as one that goes on. Only the
        // row's own pixels fade (no colour is drawn), and only while there is something to scroll to:
        // pinned, the pill is alone and nothing is touched.
        val fadeStart = scroll.canScrollBackward
        val fadeEnd = scroll.canScrollForward
        val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
        // The scroller is only as wide as what it holds: pinned, a touch beside the pill reaches the card beneath.
        Box(
            Modifier.weight(1f).onSizeChanged { boxW = it.width }.then(
                if (fadeStart || fadeEnd) Modifier
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val w = CHIPS_FADE.toPx()
                        val left = if (rtl) fadeEnd else fadeStart
                        val right = if (rtl) fadeStart else fadeEnd
                        if (left) drawRect(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, w),
                            size = androidx.compose.ui.geometry.Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                        if (right) drawRect(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - w, size.width),
                            topLeft = Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                else Modifier,
            ),
        ) {
            Row(
                // The room above and below is inside the scroller, where the pill's shadow is drawn.
                Modifier.offset { androidx.compose.ui.unit.IntOffset(if (rtl) -shift.value else shift.value, 0) }
                    .horizontalScroll(scroll).padding(start = LIST_SIDE, end = if (trailing != null) 8.dp else LIST_SIDE, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tally != null) Row(Modifier.onSizeChanged { pillW = it.width }, verticalAlignment = Alignment.CenterVertically) {
                    val runningCount = tally.total
                    val busy = runningCount > 0
                    androidx.compose.material3.Surface(
                        onClick = { if (!busy || tally.allPaused) haptic(); onClick() },
                        shape = RoundedCornerShape(50),
                        color = if (busy) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                        contentColor = if (busy) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 6.dp,
                    ) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            // All of them paused: nothing moves, so no spinner; a still pause sign, and a tap resumes them.
                            when {
                                tally.allPaused -> Icon(Icons.Filled.Pause, null, Modifier.size(20.dp))
                                busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                else -> Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    tally.allPaused -> pluralStringResource(R.plurals.syncs_paused, runningCount, runningCount)
                                    tally.somePaused -> stringResource(R.string.syncs_running_paused, tally.running, tally.paused)
                                    busy -> pluralStringResource(R.plurals.syncs_running, runningCount, runningCount)
                                    else -> stringResource(R.string.sync_all)
                                },
                                style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            )
                        }
                    }
                    // Its own small raised circle: over a card scrolling beneath, the "?" still reads.
                    androidx.compose.material3.Surface(
                        shape = CircleShape,
                        color = if (pinned) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        shadowElevation = helpShadow,
                    ) { HelpButton(*CARD_HELP, label = stringResource(R.string.help_cards_title)) }
                }
                // One sync, no pill: the "?" alone leads the row, so what a card shows is still explained.
                else HelpButton(*CARD_HELP, label = stringResource(R.string.help_cards_title))
                if (chips != null) androidx.compose.animation.AnimatedVisibility(
                    !pinned, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
                }
            }
        }
        if (trailing != null) androidx.compose.animation.AnimatedVisibility(
            !pinned, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) { Box(Modifier.padding(top = 4.dp, bottom = 12.dp)) { trailing() } }
    }
}

/**
 * A sync's menu, behind the card's "⋮" and a tile held down: [content] gets its actions, and the
 * dialogs they open (Move to, Style, Delete, the AI's "add tracks") live here beside it.
 */
@Composable
private fun JobMenuHost(
    job: SyncJob, data: StoreData,
    onEdit: (SyncJob) -> Unit, onPreview: (SyncJob) -> Unit, onDelete: (SyncJob) -> Unit,
    onToggleEnabled: (SyncJob) -> Unit, onMove: (SyncJob, Int, Boolean) -> Unit,
    content: @Composable (List<MenuAction>) -> Unit,
) {
    val ctx = LocalContext.current
    // Open across a turn of the phone, with what was being chosen (the dialogs keep theirs too).
    var colorOpen by rememberSaveable { mutableStateOf(false) }
    var moveToOpen by rememberSaveable { mutableStateOf(false) }
    if (moveToOpen) {
        val total = data.syncs.size
        val now = com.xlollx.songport.sync.Labels.favoritesFirst(data.syncs, data.settings.favoritesOnTop).indexOfFirst { it.id == job.id } + 1
        var typed by rememberSaveable { mutableStateOf(now.toString()) }
        val wanted = typed.toIntOrNull()?.takeIf { it in 1..total }
        val close = rememberDiscardGuard(typed != now.toString()) { moveToOpen = false }
        FitDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.sync_menu_move_to)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.move_to_now, now, total), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { v -> typed = v.filter { it.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.move_to_label, total)) },
                        singleLine = true,
                        isError = typed.isNotEmpty() && wanted == null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = wanted != null && wanted != now, onClick = {
                    moveToOpen = false
                    onMove(job, wanted!! - 1, true)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { moveToOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A two-way sync is one sync to the user: both halves take the colour, its style and the star.
    val halves = { d: com.xlollx.songport.data.StoreData -> d.jobs.filter { it.id == job.id || it.id == job.linkedJobId || it.linkedJobId == job.id }.mapTo(HashSet()) { it.id } }
    if (colorOpen) JobStyleDialog(
        job.name.ifBlank { job.source.playlistName }, job.label ?: job.color, job.accentStyle, data.settings.cardAccent, data.settings.labels, onDismiss = { colorOpen = false },
    ) { pick, st, labelName, recolor ->
        Store.get(ctx).update { d ->
            val ids = halves(d)
            com.xlollx.songport.sync.Labels.styled(d, ids, pick, labelName, recolor).let { n -> n.copy(jobs = n.jobs.map { if (it.id in ids) it.copy(accentStyle = st) else it }) }
        }
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var extendOpen by rememberSaveable { mutableStateOf(false) }
    val sharedGroup = com.xlollx.songport.shared.SharedGroups.sharedSide(job)
    if (extendOpen) AiExtendDialog(job) { extendOpen = false }
    // Handed over to the user's server (Coordinate with my server): it runs there; taken back here to change it.
    val onServer = data.handedOver[job.id] != null
    val serverOn = remember(data.handedOver) { com.xlollx.songport.sync.ServerLink.isOn(ctx) }
    var handOverOpen by rememberSaveable { mutableStateOf(false) }
    if (handOverOpen) HandOverDialog(job, back = onServer) { handOverOpen = false }
    content(
        listOfNotNull(
            if (job.aiPrompt != null) MenuAction(stringResource(R.string.ai_extend), { extendOpen = true }, icon = Icons.Outlined.AutoAwesome) else null,
            MenuAction(stringResource(R.string.preview), { onPreview(job) }, icon = Icons.Outlined.Visibility),
            // On the server: changed only once taken back. A shared playlist's syncs are made and changed from its group; anything else can become one.
            if (onServer) null
            else sharedGroup?.let { gid -> MenuAction(stringResource(R.string.shared_open_group), { SharedUi.request.value = SharedUi.Request.Open(gid) }, icon = Icons.Outlined.Groups) }
                ?: MenuAction(stringResource(R.string.edit), { onEdit(job) }, icon = Icons.Outlined.Edit),
            if (!onServer && sharedGroup == null && SharedUi.canShare(job)) MenuAction(stringResource(R.string.shared_share_from_sync), {
                SharedUi.request.value = SharedUi.Request.Create(job.target, job.target.playlistName.ifBlank { job.name })
            }, icon = Icons.Outlined.GroupAdd) else null,
            if (onServer) null
            else if (job.enabled) MenuAction(stringResource(R.string.sync_menu_disable), { onToggleEnabled(job) }, icon = Icons.Outlined.PauseCircle)
            else MenuAction(stringResource(R.string.sync_menu_enable), { onToggleEnabled(job) }, icon = Icons.Outlined.PlayCircle),
            if (onServer) MenuAction(stringResource(R.string.server_bring_back), { handOverOpen = true }, icon = Icons.Outlined.CloudDownload)
            else if (serverOn && sharedGroup == null) MenuAction(stringResource(R.string.server_handover), { handOverOpen = true }, icon = Icons.Outlined.Cloud)
            else null,
            MenuAction(
                stringResource(if (job.favorite) R.string.sync_menu_unfavorite else R.string.sync_menu_favorite),
                { Store.get(ctx).update { d -> halves(d).let { ids -> d.copy(jobs = d.jobs.map { if (it.id in ids) it.copy(favorite = !job.favorite) else it }) } } },
                icon = if (job.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            ),
            MenuAction(stringResource(R.string.job_style), { colorOpen = true }, icon = Icons.Outlined.Palette),
            // The order is the list's, Sync all's and the widgets': cards are dragged (held), or put
            // at a position typed as a number.
            // Many syncs: a position typed as a number, quicker than many taps on the arrows.
            if (data.syncs.size >= 2) MenuAction(stringResource(R.string.sync_menu_move_to), { moveToOpen = true }, icon = Icons.Outlined.Numbers) else null,
            if (sharedGroup == null) MenuAction(stringResource(R.string.delete), { confirmDelete = true }, destructive = true, icon = Icons.Outlined.Delete) else null,
        ),
    )
    if (confirmDelete) {
        FitDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(job) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.no)) } },
        )
    }
}

/**
 * What a sync's card (and its tile) says about it: [cardStatus] in a few words, with its tone and
 * icon. A run without errors can still leave something for the user: the status says what, and a
 * tap leads where it is handled.
 */
@Composable
private fun statusInfo(
    job: SyncJob, report: SyncReport?, run: SyncState.Run?,
    onPreview: (SyncJob) -> Unit, onReview: (String) -> Unit, onOpenLog: () -> Unit, onRun: (SyncJob) -> Unit,
): CardStatusInfo {
    val haptic = rememberConfirmHaptic()
    // Handed over: the server runs it, whatever this phone last did.
    if (run == null && Store.get(LocalContext.current).state.collectAsState().value.handedOver[job.id] != null)
        return CardStatusInfo(stringResource(R.string.server_on_server), Tone.Accent, Icons.Filled.Cloud)
    return when (cardStatus(run != null, job.enabled, report, run, radio = job.radio != null)) {
        CardStatus.RUNNING -> CardStatusInfo(stringResource(R.string.pill_running), Tone.Accent, Icons.Filled.Sync)
        CardStatus.PAUSING -> CardStatusInfo(stringResource(R.string.pill_pausing), Tone.Accent, Icons.Filled.Pause)
        CardStatus.PAUSED -> CardStatusInfo(stringResource(R.string.sync_paused), Tone.Warning, Icons.Filled.Pause)
        CardStatus.DISABLED -> CardStatusInfo(stringResource(R.string.disabled), Tone.Neutral, null)
        CardStatus.NEVER -> CardStatusInfo(stringResource(R.string.pill_never), Tone.Neutral, null)
        CardStatus.ERROR -> CardStatusInfo(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
        CardStatus.HELD_BACK -> CardStatusInfo(stringResource(R.string.pill_held_back), Tone.Warning, Icons.Filled.Warning) { onPreview(job) }
        CardStatus.INCOMPLETE -> CardStatusInfo(stringResource(R.string.pill_incomplete), Tone.Warning, Icons.Filled.Warning, onOpenLog)
        CardStatus.NOT_FOUND -> CardStatusInfo(stringResource(R.string.pill_not_found, report!!.unmatchedTracks.size), Tone.Warning, Icons.Filled.Warning) { onReview(report.id) }
        CardStatus.EMPTY_SOURCE -> CardStatusInfo(stringResource(R.string.pill_source_empty), Tone.Warning, Icons.Filled.Info)
        // Up to date only once checked: the source seen unchanged since the last run. Changed:
        // a tap runs it. Not checkable (no version from the service, offline) or not checked
        // yet: when it last ran, without the tick.
        CardStatus.OK -> when (FreshnessChecks.stateOf(job)) {
            Freshness.State.CURRENT -> CardStatusInfo(stringResource(R.string.pill_ok), Tone.Ok, Icons.Filled.CheckCircle)
            Freshness.State.CHANGED -> CardStatusInfo(stringResource(R.string.pill_changed), Tone.Accent, Icons.Filled.Sync) { haptic(); onRun(job) }
            else -> CardStatusInfo(
                // Under a minute "0 min. ago" reads oddly: "Just ran" instead.
                if (System.currentTimeMillis() - job.lastRunEpoch < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.pill_just_ran)
                else stringResource(R.string.pill_last_run, relativeTime(job.lastRunEpoch)),
                Tone.Neutral, Icons.Filled.Schedule,
            )
        }
    }
}

/**
 * A sync's card, in the [size] the list shows (Settings › Card size, capped on a phone on its side):
 * LARGE as it always was; MEDIUM tighter, with shorter figures; SMALL with its figures in one row
 * of small marks and its status, schedule and buttons on one line.
 */
@Composable
private fun JobCard(
    job: SyncJob, data: StoreData, progress: Progress?, run: SyncState.Run?,
    onEdit: (SyncJob) -> Unit, onPreview: (SyncJob) -> Unit, onRun: (SyncJob) -> Unit, onDelete: (SyncJob) -> Unit,
    onReview: (String) -> Unit = {}, onOpenTarget: (SyncJob) -> Unit = {}, onOpenLog: () -> Unit = {},
    onToggleEnabled: (SyncJob) -> Unit = {},
    onMove: (SyncJob, Int, Boolean) -> Unit = { _, _, _ -> },
    size: CardSize = CardSize.LARGE,
    /** A small card with room to spare (a phone on its side, one column): its figures beside its name, two lines in all. */
    wide: Boolean = false,
) = JobMenuHost(job, data, onEdit, onPreview, onDelete, onToggleEnabled, onMove) { menu ->
    val ctx = LocalContext.current
    val isRunning = run != null
    val large = size == CardSize.LARGE
    val small = size == CardSize.SMALL
    var listOpen by remember { mutableStateOf<StatList?>(null) }
    listOpen?.let { l -> StatListSheet(l) { listOpen = null } }
    var liveOpen by remember { mutableStateOf(false) }
    if (liveOpen && isRunning) LiveSheet(job) { liveOpen = false }
    val handed = data.handedOver[job.id]
    // Handed over: what the server did, not what this phone did before.
    val report = if (handed != null) null else data.reports.firstOrNull { it.id == job.lastReportId }
    val src = Providers.byId(job.source.provider)
    val dst = Providers.byId(job.target.provider)
    val srcName = playlistDisplayName(job.source.playlistId, job.source.playlistName, job.source.provider)
    val dstName = job.target.playlistName.ifBlank { stringResource(R.string.editor_new_playlist) }
    val haptic = rememberConfirmHaptic()
    // The last run's figures: each opens what it counts (the tracks added, removed, not found, the playlist).
    val statsShown = run == null && report != null && data.settings.newLook && report.ok
    val onAdded = { listOpen = StatList(StatList.Kind.ADDED, report!!) }
    val onRemoved = { listOpen = StatList(StatList.Kind.REMOVED, report!!) }
    val onNotFound = {
        if (report!!.unmatchedTracks.isNotEmpty() || report.reviewTracks.isNotEmpty()) onReview(report.id)
        else listOpen = StatList(StatList.Kind.NOT_FOUND, report)
    }
    val onTracks = if (job.target.playlistId != null) ({ onOpenTarget(job) }) else null
    val marksBeside = small && wide && statsShown
    Card(
        // While running, a tap opens the live list of tracks processed so far.
        Modifier.fillMaxWidth().testTag(JOB_CARD_TAG).animateContentSize().clickable(enabled = isRunning) { liveOpen = true },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = jobContainer(job.color)),
    ) {
        val accent = jobAccent(job.color)
        // A coloured sync wears its colour on its controls too, as a gradient.
        val controls = jobControlBrush(job.color)
        val ink = jobInk(job.color)
        val accentStyle = CardAccent.resolve(job.accentStyle, Store.get(ctx).state.collectAsState().value.settings.cardAccent)
        val side = if (large) 20.dp else 16.dp
        // On a low window (a phone on its side) a small card keeps the tighter spacing, so two still fit one above the other.
        val low = LocalWindowLayout.current.short
        val vertical = when (size) { CardSize.LARGE -> 20.dp; CardSize.MEDIUM -> 16.dp; CardSize.SMALL -> if (low) 12.dp else 14.dp }
        Column(
            Modifier.cardAccent(accentStyle, accent)
                .padding(start = accentStyle.startPadding(side, accent), end = side, top = vertical, bottom = vertical),
        ) {
            // Shown as a pill by the schedule, or as a badge on the logos.
            val statusOnLogos = Store.get(ctx).state.collectAsState().value.settings.cardStatusPlace == "badge"
            val status = statusInfo(job, report, run, onPreview, onReview, onOpenLog, onRun)
            val logo = when (size) { CardSize.LARGE -> 34.dp; CardSize.MEDIUM -> 30.dp; CardSize.SMALL -> 28.dp }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The badge hangs off the logos' corner: the room it takes is reserved, so that it
                // neither sticks out of the stack nor sits on the text beside it.
                val overhang = if (statusOnLogos) 6.dp else 0.dp
                Box {
                    Box(Modifier.padding(end = overhang, bottom = overhang).width(logo + 22.dp).height(logo + 2.dp)) {
                        ProviderBadge(src, logo)
                        Box(Modifier.align(Alignment.CenterEnd)) { ProviderBadge(dst, logo) }
                    }
                    if (statusOnLogos) StatusBadge(status, Modifier.align(Alignment.BottomEnd))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        JobMarks(job, ink)
                        Text(job.name.ifBlank { job.source.playlistName }, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.cardMarquee())
                    }
                    Text(
                        // A radio sync's source is its station, not the File playlist that keeps what it heard.
                        (job.radio?.let { stringResource(R.string.editor_source_radio) + " · " + it.station }
                            ?: ((sharedLabel(job.source.provider) ?: src?.label(ctx) ?: job.source.provider).let { if (job.source.link != null) stringResource(R.string.source_link_label, it) else it } + " · " + srcName)) +
                            (if (job.linkedJobId != null) "  ↔  " else "  →  ") +
                            (sharedLabel(job.target.provider) ?: dst?.label(ctx) ?: job.target.provider) + " · " + dstName,
                        style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, modifier = Modifier.cardMarquee(),
                    )
                    // Its label's name and its tags, on one line: what the search and the filters go by.
                    // A small card leaves them out: its colour still shows, and the search still finds them.
                    val groups = remember(job.label, job.tags, data.settings.labels) {
                        (listOf(com.xlollx.songport.sync.Labels.name(job, data.settings.labels)) + job.tags.map { tagText(it.key, it.value) }).filter { it.isNotEmpty() }.joinToString(" · ")
                    }
                    if (groups.isNotEmpty() && !small) Text(
                        groups, style = MaterialTheme.typography.bodySmall, color = ink ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (marksBeside) {
                    Spacer(Modifier.width(12.dp))
                    SmallStats(job.color, report!!, onAdded, onRemoved, onNotFound, onTracks, Modifier.width(240.dp))
                }
            }
            if (run != null) {
                // Pause and stop act between steps (see SyncState.checkpoint): while a step finishes
                // the card says the request is taken ("Pausing after this step…"), and only once the
                // engine waits does it read "Paused", with a bar that stands still.
                val paused = run == SyncState.Run.PAUSED || run == SyncState.Run.PAUSING
                val stopping = run == SyncState.Run.STOPPING
                Spacer(Modifier.height(if (large) 12.dp else 8.dp))
                val pct = progress?.percent
                if (run == SyncState.Run.PAUSED) LinearProgressIndicator(
                    // Frozen where the sync stopped, greyed: determinate, never the moving bar.
                    progress = { (pct ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().testTag(PAUSED_BAR_TAG),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                else if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (run) {
                            SyncState.Run.STOPPING -> stringResource(R.string.sync_stopping) + " · "
                            SyncState.Run.PAUSING -> stringResource(R.string.sync_pausing) + " · "
                            SyncState.Run.PAUSED -> stringResource(R.string.sync_paused) + " · "
                            SyncState.Run.RUNNING -> ""
                        } + progressText(progress) + (pct?.let { " · $it%" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    IconButton(enabled = !stopping, modifier = Modifier.size(48.dp), onClick = { if (paused) SyncState.resume(job.id) else SyncState.pause(job.id) }) {
                        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (paused) R.string.sync_resume else R.string.sync_pause))
                    }
                    IconButton(enabled = !stopping, modifier = Modifier.size(48.dp), onClick = { SyncState.stop(job.id) }) { Icon(Icons.Filled.Stop, stringResource(R.string.sync_stop)) }
                }
            } else if (marksBeside) {
                // Beside the name already.
            } else if (statsShown) {
                Spacer(Modifier.height(when (size) { CardSize.LARGE -> 14.dp; CardSize.MEDIUM -> 12.dp; CardSize.SMALL -> if (low) 8.dp else 10.dp }))
                if (small) SmallStats(job.color, report, onAdded, onRemoved, onNotFound, onTracks)
                else RunStats(
                    job.color, report, data.reports.filter { it.jobId == job.id && !it.partial }.sortedBy { it.startedEpoch }.takeLast(10),
                    onAdded = onAdded, onRemoved = onRemoved, onNotFound = onNotFound, onTracks = onTracks, compact = !large,
                )
            } else if (report != null) {
                Spacer(Modifier.height(if (small) 6.dp else 8.dp))
                Text(
                    Notifications.summary(ctx, report), style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = if (report.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = if (small) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                )
            }
            // Brani da sistemare a mano: raggiungibili appena la ricerca e' finita, anche mentre la
            // sync sta ancora aggiungendo, e dopo, finche' ne restano. A small card leads there from
            // its "not found" mark and its status instead.
            val mine = report?.takeIf { it.jobId == job.id }
            val toFix = mine?.let { it.unmatchedTracks.size + it.reviewTracks.size } ?: 0
            if (toFix > 0 && report != null && !small) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = { onReview(report.id) },
                    modifier = Modifier.fillMaxWidth().let { m -> controls?.let { m.clip(ButtonDefaults.shape).background(it) } ?: m },
                    colors = if (controls != null) ButtonDefaults.filledTonalButtonColors(containerColor = Color.Transparent, contentColor = ink ?: Color.Unspecified)
                    else ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(Icons.Filled.Build, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(reviewLabel(mine?.unmatchedTracks?.size ?: 0, mine?.reviewTracks?.size ?: 0))
                }
            }
            if (handed != null && run == null) ServerCardStatus(handed.serverId, small)
            // A radio sync while it listens: what is on air (or that it waits for Wi-Fi, or what went wrong).
            if (job.radio != null) RadioCardStatus(job)
            Spacer(Modifier.height(if (small) (if (low) 4.dp else 6.dp) else 8.dp))
            val schedule: @Composable (Modifier) -> Unit = { m ->
                Row(m, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    val last = if (job.lastRunEpoch > 0) formatDate(job.lastRunEpoch) else stringResource(R.string.last_run_never)
                    Text(
                        // A two-way pair never removes, whatever an older version saved. A radio sync shows
                        // its window (days and hours) where the others show their schedule.
                        // Turned off on arrival from another device: where its schedule runs, in place of the timing.
                        (handed?.let { stringResource(R.string.server_runs_there) }
                            ?: job.scheduledOn?.takeIf { !job.enabled }?.let { stringResource(R.string.job_scheduled_on, it) }
                            ?: job.radio?.let { radioWindowLabel(ctx, it) } ?: timingLabel(job)) +
                            (if (job.archive) " · " + stringResource(R.string.job_archive_badge) else if (job.mirrorRemovals && job.linkedJobId == null) " · " + stringResource(R.string.editor_mirror) else "") + " · " + last,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Glides like the name and the services line (the fade shows it is moving, not cut).
                        maxLines = 1,
                        modifier = Modifier.weight(1f).cardMarquee(),
                    )
                }
            }
            // Status in the footer: the schedule gets its own line, beside the chip it was cut short.
            // A small card keeps them on one line with the buttons, the schedule gliding if long.
            if (!statusOnLogos && !small) schedule(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (!statusOnLogos) {
                    StatusChip(status)
                    if (small) {
                        Spacer(Modifier.width(8.dp))
                        schedule(Modifier.weight(1f))
                    } else Spacer(Modifier.weight(1f))
                } else schedule(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                // Smaller on a medium or small card, where a 48 dp disc outweighed the line beside it.
                FilledTonalIconButton(
                    onClick = { haptic(); onRun(job) }, enabled = !isRunning,
                    modifier = Modifier.size(if (large) 48.dp else 40.dp).let { m -> controls?.let { m.clip(CircleShape).background(it, alpha = if (isRunning) 0.4f else 1f) } ?: m },
                    colors = if (controls != null) IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.Transparent, contentColor = ink ?: Color.Unspecified,
                        disabledContainerColor = Color.Transparent,
                    ) else IconButtonDefaults.filledTonalIconButtonColors(),
                ) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.run_now), Modifier.size(if (large) 24.dp else 22.dp))
                }
                // The menu wears the card's colour: a teal card opens a teal-tinted menu.
                OverflowMenu(
                    menu,
                    enabled = !isRunning,
                    container = jobMenuContainer(job.color),
                    iconTint = ink ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    title = job.name.ifBlank { job.source.playlistName },
                )
            }
        }
    }
}

/**
 * On the card of a sync handed over to the user's server: its last run there, as last read (read
 * once when missing), and the way to review what it left.
 */
@Composable
private fun ServerCardStatus(serverId: String, small: Boolean) {
    val ctx = LocalContext.current
    val known by com.xlollx.songport.sync.ServerLink.known.collectAsState()
    LaunchedEffect(serverId) { if (serverId !in known) com.xlollx.songport.sync.ServerLink.refreshSoon(ctx) }
    val s = known[serverId] ?: return
    var reviewing by remember { mutableStateOf<String?>(null) }
    reviewing?.let { ServerReviewSheet(it) { reviewing = null; com.xlollx.songport.sync.ServerLink.refreshSoon(ctx, force = true) } }
    val last = s.last ?: return
    Spacer(Modifier.height(if (small) 6.dp else 8.dp))
    Text(
        if (last.ok) stringResource(R.string.server_last_result, relativeTime(last.started.takeIf { it > 0 } ?: s.lastRun ?: 0), last.added, last.removed)
        else stringResource(R.string.server_last_failed, last.error.orEmpty()),
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = if (last.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    val toFix = last.notFound + last.toCheck
    if (toFix > 0) {
        Spacer(Modifier.height(6.dp))
        FilledTonalButton(onClick = { reviewing = last.id }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Build, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(reviewLabel(last.notFound, last.toCheck))
        }
    }
}

/** The review button: both counts when there are both, so it adds up with the card's "not found". */
@Composable
internal fun reviewLabel(notFound: Int, toCheck: Int): String =
    if (notFound > 0 && toCheck > 0) stringResource(R.string.review_summary, notFound, toCheck)
    else stringResource(R.string.review_now, notFound + toCheck)

/** The small marks before a sync's name: written by an AI, every Shazam, a radio, starred. */
@Composable
private fun JobMarks(job: SyncJob, ink: Color?, size: androidx.compose.ui.unit.Dp = 16.dp, star: Boolean = true) {
    // Written by the user's AI: a small spark before the name, and "add tracks" in the menu.
    if (job.aiPrompt != null) {
        Icon(Icons.Filled.AutoAwesome, stringResource(R.string.ai_badge_desc), Modifier.size(size), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(4.dp))
    }
    // Every song the user Shazams: Shazam's mark, in the same place.
    if (job.shazam) {
        Icon(ShazamGlyph, stringResource(R.string.shazam_badge_desc), Modifier.size(size), tint = shazamTint())
        Spacer(Modifier.width(4.dp))
    }
    // The songs a radio plays: a radio, tinted like the AI's spark.
    if (job.radio != null) {
        Icon(Icons.Filled.Radio, stringResource(R.string.radio_badge_desc), Modifier.size(size), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(4.dp))
    }
    // One half of a shared playlist: a group's mark.
    if (com.xlollx.songport.shared.SharedGroups.sharedSide(job) != null) {
        Icon(Icons.Filled.Groups, stringResource(R.string.shared_badge), Modifier.size(size), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(4.dp))
    }
    // Starred: first in the list, and under "Favourites" in the filters.
    if (star && job.favorite) {
        Icon(Icons.Filled.Star, stringResource(R.string.favorite_badge_desc), Modifier.size(size), tint = ink ?: MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * A sync in the minimal view: a small tile in a grid with its services, its name and its status,
 * wearing its colour as its card does, and a button to run it. A tap opens its whole card; held
 * down (and let go without moving) it opens the card's menu, as the "⋮" does.
 */
@Composable
private fun JobTile(
    job: SyncJob, data: StoreData, progress: Progress?, run: SyncState.Run?,
    menuOpen: Boolean, onMenu: (Boolean) -> Unit, canDrag: Boolean, onOpen: () -> Unit,
    onEdit: (SyncJob) -> Unit, onPreview: (SyncJob) -> Unit, onRun: (SyncJob) -> Unit, onDelete: (SyncJob) -> Unit,
    onReview: (String) -> Unit, onOpenLog: () -> Unit, onToggleEnabled: (SyncJob) -> Unit, onMove: (SyncJob, Int, Boolean) -> Unit,
) = JobMenuHost(job, data, onEdit, onPreview, onDelete, onToggleEnabled, onMove) { menu ->
    val ctx = LocalContext.current
    val isRunning = run != null
    val report = data.reports.firstOrNull { it.id == job.lastReportId }
    val status = statusInfo(job, report, run, onPreview, onReview, onOpenLog, onRun)
    val ink = jobInk(job.color)
    val controls = jobControlBrush(job.color)
    val haptic = rememberConfirmHaptic()
    val name = job.name.ifBlank { job.source.playlistName }
    val openLabel = stringResource(R.string.tile_open)
    val moreLabel = stringResource(R.string.more_actions)
    Box {
        Card(
            Modifier.fillMaxWidth().testTag(JOB_TILE_TAG)
                // Held down: with a whole list a hold is the start of a drag (the menu opens if the tile is let go
                // where it was); with a filter on nothing is dragged, and the hold opens the menu at once.
                .then(
                    if (canDrag) Modifier.clickable(onClickLabel = openLabel, onClick = onOpen)
                    else Modifier.combinedClickable(onClickLabel = openLabel, onClick = onOpen, onLongClickLabel = moreLabel, onLongClick = { haptic(); onMenu(true) }),
                )
                .semantics { customActions = listOf(CustomAccessibilityAction(moreLabel) { onMenu(true); true }) },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = jobContainer(job.color)),
        ) {
            Column(
                Modifier.cardAccent(CardAccent.resolve(job.accentStyle, data.settings.cardAccent), jobAccent(job.color), tile = true)
                    .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(46.dp).height(30.dp)) {
                        ProviderBadge(Providers.byId(job.source.provider), 28.dp)
                        Box(Modifier.align(Alignment.CenterEnd)) { ProviderBadge(Providers.byId(job.target.provider), 28.dp) }
                    }
                    Spacer(Modifier.weight(1f))
                    // Running: the tile says how far it is; the button would only be greyed.
                    FilledTonalIconButton(
                        onClick = { haptic(); onRun(job) }, enabled = !isRunning,
                        modifier = Modifier.size(48.dp).padding(4.dp).let { m -> controls?.let { m.clip(CircleShape).background(it, alpha = if (isRunning) 0.4f else 1f) } ?: m },
                        colors = if (controls != null) IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.Transparent, contentColor = ink ?: Color.Unspecified, disabledContainerColor = Color.Transparent,
                        ) else IconButtonDefaults.filledTonalIconButtonColors(),
                    ) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.run_now), Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) { JobMarks(job, ink, size = 14.dp, star = false) }
                    // Two lines kept for every name, so the tiles of a row line up.
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(toneBackground(status.tone)), contentAlignment = Alignment.Center) {
                        Icon(status.icon ?: Icons.Filled.Info, null, tint = toneContent(status.tone), modifier = Modifier.size(11.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    val pct = progress?.percent
                    Text(
                        status.text + (if (isRunning && pct != null) " · $pct%" else ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // When it runs next, in short: only for a sync that runs by itself.
                // Already due (late, about to start): nothing to say.
                val next = remember(job, isRunning) { if (isRunning) null else System.currentTimeMillis().let { now -> com.xlollx.songport.sync.Timing.nextRun(job, now)?.takeIf { it > now } } }
                // One line kept for every tile, so the tiles of a row are as tall as each other:
                // the progress while it runs, else when it runs next, else nothing.
                Spacer(Modifier.height(2.dp))
                // A starred sync shows its star in the tile's bottom corner.
                Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(
                        if (!isRunning && next != null && job.radio == null) stringResource(R.string.tile_next_run, nextRunShort(ctx, next)) else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (isRunning) {
                        val pct = progress?.percent
                        if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().padding(end = 6.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 6.dp))
                    }
                }
                if (job.favorite) Icon(Icons.Filled.Star, stringResource(R.string.favorite_badge_desc), Modifier.size(16.dp), tint = ink ?: MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        OverflowDropdown(menuOpen && !isRunning, { onMenu(false) }, menu, jobMenuContainer(job.color), ink ?: MaterialTheme.colorScheme.onSurfaceVariant, name)
        // While it runs the menu stays shut, as on the card: a hold shows the run live instead (it did nothing before).
        if (menuOpen && isRunning) LiveSheet(job) { onMenu(false) }
        // The run ending under the live view closes it, rather than turning it into the menu.
        LaunchedEffect(isRunning) { if (!isRunning && menuOpen) onMenu(false) }
    }
}

/** When a sync runs next, short: the time today, else the day and the time ("Sat 09:00"). */
private fun nextRunShort(ctx: android.content.Context, at: Long): String {
    val today = DateUtils.isToday(at)
    return DateUtils.formatDateTime(
        ctx, at,
        DateUtils.FORMAT_SHOW_TIME or (if (today) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY),
    )
}

/** The tiles of the minimal view (for the UI tests). */
const val JOB_TILE_TAG = "job-tile"

/** A sync's card (for the UI tests). */
const val JOB_CARD_TAG = "job-card"

/** The pinned Sync-all pill's background and where it ends (for the UI tests); the fade under it, inside a card's padding. */
const val RUN_ALL_BAND_TAG = "run-all-band"
val PILL_FADE = 10.dp

/** The sync list's padding at each side. */
private val LIST_SIDE = 16.dp

/** How far the row of filter chips fades out at an edge with more chips past it. */
private val CHIPS_FADE = 24.dp

/** "3 days ago", "5 min ago": how long since [epoch], in the user's language. */
internal fun relativeTime(epoch: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(epoch, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** The still, greyed bar of a paused card (for the UI tests). */
internal const val PAUSED_BAR_TAG = "paused-bar"

/** A status pill that leads somewhere: the warning icon, and a tap target as tall as a button. */
@Composable
private fun ActionPill(text: String, tone: Tone, icon: ImageVector = Icons.Filled.Warning, onClick: () -> Unit) {
    StatusPill(
        text, tone, icon,
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * The original sync of [job]'s two-way pair when [job] is the "↔" half the editor made, else null.
 * Pairs saved before the [SyncJob.twin] flag are told apart by the name the editor gave the twin.
 */
fun twinOriginal(job: SyncJob, jobs: List<SyncJob>): SyncJob? {
    val other = job.linkedJobId?.let { id -> jobs.firstOrNull { it.id == id } } ?: return null
    if (other.twin) return null
    return other.takeIf { job.twin || job.name == other.name + " ↔" }
}

/** Live view of a running sync: what was searched, found, taken from the cache or not found. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSheet(job: SyncJob, onDismiss: () -> Unit) {
    val all by SyncState.items.collectAsState()
    val running by SyncState.running.collectAsState()
    val items = all[job.id].orEmpty()
    val progress = running[job.id]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                HelpButton(Helps.cached)
            }
            Text(progressText(progress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Why the menu did not open: a hold on a running sync lands here (11/11 report).
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.live_menu_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) Text(stringResource(R.string.live_empty), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items.size) { i ->
                    val it = items[i]
                    Row(verticalAlignment = Alignment.Top) {
                        val (icon, tint) = when (it.outcome) {
                            SyncState.Outcome.FOUND -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
                            SyncState.Outcome.CACHED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
                            SyncState.Outcome.NOT_FOUND -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
                            SyncState.Outcome.ADDED -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
                            SyncState.Outcome.ADD_FAILED -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
                            SyncState.Outcome.REMOVED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(it.source, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (it.outcome) {
                                    SyncState.Outcome.FOUND -> stringResource(R.string.live_found, it.result ?: "")
                                    SyncState.Outcome.CACHED -> stringResource(R.string.live_cached, it.result ?: "")
                                    SyncState.Outcome.NOT_FOUND -> stringResource(R.string.live_not_found)
                                    SyncState.Outcome.ADDED -> stringResource(R.string.live_added)
                                    SyncState.Outcome.ADD_FAILED -> stringResource(R.string.live_add_failed, it.result ?: "")
                                    SyncState.Outcome.REMOVED -> stringResource(R.string.live_removed)
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun progressText(p: Progress?): String = when (p?.step) {
    null -> stringResource(R.string.running)
    Progress.Step.FETCH_SOURCE -> stringResource(R.string.progress_fetch_source)
    Progress.Step.CREATE_TARGET -> stringResource(R.string.progress_create_target)
    Progress.Step.FETCH_TARGET -> stringResource(R.string.progress_fetch_target)
    Progress.Step.MATCHING -> stringResource(R.string.progress_matching, p?.done ?: 0, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.WAITING -> stringResource(R.string.progress_waiting, ((p?.total ?: 0) - (p?.done ?: 0)).coerceAtLeast(0))
    Progress.Step.ADDING -> stringResource(R.string.progress_adding, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.REMOVING -> stringResource(R.string.progress_removing, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.BACKUP -> stringResource(R.string.tools_progress_backup, p?.done ?: 0, p?.total ?: 0)
    Progress.Step.DEDUPE -> stringResource(R.string.tools_progress_dedupe, p?.done ?: 0, p?.total ?: 0)
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

private sealed class Loaded {
    object Loading : Loaded()
    data class Ok(val items: List<Playlist>) : Loaded()
    data class Failed(val message: String) : Loaded()
}

/** A playlist in the saved instance state, as JSON. */
internal val PlaylistSaver = androidx.compose.runtime.saveable.Saver<Playlist?, String>(
    save = { it?.let { p -> com.xlollx.songport.net.json.encodeToString(Playlist.serializer(), p) } },
    restore = { runCatching { com.xlollx.songport.net.json.decodeFromString(Playlist.serializer(), it) }.getOrNull() },
)

/** The matching rules in the saved instance state, as JSON. */
private val PolicySaver = androidx.compose.runtime.saveable.Saver<com.xlollx.songport.model.MatchPolicy, String>(
    save = { com.xlollx.songport.net.json.encodeToString(com.xlollx.songport.model.MatchPolicy.serializer(), it) },
    restore = { runCatching { com.xlollx.songport.net.json.decodeFromString(com.xlollx.songport.model.MatchPolicy.serializer(), it) }.getOrNull() },
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SyncEditorScreen(job: SyncJob, onCancel: () -> Unit, onConnectService: () -> Unit = {}, twinName: String? = null, onSave: (SyncJob, SyncJob?) -> Unit) {
    val ctx = LocalContext.current
    val saveHaptic = rememberConfirmHaptic()
    val isNew = job.name.isEmpty()
    // Solo i connettori aggiunti nella scheda Account: uno tolto di li' non deve piu' essere
    // scegliibile, anche se il token o la sessione nell'app plugin sono ancora validi. I servizi gia'
    // usati dal job che si sta modificando restano nell'elenco per non cambiarlo di nascosto.
    val available = remember {
        (Providers.connectors().filter { it.isConnected(ctx) } +
            listOfNotNull(Providers.byId(job.source.provider), Providers.byId(job.target.provider))).distinctBy { it.id }
    }

    // Saved, so a rotation or a fold keeps what was chosen (the job itself travels as JSON).
    var name by rememberSaveable { mutableStateOf(job.name) }
    var srcProvider by rememberSaveable { mutableStateOf(job.source.provider.ifEmpty { available.firstOrNull()?.id ?: "" }) }
    var srcPlaylist by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(job.source.playlistId?.let { Playlist(it, job.source.playlistName) }) }
    // A pasted link is a source of its own: the field stays, the service's playlist list does not.
    var linkMode by rememberSaveable { mutableStateOf(job.source.link != null) }
    var linkText by rememberSaveable { mutableStateOf(job.source.link ?: "") }
    // Every song the user Shazams: the source is the playlist the Shazam app fills on Spotify or
    // Apple Music, looked up among the connected accounts instead of picked from a list.
    var shazamMode by rememberSaveable { mutableStateOf(job.shazam) }
    var shazamLookup by remember { mutableStateOf<ShazamLookup>(ShazamLookup.Looking) }
    var shazamTry by remember { mutableIntStateOf(0) }
    // Written by the AI: the source is the AI's own list, kept in a file; nothing to pick.
    val aiSource = job.aiPrompt != null && job.source.provider == com.xlollx.songport.providers.LocalFilesProvider.id
    // The songs a radio plays: the station, its days and hours. The source, a File playlist, is made on saving.
    var radioMode by rememberSaveable { mutableStateOf(job.radio != null) }
    var radioStation by rememberSaveable { mutableStateOf(job.radio?.station ?: "") }
    var radioStream by rememberSaveable { mutableStateOf(job.radio?.stream ?: "") }
    var radioHome by rememberSaveable { mutableStateOf(job.radio?.homepage ?: "") }
    // As "1,2,3": a list of days kept across a rotation.
    var radioDays by rememberSaveable { mutableStateOf((job.radio?.days ?: (1..7).toList()).joinToString(",")) }
    var radioStart by rememberSaveable { mutableIntStateOf(job.radio?.startMinute ?: RADIO_DEFAULT_START) }
    var radioEnd by rememberSaveable { mutableIntStateOf(job.radio?.endMinute ?: RADIO_DEFAULT_END) }
    var radioSaving by remember { mutableStateOf(false) }
    val radioName = radioStation.trim().takeIf { radioMode && it.isNotEmpty() }?.let { stringResource(R.string.radio_sync_name, it) }
    val editorScope = rememberCoroutineScope()
    // With a single service there is nowhere else to write to: a file on the phone always is, so a
    // first sync is possible before a second service is connected.
    val writable = available.filter { it.canWrite }.let { w ->
        if (w.size < 2 && w.none { it.id == com.xlollx.songport.providers.LocalFilesProvider.id }) w + com.xlollx.songport.providers.LocalFilesProvider else w
    }
    val loginServices = remember(available) { available.count { it.requiresAuth && it.isConnected(ctx) } }
    var dstProvider by rememberSaveable { mutableStateOf(job.target.provider.ifEmpty { writable.getOrNull(1)?.id ?: writable.firstOrNull()?.id ?: "" }) }
    var createNew by rememberSaveable { mutableStateOf(job.target.playlistId == null) }
    var dstPlaylist by rememberSaveable(stateSaver = PlaylistSaver) { mutableStateOf<Playlist?>(job.target.playlistId?.let { Playlist(it, job.target.playlistName) }) }
    var newName by rememberSaveable { mutableStateOf(if (job.target.playlistId == null) job.target.playlistName else "") }
    // A new Shazam sync checks every hour: a song Shazamed in the morning is there by lunch.
    var schedule by rememberSaveable { mutableStateOf(if (isNew && job.shazam) Schedule.HOURLY else job.schedule) }
    // The timing (sync/Timing): how often, at what time, on which days.
    var everyM by rememberSaveable { mutableStateOf(if (isNew && job.shazam) 60 else com.xlollx.songport.sync.Timing.interval(job)) }
    var atStr by rememberSaveable { mutableStateOf(job.at ?: "09:00") }
    var daysCsv by rememberSaveable { mutableStateOf((job.days.ifEmpty { listOfNotNull(legacyWeeklyDay(job) ?: java.time.LocalDate.now().dayOfWeek.value.takeIf { !isNew && com.xlollx.songport.sync.Timing.interval(job) >= 10080 }) }).joinToString(",")) }
    var mirror by rememberSaveable { mutableStateOf(job.mirrorRemovals) }
    var keepOrder by rememberSaveable { mutableStateOf(job.keepOrder) }
    var follows by rememberSaveable { mutableStateOf(job.followsChanges) }
    // Keep every track (an archive): never removes, adds only what is new; Mirror, its "changes by
    // itself" and the order are then out of the question, and hidden.
    var archive by rememberSaveable { mutableStateOf(job.archive) }
    // A source named before its id is known (an idea: "Discover Weekly"): picked from the list once it loads.
    var pickByName by rememberSaveable { mutableStateOf(job.source.playlistId == null && job.source.playlistName.isNotBlank()) }
    var wifiOnly by rememberSaveable { mutableStateOf(job.wifiOnly) }
    var enabled by rememberSaveable { mutableStateOf(job.enabled) }
    // What the colour picker holds: a ready colour, a saved label, or a colour of the user's own.
    var color by rememberSaveable { mutableStateOf(job.label ?: job.color) }
    val storeNow = remember { Store.get(ctx).data }
    var tagDraft by rememberSaveable(stateSaver = TagDraftSaver) { mutableStateOf(TagDraft(job.tags.toList())) }
    var accentStyle by rememberSaveable { mutableStateOf(job.accentStyle) }
    var bidirectional by rememberSaveable { mutableStateOf(job.linkedJobId != null) }
    // In a pair: a track removed on one side leaves the other too (off unless turned on).
    var twoWayRemovals by rememberSaveable { mutableStateOf(job.twoWayRemovals) }
    var policy by rememberSaveable(stateSaver = PolicySaver) { mutableStateOf(job.policy) }
    var matchingOpen by rememberSaveable { mutableStateOf(job.policy != com.xlollx.songport.model.MatchPolicy()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Everything the user can change, as one line: back or the arrow with a line other than the one
    // the editor opened with asks "Discard changes?". What the editor fills in by itself (a playlist
    // found by its name, liked songs as the target, Shazam's playlist) moves the starting line along.
    fun shape() = listOf(
        name, srcProvider, srcPlaylist?.id, linkMode, linkText, shazamMode, radioMode, radioStation, radioStream, radioHome, radioDays,
        radioStart, radioEnd, dstProvider, createNew, dstPlaylist?.id, newName, schedule, everyM, atStr, daysCsv, mirror, keepOrder, follows,
        archive, wifiOnly, enabled, color, tagDraft.all, accentStyle, bidirectional, twoWayRemovals, policy,
    ).joinToString("\u0001")
    var opened by rememberSaveable { mutableStateOf(shape()) }
    /** Runs [fill] (the editor filling something in) without counting it as the user's change. */
    fun filling(fill: () -> Unit) { val clean = shape() == opened; fill(); if (clean) opened = shape() }
    val leave = rememberDiscardGuard(shape() != opened, onClose = onCancel)
    ScreenBackHandler(leave)

    var srcLists by remember { mutableStateOf<Loaded>(Loaded.Loading) }
    var dstLists by remember { mutableStateOf<Loaded>(Loaded.Loading) }

    // "Try again" in the picker asks the service once more.
    var srcReload by remember { mutableIntStateOf(0) }
    var dstReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(srcProvider, srcReload) {
        srcLists = Loaded.Loading
        srcLists = loadPlaylists(ctx, srcProvider, forTarget = false)
    }
    LaunchedEffect(dstProvider, dstReload) {
        dstLists = Loaded.Loading
        dstLists = loadPlaylists(ctx, dstProvider, forTarget = true)
    }
    // The pickers' sheets: the services and what each offers, opened from the Source and Target fields.
    var srcSheet by rememberSaveable { mutableStateOf(false) }
    var dstSheet by rememberSaveable { mutableStateOf(false) }
    // Liked songs to a service that can like: propose "liked songs" as the target instead of a new playlist.
    // Not on the same account: liked songs into the same liked songs would be one list synced onto itself.
    LaunchedEffect(srcPlaylist?.id, dstProvider) {
        val dstObj = Providers.byId(dstProvider)
        val sid = srcPlaylist?.id
        if (MusicProvider.isLibrary(sid) && dstProvider != srcProvider && dstObj?.supportsLibrary(sid, asTarget = true) == true && dstPlaylist == null) filling {
            createNew = false
            dstPlaylist = Playlist(sid!!, srcPlaylist?.name ?: "")
        }
    }
    LaunchedEffect(srcLists) {
        if (!pickByName || srcProvider != job.source.provider) return@LaunchedEffect
        val items = (srcLists as? Loaded.Ok)?.items ?: return@LaunchedEffect
        pickByName = false
        val wanted = job.source.playlistName.trim()
        items.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }?.let { if (srcPlaylist == null) filling { srcPlaylist = it } }
    }
    // Job creato da un link condiviso: l'id c'e', il nome ancora no.
    LaunchedEffect(Unit) {
        val sp = srcPlaylist
        if (sp != null && sp.name.isBlank() && !MusicProvider.isLibrary(sp.id)) {
            val p = Providers.byId(srcProvider)
            if (p != null) srcPlaylist = runCatching { p.playlistInfo(ctx, sp.id) }.getOrElse { Playlist(sp.id, p.displayName) }
        }
    }

    // Shazam as the source: its playlist, once found (null: looked up by the effect below).
    fun useShazam(playlist: Playlist? = null) {
        if (!shazamMode && isNew) { schedule = Schedule.HOURLY; everyM = 60; mirror = false }
        shazamMode = true
        linkMode = false
        radioMode = false
        srcPlaylist = playlist
    }
    // A radio as the source: listened to on Wi-Fi unless the user says otherwise, run by its window, never mirrored.
    fun useRadio() {
        if (!radioMode && isNew) { schedule = Schedule.MANUAL; everyM = 0; mirror = false; wifiOnly = true; bidirectional = false; keepOrder = false }
        radioMode = true
        linkMode = false
        shazamMode = false
        srcPlaylist = null
    }
    // Looks for Shazam's playlist on every connected Spotify and Apple Music account, the source's own first.
    LaunchedEffect(shazamMode, shazamTry) {
        if (!shazamMode) return@LaunchedEffect
        shazamLookup = ShazamLookup.Looking
        val hosts = (listOfNotNull(Providers.byId(srcProvider)) + Providers.connectors())
            .filter { ShazamLibrary.canHost(it.serviceId) && it.isConnected(ctx) }.distinctBy { it.id }
        val errors = ArrayList<String>()
        for (p in hosts) {
            val l = loadPlaylists(ctx, p.id, forTarget = false)
            if (l is Loaded.Failed) errors += p.label(ctx) + ": " + l.message
            val found = (l as? Loaded.Ok)?.items?.let { ShazamLibrary.find(it) } ?: continue
            filling { srcProvider = p.id; srcPlaylist = found }
            shazamLookup = ShazamLookup.Found
            return@LaunchedEffect
        }
        shazamLookup = ShazamLookup.Missing(hostConnected = hosts.isNotEmpty(), errors = errors)
    }

    // Save, from the bottom bar: the same checks and the same jobs as before the cards.
    fun saveRadio() {
        if (radioSaving) return
        val station = radioStation.trim()
        val stream = radioStream.trim()
        val dp = dstPlaylist
        if (station.isEmpty() || stream.isEmpty() || dstProvider.isEmpty() || (!createNew && dp == null)) {
            error = ctx.getString(R.string.editor_error_incomplete)
            return
        }
        val days = radioDays.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.distinct().sorted()
        if (days.isEmpty()) {
            error = ctx.getString(R.string.radio_window_no_day)
            return
        }
        val radio = com.xlollx.songport.model.RadioSource(station, stream, radioHome.trim().ifEmpty { null }, days, radioStart, radioEnd)
        val defaultName = ctx.getString(R.string.radio_sync_name, station)
        val target = if (createNew) PlaylistRef(dstProvider, null, newName.ifBlank { defaultName }) else PlaylistRef(dstProvider, dp!!.id, dp.name)
        radioSaving = true
        editorScope.launch {
            try {
                val files = com.xlollx.songport.providers.LocalFilesProvider
                // The songs heard are kept in a File playlist of their own, as on the server: the sync's source.
                val source = job.source.takeIf { job.radio != null && it.provider == files.id && it.playlistId != null }
                    ?: files.createPlaylist(ctx, "Radio - $station", "").let { PlaylistRef(files.id, it.id, it.name) }
                saveHaptic()
                onSave(
                    job.copy(
                        name = name.ifBlank { defaultName }, source = source, target = target,
                        schedule = Schedule.MANUAL, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
                        keepOrder = false, color = pickedColor(color, storeNow.settings.labels), label = pickedLabel(color), tags = tagDraft.all, accentStyle = accentStyle, radio = radio, shazam = false, linkedJobId = null, archive = false,
                    ),
                    null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: ctx.getString(R.string.error_generic)
            } finally {
                radioSaving = false
            }
        }
    }

    fun save() {
        if (radioMode) return saveRadio()
        val sp = srcPlaylist
        val dp = dstPlaylist
        if (srcProvider.isEmpty() || dstProvider.isEmpty() || sp == null || (!createNew && dp == null)) {
            error = ctx.getString(R.string.editor_error_incomplete)
            return
        }
        // Two playlists of the same account are fine (an archive of Discover Weekly); one playlist onto itself is not.
        if (srcProvider == dstProvider && !createNew && dp?.id == sp.id) {
            error = ctx.getString(R.string.error_same_playlist)
            return
        }
        val srcName = sp.name
        // A link whose name could not be read yet: the run reads it and names the new playlist after it.
        val target = if (createNew) PlaylistRef(dstProvider, null, newName.ifBlank { if (linkMode) "" else srcName })
        else PlaylistRef(dstProvider, dp!!.id, dp.name)
        val source = PlaylistRef(srcProvider, sp.id, srcName, link = linkText.trim().takeIf { linkMode && it.isNotBlank() })
        fun timed(j: SyncJob): SyncJob = com.xlollx.songport.sync.Timing.apply(j, everyM, atStr, daysCsv.split(",").mapNotNull { it.trim().toIntOrNull() })
        val main = job.copy(
            name = name.ifBlank { srcName },
            source = source,
            target = target,
            schedule = schedule, mirrorRemovals = mirror && !archive, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
            keepOrder = keepOrder && !archive, color = pickedColor(color, storeNow.settings.labels), label = pickedLabel(color), tags = tagDraft.all, accentStyle = accentStyle, followsChanges = mirror && follows && !archive,
            archive = archive, shazam = shazamMode && !linkMode,
        ).let { timed(it) }
        // The other half of the pair takes only what the two share (schedule, rules, the two playlists
        // swapped); its name, ignore list, reports and order setting stay its own. A pair never removes.
        val partner = job.linkedJobId?.let { Store.get(ctx).job(it) }
        val reverse = if (bidirectional && !createNew && dp != null) {
            if (partner != null) {
                val moved = partner.source != target || partner.target != source
                partner.copy(
                    // Renamed with this sync only when it carries the name the editor gave it.
                    name = if (partner.name == "${job.name} ↔") "${main.name} ↔" else partner.name,
                    // Pairs saved before the flag existed get it here.
                    twin = partner.twin || partner.name == "${job.name} ↔",
                    source = target, target = source,
                    schedule = schedule, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
                    linkedJobId = job.id, color = main.color, label = main.label, tags = main.tags, favorite = main.favorite, accentStyle = accentStyle, twoWayRemovals = twoWayRemovals && !archive,
                    sourceVersion = if (moved) null else partner.sourceVersion,
                    targetVersion = if (moved) null else partner.targetVersion,
                ).let { timed(it) }
            } else SyncJob(
                id = UUID.randomUUID().toString(),
                name = "${main.name} ↔",
                source = target,
                target = source,
                schedule = schedule, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled, policy = policy,
                linkedJobId = job.id, color = main.color, label = main.label, tags = main.tags, favorite = main.favorite, accentStyle = accentStyle, twoWayRemovals = twoWayRemovals && !archive, twin = true,
            ).let { timed(it) }
        } else null
        val removals = reverse != null && twoWayRemovals && !archive
        // Turned off: what the pair had in common is forgotten, so turning it on again starts afresh.
        if (!removals) Store.get(ctx).dropPairState(job)
        saveHaptic()
        onSave(main.copy(linkedJobId = reverse?.id, twoWayRemovals = removals), reverse)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.editor_title_new else R.string.editor_title_edit)) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
            )
        },
        // Save stays in reach at the bottom whatever the length of the form. On a phone on its side
        // the keyboard and this bar together would leave no room for the field being typed in: the
        // bar steps aside while the keyboard is open.
        bottomBar = {
            if (!(LocalWindowLayout.current.short && WindowInsets.isImeVisible)) androidx.compose.material3.Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = { save() }, modifier = Modifier.weight(1f).height(52.dp).tourTarget(TourTarget.EDITOR_SAVE)) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Opened from the "↔" card: its settings follow this sync, so this is where they change.
            if (twinName != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        stringResource(R.string.editor_twin_note, twinName), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // Nothing to sync from yet, or only one service to sync between: say so, with the way to connect one.
            if (loginServices == 0) {
                ConnectServiceCard(stringResource(R.string.no_connected_providers), onConnectService)
            } else if (loginServices == 1) {
                ConnectServiceCard(stringResource(R.string.editor_one_service_hint), onConnectService, help = Helps.oneService)
            }
            EditorStep(1, stringResource(R.string.editor_source), Modifier.tourTarget(TourTarget.EDITOR_SOURCE), help = { HelpButton(Helps.library, Helps.link, label = stringResource(R.string.editor_source)) }) {
                if (aiSource) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(R.string.editor_source_ai), style = MaterialTheme.typography.titleSmall)
                                job.aiPrompt?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(stringResource(R.string.editor_source_ai_desc, job.source.playlistName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (job.radio != null) {
                    // A radio sync stays one: its station is shown, its days and hours change below.
                    RadioStationCard(radioStation, radioStream, onChange = null)
                } else {
                    // Il servizio di un link incollato puo' non essere fra quelli collegati (playlist pubblica).
                    // I file locali sono un'origine come le altre: un CSV importato sul telefono e' una
                    // lista da cui leggere, e l'app lo promette ("importa un file, poi usalo come
                    // origine della sync"). Senza questo non si poteva scegliere "File" come origine.
                    val srcProviders = (available + listOfNotNull(Providers.byId(srcProvider), Providers.byId(LocalFilesProvider.id))).distinctBy { it.id }
                    val srcObj = Providers.byId(srcProvider)
                    // One field: what the sync reads. Its sheet has the services, the link, Shazam and the radio.
                    when {
                        radioMode -> PlaylistField(
                            stringResource(R.string.editor_source_radio), radioStation.takeIf { radioStream.isNotEmpty() }?.ifBlank { radioStream },
                            stringResource(R.string.radio_search), onClick = { srcSheet = true },
                            subtitle = radioStream.takeIf { it.isNotEmpty() },
                            thumb = { PlaylistThumb(radioStation.takeIf { radioStream.isNotEmpty() }, null, 48.dp, icon = Icons.Filled.Radio) },
                        )
                        shazamMode -> PlaylistField(
                            stringResource(R.string.editor_playlist), stringResource(R.string.editor_source_shazam), "", onClick = { srcSheet = true },
                            subtitle = srcPlaylist?.let { playlistWhere(srcObj, it) }, provider = srcObj.takeIf { srcPlaylist != null },
                            thumb = { Icon(ShazamGlyph, null, Modifier.size(48.dp), tint = shazamTint()) },
                        )
                        else -> PlaylistField(
                            stringResource(R.string.editor_playlist), srcPlaylist?.let { playlistDisplayName(it.id, it.name, srcProvider) },
                            stringResource(if (linkMode) R.string.link_paste else R.string.editor_choose_playlist), onClick = { srcSheet = true },
                            subtitle = srcPlaylist?.let { listOfNotNull(playlistWhere(srcObj, it).takeIf { w -> w.isNotEmpty() } ?: srcProvider, stringResource(R.string.editor_source_link).takeIf { linkMode }).joinToString(" · ") },
                            provider = srcObj.takeIf { srcPlaylist != null },
                            thumb = { PlaylistThumb(srcPlaylist?.let { it.name.ifBlank { it.id } }, srcPlaylist?.id, 48.dp, imageUrl = srcPlaylist?.imageUrl, icon = Icons.Filled.Link.takeIf { linkMode && srcPlaylist == null }) },
                        )
                    }
                    if (srcSheet) PickerSheet(stringResource(R.string.editor_source), onDismiss = { srcSheet = false }) {
                        // Not a list to choose from: the radio's stations, the link's field (Shazam: nothing more).
                        val other: (@Composable () -> Unit)? = when {
                            radioMode -> ({
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (radioStream.isEmpty()) RadioStationPicker { st ->
                                        radioStation = st.name
                                        radioStream = st.stream
                                        radioHome = st.homepage
                                        srcSheet = false
                                    } else RadioStationCard(radioStation, radioStream) { radioStream = ""; radioStation = ""; radioHome = "" }
                                }
                            })
                            shazamMode -> ({})
                            linkMode -> ({
                                LinkImportField(initial = linkText, keep = true) { providerId, playlist, text ->
                                    srcProvider = providerId
                                    srcPlaylist = playlist
                                    linkText = text
                                    srcSheet = false
                                }
                            })
                            else -> null
                        }
                        PlaylistRows(
                            (srcLists as? Loaded.Ok)?.items, (srcLists as? Loaded.Failed)?.message, srcObj, srcPlaylist?.id,
                            onPick = { srcPlaylist = it; srcSheet = false },
                            recentIds = remember(srcProvider) { recentPlaylistIds(ctx, srcProvider) },
                            onRetry = { srcReload++ }, onSignIn = { srcSheet = false; onConnectService() },
                            header = {
                                ProviderPicker(srcProviders, if (linkMode || shazamMode || radioMode) "" else srcProvider, link = true, linkSelected = linkMode,
                                    onLink = { if (!linkMode) { linkMode = true; shazamMode = false; radioMode = false; srcPlaylist = null } },
                                    // Shazam needs nothing more chosen: the page shows what it found.
                                    shazam = true, shazamSelected = shazamMode, onShazam = { if (!shazamMode) useShazam(); srcSheet = false },
                                    radio = true, radioSelected = radioMode, onRadio = { useRadio() },
                                    onConnect = { srcSheet = false; onConnectService() },
                                ) { srcProvider = it; srcPlaylist = null; linkMode = false; shazamMode = false; radioMode = false }
                            },
                            other = other,
                        )
                    }
                    if (radioMode) {
                        // The station is in the field above; it is chosen and changed in its sheet.
                    } else if (shazamMode) {
                        ShazamSource(
                            shazamLookup, srcProvider, srcPlaylist,
                            onRetry = { shazamTry++ },
                            onConnect = onConnectService,
                        )
                    } else if (linkMode) {
                        // The link is pasted in the sheet; the playlist it leads to is in the field above.
                    } else {
                        (srcLists as? Loaded.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                        // A playlist that changes by itself (Discover Weekly, a daylist, a chart): replace or keep everything, said here.
                        val changing = srcPlaylist?.let { com.xlollx.songport.sync.ChangingPlaylists.isSelfChanging(Providers.byId(srcProvider)?.serviceId, it.name) } == true
                        if (changing) ChangesChoice(
                            replace = mirror && !archive, archive = archive,
                            onReplace = { archive = false; mirror = true; follows = true; bidirectional = false },
                            onArchive = { archive = true; mirror = false; follows = false; keepOrder = false },
                        )
                        // Spotify's own playlists through the official connection: empty by Spotify's rule, said plainly.
                        if (Providers.byId(srcProvider)?.serviceId == "spotify" && com.xlollx.songport.sync.ChangingPlaylists.madeBySpotify(srcPlaylist?.name)) {
                            SpotifyOwnPlaylistNote(quickRoute = com.xlollx.songport.providers.BridgePlugin.installed(ctx), onConnect = onConnectService)
                        }
                        // The playlist Shazam fills on Spotify or Apple Music: one tap makes this a Shazam sync.
                        val shazam = (srcLists as? Loaded.Ok)?.items?.takeIf { ShazamLibrary.canHost(Providers.byId(srcProvider)?.serviceId.orEmpty()) }?.let { ShazamLibrary.find(it) }
                        if (shazam != null) {
                            androidx.compose.material3.AssistChip(
                                onClick = { useShazam(shazam) },
                                label = { Text(stringResource(R.string.shazam_editor_suggest, shazam.name)) },
                                leadingIcon = { Icon(ShazamGlyph, null, Modifier.size(18.dp), tint = shazamTint()) },
                            )
                        }
                    }
                }
            }

            EditorStep(2, stringResource(R.string.editor_target), Modifier.tourTarget(TourTarget.EDITOR_TARGET), help = { HelpButton(Helps.target, Helps.liked, label = stringResource(R.string.editor_target)) }) {
                val dstObj = Providers.byId(dstProvider)
                val canCreate = dstObj?.canCreatePlaylists != false
                if (!canCreate && createNew) createNew = false
                // One field: where the sync writes. A new playlist shows the name it will get.
                val newTitle = newName.ifBlank { radioName ?: srcPlaylist?.let { playlistDisplayName(it.id, it.name) }.orEmpty() }.ifBlank { stringResource(R.string.editor_new_playlist) }
                PlaylistField(
                    stringResource(R.string.editor_playlist), if (createNew) newTitle else dstPlaylist?.let { playlistDisplayName(it.id, it.name) },
                    stringResource(R.string.editor_choose_playlist), onClick = { dstSheet = true },
                    subtitle = if (createNew) dstObj?.label(ctx) ?: dstProvider else playlistWhere(dstObj, dstPlaylist).ifEmpty { dstProvider },
                    provider = dstObj,
                    thumb = { if (createNew) NewPlaylistThumb(48.dp) else PlaylistThumb(dstPlaylist?.let { it.name.ifBlank { it.id } }, dstPlaylist?.id, 48.dp, imageUrl = dstPlaylist?.imageUrl) },
                )
                if (dstSheet) PickerSheet(stringResource(R.string.editor_target), onDismiss = { dstSheet = false }) {
                    PlaylistRows(
                        (dstLists as? Loaded.Ok)?.items?.filter { it.ownedByMe }, (dstLists as? Loaded.Failed)?.message, dstObj,
                        dstPlaylist?.id.takeIf { !createNew },
                        onPick = { createNew = false; dstPlaylist = it; dstSheet = false },
                        recentIds = remember(dstProvider) { recentPlaylistIds(ctx, dstProvider) },
                        onRetry = { dstReload++ }, onSignIn = { dstSheet = false; onConnectService() },
                        first = if (canCreate) ({
                            item("new") {
                                PickerRow(stringResource(R.string.editor_new_playlist), onClick = { createNew = true; dstSheet = false }, selected = createNew) { NewPlaylistThumb(48.dp) }
                            }
                        }) else null,
                        header = { ProviderPicker(writable, dstProvider, onConnect = { dstSheet = false; onConnectService() }) { dstProvider = it; dstPlaylist = null } },
                    )
                }
                SwitchRow(stringResource(R.string.editor_new_playlist), null, createNew, enabled = canCreate, help = { HelpButton(Helps.newPlaylist) }) { createNew = it }
                if (!canCreate && dstObj != null) {
                    Text(stringResource(R.string.error_create_unsupported, dstObj.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (createNew) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.editor_new_playlist_name)) },
                        placeholder = { Text(radioName ?: srcPlaylist?.let { playlistDisplayName(it.id, it.name) } ?: "") },
                        supportingText = { Text(stringResource(R.string.editor_new_playlist_name_hint)) })
                } else {
                    (dstLists as? Loaded.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                }
            }

            EditorStep(3, stringResource(R.string.editor_step_options)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.editor_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(radioName ?: srcPlaylist?.let { playlistDisplayName(it.id, it.name) } ?: "") })
                Text(stringResource(R.string.job_color), style = MaterialTheme.typography.labelLarge)
                JobColorPicker(color, storeNow.settings.labels) { color = it }
                TagsEditor(tagDraft, storeNow.syncs) { tagDraft = it }
                // A radio sync runs by its window: days and hours instead of a schedule, and no
                // removals, two-way or reordering (its source only grows).
                if (radioMode) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioWindowPicker(
                        days = radioDays.split(',').mapNotNull { it.trim().toIntOrNull() },
                        start = radioStart, end = radioEnd,
                        onDays = { radioDays = it.joinToString(",") }, onStart = { radioStart = it }, onEnd = { radioEnd = it },
                    )
                    RadioAlarmHint()
                } else Column(Modifier.tourTarget(TourTarget.EDITOR_SCHEDULE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HelpLabel(stringResource(R.string.editor_schedule)) { HelpButton(Helps.schedule) }
                    // I chip vanno a capo da soli, quante righe servono per lo schermo.
                    TimingPicker(everyM, atStr, daysCsv,
                        onEvery = { everyM = it; schedule = com.xlollx.songport.sync.Timing.legacy(it) },
                        onAt = { atStr = it }, onDays = { daysCsv = it })
                    if (everyM > 0 || daysCsv.isNotBlank()) {
                        // A sync at a time of day waits for that time; one by interval starts soon after saving.
                        Text(stringResource(if (everyM >= 1440 || daysCsv.isNotBlank()) R.string.editor_schedule_desc_timed else R.string.editor_schedule_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Two-way and Mirror removals don't go together: a track added on the target could be
                // removed by this sync before the reverse one copies it back.
                if (!radioMode) {
                    if (bidirectional && mirror) mirror = false
                    SwitchRow(
                        stringResource(R.string.editor_changes_archive), stringResource(R.string.editor_archive_desc), archive,
                        modifier = Modifier.testTag("editor_archive"),
                    ) { archive = it; if (it) { mirror = false; follows = false; keepOrder = false } }
                    // An archive never removes: the removal switches would only say what it does not do.
                    if (!archive) SwitchRow(
                        stringResource(R.string.editor_mirror),
                        stringResource(if (bidirectional) R.string.editor_mirror_two_way else R.string.editor_mirror_desc),
                        mirror, enabled = !bidirectional,
                        modifier = Modifier.tourTarget(TourTarget.EDITOR_MIRROR),
                        // Towards Liked songs, mirroring means unliking: said where the switch is.
                        help = { if (MusicProvider.isLibrary(dstPlaylist?.id) && !createNew) HelpButton(Helps.mirror, Helps.liked, label = stringResource(R.string.editor_mirror)) else HelpButton(Helps.mirror) },
                    ) { mirror = it }
                    // Into Liked songs a removal is an unlike, of everything liked there that the source lacks:
                    // said in plain words under the switch, not only behind the "?".
                    if (mirror && !archive && !createNew && dstPlaylist?.id == MusicProvider.LIKED_ID) {
                        Text(
                            stringResource(R.string.editor_mirror_liked_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // A source that renews itself (daylist, Discover Weekly): its big turnovers are not mistakes.
                    if (mirror && !bidirectional && !archive) {
                        SwitchRow(stringResource(R.string.editor_follows), stringResource(R.string.editor_follows_desc), follows) { follows = it }
                    }
                    // Liked songs on a service that orders them by the second of saving (Spotify): the
                    // switch decides between one a second, in order, and batches at full speed.
                    val likedSpacing = if (!createNew && dstPlaylist?.id == MusicProvider.LIKED_ID) Providers.byId(dstProvider)?.addSpacingMs(MusicProvider.LIKED_ID) ?: 0 else 0
                    if ((Providers.byId(dstProvider)?.canReorder == true || likedSpacing > 0) && !archive) {
                        SwitchRow(stringResource(R.string.editor_keep_order), stringResource(R.string.editor_keep_order_desc), keepOrder, help = { HelpButton(Helps.keepOrder) }) { keepOrder = it }
                        if (likedSpacing > 0) Text(
                            stringResource(if (keepOrder) R.string.editor_keep_order_liked_slow else R.string.editor_keep_order_liked_fast),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (keepOrder) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val dstProviderObj = Providers.byId(dstProvider)
                    if (mirror && !archive && dstProviderObj != null && !dstProviderObj.canRemoveTracks) {
                        Text(
                            stringResource(R.string.editor_mirror_unsupported, dstProviderObj.displayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Bidirezionale: crea la sync gemella al contrario, senza rimozioni (niente cancellazioni a ping-pong).
                    val srcObj = Providers.byId(srcProvider)
                    // A public link or someone else's playlist can be read, not written: nothing to send back to.
                    val srcOwned = srcPlaylist?.ownedByMe == true && srcObj?.isConnected(ctx) == true
                    // Not into Shazam's own playlist: Shazam alone writes there.
                    val biAllowed = !shazamMode && !createNew && srcObj?.canWrite == true && srcOwned &&
                        srcObj.supportsLibrary(srcPlaylist?.id, asTarget = true) &&
                        dstProviderObj?.supportsLibrary(dstPlaylist?.id, asTarget = false) == true
                    if (!biAllowed && bidirectional) bidirectional = false
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.editor_bidirectional))
                            Text(
                                stringResource(
                                    when {
                                        biAllowed -> R.string.editor_bidirectional_desc
                                        srcPlaylist != null && !srcOwned -> R.string.editor_bidirectional_not_owned
                                        else -> R.string.editor_bidirectional_unavailable
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HelpButton(Helps.twoWay)
                        Switch(checked = bidirectional, enabled = biAllowed, onCheckedChange = { bidirectional = it })
                    }
                    // With Two-way: removals travel too, told from additions by what both sides had after the last run.
                    if (bidirectional && biAllowed && !archive) SwitchRow(
                        stringResource(R.string.editor_two_way_removals), stringResource(R.string.editor_two_way_removals_desc), twoWayRemovals,
                        modifier = Modifier.testTag("editor_two_way_removals"),
                    ) { twoWayRemovals = it }
                }
                SwitchRow(stringResource(R.string.editor_wifi_only), stringResource(if (radioMode) R.string.radio_wifi_desc else R.string.editor_wifi_only_desc), wifiOnly) { wifiOnly = it }
                SwitchRow(stringResource(R.string.editor_enabled), null, enabled, help = { HelpButton(Helps.enabled) }) { enabled = it }

                // How candidates are chosen: folded, because the defaults suit most syncs.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { matchingOpen = !matchingOpen }) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.policy_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.policy_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (matchingOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (matchingOpen) {
                    HelpLabel(stringResource(R.string.policy_strictness)) { HelpButton(Helps.strictness) }
                    // I chip vanno a capo: in alcune lingue tre etichette non stanno su una riga.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(-1 to R.string.policy_loose, 0 to R.string.policy_normal, 1 to R.string.policy_strict).forEach { (v, res) ->
                            FilterChip(selected = policy.strictness == v, onClick = { policy = policy.copy(strictness = v) }, label = { Text(stringResource(res)) })
                        }
                    }
                    Text(stringResource(R.string.policy_strictness_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HelpLabel(stringResource(R.string.policy_explicit)) { HelpButton(Helps.explicit) }
                    // I chip vanno a capo: in alcune lingue tre etichette non stanno su una riga.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to R.string.policy_explicit_any, 1 to R.string.policy_explicit_yes, -1 to R.string.policy_explicit_clean).forEach { (v, res) ->
                            FilterChip(selected = policy.explicit == v, onClick = { policy = policy.copy(explicit = v) }, label = { Text(stringResource(res)) })
                        }
                    }
                    SwitchRow(stringResource(R.string.policy_same_album), stringResource(R.string.policy_same_album_desc), policy.sameAlbum, help = { HelpButton(Helps.sameAlbum) }) { policy = policy.copy(sameAlbum = it) }
                    SwitchRow(stringResource(R.string.policy_studio), stringResource(R.string.policy_studio_desc), policy.studioOnly) { policy = policy.copy(studioOnly = it) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Under a source that changes by itself: what the target does with each new version, as two plain
 * choices. Neither is chosen until the user picks one (or the sync already had one).
 */
@Composable
private fun ChangesChoice(replace: Boolean, archive: Boolean, onReplace: () -> Unit, onArchive: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("changes_choice"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.editor_changes_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(stringResource(R.string.editor_changes_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            listOf(
                Triple(replace, R.string.editor_changes_replace to R.string.editor_changes_replace_desc, onReplace),
                Triple(archive, R.string.editor_changes_archive to R.string.editor_changes_archive_desc, onArchive),
            ).forEach { (selected, texts, onClick) ->
                Row(
                    Modifier.fillMaxWidth().testTag(if (texts.first == R.string.editor_changes_archive) "changes_archive" else "changes_replace").heightIn(min = 48.dp).selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(texts.first), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(texts.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/**
 * The official Spotify connection cannot read the playlists Spotify makes (Discover Weekly, Release
 * Radar, daylist) since February 2026: Spotify's rule, not an error. Where this phone can connect
 * Spotify by the quick route, the way there.
 */
@Composable
private fun SpotifyOwnPlaylistNote(quickRoute: Boolean, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("spotify_own_note"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.spotify_own_playlists_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (quickRoute) {
                Text(stringResource(R.string.spotify_own_playlists_quick), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                FilledTonalButton(onClick = onConnect, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.empty_syncs_cta_accounts)) }
            }
        }
    }
}

/** A note at the top of the editor with the way to connect a service. */
@Composable
private fun ConnectServiceCard(text: String, onConnect: () -> Unit, help: HelpTopic? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                if (help != null) HelpButton(help, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.empty_syncs_cta_accounts)) }
            }
        }
    }
}

/** One of the editor's three steps: a numbered card (source, target, options). */
@Composable
private fun EditorStep(
    number: Int, title: String, modifier: Modifier = Modifier, help: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier.fillMaxWidth(),
        shape = ToolCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(number.toString(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (help != null) help()
            }
            content()
        }
    }
}

/** An editor label with its "?" at the end of the line. */
@Composable
private fun HelpLabel(text: String, help: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        help()
    }
}

/** Where the editor's search for Shazam's playlist stands. */
private sealed class ShazamLookup {
    object Looking : ShazamLookup()
    object Found : ShazamLookup()
    /** Not on any connected account; [hostConnected] false: no Spotify or Apple Music connected at all. */
    data class Missing(val hostConnected: Boolean, val errors: List<String>) : ShazamLookup()
}

/**
 * The source of a Shazam sync: Shazam's playlist once found, with the service it is on and its
 * size; otherwise how to have Shazam keep one (its settings › connect Spotify or Apple Music), a
 * "Check again", and the way to connect one of those two here when neither is.
 */
@Composable
private fun ShazamSource(lookup: ShazamLookup, providerId: String, playlist: Playlist?, onRetry: () -> Unit, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    when {
        lookup is ShazamLookup.Looking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.shazam_source_looking), style = MaterialTheme.typography.bodyMedium)
        }
        // Found, or (when it could not be looked up now) the one an existing sync already reads.
        playlist != null -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(ShazamGlyph, null, Modifier.size(24.dp), tint = shazamTint())
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.shazam_source_title), style = MaterialTheme.typography.titleSmall)
                    val service = Providers.byId(providerId)?.label(ctx) ?: providerId
                    val count = playlist.trackCount.takeIf { it >= 0 }?.let { pluralStringResource(R.plurals.card_tracks, it, it) }
                    Text(stringResource(R.string.shazam_source_found, playlist.name, service) + (count?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.shazam_source_found_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        else -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ShazamGlyph, null, Modifier.size(24.dp), tint = shazamTint())
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.shazam_howto_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(stringResource(R.string.shazam_howto_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                val hostConnected = (lookup as? ShazamLookup.Missing)?.hostConnected == true
                if (!hostConnected) {
                    Text(stringResource(R.string.shazam_howto_connect), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.shazam_howto_retry)) }
                    if (!hostConnected) FilledTonalButton(onClick = onConnect) { Text(stringResource(R.string.empty_syncs_cta_accounts)) }
                }
            }
        }
    }
    (lookup as? ShazamLookup.Missing)?.errors?.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private suspend fun loadPlaylists(ctx: android.content.Context, providerId: String, forTarget: Boolean): Loaded {
    val p = Providers.byId(providerId) ?: return Loaded.Ok(emptyList())
    // Servizio non collegato (es. playlist pubblica da link): niente elenco, nessun errore.
    if (!p.isConnected(ctx)) return Loaded.Ok(emptyList())
    return try {
        val lists = p.playlists(ctx)
        // "Brani preferiti": come origine dove il servizio li espone, come destinazione dove si possono scrivere.
        Loaded.Ok(p.libraryEntries(ctx, asTarget = forTarget) + lists)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        Loaded.Failed(e.message ?: ctx.getString(R.string.error_generic))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderPicker(
    available: List<MusicProvider>, selected: String,
    link: Boolean = false, linkSelected: Boolean = false, onLink: () -> Unit = {},
    shazam: Boolean = false, shazamSelected: Boolean = false, onShazam: () -> Unit = {},
    radio: Boolean = false, radioSelected: Boolean = false, onRadio: () -> Unit = {},
    onConnect: (() -> Unit)? = null,
    onSelect: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val connectLabel = stringResource(R.string.connect)
    // A row of tiles that scrolls: with several accounts and personal servers there can be many.
    val linkLabel = stringResource(R.string.editor_source_link)
    val shazamLabel = stringResource(R.string.editor_source_shazam)
    val radioLabel = stringResource(R.string.editor_source_radio)
    val tiles = available.map { ServiceTile(it.id, it.label(ctx), it) } +
        (if (link) listOf(ServiceTile(LINK_KEY, linkLabel, icon = Icons.Filled.Link)) else emptyList()) +
        (if (shazam) listOf(ServiceTile(SHAZAM_KEY, shazamLabel, icon = ShazamGlyph, brandColor = ServiceMarks.SHAZAM_COLOR)) else emptyList()) +
        (if (radio) listOf(ServiceTile(RADIO_KEY, radioLabel, icon = Icons.Filled.Radio)) else emptyList()) +
        // Last, the way to another service: Accounts.
        (if (onConnect != null) listOf(ServiceTile(CONNECT_KEY, connectLabel, icon = Icons.Filled.Add, action = true)) else emptyList())
    val current = when { linkSelected -> LINK_KEY; shazamSelected -> SHAZAM_KEY; radioSelected -> RADIO_KEY; else -> selected }
    ServiceTiles(tiles, current, { key -> when (key) { LINK_KEY -> onLink(); SHAZAM_KEY -> onShazam(); RADIO_KEY -> onRadio(); CONNECT_KEY -> onConnect?.invoke(); else -> onSelect(key) } })
}

/** The "from a link" tile among the services. */
private const val LINK_KEY = "link:"
private const val SHAZAM_KEY = "shazam:"
private const val RADIO_KEY = "radio:"
private const val CONNECT_KEY = "connect:"

/** The picture of a playlist that does not exist yet: a "+" on the target's green. */
@Composable
private fun NewPlaylistThumb(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF2FB67A), Color(0xFF0E7C56)))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

/** Campo per incollare il link di una playlist pubblica e usarla come origine. */
@Composable
private fun LinkImportField(initial: String = "", keep: Boolean = false, onResolved: (String, Playlist, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // A link pasted and not looked up yet is still there after a rotation.
    var text by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(stringResource(R.string.link_paste)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        // Short share links (open.spotify.com/s/…) are followed to the playlist first.
                        val ref = com.xlollx.songport.sync.LinkResolver.resolve(ctx, text)
                        // A Spotify link opens with whichever Spotify route is connected, web or official.
                        val provider = ref?.let { r ->
                            val named = com.xlollx.songport.providers.linkProvider(ctx, r.providerId)
                            named?.takeIf { it.canRead(ctx, r.playlistId) }
                                ?: Providers.connectors().firstOrNull { it.familyName == named?.familyName && it.canRead(ctx, r.playlistId) }
                                ?: named
                        }
                        if (ref == null || provider == null) {
                            error = ctx.getString(R.string.link_invalid)
                        } else if (!provider.canRead(ctx, ref.playlistId)) {
                            error = ctx.getString(R.string.link_not_connected, provider.displayName)
                        } else {
                            // No name when the service will not say yet: the run fills it in. A
                            // link-only source has nothing else to go on: its error (a private set) shows here.
                            val got = runCatching { provider.playlistInfo(ctx, ref.playlistId) }
                            val failed = got.exceptionOrNull()?.takeIf { provider.reportsLinkErrors(ref.playlistId) && it !is kotlinx.coroutines.CancellationException }
                            if (failed != null) {
                                error = failed.message ?: ctx.getString(R.string.error_generic)
                            } else {
                                val info = got.getOrElse { Playlist(ref.playlistId, "", ownedByMe = false) }
                                val pasted = text.trim()
                                if (!keep) text = ""
                                onResolved(provider.id, info, pasted)
                            }
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.link_use)) }
        }
        if (busy) Text(stringResource(R.string.link_resolving), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The periodic coffee card: two lines, two buttons, gone at the next open whatever is tapped. */
@Composable
private fun CoffeeCard() {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.support_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { SupportPrompt.closeNudge(store) }) { Text(stringResource(R.string.support_prompt_later)) }
                    TextButton(onClick = {
                        openSupport(ctx)
                        SupportPrompt.closeNudge(store)
                    }) { Text(stringResource(R.string.support_row)) }
                }
            }
        }
    }
}

/** La richiesta di contributo: cosa ha appena fatto l'app, perche' e' gratis, un tocco per offrire un caffe'. */
private fun crowdKey(c: com.xlollx.songport.sync.Timing.Crowd) = c.service + ":" + c.jobIds.sorted().joinToString(",")

/** The services where many syncs at once can be turned away (MusicProvider.spreadSchedules). */
private fun spreads(id: String): Boolean = Providers.byId(id)?.spreadSchedules == true

/** Several syncs reach one service at the same time: why that can fail, and two ways out. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CrowdCard(crowd: com.xlollx.songport.sync.Timing.Crowd, onDismiss: () -> Unit, onShow: () -> Unit, onSpread: (() -> Unit)?) {
    val name = Providers.byId(crowd.service)?.displayName ?: crowd.service
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(20.dp), tint = ink)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.crowd_title, name, crowd.at), style = MaterialTheme.typography.titleMedium, color = ink)
            }
            Text(stringResource(R.string.crowd_body), style = MaterialTheme.typography.bodyMedium, color = ink)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.whats_new_got_it), color = ink) }
                TextButton(onClick = onShow) { Text(stringResource(R.string.crowd_show), color = ink) }
                if (onSpread != null) FilledTonalButton(onClick = onSpread) { Text(stringResource(R.string.crowd_spread)) }
            }
        }
    }
}

/** The new times, one row per sync, old one struck through: nothing changes until Confirm. */
@Composable
private fun SpreadDialog(jobs: List<com.xlollx.songport.model.SyncJob>, crowd: com.xlollx.songport.sync.Timing.Crowd, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val times = remember(jobs, crowd) { com.xlollx.songport.sync.Timing.spread(jobs, crowd, sensitive = ::spreads) }
    val group = jobs.filter { it.id in crowd.jobIds }
    val name = Providers.byId(crowd.service)?.displayName ?: crowd.service
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spread_title, name)) },
        // A list of its own: it scrolls, under the line above it.
        scroll = false,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.spread_body), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(group, key = { it.id }) { j ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(j.name.ifBlank { j.source.playlistName }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            val new = times[j.id]
                            // A sync every few hours keeps its rhythm: it shows it, marked as staying.
                            if (new == null) Text(
                                stringResource(R.string.spread_stays, if (com.xlollx.songport.sync.Timing.timed(j)) j.at.orEmpty() else timingLabel(j)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                            else {
                                Text(j.at.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                                Text(" → ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(new, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val store = Store.get(ctx)
                jobs.filter { it.id in times }.forEach { j ->
                    val moved = j.copy(at = times.getValue(j.id))
                    store.upsertJob(moved)
                    com.xlollx.songport.sync.Scheduler.apply(ctx, moved)
                }
                onDismiss()
            }) { Text(stringResource(R.string.spread_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SupportCard(report: com.xlollx.songport.model.SyncReport) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val count = remember(report.added) { java.text.NumberFormat.getIntegerInstance().format(report.added) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.support_prompt_title, count), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(stringResource(R.string.support_prompt_body, report.jobName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { SupportPrompt.close(store) }) {
                    Text(stringResource(R.string.support_prompt_later), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = {
                    openSupport(ctx)
                    SupportPrompt.close(store)
                }) {
                    Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.support_row))
                }
            }
        }
    }
}

/**
 * The last run in numbers: added, removed, not found, and the playlist's size, large enough to
 * read at a glance; under them the last runs as a strip of marks (filled: ok, red: failed), oldest
 * on the left, so a sync that keeps failing shows before anyone opens the log.
 */
@Composable
private fun RunStats(
    color: String?, r: SyncReport, history: List<SyncReport>,
    onAdded: () -> Unit, onRemoved: () -> Unit, onNotFound: () -> Unit, onTracks: (() -> Unit)?,
    /** A medium card: shorter tiles, closer to the run history. */
    compact: Boolean = false,
) {
    val brush = jobControlBrush(color)
    val ink = jobInk(color)
    // Each figure opens what it counts: the tracks added, removed or not found, or the playlist itself.
    // Equal heights: a label that needs two lines (German, large fonts) doesn't make its tile taller than the rest.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(if (r.added > 0) "+${r.added}" else "0", stringResource(R.string.stat_added), Modifier.weight(1f), brush, ink ?: MaterialTheme.colorScheme.primary, onAdded.takeIf { r.added > 0 }, compact = compact)
        Stat(if (r.removed > 0) "−${r.removed}" else "0", stringResource(R.string.stat_removed), Modifier.weight(1f), brush, onClick = onRemoved.takeIf { r.removed > 0 }, compact = compact)
        Stat("${r.unmatched.size}", stringResource(R.string.stat_not_found), Modifier.weight(1f), brush, if (r.unmatched.isNotEmpty()) MaterialTheme.colorScheme.tertiary else null, onNotFound.takeIf { r.unmatched.isNotEmpty() }, compact = compact)
        // The tile opens the synced playlist, so it counts that one: what it held before the run, plus
        // and minus this run's changes. A source that replaces its songs (a daylist) without Mirror
        // removals leaves the target larger than the source: +43 on 50 reads 93, not 50.
        val size = if (r.targetCount > 0 || r.added > 0 || r.removed > 0) (r.targetCount + r.added - r.removed).coerceAtLeast(0) else r.sourceCount
        // Apart from this run's figures: a thin rule, then the playlist's size in an outlined tile.
        androidx.compose.material3.VerticalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        Stat("$size", stringResource(R.string.stat_in_playlist), Modifier.weight(1f), onClick = onTracks, outlined = true, compact = compact)
    }
    if (history.size >= 2) {
        Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
        val style = RunHistoryStyle.of(Store.get(LocalContext.current).state.collectAsState().value.settings.runHistoryStyle)
        RunHistory(history, style, ink ?: MaterialTheme.colorScheme.primary,
            pluralStringResource(R.plurals.stat_history, history.size, history.size, history.count { !it.ok }))
    }
}

@Composable
internal fun Stat(value: String, label: String, modifier: Modifier, brush: Brush? = null, tint: Color? = null, onClick: (() -> Unit)? = null, outlined: Boolean = false, compact: Boolean = false) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp))
            .let {
                when {
                    // A state, not a result: no fill, a dashed outline.
                    outlined -> it.drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawRoundRect(
                            line, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() - stroke / 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    brush != null -> it.background(brush)
                    else -> it.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
            .let { if (onClick != null) it.clickable(onClickLabel = label, onClick = onClick) else it }
            .padding(vertical = if (compact) 6.dp else 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A long figure ("+1150", "12345") shrinks to fit its tile instead of losing its last digits.
        val ink = tint ?: MaterialTheme.colorScheme.onSurface
        val big = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall
        androidx.compose.foundation.text.BasicText(
            value,
            style = big.copy(fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = big.fontSize, stepSize = 1.sp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * A small card's figures: one row of small marks, each an icon and a number (added, removed, not
 * found, then the playlist's size apart, outlined), read out with its words. Each opens what it
 * counts, as the large card's tiles do.
 */
@Composable
private fun SmallStats(
    color: String?, r: SyncReport, onAdded: () -> Unit, onRemoved: () -> Unit, onNotFound: () -> Unit, onTracks: (() -> Unit)?,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val brush = jobControlBrush(color)
    val ink = jobInk(color)
    val size = if (r.targetCount > 0 || r.added > 0 || r.removed > 0) (r.targetCount + r.added - r.removed).coerceAtLeast(0) else r.sourceCount
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    @Composable
    fun Mark(icon: ImageVector, value: Int, label: String, modifier: Modifier, tint: Color? = null, onClick: (() -> Unit)?, outlined: Boolean = false) {
        val shape = RoundedCornerShape(10.dp)
        Row(
            modifier.height(28.dp).clip(shape)
                .let { if (outlined) it.border(1.dp, line, shape) else if (brush != null) it.background(brush) else it.background(MaterialTheme.colorScheme.surfaceContainerHigh) }
                .let { if (onClick != null) it.clickable(onClickLabel = label, onClick = onClick) else it }
                .semantics(mergeDescendants = true) { contentDescription = "$value $label" }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("$value", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mark(Icons.Filled.Add, r.added, stringResource(R.string.stat_added), Modifier.weight(1f), if (r.added > 0) ink ?: MaterialTheme.colorScheme.primary else null, onAdded.takeIf { r.added > 0 })
        Mark(Icons.Filled.Remove, r.removed, stringResource(R.string.stat_removed), Modifier.weight(1f), onClick = onRemoved.takeIf { r.removed > 0 })
        Mark(Icons.Filled.SearchOff, r.unmatched.size, stringResource(R.string.stat_not_found), Modifier.weight(1f), if (r.unmatched.isNotEmpty()) MaterialTheme.colorScheme.tertiary else null, onNotFound.takeIf { r.unmatched.isNotEmpty() })
        Mark(Icons.AutoMirrored.Filled.QueueMusic, size, stringResource(R.string.stat_in_playlist), Modifier.weight(1.3f), onClick = onTracks, outlined = true)
    }
}

/** Which list a figure on the card opens, from which run. */
private data class StatList(val kind: Kind, val report: SyncReport) {
    enum class Kind { ADDED, REMOVED, NOT_FOUND }
}

/** The tracks behind a figure: what the last run added, removed or could not find. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatListSheet(list: StatList, onDismiss: () -> Unit) {
    val r = list.report
    val (title, lines, total) = when (list.kind) {
        StatList.Kind.ADDED -> Triple(stringResource(R.string.stat_list_added), r.addedTracks.map { it.toString() }, r.added)
        StatList.Kind.REMOVED -> Triple(stringResource(R.string.stat_list_removed), r.removedTracks.map { it.toString() }, r.removed)
        StatList.Kind.NOT_FOUND -> Triple(stringResource(R.string.stat_list_not_found), r.unmatched, r.unmatched.size)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(formatDate(r.startedEpoch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // Reports from before this list existed only kept the count.
            if (lines.isEmpty()) Text(stringResource(R.string.stat_list_unavailable), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines.size) { i -> Text(lines[i], style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (lines.isNotEmpty() && total > lines.size) item {
                    Text(stringResource(R.string.stat_list_more, total - lines.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A long line on a sync's card: it can be dragged sideways, and on its own it glides to the end and back,
 * pausing at each end, slowly. A short line stays still. A drag stops the gliding for a while; a card
 * scrolled away and back starts again from the beginning (basicMarquee stayed stuck half-way there).
 * An edge with more of the line beyond it fades out.
 * With animations turned off in Android, only the drag.
 */
@Composable
private fun Modifier.cardMarquee(): Modifier {
    val state = rememberScrollState()
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val animate = remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    LaunchedEffect(state.maxValue, animate) {
        if (!animate || state.maxValue <= 0) return@LaunchedEffect
        val pxPerSecond = with(density) { 32.dp.toPx() }
        var pause = 1500L
        while (true) {
            kotlinx.coroutines.delay(pause)
            pause = 1500L
            val target = if (state.value < state.maxValue / 2) state.maxValue else 0
            val ms = (kotlin.math.abs(target - state.value) / pxPerSecond * 1000).toInt().coerceAtLeast(300)
            try {
                state.animateScrollTo(target, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The finger took over: wait a little longer before gliding again from where it left.
                if (!coroutineContext[kotlinx.coroutines.Job]!!.isActive) throw e
                pause = 4000L
            }
        }
    }
    // Faded at an edge with more text beyond it: a line part-way through its glide showed cut at its
    // start (") · Brani preferiti"), as if hidden under the logos beside it.
    return this.graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val fade = 16.dp.toPx().coerceAtMost(size.width / 4)
            val rtl = layoutDirection == LayoutDirection.Rtl
            // Scrolled from its start: text hidden at the leading edge; short of its end: at the trailing one.
            val hiddenLeft = if (rtl) state.value < state.maxValue else state.value > 0
            val hiddenRight = if (rtl) state.value > 0 else state.value < state.maxValue
            if (hiddenLeft) drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                size = androidx.compose.ui.geometry.Size(fade, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (hiddenRight) drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f), size = androidx.compose.ui.geometry.Size(fade, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
        .horizontalScroll(state)
}

/** What a sync's card says about it: a few words, their tone, an icon, and what a tap does (if anything). */
private class CardStatusInfo(val text: String, val tone: Tone, val icon: ImageVector?, val onClick: (() -> Unit)? = null)

@Composable
private fun StatusChip(info: CardStatusInfo) {
    val click = info.onClick
    if (click != null) ActionPill(info.text, info.tone, info.icon ?: Icons.Filled.Warning, click) else StatusPill(info.text, info.tone, info.icon)
}

/** The status as a small badge on the services' logos: its icon in its tone; a tap acts, or says the words. */
@Composable
private fun StatusBadge(info: CardStatusInfo, modifier: Modifier) {
    // A tap shows the status in a small bubble over the badge; it goes after a few seconds or a tap elsewhere.
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(tip) { if (tip) { delay(3000); tip = false } }
    Box(modifier) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(toneBackground(info.tone))
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(onClickLabel = info.text) { info.onClick?.invoke() ?: run { tip = true } }
                .semantics { contentDescription = info.text },
            contentAlignment = Alignment.Center,
        ) {
            Icon(info.icon ?: Icons.Filled.Info, null, tint = toneContent(info.tone), modifier = Modifier.size(12.dp))
        }
        if (tip) BubbleOver(info.text) { tip = false }
    }
}

/**
 * A small bubble above its parent (below when there is no room), its tip on the parent's centre even
 * when the bubble is pushed in from the screen's edge. Material's tooltip put the tip off the badge.
 */
@Composable
private fun BubbleOver(text: String, onDismiss: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 2.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    var tipX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var below by remember { mutableStateOf(false) }
    val place = remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val mid = anchorBounds.center.x
                val x = (mid - popupContentSize.width / 2).coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
                val above = anchorBounds.top - popupContentSize.height - gap
                below = above < 0
                tipX = (mid - x).toFloat()
                return androidx.compose.ui.unit.IntOffset(x, if (below) anchorBounds.bottom + gap else above)
            }
        }
    }
    val bg = MaterialTheme.colorScheme.inverseSurface
    androidx.compose.ui.window.Popup(popupPositionProvider = place, onDismissRequest = onDismiss, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Box(
            Modifier.drawBehind {
                val c = 6.dp.toPx()
                val r = 8.dp.toPx()
                val top = if (below) c else 0f
                val bottom = if (below) size.height else size.height - c
                drawRoundRect(bg, topLeft = Offset(0f, top), size = Size(size.width, bottom - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                val x = tipX.coerceIn(r, size.width - r)
                val path = androidx.compose.ui.graphics.Path().apply {
                    if (below) { moveTo(x - c, c); lineTo(x, 0f); lineTo(x + c, c) } else { moveTo(x - c, bottom); lineTo(x, size.height); lineTo(x + c, bottom) }
                    close()
                }
                drawPath(path, bg)
            }.padding(top = if (below) 6.dp else 0.dp, bottom = if (below) 0.dp else 6.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

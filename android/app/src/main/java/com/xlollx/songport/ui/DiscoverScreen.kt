package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.JoinInner
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextAlign
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.sync.DiscoverOrder
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.config
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.importTracks

/**
 * The cards of the Discover tab, each opening its own page. The names are kept stable: a page
 * open survives a rotation by name. [emoji] and [tint] draw the tile's icon, [icon], [tint] and
 * [gradient] the header of its page ([identity]); [ai] puts the "AI"
 * tag on the card (it then needs the user's own AI key, see [AiClient]).
 */
internal enum class DiscoverCard(val emoji: String, val tint: Long, val titleRes: Int, val descRes: Int, val icon: ImageVector, val gradient: LongArray = LongArray(0), val ai: Boolean = false) {
    WRAPPED("✨", 0xFF6B4BD8, R.string.discover_wrapped_title, R.string.discover_wrapped_desc, Icons.Filled.AutoAwesome, longArrayOf(0xFF5B3FD9, 0xFFC2185B, 0xFFE0562F)),
    RELEASES("🆕", 0xFF8E24AA, R.string.discover_releases_title, R.string.discover_releases_desc, Icons.Filled.NewReleases),
    LINK("🔗", 0xFF1976D2, R.string.discover_link_title, R.string.discover_link_desc, Icons.Filled.Link, longArrayOf(0xFF0D47A1, 0xFF1976D2, 0xFF0097A7)),
    COMPAT("💞", 0xFFC2185B, R.string.tools_compat_title, R.string.discover_compat_desc, Icons.Filled.JoinInner, longArrayOf(0xFFC2185B, 0xFF7B1FA2, 0xFF1565C0)),
    ROAST("🔥", 0xFFD84315, R.string.discover_roast_title, R.string.discover_roast_desc, Icons.Filled.LocalFireDepartment, longArrayOf(0xFFB71C1C, 0xFFD84315, 0xFFEF6C00), ai = true),
    VANISHED("👻", 0xFF607D8B, R.string.discover_vanished_title, R.string.discover_vanished_desc, Icons.Filled.VisibilityOff, longArrayOf(0xFF263238, 0xFF455A64, 0xFF78909C)),
    SUBS("💸", 0xFF2E7D32, R.string.discover_subs_title, R.string.discover_subs_desc, Icons.Filled.Payments, longArrayOf(0xFF1B5E20, 0xFF2E7D32, 0xFF00796B)),
    POSTER("🖼️", 0xFFF9A825, R.string.tools_poster_title, R.string.discover_poster_desc, Icons.Filled.Palette, longArrayOf(0xFF3E2723, 0xFF8D5A2B, 0xFFC68A12)),
    CONCERTS("🎟️", 0xFFAD1457, R.string.discover_concerts_title, R.string.discover_concerts_desc, Icons.Filled.ConfirmationNumber),
    SETLISTS("🎤", 0xFF3949AB, R.string.discover_setlists_title, R.string.discover_setlists_desc, Icons.Filled.Mic),
    RECORDS("💿", 0xFF4E342E, R.string.discover_records_title, R.string.discover_records_desc, Icons.Filled.Album),
    FOLLOWED("🔔", 0xFF00695C, R.string.alerts_followed_title, R.string.alerts_followed_desc, Icons.Filled.Notifications),
    ;

    /** The tool's look on its page: this card's colour, its icon and its header's gradient ([DiscoverToolScaffold]). */
    val identity: ToolIdentity get() = ToolIdentity(icon, tint, *gradient)

    /** The card's id in the saved order (Settings.discoverOrder, sync/DiscoverOrder). */
    val id: String get() = name.lowercase()

    companion object {
        /**
         * The cards of Trackhop 1.18, in the grid only with [com.xlollx.songport.Features.v2]. Followed
         * artists still opens from its alerts' notification for who follows artists already.
         */
        val V2: Set<DiscoverCard> = setOf(RELEASES, CONCERTS, SETLISTS, RECORDS, FOLLOWED)

        /** The cards of the grid (the Wrapped is on top, outside it) in [saved]'s order, new ones where the default puts them. */
        fun ordered(saved: List<String>): List<DiscoverCard> {
            val v2 = com.xlollx.songport.Features.v2
            val byId = entries.filter { it != WRAPPED && (v2 || it !in V2) }.associateBy { it.id }
            return DiscoverOrder.merge(saved, DiscoverOrder.DEFAULT.filter { it in byId } + byId.keys.filter { it !in DiscoverOrder.DEFAULT })
                .mapNotNull { byId[it] }
        }
    }
}

/** Test tag of a card of the Discover grid. */
const val DISCOVER_TILE_TAG = "discoverTile"

/**
 * Discover ("Scopri"): playful things to do with one's own music and to share. Everything is
 * computed on the phone from what the app reads from the user's services or already keeps
 * (backups, sync history); nothing leaves it unless the user shares a card or a link. One hero
 * card (the Wrapped) and a two-column grid; each card opens its page, with its own top bar, like
 * a tool in Tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    snackbar: SnackbarHostState,
    compareLinks: Pair<String, String>? = null,
    onCompareClosed: () -> Unit = {},
    onSubpage: (Boolean) -> Unit = {},
    onGoToAccounts: () -> Unit = {},
    onBackup: () -> Unit = {},
    onNewSyncFromFile: (name: String, fileId: String) -> Unit = { _, _ -> },
    /** A new sync opened in the editor, as the page sets it up (New releases: from its file, or the weekly one). */
    onNewSync: (com.xlollx.songport.model.SyncJob.() -> com.xlollx.songport.model.SyncJob) -> Unit = {},
    /** The songs a sync did not find, to review (a setlist sent to a service). */
    onReview: (reportId: String) -> Unit = {},
    /** Grows when the Discover tab is tapped again while it is open: back to the list of cards. */
    homeRequests: Int = 0,
    /** Asked from elsewhere (an idea on the Syncs tab): the Wrapped opens, once. */
    openWrapped: Boolean = false,
    onWrappedOpened: () -> Unit = {},
    /** Asked from the alerts' notification: Followed artists opens, once. */
    openFollowed: Boolean = false,
    onFollowedOpened: () -> Unit = {},
    /** No AI to use: where the key is set up (Tools › Playlist generator). */
    onSetUpAi: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<DiscoverCard?>(null) }
    LaunchedEffect(openWrapped) { if (openWrapped) { open = DiscoverCard.WRAPPED; onWrappedOpened() } }
    LaunchedEffect(openFollowed) { if (openFollowed) { open = DiscoverCard.FOLLOWED; onFollowedOpened() } }
    // A setlist made on setlist.fm's own site in the app: shown on the Setlists page.
    LaunchedEffect(SetlistsKept.showBrowsed) { if (SetlistsKept.showBrowsed) { open = DiscoverCard.SETLISTS; SetlistsKept.showBrowsed = false } }
    val listState = rememberLazyGridState()
    // Two playlist links shared together open the comparison straight away.
    LaunchedEffect(compareLinks) { if (compareLinks != null) open = DiscoverCard.COMPAT }
    val current = open
    fun close() { if (open == DiscoverCard.COMPAT) onCompareClosed(); open = null }
    LaunchedEffect(homeRequests) { if (homeRequests > 0 && open != null) { close(); listState.scrollToItem(0) } }
    DisposableEffect(current != null) {
        onSubpage(current != null)
        onDispose { onSubpage(false) }
    }
    // The user's AI, read again whenever the list shows (it may have been set up in Tools meanwhile);
    // without a key, Gemini Nano when the phone has it.
    val saved = remember(current) { AiClient.config(ctx)?.takeIf { it.complete } }
    val ai = AiClient.pick(saved, com.xlollx.songport.ai.NanoAi.state, com.xlollx.songport.ai.NanoAi.NAME)

    if (current != null) {
        val local = remember { SnackbarHostState() }
        // Followed artists: its settings sheet, opened from the gear in the bar.
        var followedSettings by remember { mutableStateOf(false) }
        BackHandler { close() }
        Scaffold(
            // Above the glass bottom bar, which the page runs under: a snackbar there would be hidden by it.
            snackbarHost = { SnackbarHost(local, Modifier.padding(bottom = LocalBottomBarInset.current)) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.titleRes)) },
                    navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                    actions = {
                        if (current == DiscoverCard.FOLLOWED) IconButton(onClick = { followedSettings = true }) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.alerts_settings))
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
        ) { padding ->
            val scroll = rememberScrollState()
            androidx.compose.runtime.CompositionLocalProvider(LocalToolScroll provides scroll) { Column(
                Modifier.padding(padding).verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp + LocalBottomBarInset.current),
            ) {
                // The pages redesigned on their own keep the whole width (their lists run in columns there).
                val wide = current in setOf(DiscoverCard.RELEASES, DiscoverCard.CONCERTS, DiscoverCard.SETLISTS, DiscoverCard.RECORDS, DiscoverCard.FOLLOWED)
                DiscoverToolScaffold(current.identity, stringResource(current.descRes), maxWidth = if (wide) WindowLayout.WIDE_CONTENT else 840.dp) {
                    when (current) {
                        DiscoverCard.WRAPPED -> WrappedPage(local, onGoToAccounts)
                        DiscoverCard.RELEASES -> ReleasesPage(local, onNewSync)
                        DiscoverCard.LINK -> UniversalLinkPage(local)
                        DiscoverCard.COMPAT -> CompatibilityCard(local, compareLinks) { name, tracks ->
                            // The songs in common become a file playlist, the source of a new sync to any service.
                            val id = LocalFilesProvider.importTracks(ctx, name, tracks)
                            onNewSyncFromFile(name, id)
                        }
                        DiscoverCard.ROAST -> RoastPage(local, ai, onSetUpAi)
                        DiscoverCard.VANISHED -> VanishedPage(onBackup)
                        DiscoverCard.SUBS -> SubscriptionsPage(local, onGoToAccounts)
                        DiscoverCard.POSTER -> PosterCard(local)
                        DiscoverCard.CONCERTS -> ConcertsPage(local)
                        DiscoverCard.SETLISTS -> SetlistsPage(local, onReview)
                        DiscoverCard.RECORDS -> RecordsPage(local)
                        DiscoverCard.FOLLOWED -> FollowedArtistsPage(local, followedSettings) { followedSettings = false }
                    }
                }
            } }
        }
        return
    }

    val store = Store.get(ctx)
    val savedOrder = store.state.collectAsState().value.settings.discoverOrder
    val order = DiscoverCard.ordered(savedOrder)
    // The order while a card is dragged: saved when it is let go.
    var dragOrder by remember { mutableStateOf<List<DiscoverCard>?>(null) }
    val shown = dragOrder ?: order
    // The store's order as it is now: the drag and TalkBack callbacks can outlive the composition they were made in.
    val orderNow = { DiscoverCard.ordered(store.data.settings.discoverOrder) }
    fun save(cards: List<DiscoverCard>) {
        val ids = cards.map { it.id }
        store.updateSettings { it.copy(discoverOrder = DiscoverOrder.toSave(ids, DiscoverCard.ordered(emptyList()).map { c -> c.id })) }
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The card held in the gesture under way: its release ends a hold, it does not open the card.
    val held = remember { arrayOfNulls<DiscoverCard>(1) }
    val reorder = rememberReorderableLazyGridState(listState) { from, to ->
        val o = (dragOrder ?: orderNow()).toMutableList()
        val a = o.indexOfFirst { it.id == from.key }; val b = o.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) { o.add(b, o.removeAt(a)); dragOrder = o }
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // The height of each card's content: the two cards of a row are as tall as the taller one.
    val heights = remember { mutableStateMapOf<DiscoverCard, Int>() }
    val density = LocalDensity.current
    val wholeRow: (LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    val earlier = stringResource(R.string.discover_move_earlier)
    val later = stringResource(R.string.discover_move_later)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), state = listState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "subtitle", span = wholeRow) {
            Text(
                stringResource(R.string.discover_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item(key = "wrapped", span = wholeRow) { WrappedHero { open = DiscoverCard.WRAPPED } }
        itemsIndexed(shown, key = { _, c -> c.id }) { i, card ->
            ReorderableItem(reorder, key = card.id) { isDragging ->
                // No AI at all: the card still opens, on Oli's verdict and the way to an AI (OliRoast).
                val noAi = card.ai && ai == null
                val disabledWhy = if (noAi) stringResource(R.string.discover_roast_no_ai_tap) else null
                val partner = shown.getOrNull(if (i % 2 == 0) i + 1 else i - 1)
                val rowHeight = maxOf(heights[card] ?: 0, partner?.let { heights[it] } ?: 0)
                val lift by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "lift")
                DiscoverTile(
                    card, disabledWhy,
                    Modifier.heightIn(min = with(density) { rowHeight.toDp() })
                        .graphicsLayer {
                            shadowElevation = lift.toPx(); shape = RoundedCornerShape(20.dp); clip = false
                            val k = 1f + 0.03f * (lift.value / 10f); scaleX = k; scaleY = k
                        }
                        .testTag(DISCOVER_TILE_TAG)
                        // TalkBack and Switch Access: the same moves without dragging.
                        .semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(earlier) { save(DiscoverCard.ordered(DiscoverOrder.move(orderNow().map { it.id }, card.id, -1))); true }.takeIf { i > 0 },
                                CustomAccessibilityAction(later) { save(DiscoverCard.ordered(DiscoverOrder.move(orderNow().map { it.id }, card.id, 1))); true }.takeIf { i < shown.size - 1 },
                            )
                        }
                        .longPressDraggableHandle(
                            onDragStarted = { held[0] = card; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = {
                                dragOrder?.let { if (it != orderNow()) save(it) }
                                dragOrder = null
                                // Cleared once this release has been handled everywhere (the card's own tap included).
                                scope.launch { held[0] = null }
                            },
                        ),
                    onMeasured = { heights[card] = it },
                ) { if (held[0] != card) open = card }
            }
        }
        item(key = "order", span = wholeRow) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.discover_order_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                if (savedOrder.isNotEmpty()) TextButton(onClick = { store.updateSettings { it.copy(discoverOrder = emptyList()) } }) {
                    Text(stringResource(R.string.discover_order_reset))
                }
            }
        }
    }
}

/** The Wrapped, on top: a gradient card with its button. */
@Composable
private fun WrappedHero(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF6B4BD8), Color(0xFFC2185B), Color(0xFFFF7043))))
            // The whole card opens the Wrapped, not only its button.
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.discover_wrapped_title), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.discover_wrapped_desc), color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF3A2470)),
        ) { Text(stringResource(R.string.discover_wrapped_button), fontWeight = FontWeight.SemiBold) }
    }
}

/** One card of the grid: its emoji on a tinted tile, name, one line of what it does; dimmed with the reason when it cannot be used yet (still tappable). */
@Composable
private fun DiscoverTile(card: DiscoverCard, disabledWhy: String?, modifier: Modifier, onMeasured: (Int) -> Unit = {}, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val enabled = disabledWhy == null
    Card(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.onSizeChanged { onMeasured(it.height) }.padding(14.dp).fillMaxWidth()) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(card.tint).copy(alpha = if (dark) 0.45f else 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Text(card.emoji, fontSize = 20.sp) }
                Spacer(Modifier.height(8.dp))
                val alpha = if (enabled) 1f else 0.6f
                Text(stringResource(card.titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Spacer(Modifier.height(3.dp))
                Text(disabledWhy ?: stringResource(card.descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            if (card.ai) StatusPill(stringResource(R.string.discover_ai_tag), Tone.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
    }
}

/** The system share sheet for a piece of text (a link, a roast). */
internal fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

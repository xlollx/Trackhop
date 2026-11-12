package com.xlollx.songport.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider

// The playlist picker: one field on the page (the playlist's picture, its name, where it lives and
// how long it is, "Change") that opens a sheet with the services as a strip of marks and the
// playlists as rows with their pictures. The pieces are here; each page puts in its own choices.

internal val PickerFieldShape = RoundedCornerShape(16.dp)

/**
 * A playlist's small picture: its cover where the service lists one ([imageUrl], fetched only where
 * pictures are on), else a tile in its name's colours with a note (a heart for the liked songs, a
 * shelf for a library section). Without a [name], the dashed "+" of a choice not made yet.
 */
@Composable
internal fun PlaylistThumb(name: String?, id: String?, size: Dp, modifier: Modifier = Modifier, imageUrl: String? = null, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(size / 5)
    if (name == null) {
        val dash = MaterialTheme.colorScheme.outline
        Box(
            modifier.size(size).drawBehind {
                drawRoundRect(dash, cornerRadius = CornerRadius(size.toPx() / 5), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
            }.clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) { Icon(icon ?: Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.45f)) }
        return
    }
    val ctx = LocalContext.current
    val url = imageUrl?.takeIf { it.isNotBlank() && !MusicProvider.isLibrary(id) }
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { if (url != null && discoverImagesOn(ctx)) bmp = Cards.bitmap(url, 160) }
    val h = ((name.hashCode() % 360) + 360) % 360
    val brush = Brush.linearGradient(listOf(Color.hsv(h.toFloat(), 0.5f, 0.62f), Color.hsv(((h + 50) % 360).toFloat(), 0.65f, 0.32f)))
    Box(modifier.size(size).clip(shape).background(brush).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(
            icon ?: when { id == MusicProvider.LIKED_ID -> Icons.Filled.Favorite; MusicProvider.isLibrary(id) -> Icons.Filled.LibraryMusic; else -> Icons.Filled.MusicNote },
            null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(size * 0.45f),
        )
    }
}

/**
 * The field a playlist is chosen in: what it is for ([label]), the playlist's picture and name, the
 * service's mark with where it lives and how long it is, and "Change". The whole row opens the picker.
 * Nothing chosen yet: [placeholder], and an arrow.
 */
@Composable
internal fun PlaylistField(
    label: String, title: String?, placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, provider: MusicProvider? = null, enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = PickerFieldShape, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    thumb: @Composable () -> Unit = { PlaylistThumb(title, null, 48.dp) },
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(color)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumb()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null || provider != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (provider != null) { ServiceLogo(provider, 16.dp, Modifier.clearAndSetSemantics {}); Spacer(Modifier.width(6.dp)) }
                Text(subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (title != null) Text(stringResource(R.string.share_sheet_change), style = MaterialTheme.typography.labelLarge, color = if (enabled) scheme.primary else scheme.onSurfaceVariant)
        else Icon(Icons.Filled.KeyboardArrowDown, null, tint = scheme.onSurfaceVariant)
    }
}

/** "Spotify · 60 tracks": where a playlist lives and how long it is, for a [PlaylistField]'s second line. */
@Composable
internal fun playlistWhere(provider: MusicProvider?, playlist: Playlist?): String {
    val ctx = LocalContext.current
    val count = playlist?.trackCount?.takeIf { it >= 0 }?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.card_tracks, it, it) }
    return listOfNotNull(provider?.label(ctx.env), count).joinToString(" · ")
}

/**
 * The picker's sheet, from the bottom: [title] says what is being chosen. One sheet for every window
 * (as wide as a readable column on a tablet or a phone on its side), so what is typed in it is still
 * there after a rotation. [tall]: as high as the window allows whatever is in it, so it does not
 * grow and shrink as a list loads or another service is chosen; in a low window (a phone on its
 * side) it takes all of it, without the handle and with a smaller title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheet(title: String, onDismiss: () -> Unit, tall: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val short = LocalWindowLayout.current.short
    val body: @Composable ColumnScope.() -> Unit = {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = if (tall && !short) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = if (short) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            if (tall) androidx.compose.material3.IconButton(onClick = onDismiss) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, stringResource(R.string.ai_done_close))
            }
        }
        content()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // A tall sheet has no handle: it closes with ✕, a tap above it or Back.
        dragHandle = if (short || tall) null else ({ androidx.compose.material3.BottomSheetDefaults.DragHandle() }),
    ) {
        // A tall sheet with a long list never moves under a finger (it slid down and snapped back, or
        // closed, owner 12/11): the list keeps the scroll it does not use, and a drag anywhere else on
        // the sheet is taken here, before the sheet sees it.
        val keepScroll = remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) = available
                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = available
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .then(if (tall) Modifier.fillMaxHeight(if (short) 1f else 0.92f).nestedScroll(keepScroll).pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val e = awaitPointerEvent()
                            e.changes.forEach { if (it.positionChange() != androidx.compose.ui.geometry.Offset.Zero) it.consume() }
                        } while (e.changes.any { it.pressed })
                    }
                } else Modifier)
                .padding(top = if (short || tall) 12.dp else 0.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 12.dp), content = body,
        )
    }
}

/** The sheet's side margin: what is put in it by a page keeps to it. */
internal val PickerSheetMargin = 16.dp

/**
 * What a picker's sheet holds under its title: the services' strip ([header]) and a service's
 * playlists as rows with their pictures: a search once they are many, the Playlists / Library
 * choice where the service has library sections (liked songs stay with the playlists, first), the
 * ones already in the user's syncs first as "Recent", then all of them.
 * [items] null: still loading (grey rows, which the list fades in over). [error]: what the service
 * answered instead, with "Try again" and, where given, the way to sign in again. [first]: the page's
 * own rows above the list ("Create a new playlist"). [other]: what is chosen is not from a list (a
 * pasted link, a radio): this instead of the search and the rows.
 *
 * In a low window (a phone on its side) the strip, the search and the choice scroll away with the
 * rows, the search and the choice on one line: the list has the sheet's height. [fill]: the list
 * takes what is left of a sheet of a fixed height.
 */
@Composable
internal fun ColumnScope.PlaylistRows(
    items: List<Playlist>?, error: String?, provider: MusicProvider?, selectedId: String?, onPick: (Playlist) -> Unit,
    recentIds: List<String> = emptyList(), onRetry: (() -> Unit)? = null, onSignIn: (() -> Unit)? = null,
    first: (LazyListScope.() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null, other: (@Composable () -> Unit)? = null, fill: Boolean = true,
) {
    val short = LocalWindowLayout.current.short
    val isSection = { p: Playlist -> MusicProvider.isLibrary(p.id) && p.id != MusicProvider.LIKED_ID }
    val (library, playlists) = items.orEmpty().partition(isSection)
    var showLibrary by rememberSaveable(provider?.id) { mutableStateOf(MusicProvider.isLibrary(selectedId) && selectedId != MusicProvider.LIKED_ID) }
    if (items != null && library.isEmpty()) showLibrary = false
    var query by rememberSaveable(provider?.id) { mutableStateOf("") }
    val shown = if (showLibrary) library else playlists
    val q = query.trim()
    // The search has its place while the list loads too, so the rows do not start lower once it is there.
    val showSearch = other == null && error == null && (items == null || items.size > 6)
    // The choice keeps its place while another service's list loads, if the one before had it.
    var hadLibrary by remember { mutableStateOf(false) }
    if (items != null) hadLibrary = library.isNotEmpty()
    val showChips = other == null && error == null && hadLibrary

    val search: @Composable (Modifier) -> Unit = { m ->
        TextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.unmatched_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = m,
        )
    }
    val chips: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showLibrary, onClick = { showLibrary = false }, label = { Text(stringResource(R.string.editor_section_playlists)) })
            FilterChip(selected = showLibrary, onClick = { showLibrary = true }, label = { Text(stringResource(R.string.editor_section_library)) })
        }
    }
    // Upright: the search over the choice. On its side: both on one line.
    val tools: @Composable () -> Unit = {
        if (short) Row(Modifier.fillMaxWidth().animateContentSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSearch) search(Modifier.weight(1f))
            if (showChips) chips()
        } else Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSearch) search(Modifier.fillMaxWidth())
            if (showChips) chips()
        }
    }
    val hasTools = showSearch || showChips

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Another service, the other section, a search: the list from its top.
    var seen by remember { mutableStateOf(Triple(provider?.id, showLibrary, other != null)) }
    LaunchedEffect(provider?.id, showLibrary, other != null) {
        val now = Triple(provider?.id, showLibrary, other != null)
        if (now != seen) { seen = now; listState.scrollToItem(0) }
    }
    if (!short) {
        if (header != null) Box(Modifier.padding(horizontal = PickerSheetMargin)) { header() }
        if (hasTools) Box(Modifier.padding(horizontal = PickerSheetMargin)) { tools() }
    }
    LazyColumn(
        Modifier.fillMaxWidth().weight(1f, fill = fill), state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        if (short) {
            if (header != null) item("picker-head") { Box(Modifier.padding(horizontal = 8.dp)) { header() } }
            if (hasTools) item("picker-tools") { Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) { tools() } }
        }
        if (other != null) { item("picker-other") { Box(Modifier.padding(horizontal = 8.dp).padding(top = if (short) 6.dp else 0.dp)) { other() } }; return@LazyColumn }
        first?.invoke(this)
        // The rows fade in where the grey ones were: nothing slides, nothing waits.
        val fade = androidx.compose.animation.core.tween<Float>(200)
        when {
            error != null -> item("error") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onSignIn != null) FilledTonalButton(onClick = onSignIn) { Text(stringResource(R.string.sign_in_again)) }
                        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.artists_retry)) }
                    }
                }
            }
            items == null -> items(8, key = { "skeleton:$it" }) { SkeletonPlaylistRow() }
            else -> {
                val found = if (q.isEmpty()) shown else shown.filter { it.name.contains(q, ignoreCase = true) }
                val recent = if (q.isEmpty() && !showLibrary && shown.size >= 8) recentIds.mapNotNull { id -> shown.firstOrNull { it.id == id } }.distinctBy { it.id }.take(3) else emptyList()
                if (recent.isNotEmpty()) {
                    item("recent-title") { PickerSectionTitle(stringResource(R.string.picker_recent), Modifier.animateItem(fadeInSpec = fade, placementSpec = null, fadeOutSpec = null)) }
                    items(recent, key = { "recent:" + it.id }) { p -> PlaylistRow(p, provider, p.id == selectedId, Modifier.animateItem(fadeInSpec = fade, placementSpec = null, fadeOutSpec = null)) { onPick(p) } }
                    item("all-title") { PickerSectionTitle(stringResource(R.string.picker_all, shown.size), Modifier.animateItem(fadeInSpec = fade, placementSpec = null, fadeOutSpec = null)) }
                }
                if (found.isEmpty()) item("none") {
                    Text(
                        stringResource(if (q.isEmpty()) R.string.transfer_none else R.string.unmatched_no_results),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                items(found, key = { it.id }) { p -> PlaylistRow(p, provider, p.id == selectedId, Modifier.animateItem(fadeInSpec = fade, placementSpec = null, fadeOutSpec = null)) { onPick(p) } }
            }
        }
    }
}

@Composable
private fun PickerSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One row of the sheet: a picture, a name, a line under it, and a check on the one chosen. */
@Composable
internal fun PickerRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, selected: Boolean = false, thumb: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumb()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (selected) scheme.onSecondaryContainer else scheme.onSurface)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        }
        if (selected) Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PlaylistRow(p: Playlist, provider: MusicProvider?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // The heart is on the picture here, not in the name.
    val name = playlistDisplayName(p.id, p.name, provider?.id).removePrefix("\u2665 ")
    // What a list of a service without playlists is (Last.fm's loved, recent, most played).
    val note = p.description.takeIf { provider?.serviceId == com.xlollx.songport.providers.LastFmProvider.SERVICE && it.isNotBlank() }
    val count = p.trackCount.takeIf { it >= 0 }?.let { stringResource(R.string.tracks_count, it) }
    PickerRow(name, onClick, modifier, subtitle = listOfNotNull(note, count).joinToString("\n"), selected = selected) {
        PlaylistThumb(p.name.ifBlank { name }, p.id, 48.dp, imageUrl = p.imageUrl)
    }
}

/** A row of the list before the service answers. */
@Composable
private fun SkeletonPlaylistRow() {
    val loading = stringResource(R.string.loading)
    val grey = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(8.dp).semantics { contentDescription = loading }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(grey))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(CircleShape).background(grey))
            Box(Modifier.fillMaxWidth(0.3f).height(10.dp).clip(CircleShape).background(grey))
        }
    }
}

/** The playlists of [providerId] already in the user's syncs, the newest sync first: the picker's "Recent". */
internal fun recentPlaylistIds(ctx: android.content.Context, providerId: String?): List<String> {
    if (providerId == null) return emptyList()
    return ctx.env.store.data.jobs.asReversed().flatMap { listOf(it.source, it.target) }
        .filter { it.provider == providerId }.mapNotNull { it.playlistId }.distinct()
}

/**
 * A playlist chosen from a list the page already holds, where the service is chosen elsewhere on
 * the page: the field, and the sheet with the rows. [first]: rows of the page's own above the list.
 */
@Composable
internal fun PlaylistChoice(
    label: String, items: List<Playlist>?, selected: Playlist?, provider: MusicProvider?, onPick: (Playlist) -> Unit,
    modifier: Modifier = Modifier, placeholder: String = stringResource(R.string.editor_choose_playlist), title: String? = selected?.let { playlistDisplayName(it.id, it.name, provider?.id) },
    first: (LazyListScope.(close: () -> Unit) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    PlaylistField(
        label, title, placeholder, onClick = { open = true }, modifier = modifier,
        subtitle = if (selected != null) playlistWhere(provider, selected) else null, provider = provider.takeIf { selected != null },
        thumb = { PlaylistThumb(title, selected?.id, 48.dp, imageUrl = selected?.imageUrl) },
    )
    // The service is chosen on the page: one list, as high as it is long.
    if (open) PickerSheet(label, onDismiss = { open = false }, tall = false) {
        PlaylistRows(
            items, null, provider, selected?.id, onPick = { onPick(it); open = false },
            recentIds = remember(provider?.id) { recentPlaylistIds(ctx, provider?.id) },
            first = first?.let { f -> { f { open = false } } },
            fill = false,
        )
    }
}

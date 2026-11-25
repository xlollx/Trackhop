package com.xlollx.songport.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.SavedMatches
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Rows drawn at a time: a list of thousands stays quick, "Show more" adds the next ones. */
private const val PAGE = 60

/** Names read from the syncs' playlists, and the source songs each sync lists: kept for the process, read again after a while. */
internal object SavedMatchesKept {
    @Volatile var names: Map<String, Track> = emptyMap()
    @Volatile var members: Map<String, Set<String>> = emptyMap()
    @Volatile var readAt = 0L
    const val FRESH_MS = 10 * 60_000L
}

/** A connector of [family], a connected one first: for its logo, its name, its links and its search. */
internal fun providerOf(family: String, connected: Boolean = false, ctx: android.content.Context): MusicProvider? {
    val fam = family.substringBefore('#')
    return Providers.connectors().firstOrNull { FileStore.family(it.id) == fam && (!it.requiresAuth || it.isConnected(ctx.env)) }
        ?: if (connected) null else Providers.all().firstOrNull { FileStore.family(it.id) == fam }
}

private fun line(t: Track) = listOfNotNull(t.title, t.artistLine.ifBlank { null }).joinToString(" · ")

/**
 * Tools › Saved matches: two tabs. "To fix" (2.1, [ToFixTab]) lists what the syncs' last runs left
 * not found or matched loosely, with its count beside the name; "Saved" (1.18, [SavedTab]) is
 * everything Trackhop remembers.
 */
@Composable
fun SavedMatchesPage(snackbar: SnackbarHostState, onOpenFiles: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val toFix = remember(data.jobs, data.reports, data.toFixLater) { com.xlollx.songport.sync.ToFix.items(data) }
    TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.heightIn(min = 48.dp), text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.to_fix_tab), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ToFixCount(toFix.size)
            }
        })
        Tab(selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.heightIn(min = 48.dp), text = { Text(stringResource(R.string.to_fix_tab_saved), maxLines = 1, overflow = TextOverflow.Ellipsis) })
    }
    if (tab == 0) ToFixTab(toFix, snackbar, onOpenFiles) { tab = 1 } else SavedTab(snackbar)
}

/**
 * The Saved tab (1.18, [SavedMatches]): every song Trackhop remembers across the syncs, its
 * match on the other service, a song not found or one ignored. A row opens a sheet to listen, change,
 * mark it as not there or forget it: what is done there holds for every sync between the two services.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SavedTab(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<SavedMatches.Kind?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var kept by remember { mutableStateOf(SavedMatchesKept.names) }
    var reading by remember { mutableStateOf(false) }
    val entries = remember(data) { SavedMatches.entries(data) }
    val names = remember(data, kept) { SavedMatches.names(data) + kept }

    // The caches keep ids only: the syncs' own playlists name most of the songs, read once a while.
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - SavedMatchesKept.readAt < SavedMatchesKept.FRESH_MS) return@LaunchedEffect
        reading = true
        val n = HashMap(SavedMatchesKept.names)
        val members = HashMap<String, Set<String>>()
        for (j in data.syncs) for ((ref, isSource) in listOf(j.source to true, j.target to false)) {
            val id = ref.playlistId ?: continue
            val p = Providers.byId(ref.provider)?.takeIf { !it.requiresAuth || it.isConnected(ctx.env) } ?: continue
            val tracks = try { p.tracks(ctx.env, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
            val fam = FileStore.family(p.id)
            tracks.forEach { n[SavedMatches.nameKey(fam, it.id)] = it }
            if (isSource) members[j.id] = tracks.mapTo(HashSet()) { it.id }
            kept = HashMap(n)
        }
        SavedMatchesKept.names = n; SavedMatchesKept.members = members; SavedMatchesKept.readAt = System.currentTimeMillis()
        kept = n
        reading = false
    }

    val byTarget = entries.filter { target == null || it.targetService == target }
    val found = byTarget.filter { SavedMatches.matches(it, names, query) }
    val list = found.filter { kind == null || it.kind == kind }
    val num = remember { NumberFormat.getIntegerInstance() }

    OutlinedTextField(
        value = query, onValueChange = { query = it; shown = PAGE }, singleLine = true,
        label = { Text(stringResource(R.string.saved_matches_search)) }, modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            null to R.string.saved_matches_all, SavedMatches.Kind.CHOSEN to R.string.saved_matches_chosen,
            SavedMatches.Kind.NOT_FOUND to R.string.saved_matches_not_found, SavedMatches.Kind.IGNORED to R.string.saved_matches_ignored,
        ).forEach { (k, label) ->
            val count = found.count { k == null || it.kind == k }
            FilterChip(selected = kind == k, onClick = { kind = k; shown = PAGE }, label = { Text(stringResource(label) + "  " + num.format(count)) })
        }
        val services = entries.map { it.targetService }.distinct()
        if (services.size > 1 || target != null) Box {
            var menu by remember { mutableStateOf(false) }
            FilterChip(
                selected = target != null, onClick = { menu = true },
                label = { Text(target?.let { stringResource(R.string.saved_matches_to, providerOf(it, ctx = ctx)?.displayName ?: it) } ?: stringResource(R.string.saved_matches_to_all)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.saved_matches_all_services)) }, onClick = { target = null; menu = false; shown = PAGE })
                services.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(providerOf(s, ctx = ctx)?.displayName ?: s) }, leadingIcon = { ServiceLogo(providerOf(s, ctx = ctx), 20.dp) },
                        onClick = { target = s; menu = false; shown = PAGE },
                    )
                }
            }
        }
    }
    if (reading) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.saved_matches_reading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (entries.isEmpty()) { Text(stringResource(R.string.saved_matches_empty), style = MaterialTheme.typography.bodyMedium); return }
    if (list.isEmpty()) { Text(stringResource(R.string.saved_matches_no_results), style = MaterialTheme.typography.bodyMedium); return }
    PageCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            list.take(shown).forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                MatchRow(e, names) { openKey = e.key }
            }
        }
    }
    if (list.size > shown) TextButton(onClick = { shown += PAGE }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.saved_matches_more) + " · " + stringResource(R.string.preview_more, list.size - shown))
    }
    entries.firstOrNull { it.key == openKey }?.let { e -> MatchSheet(e, names, snackbar, onName = { k, t -> kept = kept + (k to t); SavedMatchesKept.names = SavedMatchesKept.names + (k to t) }) { openKey = null } }
}

/** One song: where it comes from, what it goes to, and how that was settled. */
@Composable
private fun MatchRow(e: SavedMatches.Entry, names: Map<String, Track>, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val src = names[SavedMatches.nameKey(e.source, e.sourceId)]
    val chosen = e.chosenId?.let { names[SavedMatches.nameKey(e.target, it)] }
    val srcP = providerOf(e.source, ctx = ctx)
    val dstP = providerOf(e.target, ctx = ctx)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ServiceLogo(srcP, 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(src?.let(::line) ?: stringResource(R.string.saved_matches_song_on, srcP?.displayName ?: e.source), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("→ ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ServiceLogo(dstP, 14.dp)
                Spacer(Modifier.width(4.dp))
                val sub = when (e.kind) {
                    SavedMatches.Kind.NOT_FOUND -> stringResource(R.string.saved_matches_none_found)
                    SavedMatches.Kind.IGNORED -> stringResource(R.string.saved_matches_ignored_line)
                    else -> chosen?.let(::line) ?: dstP?.displayName ?: e.target
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        KindPill(e.kind)
    }
}

@Composable
private fun KindPill(kind: SavedMatches.Kind) = when (kind) {
    SavedMatches.Kind.CHOSEN -> StatusPill(stringResource(R.string.saved_matches_tag_chosen), Tone.Accent)
    SavedMatches.Kind.AUTO -> StatusPill(stringResource(R.string.saved_matches_tag_auto), Tone.Neutral)
    SavedMatches.Kind.NOT_FOUND -> StatusPill(stringResource(R.string.saved_matches_tag_not_found), Tone.Warning)
    SavedMatches.Kind.IGNORED -> StatusPill(stringResource(R.string.saved_matches_tag_ignored), Tone.Neutral)
}

/**
 * The song and its match, and what can be done with them. "Change song…" is the review's own search
 * ([MatchSearch]); every change is made in the caches all syncs share ([SavedMatches]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchSheet(e: SavedMatches.Entry, names: Map<String, Track>, snackbar: SnackbarHostState, onName: (String, Track) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var changing by rememberSaveable(e.key) { mutableStateOf(false) }
    val srcP = providerOf(e.source, ctx = ctx)
    val dstP = providerOf(e.target, ctx = ctx)
    val dstConnected = providerOf(e.target, connected = true, ctx = ctx)
    val dstName = dstP?.displayName ?: e.targetService
    val srcKey = SavedMatches.nameKey(e.source, e.sourceId)
    val dstKey = e.chosenId?.let { SavedMatches.nameKey(e.target, it) }
    val src = names[srcKey]
    val chosen = dstKey?.let { names[it] }
    // A song no report or sync names: asked of its service once, for this sheet and the list.
    LaunchedEffect(e.key, e.chosenId) {
        if (src == null) providerOf(e.source, connected = true, ctx = ctx)?.let { p -> runCatching { p.track(ctx.env, e.sourceId) }.getOrNull()?.let { onName(srcKey, it) } }
        if (chosen == null && dstKey != null) dstConnected?.let { p -> runCatching { p.track(ctx.env, e.chosenId!!) }.getOrNull()?.let { onName(dstKey, it) } }
    }
    fun say(res: Int, vararg args: Any) { val text = ctx.getString(res, *args); scope.launch { snackbar.showSnackbar(text) } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Side(srcP, src?.let(::line) ?: stringResource(R.string.saved_matches_song_on, srcP?.displayName ?: e.source),
                listOfNotNull(stringResource(R.string.saved_matches_on, srcP?.displayName ?: e.source), src?.durationMs?.let { Durations.format(it).ifBlank { null } }).joinToString(" · "))
            Text(stringResource(R.string.saved_matches_matched_to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp))
            val when_ = remember(e.at) { if (e.at > 0) formatDate(e.at) else null }
            Side(
                dstP,
                when (e.kind) {
                    SavedMatches.Kind.NOT_FOUND -> stringResource(R.string.saved_matches_none_found)
                    SavedMatches.Kind.IGNORED -> stringResource(R.string.saved_matches_ignored_line)
                    else -> chosen?.let(::line) ?: stringResource(R.string.saved_matches_song_on, dstName)
                },
                listOfNotNull(
                    stringResource(R.string.saved_matches_on, dstName), chosen?.durationMs?.let { Durations.format(it).ifBlank { null } },
                    if (e.kind == SavedMatches.Kind.CHOSEN && when_ != null) stringResource(R.string.saved_matches_chosen_on, when_) else null,
                ).joinToString(" · "),
            )
            Spacer(Modifier.width(4.dp))
            if (changing && dstConnected != null) {
                val kindOfRow = e.target.substringAfter('#', "")
                val source = (src ?: Track(id = e.sourceId, title = "")).copy(kind = kindOfRow)
                MatchSearch(
                    initialQuery = src?.let { if (it.artists.isEmpty()) it.title else "${it.artistLine} - ${it.title}" }.orEmpty(),
                    targetName = dstName, pickLabel = stringResource(R.string.review_use_this),
                    search = { q -> SyncEngine(ctx).searchOn(dstConnected, null, MatchPolicy(), q, source.takeIf { src != null } ?: source.copy(title = q)) },
                    onPick = { t ->
                        SavedMatches.choose(store, e, t.id)
                        onName(SavedMatches.nameKey(e.target, t.id), t)
                        changing = false
                        say(R.string.saved_matches_changed)
                    },
                    onError = { m -> scope.launch { snackbar.showSnackbar(m) } }, autoSearch = src != null,
                    webSearchUrl = { q -> dstConnected.webSearchUrl(ctx.env, q) }, openTrack = { t -> dstConnected.trackUrl(ctx.env, t.id) },
                    extraActions = { TextButton(onClick = { changing = false }) { Text(stringResource(R.string.cancel)) } },
                )
            } else {
                val url = e.chosenId?.let { dstP?.trackUrl(ctx.env, it) }
                if (url != null) PrefRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.saved_matches_listen, dstName), chevron = false) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                if (dstConnected != null) PrefRow(Icons.Filled.SwapHoriz, stringResource(R.string.saved_matches_change), chevron = false) { changing = true }
                if (e.kind != SavedMatches.Kind.IGNORED) PrefRow(Icons.Filled.Block, stringResource(R.string.saved_matches_not_there, dstName), chevron = false) {
                    SavedMatches.notThere(store, e)
                    say(R.string.saved_matches_marked_absent, dstName)
                }
                PrefRow(Icons.Filled.DeleteOutline, stringResource(R.string.saved_matches_forget), danger = true, chevron = false) {
                    SavedMatches.forget(store, e)
                    say(R.string.saved_matches_forgotten)
                    onDismiss()
                }
            }
            // Which syncs it reaches: those between the two services whose source lists the song (all of them until their playlists are read).
            val used = SavedMatches.syncsBetween(data, e).filter { SavedMatchesKept.members[it.id]?.contains(e.sourceId) ?: true }
            Text(
                if (used.isEmpty()) stringResource(R.string.saved_matches_used_none)
                else pluralStringResource(R.plurals.saved_matches_used_by, used.size, used.size, used.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Side(p: MusicProvider?, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ServiceLogo(p, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

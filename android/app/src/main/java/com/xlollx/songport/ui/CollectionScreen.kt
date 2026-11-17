package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Collections
import com.xlollx.songport.shared.GroupState
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.NtfyServer
import com.xlollx.songport.shared.SharedCollection
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.shared.SharedTransports
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.ServerLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collections of shared playlists (shared/Collections): one card for the whole collection, its
 * page with a switch per playlist, making one, and the choice a follower makes on the way in.
 * The playlists' own syncs never show as cards: they run and are changed through the collection.
 */
object CollectionUi {
    /** What the last change to a collection left to say: what could not be set up, with why, and what the curator took out. */
    data class Said(val failed: List<Pair<String, String>> = emptyList(), val error: String? = null, val removed: List<String> = emptyList())

    /** The collections being set up now, by id. */
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val said = MutableStateFlow<Map<String, Said>>(emptyMap())

    /**
     * Changes to a collection run here, not in a screen's scope: leaving the page (or the activity
     * made anew) does not stop one half way. Should the process end, the next one carries on from
     * what is stored (see [Collections.settle]).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The syncs of every collection's playlists: behind the collection's card, not cards themselves. */
    fun memberJobIds(d: StoreData): Set<String> {
        if (d.collections.isEmpty()) return emptySet()
        val collections = d.collections.mapTo(HashSet()) { it.id }
        val ids = d.groups.filter { it.collectionId in collections }.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return emptySet()
        return d.jobs.filter { SharedGroups.sharedSide(it) in ids }.mapTo(HashSet()) { it.id }
    }

    /** The schedules brought in line with what a change did, and the new syncs run for the first time. */
    fun apply(ctx: android.content.Context, s: Collections.Settled) {
        val store = ctx.env.store
        s.gone.forEach { Scheduler.cancel(ctx, it) }
        (s.started + s.changed).forEach { id -> store.job(id)?.let { Scheduler.apply(ctx, it) } }
        if (s.started.isNotEmpty()) Scheduler.runInSequence(ctx, s.started)
    }

    /** One change to the collection [id], then what it left to say; a second one asked while it runs waits its turn (the collection's lock). */
    fun run(ctx: android.content.Context, id: String, after: (Collections.Settled) -> Unit = {}, block: suspend () -> Collections.Settled) {
        val app = ctx.applicationContext
        busy.update { it + id }
        scope.launch {
            val r = runCatching { block() }
            r.onSuccess { s ->
                apply(app, s)
                said.update { it + (id to Said(s.failed, null, s.removed)) }
                after(s)
            }.onFailure { e -> said.update { it + (id to Said(error = e.userMessage(app))) } }
            busy.update { it - id }
        }
    }

    /** "Sync now": the list is read for news first (new playlists, ones taken out), then every playlist that is on runs. */
    fun sync(ctx: android.content.Context, id: String) {
        val app = ctx.applicationContext
        run(app, id, after = { s ->
            val env = app.env
            Collections.get(env, id)?.let { c -> Scheduler.runInSequence(app, Collections.order(env, c).filter { it !in s.started }) }
        }) { Collections.settle(app.env, id) }
    }

    fun leave(ctx: android.content.Context, id: String, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        busy.update { it + id }
        scope.launch {
            runCatching { Collections.leave(app.env, id) }.getOrNull()?.forEach { Scheduler.cancel(app, it) }
            busy.update { it - id }
            said.update { it - id }
            withContext(Dispatchers.Main) { done() }
        }
    }

    /** A new collection, made off the screen's scope: the answer is the collection (made, even when some playlists are still to share) or the error. */
    suspend fun create(ctx: android.content.Context, block: suspend () -> Pair<SharedCollection, Collections.Settled>): Result<SharedCollection> {
        val app = ctx.applicationContext
        return scope.async {
            runCatching {
                val (c, s) = block()
                apply(app, s)
                said.update { it + (c.id to Said(s.failed)) }
                c
            }
        }.await()
    }

    /**
     * Before a background run of [jobs]: a collection one of them belongs to has its list read when
     * that is due, so playlists added since arrive (with "All"), ones taken out stop, and a setting
     * up that stopped half way carries on. A list that cannot be read changes nothing.
     */
    suspend fun check(ctx: android.content.Context, jobs: List<SyncJob>) {
        val env = ctx.env
        val groups = jobs.mapNotNull { SharedGroups.sharedSide(it) }.toSet()
        if (groups.isEmpty()) return
        val ids = env.store.data.groups.filter { it.id in groups && it.collectionId.isNotBlank() }.map { it.collectionId }.distinct()
        for (c in ids.mapNotNull { Collections.get(env, it) }.filter { Collections.due(it) }) {
            runCatching { Collections.settle(env, c.id) }
                .onSuccess { s -> apply(ctx, s); said.update { it + (c.id to Said(s.failed, null, s.removed)) } }
                .onFailure { env.log("shared", "${c.name}: the collection's list was not read: ${it.message?.take(120)}") }
        }
    }

    /** At the start of the process: a leave the app closed on is finished, and its schedules go. */
    fun recover(ctx: android.content.Context) {
        runCatching { Collections.recover(ctx.env) }.getOrNull()?.forEach { Scheduler.cancel(ctx, it) }
    }
}

private class CollectionStatus(val last: Long, val failed: Boolean, val notFound: Int)

private fun statusOf(d: StoreData, c: SharedCollection): CollectionStatus {
    val groups = d.groups.filter { it.collectionId == c.id }.mapTo(HashSet()) { it.id }
    val jobs = d.jobs.filter { SharedGroups.sharedSide(it) in groups }
    val reports = jobs.mapNotNull { j -> d.reports.firstOrNull { it.id == j.lastReportId } }
    return CollectionStatus(jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L, reports.any { !it.ok }, reports.sumOf { it.unmatchedTracks.size })
}

/** The collection's list as this device holds it, read off the main thread and again after each change. */
@Composable
private fun listState(c: SharedCollection, vararg keys: Any?): GroupState? {
    val env = LocalContext.current.env
    val s by produceState<GroupState?>(null, c.id, c.checkedAt, c.on, c.todo.size, *keys) {
        value = withContext(Dispatchers.IO) { runCatching { SharedGroups.load(env, c.id) }.getOrNull() }
    }
    return s
}

/** A collection's one card, in Syncs and among the shared playlists: how many playlists are on, where, the last run, and its two buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CollectionCard(c: SharedCollection, data: StoreData, running: Set<String>, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val status = remember(data.jobs, data.reports, data.groups, c.id) { statusOf(data, c) }
    val busyIds by CollectionUi.busy.collectAsState()
    val jobs = remember(data.jobs, data.groups, c.id) { CollectionUi.memberJobIds(data.copy(collections = listOf(c))) }
    val busy = c.id in busyIds || jobs.any { it in running }
    val state = listState(c, status.last)
    val entries = remember(state, c) { state?.let { Collections.entries(c, it) } }
    val rows = remember(entries, data.jobs, c) { entries?.let { Collections.rows(ctx.env, c, it) } }
    val proposals = entries?.let { Collections.proposals(c, it).size } ?: 0
    val waiting = (rows?.count { it.pending } ?: 0) + c.todo.size
    val dark = LocalAppDark.current
    Card(
        onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LibraryMusic, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(
                listOfNotNull(
                    if (!c.chosen) stringResource(R.string.collection_choose_pending)
                    else rows?.let { r -> stringResource(R.string.collection_n_of_m, r.count { it.on }, r.size) },
                    if (status.last > 0) stringResource(R.string.pill_last_run, relativeTime(status.last)) else stringResource(R.string.last_run_never),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.services.forEach { ServiceLogo(Providers.byId(it), 28.dp) }
                StatusPill(
                    stringResource(if (c.createdHere) R.string.shared_role_curator else R.string.shared_role_follower),
                    if (c.createdHere) Tone.Accent else Tone.Neutral, modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            if (proposals > 0 || waiting > 0 || status.failed || status.notFound > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (proposals > 0) StatusPill(pluralStringResource(R.plurals.collection_new_n, proposals, proposals), Tone.Accent)
                if (waiting > 0) StatusPill(pluralStringResource(R.plurals.collection_pending_n, waiting, waiting), Tone.Warning, Icons.Filled.Warning)
                if (status.notFound > 0) StatusPill(stringResource(R.string.pill_not_found, status.notFound), Tone.Warning)
                if (status.failed) StatusPill(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_open)) }
                Button(onClick = { CollectionUi.sync(ctx, c.id) }, enabled = !busy && c.chosen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (busy) R.string.running else R.string.shared_sync_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A playlist of a collection in a list: its mark, its name, how many tracks, and what the row ends with (a tick box, a switch, a menu). */
@Composable
private fun PlaylistLine(name: String, summary: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

/** "All" or "Pick", as the two halves of one control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChoice(mode: String, enabled: Boolean = true, onMode: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        listOf(SharedCollection.ALL to R.string.collection_all, SharedCollection.PICK to R.string.collection_pick).forEachIndexed { i, (m, label) ->
            SegmentedButton(selected = mode == m, onClick = { if (mode != m) onMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, 2), enabled = enabled) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean, action: String? = null, enabled: Boolean = true, onAction: () -> Unit = {}) {
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error) Icon(Icons.Filled.Warning, null, tint = fg)
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (action != null) FilledTonalButton(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

/**
 * A collection's page. Joined and nothing chosen yet: the choice (all or some, where, how). Then:
 * what is on, what is new, a switch per playlist and, for its curator, the invite and its playlists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen(id: String, onClose: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val data by Store.get(ctx).state.collectAsState()
    val running by com.xlollx.songport.sync.SyncState.running.collectAsState()
    val busyIds by CollectionUi.busy.collectAsState()
    val saidAll by CollectionUi.said.collectAsState()
    val c = data.collections.firstOrNull { it.id == id && !it.leaving }
    // Left (here, or finished after a stop): nothing to show.
    LaunchedEffect(c == null) { if (c == null) onClose() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    // Before the choice nothing was made on any service: going back is not joining, asked first.
    val back = { if (c != null && !c.chosen) confirmLeave = true else onClose() }
    ScreenBackHandler(back)
    if (c == null) return
    val busy = id in busyIds
    val said = saidAll[id]
    val state = listState(c, busy)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state == null) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
            else if (!c.chosen) CollectionChoice(c, state, busy, said)
            else CollectionPage(c, state, data, busy || CollectionUi.memberJobIds(data.copy(collections = listOf(c))).any { it in running.keys }, said, onOpenPlaylist) { confirmLeave = true }
        }
    }
    if (confirmLeave) FitDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(if (c.chosen) R.string.collection_leave else R.string.collection_not_join)) },
        text = { Text(stringResource(if (c.chosen) R.string.collection_leave_confirm else R.string.collection_not_join_note)) },
        confirmButton = {
            TextButton(onClick = { confirmLeave = false; CollectionUi.leave(ctx, id) }) {
                Text(stringResource(if (c.chosen) R.string.collection_leave else R.string.collection_not_join), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The follower's choice on the way in: all the playlists or some, on which services, keeping their own additions or not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionChoice(c: SharedCollection, state: GroupState, busy: Boolean, said: CollectionUi.Said?) {
    val ctx = LocalContext.current
    val env = ctx.env
    val entries = remember(state, c) { Collections.entries(c, state) }
    val services = remember { writableServices(ctx, needsCreate = true) }
    var mode by rememberSaveable { mutableStateOf(SharedCollection.ALL) }
    var picked by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var chosenServices by rememberSaveable { mutableStateOf<List<String>>(ArrayList(listOfNotNull(services.firstOrNull()?.id))) }
    var keepMine by rememberSaveable { mutableStateOf(true) }
    val wanted = if (mode == SharedCollection.ALL) entries else entries.filter { it.groupId in picked }
    // The member's playlists there, by name: one of the same name is used, not made again.
    val mine by produceState<Map<String, Set<String>>>(emptyMap(), chosenServices) {
        value = withContext(Dispatchers.IO) {
            chosenServices.associateWith { s ->
                runCatching { Providers.byId(s)!!.playlists(env).filter { it.ownedByMe }.mapTo(HashSet()) { it.name.trim().lowercase() } }.getOrDefault(emptySet())
            }
        }
    }
    Text(
        listOf(pluralStringResource(R.plurals.collection_playlists_n, entries.size, entries.size), transportLabel(c.list.transport)).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
    )
    said?.error?.let { Notice(it, error = true) }
    if (entries.isEmpty()) Notice(stringResource(R.string.collection_empty), error = false)
    ModeChoice(mode, !busy) { mode = it; if (it == SharedCollection.PICK && picked.isEmpty()) picked = ArrayList(entries.map { e -> e.groupId }) }
    Column {
        for (e in entries) {
            val on = mode == SharedCollection.ALL || e.groupId in picked
            PlaylistLine(
                e.name, stringResource(R.string.tracks_count, e.tracks),
                modifier = if (mode == SharedCollection.PICK) Modifier.toggleable(on, enabled = !busy, role = Role.Checkbox) {
                    picked = ArrayList(if (it) picked + e.groupId else picked - e.groupId)
                } else Modifier,
                leading = if (mode == SharedCollection.PICK) { { Checkbox(on, null) } } else null,
            )
        }
    }
    Text(
        if (mode == SharedCollection.ALL) stringResource(R.string.collection_all_note) else stringResource(R.string.collection_pick_note, wanted.size, entries.size),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
    )
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.collection_where), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (services.isEmpty()) Text(stringResource(R.string.shared_no_services), color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in services) {
                val on = p.id in chosenServices
                FilterChip(
                    selected = on, enabled = !busy,
                    onClick = { chosenServices = ArrayList(if (on) chosenServices - p.id else chosenServices + p.id) },
                    label = { Text(p.label(env)) },
                    leadingIcon = { if (on) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else ServiceLogo(p, 18.dp) },
                )
            }
        }
        if (chosenServices.size > 1) Text(stringResource(R.string.collection_where_several), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.collection_when_follow), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
        for ((keep, title, desc) in listOf(
            Triple(true, R.string.shared_mode_keep, R.string.collection_keep_desc), Triple(false, R.string.shared_mode_exact, R.string.collection_exact_desc),
        )) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(keepMine == keep, enabled = !busy, role = Role.RadioButton) { keepMine = keep }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(keepMine == keep, null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // What joining makes: new playlists, and the ones of the same name that are used as they are.
    if (wanted.isNotEmpty() && chosenServices.isNotEmpty()) {
        val lines = ArrayList<String>()
        for (s in chosenServices) {
            val name = Providers.byId(s)?.label(env) ?: s
            val there = mine[s].orEmpty()
            val (old, new) = wanted.partition { it.name.trim().lowercase() in there }
            if (new.isNotEmpty()) lines += pluralStringResource(R.plurals.collection_tip_new, new.size, new.size, name)
            old.take(3).forEach { lines += stringResource(R.string.collection_tip_exists, it.name, name) }
        }
        Notice(lines.joinToString("\n"), error = false)
    }
    Button(
        enabled = !busy && wanted.isNotEmpty() && chosenServices.isNotEmpty(),
        onClick = { CollectionUi.run(ctx, c.id) { Collections.choose(env, c.id, mode, chosenServices.toList(), keepMine, wanted.mapTo(HashSet()) { it.groupId }) } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
    ) {
        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(pluralStringResource(R.plurals.collection_join_n, wanted.size, wanted.size))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionPage(
    c: SharedCollection, state: GroupState, data: StoreData, busy: Boolean, said: CollectionUi.Said?,
    onOpenPlaylist: (String) -> Unit, onLeave: () -> Unit,
) {
    val ctx = LocalContext.current
    val env = ctx.env
    val entries = remember(state, c) { Collections.entries(c, state) }
    val rows = remember(entries, data.jobs, data.groups, c) { Collections.rows(env, c, entries) }
    val proposals = remember(entries, c) { Collections.proposals(c, entries) }
    val status = remember(data.jobs, data.reports, data.groups, c.id) { statusOf(data, c) }
    val waiting = rows.count { it.pending } + c.todo.size
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // Read for news once when the page opens: new playlists, ones taken out, a setting up that stopped.
    LaunchedEffect(c.id) { if (c.id !in CollectionUi.busy.value) CollectionUi.run(ctx, c.id) { Collections.settle(env, c.id) } }

    Button(onClick = { CollectionUi.sync(ctx, c.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
        Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(if (busy) R.string.running else R.string.shared_sync_now))
    }
    // What stopped half way is said, with the way to carry on: what was set up stays, nothing is made twice.
    said?.error?.let { Notice(it, error = true, action = stringResource(R.string.collection_continue), enabled = !busy) { CollectionUi.run(ctx, c.id) { Collections.settle(env, c.id) } } }
    if (waiting > 0 && said?.error == null) {
        val why = said?.failed?.firstOrNull()?.let { (name, reason) -> "\n$name: $reason" }.orEmpty()
        Notice(
            pluralStringResource(if (c.createdHere) R.plurals.collection_todo_n else R.plurals.collection_pending_n, waiting, waiting) + " " + stringResource(R.string.collection_stopped) + why,
            error = said?.failed?.isNotEmpty() == true, action = stringResource(R.string.collection_continue), enabled = !busy,
        ) { CollectionUi.run(ctx, c.id) { Collections.settle(env, c.id) } }
    }
    said?.removed?.takeIf { it.isNotEmpty() && !c.createdHere }?.let { Notice(stringResource(R.string.collection_removed_by_curator, it.joinToString(", ")), error = false) }

    val dark = LocalAppDark.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.collection_n_of_m, rows.count { it.on }, rows.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (status.last > 0) stringResource(R.string.pill_last_run, relativeTime(status.last)) else stringResource(R.string.last_run_never),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.services.forEach { s -> Providers.byId(s)?.let { p -> StatusPill(p.label(env), Tone.Neutral, leading = { ServiceLogo(p, 16.dp) }) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.createdHere) {
                    StatusPill(stringResource(R.string.shared_role_curator), Tone.Accent)
                    val n = Collections.followers(c, state)
                    StatusPill(pluralStringResource(R.plurals.collection_followers_n, n, n), Tone.Neutral, Icons.Filled.Groups)
                } else {
                    StatusPill(stringResource(R.string.shared_role_follower), Tone.Neutral)
                    StatusPill(stringResource(if (c.keepMine) R.string.shared_mode_keep else R.string.shared_mode_exact), Tone.Neutral)
                }
                if (status.failed) StatusPill(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
            }
        }
    }

    // A follower who picks: what the curator added since, each waiting for a yes or a no.
    val curator = remember(state, c) { SharedGroups.members(c.list, state).firstOrNull { it.creator }?.name.orEmpty() }
    for (p in proposals) Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (curator.isBlank()) stringResource(R.string.collection_new_playlist) else stringResource(R.string.collection_new_from, curator),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        PlaylistLine(p.name, stringResource(R.string.tracks_count, p.tracks))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { CollectionUi.run(ctx, c.id) { Collections.answer(env, c.id, p.groupId, false) } }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.collection_ignore))
            }
            Button(onClick = { CollectionUi.run(ctx, c.id) { Collections.answer(env, c.id, p.groupId, true) } }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.collection_add))
            }
        }
    }

    if (!c.createdHere) ModeChoice(c.mode, !busy) { m -> CollectionUi.run(ctx, c.id) { Collections.setMode(env, c.id, m) } }

    PrefSection(
        stringResource(R.string.collection_playlists), accent = JobColor.BLUE,
        description = if (c.createdHere) null else stringResource(R.string.collection_switch_note),
    ) {
        if (rows.isEmpty()) PrefBlock { Text(stringResource(R.string.collection_empty), style = MaterialTheme.typography.bodyMedium) }
        // A proposal not answered yet is above, not a row with a switch.
        for (r in rows.filter { row -> proposals.none { it.groupId == row.entry.groupId } }) {
            val jobs = data.jobs.filter { SharedGroups.sharedSide(it) == r.entry.groupId }
            val notFound = jobs.sumOf { j -> data.reports.firstOrNull { it.id == j.lastReportId }?.unmatchedTracks?.size ?: 0 }
            val failed = jobs.any { j -> data.reports.firstOrNull { it.id == j.lastReportId }?.ok == false }
            val summary = listOfNotNull(
                stringResource(R.string.tracks_count, r.entry.tracks),
                if (r.pending) stringResource(R.string.collection_row_pending) else null,
                if (failed) stringResource(R.string.pill_error) else null,
                if (notFound > 0) stringResource(R.string.pill_not_found, notFound) else null,
            ).joinToString(" · ")
            PlaylistLine(
                r.entry.name, summary,
                // Each playlist still has its own page (members, the log, its own choice).
                modifier = if (r.joined) Modifier.clickable(role = Role.Button) { onOpenPlaylist(r.entry.groupId) } else Modifier,
                trailing = {
                    if (c.createdHere) OverflowMenu(
                        listOf(MenuAction(stringResource(R.string.collection_remove_playlist), { removing = r.entry.groupId }, destructive = true)),
                        enabled = !busy, title = r.entry.name,
                    ) else Switch(
                        r.on, { on -> CollectionUi.run(ctx, c.id) { Collections.setOn(env, c.id, r.entry.groupId, on) } }, enabled = !busy,
                        // Said with the playlist's name: the row itself opens the playlist's page.
                        modifier = Modifier.semantics { contentDescription = r.entry.name },
                    )
                },
            )
        }
        if (c.createdHere) {
            PrefRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.collection_add_playlists), null, chevron = false, enabled = !busy) { adding = true }
            PrefSwitch(null, stringResource(R.string.collection_auto_add), stringResource(R.string.collection_auto_add_desc), c.autoAdd, enabled = !busy) { on ->
                CollectionUi.run(ctx, c.id) {
                    // From now on: the playlists there now are not "made later".
                    val seen = if (on) runCatching { Providers.byId(c.services.first())!!.playlists(env).mapTo(HashSet()) { it.id } }.getOrDefault(emptySet()) else emptySet()
                    Collections.setAutoAdd(env, c.id, on, seen)
                    Collections.Settled()
                }
            }
        }
    }

    if (c.createdHere) {
        val secret = c.list.transport != Invite.FOLDER
        val link = remember(c.list) { Collections.invite(env, c).link() }
        PrefSection(stringResource(R.string.shared_invite), accent = JobColor.PINK, description = stringResource(R.string.collection_invite_desc)) {
            PrefBlock(indent = false) { InviteBlock(link, Invite.codeOf(c.list.creator), secret) }
        }
    } else PrefSection(stringResource(R.string.shared_follow_mode), accent = JobColor.TEAL) {
        for ((keep, title, desc) in listOf(
            Triple(false, R.string.shared_mode_exact, R.string.collection_exact_desc), Triple(true, R.string.shared_mode_keep, R.string.collection_keep_desc),
        )) {
            val set = { CollectionUi.run(ctx, c.id) { Collections.setKeepMine(env, c.id, keep); Collections.Settled() } }
            PrefRow(null, stringResource(title), stringResource(desc), chevron = false, enabled = !busy, trailing = { RadioButton(selected = c.keepMine == keep, onClick = set, enabled = !busy) }) { set() }
        }
    }

    PrefSection(listOf(transportLabel(c.list.transport), c.list.folderName).filter { it.isNotBlank() }.joinToString(" · "), accent = JobColor.GREEN) {
        PrefRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.collection_leave), null, danger = true, chevron = false, enabled = !busy) { onLeave() }
    }

    removing?.let { gid ->
        FitDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.collection_remove_playlist)) },
            text = { Text(stringResource(R.string.collection_remove_playlist_confirm)) },
            confirmButton = {
                TextButton(onClick = { removing = null; CollectionUi.run(ctx, c.id) { Collections.remove(env, c.id, gid) } }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (adding) AddPlaylistsDialog(c, data, onDismiss = { adding = false }) { lists ->
        adding = false
        CollectionUi.run(ctx, c.id) { Collections.add(env, c.id, lists) }
    }
}

/** The creator's playlists not in the collection yet, to tick. */
@Composable
private fun AddPlaylistsDialog(c: SharedCollection, data: StoreData, onDismiss: () -> Unit, onAdd: (List<Playlist>) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    var picked by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    val shared = remember(data.jobs, data.groups, c) { Collections.jobs(env, c).mapNotNull { it.target.playlistId }.toSet() + c.todo.mapNotNull { it.playlist.playlistId } }
    val lists by produceState<List<Playlist>?>(null, c.id) {
        value = withContext(Dispatchers.IO) { runCatching { Providers.byId(c.services.first())!!.playlists(env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) } }.getOrDefault(emptyList()) }
    }
    val free = lists?.filter { it.id !in shared }
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collection_add_playlists)) },
        text = {
            Column {
                when {
                    free == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    free.isEmpty() -> Text(stringResource(R.string.collection_no_playlists))
                    else -> for (p in free) {
                        val on = p.id in picked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { picked = ArrayList(if (it) picked + p.id else picked - p.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(p.name)
                                if (p.trackCount >= 0) Text(stringResource(R.string.tracks_count, p.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onAdd(free.orEmpty().filter { it.id in picked }) }) { Text(stringResource(R.string.collection_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A new collection: its name, the creator's name, the service the playlists are on, the playlists
 * (ticked; all at first), whether later ones join by themselves, and how the collection meets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionCreateScreen(onClose: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val scope = rememberCoroutineScope()
    val services = remember { writableServices(ctx, needsCreate = false) }
    var name by rememberSaveable { mutableStateOf("") }
    var myName by rememberSaveable { mutableStateOf("") }
    var serviceId by rememberSaveable { mutableStateOf(services.firstOrNull()?.id) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    // The playlists left out: all are in until one is unticked (also the ones a slow listing brings later).
    var left by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var autoAdd by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var transport by rememberSaveable { mutableStateOf(Invite.FOLDER) }
    var server by rememberSaveable { mutableStateOf(DEFAULT_NTFY_SERVER) }
    val signIn = remember { NtfySignIn() }
    val myServer = remember { ServerLocks.config(env)?.address }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberFolderPicker { uri, n -> folder = uri.toString(); folderName = n }
    val service = serviceId?.let { Providers.byId(it) }
    LaunchedEffect(serviceId) {
        lists = null
        val p = service ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) { runCatching { p.playlists(env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) } }.getOrDefault(emptyList()) }
    }
    val all = lists.orEmpty()
    val chosen = all.filter { it.id !in left }.take(Collections.MAX_PLAYLISTS)
    val shown = if (query.isBlank()) all else all.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val missing = listOfNotNull(
        stringResource(R.string.collection_name).takeIf { name.isBlank() },
        stringResource(R.string.shared_your_name).takeIf { myName.isBlank() },
        stringResource(R.string.shared_service).takeIf { service == null },
        stringResource(R.string.collection_playlists).takeIf { service != null && lists != null && chosen.isEmpty() },
        when (transport) {
            Invite.NTFY -> when {
                !NtfyServer.valid(server) -> stringResource(R.string.shared_ntfy_server)
                !signIn.ready -> stringResource(R.string.shared_ntfy_signin_change)
                else -> null
            }
            Invite.SERVER -> null
            else -> stringResource(R.string.shared_folder).takeIf { folder == null }
        },
    )
    val close = rememberDiscardGuard(name.isNotBlank() || myName.isNotBlank() || left.isNotEmpty() || folder != null || transport != Invite.FOLDER || signIn.typed, onClose = onClose)
    val back = { if (!busy) close() }
    ScreenBackHandler(back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collection_new)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (services.isEmpty()) Text(stringResource(R.string.shared_no_services), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it.take(Invite.MAX_NAME) }, label = { Text(stringResource(R.string.collection_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(myName, { myName = it.take(40) }, label = { Text(stringResource(R.string.shared_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ServiceDropdown(services, service) { if (it != serviceId) { serviceId = it; left = ArrayList(); query = "" } }
                if (all.size > 8) OutlinedTextField(
                    query, { query = it.take(60) }, placeholder = { Text(stringResource(R.string.collection_search)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                service == null -> {}
                lists == null -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp))
                all.isEmpty() -> Text(stringResource(R.string.collection_no_playlists), modifier = Modifier.padding(horizontal = 16.dp))
                else -> Column {
                    val allOn = left.isEmpty()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(allOn, enabled = !busy, role = Role.Checkbox) { on -> left = ArrayList(if (on) emptyList() else all.map { it.id }) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(allOn, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.collection_all), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.collection_chosen_n, chosen.size, all.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (p in shown) {
                        val on = p.id !in left
                        PlaylistLine(
                            p.name, if (p.trackCount >= 0) stringResource(R.string.tracks_count, p.trackCount) else "",
                            modifier = Modifier.toggleable(on, enabled = !busy, role = Role.Checkbox) { left = ArrayList(if (it) left - p.id else left + p.id) },
                            leading = { Checkbox(on, null) },
                        )
                    }
                }
            }
            PrefSwitch(null, stringResource(R.string.collection_auto_add), stringResource(R.string.collection_auto_add_desc), autoAdd, enabled = !busy) { autoAdd = it }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TransportChoice(transport, { transport = it }, server, { server = it }, myServer?.let { NtfyServer.hostOf(it) }, signIn) {
                    FolderPicker(folderName) { pick.launch(null) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!busy && missing.isNotEmpty()) Text(
                    stringResource(R.string.shared_missing, missing.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy && missing.isEmpty() && chosen.isNotEmpty() && (transport != Invite.SERVER || myServer != null),
                    onClick = {
                        val p = service ?: return@Button
                        busy = true; error = null
                        scope.launch {
                            val made = CollectionUi.create(ctx) {
                                val seen = all.mapTo(HashSet()) { it.id }
                                when (transport) {
                                    Invite.NTFY -> Collections.create(env, name, myName, p.id, chosen, server, NtfyServer.hostOf(server), Invite.NTFY, signIn.auth, autoAdd, seen)
                                    Invite.SERVER -> Collections.create(env, name, myName, p.id, chosen, myServer!!, NtfyServer.hostOf(myServer), Invite.SERVER, null, autoAdd, seen)
                                    else -> Collections.create(env, name, myName, p.id, chosen, folder!!, folderName, Invite.FOLDER, null, autoAdd, seen)
                                }
                            }
                            busy = false
                            made.onSuccess { onDone(it.id) }.onFailure { error = it.userMessage(ctx) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(pluralStringResource(R.plurals.collection_create_n, chosen.size, chosen.size))
                }
            }
        }
    }
}

/** "Create": one playlist as ever, or several together. */
@Composable
internal fun CreateKindDialog(onDismiss: () -> Unit, onOne: () -> Unit, onSeveral: () -> Unit) {
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_create)) },
        text = {
            Column {
                PrefRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.collection_one), stringResource(R.string.collection_one_desc)) { onOne() }
                PrefRow(Icons.Filled.LibraryMusic, stringResource(R.string.collection_several), stringResource(R.string.collection_several_desc)) { onSeveral() }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

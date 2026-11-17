package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.GroupLog
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.sync.Labels
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One playlist on several services (shared/Nway): making a group, its one card in the Syncs list,
 * and its page. The group's own syncs never show as cards: they run and are changed through it.
 */
object NwayUi {
    /** What MainActivity keeps as the open page: this for a new group, else a group's id. */
    const val NEW = ""

    fun groups(d: StoreData): List<SharedGroup> = d.groups.filter { Nway.isGroup(it) }

    /** The syncs that belong to a group of the user's own playlists: behind the group's card, not cards themselves. */
    fun memberJobIds(d: StoreData): Set<String> {
        // So are the syncs of a collection's playlists: behind the collection's one card (CollectionUi).
        val collected = CollectionUi.memberJobIds(d)
        val ids = groups(d).mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return collected
        return d.jobs.filter { SharedGroups.sharedSide(it) in ids }.mapTo(HashSet()) { it.id } + collected
    }

    /** [Labels.storeIndex] for a place among the syncs the list shows, as a place in the stored list, which holds the groups' syncs too. */
    fun storeIndex(d: StoreData, id: String, shownIndex: Int): Int {
        val hidden = memberJobIds(d)
        val shown = d.jobs.filter { it.id !in hidden }
        val rest = shown.filter { it.id != id }
        val at = Labels.storeIndex(shown, id, shownIndex, d.settings.favoritesOnTop)
        return rest.getOrNull(at)?.let { next -> d.jobs.filter { it.id != id }.indexOfFirst { it.id == next.id } } ?: d.jobs.size
    }

    /** "Sync now" on a group: its syncs in the group's order, one after the other. */
    fun run(ctx: android.content.Context, group: SharedGroup) = Scheduler.runInSequence(ctx, Nway.order(ctx.env, group).map { it.id })
}

/** A playlist of a group being made: [playlistId] null is a new one, made with the group and named after it. */
internal data class NwayDraft(
    val provider: String, val playlistId: String?, val name: String, val count: Int, val main: Boolean, val keepMine: Boolean = true,
)

private const val SEP = "\u001f"

private val DraftsSaver = listSaver<List<NwayDraft>, String>(
    save = { list -> list.map { listOf(it.provider, it.playlistId ?: SEP, it.name, it.count, it.main, it.keepMine).joinToString("\n") } },
    restore = { saved ->
        saved.mapNotNull { line ->
            val f = line.split("\n")
            if (f.size != 6) null else NwayDraft(f[0], f[1].takeIf { it != SEP }, f[2], f[3].toIntOrNull() ?: -1, f[4] == "true", f[5] == "true")
        }
    },
)

@Composable
private fun roleLabel(main: Boolean) = stringResource(if (main) R.string.nway_role_main else R.string.nway_role_follows)

/** Main or follows and, for one that follows, whether it keeps what the user adds there. */
@Composable
private fun RoleChoice(main: Boolean, keepMine: Boolean, onMain: (Boolean) -> Unit, onKeep: (Boolean) -> Unit) {
    Column {
        PrefRow(null, stringResource(R.string.nway_role_main), stringResource(R.string.nway_role_main_desc), chevron = false,
            trailing = { RadioButton(selected = main, onClick = { onMain(true) }) }) { onMain(true) }
        PrefRow(null, stringResource(R.string.nway_role_follows), stringResource(R.string.nway_role_follows_desc), chevron = false,
            trailing = { RadioButton(selected = !main, onClick = { onMain(false) }) }) { onMain(false) }
        if (!main) {
            Text(
                stringResource(R.string.nway_follow_mode), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() },
            )
            PrefRow(null, stringResource(R.string.shared_mode_keep), stringResource(R.string.nway_keep_desc), chevron = false,
                trailing = { RadioButton(selected = keepMine, onClick = { onKeep(true) }) }) { onKeep(true) }
            PrefRow(null, stringResource(R.string.shared_mode_exact), stringResource(R.string.nway_exact_desc), chevron = false,
                trailing = { RadioButton(selected = !keepMine, onClick = { onKeep(false) }) }) { onKeep(false) }
        }
    }
}

/** A playlist for the group: the service, one of its playlists or a new one, and its role. [taken]: the playlists already in. */
@Composable
internal fun NwayAddDialog(taken: List<Pair<String, String?>>, onDismiss: () -> Unit, onAdd: (NwayDraft) -> Unit) {
    val ctx = LocalContext.current
    val services = remember { writableServices(ctx, needsCreate = false) }
    var serviceId by rememberSaveable { mutableStateOf(services.firstOrNull()?.id) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var playlistCount by rememberSaveable { mutableStateOf(-1) }
    var makeNew by rememberSaveable { mutableStateOf(false) }
    var main by rememberSaveable { mutableStateOf(true) }
    var keepMine by rememberSaveable { mutableStateOf(true) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    val service = serviceId?.let { Providers.byId(it) }
    LaunchedEffect(serviceId) {
        lists = null
        val p = service ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) { runCatching { p.playlists(ctx.env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) } }.getOrDefault(emptyList()) }
    }
    val chosen = makeNew || playlistId != null
    val already = !makeNew && playlistId != null && taken.any { it.first == serviceId && it.second == playlistId }
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nway_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (services.isEmpty()) Text(stringResource(R.string.shared_no_services), color = MaterialTheme.colorScheme.error)
                ServiceDropdown(services, service) { if (it != serviceId) { serviceId = it; playlistId = null; playlistName = ""; makeNew = false } }
                if (service != null) PlaylistDropdown(
                    lists, playlistId?.let { Playlist(it, playlistName, playlistCount) }, makeNew, service.canCreatePlaylists,
                    // A new, empty playlist has nothing to give the others: it follows unless told otherwise.
                    onNew = { makeNew = true; playlistId = null; playlistName = ""; main = false },
                ) { makeNew = false; playlistId = it.id; playlistName = it.name; playlistCount = it.trackCount }
                if (already) Text(stringResource(R.string.nway_already), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                RoleChoice(main, keepMine, { main = it }, { keepMine = it })
            }
        },
        confirmButton = {
            TextButton(enabled = service != null && chosen && !already, onClick = {
                onAdd(NwayDraft(service!!.id, if (makeNew) null else playlistId, if (makeNew) "" else playlistName, if (makeNew) 0 else playlistCount, main, keepMine))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** New sync › One playlist on several services: the name, the playlists and their roles, when it runs; then what the first run does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NwayCreateScreen(onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var members by rememberSaveable(stateSaver = DraftsSaver) { mutableStateOf(emptyList<NwayDraft>()) }
    var schedule by rememberSaveable { mutableStateOf(Schedule.HOURLY) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(-1) }
    var pickSchedule by rememberSaveable { mutableStateOf(false) }
    var firstRun by rememberSaveable { mutableStateOf(false) }
    val close = rememberDiscardGuard(name.isNotBlank() || members.isNotEmpty(), onClose = onClose)
    ScreenBackHandler { close() }
    val missing = when {
        name.isBlank() -> stringResource(R.string.shared_missing, stringResource(R.string.nway_name))
        members.size < 2 -> stringResource(R.string.nway_need_two)
        members.none { it.main } -> stringResource(R.string.nway_need_main)
        else -> null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nway_title)) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    missing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = { firstRun = true }, enabled = missing == null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.preview)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                name, { name = it.take(Invite.MAX_NAME) }, label = { Text(stringResource(R.string.nway_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            PrefSection(stringResource(R.string.nway_members), accent = JobColor.BLUE) {
                members.forEachIndexed { i, m ->
                    val p = Providers.byId(m.provider)
                    val service = p?.label(ctx.env) ?: m.provider
                    NwayRow(
                        p, if (m.playlistId == null) stringResource(R.string.nway_new_named, name.ifBlank { stringResource(R.string.nway_name) }) else m.name,
                        if (m.playlistId == null) stringResource(R.string.nway_to_create, service)
                        else if (m.count >= 0) service + " · " + stringResource(R.string.tracks_count, m.count) else service,
                        m.main, onClick = { editing = i },
                    )
                }
                PrefRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.nway_add), null, chevron = false) { adding = true }
            }
            Text(
                stringResource(R.string.nway_role_main) + ": " + stringResource(R.string.nway_role_main_desc) + "\n" +
                    stringResource(R.string.nway_role_follows) + ": " + stringResource(R.string.nway_role_follows_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
            )
            PrefSection(stringResource(R.string.editor_schedule), accent = JobColor.TEAL) {
                PrefRow(Icons.Filled.Schedule, scheduleLabel(schedule), null) { pickSchedule = true }
            }
        }
    }
    if (adding) NwayAddDialog(members.map { it.provider to it.playlistId }, onDismiss = { adding = false }) { members = members + it; adding = false }
    members.getOrNull(editing)?.let { m ->
        FitDialog(
            onDismissRequest = { editing = -1 },
            title = { Text(m.name.ifBlank { stringResource(R.string.nway_new_named, name) }) },
            text = {
                RoleChoice(m.main, m.keepMine,
                    { main -> members = members.mapIndexed { i, x -> if (i == editing) x.copy(main = main) else x } },
                    { keep -> members = members.mapIndexed { i, x -> if (i == editing) x.copy(keepMine = keep) else x } })
            },
            confirmButton = { TextButton(onClick = { editing = -1 }) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                TextButton(onClick = { members = members.filterIndexed { i, _ -> i != editing }; editing = -1 }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    if (pickSchedule) SchedulePicker(schedule, { pickSchedule = false }) { schedule = it; pickSchedule = false }
    if (firstRun) NwayFirstRunSheet(name.trim(), members, schedule, onDismiss = { firstRun = false }, onDone = { firstRun = false; onDone() })
}

@Composable
private fun SchedulePicker(selected: Schedule, onDismiss: () -> Unit, onSelect: (Schedule) -> Unit) {
    val options = Schedule.entries.map { it to scheduleLabel(it) }
    ChoiceDialog(stringResource(R.string.editor_schedule), options, selected, onDismiss, onSelect = onSelect)
}

/** A playlist of a group: its service's mark, what it is, its role at the end (or [trailing]). */
@Composable
private fun NwayRow(
    p: MusicProvider?, title: String, summary: String, main: Boolean, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    PrefRow(
        null, title, summary, chevron = false, leading = { ServiceLogo(p, 36.dp) },
        trailing = trailing ?: { StatusPill(roleLabel(main), if (main) Tone.Accent else Tone.Neutral) }, onClick = onClick,
    )
}

/** What the first run would do to each playlist, counted from their listings with the sync's own matching: no search, nothing written. */
private class FirstRun(val group: Int, val plus: Map<Int, Int>, val extra: Map<Int, Int>)

private suspend fun firstRunOf(env: Env, members: List<NwayDraft>): FirstRun? = withContext(Dispatchers.IO) {
    runCatching {
        // Only the listings the figures need: the main ones, and the playlists an exact copy would take songs from.
        val read = members.mapIndexed { i, m ->
            i to if (m.playlistId != null && (m.main || !m.keepMine)) Providers.byId(m.provider)!!.tracks(env, m.playlistId) else null
        }.toMap()
        val group = ArrayList<Track>()
        members.forEachIndexed { i, m ->
            if (m.main) { val have = Matcher.TrackIndex(group.toList()); group += read[i].orEmpty().filter { have.best(it) == null } }
        }
        val all = Matcher.TrackIndex(group)
        FirstRun(
            group.size,
            members.indices.filter { members[it].main }.associateWith { i -> Matcher.TrackIndex(read[i].orEmpty()).let { own -> group.count { own.best(it) == null } } },
            members.indices.filter { !members[it].main && read[it] != null }.associateWith { i -> read[i].orEmpty().count { all.best(it) == null } },
        )
    }.getOrNull()
}

/** "What happens at the first sync", service by service, before anything is made: Back, or Create and sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NwayFirstRunSheet(name: String, members: List<NwayDraft>, schedule: Schedule, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var figures by remember { mutableStateOf<FirstRun?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(members) { figures = firstRunOf(env, members); loaded = true }
    fun label(m: NwayDraft) = Providers.byId(m.provider)?.label(env) ?: m.provider
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.nway_first_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            if (!loaded) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                val f = figures
                val mains = members.withIndex().filter { it.value.main }
                // The main ones in one line, as they merge with each other.
                val merged = when {
                    f == null -> stringResource(R.string.nway_first_unknown)
                    mains.size == 1 -> stringResource(R.string.nway_first_main_alone, f.group)
                    else -> stringResource(R.string.nway_first_merge, mains.map { (i, m) -> stringResource(R.string.nway_first_plus, f.plus[i] ?: 0, label(m)) }.joinToString(", "))
                }
                NwayRow(Providers.byId(mains.first().value.provider), mains.joinToString(", ") { label(it.value) }, merged, main = true)
                members.forEachIndexed { i, m ->
                    if (!m.main) NwayRow(
                        Providers.byId(m.provider), label(m),
                        when {
                            f == null -> stringResource(R.string.nway_first_unknown)
                            m.playlistId == null -> stringResource(R.string.nway_first_new, f.group)
                            m.keepMine -> stringResource(R.string.nway_first_keep, f.group)
                            else -> stringResource(R.string.nway_first_exact, f.group, f.extra[i] ?: 0)
                        },
                        main = false,
                    )
                }
            }
            Text(
                stringResource(R.string.nway_first_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.nav_back)) }
                Button(enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), onClick = {
                    busy = true; error = null
                    val description = MusicProvider.description(ctx.getString(R.string.app_name))
                    scope.launch {
                        val made = withContext(Dispatchers.IO) {
                            runCatching {
                                val refs = members.map { m ->
                                    val p = Providers.byId(m.provider) ?: throw IllegalStateException(m.provider)
                                    if (m.playlistId != null) PlaylistRef(p.id, m.playlistId, m.name)
                                    else p.createPlaylist(env, name, description).let { PlaylistRef(p.id, it.id, it.name) }
                                }
                                val group = Nway.create(env, name, refs.zip(members.map { it.main }), schedule)
                                // An exact copy is a follower's other mode: the group makes followers that keep their additions.
                                Nway.members(env, group).forEach { mem ->
                                    val draft = members[refs.indexOfFirst { it.provider == mem.playlist.provider && it.playlistId == mem.playlist.playlistId }]
                                    if (!draft.main && !draft.keepMine) Nway.setKeepMine(env, mem, false)
                                }
                                group
                            }
                        }
                        busy = false
                        made.onSuccess { g ->
                            Nway.members(env, g).forEach { m -> listOfNotNull(m.down.id, m.up?.id).forEach { id -> env.store.job(id)?.let { Scheduler.apply(ctx, it) } } }
                            NwayUi.run(ctx, g)
                            onDone()
                        }.onFailure { error = it.userMessage(ctx) }
                    }
                }) { Text(stringResource(R.string.nway_create_sync), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** How a group stands, from its syncs' last runs. */
private class GroupStatus(val last: Long, val failed: Boolean, val notFound: Int, val paused: Boolean, val schedule: Schedule?)

private fun statusOf(d: StoreData, members: List<Nway.Member>): GroupStatus {
    val jobs = members.flatMap { listOfNotNull(it.down, it.up) }
    val reports = jobs.mapNotNull { j -> d.reports.firstOrNull { it.id == j.lastReportId } }
    return GroupStatus(
        jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L, reports.any { !it.ok },
        // What each service lacks is in the report of the sync that brings the group to it.
        members.sumOf { m -> d.reports.firstOrNull { it.id == m.down.lastReportId }?.unmatchedTracks?.size ?: 0 },
        jobs.isNotEmpty() && jobs.none { it.enabled }, jobs.firstOrNull()?.schedule,
    )
}

/** The songs the group holds now; null until read (and before its first run it holds none). */
@Composable
private fun groupTracks(group: SharedGroup, lastRun: Long): Int? {
    val env = LocalContext.current.env
    val n by produceState<Int?>(null, group.id, lastRun) {
        value = withContext(Dispatchers.IO) { runCatching { GroupLog(group.id, group.creator).live(SharedGroups.load(env, group.id).events).size }.getOrNull() }
    }
    return n
}

/** A group's one card in the Syncs list: its services by role, the last run, what it holds, and its two buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NwayCard(group: SharedGroup, data: StoreData, running: Set<String>, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val members = remember(data.jobs, group.id) { Nway.members(ctx.env, group) }
    val status = remember(data.jobs, data.reports, members) { statusOf(data, members) }
    val busy = members.any { it.down.id in running || it.up?.id in running }
    val tracks = groupTracks(group, status.last)
    val dark = LocalAppDark.current
    Card(
        onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dark) 0.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    status.schedule?.let { scheduleLabel(it) },
                    if (status.last > 0) stringResource(R.string.pill_last_run, relativeTime(status.last)) else stringResource(R.string.last_run_never),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val (mains, followers) = members.partition { it.main }
                mains.forEach { ServiceLogo(Providers.byId(it.playlist.provider), 28.dp) }
                Text(stringResource(R.string.nway_card_main), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                if (followers.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp).align(Alignment.CenterVertically), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    followers.forEach { ServiceLogo(Providers.byId(it.playlist.provider), 28.dp) }
                    Text(stringResource(R.string.nway_card_follow), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tracks != null && status.last > 0) StatusPill(stringResource(R.string.tracks_count, tracks), Tone.Neutral)
                if (status.notFound > 0) StatusPill(stringResource(R.string.pill_not_found, status.notFound), Tone.Warning)
                if (status.failed) StatusPill(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
                if (status.paused) StatusPill(stringResource(R.string.sync_paused), Tone.Neutral, Icons.Filled.Pause)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_open)) }
                Button(onClick = { NwayUi.run(ctx, group) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (busy) R.string.running else R.string.shared_sync_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A group's page: each playlist with its role and how its last run went, then when it runs, pausing it, and deleting it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NwayGroupScreen(groupId: String, onClose: () -> Unit, onReview: (String) -> Unit, onPreview: (SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val scope = rememberCoroutineScope()
    val data by Store.get(ctx).state.collectAsState()
    val running by com.xlollx.songport.sync.SyncState.running.collectAsState()
    val group = data.groups.firstOrNull { it.id == groupId && Nway.isGroup(it) }
    // Deleted (here, or by a file brought in): nothing to show.
    LaunchedEffect(group == null) { if (group == null) onClose() }
    ScreenBackHandler(onClose)
    if (group == null) return
    val members = remember(data.jobs, group.id) { Nway.members(env, group) }
    val status = remember(data.jobs, data.reports, members) { statusOf(data, members) }
    val busy = members.any { it.down.id in running.keys || it.up?.id in running.keys }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pickSchedule by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // The playlist asked to be taken out, by its place in the list.
    var removing by rememberSaveable { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    /** A change to the group's syncs, then their schedules brought in line (those gone are cancelled). */
    fun change(block: suspend () -> List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { block() } }
                .onSuccess { gone -> gone.forEach { Scheduler.cancel(ctx, it) }; error = null }
                .onFailure { error = it.userMessage(ctx) }
            Nway.members(env, group).forEach { m -> listOfNotNull(m.down.id, m.up?.id).forEach { id -> env.store.job(id)?.let { Scheduler.apply(ctx, it) } } }
        }
    }
    fun jobsOf() = Nway.members(env, group).flatMap { listOfNotNull(it.down, it.up) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { NwayUi.run(ctx, group) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.running else R.string.shared_sync_now))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            PrefSection(stringResource(R.string.nway_members), accent = JobColor.BLUE) {
                members.forEachIndexed { i, m ->
                    val p = Providers.byId(m.playlist.provider)
                    val down = data.reports.firstOrNull { it.id == m.down.lastReportId }
                    val up = m.up?.let { u -> data.reports.firstOrNull { it.id == u.lastReportId } }
                    // The sync whose removals wait for a yes: its preview is where they are confirmed.
                    val held = listOfNotNull(m.down.takeIf { (down?.heldBack ?: 0) > 0 }, m.up?.takeIf { (up?.heldBack ?: 0) > 0 }).firstOrNull()
                    val notFound = down?.unmatchedTracks?.size ?: 0
                    val state = when {
                        down?.ok == false || up?.ok == false -> stringResource(R.string.pill_error)
                        held != null -> stringResource(R.string.nway_held, maxOf(down?.heldBack ?: 0, up?.heldBack ?: 0))
                        notFound > 0 -> stringResource(R.string.nway_not_found_review, notFound)
                        down == null -> stringResource(R.string.last_run_never)
                        else -> stringResource(R.string.pill_ok)
                    }
                    val role = when {
                        m.main -> stringResource(R.string.nway_role_main)
                        m.keepMine -> stringResource(R.string.nway_role_follows) + " (" + stringResource(R.string.shared_mode_keep) + ")"
                        else -> stringResource(R.string.nway_role_follows) + " (" + stringResource(R.string.shared_mode_exact) + ")"
                    }
                    val lastMain = m.main && members.count { it.main } == 1
                    NwayRow(
                        p, listOf(p?.label(env) ?: m.playlist.provider, m.playlist.playlistName).filter { it.isNotBlank() }.joinToString(" · "), "$role · $state", m.main,
                        onClick = when {
                            held != null -> ({ onPreview(held) })
                            notFound > 0 && down != null -> ({ onReview(down.id) })
                            else -> null
                        },
                        trailing = {
                            OverflowMenu(listOfNotNull(
                                // The only main one stays main, and stays in: a group with none would never change.
                                if (m.main) MenuAction(stringResource(R.string.nway_make_follow), { change { Nway.setMain(env, group, m, false); emptyList() } }, enabled = !lastMain)
                                else MenuAction(stringResource(R.string.nway_make_main), { change { Nway.setMain(env, group, m, true); emptyList() } }),
                                if (!m.main && !m.keepMine) MenuAction(stringResource(R.string.shared_mode_keep), { change { Nway.setKeepMine(env, m, true); emptyList() } }) else null,
                                if (!m.main && m.keepMine) MenuAction(stringResource(R.string.shared_mode_exact), { change { Nway.setKeepMine(env, m, false); emptyList() } }) else null,
                                MenuAction(stringResource(R.string.nway_remove_member), { removing = i }, destructive = true, enabled = !lastMain),
                            ), enabled = !busy, title = p?.label(env))
                        },
                    )
                }
                if (com.xlollx.songport.Features.v2(data.settings)) PrefRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.nway_add), null, chevron = false, enabled = !busy) { adding = true }
            }
            PrefSection(stringResource(R.string.editor_schedule), accent = JobColor.TEAL) {
                PrefRow(Icons.Filled.Schedule, status.schedule?.let { scheduleLabel(it) }.orEmpty(), null) { pickSchedule = true }
                PrefSwitch(Icons.Filled.Pause, stringResource(R.string.nway_pause), stringResource(R.string.nway_pause_desc), status.paused) { paused ->
                    change { jobsOf().forEach { env.store.upsertJob(it.copy(enabled = !paused)) }; emptyList() }
                }
                PrefRow(Icons.Filled.Delete, stringResource(R.string.nway_delete), null, danger = true, chevron = false, enabled = !busy) { confirmDelete = true }
            }
        }
    }
    if (adding) NwayAddDialog(members.map { it.playlist.provider to it.playlist.playlistId }, onDismiss = { adding = false }) { d ->
        adding = false
        val description = MusicProvider.description(ctx.getString(R.string.app_name))
        change {
            val p = Providers.byId(d.provider) ?: throw IllegalStateException(d.provider)
            val ref = if (d.playlistId != null) PlaylistRef(p.id, d.playlistId, d.name)
                else p.createPlaylist(env, group.name, description).let { PlaylistRef(p.id, it.id, it.name) }
            Nway.add(env, group, ref, d.main, status.schedule ?: Schedule.HOURLY, d.keepMine)
            emptyList()
        }
    }
    if (pickSchedule) SchedulePicker(status.schedule ?: Schedule.HOURLY, { pickSchedule = false }) { s ->
        pickSchedule = false
        change { jobsOf().forEach { env.store.upsertJob(it.copy(schedule = s, everyMinutes = null, at = null, days = emptyList())) }; emptyList() }
    }
    members.getOrNull(removing)?.let { m ->
        FitDialog(
            onDismissRequest = { removing = -1 },
            title = { Text(stringResource(R.string.nway_remove_member)) },
            text = { Text(stringResource(R.string.nway_remove_member_note)) },
            confirmButton = {
                TextButton(onClick = { removing = -1; change { Nway.remove(env, group, m) } }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = -1 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) FitDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.nway_delete)) },
        text = { Text(stringResource(R.string.nway_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Nway.delete(env, group) } }.getOrNull()?.forEach { Scheduler.cancel(ctx, it) }
                    onClose()
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** In the sync editor of a new sync: the way to a group instead. */
@Composable
internal fun NwayEntryCard(onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Hub, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nway_entry_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.nway_entry_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(1.dp))
        }
    }
}

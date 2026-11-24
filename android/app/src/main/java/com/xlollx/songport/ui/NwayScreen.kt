package com.xlollx.songport.ui

import com.xlollx.songport.shared.NwayRun
import com.xlollx.songport.shared.NwayHistory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.Timing
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import com.xlollx.songport.shared.NwayChanges
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.sync.Labels
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One playlist on several services (shared/Nway), a "multi sync": making a group, and its page. In
 * the Syncs list it is a card like any other, on the sync of its source ([Nway.anchor]); the
 * group's other syncs never show as cards: they run and are changed through it.
 */
object NwayUi {
    /** What MainActivity keeps as the open page: this for a new group, else a group's id. */
    const val NEW = ""

    fun groups(d: StoreData): List<SharedGroup> = d.groups.filter { Nway.isGroup(it) }

    /**
     * The syncs the Syncs list does not show: those of a group but the one that stands for it (its
     * card, drawn as any sync's: [Nway.anchor]), and those of a collection's playlists.
     */
    fun hiddenJobIds(d: StoreData): Set<String> {
        val all = memberJobIds(d)
        return if (all.isEmpty()) all else all - Nway.anchors(d).keys
    }

    /**
     * The syncs the Syncs list shows as syncs: the classic ones and one per multi sync (the sync of its
     * source), without the syncs behind a card. What the search counts ("Search 13 syncs") and what the
     * filter's line counts ("1 of 13 syncs"): the same number.
     */
    fun listedSyncs(d: StoreData): List<SyncJob> = Nway.listedSyncs(d, memberJobIds(d))

    /** The syncs a card stands for: all the syncs of its group for a multi sync's card, else itself. */
    fun standsFor(d: StoreData): (SyncJob) -> List<SyncJob> {
        val anchors = Nway.anchors(d)
        if (anchors.isEmpty()) return { listOf(it) }
        val byGroup = anchors.values.associate { g -> g.id to Nway.jobsOf(d, g.id) }
        return { j -> anchors[j.id]?.let { byGroup[it.id] } ?: listOf(j) }
    }

    /** Every sync that belongs to a group of the user's own playlists or to a collection: one card for them all (the widgets list none of them). */
    fun memberJobIds(d: StoreData): Set<String> {
        // So are the syncs of a collection's playlists: behind the collection's one card (CollectionUi).
        val collected = CollectionUi.memberJobIds(d)
        return Nway.memberJobIds(d).let { if (it.isEmpty()) collected else it + collected }
    }

    /** [Labels.storeIndex] for a place among the syncs the list shows, as a place in the stored list, which holds the groups' syncs too. */
    fun storeIndex(d: StoreData, id: String, shownIndex: Int): Int {
        val hidden = hiddenJobIds(d)
        val shown = d.jobs.filter { it.id !in hidden }
        val rest = shown.filter { it.id != id }
        val at = Labels.storeIndex(shown, id, shownIndex, d.settings.favoritesOnTop)
        return rest.getOrNull(at)?.let { next -> d.jobs.filter { it.id != id }.indexOfFirst { it.id == next.id } } ?: d.jobs.size
    }

    /**
     * The service [job]'s songs come from, as the person knows it: for a sync of a multi sync, whose
     * source is the group's hub, the service of the group's source; for any other sync its own
     * source. Null where it is not known (a hub with no group, or no source left).
     */
    fun sourceOf(d: StoreData, job: SyncJob): String? {
        if (job.source.provider != com.xlollx.songport.shared.SharedProvider.ID) return job.source.provider
        val g = d.groups.firstOrNull { it.id == job.source.playlistId && Nway.isGroup(it) } ?: return null
        return Nway.source(g, Nway.members(d.jobs, g.id))?.playlist?.provider
    }

    /** "Sync now" on a group: its syncs in the group's order, one after the other; paused or not, as Sync now on a paused classic sync. */
    fun run(ctx: android.content.Context, group: SharedGroup) {
        val order = Nway.order(ctx.env, group, manual = true).map { it.id }
        // Nothing skipped in silence.
        if (order.isEmpty()) Nway.log(ctx.env, group.id, "sync now skipped: the group has no sync")
        Scheduler.runInSequence(ctx, order)
    }
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

/** What the multi-sync editor's playlist picker is for: Liked songs is offered by it ([NwayAddDialog]). */
internal enum class NwayPick { SOURCE, TARGET }

/**
 * A playlist for the group: the service, one of its playlists or a new one, and its role. [taken]:
 * the playlists already in. The multi-sync editor asks for the playlist alone ([roles] off), for a
 * source that exists ([allowNew] off), and, adding a target ([modes] on), for its way to follow
 * too, as its sheet offers them. [pick]: the editor's, which offers the service's Liked songs, first,
 * as the sync editor's picker does, where the multi sync can take it that way (NwayEdit). [newName]:
 * the name a new playlist gets.
 */
@Composable
internal fun NwayAddDialog(
    taken: List<Pair<String, String?>>, onDismiss: () -> Unit,
    title: String = stringResource(R.string.nway_add), roles: Boolean = true, allowNew: Boolean = true,
    pick: NwayPick? = null, modes: Boolean = false, newName: String? = null,
    /** The role offered first: main (two-way), or one that follows and keeps the user's additions. */
    asMain: Boolean = true,
    onAdd: (NwayDraft) -> Unit,
) {
    val ctx = LocalContext.current
    val services = remember { writableServices(ctx, needsCreate = false) }
    var serviceId by rememberSaveable { mutableStateOf(services.firstOrNull()?.id) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var playlistCount by rememberSaveable { mutableStateOf(-1) }
    var makeNew by rememberSaveable { mutableStateOf(false) }
    var main by rememberSaveable { mutableStateOf(asMain) }
    var keepMine by rememberSaveable { mutableStateOf(true) }
    // A target's way, as its sheet sets it (keeps its additions until told otherwise); Exact copy of an existing playlist is asked first.
    var mode by rememberSaveable { mutableStateOf(com.xlollx.songport.shared.NwayEdit.Mode.KEEP) }
    var askExact by rememberSaveable { mutableStateOf(false) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    val service = serviceId?.let { Providers.byId(it) }
    LaunchedEffect(serviceId) {
        lists = null
        val p = service ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) {
            runCatching {
                // Liked songs first, as the sync editor lists it (MusicProvider.libraryEntries), then the user's own playlists.
                // As the source only where it can send its changes; where its list leaves likes out, shown with why (notHere).
                val liked = if (pick == null) emptyList() else p.libraryEntries(ctx.env, asTarget = pick == NwayPick.TARGET).filter {
                    it.id == MusicProvider.LIKED_ID && (pick == NwayPick.TARGET || !p.listsEveryLike || com.xlollx.songport.shared.NwayEdit.canBeSource(p.id, it.id))
                }
                liked + p.playlists(ctx.env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) }
            }.getOrDefault(emptyList())
        }
    }
    // Liked songs where the service does not list every like: shown, not offered as the source, and said why.
    val notHere = if (pick == NwayPick.SOURCE && service != null && !com.xlollx.songport.shared.NwayEdit.canBeSource(service.id, MusicProvider.LIKED_ID))
        mapOf(MusicProvider.LIKED_ID to stringResource(R.string.nway_liked_target_only, service.label(ctx.env))) else emptyMap()
    val chosen = makeNew || playlistId != null
    val already = !makeNew && playlistId != null && taken.any { it.first == serviceId && it.second == playlistId }
    /** Another playlist: a way it cannot follow (Liked songs) is "keep my additions"; an exact copy of an existing one is asked again. */
    fun fit(id: String?) {
        val ok = service?.let { com.xlollx.songport.shared.NwayEdit.modes(it.id, id) } ?: com.xlollx.songport.shared.NwayEdit.Mode.entries
        if (mode !in ok || (mode == com.xlollx.songport.shared.NwayEdit.Mode.EXACT && id != null)) mode = com.xlollx.songport.shared.NwayEdit.Mode.KEEP
    }
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (services.isEmpty()) Text(stringResource(R.string.shared_no_services), color = MaterialTheme.colorScheme.error)
                ServiceDropdown(services, service) { if (it != serviceId) { serviceId = it; playlistId = null; playlistName = ""; makeNew = false; fit(null) } }
                if (service != null) PlaylistDropdown(
                    lists, playlistId?.let { Playlist(it, playlistName, playlistCount) }, makeNew, allowNew && service.canCreatePlaylists,
                    // A new, empty playlist has nothing to give the others: it follows unless told otherwise.
                    onNew = { makeNew = true; playlistId = null; playlistName = ""; main = false; fit(null) },
                    provider = service.id,
                    newLabel = if (pick == null) null else newName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.nway_new_playlist_named, it) } ?: stringResource(R.string.nway_new_playlist),
                    disabled = notHere,
                ) { makeNew = false; playlistId = it.id; playlistName = it.name; playlistCount = it.trackCount; fit(it.id) }
                if (already) Text(stringResource(R.string.nway_already), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (roles) RoleChoice(main, keepMine, { main = it }, { keepMine = it })
                if (modes && service != null) NwayModeRows(service.id, if (makeNew) null else playlistId, mode) { to ->
                    // Exact copy of a playlist that exists removes what is only there: asked, as on the group's page. A new one has nothing to lose.
                    if (to == com.xlollx.songport.shared.NwayEdit.Mode.EXACT && !makeNew && playlistId != null && mode != to) askExact = true else mode = to
                }
            }
        },
        confirmButton = {
            TextButton(enabled = service != null && chosen && !already, onClick = {
                val m = if (modes) mode != com.xlollx.songport.shared.NwayEdit.Mode.KEEP && mode != com.xlollx.songport.shared.NwayEdit.Mode.EXACT else main
                val k = if (modes) mode != com.xlollx.songport.shared.NwayEdit.Mode.EXACT else keepMine
                onAdd(NwayDraft(service!!.id, if (makeNew) null else playlistId, if (makeNew) "" else playlistName, if (makeNew) 0 else playlistCount, m, k))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (askExact && service != null) NwayExactConfirm(service.label(ctx.env), onDismiss = { askExact = false }) {
        askExact = false; mode = com.xlollx.songport.shared.NwayEdit.Mode.EXACT
    }
}

/**
 * "Exact copy?" of a playlist that exists, as the group's page asks it since 2.1.2: what the user
 * added only there is removed at its next run. Yes ([NWAY_EXACT_OK_TAG]) or Cancel.
 */
@Composable
internal fun NwayExactConfirm(service: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_mode_exact) + " · " + service) },
        text = { Text(stringResource(R.string.nway_exact_desc)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(NWAY_EXACT_OK_TAG)) {
                Text(stringResource(R.string.shared_mode_exact), color = MaterialTheme.colorScheme.error)
            }
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
    // As in the editor: the first playlist is the main one, the next ones follow and keep the user's additions.
    if (adding) NwayAddDialog(members.map { it.provider to it.playlistId }, onDismiss = { adding = false }, asMain = members.none { it.main }) { members = members + it; adding = false }
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
    val env = ctx.env
    if (firstRun) NwayFirstRunSheet(name.trim(), members, onDismiss = { firstRun = false }, onDone = { firstRun = false; onDone() }) {
        val description = MusicProvider.newDescription(ctx.env, ctx.getString(R.string.app_name))
        val g = withContext(Dispatchers.IO) {
            val refs = members.map { m ->
                val p = Providers.byId(m.provider) ?: throw IllegalStateException(m.provider)
                if (m.playlistId != null) PlaylistRef(p.id, m.playlistId, m.name)
                else p.createPlaylist(env, name.trim(), description).let { PlaylistRef(p.id, it.id, it.name) }
            }
            val group = Nway.create(env, name.trim(), refs.zip(members.map { it.main }), schedule)
            // An exact copy is a follower's other mode: the group makes followers that keep their additions.
            Nway.members(env, group).forEach { mem ->
                val draft = members[refs.indexOfFirst { it.provider == mem.playlist.provider && it.playlistId == mem.playlist.playlistId }]
                if (!draft.main && !draft.keepMine) Nway.setKeepMine(env, mem, false)
            }
            group
        }
        Nway.members(env, g).forEach { m -> listOfNotNull(m.down.id, m.up?.id).forEach { id -> env.store.job(id)?.let { Scheduler.apply(ctx, it) } } }
        NwayUi.run(ctx, g)
    }
}

@Composable
private fun SchedulePicker(selected: Schedule, onDismiss: () -> Unit, onSelect: (Schedule) -> Unit) {
    val options = Schedule.entries.map { it to scheduleLabel(it) }
    ChoiceDialog(stringResource(R.string.editor_schedule), options, selected, onDismiss, onSelect = onSelect)
}

/** For the UI tests: the group page's schedule row, and its chooser's Save. */
internal const val GROUP_SCHEDULE_ROW_TAG = "group-schedule-row"
internal const val GROUP_SCHEDULE_SAVE_TAG = "group-schedule-save"

/** For the UI tests: the yes of "Exact copy?" on the group's page. */
internal const val NWAY_EXACT_OK_TAG = "nway-exact-ok"

/** A group's timing as its card says it ("Every Saturday at 09:00", "Every 6 hours"), after its saved schedule's name when it follows one. */
@Composable
private fun groupScheduleLabel(d: StoreData, job: SyncJob?): String {
    if (job == null) return ""
    val name = Schedules.find(d, job.scheduleId)?.name?.takeIf { it.isNotBlank() }
    return listOfNotNull(name, timingLabel(job)).joinToString(" · ")
}

/**
 * The group page's "Automatic schedule": the sync editors' own chooser ([SchedulePicker]), a saved
 * schedule or a timing by hand with its days and time, and Detach. It opens on the group's timing
 * ([timing], the sync of its card); [onSave] gets the schedule picked (null: by hand) and the timing
 * by hand, and answers whether it was saved. What is chosen is kept when Android makes the screen anew.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupScheduleDialog(timing: SyncJob, onDismiss: () -> Unit, onSave: (String?, Schedules.Spec) -> Boolean) {
    val ctx = LocalContext.current
    val data by Store.get(ctx).state.collectAsState()
    var everyM by rememberSaveable { mutableIntStateOf(Timing.interval(timing)) }
    var atStr by rememberSaveable { mutableStateOf(timing.at ?: "09:00") }
    // A weekly sync saved before days could be chosen: the day it has been running on.
    var daysCsv by rememberSaveable { mutableStateOf(timing.days.ifEmpty { listOfNotNull(legacyWeeklyDay(timing)) }.joinToString(",")) }
    var scheduleId by rememberSaveable { mutableStateOf(timing.scheduleId) }
    var scheduleSaved by rememberSaveable { mutableStateOf(timing.scheduleId != null) }
    val days = daysCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
    val linkedTo = if (scheduleSaved) Schedules.find(data, scheduleId) else null
    val chosen = if (linkedTo != null) Schedules.link(timing, linkedTo) else Timing.apply(timing, everyM, atStr, days).copy(scheduleId = null)
    val changed = Schedules.specOf(chosen) != Schedules.specOf(timing) || chosen.scheduleId != timing.scheduleId
    val leave = rememberDiscardGuard(changed, onClose = onDismiss)
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.editor_schedule)) },
                        navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = leave, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.cancel)) }
                            Button(
                                onClick = { if (onSave(linkedTo?.id, Schedules.spec(everyM, atStr, days))) onDismiss() },
                                modifier = Modifier.weight(1f).height(52.dp).testTag(GROUP_SCHEDULE_SAVE_TAG),
                            ) { Text(stringResource(R.string.save)) }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.nway_schedule_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SchedulePicker(
                        scheduleId, scheduleSaved, timing.id, hand = Schedules.spec(everyM, atStr, days),
                        onTab = { scheduleSaved = it }, onPick = { scheduleId = it },
                        onDetach = { t -> everyM = t.everyMinutes; atStr = t.at ?: "09:00"; daysCsv = t.days.joinToString(","); scheduleId = null; scheduleSaved = false },
                    ) {
                        TimingPicker(everyM, atStr, daysCsv, onEvery = { everyM = it }, onAt = { atStr = it }, onDays = { daysCsv = it })
                    }
                }
            }
        }
    }
}

/** For the UI tests: the head of a running group's page, and a service's row by where it stands ("group-row-now:tidal"; "pinned-" before it when it is on top). */
internal const val GROUP_RUN_HEAD_TAG = "group-run-head"
internal fun groupRowTag(phase: NwayRun.Phase?, provider: String, pinned: Boolean = false) =
    (if (pinned) "pinned-" else "") + "group-row-" + (phase?.name?.lowercase() ?: "rest") + ":" + provider

/** A playlist of a group: its service's mark, what it is, its role at the end (or [trailing]). */
@Composable
private fun NwayRow(
    p: MusicProvider?, title: String, summary: String, main: Boolean, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    PrefRow(
        null, title, summary, modifier, chevron = false, leading = { ServiceLogo(p, 36.dp, ring = accountRing(p?.id)) },
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

/**
 * "What happens at the first sync", service by service, before anything is made: Back, or Create and
 * sync, which is [action] (the caller's own way of making the group; what it throws is said here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NwayFirstRunSheet(name: String, members: List<NwayDraft>, onDismiss: () -> Unit, onDone: () -> Unit, action: suspend () -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var figures by remember { mutableStateOf<FirstRun?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(members) { figures = firstRunOf(env, members); loaded = true }
    fun label(m: NwayDraft) = nwayMemberLabel(ctx, m.provider, m.playlistId)
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
                    scope.launch {
                        val made = runCatching { action() }
                        busy = false
                        made.onSuccess { onDone() }.onFailure { error = it.userMessage(ctx) }
                    }
                }) { Text(stringResource(R.string.nway_create_sync), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** [NwayStatus] of a group, and when it runs. */
private fun statusOf(d: StoreData, members: List<Nway.Member>): NwayStatus.Group = NwayStatus.group(d, members)

/** A service's name as the cards say it. */
@Composable
internal fun nwayService(provider: String): String = Providers.byId(provider)?.label(LocalContext.current.env) ?: provider

/** A playlist of a multi sync as the cards name it: its service, and "♥ Liked songs" when it is that list (2.1.3). */
internal fun nwayMemberLabel(ctx: android.content.Context, provider: String, playlistId: String?): String {
    val p = Providers.byId(provider)
    val service = p?.label(ctx.env) ?: provider
    if (!com.xlollx.songport.shared.NwayEdit.isLiked(playlistId)) return service
    return service + " · \u2665 " + ctx.getString(if (p?.serviceId == com.xlollx.songport.providers.LastFmProvider.SERVICE) R.string.lastfm_loved else R.string.liked_songs)
}

@Composable
internal fun nwayMember(provider: String, playlistId: String?): String = nwayMemberLabel(LocalContext.current, provider, playlistId)

@Composable
internal fun nwayMember(m: Nway.Member): String = nwayMember(m.playlist.provider, m.playlist.playlistId)

/**
 * A group's state in a few words, as a card's pill says its own, with the service it is about when
 * it is one: "Needs attention · Qobuz", "Removals on hold · TIDAL", "1 to check · Amazon Music", "Not found: 12".
 */
@Composable
internal fun groupStatusText(g: NwayStatus.Group): String {
    val text = when (g.status) {
        CardStatus.DISABLED -> stringResource(R.string.sync_paused)
        CardStatus.NEVER -> stringResource(R.string.pill_never)
        CardStatus.ERROR -> stringResource(R.string.pill_error)
        CardStatus.OFFLINE -> stringResource(R.string.pill_stopped_offline)
        CardStatus.HELD_BACK -> stringResource(R.string.pill_held_back)
        CardStatus.INCOMPLETE -> stringResource(R.string.pill_incomplete)
        CardStatus.NOT_ADDED -> stringResource(R.string.pill_not_added, g.count)
        CardStatus.NOT_FOUND -> stringResource(R.string.pill_not_found, g.count)
        CardStatus.TO_CHECK -> toCheckText(g.count)
        else -> stringResource(R.string.pill_ok)
    }
    // Songs not found are counted over all the services: the count says more than one name.
    val named = g.provider?.takeIf { g.status != CardStatus.NOT_FOUND }
    return if (named != null) text + " · " + nwayService(named) else text
}

internal fun groupStatusTone(s: CardStatus): Tone = when (s) {
    CardStatus.ERROR -> Tone.Error
    CardStatus.HELD_BACK, CardStatus.INCOMPLETE, CardStatus.NOT_ADDED, CardStatus.NOT_FOUND, CardStatus.TO_CHECK -> Tone.Warning
    CardStatus.OK -> Tone.Ok
    else -> Tone.Neutral
}

/** The songs the group holds now; null until read (and before its first run it holds none). */
@Composable
internal fun groupTracks(group: SharedGroup, lastRun: Long): Int? {
    val env = LocalContext.current.env
    val n by produceState<Int?>(null, group.id, lastRun) {
        value = withContext(Dispatchers.IO) { runCatching { GroupLog(group.id, group.creator).live(SharedGroups.load(env, group.id).events).size }.getOrNull() }
    }
    return n
}

/** A group's page: each playlist with its role and how its last run went, then when it runs, pausing it, and deleting it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NwayGroupScreen(
    groupId: String, onClose: () -> Unit, onReview: (String) -> Unit, onPreview: (SyncJob) -> Unit,
    /** A service that failed, tapped: to Accounts when it is signed out, else to the log, where the run says what went wrong. */
    onAccounts: () -> Unit = {}, onOpenLog: () -> Unit = {},
    /** Edit: the multi-sync editor on this group (2.1.1). */
    onEdit: (() -> Unit)? = null,
    /** "Add a target": the multi-sync editor on this group, its playlist picker open (2.1.2). */
    onAddTarget: (() -> Unit)? = null,
) {
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
    val batches by com.xlollx.songport.sync.SyncState.batches.collectAsState()
    // The source first, then the targets; while it runs, where each service stands.
    val source = Nway.source(group, members)
    val ordered = remember(members, source?.down?.id) { NwayRun.sourceFirst(members, source?.down?.id) }
    val run = NwayRun.of(ordered, running, com.xlollx.songport.sync.SyncState.batchOf(group.id, batches))
    // The run of one service, live (its row tapped while it is being synced).
    var live by rememberSaveable { mutableStateOf<String?>(null) }
    var pickSchedule by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // The playlist asked to be taken out, by its place in the list.
    var removing by rememberSaveable { mutableStateOf(-1) }
    // The playlist asked to be made an exact copy, by its place in the list: asked first.
    var exactFor by rememberSaveable { mutableStateOf(-1) }
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
    /** A target's way to follow, as the editor sets it: two-way is a main member; an exact copy and "keep my additions" follow. */
    fun setMode(m: Nway.Member, to: com.xlollx.songport.shared.NwayEdit.Mode) = change {
        val keep = to == com.xlollx.songport.shared.NwayEdit.Mode.KEEP
        when {
            to == com.xlollx.songport.shared.NwayEdit.Mode.TWO_WAY -> Nway.setMain(env, group, m, true)
            m.main -> Nway.setMain(env, group, m, false, keepMine = keep)
            else -> Nway.setKeepMine(env, m, keep)
        }
        emptyList()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { MultiBetaTitle(group.name) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                // Not while it runs: its syncs are at work.
                actions = { if (onEdit != null && com.xlollx.songport.Features.v2(data.settings)) TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.edit)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { NwayUi.run(ctx, group) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.running else R.string.shared_sync_now))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (run != null && run.place > 0) Text(
                stringResource(R.string.nway_run_services, run.place, run.total), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(GROUP_RUN_HEAD_TAG),
            )
            /** A service signed out is fixed in Accounts; anything else says what went wrong in the log. */
            fun signedOut(provider: String) = Providers.byId(provider)?.let { it.requiresAuth && !it.isConnected(env) } == true
            fun fix(provider: String) = if (signedOut(provider)) onAccounts() else onOpenLog()
            @Composable fun fixText(provider: String, reason: String?) =
                listOfNotNull(reason?.takeIf { it.isNotBlank() }, stringResource(if (signedOut(provider)) R.string.sign_in_again else R.string.settings_log)).joinToString(" › ")
            @Composable fun row(st: NwayStatus.Member, pinned: Boolean = false) {
                val m = st.member
                val i = members.indexOf(m)
                val p = Providers.byId(m.playlist.provider)
                val down = data.reports.firstOrNull { it.id == m.down.lastReportId }
                val up = m.up?.let { u -> data.reports.firstOrNull { it.id == u.lastReportId } }
                // The sync whose removals wait for a yes: its preview is where they are confirmed.
                val held = listOfNotNull(m.down.takeIf { (down?.heldBack ?: 0) > 0 }, m.up?.takeIf { (up?.heldBack ?: 0) > 0 }).firstOrNull()
                // Songs not found or matches to check: the run that left them is where they are reviewed, as from a card.
                val review = st.report?.takeIf { st.status == CardStatus.NOT_FOUND || st.status == CardStatus.TO_CHECK }
                    ?: down?.takeIf { it.unmatchedTracks.isNotEmpty() || it.reviewTracks.isNotEmpty() }
                val state = when (st.status) {
                    // No internet is not a failure of the service: said as on a card.
                    CardStatus.ERROR -> stringResource(R.string.pill_error) + " · " + fixText(m.playlist.provider, userText(ctx, st.report?.error))
                    CardStatus.OFFLINE -> stringResource(R.string.pill_stopped_offline)
                    CardStatus.HELD_BACK -> stringResource(R.string.nway_held, st.count)
                    CardStatus.INCOMPLETE -> stringResource(R.string.pill_incomplete)
                    CardStatus.NOT_ADDED -> stringResource(R.string.pill_not_added, st.count)
                    CardStatus.NOT_FOUND -> stringResource(R.string.nway_not_found_review, st.count)
                    CardStatus.TO_CHECK -> toCheckText(st.count)
                    CardStatus.NEVER -> stringResource(R.string.last_run_never)
                    else -> stringResource(R.string.pill_ok)
                }
                // As the editor says it: the source is the source; a target is an exact copy, keeps its additions, or is two-way.
                val role = if (m.down.id == source?.down?.id) null else modeLabel(com.xlollx.songport.shared.NwayEdit.modeOf(m))
                val lastMain = m.main && members.count { it.main } == 1
                val phase = run?.services?.firstOrNull { it.member.down.id == m.down.id }
                // While it runs each service says where it stands; at rest, its role and how its last run went.
                val summary = when (phase?.phase) {
                    NwayRun.Phase.NOW -> stringResource(R.string.nway_row_now, progressText(running[phase.job?.id]))
                    NwayRun.Phase.DONE -> phase.result?.takeIf { it.added > 0 || it.removed > 0 }?.let { stringResource(R.string.nway_row_done_changes, it.added, it.removed) } ?: stringResource(R.string.nway_row_done)
                    NwayRun.Phase.WAITING -> stringResource(R.string.nway_row_waiting)
                    NwayRun.Phase.FAILED -> stringResource(R.string.nway_row_failed, fixText(m.playlist.provider, userText(ctx, phase.result?.error)))
                    // A target's songs after its last run, when that run read it (the source's are in its heading).
                    else -> listOfNotNull(role, state, NwayChanges.targetSize(down)?.takeIf { role != null }?.let { pluralStringResource(R.plurals.setlists_songs, it, it) }).joinToString(" · ")
                }
                NwayRow(
                    p, listOf(p?.label(env) ?: m.playlist.provider, playlistDisplayName(m.playlist.playlistId, m.playlist.playlistName, p?.serviceId)).filter { it.isNotBlank() }.joinToString(" · "), summary, m.main,
                    modifier = Modifier.testTag(groupRowTag(phase?.phase, m.playlist.provider, pinned)),
                    onClick = when {
                        phase?.phase == NwayRun.Phase.NOW -> ({ live = phase.job?.id })
                        phase?.phase == NwayRun.Phase.FAILED || (phase == null && st.status == CardStatus.ERROR) -> ({ fix(m.playlist.provider) })
                        phase != null && phase.phase != NwayRun.Phase.IDLE -> null
                        held != null -> ({ onPreview(held) })
                        review != null -> ({ onReview(review.id) })
                        else -> null
                    },
                    trailing = when (phase?.phase) {
                        NwayRun.Phase.NOW -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                        NwayRun.Phase.DONE -> ({ Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = Tones.onSuccessContainer()) })
                        NwayRun.Phase.FAILED -> ({ Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) })
                        NwayRun.Phase.WAITING -> ({ Spacer(Modifier.width(20.dp)) })
                        else -> ({
                            // As the editor offers it: a target follows one of three ways, can be made the source, or leaves.
                            // The source has none of these: it changes only by making a target the source (here or in the editor).
                            val mode = com.xlollx.songport.shared.NwayEdit.modeOf(m)
                            // Liked songs only the ways it can follow, and the source only where it can be (NwayEdit).
                            OverflowMenu(if (m.down.id == source?.down?.id) emptyList() else com.xlollx.songport.shared.NwayEdit.modes(m.playlist.provider, m.playlist.playlistId).filter { it != mode }.map { to ->
                                // An exact copy removes what the user added there at its next run: asked first.
                                MenuAction(modeLabel(to), { if (to == com.xlollx.songport.shared.NwayEdit.Mode.EXACT) exactFor = i else setMode(m, to) })
                            } + listOf(
                                MenuAction(stringResource(R.string.nway_make_source), { change { Nway.setSource(env, group, m); emptyList() } },
                                    enabled = com.xlollx.songport.shared.NwayEdit.canBeSource(m.playlist.provider, m.playlist.playlistId)),
                                // The only main one stays in: a group with none would never change.
                                MenuAction(stringResource(R.string.nway_remove_member), { removing = i }, destructive = true, enabled = !lastMain),
                            ), enabled = !busy, title = p?.label(env))
                        })
                    },
                )
            }
            val states = status.members.sortedBy { st -> ordered.indexOfFirst { it.down.id == st.member.down.id } }
            // What failed is on top, at rest and while it runs: it is the row that leads to the fix.
            val failing = states.filter { st ->
                val phase = run?.services?.firstOrNull { it.member.down.id == st.member.down.id }?.phase
                phase == NwayRun.Phase.FAILED || (run == null && st.status == CardStatus.ERROR)
            }
            if (failing.isNotEmpty()) PrefSection(stringResource(R.string.pill_error), accent = JobColor.RED) { failing.forEach { row(it, pinned = true) } }
            val (first, rest) = states.filter { it !in failing }.partition { it.member.down.id == source?.down?.id }
            // The source's heading says its songs ("Source · 1152 songs"), the figure the card's "in source" shows.
            val hubSongs = groupTracks(group, status.last)
            val lastRead = remember(data.reports, members) { NwayHistory.runs(data.reports, members, 1).lastOrNull()?.sourceCount?.takeIf { it > 0 } }
            val sourceSongs = hubSongs ?: lastRead
            val sourceHead = stringResource(R.string.editor_source) + (sourceSongs?.let { " · " + pluralStringResource(R.plurals.setlists_songs, it, it) } ?: "")
            if (first.isNotEmpty()) PrefSection(sourceHead, accent = JobColor.BLUE) { first.forEach { row(it) } }
            PrefSection(stringResource(R.string.nway_targets), accent = JobColor.BLUE) {
                rest.forEach { row(it) }
                // In the editor, as a target is added there: it follows as chosen, and runs as the group does.
                if (onAddTarget != null && com.xlollx.songport.Features.v2(data.settings)) PrefRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.nway_add_target), null, chevron = false, enabled = !busy, onClick = onAddTarget)
            }
            PrefSection(stringResource(R.string.editor_schedule), accent = JobColor.TEAL) {
                // The whole timing, as a card says it, after the saved schedule's name when it follows one.
                PrefRow(Icons.Filled.Schedule, groupScheduleLabel(data, Nway.anchor(data, group) ?: members.firstOrNull()?.down), null, Modifier.testTag(GROUP_SCHEDULE_ROW_TAG)) { pickSchedule = true }
                PrefSwitch(Icons.Filled.Pause, stringResource(R.string.nway_pause), stringResource(R.string.nway_pause_desc), status.paused) { paused ->
                    change { jobsOf().forEach { env.store.upsertJob(it.copy(enabled = !paused)) }; emptyList() }
                }
                PrefRow(Icons.Filled.Delete, stringResource(R.string.nway_delete), null, danger = true, chevron = false, enabled = !busy) { confirmDelete = true }
            }
        }
    }
    // One service's run, live, as a sync's card shows its own.
    members.flatMap { listOfNotNull(it.up, it.down) }.firstOrNull { it.id == live }?.let { j -> LiveSheet(j, onReview) { live = null } }
    // The schedule chooser of the sync editors: a saved schedule (linked) or a timing by hand, with its days and time.
    // Every sync of the group takes it in one change of the store, or none does; then their work is set again, as after any change here.
    if (pickSchedule) (Nway.anchor(data, group) ?: members.firstOrNull()?.down)?.let { timing ->
        GroupScheduleDialog(timing, onDismiss = { pickSchedule = false }) { scheduleId, spec ->
            val saved = ManyChange.applyOrSay(ctx) { d -> com.xlollx.songport.shared.NwayEdit.scheduled(d, group.id, scheduleId, spec) }
            if (saved) jobsOf().forEach { j -> runCatching { Scheduler.apply(ctx, j) } }
            saved
        }
    }
    members.getOrNull(exactFor)?.let { m ->
        NwayExactConfirm(nwayService(m.playlist.provider), onDismiss = { exactFor = -1 }) { exactFor = -1; setMode(m, com.xlollx.songport.shared.NwayEdit.Mode.EXACT) }
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
                    // Not saved: the group stays whole, with its key and its list, and the page says so.
                    withContext(Dispatchers.IO) { runCatching { Nway.delete(env, group) } }
                        .onSuccess { ids -> ids.forEach { Scheduler.cancel(ctx, it) }; onClose() }
                        .onFailure { error = it.userMessage(ctx) }
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

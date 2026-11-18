package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.Timing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** For the UI tests: the multi-sync editor's source row, a target's row by its place, and a target sheet's choice by its way. */
const val NWAY_SOURCE_ROW_TAG = "nway-source"
fun nwayTargetTag(index: Int) = "nway-target-$index"
fun nwayModeTag(mode: NwayEdit.Mode) = "nway-mode-" + mode.name.lowercase()

private const val FS = "\u001f"

private fun encode(i: NwayEdit.Item) = listOf(i.provider, i.playlistId.orEmpty(), i.name, i.count.toString(), i.mode.name, i.downId.orEmpty()).joinToString(FS)
private fun decode(s: String): NwayEdit.Item? = s.split(FS).takeIf { it.size == 6 }?.let { f ->
    NwayEdit.Item(f[0], f[1].ifEmpty { null }, f[2], f[3].toIntOrNull() ?: -1, runCatching { NwayEdit.Mode.valueOf(f[4]) }.getOrDefault(NwayEdit.Mode.KEEP), f[5].ifEmpty { null })
}

/** The editor's source across a rotation ("" for none yet). */
private val ItemSaver = Saver<NwayEdit.Item?, String>(save = { it?.let(::encode).orEmpty() }, restore = { decode(it) })
private val ItemsSaver = listSaver<List<NwayEdit.Item>, String>(save = { it.map(::encode) }, restore = { it.mapNotNull(::decode) })

@Composable
internal fun modeLabel(mode: NwayEdit.Mode): String = stringResource(
    when (mode) { NwayEdit.Mode.EXACT -> R.string.shared_mode_exact; NwayEdit.Mode.KEEP -> R.string.shared_mode_keep; NwayEdit.Mode.TWO_WAY -> R.string.editor_bidirectional },
)

@Composable
private fun itemTitle(i: NwayEdit.Item): String = if (i.playlistId == null) stringResource(R.string.nway_new_playlist) else i.name

/**
 * New sync › Multi sync, and Edit on a multi sync (2.1.1): the classic editor's look and steps for a
 * sync with one source and several targets. 1 Source, 2 Targets (each with one way to follow, in its
 * sheet), 3 Options (name, colour, tags, schedule); Preview at the bottom says what saving does.
 * Nothing is changed until then, and then all of it is, as one change.
 *
 * [groupId] null: a new one, optionally from [startSource] (the classic editor's source, kept when
 * the user switches). [onClassic]: "One target", back to the classic editor with the source chosen.
 * [onPrevious]: the screen this one takes the place of, still there with every option it had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NwayEditorScreen(
    groupId: String?, startSource: PlaylistRef? = null,
    onClose: () -> Unit, onDone: () -> Unit,
    onClassic: ((PlaylistRef?) -> Unit)? = null, onPrevious: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val env = ctx.env
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val isNew = groupId == null
    // The group as it was when the editor opened: what the draft starts from, and what "changed" is told by.
    val opened = remember(groupId) {
        store.data.groups.firstOrNull { it.id == groupId && Nway.isGroup(it) }?.let { g -> Triple(g, NwayEdit.draftOf(g, Nway.members(env, g)), Nway.anchor(store.data, g)) }
    }
    val group = data.groups.firstOrNull { it.id == groupId && Nway.isGroup(it) }
    val card = opened?.third

    var name by rememberSaveable { mutableStateOf(opened?.second?.name.orEmpty()) }
    var source by rememberSaveable(stateSaver = ItemSaver) {
        mutableStateOf(opened?.second?.source ?: startSource?.takeIf { !it.playlistId.isNullOrBlank() && writableServices(ctx, false).any { p -> p.id == it.provider } }
            ?.let { NwayEdit.Item(it.provider, it.playlistId, it.playlistName) })
    }
    var targets by rememberSaveable(stateSaver = ItemsSaver) { mutableStateOf(opened?.second?.targets.orEmpty()) }
    // Options, as the classic editor holds them: the card's colour or label, its tags, and when it runs.
    var color by rememberSaveable { mutableStateOf(card?.label ?: card?.color) }
    var tagDraft by rememberSaveable(stateSaver = TagDraftSaver) { mutableStateOf(TagDraft(card?.tags.orEmpty().toList())) }
    var everyM by rememberSaveable { mutableIntStateOf(card?.let { Timing.interval(it) } ?: 60) }
    var atStr by rememberSaveable { mutableStateOf(card?.at ?: "09:00") }
    var daysCsv by rememberSaveable { mutableStateOf(card?.days.orEmpty().joinToString(",")) }
    var scheduleId by rememberSaveable { mutableStateOf(card?.scheduleId) }
    var scheduleSaved by rememberSaveable { mutableStateOf(card?.scheduleId != null || (isNew && store.data.settings.savedSchedules.isNotEmpty())) }

    // What is open: the playlist picker ("source", "add" or "change:<target>"), a target's sheet, the preview.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetFor by rememberSaveable { mutableIntStateOf(-1) }
    var preview by rememberSaveable { mutableStateOf(false) }

    val draft = NwayEdit.Draft(name.trim().ifEmpty { source?.name.orEmpty() }.take(Invite.MAX_NAME), source, targets)
    val days = daysCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
    val linkedTo = if (scheduleSaved) Schedules.find(data, scheduleId) else null
    /** The timing chosen, given to a sync of the group. */
    fun timed(j: SyncJob): SyncJob = if (linkedTo != null) Schedules.link(j, linkedTo) else Timing.apply(j, everyM, atStr, days).copy(scheduleId = null)
    fun styled(j: SyncJob): SyncJob = j.copy(color = pickedColor(color, data.settings.labels), label = pickedLabel(color), tags = tagDraft.all)
    // Only what was touched is written: a group opened and saved as it is stays the very same.
    val timingTouched = card == null || timed(card).let { Schedules.specOf(it) != Schedules.specOf(card) || it.scheduleId != card.scheduleId }
    val lookTouched = card == null || (card.label ?: card.color) != color || card.tags != tagDraft.all
    val changed = if (opened == null) source != null || targets.isNotEmpty() || name.isNotBlank() else draft != opened.second || timingTouched || lookTouched

    val close = rememberDiscardGuard(changed, onClose = onClose)
    // To the screen this one takes the place of: what was set here is not carried there, so it asks first, as leaving does.
    val toPrevious = rememberDiscardGuard(changed, onClose = onPrevious)
    ScreenBackHandler { close() }
    // The group deleted meanwhile (a file brought in): nothing to edit.
    if (!isNew && group == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }; return }

    // While any of its syncs runs (or a run of the whole group is under way) the group is not changed:
    // its page and its card's menu keep Edit off then too; an editor already open waits, and says why.
    val runningNow by com.xlollx.songport.sync.SyncState.running.collectAsState()
    val batchesNow by com.xlollx.songport.sync.SyncState.batches.collectAsState()
    val busy = group != null && (com.xlollx.songport.sync.SyncState.batchOf(group.id, batchesNow) != null ||
        Nway.jobsOf(data, group.id).any { it.id in runningNow })
    val missing = when (NwayEdit.missing(draft)) {
        NwayEdit.Missing.SOURCE, NwayEdit.Missing.NEW_SOURCE -> stringResource(R.string.nway_need_source)
        NwayEdit.Missing.TARGET -> stringResource(R.string.nway_need_target)
        NwayEdit.Missing.TWICE -> stringResource(R.string.nway_need_distinct)
        null -> null
    }

    /**
     * Saving, all of it or nothing. The playlists to make are made first, each kept in the draft as
     * soon as it exists (tried again after a failure, none is made twice); then the group and its
     * syncs are written as one change. Not cut short by the screen going away.
     */
    suspend fun save() {
        val description = MusicProvider.description(ctx.getString(R.string.app_name))
        val app = ctx.applicationContext
        withContext(NonCancellable + Dispatchers.IO) {
            // Checked again at the moment of saving: a run may have started since the button was tapped.
            if (group != null && (com.xlollx.songport.sync.SyncState.batchOf(group.id) != null ||
                    Nway.jobsOf(store.data, group.id).any { it.id in com.xlollx.songport.sync.SyncState.running.value }))
                throw IllegalStateException(app.getString(R.string.nway_edit_busy))
            var ready = draft
            for ((i, t) in ready.targets.withIndex()) if (t.playlistId == null) {
                val p = Providers.byId(t.provider) ?: throw IllegalStateException(t.provider)
                val made = p.createPlaylist(env, ready.name, description)
                val item = t.copy(playlistId = made.id, name = made.name, count = 0)
                ready = ready.copy(targets = ready.targets.toMutableList().also { it[i] = item })
                onMain { targets = targets.toMutableList().also { l -> if (l.getOrNull(i) == t) l[i] = item } }
            }
            val time: (SyncJob) -> SyncJob = { j -> if (timingTouched) timed(j) else j }
            val look: (SyncJob) -> SyncJob = { j -> if (lookTouched) styled(j) else j }
            if (group == null) {
                val g = NwayEdit.create(env, ready, time, look)
                Nway.jobsOf(store.data, g.id).forEach { Scheduler.apply(app, it) }
                // The first sync, as "Create and sync" always did.
                NwayUi.run(app, g)
            } else {
                val before = store.data
                val changes = NwayEdit.changes(group, Nway.members(before.jobs, group.id), ready)
                if (!store.commit { d -> NwayEdit.applied(d, group.id, ready, time, look) }) throw IllegalStateException(app.getString(R.string.bulk_not_saved))
                // The store is the truth: what could not be scheduled now is at the next start (Scheduler.applyAll).
                Scheduler.applyChanges(app, before, store.data)
                Nway.log(env, group.id, NwayEdit.logLine(changes))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.nway_editor_new else R.string.nway_editor_edit)) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (busy) stringResource(R.string.nway_edit_busy) else missing)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = close, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = { preview = true }, enabled = missing == null && !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.preview)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A shortcut to the other kind of sync, the source chosen kept: only while it is new.
            if (isNew && onClassic != null) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = false, onClick = { onClassic(source?.ref) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.nway_one_target), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                SegmentedButton(selected = true, onClick = {}, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.nway_several_targets), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            EditorStep(1, stringResource(R.string.editor_source)) {
                val s = source
                if (s == null) PrefRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.nway_choose_source), null, Modifier.testTag(NWAY_SOURCE_ROW_TAG)) { picking = "source" }
                else PlaylistRow(s, summary = listOfNotNull(nwayService(s.provider), s.count.takeIf { it >= 0 }?.let { stringResource(R.string.tracks_count, it) }).joinToString(" · "), Modifier.testTag(NWAY_SOURCE_ROW_TAG)) { picking = "source" }
            }
            EditorStep(2, stringResource(R.string.nway_targets)) {
                targets.forEachIndexed { i, t ->
                    PlaylistRow(t, summary = nwayService(t.provider) + " · " + modeLabel(t.mode), Modifier.testTag(nwayTargetTag(i)), more = true) { sheetFor = i }
                }
                PrefRow(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.nway_add_target), null, chevron = false) { picking = "add" }
                Text(stringResource(R.string.nway_source_reaches), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorStep(3, stringResource(R.string.editor_step_options)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(Invite.MAX_NAME) }, label = { Text(stringResource(R.string.editor_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(source?.name.orEmpty()) },
                )
                Text(stringResource(R.string.job_color), style = MaterialTheme.typography.labelLarge)
                JobColorPicker(color, data.settings.labels) { color = it }
                TagsEditor(tagDraft, data.syncs) { tagDraft = it }
                Text(stringResource(R.string.editor_schedule), style = MaterialTheme.typography.labelLarge)
                // A saved schedule picked from the library (every sync of the group is linked to it), or the timing set by hand.
                SchedulePicker(
                    scheduleId, scheduleSaved, card?.id.orEmpty(), hand = Schedules.spec(everyM, atStr, days),
                    onTab = { scheduleSaved = it }, onPick = { scheduleId = it },
                    onDetach = { t -> everyM = t.everyMinutes; atStr = t.at ?: "09:00"; daysCsv = t.days.joinToString(","); scheduleId = null; scheduleSaved = false },
                ) {
                    TimingPicker(everyM, atStr, daysCsv, onEvery = { everyM = it }, onAt = { atStr = it }, onDays = { daysCsv = it })
                }
            }
            // The screen this one takes the place of: every option it had is still there.
            TextButton(onClick = toPrevious, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nway_previous_screen)) }
        }
    }

    // ---- the playlist picker: the source, one more target, or another playlist for a target
    picking?.let { what ->
        val forSource = what == "source"
        val changing = what.removePrefix("change:").toIntOrNull()?.takeIf { what.startsWith("change:") }
        // The playlists already in, but the one being replaced.
        val taken = draft.items.filter { it !== (if (forSource) source else changing?.let { i -> targets.getOrNull(i) }) }.map { it.provider to it.playlistId }
        NwayAddDialog(
            taken, onDismiss = { picking = null },
            title = stringResource(if (forSource) R.string.nway_choose_source else if (changing != null) R.string.nway_change_playlist else R.string.nway_add_target),
            roles = false, allowNew = !forSource,
        ) { d ->
            val item = NwayEdit.Item(d.provider, d.playlistId, d.name, d.count)
            when {
                forSource -> source = item
                // Another playlist for this target: it follows the same way; the one it replaces leaves the sync.
                changing != null -> targets = targets.toMutableList().also { l -> if (changing in l.indices) l[changing] = item.copy(mode = l[changing].mode) }
                else -> targets = targets + item
            }
            picking = null
        }
    }

    // ---- a target's sheet: one way to follow, another playlist, the source, out
    targets.getOrNull(sheetFor)?.let { t ->
        val index = sheetFor
        fun set(mode: NwayEdit.Mode) { targets = targets.toMutableList().also { it[index] = t.copy(mode = mode) } }
        ModalBottomSheet(onDismissRequest = { sheetFor = -1 }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(
                    nwayService(t.provider) + " · " + itemTitle(t), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                )
                for ((mode, desc) in listOf(
                    NwayEdit.Mode.EXACT to R.string.nway_mode_exact_desc, NwayEdit.Mode.KEEP to R.string.nway_mode_keep_desc, NwayEdit.Mode.TWO_WAY to R.string.nway_mode_two_way_desc,
                )) PrefRow(
                    null, modeLabel(mode), stringResource(desc), Modifier.testTag(nwayModeTag(mode)), chevron = false,
                    trailing = { RadioButton(selected = t.mode == mode, onClick = { set(mode) }) },
                ) { set(mode) }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PrefRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.nway_change_playlist), null) { sheetFor = -1; picking = "change:$index" }
                // A new, empty playlist has nothing to give the others: only one that exists can be the source.
                PrefRow(Icons.Filled.SwapVert, stringResource(R.string.nway_make_source), null, chevron = false, enabled = t.playlistId != null) {
                    val next = draft.withSource(index)
                    source = next.source; targets = next.targets; sheetFor = -1
                }
                PrefRow(Icons.Filled.Delete, stringResource(R.string.nway_remove_target), null, danger = true, chevron = false) {
                    targets = targets.filterIndexed { i, _ -> i != index }; sheetFor = -1
                }
            }
        }
    }

    // ---- Preview: what saving does, then the save itself
    if (preview && missing == null) {
        if (group == null) NwayFirstRunSheet(
            draft.name,
            draft.items.map { NwayDraft(it.provider, it.playlistId, it.name, it.count, main = it === draft.source || it.mode == NwayEdit.Mode.TWO_WAY, keepMine = it.mode != NwayEdit.Mode.EXACT) },
            onDismiss = { preview = false }, onDone = { preview = false; onDone() },
        ) { save() }
        else NwayChangesSheet(
            NwayEdit.changes(group, Nway.members(data.jobs, group.id), draft), group.name.takeIf { it != draft.name }?.let { draft.name },
            onDismiss = { preview = false }, onDone = { preview = false; onDone() },
        ) { save() }
    }
}

/** For the UI tests: the two rows of "New sync"'s sheet. */
const val NEW_SYNC_CLASSIC_TAG = "new-sync-classic"
const val NEW_SYNC_MULTI_TAG = "new-sync-multi"

/**
 * "New sync" (2.1.1): which kind, before its editor. A classic sync, one playlist to another, or a
 * multi sync, one source and several targets. Each row then opens its own editor, where two buttons
 * still lead from one kind to the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSyncChooser(onDismiss: () -> Unit, onClassic: () -> Unit, onMulti: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.new_sync), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            PrefRow(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.new_sync_classic), stringResource(R.string.new_sync_classic_desc), Modifier.testTag(NEW_SYNC_CLASSIC_TAG), onClick = onClassic)
            PrefRow(Icons.Filled.Hub, stringResource(R.string.new_sync_multi), stringResource(R.string.new_sync_multi_desc), Modifier.testTag(NEW_SYNC_MULTI_TAG), onClick = onMulti)
        }
    }
}

/** A playlist of the sync in the editor: its service's mark, its name (or "New playlist"), and a line under it. */
@Composable
private fun PlaylistRow(item: NwayEdit.Item, summary: String, modifier: Modifier = Modifier, more: Boolean = false, onClick: () -> Unit) {
    PrefRow(
        null, itemTitle(item), summary, modifier, chevron = !more, leading = { ServiceLogo(Providers.byId(item.provider), 36.dp) },
        trailing = if (more) ({ Icon(Icons.Filled.MoreVert, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else null, onClick = onClick,
    )
}

/** Preview of an edited multi sync: what saving changes, playlist by playlist, before anything is; Back, or Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NwayChangesSheet(changes: List<NwayEdit.Change>, newName: String?, onDismiss: () -> Unit, onDone: () -> Unit, action: suspend () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.nway_changes_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            newName?.let { PrefRow(null, stringResource(R.string.editor_name), it, chevron = false) }
            for (c in changes) PrefRow(
                null, nwayService(c.item.provider) + " · " + itemTitle(c.item),
                when (c.kind) {
                    NwayEdit.Change.Kind.ADDED -> stringResource(R.string.nway_change_added) + " · " + modeLabel(c.item.mode)
                    NwayEdit.Change.Kind.REMOVED -> stringResource(R.string.nway_change_removed)
                    NwayEdit.Change.Kind.SOURCE -> stringResource(R.string.nway_change_source)
                    NwayEdit.Change.Kind.MODE -> (c.was?.let { modeLabel(it) + "  →  " } ?: "") + modeLabel(c.item.mode)
                },
                chevron = false, leading = { ServiceLogo(Providers.byId(c.item.provider), 36.dp) },
            )
            if (changes.isEmpty()) Text(
                stringResource(R.string.nway_change_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.nav_back)) }
                Button(enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), onClick = {
                    busy = true; error = null
                    scope.launch {
                        val r = runCatching { action() }
                        busy = false
                        r.onSuccess { onDone() }.onFailure { error = it.userMessage(ctx) }
                    }
                }) { Text(stringResource(R.string.save)) }
            }
            Spacer(Modifier.height(1.dp))
        }
    }
}

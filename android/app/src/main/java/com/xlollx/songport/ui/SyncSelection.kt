package com.xlollx.songport.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.BulkEdit
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.Timing

/**
 * Several syncs at once (#68). A long press on a card, or Select in its menu, starts a selection; a
 * tap then ticks a card, the bar at the bottom acts on every ticked sync, and Back (or the cross)
 * ends it. The rules of the changes are the engine's (sync/BulkEdit); here are the selection itself,
 * its bar and its sheets.
 */
object SyncSelection {
    /** [id] ticked, or unticked if it was. */
    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    /** The syncs shown that can be ticked ([BulkEdit.selectable]). */
    fun tickable(data: StoreData, shown: List<SyncJob>): List<String> = shown.filter { BulkEdit.selectable(data, it) }.map { it.id }

    /** Every tickable sync shown is ticked: "Select shown" then reads "Unselect shown". */
    fun allShown(selected: Set<String>, tickable: List<String>): Boolean = tickable.isNotEmpty() && tickable.all { it in selected }

    /** "Select shown": ticks all those shown, or unticks them when they all are. What is ticked and not shown stays. */
    fun toggleShown(selected: Set<String>, tickable: List<String>): Set<String> =
        if (allShown(selected, tickable)) selected - tickable.toSet() else selected + tickable

    /**
     * A sync held while selecting: [held] and every sync between it and [last], the one ticked before
     * it, in the order shown ([tickable]), ticked as well. With no such sync shown, [held] alone.
     */
    fun range(selected: Set<String>, tickable: List<String>, last: String?, held: String): Set<String> {
        val to = tickable.indexOf(held)
        if (to < 0) return selected
        val from = tickable.indexOf(last)
        if (from < 0) return selected + held
        return selected + tickable.subList(minOf(from, to), maxOf(from, to) + 1)
    }

    /** Without the syncs that are gone or can no longer be ticked (deleted, handed over meanwhile). */
    fun pruned(selected: Set<String>, data: StoreData): Set<String> {
        if (selected.isEmpty()) return selected
        val ok = data.syncs.filter { BulkEdit.selectable(data, it) }.mapTo(HashSet()) { it.id }
        return if (selected.all { it in ok }) selected else selected.filterTo(LinkedHashSet()) { it in ok }
    }

    /** The names a deletion lists: the ticked syncs and the other halves of their two-way pairs, in the list's order. */
    fun deleteNames(data: StoreData, selected: Set<String>): List<String> {
        val reach = BulkEdit.reach(data, selected)
        // A multi sync is one line, by its own name: its services' syncs are not listed one by one.
        val anchors = com.xlollx.songport.shared.Nway.anchors(data)
        val whole = BulkEdit.groups(data, selected)
        return data.jobs.filter { it.id in reach && (com.xlollx.songport.shared.SharedGroups.sharedSide(it) !in whole || it.id in anchors) }
            .map { j -> anchors[j.id]?.name ?: j.name.ifBlank { j.source.playlistName } }
    }

    /**
     * How often a timing runs, as one of the Schedule sheet's choices ([BulkEdit.FREQUENCIES]): one
     * day of the week is weekly; several days ([CHOSEN_DAYS]) and the slider's other steps are
     * themselves, and light no chip.
     */
    fun frequency(t: Schedules.Spec): Int = when {
        t.days.size == 1 -> 10080
        t.days.isNotEmpty() -> CHOSEN_DAYS
        else -> t.everyMinutes
    }

    const val CHOSEN_DAYS = -1

    /** The times of day the syncs that have one run at, in order: "07:30, 09:00, 18:00". */
    fun times(jobs: List<SyncJob>): List<String> = jobs.filter { Timing.timed(it) }.mapNotNull { it.at }.distinct().sorted()
}

/** Asked by a card's menu: start a selection with this sync. Set by the Syncs list. */
val LocalSyncSelect = staticCompositionLocalOf<((SyncJob) -> Unit)?> { null }

/** The selection's bar and its header (for the UI tests). */
const val SELECTION_BAR_TAG = "selection-bar"
const val SELECTION_HEADER_TAG = "selection-header"

/** The tick of a card while selecting (for the UI tests: its state is the card's). */
const val SELECTION_TICK_TAG = "selection-tick"

/** The ticked syncs across a rotation. */
val SyncSelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * Over a card while selecting: the whole card is the tick's target, a ticked card wears an outline
 * and its box. A sync that cannot be changed here (a shared playlist's, one on the server) is dimmed
 * and says so when tapped.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.SelectionTick(
    name: String, ticked: Boolean, tickable: Boolean, onToggle: () -> Unit, onNotHere: () -> Unit,
    /** Held down: this one and all those up to the last one ticked ([SyncSelection.range]). */
    onRange: (() -> Unit)? = null,
) {
    val rangeLabel = stringResource(R.string.bulk_select_range)
    val shape = MaterialTheme.shapes.large
    Box(
        Modifier.matchParentSize().clip(shape)
            .then(if (ticked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .then(if (!tickable) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) else Modifier)
            .testTag(SELECTION_TICK_TAG)
            .then(
                // A tap ticks or unticks; a hold ticks up to here. A checkbox still, for TalkBack and the tests.
                if (tickable && onRange != null) Modifier.semantics { role = Role.Checkbox; toggleableState = androidx.compose.ui.state.ToggleableState(ticked) }
                    .combinedClickable(onClick = onToggle, onLongClickLabel = rangeLabel, onLongClick = onRange)
                else if (tickable) Modifier.toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() })
                else Modifier.clickable(onClick = onNotHere),
            )
            .semantics { contentDescription = name },
    ) {
        if (tickable) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Checkbox(checked = ticked, onCheckedChange = null, modifier = Modifier.padding(4.dp))
        }
    }
}

/** What the Schedule sheet holds while it is open: only what was touched is set. */
data class BulkScheduleDraft(val scheduleId: String? = null, val every: Int? = null, val at: String? = null, val wifi: Boolean? = null) {
    val change: BulkEdit.ScheduleChange get() = BulkEdit.ScheduleChange(scheduleId, every, at, wifi)

    /** A saved schedule tapped: picked, with the by-hand fields back to untouched; tapped again, unpicked. */
    fun pick(id: String): BulkScheduleDraft = if (scheduleId == id) copy(scheduleId = null) else copy(scheduleId = id, every = null, at = null)

    companion object {
        /** The draft as strings, and back: what is kept while the activity is made anew. */
        fun save(d: BulkScheduleDraft): List<String> = listOf(d.scheduleId.orEmpty(), d.every?.toString().orEmpty(), d.at.orEmpty(), d.wifi?.toString().orEmpty())
        fun restore(l: List<String>): BulkScheduleDraft = BulkScheduleDraft(l[0].ifEmpty { null }, l[1].toIntOrNull(), l[2].ifEmpty { null }, l[3].ifEmpty { null }?.toBoolean())
        val Saver = listSaver<BulkScheduleDraft, String>(save = { save(it) }, restore = { restore(it) })
    }
}

/**
 * The header of a selection, in the place of the Sync-all row: the cross that ends it, "3 selected"
 * and "Select shown" (every card the search and the filters leave on screen).
 */
@Composable
fun SelectionHeader(count: Int, allShown: Boolean, onClose: () -> Unit, onToggleShown: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth().testTag(SELECTION_HEADER_TAG)) {
        Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
            Text(
                // Selecting with none ticked yet (the "Select syncs" button): what to do next.
                if (count == 0) stringResource(R.string.bulk_select_tap) else pluralStringResource(R.plurals.files_selected, count, count), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onToggleShown) { Text(stringResource(if (allShown) R.string.tracks_unselect_shown else R.string.tracks_select_shown)) }
        }
    }
}

/**
 * The selection's actions, at the bottom: Schedule, Removals, On / Off and Run now, and under
 * "More" Colour and labels and Delete.
 */
@Composable
fun SelectionBar(
    onSchedule: () -> Unit, onRemovals: () -> Unit, onOnOff: () -> Unit, onRun: () -> Unit, onColor: () -> Unit, onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var more by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 6.dp, shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().testTag(SELECTION_BAR_TAG),
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
            BarAction(Icons.Filled.Schedule, stringResource(R.string.bulk_schedule), Modifier.weight(1f), onSchedule)
            BarAction(Icons.Outlined.PlaylistRemove, stringResource(R.string.bulk_removals), Modifier.weight(1f), onRemovals)
            BarAction(Icons.Outlined.PowerSettingsNew, stringResource(R.string.bulk_on_off), Modifier.weight(1f), onOnOff)
            BarAction(Icons.Filled.PlayArrow, stringResource(R.string.run_now), Modifier.weight(1f), onRun)
            Box(Modifier.weight(1f)) {
                BarAction(Icons.Filled.MoreVert, stringResource(R.string.more_actions), Modifier.fillMaxWidth()) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bulk_color)) }, leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                        onClick = { more = false; onColor() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { more = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        // Two lines at most: in some languages a word does not fit beside four others.
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A field's state beside its name: "Different values", "Will change", "From Morning refresh". */
@Composable
private fun FieldHead(label: String, state: String?, strong: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (state != null) Surface(
            shape = MaterialTheme.shapes.small,
            color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Text(
                state, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SheetHead(title: String, count: Int) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(pluralStringResource(R.plurals.bulk_for_n, count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SheetButtons(count: Int, enabled: Boolean, onCancel: () -> Unit, onApply: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.cancel)) }
        Button(onClick = onApply, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp).testTag("bulk_apply")) {
            Text(pluralStringResource(R.plurals.bulk_apply_n, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun frequencyLabel(m: Int): String = if (m == SyncSelection.CHOSEN_DAYS) stringResource(R.string.timing_custom_days) else stepLabel(m)

/**
 * Schedule, for the selected syncs [jobs]: the saved schedules on top (one picked links them all to
 * it), then the timing by hand, where a field left alone stays as each sync has it, and Wi-Fi only,
 * each sync's own. [onApply] gets only what was touched.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkScheduleSheet(data: StoreData, jobs: List<SyncJob>, onDismiss: () -> Unit, onApply: (BulkEdit.ScheduleChange) -> Unit) {
    val ctx = LocalContext.current
    var draft by rememberSaveable(stateSaver = BulkScheduleDraft.Saver) { mutableStateOf(BulkScheduleDraft()) }
    var manage by rememberSaveable { mutableStateOf(false) }
    val timedJobs = jobs.filter { BulkEdit.scheduled(it) }
    val picked = Schedules.find(data, draft.scheduleId)
    val pickedName = picked?.let { scheduleTitle(it) }
    // What the by-hand fields start from: the schedule picked, else what the syncs have in common.
    val base = picked?.let { Schedules.specOf(it) }
    val frequencies = BulkEdit.tally(timedJobs) { SyncSelection.frequency(Schedules.specOf(it)) }
    val times = SyncSelection.times(timedJobs)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetHead(stringResource(R.string.bulk_schedule), jobs.size)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bulk_use_saved), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { manage = true }) { Text(stringResource(R.string.sched_manage)) }
            }
            if (data.settings.savedSchedules.isEmpty()) Note(stringResource(R.string.sched_empty))
            data.settings.savedSchedules.forEach { s ->
                ScheduleRow(s, Schedules.usedBy(data, s.id).size, selected = s.id == draft.scheduleId) { draft = draft.pick(s.id) }
            }
            Text(stringResource(R.string.bulk_or_by_hand), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // How often.
            val shownEvery = draft.every ?: base?.let { SyncSelection.frequency(it) } ?: frequencies.singleOrNull()?.first
            FieldHead(
                stringResource(R.string.bulk_how_often),
                when {
                    draft.every != null -> stringResource(R.string.bulk_will_change)
                    pickedName != null -> stringResource(R.string.bulk_from, pickedName)
                    frequencies.size > 1 -> stringResource(R.string.bulk_different)
                    else -> null
                },
                strong = draft.every != null,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BulkEdit.FREQUENCIES.forEach { m ->
                    FilterChip(selected = shownEvery == m, onClick = { draft = draft.copy(every = m) }, label = { Text(stepLabel(m)) }, modifier = Modifier.testTag("bulk_every_$m"))
                }
            }
            if (draft.every != null) Row(verticalAlignment = Alignment.CenterVertically) {
                val was = (base?.let { listOf(SyncSelection.frequency(it) to timedJobs.size) } ?: frequencies).map { (m, n) -> frequencyLabel(m) + " ($n)" }.joinToString(", ")
                Text(stringResource(R.string.bulk_was, was), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = draft.copy(every = null) }) { Text(stringResource(R.string.bulk_undo)) }
            }

            // Time of day.
            FieldHead(
                stringResource(R.string.bulk_time),
                when {
                    draft.at != null -> stringResource(R.string.bulk_will_change)
                    pickedName != null -> stringResource(R.string.bulk_from, pickedName)
                    times.size > 1 -> stringResource(R.string.bulk_different)
                    else -> null
                },
                strong = draft.at != null,
            )
            val shownAt = draft.at ?: base?.at ?: times.joinToString(", ").ifEmpty { null }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shownAt != null) Text(shownAt, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                OutlinedButton(onClick = {
                    val t = Timing.parseAt(draft.at ?: base?.at ?: times.firstOrNull()) ?: java.time.LocalTime.of(9, 0)
                    android.app.TimePickerDialog(ctx, { _, h, m -> draft = draft.copy(at = "%02d:%02d".format(h, m)) }, t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }) { Text(stringResource(R.string.bulk_set_time)) }
                if (draft.at != null) TextButton(onClick = { draft = draft.copy(at = null) }) { Text(stringResource(R.string.bulk_undo)) }
            }
            if (picked != null) Note(stringResource(R.string.bulk_linked_note))
            else if (draft.at == null) Note(stringResource(R.string.bulk_time_untouched))
            if (timedJobs.size < jobs.size) Note(stringResource(R.string.bulk_radio_note))

            // Wi-Fi only: each sync's own, never part of a saved schedule.
            val wifi = BulkEdit.common(jobs) { it.wifiOnly }
            FieldHead(stringResource(R.string.editor_wifi_only), if (wifi == null && draft.wifi == null) stringResource(R.string.bulk_different) else null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (wifi == null) FilterChip(selected = draft.wifi == null, onClick = { draft = draft.copy(wifi = null) }, label = { Text(stringResource(R.string.bulk_leave)) })
                FilterChip(selected = (draft.wifi ?: wifi) == true, onClick = { draft = draft.copy(wifi = true.takeIf { wifi != true }) }, label = { Text(stringResource(R.string.bulk_on_all)) }, modifier = Modifier.testTag("bulk_wifi_on"))
                FilterChip(selected = (draft.wifi ?: wifi) == false, onClick = { draft = draft.copy(wifi = false.takeIf { wifi != false }) }, label = { Text(stringResource(R.string.bulk_off_all)) }, modifier = Modifier.testTag("bulk_wifi_off"))
            }
            SheetButtons(jobs.size, enabled = !draft.change.isEmpty, onCancel = onDismiss) { onApply(draft.change) }
        }
    }
    if (manage) SchedulesDialog { manage = false }
}

/**
 * One choice for all the selected syncs (Removals, On / Off): when they differ, "Different values"
 * is the starting option and leaves each as it is; [onApply] gets the choice made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkChoiceSheet(
    title: String, count: Int, common: Boolean?, leave: String,
    yes: Pair<String, String>, no: Pair<String, String>, notes: List<String>,
    onDismiss: () -> Unit, onApply: (Boolean) -> Unit,
) {
    // "" = different values, left as they are.
    var choice by rememberSaveable { mutableStateOf(common?.toString().orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHead(title, count)
            if (common == null) ChoiceRow(stringResource(R.string.bulk_different), leave, choice.isEmpty(), "bulk_choice_leave") { choice = "" }
            ChoiceRow(yes.first, yes.second, choice == "true", "bulk_choice_yes") { choice = "true" }
            ChoiceRow(no.first, no.second, choice == "false", "bulk_choice_no") { choice = "false" }
            notes.forEach { Note(it) }
            SheetButtons(count, enabled = choice.isNotEmpty() && choice != common?.toString(), onCancel = onDismiss) { onApply(choice == "true") }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, desc: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().testTag(tag).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Removals for the selected syncs: Mirror removals or Add only; the pairs, archives and radio syncs keep their own. */
@Composable
fun BulkRemovalsSheet(jobs: List<SyncJob>, onDismiss: () -> Unit, onApply: (Boolean) -> Unit) {
    val mine = jobs.filter { BulkEdit.takesRemovals(it) }
    val mirror = mine.count { it.mirrorRemovals }
    BulkChoiceSheet(
        stringResource(R.string.bulk_removals), jobs.size, BulkEdit.common(mine) { it.mirrorRemovals },
        leave = stringResource(R.string.bulk_removals_leave, mirror, mine.size - mirror),
        yes = stringResource(R.string.editor_mirror) to stringResource(R.string.bulk_removals_mirror_desc),
        no = stringResource(R.string.bulk_add_only) to stringResource(R.string.bulk_add_only_desc),
        notes = listOfNotNull(stringResource(R.string.bulk_removals_note), stringResource(R.string.bulk_removals_skipped).takeIf { mine.size < jobs.size }),
        onDismiss = onDismiss, onApply = onApply,
    )
}

/** On or off for the selected syncs. */
@Composable
fun BulkEnabledSheet(jobs: List<SyncJob>, onDismiss: () -> Unit, onApply: (Boolean) -> Unit) {
    val on = jobs.count { it.enabled }
    BulkChoiceSheet(
        stringResource(R.string.bulk_on_off), jobs.size, BulkEdit.common(jobs) { it.enabled },
        leave = stringResource(R.string.bulk_onoff_leave, on, jobs.size - on),
        yes = stringResource(R.string.bulk_on) to stringResource(R.string.bulk_on_desc),
        no = stringResource(R.string.bulk_off) to stringResource(R.string.bulk_off_desc),
        notes = emptyList(), onDismiss = onDismiss, onApply = onApply,
    )
}

/** No swatch: the selected syncs wear different colours, and nothing was picked yet. */
private const val NO_PICK = "?"

/** Colour and labels for the selected syncs: one of the ready colours, a saved label, a colour of one's own, or none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkColorSheet(data: StoreData, jobs: List<SyncJob>, onDismiss: () -> Unit, onApply: (String?) -> Unit) {
    val common = BulkEdit.common(jobs) { it.label ?: it.color ?: "" }
    var pick by rememberSaveable { mutableStateOf(common ?: NO_PICK) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetHead(stringResource(R.string.bulk_color), jobs.size)
            FieldHead(stringResource(R.string.job_color), if (pick == NO_PICK) stringResource(R.string.bulk_different) else null)
            JobColorPicker(pick.ifEmpty { null }, data.settings.labels) { pick = it.orEmpty() }
            Spacer(Modifier.height(4.dp))
            SheetButtons(jobs.size, enabled = pick != NO_PICK && pick != common, onCancel = onDismiss) { onApply(pick.ifEmpty { null }) }
        }
    }
}

/**
 * Delete the selected syncs? Every sync that goes is named (the other half of a two-way pair too),
 * and what stays is said: the past runs in the log, the playlists on each service.
 */
@Composable
fun BulkDeleteDialog(names: List<String>, onDismiss: () -> Unit, onDelete: () -> Unit) {
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.bulk_delete_title, names.size, names.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                names.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.bulk_delete_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("bulk_delete_ok")) {
                Text(pluralStringResource(R.plurals.bulk_delete_n, names.size, names.size), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

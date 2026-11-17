package com.xlollx.songport.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.Schedules

/**
 * A change that reaches many syncs at once (a saved schedule edited or deleted, the selection's
 * actions on the Syncs tab), made whole: the store takes it for all the syncs or for none and has it
 * on disk before anything else happens (FileStore.commit), then each sync's scheduled work is brought
 * in line with it. The app closed between the two leaves nothing half done: at its next start every
 * sync's work is set again from the store (Scheduler.applyAll). What went wrong is said.
 */
object ManyChange {
    enum class Outcome { DONE, NOT_SAVED, NOT_SCHEDULED }

    fun apply(ctx: Context, change: (StoreData) -> StoreData): Outcome {
        val store = Store.get(ctx)
        val before = store.data
        if (!store.commit(change)) return Outcome.NOT_SAVED
        return if (Scheduler.applyChanges(ctx, before, store.data) > 0) Outcome.NOT_SCHEDULED else Outcome.DONE
    }

    /** The syncs [ids] deleted, both halves of a pair together, the same way: all of them or none. */
    fun delete(ctx: Context, ids: Set<String>): Outcome {
        val store = Store.get(ctx)
        val before = store.data
        if (!store.deleteJobs(com.xlollx.songport.sync.BulkEdit.reach(before, ids))) return Outcome.NOT_SAVED
        return if (Scheduler.applyChanges(ctx, before, store.data) > 0) Outcome.NOT_SCHEDULED else Outcome.DONE
    }

    /** What to tell the person when it did not all go well; null when it did. */
    fun problem(ctx: Context, outcome: Outcome): String? = when (outcome) {
        Outcome.DONE -> null
        Outcome.NOT_SAVED -> ctx.getString(R.string.bulk_not_saved)
        Outcome.NOT_SCHEDULED -> ctx.getString(R.string.bulk_not_scheduled)
    }

    /** [apply], with a failure said in a toast (where no snackbar is at hand: a dialog). True: saved. */
    fun applyOrSay(ctx: Context, change: (StoreData) -> StoreData): Boolean {
        val outcome = apply(ctx, change)
        problem(ctx, outcome)?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
        return outcome != Outcome.NOT_SAVED
    }
}

/** A saved schedule's timing in words: "Daily at 07:30", "Every 6 hours", "Sat, Sun at 10:00". */
@Composable
fun scheduleSummary(s: SavedSchedule): String = timingLabel(Schedules.probe(s))

/** How a saved schedule is listed: by its name, or by its timing when it has none. */
@Composable
fun scheduleTitle(s: SavedSchedule): String = s.name.ifBlank { scheduleSummary(s) }

/** "Used by 4 syncs", "Not used by any sync". */
@Composable
fun scheduleUsedBy(n: Int): String = if (n == 0) stringResource(R.string.sched_not_used) else pluralStringResource(R.plurals.sched_used_by, n, n)

/**
 * "Next run: Tomorrow, 07:30" for a schedule at a time of day; "Next run: Within 6 hours" for one by
 * interval, where each sync counts from its own last run.
 */
@Composable
fun scheduleNextRun(s: SavedSchedule): String {
    val ctx = LocalContext.current
    val now = remember(s) { System.currentTimeMillis() }
    val at = Schedules.nextRun(s, now)
    val text = if (at != null) DateUtils.getRelativeDateTimeString(ctx, at, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, DateUtils.FORMAT_SHOW_WEEKDAY).toString()
    else Schedules.specOf(s).everyMinutes.let { m ->
        if (m < 1440) pluralStringResource(R.plurals.sched_within_hours, m / 60, m / 60) else pluralStringResource(R.plurals.sched_within_days, m / 1440, m / 1440)
    }
    return stringResource(R.string.sched_next, text)
}

/** A row of the library and of the pickers (for the UI tests). */
const val SCHEDULE_ROW_TAG = "schedule-row"

/**
 * One saved schedule in a list: its name (or its timing), its timing (or "No name"), when it runs
 * next where [next] asks, and how many syncs use it. With [selected] set it is one choice of a
 * picker, with its radio button.
 */
@Composable
fun ScheduleRow(s: SavedSchedule, usedBy: Int, selected: Boolean? = null, next: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected == true) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().testTag(SCHEDULE_ROW_TAG).then(
            if (selected != null) Modifier.semantics { this.selected = selected; role = Role.RadioButton } else Modifier,
        ),
    ) {
        Row(Modifier.padding(start = if (selected != null) 4.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(scheduleTitle(s), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (s.name.isBlank()) stringResource(R.string.sched_no_name) else scheduleSummary(s),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (next) Text(scheduleNextRun(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(scheduleUsedBy(usedBy), style = MaterialTheme.typography.labelMedium, color = if (usedBy == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The editor's "new schedule" (its [SchedulesDialog] state and [ScheduleEditorDialog]'s). */
private const val NEW = "+"

/**
 * The library: Settings › Schedules, and "Manage" wherever a schedule is picked. Every saved
 * schedule with its timing, its next run and the syncs that use it; a tap edits it, for all of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data by Store.get(ctx).state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.sched_title)) },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.sched_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (data.settings.savedSchedules.isEmpty()) Text(stringResource(R.string.sched_empty), style = MaterialTheme.typography.bodyMedium)
                    data.settings.savedSchedules.forEach { s -> ScheduleRow(s, Schedules.usedBy(data, s.id).size, next = true) { editing = s.id } }
                    OutlinedButton(onClick = { editing = NEW }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sched_new))
                    }
                }
            }
        }
    }
    editing?.let { id ->
        val s = Schedules.find(data, id)
        // Deleted meanwhile (from another place): nothing left to edit.
        if (s == null && id != NEW) editing = null else ScheduleEditorDialog(s, onClose = { editing = null })
    }
}

/**
 * A saved schedule being made ([schedule] null) or edited: its name, if any, and the same timing
 * choices as a sync's own, without "Manual only". Saving an edited one changes every sync that uses
 * it, all together ([ManyChange]); they are listed, with the way to change only one (Detach).
 * [onSaved] gets the schedule saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorDialog(
    schedule: SavedSchedule?, onClose: () -> Unit,
    /** A new schedule opens with this timing (the sync editor's "New schedule" starts from what it holds). */
    start: Schedules.Spec? = null,
    onSaved: (SavedSchedule) -> Unit = {},
) {
    val ctx = LocalContext.current
    val data by Store.get(ctx).state.collectAsState()
    val from = schedule?.let { Schedules.specOf(it) } ?: start?.takeIf { it.automatic } ?: Schedules.Spec(1440, "09:00", emptyList())
    var name by rememberSaveable { mutableStateOf(schedule?.name.orEmpty()) }
    var every by rememberSaveable { mutableStateOf(from.everyMinutes) }
    var at by rememberSaveable { mutableStateOf(from.at ?: "09:00") }
    var daysCsv by rememberSaveable { mutableStateOf(from.days.joinToString(",")) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val days = daysCsv.split(",").mapNotNull { it.trim().toIntOrNull() }
    val users = remember(data, schedule?.id) { schedule?.let { Schedules.usedBy(data, it.id) }.orEmpty() }
    val changed = name != schedule?.name.orEmpty() || Schedules.spec(every, at, days) != from || schedule == null
    val leave = rememberDiscardGuard(schedule != null && changed || schedule == null && name.isNotBlank(), onClose = onClose)
    fun save() {
        val made = if (schedule == null) Schedules.make(name, every, at, days) else Schedules.spec(every, at, days).let { t ->
            schedule.copy(name = name.trim().take(Schedules.MAX_NAME), everyMinutes = t.everyMinutes, at = t.at, days = t.days)
        }
        if (made == null) return
        if (ManyChange.applyOrSay(ctx) { Schedules.save(it, made) }) { onSaved(made); onClose() }
    }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(if (schedule == null) R.string.sched_new else R.string.sched_edit_title)) },
                        navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                        actions = {
                            if (schedule != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.sched_delete)) }
                        },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = leave, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.cancel)) }
                            Button(onClick = { save() }, modifier = Modifier.weight(1f).height(52.dp).testTag("schedule_save")) {
                                Text(if (users.isEmpty()) stringResource(R.string.sched_save) else pluralStringResource(R.plurals.sched_save_for, users.size, users.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(Schedules.MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("schedule_name"),
                        label = { Text(stringResource(R.string.sched_name)) },
                        placeholder = { Text(timingLabel(Schedules.probe(SavedSchedule("", "", every.coerceAtLeast(60), at, days)))) },
                        supportingText = { Text(stringResource(R.string.sched_name_hint)) },
                    )
                    TimingPicker(every, at, daysCsv, onEvery = { every = it }, onAt = { at = it }, onDays = { daysCsv = it }, manual = false)
                    if (schedule != null) {
                        Text(scheduleUsedBy(users.size), style = MaterialTheme.typography.titleSmall)
                        users.forEach { j -> Text(j.name.ifBlank { j.source.playlistName }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (users.isNotEmpty()) Text(stringResource(R.string.sched_edit_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (confirmDelete && schedule != null) FitDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.sched_delete_title, scheduleTitle(schedule))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Named, so it is clear which syncs it is about; they always keep the timing they have.
                if (users.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.sched_delete_used, users.size, users.size), style = MaterialTheme.typography.bodyMedium)
                    users.forEach { j -> Text(j.name.ifBlank { j.source.playlistName }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(stringResource(R.string.sched_delete_keep), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(stringResource(R.string.sched_not_used), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                if (ManyChange.applyOrSay(ctx) { Schedules.delete(it, schedule.id) }) onClose()
            }) { Text(stringResource(R.string.sched_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The two ways to give a sync its timing (for the UI tests: the tabs' tags end in these). */
const val SCHEDULE_TAB_SAVED = "schedule_tab_saved"
const val SCHEDULE_TAB_HAND = "schedule_tab_hand"

/**
 * A sync's timing in its editor (New sync, Edit sync): "Saved schedules | Set by hand". The first
 * lists the library, to pick one (the sync is then linked to it), with "New schedule" and "Manage";
 * a sync already linked shows its schedule with Pick another, Edit (for every sync that uses it) and
 * Detach (its own copy, set by hand). The second is [byHand], the editor as it always was, and under
 * it "Save this as a schedule" while what it holds runs by itself.
 *
 * [scheduleId] is the schedule picked, or null; [saved] says which tab is shown. Nothing here
 * changes the sync: its editor's Save does. A schedule made or edited here is saved at once, as in
 * the library.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SchedulePicker(
    scheduleId: String?, saved: Boolean, jobId: String, hand: Schedules.Spec,
    onTab: (Boolean) -> Unit, onPick: (String?) -> Unit,
    /** Detach: the timing to set by hand from now on. */
    onDetach: (Schedules.Spec) -> Unit,
    byHand: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val data by Store.get(ctx).state.collectAsState()
    val schedules = data.settings.savedSchedules
    val picked = Schedules.find(data, scheduleId)
    var manage by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // The list is shown to pick from; a sync that opens linked shows its schedule until "Pick another".
    var picking by rememberSaveable { mutableStateOf(scheduleId == null) }
    var saveAs by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = saved, onClick = { onTab(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag(SCHEDULE_TAB_SAVED)) {
                Text(stringResource(R.string.sched_saved_tab), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SegmentedButton(selected = !saved, onClick = { onTab(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag(SCHEDULE_TAB_HAND)) {
                Text(stringResource(R.string.sched_by_hand_tab), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (saved) {
            if (picked != null && !picking) {
                // This sync's schedule: what it is, when it runs next, who else uses it.
                val others = Schedules.usedBy(data, picked.id).count { it.id != jobId }
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("schedule_linked")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(scheduleTitle(picked), style = MaterialTheme.typography.titleMedium)
                        if (picked.name.isNotBlank()) Text(scheduleSummary(picked), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.sched_linked), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(scheduleNextRun(picked), style = MaterialTheme.typography.bodySmall)
                        if (others > 0) Text(pluralStringResource(R.plurals.sched_also_used, others, others), style = MaterialTheme.typography.bodySmall)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.sched_pick_another)) }
                    OutlinedButton(onClick = { editing = picked.id }) { Text(stringResource(R.string.edit)) }
                    OutlinedButton(onClick = { onDetach(Schedules.specOf(picked)) }, modifier = Modifier.testTag("schedule_detach")) { Text(stringResource(R.string.sched_detach)) }
                }
                Text(
                    stringResource(R.string.sched_detach_note, scheduleSummary(picked), scheduleTitle(picked)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (schedules.isEmpty()) Text(stringResource(R.string.sched_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                schedules.forEach { s -> ScheduleRow(s, Schedules.usedBy(data, s.id).size, selected = s.id == scheduleId) { onPick(s.id) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { editing = NEW }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sched_new)) }
                    if (schedules.isNotEmpty()) TextButton(onClick = { manage = true }) { Text(stringResource(R.string.sched_manage)) }
                }
                if (picked != null) Text(
                    stringResource(R.string.sched_linked_note, scheduleTitle(picked)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.sched_wifi_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            byHand()
            // "Manual only" is not a schedule to save.
            if (hand.automatic) {
                if (!saveAs) TextButton(onClick = { saveAs = true; saveName = "" }, modifier = Modifier.testTag("schedule_save_as")) { Text(stringResource(R.string.sched_save_as)) }
                else Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sched_save_as), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = saveName, onValueChange = { saveName = it.take(Schedules.MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("schedule_name"),
                            label = { Text(stringResource(R.string.sched_name)) },
                            placeholder = { Text(timingLabel(Schedules.probe(SavedSchedule("", "", hand.everyMinutes, hand.at, hand.days)))) },
                        )
                        Text(stringResource(R.string.sched_save_as_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { saveAs = false }) { Text(stringResource(R.string.sched_not_now)) }
                            Button(onClick = {
                                val made = Schedules.make(saveName, hand.everyMinutes, hand.at, hand.days) ?: return@Button
                                if (ManyChange.applyOrSay(ctx) { Schedules.save(it, made) }) { saveAs = false; picking = true; onPick(made.id); onTab(true) }
                            }, modifier = Modifier.testTag("schedule_save_as_ok")) { Text(stringResource(R.string.sched_save)) }
                        }
                    }
                }
            }
        }
    }
    if (manage) SchedulesDialog { manage = false }
    editing?.let { id ->
        val s = Schedules.find(data, id)
        if (s == null && id != NEW) editing = null
        else ScheduleEditorDialog(s, onClose = { editing = null }, start = hand, onSaved = { made -> if (s == null) { picking = true; onPick(made.id) } })
    }
}

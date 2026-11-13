package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.label
import com.xlollx.songport.sync.Labels
import com.xlollx.songport.sync.SyncFilter
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/** The filter chips (colours, services) show from this many syncs on: with fewer, they would only crowd the list. The search itself is there from one sync on. */
const val FIND_MIN_SYNCS = 5

/** Keeps the filter across rotation and process death: only strings and lists of strings. */
val SyncFilterSaver: Saver<SyncFilter, Any> = listSaver<SyncFilter, Any>(
    save = { f ->
        listOf(
            f.query, ArrayList(f.statuses.map { it.name }), ArrayList(f.services), ArrayList(f.labels), ArrayList(f.ids), f.favorite,
            // Each tag key, then how many values, then the values.
            ArrayList(f.tags.flatMap { (k, vs) -> listOf(k, vs.size.toString()) + vs }),
        )
    },
    restore = { l ->
        SyncFilter(
            query = l[0] as String,
            statuses = (l[1] as List<*>).mapNotNull { n -> SyncFilter.Status.entries.firstOrNull { it.name == n } }.toSet(),
            services = (l[2] as List<*>).filterIsInstance<String>().toSet(),
            labels = (l[3] as List<*>).filterIsInstance<String>().toSet(),
            ids = (l.getOrNull(4) as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
            favorite = l.getOrNull(5) == true,
            tags = buildMap {
                val t = (l.getOrNull(6) as? List<*>)?.filterIsInstance<String>().orEmpty()
                var i = 0
                while (i + 1 < t.size) {
                    val n = t[i + 1].toIntOrNull() ?: break
                    put(t[i], t.drop(i + 2).take(n).toSet())
                    i += 2 + n
                }
            },
        )
    },
)

/**
 * The search of the sync list, a pill in the Syncs bar beside the mark: always there from
 * [FIND_MIN_SYNCS] syncs on, saying how many it looks through ([total]). What is typed is the
 * filter's query, so it survives a turn of the phone with the rest of the filter. It takes the width
 * the bar's buttons leave, and its placeholder is cut short before anything else gives way.
 */
@Composable
fun SyncSearchField(filter: SyncFilter, total: Int, modifier: Modifier = Modifier, onChange: (SyncFilter) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val name = stringResource(R.string.syncs_find_hint)
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    androidx.compose.foundation.text.BasicTextField(
        value = filter.query,
        onValueChange = { onChange(filter.copy(query = it)) },
        modifier = modifier.testTag(SYNC_SEARCH_TAG).semantics { contentDescription = name }
            // A sheet just closed hands the focus back to the first field on screen, this one, and the
            // keyboard came up by itself: that focus is let go. A tap on the field later works as ever.
            .onFocusChanged { f ->
                if (f.isFocused && android.os.SystemClock.uptimeMillis() - SheetFocus.closedAt < 800) { focus.clearFocus(); keyboard?.hide() }
            },
        singleLine = true,
        textStyle = style,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { inner ->
            Row(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 14.dp, end = if (filter.query.isNotEmpty()) 0.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (filter.query.isEmpty()) Text(
                        pluralStringResource(R.plurals.syncs_find_n, total, total), style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    inner()
                }
                if (filter.query.isNotEmpty()) IconButton(onClick = { onChange(filter.copy(query = "")) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.syncs_find_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/** When a sheet over the Syncs list last closed (uptime), for [SyncSearchField]. */
object SheetFocus {
    @Volatile var closedAt = 0L
    fun closed() { closedAt = android.os.SystemClock.uptimeMillis() }
}

/** The search field in the Syncs bar (for the UI tests). */
const val SYNC_SEARCH_TAG = "sync-search"

/**
 * The filter chips (starred, status, the services in use, the colours and tags in use), one after
 * the other: the caller's row lays them out, after the "Sync all" pill, and scrolls sideways.
 */
@Composable
fun SyncFinderChips(
    jobs: List<SyncJob>, filter: SyncFilter, counts: Map<SyncFilter.Status, Int> = emptyMap(),
    labels: List<com.xlollx.songport.model.Label> = emptyList(), onChange: (SyncFilter) -> Unit,
) {
    val ctx = LocalContext.current
    // The starred syncs, when there are any.
    if (jobs.any { it.favorite }) StatusChip(stringResource(R.string.filter_favorites), Icons.Filled.Star, filter.favorite) { onChange(filter.toggleFavorite()) }
    // By status, each with how many syncs it holds; a status no sync is in shows only while chosen.
    SyncFilter.Status.entries.forEach { s ->
        val n = counts[s] ?: 0
        if (n > 0 || s in filter.statuses || counts.isEmpty()) {
            val (label, icon) = when (s) {
                SyncFilter.Status.UP_TO_DATE -> R.string.filter_up_to_date to Icons.Filled.CheckCircle
                SyncFilter.Status.ERROR -> R.string.filter_errors to Icons.Filled.Warning
                SyncFilter.Status.NEEDS_FIX -> R.string.filter_needs_fix to Icons.Filled.Build
                SyncFilter.Status.RUNNING -> R.string.filter_running to Icons.Filled.Sync
                SyncFilter.Status.NEVER_RUN -> R.string.filter_never_run to Icons.Filled.Schedule
                SyncFilter.Status.DISABLED -> R.string.filter_disabled to Icons.Filled.Block
            }
            val text = stringResource(label)
            StatusChip(if (counts.isEmpty()) text else "$text · $n", icon, s in filter.statuses) { onChange(filter.toggle(s)) }
        }
    }
    val services = SyncFilter.servicesInUse(jobs)
    if (services.size > 1) {
        services.forEach { id ->
            val p = Providers.byId(id)
            FilterChip(
                selected = id in filter.services,
                onClick = { onChange(filter.toggleService(id)) },
                label = { Text(p?.label(ctx) ?: id, maxLines = 1) },
                // The mark says which service, the label says it in words: the mark itself stays silent.
                leadingIcon = { Box(Modifier.clearAndSetSemantics {}) { ServiceLogo(p, FilterChipDefaults.IconSize) } },
            )
        }
    }
    // By label: a named one says its name; a ready colour nobody named stays a dot. A colour of
    // the user's own without a name is no label, and has no chip.
    Labels.inUse(jobs).forEach { id ->
        val l = Labels.find(labels, id) ?: return@forEach
        if (l.name.isBlank()) ColorDot(l.color, id in filter.labels) { onChange(filter.toggleLabel(id)) }
        else FilterChip(
            selected = id in filter.labels,
            onClick = { onChange(filter.toggleLabel(id)) },
            label = { Text(l.name, maxLines = 1) },
            leadingIcon = {
                Box(Modifier.size(FilterChipDefaults.IconSize).clip(CircleShape).background(jobAccent(l.color) ?: MaterialTheme.colorScheme.outline).clearAndSetSemantics {})
            },
        )
    }
    // By tag: one chip per key. A key with values opens them; a key alone is on or off.
    Labels.keysInUse(jobs).forEach { key -> TagChip(key, Labels.valuesInUse(jobs, key), filter, onChange) }
}

/** With a filter on: how many cards are shown, with a way to clear it all. */
@Composable
fun SyncFinderSummary(total: Int, shown: Int, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.filter_shown, total, shown, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.filter_clear)) }
    }
}

@Composable
private fun StatusChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(if (selected) Icons.Filled.Check else icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/**
 * A tag key to filter by. With values in use, a tap opens them ("Any" for the key whatever its
 * value); the chip then reads "genre: metal". A key no sync gives a value is a plain on/off chip.
 */
@Composable
private fun TagChip(key: String, values: List<String>, filter: SyncFilter, onChange: (SyncFilter) -> Unit) {
    // The filter keeps keys as first chosen; compared without case, as the tags are.
    val on = filter.tags.keys.firstOrNull { Labels.fold(it) == Labels.fold(key) }
    val chosen = on?.let { filter.tags[it] }.orEmpty()
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = on != null,
            onClick = { if (values.isEmpty()) onChange(filter.toggleTag(on ?: key)) else open = true },
            label = { Text(if (chosen.isEmpty()) key else "$key: " + chosen.joinToString(", "), maxLines = 1) },
            leadingIcon = if (on != null) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            trailingIcon = if (values.isNotEmpty()) ({ Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_tag_any)) },
                leadingIcon = { if (on != null && chosen.isEmpty()) Icon(Icons.Filled.Check, null) },
                onClick = { open = false; onChange(if (on != null && chosen.isEmpty()) filter.toggleTag(on) else filter.copy(tags = filter.tags - (on ?: key) + (key to emptySet()))) },
            )
            values.forEach { v ->
                val ticked = chosen.any { Labels.fold(it) == Labels.fold(v) }
                DropdownMenuItem(
                    text = { Text(v) },
                    leadingIcon = { if (ticked) Icon(Icons.Filled.Check, null) },
                    onClick = { onChange(filter.toggleTag(on ?: key, chosen.firstOrNull { Labels.fold(it) == Labels.fold(v) } ?: v)) },
                )
            }
        }
    }
}

/** A colour to filter by: a dot in a 48dp target, ticked when on; TalkBack reads its name. */
@Composable
private fun ColorDot(key: String, selected: Boolean, onToggle: () -> Unit) {
    // The colour the cards wear (a colour of one's own as its card shows it).
    val color = jobAccent(key) ?: MaterialTheme.colorScheme.outline
    val label = JobColor.of(key)?.let { stringResource(it.label) } ?: (stringResource(R.string.job_color_custom) + " " + key)
    val name = stringResource(R.string.filter_color, label)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(color)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = if (color.luminance() < 0.45f) Color.White else Color.Black)
        }
    }
}

/** Nothing left with this filter: a friendly line and a way back to every sync. */
@Composable
fun SyncFinderEmpty(onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.SearchOff, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.filter_none), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.filter_clear))
        }
    }
}

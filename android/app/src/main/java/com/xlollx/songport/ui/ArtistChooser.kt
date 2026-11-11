package com.xlollx.songport.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.sync.ArtistPick
import com.xlollx.songport.sync.Concerts
import java.time.ZoneId

/** A field that filters a list of artists by name (case and accents ignored, see [ArtistPick.named]). */
@Composable
internal fun ArtistSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        label = { Text(stringResource(R.string.artists_search)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The playlist's artists, to look up only some of them: ticked ones (none ticked = the whole
 * playlist) and one more typed by name. [all] null while the playlist is being read.
 */
@Composable
internal fun ArtistChooser(all: List<String>?, ticked: Set<String>, typed: String, onTicked: (Set<String>) -> Unit, onTyped: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(all) { ArtistPick.byName(all.orEmpty()) { it } }
    val n = ArtistPick.chosen(all.orEmpty(), ticked, typed).size
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ticked.isEmpty() && typed.isBlank()) stringResource(R.string.artists_whole_playlist)
                    else stringResource(R.string.artists_chosen, n, all?.size ?: 0),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, stringResource(R.string.artists_choose), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!open) return@Column
            Text(stringResource(R.string.artists_choose_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = typed, onValueChange = onTyped, singleLine = true,
                label = { Text(stringResource(R.string.artists_typed)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (sorted.isEmpty()) return@Column
            ArtistSearchField(query) { query = it }
            val shown = ArtistPick.named(sorted, query) { it }
            Row {
                TextButton(onClick = { onTicked(ticked + shown) }) { Text(stringResource(R.string.transfer_select_all)) }
                TextButton(onClick = { onTicked(ticked - shown.toSet()) }) { Text(stringResource(R.string.transfer_select_none)) }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                shown.forEach { a ->
                    val on = a in ticked
                    Row(Modifier.fillMaxWidth().clickable { onTicked(if (on) ticked - a else ticked + a) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { onTicked(if (on) ticked - a else ticked + a) })
                        Text(a, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * Opens the calendar app on a new entry for [e], filled in ([Concerts.calendarEntry]): no calendar
 * permission, the user saves it there. False when no app takes it.
 */
internal fun addToCalendar(ctx: Context, e: Concerts.Event): Boolean {
    // MusicBrainz says no time zone: the country's, when it has only one; else the phone's.
    val countryZone = runCatching {
        android.icu.util.TimeZone.getAvailableIDs(android.icu.util.TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, e.country, null)
            .singleOrNull()?.let { ZoneId.of(it) }
    }.getOrNull()
    val c = Concerts.calendarEntry(e, countryZone ?: ZoneId.systemDefault())
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, c.allDay)
        .putExtra(CalendarContract.Events.TITLE, c.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, c.location)
        .putExtra(CalendarContract.Events.DESCRIPTION, c.description)
        .putExtra(CalendarContract.Events.EVENT_TIMEZONE, c.timeZone)
    if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { ctx.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
}

package com.xlollx.songport.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.LaunchedEffect
import com.xlollx.songport.platform.env
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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
 * Which artists a Discover search looks up: the ones played most (the default, capped), or the ones
 * the user [picked] from the playlist or added by name. Kept across rotation ([rememberArtistChoice])
 * and per playlist ([forPlaylist]).
 */
@Stable
internal class ArtistChoice(choose: Boolean = false, picked: Set<String> = emptySet(), of: String? = null) {
    var choose by mutableStateOf(choose)
    var picked by mutableStateOf(picked)
    private var of: String? = of

    /** The picked artists, or null for the ones played most: what [ArtistPick.chosen] takes. */
    val pickedOrNull: Set<String>? get() = picked.takeIf { choose }

    /** A search can start: most played, or at least one artist chosen. */
    val ready: Boolean get() = !choose || picked.isNotEmpty()

    /** Another playlist ([key]) starts again from the ones played most; the same one keeps the choice. */
    fun forPlaylist(key: String) {
        if (key == of) return
        of = key; choose = false; picked = emptySet()
    }

    companion object {
        val Saver = listSaver<ArtistChoice, Any?>(
            save = { listOf(it.choose, ArrayList(it.picked), it.of) },
            restore = { @Suppress("UNCHECKED_CAST") ArtistChoice(it[0] as Boolean, LinkedHashSet(it[1] as List<String>), it[2] as String?) },
        )
    }
}

@Composable
internal fun rememberArtistChoice(): ArtistChoice = rememberSaveable(saver = ArtistChoice.Saver) { ArtistChoice() }

/**
 * The search button's words, saying what it will do: [chosen] for N artists (plurals), [top] for
 * the N played most, [plain] while the playlist's artists are being read.
 */
@Composable
internal fun artistsAction(all: List<String>?, choice: ArtistChoice, cap: Int, plain: Int, chosen: Int, top: Int): String {
    if (all == null && !choice.choose) return stringResource(plain)
    val list = all.orEmpty()
    val n = ArtistPick.count(list, choice.pickedOrNull, cap)
    return if (!choice.choose && list.size > cap) stringResource(top, n) else pluralStringResource(chosen, n, n)
}

/** Most chips shown on the page; the rest are counted on a chip that opens the list. */
private const val CHIPS = 12

/**
 * The artists a search looks up: the ones played most ([cap] of the playlist's [all], null while it
 * is read) or the chosen ones, as chips, picked in a sheet with one search field.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistChooser(all: List<String>?, choice: ArtistChoice, cap: Int) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.artists_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !choice.choose, onClick = { choice.choose = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.artists_mode_top), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SegmentedButton(selected = choice.choose, onClick = { choice.choose = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.artists_choose), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val hintStyle = MaterialTheme.typography.bodySmall
            val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
            val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            if (!choice.choose) {
                val n = all?.size ?: return@Column
                Text(
                    if (n > cap) stringResource(R.string.artists_top_hint, cap, n) else pluralStringResource(R.plurals.artists_all_hint, n, n),
                    style = hintStyle, color = hintColor, modifier = live,
                )
                return@Column
            }
            val picked = ArtistPick.chosen(all.orEmpty(), choice.picked).let { if (all == null) choice.picked.toList() else it }
            Text(
                if (picked.isEmpty()) stringResource(R.string.artists_none_chosen) else pluralStringResource(R.plurals.artists_chosen_n, picked.size, picked.size),
                style = hintStyle, color = hintColor, modifier = live,
            )
            if (picked.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                picked.take(CHIPS).forEach { a ->
                    val remove = stringResource(R.string.artists_remove, a)
                    InputChip(
                        selected = false, onClick = { choice.picked = choice.picked - a },
                        label = { Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(InputChipDefaults.IconSize)) },
                        modifier = Modifier.semantics { contentDescription = remove },
                    )
                }
                if (picked.size > CHIPS) AssistChip(onClick = { sheet = true }, label = { Text("+${picked.size - CHIPS}") })
            }
            OutlinedButton(onClick = { sheet = true }, enabled = all != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.artists_add))
            }
        }
    }
    if (sheet && all != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state) {
            ArtistSheetContent(all, choice.picked, { choice.picked = it }) { sheet = false }
        }
    }
}

/**
 * The inside of the sheet that picks artists: one field that filters the playlist's [all] (and can
 * add a name it does not have), the list with checkboxes, and Done. Changes apply at once.
 */
@Composable
internal fun ArtistSheetContent(all: List<String>, picked: Set<String>, onPicked: (Set<String>) -> Unit, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val rows = remember(all, picked, query) { ArtistPick.rows(all, picked, query) }
    val addable = remember(all, picked, query) { ArtistPick.addable(all, picked, query) }
    fun add(name: String) { onPicked(ArtistPick.add(all, picked, name)); query = "" }
    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.artists_choose), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            Text(
                pluralStringResource(R.plurals.artists_chosen_n, picked.size, picked.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isEmpty()) null else ({
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.artists_search_clear)) }
            }),
            label = { Text(stringResource(R.string.artists_sheet_search)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (rows.isEmpty() && addable != null) add(addable)
                keyboard?.hide()
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { onPicked(picked + rows) }, enabled = rows.isNotEmpty()) { Text(stringResource(R.string.artists_select_shown)) }
            TextButton(onClick = { onPicked(picked - rows.toSet()) }, enabled = rows.any { it in picked }) { Text(stringResource(R.string.artists_clear)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it }) { a ->
                val on = a in picked
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = on, role = Role.Checkbox) { onPicked(if (on) picked - a else picked + a) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Text(a, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (addable != null) item(key = 0) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { add(addable) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(horizontal = 12.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.artists_add_free, addable), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 8.dp)) {
            Text(stringResource(R.string.artists_done, picked.size))
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

/** Discover shows pictures: the setting is on, and on mobile data unless it says Wi-Fi only. */
internal fun discoverImagesOn(ctx: Context): Boolean {
    val s = ctx.env.store.data.settings
    return s.discoverImages && (!s.discoverImagesWifiOnly || com.xlollx.songport.sync.onUnmetered(ctx))
}

/**
 * A small picture from [url] (a concert's, a record's cover) in a box of the given size, so nothing
 * moves when it arrives: a plain placeholder until then, or for good when it does not load. With no
 * [url] (or one that does not load), the address [fallback] finds, if any.
 */
@Composable
internal fun DiscoverImage(url: String, width: Dp, height: Dp, fallback: (suspend () -> String?)? = null) {
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bmp = url.takeIf { it.isNotBlank() }?.let { Cards.bitmap(it, 300) }
            ?: fallback?.invoke()?.let { Cards.bitmap(it, 300) }
    }
    Box(Modifier.size(width, height).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

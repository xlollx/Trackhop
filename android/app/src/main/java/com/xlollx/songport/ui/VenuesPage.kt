package com.xlollx.songport.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.DiscoverSource
import com.xlollx.songport.sync.ArtistAlerts
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.Venues
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The venue search and the nights to come, kept while the app runs. */
internal object VenuesKept {
    val search = KeptWork<List<Venues.Venue>>()
    val upcoming = KeptWork<Venues.Upcoming>()
}

/** Test tag of the venue search field. */
const val VENUE_SEARCH_TAG = "venueSearch"

private val NIGHT = DateTimeFormatter.ofPattern("d MMM")

/**
 * Concerts › Venues: venues searched ([Venues.search]) and followed, the city typed, and who plays
 * there in the next weeks, whoever it is; the heart follows the artist (the same list as Followed
 * artists). "Concerts near you" is a playlist of who plays, the source of a sync ([DiscoverSource.NEARBY]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VenuesTab(snackbar: SnackbarHostState, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val store = Store.get(ctx)
    val settings = store.state.collectAsState().value.settings
    val followed = settings.followedVenues
    val k = VenuesKept
    var query by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf(settings.concertCity) }
    var country by rememberSaveable { mutableStateOf(Concerts.savedCountry(env) ?: defaultConcertCountry(ctx)) }
    var keyOpen by rememberSaveable { mutableStateOf(false) }
    if (keyOpen) TicketmasterKeyDialog { keyOpen = false }

    fun message(e: Exception) = when (e) {
        is Concerts.KeyRefused -> ctx.getString(R.string.concerts_key_refused)
        is Concerts.QuotaGone -> ctx.getString(R.string.concerts_quota)
        else -> e.userMessage(ctx)
    }
    fun search() {
        val q = query.trim(); val c = country
        if (q.length < 2) return
        k.search.start(q) { _ -> try { Venues.search(env, q, c) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw Exception(message(e)) } }
    }
    val watch = followed.map { it.id }.joinToString(",") + "|" + settings.concertCity + "|" + country
    fun look() {
        val s = store.data.settings
        if (s.followedVenues.isEmpty() && s.concertCity.isBlank()) { k.upcoming.clear(); return }
        val c = country
        k.upcoming.start(watch) { progress -> Venues.upcoming(env, s.followedVenues, s.concertCity, c, progress = progress) }
    }
    // The venues, the city or the country changed (or the tab opened): looked at again; a day's answers are kept.
    LaunchedEffect(watch) { if (k.upcoming.key != watch || k.upcoming.result == null) look() }

    Text(stringResource(R.string.venues_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    CountryPicker(country) { country = it; Concerts.saveCountry(env, it) }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.venues_search)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
        trailingIcon = { TextButton(onClick = { search() }, enabled = query.trim().length >= 2 && !k.search.running) { Text(stringResource(R.string.venues_search_go)) } },
        modifier = Modifier.fillMaxWidth().testTag(VENUE_SEARCH_TAG),
    )
    k.search.error?.let { ToolError(it.ifBlank { stringResource(R.string.error_generic) }) }
    val found = k.search.result?.takeIf { k.search.key == query.trim() }
    if (found != null) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            if (found.isEmpty()) Text(stringResource(R.string.venues_none_found), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            // What was typed may be a city: its concerts are looked at too.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.venues_city_use, query.trim()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { city = query.trim(); store.updateSettings { it.copy(concertCity = query.trim()) } }) { Text(stringResource(R.string.venues_follow)) }
            }
            found.forEach { v ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (v.city.isNotBlank()) Text(v.city, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (Venues.isFollowed(followed, v)) Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
                    else TextButton(onClick = { store.updateSettings { it.copy(followedVenues = Venues.toggle(it.followedVenues, v)) }; Scheduler.applyArtistAlerts(ctx) }) { Text(stringResource(R.string.venues_follow)) }
                }
            }
        }
    }
    if (followed.isNotEmpty() || settings.concertCity.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        followed.forEach { v ->
            InputChip(selected = true, onClick = { store.updateSettings { it.copy(followedVenues = Venues.toggle(it.followedVenues, v)) }; Scheduler.applyArtistAlerts(ctx) },
                label = { Text(v.name) }, trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.venues_unfollow, v.name), Modifier.size(18.dp)) })
        }
        if (settings.concertCity.isNotBlank()) InputChip(selected = true, onClick = { city = ""; store.updateSettings { it.copy(concertCity = "") } },
            label = { Text(stringResource(R.string.venues_city) + ": " + settings.concertCity) },
            trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.venues_unfollow, settings.concertCity), Modifier.size(18.dp)) })
    } else ToolEmpty(stringResource(R.string.venues_empty), body = stringResource(R.string.venues_no_location))

    if (k.upcoming.running) ToolLoading(stringResource(R.string.venues_reading), progress = k.upcoming.progress?.takeIf { it.second > 0 }?.let { it.first.toFloat() / it.second })
    val up = k.upcoming.result?.takeIf { k.upcoming.key == watch }
    if (up != null && !k.upcoming.running) {
        ToolSectionTitle(stringResource(R.string.venues_next_weeks, Venues.WEEKS))
        val notes = buildList {
            if (up.keyRefused) add(stringResource(R.string.concerts_key_refused))
            if (up.quotaHit) add(stringResource(R.string.concerts_quota))
            if (!up.ticketmaster) add(stringResource(R.string.venues_no_key))
            else if (up.unanswered > 0 && !up.keyRefused && !up.quotaHit) add(stringResource(R.string.venues_some_unanswered))
        }
        notes.forEach { ToolNote(it) }
        if (up.keyRefused || !up.ticketmaster) TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.concerts_key_title)) }
        if (up.nights.isEmpty()) Text(stringResource(R.string.venues_no_events), style = MaterialTheme.typography.bodyMedium)
        else Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                up.nights.forEachIndexed { i, n ->
                    if (i > 0) HorizontalDivider()
                    NightRow(n, settings.followedArtists)
                }
            }
        }
        Text(stringResource(if (up.ticketmaster) R.string.concerts_sources_both else R.string.concerts_sources_mb), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val name = stringResource(R.string.venues_playlist_name)
        ToolPrimaryButton(stringResource(R.string.venues_playlist), onClick = {
            // Who played is gone from the playlist at the next run: it follows what is on.
            onNewSync { copy(name = name, source = PlaylistRef(DiscoverSource.SERVICE, DiscoverSource.NEARBY, name), schedule = Schedule.DAILY, mirrorRemovals = true) }
        })
        Text(stringResource(R.string.venues_playlist_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.venues_no_location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One night: its day, who plays, where, whether the user follows them, and the heart that follows or stops. */
@Composable
private fun NightRow(n: Venues.Night, followedArtists: List<ArtistAlerts.Followed>) {
    val ctx = LocalContext.current
    val e = n.event
    val on = ArtistAlerts.isFollowed(followedArtists, e.artist)
    Row(Modifier.fillMaxWidth().clickable(enabled = e.url.isNotBlank()) { openUrl(ctx, e.url) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(runCatching { LocalDate.parse(e.date).format(NIGHT) }.getOrDefault(e.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text(e.lineup.ifEmpty { listOf(e.artist) }.joinToString(", "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                listOf(n.venue?.name ?: e.venue.ifBlank { e.city }, stringResource(if (on) R.string.venues_you_follow else R.string.venues_not_followed)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { toggleFollow(ctx, e.artist) }) {
            Icon(if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(if (on) R.string.alerts_unfollow else R.string.alerts_follow, e.artist),
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

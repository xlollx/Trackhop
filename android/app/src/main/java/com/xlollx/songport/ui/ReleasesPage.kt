package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.NewReleasesProvider
import com.xlollx.songport.providers.importTracks
import com.xlollx.songport.sync.NewReleases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the New releases page found and is doing, for as long as the app runs: a search of fifty
 * artists takes a minute (MusicBrainz answers once a second), and going to another tab must not
 * stop it or lose it.
 */
internal object ReleasesKept {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pick by mutableStateOf<CardPick?>(null)
    var days by mutableStateOf(30)
    var upcoming by mutableStateOf(false)
    var result by mutableStateOf<NewReleases.Result?>(null)
    /** The releases left out of the playlist (unticked), by link. */
    var unticked by mutableStateOf<Set<String>>(emptySet())
    /** "done/total" while artists are looked up or tracks read; null when idle. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var readingTracks by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** "Add to an existing playlist" open, and the playlist picked to add to. */
    var addOpen by mutableStateOf(false)
    var addTo by mutableStateOf<CardPick?>(null)
    /** The search going on, what it found so far, and where it was when the user stopped it. */
    var job: Job? = null
    var found: NewReleases.Result? = null
    var stopped by mutableStateOf<Pair<Int, Int>?>(null)
    /** Counts the searches: a stopped one still finishing a request no longer touches the page. */
    var runs = 0

    /** Stops the search going on: its requests are cancelled, what it found so far is the result. */
    fun stop() {
        val j = job?.takeIf { it.isActive } ?: return
        val at = progress ?: (0 to 0)
        j.cancel()
        job = null
        runs++
        progress = null
        found?.let { result = it }
        stopped = at
    }
}

private fun typeName(ctx: Context, t: NewReleases.Type) = ctx.getString(
    when (t) {
        NewReleases.Type.ALBUM -> R.string.releases_type_album
        NewReleases.Type.EP -> R.string.releases_type_ep
        NewReleases.Type.SINGLE -> R.string.releases_type_single
    },
)

/**
 * Discover › New releases: a playlist of the user's (any service, or a pasted link), a window of
 * 7, 30 or 90 days and, if wanted, what is announced; the albums, EPs and singles its artists put
 * out ([NewReleases]). The ticked ones become a File playlist that a new sync takes to any
 * service, a new playlist or one of the user's ("New releases from <playlist>"); or a weekly sync
 * keeps such a playlist filled ([NewReleasesProvider]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ReleasesPage(snackbar: SnackbarHostState, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val k = ReleasesKept
    k.error?.let { e -> androidx.compose.runtime.LaunchedEffect(e) { snackbar.showSnackbar(e); k.error = null } }
    val busy = k.progress != null

    Text(stringResource(R.string.releases_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "releases") { p ->
        if (p?.playlist?.id != k.pick?.playlist?.id || p?.provider?.id != k.pick?.provider?.id) { k.result = null; k.unticked = emptySet() }
        k.pick = p
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NewReleases.WINDOWS.forEach { d ->
            FilterChip(selected = k.days == d, enabled = !busy, onClick = { k.days = d; k.result = null }, label = { Text(stringResource(R.string.releases_window, d)) })
        }
        FilterChip(selected = k.upcoming, enabled = !busy, onClick = { k.upcoming = !k.upcoming; k.result = null }, label = { Text(stringResource(R.string.releases_upcoming)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        k.progress?.let { (done, total) ->
            CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (k.readingTracks) R.string.releases_reading_tracks else R.string.releases_progress, done, total),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
        }
        // A search going on can be stopped (it goes on when the page is left): what it found stays.
        if (busy && !k.readingTracks) OutlinedButton(onClick = { k.stop() }) {
            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.search_stop))
        }
        else Button(enabled = k.pick != null && !busy, onClick = { find(ctx) }) { Text(stringResource(R.string.releases_find)) }
    }

    val r = k.result ?: return
    if (!busy) SearchStopped(k.stopped)
    if (r.unanswered > 0) Text(
        pluralStringResource(R.plurals.releases_unanswered, r.unanswered, r.unanswered),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    )
    if (r.releases.isEmpty()) {
        Text(stringResource(R.string.releases_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(pluralStringResource(R.plurals.releases_count, r.releases.size, r.releases.size), style = MaterialTheme.typography.titleSmall)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            r.releases.forEach { rel ->
                val coming = NewReleases.isUpcoming(rel)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { openUrl(ctx, rel.link) }.padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    // Announced releases have no tracks yet: nothing to tick.
                    Checkbox(
                        checked = !coming && rel.link !in k.unticked, enabled = !coming && !busy,
                        onCheckedChange = { on -> k.unticked = if (on) k.unticked - rel.link else k.unticked + rel.link },
                    )
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(rel.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(rel.artist, typeName(ctx, rel.type), rel.date).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (coming) StatusPill(stringResource(R.string.releases_upcoming), Tone.Accent)
                    FollowBell(rel.artist)
                }
            }
        }
    }
    val chosen = r.releases.filter { !NewReleases.isUpcoming(it) && it.link !in k.unticked }
    val pick = k.pick
    val name = stringResource(R.string.releases_playlist_name, pick?.playlist?.name.orEmpty())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(enabled = chosen.isNotEmpty() && !busy, onClick = { k.addOpen = !k.addOpen }) { Text(stringResource(R.string.releases_add_existing)) }
        Button(enabled = chosen.isNotEmpty() && !busy, onClick = { makePlaylist(ctx, name, chosen, onNewSync) }) { Text(stringResource(R.string.releases_make)) }
    }
    if (k.addOpen) {
        // A sync from the releases into the playlist picked: it adds only the songs not already there.
        Text(stringResource(R.string.releases_add_existing_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlaylistSource(stringResource(R.string.releases_add_existing_target), null, snackbar, memory = "releases_add") { k.addTo = it }
        val to = k.addTo?.takeIf { it.pastedLink == null }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(enabled = to != null && chosen.isNotEmpty() && !busy, onClick = {
                val target = PlaylistRef(provider = to!!.provider.id, playlistId = to.playlist.id, playlistName = to.playlist.name)
                makePlaylist(ctx, name, chosen, onNewSync, target)
            }) { Text(stringResource(R.string.releases_add_existing_go)) }
        }
    }
    if (pick != null) {
        Text(stringResource(R.string.releases_weekly_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(enabled = !busy, onClick = {
                // The window is the one chosen; a week apart, 7 days would do, a longer one forgives a missed run.
                val src = PlaylistRef(NewReleasesProvider.SERVICE, NewReleasesProvider.id(k.days, pick.provider.id, pick.playlist.id), name)
                onNewSync { copy(name = name, source = src, schedule = Schedule.WEEKLY, archive = true) }
            }) { Text(stringResource(R.string.releases_weekly)) }
        }
    }
}

private fun find(ctx: Context) {
    val k = ReleasesKept
    val p = k.pick ?: return
    val app = ctx.applicationContext
    if (k.job?.isActive == true) return
    k.progress = 0 to 0; k.readingTracks = false; k.result = null; k.unticked = emptySet(); k.stopped = null; k.found = null
    // Stopped ([ReleasesKept.stop]): this run no longer touches the page, a new one may be going.
    val run = ++k.runs
    fun mine() = run == k.runs
    k.job = k.scope.launch {
        try {
            val artists = NewReleases.artists(p.provider.tracks(app.env, p.playlist.id)).also { com.xlollx.songport.sync.ArtistSuggest.note(app.env, it) }
            val r = withContext(Dispatchers.IO) {
                NewReleases.find(app.env, artists, k.days, k.upcoming, partial = { if (mine()) k.found = it }) { done, total -> if (mine()) k.progress = done to total }
            }
            if (mine()) k.result = r
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (mine()) k.error = e.userMessage(app)
        } finally {
            if (mine()) { k.progress = null; k.job = null }
        }
    }
}

/** The ticked releases' tracks, as a File playlist, and the editor of a new sync from it. */
private fun makePlaylist(ctx: Context, name: String, chosen: List<NewReleases.Release>, onNewSync: (SyncJob.() -> SyncJob) -> Unit, target: PlaylistRef? = null) {
    val k = ReleasesKept
    val app = ctx.applicationContext
    k.progress = 0 to chosen.size; k.readingTracks = true
    k.scope.launch {
        try {
            val tracks = withContext(Dispatchers.IO) { NewReleases.tracksOf(app.env, chosen) { done, total -> k.progress = done to total } }
            if (tracks.isEmpty()) k.error = app.getString(R.string.releases_no_tracks)
            else {
                val id = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(app, name, tracks) }
                val source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id)
                onNewSync { if (target == null) copy(name = name, source = source) else copy(name = target.playlistName.ifBlank { name }, source = source, target = target, mirrorRemovals = false) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            k.error = e.userMessage(app)
        }
        k.progress = null; k.readingTracks = false
    }
}

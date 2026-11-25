package com.xlollx.songport.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayChanges
import com.xlollx.songport.sync.Matcher

/** A multi sync's last run, for the lists behind its added and removed figures: its syncs' reports, and its playlists (the source first). */
internal class GroupRunLists(val batch: List<SyncReport>, val members: List<Nway.Member>)

/**
 * What a multi sync's last run added or removed, one row per song ([NwayChanges]) rather than once
 * per target: the song, the logos of the playlists it changed on and their names ("removed from all
 * 4"); a tap lists each playlist with the name its service gives the song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupStatListSheet(title: String, r: SyncReport, lists: GroupRunLists, added: Boolean, onDismiss: () -> Unit) {
    val data by Store.get(LocalContext.current).state.collectAsState()
    val songs = remember(lists, added, data.matchCache) {
        NwayChanges.group(
            NwayChanges.changes(data, lists.batch, lists.members, if (added) NwayChanges.Kind.ADDED else NwayChanges.Kind.REMOVED),
            lists.members.map { it.down.id },
        )
    }
    val byId = remember(lists) { lists.members.associateBy { it.down.id } }
    val targets = remember(lists) { lists.members.drop(1).map { it.down.id } }
    val changes = songs.sumOf { it.changes.size }
    // What the playlists' runs counted but did not list (a capped list, or a run from before the lists).
    val unlisted = lists.batch.filter { it.jobId in byId }.sumOf { rep ->
        (if (added) rep.added - rep.addedTracks.size else rep.removed - rep.removedTracks.size).coerceAtLeast(0)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            val counts = if (songs.isEmpty()) null else pluralStringResource(R.plurals.setlists_songs, songs.size, songs.size) + ", " +
                pluralStringResource(if (added) R.plurals.stat_list_additions else R.plurals.stat_list_removals, changes, changes)
            Text(listOfNotNull(formatDate(r.startedEpoch), counts).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (songs.isEmpty()) Text(stringResource(R.string.stat_list_unavailable), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(songs.size) { i -> GroupChangeRow(songs[i], added, byId, targets) }
                if (songs.isNotEmpty() && unlisted > 0) item {
                    Text(stringResource(R.string.stat_list_more, unlisted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** One song: its name, then its playlists' logos and names; a tap shows what each service calls it. */
@Composable
private fun GroupChangeRow(song: NwayChanges.Song, added: Boolean, byId: Map<String, Nway.Member>, targets: List<String>) {
    var open by remember(song) { mutableStateOf(false) }
    val n = song.members.size
    val summary = if (NwayChanges.everyTarget(song, targets))
        pluralStringResource(if (added) R.plurals.nway_added_to_all else R.plurals.nway_removed_from_all, n, n)
    else pluralStringResource(if (added) R.plurals.nway_added_to else R.plurals.nway_removed_from, n, n, song.members.map { id -> byId[id]?.let { nwayMember(it) } ?: id }.joinToString(", "))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }
            .padding(horizontal = 8.dp, vertical = 6.dp).animateContentSize(),
    ) {
        Text(song.name.toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            song.members.forEach { id ->
                val provider = byId[id]?.playlist?.provider
                ProviderBadge(provider?.let { Providers.byId(it) }, 16.dp, accountRing(provider))
                Spacer(Modifier.width(2.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(top = 6.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            song.changes.forEach { c ->
                val m = c.member?.let { byId[it] }
                val provider = m?.playlist?.provider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderBadge(provider?.let { Providers.byId(it) }, 16.dp, accountRing(provider))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(m?.let { nwayMember(it) }, serviceName(c.track, song.name)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** What a service calls the song: its title alone when the artist is the row's, else its artist too. */
private fun serviceName(t: Track, name: Track): String =
    if (Matcher.normalizeArtist(t.artistLine) == Matcher.normalizeArtist(name.artistLine)) t.title else t.toString()

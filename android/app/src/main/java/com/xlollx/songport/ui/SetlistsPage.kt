package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.importTracks
import com.xlollx.songport.sync.ArtistPick
import com.xlollx.songport.sync.LookupCaps
import com.xlollx.songport.sync.Setlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the Setlists page found, for as long as the app runs: another tab and back keeps it (and a running search). */
internal object SetlistsKept {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pick by mutableStateOf<CardPick?>(null)
    var result by mutableStateOf<Setlists.Result?>(null)
    /** The setlist opened to show its songs. */
    var open by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var error by mutableStateOf<String?>(null)
    /** The playlist's artists (null while read), and the ones played most or some chosen. */
    var artists by mutableStateOf<List<String>?>(null)
    val choice = ArtistChoice()
    var artistsOf: String? = null
}

/**
 * Discover › Setlists: a playlist, then the latest setlist.fm setlists of its artists
 * ([Setlists]); one picked becomes a File playlist that a new sync takes to any service. Asked
 * only when the user taps: never in the background. The data is setlist.fm's: each setlist links
 * to its page there, and the page says where it comes from.
 */
@Composable
internal fun SetlistsPage(snackbar: SnackbarHostState, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val k = SetlistsKept
    k.error?.let { e -> LaunchedEffect(e) { snackbar.showSnackbar(e); k.error = null } }
    val busy = k.progress != null
    var keyOpen by rememberSaveable { mutableStateOf(false) }
    if (keyOpen) SetlistFmKeyDialog { keyOpen = false }

    Text(stringResource(R.string.setlists_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar, memory = "setlists") { p ->
        if (p?.playlist?.id != k.pick?.playlist?.id || p?.provider?.id != k.pick?.provider?.id) { k.result = null; k.open = null }
        k.pick = p
    }
    LaunchedEffect(k.pick?.provider?.id, k.pick?.playlist?.id) {
        val p = k.pick
        if (p == null) { k.artists = null; return@LaunchedEffect }
        val of = p.provider.id + "|" + p.playlist.id
        if (k.artists != null && k.artistsOf == of) return@LaunchedEffect // back on the page: kept
        k.artists = null; k.choice.forPlaylist(of); k.artistsOf = of
        k.artists = try { Setlists.artistsOf(p.provider.tracks(ctx.env, p.playlist.id)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    if (k.pick != null) ArtistChooser(k.artists, k.choice, LookupCaps.SETLISTS)
    Button(onClick = { find(ctx) }, enabled = k.pick != null && !busy && k.choice.ready, modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (k.pick == null) stringResource(R.string.setlists_find) else artistsAction(k.artists, k.choice, LookupCaps.SETLISTS, R.string.setlists_find, R.plurals.setlists_find_for, R.string.setlists_find_top))
    }
    k.progress?.takeIf { it.second > 0 }?.let { (done, total) ->
        Text(stringResource(R.string.concerts_progress, (done + 1).coerceAtMost(total), total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val r = k.result
    if (r == null || busy) {
        if (!busy) TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.setlists_key_title)) }
        return
    }
    val notes = buildList {
        if (!r.hasKey) add(stringResource(R.string.setlists_no_key))
        if (r.quotaHit) add(stringResource(R.string.setlists_quota))
        if (r.keyRefused) add(stringResource(R.string.setlists_key_refused))
        if (r.hasKey && r.total > r.artists.size) add(stringResource(R.string.artists_capped, r.artists.size, r.total))
        if (r.unanswered > 0) add(pluralStringResource(R.plurals.setlists_unanswered, r.unanswered, r.unanswered))
    }
    if (notes.isNotEmpty()) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.setlists_key_title)) }
            }
        }
    }
    if (!r.hasKey) return
    val found = r.artists.filter { it.setlists.isNotEmpty() }
    if (found.isEmpty()) Text(stringResource(R.string.setlists_none), style = MaterialTheme.typography.bodyMedium)
    // A long list can be searched by name, both the artists with setlists and those without.
    var query by rememberSaveable { mutableStateOf("") }
    if (r.artists.size > 5) ArtistSearchField(query) { query = it }
    ArtistPick.named(found, query) { it.artist }.forEach { a -> ArtistSetlistsCard(a, busy, onNewSync) }
    val without = ArtistPick.named(r.artists.filter { it.setlists.isEmpty() }, query) { it.artist }
    if (without.isNotEmpty()) Text(
        stringResource(R.string.setlists_without, without.joinToString(", ") { it.artist }),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // setlist.fm's terms: where the setlists come from, with a link.
    Text(
        stringResource(R.string.setlists_attribution), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { openUrl(ctx, Setlists.HOME) }.padding(vertical = 4.dp),
    )
}

private fun dateText(s: Setlists.Setlist): String =
    runCatching { LocalDate.parse(s.date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(s.date)

@Composable
private fun ArtistSetlistsCard(a: Setlists.ArtistSetlists, busy: Boolean, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val k = SetlistsKept
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.artist, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            a.setlists.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                val opened = k.open == s.id
                Row(Modifier.fillMaxWidth().clickable { k.open = if (opened) null else s.id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dateText(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(listOf(s.venue, s.city).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOfNotNull(s.tour.takeIf { it.isNotBlank() }, pluralStringResource(R.plurals.setlists_songs, Setlists.tracks(s).size, Setlists.tracks(s).size)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(if (opened) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (opened) SetlistSongs(s)
                if (opened) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    // The attribution link each setlist.fm answer carries: the setlist's own page.
                    if (s.url.isNotBlank()) TextButton(onClick = { openUrl(ctx, s.url) }) { Text(stringResource(R.string.setlists_open)) }
                    Button(enabled = !busy && Setlists.tracks(s).isNotEmpty(), onClick = { makePlaylist(ctx, s, onNewSync) }) { Text(stringResource(R.string.setlists_make)) }
                }
            }
        }
    }
}

/** The songs as played: the encores under their heading, the tapes greyed (they stay out of the playlist). */
@Composable
private fun SetlistSongs(s: Setlists.Setlist) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var n = 0
        s.songs.forEachIndexed { i, song ->
            if (song.encore > 0 && (i == 0 || s.songs[i - 1].encore != song.encore)) {
                Text(stringResource(R.string.setlists_encore), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            val cover = song.by?.let { " (" + stringResource(R.string.setlists_cover, it) + ")" }.orEmpty()
            if (song.tape) Text(
                "· ${song.title}$cover · " + stringResource(R.string.setlists_tape),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else Text("${++n}. ${song.title}$cover", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun find(ctx: Context) {
    val k = SetlistsKept
    val p = k.pick ?: return
    val app = ctx.applicationContext
    k.progress = 0 to 0; k.result = null; k.open = null
    k.scope.launch {
        try {
            val all = k.artists ?: Setlists.artistsOf(p.provider.tracks(app.env, p.playlist.id)).also { k.artists = it }
            val picked = k.choice.pickedOrNull
            val artists = ArtistPick.chosen(all, picked)
            k.result = withContext(Dispatchers.IO) { Setlists.find(app.env, artists, picked == null) { done, total -> k.progress = done to total } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            k.error = e.message ?: app.getString(R.string.error_generic)
        }
        k.progress = null
    }
}

/** The setlist's songs as a File playlist, and the editor of a new sync from it (to any service). */
private fun makePlaylist(ctx: Context, s: Setlists.Setlist, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val app = ctx.applicationContext
    val name = Setlists.playlistName(s)
    SetlistsKept.scope.launch {
        try {
            val id = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(app, name, Setlists.tracks(s)) }
            onNewSync { copy(name = name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id)) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            SetlistsKept.error = e.message ?: app.getString(R.string.error_generic)
        }
    }
}

/** The user's own setlist.fm key: optional where the build has one, needed where it has none. */
@Composable
internal fun SetlistFmKeyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val saved = remember { Setlists.ownKey(ctx.env).orEmpty() }
    var key by remember { mutableStateOf(saved) }
    val close = rememberDiscardGuard(key != saved, onClose = onDismiss)
    FitDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.setlists_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.setlists_key_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.setlists_key_steps), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(setlistFmKeyState(ctx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(ctx, Setlists.KEY_URL) }) { Text(stringResource(R.string.concerts_key_get)) }
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.setlists_key_field)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.lastfm_key_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { Setlists.saveKey(ctx.env, key); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (Setlists.ownKey(ctx.env) != null) TextButton(onClick = { Setlists.saveKey(ctx.env, ""); onDismiss() }) { Text(stringResource(R.string.remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Which setlist.fm key is used: the user's, the app's shared one, or none. */
internal fun setlistFmKeyState(ctx: Context): Int = when {
    Setlists.ownKey(ctx.env) != null -> R.string.concerts_key_own
    Setlists.key(ctx.env) != null -> R.string.concerts_key_shared
    else -> R.string.setlists_key_none
}

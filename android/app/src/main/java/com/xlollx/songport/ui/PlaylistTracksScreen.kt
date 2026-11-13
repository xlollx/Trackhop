package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.update
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.launch

/**
 * The tracks of one playlist on a service, to take some out: a search box narrows the list by
 * title, artist or album; each row can be ticked, and a row's menu ticks every track by the same
 * artist or from the same album. One confirmation, then one removal call for the whole selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistTracksScreen(provider: MusicProvider, playlist: Playlist, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Selection by position: the same track can sit in a playlist twice.
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirming by remember { mutableStateOf(false) }
    // The removal runs outside this screen (see [Removals]): leaving it does not stop it, coming back shows how far it is.
    val removalKey = Removals.key(provider, playlist.id)
    val run = Removals.state.collectAsState().value[removalKey]
    val busy = run != null && !run.finished
    androidx.compose.runtime.DisposableEffect(removalKey) { Removals.watch(removalKey, 1); onDispose { Removals.watch(removalKey, -1) } }
    LaunchedEffect(run?.finished) {
        if (run?.finished != true) return@LaunchedEffect
        Removals.clear(removalKey)
        onChanged(); version++
        snackbar.showSnackbar(run.error?.let { ctx.getString(R.string.removal_not_done, run.failed, it) } ?: ctx.getString(R.string.tracks_removed, run.done))
    }
    // Saved albums, followed artists, podcasts: "items", not tracks.
    val library = MusicProvider.isLibrary(playlist.id)
    ScreenBackHandler(onClose)

    LaunchedEffect(version) {
        tracks = null; error = null; selected = emptySet()
        tracks = try { provider.tracks(ctx, playlist.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.userMessage(ctx); emptyList() }
    }

    val all = tracks.orEmpty()
    val q = Matcher.normalizeTitle(query)
    val shown = remember(all, q) {
        if (q.isBlank()) all.indices.toList()
        else all.indices.filter { i ->
            val t = all[i]
            Matcher.normalizeTitle(t.title).contains(q) || t.artists.any { Matcher.normalizeArtist(it).contains(q) } || Matcher.normalizeTitle(t.album).contains(q)
        }
    }

    if (confirming) {
        val n = selected.size
        FitDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (library) stringResource(R.string.library_remove_n, n) else pluralStringResource(R.plurals.tracks_remove_n, n, n)) },
            text = { Text(stringResource(if (library) R.string.library_remove_confirm else R.string.tracks_remove_confirm, n, playlist.name, provider.label(ctx))) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    Removals.start(ctx.applicationContext, provider, playlist, selected.sorted().map { all[it] })
                    selected = emptySet()
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).width(20.dp))
                    else if (selected.isNotEmpty()) TextButton(onClick = { confirming = true }) {
                        Text(if (library) stringResource(R.string.library_remove_n, selected.size) else pluralStringResource(R.plurals.tracks_remove_n, selected.size, selected.size), color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(if (library) R.string.library_search else R.string.tracks_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.syncs_find_clear)) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // While loading there is nothing to count yet: "0 of 0" would read as an empty playlist.
                    if (tracks == null) stringResource(R.string.loading) else stringResource(R.string.tracks_shown, shown.size, all.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (shown.isNotEmpty()) {
                    val allShownSelected = shown.all { it in selected }
                    TextButton(onClick = { selected = if (allShownSelected) selected - shown.toSet() else selected + shown }) {
                        Text(stringResource(if (allShownSelected) R.string.tracks_unselect_shown else R.string.tracks_select_shown))
                    }
                }
            }
            // On YouTube Music an artist followed is a channel subscribed: said before removing one.
            if (playlist.id == MusicProvider.ARTISTS_ID && provider is YouTubeBridgeProvider) Text(
                stringResource(R.string.library_ytm_artists_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (run != null && !run.finished) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                // One step per item (or a few) on the library lists; a playlist goes in one call: no share to show.
                if (run.stepped) {
                    WorkBar({ run.done.toFloat() / run.total.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.library_removing, run.done, run.total, run.done * 100 / run.total.coerceAtLeast(1)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                } else WorkBar(null, Modifier.fillMaxWidth())
                Text(stringResource(R.string.library_removing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                tracks == null -> SkeletonRows(14)
                error != null && isNetText(ctx, error) -> NetErrorState(error, onRetry = { version++ })
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                all.isEmpty() -> Text(stringResource(if (library) R.string.library_none else R.string.tracks_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(shown, key = { _, i -> i }) { _, i ->
                        val t = all[i]
                        val checked = i in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = if (checked) selected - i else selected + i }
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, enabled = !busy, onCheckedChange = { selected = if (it) selected + i else selected - i })
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOf(t.artists.joinToString(", "), t.album).filter { it.isNotBlank() && it != t.title }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val artist = t.artists.firstOrNull().orEmpty()
                            // Selection helpers only (nothing goes before "Remove" is confirmed); in the
                            // followed artists each row is the artist itself, so there is nothing to add.
                            if (playlist.id != MusicProvider.ARTISTS_ID && (artist.isNotBlank() || t.album.isNotBlank())) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        if (artist.isNotBlank()) {
                                            val a = Matcher.normalizeArtist(artist)
                                            DropdownMenuItem(text = { Text(stringResource(R.string.tracks_select_artist, artist)) }, onClick = {
                                                menu = false
                                                selected = selected + all.indices.filter { j -> all[j].artists.any { Matcher.normalizeArtist(it) == a } }
                                            })
                                        }
                                        if (t.album.isNotBlank()) {
                                            val al = Matcher.normalizeTitle(t.album)
                                            DropdownMenuItem(text = { Text(stringResource(R.string.tracks_select_album, t.album)) }, onClick = {
                                                menu = false
                                                selected = selected + all.indices.filter { j -> Matcher.normalizeTitle(all[j].album) == al }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Removals from a list that carry on when its screen is left (the screen's own scope ended them
 * there): one at a time per list, with how far they got. The library lists go a few items per call,
 * so the share done can be shown; a playlist goes in one call, as before.
 */
object Removals {
    data class Run(val done: Int, val total: Int, val stepped: Boolean, val failed: Int = 0, val error: String? = null, val finished: Boolean = false)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val watchers = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val state = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Run>>(emptyMap())

    fun key(p: MusicProvider, listId: String) = p.id + "\u0000" + listId
    fun watch(key: String, delta: Int) { watchers.merge(key, delta, Int::plus) }
    fun clear(key: String) = state.update { it - key }
    private fun set(key: String, run: Run) = state.update { it + (key to run) }

    fun start(app: android.content.Context, p: MusicProvider, list: Playlist, items: List<Track>) {
        val key = key(p, list.id)
        if (items.isEmpty() || state.value[key]?.finished == false) return
        // At most about fifty calls: one item each for a short list, a few each for a long one.
        val step = if (MusicProvider.isLibrary(list.id)) maxOf(1, items.size / 50) else items.size
        set(key, Run(0, items.size, stepped = step < items.size))
        scope.launch {
            var done = 0; var failed = 0; var error: String? = null
            for (chunk in items.chunked(step)) {
                try { p.removeTracks(app, list.id, chunk) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    failed += chunk.size; error = e.userMessage(app)
                }
                done += chunk.size
                set(key, Run(done, items.size, step < items.size, failed))
            }
            val end = Run(done - failed, items.size, step < items.size, failed, error, finished = true)
            if ((watchers[key] ?: 0) > 0) { set(key, end); return@launch }
            // Nobody on the screen: said once, and nothing left to show.
            clear(key)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val text = list.name + ": " + (error?.let { app.getString(R.string.removal_not_done, failed, it) } ?: app.getString(R.string.tracks_removed, end.done))
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.importTracks
import com.xlollx.songport.sync.ArtistPick
import com.xlollx.songport.sync.LookupCaps
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.Setlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

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
    /** Reading them failed: shown with Try again, which counts up [reads]. */
    var artistsFailed by mutableStateOf(false)
    var reads by mutableStateOf(0)
    val choice = ArtistChoice()
    var artistsOf: String? = null
    /** The setlist being sent to a service, and how: a new playlist, or added to an existing one. */
    var sendFor by mutableStateOf<String?>(null)
    var sendExisting by mutableStateOf(false)
    var sendTo by mutableStateOf<String?>(null)
    var addTo by mutableStateOf<CardPick?>(null)
    /** The sync last started from a setlist: its summary is shown here once it has run. */
    var sent by mutableStateOf<String?>(null)
    /** The search going on, what it found so far, and where it was when the user stopped it. */
    var job: Job? = null
    var found: Setlists.Result? = null
    /** Counts the runs: a stopped one still finishing a request no longer touches the page. */
    var runs = 0
    var stopped by mutableStateOf<Pair<Int, Int>?>(null)

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

    /**
     * A setlist the person opened on setlist.fm's own site in the in-app browser (GitHub and F-Droid
     * builds; the store builds browse it in a connector plugin; no key): shown at the top of the page
     * with where it goes already asked.
     */
    var browsed by mutableStateOf<Setlists.Setlist?>(null)
    /** The browser closed with a setlist: the app shows this page ([browsed]); set back once shown. */
    var showBrowsed by mutableStateOf(false)

    /** From the in-app browser: [s] as a new playlist, or added to an existing one ([existing]). */
    fun browsed(s: Setlists.Setlist, existing: Boolean) {
        browsed = s; open = s.id; sendFor = s.id; sendExisting = existing; showBrowsed = true
    }

    /** The key saved or removed: what the old key found (or "key refused") no longer holds, the next search uses the new one. */
    fun keyChanged() { result = null; open = null; sendFor = null; stopped = null }
}

/**
 * Discover › Setlists: a playlist, then the latest setlists of its artists ([Setlists]): setlist.fm's
 * with a key, MusicBrainz's without one. One picked goes to a service, as a new playlist or added to
 * one of the user's: only where to put it is asked (the setlist is the source), and a one-off
 * transfer run once does it ([com.xlollx.songport.sync.OneOffs]): not a sync, its review is here,
 * in its notification and in Tools › Recent transfers. Asked only when the user taps: never in the background.
 * Each setlist links to its page where it comes from, and the page says where that is.
 */
@Composable
internal fun SetlistsPage(snackbar: SnackbarHostState, onReview: (String) -> Unit) {
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
    LaunchedEffect(k.pick?.provider?.id, k.pick?.playlist?.id, k.reads) {
        val p = k.pick
        if (p == null) { k.artists = null; return@LaunchedEffect }
        val of = p.provider.id + "|" + p.playlist.id
        if (k.artists != null && k.artistsOf == of) return@LaunchedEffect // back on the page: kept
        k.artists = null; k.artistsFailed = false; k.choice.forPlaylist(of); k.artistsOf = of
        k.artists = try { Setlists.artistsOf(p.provider.tracks(ctx.env, p.playlist.id)).also { com.xlollx.songport.sync.ArtistSuggest.note(ctx.env, it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { k.artistsFailed = true; null }
    }
    if (k.pick != null) ArtistChooser(k.artists, k.choice, LookupCaps.SETLISTS, k.artistsFailed) { k.reads++ }
    SentSummary(onReview)
    Button(onClick = { find(ctx) }, enabled = k.pick != null && !busy && k.choice.ready, modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (k.pick == null) stringResource(R.string.setlists_find) else artistsAction(k.artists, k.choice, LookupCaps.SETLISTS, R.string.setlists_find, R.plurals.setlists_find_for, R.string.setlists_find_top))
    }
    if (busy) SearchRunning(k.progress) { k.stop() }
    // No key: setlist.fm's own site, browsed by the person in the app (in a connector plugin in the store builds).
    val site = if (Setlists.key(ctx.env) == null) siteBrowsing(ctx) else null
    if (site != null) OutlinedButton(
        onClick = { site.second(k.artists?.let { ArtistPick.chosen(it, k.choice.pickedOrNull) }?.firstOrNull()) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(site.first) }
    k.browsed?.let { b -> ArtistSetlistsCard(Setlists.ArtistSetlists(b.artist, listOf(b)), busy = false, keyless = false, snackbar, null) }

    val r = k.result
    if (r == null || busy) {
        if (!busy) TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.setlists_key_title)) }
        return
    }
    val keyless = r.source == Setlists.Source.MUSICBRAINZ
    SearchStopped(k.stopped)
    val notes = buildList {
        if (keyless) add(stringResource(R.string.setlists_keyless))
        else if (!r.hasKey) add(stringResource(R.string.setlists_no_key))
        if (r.quotaHit) add(stringResource(R.string.setlists_quota))
        if (r.keyRefused) add(stringResource(R.string.setlists_key_refused))
        if ((r.hasKey || keyless) && r.total > r.artists.size) add(stringResource(R.string.artists_capped, r.artists.size, r.total))
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
    if (!r.hasKey && !keyless) return
    val found = r.artists.filter { it.setlists.isNotEmpty() }
    if (found.isEmpty()) Text(stringResource(R.string.setlists_none), style = MaterialTheme.typography.bodyMedium)
    // A long list can be searched by name, both the artists with setlists and those without.
    var query by rememberSaveable { mutableStateOf("") }
    if (r.artists.size > 5) ArtistSearchField(query) { query = it }
    ArtistPick.named(found, query) { it.artist }.forEach { a -> ArtistSetlistsCard(a, busy, keyless, snackbar, site) }
    val without = ArtistPick.named(r.artists.filter { it.setlists.isEmpty() }, query) { it.artist }
    if (without.isNotEmpty()) Text(
        stringResource(R.string.setlists_without, without.joinToString(", ") { it.artist }),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // setlist.fm's terms (and MusicBrainz's courtesy): where the setlists come from, with a link.
    Text(
        stringResource(if (keyless) R.string.setlists_attribution_mb else R.string.setlists_attribution), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { openUrl(ctx, if (keyless) Setlists.MB_HOME else Setlists.HOME) }.padding(vertical = 4.dp),
    )
}

private fun dateText(s: Setlists.Setlist): String =
    runCatching { LocalDate.parse(s.date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(s.date)

@Composable
private fun ArtistSetlistsCard(a: Setlists.ArtistSetlists, busy: Boolean, keyless: Boolean, snackbar: SnackbarHostState, site: Pair<String, (String?) -> Unit>?) {
    val ctx = LocalContext.current
    val k = SetlistsKept
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtistTitle(a.artist)
            // MusicBrainz's setlists: the artist's full ones on setlist.fm's own site, browsed in the app.
            if (keyless && site != null) TextButton(onClick = { site.second(a.artist) }) { Text(site.first) }
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
                if (opened) {
                    // The attribution link each answer carries: the setlist's own page.
                    if (s.url.isNotBlank()) TextButton(onClick = { openUrl(ctx, s.url) }) { Text(stringResource(if (keyless) R.string.setlists_open_mb else R.string.setlists_open)) }
                    val can = !busy && Setlists.tracks(s).isNotEmpty()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = can, onClick = { k.sendFor = s.id; k.sendExisting = true }) { Text(stringResource(R.string.releases_add_existing)) }
                        Button(enabled = can, onClick = { k.sendFor = s.id; k.sendExisting = false }) { Text(stringResource(R.string.setlists_make)) }
                    }
                    if (k.sendFor == s.id) SendSetlist(s, snackbar)
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
    if (k.job?.isActive == true) return
    k.progress = 0 to 0; k.result = null; k.open = null; k.stopped = null; k.found = null
    val run = ++k.runs
    k.job = k.scope.launch {
        // Stopped ([SetlistsKept.stop]): this run no longer touches the page, a new one may be going.
        fun mine() = run == k.runs
        try {
            val all = k.artists ?: Setlists.artistsOf(p.provider.tracks(app.env, p.playlist.id)).also { k.artists = it }
            val picked = k.choice.pickedOrNull
            val artists = ArtistPick.chosen(all, picked)
            val r = withContext(Dispatchers.IO) {
                Setlists.find(app.env, artists, picked == null, keyless = true, partial = { if (mine()) k.found = it }) { done, total -> if (mine()) k.progress = done to total }
            }
            if (mine()) k.result = r
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (mine()) k.error = e.message ?: app.getString(R.string.error_generic)
        } finally {
            if (mine()) { k.progress = null; k.job = null }
        }
    }
}

/**
 * Where the setlist goes: a service for a new playlist, or one of the user's playlists (the picker of
 * New releases) where only the songs not already there are added. Nothing else is asked.
 */
@Composable
private fun SendSetlist(s: Setlists.Setlist, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val k = SetlistsKept
    if (k.sendExisting) {
        PlaylistSource(stringResource(R.string.releases_add_existing_target), null, snackbar, memory = "setlists_add") { k.addTo = it }
        val to = k.addTo?.takeIf { it.pastedLink == null && it.provider.canWrite }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(enabled = to != null, onClick = { send(ctx, s, PlaylistRef(to!!.provider.id, to.playlist.id, to.playlist.name)) }) { Text(stringResource(R.string.setlists_add_go)) }
        }
        return
    }
    val services = remember { Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx.env) && it.canWrite && it.canCreatePlaylists } }
    if (services.isEmpty()) { Text(stringResource(R.string.no_connected_providers), style = MaterialTheme.typography.bodySmall); return }
    Text(stringResource(R.string.editor_target), style = MaterialTheme.typography.labelLarge)
    ServicePicker(services, k.sendTo, onSelect = { k.sendTo = it })
    val to = k.sendTo?.takeIf { id -> services.any { it.id == id } }
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        Button(enabled = to != null, onClick = { send(ctx, s, PlaylistRef(to!!)) }) { Text(stringResource(R.string.setlists_make_go)) }
    }
}

/** The setlist as a File playlist, and a one-off transfer from it to [target] run now (not a sync: Tools › Recent transfers keeps it for its review). */
private fun send(ctx: Context, s: Setlists.Setlist, target: PlaylistRef) {
    val app = ctx.applicationContext
    val name = Setlists.playlistName(s)
    SetlistsKept.scope.launch {
        try {
            val file = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(app, name, Setlists.tracks(s)) }
            val job = Setlists.job(UUID.randomUUID().toString(), name, file, target)
            Store.get(app).upsertJob(job)
            Scheduler.runNow(app, job.id)
            SetlistsKept.sent = job.id; SetlistsKept.sendFor = null
            if (SetlistsKept.browsed?.id == s.id) SetlistsKept.browsed = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            SetlistsKept.error = e.message ?: app.getString(R.string.error_generic)
        }
    }
}

/** The last setlist sent: working, then added / already there / not found, with the review of what was not found. */
@Composable
private fun SentSummary(onReview: (String) -> Unit) {
    val ctx = LocalContext.current
    val id = SetlistsKept.sent ?: return
    val data by Store.get(ctx).state.collectAsState()
    val job = data.jobs.firstOrNull { it.id == id } ?: return
    val report = job.lastReportId?.let { r -> data.reports.firstOrNull { it.id == r } }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.target.playlistName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            val where = Providers.byId(job.target.provider)?.label(ctx.env) ?: job.target.provider
            if (report == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setlists_adding, where), style = MaterialTheme.typography.bodySmall)
            } else {
                val sum = Setlists.summary(report)
                report.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.setlists_summary, sum.added, sum.alreadyThere, sum.notFound), style = MaterialTheme.typography.bodySmall)
                val toFix = report.unmatchedTracks.size + report.reviewTracks.size
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (toFix > 0) TextButton(onClick = { onReview(report.id) }) { Text(reviewLabel(report.unmatchedTracks.size, report.reviewTracks.size)) }
                    TextButton(onClick = { SetlistsKept.sent = null }) { Text(stringResource(R.string.ok)) }
                }
            }
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
                Text(stringResource(setlistFmKeyState(ctx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // How to get a key, step by step: an account, an "application" on the API page, its key.
                Text(stringResource(R.string.setlists_key_step1), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openUrl(ctx, Setlists.SIGNUP_URL) }) { Text(stringResource(R.string.setlists_key_signup)) }
                Text(stringResource(R.string.setlists_key_step2), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openUrl(ctx, Setlists.KEY_URL) }) { Text(stringResource(R.string.concerts_key_get)) }
                Text(stringResource(R.string.setlists_key_step3), style = MaterialTheme.typography.bodySmall)
                var copied by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Setlists.KEY_DESCRIPTION, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(Setlists.KEY_DESCRIPTION)); copied = true }) {
                        Text(stringResource(if (copied) R.string.setup_copied else R.string.setup_copy))
                    }
                }
                Text(stringResource(R.string.setlists_key_step4), style = MaterialTheme.typography.bodySmall)
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
        confirmButton = { TextButton(onClick = { Setlists.saveKey(ctx.env, key); if (key != saved) SetlistsKept.keyChanged(); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (Setlists.ownKey(ctx.env) != null) TextButton(onClick = { Setlists.saveKey(ctx.env, ""); SetlistsKept.keyChanged(); onDismiss() }) { Text(stringResource(R.string.remove)) }
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

/**
 * setlist.fm's own site in the app ([com.xlollx.songport.providers.BuiltIn.setlistSite]): in the GitHub
 * and F-Droid builds, and in the store builds through a connector plugin (null without one): its
 * label, and opening it on an artist's search.
 */
private fun siteBrowsing(ctx: Context): Pair<String, (String?) -> Unit>? = com.xlollx.songport.providers.BuiltIn.setlistSite(ctx, null)?.let { (label, _) ->
    label to { artist: String? -> com.xlollx.songport.providers.BuiltIn.setlistSite(ctx, artist)?.second?.let { runCatching { ctx.startActivity(it) } }; Unit }
}

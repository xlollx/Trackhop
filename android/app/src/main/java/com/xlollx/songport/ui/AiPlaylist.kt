package com.xlollx.songport.ui

import com.xlollx.songport.ai.config
import com.xlollx.songport.ai.available
import com.xlollx.songport.ai.save
import com.xlollx.songport.ai.clear
import com.xlollx.songport.providers.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import com.xlollx.songport.model.Track
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.AiMatch
import com.xlollx.songport.ai.NanoAi
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Stato della scheda del generatore, fuori dalla schermata: testi, destinazione e la richiesta in
 * corso sopravvivono al cambio di scheda (la Column di Strumenti viene ricomposta da zero) e i
 * testi anche alla chiusura dell'app. La chiamata all'AI gira in uno scope proprio: se l'utente
 * va a guardare le sync mentre aspetta, la risposta lo trova al ritorno.
 */
object AiDraft {
    var prompt by mutableStateOf("")
    var seeds by mutableStateOf("")
    var name by mutableStateOf("")
    var count by mutableStateOf(25f)
    var targetId by mutableStateOf("")
    var busy by mutableStateOf(false)
    /** "found/wanted" while the proposals are checked on the destination. */
    var step by mutableStateOf("")
    /** The AI's proposal, shown for a look (and a prune) before anything is created. */
    var proposed by mutableStateOf<List<Track>?>(null)
    /** Error of the last request, shown once by whoever is on screen. */
    var error by mutableStateOf<String?>(null)
    /** Models offered by the configured provider, read once per key/vendor. */
    var models by mutableStateOf<List<String>>(emptyList())
    /** Outcome of the last model check: the configuration tried and its error (null = fine). */
    var checked by mutableStateOf<Pair<AiClient.Config, String?>?>(null)
    var checking by mutableStateOf(false)
    /** Asked from elsewhere (the Roast without an AI): open on "With your AI" with the setup dialog. */
    var setupAsked by mutableStateOf(false)

    /** Keyless mode (ArtistMix): null = not chosen yet, which means keyless while no AI is set up. */
    var keyless by mutableStateOf<Boolean?>(null)
    /** Keyless seeds: typed artists, or (true) the top artists of one of the user's playlists. */
    var mixFromPlaylist by mutableStateOf(false)
    var mixArtists by mutableStateOf("")
    var mixCount by mutableStateOf(25)
    var mixLeaning by mutableStateOf(com.xlollx.songport.ai.ArtistMix.Leaning.BALANCED)
    var mixSourceId by mutableStateOf("")
    var mixPlaylistId by mutableStateOf("")
    var mixPlaylistName by mutableStateOf("")
    /** The seed artists of the list under review, for its default name. */
    var mixLabel by mutableStateOf("")
    /** True while the list under review comes from the keyless mode. */
    var proposedKeyless by mutableStateOf(false)
    /** The playlist just made ("12 songs in … on Spotify"): shown in a "Done" dialog, not a jump away. */
    var done by mutableStateOf<String?>(null)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ai_draft", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        prompt = p.getString("prompt", "") ?: ""
        seeds = p.getString("seeds", "") ?: ""
        name = p.getString("name", "") ?: ""
        count = p.getFloat("count", 25f)
        targetId = p.getString("target", "") ?: ""
        keyless = if (p.contains("keyless")) p.getBoolean("keyless", true) else null
        mixFromPlaylist = p.getBoolean("mix_from_playlist", false)
        mixArtists = p.getString("mix_artists", "") ?: ""
        mixCount = p.getInt("mix_count", 25)
        mixLeaning = com.xlollx.songport.ai.ArtistMix.Leaning.of(p.getString("mix_leaning", null))
        mixSourceId = p.getString("mix_source", "") ?: ""
        mixPlaylistId = p.getString("mix_playlist", "") ?: ""
        mixPlaylistName = p.getString("mix_playlist_name", "") ?: ""
    }

    fun persist(ctx: Context) {
        prefs(ctx).edit().putString("prompt", prompt).putString("seeds", seeds).putString("name", name)
            .putFloat("count", count).putString("target", targetId)
            .also { e -> keyless?.let { e.putBoolean("keyless", it) } }
            .putBoolean("mix_from_playlist", mixFromPlaylist).putString("mix_artists", mixArtists).putInt("mix_count", mixCount)
            .putString("mix_leaning", mixLeaning.name).putString("mix_source", mixSourceId)
            .putString("mix_playlist", mixPlaylistId).putString("mix_playlist_name", mixPlaylistName).apply()
    }

    fun clearText(ctx: Context) { prompt = ""; seeds = ""; name = ""; mixArtists = ""; persist(ctx) }
}

/**
 * "Generatore di playlist" (beta): l'utente descrive, la SUA AI propone, Trackhop cerca. La lista
 * proposta viene salvata come file (cosi' resta consultabile in File) e, se la destinazione e' un
 * servizio, diventa l'origine di una sync normale creata e avviata subito: ricerca, non trovati e
 * revisione sono quelli di sempre.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiPlaylistCard(snackbar: SnackbarHostState, onSyncStarted: () -> Unit) {
    val ctx = LocalContext.current
    val d = AiDraft
    remember { d.load(ctx); true }
    var config by remember { mutableStateOf(AiClient.config(ctx)) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val targets = remember { (Providers.connectors().filter { it.isConnected(ctx) } + LocalFilesProvider).distinctBy { it.id }.filter { it.canWrite && it.canCreatePlaylists } }
    val target = targets.firstOrNull { it.id == d.targetId } ?: targets.firstOrNull { it.requiresAuth } ?: LocalFilesProvider

    // Errors of a request that may have finished while another tab was open.
    LaunchedEffect(d.error) { d.error?.let { snackbar.showSnackbar(it); d.error = null } }

    LaunchedEffect(d.setupAsked) { if (d.setupAsked) { d.keyless = false; setupOpen = true; d.setupAsked = false } }
    d.done?.let { text ->
        FitDialog(
            onDismissRequest = { d.done = null },
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = Tones.onSuccessContainer()) },
            title = { Text(stringResource(R.string.ai_done_title)) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { d.done = null; onSyncStarted() }) { Text(stringResource(R.string.ai_done_open)) } },
            dismissButton = { TextButton(onClick = { d.done = null }) { Text(stringResource(R.string.ai_done_close)) } },
        )
    }
    if (setupOpen) AiSetupDialog(config, onDismiss = { setupOpen = false }) { config = it; d.models = emptyList(); setupOpen = false }
    d.proposed?.let { list ->
        AiReviewDialog(list, target.label(ctx), onDismiss = { d.proposed = null }) { kept ->
            d.proposed = null
            val keylessList = d.proposedKeyless
            val finalName = d.name.trim().ifBlank {
                if (keylessList && d.mixLabel.isNotBlank()) ctx.getString(R.string.lb_default_name, d.mixLabel) else ctx.getString(R.string.ai_default_name)
            }
            val app = ctx.applicationContext
            d.scope.launch {
                try {
                    val fileId = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(app, finalName, kept) }
                    if (target.id == LocalFilesProvider.id) {
                        d.clearText(app)
                        snackbar.showSnackbar(app.getString(R.string.ai_done_file, kept.size, fileId))
                    } else {
                        // The tracks are the service's own already: the playlist is made here, with them in it,
                        // and the sync keeps the file and the playlist paired for "Extend with AI" and later runs.
                        d.busy = true
                        val created = target.createPlaylist(app, finalName, MusicProvider.description(app.getString(R.string.app_name)))
                        if (kept.isNotEmpty()) target.addTracks(app, created.id, kept)
                        val job = SyncJob(
                            id = UUID.randomUUID().toString(),
                            name = finalName,
                            source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = fileId, playlistName = fileId),
                            target = PlaylistRef(provider = target.id, playlistId = created.id, playlistName = created.name),
                            // A keyless list has no description to extend it with.
                            aiPrompt = if (keylessList) null else d.prompt.trim(),
                        )
                        Store.get(app).upsertJob(job)
                        d.busy = false
                        d.clearText(app)
                        d.done = app.getString(R.string.ai_done_created, kept.size, created.name, target.label(app))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    d.busy = false
                    d.error = e.message ?: app.getString(R.string.error_generic)
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            // Two ways in: no key at all (open music data), or the user's own AI. Without an AI set up,
            // the keyless one is where the card opens.
            // Gemini Nano on the phone counts as an AI: the card then opens on the description.
            val keyless = d.keyless ?: (AiClient.pick(config, NanoAi.state, NanoAi.NAME) == null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = keyless, onClick = { d.keyless = true; d.persist(ctx) }, label = { Text(stringResource(R.string.lb_mode_keyless)) })
                FilterChip(selected = !keyless, onClick = { d.keyless = false; d.persist(ctx) }, label = { Text(stringResource(R.string.lb_mode_ai)) })
            }
            Spacer(Modifier.height(6.dp))
            if (keyless) {
                KeylessForm(targets, target)
                return@Column
            }
            // The user's key first; without one, Gemini Nano when the phone has it.
            val c = AiClient.pick(config, NanoAi.state, NanoAi.NAME)
            Text(stringResource(if (c?.local == true) R.string.ai_nano_desc else R.string.ai_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c?.local == true) { Spacer(Modifier.height(6.dp)); NanoSmallNote() }
            Spacer(Modifier.height(10.dp))
            if (c == null) {
                // Not a dead end: where free keys are, then the setup.
                Text(stringResource(R.string.ai_free_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { setupOpen = true }) { Text(stringResource(R.string.ai_setup)) }
                }
                return@Column
            }
            var ready = true
            if (c.local) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ai_nano_label, c.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { setupOpen = true }) { Text(stringResource(R.string.ai_nano_use_key)) }
                }
                ready = NanoStatus()
            } else {
                // Provider and model on one line; the model is a menu right here, the rest is in the dialog.
                ModelRow(c, onChange = { setupOpen = true }) { m -> AiClient.save(ctx, c.copy(model = m)); config = c.copy(model = m); d.checked = null }
                // The model is tried with a one-word request whenever it changes: a wrong key, an exhausted
                // quota or a retired model show up here, not after the playlist has been described.
                LaunchedEffect(c.vendor, c.model, c.apiKey, c.baseUrl) {
                    if (d.checked?.first == c) return@LaunchedEffect
                    d.checking = true
                    try { d.checked = c to AiClient.check(c) } finally { d.checking = false }
                }
            }
            when {
                c.local -> Unit
                d.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_checking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.checked?.first == c && d.checked?.second == null -> Text(stringResource(R.string.ai_check_ok), style = MaterialTheme.typography.bodySmall, color = Tones.onSuccessContainer())
                d.checked?.first == c -> Text(d.checked?.second.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = d.prompt, onValueChange = { d.prompt = it; d.persist(ctx) }, minLines = 2, maxLines = 5,
                label = { Text(stringResource(R.string.ai_prompt)) }, placeholder = { Text(stringResource(R.string.ai_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.seeds, onValueChange = { d.seeds = it; d.persist(ctx) }, minLines = 1, maxLines = 5,
                label = { Text(stringResource(R.string.ai_seeds)) }, placeholder = { Text(stringResource(R.string.ai_seeds_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AiCountField(d.count.toInt()) { d.count = it.toFloat(); d.persist(ctx) }
            Text(stringResource(R.string.ai_target), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            ServicePicker(targets, target.id, { d.targetId = it; d.persist(ctx) })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.name, onValueChange = { d.name = it; d.persist(ctx) }, singleLine = true,
                label = { Text(stringResource(R.string.editor_new_playlist_name)) }, placeholder = { Text(stringResource(R.string.ai_default_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (d.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(
                        if (d.step.isEmpty()) stringResource(R.string.ai_generating) else stringResource(R.string.ai_checking_availability, target.label(ctx), d.step),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Button(enabled = !d.busy && ready && d.prompt.isNotBlank(), onClick = {
                    d.busy = true; d.step = ""
                    val app = ctx.applicationContext
                    val seedLines = d.seeds.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val request = AiClient.Prompt(d.prompt, d.count.toInt(), seedLines, java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH))
                    d.scope.launch {
                        // A file takes any name; a service gets only what it has, checked before the review.
                        val outcome = runCatching {
                            if (target.id == LocalFilesProvider.id) AiClient.generateAll(c, request)
                            else AiMatch.available(app, c, target, request, emptyList()) { found, want -> d.step = "$found/$want" }.found
                        }
                        d.busy = false; d.step = ""
                        outcome.onFailure { e -> d.error = e.message ?: app.getString(R.string.error_generic) }
                        val tracks = outcome.getOrNull() ?: return@launch
                        if (tracks.isEmpty()) d.error = app.getString(R.string.ai_empty) else { d.proposedKeyless = false; d.proposed = tracks }
                    }
                }) { Text(stringResource(R.string.ai_generate)) }
            }
        }
    }
}

/**
 * The generator without a key: seed artists (typed, or the top artists of a playlist of the
 * user's), a length and how adventurous; [ArtistMixSource] builds the list from MusicBrainz and
 * ListenBrainz, the destination is searched for it, and the same review and creation follow.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeylessForm(targets: List<MusicProvider>, target: MusicProvider) {
    val ctx = LocalContext.current
    val d = AiDraft
    Text(stringResource(R.string.lb_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !d.mixFromPlaylist, onClick = { d.mixFromPlaylist = false; d.persist(ctx) }, label = { Text(stringResource(R.string.lb_seed_from_artists)) })
        FilterChip(selected = d.mixFromPlaylist, onClick = { d.mixFromPlaylist = true; d.persist(ctx) }, label = { Text(stringResource(R.string.lb_seed_from_playlist)) })
    }
    Spacer(Modifier.height(6.dp))
    if (!d.mixFromPlaylist) {
        OutlinedTextField(
            value = d.mixArtists, onValueChange = { d.mixArtists = it; d.persist(ctx) }, minLines = 1, maxLines = 5,
            label = { Text(stringResource(R.string.lb_seed_artists)) }, supportingText = { Text(stringResource(R.string.lb_seed_artists_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        SeedPlaylistPicker()
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.lb_length), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.xlollx.songport.ai.ArtistMix.LENGTHS.forEach { n ->
            FilterChip(selected = d.mixCount == n, onClick = { d.mixCount = n; d.persist(ctx) }, label = { Text(n.toString()) })
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.lb_leaning), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            com.xlollx.songport.ai.ArtistMix.Leaning.FAMILIAR to R.string.lb_familiar,
            com.xlollx.songport.ai.ArtistMix.Leaning.BALANCED to R.string.lb_balanced,
            com.xlollx.songport.ai.ArtistMix.Leaning.DISCOVERY to R.string.lb_discovery,
        ).forEach { (l, res) ->
            FilterChip(selected = d.mixLeaning == l, onClick = { d.mixLeaning = l; d.persist(ctx) }, label = { Text(stringResource(res)) })
        }
    }
    Text(stringResource(R.string.lb_leaning_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.ai_target), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))
    ServicePicker(targets, target.id, { d.targetId = it; d.persist(ctx) })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = d.name, onValueChange = { d.name = it; d.persist(ctx) }, singleLine = true,
        label = { Text(stringResource(R.string.editor_new_playlist_name)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    val ready = if (d.mixFromPlaylist) d.mixPlaylistId.isNotEmpty() else d.mixArtists.isNotBlank()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        if (d.busy) {
            CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(d.step.ifEmpty { stringResource(R.string.ai_generating) }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
        }
        Button(enabled = !d.busy && ready, onClick = {
            d.busy = true; d.step = ""
            val app = ctx.applicationContext
            val count = d.mixCount
            val leaning = d.mixLeaning
            val fromPlaylist = d.mixFromPlaylist
            val source = Providers.byId(d.mixSourceId)
            val playlistId = d.mixPlaylistId
            val typed = d.mixArtists
            d.scope.launch {
                val outcome = runCatching {
                    val exclude = if (fromPlaylist && source != null) source.tracks(app, playlistId) else emptyList()
                    val seeds = if (fromPlaylist) com.xlollx.songport.ai.ArtistMix.topArtists(exclude) else com.xlollx.songport.ai.ArtistMix.parseSeeds(typed)
                    if (seeds.isEmpty()) throw com.xlollx.songport.model.ProviderException(app.getString(if (fromPlaylist) R.string.lb_playlist_empty else R.string.lb_seed_needed))
                    val r = com.xlollx.songport.ai.ArtistMixSource.generate(app.env, com.xlollx.songport.ai.ArtistMixSource.Request(seeds, count, leaning, exclude)) { step, done, total ->
                        d.step = when (step) {
                            com.xlollx.songport.ai.ArtistMixSource.Step.ARTISTS -> app.getString(R.string.lb_step_artists, done, total)
                            com.xlollx.songport.ai.ArtistMixSource.Step.SIMILAR -> app.getString(R.string.lb_step_similar, done, total)
                            else -> app.getString(R.string.lb_step_tracks, done, total)
                        }
                    }
                    if (r.unknown.isNotEmpty()) d.error = app.getString(R.string.lb_unknown_some, r.unknown.joinToString(", "))
                    d.mixLabel = r.seeds.take(2).joinToString(", ") { it.name }
                    // A file takes the list as it is; a service gets only what it has, checked before the review.
                    if (target.id == LocalFilesProvider.id) r.tracks.take(count)
                    else com.xlollx.songport.ai.ArtistMixSource.onService(app.env, target, r.tracks, count, exclude) { found, want ->
                        d.step = app.getString(R.string.ai_checking_availability, target.label(app), "$found/$want")
                    }.found
                }
                d.busy = false; d.step = ""
                outcome.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; d.error = e.message ?: app.getString(R.string.error_generic) }
                val tracks = outcome.getOrNull() ?: return@launch
                if (tracks.isEmpty()) d.error = app.getString(R.string.lb_nothing) else { d.proposedKeyless = true; d.proposed = tracks }
            }
        }) { Text(stringResource(R.string.ai_generate)) }
    }
}

/** "A playlist of mine": a connected service, then one of its playlists, loaded when the service is chosen. */
@Composable
private fun SeedPlaylistPicker() {
    val ctx = LocalContext.current
    val d = AiDraft
    val sources = remember {
        (Providers.connectors().filter { (!it.requiresAuth || it.isConnected(ctx)) && !it.linkOnly } + LocalFilesProvider).distinctBy { it.id }
    }
    val source = sources.firstOrNull { it.id == d.mixSourceId } ?: sources.firstOrNull { it.requiresAuth } ?: sources.firstOrNull() ?: return
    var lists by remember(source.id) { mutableStateOf<List<Playlist>?>(null) }
    LaunchedEffect(source.id) {
        if (d.mixSourceId != source.id) d.mixSourceId = source.id
        lists = runCatching { source.playlists(ctx.applicationContext) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            d.error = e.message ?: ctx.getString(R.string.error_generic); emptyList()
        }
    }
    ServicePicker(sources, source.id, { id ->
        if (id != d.mixSourceId) { d.mixSourceId = id; d.mixPlaylistId = ""; d.mixPlaylistName = ""; d.persist(ctx) }
    })
    PlaylistChoice(
        stringResource(R.string.editor_playlist), lists, lists?.firstOrNull { it.id == d.mixPlaylistId }, source,
        onPick = { p -> d.mixPlaylistId = p.id; d.mixPlaylistName = p.name; d.persist(ctx) },
        placeholder = stringResource(R.string.lb_playlist_pick), title = d.mixPlaylistName.ifEmpty { null },
    )
    Text(stringResource(R.string.lb_playlist_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Fornitore · modello  Cambia": il modello si sceglie da un menu letto dall'API, il resto nel dialogo. */
@Composable
private fun ModelRow(c: AiClient.Config, onChange: () -> Unit, onModel: (String) -> Unit) {
    val ctx = LocalContext.current
    val d = AiDraft
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(c.vendor.label + " · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), onClick = {
                if (d.models.isNotEmpty()) { open = true; return@TextButton }
                loading = true
                d.scope.launch {
                    val r = runCatching { AiClient.models(c) }
                    loading = false
                    r.onSuccess { d.models = it; if (it.isEmpty()) d.error = ctx.getString(R.string.ai_models_none) else open = true }
                        .onFailure { e -> d.error = e.message ?: ctx.getString(R.string.error_generic) }
                }
            }) {
                if (loading) { CircularProgressIndicator(Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                Text(c.model, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.ai_model))
            }
            var all by remember { mutableStateOf(false) }
            val shown = if (all) d.models else AiClient.curated(c.vendor, d.models)
            DropdownMenu(expanded = open, onDismissRequest = { open = false; all = false }) {
                shown.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { open = false; all = false; onModel(m) }) }
                if (!all && shown.size < d.models.size) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_models_all), color = MaterialTheme.colorScheme.primary) },
                        onClick = { all = true },
                    )
                }
            }
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.ai_setup_change)) }
    }
}

/** Fornitore, URL (solo per i server compatibili), chiave e modello, con la lista dei modelli letta dall'API. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiSetupDialog(initial: AiClient.Config?, onDismiss: () -> Unit, onSaved: (AiClient.Config?) -> Unit) {
    // Closed on purpose: the unsaved key is forgotten (see AiSetupDraft).
    val cancel = { AiSetupDraft.closing = true; AiSetupDraft.key = null; onDismiss() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // What is being chosen survives leaving the app and coming back (the activity may be made anew
    // meanwhile). The key typed but not saved yet stays in this process only, never in saved state.
    var vendor by rememberSaveable { mutableStateOf(initial?.vendor ?: AiClient.Vendor.OPENAI) }
    var key by remember { mutableStateOf(AiSetupDraft.key ?: initial?.apiKey.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(initial?.model.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var imageModel by rememberSaveable { mutableStateOf(initial?.imageModel.orEmpty()) }
    var models by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.SideEffect { if (!AiSetupDraft.closing) AiSetupDraft.key = key }
    androidx.compose.runtime.DisposableEffect(Unit) { AiSetupDraft.closing = false; onDispose { if (AiSetupDraft.closing) AiSetupDraft.key = null } }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** "" = the test passed; text = its error; null = not tried. */
    var checkResult by remember { mutableStateOf<String?>(null) }
    fun current() = AiClient.Config(vendor, key.trim(), model.trim(), baseUrl.trim(), if (vendor == AiClient.Vendor.CUSTOM) imageModel.trim() else "")
    // Back or a tap beside it with a key or a choice not saved: asked first.
    val changed = vendor != (initial?.vendor ?: AiClient.Vendor.OPENAI) || key.trim() != initial?.apiKey.orEmpty().trim() ||
        model.trim() != initial?.model.orEmpty().trim() || baseUrl.trim() != initial?.baseUrl.orEmpty().trim() || imageModel.trim() != initial?.imageModel.orEmpty().trim()
    val close = rememberDiscardGuard(changed, onClose = cancel)

    FitDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ai_setup_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ai_vendor), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiClient.Vendor.entries.forEach { v ->
                        FilterChip(selected = vendor == v, onClick = { vendor = v; models = emptyList(); checkResult = null }, label = { Text(v.label) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ai_free_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vendor == AiClient.Vendor.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                        label = { Text(stringResource(R.string.ai_base_url)) }, placeholder = { Text(stringResource(R.string.ai_base_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it; models = emptyList(); checkResult = null }, singleLine = true,
                    label = { Text(stringResource(R.string.ai_key)) }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_key_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (vendor.keyUrl.isNotEmpty()) TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vendor.keyUrl))) } }) { Text(stringResource(R.string.ai_key_get)) }
                }
                // The plain answer to "where does my key go?", with the one host it goes to, spelled out.
                var trustOpen by remember { mutableStateOf(false) }
                TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), onClick = { trustOpen = !trustOpen }) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_trust_title), style = MaterialTheme.typography.labelLarge)
                }
                if (trustOpen) {
                    val host = current().host.ifBlank { "…" }
                    listOf(stringResource(R.string.ai_trust_1), stringResource(R.string.ai_trust_2, host), stringResource(R.string.ai_trust_3)).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (vendor.limitsUrl.isNotEmpty()) TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vendor.limitsUrl))) } }) { Text(stringResource(R.string.ai_limits)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ai_model)) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(enabled = !loading && (key.isNotBlank() || vendor == AiClient.Vendor.CUSTOM), onClick = {
                            loading = true; error = null
                            scope.launch {
                                try {
                                    models = AiClient.models(current())
                                    if (models.isEmpty()) error = ctx.getString(R.string.ai_models_none) else modelsOpen = true
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.error_generic) }
                                loading = false
                            }
                        }) { Text(stringResource(R.string.ai_models_load)) }
                        var all by remember { mutableStateOf(false) }
                        val shown = if (all) models else AiClient.curated(vendor, models)
                        DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false; all = false }) {
                            shown.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelsOpen = false; all = false; checkResult = null }) }
                            if (!all && shown.size < models.size) DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_models_all), color = MaterialTheme.colorScheme.primary) },
                                onClick = { all = true },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = !loading && current().complete, onClick = {
                        loading = true; error = null; checkResult = null
                        scope.launch { checkResult = AiClient.check(current()) ?: ""; loading = false }
                    }) { Text(stringResource(R.string.ai_check)) }
                }
                if (vendor == AiClient.Vendor.CUSTOM) {
                    // Optional: the model the server draws with, for the poster's AI cover.
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = imageModel, onValueChange = { imageModel = it }, singleLine = true,
                        label = { Text(stringResource(R.string.ai_image_model)) }, placeholder = { Text(stringResource(R.string.ai_image_model_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                checkResult?.let { r ->
                    if (r.isEmpty()) Text(stringResource(R.string.ai_check_ok), color = Tones.onSuccessContainer(), style = MaterialTheme.typography.bodySmall)
                    else Text(r, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = current().complete, onClick = { AiClient.save(ctx, current()); AiDraft.models = emptyList(); AiSetupDraft.closing = true; AiSetupDraft.key = null; onSaved(current()) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { AiClient.clear(ctx); AiDraft.models = emptyList(); AiSetupDraft.closing = true; AiSetupDraft.key = null; onSaved(null) }) { Text(stringResource(R.string.ai_forget), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** La lista proposta dall'AI: si toglie quello che non si vuole, poi si conferma. */
@Composable
private fun AiReviewDialog(tracks: List<Track>, targetLabel: String, onDismiss: () -> Unit, onConfirm: (List<Track>) -> Unit) {
    var kept by remember { mutableStateOf(tracks) }
    FitDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_review_title)) },
        // A list of its own: it scrolls, under the line above it.
        scroll = false,
        text = {
            Column {
                Text(stringResource(R.string.ai_review_hint, targetLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(kept, key = { it.id }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(t.artistLine, t.album).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { kept = kept - t }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_review_remove)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = kept.isNotEmpty(), onClick = { onConfirm(kept) }) { Text(pluralStringResource(R.plurals.ai_review_confirm, kept.size, kept.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * "Aggiungi brani con l'AI" su una sync nata dal generatore: stessa descrizione, i brani gia' nel
 * file come contesto, N proposte nuove da rivedere e poi aggiungere al file; la sync riparte e le
 * porta sul servizio.
 */
@Composable
fun AiExtendDialog(job: SyncJob, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val d = AiDraft
    var config by remember { mutableStateOf(AiClient.config(ctx)) }
    val use = AiClient.pick(config, NanoAi.state, NanoAi.NAME)
    var setupOpen by remember { mutableStateOf(use == null) }
    var count by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(10f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var proposed by remember { mutableStateOf<List<Track>?>(null) }
    val fileId = job.source.playlistId

    if (setupOpen) {
        AiSetupDialog(config, onDismiss = { if (use != null) setupOpen = false else onClose() }) { config = it; d.models = emptyList(); setupOpen = false }
        return
    }
    val c = use ?: return
    val ready = !c.local || NanoAi.state == AiClient.LocalState.READY
    val target = Providers.byId(job.target.provider)
    proposed?.let { list ->
        AiReviewDialog(list, target?.label(ctx) ?: job.target.provider, onDismiss = { proposed = null }) { kept ->
            proposed = null
            if (fileId == null) return@AiReviewDialog
            d.scope.launch {
                try {
                    withContext(Dispatchers.IO) { LocalFilesProvider.addTracks(app, fileId, kept) }
                    Scheduler.runNow(app, job.id)
                    onClose()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    error = e.message ?: app.getString(R.string.error_generic)
                }
            }
        }
        return
    }
    FitDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ai_extend_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ai_extend_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(job.aiPrompt.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                if (c.local) NanoStatus()
                AiCountField(count.toInt()) { count = it.toFloat() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                TextButton(enabled = !busy && ready && fileId != null, onClick = {
                    busy = true; error = null
                    d.scope.launch {
                        val outcome = runCatching {
                            val existing = if (fileId != null) LocalFilesProvider.tracks(app, fileId) else emptyList()
                            val language = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
                            val names = existing.map { it.toString() }.take(300)
                            val request = AiClient.Prompt(job.aiPrompt.orEmpty(), count.toInt(), emptyList(), language, names)
                            // Checked on the destination first: the review shows only what it has.
                            if (target != null && target.id != LocalFilesProvider.id) AiMatch.available(app, c, target, request, existing).found
                            else AiMatch.fresh(AiClient.generateAll(c, request), existing)
                        }
                        busy = false
                        outcome.onFailure { e -> error = e.message ?: app.getString(R.string.error_generic) }
                        val tracks = outcome.getOrNull() ?: return@launch
                        if (tracks.isEmpty()) error = app.getString(R.string.ai_empty) else proposed = tracks
                    }
                }) { Text(stringResource(R.string.ai_generate)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * "Allunga con l'AI" su una playlist qualsiasi di un servizio: la descrizione (o, vuota, lo spirito
 * dei brani gia' presenti) va all'AI insieme ai brani che ci sono; le proposte passano dal riesame,
 * poi dalla ricerca sul servizio, e solo i brani trovati vengono aggiunti alla playlist stessa.
 */
@Composable
fun AiExpandPlaylistDialog(provider: MusicProvider, playlist: Playlist, onClose: () -> Unit, onDone: (added: Int, missing: Int, alreadyThere: Int) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val d = AiDraft
    var config by remember { mutableStateOf(AiClient.config(ctx)) }
    val use = AiClient.pick(config, NanoAi.state, NanoAi.NAME)
    var setupOpen by remember { mutableStateOf(use == null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf(10f) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var proposed by remember { mutableStateOf<List<Track>?>(null) }
    // The playlist as it is: the AI is told, and whatever it repeats anyway is dropped, by name before
    // the review and by id after the search on the service.
    var existing by remember { mutableStateOf<List<Track>>(emptyList()) }

    if (setupOpen) {
        AiSetupDialog(config, onDismiss = { if (use != null) setupOpen = false else onClose() }) { config = it; d.models = emptyList(); setupOpen = false }
        return
    }
    val c = use ?: return
    val ready = !c.local || NanoAi.state == AiClient.LocalState.READY
    proposed?.let { list ->
        AiReviewDialog(list, provider.label(ctx), onDismiss = { proposed = null }) { kept ->
            proposed = null
            busy = true
            d.scope.launch {
                try {
                    // Already the service's own tracks: straight in.
                    if (kept.isNotEmpty()) provider.addTracks(app, playlist.id, kept)
                    busy = false
                    onDone(kept.size, 0, 0)
                    onClose()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    busy = false
                    error = e.message ?: app.getString(R.string.error_generic)
                }
            }
        }
        return
    }
    val close = rememberDiscardGuard(prompt.isNotBlank(), onClose = onClose)
    FitDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.ai_extend_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ai_expand_hint, provider.label(ctx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, minLines = 2, maxLines = 4,
                    label = { Text(stringResource(R.string.ai_prompt)) }, placeholder = { Text(stringResource(R.string.ai_expand_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (c.local) NanoStatus()
                AiCountField(count.toInt()) { count = it.toFloat() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(step, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp)) }
                TextButton(enabled = !busy && ready, onClick = {
                    busy = true; error = null; step = ""
                    d.scope.launch {
                        val outcome = runCatching {
                            existing = provider.tracks(app, playlist.id)
                            val language = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
                            val names = existing.map { it.toString() }.take(300)
                            AiMatch.available(app, c, provider, AiClient.Prompt(prompt, count.toInt(), emptyList(), language, names), existing) { found, want -> step = "$found/$want" }.found
                        }
                        busy = false
                        outcome.onFailure { e -> error = e.message ?: app.getString(R.string.error_generic) }
                        val tracks = outcome.getOrNull() ?: return@launch
                        if (tracks.isEmpty()) error = app.getString(R.string.ai_empty) else proposed = tracks
                    }
                }) { Text(stringResource(R.string.ai_generate)) }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * How many tracks to ask for: a number, typed. Above a hundred the model is asked in rounds and the
 * bill in tokens grows with the count, so the field says so and suggests checking the plan first.
 */
@Composable
fun AiCountField(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val digits = t.filter { it.isDigit() }.take(4)
            text = digits
            digits.toIntOrNull()?.coerceIn(1, 2000)?.let(onChange)
        },
        singleLine = true,
        label = { Text(stringResource(R.string.ai_count_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            if (value > AiClient.ROUND) Text(stringResource(R.string.ai_count_warning), color = MaterialTheme.colorScheme.tertiary)
            else Text(stringResource(R.string.ai_count_hint))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}


/**
 * Gemini Nano's state on screen: starts the download when the phone can fetch it and says where it
 * stands meanwhile (bytes, Android still fetching it, or why it failed, with "try again"). Asks
 * AICore again every few seconds, so the screen moves on by itself once the model is there.
 * [onUseKey]: the way out for whoever does not want to wait. True once it is ready to answer.
 */
@Composable
fun NanoStatus(onUseKey: (() -> Unit)? = null): Boolean {
    LaunchedEffect(Unit) {
        NanoAi.prepare()
        while (NanoAi.state != AiClient.LocalState.READY) {
            kotlinx.coroutines.delay(NANO_POLL_MS)
            NanoAi.poll()
        }
    }
    val state = NanoAi.state
    if (state == AiClient.LocalState.READY) return true
    val failed = NanoAi.error
    val total = NanoAi.total
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (failed != null) {
            Text(stringResource(R.string.ai_nano_failed, NanoAi.NAME, failed), style = small, color = MaterialTheme.colorScheme.error)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (total > 0) CircularProgressIndicator(progress = { (NanoAi.downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.size(12.dp))
                else CircularProgressIndicator(Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                val progress = if (total > 0) " ${NanoAi.downloaded / 1_000_000}/${total / 1_000_000} MB" else ""
                Text(stringResource(R.string.ai_nano_preparing, NanoAi.NAME) + progress, style = small, color = muted)
            }
            // No bytes counted: Android fetches the model by itself, and may wait for Wi-Fi to do it.
            if (total <= 0) Text(stringResource(R.string.ai_nano_background), style = small, color = muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (failed != null) TextButton(onClick = { NanoAi.retry() }) { Text(stringResource(R.string.ai_nano_retry)) }
            if (onUseKey != null) TextButton(onClick = onUseKey) { Text(stringResource(R.string.ai_nano_use_key)) }
        }
    }
    return false
}

/** How often a screen waiting for Gemini Nano asks AICore again. */
private const val NANO_POLL_MS = 8_000L

/** The key typed in [AiSetupDialog] and not saved yet, kept across a remade activity in memory only. */
object AiSetupDraft {
    @Volatile var key: String? = null
    /** Set when the dialog is closed on purpose (saved or cancelled), so the draft is dropped. */
    @Volatile var closing = false
}

/** Said wherever Gemini Nano answers (generator, Roast): a small model, with the way to a better one. */
@Composable
internal fun NanoSmallNote() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ai_nano_small), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

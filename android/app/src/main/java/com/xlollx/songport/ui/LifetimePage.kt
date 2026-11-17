package com.xlollx.songport.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.importTracks
import com.xlollx.songport.sync.ListeningExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * The export being read, the sums made of it and the sending to ListenBrainz, kept while the app
 * runs. The listens themselves stay in memory only, for ListenBrainz: gone with the process, never
 * written or logged; what is saved for the page opened again is the sums ([ListeningExport.keep]).
 */
internal object LifetimeKept {
    val work = KeptWork<ListeningExport.Stats>()
    val send = KeptWork<Int>()
    var plays by mutableStateOf<List<ListeningExport.Play>?>(null)
    /** How many listens were read so far, for the progress line. */
    var read by mutableStateOf(0)
    /** The "how niche" score is being asked. */
    var scoring by mutableStateOf(false)
}

private const val HOW_SPOTIFY = "https://www.spotify.com/account/privacy/"
private const val HOW_YOUTUBE = "https://takeout.google.com/"
private const val HOW_DEEZER = "https://www.deezer.com/account/"

/**
 * Discover › Lifetime stats: the export file a service sends on request, opened with the system's
 * picker and read here ([ListeningExport]): figures, a listening clock, the artists of all time,
 * the years, a card to share, a playlist of the most played, and the history sent to ListenBrainz
 * for who wants it.
 */
@Composable
internal fun LifetimePage(snackbar: SnackbarHostState, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val k = LifetimeKept
    val scope = rememberCoroutineScope()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readExport(ctx, uri) }
    // Opened again: the sums kept are shown, with no file.
    LaunchedEffect(Unit) { if (k.work.result == null && !k.work.running) ListeningExport.kept(ctx.env)?.let { k.work.show("kept", it) } }

    val stats = k.work.result
    if (k.work.running) { ToolLoading(stringResource(R.string.lifetime_reading, k.read)); return }
    k.work.error?.let { ToolError(it.ifBlank { stringResource(R.string.error_generic) }) }
    if (stats == null) {
        ToolNote(stringResource(R.string.lifetime_steps))
        HowRow(stringResource(R.string.lifetime_how_spotify), HOW_SPOTIFY)
        HowRow(stringResource(R.string.lifetime_how_youtube), HOW_YOUTUBE)
        HowRow(stringResource(R.string.lifetime_how_deezer), HOW_DEEZER)
        ToolPrimaryButton(stringResource(R.string.lifetime_open), onClick = { open.launch(arrayOf("*/*")) })
        Text(stringResource(R.string.lifetime_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val zone = remember { ZoneId.systemDefault() }
    val fromYear = remember(stats) { if (stats.from > 0) Instant.ofEpochMilli(stats.from).atZone(zone).year else null }
    Text(
        listOfNotNull(fromYear?.let { stringResource(R.string.lifetime_since, it) }, stats.sources.joinToString(", ")).joinToString(" · "),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stats.timed) ToolStat(grouped(stats.hours), stringResource(R.string.lifetime_hours), Modifier.weight(1f))
        else ToolStat(compact(stats.plays.toLong()), stringResource(R.string.lifetime_plays), Modifier.weight(1f))
        ToolStat(grouped(stats.artists.toLong()), stringResource(R.string.diary_stat_artists), Modifier.weight(1f))
        ToolStat(compact(stats.tracks.toLong()), stringResource(R.string.lifetime_songs), Modifier.weight(1f))
        if (stats.obscurity != null || k.scoring) ToolStat(stats.obscurity?.toString() ?: "…", stringResource(R.string.lifetime_niche), Modifier.weight(1f))
    }
    if (stats.obscurity != null) Text(stringResource(R.string.lifetime_niche_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    ToolSection(stringResource(R.string.lifetime_clock)) { ListeningClock(stats.clock) }
    ToolSection(stringResource(R.string.lifetime_top_artists)) {
        val top = stats.topArtists.take(8)
        val max = top.maxOfOrNull { stats.weight(it) }?.coerceAtLeast(1) ?: 1
        top.forEach { a -> BarRow(a.name, stats.weight(a).toFloat() / max, amount(stats, a.ms, a.plays)) }
    }
    var yearsOpen by rememberSaveable { mutableStateOf(false) }
    var songsOpen by rememberSaveable { mutableStateOf(false) }
    if (stats.years.isNotEmpty()) {
        Expandable(stringResource(R.string.diary_card_by_year), yearsOpen, { yearsOpen = !yearsOpen }) {
            stats.years.asReversed().forEach { y -> BarRow(y.year.toString(), 0f, listOf(amount(stats, y.ms, y.plays), y.artist).filter { it.isNotBlank() }.joinToString(" · "), bar = false) }
        }
        Expandable(stringResource(R.string.lifetime_year_songs), songsOpen, { songsOpen = !songsOpen }) {
            stats.years.asReversed().forEach { y -> y.song?.let { BarRow(y.year.toString(), 0f, it.title + " · " + it.artist, bar = false) } }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
            val card = lifetimeCard(ctx, stats, fromYear)
            scope.launch { Cards.share(ctx, withContext(Dispatchers.Default) { Cards.wrapped(ctx, card, Cards.Format.STORY) }, "trackhop-lifetime") }
        }) { Text(stringResource(R.string.lifetime_card)) }
        Button(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
            // The most played as a File playlist, the source of a new sync: the editor asks where it goes.
            val name = ctx.getString(R.string.lifetime_playlist_name)
            scope.launch {
                try {
                    val id = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(ctx.applicationContext, name, ListeningExport.topTracks(stats)) }
                    onNewSync { copy(name = name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id)) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { snackbar.showSnackbar(e.userMessage(ctx)) }
            }
        }) { Text(stringResource(R.string.lifetime_playlist)) }
    }
    SendToListenBrainz()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.lifetime_another)) }
        TextButton(onClick = { ListeningExport.keep(ctx.env, null); k.plays = null; k.work.clear() }) { Text(stringResource(R.string.lifetime_forget)) }
    }
}

private fun grouped(n: Long): String = String.format("%,d", n)

/** 21500 as "21.5k": a tile has little room. */
private fun compact(n: Long): String = if (n < 10_000) grouped(n) else String.format("%.1fk", n / 1000.0)

/** "412 h" where the export says how long, else "×412" plays. */
private fun amount(stats: ListeningExport.Stats, ms: Long, plays: Int): String = if (stats.timed) grouped(ms / 3_600_000) + " h" else "×" + grouped(plays.toLong())

@Composable
private fun HowRow(label: String, url: String) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { openUrl(ctx, url) }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.lifetime_request), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The listening of each hour of the day as 24 bars, midnight at the left. */
@Composable
private fun ListeningClock(clock: List<Long>) {
    val id = LocalToolIdentity.current
    val max = (clock.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(72.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        clock.forEach { v ->
            Box(Modifier.weight(1f).fillMaxHeight((v.toFloat() / max).coerceIn(0.03f, 1f)).clip(RoundedCornerShape(3.dp)).background(id.accent))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A name, a bar of its share (when [bar]) and its amount. */
@Composable
private fun BarRow(name: String, share: Float, value: String, bar: Boolean = true) {
    val id = LocalToolIdentity.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (bar) Modifier.width(120.dp) else Modifier.width(48.dp))
        if (bar) Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(id.accent))
        }
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = if (bar) Modifier else Modifier.weight(1f))
    }
}

/** A section that opens on a tap of its title. */
@Composable
internal fun Expandable(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) content()
    }
}

/** Optional: the whole history to the user's ListenBrainz, after a second tap that says how many listens go. */
@Composable
private fun SendToListenBrainz() {
    val ctx = LocalContext.current
    val k = LifetimeKept
    var armed by rememberSaveable { mutableStateOf(false) }
    ToolSection(stringResource(R.string.lifetime_lb_title), hint = stringResource(R.string.lifetime_lb_desc)) {
        val token = remember { ArtistMixSource.listenBrainzToken(ctx.env) }
        val plays = k.plays
        val real = remember(plays) { plays?.count { it.at > 0 && (it.ms == 0L || it.ms >= ListeningExport.MIN_MS) } ?: 0 }
        when {
            k.send.running -> ToolLoading(stringResource(R.string.lifetime_lb_sending), progress = k.send.progress?.takeIf { it.second > 0 }?.let { it.first.toFloat() / it.second })
            token == null -> Text(stringResource(R.string.lifetime_lb_no_token), style = MaterialTheme.typography.bodySmall)
            plays == null -> Text(stringResource(R.string.lifetime_lb_reopen), style = MaterialTheme.typography.bodySmall)
            else -> OutlinedButton(onClick = {
                if (!armed) armed = true
                else { armed = false; k.send.start(null) { progress -> try { ListeningExport.submit(token, plays, progress) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw Exception(e.userMessage(ctx)) } } }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (armed) stringResource(R.string.lifetime_lb_confirm, real) else stringResource(R.string.lifetime_lb_title))
            }
        }
        k.send.error?.let { ToolError(it.ifBlank { stringResource(R.string.error_generic) }) }
        if (!k.send.running) k.send.result?.let { Text(stringResource(R.string.lifetime_lb_sent, it), style = MaterialTheme.typography.bodySmall) }
    }
}

/** Reads the export at [uri] (the user picked it), makes the sums and then asks how niche the top artists are. */
private fun readExport(ctx: Context, uri: Uri) {
    val app = ctx.applicationContext
    val k = LifetimeKept
    k.read = 0; k.plays = null; k.send.clear()
    k.work.start("file") { _ ->
        val stats = withContext(Dispatchers.IO) {
            val builder = ListeningExport.Builder()
            val plays = ArrayList<ListeningExport.Play>()
            try {
                (app.contentResolver.openInputStream(uri) ?: throw ListeningExport.NotAnExport()).use { input ->
                    ListeningExport.read(input) { p -> builder.add(p); plays += p; if (plays.size % 2000 == 0) k.read = plays.size }
                }
            } catch (e: ListeningExport.NotAnExport) {
                throw Exception(app.getString(R.string.lifetime_not_export))
            }
            k.plays = plays
            builder.build().also { ListeningExport.keep(app.env, it) }
        }
        // The score needs the network and a few seconds: the page shows the rest meanwhile.
        k.scoring = true
        AppWork.scope.launch {
            try {
                val score = withContext(Dispatchers.IO) { ListeningExport.obscurity(app.env, stats) }
                if (score != null && k.work.result === stats) stats.copy(obscurity = score).let { ListeningExport.keep(app.env, it); k.work.show("file", it) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
            } finally { k.scoring = false }
        }
        stats
    }
}

/** The lifetime as a card to share, drawn like the Wrapped's. */
private fun lifetimeCard(ctx: Context, s: ListeningExport.Stats, fromYear: Int?): Cards.WrappedCard {
    val total = s.years.sumOf { if (s.timed) it.ms else it.plays.toLong() }.coerceAtLeast(1)
    return Cards.WrappedCard(
        eyebrow = ctx.getString(R.string.lifetime_card_eyebrow),
        headline = if (s.timed) grouped(s.hours) + " " + ctx.getString(R.string.lifetime_hours) else grouped(s.plays.toLong()) + " " + ctx.getString(R.string.lifetime_plays),
        subline = fromYear?.let { ctx.getString(R.string.lifetime_since, it) },
        artistsTitle = ctx.getString(R.string.lifetime_top_artists),
        artists = s.topArtists.take(5).map { it.name to amount(s, it.ms, it.plays) },
        decadesTitle = ctx.getString(R.string.diary_card_by_year),
        decades = s.years.sortedByDescending { if (s.timed) it.ms else it.plays.toLong() }.take(4).sortedBy { it.year }
            .map { it.year.toString() to ((if (s.timed) it.ms else it.plays.toLong()) * 100 / total).toInt() },
        tiles = listOfNotNull(
            compact(s.artists.toLong()) to ctx.getString(R.string.diary_stat_artists),
            compact(s.tracks.toLong()) to ctx.getString(R.string.lifetime_songs),
            s.obscurity?.let { it.toString() to ctx.getString(R.string.lifetime_niche) },
        ),
        facts = emptyList(), servicesTitle = "", services = emptyList(),
    )
}

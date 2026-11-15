package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.DiscoverSource
import com.xlollx.songport.sync.ConcertDiary
import com.xlollx.songport.sync.SetlistImport
import com.xlollx.songport.sync.Setlists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The diary being read and the one last read, kept while the app runs (another tab and back, the screen turned). */
internal object DiaryKept {
    val work = KeptWork<ConcertDiary.Diary>()
}

/** What went wrong reading something of Discover's, in the user's words. */
internal fun discoverError(ctx: Context, e: Exception): String = when (e) {
    is SetlistImport.NoKey -> ctx.getString(R.string.diary_need_key)
    is Setlists.QuotaGone -> ctx.getString(R.string.setlists_quota)
    is Setlists.KeyRefused -> ctx.getString(R.string.setlists_key_refused)
    else -> e.userMessage(ctx)
}

/**
 * Concerts › "I was there": the concerts marked as attended on setlist.fm ([ConcertDiary]) as
 * figures, a list by year, a card to share and three playlists, each the source of a sync the
 * editor then asks the destination for ([DiscoverSource]).
 */
@Composable
internal fun ConcertDiaryTab(snackbar: SnackbarHostState, onNewSync: (SyncJob.() -> SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val env = ctx.env
    val k = DiaryKept.work
    val scope = rememberCoroutineScope()
    var user by rememberSaveable { mutableStateOf(ConcertDiary.user(env).orEmpty()) }
    var editing by rememberSaveable { mutableStateOf(ConcertDiary.user(env) == null) }
    var keyOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    if (keyOpen) SetlistFmKeyDialog { keyOpen = false }
    val hasKey = remember(keyOpen) { Setlists.key(env) != null }

    fun read(refresh: Boolean) {
        k.start(ConcertDiary.user(env)) { progress ->
            try { ConcertDiary.read(env, refresh, progress) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw Exception(discoverError(ctx, e)) }
        }
    }
    // Opened again: the diary kept is shown at once, and read again only when it is old.
    LaunchedEffect(Unit) {
        if (editing || k.running) return@LaunchedEffect
        if (k.result == null) ConcertDiary.kept(env)?.let { k.show(it.user, it) }
        if (hasKey) read(false)
    }

    Text(stringResource(R.string.diary_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (editing) {
        OutlinedTextField(
            value = user, onValueChange = { user = it.trim() }, singleLine = true,
            label = { Text(stringResource(R.string.diary_user_field)) }, modifier = Modifier.fillMaxWidth(),
        )
        if (!hasKey) {
            ToolNote(stringResource(R.string.diary_need_key))
            TextButton(onClick = { keyOpen = true }) { Text(stringResource(R.string.setlists_key_title)) }
        }
        ToolPrimaryButton(stringResource(R.string.diary_connect), enabled = user.isNotBlank() && hasKey, onClick = {
            ConcertDiary.saveUser(env, user); k.clear(); editing = false; read(true)
        })
        return
    }
    if (k.running) ToolLoading(stringResource(R.string.diary_reading), progress = k.progress?.takeIf { it.second > 1 }?.let { it.first.toFloat() / it.second })
    k.error?.let { e -> ToolError(e.ifBlank { stringResource(R.string.error_generic) }) { k.error = null; read(true) } }

    val diary = k.result?.takeIf { k.key == ConcertDiary.user(env) }
    if (diary != null) {
        val list = diary.concerts
        if (list.isEmpty()) ToolEmpty(stringResource(R.string.diary_empty))
        else {
            val stats = remember(diary) { ConcertDiary.stats(list) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolStat(stats.concerts.toString(), stringResource(R.string.diary_stat_concerts), Modifier.weight(1f))
                ToolStat(stats.artists.toString(), stringResource(R.string.diary_stat_artists), Modifier.weight(1f))
                ToolStat(stats.venues.toString(), stringResource(R.string.diary_stat_venues), Modifier.weight(1f))
                stats.firstYear?.let { ToolStat(it.toString(), stringResource(R.string.diary_stat_first), Modifier.weight(1f)) }
            }
            stats.topArtist?.let { Text(stringResource(R.string.diary_top_artist, it, stats.topTimes), style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val card = diaryCard(ctx, list, stats)
                OutlinedButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = {
                    scope.launch { Cards.share(ctx, withContext(Dispatchers.Default) { Cards.wrapped(ctx, card, Cards.Format.STORY) }, "trackhop-concerts") }
                }) { Text(stringResource(R.string.diary_share)) }
                Button(modifier = Modifier.weight(1f).heightIn(min = 48.dp), onClick = { sheet = true }) { Text(stringResource(R.string.diary_make)) }
            }
            ConcertDiary.byYear(list).forEach { (year, concerts) ->
                ToolSectionTitle(year)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        concerts.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider()
                            ConcertRow(s) { if (s.url.isNotBlank()) openUrl(ctx, s.url) }
                        }
                    }
                }
            }
            // setlist.fm's terms: where the setlists come from, with a link.
            Text(
                stringResource(R.string.setlists_attribution), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(ctx, Setlists.HOME) }.padding(vertical = 4.dp),
            )
        }
    }
    if (!k.running) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { read(true) }) { Text(stringResource(R.string.diary_refresh)) }
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.diary_change_user)) }
    }
    if (sheet && diary != null) DiaryPlaylistSheet(diary, onDismiss = { sheet = false }) { id, name, schedule ->
        sheet = false
        onNewSync { copy(name = name, source = PlaylistRef(DiscoverSource.SERVICE, id, name), schedule = schedule) }
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** One concert: its day, the artist, where it was and how many songs were played. */
@Composable
private fun ConcertRow(s: Setlists.Setlist, onClick: () -> Unit) {
    val songs = remember(s) { Setlists.tracks(s).size }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            runCatching { LocalDate.parse(s.date).format(DAY) }.getOrDefault(s.date), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(s.artist, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                (listOf(s.venue, s.city).filter { it.isNotBlank() } + listOfNotNull(pluralStringResource(R.plurals.setlists_songs, songs, songs).takeIf { songs > 0 })).joinToString(" · ").replaceFirst(" · ", ", "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The diary as a card to share, drawn like the Wrapped's: the concerts, the artists seen most, the years, three figures. */
private fun diaryCard(ctx: Context, list: List<Setlists.Setlist>, stats: ConcertDiary.Stats): Cards.WrappedCard = Cards.WrappedCard(
    eyebrow = ctx.getString(R.string.diary_card_eyebrow),
    headline = ctx.resources.getQuantityString(R.plurals.diary_concerts, stats.concerts, stats.concerts),
    subline = stats.firstYear?.let { ctx.getString(R.string.diary_card_since, it) },
    artistsTitle = ctx.getString(R.string.diary_card_top),
    artists = ConcertDiary.artists(list).take(5).map { it.first to "${it.second}×" },
    decadesTitle = ctx.getString(R.string.diary_card_by_year),
    decades = ConcertDiary.byYear(list).take(4).map { (y, c) -> y to (c.size * 100 / list.size) },
    tiles = listOf(
        stats.artists.toString() to ctx.getString(R.string.diary_stat_artists),
        stats.venues.toString() to ctx.getString(R.string.diary_stat_venues),
        stats.songs.toString() to ctx.resources.getQuantityString(R.plurals.setlists_songs, stats.songs, stats.songs).substringAfter(' '),
    ),
    facts = emptyList(), servicesTitle = "", services = emptyList(),
)

/** Test tag of the sheet "A playlist from your concerts". */
const val DIARY_SHEET_TAG = "diarySheet"

/**
 * "A playlist from your concerts": every song heard, one concert, or an artist's songs not heard
 * yet; the last two ask which one. [onPick] gets the source's id, the playlist's name and how often it is read again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryPlaylistSheet(diary: ConcertDiary.Diary, onDismiss: () -> Unit, onPick: (String, String, Schedule) -> Unit) {
    val ctx = LocalContext.current
    // 0: the three choices; 1: which concert; 2: which artist.
    var step by rememberSaveable { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag(DIARY_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val title = when (step) { 1 -> R.string.diary_pick_show; 2 -> R.string.diary_pick_artist; else -> R.string.diary_sheet_title }
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            when (step) {
                0 -> {
                    val heard = remember(diary) { ConcertDiary.heard(diary.concerts).size }
                    SheetChoice(stringResource(R.string.diary_opt_heard), stringResource(R.string.diary_opt_heard_sub, heard)) {
                        onPick(DiscoverSource.HEARD, ctx.getString(R.string.diary_playlist_heard), Schedule.DAILY)
                    }
                    SheetChoice(stringResource(R.string.diary_opt_show), stringResource(R.string.diary_opt_show_sub)) { step = 1 }
                    SheetChoice(stringResource(R.string.diary_opt_missing), stringResource(R.string.diary_opt_missing_sub)) { step = 2 }
                    Text(stringResource(R.string.diary_sheet_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                // A night's setlist does not change: made once, run by hand.
                1 -> diary.concerts.filter { c -> c.songs.any { !it.tape } }.forEach { s ->
                    SheetChoice(s.artist, listOf(s.date, s.venue, s.city).filter { it.isNotBlank() }.joinToString(" · ")) {
                        onPick(DiscoverSource.SHOW + s.id, Setlists.playlistName(s), Schedule.MANUAL)
                    }
                }
                else -> ConcertDiary.artists(diary.concerts).forEach { (artist, times) ->
                    SheetChoice(artist, "$times×") { onPick(DiscoverSource.MISSING + artist, ctx.getString(R.string.diary_playlist_missing, artist), Schedule.DAILY) }
                }
            }
        }
    }
}

/** One choice of a sheet: its name and a line of what it is. */
@Composable
internal fun SheetChoice(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

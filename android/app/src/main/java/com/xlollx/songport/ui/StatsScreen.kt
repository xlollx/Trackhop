package com.xlollx.songport.ui

import com.xlollx.songport.providers.ServiceBrand
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.providers.*
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** The figures of the screen and of the card, formatted once in the app's language. */
private class StatsText(ctx: Context, val s: UsageStats.Summary, val services: Int) {
    private val locale: Locale = ctx.resources.configuration.locales[0]
    private val nf: NumberFormat = NumberFormat.getIntegerInstance(locale)
    fun n(v: Int): String = nf.format(v)

    val since: String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(s.sinceEpoch))

    /** "≈ 12 h" or "≈ 25 min": rounded, and always marked as approximate. */
    val timeSaved: String? = s.secondsSaved.takeIf { s.added > 0 }?.let { sec ->
        if (sec >= 3_600) "≈ " + ctx.getString(R.string.stats_hours_short, nf.format((sec + 1_800) / 3_600))
        else "≈ " + ctx.getString(R.string.stats_minutes_short, nf.format(((sec + 30) / 60).coerceAtLeast(1)))
    }

    val match: String? = s.matchPercent?.let { "$it%" }
}

private fun provider(id: String): MusicProvider? = Providers.byId(id)

/** A service's name for the route and the longest playlist: the service's, never an account's. */
private fun serviceName(id: String): String = provider(id)?.let { ServiceBrand.of(it.serviceId, it.familyName) } ?: id

private fun cardMark(id: String): Cards.Mark {
    val p = provider(id)
    return Cards.Mark(p?.let { ServiceBrand.of(it.serviceId, it.familyName) } ?: id, p?.brandColor?.toInt() ?: 0xFF7C3AED.toInt(), p?.serviceId)
}

/** The card: tracks moved as the headline, then up to three figures the history backs, and the route. */
private fun statsCard(ctx: Context, t: StatsText): Cards.StatsCard {
    val s = t.s
    val figures = buildList {
        add(t.n(s.runs) to ctx.getString(R.string.stats_syncs_done))
        t.match?.let { add(it to ctx.getString(R.string.stats_match_rate)) }
        t.timeSaved?.let { add(it to ctx.getString(R.string.stats_time_saved)) }
        if (size < 3 && s.addedThisMonth > 0) add(t.n(s.addedThisMonth) to ctx.getString(R.string.stats_this_month))
        if (size < 3 && t.services > 0) add(t.n(t.services) to ctx.getString(R.string.stats_services))
    }
    return Cards.StatsCard(
        title = ctx.getString(R.string.stats_title),
        headline = t.n(s.added),
        headlineLabel = ctx.getString(R.string.stats_tracks_moved),
        since = ctx.getString(R.string.stats_card_since, t.since),
        figures = figures.take(3),
        routeLabel = ctx.getString(R.string.stats_top_route),
        route = s.route?.let { cardMark(it.sourceProvider) to cardMark(it.targetProvider) },
    )
}

/**
 * "Trackhop in numbers": what the syncs have done, counted from the history the app already keeps
 * ([UsageStats]), and a story or square card to share it. Nothing leaves the phone except the card
 * the user shares; a figure the history cannot back is not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(data: StoreData, onClose: () -> Unit) {
    val ctx = LocalContext.current
    ScreenBackHandler(onClose)
    // Services, not connectors: two Spotify accounts are one service.
    val services = remember {
        Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) }.map { FileStore.family(it.id) }.distinct().size
    }
    val summary = remember(data.syncs, data.reports) { UsageStats.compute(data, System.currentTimeMillis(), ZoneId.systemDefault()) }
    val text = remember(summary, services) { StatsText(ctx, summary, services) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    LaunchedEffect(text) {
        if (summary.isEmpty) return@LaunchedEffect
        val card = statsCard(ctx, text)
        bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.stats(ctx, card, it) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
    ) { padding ->
        if (summary.isEmpty) Box(Modifier.padding(padding)) {
            EmptyState(Icons.Filled.Insights, stringResource(R.string.stats_empty_title), stringResource(R.string.stats_empty_body))
        } else Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatsHero(text)
            StatTiles(text)
            summary.route?.let { r ->
                SectionCard(stringResource(R.string.stats_top_route)) {
                    val a = serviceName(r.sourceProvider); val b = serviceName(r.targetProvider)
                    val detail = ctx.resources.getQuantityString(R.plurals.stats_n_syncs, r.runs, r.runs) + "  ·  " +
                        ctx.resources.getQuantityString(R.plurals.card_tracks, r.added, r.added)
                    val route = stringResource(R.string.stats_route_desc, a, b)
                    Row(
                        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$route, $detail" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RouteLogos(r.sourceProvider, r.targetProvider, 40)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$a → $b", style = MaterialTheme.typography.titleMedium)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            summary.longest?.let { l ->
                SectionCard(stringResource(R.string.stats_longest)) {
                    val count = ctx.resources.getQuantityString(R.plurals.card_tracks, l.tracks, l.tracks)
                    val route = stringResource(R.string.stats_route_desc, serviceName(l.sourceProvider), serviceName(l.targetProvider))
                    Row(
                        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${l.name}, $count, $route" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(count, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        RouteLogos(l.sourceProvider, l.targetProvider, 28)
                    }
                }
            }
            if (summary.topArtists.isNotEmpty()) {
                SectionCard(stringResource(R.string.stats_top_artists)) {
                    summary.topArtists.forEachIndexed { i, a ->
                        val count = ctx.resources.getQuantityString(R.plurals.card_tracks, a.tracks, a.tracks)
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(count, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Note(ctx.resources.getQuantityString(R.plurals.stats_artists_note, summary.artistSample, summary.artistSample))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stats_share_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            CardActions(
                bitmaps, "trackhop-in-numbers", busy = bitmaps == null, onRender = null, renderLabel = "",
                previewDescription = stringResource(R.string.stats_card_preview),
            )

            // How the numbers are made: the estimate's rule, what the match rate counts, what is left out.
            Spacer(Modifier.height(4.dp))
            if (text.timeSaved != null) Note(ctx.resources.getQuantityString(R.plurals.stats_time_note, UsageStats.SECONDS_PER_TRACK, UsageStats.SECONDS_PER_TRACK))
            if (text.match != null) Note(stringResource(R.string.stats_match_note))
            if (summary.historyCapped) Note(ctx.resources.getQuantityString(R.plurals.stats_history_cap, FileStore.MAX_REPORTS, FileStore.MAX_REPORTS))
            Note(stringResource(R.string.stats_local_note))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The headline, on the brand beam like the Syncs header: tracks moved, and over which period. */
@Composable
private fun StatsHero(t: StatsText) {
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .brandBeam()
            .semantics(mergeDescendants = true) {}
            .padding(20.dp),
    ) {
        Column {
            BrandTile(40.dp)
            Spacer(Modifier.height(12.dp))
            Text(t.n(t.s.added), color = Color.White, style = MaterialTheme.typography.displayMedium.merge(TabularNumbers))
            Text(stringResource(R.string.stats_tracks_moved), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(ctx.resources.getQuantityString(R.plurals.stats_since, t.s.runs, t.s.runs, t.since), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The other figures as tiles, two to a row; one to a row at a large font size, where two would
 * squeeze the numbers. A tile the history cannot back is not there.
 */
@Composable
private fun StatTiles(t: StatsText) {
    val s = t.s
    val month = stringResource(R.string.stats_this_month)
    val runs = stringResource(R.string.stats_syncs_done)
    val services = stringResource(R.string.stats_services)
    val match = stringResource(R.string.stats_match_rate)
    val saved = stringResource(R.string.stats_time_saved)
    val tiles = buildList {
        add(t.n(s.addedThisMonth) to month)
        add(t.n(s.runs) to runs)
        if (t.services > 0) add(t.n(t.services) to services)
        t.match?.let { add(it to match) }
        t.timeSaved?.let { add(it to saved) }
    }
    val perRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f).fillMaxHeight()) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Card(modifier.semantics(mergeDescendants = true) {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium.merge(TabularNumbers), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Source → target marks; the names are read from the row around them. */
@Composable
private fun RouteLogos(source: String, target: String, size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServiceLogo(provider(source), size.dp)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp).size((size * 0.5f).dp))
        ServiceLogo(provider(target), size.dp)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

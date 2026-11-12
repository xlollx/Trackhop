package com.xlollx.songport.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.PlayCounts
import com.xlollx.songport.providers.SpotifyBridgeProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.SpotifyProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.providers.brand
import com.xlollx.songport.providers.isConnected
import com.xlollx.songport.providers.tracks
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.Wrapped
import kotlinx.coroutines.CancellationException

/*
 * The Wrapped of what was listened to: where it is read from (a listening service, or the
 * playlists), the period, and the top songs on the page. The reading is [WrappedRun.startListening].
 */

/** The value of [com.xlollx.songport.data.Settings.wrappedSource] that asks for the playlists. */
internal const val WRAPPED_FROM_PLAYLISTS = "playlists"

/** The listening services connected, the best first, each with the connector that reads it (the first connected one). */
internal fun listeningSources(ctx: Context): List<Pair<Listening.Source, MusicProvider>> {
    // The Wrapped and the poster from real listening are 1.18's (Features): before it, both read the playlists, as in 1.16.
    if (!com.xlollx.songport.Features.v2) return emptyList()
    val connected = Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) }
    return Listening.Source.entries.mapNotNull { s ->
        connected.firstOrNull { p ->
            p.serviceId == s.serviceId && when (s) {
                Listening.Source.LASTFM -> p is LastFmProvider
                Listening.Source.LISTENBRAINZ -> p is ListenBrainzProvider
                Listening.Source.SPOTIFY -> p is SpotifyProvider
                // The web player's top songs: only where the connectors are the full ones (GitHub, F-Droid).
                Listening.Source.SPOTIFY_WEB -> p is SpotifyBridgeProvider && BridgePlugin.full
                Listening.Source.DEEZER -> p is DeezerProvider
                // The top artists of TIDAL's "My activity", asked with the Open API sign-in's token.
                Listening.Source.TIDAL -> p is TidalProvider
                // A personal server's own play counts.
                Listening.Source.SUBSONIC, Listening.Source.JELLYFIN, Listening.Source.EMBY,
                Listening.Source.PLEX, Listening.Source.LYRION -> p is PlayCounts
                // The recent plays: only where the connector reads them (the full build's).
                Listening.Source.YTM -> p.supportsRecent
                // The official Apple Music sign-in: its recent plays and Replay.
                Listening.Source.APPLE -> p is AppleMusicProvider && p.supportsRecent
            }
        }?.let { s to it }
    }
}

/**
 * What [p] ([s]'s connector) says the user listened to in [period]: the one place each source is
 * read, for the Wrapped and the poster alike. Blocking calls: run it off the main thread.
 */
internal suspend fun readListening(ctx: Context, s: Listening.Source, p: MusicProvider, period: Listening.Period): Listening.Report {
    val env = ctx.env
    return when (s) {
        Listening.Source.LASTFM -> (p as LastFmProvider).listening(env, period)
        Listening.Source.LISTENBRAINZ -> (p as ListenBrainzProvider).listening(env, period)
        // One Spotify for the user: the official sign-in first; when it gives nothing (no permission
        // for the top songs yet, a refusal), the web player's sign-in, where there is one, answers.
        Listening.Source.SPOTIFY -> {
            val official = try { Result.success((p as SpotifyProvider).listening(env, period)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            official.getOrNull()?.takeIf { it.tracks.isNotEmpty() || it.artists.isNotEmpty() }
                ?: spotifyWeb(ctx)?.let { w -> try { w.listening(env, period).takeIf { it.tracks.isNotEmpty() || it.artists.isNotEmpty() } } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
                ?: official.getOrThrow()
        }
        Listening.Source.SPOTIFY_WEB -> (p as SpotifyBridgeProvider).listening(env, period)
        Listening.Source.DEEZER -> (p as DeezerProvider).listening(env)
        Listening.Source.TIDAL -> (p as TidalProvider).listening(env, period)
        Listening.Source.SUBSONIC, Listening.Source.JELLYFIN, Listening.Source.EMBY,
        Listening.Source.PLEX, Listening.Source.LYRION -> Listening.fromCounts((p as PlayCounts).topPlays(env))
        Listening.Source.YTM -> Listening.fromHistory(p.tracks(ctx, MusicProvider.RECENT_ID))
        Listening.Source.APPLE -> (p as AppleMusicProvider).listening(env, period)
    }
}

/** The web player's Spotify connector, signed in, where the connectors are the full ones. */
private fun spotifyWeb(ctx: Context): SpotifyBridgeProvider? =
    if (!BridgePlugin.full) null else Providers.connectors().firstOrNull { it is SpotifyBridgeProvider && it.isConnected(ctx) } as SpotifyBridgeProvider?

/** The choices on the Wrapped page: one Spotify, the official one standing for both when both are connected. */
internal fun wrappedChoices(all: List<Pair<Listening.Source, MusicProvider>>): List<Pair<Listening.Source, MusicProvider>> =
    if (all.any { it.first == Listening.Source.SPOTIFY }) all.filterNot { it.first == Listening.Source.SPOTIFY_WEB } else all

internal fun periodLabel(ctx: Context, p: Listening.Period): String = ctx.getString(when (p) {
    Listening.Period.MONTH -> R.string.discover_wrapped_period_month
    Listening.Period.HALF_YEAR -> R.string.discover_wrapped_period_half
    Listening.Period.YEAR -> R.string.discover_wrapped_period_year
    Listening.Period.RECENT -> R.string.discover_wrapped_eyebrow_recent
    Listening.Period.ALL -> R.string.discover_wrapped_period_all
})

/** The card's eyebrow: the period ("My last 6 months"), or the year for a Wrapped of the playlists ("My 2026 so far"). */
internal fun wrappedEyebrow(ctx: Context, w: Wrapped.Saved): String = when (Listening.Period.of(w.period)) {
    Listening.Period.MONTH -> ctx.getString(R.string.discover_wrapped_eyebrow_month)
    Listening.Period.HALF_YEAR -> ctx.getString(R.string.discover_wrapped_eyebrow_half)
    Listening.Period.YEAR -> ctx.getString(R.string.discover_wrapped_eyebrow_year)
    Listening.Period.RECENT -> ctx.getString(R.string.discover_wrapped_eyebrow_recent)
    Listening.Period.ALL -> ctx.getString(R.string.discover_wrapped_eyebrow_all)
    null -> ctx.getString(R.string.discover_wrapped_eyebrow, w.year)
}

/** Where a Wrapped comes from, said plainly with the service's own name: "From your listening on Last.fm", "From your playlists". */
internal fun wrappedSourceLine(ctx: Context, w: Wrapped.Saved): String =
    w.services.firstOrNull()?.takeIf { w.listening }?.let { ctx.getString(R.string.discover_wrapped_from_listening, it.name) }
        ?: ctx.getString(R.string.discover_wrapped_from_playlists)

/**
 * What the Wrapped is made from: a chip per listening service connected and one for the
 * playlists, then the period the chosen service can tell (a month, six months, a year), or a line
 * saying that one only knows the latest plays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WrappedSourcePicker(
    sources: List<Pair<Listening.Source, MusicProvider>>,
    chosen: Listening.Source?,
    period: Listening.Period,
    enabled: Boolean,
    onSource: (Listening.Source?) -> Unit,
    onPeriod: (Listening.Period) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ToolSection(stringResource(R.string.discover_wrapped_source), hint = stringResource(R.string.discover_wrapped_source_hint)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sources.forEach { (s, p) ->
                val on = s == chosen
                FilterChip(
                    selected = on, enabled = enabled, onClick = { onSource(s) },
                    label = { Text(p.brand) },
                    leadingIcon = { ServiceLogo(p, size = 20.dp) },
                    trailingIcon = if (on) ({ Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)) }) else null,
                )
            }
            val on = chosen == null
            FilterChip(
                selected = on, enabled = enabled, onClick = { onSource(null) },
                label = { Text(stringResource(R.string.discover_wrapped_playlists)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(20.dp)) },
                trailingIcon = if (on) ({ Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)) }) else null,
            )
        }
        if (chosen != null && chosen.periods.size > 1) {
            ToolSegmented(chosen.periods.map { periodLabel(ctx, it) }, chosen.periods.indexOf(period).coerceAtLeast(0), Modifier.fillMaxWidth(), enabled = enabled) { onPeriod(chosen.periods[it]) }
        } else if (chosen != null) {
            val name = sources.firstOrNull { it.first == chosen }?.second?.brand.orEmpty()
            // A single span: the latest plays, every play a server counted, or a ranking with no span said.
            val hint = when {
                Listening.Period.RECENT in chosen.periods -> R.string.discover_wrapped_recent_hint
                chosen.counts -> R.string.discover_wrapped_all_hint_counts
                else -> R.string.discover_wrapped_all_hint_rank
            }
            Text(stringResource(hint, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The songs played most, numbered, each with its cover (loaded from the address kept with the Wrapped) and its plays. */
@Composable
internal fun WrappedTopSongs(songs: List<Wrapped.Song>) {
    if (songs.isEmpty()) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ToolSection(stringResource(R.string.discover_wrapped_top_tracks)) {
        songs.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
                Spacer(Modifier.width(8.dp))
                SongCover(s, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.artist.isNotBlank()) Text(s.artist, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.plays > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(pluralStringResource(R.plurals.discover_wrapped_plays, s.plays, s.plays), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                }
            }
        }
    }
}

/** A top song's cover, [side] dp wide: the kept address, else (and until it loads) a tile in the song's colours. */
@Composable
private fun SongCover(s: Wrapped.Song, side: Int) {
    var bmp by remember(s.cover) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(s.cover) { if (s.cover.isNotBlank()) bmp = Cards.bitmap(s.cover, side * 3) }
    val b = bmp
    if (b == null) TrackCover(Track(s.title + "|" + s.artist, s.title, listOf(s.artist)), null, side.dp, load = false)
    else androidx.compose.foundation.Image(
        b.asImageBitmap(), null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(side.dp).clip(RoundedCornerShape(8.dp)),
    )
}

/**
 * Spotify refused the top songs: the sign-in predates the permission. Said plainly, with a
 * button to sign in again (the user's choice: nothing else changes until they do).
 */
@Composable
internal fun SpotifyScopeNote(onSignIn: () -> Unit) {
    androidx.compose.material3.Surface(Modifier.fillMaxWidth(), shape = ToolCardShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.discover_wrapped_spotify_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            androidx.compose.material3.FilledTonalButton(onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.discover_wrapped_spotify_signin))
            }
        }
    }
}

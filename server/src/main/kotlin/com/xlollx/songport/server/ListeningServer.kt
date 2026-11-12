package com.xlollx.songport.server

import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.PlayCounts
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.SpotifyProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.Listening
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * What the user actually listened to, read on the server as the app reads it (the app's
 * WrappedListening.kt): the listening services connected, the best first, and the one place each is
 * read, for the Wrapped (WrappedWeb.kt) and the poster (PosterWeb.kt). The parsing is :core's
 * [Listening]; each connector is the user's own (a shared server reads each person's with theirs).
 */
internal object ServerListening {
    /** The listening services connected, the best first, each with the connector that reads it (the first connected one). */
    fun sources(env: Env): List<Pair<Listening.Source, MusicProvider>> {
        val connected = Providers.connectors().filter { it.requiresAuth && it.isConnected(env) }
        return Listening.Source.entries.mapNotNull { s ->
            connected.firstOrNull { p ->
                p.serviceId == s.serviceId && when (s) {
                    Listening.Source.LASTFM -> p is LastFmProvider
                    Listening.Source.LISTENBRAINZ -> p is ListenBrainzProvider
                    Listening.Source.SPOTIFY -> p is SpotifyProvider
                    // The web player's top songs: the server's own Spotify (web) connector (cookies).
                    Listening.Source.SPOTIFY_WEB -> p is SpotifyWebServerProvider
                    Listening.Source.DEEZER -> p is DeezerProvider
                    Listening.Source.TIDAL -> p is TidalProvider
                    Listening.Source.SUBSONIC, Listening.Source.JELLYFIN, Listening.Source.EMBY,
                    Listening.Source.PLEX, Listening.Source.LYRION -> p is PlayCounts
                    Listening.Source.YTM -> p.supportsRecent
                }
            }?.let { s to it }
        }
    }

    /** The choices offered for a Wrapped: one Spotify, the official sign-in standing for both when both are connected. */
    fun choices(env: Env): List<Pair<Listening.Source, MusicProvider>> {
        val all = sources(env)
        return if (all.any { it.first == Listening.Source.SPOTIFY }) all.filterNot { it.first == Listening.Source.SPOTIFY_WEB } else all
    }

    /** A service's name as people know it: "Spotify (web)" is Spotify. */
    fun brand(p: MusicProvider): String = com.xlollx.songport.sync.Wrapped.brandName(p.familyName)

    /** The source asked by its key ("lastfm", "spotify", "jellyfin"…), its service id, or its connector's id. */
    fun source(env: Env, asked: String): Pair<Listening.Source, MusicProvider>? {
        val k = asked.trim().lowercase()
        return sources(env).firstOrNull { (s, p) -> s.name.lowercase() == k || s.serviceId == k || p.id.lowercase() == k }
    }

    fun key(s: Listening.Source): String = s.name.lowercase()

    /**
     * What [p] ([s]'s connector) says the user listened to in [period]. Blocking calls behind
     * suspensions; throws [Listening.MissingScope], [Listening.Unavailable] or the service's error.
     */
    suspend fun read(env: Env, s: Listening.Source, p: MusicProvider, period: Listening.Period): Listening.Report = when (s) {
        Listening.Source.LASTFM -> (p as LastFmProvider).listening(env, period)
        Listening.Source.LISTENBRAINZ -> (p as ListenBrainzProvider).listening(env, period)
        // One Spotify for the user: the official sign-in first; when it gives nothing, the web player's, where connected.
        Listening.Source.SPOTIFY -> {
            val official = try { Result.success((p as SpotifyProvider).listening(env, period)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            official.getOrNull()?.takeIf { it.tracks.isNotEmpty() || it.artists.isNotEmpty() }
                ?: spotifyWeb(env)?.let { w -> try { w.listening(env, period).takeIf { it.tracks.isNotEmpty() || it.artists.isNotEmpty() } } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                ?: official.getOrThrow()
        }
        Listening.Source.SPOTIFY_WEB -> (p as SpotifyWebServerProvider).listening(env, period)
        Listening.Source.DEEZER -> (p as DeezerProvider).listening(env)
        Listening.Source.TIDAL -> (p as TidalProvider).listening(env, period)
        Listening.Source.SUBSONIC, Listening.Source.JELLYFIN, Listening.Source.EMBY,
        Listening.Source.PLEX, Listening.Source.LYRION -> Listening.fromCounts((p as PlayCounts).topPlays(env))
        Listening.Source.YTM -> Listening.fromHistory(p.tracks(env, MusicProvider.RECENT_ID)).let { r ->
            r.copy(tracks = r.tracks.map { it.copy(cover = DeezerCatalog.ownCover(it.track.id, p.serviceId).lastOrNull()) })
        }
    }

    private fun spotifyWeb(env: Env): SpotifyWebServerProvider? =
        Providers.connectors().filterIsInstance<SpotifyWebServerProvider>().firstOrNull { it.isConnected(env) }

    // ------------------------------------------------------------------ a short memory, for the poster

    private class Kept(val at: Long, val report: Listening.Report)
    private val kept = ConcurrentHashMap<String, Kept>()
    private const val TTL_MS = 10 * 60_000L

    /** [read], kept ten minutes per user, source and period: a poster made again does not ask the service again. */
    suspend fun cached(env: Env, s: Listening.Source, p: MusicProvider, period: Listening.Period, fresh: Boolean = false): Listening.Report {
        val k = "${(env as? ServerEnv)?.owner.orEmpty()}|${s.name}|${p.id}|${period.key}"
        val now = System.currentTimeMillis()
        if (kept.size > 500) kept.entries.removeIf { now - it.value.at >= TTL_MS }
        kept[k]?.takeIf { !fresh && now - it.at < TTL_MS }?.let { return it.report }
        return read(env, s, p, period).also { kept[k] = Kept(now, it) }
    }
}

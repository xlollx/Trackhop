package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.providers.Providers.Factory

/**
 * The connectors of the Android app, in the order the service picker shows them: the engine's,
 * with the web connectors (built in, or through a plugin app) next to their official route.
 * SoundCloud is one service: the account (web connector) and the public links it has always read,
 * which the builds without built-in connectors reach only through a plugin (see [linkProvider]).
 */
fun installConnectors() = Providers.install(
    listOfNotNull(
        Factory(SpotifyProvider.SERVICE) { SpotifyProvider(it) },
        Factory(SpotifyBridgeProvider.SERVICE) { SpotifyBridgeProvider(it) },
        Factory(AppleMusicProvider.SERVICE) { AppleMusicProvider(it) },
        Factory(AppleBridgeProvider.SERVICE) { AppleBridgeProvider(it) },
        Factory(YouTubeBridgeProvider.SERVICE) { YouTubeBridgeProvider(it) },
        Factory(YouTubeProvider.SERVICE) { YouTubeProvider(it) },
        Factory(AmazonBridgeProvider.SERVICE) { AmazonBridgeProvider(it) },
        Factory(TidalProvider.SERVICE) { TidalProvider(it) },
        Factory(DeezerProvider.SERVICE) { DeezerProvider(it) },
        Factory(DeezerBridgeProvider.SERVICE) { DeezerBridgeProvider(it) },
        // LiveOne has no official route: only in the builds that carry the web connectors.
        Factory(LiveOneBridgeProvider.SERVICE) { LiveOneBridgeProvider(it) }.takeIf { BuiltIn.available },
        Factory(com.xlollx.songport.sync.SoundCloudPublic.SERVICE) { SoundCloudBridgeProvider(it) },
        Factory(SubsonicProvider.SERVICE) { SubsonicProvider(it) },
        Factory(JellyfinProvider.SERVICE) { JellyfinProvider(it) },
        Factory(PlexProvider.SERVICE) { PlexProvider(it) },
        Factory(LastFmProvider.SERVICE) { LastFmProvider(it) },
        Factory(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) },
    ),
    // The read-only link sources with no official route (NetEase Cloud Music, QQ Music): with the
    // builds that carry unofficial connectors, as SoundCloud's links; not in the Google Play one.
    singletons = listOfNotNull(LocalFilesProvider, NeteaseProvider.takeIf { BuiltIn.available }, QqMusicProvider.takeIf { BuiltIn.available }),
)

/**
 * The connector a pasted playlist link of [providerId] opens with in this build, or null: a service
 * that only a plugin serves (SoundCloud in the Google Play build) is no link this build can use
 * until a plugin serves it.
 */
fun linkProvider(ctx: Context, providerId: String): MusicProvider? =
    Providers.byId(providerId)?.takeIf { !it.pluginBased || BridgePlugin.builtIn || it.isConfigured(ctx) }

/** A source whose own error (a private set) is all there is to say about a pasted link: shown at once. */
fun MusicProvider.reportsLinkErrors(playlistId: String): Boolean =
    linkOnly || (serviceId == com.xlollx.songport.sync.SoundCloudPublic.SERVICE && SoundCloudBridgeProvider.isPage(playlistId))

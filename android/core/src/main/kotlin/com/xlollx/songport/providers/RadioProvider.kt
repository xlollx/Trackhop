package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.RadioConnectors
import com.xlollx.songport.sync.ShazamLibrary

/**
 * A radio station as a connector (sync/RadioConnectors): one per station, as many as wanted, each
 * with its own name and colour. Its songs are the ones the station played on air, noted by the
 * listening of each sync from it in that sync's File playlist: those are its playlists here, read
 * only. No account, nothing to sign in to: the station is chosen when the connector is added.
 */
class RadioProvider(override val slot: String = "") : MusicProvider {
    override val serviceId = RadioConnectors.SERVICE
    override val displayName = "Radio"
    override val brandColor = 0xFFD84315
    override val noteName = "provider_note_radio"
    override val requiresAuth = false
    override val supportsMultipleAccounts = true
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1

    /** The station this connector listens to; null when it has none yet. */
    fun station(ctx: Env) = ctx.store.data.settings.radioStations[id]

    override fun label(ctx: Env): String = Providers.customName(id) ?: station(ctx)?.station ?: displayName
    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = station(ctx)?.station
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** The File playlists of the syncs from this station: what each heard in its hours. */
    override suspend fun playlists(ctx: Env): List<Playlist> =
        RadioConnectors.syncsOf(ctx.store.data.jobs, id).mapNotNull { j ->
            j.source.playlistId?.takeIf { j.source.provider == LocalFilesProvider.id }?.let { Playlist(it, j.source.playlistName.ifBlank { j.name }, ownedByMe = false) }
        }.distinctBy { it.id }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = LocalFilesProvider.tracks(ctx, playlistId)
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = LocalFilesProvider.playlistVersion(ctx, playlistId)
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = LocalFilesProvider.playlistInfo(ctx, playlistId)
    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = throw ProviderException("A radio is read-only.")
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = throw ProviderException("A radio is read-only.")
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = throw ProviderException("A radio is read-only.")
}

/**
 * Shazam as a connector, one only: every song the user Shazams, from the playlist the Shazam app
 * fills on their Spotify or Apple Music ([ShazamLibrary]), read through that account's own
 * connector. Nothing is asked of Shazam; a sync from it is the Shazam sync it always was.
 */
object ShazamProvider : MusicProvider {
    override val serviceId = RadioConnectors.SHAZAM
    override val displayName = "Shazam"
    override val brandColor = 0xFF0088FF
    override val noteName = "provider_note_shazam"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** The accounts that can hold Shazam's playlist and are signed in, in the Accounts order. */
    fun hosts(ctx: Env): List<MusicProvider> = Providers.connectors().filter { ShazamLibrary.canHost(it.serviceId) && runCatching { it.isConnected(ctx) }.getOrDefault(false) }

    /** A playlist here is "<account id>|<its playlist id>": Shazam's playlist on that account. */
    private fun split(playlistId: String): Pair<MusicProvider, String>? {
        val host = Providers.byId(playlistId.substringBefore('|', "")) ?: return null
        return host to playlistId.substringAfter('|')
    }

    /** Shazam's playlist on each account that has one. */
    override suspend fun playlists(ctx: Env): List<Playlist> = hosts(ctx).mapNotNull { h ->
        val found = runCatching { ShazamLibrary.find(h.playlists(ctx)) }.getOrNull() ?: return@mapNotNull null
        found.copy(id = "${h.id}|${found.id}", name = "${found.name} · ${h.label(ctx)}", ownedByMe = false)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val (host, id) = split(playlistId) ?: throw ProviderException("Shazam's playlist was not found.")
        return host.tracks(ctx, id)
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = throw ProviderException("Shazam is read-only.")
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = throw ProviderException("Shazam is read-only.")
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = throw ProviderException("Shazam is read-only.")
}

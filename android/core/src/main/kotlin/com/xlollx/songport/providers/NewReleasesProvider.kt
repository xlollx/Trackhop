package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.NewReleases

/**
 * The "New releases" sync: its source is the new albums, EPs and singles of the artists of one of
 * the user's playlists ([NewReleases]), read again at every run. Made from Discover › New releases
 * (app) or the Releases page (server), weekly and keeping every track (SyncJob.archive): the
 * target fills up with what came out, on any service. Its playlist id is "<days>:<service>:<playlist>",
 * the window and the playlist the artists come from.
 *
 * Never listed with the services (nothing to add or sign in to): [Providers.byId] finds it by [SERVICE].
 */
object NewReleasesProvider : MusicProvider {
    const val SERVICE = "releases"

    override val serviceId = SERVICE
    override val displayName = "New releases"
    override val brandColor = 0xFF8E24AA
    override val noteName = "releases_note"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1

    override fun label(ctx: Env): String = ctx.text("releases_label")
    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** The window and the playlist behind a playlist id of this source. */
    data class Ref(val days: Int, val provider: String, val playlist: String)

    fun id(days: Int, provider: String, playlist: String) = "$days:$provider:$playlist"

    fun parse(playlistId: String): Ref? {
        val parts = playlistId.split(':', limit = 3)
        if (parts.size != 3) return null
        val days = parts[0].toIntOrNull()?.takeIf { it in 1..365 } ?: return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null
        return Ref(days, parts[1], parts[2])
    }

    /** Nothing to list: such a sync is made from the page that finds new releases. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()

    override fun canRead(ctx: Env, playlistId: String) = parse(playlistId) != null

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val ref = parse(playlistId) ?: throw ProviderException(ctx.text("releases_unknown"), code = 404)
        val p = Providers.byId(ref.provider)
        val name = p?.let { runCatching { it.playlistInfo(ctx, ref.playlist).name }.getOrNull() }?.takeIf { it.isNotBlank() } ?: ref.playlist
        return Playlist(playlistId, ctx.text("releases_playlist_name", name))
    }

    /**
     * The tracks of what the playlist's artists released in the window. Throws when the playlist
     * cannot be read or neither catalogue answered for any artist: a run that learned nothing
     * must not look like a week without news.
     */
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val ref = parse(playlistId) ?: throw ProviderException(ctx.text("releases_unknown"), code = 404)
        val p = Providers.byId(ref.provider) ?: throw ProviderException(ctx.text("releases_unknown"), code = 404)
        if (p.requiresAuth && !p.isConnected(ctx)) throw ProviderException(ctx.text("error_not_connected", p.label(ctx)))
        val artists = NewReleases.artists(p.tracks(ctx, ref.playlist))
        if (artists.isEmpty()) return emptyList()
        val found = NewReleases.find(ctx, artists, ref.days, upcoming = false)
        if (found.unanswered == artists.size) throw ProviderException(ctx.text("releases_unavailable"))
        return NewReleases.tracksOf(ctx, found.releases)
    }

    /** Never a destination. */
    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", label(ctx)))
}

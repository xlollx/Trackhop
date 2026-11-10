package com.xlollx.songport.shared

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider

/**
 * A shared playlist as one side of a sync: its playlists are the groups this device takes part in,
 * a group's tracks are its live tracks (see [SharedGroups.listing]), adding and removing write
 * signed events. Searching it for a member's track answers the track itself: the group holds
 * whatever its curators add. No list of services shows it; a group's syncs name it by [ID].
 */
object SharedProvider : MusicProvider {
    const val ID = "shared"
    override val serviceId = ID
    override val displayName = "Shared playlist"
    override val brandColor = 0xFF7C4DFF
    override val noteName = "shared_note"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canCreatePlaylists = false
    override val canRenamePlaylists = false
    override val canDeletePlaylists = false
    // Everything is local: no reason to search one track at a time.
    override val searchParallelism = 8

    private fun group(ctx: Env, id: String): SharedGroup =
        SharedGroups.group(ctx, id) ?: throw ProviderException(ctx.text("shared_error_gone"))

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun canRead(ctx: Env, playlistId: String): Boolean = SharedGroups.group(ctx, playlistId) != null
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    override suspend fun playlists(ctx: Env): List<Playlist> = ctx.store.data.groups.map { Playlist(it.id, it.name) }
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = Playlist(playlistId, group(ctx, playlistId).name)
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = SharedGroups.listing(ctx, group(ctx, playlistId))

    /** Only the group a sync writes to knows which one is meant: the track is answered as a group track of each. */
    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val groups = ctx.store.data.groups
        return if (groups.size == 1) listOf(SharedGroups.asGroupTrack(ctx, groups[0].id, track))
        else listOf(groups.map { SharedGroups.asGroupTrack(ctx, it.id, track) }.firstOrNull { it.id != SharedEvent.keyOf(track) } ?: track.copy(id = SharedEvent.keyOf(track)))
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        throw ProviderException(ctx.text("shared_error_gone"))

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = SharedGroups.add(ctx, group(ctx, playlistId), tracks)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = SharedGroups.remove(ctx, group(ctx, playlistId), tracks)
}

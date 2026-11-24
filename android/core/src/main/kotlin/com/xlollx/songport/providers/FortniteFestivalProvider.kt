package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Relay
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.FortniteFestival

/**
 * Fortnite Festival's Jam Tracks as a source: all of them, or those added in the last 30 days, to
 * keep a playlist of the user's on any service up to date as the season brings new ones. No
 * account (a player's own Jam Tracks cannot be read). Read from a page Epic does not document
 * ([FortniteFestival]): installed only by the builds that carry unofficial connectors, and on Google
 * Play only while a plugin makes its requests ([Relay]), like the link sources.
 */
object FortniteFestivalProvider : MusicProvider {
    const val ALL = "all"
    const val NEW = "new"

    override val serviceId = FortniteFestival.SERVICE
    override val displayName = "Fortnite Festival"
    override val brandColor = 0xFF8E44EC
    override val noteName = "provider_note_fortnite"
    override val beta = true
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1
    override val category get() = ServiceCatalog.Category.GAMES
    override val aliases get() = listOf("Fortnite", "Jam Tracks")

    override fun isConfigured(ctx: Env) = Relay.available(ctx)
    override fun isConnected(ctx: Env) = Relay.available(ctx)
    override val pluginBased: Boolean get() = Relay.throughPlugin
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit
    override fun canRead(ctx: Env, playlistId: String) = (playlistId == ALL || playlistId == NEW) && Relay.available(ctx)

    private suspend fun <T> reading(ctx: Env, block: suspend () -> T): T = try {
        block()
    } catch (e: FortniteFestival.Changed) {
        throw ProviderException(ctx.text("fortnite_changed"), e)
    }

    override suspend fun playlists(ctx: Env): List<Playlist> = reading(ctx) {
        val all = FortniteFestival.all()
        listOf(
            Playlist(ALL, ctx.text("fortnite_all"), all.size, ownedByMe = false),
            Playlist(NEW, ctx.text("fortnite_new"), FortniteFestival.recent(all, System.currentTimeMillis()).size, ownedByMe = false),
        )
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        Playlist(playlistId, ctx.text(if (playlistId == NEW) "fortnite_new" else "fortnite_all"), ownedByMe = false)

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = reading(ctx) {
        val all = FortniteFestival.all()
        when (playlistId) {
            ALL -> all
            NEW -> FortniteFestival.recent(all, System.currentTimeMillis())
            else -> throw ProviderException(ctx.text("fortnite_changed"), null, 404)
        }.map { it.track() }
    }

    /**
     * The newest Jam Track and how many there are (and, for the last 30 days, the day, as tracks
     * leave that list by date): a scheduled sync skips the run when nothing changed.
     */
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = runCatching {
        val all = FortniteFestival.all()
        val day = if (playlistId == NEW) ":" + System.currentTimeMillis() / 86_400_000L else ""
        "${all.size}:${all.firstOrNull()?.id}$day"
    }.getOrNull()

    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", displayName))
}

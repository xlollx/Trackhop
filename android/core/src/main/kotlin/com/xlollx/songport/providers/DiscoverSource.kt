package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.ConcertDiary
import com.xlollx.songport.sync.Discogs
import com.xlollx.songport.sync.Setlists
import com.xlollx.songport.sync.Venues

/**
 * The playlists Discover's pages make and keep updated: the source of a sync, read again at every
 * run like [NewReleasesProvider], so the target follows what the user does elsewhere (a concert
 * marked on setlist.fm). The playlist id says which:
 * - [HEARD]: every song heard live, the most heard first ([ConcertDiary.heard]);
 * - [SHOW]`<setlist id>`: one concert's setlist, in order;
 * - [MISSING]`<artist>`: the artist's best known songs never heard at their concerts;
 * - [RECORDS]: the songs of the records of the user's Discogs collection ([Discogs.collectionTracks]);
 * - [NEARBY]: "Concerts near you", the best known songs of who plays at the venues followed ([Venues.nearbyTracks]).
 *
 * Never listed with the services: [Providers.byId] finds it by [SERVICE].
 */
object DiscoverSource : MusicProvider {
    const val SERVICE = "discover"
    const val HEARD = "heard"
    const val SHOW = "show:"
    const val MISSING = "missing:"
    const val RECORDS = "records"
    const val NEARBY = "nearby"

    override val serviceId = SERVICE
    override val displayName = "Discover"
    override val brandColor = 0xFFAD1457
    override val noteName = "discover_source_note"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1

    override fun label(ctx: Env): String = ctx.text("discover_source_label")
    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** Nothing to list: such a sync is made from the page that shows the playlist. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()

    private fun known(id: String) = id == HEARD || id == RECORDS || id == NEARBY || (id.startsWith(SHOW) && id.length > SHOW.length) || (id.startsWith(MISSING) && id.length > MISSING.length)

    override fun canRead(ctx: Env, playlistId: String) = known(playlistId)

    /** The playlist's name, without asking anyone: what the page shows before it is made. */
    fun name(ctx: Env, playlistId: String): String = when {
        playlistId == HEARD -> ctx.text("diary_playlist_heard")
        playlistId == RECORDS -> ctx.text("discover_myrecords_title")
        playlistId == NEARBY -> ctx.text("venues_playlist_name")
        playlistId.startsWith(MISSING) -> ctx.text("diary_playlist_missing", playlistId.removePrefix(MISSING))
        playlistId.startsWith(SHOW) -> ConcertDiary.kept(ctx)?.concerts?.firstOrNull { it.id == playlistId.removePrefix(SHOW) }?.let { Setlists.playlistName(it) } ?: ctx.text("diary_playlist_show")
        else -> throw ProviderException(ctx.text("discover_source_unknown"), code = 404)
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = Playlist(playlistId, name(ctx, playlistId))

    /** Throws when the source cannot be read: a run that learned nothing must not empty the target. */
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = when {
        playlistId == HEARD -> ConcertDiary.heard(ConcertDiary.read(ctx).concerts)
        playlistId == RECORDS -> Discogs.collectionTracks(ctx)
        playlistId == NEARBY -> Venues.nearbyTracks(ctx)
        playlistId.startsWith(MISSING) -> ConcertDiary.notHeardYet(ctx, ConcertDiary.read(ctx).concerts, playlistId.removePrefix(MISSING))
        playlistId.startsWith(SHOW) -> {
            val id = playlistId.removePrefix(SHOW)
            // The diary's own copy first (no call); a concert no longer marked is still its setlist.
            val s = ConcertDiary.kept(ctx)?.concerts?.firstOrNull { it.id == id } ?: Setlists.byId(ctx, id)
                ?: throw ProviderException(ctx.text("discover_source_unknown"), code = 404)
            Setlists.tracks(s)
        }
        else -> throw ProviderException(ctx.text("discover_source_unknown"), code = 404)
    }

    /** Never a destination. */
    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", label(ctx)))
}

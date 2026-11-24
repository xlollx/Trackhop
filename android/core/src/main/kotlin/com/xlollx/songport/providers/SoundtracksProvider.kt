package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Soundtracks

/**
 * A MusicBrainz release or release group (a game's, a film's or a series' soundtrack, or any
 * album) from a pasted musicbrainz.org link, as a read-only source: its songs with their
 * MusicBrainz ids and ISRCs, which find them on any service ([Soundtracks]). The official API, no
 * account and no key: every build has it, Google Play's too.
 */
object SoundtracksProvider : MusicProvider {
    override val serviceId = Soundtracks.SERVICE
    override val displayName = "MusicBrainz"
    override val brandColor = 0xFFBA478F
    override val noteName = "provider_note_musicbrainz"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val linkOnly = true
    override val searchParallelism = 1

    override fun label(ctx: Env): String = ctx.text("musicbrainz_label")
    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit
    override fun canRead(ctx: Env, playlistId: String) = playlistId.startsWith("release-group/") || playlistId.startsWith("release/")
    override fun webSearchUrl(ctx: Env, query: String): String = "https://musicbrainz.org/search?type=recording&query=${Http.enc(query)}"

    private suspend fun <T> reading(ctx: Env, block: suspend () -> T): T = try {
        block()
    } catch (e: Soundtracks.Unavailable) {
        throw ProviderException(ctx.text("musicbrainz_unavailable"), e)
    }

    /** Nothing to list: the source is always a pasted link. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = reading(ctx) { Soundtracks.read(playlistId).second }
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = reading(ctx) {
        val (title, songs) = Soundtracks.read(playlistId)
        Playlist(playlistId, title, songs.size, ownedByMe = false)
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", displayName))
}

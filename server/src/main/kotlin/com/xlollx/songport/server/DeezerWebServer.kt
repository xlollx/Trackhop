package com.xlollx.songport.server

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.ytmbridge.BridgeException
import com.xlollx.songport.ytmbridge.DeezerWebClient
import com.xlollx.songport.ytmbridge.PlaylistDto
import com.xlollx.songport.ytmbridge.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Deezer without an app id on the server (Deezer no longer registers new ones): the app's own web
 * client (DeezerWebClient, compiled from :bridge), signed in with the cookies of a browser where the
 * user is signed in to deezer.com. `arl` is the one that names the account; playlists and favourite
 * tracks go through the web player's gateway, search through the public catalogue, as in the app.
 * Unofficial: Deezer's terms do not allow it and it can stop working when the player changes.
 *
 * Same service id and the same mapping as the app's connector (DeezerBridgeProvider and the Bridge's
 * `deezer.*` methods), so a sync means the same thing on both.
 */
class DeezerWebServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "Deezer (web)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Deezer"
    override val brandColor = 0xFFA238FF
    override val noteName = "provider_note_deezer_bridge"
    override val alpha: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: its own cookies, kv file and caches (see [ServerWebHost]). */
    override val supportsMultipleAccounts = true
    override val authDomain = "deezer.com"
    /** The gateway and the public API both dislike bursts. */
    override val searchParallelism = 2
    override val cookieSite = "www.deezer.com"
    override val cookieExample = "arl=…; sid=…; dzr_uniq_id=…"

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = session(ctx) != null
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    /** Signing in is pasting cookies in the web page. */
    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /** Checked before saving: the gateway must name a signed-in user. */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val cookies = pastedCookies(pasted, DOMAINS, "arl") ?: throw ProviderException(
            "These cookies hold no signed-in Deezer session (no arl cookie). Copy them from a browser where you are signed in to deezer.com.",
        )
        val probe = Tokens(accessToken = cookies, extra = pastedUserAgent(userAgent)?.let { mapOf(ServerWebHost.UA to it) } ?: emptyMap())
        val kv = cache(ctx)
        val user = withContext(Dispatchers.IO) {
            // A new session: the last one's gateway sid and user token go.
            forget(ctx, kv)
            try {
                DeezerWebClient(ServerWebHost(ctx, id, kv, probe)).user(refresh = true)
            } catch (e: Exception) {
                forget(ctx, kv)
                throw when {
                    e.message == DeezerWebClient.EXPIRED -> ProviderException(
                        "These Deezer cookies are signed out (the gateway answers as a visitor). Copy fresh ones from a browser where you are signed in to deezer.com.", e,
                    )
                    e is BridgeException -> ProviderException("$displayName: ${e.message}", e)
                    else -> e
                }
            }
        }
        ctx.tokens.set(id, probe.copy(userId = user.id, userName = user.name.orEmpty()))
    }

    override fun disconnect(ctx: Env) {
        ctx.tokens.clear(id)
        forget(ctx, cache(ctx))
    }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx) { c -> c.libraryPlaylists().map { toPlaylist(it) } }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = io(ctx) { c -> toPlaylist(c.playlistInfo(playlistId)) }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = io(ctx) { c -> c.version(playlistId) }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = io(ctx) { c ->
        (if (playlistId == MusicProvider.LIKED_ID) c.likedTracks() else c.playlistTracks(playlistId)).map { toTrack(it) }
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = io(ctx) { c -> c.track(trackId)?.let { toTrack(it) } }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.deezer.com/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Only tracks: the web route does not handle saved albums or followed artists.
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx) { c -> c.search(q, track.isrcNorm).map { toTrack(it) } }
        if (r.isNotEmpty()) return r
        // The plain title, when artist and title together found nothing (features, remixes).
        return io(ctx) { c -> c.search(Matcher.searchTitle(track.title), null).map { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx) { c -> toPlaylist(c.createPlaylist(name, description)) }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) { io(ctx) { it.renamePlaylist(playlistId, name) } }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { io(ctx) { it.deletePlaylist(playlistId) } }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ids = tracks.map { it.id }
        io(ctx) { c -> if (playlistId == MusicProvider.LIKED_ID) c.like(ids, on = true) else c.addToPlaylist(playlistId, ids) }
    }

    /** By track id, like the official route: every copy of a track leaves the playlist. */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ids = tracks.map { it.id }
        io(ctx) { c -> if (playlistId == MusicProvider.LIKED_ID) c.like(ids, on = false) else c.removeFromPlaylist(playlistId, ids) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun session(ctx: Env): Tokens? = ctx.tokens.get(id)?.takeIf { it.accessToken.isNotBlank() }

    private fun cache(ctx: Env): KeyValues = ctx.keyValues(ServerWebHost.kvName("deezer_web", slot))

    /** Drops what the client keeps for this account's session: the gateway's sid and the user with its token (process-wide, under this account's scope). */
    private fun forget(ctx: Env, kv: KeyValues) {
        kv.clear()
        DeezerWebClient.forget(ServerWebHost.scope(ctx, id))
    }

    private fun toPlaylist(p: PlaylistDto) = Playlist(p.id, p.name, p.count, ownedByMe = p.owned, description = p.description)

    /** The same fields the app reads from the Bridge's answer. */
    private fun toTrack(t: TrackDto): Track = Track(
        id = t.id,
        title = t.title,
        artists = t.artists,
        album = t.album,
        durationMs = t.durationMs,
        isrc = t.isrc,
        explicit = t.explicit,
        addedAt = t.addedAt ?: 0,
        year = t.year ?: 0,
        available = t.available,
    )

    /** A blocking client call off the caller's thread, its failure worded as the app words it, with the server's way out. */
    private suspend fun <T> io(ctx: Env, block: (DeezerWebClient) -> T): T = withContext(Dispatchers.IO) {
        if (session(ctx) == null) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(DeezerWebClient(ServerWebHost(ctx, id, cache(ctx))))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            val why = if (e.message == DeezerWebClient.EXPIRED) EXPIRED else e.message ?: e.javaClass.simpleName
            throw ProviderException("$displayName: $why", e)
        }
    }

    companion object {
        const val SERVICE = "deezer_bridge"
        private val DOMAINS = listOf("deezer.com")
        const val EXPIRED = "the web session has expired: paste fresh cookies in Services, Deezer (web)"
    }
}

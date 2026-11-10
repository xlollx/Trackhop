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
import com.xlollx.songport.sync.Reorder
import com.xlollx.songport.ytmbridge.BridgeException
import com.xlollx.songport.ytmbridge.PlaylistDto
import com.xlollx.songport.ytmbridge.RemoveItem
import com.xlollx.songport.ytmbridge.SpotifyWebClient
import com.xlollx.songport.ytmbridge.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Spotify without a developer key on the server: the app's own web client (SpotifyWebClient, compiled
 * from :bridge), signed in with the cookies of a browser where the user is signed in to
 * open.spotify.com. Everything goes through the web player's backend, as in the app.
 *
 * The bearer is read from the player page fetched with the cookies (its `session` block), the
 * first of the app's ways to get it; the others need the app's hidden browser, which a server does
 * not have. A page that answers as a visitor means the cookies have ended. Unofficial, like the
 * app's connector: Spotify's terms do not allow it and it can stop working when the player changes.
 *
 * Same service id and the same mapping as the app's connector (SpotifyBridgeProvider and the
 * Bridge's `spotify.*` methods), so a sync means the same thing on both.
 */
class SpotifyWebServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "Spotify (web)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Spotify"
    override val brandColor = 0xFF1DB954
    override val noteName = "provider_note_spotify_bridge"
    override val alpha: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    /** Another account: its own cookies, kv file and caches (see [ServerWebHost]). */
    override val supportsMultipleAccounts = true
    override val canReorder = true
    override val canSetVisibility = true
    override val detectsUnplayable = true
    override val authDomain = "spotify.com"
    /** The player's gateway is quick to say 429 to bursts: two searches at a time, as in the app. */
    override val searchParallelism = 2
    override val cookieSite = "open.spotify.com"
    override val cookieExample = "sp_dc=…; sp_key=…; sp_t=…"

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = session(ctx) != null
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    /** Signing in is pasting cookies in the web page. */
    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /** Checked before saving: the page must hand out a signed-in bearer, and the account page names who it is. */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val cookies = pastedCookies(pasted, DOMAINS, "sp_dc") ?: throw ProviderException(
            "These cookies hold no signed-in Spotify session (no sp_dc cookie). Copy them from a browser where you are signed in to open.spotify.com.",
        )
        val probe = Tokens(accessToken = cookies, extra = pastedUserAgent(userAgent)?.let { mapOf(ServerWebHost.UA to it) } ?: emptyMap())
        val kv = cache(ctx)
        val (userId, name) = withContext(Dispatchers.IO) {
            // A new session: what the last one left (the page, the account id, the client token) goes.
            forget(ctx, kv)
            try {
                val host = Host(ctx, kv, probe)
                host.bearer()
                SpotifyWebClient(host).profile()
            } catch (e: Exception) {
                forget(ctx, kv)
                throw if (e is BridgeException) ProviderException("$displayName: ${e.message}", e) else e
            }
        }
        ctx.tokens.set(id, probe.copy(userId = userId.orEmpty(), userName = name.orEmpty()))
    }

    override fun disconnect(ctx: Env) {
        ctx.tokens.clear(id)
        forget(ctx, cache(ctx))
    }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx) { c -> c.libraryPlaylists().map { toPlaylist(it) } }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = io(ctx) { c -> toPlaylist(c.playlistInfo(playlistId)) }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = io(ctx) { c -> c.version(playlistId) }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = io(ctx) { c ->
        when (playlistId) {
            MusicProvider.LIKED_ID -> c.likedTracks()
            MusicProvider.ALBUMS_ID -> c.libraryItems("album")
            MusicProvider.ARTISTS_ID -> c.libraryItems("artist")
            else -> c.playlistTracks(playlistId)
        }.map { toTrack(it) }
    }

    /** Reads only until the tracks turn up, from the end of the playlist where a sync adds them. */
    override suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit): List<Track> {
        if (MusicProvider.isLibrary(playlistId)) return super.findEntries(ctx, playlistId, tracks, seen)
        val ids = tracks.mapTo(HashSet()) { it.id }
        return io(ctx) { c -> c.findEntries(playlistId, ids).map { toTrack(it) } }.also(seen)
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = io(ctx) { c -> c.track(trackId)?.let { toTrack(it) } }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://open.spotify.com/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    override fun rehydrate(track: Track): Track = track.copy(uri = track.uri ?: uriOf(track))

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val kind = when (track.kind) { Track.KIND_ALBUM -> "album"; Track.KIND_ARTIST -> "artist"; Track.KIND_PODCAST -> return emptyList(); else -> null }
        val q = when (kind) {
            "artist" -> track.title
            else -> listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        }
        val r = io(ctx) { c -> c.search(q, kind).map { toTrack(it) } }
        if (r.isNotEmpty() || kind != null) return r
        // The plain title, when artist and title together found nothing (features, remixes).
        return io(ctx) { c -> c.search(Matcher.searchTitle(track.title), kind).map { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx) { c -> toPlaylist(c.createPlaylist(name, description)) }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) { io(ctx) { it.renamePlaylist(playlistId, name) } }

    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        val moves = Reorder.moves(current.map { it.id }, ordered.map { it.id })
        if (moves.isEmpty()) return
        io(ctx) { it.moveItems(playlistId, moves.map { (from, to) -> from to Reorder.insertBefore(from, to) }) }
    }

    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) { io(ctx) { it.setPublic(playlistId, public) } }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { io(ctx) { it.deletePlaylist(playlistId) } }

    /** The gap between two liked songs: a little over a second, so each gets its own second (#34). */
    @Volatile var likedSpacingMs = 1_050L
    @Volatile private var lastLikedSave = 0L
    override fun addSpacingMs(playlistId: String): Long = if (playlistId == MusicProvider.LIKED_ID) likedSpacingMs else 0

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        // Liked songs: one per call, a second apart, oldest first, or Spotify shuffles those saved in
        // the same second (issue #34). Only when the job keeps the order.
        if (playlistId == MusicProvider.LIKED_ID && MusicProvider.addsInOrder()) {
            for (t in tracks) {
                val wait = lastLikedSave + likedSpacingMs - System.currentTimeMillis()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                io(ctx) { c -> c.addToLibrary(listOf(uriOf(t))) }
                lastLikedSave = System.currentTimeMillis()
            }
            return
        }
        val uris = tracks.map { uriOf(it) }
        io(ctx) { c -> if (playlistId in LIBRARY) c.addToLibrary(uris) else c.addToPlaylist(playlistId, uris) }
    }

    /** Entries go by a lasting id (or the track's own): one read a few minutes ago names the same entry, or none. */
    override val keepsEntryIds: Boolean get() = true

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c ->
            // Library entries go by uri; playlist entries by their uid, which the client looks up when missing.
            if (playlistId in LIBRARY) c.removeFromLibrary(tracks.map { uriOf(it) })
            else c.removeFromPlaylist(playlistId, tracks.map { RemoveItem(it.id, it.itemId) })
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun session(ctx: Env): Tokens? = ctx.tokens.get(id)?.takeIf { it.accessToken.isNotBlank() }

    private fun cache(ctx: Env): KeyValues = ctx.keyValues(ServerWebHost.kvName("spotify_web", slot))

    /** Drops what the client keeps for this account's session: its cached values and its player page (process-wide, under this account's scope). */
    private fun forget(ctx: Env, kv: KeyValues) {
        kv.clear()
        SpotifyWebClient.dropPage(ServerWebHost.scope(ctx, id))
    }

    private fun uriOf(t: Track): String = t.uri ?: when (t.kind) {
        Track.KIND_ALBUM -> "spotify:album:${t.id}"
        Track.KIND_ARTIST -> "spotify:artist:${t.id}"
        Track.KIND_PODCAST -> "spotify:show:${t.id}"
        else -> "spotify:track:${t.id}"
    }

    private fun toPlaylist(p: PlaylistDto) = Playlist(p.id, p.name, p.count, ownedByMe = p.owned, description = p.description)

    /** The same fields the app reads from the Bridge's answer. */
    private fun toTrack(t: TrackDto): Track = Track(
        id = t.id,
        title = t.title,
        artists = t.artists,
        album = t.album,
        durationMs = t.durationMs,
        itemId = t.setVideoId,
        uri = t.uri ?: "spotify:track:${t.id}",
        explicit = t.explicit,
        addedAt = t.addedAt ?: 0,
        year = t.year ?: 0,
        kind = when (t.kind) { "album" -> Track.KIND_ALBUM; "artist" -> Track.KIND_ARTIST; else -> "" },
        available = t.available,
    )

    /** A blocking client call off the caller's thread, its failure worded as the app words it. */
    private suspend fun <T> io(ctx: Env, block: (SpotifyWebClient) -> T): T = withContext(Dispatchers.IO) {
        if (session(ctx) == null) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(SpotifyWebClient(Host(ctx, cache(ctx))))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * The bearer from the player page only: the page is fetched with the cookies (and cached a few
     * minutes by the client), and fetched again when its bearer is about to end or was refused.
     */
    private inner class Host(ctx: Env, kv: KeyValues, fixed: Tokens? = null) : ServerWebHost(ctx, id, kv, fixed), SpotifyWebClient.Host {
        override fun bearer(): String {
            val client = SpotifyWebClient(this)
            var page = client.page()
            if (page.accessToken != null && page.expiresAt - 90_000 < System.currentTimeMillis()) {
                SpotifyWebClient.dropPage(cacheScope)
                page = client.page()
            }
            if (page.isAnonymous) throw BridgeException(EXPIRED)
            return page.accessToken ?: throw BridgeException(NO_TOKEN)
        }

        override fun bearerRefused() = SpotifyWebClient.dropPage(cacheScope)
    }

    companion object {
        const val SERVICE = "spotify_bridge"
        private val DOMAINS = listOf("spotify.com")
        private val LIBRARY = setOf(MusicProvider.LIKED_ID, MusicProvider.ALBUMS_ID, MusicProvider.ARTISTS_ID)
        const val EXPIRED = "the cookies have expired (open.spotify.com answers as a signed-out visitor): paste fresh ones in Services, Spotify (web)"
        const val NO_TOKEN = "open.spotify.com sent no access token with its page (Spotify may have changed the page; the app's hidden browser has other ways to get one, the server has not)"
    }
}

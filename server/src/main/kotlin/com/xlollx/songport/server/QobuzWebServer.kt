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
import com.xlollx.songport.ytmbridge.QobuzWebClient
import com.xlollx.songport.ytmbridge.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Qobuz with an account, through play.qobuz.com's web player ([QobuzWebClient], compiled from
 * :bridge): Qobuz opens its API to partners only. Signed in with the player's user token
 * (X-User-Auth-Token) and app id, pasted once from a browser signed in to Qobuz. Playlists of one's
 * own and the favourite tracks, read and written; search by ISRC first.
 * Unofficial: it can stop working when the player changes.
 */
class QobuzWebServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "Qobuz (web)"
    override val familyName: String get() = "Qobuz"
    override val brandColor = 0xFF141414
    override val noteName = "provider_note_qobuz_web"
    override val alpha: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: its own cookies, kv file and caches (see [ServerWebHost]). */
    override val supportsMultipleAccounts = true
    override val canSetVisibility = true
    override val searchesByIsrc = true
    override val authDomain = "qobuz.com"
    override val searchParallelism = 2
    override val cookieSite = "play.qobuz.com"
    override val cookieExample = "curl 'https://www.qobuz.com/api.json/0.2/playlist/getUserPlaylists?…' -H 'x-app-id: …' -H 'x-user-auth-token: …'"
    override val pasteLabel = "Qobuz's session (a request copied as cURL, or its X-User-Auth-Token)"
    override val pasteHelp = """<p class="muted">In a desktop browser signed in to <code>play.qobuz.com</code>, open the developer tools (F12), then <b>Network</b>, type <code>api.json</code> in the filter and open your library. Right-click one of the requests › <b>Copy</b> › <b>Copy as cURL</b>, and paste it here: its <code>X-User-Auth-Token</code> header is the session (as good as your password, it lasts until you sign out of Qobuz in that browser), its <code>X-App-Id</code> the player's app id.</p>"""

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = ctx.tokens.get(id)?.accessToken?.isNotBlank() == true
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /** Checked before saving: the session must read the account's playlists, which also name it. */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val (token, appId) = QobuzWebClient.sessionIn(pasted) ?: throw ProviderException(
            "This holds no Qobuz session: copy a request to www.qobuz.com/api.json of play.qobuz.com as cURL (it has X-User-Auth-Token).",
        )
        val kv = cache(ctx)
        kv.clear()
        kv[QobuzWebClient.TOKEN] = token
        appId?.let { kv[QobuzWebClient.APP_ID] = it }
        val extra = pastedUserAgent(userAgent)?.let { mapOf(ServerWebHost.UA to it) } ?: emptyMap()
        val account = withContext(Dispatchers.IO) {
            try {
                QobuzWebClient(ServerWebHost(ctx, id, kv, Tokens(accessToken = "token", userName = "Qobuz", extra = extra))).probe()
            } catch (e: Exception) {
                kv.clear()
                throw if (e is BridgeException) ProviderException("$displayName: ${e.message}", e) else e
            }
        }
        ctx.tokens.set(id, Tokens(accessToken = "token", userName = account, extra = extra))
    }

    override fun disconnect(ctx: Env) {
        ctx.tokens.clear(id)
        cache(ctx).clear()
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        io(ctx) { c -> c.playlists().map { Playlist(it.id, it.name, it.count, ownedByMe = it.owned, description = it.description) } }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        io(ctx) { c -> (if (playlistId == MusicProvider.LIKED_ID) c.liked() else c.tracks(playlistId)).map { toTrack(it) } }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://play.qobuz.com/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, needsSession = false) { c -> c.search(q, track.isrcNorm).map { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, needsSession = false) { c -> c.search(Matcher.searchTitle(track.title)).map { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx) { c -> c.create(name, description).let { Playlist(it.id, it.name, 0, description = description) } }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) { io(ctx) { c -> c.rename(playlistId, name) } }

    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) { io(ctx) { c -> c.setPublic(playlistId, public) } }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { io(ctx) { c -> c.delete(playlistId) } }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c -> if (playlistId == MusicProvider.LIKED_ID) c.like(tracks.map { it.id }, on = true) else c.add(playlistId, tracks.map { it.id }) }
    }

    /** By entry where the listing gave one (Qobuz removes a playlist's entries, not tracks). */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c ->
            if (playlistId == MusicProvider.LIKED_ID) c.like(tracks.map { it.id }, on = false)
            else c.remove(playlistId, tracks.map { it.id }, tracks.map { it.itemId })
        }
    }

    private fun cache(ctx: Env): KeyValues = ctx.keyValues(ServerWebHost.kvName("qobuz_web", slot))

    private fun toTrack(t: TrackDto) = Track(
        id = t.id, title = t.title, artists = t.artists, album = t.album, durationMs = t.durationMs, isrc = t.isrc,
        itemId = t.setVideoId, explicit = t.explicit, available = t.available, year = t.year ?: 0,
    )

    private suspend fun <T> io(ctx: Env, needsSession: Boolean = true, block: (QobuzWebClient) -> T): T = withContext(Dispatchers.IO) {
        if (needsSession && !isConnected(ctx)) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(QobuzWebClient(ServerWebHost(ctx, id, cache(ctx))))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val SERVICE = "qobuz_web"
    }
}

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
import com.xlollx.songport.ytmbridge.GaanaWebClient
import com.xlollx.songport.ytmbridge.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Gaana with an account, through gaana.com's own website ([GaanaWebClient], compiled from :bridge):
 * Gaana has no interface for third-party apps. Signed in with the site's user token (`token=` in
 * the address of its calls to api.gaana.com), pasted once from a browser signed in to Gaana.
 * Playlists of one's own and the favourite songs, read and written; the search gives each song's
 * ISRC. Unofficial: it can stop working when the site changes.
 */
class GaanaWebServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "Gaana (web)"
    override val familyName: String get() = "Gaana"
    override val brandColor = 0xFFE72C30
    override val noteName = "provider_note_gaana_web"
    override val alpha: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: its own session, kv file and caches (see [ServerWebHost]). */
    override val supportsMultipleAccounts = true
    override val searchesByIsrc = true
    override val authDomain = "gaana.com"
    override val searchParallelism = 2
    override val cookieSite = "gaana.com"
    override val cookieExample = "curl 'https://api.gaana.com/user.php?type=myplaylists&subtype=myplaylists&token=…' -H 'deviceId: …'"
    override val pasteLabel = "Gaana's session (a request copied as cURL, or its token)"
    override val pasteHelp = """<p class="muted">In a desktop browser signed in to <code>gaana.com</code>, open the developer tools (F12), then <b>Network</b>, type <code>token=</code> in the filter and open your library. Right-click one of the requests to <code>api.gaana.com</code> › <b>Copy</b> › <b>Copy as cURL</b>, and paste it here: the <code>token</code> in its address is the session (as good as your password, it lasts until you sign out of Gaana in that browser). The same token is the <code>uToken</code> entry under <b>Application</b> › <b>Local storage</b>.</p>"""

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = ctx.tokens.get(id)?.accessToken?.isNotBlank() == true
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /** Checked before saving: the session must read the account's playlists. */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val seen = GaanaWebClient.sessionIn(pasted) ?: throw ProviderException(
            "This holds no Gaana session: copy a request to api.gaana.com of gaana.com as cURL (its address has token=).",
        )
        val kv = cache(ctx)
        kv.clear()
        kv[GaanaWebClient.TOKEN] = seen.token
        seen.deviceId?.let { kv[GaanaWebClient.DEVICE_ID] = it }
        seen.country?.let { kv[GaanaWebClient.COUNTRY] = it }
        val extra = pastedUserAgent(userAgent)?.let { mapOf(ServerWebHost.UA to it) } ?: emptyMap()
        val account = withContext(Dispatchers.IO) {
            try {
                GaanaWebClient(ServerWebHost(ctx, id, kv, Tokens(accessToken = "token", userName = "Gaana", extra = extra))).probe()
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
        io(ctx) { c -> c.playlists().map { Playlist(it.id, it.name, it.count) } }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        io(ctx) { c -> (if (playlistId == MusicProvider.LIKED_ID) c.liked() else c.tracks(playlistId)).map { toTrack(it) } }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://gaana.com/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(Matcher.searchTitle(track.title), track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }).joinToString(" ")
        val r = io(ctx, needsSession = false) { c -> c.search(q, track.isrcNorm).map { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, needsSession = false) { c -> c.search(Matcher.searchTitle(track.title), track.isrcNorm).map { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx) { c -> c.create(name).let { Playlist(it.id, it.name, 0) } }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) { io(ctx) { c -> c.rename(playlistId, name) } }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { io(ctx) { c -> c.delete(playlistId) } }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c -> if (playlistId == MusicProvider.LIKED_ID) c.like(tracks.map { it.id }, on = true) else c.add(playlistId, tracks.map { it.id }) }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c -> if (playlistId == MusicProvider.LIKED_ID) c.like(tracks.map { it.id }, on = false) else c.remove(playlistId, tracks.map { it.id }) }
    }

    private fun cache(ctx: Env): KeyValues = ctx.keyValues(ServerWebHost.kvName("gaana_web", slot))

    private fun toTrack(t: TrackDto) = Track(
        id = t.id, title = t.title, artists = t.artists, album = t.album, durationMs = t.durationMs, isrc = t.isrc,
        uri = t.uri, explicit = t.explicit, year = t.year ?: 0,
    )

    private suspend fun <T> io(ctx: Env, needsSession: Boolean = true, block: (GaanaWebClient) -> T): T = withContext(Dispatchers.IO) {
        if (needsSession && !isConnected(ctx)) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(GaanaWebClient(ServerWebHost(ctx, id, cache(ctx))))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val SERVICE = "gaana_web"
    }
}

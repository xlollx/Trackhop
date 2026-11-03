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
import com.xlollx.songport.ytmbridge.AnghamiWebClient
import com.xlollx.songport.ytmbridge.BridgeException
import com.xlollx.songport.ytmbridge.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Anghami with an account, through play.anghami.com's web player ([AnghamiWebClient], compiled from
 * :bridge): no public API. Signed in with the player's session id (sid), pasted once from a browser
 * signed in to Anghami. Playlists of one's own and the liked songs, read and written.
 * Unofficial: it can stop working when the player changes.
 */
class AnghamiWebServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "Anghami (web)"
    override val familyName: String get() = "Anghami"
    override val brandColor = 0xFF8D35F5
    override val noteName = "provider_note_anghami_web"
    override val alpha: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsMultipleAccounts = false
    override val authDomain = "anghami.com"
    override val searchParallelism = 2
    override val cookieSite = "play.anghami.com"
    override val cookieExample = "https://coussa.anghami.com/gateway.php?type=GETplaylists&…&fingerprint=…&sid=…"
    override val pasteLabel = "Anghami's session (a request address, or its sid)"
    override val pasteHelp = """<p class="muted">In a desktop browser signed in to <code>play.anghami.com</code>, open the developer tools (F12), then <b>Network</b>, type <code>gateway</code> in the filter and open your library. Right-click one of the <code>gateway.php</code> requests › <b>Copy</b> › <b>Copy URL</b>, and paste it here: its <code>sid</code> is the session (as good as your password, it lasts until you sign out of Anghami in that browser). <a href="https://trackhop.org/guide/docker#anghami-unofficial">How to copy it</a></p>"""

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = ctx.tokens.get(id)?.accessToken?.isNotBlank() == true
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /** Checked before saving: the session must read the account's playlists. */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val (sid, fingerprint) = AnghamiWebClient.sessionIn(pasted) ?: throw ProviderException(
            "This holds no Anghami session: copy the address of a gateway.php request of play.anghami.com (it has sid=…).",
        )
        val kv = cache(ctx)
        kv.clear()
        kv[AnghamiWebClient.SID] = sid
        fingerprint?.let { kv[AnghamiWebClient.FP] = it }
        val probe = Tokens(accessToken = "sid", userName = "Anghami",
            extra = pastedUserAgent(userAgent)?.let { mapOf(ServerWebHost.UA to it) } ?: emptyMap())
        withContext(Dispatchers.IO) {
            try {
                AnghamiWebClient(ServerWebHost(ctx, id, kv, probe)).playlists()
            } catch (e: Exception) {
                kv.clear()
                throw if (e is BridgeException) ProviderException("$displayName: ${e.message}", e) else e
            }
        }
        ctx.tokens.set(id, probe)
    }

    override fun disconnect(ctx: Env) {
        ctx.tokens.clear(id)
        cache(ctx).clear()
    }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx) { c -> c.playlists().map { Playlist(it.id, it.name, it.count) } }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        io(ctx) { c -> (if (playlistId == MusicProvider.LIKED_ID) c.liked() else c.tracks(playlistId)).map { toTrack(it) } }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://play.anghami.com/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, needsSession = false) { c -> c.search(q).map { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, needsSession = false) { c -> c.search(Matcher.searchTitle(track.title)).map { toTrack(it) } }
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

    private fun cache(ctx: Env): KeyValues = ctx.keyValues("anghami_web")

    private fun toTrack(t: TrackDto) = Track(id = t.id, title = t.title, artists = t.artists, album = t.album, durationMs = t.durationMs, year = t.year ?: 0)

    private suspend fun <T> io(ctx: Env, needsSession: Boolean = true, block: (AnghamiWebClient) -> T): T = withContext(Dispatchers.IO) {
        if (needsSession && !isConnected(ctx)) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(AnghamiWebClient(ServerWebHost(ctx, id, cache(ctx))))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val SERVICE = "anghami_web"
    }
}

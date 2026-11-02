package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.ytmbridge.BridgeException
import com.xlollx.songport.ytmbridge.LiveOneWebClient
import com.xlollx.songport.ytmbridge.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * LiveOne (formerly Slacker Radio) through liveone.com's web player ([LiveOneWebClient], compiled from
 * :bridge): no public API, no developer key. Signed in with the player's refresh token, pasted once
 * from the browser's storage; Trackhop renews the session from it as the player does.
 *
 * A free account has one list of its own, its presets: shown here as the liked songs, read whole and
 * added to. Playlists of one's own need a paid plan and are not handled yet; removing from the
 * presets is not either (the call is not known). Unofficial: it can stop working when the player changes.
 */
class LiveOneWebServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "LiveOne (web)"
    override val familyName: String get() = "LiveOne"
    override val brandColor = 0xFFE4002B
    override val noteName = "provider_note_liveone"
    override val alpha: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsMultipleAccounts = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val authDomain = "liveone.com"
    override val searchParallelism = 2
    override val cookieSite = "www.liveone.com"
    override val cookieExample = "{\"id\":\"…\",\"refresh_token\":\"eyJ…\", …} (the slackerUser entry)"
    override val pasteLabel = "LiveOne's refresh token"
    override val pasteHelp = """<p class="muted">In a desktop browser signed in to <code>www.liveone.com</code>, open the developer tools (F12), then <b>Application</b> (<b>Storage</b> in Firefox) › <b>Local storage</b> › <code>https://www.liveone.com</code>, and copy the whole value of <code>slackerUser</code> (it holds the refresh token; a <code>refresh_token</code> entry, when there is one, works too). It is as good as your password and lasts until you sign out of LiveOne in that browser. <a href="https://trackhop.org/guide/docker#liveone-unofficial">How to copy it</a></p>"""

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = ctx.tokens.get(id)?.accessToken?.isNotBlank() == true
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /** Checked before saving: the token must renew into a session that reads the presets. */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val token = LiveOneWebClient.tokenIn(pasted) ?: throw ProviderException(
            "This holds no LiveOne token: copy the whole value of slackerUser from www.liveone.com's local storage.",
        )
        LiveOneWebClient.problemWith(token)?.let { throw ProviderException("$displayName: $it. Copy the whole slackerUser entry from www.liveone.com's local storage.") }
        val account = LiveOneWebClient.accountOf(token) ?: throw ProviderException("This token names no LiveOne account: copy refresh_token again.")
        val kv = cache(ctx)
        kv.clear()
        kv[LiveOneWebClient.REFRESH] = token
        val probe = Tokens(accessToken = "refresh", userId = account, userName = "LiveOne $account",
            extra = pastedUserAgent(userAgent)?.let { mapOf(ServerWebHost.UA to it) } ?: emptyMap())
        withContext(Dispatchers.IO) {
            try {
                LiveOneWebClient(ServerWebHost(ctx, id, kv, probe)).apply { wsToken(renew = true); presets() }
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

    /** No playlists of one's own on a free account: the presets are the liked songs. */
    override suspend fun playlists(ctx: Env): List<Playlist> = emptyList()

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId != MusicProvider.LIKED_ID) throw ProviderException("$displayName: only the presets (liked songs) can be read for now")
        return io(ctx) { c -> c.presets().map { toTrack(it) } }
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.liveone.com/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    /**
     * A search that fails is counted as "not found" by the engine without a word: the first few
     * failures of a process go to the log, so a sync with many not found shows whether LiveOne refused.
     */
    override suspend fun search(ctx: Env, track: Track): List<Track> = try {
        searchOnce(ctx, track)
    } catch (e: Exception) {
        if (e !is kotlinx.coroutines.CancellationException && searchErrors.incrementAndGet() <= 5) ctx.log("liveone", "search failed for \"${track.title}\": ${e.message}")
        throw e
    }

    private suspend fun searchOnce(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, needsSession = false) { c -> c.search(q).map { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, needsSession = false) { c -> c.search(Matcher.searchTitle(track.title)).map { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        throw ProviderException("$displayName: playlists of your own need LiveOne's paid plan and are not handled yet; sync into the presets (liked songs)")

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (playlistId != MusicProvider.LIKED_ID) throw ProviderException("$displayName: only the presets (liked songs) can be written for now")
        io(ctx) { c -> c.addPresets(tracks.map { it.id }) }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isNotEmpty()) throw ProviderException("$displayName: removing from the presets is not possible yet")
    }

    private fun cache(ctx: Env): KeyValues = ctx.keyValues("liveone_web")

    private fun toTrack(t: TrackDto) = Track(id = t.id, title = t.title, artists = t.artists, album = t.album, durationMs = t.durationMs)

    private suspend fun <T> io(ctx: Env, needsSession: Boolean = true, block: (LiveOneWebClient) -> T): T = withContext(Dispatchers.IO) {
        if (needsSession && !isConnected(ctx)) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(LiveOneWebClient(ServerWebHost(ctx, id, cache(ctx))))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        private val searchErrors = java.util.concurrent.atomic.AtomicInteger()
        const val SERVICE = "liveone_web"
    }
}

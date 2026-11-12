package com.xlollx.songport.server

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.net.get
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Reorder
import com.xlollx.songport.sync.TitleParser
import com.xlollx.songport.ytmbridge.ChannelDto
import com.xlollx.songport.ytmbridge.PlaylistDto
import com.xlollx.songport.ytmbridge.RemoveItem
import com.xlollx.songport.ytmbridge.TrackDto
import com.xlollx.songport.ytmbridge.YtmClient
import com.xlollx.songport.ytmbridge.YtmCookies
import com.xlollx.songport.ytmbridge.YtmHost
import com.xlollx.songport.ytmbridge.YtmVerificationStore
import com.xlollx.songport.ytmbridge.YtmWebTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * YouTube Music on the server: the app's own web client (:ytm), signed in with cookies the user
 * pastes from a browser where they are signed in to music.youtube.com. Unofficial, like the app's
 * built-in connector, and without the app's hidden browser: every call is plain HTTP, which Google's
 * abuse detection blocks sooner, above all from a datacenter address.
 *
 * Same service id and the same mapping as the app's connector (YouTubeBridgeProvider and the
 * Bridge's methods), so a sync means the same thing on both.
 */
class YtmServerProvider(override val slot: String = "") : CookieConnector {
    override val serviceId = SERVICE
    override val displayName = "YouTube Music"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "YouTube Music"
    override val brandColor = 0xFFFF0000
    override val noteName = "provider_note_ytm"
    override val beta: Boolean get() = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val supportsRecent = true
    /** Followed podcasts, read only: following a show from here is not documented (see YtmClient.libraryPodcasts). */
    override val supportsPodcasts = true
    override fun supportsLibrary(playlistId: String?, asTarget: Boolean): Boolean =
        if (playlistId == MusicProvider.PODCASTS_ID && asTarget) false else super.supportsLibrary(playlistId, asTarget)
    /** Another account: its own cookies, kv file and caches (see [ServerWebHost]). */
    override val supportsMultipleAccounts = true
    override val canRenamePlaylists = true
    override val canDeletePlaylists = true
    override val canReorder = true
    override val canSetVisibility = true
    override val authDomain = "accounts.google.com"
    override val revokeUrl = "https://myaccount.google.com/device-activity"
    // As in the app: two searches at a time, paced by the client; more only gets the address blocked.
    override val searchParallelism = 2
    override val cookieSite = "music.youtube.com"
    override val cookieExample = "SAPISID=…; __Secure-3PAPISID=…; SID=…"

    override fun isConfigured(ctx: Env): Boolean = true
    override fun isConnected(ctx: Env): Boolean = session(ctx) != null
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    /** Signing in is pasting cookies in the web page: a form, as for the personal servers. */
    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.CREDENTIALS)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}

    /**
     * Stores the pasted cookies (a `Cookie` header, cookies.txt or a JSON export) and checks them with
     * one call. Fresh cookies also end a Google block: the user was told to pass Google's check first.
     */
    override suspend fun connect(ctx: Env, pasted: String, userAgent: String?) {
        val cookies = YtmCookies.parse(pasted) ?: throw ProviderException(
            "These cookies hold no signed-in YouTube session (no SAPISID cookie). Copy them from a browser where you are signed in to music.youtube.com.",
        )
        val ua = userAgent?.trim()?.takeIf { it.startsWith("Mozilla/") }
        YtmClient.Verification.passed(verification(ctx))
        val probe = Tokens(accessToken = cookies, extra = ua?.let { mapOf(UA to it) } ?: emptyMap())
        // Checked before saving: the account name needs a signed-in call, a dead copy fails here.
        val name = withContext(Dispatchers.IO) {
            val client = YtmClient(ServerYtmHost(ctx, fixed = probe))
            client.playlists()
            client.accountName()
        }
        ctx.tokens.set(id, probe.copy(userName = name.orEmpty()))
    }

    override fun disconnect(ctx: Env) { ctx.tokens.clear(id) }

    /** The brand account's page id this account's calls are made as; null (the default) is its own channel. */
    fun channel(ctx: Env): String? = ctx.tokens.get(id)?.extra?.get(CHANNEL)?.takeIf { it.isNotBlank() }
    /** The chosen channel's name, for the page; null when none was chosen. */
    fun channelName(ctx: Env): String? = if (channel(ctx) == null) null else ctx.tokens.get(id)?.extra?.get(CHANNEL_NAME)

    /** The channels the signed-in account can act as, the chosen one marked. */
    suspend fun channels(ctx: Env): List<ChannelDto> {
        val chosen = channel(ctx).orEmpty()
        return io(ctx) { c -> c.channels().map { it.copy(selected = it.id == chosen) } }
    }

    /**
     * Makes this account's calls as [pageId] ("" for its own channel, as before any choice), kept in
     * tokens.json under this account's id with the cookies, and names the account as that channel.
     */
    suspend fun setChannel(ctx: Env, pageId: String, name: String?) {
        val t = session(ctx) ?: throw ProviderException(ctx.text("error_not_connected", displayName))
        val extra = t.extra - CHANNEL - CHANNEL_NAME + (if (pageId.isBlank()) emptyMap() else mapOf(CHANNEL to pageId.trim(), CHANNEL_NAME to name.orEmpty()))
        ctx.tokens.set(id, t.copy(extra = extra))
        val named = runCatching { io(ctx) { it.accountName() } }.getOrNull()
        if (named != null) ctx.tokens.set(id, t.copy(extra = extra, userName = named))
    }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx) { c ->
        c.playlists().map { Playlist(it.id, it.name, it.count) }
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = io(ctx) { c ->
        val p: PlaylistDto = c.playlistInfo(playlistId)
        Playlist(playlistId, p.name, p.count, ownedByMe = false)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = io(ctx) { c ->
        when (playlistId) {
            MusicProvider.ALBUMS_ID -> c.libraryAlbums()
            MusicProvider.ARTISTS_ID -> c.librarySubscriptions()
            MusicProvider.PODCASTS_ID -> c.libraryPodcasts()
            MusicProvider.RECENT_ID -> c.history()
            else -> c.tracks(playlistId)
        }.map { toTrack(it) }
    }

    /** Reads only until the tracks turn up, page after page. */
    override suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit): List<Track> {
        if (MusicProvider.isLibrary(playlistId)) return super.findEntries(ctx, playlistId, tracks, seen)
        val ids = tracks.mapTo(HashSet()) { it.id }
        return io(ctx) { c -> c.findEntries(playlistId, ids).map { toTrack(it) } }.also(seen)
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://music.youtube.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val kind = when (track.kind) { Track.KIND_ALBUM -> "album"; Track.KIND_ARTIST -> "artist"; Track.KIND_PODCAST -> return emptyList(); else -> null }
        val q = if (kind == "artist") track.title else (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx) { c -> (if (kind != null) c.searchKind(q, kind) else c.search(q)).map { toTrack(it) } }
    }

    /** YouTube videos, for a song the music catalogue does not have. */
    override suspend fun searchWide(ctx: Env, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx) { c -> c.search(q, videos = true).map { toTrack(it) } }
    }

    /** Title and channel from YouTube's public oEmbed, as in the app: no session needed. */
    override suspend fun track(ctx: Env, trackId: String): Track? {
        val r = Http.send("GET", "https://www.youtube.com/oembed?format=json&url=" + Http.enc("https://www.youtube.com/watch?v=$trackId"))
        if (!r.ok) return null
        val j = parseJson(r.body)
        val (title, artists) = TitleParser.parseYouTube(j["title"].str ?: return null, j["author_name"].str)
        return Track(id = trackId, title = title, artists = artists)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = io(ctx) { c ->
        val p = c.createPlaylist(name, description)
        Playlist(p.id, name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) { io(ctx) { it.renamePlaylist(playlistId, name) } }

    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        // Entries move by their set id; a listing without them cannot be reordered.
        if (current.any { it.itemId.isNullOrEmpty() }) return
        val steps = Reorder.before(current.map { it.itemId!! }, ordered.map { it.itemId!! })
        if (steps.isEmpty()) return
        io(ctx) { it.moveBefore(playlistId, steps) }
    }

    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) { io(ctx) { it.setPrivacy(playlistId, public) } }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { io(ctx) { it.deletePlaylist(playlistId) } }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c ->
            when (playlistId) {
                // Albums are liked through their playlist id, which the row carried as uri when it had one.
                MusicProvider.ALBUMS_ID -> c.rateAlbums(tracks.map { it.id }, tracks.map { it.uri ?: "" }, like = true)
                MusicProvider.ARTISTS_ID -> c.subscribeArtists(tracks.map { it.id }, on = true)
                else -> c.addTracks(playlistId, tracks.map { it.id })
            }
        }
    }

    /** Entries go by a lasting id (or the track's own): one read a few minutes ago names the same entry, or none. */
    override val keepsEntryIds: Boolean get() = true

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        io(ctx) { c ->
            when (playlistId) {
                MusicProvider.ALBUMS_ID -> c.rateAlbums(tracks.map { it.id }, tracks.map { it.uri ?: "" }, like = false)
                MusicProvider.ARTISTS_ID -> c.subscribeArtists(tracks.map { it.id }, on = false)
                else -> c.removeTracks(playlistId, tracks.map { RemoveItem(it.id, it.itemId) })
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun session(ctx: Env): Tokens? = ctx.tokens.get(id)?.takeIf { it.accessToken.isNotBlank() }

    /** The same fields the app reads from the Bridge's answer. */
    private fun toTrack(t: TrackDto): Track = Track(
        id = t.id,
        title = t.title,
        artists = t.artists,
        album = t.album,
        durationMs = t.durationMs,
        itemId = t.setVideoId,
        uri = t.uri,
        kind = when (t.kind) { "album" -> Track.KIND_ALBUM; "artist" -> Track.KIND_ARTIST; "podcast" -> Track.KIND_PODCAST; else -> "" },
        year = t.year ?: 0,
    )

    /** A blocking client call off the caller's thread, its failure worded as the app words it. */
    private suspend fun <T> io(ctx: Env, block: (YtmClient) -> T): T = withContext(Dispatchers.IO) {
        if (session(ctx) == null) throw ProviderException(ctx.text("error_not_connected", displayName))
        try {
            block(YtmClient(ServerYtmHost(ctx)))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("YouTube Music: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val SERVICE = "ytm"
        /** The browser's user agent, when the user gave it with the cookies. */
        const val UA = "userAgent"
        /** The chosen brand account's page id and name, next to the cookies (none: the account's own channel). */
        const val CHANNEL = "channel"
        const val CHANNEL_NAME = "channelName"

        fun verification(ctx: Env): YtmVerificationStore = KeyValuesVerification(ctx.keyValues("ytm_verification"))
    }

    /** The server's side of the client: the pasted cookies, no browser engine, plain HTTP. */
    private inner class ServerYtmHost(private val ctx: Env, private val fixed: Tokens? = null) : YtmHost {
        private val tokens get() = fixed ?: session(ctx)
        override fun cookies(): String? = tokens?.accessToken
        override val userAgent: String get() = tokens?.extra?.get(UA) ?: YtmClient.USER_AGENT
        // None on a server (the connections hook stays null); the tests put a stub behind it.
        override val interceptor: okhttp3.Interceptor? get() = com.xlollx.songport.ytmbridge.BridgeNet.interceptor
        override val web: YtmWebTransport? = null
        override val verification: YtmVerificationStore get() = verification(ctx)
        /** This person's data folder and this account: the client's cached player page never serves another. */
        override val cacheScope: String get() = ServerWebHost.scope(ctx, id)
        override val channel: String? get() = tokens?.extra?.get(CHANNEL)?.takeIf { it.isNotBlank() }
        override val signInAgain = "paste fresh cookies in Services, YouTube Music"
        override val blockedHint =
            "Open music.youtube.com in a browser on the same network as this server, pass Google's check, then paste fresh cookies in Services, YouTube Music"
    }
}

/** Google's block in the server's data folder, under the same keys the app uses in its preferences. */
private class KeyValuesVerification(private val kv: KeyValues) : YtmVerificationStore {
    override fun until(): Long = kv["until"]?.toLongOrNull() ?: 0
    override fun seen(): Long = kv["seen"]?.toLongOrNull() ?: 0
    override fun level(): Int = kv["level"]?.toIntOrNull() ?: 0
    override fun html(): String? = kv["html"]
    override fun save(until: Long, seen: Long, level: Int, html: String) {
        kv["until"] = until.toString(); kv["seen"] = seen.toString(); kv["level"] = level.toString(); kv["html"] = html
    }
    override fun clear() = kv.clear()
}

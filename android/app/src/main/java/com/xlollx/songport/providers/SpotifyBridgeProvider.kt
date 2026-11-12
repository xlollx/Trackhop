package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.android
import com.xlollx.songport.platform.env
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Spotify through the web player's session, kept by the companion connectors ("Trackhop Bridge",
 * built into this app on GitHub and F-Droid): the user signs in on Spotify's own page, no developer
 * app and no Premium needed.
 *
 * Everything goes through the player's own backend, the way open.spotify.com does it (see
 * SpotifyWebClient in the Bridge): since December 2025 Spotify answers 429 to any first-party token
 * on the public Web API, so this route shares no code with [SpotifyProvider]. Tracks carry no ISRC
 * here, so matching falls back to title, artist and duration.
 *
 * Not an official route: Spotify's terms do not allow it and it can stop working when the player
 * changes. The Bridge shows the notice before sign-in. The official path with your own client ID
 * stays available as [SpotifyProvider].
 */
class SpotifyBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector, StartsOver {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Spotify (web)" else "Spotify (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Spotify"
    override val brandColor = 0xFF1DB954
    override val noteName = "provider_note_spotify_bridge"
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    /** An extra account of a web connector is new: said so, as the other new connectors are. */
    override val beta: Boolean get() = slot.isNotEmpty()
    override val authDomain = "spotify.com"
    override val pluginBased = true
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "bridge_needed_hint"
    /** The player's gateway is quick to say 429 to bursts: two searches at a time. */
    override val searchParallelism = 2
    /** The connectors list one Spotify or YouTube Music playlist at a time (BridgeCore's lock): see [com.xlollx.songport.sync.ServiceReads]. */
    override val readGate: String get() = BridgePlugin.BRIDGE_GATE

    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "spotify.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "spotify.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "spotify.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "spotify.disconnect") } }

    // Start over: the Bridge forgets the session and the sites' data; an older one only signs out.
    override fun startOver(ctx: Env) { runCatching { call(ctx, "spotify.reset") }.onFailure { disconnect(ctx) } }
    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "spotify.playlists") { j ->
        j.arr.mapNotNull { p -> toPlaylist(p) }
    }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? =
        withContext(Dispatchers.IO) { call(ctx, "spotify.version", playlistId).getString("version") }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        io(ctx, "spotify.playlistInfo", playlistId) { j -> toPlaylist(j) ?: Playlist(playlistId, playlistId) }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "spotify.tracks", playlistId, BridgePlugin.page(offset!!, MusicProvider.readsFresh()))
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    /** Through the Bridge's `spotify.find`, which reads only until the tracks turn up; an older Bridge reads the whole playlist. */
    override suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit): List<Track> {
        if (MusicProvider.isLibrary(playlistId)) return super.findEntries(ctx, playlistId, tracks, seen)
        return MusicProvider.entriesOrWhole(ctx, id, {
            withContext(Dispatchers.IO) {
                val b = call(ctx, "spotify.find", playlistId, Bundle().apply { putStringArray("ids", tracks.map { it.id }.distinct().toTypedArray()) })
                parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            }.also(seen)
        }) { super.findEntries(ctx, playlistId, tracks, seen) }
    }

    override suspend fun track(ctx: Env, trackId: String): Track? =
        io(ctx, "spotify.track", trackId) { j -> toTrack(j) }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://open.spotify.com/search/" + Uri.encode(query)

    /**
     * What the user plays most in [period], as Spotify's own profile page shows it ("Top tracks
     * this month"): the Bridge's `spotify.top`, read through the player's backend with the web
     * session. A ranking, no counts. An older Bridge does not know the method: that is an error,
     * and the poster or the Wrapped goes on without it.
     */
    suspend fun listening(ctx: Env, period: com.xlollx.songport.sync.Listening.Period): com.xlollx.songport.sync.Listening.Report {
        val range = com.xlollx.songport.sync.Listening.spotifyWebRange(period) ?: throw ProviderException("Spotify: no top songs for ${period.key}")
        return io(ctx, "spotify.top", range) { j ->
            com.xlollx.songport.sync.Listening.Report(
                tracks = j["tracks"].arr.mapNotNull { toTrack(it) }.map { com.xlollx.songport.sync.Listening.Play(it) },
                artists = j["artists"].arr.mapNotNull { it.str?.takeIf { n -> n.isNotBlank() } }.map { com.xlollx.songport.sync.Listening.ArtistPlays(it) },
            )
        }
    }

    override fun rehydrate(track: Track): Track = track.copy(uri = track.uri ?: uriOf(track))

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val kind = when (track.kind) { Track.KIND_ALBUM -> "album"; Track.KIND_ARTIST -> "artist"; Track.KIND_PODCAST -> return emptyList(); else -> null }
        val q = when (kind) {
            "artist" -> track.title
            else -> listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        }
        val r = io(ctx, "spotify.search", q, Bundle().apply { putString("kind", kind) }) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty() || kind != null) return r
        // The plain title, when artist and title together found nothing (features, remixes).
        return io(ctx, "spotify.search", Matcher.searchTitle(track.title), Bundle().apply { putString("kind", kind) }) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "spotify.create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            toPlaylist(j) ?: throw ProviderException("Spotify: playlist not created")
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "spotify.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override val canReorder: Boolean get() = true
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        val moves = com.xlollx.songport.sync.Reorder.moves(current.map { it.id }, ordered.map { it.id })
        if (moves.isEmpty()) return
        withContext(Dispatchers.IO) {
            call(ctx, "spotify.reorder", playlistId, Bundle().apply {
                putIntArray("from", moves.map { it.first }.toIntArray())
                putIntArray("to", moves.map { (from, to) -> com.xlollx.songport.sync.Reorder.insertBefore(from, to) }.toIntArray())
            })
        }
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        withContext(Dispatchers.IO) { call(ctx, "spotify.visibility", playlistId, Bundle().apply { putBoolean("public", public) }) }
    }

    /** A playlist's picture, uploaded and set the way the web player's "Edit details" does. An older plugin does not know the method: no cover ([BridgePlugin.setCover]). */
    override val canSetCover: Boolean get() = BridgePlugin.knows("spotify.setCover")
    override val coverIsV2: Boolean get() = true
    override suspend fun setPlaylistCover(ctx: Env, playlistId: String, jpeg: ByteArray) =
        BridgePlugin.setCover("spotify.setCover", playlistId, jpeg) { m, id, extras -> call(ctx, m, id, extras) }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "spotify.delete", playlistId) }
    }

    /** The gap between two liked songs: a little over a second, so each gets its own second (#34). */
    @Volatile var likedSpacingMs = 1_050L
    @Volatile private var lastLikedSave = 0L
    override fun addSpacingMs(playlistId: String): Long = if (playlistId == MusicProvider.LIKED_ID) likedSpacingMs else 0

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        // Liked songs are listed by the second they were saved, and Spotify shuffles those saved in the
        // same second (issue #34, as with the developer sign-in): one per call, a second apart, in the
        // order given (the engine sends the oldest first). Only when the job keeps the order.
        if (playlistId == MusicProvider.LIKED_ID && MusicProvider.addsInOrder()) {
            for (t in tracks) {
                val wait = lastLikedSave + likedSpacingMs - System.currentTimeMillis()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                withContext(Dispatchers.IO) { call(ctx, "spotify.add", playlistId, Bundle().apply { putStringArray("uris", arrayOf(uriOf(t))) }) }
                lastLikedSave = System.currentTimeMillis()
            }
            return
        }
        withContext(Dispatchers.IO) {
            tracks.chunked(100).forEach { chunk ->
                call(ctx, "spotify.add", playlistId, Bundle().apply { putStringArray("uris", chunk.map { uriOf(it) }.toTypedArray()) })
            }
        }
    }

    /** Entries go by a lasting id (or the track's own): one read a few minutes ago names the same entry, or none. */
    override val keepsEntryIds: Boolean get() = true

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            if (MusicProvider.isLibrary(playlistId)) {
                call(ctx, "spotify.remove", playlistId, Bundle().apply { putStringArray("uris", tracks.map { uriOf(it) }.toTypedArray()) })
            } else {
                // Entries are removed by their uid inside the playlist; the Bridge looks up the ones we lack.
                val items = tracks.joinToString(",", "[", "]") { t ->
                    """{"videoId":${JsonPrimitive(t.id)},"setVideoId":${t.itemId?.let { JsonPrimitive(it).toString() } ?: "null"}}"""
                }
                call(ctx, "spotify.remove", playlistId, Bundle().apply { putString("json", items) })
            }
        }
    }

    private fun uriOf(t: Track): String = t.uri ?: when (t.kind) {
        Track.KIND_ALBUM -> "spotify:album:${t.id}"
        Track.KIND_ARTIST -> "spotify:artist:${t.id}"
        Track.KIND_PODCAST -> "spotify:show:${t.id}"
        else -> "spotify:track:${t.id}"
    }

    private fun toPlaylist(j: JsonElement?): Playlist? {
        val id = j["id"].str ?: return null
        return Playlist(id, j["name"].str ?: "", j["count"]?.long?.toInt() ?: -1, ownedByMe = j["owned"].bool != false && !SpotifyMade.isSpotifyMade(id), description = j["description"].str.orEmpty())
    }

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        val kind = when (j["kind"].str) { "album" -> Track.KIND_ALBUM; "artist" -> Track.KIND_ARTIST; else -> "" }
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
            itemId = j["setVideoId"].str,
            uri = j["uri"].str ?: "spotify:track:$id",
            explicit = j["explicit"].bool,
            addedAt = j["addedAt"].long ?: 0,
            year = j["year"].long?.toInt() ?: 0,
            kind = kind,
            available = j["available"].bool,
        )
    }

    override val detectsUnplayable: Boolean get() = BridgePlugin.full

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("ytm_not_installed"))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras, slot)
        } catch (e: Exception) {
            throw ProviderException("Connector plugin: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Connector plugin: no answer")
        // A session gone for good is told once ("sign in again"); a signed-in listing that answers ends that.
        com.xlollx.songport.sync.SessionKeepAlive.noticeAnswer(ctx, id, familyName, method, b)
        b.getString("error")?.let { throw ProviderException("Spotify (web): $it") }
        return b
    }

    companion object {
        const val SERVICE = "spotify_bridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.SPOTIFY_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "spotify.status"

        /**
         * Concerts' Spotify source ([com.xlollx.songport.sync.SpotifyConcerts]): the tour dates of the
         * player's artist pages, read with the session of the first Spotify (web) account connected,
         * where the connectors are the full ones. No such account, or connectors too old to know the
         * methods: nothing is asked and nothing is said.
         */
        fun concertsReader(app: Context): com.xlollx.songport.sync.SpotifyConcerts.Reader = object : com.xlollx.songport.sync.SpotifyConcerts.Reader {
            private fun connected(): SpotifyBridgeProvider? {
                val env = app.env
                return Providers.connectors().filterIsInstance<SpotifyBridgeProvider>().firstOrNull { it.isConnected(env) }?.takeIf { BridgePlugin.full }
            }

            /** The connectors' answer; null when they do not know [method]. Their other errors throw. */
            private fun ask(p: SpotifyBridgeProvider, method: String, arg: String): JsonElement? {
                val b = BridgePlugin.call(app, method, arg, null, p.slot) ?: return null
                b.getString("error")?.let { if (it.startsWith("unknown method")) return null; throw ProviderException("Spotify (web): $it") }
                return parseJson(b.getString("json") ?: return null)
            }

            override suspend fun dates(artist: String) = withContext(Dispatchers.IO) {
                val p = connected() ?: return@withContext null
                ask(p, "spotify.concerts", artist)?.arr?.mapNotNull { concertDate(it) }
            }

            override suspend fun details(uri: String) = withContext(Dispatchers.IO) {
                val p = connected() ?: return@withContext null
                ask(p, "spotify.concert", uri)?.let { concertDate(it) }
            }
        }

        /**
         * Followed artists' alerts' extra source ([com.xlollx.songport.sync.ArtistAlerts.Feed]): the web
         * player's "What's new", the latest releases of the artists followed on Spotify, read with the
         * first Spotify (web) account connected, once per look. No such account, or connectors too old
         * to know it: null, and the alerts ask each artist's own sources only, as before.
         */
        fun whatsNewFeed(app: Context): com.xlollx.songport.sync.ArtistAlerts.Feed = com.xlollx.songport.sync.ArtistAlerts.Feed {
            withContext(Dispatchers.IO) {
                val p = Providers.connectors().filterIsInstance<SpotifyBridgeProvider>().firstOrNull { it.isConnected(app.env) }?.takeIf { BridgePlugin.full }
                    ?: return@withContext null
                val b = BridgePlugin.call(app, "spotify.whatsNew", null, null, p.slot) ?: return@withContext null
                b.getString("error")?.let { if (it.startsWith("unknown method")) return@withContext null; throw ProviderException("Spotify (web): $it") }
                parseJson(b.getString("json") ?: return@withContext null).arr.mapNotNull { feedRelease(it) }
            }
        }

        /** One release of the connectors' "What's new" answer; null for one without an album, a name or a day. */
        internal fun feedRelease(j: JsonElement): com.xlollx.songport.sync.ArtistAlerts.FeedRelease? {
            val uri = j["uri"].str?.takeIf { it.startsWith("spotify:album:") } ?: return null
            val name = j["name"].str?.takeIf { it.isNotBlank() } ?: return null
            val date = j["date"].str?.takeIf { it.length >= 7 } ?: return null
            val type = when (j["type"].str?.uppercase()) {
                "SINGLE" -> com.xlollx.songport.sync.NewReleases.Type.SINGLE
                "EP" -> com.xlollx.songport.sync.NewReleases.Type.EP
                else -> com.xlollx.songport.sync.NewReleases.Type.ALBUM
            }
            return com.xlollx.songport.sync.ArtistAlerts.FeedRelease(
                artists = j["artists"].arr.mapNotNull { it.str }, title = name, type = type, date = date,
                url = "https://open.spotify.com/album/" + uri.substringAfterLast(':'),
            )
        }

        /** The connectors' ConcertDto. */
        internal fun concertDate(j: JsonElement): com.xlollx.songport.sync.SpotifyConcerts.Date? {
            val uri = j["uri"].str ?: return null
            val start = j["start"].str ?: return null
            return com.xlollx.songport.sync.SpotifyConcerts.Date(
                uri = uri, title = j["title"].str.orEmpty(), start = start,
                city = j["city"].str.orEmpty(), venue = j["venue"].str.orEmpty(), country = j["country"].str.orEmpty(),
            )
        }
    }
}

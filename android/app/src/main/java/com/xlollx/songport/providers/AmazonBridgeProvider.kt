package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.android
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Amazon Music through the companion app "Trackhop Bridge", experimental.
 *
 * Amazon has no open API and its official one is reserved to approved partners, so the Bridge uses
 * the web player's interface, as it does for YouTube Music: the library's playlists (read, create,
 * add, remove, delete), search, and the saved albums.
 */
class AmazonBridgeProvider(override val slot: String = "") : MusicProvider {
    // Renaming and deleting were mapped from the desktop player's own calls; an older plugin lacks them.
    // The GraphQL player (October 2026) has no rename or visibility call we have seen yet.
    override val canRenamePlaylists: Boolean get() = false
    override val canDeletePlaylists: Boolean get() = BridgePlugin.full
    override val serviceId = SERVICE
    override val displayName = "Amazon Music"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web_only"
    override val brandColor = 0xFF1AB0D6
    override val noteName = "provider_note_amazon"
    override val supportsMultipleAccounts = false
    override val authDomain = "amazon.com"
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "amazon_not_installed_hint"
    override val searchParallelism = 1
    /**
     * Saved albums (the albums the user "liked" in the player), from the Bridge app that knows them
     * ([savedAlbums]). Followed artists are not offered: the player's traffic shows following and
     * unfollowing, but no listing of them yet.
     */
    override val supportsAlbums: Boolean get() = savedAlbums(BridgePlugin.builtIn, BridgePlugin.reportedVersion)
    override val pluginBased = true

    /** "Configured" means a Bridge new enough to know Amazon Music is installed. */
    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "amazon.status").containsKey("connected") }.getOrDefault(false)

    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "amazon.status").getBoolean("connected", false) }.getOrDefault(false)

    override fun accountName(ctx: Env): String? =
        runCatching { call(ctx, "amazon.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }

    override fun disconnect(ctx: Env) { runCatching { call(ctx, "amazon.disconnect") } }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "amazon.playlists") { j ->
        j.arr.mapNotNull { p -> Playlist(p["id"].str ?: return@mapNotNull null, p["name"].str ?: "", p["count"]?.long?.toInt() ?: -1) }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        io(ctx, "amazon.tracks", playlistId) { j -> j.arr.mapNotNull { toTrack(it) } }

    /** The regional site the account signed in to (music.amazon.it...), where its links then work. */
    private fun domain(ctx: Env): String =
        runCatching { call(ctx, "amazon.status").getString("domain") }.getOrNull()?.takeIf { it.startsWith("music.amazon.") } ?: "music.amazon.com"

    override fun trackUrl(ctx: Env, trackId: String): String = "https://${domain(ctx)}/tracks/$trackId"

    override fun webSearchUrl(ctx: Env, query: String): String {
        val domain = domain(ctx)
        // A slash in a title ("n/A") would read as a path separator and land on "page not found".
        val q = query.replace(Regex("""[/\\]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        return "https://$domain/search/" + android.net.Uri.encode(q)
    }

    /**
     * Songs, as the player's search lists them; an album among albums (the player's albums search).
     * The target is not known here: an album row is taken for the saved albums, where it goes when
     * there are some ([searchesAlbums]). A sync says the target through [searchFor].
     */
    override suspend fun search(ctx: Env, track: Track): List<Track> =
        searchFor(ctx, track, MusicProvider.ALBUMS_ID.takeIf { track.kind == Track.KIND_ALBUM })

    /**
     * An album is looked for among albums only on its way to the saved albums: a playlist takes songs
     * only (an album id there would fail), so saved albums synced into an Amazon playlist are searched
     * as songs, as before there were saved albums here. A Bridge app older than the albums search
     * answers it with songs, which are left out: nothing found rather than a wrong proposal.
     */
    override suspend fun searchFor(ctx: Env, track: Track, playlistId: String?): List<Track> {
        if (searchesAlbums(track, supportsAlbums, playlistId)) {
            val q = (track.artists.take(1).map { Matcher.searchArtist(it) } + Matcher.searchTitle(track.title)).joinToString(" ")
            return io(ctx, "amazon.search", q, Bundle().apply { putString("kind", "album") }) { j ->
                j.arr.mapNotNull { toTrack(it) }.filter { it.kind == Track.KIND_ALBUM }
            }
        }
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx, "amazon.search", q) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "amazon.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override val canSetVisibility: Boolean get() = false
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        withContext(Dispatchers.IO) { call(ctx, "amazon.visibility", playlistId, Bundle().apply { putBoolean("public", public) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "amazon.delete", playlistId) }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "amazon.create", null, Bundle().apply { putString("name", name) }) { j ->
            Playlist(j["id"].str ?: throw ProviderException("Amazon Music: playlist not created"), name, 0)
        }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        addTracksReportingPresent(ctx, playlistId, tracks)
    }

    /**
     * The tracks Amazon refused as already in the playlist (DUPLICATE_TRACK), from the Bridge's
     * "duplicates": present, not added. A Bridge app older than this answer reports none.
     */
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        if (tracks.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            val present = HashSet<String>()
            // One web call per track: keep the batches small so a failure is reported precisely.
            tracks.chunked(25).forEach { chunk ->
                val b = call(ctx, "amazon.add", playlistId, Bundle().apply {
                    putStringArray("ids", chunk.map { it.id }.toTypedArray())
                    putStringArray("titles", chunk.map { it.title }.toTypedArray())
                })
                b.getStringArray("duplicates")?.let { present += it }
            }
            present
        }
    }

    /** Entries Amazon describes only in part come by id, without a title (see AmazonClient.playlistTracks). */
    override val listsUntitledEntries: Boolean get() = true

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        removeTracksReportingKept(ctx, playlistId, tracks)
    }

    /**
     * The tracks the playlist still lists after Amazon answered their removal, from the Bridge's
     * "kept" (it reads the playlist again): not removed. A Bridge app older than this answer reports none.
     */
    override suspend fun removeTracksReportingKept(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        if (tracks.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            val b = call(ctx, "amazon.remove", playlistId, Bundle().apply {
                putStringArray("ids", tracks.map { it.id }.toTypedArray())
                putStringArray("entryIds", tracks.map { it.itemId ?: "" }.toTypedArray())
            })
            b.getStringArray("kept")?.toSet() ?: emptySet()
        }
    }

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
            itemId = j["setVideoId"].str,
            kind = if (j["kind"].str == "album") Track.KIND_ALBUM else "",
            year = j["year"].long?.toInt() ?: 0,
        )
    }

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("ytm_not_installed"))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras)
        } catch (e: Exception) {
            throw ProviderException("Connector plugin: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Connector plugin: no answer")
        b.getString("error")?.let { throw ProviderException("Amazon Music: $it") }
        return b
    }

    companion object {
        /**
         * Whether [track] is looked for with the albums search: an album on its way to the saved
         * albums ([targetId]), where there are some. Into a playlist it is searched as songs.
         */
        fun searchesAlbums(track: Track, supportsAlbums: Boolean, targetId: String?): Boolean =
            supportsAlbums && track.kind == Track.KIND_ALBUM && targetId == MusicProvider.ALBUMS_ID

        /**
         * Saved albums: built in, or a Bridge app that reports [BridgePlugin.AMAZON_ALBUMS_VERSION]
         * (1.11.0) or more. An older one would read `__albums__` as a playlist id.
         */
        fun savedAlbums(builtIn: Boolean, version: Int): Boolean = builtIn || version >= BridgePlugin.AMAZON_ALBUMS_VERSION

        const val SERVICE = "amazon"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.AMAZON_LOGIN"
    }
}

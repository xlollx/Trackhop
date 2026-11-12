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
 * add, remove, delete), search, the saved albums and the followed artists.
 */
class AmazonBridgeProvider(override val slot: String = "") : MusicProvider {
    // Renaming and deleting were mapped from the desktop player's own calls; an older plugin lacks them.
    // Renaming on the GraphQL player (updatePlaylistDetails, 11/2026) comes with the Bridge app of the
    // saved albums ([libraryKinds]); an older one renames on the skill API only, so none is asked of it.
    override val canRenamePlaylists: Boolean get() = libraryKinds(BridgePlugin.builtIn, BridgePlugin.reportedVersion)
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
     * Saved albums (the albums the user "liked" in the player) and followed artists, from the Bridge
     * app that knows them ([libraryKinds]).
     */
    override val supportsAlbums: Boolean get() = libraryKinds(BridgePlugin.builtIn, BridgePlugin.reportedVersion)
    override val supportsArtists: Boolean get() = libraryKinds(BridgePlugin.builtIn, BridgePlugin.reportedVersion)
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
    } + listOfNotNull(purchased(ctx))

    /**
     * The purchased music, after the playlists: read only (not the user's own to write to), and only
     * where the Bridge app reads it for this account and something was bought ([purchasedOffered]).
     */
    private suspend fun purchased(ctx: Env): Playlist? {
        // Offered from Trackhop 2.0 ([com.xlollx.songport.Features]); a sync that reads it goes on reading it.
        if (!com.xlollx.songport.Features.v2) return null
        if (!libraryKinds(BridgePlugin.builtIn, BridgePlugin.reportedVersion)) return null
        val count = withContext(Dispatchers.IO) {
            purchasedOffered(purchasedCheck) {
                runCatching { call(ctx, "amazon.purchased").takeIf { it.containsKey("count") }?.getInt("count") }.getOrNull()
            }
        }
        return if (count > 0) Playlist(PURCHASED_ID, ctx.text("amazon_purchased"), ownedByMe = false) else null
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
     * there are some ([searchedKind]), an artist row for the followed artists. A sync says the target
     * through [searchFor].
     */
    override suspend fun search(ctx: Env, track: Track): List<Track> =
        searchFor(ctx, track, when (track.kind) { Track.KIND_ALBUM -> MusicProvider.ALBUMS_ID; Track.KIND_ARTIST -> MusicProvider.ARTISTS_ID; else -> null })

    /**
     * An album is looked for among albums only on its way to the saved albums, an artist among artists
     * only on its way to the followed artists: a playlist takes songs only (an album or artist id
     * there would fail), so such rows synced into an Amazon playlist are searched as songs, as before
     * there were saved albums and followed artists here. A Bridge app older than these searches
     * answers them with songs, which are left out: nothing found rather than a wrong proposal.
     */
    override suspend fun searchFor(ctx: Env, track: Track, playlistId: String?): List<Track> {
        when (searchedKind(track, supportsAlbums, playlistId)) {
            Track.KIND_ALBUM -> {
                val q = (track.artists.take(1).map { Matcher.searchArtist(it) } + Matcher.searchTitle(track.title)).joinToString(" ")
                return io(ctx, "amazon.search", q, Bundle().apply { putString("kind", "album") }) { j ->
                    j.arr.mapNotNull { toTrack(it) }.filter { it.kind == Track.KIND_ALBUM }
                }
            }
            Track.KIND_ARTIST -> return io(ctx, "amazon.search", track.title.trim(), Bundle().apply { putString("kind", "artist") }) { j ->
                j.arr.mapNotNull { toTrack(it) }.filter { it.kind == Track.KIND_ARTIST }
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
            kind = when (j["kind"].str) { "album" -> Track.KIND_ALBUM; "artist" -> Track.KIND_ARTIST; else -> "" },
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
        // A session gone for good is told once ("sign in again"); a signed-in listing that answers ends that.
        com.xlollx.songport.sync.SessionKeepAlive.noticeAnswer(ctx, id, familyName, method, b)
        b.getString("error")?.let { throw ProviderException("Amazon Music: $it") }
        return b
    }

    companion object {
        /**
         * What [track] is looked for as on its way to [targetId]: an album in the saved albums, an
         * artist in the followed artists (where there are some, [library]); null: songs.
         */
        fun searchedKind(track: Track, library: Boolean, targetId: String?): String? = when {
            !library -> null
            track.kind == Track.KIND_ALBUM && targetId == MusicProvider.ALBUMS_ID -> Track.KIND_ALBUM
            track.kind == Track.KIND_ARTIST && targetId == MusicProvider.ARTISTS_ID -> Track.KIND_ARTIST
            else -> null
        }

        /**
         * Saved albums and followed artists: built in, or a Bridge app that reports [BridgePlugin.AMAZON_LIBRARY_VERSION]
         * (1.11.0) or more. An older one would read `__albums__` or `__artists__` as a playlist id.
         */
        fun libraryKinds(builtIn: Boolean, version: Int): Boolean = builtIn || version >= BridgePlugin.AMAZON_LIBRARY_VERSION

        /** The purchased music (the Bridge's own id for it): listed like a playlist, read only. */
        const val PURCHASED_ID = "__purchased__"

        /** The last look at the purchased music: when, and the tracks on its first page (-1: not readable). */
        @Volatile private var purchasedCheck: Pair<Long, Int>? = null

        /**
         * The tracks on the purchased music's first page, from [last] while it is fresh (six hours, one
         * when it could not be read), else from [ask] (null: not readable, an older Bridge app or the
         * skill API). Above 0: offered.
         */
        internal fun purchasedOffered(last: Pair<Long, Int>?, now: Long = System.currentTimeMillis(), ask: () -> Int?): Int {
            if (last != null && now - last.first < if (last.second >= 0) 6 * 3_600_000L else 3_600_000L) return last.second
            val count = ask() ?: -1
            purchasedCheck = now to count
            return count
        }

        const val SERVICE = "amazon"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.AMAZON_LOGIN"
    }
}

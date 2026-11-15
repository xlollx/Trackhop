package com.xlollx.songport.providers

import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Gaana with an account, through gaana.com's web player, built into the GitHub and F-Droid builds
 * only (never Google Play): Gaana has no interface for third-party apps. The user signs in on
 * Gaana's own page inside the app; the page's session is kept encrypted by the web connectors
 * (GaanaWebClient).
 *
 * Playlists of one's own and the favourite songs (as liked songs), read and written: playlists
 * created, renamed and deleted, songs added and removed. Search through the site's own, which gives each song's ISRC:
 * a result with the source's ISRC is the match. Song ids are those of Gaana's public links
 * ([GaanaProvider]), so matches serve both. Same service id as the server's connector, so a sync
 * means the same on both.
 *
 * Not an official route: Gaana's terms do not allow it and it can stop working when the site
 * changes. The sign-in screen shows the notice first.
 */
class GaanaBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector, StartsOver {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Gaana (web)" else "Gaana (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Gaana"
    override val brandColor = BRAND
    override val noteName = "provider_note_gaana_web"
    override val alpha = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    override val searchesByIsrc = true
    override val authDomain = "gaana.com"
    override val pluginBased = true
    override val searchParallelism = 2
    /** The connectors list one Gaana playlist at a time. */
    override val readGate: String get() = SERVICE

    /** Built in (GitHub, F-Droid), or through a connector plugin that knows it (Google Play). */
    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "gaana.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "gaana.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Env): String? = runCatching { call(ctx, "gaana.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Env) = BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the sign-in screen keeps the session itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "gaana.disconnect") } }

    // Start over: the Bridge forgets the session and the sites' data; an older one only signs out.
    override fun startOver(ctx: Env) { runCatching { call(ctx, "gaana.reset") }.onFailure { disconnect(ctx) } }

    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "gaana.playlists") { j ->
        j.arr.mapNotNull { p -> p["id"].str?.let { Playlist(it, p["name"].str ?: "", p["count"]?.long?.toInt() ?: -1) } }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val id = if (playlistId == MusicProvider.LIKED_ID) LIKED else playlistId
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "gaana.tracks", id, BridgePlugin.page(offset!!, MusicProvider.readsFresh()))
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://gaana.com/search/" + Uri.encode(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind.isNotEmpty()) return emptyList()
        val isrc = Bundle().apply { putString("isrc", track.isrcNorm) }
        val q = listOfNotNull(Matcher.searchTitle(track.title), track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }).joinToString(" ")
        val r = io(ctx, "gaana.search", q, isrc) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty()) return r
        return io(ctx, "gaana.search", Matcher.searchTitle(track.title), isrc) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "gaana.create", null, Bundle().apply { putString("name", name) }) { j ->
            j["id"].str?.let { Playlist(it, name, 0) } ?: throw ProviderException("Gaana: playlist not created")
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "gaana.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "gaana.delete", playlistId) }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = write(ctx, "gaana.add", playlistId, tracks)

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = write(ctx, "gaana.remove", playlistId, tracks)

    /** In chunks, so a long list shows its progress in the log. */
    private suspend fun write(ctx: Env, method: String, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val id = if (playlistId == MusicProvider.LIKED_ID) LIKED else playlistId
        withContext(Dispatchers.IO) {
            tracks.chunked(50).forEach { chunk ->
                call(ctx, method, id, Bundle().apply { putStringArray("ids", chunk.map { it.id }.toTypedArray()) })
            }
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
            isrc = j["isrc"].str,
            uri = j["uri"].str,
            explicit = j["explicit"].bool,
            year = j["year"].long?.toInt() ?: 0,
        )
    }

    private suspend fun <T> io(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.text("error_not_connected", displayName))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras, slot)
        } catch (e: Exception) {
            throw ProviderException("$displayName: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("$displayName: no answer")
        // A session gone for good is told once ("sign in again"); a signed-in listing that answers ends that.
        com.xlollx.songport.sync.SessionKeepAlive.noticeAnswer(ctx, id, familyName, method, b)
        b.getString("error")?.let { throw ProviderException("$displayName: $it") }
        return b
    }

    companion object {
        const val SERVICE = "gaana_web"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.GAANA_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "gaana.status"
        /** Gaana's red. */
        const val BRAND = 0xFFE72C30
        /** The liked songs as the connectors name them. */
        private const val LIKED = "__liked__"
    }
}

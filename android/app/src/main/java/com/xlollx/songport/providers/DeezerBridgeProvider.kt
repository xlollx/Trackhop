package com.xlollx.songport.providers

import com.xlollx.songport.providers.ConnectorLog.logged
import android.content.Intent
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
import com.xlollx.songport.platform.android
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Deezer through the web player's session, kept by the companion connectors ("Trackhop Bridge",
 * built into this app on GitHub and F-Droid): the user signs in on deezer.com's own page. Deezer no
 * longer registers new developer apps, so for most people this is the only way in; the official
 * route ([DeezerProvider]) stays for those who have an app id.
 *
 * Playlists and favourite tracks go through the web player's gateway (see DeezerWebClient in the
 * Bridge); search goes through Deezer's public catalogue, by ISRC when the track has one. Deezer's
 * track ids are the same on both routes, so matches found through one serve the other.
 *
 * Not an official route: Deezer's terms do not allow it and it can stop working when the player
 * changes. The Bridge shows the notice before sign-in.
 */
class DeezerBridgeProvider(override val slot: String = "") : MusicProvider, WebAccounts.Connector, StartsOver {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Deezer (web)" else "Deezer (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteName: String? get() = "route_note_web"
    override val familyName: String get() = "Deezer"
    override val brandColor = 0xFFA238FF
    override val noteName = "provider_note_deezer_bridge"
    override val alpha = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Another account: where the WebView keeps profiles apart and the connectors take a slot (see [WebAccounts]). */
    override val supportsMultipleAccounts: Boolean get() = WebAccounts.allowed(STATUS)
    override val statusMethod: String get() = STATUS
    override val authDomain = "deezer.com"
    override val pluginBased = true
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredName: String? = "bridge_needed_hint"
    /** The gateway and the public API both dislike bursts. */
    override val searchParallelism = 2
    /** The connectors list one Deezer playlist at a time. */
    override val readGate: String get() = SERVICE

    override fun isConfigured(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "deezer.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Env): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "deezer.status").getBoolean("connected", false) }.getOrDefault(false)
    /** The account's name; on a Deezer Family account moved to another profile, that profile's, said as such (#82). */
    override fun accountName(ctx: Env): String? = runCatching {
        val b = call(ctx, "deezer.status")
        b.getString("profileName")?.let { ctx.text("deezer_profile_as", it) } ?: b.getString("account")
    }.getOrNull()

    /** A profile of a Deezer Family account: [holder] the account holder's own, [selected] the one the calls are made as. */
    class Profile(val id: String, val name: String, val picture: String?, val holder: Boolean, val selected: Boolean)

    /**
     * What the connectors know of the account's Family profiles, read from their status: whether they
     * offer the choice at all (an older Bridge does not), whether the account is a Family one (null:
     * not known yet) and whether a profile was chosen. Cheap: no call to Deezer.
     */
    class FamilyState(val supported: Boolean, val family: Boolean?, val chosen: Boolean)

    fun familyState(ctx: Env): FamilyState = runCatching {
        if (!BridgePlugin.installed(ctx)) return@runCatching null
        val b = call(ctx, "deezer.status")
        FamilyState(b.containsKey("profiles"), if (b.containsKey("family")) b.getBoolean("family") else null, b.getString("profile") != null)
    }.getOrNull() ?: FamilyState(false, null, false)

    /** The profiles the account can act as, the chosen one marked; empty when it is not a Family account. Blocking: off the main thread. */
    fun profiles(ctx: Env): List<Profile> = parseJson(call(ctx, "deezer.profiles").getString("json") ?: "[]").arr.mapNotNull { j ->
        Profile(j["id"].str ?: return@mapNotNull null, j["name"].str ?: return@mapNotNull null, j["picture"].str, j["holder"].bool == true, j["selected"].bool == true)
    }

    /** Makes the account's calls as [profile]; Deezer refusing it keeps the profile before and throws. Blocking: off the main thread. */
    fun setProfile(ctx: Env, profile: Profile) {
        call(ctx, "deezer.profile", profile.id)
    }

    override fun startAuth(ctx: Env) {
        if (BridgePlugin.packageName(ctx) == null) {
            installUrl?.let { ctx.android.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION, slot)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { /* the connector completes the login itself */ }
    override fun disconnect(ctx: Env) { runCatching { call(ctx, "deezer.disconnect") } }

    // Start over: the Bridge forgets the session and the sites' data; an older one only signs out.
    override fun startOver(ctx: Env) { runCatching { call(ctx, "deezer.reset") }.onFailure { disconnect(ctx) } }
    override suspend fun playlists(ctx: Env): List<Playlist> = io(ctx, "deezer.playlists") { j -> j.arr.mapNotNull { toPlaylist(it) }.logged(ctx, this, "playlists", j.arr.size) }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        io(ctx, "deezer.playlistInfo", playlistId) { j -> toPlaylist(j) ?: Playlist(playlistId, playlistId) }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? =
        withContext(Dispatchers.IO) { call(ctx, "deezer.version", playlistId).getString("version") }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "deezer.tracks", playlistId, BridgePlugin.page(offset!!, MusicProvider.readsFresh()))
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = io(ctx, "deezer.track", trackId) { j -> toTrack(j) }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.deezer.com/search/" + Uri.encode(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Only tracks: the web route does not handle saved albums or followed artists.
        if (track.kind.isNotEmpty()) return emptyList()
        val q = listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }, Matcher.searchTitle(track.title)).joinToString(" ")
        val r = io(ctx, "deezer.search", q, Bundle().apply { putString("isrc", track.isrcNorm) }) { j -> j.arr.mapNotNull { toTrack(it) } }
        if (r.isNotEmpty()) return r
        // The plain title, when artist and title together found nothing (features, remixes).
        return io(ctx, "deezer.search", Matcher.searchTitle(track.title)) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist =
        io(ctx, "deezer.create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            toPlaylist(j) ?: throw ProviderException("Deezer: playlist not created")
        }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "deezer.rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "deezer.delete", playlistId) }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        addTracksReportingPresent(ctx, playlistId, tracks)
    }

    /**
     * Favourites Deezer stopped taking part way (it limits the requests; the Bridge waits and tries
     * again a few times first) end as [com.xlollx.songport.model.AddsLimited]: those not added, and
     * every block after them, are left for the next run, never asked now. An older Bridge answers
     * such a run with an error, which the sync reads as a limit by its words.
     */
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        if (tracks.isEmpty()) return emptySet()
        withContext(Dispatchers.IO) {
            val chunks = tracks.chunked(100)
            for ((i, chunk) in chunks.withIndex()) {
                val b = call(ctx, "deezer.add", playlistId, Bundle().apply {
                    putStringArray("ids", chunk.map { it.id }.toTypedArray())
                    putBoolean("partial", true)
                })
                val left = b.getStringArray("notAdded")
                if (!left.isNullOrEmpty()) {
                    val rest = chunks.drop(i + 1).flatten().map { it.id }
                    throw com.xlollx.songport.model.AddsLimited(left.toSet() + rest, "$displayName: " + (b.getString("notAddedWhy") ?: "not added"))
                }
            }
        }
        return emptySet()
    }

    /** Entries go by a lasting id (or the track's own): one read a few minutes ago names the same entry, or none. */
    override val keepsEntryIds: Boolean get() = true

    /** By track id, like the official route: every copy of a track leaves the playlist. */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            tracks.chunked(100).forEach { chunk ->
                call(ctx, "deezer.remove", playlistId, Bundle().apply { putStringArray("ids", chunk.map { it.id }.toTypedArray()) })
            }
        }
    }

    private fun toPlaylist(j: JsonElement?): Playlist? {
        val id = j["id"].str ?: return null
        return Playlist(id, j["name"].str ?: "", j["count"]?.long?.toInt() ?: -1, ownedByMe = j["owned"].bool != false, description = j["description"].str.orEmpty())
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
            explicit = j["explicit"].bool,
            addedAt = j["addedAt"].long ?: 0,
            year = j["year"].long?.toInt() ?: 0,
            available = j["available"].bool,
        )
    }

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
        b.getString("error")?.let { throw ProviderException("$displayName: $it") }
        return b
    }

    companion object {
        const val SERVICE = "deezer_bridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.DEEZER_LOGIN"
        /** The status call of this service, which says whether the connectors take extra accounts. */
        const val STATUS = "deezer.status"
    }
}

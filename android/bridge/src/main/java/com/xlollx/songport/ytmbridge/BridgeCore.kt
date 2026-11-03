package com.xlollx.songport.ytmbridge

import android.content.Context
import android.os.Bundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The connectors' methods, the same the Trackhop Bridge app answers through its ContentProvider
 * (`status`, `playlists`, `tracks`, `search`, `add`, `amazon.*`, `spotify.*`, `apple.*`, `deezer.*`,
 * `soundcloud.*`...), called by
 * Trackhop in-process. No caller check is needed: nothing outside this app can reach it.
 * Callers block, so this is never called on the main thread.
 */
object BridgeCore {
    /** Reported as "version" by the status calls: the built-in connectors have every feature. */
    const val VERSION = 1000
    const val PAGE = 400
    /** Trackhop's ids for the library objects, the same on every connector. */
    const val ALBUMS = "__albums__"
    const val ARTISTS = "__artists__"
    const val RECENT = "__recent__"

    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            val client = YtmClient(ctx)
            when (method) {
                "status" -> Bundle().apply {
                    putBoolean("connected", Session.isConnected(ctx))
                    putString("account", Session.account(ctx))
                    putInt("version", VERSION)
                }
                "disconnect" -> { Session.clear(ctx); Bundle() }
                "playlists" -> ok(json.encodeToString(client.playlists()))
                "playlistInfo" -> ok(json.encodeToString(client.playlistInfo(arg ?: return err("missing playlist id"))))
                "tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedTracks(client, id)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                "search" -> {
                    val kind = extras?.getString("kind")
                    ok(json.encodeToString(if (kind == "album" || kind == "artist") client.searchKind(arg ?: "", kind) else client.search(arg ?: "", extras?.getBoolean("videos") == true)))
                }
                "stats" -> Bundle().apply { putString("stats", YtmClient.Stats.summary()) }
                "create" -> ok(json.encodeToString(client.createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    // Liked, but said by YouTube to go to Liked videos: Trackhop counts them as present, not added.
                    val elsewhere = when (id) {
                        ALBUMS -> { client.rateAlbums(ids, extras?.getStringArray("uris")?.toList() ?: emptyList(), like = true); emptySet() }
                        ARTISTS -> { client.subscribeArtists(ids, on = true); emptySet() }
                        else -> client.addTracks(id, ids)
                    }
                    invalidate(id)
                    Bundle().apply { if (elsewhere.isNotEmpty()) putStringArray("present", elsewhere.toTypedArray()) }
                }
                "rename" -> { client.renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "delete" -> { client.deletePlaylist(arg ?: return err("missing playlist id")); invalidate(arg); Bundle() }
                "visibility" -> { client.setPrivacy(arg ?: return err("missing playlist id"), extras?.getBoolean("public") ?: false); Bundle() }
                "reorder" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val items = extras?.getStringArray("items") ?: emptyArray()
                    val before = extras?.getStringArray("before") ?: emptyArray()
                    client.moveBefore(pid, items.indices.map { items[it] to before[it] })
                    invalidate(pid)
                    Bundle()
                }
                "remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    when (id) {
                        ALBUMS -> client.rateAlbums(extras?.getStringArray("ids")?.toList() ?: emptyList(), extras?.getStringArray("uris")?.toList() ?: emptyList(), like = false)
                        ARTISTS -> client.subscribeArtists(extras?.getStringArray("ids")?.toList() ?: emptyList(), on = false)
                        else -> client.removeTracks(id, json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]"))
                    }
                    invalidate(id)
                    Bundle()
                }
                // ---- Amazon Music (experimental): only what the protocol mapping covers so far.
                "amazon.status" -> Bundle().apply {
                    putBoolean("connected", AmazonSession.isConnected(ctx))
                    putString("account", AmazonSession.account(ctx))
                    putString("domain", AmazonSession.domain(ctx))
                    putInt("version", VERSION)
                }
                "amazon.disconnect" -> { AmazonSession.clear(ctx); Bundle() }
                "amazon.search" -> ok(json.encodeToString(AmazonClient(ctx).searchTracks(arg ?: "")))
                "amazon.tracks" -> ok(json.encodeToString(AmazonClient(ctx).playlistTracks(arg ?: return err("missing playlist id"))))
                "amazon.playlists" -> ok(json.encodeToString(AmazonClient(ctx).libraryPlaylists()))
                "amazon.create" -> ok(json.encodeToString(AmazonClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist")))
                "amazon.add" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val c = AmazonClient(ctx)
                    // Tracks Amazon refused as already in the playlist: Trackhop counts them as present, not added.
                    val duplicates = ids.filterNot { id -> c.addTrack(pid, id).also { Thread.sleep(150) } }
                    Bundle().apply { putStringArray("duplicates", duplicates.toTypedArray()) }
                }
                "amazon.rename" -> err("Amazon Music: renaming is not available in the current web player")
                "amazon.delete" -> { AmazonClient(ctx).removePlaylist(arg ?: return err("missing playlist id")); Bundle() }
                "amazon.visibility" -> err("Amazon Music: visibility cannot be changed from the current web player")
                "amazon.remove" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val entries = extras?.getStringArray("entryIds") ?: emptyArray()
                    // By cursor, highest index first, then checked against the playlist: the entries
                    // Amazon still lists go back as "kept", and Trackhop counts them as not removed.
                    val kept = AmazonClient(ctx).removeEntries(pid, ids.toList(), entries.toList())
                    Bundle().apply { putStringArray("kept", kept.toTypedArray()) }
                }
                // ---- Spotify web session: Trackhop gets a short-lived Web API token, never the cookies.
                "spotify.status" -> Bundle().apply {
                    putBoolean("connected", SpotifyBridge.session.isConnected(ctx))
                    putString("account", SpotifyBridge.session.account(ctx))
                    putString("userId", SpotifyBridge.session.get(ctx, "userId"))
                    putInt("version", VERSION)
                }
                "spotify.disconnect" -> { SpotifyBridge.session.clear(ctx); Bundle() }
                "spotify.playlists" -> ok(json.encodeToString(SpotifyWebClient(ctx).libraryPlaylists()))
                "spotify.version" -> Bundle().apply { SpotifyWebClient(ctx).version(arg ?: return err("missing playlist id"))?.let { putString("version", it) } }
                "spotify.playlistInfo" -> ok(json.encodeToString(SpotifyWebClient(ctx).playlistInfo(arg ?: return err("missing playlist id"))))
                "spotify.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedSpotifyTracks(ctx, id)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                "spotify.search" -> ok(json.encodeToString(SpotifyWebClient(ctx).search(arg ?: "", extras?.getString("kind"))))
                "spotify.track" -> ok(json.encodeToString(SpotifyWebClient(ctx).track(arg ?: return err("missing track id"))))
                "spotify.create" -> ok(json.encodeToString(SpotifyWebClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "spotify.add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val uris = extras?.getStringArray("uris")?.toList() ?: emptyList()
                    val c = SpotifyWebClient(ctx)
                    when (id) {
                        "__liked__", "__albums__", "__artists__" -> c.addToLibrary(uris)
                        else -> c.addToPlaylist(id, uris)
                    }
                    invalidateSpotify(id)
                    Bundle()
                }
                "spotify.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val c = SpotifyWebClient(ctx)
                    when (id) {
                        "__liked__", "__albums__", "__artists__" -> c.removeFromLibrary(extras?.getStringArray("uris")?.toList() ?: emptyList())
                        else -> c.removeFromPlaylist(id, json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]"))
                    }
                    invalidateSpotify(id)
                    Bundle()
                }
                "spotify.rename" -> { SpotifyWebClient(ctx).renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "spotify.delete" -> { SpotifyWebClient(ctx).deletePlaylist(arg ?: return err("missing playlist id")); invalidateSpotify(arg); Bundle() }
                "spotify.visibility" -> { SpotifyWebClient(ctx).setPublic(arg ?: return err("missing playlist id"), extras?.getBoolean("public") ?: false); Bundle() }
                "spotify.reorder" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val from = extras?.getIntArray("from") ?: IntArray(0)
                    val to = extras?.getIntArray("to") ?: IntArray(0)
                    SpotifyWebClient(ctx).moveItems(pid, from.indices.map { from[it] to to[it] })
                    invalidateSpotify(pid)
                    Bundle()
                }
                "spotify.token" -> {
                    val t = SpotifyBridge.token(ctx)
                    if (SpotifyBridge.session.get(ctx, "userId") == null) runCatching {
                        val (id, name) = SpotifyBridge.accountInfo(ctx)
                        id?.let { SpotifyBridge.session.put(ctx, "userId", it) }
                        SpotifyBridge.session.save(ctx, SpotifyBridge.session.cookies(ctx) ?: "", name)
                    }
                    Bundle().apply {
                        putString("token", t.value)
                        putLong("expiresAt", t.expiresAt)
                        putString("userId", SpotifyBridge.session.get(ctx, "userId"))
                        putString("account", SpotifyBridge.session.account(ctx))
                    }
                }
                // ---- Apple Music web session: Apple's web developer token plus the user's music user token.
                "apple.status" -> Bundle().apply {
                    putBoolean("connected", AppleBridge.session.isConnected(ctx))
                    putString("account", AppleBridge.session.account(ctx))
                    putInt("version", VERSION)
                }
                "apple.disconnect" -> { AppleBridge.session.clear(ctx); Bundle() }
                "apple.tokens" -> {
                    val user = AppleBridge.userToken(AppleBridge.session.cookies(ctx)) ?: return err("not connected")
                    Bundle().apply {
                        putString("developerToken", AppleBridge.developerToken(ctx))
                        putString("userToken", user)
                        putString("storefront", runCatching { AppleBridge.storefront(ctx) }.getOrNull())
                    }
                }
                // ---- Deezer web session: the web player's gateway; catalogue search through the public API.
                "deezer.status" -> Bundle().apply {
                    putBoolean("connected", DeezerBridge.session.isConnected(ctx))
                    putString("account", DeezerBridge.session.account(ctx))
                    putInt("version", VERSION)
                }
                "deezer.disconnect" -> { DeezerBridge.session.clear(ctx); DeezerWebClient.forget(); deezerCache.clear(); Bundle() }
                "deezer.playlists" -> ok(json.encodeToString(DeezerWebClient(ctx).libraryPlaylists()))
                "deezer.playlistInfo" -> ok(json.encodeToString(DeezerWebClient(ctx).playlistInfo(arg ?: return err("missing playlist id"))))
                "deezer.version" -> Bundle().apply { DeezerWebClient(ctx).version(arg ?: return err("missing playlist id"))?.let { putString("version", it) } }
                "deezer.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val all = deezerCache.get(id) { val c = DeezerWebClient(ctx); if (id == LIKED) c.likedTracks() else c.playlistTracks(id) }
                    paged(json.encodeToString(all.drop(extras?.getInt("offset") ?: 0).take(PAGE)), all.size, extras?.getInt("offset") ?: 0)
                }
                "deezer.search" -> ok(json.encodeToString(DeezerWebClient(ctx).search(arg ?: "", extras?.getString("isrc"))))
                "deezer.track" -> ok(json.encodeToString(DeezerWebClient(ctx).track(arg ?: return err("missing track id"))))
                "deezer.create" -> ok(json.encodeToString(DeezerWebClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "deezer.add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    if (id == LIKED) DeezerWebClient(ctx).like(ids, on = true) else DeezerWebClient(ctx).addToPlaylist(id, ids)
                    deezerCache.invalidate(id)
                    Bundle()
                }
                "deezer.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    if (id == LIKED) DeezerWebClient(ctx).like(ids, on = false) else DeezerWebClient(ctx).removeFromPlaylist(id, ids)
                    deezerCache.invalidate(id)
                    Bundle()
                }
                "deezer.rename" -> { DeezerWebClient(ctx).renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "deezer.delete" -> { DeezerWebClient(ctx).deletePlaylist(arg ?: return err("missing playlist id")); deezerCache.invalidate(arg); Bundle() }
                // ---- Anghami web session: the web player's gateway with its sid; the liked songs as "__liked__".
                "anghami.status" -> Bundle().apply {
                    putBoolean("connected", AnghamiBridge.session.isConnected(ctx))
                    putString("account", AnghamiBridge.session.account(ctx))
                    putInt("version", VERSION)
                }
                "anghami.disconnect" -> { AnghamiBridge.session.clear(ctx); anghamiCache.clear(); Bundle() }
                "anghami.playlists" -> ok(json.encodeToString(AnghamiWebClient(ctx).playlists()))
                "anghami.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val all = anghamiCache.get(id) { val c = AnghamiWebClient(ctx); if (id == LIKED) c.liked() else c.tracks(id) }
                    paged(json.encodeToString(all.drop(extras?.getInt("offset") ?: 0).take(PAGE)), all.size, extras?.getInt("offset") ?: 0)
                }
                "anghami.search" -> ok(json.encodeToString(AnghamiWebClient(ctx).search(arg ?: "")))
                "anghami.create" -> ok(json.encodeToString(AnghamiWebClient(ctx).create(extras?.getString("name") ?: "Playlist")))
                "anghami.add", "anghami.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    val on = method == "anghami.add"
                    val c = AnghamiWebClient(ctx)
                    when { id == LIKED -> c.like(ids, on); on -> c.add(id, ids); else -> c.remove(id, ids) }
                    anghamiCache.invalidate(id)
                    Bundle()
                }
                "anghami.rename" -> { AnghamiWebClient(ctx).rename(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "anghami.delete" -> { AnghamiWebClient(ctx).delete(arg ?: return err("missing playlist id")); anghamiCache.invalidate(arg); Bundle() }
                // ---- LiveOne web session: the presets (as liked songs), read whole and added to; search without an account.
                "liveone.status" -> Bundle().apply {
                    putBoolean("connected", LiveOneBridge.session.isConnected(ctx))
                    putString("account", LiveOneBridge.session.account(ctx))
                    putInt("version", VERSION)
                }
                "liveone.disconnect" -> { LiveOneBridge.session.clear(ctx); Bundle() }
                "liveone.presets" -> ok(json.encodeToString(LiveOneWebClient(ctx).presets()))
                "liveone.search" -> ok(json.encodeToString(LiveOneWebClient(ctx).search(arg ?: "")))
                "liveone.add" -> { LiveOneWebClient(ctx).addPresets(extras?.getStringArray("ids")?.toList() ?: emptyList()); Bundle() }
                // ---- SoundCloud web session: api-v2 with the web app's client id and the account's OAuth token.
                // Tracks travel as SoundCloud's own track objects, trimmed: Trackhop parses them like its public links.
                "soundcloud.status" -> Bundle().apply {
                    putBoolean("connected", SoundCloudBridge.session.isConnected(ctx))
                    putString("account", SoundCloudBridge.session.account(ctx))
                    putInt("version", VERSION)
                }
                "soundcloud.disconnect" -> { SoundCloudBridge.session.clear(ctx); SoundCloudBridge.forgetWebStorage(); SoundCloudRenewal.forget(); soundCloudCache.clear(); Bundle() }
                "soundcloud.playlists" -> ok(json.encodeToString(SoundCloudWebClient(ctx).libraryPlaylists()))
                "soundcloud.playlistInfo" -> ok(json.encodeToString(SoundCloudWebClient(ctx).playlistInfo(arg ?: return err("missing playlist id"))))
                "soundcloud.version" -> Bundle().apply { SoundCloudWebClient(ctx).version(arg ?: return err("missing playlist id"))?.let { putString("version", it) } }
                "soundcloud.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = soundCloudCache.get(id) { SoundCloudWebClient(ctx).tracks(id) }
                    paged(kotlinx.serialization.json.JsonArray(all.drop(offset).take(PAGE)).toString(), all.size, offset)
                }
                "soundcloud.search" -> ok(kotlinx.serialization.json.JsonArray(SoundCloudWebClient(ctx).search(arg ?: "")).toString())
                "soundcloud.create" -> ok(json.encodeToString(SoundCloudWebClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "soundcloud.add" -> {
                    val id = arg ?: return err("missing playlist id")
                    SoundCloudWebClient(ctx).add(id, extras?.getStringArray("ids")?.toList() ?: emptyList())
                    soundCloudCache.invalidate(id)
                    Bundle()
                }
                "soundcloud.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    SoundCloudWebClient(ctx).remove(id, extras?.getStringArray("ids")?.toList() ?: emptyList())
                    soundCloudCache.invalidate(id)
                    Bundle()
                }
                "soundcloud.reorder" -> {
                    val id = arg ?: return err("missing playlist id")
                    SoundCloudWebClient(ctx).reorder(id, extras?.getStringArray("ids")?.toList() ?: emptyList())
                    soundCloudCache.invalidate(id)
                    Bundle()
                }
                "soundcloud.rename" -> { SoundCloudWebClient(ctx).rename(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "soundcloud.visibility" -> { SoundCloudWebClient(ctx).setPublic(arg ?: return err("missing playlist id"), extras?.getBoolean("public") ?: false); Bundle() }
                "soundcloud.delete" -> { SoundCloudWebClient(ctx).delete(arg ?: return err("missing playlist id")); soundCloudCache.invalidate(arg); Bundle() }
                else -> err("unknown method $method")
            }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    // A playlist is fetched once and served in pages (Binder transactions are capped at ~1 MB).
    private var lastTracks: Triple<String, Long, List<TrackDto>>? = null

    @Synchronized
    private fun cachedTracks(client: YtmClient, id: String): List<TrackDto> {
        val now = System.currentTimeMillis()
        lastTracks?.let { (pid, at, list) -> if (pid == id && now - at < 120_000) return list }
        val list = when (id) { ALBUMS -> client.libraryAlbums(); ARTISTS -> client.librarySubscriptions(); RECENT -> client.history(); else -> client.tracks(id) }
        lastTracks = Triple(id, now, list)
        return list
    }

    @Synchronized
    private fun invalidate(id: String) { if (lastTracks?.first == id) lastTracks = null }

    private var lastSpotify: Triple<String, Long, List<TrackDto>>? = null

    @Synchronized
    private fun cachedSpotifyTracks(ctx: Context, id: String): List<TrackDto> {
        val now = System.currentTimeMillis()
        lastSpotify?.let { (pid, at, list) -> if (pid == id && now - at < 120_000) return list }
        val c = SpotifyWebClient(ctx)
        val list = when (id) {
            "__liked__" -> c.likedTracks()
            "__albums__" -> c.libraryItems("album")
            "__artists__" -> c.libraryItems("artist")
            else -> c.playlistTracks(id)
        }
        lastSpotify = Triple(id, now, list)
        return list
    }

    @Synchronized
    private fun invalidateSpotify(id: String) { if (lastSpotify?.first == id) lastSpotify = null }

    private const val LIKED = "__liked__"

    /** One listing at a time, fetched once and served in pages for two minutes; a write to it drops it. */
    private class ListCache<T> {
        private var last: Triple<String, Long, List<T>>? = null
        @Synchronized fun get(id: String, load: () -> List<T>): List<T> {
            val now = System.currentTimeMillis()
            last?.let { (pid, at, list) -> if (pid == id && now - at < 120_000) return list }
            return load().also { last = Triple(id, now, it) }
        }
        @Synchronized fun invalidate(id: String) { if (last?.first == id) last = null }
        @Synchronized fun clear() { last = null }
    }

    private val deezerCache = ListCache<TrackDto>()
    private val anghamiCache = ListCache<TrackDto>()
    private val soundCloudCache = ListCache<kotlinx.serialization.json.JsonObject>()

    /** One page of a listing, as the `tracks` methods answer: the page, the total, and where the next one starts. */
    private fun paged(pageJson: String, total: Int, offset: Int) = Bundle().apply {
        putString("json", pageJson)
        putInt("total", total)
        if (offset + PAGE < total) putInt("next", offset + PAGE)
    }

    private fun ok(jsonText: String) = Bundle().apply { putString("json", jsonText) }
    private fun err(message: String) = Bundle().apply { putString("error", message) }
}

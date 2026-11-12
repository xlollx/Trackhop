package com.xlollx.songport.ytmbridge

import android.content.Context
import android.os.Bundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The connectors' methods, the same the Trackhop Bridge app answers through its ContentProvider
 * (`status`, `playlists`, `tracks`, `search`, `add`, `amazon.*`, `spotify.*`, `apple.*`, `deezer.*`,
 * `soundcloud.*`, `anghami.*`, `qobuz.*`, `ticketone.read`, `vivaticket.read`...), called by
 * Trackhop in-process. No caller check is needed: nothing outside this app can reach it.
 * Callers block, so this is never called on the main thread.
 */
object BridgeCore {
    /** Reported as "version" by the status calls: the built-in connectors have every feature. */
    const val VERSION = 1000
    const val PAGE = 400
    /** The extra of the `setCover` methods: the playlist's picture, a JPEG. */
    const val COVER = "jpeg"
    /** Trackhop's ids for the library objects, the same on every connector. */
    const val ALBUMS = "__albums__"
    const val ARTISTS = "__artists__"
    const val RECENT = "__recent__"
    /** Followed podcasts: read only on YouTube Music. */
    const val PODCASTS = "__podcasts__"
    /** Saved albums and followed artists on the older skill API: none mapped. */
    private const val AMAZON_NO_LIBRARY = "Amazon Music: saved albums and followed artists are not available for this account's player (the older skill API)"

    /**
     * Saves or removes Amazon Music albums ([ALBUMS]), follows or unfollows artists ([ARTISTS]), one at
     * a time as the player does; returns the ids Amazon did not confirm.
     */
    private fun amazonLibrary(ctx: Context, kind: String, ids: Array<String>, on: Boolean): List<String> =
        AmazonRoute.run(ctx, { c ->
            ids.filterNot { id ->
                (if (kind == ALBUMS) c.setAlbumSaved(id, saved = on) else c.setArtistFollowed(id, follow = on)).also { Thread.sleep(150) }
            }
        }, { throw BridgeException(AMAZON_NO_LIBRARY) })

    /** The services whose extra accounts ([Slots]) this answers; the others serve one account. */
    private val SLOT_SERVICES = setOf("", "spotify", "apple", "deezer", "anghami", "qobuz", "liveone")
    const val ONE_ACCOUNT = "this connector serves one account"

    /** The namespace of [method]: "" for YouTube Music, "spotify" for `spotify.status`... */
    private fun serviceOf(method: String): String = method.substringBefore('.', "")

    /** Whether [method]'s service takes a slot on this device (reported as `slots` by its status call). */
    fun slotsFor(method: String): Boolean = serviceOf(method) in SLOT_SERVICES && Slots.supported()

    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle {
        BridgeNet.attach(ctx.filesDir)
        // The account the call is about: absent (every Trackhop before 1.15) means the primary one.
        val slot = Slots.of(extras)
        if (slot.isNotEmpty() && serviceOf(method) !in SLOT_SERVICES) return err(ONE_ACCOUNT)
        // "Start over": the disconnect, then the sites' stored data too (see [WebReset]).
        if (method.endsWith(".reset")) return runCatching { reset(ctx, method, arg, extras, slot) }.getOrElse { err(it.message ?: it.javaClass.simpleName) }
        // A listing asked for fresh (the poster made again from scratch): the one kept for it goes first.
        if (arg != null && extras?.getBoolean("fresh") == true && extras.getInt("offset") == 0) dropListing(method, arg, slot)
        return try {
            val client = YtmClient(ctx, slot)
            val ytm = Session.forSlot(slot)
            when (method) {
                "status" -> Bundle().apply {
                    putBoolean("connected", ytm.isConnected(ctx))
                    putString("account", ytm.account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                    // The channel the calls are made as (a brand account), and that this Bridge offers the choice.
                    putBoolean("channels", true)
                    ytm.channel(ctx)?.let { putString("channel", it); putString("channelName", ytm.channelName(ctx)) }
                }
                "disconnect" -> { ytm.clear(ctx); Bundle() }
                // Trackhop's background keep-alive: the player's page run once so Google rotates the
                // session's cookies (see [YtmRenewal]), and whether the session is gone. An older
                // connector answers "unknown method", which Trackhop ignores.
                "keepAlive" -> {
                    val outcome = YtmRenewal.keepAlive(ctx, slot)
                    Bundle().apply {
                        putBoolean("renewed", outcome == YtmRenewal.Outcome.RENEWED)
                        putBoolean("connected", ytm.isConnected(ctx))
                        putBoolean("expired", outcome == YtmRenewal.Outcome.SIGNED_OUT)
                    }
                }
                // The channels the account can act as; the chosen one is marked by the stored choice, not by Google's switcher.
                "channels" -> {
                    val chosen = ytm.channel(ctx).orEmpty()
                    ok(json.encodeToString(client.channels().map { it.copy(selected = it.id == chosen) }))
                }
                // Keeps the chosen channel (arg: its page id, "" or absent for the account's own) and names the account as it.
                "channel" -> {
                    val pageId = arg?.trim().orEmpty()
                    val name = extras?.getString("name")
                    ytm.setChannel(ctx, pageId, name)
                    val cookies = ytm.cookies(ctx)
                    if (cookies != null) runCatching { YtmClient(ctx, slot).accountName() }.getOrNull()?.let { ytm.save(ctx, cookies, it) }
                    Bundle().apply { putString("channel", ytm.channel(ctx)); putString("channelName", ytm.channelName(ctx)); putString("account", ytm.account(ctx)) }
                }
                "playlists" -> ok(json.encodeToString(client.playlists()))
                "playlistInfo" -> ok(json.encodeToString(client.playlistInfo(arg ?: return err("missing playlist id"))))
                "tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedTracks(client, id, slot)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                // The entries holding some tracks (a removal needs their set ids): from the listing read a
                // moment ago when there is one, otherwise read only until they have all turned up.
                "find" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toSet() ?: emptySet()
                    val listed = freshTracks(id, slot) ?: if (id.startsWith("__")) cachedTracks(client, id, slot) else null
                    ok(json.encodeToString(listed?.filter { it.id in ids } ?: client.findEntries(id, ids)))
                }
                "search" -> {
                    val kind = extras?.getString("kind")
                    ok(json.encodeToString(if (kind == "album" || kind == "artist") client.searchKind(arg ?: "", kind) else client.search(arg ?: "", extras?.getBoolean("videos") == true)))
                }
                "stats" -> Bundle().apply { putString("stats", YtmClient.Stats.summary()) }
                // The song in an audio file (a TikTok "original sound"): title, artist, album, ISRC; "{}" when none.
                "audio.recognize" -> ok(AudioRecognition.recognize(ctx, arg ?: return err("missing address"))?.let {
                    jsonObj("title" to it.title, "artist" to it.artist, "album" to it.album, "isrc" to it.isrc).toString()
                } ?: "{}")
                // A page of TicketOne read in a browser engine out of sight, for Concerts (see [TicketOnePage]).
                "ticketone.read" -> ok(TicketOnePage.read(ctx, arg ?: return err("missing address")))
                // An answer of Vivaticket's gateway, for Concerts in the builds that do not ask it themselves (see [VivaticketRead]).
                "vivaticket.read" -> VivaticketRead.read(arg ?: return err("missing address"), extras)
                "log" -> Bundle().apply { putString("log", BridgeNet.recent(extras?.getInt("lines")?.takeIf { it > 0 } ?: 300)) }
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
                    invalidate(id, slot)
                    Bundle().apply { if (elsewhere.isNotEmpty()) putStringArray("present", elsewhere.toTypedArray()) }
                }
                "rename" -> { client.renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "delete" -> { client.deletePlaylist(arg ?: return err("missing playlist id")); invalidate(arg, slot); Bundle() }
                "visibility" -> { client.setPrivacy(arg ?: return err("missing playlist id"), extras?.getBoolean("public") ?: false); Bundle() }
                // A playlist's picture (extra `jpeg`: square, at least 360 px). An older connector answers "unknown method": no cover.
                "setCover" -> { client.setCover(arg ?: return err("missing playlist id"), extras?.getByteArray(COVER) ?: return err("missing image")); Bundle() }
                "reorder" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val items = extras?.getStringArray("items") ?: emptyArray()
                    val before = extras?.getStringArray("before") ?: emptyArray()
                    client.moveBefore(pid, items.indices.map { items[it] to before[it] })
                    invalidate(pid, slot)
                    Bundle()
                }
                "remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    when (id) {
                        ALBUMS -> client.rateAlbums(extras?.getStringArray("ids")?.toList() ?: emptyList(), extras?.getStringArray("uris")?.toList() ?: emptyList(), like = false)
                        ARTISTS -> client.subscribeArtists(extras?.getStringArray("ids")?.toList() ?: emptyList(), on = false)
                        else -> client.removeTracks(id, json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]"))
                    }
                    invalidate(id, slot)
                    Bundle()
                }
                // ---- Amazon Music (experimental): only what the protocol mapping covers so far.
                "amazon.status" -> Bundle().apply {
                    putBoolean("connected", AmazonSession.isConnected(ctx))
                    putString("account", AmazonSession.account(ctx))
                    putString("domain", AmazonSession.domain(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "amazon.disconnect" -> { AmazonSession.clear(ctx); Bundle() }
                // GraphQL for most accounts; the older skill API for the players still on it (issue #35).
                // Albums (kind "album", for saved albums) from the albums search, artists (kind "artist", for
                // followed artists) from the artists search; the skill API has neither mapped: nothing rather
                // than songs that share their name.
                "amazon.search" -> ok(json.encodeToString(
                    when (extras?.getString("kind")) {
                        "album" -> AmazonRoute.run(ctx, { it.searchAlbums(arg ?: "") }, { emptyList() })
                        "artist" -> AmazonRoute.run(ctx, { it.searchArtists(arg ?: "") }, { emptyList() })
                        else -> AmazonRoute.run(ctx, { it.searchTracks(arg ?: "") }, { it.searchTracks(arg ?: "") })
                    },
                ))
                "amazon.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    // ALBUMS, ARTISTS: the saved albums, the followed artists (GraphQL only; Trackhop asks from Bridge 1.11.0 on).
                    if (id == ALBUMS) return ok(json.encodeToString(AmazonRoute.run(ctx, { it.savedAlbums() }, { throw BridgeException(AMAZON_NO_LIBRARY) })))
                    if (id == ARTISTS) return ok(json.encodeToString(AmazonRoute.run(ctx, { it.followedArtists() }, { throw BridgeException(AMAZON_NO_LIBRARY) })))
                    ok(json.encodeToString(AmazonRoute.run(ctx, { it.playlistTracks(id) }, { it.playlistTracks(id) })))
                }
                "amazon.playlists" -> ok(json.encodeToString(AmazonRoute.run(ctx, { it.libraryPlaylists() }, { it.libraryPlaylists() })))
                "amazon.create" -> {
                    val name = extras?.getString("name") ?: "Playlist"
                    ok(json.encodeToString(AmazonRoute.run(ctx, { it.createPlaylist(name) }, { it.createPlaylist(name) })))
                }
                "amazon.add" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val titles = extras?.getStringArray("titles") ?: emptyArray()
                    // ALBUMS, ARTISTS: each album saved ("liked"), each artist followed; one Amazon does not
                    // confirm fails the call, so it is never counted as added.
                    if (pid == ALBUMS || pid == ARTISTS) {
                        val refused = amazonLibrary(ctx, pid, ids, on = true)
                        val what = if (pid == ALBUMS) "save ${refused.size} of ${ids.size} albums" else "follow ${refused.size} of ${ids.size} artists"
                        if (refused.isNotEmpty()) return err("Amazon Music did not $what (${refused.take(5).joinToString()})")
                        return Bundle().apply { putStringArray("duplicates", emptyArray()) }
                    }
                    // Tracks Amazon refused as already in the playlist: Trackhop counts them as present, not added.
                    val duplicates = AmazonRoute.run(ctx,
                        { c -> ids.filterNot { id -> c.addTrack(pid, id).also { Thread.sleep(150) } } },
                        { c -> ids.filterIndexed { i, id -> !c.addTrack(pid, id, titles.getOrNull(i) ?: "").also { Thread.sleep(150) } } },
                    )
                    Bundle().apply { putStringArray("duplicates", duplicates.toTypedArray()) }
                }
                "amazon.rename" -> {
                    val id = arg ?: return err("missing playlist id")
                    if (AmazonSession.api(ctx) != AmazonRoute.SKILL) return err("Amazon Music: renaming is not available in the current web player")
                    AmazonSkillClient(ctx).renamePlaylist(id, extras?.getString("name") ?: ""); Bundle()
                }
                "amazon.delete" -> {
                    val id = arg ?: return err("missing playlist id")
                    AmazonRoute.run(ctx, { it.removePlaylist(id) }, { it.removePlaylist(id) }); Bundle()
                }
                "amazon.visibility" -> {
                    val id = arg ?: return err("missing playlist id")
                    if (AmazonSession.api(ctx) != AmazonRoute.SKILL) return err("Amazon Music: visibility cannot be changed from the current web player")
                    AmazonSkillClient(ctx).setVisibility(id, extras?.getBoolean("public") ?: false); Bundle()
                }
                "amazon.remove" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val entries = extras?.getStringArray("entryIds") ?: emptyArray()
                    // ALBUMS, ARTISTS: each album un-liked, each artist unfollowed; the ones Amazon did not confirm go back as "kept".
                    if (pid == ALBUMS || pid == ARTISTS) {
                        return Bundle().apply { putStringArray("kept", amazonLibrary(ctx, pid, ids, on = false).toTypedArray()) }
                    }
                    // Checked against the playlist afterwards: the entries Amazon still lists go back
                    // as "kept", and Trackhop counts them as not removed. GraphQL removes by cursor,
                    // highest index first; the skill API by trackEntryId.
                    val kept = AmazonRoute.run(ctx,
                        { it.removeEntries(pid, ids.toList(), entries.toList()) },
                        { it.removeEntries(pid, ids.toList(), entries.toList()) },
                    )
                    Bundle().apply { putStringArray("kept", kept.toTypedArray()) }
                }
                // ---- Spotify web session: Trackhop gets a short-lived Web API token, never the cookies.
                "spotify.status" -> Bundle().apply {
                    putBoolean("connected", SpotifyBridge.session(slot).isConnected(ctx))
                    putString("account", SpotifyBridge.session(slot).account(ctx))
                    putString("userId", SpotifyBridge.session(slot).get(ctx, "userId"))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "spotify.disconnect" -> { SpotifyBridge.session(slot).clear(ctx); SpotifyBridge.invalidateToken(slot); Bundle() }
                "spotify.playlists" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).libraryPlaylists()))
                "spotify.version" -> Bundle().apply { SpotifyWebClient(ctx, slot).version(arg ?: return err("missing playlist id"))?.let { putString("version", it) } }
                "spotify.playlistInfo" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).playlistInfo(arg ?: return err("missing playlist id"))))
                "spotify.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedSpotifyTracks(ctx, id, slot)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                "spotify.find" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toSet() ?: emptySet()
                    val listed = freshSpotify(id, slot) ?: if (id.startsWith("__")) cachedSpotifyTracks(ctx, id, slot) else null
                    ok(json.encodeToString(listed?.filter { it.id in ids } ?: SpotifyWebClient(ctx, slot).findEntries(id, ids)))
                }
                "spotify.search" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).search(arg ?: "", extras?.getString("kind"))))
                "spotify.track" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).track(arg ?: return err("missing track id"))))
                // What the user plays most, as the player's profile shows it; arg: SHORT_TERM, MID_TERM or LONG_TERM.
                "spotify.top" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).top(arg ?: "SHORT_TERM")))
                // An artist's upcoming concerts on Spotify (arg: the artist's name; [] when the search finds no artist of
                // that name), and one concert's venue and country (arg: its uri). Concerts' Spotify source, unofficial.
                "spotify.concerts" -> {
                    val c = SpotifyWebClient(ctx, slot)
                    ok(json.encodeToString(c.artistUri(arg ?: return err("missing artist"))?.let { c.concerts(it) } ?: emptyList()))
                }
                // What the artist's page says of their own site (arg: the artist's name; null when the search finds no
                // artist of that name): its links and its merch shelf. Merch's and Concerts' look for the official site.
                "spotify.artist" -> {
                    val c = SpotifyWebClient(ctx, slot)
                    ok(json.encodeToString(c.artistUri(arg ?: return err("missing artist"))?.let { c.artistSites(it) }))
                }
                "spotify.concert" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).concert(arg ?: return err("missing concert"))))
                "spotify.create" -> ok(json.encodeToString(SpotifyWebClient(ctx, slot).createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "spotify.add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val uris = extras?.getStringArray("uris")?.toList() ?: emptyList()
                    val c = SpotifyWebClient(ctx, slot)
                    when (id) {
                        "__liked__", "__albums__", "__artists__" -> c.addToLibrary(uris)
                        else -> c.addToPlaylist(id, uris)
                    }
                    invalidateSpotify(id, slot)
                    Bundle()
                }
                "spotify.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val c = SpotifyWebClient(ctx, slot)
                    when (id) {
                        "__liked__", "__albums__", "__artists__" -> c.removeFromLibrary(extras?.getStringArray("uris")?.toList() ?: emptyList())
                        else -> c.removeFromPlaylist(id, json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]"))
                    }
                    invalidateSpotify(id, slot)
                    Bundle()
                }
                "spotify.rename" -> { SpotifyWebClient(ctx, slot).renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "spotify.delete" -> { SpotifyWebClient(ctx, slot).deletePlaylist(arg ?: return err("missing playlist id")); invalidateSpotify(arg, slot); Bundle() }
                "spotify.setCover" -> { SpotifyWebClient(ctx, slot).setCover(arg ?: return err("missing playlist id"), extras?.getByteArray(COVER) ?: return err("missing image")); Bundle() }
                "spotify.visibility" -> { SpotifyWebClient(ctx, slot).setPublic(arg ?: return err("missing playlist id"), extras?.getBoolean("public") ?: false); Bundle() }
                "spotify.reorder" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val from = extras?.getIntArray("from") ?: IntArray(0)
                    val to = extras?.getIntArray("to") ?: IntArray(0)
                    SpotifyWebClient(ctx, slot).moveItems(pid, from.indices.map { from[it] to to[it] })
                    invalidateSpotify(pid, slot)
                    Bundle()
                }
                "spotify.token" -> {
                    val t = SpotifyBridge.token(ctx, slot)
                    val sess = SpotifyBridge.session(slot)
                    if (sess.get(ctx, "userId") == null) runCatching {
                        val (id, name) = SpotifyBridge.accountInfo(ctx, slot)
                        id?.let { sess.put(ctx, "userId", it) }
                        sess.save(ctx, sess.cookies(ctx) ?: "", name)
                    }
                    Bundle().apply {
                        putString("token", t.value)
                        putLong("expiresAt", t.expiresAt)
                        putString("userId", sess.get(ctx, "userId"))
                        putString("account", sess.account(ctx))
                    }
                }
                // ---- Apple Music web session: Apple's web developer token plus the user's music user token.
                "apple.status" -> Bundle().apply {
                    putBoolean("connected", AppleBridge.session(slot).isConnected(ctx))
                    putString("account", AppleBridge.session(slot).account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "apple.disconnect" -> { AppleBridge.session(slot).clear(ctx); Bundle() }
                "apple.tokens" -> {
                    val user = AppleBridge.userToken(AppleBridge.session(slot).cookies(ctx)) ?: return err("not connected")
                    Bundle().apply {
                        putString("developerToken", AppleBridge.developerToken(ctx, slot))
                        putString("userToken", user)
                        putString("storefront", runCatching { AppleBridge.storefront(ctx, slot) }.getOrNull())
                    }
                }
                // ---- Deezer web session: the web player's gateway; catalogue search through the public API.
                "deezer.status" -> Bundle().apply {
                    putBoolean("connected", DeezerBridge.session(slot).isConnected(ctx))
                    putString("account", DeezerBridge.session(slot).account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "deezer.disconnect" -> { DeezerBridge.session(slot).clear(ctx); DeezerWebClient.forget(slot); deezerCache.clear(); Bundle() }
                "deezer.playlists" -> ok(json.encodeToString(DeezerWebClient(ctx, slot).libraryPlaylists()))
                "deezer.playlistInfo" -> ok(json.encodeToString(DeezerWebClient(ctx, slot).playlistInfo(arg ?: return err("missing playlist id"))))
                "deezer.version" -> Bundle().apply { DeezerWebClient(ctx, slot).version(arg ?: return err("missing playlist id"))?.let { putString("version", it) } }
                "deezer.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val all = deezerCache.get(ck(slot, id)) { val c = DeezerWebClient(ctx, slot); if (id == LIKED) c.likedTracks() else c.playlistTracks(id) }
                    paged(json.encodeToString(all.drop(extras?.getInt("offset") ?: 0).take(PAGE)), all.size, extras?.getInt("offset") ?: 0)
                }
                "deezer.search" -> ok(json.encodeToString(DeezerWebClient(ctx, slot).search(arg ?: "", extras?.getString("isrc"))))
                "deezer.track" -> ok(json.encodeToString(DeezerWebClient(ctx, slot).track(arg ?: return err("missing track id"))))
                "deezer.create" -> ok(json.encodeToString(DeezerWebClient(ctx, slot).createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "deezer.add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    if (id == LIKED) DeezerWebClient(ctx, slot).like(ids, on = true) else DeezerWebClient(ctx, slot).addToPlaylist(id, ids)
                    deezerCache.invalidate(ck(slot, id))
                    Bundle()
                }
                "deezer.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    if (id == LIKED) DeezerWebClient(ctx, slot).like(ids, on = false) else DeezerWebClient(ctx, slot).removeFromPlaylist(id, ids)
                    deezerCache.invalidate(ck(slot, id))
                    Bundle()
                }
                "deezer.rename" -> { DeezerWebClient(ctx, slot).renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "deezer.delete" -> { DeezerWebClient(ctx, slot).deletePlaylist(arg ?: return err("missing playlist id")); deezerCache.invalidate(ck(slot, arg)); Bundle() }
                // ---- Relay for the Google Play build's read-only link sources and TikTok lookups (NetRelay).
                "net.status" -> Bundle().apply { putBoolean("ok", true); putInt("version", VERSION); putBoolean("slots", Slots.supported()) }
                "net.fetch" -> NetRelay.fetch(arg ?: return err("missing address"), extras)
                "net.chunk" -> NetRelay.chunk(arg ?: return err("missing id"), extras?.getInt("offset") ?: 0)
                // ---- Anghami web session: the web player's gateway with its sid; the liked songs as "__liked__".
                "anghami.status" -> Bundle().apply {
                    putBoolean("connected", AnghamiBridge.session(slot).isConnected(ctx))
                    putString("account", AnghamiBridge.session(slot).account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "anghami.disconnect" -> { AnghamiBridge.session(slot).clear(ctx); anghamiCache.clear(); WebEngine.reset("anghami", slot); Bundle() }
                "anghami.playlists" -> ok(json.encodeToString(AnghamiWebClient(ctx, slot).playlists()))
                "anghami.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val all = anghamiCache.get(ck(slot, id)) { val c = AnghamiWebClient(ctx, slot); if (id == LIKED) c.liked() else c.tracks(id) }
                    paged(json.encodeToString(all.drop(extras?.getInt("offset") ?: 0).take(PAGE)), all.size, extras?.getInt("offset") ?: 0)
                }
                "anghami.search" -> ok(json.encodeToString(AnghamiWebClient(ctx, slot).search(arg ?: "")))
                "anghami.create" -> ok(json.encodeToString(AnghamiWebClient(ctx, slot).create(extras?.getString("name") ?: "Playlist")))
                "anghami.add", "anghami.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    val on = method == "anghami.add"
                    val c = AnghamiWebClient(ctx, slot)
                    when { id == LIKED -> c.like(ids, on); on -> c.add(id, ids); else -> c.remove(id, ids) }
                    anghamiCache.invalidate(ck(slot, id))
                    Bundle()
                }
                "anghami.rename" -> { AnghamiWebClient(ctx, slot).rename(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "anghami.delete" -> { AnghamiWebClient(ctx, slot).delete(arg ?: return err("missing playlist id")); anghamiCache.invalidate(ck(slot, arg)); Bundle() }
                // ---- Qobuz web session: the web player's API with its user token and app id; the favourite tracks as "__liked__".
                "qobuz.status" -> Bundle().apply {
                    putBoolean("connected", QobuzBridge.session(slot).isConnected(ctx))
                    putString("account", QobuzBridge.session(slot).account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "qobuz.disconnect" -> { QobuzBridge.session(slot).clear(ctx); qobuzCache.clear(); Bundle() }
                "qobuz.playlists" -> ok(json.encodeToString(QobuzWebClient(ctx, slot).playlists()))
                "qobuz.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val all = qobuzCache.get(ck(slot, id)) { val c = QobuzWebClient(ctx, slot); if (id == LIKED) c.liked() else c.tracks(id) }
                    paged(json.encodeToString(all.drop(extras?.getInt("offset") ?: 0).take(PAGE)), all.size, extras?.getInt("offset") ?: 0)
                }
                "qobuz.find" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toSet() ?: emptySet()
                    val listed = qobuzCache.peek(ck(slot, id)) ?: if (id == LIKED) qobuzCache.get(ck(slot, id)) { QobuzWebClient(ctx, slot).liked() } else null
                    ok(json.encodeToString(listed?.filter { it.id in ids } ?: QobuzWebClient(ctx, slot).findEntries(id, ids)))
                }
                "qobuz.search" -> ok(json.encodeToString(QobuzWebClient(ctx, slot).search(arg ?: "", extras?.getString("isrc"))))
                "qobuz.create" -> ok(json.encodeToString(QobuzWebClient(ctx, slot).create(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "", extras?.getBoolean("public") ?: false)))
                "qobuz.add", "qobuz.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    // Removing from a playlist goes by entry (playlist_track_id); "" where the caller had none.
                    val items = extras?.getStringArray("items")?.map { it.ifEmpty { null } } ?: emptyList()
                    val on = method == "qobuz.add"
                    val c = QobuzWebClient(ctx, slot)
                    when { id == LIKED -> c.like(ids, on); on -> c.add(id, ids); else -> c.remove(id, ids, items) }
                    qobuzCache.invalidate(ck(slot, id))
                    Bundle()
                }
                "qobuz.rename" -> { QobuzWebClient(ctx, slot).rename(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "qobuz.visibility" -> { QobuzWebClient(ctx, slot).setPublic(arg ?: return err("missing playlist id"), extras?.getBoolean("public") ?: false); Bundle() }
                "qobuz.delete" -> { QobuzWebClient(ctx, slot).delete(arg ?: return err("missing playlist id")); qobuzCache.invalidate(ck(slot, arg)); Bundle() }
                // ---- LiveOne web session: the presets (as liked songs), read whole and added to; search without an account.
                "liveone.status" -> Bundle().apply {
                    putBoolean("connected", LiveOneBridge.session(slot).isConnected(ctx))
                    putString("account", LiveOneBridge.session(slot).account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                }
                "liveone.disconnect" -> { LiveOneBridge.session(slot).clear(ctx); Bundle() }
                "liveone.presets" -> ok(json.encodeToString(LiveOneWebClient(ctx, slot).presets()))
                "liveone.search" -> ok(json.encodeToString(LiveOneWebClient(ctx, slot).search(arg ?: "")))
                "liveone.add" -> { LiveOneWebClient(ctx, slot).addPresets(extras?.getStringArray("ids")?.toList() ?: emptyList()); Bundle() }
                // ---- SoundCloud web session: api-v2 with the web app's client id and the account's OAuth token.
                // Tracks travel as SoundCloud's own track objects, trimmed: Trackhop parses them like its public links.
                "soundcloud.status" -> Bundle().apply {
                    putBoolean("connected", SoundCloudBridge.session.isConnected(ctx))
                    putString("account", SoundCloudBridge.session.account(ctx))
                    putInt("version", VERSION)
                    putBoolean("slots", slotsFor(method))
                    SoundCloudRenewal.expiredAt(ctx)?.let { putString("expiredAt", it) }
                }
                // Trackhop's background keep-alive: the site's session renewed once, and whether it is gone (and since when).
                "soundcloud.keepAlive" -> Bundle().apply {
                    putBoolean("renewed", SoundCloudRenewal.keepAlive(ctx))
                    putBoolean("connected", SoundCloudBridge.session.isConnected(ctx))
                    SoundCloudRenewal.expiredAt(ctx)?.let { putString("expiredAt", it) }
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
                "soundcloud.setCover" -> { SoundCloudWebClient(ctx).setArtwork(arg ?: return err("missing playlist id"), extras?.getByteArray(COVER) ?: return err("missing image")); Bundle() }
                "soundcloud.delete" -> { SoundCloudWebClient(ctx).delete(arg ?: return err("missing playlist id")); soundCloudCache.invalidate(arg); Bundle() }
                else -> err("unknown method $method")
            }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName).apply {
                // A SoundCloud session found gone: since when, so Trackhop tells it once.
                if (method.startsWith("soundcloud.")) SoundCloudRenewal.expiredAt(ctx)?.let { putString("expiredAt", it) }
            }
        }
    }

    // A playlist is fetched once and served in pages (Binder transactions are capped at ~1 MB): see [ListCache].
    private val ytmCache = ListCache<TrackDto>()

    /** A cache key that tells the accounts apart: the same playlist id in two slots is two listings. */
    private fun ck(slot: String, id: String): String = if (slot.isEmpty()) id else "$slot@$id"

    @Synchronized
    private fun cachedTracks(client: YtmClient, id: String, slot: String): List<TrackDto> {
        return ytmCache.get(ck(slot, id)) {
            when (id) { ALBUMS -> client.libraryAlbums(); ARTISTS -> client.librarySubscriptions(); PODCASTS -> client.libraryPodcasts(); RECENT -> client.history(); else -> client.tracks(id) }
        }
    }

    @Synchronized
    private fun freshTracks(id: String, slot: String): List<TrackDto>? = ytmCache.peek(ck(slot, id))

    @Synchronized
    private fun freshSpotify(id: String, slot: String): List<TrackDto>? = spotifyCache.peek(ck(slot, id))

    @Synchronized
    private fun invalidate(id: String, slot: String) { ytmCache.invalidate(ck(slot, id)) }

    private val spotifyCache = ListCache<TrackDto>()

    @Synchronized
    private fun cachedSpotifyTracks(ctx: Context, id: String, slot: String): List<TrackDto> {
        return spotifyCache.get(ck(slot, id)) {
            val c = SpotifyWebClient(ctx, slot)
            when (id) {
                "__liked__" -> c.likedTracks()
                "__albums__" -> c.libraryItems("album")
                "__artists__" -> c.libraryItems("artist")
                else -> c.playlistTracks(id)
            }
        }
    }

    @Synchronized
    private fun invalidateSpotify(id: String, slot: String) { spotifyCache.invalidate(ck(slot, id)) }

    private const val LIKED = "__liked__"

    /** Drops the listing kept for [id] by [method]'s connector (a `tracks` read), so the next page 0 reads it again. */
    private fun dropListing(method: String, id: String, slot: String) {
        when (method) {
            "tracks" -> invalidate(id, slot)
            "spotify.tracks" -> invalidateSpotify(id, slot)
            "deezer.tracks" -> deezerCache.invalidate(ck(slot, id))
            "anghami.tracks" -> anghamiCache.invalidate(ck(slot, id))
            "qobuz.tracks" -> qobuzCache.invalidate(ck(slot, id))
            "soundcloud.tracks" -> soundCloudCache.invalidate(id)
        }
    }

    private val deezerCache = ListCache<TrackDto>()
    private val anghamiCache = ListCache<TrackDto>()
    private val qobuzCache = ListCache<TrackDto>()
    private val soundCloudCache = ListCache<kotlinx.serialization.json.JsonObject>()

    /** One page of a listing, as the `tracks` methods answer: the page, the total, and where the next one starts. */
    private fun paged(pageJson: String, total: Int, offset: Int) = Bundle().apply {
        putString("json", pageJson)
        putInt("total", total)
        if (offset + PAGE < total) putInt("next", offset + PAGE)
    }

    private fun ok(jsonText: String) = Bundle().apply { putString("json", jsonText) }
    private fun err(message: String) = Bundle().apply { putString("error", message) }

    /** The web connectors a start over applies to, with the session of [slot]. YouTube Music and Amazon are not among them. */
    fun resettable(service: String, slot: String): WebSession? = when (service) {
        "anghami" -> AnghamiBridge.session(slot)
        "qobuz" -> QobuzBridge.session(slot)
        "liveone" -> LiveOneBridge.session(slot)
        "deezer" -> DeezerBridge.session(slot)
        "spotify" -> SpotifyBridge.session(slot)
        "apple" -> AppleBridge.session(slot)
        "soundcloud" -> SoundCloudBridge.session
        else -> null
    }

    private fun reset(ctx: Context, method: String, arg: String?, extras: Bundle?, slot: String): Bundle {
        val svc = serviceOf(method)
        val session = resettable(svc, slot) ?: return err("unknown method $method")
        val cookies = WebReset.cookieCount(session)
        val out = call(ctx, "$svc.disconnect", arg, extras)
        if (out.containsKey("error")) return out
        val sites = WebReset.forgetStorage(session)
        BridgeNet.log(svc, "start over${if (slot.isEmpty()) "" else " (account $slot)"}: session forgotten, $cookies cookies and the data of $sites sites cleared")
        return Bundle().apply { putInt("cookies", cookies); putInt("sites", sites) }
    }
}

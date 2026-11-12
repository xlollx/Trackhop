package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.MusicBrainz
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Jellyfin (https://api.jellyfin.org): server personale, nessuna registrazione, nessuna quota. */
open class JellyfinProvider(override val slot: String = "") : CredentialsProvider(), PlayCounts {
    override val serviceId = SERVICE
    override val displayName = "Jellyfin"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFF00A4DC
    override val noteName = "provider_note_jellyfin"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val matchesByMbid = true
    // A Jellyfin user may have no password (the server allows it, as its public demo does): may stay empty.
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_jellyfin", secretOptional = true)

    // Client and DeviceId stay "Songport" after the rename to Trackhop: they are identifiers, not a
    // name the user reads, and Jellyfin files existing sign-ins under them in its Devices list.
    private fun authHeader(token: String?) =
        "MediaBrowser Client=\"Songport\", Device=\"Android\", DeviceId=\"songport-android\", Version=\"1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    /** The headers of every call: Jellyfin's "Authorization: MediaBrowser …" (Emby overrides it). */
    protected open fun headers(token: String?): Map<String, String> =
        mapOf("Authorization" to authHeader(token), "Accept" to "application/json")

    /** Where the API lives under the server's address (Emby: under /emby). */
    protected open fun apiRoot(base: String): String = base

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val resp = Http.send(
            "POST", "${apiRoot(base)}/Users/AuthenticateByName",
            headers(null),
            Http.jsonBody(jsonObj("Username" to user.trim(), "Pw" to secret).toString()),
        )
        if (!resp.ok) throw ProviderException("$displayName: login failed (HTTP ${resp.code})")
        val j = parseJson(resp.body)
        val token = j["AccessToken"].str ?: throw ProviderException("$displayName: no token")
        save(ctx, Tokens(accessToken = token, userId = j["User"]["Id"].str ?: "", userName = user.trim(), extra = mapOf("url" to base)))
    }

    /**
     * Quick Connect (#45): sign in with a short code approved from a device that is already signed in,
     * with no password. For servers behind single sign-on, where the password is not a Jellyfin one.
     * Emby has no Quick Connect.
     */
    open val supportsQuickConnect: Boolean get() = true

    class QuickConnect(val base: String, val secret: String, val code: String)

    /** Asks the server for a code to approve. Throws with a clear message when Quick Connect is off. */
    suspend fun quickConnectStart(ctx: Env, url: String): QuickConnect {
        val base = normalizeUrl(url)
        val root = apiRoot(base)
        val enabled = Http.send("GET", "$root/QuickConnect/Enabled", headers(null))
        if (enabled.ok && enabled.body.trim().equals("false", ignoreCase = true)) throw ProviderException(ctx.text("jellyfin_qc_off"))
        // POST since Jellyfin 10.9; 10.8 answered GET.
        var resp = Http.send("POST", "$root/QuickConnect/Initiate", headers(null))
        if (resp.code == 404 || resp.code == 405) resp = Http.send("GET", "$root/QuickConnect/Initiate", headers(null))
        if (resp.code == 401 || resp.code == 403 || resp.code == 404) throw ProviderException(ctx.text("jellyfin_qc_off"))
        if (!resp.ok) throw ProviderException("$displayName: Quick Connect failed (HTTP ${resp.code})")
        val j = parseJson(resp.body)
        val secret = j["Secret"].str ?: throw ProviderException("$displayName: Quick Connect failed (no secret)")
        val code = j["Code"].str ?: throw ProviderException("$displayName: Quick Connect failed (no code)")
        return QuickConnect(base, secret, code)
    }

    /** True once the code is approved and the sign-in saved; false while the server still waits. */
    suspend fun quickConnectPoll(ctx: Env, qc: QuickConnect): Boolean {
        val root = apiRoot(qc.base)
        val r = Http.send("GET", "$root/QuickConnect/Connect?Secret=${Http.enc(qc.secret)}", headers(null))
        if (r.code == 404) throw ProviderException(ctx.text("jellyfin_qc_expired"))
        if (!r.ok) throw ProviderException("$displayName: Quick Connect failed (HTTP ${r.code})")
        if (parseJson(r.body)["Authenticated"].bool != true) return false
        val a = Http.send(
            "POST", "$root/Users/AuthenticateWithQuickConnect", headers(null),
            Http.jsonBody(jsonObj("Secret" to qc.secret).toString()),
        )
        if (!a.ok) throw ProviderException("$displayName: login failed (HTTP ${a.code})")
        val j = parseJson(a.body)
        val token = j["AccessToken"].str ?: throw ProviderException("$displayName: no token")
        save(ctx, Tokens(accessToken = token, userId = j["User"]["Id"].str ?: "", userName = j["User"]["Name"].str ?: "", extra = mapOf("url" to qc.base)))
        return true
    }

    protected suspend fun api(ctx: Env, method: String, path: String, body: JsonElement? = null): JsonElement {
        val t = creds(ctx)
        val resp = Http.send(
            method, "${apiRoot(t.extra["url"] ?: "")}$path",
            headers(t.accessToken),
            body?.let { Http.jsonBody(it.toString()) },
        )
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}", code = resp.code)
        return parseJson(resp.body)
    }

    protected fun uid(ctx: Env) = creds(ctx).userId

    // ---- routes: Jellyfin's documented ones, the per-user ones as the fallback

    /**
     * Jellyfin's OpenAPI spec dropped the per-user routes in 10.9 (`/Users/{userId}/Items`,
     * `/Users/{userId}/Items/{itemId}`, `/Users/{userId}/FavoriteItems/{itemId}`) for `/Items?userId=`,
     * `/Items/{itemId}?userId=` and `/UserFavoriteItems/{itemId}?userId=`, and says a route not in the
     * spec should not be used. Those come first; the older ones stay as the fallback for a server
     * that refuses them (Jellyfin 10.8 has no `/UserFavoriteItems`). Emby documents only the per-user
     * routes (dev.emby.media), so it asks them first, and the Jellyfin ones second.
     */
    protected open val userRoutesFirst: Boolean get() = false

    /** Route kinds a server refused in Jellyfin's documented form, by server: the older one is asked straight away. */
    private val olderRoutesOnly: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * [documented] or [older] (paths under the API root) in the order this server takes them. A
     * refusal of the route itself (400, 404, 405) tries the other; when that fails too, the first
     * error is the one reported. With [emptyTriesOther], an empty `Items` list asks the other route
     * as well, and its answer is kept when it lists something.
     */
    private suspend fun routed(ctx: Env, kind: String, method: String, documented: String, older: String, emptyTriesOther: Boolean = false): JsonElement {
        val key = "$kind@" + (creds(ctx).extra["url"] ?: "")
        if (!userRoutesFirst && key in olderRoutesOnly) return api(ctx, method, older)
        val (first, second) = if (userRoutesFirst) older to documented else documented to older
        val answer = try {
            api(ctx, method, first)
        } catch (e: ProviderException) {
            if (e.code !in ROUTE_REFUSED) throw e
            val other = try { api(ctx, method, second) } catch (e2: ProviderException) { throw e }
            if (!userRoutesFirst) {
                olderRoutesOnly += key
                ctx.log(serviceId, "$method ${first.substringBefore('?')} refused (${e.code}): the older route from now on")
            }
            return other
        }
        if (emptyTriesOther && answer["Items"].arr.isEmpty()) {
            runCatching { api(ctx, method, second) }.getOrNull()?.takeIf { it["Items"].arr.isNotEmpty() }?.let { return it }
        }
        return answer
    }

    /** The user's items matching [query] (no leading '&'): `GET /Items?userId=`, or `/Users/{userId}/Items`. */
    private suspend fun userItems(ctx: Env, query: String): JsonElement {
        val u = uid(ctx)
        return routed(ctx, "items", "GET", "/Items?userId=$u&$query", "/Users/$u/Items?$query", emptyTriesOther = true)
    }

    /** One item as the user sees it: `GET /Items/{itemId}?userId=`, or `/Users/{userId}/Items/{itemId}`. */
    private suspend fun userItem(ctx: Env, itemId: String): JsonElement {
        val u = uid(ctx)
        return routed(ctx, "item", "GET", "/Items/$itemId?userId=$u", "/Users/$u/Items/$itemId")
    }

    /** Marks or unmarks a favourite: `/UserFavoriteItems/{itemId}?userId=`, or `/Users/{userId}/FavoriteItems/{itemId}`. */
    private suspend fun favourite(ctx: Env, method: String, itemId: String) {
        val u = uid(ctx)
        routed(ctx, "favourite", method, "/UserFavoriteItems/$itemId?userId=$u", "/Users/$u/FavoriteItems/$itemId")
    }

    /** The search page of the server's own web client, where the user finds the track and copies its link. */
    override fun webSearchUrl(ctx: Env, query: String): String? =
        ctx.tokens.get(id)?.extra?.get("url")?.let { searchUrl(it, query) }

    private fun toTrack(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        return Track(
            id = id,
            title = i["Name"].str ?: "",
            artists = i["Artists"].arr.mapNotNull { it.str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) },
            album = i["Album"].str ?: "",
            durationMs = (i["RunTimeTicks"].long ?: 0) / 10_000,
            itemId = i["PlaylistItemId"].str,
            year = i["ProductionYear"].int ?: 0,
            addedAt = com.xlollx.songport.sync.Durations.parseInstant(i["DateCreated"].str),
            // Jellyfin 10.9+ keeps the file's recording id in MusicBrainzRecording; MusicBrainzTrack is
            // the track of one release there, and the recording on Emby and older servers. Either one
            // says the file is tagged; only a recording id can equal the one a search asks for.
            mbids = listOfNotNull(MusicBrainz.mbid(i["ProviderIds"]["MusicBrainzRecording"].str), MusicBrainz.mbid(i["ProviderIds"]["MusicBrainzTrack"].str)).distinct(),
        )
    }

    /** The query filter that asks the server for the items tagged with one of [mbids], where it has one (Emby). */
    protected open fun mbidFilter(mbids: List<String>): String? = null

    override suspend fun librarySongs(ctx: Env, onProgress: (Int) -> Unit): List<Track> {
        val out = ArrayList<Track>()
        var offset = 0
        while (true) {
            val page = userItems(ctx, "IncludeItemTypes=Audio&Recursive=true&Fields=ProviderIds&StartIndex=$offset&Limit=$LIBRARY_PAGE")["Items"].arr
            out += page.mapNotNull { toTrack(it) }
            offset += page.size
            onProgress(out.size)
            if (page.size < LIBRARY_PAGE) return out
        }
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        userItems(ctx, "IncludeItemTypes=Playlist&Recursive=true&Fields=ChildCount")["Items"].arr
            .mapNotNull { p -> Playlist(p["Id"].str ?: return@mapNotNull null, p["Name"].str ?: "", p["ChildCount"].int ?: -1) }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val p = userItem(ctx, playlistId)
        return Playlist(playlistId, p["Name"].str ?: playlistId, p["ChildCount"].int ?: -1)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val answer = when (playlistId) {
            MusicProvider.LIKED_ID -> userItems(ctx, "Filters=IsFavorite&IncludeItemTypes=Audio&Recursive=true&Limit=5000&Fields=ProviderIds")
            MusicProvider.ALBUMS_ID -> userItems(ctx, "Filters=IsFavorite&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=5000")
            MusicProvider.ARTISTS_ID -> userItems(ctx, "Filters=IsFavorite&IncludeItemTypes=MusicArtist&Recursive=true&Limit=5000")
            else -> api(ctx, "GET", "/Playlists/$playlistId/Items?UserId=${uid(ctx)}&Fields=ProviderIds")
        }
        val items = answer["Items"].arr
        return when (playlistId) {
            MusicProvider.ALBUMS_ID -> items.mapNotNull { toAlbum(it) }
            MusicProvider.ARTISTS_ID -> items.mapNotNull { toArtist(it) }
            else -> items.mapNotNull { toTrack(it) }
        }
    }

    /** Each song of the playlist (or of the favourites) with its `UserData.PlayCount` for the signed-in user. */
    override suspend fun playlistPlays(ctx: Env, playlistId: String): List<Listening.Play> {
        val answer = when {
            playlistId == MusicProvider.LIKED_ID -> userItems(ctx, "Filters=IsFavorite&IncludeItemTypes=Audio&Recursive=true&Limit=5000&EnableUserData=true")
            MusicProvider.isLibrary(playlistId) -> return emptyList()
            else -> api(ctx, "GET", "/Playlists/$playlistId/Items?UserId=${uid(ctx)}&EnableUserData=true")
        }
        return answer["Items"].arr.mapNotNull { i -> toTrack(i)?.let { Listening.jellyfinPlay(i, it) } }
    }

    /** The songs played most, sorted by the server itself (SortBy=PlayCount). */
    override suspend fun topPlays(ctx: Env, n: Int): List<Listening.Play> =
        userItems(ctx, "IncludeItemTypes=Audio&Recursive=true&Filters=IsPlayed&SortBy=PlayCount&SortOrder=Descending&EnableUserData=true&Limit=$n")["Items"].arr
            .mapNotNull { i -> toTrack(i)?.let { Listening.jellyfinPlay(i, it) } }.filter { it.plays > 0 }.sortedByDescending { it.plays }

    override val searchesTitleOnly: Boolean get() = true

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            // Jellyfin's search term matches names: the album title alone finds the album, the artist filters after.
            Track.KIND_ALBUM -> return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(Matcher.searchTitle(track.title))}&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=8")["Items"].arr
                .mapNotNull { toAlbum(it) }
            Track.KIND_ARTIST -> return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(track.title)}&IncludeItemTypes=MusicArtist&Recursive=true&Limit=5")["Items"].arr
                .mapNotNull { toArtist(it) }
        }
        // The search term matches names only ("Metallica Battery" finds nothing): the title, then the artist's songs first.
        suspend fun find(term: String) = api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(term)}&IncludeItemTypes=Audio&Recursive=true&Limit=25&Fields=ProviderIds")["Items"].arr
            .mapNotNull { toTrack(it) }
        // Emby finds an item by the id it is tagged with: the exact recording, whatever its title. The
        // answer is checked (a server that ignores the filter lists anything), and a refusal is no error.
        if (track.mbids.isNotEmpty()) mbidFilter(track.mbids)?.let { filter ->
            val tagged = try {
                api(ctx, "GET", "/Items?UserId=${uid(ctx)}&$filter&IncludeItemTypes=Audio&Recursive=true&Limit=25&Fields=ProviderIds")["Items"].arr.mapNotNull { toTrack(it) }
            } catch (e: ProviderException) { emptyList() }
            tagged.filter { c -> c.mbids.any { it in track.mbids } }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Jellyfin 10.8 does not fold accents: "energie" misses "Énergie". The title as written, then.
        // "Oxygene, Pt. 4" against "Oxygène (Part IV)" (#57): the bare title, the scoring picks the part.
        var found = Matcher.searchByTitles(track) { find(it) }
        // Nothing close by name: the library may name the piece another way than the source does (a
        // title in another language, other punctuation), so the artist's own songs are scored too (#57).
        if (track.artists.isNotEmpty() && found.none { Matcher.score(track, it) >= Matcher.DEFAULT_THRESHOLD }) {
            val ids = found.mapTo(HashSet()) { it.id }
            found = found + artistSongs(ctx, track.artists).filter { ids.add(it.id) }
        }
        // The closest first: a search by the bare title brings every part of the piece, the right one may be the sixth.
        return Matcher.byArtist(track, found).sortedByDescending { Matcher.score(track, it) }.take(5)
    }

    /** The songs of the library's artist named like the first of [artists]; none when there is no such artist or the server refuses. */
    private suspend fun artistSongs(ctx: Env, artists: List<String>): List<Track> = try {
        val name = artists.first().let { Matcher.primaryArtist(it) ?: it }.trim()
        val want = Matcher.normalizeArtist(name)
        val ids = artistIds(ctx, name, want)
        if (ids.isEmpty()) emptyList()
        else api(ctx, "GET", "/Items?UserId=${uid(ctx)}&ArtistIds=${ids.joinToString(",")}&IncludeItemTypes=Audio&Recursive=true&Limit=$ARTIST_SONGS&Fields=ProviderIds")["Items"].arr
            .mapNotNull { toTrack(it) }
    } catch (e: ProviderException) { emptyList() }

    /**
     * The library's artists named [name]. `GET /Artists` is deprecated in Jellyfin 12.0: the artists
     * are asked as items of type MusicArtist first, and `/Artists` stays the fallback when that
     * finds none or is refused (an older server, Emby).
     */
    private suspend fun artistIds(ctx: Env, name: String, want: String): List<String> {
        if (want.isEmpty()) return emptyList()
        fun named(j: JsonElement?) = j["Items"].arr.filter { Matcher.normalizeArtist(it["Name"].str ?: "") == want }.mapNotNull { it["Id"].str }
        val byItems = try {
            named(api(ctx, "GET", "/Items?userId=${uid(ctx)}&searchTerm=${Http.enc(name)}&IncludeItemTypes=MusicArtist&Recursive=true&Limit=10"))
        } catch (e: ProviderException) { emptyList() }
        if (byItems.isNotEmpty()) return byItems
        return named(api(ctx, "GET", "/Artists?UserId=${uid(ctx)}&searchTerm=${Http.enc(name)}&Limit=10"))
    }

    private fun toAlbum(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        val artists = i["AlbumArtists"].arr.mapNotNull { it["Name"].str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) }
        return Track(id = id, title = i["Name"].str ?: "", artists = artists, kind = Track.KIND_ALBUM)
    }

    private fun toArtist(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        val name = i["Name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "/Playlists", jsonObj("Name" to name, "Ids" to emptyList<String>(), "UserId" to uid(ctx), "MediaType" to "Audio"))
        return Playlist(j["Id"].str ?: throw ProviderException("$displayName: playlist not created"), name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        try {
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Name" to name))
        } catch (e: ProviderException) {
            // Before 10.9 there is no playlist update: the item itself is written back, renamed, whole.
            if (e.message?.contains(" 404") != true && e.message?.contains(" 405") != true) throw e
            val item = userItem(ctx, playlistId).jsonObject
            api(ctx, "POST", "/Items/$playlistId", JsonObject(item + ("Name" to JsonPrimitive(name))))
        }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        api(ctx, "DELETE", "/Items/$playlistId")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) {
            // Favourites work the same for songs, albums and artists.
            tracks.forEach { favourite(ctx, "POST", it.id) }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            api(ctx, "POST", "/Playlists/$playlistId/Items?Ids=${chunk.joinToString(",") { it.id }}&UserId=${uid(ctx)}")
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) {
            tracks.forEach { favourite(ctx, "DELETE", it.id) }
            return
        }
        val gone = tracks.mapNotNull { it.itemId }
        // Jellyfin 10.9+ gives every copy of a song the same entry id, and deleting it drops them all:
        // when a copy has to stay, the list is written back whole.
        keptAfterRemoving(tracks(ctx, playlistId), gone)?.let { kept ->
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Ids" to kept.map { it.id }))
            return
        }
        gone.chunked(50).forEach { chunk ->
            api(ctx, "DELETE", "/Playlists/$playlistId/Items?EntryIds=${chunk.joinToString(",")}")
        }
    }

    /**
     * `POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}` (Jellyfin and Emby): one entry,
     * named by the entry id the playlist lists it with, taken out and put back at the new index. The
     * moves are worked out on those entry ids. Jellyfin 10.9+ gives every copy of a song the same entry
     * id, and the server moves the first copy: with copies in the playlist, the order is written back
     * whole instead (`POST /Playlists/{id}` with the ordered ids, as [removeTracks] does).
     */
    override val canReorder: Boolean get() = true
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        val keys = current.map { it.itemId ?: it.id }
        if (keys.toSet().size != keys.size) {
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Ids" to ordered.map { it.id }))
            return
        }
        // Each move as the server makes it: out at [from], then in at [to] of the shorter list.
        val list = keys.toMutableList()
        for ((from, to) in com.xlollx.songport.sync.Reorder.moves(keys, ordered.map { it.itemId ?: it.id })) {
            val entry = list.removeAt(from)
            list.add(to, entry)
            api(ctx, "POST", "/Playlists/$playlistId/Items/${Http.enc(entry)}/Move/$to")
        }
    }

    companion object {
        /** How a server says it has no such route: the other form is tried then. */
        private val ROUTE_REFUSED = setOf(400, 404, 405)
        const val SERVICE = "jellyfin"
        /** The most songs of one artist read when a title search found nothing close. */
        private const val ARTIST_SONGS = 2000
        private const val LIBRARY_PAGE = 500

        /**
         * jellyfin-web's search page with [query] filled in. The web client lives under /web with hash
         * routing; since 10.9 its routes drop ".html" (#/search; "search.html" only redirects, deprecated),
         * and 10.8 is past its end of life.
         */
        internal fun searchUrl(base: String, query: String): String = "$base/web/#/search?query=" + Http.enc(query)

        /**
         * The entries left once [gone] (entry ids, one per copy to remove) are removed, or null when
         * deleting those entry ids removes exactly them. With shared ids the first copies stay.
         */
        internal fun keptAfterRemoving(entries: List<Track>, gone: List<String>): List<Track>? {
            val toDrop = gone.groupingBy { it }.eachCount().toMutableMap()
            if (toDrop.all { (id, n) -> entries.count { it.itemId == id } <= n }) return null
            return entries.asReversed().filter { e ->
                val left = e.itemId?.let { toDrop[it] } ?: 0
                if (left > 0) toDrop[e.itemId!!] = left - 1
                left == 0
            }.asReversed()
        }
    }
}

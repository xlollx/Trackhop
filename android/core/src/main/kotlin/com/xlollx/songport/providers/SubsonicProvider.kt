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
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.MusicBrainz
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API Subsonic (http://www.subsonic.org/pages/api.jsp): copre Navidrome, Airsonic, Gonic, LMS,
 * Funkwhale e gli altri server compatibili. Nessuna registrazione, nessuna quota: solo il tuo server.
 * Autenticazione con token = md5(password + salt) per richiesta, mai la password in chiaro.
 * In alternativa, sui server OpenSubsonic che la offrono, una chiave API (estensione
 * apiKeyAuthentication: parametro `apiKey`, senza `u`, `t`, `s`).
 */
class SubsonicProvider(override val slot: String = "") : CredentialsProvider(), PlayCounts {
    override val serviceId = SERVICE
    override val displayName = "Subsonic / Navidrome"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFF2E7D32
    override val noteName = "provider_note_subsonic"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val matchesByMbid = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_subsonic", offersApiKey = true)

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        var t = Tokens(accessToken = secret, userName = user.trim(), extra = mapOf("url" to base))
        // The version this server speaks, when it refuses ours: every later call is made with it.
        // Its kind too ("navidrome", ...), which says whether it has a web search to open.
        try {
            call(ctx, "ping", emptyList(), t, onType = { ty -> t = t.copy(extra = t.extra + (TYPE to ty)) }) { v -> t = t.copy(extra = t.extra + ("v" to v)) }
        } catch (e: ProviderException) {
            // 41 (token sign-in not supported, LDAP users) or 42 (mechanism not supported): a server that
            // takes no password from apps. When it offers keys, the user is pointed there.
            if ((e.code == 41 || e.code == 42) && APIKEY_EXTENSION in extensions(base)) throw ProviderException(ctx.text("subsonic_apikey_needed"), code = e.code)
            throw e
        }
        save(ctx, t)
    }

    /**
     * OpenSubsonic API key sign-in (opensubsonic.netlify.app/docs/extensions/apikeyauth): the server
     * must list `apiKeyAuthentication` in `getOpenSubsonicExtensions` (public, no credentials). The
     * key is then checked with `tokenInfo`, which also names the account it belongs to; each later
     * call carries `apiKey` and no `u`, `t` or `s` (sending both is error 43). The password sign-in
     * ([login]) is untouched: a connection made with it keeps the salted token.
     */
    override suspend fun loginWithApiKey(ctx: Env, url: String, apiKey: String) {
        val base = normalizeUrl(url)
        val key = apiKey.trim()
        if (key.isEmpty()) throw ProviderException(ctx.text("subsonic_apikey_invalid"))
        if (APIKEY_EXTENSION !in extensions(base)) throw ProviderException(ctx.text("subsonic_apikey_unsupported"))
        var t = Tokens(accessToken = key, extra = mapOf("url" to base, AUTH to AUTH_APIKEY))
        val info = try {
            call(ctx, "tokenInfo", emptyList(), t, onType = { ty -> t = t.copy(extra = t.extra + (TYPE to ty)) }) { v -> t = t.copy(extra = t.extra + ("v" to v)) }
        } catch (e: ProviderException) {
            if (e.code == ERROR_INVALID_KEY) throw ProviderException(ctx.text("subsonic_apikey_invalid"), code = e.code)
            throw e
        }
        save(ctx, t.copy(userName = info["tokenInfo"]["username"].str.orEmpty()))
    }

    /** The OpenSubsonic extensions [base] lists; none for a plain Subsonic server or one that refuses the call. */
    internal suspend fun extensions(base: String): Set<String> {
        val resp = Http.send("GET", "$base/rest/getOpenSubsonicExtensions?v=$API_VERSION&c=Songport&f=json", mapOf("Accept" to "application/json"))
        if (!resp.ok) return emptySet()
        val r = runCatching { parseJson(resp.body)["subsonic-response"] }.getOrNull() ?: return emptySet()
        if (r["status"].str != "ok") return emptySet()
        return r["openSubsonicExtensions"].arr.mapNotNull { it["name"].str }.toSet()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Chiamata REST Subsonic; i parametri ripetibili (songIdToAdd…) passano come lista di coppie. */
    private suspend fun call(
        ctx: Env, method: String, params: List<Pair<String, String>>, t: Tokens = creds(ctx),
        // Connections made before 1.3.2 never stored the server's kind: the first answer records it.
        onType: (String) -> Unit = { ty -> ctx.tokens.get(id)?.let { cur -> save(ctx, cur.copy(extra = cur.extra + (TYPE to ty))) } },
        onVersion: (String) -> Unit = { v -> save(ctx, t.copy(extra = t.extra + ("v" to v))) },
    ): JsonElement {
        var version = t.extra["v"] ?: API_VERSION
        while (true) {
            val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            // "c" stays "Songport" after the rename to Trackhop: Navidrome and others register a player
            // per client name, with the admin's settings (transcoding, ...) attached to it.
            // An API key goes alone: with it, u, t and s are an error (43, conflicting mechanisms).
            val auth = if (t.extra[AUTH] == AUTH_APIKEY) listOf("apiKey" to t.accessToken)
            else listOf("u" to t.userName, "t" to md5(t.accessToken + salt), "s" to salt)
            val common = auth + listOf("v" to version, "c" to "Songport", "f" to "json")
            val query = (common + params).joinToString("&") { (k, v) -> "${Http.enc(k)}=${Http.enc(v)}" }
            val resp = Http.send("GET", "${t.extra["url"]}/rest/$method?$query", mapOf("Accept" to "application/json"))
            if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
            val r = parseJson(resp.body)["subsonic-response"] ?: throw ProviderException("$displayName: unexpected response")
            if (r["status"].str == "ok") {
                // OpenSubsonic servers name themselves in every answer; plain Subsonic ones do not.
                r["type"].str?.lowercase()?.takeIf { it.isNotBlank() && it != t.extra[TYPE] }?.let(onType)
                return r
            }
            // Error 30, "server must upgrade": an older server (Airsonic-Advanced speaks 1.15.0) refuses a
            // newer client version. Its own version is in the answer; once per call, then remembered.
            val older = olderVersion(r, version)
            if (older != null) { version = older; onVersion(older); continue }
            if (isAmpacheWrongPassword(r)) throw ProviderException(ctx.text("error_ampache_subsonic_password"))
            throw ProviderException("$displayName: ${r["error"]["message"].str ?: "error ${r["error"]["code"].int}"}", code = r["error"]["code"].int ?: 0)
        }
    }

    internal fun toTrack(s: JsonElement?, index: Int? = null): Track? {
        val id = s["id"].str ?: return null
        return Track(
            id = id,
            title = s["title"].str ?: "",
            artists = listOfNotNull(s["artist"].str),
            album = s["album"].str ?: "",
            durationMs = (s["duration"].long ?: 0) * 1000,
            itemId = index?.toString(),
            year = s["year"].int ?: 0,
            // OpenSubsonic servers (Navidrome) list the file's ISRCs; older ones a single string or nothing.
            isrc = s["isrc"].arr.firstNotNullOfOrNull { it.str } ?: s["isrc"].str,
            // The recording id the file is tagged with (OpenSubsonic, and Subsonic since 1.14).
            mbids = listOfNotNull(MusicBrainz.mbid(s["musicBrainzId"].str)),
            addedAt = com.xlollx.songport.sync.Durations.parseInstant(s["created"].str),
        )
    }

    /** Navidrome's own web player searches songs; other Subsonic servers have no search page to link. */
    override fun webSearchUrl(ctx: Env, query: String): String? {
        val t = ctx.tokens.get(id) ?: return null
        val base = t.extra["url"] ?: return null
        return if (t.extra[TYPE] == NAVIDROME) navidromeSearchUrl(base, query) else null
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val me = creds(ctx).userName
        return call(ctx, "getPlaylists", emptyList())["playlists"]["playlist"].arr.mapNotNull { p ->
            // A key whose account the server did not name: every listed playlist counts as the user's.
            Playlist(p["id"].str ?: return@mapNotNull null, p["name"].str ?: "", p["songCount"].int ?: -1, ownedByMe = me.isBlank() || (p["owner"].str ?: me) == me, isPublic = p["public"].bool)
        }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        if (playlistId == MusicProvider.LIKED_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["song"].arr.mapNotNull { toTrack(it) }
        } else if (playlistId == MusicProvider.ALBUMS_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["album"].arr.mapNotNull { toAlbum(it) }
        } else if (playlistId == MusicProvider.ARTISTS_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["artist"].arr.mapNotNull { toArtist(it) }
        } else {
            call(ctx, "getPlaylist", listOf("id" to playlistId))["playlist"]["entry"].arr
                .mapIndexedNotNull { i, s -> toTrack(s, i) }
        }

    /** Each song of the playlist (or of the starred ones) with its `playCount`, as the server counts it for the user. */
    override suspend fun playlistPlays(ctx: Env, playlistId: String): List<Listening.Play> {
        val songs = when {
            playlistId == MusicProvider.LIKED_ID -> call(ctx, "getStarred2", emptyList())["starred2"]["song"].arr
            MusicProvider.isLibrary(playlistId) -> return emptyList()
            else -> call(ctx, "getPlaylist", listOf("id" to playlistId))["playlist"]["entry"].arr
        }
        return songs.mapNotNull { s -> toTrack(s)?.let { Listening.subsonicPlay(s, it) } }
    }

    /**
     * The API sorts albums by plays (getAlbumList2 type=frequent), not songs: the songs of the
     * albums played most, by their own `playCount`.
     */
    override suspend fun topPlays(ctx: Env, n: Int): List<Listening.Play> {
        val albums = call(ctx, "getAlbumList2", listOf("type" to "frequent", "size" to "$TOP_ALBUMS"))["albumList2"]["album"].arr.mapNotNull { it["id"].str }
        val out = ArrayList<Listening.Play>()
        for (a in albums) out += call(ctx, "getAlbum", listOf("id" to a))["album"]["song"].arr.mapNotNull { s -> toTrack(s)?.let { Listening.subsonicPlay(s, it) } }
        return out.filter { it.plays > 0 }.distinctBy { it.track.id }.sortedByDescending { it.plays }.take(n)
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            Track.KIND_ALBUM -> {
                val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
                val r = call(ctx, "search3", listOf("query" to q, "songCount" to "0", "albumCount" to "5", "artistCount" to "0"))["searchResult3"]["album"].arr.mapNotNull { toAlbum(it) }
                // Servers index album titles alone too: the artist in the query can hide a match.
                return r.ifEmpty { call(ctx, "search3", listOf("query" to Matcher.searchTitle(track.title), "songCount" to "0", "albumCount" to "5", "artistCount" to "0"))["searchResult3"]["album"].arr.mapNotNull { toAlbum(it) } }
            }
            Track.KIND_ARTIST -> return call(ctx, "search3", listOf("query" to track.title, "songCount" to "0", "albumCount" to "0", "artistCount" to "5"))["searchResult3"]["artist"].arr.mapNotNull { toArtist(it) }
        }
        val title = Matcher.searchTitle(track.title)
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + title).joinToString(" ")
        suspend fun songs(query: String, count: Int) = call(ctx, "search3", listOf("query" to query, "songCount" to "$count", "albumCount" to "0", "artistCount" to "0"))["searchResult3"]["song"].arr
            .mapNotNull { toTrack(it) }
        // Navidrome answers a query that is a MusicBrainz id with the file tagged with it: the exact
        // recording, whatever its title. Another server's answer is kept only if it carries that id.
        for (mbid in track.mbids.take(MBID_QUERIES)) songs(mbid, 5).filter { mbid in it.mbids }.takeIf { it.isNotEmpty() }?.let { return it }
        // Gonic and Ampache match the query against the title alone ("metallica battery" finds nothing):
        // then the title, as written and bare, while nothing close is found, with the artist's songs first.
        val first = songs(q, 5)
        val found = Matcher.searchByTitles(track, Matcher.titleTerms(track.title).filterNot { it.equals(q, ignoreCase = true) }, first) { songs(it, 20) }
        return if (found.size == first.size) first else Matcher.byArtist(track, found).sortedByDescending { Matcher.score(track, it) }.take(5)
    }

    /** OpenSubsonic: an empty query lists every song, a page at a time. */
    override suspend fun librarySongs(ctx: Env, onProgress: (Int) -> Unit): List<Track> {
        val out = ArrayList<Track>()
        var offset = 0
        while (true) {
            val page = call(ctx, "search3", listOf("query" to "", "songCount" to "$LIBRARY_PAGE", "songOffset" to "$offset", "albumCount" to "0", "artistCount" to "0"))["searchResult3"]["song"].arr
            out += page.mapNotNull { toTrack(it) }
            offset += page.size
            onProgress(out.size)
            if (page.size < LIBRARY_PAGE) return out
        }
    }

    /**
     * Navidrome and the others read lyrics from the file's tags and from a `.lrc` or `.txt` file next
     * to it (Navidrome: LyricsPriority, ".lrc,.txt,embedded" among its defaults); the API has no call
     * that adds them.
     */
    override val lyricsSupport: LyricsSupport get() = LyricsSupport.SIDECAR

    /**
     * Every song (search3, as [librarySongs]) with its `path`, and whether the server has lyrics for it:
     * `getLyricsBySongId` where the server lists the OpenSubsonic `songLyrics` extension, else the
     * older `getLyrics` by artist and title. One call per song. Navidrome reports the real path only
     * with Subsonic.DefaultReportRealPath (or the player's "Report real path") on.
     */
    override suspend fun lyricsSongs(ctx: Env, onProgress: (Int) -> Unit): List<LyricsSong> {
        val songs = ArrayList<JsonElement>()
        var offset = 0
        while (true) {
            val page = call(ctx, "search3", listOf("query" to "", "songCount" to "$LIBRARY_PAGE", "songOffset" to "$offset", "albumCount" to "0", "artistCount" to "0"))["searchResult3"]["song"].arr
            songs += page
            offset += page.size
            if (page.size < LIBRARY_PAGE) break
        }
        val byId = LYRICS_EXTENSION in extensions(creds(ctx).extra["url"].orEmpty())
        val out = ArrayList<LyricsSong>()
        for (s in songs) {
            val t = toTrack(s) ?: continue
            val has = if (byId) {
                call(ctx, "getLyricsBySongId", listOf("id" to t.id))["lyricsList"]["structuredLyrics"].arr
                    .any { l -> l["line"].arr.any { !it["value"].str.isNullOrBlank() } }
            } else if (t.artists.isEmpty() || t.title.isBlank()) false
            else !call(ctx, "getLyrics", listOf("artist" to t.artists.first(), "title" to t.title))["lyrics"]["value"].str.isNullOrBlank()
            out += LyricsSong(t, has, s["path"].str)
            onProgress(out.size)
        }
        return out
    }

    private fun toAlbum(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        return Track(id = id, title = a["name"].str ?: a["album"].str ?: "", artists = listOfNotNull(a["artist"].str), kind = Track.KIND_ALBUM)
    }

    private fun toArtist(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        val name = a["name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val r = call(ctx, "createPlaylist", listOf("name" to name))
        val id = r["playlist"]["id"].str
            ?: call(ctx, "getPlaylists", emptyList())["playlists"]["playlist"].arr.lastOrNull { it["name"].str == name }?.get("id").str
            ?: throw ProviderException("$displayName: playlist not created")
        return Playlist(id, name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "id" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "albumId" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "artistId" to it.id }) }
            return
        }
        tracks.chunked(100).forEach { chunk ->
            call(ctx, "updatePlaylist", listOf("playlistId" to playlistId) + chunk.map { "songIdToAdd" to it.id })
        }
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId, "name" to name))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId, "public" to public.toString()))
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        call(ctx, "deletePlaylist", listOf("id" to playlistId))
    }

    override val removesSingleEntry: Boolean get() = true
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "id" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "albumId" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "artistId" to it.id }) }
            return
        }
        // Rimozione per posizione: gli indici sono quelli letti da getPlaylist, tutti in una chiamata.
        val indexes = tracks.mapNotNull { it.itemId }
        if (indexes.isEmpty()) return
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId) + indexes.map { "songIndexToRemove" to removalIndex(it) })
    }

    companion object {
        const val SERVICE = "subsonic"
        const val API_VERSION = "1.16.1"
        /** Where the server's kind is kept in the account's extras, as its answers give it ("navidrome"). */
        internal const val TYPE = "type"
        /** How the connection signs its calls: absent for the salted token, [AUTH_APIKEY] for a key. */
        internal const val AUTH = "auth"
        internal const val AUTH_APIKEY = "apikey"
        internal const val APIKEY_EXTENSION = "apiKeyAuthentication"
        /** OpenSubsonic's structured lyrics (getLyricsBySongId). */
        internal const val LYRICS_EXTENSION = "songLyrics"
        /** OpenSubsonic error 44: the API key is not valid. */
        private const val ERROR_INVALID_KEY = 44
        internal const val NAVIDROME = "navidrome"
        /** An ISRC is rarely more than a couple of recordings: that many searches by id, then the title. */
        private const val MBID_QUERIES = 2
        private const val LIBRARY_PAGE = 500
        /** The albums played most whose songs make the top songs: each one is one call. */
        internal const val TOP_ALBUMS = 20

        /**
         * Navidrome's web UI (react-admin, hash routing, served under /app) on its songs list filtered by
         * [query]: the "title" filter is the list's search box, a full-text search over title, artist and album.
         */
        internal fun navidromeSearchUrl(base: String, query: String): String =
            "$base/app/#/song?filter=" + Http.enc(JsonObject(mapOf("title" to JsonPrimitive(query))).toString())

        /**
         * Ampache drops every query parameter whose value is "0" (PHP truthiness), so the first entry
         * could not be removed there; "00" reads as 0 everywhere (Go, Java, Python, PHP).
         */
        internal fun removalIndex(index: String): String = if (index == "0") "00" else index

        /**
         * Error 40 from a server that says it is Ampache: Ampache checks the md5+salt token against the
         * user's separate "Subsonic password" (or API key), never the account password, so the account
         * password is refused even when it is right.
         */
        internal fun isAmpacheWrongPassword(r: JsonElement?): Boolean =
            r["error"]["code"].int == 40 && r["type"].str.equals("ampache", ignoreCase = true)

        /** The version to retry with when [r] is the "server must upgrade" refusal of a call made with [sent]. */
        internal fun olderVersion(r: JsonElement?, sent: String): String? =
            r["version"].str?.takeIf { r["error"]["code"].int == 30 && it.isNotBlank() && it != sent }
    }
}

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Plex Media Server. Si accede con l'indirizzo del server e il proprio X-Plex-Token (Plex
 * documenta come trovarlo). A new playlist is made empty, pointed at the server's library
 * (checked on Plex 1.43): the engine then adds the tracks as on any other target.
 */
class PlexProvider(override val slot: String = "") : CredentialsProvider(), PlayCounts {
    override val serviceId = SERVICE
    override val displayName = "Plex"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFFE5A00D
    override val noteName = "provider_note_plex"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val matchesByMbid = true
    override val searchesTitleOnly: Boolean get() = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = false, needsSecret = true, secretLabelName = "login_token", hintName = "login_hint_plex")

    /**
     * Liked songs (2.2): Plex has none, so a song rated five stars is one, as Plexist does it. Trackhop
     * keeps a register of the songs it rated ([LOVED]): taking a like away clears only those, never
     * a five-star rating the user gave by hand.
     */
    override val supportsLikedSongs: Boolean get() = true
    override val supportsLikedTarget: Boolean get() = true

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val t = Tokens(accessToken = secret.trim(), extra = mapOf("url" to base))
        val id = api(ctx, "GET", "/identity", t)["MediaContainer"]["machineIdentifier"].str
            ?: throw ProviderException("$displayName: server not recognised")
        save(ctx, t.copy(extra = mapOf("url" to base, "machine" to id), userName = base.removePrefix("https://").removePrefix("http://")))
    }

    private suspend fun api(ctx: Env, method: String, path: String, t: Tokens = creds(ctx)): JsonElement {
        val resp = Http.send(method, "${t.extra["url"]}$path", mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/json"))
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        return parseJson(resp.body)
    }

    private fun toTrack(m: JsonElement?): Track? {
        val id = m["ratingKey"].str ?: return null
        return Track(
            id = id,
            title = m["title"].str ?: "",
            artists = listOfNotNull(m["originalTitle"].str ?: m["grandparentTitle"].str),
            album = m["parentTitle"].str ?: "",
            durationMs = m["duration"].long ?: 0,
            itemId = m["playlistItemID"].str ?: m["playlistItemID"].long?.toString(),
            // The ids Plex matched the track to, when asked for (includeGuids): "mbid://<id>" is MusicBrainz's.
            mbids = m["Guid"].arr.mapNotNull { g -> g["id"].str?.takeIf { it.startsWith("mbid://") }?.let { MusicBrainz.mbid(it.removePrefix("mbid://")) } },
        )
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        api(ctx, "GET", "/playlists?playlistType=audio")["MediaContainer"]["Metadata"].arr.mapNotNull { p ->
            Playlist(p["ratingKey"].str ?: return@mapNotNull null, p["title"].str ?: "", p["leafCount"].int ?: -1)
        }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.LIKED_ID) return fiveStars(ctx)
        return api(ctx, "GET", "/playlists/$playlistId/items?includeGuids=1")["MediaContainer"]["Metadata"].arr.mapNotNull { toTrack(it) }
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        if (MusicProvider.isLibrary(playlistId)) return super.playlistInfo(ctx, playlistId)
        val m = api(ctx, "GET", "/playlists/${Http.enc(playlistId)}")["MediaContainer"]["Metadata"][0]
        return Playlist(playlistId, m["title"].str ?: playlistId, m["leafCount"].int ?: -1, description = m["summary"].str.orEmpty())
    }

    private suspend fun musicSections(ctx: Env, t: Tokens = creds(ctx)): List<String> =
        api(ctx, "GET", "/library/sections", t)["MediaContainer"]["Directory"].arr.filter { it["type"].str == "artist" }.mapNotNull { it["key"].str }

    /** Every song rated five stars (userRating 10) in the music libraries, a page at a time. */
    private suspend fun fiveStars(ctx: Env): List<Track> {
        val out = ArrayList<Track>()
        for (section in musicSections(ctx)) {
            var offset = 0
            while (true) {
                val page = api(ctx, "GET", "/library/sections/$section/all?type=10&userRating=$FIVE_STARS&includeGuids=1&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$LIBRARY_PAGE")["MediaContainer"]["Metadata"].arr
                out += page.mapNotNull { m -> toTrack(m)?.takeIf { (m["userRating"].str?.toDoubleOrNull() ?: FIVE_STARS.toDouble()) >= FIVE_STARS } }
                offset += page.size
                if (page.size < LIBRARY_PAGE) break
            }
        }
        return out
    }

    private fun loved(ctx: Env): MutableSet<String> = ctx.keyValues(LOVED)[id].orEmpty().split('\n').filterTo(LinkedHashSet()) { it.isNotBlank() }
    private fun saveLoved(ctx: Env, keys: Set<String>) { ctx.keyValues(LOVED)[id] = keys.joinToString("\n") }

    /** A song's rating, 0 to 10 (10 = five stars), -1 to clear it: PUT /:/rate, as Plex's own apps do. */
    private suspend fun rate(ctx: Env, ratingKey: String, rating: Int) {
        api(ctx, "PUT", "/:/rate?key=${Http.enc(ratingKey)}&identifier=com.plexapp.plugins.library&rating=$rating")
    }

    /**
     * A playlist's last change, size and length, from its own metadata (one light call): a scheduled
     * sync skips one that did not change. None for a smart playlist, whose songs change with the library.
     */
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? {
        if (MusicProvider.isLibrary(playlistId)) return null
        val p = api(ctx, "GET", "/playlists/$playlistId")["MediaContainer"]["Metadata"][0] ?: return null
        val smart = p["smart"].let { it.bool ?: (it.str == "1") || (it.int == 1) }
        if (smart) return null
        val updated = (p["updatedAt"].long ?: return null).toString()
        return "$updated|${p["leafCount"].long ?: -1}|${p["duration"].long ?: -1}"
    }

    /** Each song of the playlist with its `viewCount`: the plays of the account whose token signed in. */
    override suspend fun playlistPlays(ctx: Env, playlistId: String): List<Listening.Play> {
        if (MusicProvider.isLibrary(playlistId)) return emptyList()
        return api(ctx, "GET", "/playlists/$playlistId/items")["MediaContainer"]["Metadata"].arr.mapNotNull { m -> toTrack(m)?.let { Listening.plexPlay(m, it) } }
    }

    /** The songs played most of each music library, sorted by the server itself (viewCount), then together. */
    override suspend fun topPlays(ctx: Env, n: Int): List<Listening.Play> {
        val sections = api(ctx, "GET", "/library/sections")["MediaContainer"]["Directory"].arr.filter { it["type"].str == "artist" }.mapNotNull { it["key"].str }
        return sections.flatMap { s ->
            api(ctx, "GET", "/library/sections/$s/all?type=10&sort=viewCount:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=$n")["MediaContainer"]["Metadata"].arr
                .mapNotNull { m -> toTrack(m)?.let { Listening.plexPlay(m, it) } }
        }.filter { it.plays > 0 }.sortedByDescending { it.plays }.take(n)
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Plex matches the query against one name at a time ("metallica battery" finds nothing): the
        // title alone (folded, as written, bare while nothing close is found), the artist's songs and the closest first.
        suspend fun find(q: String) = api(ctx, "GET", "/hubs/search?query=${Http.enc(q)}&limit=20&includeGuids=1")["MediaContainer"]["Hub"].arr
            .filter { it["type"].str == "track" }.flatMap { it["Metadata"].arr }.mapNotNull { toTrack(it) }
        var found = Matcher.byArtist(track, Matcher.searchByTitles(track) { find(it) }).sortedByDescending { Matcher.score(track, it, Matcher.SERVER_POLICY) }.take(20)
        if (track.mbids.isEmpty() || found.isEmpty()) return found.take(5)
        // Looking for a MusicBrainz recording: where the search left the ids out, the candidates are
        // read once more with them. The one tagged with the recording goes first.
        if (found.none { it.mbids.isNotEmpty() }) {
            val ids = try {
                api(ctx, "GET", "/library/metadata/${found.joinToString(",") { it.id }}?includeGuids=1")["MediaContainer"]["Metadata"].arr
                    .mapNotNull { toTrack(it) }.associate { it.id to it.mbids }
            } catch (e: ProviderException) { emptyMap() }
            found = found.map { c -> ids[c.id]?.takeIf { it.isNotEmpty() }?.let { c.copy(mbids = it) } ?: c }
        }
        return found.sortedByDescending { c -> c.mbids.any { it in track.mbids } }.take(5)
    }

    /** Every track of the server's music libraries, a page at a time. */
    override suspend fun librarySongs(ctx: Env, onProgress: (Int) -> Unit): List<Track> {
        val out = ArrayList<Track>()
        val sections = api(ctx, "GET", "/library/sections")["MediaContainer"]["Directory"].arr.filter { it["type"].str == "artist" }.mapNotNull { it["key"].str }
        for (section in sections) {
            var offset = 0
            while (true) {
                val page = api(ctx, "GET", "/library/sections/$section/all?type=10&includeGuids=1&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$LIBRARY_PAGE")["MediaContainer"]["Metadata"].arr
                out += page.mapNotNull { toTrack(it) }
                offset += page.size
                onProgress(out.size)
                if (page.size < LIBRARY_PAGE) break
            }
        }
        return out
    }

    /**
     * Plex reads lyrics from a `.lrc` (timed) or `.txt` (plain) file named like the track, in its folder
     * (support.plex.tv, Adding local lyrics; the Local Media Assets agent). Its API has no call that adds
     * them. A track's lyrics are its streams of type 4.
     */
    override val lyricsSupport: LyricsSupport get() = LyricsSupport.SIDECAR

    /**
     * Every track of the music libraries with its file (`Media.Part.file`), then their details read
     * [LYRICS_BATCH] at a time (`/library/metadata/{ids}`), whose streams say whether there are lyrics.
     */
    override suspend fun lyricsSongs(ctx: Env, onProgress: (Int) -> Unit): List<LyricsSong> {
        val listed = ArrayList<Pair<Track, String?>>()
        val sections = api(ctx, "GET", "/library/sections")["MediaContainer"]["Directory"].arr.filter { it["type"].str == "artist" }.mapNotNull { it["key"].str }
        for (section in sections) {
            var offset = 0
            while (true) {
                val page = api(ctx, "GET", "/library/sections/$section/all?type=10&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$LIBRARY_PAGE")["MediaContainer"]["Metadata"].arr
                page.forEach { m -> toTrack(m)?.let { listed += it to fileOf(m) } }
                offset += page.size
                if (page.size < LIBRARY_PAGE) break
            }
        }
        val out = ArrayList<LyricsSong>()
        for (chunk in listed.chunked(LYRICS_BATCH)) {
            val details = api(ctx, "GET", "/library/metadata/${chunk.joinToString(",") { it.first.id }}")["MediaContainer"]["Metadata"].arr
                .associateBy { it["ratingKey"].str }
            for ((t, file) in chunk) {
                val m = details[t.id]
                val has = m["Media"].arr.any { media -> media["Part"].arr.any { part -> part["Stream"].arr.any { it["streamType"].int == LYRICS_STREAM } } }
                out += LyricsSong(t, has, file ?: fileOf(m))
            }
            onProgress(out.size)
        }
        return out
    }

    private fun fileOf(m: JsonElement?): String? = m["Media"].arr.firstNotNullOfOrNull { media -> media["Part"].arr.firstNotNullOfOrNull { it["file"].str } }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = createWith(ctx, creds(ctx), name, description)

    private suspend fun createWith(ctx: Env, t: Tokens, name: String, description: String): Playlist {
        val machine = t.extra["machine"] ?: throw ProviderException("$displayName: reconnect")
        // An empty audio playlist: the uri names the library only, no item (Plex wants a uri even then).
        val uri = "server://$machine/com.plexapp.plugins.library"
        val made = api(ctx, "POST", "/playlists?type=audio&title=${Http.enc(name)}&smart=0&uri=${Http.enc(uri)}", t)
        val id = made["MediaContainer"]["Metadata"][0]["ratingKey"].str
            ?: throw ProviderException("$displayName: playlist not created")
        // The description is Plex's "summary": a nicety, never a reason to fail the new playlist.
        if (description.isNotBlank()) runCatching { api(ctx, "PUT", "/playlists/$id?summary=${Http.enc(description)}", t) }
        return Playlist(id, name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        api(ctx, "PUT", "/playlists/$playlistId?title=${Http.enc(name)}")
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        api(ctx, "DELETE", "/playlists/$playlistId")
    }

    override val canSetCover: Boolean get() = true
    override val coverIsV2: Boolean get() = true

    /**
     * The playlist's poster: the JPEG as the body of `POST /library/metadata/{ratingKey}/posters`, the
     * upload Plex's web app makes (a playlist is a metadata item like any other); Plex then selects it.
     */
    override suspend fun setPlaylistCover(ctx: Env, playlistId: String, jpeg: ByteArray) {
        val t = creds(ctx)
        val resp = Http.send(
            "POST", "${t.extra["url"]}/library/metadata/$playlistId/posters",
            mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/json"),
            jpeg.toRequestBody("image/jpeg".toMediaType()),
        )
        if (!resp.ok) throw ProviderException("$displayName: cover not set (HTTP ${resp.code})")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            val register = loved(ctx)
            for (t in tracks) {
                rate(ctx, t.id, FIVE_STARS)
                register += t.id
                saveLoved(ctx, register)
            }
            return
        }
        addItems(ctx, creds(ctx), playlistId, tracks)
    }

    private suspend fun addItems(ctx: Env, t: Tokens, playlistId: String, tracks: List<Track>) {
        val machine = t.extra["machine"] ?: throw ProviderException("$displayName: reconnect")
        tracks.chunked(50).forEach { chunk ->
            val uri = "server://$machine/com.plexapp.plugins.library/library/metadata/${chunk.joinToString(",") { it.id }}"
            api(ctx, "PUT", "/playlists/$playlistId/items?uri=${Http.enc(uri)}", t)
        }
    }

    override val losesIds: Boolean get() = true

    /** The music libraries' `scannedAt` (`GET /library/sections`, epoch seconds): the latest one. */
    override suspend fun lastScan(ctx: Env): Long? =
        api(ctx, "GET", "/library/sections")["MediaContainer"]["Directory"].arr.filter { it["type"].str == "artist" }
            .mapNotNull { it["scannedAt"].long }.maxOrNull()?.takeIf { it > 0 }?.let { it * 1000 }

    /** The items Plex still has among [ids], asked in one call (404 when it has none of them): the others are gone. */
    override suspend fun lostIds(ctx: Env, ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val t = creds(ctx)
        val resp = Http.send("GET", "${t.extra["url"]}/library/metadata/${ids.joinToString(",") { Http.enc(it) }}", mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/json"))
        if (resp.code == 404) return ids.toCollection(LinkedHashSet())
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        val known = parseJson(resp.body)["MediaContainer"]["Metadata"].arr.mapNotNullTo(HashSet()) { it["ratingKey"].str }
        return ids.filterTo(LinkedHashSet()) { it !in known }
    }

    override val removesSingleEntry: Boolean get() = true
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) { removeTracksReportingKept(ctx, playlistId, tracks); return }
        tracks.mapNotNull { it.itemId }.forEach { api(ctx, "DELETE", "/playlists/$playlistId/items/$it") }
    }

    /** A like taken away clears the five stars Trackhop gave; one the user gave stays, said as kept. */
    override suspend fun removeTracksReportingKept(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        if (playlistId != MusicProvider.LIKED_ID) return super.removeTracksReportingKept(ctx, playlistId, tracks)
        val register = loved(ctx)
        val kept = HashSet<String>()
        for (t in tracks) {
            if (t.id !in register) { kept += t.id; continue }
            rate(ctx, t.id, -1)
            register -= t.id
            saveLoved(ctx, register)
        }
        if (kept.isNotEmpty()) ctx.log(id, "${kept.size} five-star rating(s) given outside Trackhop left as they are")
        return kept
    }

    // ---- 2.2: sonic analysis (Plex Pass) and Plex Home

    /** Songs of the library matching [query] by title, for picking a song to start from. */
    suspend fun findSongs(ctx: Env, query: String): List<Track> =
        api(ctx, "GET", "/hubs/search?query=${Http.enc(query)}&limit=20")["MediaContainer"]["Hub"].arr
            .filter { it["type"].str == "track" }.flatMap { it["Metadata"].arr }.mapNotNull { toTrack(it) }

    /**
     * Songs that sound like [trackId], the closest first (`/library/metadata/{id}/nearest`, Plex's sonic
     * analysis: a Plex Pass server that analysed its music). Empty where the server has no analysis.
     */
    suspend fun soundsLike(ctx: Env, trackId: String, limit: Int = 25, maxDistance: Double = 0.25): List<Track> =
        api(ctx, "GET", "/library/metadata/${Http.enc(trackId)}/nearest?limit=${limit.coerceIn(1, 100)}&maxDistance=$maxDistance")["MediaContainer"]["Metadata"].arr
            .mapNotNull { toTrack(it) }.filter { it.id != trackId }

    /** A journey from [fromId] to [toId] through songs that sound alike (Plex's Sonic adventure, `computePath`), both ends included. */
    suspend fun sonicAdventure(ctx: Env, fromId: String, toId: String): List<Track> {
        val section = api(ctx, "GET", "/library/metadata/${Http.enc(fromId)}")["MediaContainer"]["Metadata"][0]["librarySectionID"].let { it.str ?: it.long?.toString() }
            ?: throw ProviderException("$displayName: song not found")
        return api(ctx, "GET", "/library/sections/$section/computePath?startID=${Http.enc(fromId)}&endID=${Http.enc(toId)}")["MediaContainer"]["Metadata"].arr.mapNotNull { toTrack(it) }
    }

    /** A user of the server's Plex Home (or a friend it is shared with): their id, their name, and the token that acts as them on this server. */
    data class HomeUser(val id: String, val name: String, internal val token: String)

    /**
     * The users this server is shared with, managed Home users among them, from plex.tv
     * (`/api/servers/{machine}/shared_servers`, the server owner's token): each with the token that
     * reaches this server as them, so no PIN is asked.
     */
    suspend fun homeUsers(ctx: Env): List<HomeUser> {
        val t = creds(ctx)
        val machine = t.extra["machine"] ?: throw ProviderException("$displayName: reconnect")
        val resp = Http.send("GET", "$PLEX_TV/api/servers/$machine/shared_servers", mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/xml"))
        if (resp.code == 401 || resp.code == 403) throw ProviderException(ctx.text("plex_home_owner_only"))
        if (!resp.ok) throw ProviderException("plex.tv ${resp.code}: ${resp.body.take(160)}")
        return sharedServers(resp.body)
    }

    /** Copies playlist [playlistId] into [userId]'s library on this server, under the same name; answers the new playlist. */
    suspend fun copyToUser(ctx: Env, playlistId: String, userId: String): Playlist {
        val user = homeUsers(ctx).firstOrNull { it.id == userId } ?: throw ProviderException(ctx.text("plex_home_user_unknown"))
        val info = playlistInfo(ctx, playlistId)
        val tracks = tracks(ctx, playlistId)
        val asUser = creds(ctx).let { it.copy(accessToken = user.token) }
        val made = createWith(ctx, asUser, info.name, info.description)
        if (tracks.isNotEmpty()) addItems(ctx, asUser, made.id, tracks.distinctBy { it.id })
        ctx.log(id, "playlist ${info.name} copied to ${user.name} (${tracks.size} songs)")
        return made.copy(trackCount = tracks.size)
    }

    companion object {
        const val SERVICE = "plex"
        private const val PLEX_TV = "https://plex.tv"
        /** Plex rates 0 to 10: 10 is five stars. */
        private const val FIVE_STARS = 10
        /** The songs each Plex account had rated five stars by Trackhop (ratingKeys, one a line). */
        private const val LOVED = "plex_loved"

        /** The users of plex.tv's shared_servers answer (XML): `<SharedServer userID name|username accessToken …/>`. */
        internal fun sharedServers(xml: String): List<HomeUser> =
            Regex("""<SharedServer\b([^>]*)>""").findAll(xml).mapNotNull { m ->
                val attrs = Regex("""(\w+)="([^"]*)"""").findAll(m.groupValues[1]).associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }
                val token = attrs["accessToken"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = attrs["userID"] ?: attrs["id"] ?: return@mapNotNull null
                val name = listOf("name", "username", "email").firstNotNullOfOrNull { attrs[it]?.takeIf { v -> v.isNotBlank() } } ?: id
                HomeUser(id, name, token)
            }.distinctBy { it.id }.toList()

        private fun xmlUnescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        private const val LIBRARY_PAGE = 500
        /** Tracks whose details one request reads for the lyrics report. */
        private const val LYRICS_BATCH = 50
        /** Plex's stream type for lyrics (1 video, 2 audio, 3 subtitles, 4 lyrics). */
        private const val LYRICS_STREAM = 4
    }
}

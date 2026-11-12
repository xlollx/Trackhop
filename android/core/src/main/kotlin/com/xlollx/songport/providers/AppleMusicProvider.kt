package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/**
 * Apple Music API (https://developer.apple.com/documentation/applemusicapi).
 *
 * Due credenziali:
 *  - **developer token**: JWT ES256 firmato con la chiave MusicKit dell'account Apple Developer.
 *    Arriva dalla build (APPLE_DEVELOPER_TOKEN) o lo incolla l'utente nelle impostazioni avanzate.
 *    Dura al massimo 6 mesi, poi va rigenerato.
 *  - **music user token**: ottenuto con MusicKit JS dentro una WebView (AppleAuthActivity).
 *
 * Limite dell'API (non dell'app): si possono **aggiungere** brani a una playlist ma non toglierli,
 * quindi `canRemoveTracks` e' false e le sync a specchio verso Apple Music aggiungono soltanto.
 * Le playlist del catalogo (id `pl.…`, quelle dei link pubblici) si leggono col solo developer token.
 */
open class AppleMusicProvider(override val slot: String = "") : MusicProvider {
    override val serviceId = SERVICE
    override val authDomain = "appleid.apple.com"
    override val revokeUrl = "https://appleid.apple.com/account/manage"
    override val displayName = "Apple Music"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_apple_api"
    override val familyName: String get() = "Apple Music"
    override val brandColor = 0xFFFA243C
    override val noteName = "provider_note_apple"
    override val beta = true
    override val searchesByIsrc: Boolean get() = true
    override val canRemoveTracks: Boolean get() = false
    override val canRenamePlaylists: Boolean get() = false
    override val canDeletePlaylists: Boolean get() = false
    /** Library albums can be listed and added; the API has no "followed artists". */
    override val supportsAlbums = true
    /**
     * Favorites as a Liked songs target: POST /v1/me/favorites puts a song in the user's "Favorite
     * Songs" playlist. The API lists no favorites and removes none, so it is a target only (the
     * official route; the web route keeps what it had).
     */
    override val supportsLikedTarget: Boolean get() = serviceId == SERVICE
    /** Recently played (/v1/me/recent/played/tracks) as a source, and for the poster and the Wrapped. */
    override val supportsRecent: Boolean get() = serviceId == SERVICE
    /** Favorites and recent plays came with Trackhop 2.0: offered then ([com.xlollx.songport.Features]). */
    override fun libraryIsV2(playlistId: String, asTarget: Boolean): Boolean =
        serviceId == SERVICE && ((playlistId == MusicProvider.LIKED_ID && asTarget) || playlistId == MusicProvider.RECENT_ID)


    override val setupGuide: SetupGuide? = SetupGuide(
        dashboardUrl = "https://developer.apple.com/account/resources/authkeys/list",
        stepUrls = listOf("https://developer.apple.com/programs/enroll/", "https://developer.apple.com/account/resources/identifiers/list/musicId", "https://developer.apple.com/account/resources/authkeys/add", "https://github.com/xlollx/Trackhop#api-configuration", null),
        whyName = "setup_why_apple",
        stepsArrayName = "setup_steps_apple",
        fieldLabelName = "setup_field_apple_token",
        multiline = true,
    )

    override fun usesOwnCredentials(ctx: Env): Boolean =
        !ctx.store.data.settings.clientIds[serviceId].isNullOrBlank()

    open fun developerToken(ctx: Env): String =
        ctx.store.data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildDefaults["APPLE_DEVELOPER_TOKEN"]

    /** Il music user token della libreria dell'utente (null = non collegato). */
    protected open fun userToken(ctx: Env): String? = ctx.tokens.get(id)?.accessToken

    override fun isConfigured(ctx: Env) = developerToken(ctx).isNotBlank()
    override fun isConnected(ctx: Env) = ctx.tokens.get(id) != null
    override fun accountName(ctx: Env): String? =
        ctx.tokens.get(id)?.let { t -> t.extra["storefront"]?.uppercase()?.let { "Apple Music ($it)" } ?: displayName }

    override fun canRead(ctx: Env, playlistId: String): Boolean =
        if (playlistId.startsWith(CATALOG_PREFIX)) isConfigured(ctx) else isConnected(ctx)

    override fun startAuth(ctx: Env) {
        ctx.startAuth(this, AuthMode.MUSICKIT)
    }

    /** Chiamata da AppleAuthActivity con il music user token ottenuto da MusicKit JS. */
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {
        val mut = params["music_user_token"] ?: throw ProviderException("Apple Music: no user token")
        val store = ctx.tokens
        store.set(id, Tokens(accessToken = mut, userName = displayName))
        val sf = try {
            api(ctx, "GET", "$API/v1/me/storefront")["data"][0]["id"].str ?: defaultStorefront()
        } catch (e: Exception) {
            defaultStorefront()
        }
        store.set(id, Tokens(accessToken = mut, userName = displayName, extra = mapOf("storefront" to sf)))
    }

    override fun disconnect(ctx: Env) = ctx.tokens.clear(id)

    private fun defaultStorefront(): String =
        Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"

    protected open fun storefront(ctx: Env): String =
        ctx.tokens.get(id)?.extra?.get("storefront") ?: defaultStorefront()

    /**
     * Richiesta all'API. Il developer token basta per il catalogo; il music user token serve
     * solo per la libreria (endpoint /v1/me/...).
     */
    /** The web route sends every call to the player's own backend, with the player's headers. */
    protected open fun apiUrl(url: String): String = url
    /** [user]: a library call (/v1/me/…). The catalogue refuses a request that carries the user's token. */
    protected open fun apiHeaders(ctx: Env, user: Boolean): Map<String, String> = emptyMap()

    /**
     * [emptyOn40403]: Apple answers the tracks of a library playlist that holds no song with 404
     * "No related resources found for tracks" (code 40403); a playlist that is gone answers 40400 on
     * itself. The first is an empty list, not an error.
     */
    private suspend fun api(ctx: Env, method: String, url: String, body: JsonElement? = null, emptyOn40403: Boolean = false): JsonElement {
        // The web route fetches its tokens over the network: never on the caller's (main) thread.
        val dev = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { developerToken(ctx) }
        if (dev.isBlank()) throw ProviderException(ctx.text("apple_token_missing"))
        val headers = HashMap<String, String>()
        headers["Authorization"] = "Bearer $dev"
        headers["Accept"] = "application/json"
        val needsUser = "/v1/me/" in url
        if (needsUser) {
            val mut = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { userToken(ctx) }
                ?: throw ProviderException(ctx.text("error_not_connected", displayName))
            headers["Music-User-Token"] = mut
        }
        headers.putAll(apiHeaders(ctx, needsUser))
        val resp = Http.send(method, apiUrl(url), headers, body?.let { Http.jsonBody(it.toString()) })
        if (emptyOn40403 && resp.code == 404 && runCatching { parseJson(resp.body)["errors"][0]["code"].str }.getOrNull() == "40403")
            return parseJson("""{"data":[]}""")
        if (!resp.ok) {
            val detail = runCatching { parseJson(resp.body)["errors"][0]["detail"].str }.getOrNull() ?: resp.body.take(200).ifBlank { "no detail" }
            // 401 sul developer token = scaduto; 403 con user token = sessione da rifare.
            if (resp.code == 401 && needsUser) ctx.tokens.clear(id)
            // An Apple ID without an Apple Music subscription: 403 from the public API, or a 400 saying
            // "User's subscription tier does not have access to privilege: CloudLibrary" from the player's.
            if (needsUser && (resp.code == 403 || detail.contains("subscription tier", ignoreCase = true) || detail.contains("CloudLibrary")))
                throw ProviderException(ctx.text("apple_subscription_needed", displayName, detail))
            throw ProviderException("$displayName API ${resp.code}: $detail")
        }
        return parseJson(resp.body)
    }

    /** `next` e' un percorso relativo tipo "/v1/me/library/playlists?offset=100". */
    private fun nextUrl(j: JsonElement?): String? =
        j["next"].str?.let { if (it.startsWith("http")) it else API + it }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val out = ArrayList<Playlist>()
        var url: String? = "$API/v1/me/library/playlists?limit=100"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (p in j["data"].arr) {
                val a = p["attributes"]
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = a["name"].str ?: "",
                    // canEdit=false per le playlist sincronizzate da iTunes o create da Apple.
                    ownedByMe = a["canEdit"].bool ?: true,
                    isPublic = a["isPublic"].bool,
                )
            }
            url = nextUrl(j)
        }
        return out
    }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? {
        if (MusicProvider.isLibrary(playlistId) || playlistId.startsWith(CATALOG_PREFIX)) return null
        val a = api(ctx, "GET", "$API/v1/me/library/playlists/$playlistId")["data"][0]["attributes"]
        return a["lastModifiedDate"].str ?: a["dateModified"].str
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val url = if (playlistId.startsWith(CATALOG_PREFIX)) "$API/v1/catalog/${storefront(ctx)}/playlists/$playlistId"
        else "$API/v1/me/library/playlists/$playlistId"
        val d = api(ctx, "GET", url)["data"][0]
        return Playlist(playlistId, d["attributes"]["name"].str ?: playlistId, ownedByMe = !playlistId.startsWith(CATALOG_PREFIX), description = d["attributes"]["description"]["standard"].str.orEmpty())
    }

    /** Brano di libreria: l'id utile per aggiungerlo altrove e' quello di catalogo (playParams.catalogId). */
    private fun libraryTrack(d: JsonElement?): Track? {
        val libId = d["id"].str ?: return null
        val a = d["attributes"]
        val catalogId = a["playParams"]["catalogId"].str ?: a["playParams"]["id"].str
        return Track(
            id = catalogId ?: libId,
            title = a["name"].str ?: "",
            artists = splitArtists(a["artistName"].str),
            album = a["albumName"].str ?: "",
            durationMs = a["durationInMillis"].long ?: 0,
            itemId = libId,
            year = Durations.year(a["releaseDate"].str),
            addedAt = Durations.parseInstant(a["dateAdded"].str),
            // A library song Apple can still play carries playParams; one pulled from the catalogue has none.
            available = !a["playParams"].isNullish,
        )
    }

    /** Only the library says what it can play; a catalogue playlist is read as the catalogue lists it. */
    override val detectsUnplayable: Boolean get() = true

    private fun catalogAlbum(d: JsonElement?): Track? {
        val id = d["id"].str ?: return null
        val a = d["attributes"]
        return Track(id = id, title = a["name"].str ?: "", artists = splitArtists(a["artistName"].str), isrc = a["upc"].str, kind = Track.KIND_ALBUM)
    }

    private fun catalogTrack(d: JsonElement?): Track? {
        val cid = d["id"].str ?: return null
        val a = d["attributes"]
        return Track(
            id = cid,
            title = a["name"].str ?: "",
            artists = splitArtists(a["artistName"].str),
            album = a["albumName"].str ?: "",
            durationMs = a["durationInMillis"].long ?: 0,
            isrc = a["isrc"].str,
            explicit = a["contentRating"].str?.let { it == "explicit" },
            year = Durations.year(a["releaseDate"].str),
        )
    }

    /** Apple restituisce un unico campo "artistName" tipo "Daft Punk & Pharrell Williams". */
    private fun splitArtists(s: String?): List<String> =
        s?.split(Regex("""\s*(,|&|feat\.|ft\.|featuring)\s*""", RegexOption.IGNORE_CASE))
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.LIKED_ID) return favorited(ctx)
        if (playlistId == MusicProvider.RECENT_ID) return recent(ctx)
        if (playlistId == MusicProvider.ALBUMS_ID) {
            val out = ArrayList<Track>()
            var url: String? = "$API/v1/me/library/albums?limit=100"
            while (url != null) {
                val j = api(ctx, "GET", url)
                for (d in j["data"].arr) {
                    // The catalogue id, when the library album is linked to one, is what other services can match.
                    val id = d["attributes"]["playParams"]["catalogId"].str ?: d["id"].str ?: continue
                    val a = d["attributes"]
                    out += Track(id = id, title = a["name"].str ?: "", artists = splitArtists(a["artistName"].str), kind = Track.KIND_ALBUM)
                }
                url = nextUrl(j)
            }
            return out
        }
        val catalog = playlistId.startsWith(CATALOG_PREFIX)
        val out = ArrayList<Track>()
        var url: String? = if (catalog) "$API/v1/catalog/${storefront(ctx)}/playlists/$playlistId/tracks?limit=100"
        else "$API/v1/me/library/playlists/$playlistId/tracks?limit=100"
        while (url != null) {
            val j = api(ctx, "GET", url, emptyOn40403 = !catalog)
            for (d in j["data"].arr) {
                val t = if (catalog) catalogTrack(d) else libraryTrack(d)
                if (t != null && t.title.isNotBlank()) out += t
            }
            url = nextUrl(j)
        }
        return out
    }

    override suspend fun track(ctx: Env, trackId: String): Track? =
        catalogTrack(api(ctx, "GET", "$API/v1/catalog/${storefront(ctx)}/songs/${Http.enc(trackId)}")["data"].arr.firstOrNull())

    override fun webSearchUrl(ctx: Env, query: String): String = "https://music.apple.com/search?term=" + Http.enc(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val sf = storefront(ctx)
        if (track.kind == Track.KIND_ALBUM) {
            track.isrcNorm?.let { upc ->
                val j = api(ctx, "GET", "$API/v1/catalog/$sf/albums?filter[upc]=$upc")
                val r = j["data"].arr.mapNotNull { catalogAlbum(it) }
                if (r.isNotEmpty()) return r
            }
            val term = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
            val j = api(ctx, "GET", "$API/v1/catalog/$sf/search?types=albums&limit=5&term=${Http.enc(term)}")
            return j["results"]["albums"]["data"].arr.mapNotNull { catalogAlbum(it) }
        }
        if (track.kind == Track.KIND_ARTIST) return emptyList()
        track.isrcNorm?.let { isrc ->
            val j = api(ctx, "GET", "$API/v1/catalog/$sf/songs?filter[isrc]=$isrc&limit=5")
            val r = j["data"].arr.mapNotNull { catalogTrack(it) }
            if (r.isNotEmpty()) return r
        }
        val term = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title))
            .joinToString(" ")
        val j = api(ctx, "GET", "$API/v1/catalog/$sf/search?types=songs&limit=5&term=${Http.enc(term)}")
        return j["results"]["songs"]["data"].arr.mapNotNull { catalogTrack(it) }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "$API/v1/me/library/playlists",
            jsonObj("attributes" to mapOf("name" to name, "description" to description)))
        val pid = j["data"][0]["id"].str ?: throw ProviderException("Apple Music: playlist not created")
        return Playlist(pid, name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) return favorite(ctx, tracks)
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(25).forEach { chunk -> api(ctx, "POST", "$API/v1/me/library?ids[albums]=${chunk.joinToString(",") { it.id }}") }
            return
        }
        // Solo id di catalogo: un brano trovato nella libreria altrui non e' aggiungibile.
        tracks.chunked(25).forEach { chunk ->
            addChunk(ctx, playlistId, chunk)
        }
    }

    /**
     * A playlist created a moment ago can be invisible to the library for a few seconds: Apple
     * answers 404 "No related resources found for tracks" while it catches up. The write landed,
     * the next write does not see it yet. So the same request is repeated after a short wait,
     * a few times, before the failure is passed on.
     */
    private suspend fun addChunk(ctx: Env, playlistId: String, chunk: List<Track>) {
        var attempt = 0
        while (true) {
            try {
                api(ctx, "POST", "$API/v1/me/library/playlists/$playlistId/tracks",
                    jsonObj("data" to jsonArr(chunk.map { mapOf("id" to it.id, "type" to "songs") })))
                return
            } catch (e: ProviderException) {
                if (attempt < LIBRARY_LAG_RETRIES && isLibraryLag(e)) {
                    attempt++
                    val wait = 2_000L * attempt
                    ctx.log("apple", "Apple does not see the playlist yet (${e.message?.take(90)}); retrying in ${wait / 1000}s")
                    kotlinx.coroutines.delay(wait)
                    continue
                }
                throw e
            }
        }
    }

    /** Apple's own "the library has not caught up with the write" answer. */
    private fun isLibraryLag(e: ProviderException): Boolean {
        val m = e.message ?: return false
        return m.contains("404") && m.contains("No related resources", true)
    }

    // ---- favorites, recently played, Replay

    /**
     * Adds [tracks] (catalogue ids) to the favorites: POST /v1/me/favorites?ids[songs]=…, 25 a
     * request; Apple answers 202 and ignores an id it cannot add. The songs sent are kept on this
     * device ([favorited]), as Apple lists no favorites: the next sync knows them as there.
     */
    private suspend fun favorite(ctx: Env, tracks: List<Track>) {
        tracks.chunked(25).forEach { chunk ->
            api(ctx, "POST", "$API/v1/me/favorites?ids[songs]=${chunk.joinToString(",") { Http.enc(it.id) }}")
            val kv = ctx.keyValues(FAVORITES)
            val had = kv[id].orEmpty()
            val known = had.lineSequence().map { it.substringBefore('\t') }.toHashSet()
            val added = chunk.filter { known.add(it.id) }.joinToString("\n") { t ->
                listOf(t.id, t.title, t.artists.joinToString(", ")).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            }
            if (added.isNotEmpty()) kv[id] = if (had.isEmpty()) added else "$had\n$added"
        }
    }

    /** The songs this device put in the favorites, the latest first: Apple's API has no list of them. */
    private fun favorited(ctx: Env): List<Track> =
        ctx.keyValues(FAVORITES)[id].orEmpty().lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            Track(id = p[0], title = p.getOrElse(1) { "" }, artists = p.getOrNull(2)?.split(", ")?.filter { it.isNotBlank() }.orEmpty())
        }.toList().asReversed()

    /** Nothing to report for the favorites: only what this device sent is known. */
    override suspend fun unplayable(ctx: Env, playlistId: String): List<com.xlollx.songport.model.UnplayableEntry> =
        if (playlistId == MusicProvider.LIKED_ID) emptyList() else super.unplayable(ctx, playlistId)

    /**
     * The songs played lately, the latest first, each once: GET /v1/me/recent/played/tracks, thirty a
     * page (Apple's most), for catalogue and library songs, up to [RECENT_PAGES] pages.
     */
    private suspend fun recent(ctx: Env): List<Track> = recentPlays(ctx).map { it.track }

    private suspend fun recentPlays(ctx: Env): List<com.xlollx.songport.sync.Listening.Play> {
        val out = ArrayList<com.xlollx.songport.sync.Listening.Play>()
        var url: String? = "$API/v1/me/recent/played/tracks?types=songs,library-songs&limit=30"
        var pages = 0
        while (url != null && pages++ < RECENT_PAGES) {
            val j = api(ctx, "GET", url)
            for (d in j["data"].arr) {
                val t = (if (d["type"].str == "library-songs") libraryTrack(d) else catalogTrack(d)) ?: continue
                if (t.title.isNotBlank()) out += com.xlollx.songport.sync.Listening.Play(t, cover = com.xlollx.songport.sync.Listening.appleArtwork(d["attributes"]["artwork"]["url"].str))
            }
            url = nextUrl(j)
        }
        return out.distinctBy { it.track.id }
    }

    /**
     * What the user listened to, for the Wrapped and the poster. [Listening.Period.YEAR]: Replay's
     * top songs and artists of the latest year Apple has (GET /v1/me/music-summaries, ranked, no
     * counts); when Replay has nothing or refuses, the recent plays, said in the log. Else the
     * recent plays. Any refusal is logged once and thrown as [com.xlollx.songport.sync.Listening.Unavailable].
     */
    suspend fun listening(ctx: Env, period: com.xlollx.songport.sync.Listening.Period): com.xlollx.songport.sync.Listening.Report {
        if (period == com.xlollx.songport.sync.Listening.Period.YEAR) {
            val replay = try {
                replay(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ctx.log(LISTENING, "Apple Music Replay not read (${e.message?.take(120) ?: e.javaClass.simpleName}): the recent plays instead"); null
            }
            if (replay != null && replay.tracks.isNotEmpty()) return replay
            if (replay != null) ctx.log(LISTENING, "Apple Music Replay has no songs yet: the recent plays instead")
        }
        val plays = try {
            recentPlays(ctx)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val why = "Apple Music recent plays: ${e.message?.take(120) ?: e.javaClass.simpleName}"
            ctx.log(LISTENING, why)
            throw com.xlollx.songport.sync.Listening.Unavailable(why)
        }
        val r = com.xlollx.songport.sync.Listening.fromHistory(plays.map { it.track })
        val covers = plays.associate { it.track.id to it.cover }
        return r.copy(tracks = r.tracks.map { p -> p.copy(cover = covers[p.track.id]) })
    }

    /**
     * Replay: GET /v1/me/music-summaries?filter[year]=latest&views=top-songs,top-artists. A song the
     * view names without its attributes is read from the catalogue, by id.
     */
    private suspend fun replay(ctx: Env): com.xlollx.songport.sync.Listening.Report {
        val j = api(ctx, "GET", "$API/v1/me/music-summaries?filter[year]=latest&views=top-songs,top-artists")
        val r = com.xlollx.songport.sync.Listening.appleReplay(j)
        if (r.missing.isEmpty()) return r.report
        val found = HashMap<String, Pair<Track, String?>>()
        r.missing.chunked(100).forEach { chunk ->
            val c = api(ctx, "GET", "$API/v1/catalog/${storefront(ctx)}/songs?ids=${chunk.joinToString(",")}")
            for (d in c["data"].arr) catalogTrack(d)?.let { found[it.id] = it to com.xlollx.songport.sync.Listening.appleArtwork(d["attributes"]["artwork"]["url"].str) }
        }
        return r.report.copy(tracks = r.order.mapNotNull { sid -> r.report.tracks.firstOrNull { it.track.id == sid } ?: found[sid]?.let { (t, cover) -> com.xlollx.songport.sync.Listening.Play(t, cover = cover) } })
    }

    /** L'API Apple Music non espone la rimozione di brani da una playlist (vedi canRemoveTracks). */
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        throw ProviderException(ctx.text("error_no_removals", displayName))
    }

    companion object {
        const val SERVICE = "apple"
        private const val API = "https://api.music.apple.com"
        /** Prefisso degli id delle playlist del catalogo (pubbliche/editoriali). */
        private const val CATALOG_PREFIX = "pl."
        /** How many times a write waits for a playlist the library has not seen yet. */
        private const val LIBRARY_LAG_RETRIES = 4
        /** The songs each Apple Music account had put in its favorites from this device, one a line (id, title, artists). */
        private const val FAVORITES = "apple_favorites"
        /** Recently played: thirty songs a page. */
        private const val RECENT_PAGES = 5
        private const val LISTENING = "poster"
    }
}

package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Unplayable
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.Reorder
import kotlinx.serialization.json.JsonElement

/**
 * TIDAL Open API v2 (https://developer.tidal.com), formato JSON:API.
 * L'API TIDAL v2 cambia ancora: gli endpoint sono isolati qui per aggiornarli in fretta (fuori dalla beta dalla 1.6.2).
 */
class TidalProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    // Not inside the app: login.tidal.com's bot check blocks an in-app page ("You have been blocked.
    // Something about the behaviour of the browser…", issue #22). The phone's browser passes it.
    override val loginInApp: Boolean get() = false
    override val revokeUrl = "https://account.tidal.com/"
    override val displayName = "TIDAL"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_api"
    override val brandColor = 0xFF111111
    override val noteName = "provider_note_tidal"
    override val searchesByIsrc: Boolean get() = true
    override val supportsAlbums = true
    override val supportsArtists = true
    // My Collection › Tracks (userCollectionTracks), with the scopes the albums already need (collection.read, collection.write).
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://developer.tidal.com/dashboard",
        redirectUri = Env.APP_REDIRECT_URI,
        stepUrls = listOf("https://developer.tidal.com/dashboard", "https://developer.tidal.com/dashboard/create", null, null, "https://developer.tidal.com/dashboard"),
        whyName = "setup_why_tidal",
        stepsArrayName = "setup_steps_tidal",
        fieldLabelName = "setup_field_client_id",
    )

    override val defaultClientId: String get() = BuildDefaults["TIDAL_CLIENT_ID"]
    override val authorizeEndpoint = "https://login.tidal.com/authorize"
    override val tokenEndpoint = "https://auth.tidal.com/v1/oauth2/token"
    // collection.* for saved albums and followed artists (userCollectionAlbums, userCollectionArtists). TIDAL's dashboard leaves every
    // scope off on a new app: the setup steps say which to tick.
    override val scopes = "user.read playlists.read playlists.write search.read collection.read collection.write"
    override val apiAccept = "application/vnd.api+json"
    override val apiContentType = "application/vnd.api+json"


    override suspend fun enrichAccount(ctx: Env, t: Tokens): Tokens {
        val me = api(ctx, "GET", "$API/users/me")["data"]
        val a = me["attributes"]
        return t.copy(
            userId = me["id"].str ?: "",
            userName = a["username"].str ?: listOfNotNull(a["firstName"].str, a["lastName"].str).joinToString(" "),
            extra = mapOf("country" to (a["country"].str ?: "US")),
        )
    }

    override fun errorMessage(resp: HttpResponse): String =
        runCatching { parseJson(resp.body)["errors"][0]["detail"].str }.getOrNull() ?: super.errorMessage(resp)

    private suspend fun cc(ctx: Env) = tokens(ctx).extra["country"] ?: "US"

    /** Segue `links.next` (percorso relativo alla radice API). */
    private fun nextUrl(j: JsonElement?): String? = j["links"]["next"].str?.let { if (it.startsWith("http")) it else API + it }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val t = tokens(ctx)
        val out = ArrayList<Playlist>()
        var url: String? = "$API/playlists?countryCode=${cc(ctx)}&filter[r.owners.id]=${Http.enc(t.userId)}"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (p in j["data"].arr) {
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = p["attributes"]["name"].str ?: "",
                    trackCount = p["attributes"]["numberOfItems"].int ?: -1,
                    isPublic = p["attributes"]["accessType"].str?.let { it == "PUBLIC" },
                )
            }
            url = nextUrl(j)
        }
        return out
    }

    private fun trackFrom(res: JsonElement?, artistNames: Map<String, String>, itemId: String? = null): Track? {
        val id = res["id"].str ?: return null
        val a = res["attributes"]
        val artistIds = res["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str }
        return Track(
            id = id,
            title = a["title"].str ?: "",
            artists = artistIds.mapNotNull { artistNames[it] },
            durationMs = Durations.parseIso8601(a["duration"].str),
            isrc = a["isrc"].str,
            itemId = itemId,
            explicit = a["explicit"].bool,
            // What the track allows (STREAM, DJ, STEM): without STREAM it is listed but does not play.
            available = a["availability"].takeUnless { it.isNullish }?.let { Unplayable.tidalStreamable(it.arr.mapNotNull { v -> v.str }) },
        )
    }

    override val detectsUnplayable: Boolean get() = true

    private suspend fun artistNames(ctx: Env, ids: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        ids.distinct().chunked(20).forEach { chunk ->
            val j = api(ctx, "GET", "$API/artists?countryCode=${cc(ctx)}&filter[id]=${chunk.joinToString(",")}")
            for (a in j["data"].arr) out[a["id"].str ?: continue] = a["attributes"]["name"].str ?: ""
        }
        return out
    }

    private suspend fun resolve(ctx: Env, trackResources: List<JsonElement>): List<Track> {
        val artistIds = trackResources.flatMap { r -> r["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str } }
        val names = artistNames(ctx, artistIds)
        return trackResources.mapNotNull { r -> trackFrom(r, names) }
    }

    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? {
        if (playlistId == MusicProvider.LIKED_ID) return likedVersion(ctx)
        if (MusicProvider.isLibrary(playlistId)) return null
        return api(ctx, "GET", "$API/playlists/$playlistId?countryCode=${cc(ctx)}")["data"]["attributes"]["lastModifiedAt"].str
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val d = api(ctx, "GET", "$API/playlists/$playlistId?countryCode=${cc(ctx)}")["data"]
        return Playlist(playlistId, d["attributes"]["name"].str ?: playlistId, d["attributes"]["numberOfItems"].int ?: -1, ownedByMe = false, description = d["attributes"]["description"].str.orEmpty())
    }

    // ---- collection: saved albums and followed artists (userCollectionAlbums / userCollectionArtists, JSON:API)

    private fun albumFrom(res: JsonElement?, names: Map<String, String>): Track? {
        val id = res["id"].str ?: return null
        val a = res["attributes"]
        return Track(
            id = id, title = a["title"].str ?: "", artists = res["relationships"]["artists"]["data"].arr.mapNotNull { names[it["id"].str] },
            isrc = a["barcodeId"].str, explicit = a["explicit"].bool, year = Durations.year(a["releaseDate"].str), kind = Track.KIND_ALBUM,
        )
    }

    private fun artistFrom(res: JsonElement?): Track? {
        val id = res["id"].str ?: return null
        val name = res["attributes"]["name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    private suspend fun albumsOf(ctx: Env, resources: List<JsonElement>): List<Track> {
        val names = artistNames(ctx, resources.flatMap { r -> r["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str } })
        return resources.mapNotNull { albumFrom(it, names) }
    }

    /**
     * The saved albums or followed artists: GET /userCollectionAlbums/me/relationships/items (or
     * /userCollectionArtists/…). TIDAL split /userCollections/{userId}/relationships/{albums,artists}
     * into one resource per kind (spec 1.10, seen 11/2026; the old path answers 404 "Resource not found"). These
     * paths take no countryCode. An included album may come without its artists' ids (only a link):
     * those are asked for again with their artists, twenty at a time, as for playlist tracks.
     */
    private suspend fun collection(ctx: Env, kind: String): List<Track> {
        val order = ArrayList<String>()
        val included = LinkedHashMap<String, JsonElement>()
        var url: String? = "${collectionItems(kind)}?include=items"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (d in j["data"].arr) if (d["type"].str == kind) order += d["id"].str ?: continue
            for (inc in j["included"].arr) if (inc["type"].str == kind) included[inc["id"].str ?: continue] = inc
            url = nextUrl(j)
        }
        if (kind == "albums") {
            val bare = included.filterValues { r -> r["relationships"]["artists"]["data"].arr.isEmpty() }.keys
            bare.chunked(20).forEach { chunk ->
                val j = runCatching { api(ctx, "GET", "$API/albums?countryCode=${cc(ctx)}&filter[id]=${chunk.joinToString(",")}&include=artists") }.getOrNull() ?: return@forEach
                for (a in j["data"].arr) { val id = a["id"].str ?: continue; if (a["relationships"]["artists"]["data"].arr.isNotEmpty()) included[id] = a }
            }
        }
        val resources = order.mapNotNull { included[it] }
        return if (kind == "albums") albumsOf(ctx, resources) else resources.mapNotNull { artistFrom(it) }
    }

    private fun collectionItems(kind: String): String =
        "$API/" + (if (kind == "albums") "userCollectionAlbums" else "userCollectionArtists") + "/me/relationships/items"

    private suspend fun editCollection(ctx: Env, kind: String, method: String, ids: List<String>) {
        ids.chunked(20).forEach { chunk ->
            api(ctx, method, collectionItems(kind), jsonObj("data" to jsonArr(chunk.map { mapOf("type" to kind, "id" to it) })))
        }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.ALBUMS_ID) return collection(ctx, "albums")
        if (playlistId == MusicProvider.ARTISTS_ID) return collection(ctx, "artists")
        if (playlistId == MusicProvider.LIKED_ID) return likedTracks(ctx)
        val order = ArrayList<Pair<String, String?>>() // (trackId, itemId) nell'ordine della playlist
        val included = LinkedHashMap<String, JsonElement>()
        var url: String? = "$API/playlists/$playlistId/relationships/items?countryCode=${cc(ctx)}&include=items"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (d in j["data"].arr) {
                if (d["type"].str != "tracks") continue
                order += (d["id"].str ?: continue) to d["meta"]["itemId"].str
            }
            for (inc in j["included"].arr) if (inc["type"].str == "tracks") included[inc["id"].str ?: continue] = inc
            url = nextUrl(j)
        }
        val names = withArtists(ctx, included)
        return order.mapNotNull { (tid, itemId) -> included[tid]?.let { trackFrom(it, names, itemId) } }
    }

    /**
     * The names of the artists of [included] (tracks by id). An included track may come without its
     * artists' ids (only a link to them): those tracks are asked for again with their artists, twenty
     * at a time, and replaced in [included], so every track has its names.
     */
    private suspend fun withArtists(ctx: Env, included: MutableMap<String, JsonElement>): Map<String, String> {
        val bare = included.filterValues { r -> r["relationships"]["artists"]["data"].arr.isEmpty() }.keys
        bare.chunked(20).forEach { chunk ->
            val j = runCatching { api(ctx, "GET", "$API/tracks?countryCode=${cc(ctx)}&filter[id]=${chunk.joinToString(",")}&include=artists") }.getOrNull() ?: return@forEach
            for (t in j["data"].arr) { val id = t["id"].str ?: continue; if (t["relationships"]["artists"]["data"].arr.isNotEmpty()) included[id] = t }
        }
        return artistNames(ctx, included.values.flatMap { r -> r["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str } })
    }

    // ---- liked tracks: My Collection › Tracks (userCollectionTracks, spec 1.10.251)

    private val likedItems = "$API/userCollectionTracks/me/relationships/items"

    /**
     * The liked tracks, the latest liked first (the API's default order, `-addedAt`), each with the
     * moment it was liked (`meta.addedAt`). Like the other collection paths, no countryCode.
     */
    private suspend fun likedTracks(ctx: Env): List<Track> {
        val order = ArrayList<Pair<String, Long>>()
        val included = LinkedHashMap<String, JsonElement>()
        var url: String? = "$likedItems?include=items"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (d in j["data"].arr) if (d["type"].str == "tracks") order += (d["id"].str ?: continue) to Durations.parseInstant(d["meta"]["addedAt"].str)
            for (inc in j["included"].arr) if (inc["type"].str == "tracks") included[inc["id"].str ?: continue] = inc
            url = nextUrl(j)
        }
        val names = withArtists(ctx, included)
        return order.mapNotNull { (tid, at) -> included[tid]?.let { trackFrom(it, names) }?.copy(addedAt = at) }
    }

    /** How many tracks are liked and when the list last changed: GET /userCollectionTracks/me. Null when it cannot be told. */
    private suspend fun likedVersion(ctx: Env): String? = try {
        val a = api(ctx, "GET", "$API/userCollectionTracks/me")["data"]["attributes"]
        a["lastModifiedAt"].str?.let { "${a["numberOfItems"].int ?: -1}|$it" }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * Likes [tracks], twenty a request, each with the moment it was liked on the source
     * ([Track.addedAt], `meta.addedAt`) so TIDAL lists them in the same order and with the same
     * dates. Should TIDAL refuse a request with dates (400), that request goes again without them,
     * once logged: liked today, as any other add. Answers the ids TIDAL skipped as already liked.
     */
    private suspend fun like(ctx: Env, tracks: List<Track>): Set<String> {
        val present = HashSet<String>()
        var dated = true
        for (chunk in tracks.chunked(20)) {
            fun body(withDates: Boolean) = jsonObj("data" to jsonArr(chunk.map { t ->
                val at = t.addedAt.takeIf { withDates && it > 0 }?.let { java.time.Instant.ofEpochMilli(it).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString() }
                if (at == null) mapOf("type" to "tracks", "id" to t.id) else mapOf("type" to "tracks", "id" to t.id, "meta" to mapOf("addedAt" to at))
            }))
            val withDates = dated && chunk.any { it.addedAt > 0 }
            val j = try {
                api(ctx, "POST", likedItems, body(withDates))
            } catch (e: ProviderException) {
                if (!withDates || e.code != 400) throw e
                dated = false
                ctx.log(id, "TIDAL refused the like dates (${e.message?.take(120)}): liking without them")
                api(ctx, "POST", likedItems, body(false))
            }
            j["meta"]["skipped"].arr.filter { it["reason"].str == "ALREADY_PRESENT" }.mapNotNullTo(present) { it["id"].str }
        }
        return present
    }

    /**
     * Page after page, only the entries (track id and item id, all a removal needs): no names asked
     * for, and no page read past the last wanted track.
     */
    override suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit): List<Track> {
        if (MusicProvider.isLibrary(playlistId)) return super.findEntries(ctx, playlistId, tracks, seen)
        return MusicProvider.entriesInPages(tracks, seen) { url ->
            val j = api(ctx, "GET", url ?: "$API/playlists/$playlistId/relationships/items?countryCode=${cc(ctx)}&include=items")
            j["data"].arr.mapNotNull { d ->
                if (d["type"].str != "tracks") null else Track(id = d["id"].str ?: return@mapNotNull null, title = "", itemId = d["meta"]["itemId"].str)
            } to nextUrl(j)
        }
    }

    override val keepsEntryIds: Boolean get() = true

    override suspend fun track(ctx: Env, trackId: String): Track? {
        val j = api(ctx, "GET", "$API/tracks/$trackId?countryCode=${cc(ctx)}&include=artists")
        return resolve(ctx, listOfNotNull(j["data"].takeIf { it["id"].str != null })).firstOrNull()
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://listen.tidal.com/search?q=" + Http.enc(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        if (track.kind == Track.KIND_ALBUM) {
            track.isrcNorm?.let { upc ->
                val j = api(ctx, "GET", "$API/albums?countryCode=${cc(ctx)}&filter[barcodeId]=$upc")
                val r = albumsOf(ctx, j["data"].arr)
                if (r.isNotEmpty()) return r
            }
            val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
            val (ids, included) = searchFor(ctx, q, "albums")
            return albumsOf(ctx, included.filter { it["type"].str == "albums" && it["id"].str in ids })
        }
        if (track.kind == Track.KIND_ARTIST) {
            val (ids, included) = searchFor(ctx, track.title, "artists")
            return included.filter { it["type"].str == "artists" && it["id"].str in ids }.mapNotNull { artistFrom(it) }
        }
        if (track.kind == Track.KIND_PODCAST) return emptyList()
        track.isrcNorm?.let { isrc ->
            val j = api(ctx, "GET", "$API/tracks?countryCode=${cc(ctx)}&filter[isrc]=$isrc")
            val r = resolve(ctx, j["data"].arr)
            if (r.isNotEmpty()) return r
        }
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
        val (ids, included) = searchFor(ctx, q, "tracks")
        return resolve(ctx, included.filter { it["type"].str == "tracks" && it["id"].str in ids })
    }

    /**
     * A catalogue search: the first five ids of [kind] (tracks, albums, artists) and the resources
     * included with them. The text goes in filter[query] of GET /searchResults: the search id in
     * /searchResults/{id}/… is an opaque id TIDAL issues, and a query there is "Invalid resource ID"
     * (since late 2026). When the search answers without the [kind] included, its relationship is read.
     */
    private suspend fun searchFor(ctx: Env, query: String, kind: String): Pair<Set<String>, List<JsonElement>> {
        val q = query.take(256)
        val j = api(ctx, "GET", "$API/searchResults?countryCode=${cc(ctx)}&filter[query]=${Http.enc(q)}&include=$kind")
        // "A collection containing exactly one search results resource": an array of one, or the object.
        val result = j["data"].arr.firstOrNull() ?: j["data"]
        var ids = result["relationships"][kind]["data"].arr.mapNotNull { it["id"].str }
        var included = j["included"].arr
        if (ids.isEmpty() || included.none { it["type"].str == kind }) {
            val id = result["id"].str ?: return emptySet<String>() to emptyList()
            val r = api(ctx, "GET", "$API/searchResults/${Http.enc(id)}/relationships/$kind?countryCode=${cc(ctx)}&include=$kind")
            ids = r["data"].arr.mapNotNull { it["id"].str }
            included = r["included"].arr
        }
        return ids.take(5).toSet() to included
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "$API/playlists?countryCode=${cc(ctx)}",
            jsonObj("data" to mapOf(
                "type" to "playlists",
                "attributes" to mapOf("name" to name, "description" to description, "accessType" to "UNLISTED"),
            )))
        return Playlist(j["data"]["id"].str ?: throw ProviderException("TIDAL: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.ALBUMS_ID) return editCollection(ctx, "albums", "POST", tracks.map { it.id })
        if (playlistId == MusicProvider.ARTISTS_ID) return editCollection(ctx, "artists", "POST", tracks.map { it.id })
        if (playlistId == MusicProvider.LIKED_ID) { like(ctx, tracks); return }
        tracks.chunked(20).forEach { chunk ->
            api(ctx, "POST", "$API/playlists/$playlistId/relationships/items?countryCode=${cc(ctx)}",
                jsonObj("data" to jsonArr(chunk.map { mapOf("type" to "tracks", "id" to it.id) })))
        }
    }

    /** The liked tracks TIDAL skipped as liked already (`meta.skipped`, ALREADY_PRESENT): present, not added. */
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> =
        if (playlistId == MusicProvider.LIKED_ID) like(ctx, tracks) else super.addTracksReportingPresent(ctx, playlistId, tracks)

    /**
     * Moves the entries into [ordered]'s order by item id: PATCH /playlists/{id}/relationships/items
     * puts the entries it names before `meta.positionBefore` (an item id), or at the end without a
     * meta; one move a request, the fewest moves ([Reorder.movesBefore]). Only where the sync keeps the
     * source's order. A refusal is logged and leaves the order as it is ([Reorder.Skipped]).
     */
    override val canReorder: Boolean get() = true
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) return
        if (!MusicProvider.addsInOrder()) throw Reorder.Skipped("TIDAL: moves only for a sync that keeps the order")
        if (current.any { it.itemId.isNullOrEmpty() } || ordered.any { it.itemId.isNullOrEmpty() }) throw Reorder.Skipped("TIDAL: an entry without its item id")
        val byItem = current.associateBy { it.itemId!! }
        val steps = Reorder.movesBefore(current.map { it.itemId!! }, ordered.map { it.itemId!! })
        for ((item, before) in steps) {
            val body = jsonObj(
                "data" to jsonArr(listOf(mapOf("type" to "tracks", "id" to byItem.getValue(item).id, "meta" to mapOf("itemId" to item)))),
                "meta" to before?.let { mapOf("positionBefore" to it) },
            )
            try {
                api(ctx, "PATCH", "$API/playlists/$playlistId/relationships/items", body)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                throw Reorder.Skipped("TIDAL: ${e.message?.take(160) ?: e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        api(ctx, "PATCH", "$API/playlists/$playlistId?countryCode=${cc(ctx)}",
            jsonObj("data" to mapOf("type" to "playlists", "id" to playlistId, "attributes" to mapOf("name" to name))))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        api(ctx, "PATCH", "$API/playlists/$playlistId?countryCode=${cc(ctx)}",
            jsonObj("data" to mapOf("type" to "playlists", "id" to playlistId, "attributes" to mapOf("accessType" to if (public) "PUBLIC" else "UNLISTED"))))
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        api(ctx, "DELETE", "$API/playlists/$playlistId")
    }

    override val removesSingleEntry: Boolean get() = true
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.ALBUMS_ID) return editCollection(ctx, "albums", "DELETE", tracks.map { it.id })
        if (playlistId == MusicProvider.ARTISTS_ID) return editCollection(ctx, "artists", "DELETE", tracks.map { it.id })
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(20).forEach { chunk -> api(ctx, "DELETE", likedItems, jsonObj("data" to jsonArr(chunk.map { mapOf("type" to "tracks", "id" to it.id) }))) }
            return
        }
        tracks.filter { it.itemId != null }.chunked(20).forEach { chunk ->
            api(ctx, "DELETE", "$API/playlists/$playlistId/relationships/items",
                jsonObj("data" to jsonArr(chunk.map { mapOf("type" to "tracks", "id" to it.id, "meta" to mapOf("itemId" to it.itemId)) })))
        }
    }

    // ---- listening: "My activity", the top artists of a month with their streams

    /**
     * The artists the user streamed most in [period], with TIDAL's own stream counts: the latest
     * month with data ([Listening.Period.MONTH]), or the months of the last twelve added up
     * ([Listening.Period.YEAR]). No songs: TIDAL's activity tells artists only.
     *
     * Not in the Open API: TIDAL's apps read it from api.tidal.com/v2/my-activity (timeline, then
     * top-artists?year&month), as the open-source tidlers crate (0.1.1) calls it. The web player has
     * no such page. It is asked with this sign-in's own token (the user's developer client); TIDAL
     * may refuse it. Any refusal or answer of another shape is logged once ("[poster] TIDAL
     * activity: HTTP <code> …", never the token) and thrown as [Listening.Unavailable].
     */
    suspend fun listening(ctx: Env, period: Listening.Period, now: Listening.Month = thisMonth()): Listening.Report {
        val timeline = Listening.tidalTimeline(activity(ctx, "timeline", emptyMap())) ?: unavailable(ctx, "GET /my-activity/timeline: an answer without a timeline")
        val months = Listening.tidalMonths(timeline, period, now)
        if (months.isEmpty()) {
            ctx.log(LOG, "TIDAL activity: no month with data (${period.key})")
            return Listening.Report(emptyList())
        }
        val lists = months.map { m ->
            Listening.tidalArtists(activity(ctx, "top-artists", mapOf("year" to "${m.year}", "month" to "${m.month}")))
                ?: unavailable(ctx, "GET /my-activity/top-artists ${m.year}-${m.month}: an answer without artists")
        }
        val artists = Listening.sumArtists(lists).take(Listening.TOP)
        ctx.log(LOG, "TIDAL activity: ${artists.size} artists over ${months.size} month(s) (${period.key}, latest ${months.first().year}-${months.first().month})")
        return Listening.Report(emptyList(), artists)
    }

    /** GET [ACTIVITY]/my-activity/[path] with the sign-in's token, the country and the locale; refused: [unavailable]. */
    private suspend fun activity(ctx: Env, path: String, params: Map<String, String>): JsonElement? {
        val t = tokens(ctx)
        val query = (params + mapOf("countryCode" to cc(ctx), "locale" to locale())).entries.joinToString("&") { "${it.key}=${Http.enc(it.value)}" }
        val resp = try {
            Http.send("GET", "$ACTIVITY/my-activity/$path?$query", mapOf("Authorization" to "Bearer ${t.accessToken}", "Accept" to "application/json"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            unavailable(ctx, "GET /my-activity/$path: ${e.javaClass.simpleName}")
        }
        if (!resp.ok) unavailable(ctx, "HTTP ${resp.code} GET /my-activity/$path${activityError(resp)}")
        return try { parseJson(resp.body) } catch (_: Exception) { unavailable(ctx, "GET /my-activity/$path: an answer that is not JSON") }
    }

    /** TIDAL's own words for a refusal (subStatus, userMessage), never the body as it is. */
    private fun activityError(resp: HttpResponse): String {
        val j = runCatching { parseJson(resp.body) }.getOrNull()
        val sub = j["subStatus"].str
        val msg = (j["userMessage"].str ?: j["errors"][0]["detail"].str)?.take(120)
        return listOfNotNull(sub?.let { "subStatus $it" }, msg).joinToString(", ").let { if (it.isEmpty()) "" else ": $it" }
    }

    private fun unavailable(ctx: Env, why: String): Nothing {
        ctx.log(LOG, "TIDAL activity: $why")
        throw Listening.Unavailable(why)
    }

    private fun locale(): String = java.util.Locale.getDefault().let { l ->
        if (l.language.isNotEmpty() && l.country.isNotEmpty()) "${l.language}_${l.country}" else "en_US"
    }

    private fun thisMonth(): Listening.Month = java.util.Calendar.getInstance().let { Listening.Month(it.get(java.util.Calendar.YEAR), it.get(java.util.Calendar.MONTH) + 1) }

    companion object {
        const val SERVICE = "tidal"
        private const val API = "https://openapi.tidal.com/v2"
        /** TIDAL's own API, where its apps read the listening activity (not part of the Open API). */
        private const val ACTIVITY = "https://api.tidal.com/v2"
        private const val LOG = "poster"
    }
}

package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.sync.Unplayable
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.sync.Reorder
import com.xlollx.songport.sync.TitleParser
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * YouTube Music non ha un'API pubblica: usiamo YouTube Data API v3, le playlist sono le stesse.
 * Quota (dal 1 giugno 2026): 100 ricerche al giorno per progetto Google, in un secchio a parte,
 * piu' 10.000 unita' al giorno per tutto il resto (un inserimento 50, una lettura 1). Le ricerche
 * non consumano piu' unita': ~100 brani nuovi al giorno per TUTTI gli utenti dell'app, limitati
 * dalle ricerche, finche' Google non alza la quota. Vedi [QuotaMeter].
 * La cache degli abbinamenti (Store.matchCache) evita di ripetere le ricerche.
 */
class YouTubeProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val revokeUrl = "https://myaccount.google.com/permissions"
    override val displayName = "YouTube Music (Google API)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_youtube_api"
    override val familyName: String get() = "YouTube Music"
    override val brandColor = 0xFFFF0000
    override val noteName = "provider_note_youtube"
    // Liked songs: the videos the account liked, the music ones (issue #30). Liking or unliking one
    // costs 50 quota units, as a playlist write does.
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://console.cloud.google.com/apis/credentials",
        whyName = "setup_why_youtube",
        stepsArrayName = "setup_steps_youtube",
        fieldLabelName = "setup_field_client_id",
        needsSecret = true,
        // La console ricorda l'ultimo progetto scelto: dopo il primo passo i link si aprono gia' dentro.
        stepUrls = listOf(
            "https://console.cloud.google.com/projectcreate",
            "https://console.cloud.google.com/apis/library/youtube.googleapis.com",
            "https://console.cloud.google.com/auth/overview",
            "https://console.cloud.google.com/auth/audience",
            "https://console.cloud.google.com/auth/clients/create",
        ),
    )

    override val defaultClientId: String get() = BuildDefaults["GOOGLE_CLIENT_ID"]
    override val authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenEndpoint = "https://oauth2.googleapis.com/token"
    override val scopes = "https://www.googleapis.com/auth/youtube"
    // prompt=consent garantisce il refresh token anche ai riaccessi successivi. select_account: Google's
    // chooser every time, where a brand account's channel is picked (#41; the API has no channel switch).
    override val extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent select_account")
    // Extra accounts: Google shows the account chooser instead of reusing the signed-in one.
    override val switchAccountParams = mapOf("prompt" to "consent select_account")

    // Client Android della build: Google impone lo schema "client id invertito" (nel manifest).
    override fun redirectUri(ctx: Env) = "${BuildDefaults["GOOGLE_REDIRECT_SCHEME"]}:/oauth2redirect"

    override fun isConfigured(ctx: Env) = clientId(ctx).isNotBlank()

    /**
     * Con le credenziali dell'utente si usa il flusso "installed app": client OAuth di tipo
     * Desktop e redirect su 127.0.0.1, perche' un client Desktop non accetta schemi personalizzati.
     * Cosi' la quota giornaliera YouTube consumata e' quella del progetto Google dell'utente,
     * non quella (condivisa da tutti) della build.
     */
    override fun startAuth(ctx: Env) {
        ctx.startAuth(this, if (ownClientId(ctx) != null) AuthMode.LOOPBACK else AuthMode.BROWSER)
    }



    /** Ogni chiamata ha un costo in unita' di quota: lo registriamo per mostrarlo all'utente. */
    private suspend fun counted(ctx: Env, cost: Long, call: suspend () -> JsonElement): JsonElement {
        val r = call()
        QuotaMeter.add(ctx, serviceId, cost)
        return r
    }

    override suspend fun enrichAccount(ctx: Env, t: Tokens): Tokens {
        val j = api(ctx, "GET", "$API/channels?part=snippet&mine=true")
        val ch = j["items"][0]
        return t.copy(userId = ch["id"].str ?: "", userName = ch["snippet"]["title"].str ?: "")
    }

    override fun errorMessage(resp: HttpResponse): String {
        val e = runCatching { parseJson(resp.body)["error"] }.getOrNull()
        val reason = e["errors"][0]["reason"].str
        if (reason == "quotaExceeded") return "the daily YouTube API quota (a limit set by Google, not by Trackhop) is used up; it resets at midnight Pacific time"
        return e["message"].str ?: super.errorMessage(resp)
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val out = ArrayList<Playlist>()
        var page: String? = null
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet,contentDetails,status&mine=true&maxResults=50" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            for (p in j["items"].arr) {
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = p["snippet"]["title"].str ?: "",
                    trackCount = p["contentDetails"]["itemCount"].int ?: -1,
                    isPublic = p["status"]["privacyStatus"].str?.let { it == "public" },
                )
            }
            page = j["nextPageToken"].str
        } while (page != null)
        return out
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val p = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet,contentDetails&id=$playlistId") }["items"][0]
        return Playlist(playlistId, p["snippet"]["title"].str ?: playlistId, p["contentDetails"]["itemCount"].int ?: -1, ownedByMe = false, description = p["snippet"]["description"].str.orEmpty())
    }

    private suspend fun isEmptyPlaylist(ctx: Env, playlistId: String): Boolean {
        val j = runCatching { counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=contentDetails&id=$playlistId") } }.getOrNull()
        val p = j["items"].arr.firstOrNull() ?: return false
        return (p["contentDetails"]["itemCount"].int ?: -1) == 0
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.LIKED_ID) return likedTracks(ctx)
        data class Raw(val itemId: String, val videoId: String, val title: String, val channel: String?)
        val raws = ArrayList<Raw>()
        var page: String? = null
        do {
            val j = itemsPage(ctx, playlistId, page) ?: return emptyList()
            for (item in j["items"].arr) {
                val vid = item["contentDetails"]["videoId"].str ?: continue
                val title = item["snippet"]["title"].str ?: continue
                if (title == "Private video" || title == "Deleted video") continue
                raws += Raw(item["id"].str ?: continue, vid, title, item["snippet"]["videoOwnerChannelTitle"].str)
            }
            page = j["nextPageToken"].str
        } while (page != null)

        // Durate (1 unita' di quota ogni 50 video): migliorano molto l'abbinamento.
        val durations = HashMap<String, Long>()
        raws.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=contentDetails&id=${ids.joinToString(",")}") }
            for (v in j["items"].arr) {
                val id = v["id"].str ?: continue
                durations[id] = Durations.parseIso8601(v["contentDetails"]["duration"].str)
            }
        }
        return raws.map { r ->
            val (title, artists) = TitleParser.parseYouTube(r.title, r.channel)
            Track(id = r.videoId, title = title, artists = artists, durationMs = durations[r.videoId] ?: 0, itemId = r.itemId)
        }
    }

    /** One page of a playlist's entries (fifty); null for an empty playlist. */
    private suspend fun itemsPage(ctx: Env, playlistId: String, page: String?): JsonElement? = try {
        counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId" +
            (page?.let { "&pageToken=$it" } ?: "")) }
    } catch (e: ProviderException) {
        // The Data API answers 404 "playlist cannot be found" for a playlist that exists but is
        // empty (typically one just created). Check the playlist itself before giving up.
        if (page == null && e.message?.contains("cannot be found") == true && isEmptyPlaylist(ctx, playlistId)) {
            ctx.log(id, "playlist $playlistId is empty; YouTube reports it as not found")
            null
        } else throw e
    }

    /**
     * Page after page, stopping at the last wanted track; the entries without the durations
     * (a removal needs only the entry id), so no quota spent on them.
     */
    override suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit): List<Track> {
        if (playlistId == MusicProvider.LIKED_ID) return super.findEntries(ctx, playlistId, tracks, seen)
        return MusicProvider.entriesInPages(tracks, seen) { page ->
            val j = itemsPage(ctx, playlistId, page) ?: return@entriesInPages emptyList<Track>() to null
            j["items"].arr.mapNotNull { item ->
                val vid = item["contentDetails"]["videoId"].str ?: return@mapNotNull null
                val title = item["snippet"]["title"].str ?: return@mapNotNull null
                if (title == "Private video" || title == "Deleted video") return@mapNotNull null
                val (name, artists) = TitleParser.parseYouTube(title, item["snippet"]["videoOwnerChannelTitle"].str)
                Track(id = vid, title = name, artists = artists, itemId = item["id"].str ?: return@mapNotNull null)
            } to j["nextPageToken"].str
        }
    }

    override val keepsEntryIds: Boolean get() = true

    override val detectsUnplayable: Boolean get() = true

    /**
     * Entries YouTube shows as "Private video" or "Deleted video", and videos the API no longer
     * lists at all. The position counts every entry of the playlist, placeholders included. One
     * quota unit per page and one per fifty videos.
     */
    override suspend fun unplayable(ctx: Env, playlistId: String): List<UnplayableEntry> {
        if (MusicProvider.isLibrary(playlistId)) return emptyList()
        data class Raw(val index: Int, val itemId: String, val videoId: String, val title: String, val channel: String?)
        val raws = ArrayList<Raw>()
        var page: String? = null
        var index = 0
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            for (item in j["items"].arr) {
                val vid = item["contentDetails"]["videoId"].str ?: continue
                raws += Raw(index++, item["id"].str ?: continue, vid, item["snippet"]["title"].str ?: "", item["snippet"]["videoOwnerChannelTitle"].str)
            }
            page = j["nextPageToken"].str
        } while (page != null)
        // Which of the videos the API still knows: a deleted one is simply missing from the answer.
        val known = HashSet<String>()
        raws.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=id&id=${ids.joinToString(",")}") }
            j["items"].arr.mapNotNullTo(known) { it["id"].str }
        }
        return raws.mapNotNull { r ->
            val reason = when {
                r.title == "Private video" -> Unplayable.REASON_PRIVATE
                r.title == "Deleted video" || r.videoId !in known -> Unplayable.REASON_DELETED
                else -> return@mapNotNull null
            }
            val (title, artists) = TitleParser.parseYouTube(r.title, r.channel)
            UnplayableEntry(Track(id = r.videoId, title = title, artists = artists, itemId = r.itemId, available = false), r.index, reason)
        }
    }

    override suspend fun track(ctx: Env, trackId: String): Track? {
        val v = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&id=${Http.enc(trackId)}") }
        val item = v["items"].arr.firstOrNull() ?: return null
        val (title, artists) = TitleParser.parseYouTube(item["snippet"]["title"].str ?: "", item["snippet"]["channelTitle"].str)
        return Track(id = trackId, title = title, artists = artists, durationMs = Durations.parseIso8601(item["contentDetails"]["duration"].str))
    }

    override fun webSearchUrl(ctx: Env, query: String): String = "https://www.youtube.com/results?search_query=" + Http.enc(query)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        // search.list has its own bucket since 2026-06-01 (100 calls a day): no units, one search.
        val s = api(ctx, "GET", "$API/search?part=snippet&type=video&videoCategoryId=10&maxResults=5&q=${Http.enc(q)}").also { QuotaMeter.addSearch(ctx, serviceId) }
        val ids = s["items"].arr.mapNotNull { it["id"]["videoId"].str }
        if (ids.isEmpty()) return emptyList()
        val v = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&id=${ids.joinToString(",")}") }
        return v["items"].arr.mapNotNull { item ->
            val id = item["id"].str ?: return@mapNotNull null
            val (title, artists) = TitleParser.parseYouTube(item["snippet"]["title"].str ?: "", item["snippet"]["channelTitle"].str)
            Track(id = id, title = title, artists = artists, durationMs = Durations.parseIso8601(item["contentDetails"]["duration"].str))
        }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/playlists?part=snippet,status",
            jsonObj(
                "snippet" to mapOf("title" to name, "description" to description),
                "status" to mapOf("privacyStatus" to "private"),
            )) }
        return Playlist(j["id"].str ?: throw ProviderException("YouTube: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) return rate(ctx, tracks, "like")
        for (t in tracks) {
            var attempt = 0
            while (true) {
                try {
                    counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/playlistItems?part=snippet",
                        jsonObj("snippet" to mapOf(
                            "playlistId" to playlistId,
                            "resourceId" to mapOf("kind" to "youtube#video", "videoId" to t.id),
                        ))) }
                    break
                } catch (e: ProviderException) {
                    // A 409 "The operation was aborted" (or a 503) is YouTube refusing an insert that
                    // came too close to the one before on the same playlist: the same insert a moment
                    // later goes through.
                    if ((e.code != 409 && e.code != 503) || ++attempt > INSERT_RETRIES) throw e
                    ctx.log(id, "YouTube aborted adding a video (${e.code}): trying again, ${attempt} of $INSERT_RETRIES")
                    kotlinx.coroutines.delay(2_000L * attempt)
                }
            }
        }
    }

    private val INSERT_RETRIES = 3

    /**
     * The playlist's snippet as it is, with [title] in place of its own when given. An update
     * replaces the whole part it names: what it leaves out (the description) would be erased.
     */
    private suspend fun snippet(ctx: Env, playlistId: String, title: String? = null): Map<String, Any?> {
        val s = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet&id=$playlistId") }["items"][0]["snippet"]
        val current = s["title"].str ?: throw ProviderException("$displayName: playlist $playlistId not found")
        return mapOf("title" to (title ?: current), "description" to s["description"].str, "defaultLanguage" to s["defaultLanguage"].str, "tags" to s["tags"])
            .filterValues { !it.isNullish() }
    }

    private fun Any?.isNullish(): Boolean = this == null || this is kotlinx.serialization.json.JsonNull

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        val snippet = snippet(ctx, playlistId, title = name)
        counted(ctx, QuotaMeter.COST_WRITE) {
            api(ctx, "PUT", "$API/playlists?part=snippet", jsonObj("id" to playlistId, "snippet" to snippet))
        }
    }

    /** An update must carry the title as well, so the current snippet is read first. */
    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        val snippet = snippet(ctx, playlistId)
        counted(ctx, QuotaMeter.COST_WRITE) {
            api(ctx, "PUT", "$API/playlists?part=snippet,status", jsonObj(
                "id" to playlistId, "snippet" to snippet, "status" to mapOf("privacyStatus" to if (public) "public" else "private"),
            ))
        }
    }

    /**
     * A playlist's picture: its "hero" image through playlistImages, the image itself in a
     * multipart upload (the scope the sign-in already has). The playlist's current one is looked
     * up first (a unit of quota), then replaced or added (fifty units, as any write). A cover is a
     * nicety: it is not set once the day's estimated quota leaves too little for the tracks.
     */
    override val canSetCover: Boolean get() = true
    override val coverIsV2: Boolean get() = true

    override suspend fun setPlaylistCover(ctx: Env, playlistId: String, jpeg: ByteArray) {
        val used = QuotaMeter.used(ctx.store.data, serviceId)
        if (used + QuotaMeter.COST_LIST + QuotaMeter.COST_WRITE > QuotaMeter.YOUTUBE_DAILY * COVER_QUOTA_SHARE / 100) {
            throw ProviderException("$displayName: cover not set, the day's quota is kept for the tracks")
        }
        val listed = authorized(ctx, "GET", "$API/playlistImages?part=id&parent=${Http.enc(playlistId)}", null)
        QuotaMeter.add(ctx, serviceId, QuotaMeter.COST_LIST)
        if (!listed.ok) throw ProviderException("$displayName: cover not set (HTTP ${listed.code}: ${errorMessage(listed).take(120)})")
        val current = parseJson(listed.body)["items"][0]["id"].str
        val meta = jsonObj(*listOfNotNull(current?.let { "id" to it }).toTypedArray(),
            "snippet" to mapOf("playlistId" to playlistId, "type" to "hero"))
        val body = okhttp3.MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val resp = authorized(ctx, if (current != null) "PUT" else "POST", "$UPLOAD/playlistImages?part=snippet&uploadType=multipart", body)
        QuotaMeter.add(ctx, serviceId, QuotaMeter.COST_WRITE)
        if (!resp.ok) throw ProviderException("$displayName: cover not set (HTTP ${resp.code}: ${errorMessage(resp).take(120)})")
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "DELETE", "$API/playlists?id=$playlistId") }
    }

    /**
     * The account's liked videos (videos.list myRating=like, 1 unit a page of 50), keeping the music
     * ones: those in YouTube's Music category, which is where YouTube Music's own likes land.
     */
    private suspend fun likedTracks(ctx: Env): List<Track> {
        val out = ArrayList<Track>()
        var page: String? = null
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&myRating=like&maxResults=50" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            out += likedFrom(j)
            page = j["nextPageToken"].str
        } while (page != null)
        return out
    }

    private suspend fun rate(ctx: Env, tracks: List<Track>, rating: String) {
        for (t in tracks) counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/videos/rate?id=${Http.enc(t.id)}&rating=$rating") }
    }

    override val removesSingleEntry: Boolean get() = true
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) return rate(ctx, tracks, "none")
        for (t in tracks) {
            val itemId = t.itemId ?: continue
            counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "DELETE", "$API/playlistItems?id=$itemId") }
        }
    }

    /**
     * Puts the entries in [ordered]'s order with playlistItems.update (`snippet.position`), the
     * fewest moves ([Reorder.moves]); only where a sync keeps the source's order. Each move costs a
     * write (50 units), so the moves are counted first: when the day's quota as [QuotaMeter] counts
     * it would not cover them all, none is made and the order stays as it is ([Reorder.Skipped],
     * logged). Positions count every entry, the private and deleted videos [tracks] leaves out
     * included, so the whole playlist is read for them (one unit a page).
     */
    override val canReorder: Boolean get() = true
    override val reorderIsV2: Boolean get() = true
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) return
        if (!MusicProvider.addsInOrder()) throw Reorder.Skipped("YouTube: moves cost quota, made only for a sync that keeps the order")
        if (current.any { it.itemId.isNullOrEmpty() } || ordered.any { it.itemId.isNullOrEmpty() }) throw Reorder.Skipped("YouTube: an entry without its item id")
        val listed = current.map { it.itemId!! }
        val wanted = ordered.map { it.itemId!! }
        if (wanted.toSet() != listed.toSet() || wanted.size != listed.size) throw Reorder.Skipped("YouTube: the order asked for is not the playlist's entries")
        if (Reorder.moves(listed, wanted).isEmpty()) return
        val all = ArrayList<Pair<String, String>>() // entry id to video id, every entry in playlist order
        var page: String? = null
        do {
            val j = itemsPage(ctx, playlistId, page) ?: break
            for (item in j["items"].arr) {
                val itemId = item["id"].str ?: continue
                all += itemId to (item["contentDetails"]["videoId"].str ?: item["snippet"]["resourceId"]["videoId"].str ?: "")
            }
            page = j["nextPageToken"].str
        } while (page != null)
        val ids = all.map { it.first }
        val shown = listed.toHashSet()
        if (ids.filter { it in shown } != listed) throw Reorder.Skipped("YouTube: the playlist changed while it was being reordered")
        // The wanted order in the places of the entries listed; the others stay where they are.
        val queue = ArrayDeque(wanted)
        val desired = ids.map { if (it in shown) queue.removeFirst() else it }
        val moves = Reorder.moves(ids, desired)
        if (moves.isEmpty()) return
        val cost = moves.size * QuotaMeter.COST_WRITE
        val used = QuotaMeter.used(ctx.store.data, serviceId)
        if (used + cost > QuotaMeter.YOUTUBE_DAILY) throw Reorder.Skipped("YouTube: ${moves.size} moves need $cost quota units, $used of ${QuotaMeter.YOUTUBE_DAILY} counted today")
        val video = all.toMap()
        val cur = ids.toMutableList()
        for ((from, to) in moves) {
            val item = cur.removeAt(from)
            cur.add(to, item)
            val videoId = video[item].orEmpty().ifEmpty { throw Reorder.Skipped("YouTube: entry $item has no video id") }
            try {
                counted(ctx, QuotaMeter.COST_WRITE) {
                    api(ctx, "PUT", "$API/playlistItems?part=snippet", jsonObj("id" to item, "snippet" to mapOf(
                        "playlistId" to playlistId, "resourceId" to mapOf("kind" to "youtube#video", "videoId" to videoId), "position" to to,
                    )))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                throw Reorder.Skipped("YouTube: ${e.message?.take(160) ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val SERVICE = "youtube"
        private const val API = "https://www.googleapis.com/youtube/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/youtube/v3"
        /** A cover is set only while the day's estimated quota stays under this share (percent) of the limit. */
        private const val COVER_QUOTA_SHARE = 80

        /** The music videos of a page of videos.list (category 10, "Music"), as tracks. */
        internal fun likedFrom(page: JsonElement): List<Track> = page["items"].arr.mapNotNull { v ->
            val id = v["id"].str ?: return@mapNotNull null
            if (v["snippet"]["categoryId"].str != "10") return@mapNotNull null
            val raw = v["snippet"]["title"].str ?: return@mapNotNull null
            val ms = Durations.parseIso8601(v["contentDetails"]["duration"].str)
            if (!looksLikeASong(raw, ms)) return@mapNotNull null
            val (title, artists) = TitleParser.parseYouTube(raw, v["snippet"]["channelTitle"].str)
            Track(id = id, title = title, artists = artists, durationMs = ms)
        }

        /** Words in a Music video's title that say it is about a song, not the song (an interview, a trailer). */
        private val NOT_A_SONG = Regex(
            """\b(interview|trailer|teaser|behind the scenes|making of|track by track|track inspiration|documentary|reaction|reacts?|podcast|livestream|live stream|unboxing|vlog|tutorial|lesson|how to play|guitar cover lesson|full album|full concert|album review|q&a)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * A liked Music video that is a song: not one whose title says it is something else, and between
         * 30 seconds and 15 minutes (a short is a clip; a long one a concert, an album or a mix).
         */
        internal fun looksLikeASong(title: String, durationMs: Long): Boolean {
            if (NOT_A_SONG.containsMatchIn(title)) return false
            if (durationMs in 1 until 30_000) return false
            if (durationMs > 15 * 60_000L) return false
            return true
        }
    }
}

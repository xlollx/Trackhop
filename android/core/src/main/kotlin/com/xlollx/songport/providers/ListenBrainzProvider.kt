package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement

/**
 * ListenBrainz (https://listenbrainz.readthedocs.io): sorgente in sola lettura, API aperta senza
 * chiave. Espone le playlist dell'utente (formato JSPF) e i brani "amati" (feedback +1).
 */
class ListenBrainzProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "ListenBrainz"
    override val brandColor = 0xFFEB743B
    override val noteName = "provider_note_listenbrainz"
    override val beta = true
    /** Writes reach only the loved tracks, and only with the user token; playlists stay read-only. */
    override val canWrite = true
    override val canCreatePlaylists = false
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsRecent = true
    override val loginForm = LoginForm(
        needsUrl = false, needsUser = true, needsSecret = true, secretLabelName = "login_token_optional",
        hintName = "login_hint_listenbrainz", secretOptional = true,
    )

    private suspend fun api(ctx: Env, path: String, method: String = "GET", body: JsonElement? = null, token: String? = null): JsonElement {
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        token?.let { headers["Authorization"] = "Token $it" }
        // Several syncs (or this one's search and writes) share the user's window: a 429 is waited out
        // a few times over, each wait as long as the window still has to run.
        val resp = Http.send(method, "$API$path", headers, body?.let { Http.jsonBody(it.toString()) }, maxRateLimitRetries = 8)
        // ListenBrainz paces by window (X-RateLimit-*): the last call of a window waits for the next one,
        // so a long search never meets a 429.
        if (resp.header("X-RateLimit-Remaining") == "0") {
            val wait = resp.header("X-RateLimit-Reset-In")?.toLongOrNull()?.coerceIn(1, 60) ?: 5
            Http.pause(wait * 1000 + 250)
        }
        if (!resp.ok) {
            val why = runCatching { parseJson(resp.body)["error"].str ?: "" }.getOrElse { resp.body.take(160) }
            throw ProviderException("$displayName API ${resp.code}: $why")
        }
        // 204: statistics not computed yet (a new account): nothing, rather than a parse error.
        if (resp.code == 204 || resp.body.isBlank()) return kotlinx.serialization.json.JsonNull
        return parseJson(resp.body)
    }

    /**
     * What the user played most in [period], for the Wrapped: ListenBrainz's statistics of the
     * recordings and the artists, with their listens. Statistics not ready yet read as none.
     */
    suspend fun listening(ctx: Env, period: Listening.Period): Listening.Report {
        val range = Listening.listenBrainzRange(period) ?: unsupported(ctx)
        val user = Http.enc(creds(ctx).userName)
        val (tracks, totalTracks) = Listening.listenBrainzTracks(api(ctx, "/1/stats/user/$user/recordings?range=$range&count=${Listening.TOP}"))
        val (artists, totalArtists) = Listening.listenBrainzArtists(api(ctx, "/1/stats/user/$user/artists?range=$range&count=${Listening.TOP}"))
        return Listening.Report(tracks, artists, totalTracks, totalArtists)
    }

    private fun token(ctx: Env): String? = creds(ctx).extra["token"]?.takeIf { it.isNotBlank() }

    /** The user token given at sign-in, if any: the keyless playlist generator sends it to ListenBrainz's popularity API. */
    fun userToken(ctx: Env): String? = token(ctx)

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val name = user.trim()
        api(ctx, "/1/user/${Http.enc(name)}/playlists?count=1")
        val token = secret.trim()
        if (token.isNotEmpty()) {
            val v = api(ctx, "/1/validate-token", token = token)
            if (v["valid"].bool != true) throw ProviderException(ctx.text("listenbrainz_token_invalid"))
        }
        save(ctx, Tokens(accessToken = name, userName = name, extra = if (token.isEmpty()) emptyMap() else mapOf("token" to token)))
    }

    private fun mbidOf(identifier: String?): String? = identifier?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val user = Http.enc(creds(ctx).userName)
        val out = ArrayList<Playlist>()
        var offset = 0
        do {
            val j = api(ctx, "/1/user/$user/playlists?count=50&offset=$offset")
            val items = j["playlists"].arr
            for (p in items) {
                val pl = p["playlist"]
                val id = mbidOf(pl["identifier"].str) ?: continue
                val count = pl["extension"]["https://musicbrainz.org/doc/jspf#playlist"]["track_count"].int ?: pl["track"].arr.size
                out += Playlist(id, pl["title"].str ?: id, count)
            }
            offset += items.size
        } while (items.size == 50 && offset < 1000)
        // The playlists ListenBrainz makes for the user (Weekly Jams, Weekly Exploration…): one entry
        // each, always the latest. Not answering leaves the list as it was, with a line in the log.
        try {
            out += createdFor(ctx).map { (key, p) -> Playlist(CREATED_FOR + key, createdForName(p), -1, ownedByMe = false, description = MusicProvider.plainDescription(p["annotation"].str)) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ctx.log(id, "playlists created for the user not read: ${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
        return out
    }

    /**
     * The playlists created for the user (GET /1/user/{user}/playlists/createdfor, public, no token),
     * the latest of each kind (`algorithm_metadata.source_patch`, else the title before " for "),
     * those not expired yet, the latest first. Each kind is a source that renews itself: its id
     * ([CREATED_FOR] + kind) always reads the latest playlist of the kind.
     */
    private suspend fun createdFor(ctx: Env, expired: Boolean = false): List<Pair<String, JsonElement>> {
        val user = Http.enc(creds(ctx).userName)
        val all = ArrayList<JsonElement>()
        var offset = 0
        do {
            val items = api(ctx, "/1/user/$user/playlists/createdfor?count=100&offset=$offset")["playlists"].arr
            items.mapNotNullTo(all) { it["playlist"].takeIf { p -> mbidOf(p["identifier"].str) != null } }
            offset += items.size
        } while (items.size == 100 && offset < 300)
        val now = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        return all.groupBy { createdForKind(it) }.mapNotNull { (kind, list) ->
            val latest = list.maxByOrNull { it["date"].str.orEmpty() } ?: return@mapNotNull null
            val ends = com.xlollx.songport.sync.Durations.parseInstant(latest["extension"][JSPF_PLAYLIST]["additional_metadata"]["expires_at"].str)
            if (!expired && ends > 0 && ends < now.toEpochMilli()) null else kind to latest
        }.sortedByDescending { it.second["date"].str.orEmpty() }
    }

    private fun createdForKind(p: JsonElement?): String =
        p["extension"][JSPF_PLAYLIST]["additional_metadata"]["algorithm_metadata"]["source_patch"].str?.takeIf { it.isNotBlank() }
            ?: createdForName(p).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** "Weekly Jams for rob, week of 2026-11-10 Mon" is "Weekly Jams": the kind's name, the same every week. */
    private fun createdForName(p: JsonElement?): String {
        val title = p["title"].str.orEmpty().trim()
        return title.substringBefore(" for ").trim().ifEmpty { title }
    }

    /** The latest playlist of [kind] made for the user; null when there is none. */
    private suspend fun latestCreatedFor(ctx: Env, kind: String): JsonElement? =
        createdFor(ctx, expired = true).firstOrNull { it.first == kind }?.second

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        if (!playlistId.startsWith(CREATED_FOR)) return super.playlistInfo(ctx, playlistId)
        val p = latestCreatedFor(ctx, playlistId.removePrefix(CREATED_FOR)) ?: return super.playlistInfo(ctx, playlistId)
        return Playlist(playlistId, createdForName(p), ownedByMe = false, description = MusicProvider.plainDescription(p["annotation"].str))
    }

    /** A track's id here is its MusicBrainz recording id, when ListenBrainz knows it: a personal server then matches the tagged file. */
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        read(ctx, playlistId).map { t -> com.xlollx.songport.sync.MusicBrainz.mbid(t.id)?.let { t.copy(mbids = listOf(it)) } ?: t }

    private suspend fun read(ctx: Env, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.RECENT_ID) {
            val user = Http.enc(creds(ctx).userName)
            val j = api(ctx, "/1/user/$user/listens?count=100")
            return j["payload"]["listens"].arr.mapNotNull { l ->
                val md = l["track_metadata"]
                val title = md["track_name"].str ?: return@mapNotNull null
                Track(
                    id = md["additional_info"]["recording_mbid"].str ?: md["mbid_mapping"]["recording_mbid"].str ?: "$title|${md["artist_name"].str}",
                    title = title, artists = listOfNotNull(md["artist_name"].str), album = md["release_name"].str ?: "",
                    durationMs = md["additional_info"]["duration_ms"].long ?: 0,
                    addedAt = (l["listened_at"].long ?: 0) * 1000,
                )
            }.distinctBy { it.id }
        }
        if (playlistId == MusicProvider.LIKED_ID) {
            val user = Http.enc(creds(ctx).userName)
            val out = ArrayList<Track>()
            var offset = 0
            do {
                val j = api(ctx, "/1/feedback/user/$user/get-feedback?score=1&metadata=true&count=100&offset=$offset")
                val items = j["feedback"].arr
                for (f in items) {
                    val md = f["track_metadata"]
                    val title = md["track_name"].str ?: continue
                    out += Track(
                        id = f["recording_mbid"].str ?: "$title|${md["artist_name"].str}",
                        title = title,
                        artists = listOfNotNull(md["artist_name"].str),
                        album = md["release_name"].str ?: "",
                        durationMs = md["additional_info"]["duration_ms"].long ?: 0,
                    )
                }
                offset += items.size
            } while (items.size == 100 && offset < 5000)
            return out
        }
        val mbid = if (!playlistId.startsWith(CREATED_FOR)) playlistId else {
            val kind = playlistId.removePrefix(CREATED_FOR)
            mbidOf(latestCreatedFor(ctx, kind)?.get("identifier").str)
                ?: throw ProviderException("$displayName: no playlist \"$kind\" made for ${creds(ctx).userName} now")
        }
        val pl = api(ctx, "/1/playlist/$mbid")["playlist"]
        return pl["track"].arr.mapNotNull { t ->
            val title = t["title"].str ?: return@mapNotNull null
            val artists = t["extension"]["https://musicbrainz.org/doc/jspf#track"]["additional_metadata"]["artists"].arr
                .mapNotNull { it["artist_credit_name"].str }.ifEmpty { listOfNotNull(t["creator"].str) }
            Track(
                id = mbidOf(t["identifier"].arr.firstOrNull()?.str ?: t["identifier"].str) ?: "$title|${t["creator"].str}",
                title = title,
                artists = artists,
                album = t["album"].str ?: "",
                durationMs = t["duration"].long ?: 0,
            )
        }
    }

    /** MusicBrainz knows the recording: its MBID is what feedback is written against. */
    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val artist = track.artists.firstOrNull() ?: return emptyList()
        // The lookup wants the user token (since 2026) and its path ends with a slash (without, a 308).
        // Only a ListenBrainz target is searched, and writing its loved tracks needs the token anyway.
        val token = token(ctx) ?: throw ProviderException(ctx.text("listenbrainz_token_needed"))
        // The lookup matches names closely: the title as written first, then without its decorations
        // ("- Remastered 2011", "(feat. X)", "(with X)"), keeping case and apostrophes.
        var j: JsonElement? = null
        for (title in listOf(track.title, Matcher.plainTitle(track.title)).distinct()) {
            j = api(ctx, "/1/metadata/lookup/?artist_name=${Http.enc(artist)}&recording_name=${Http.enc(title)}", token = token)
            if (j["recording_mbid"].str != null) break
        }
        if (j == null) return emptyList()
        val mbid = j["recording_mbid"].str ?: return emptyList()
        return listOf(Track(
            id = mbid, title = j["recording_name"].str ?: track.title,
            artists = listOfNotNull(j["artist_credit_name"].str ?: artist), album = j["release_name"].str ?: "",
        ))
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = unsupported(ctx)

    private suspend fun feedback(ctx: Env, tracks: List<Track>, score: Int) {
        val token = token(ctx) ?: throw ProviderException(ctx.text("listenbrainz_token_needed"))
        for (t in tracks) {
            if (!MBID.matches(t.id)) continue
            api(ctx, "/1/feedback/recording-feedback", "POST", jsonObj("recording_mbid" to t.id, "score" to score), token)
        }
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId != MusicProvider.LIKED_ID) unsupported(ctx)
        feedback(ctx, tracks, 1)
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (playlistId != MusicProvider.LIKED_ID) unsupported(ctx)
        feedback(ctx, tracks, 0)
    }

    companion object {
        const val SERVICE = "listenbrainz"
        private const val API = "https://api.listenbrainz.org"
        /** The id of a kind of playlist made for the user ("createdfor:weekly-jams"): it reads the latest of that kind. */
        const val CREATED_FOR = "createdfor:"
        private const val JSPF_PLAYLIST = "https://musicbrainz.org/doc/jspf#playlist"
        private val MBID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    }
}

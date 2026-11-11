package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement

/**
 * Last.fm: the "loved" tracks of a user (public profile, the user name is enough), their recent and
 * most played tracks, as sources. Needs a free API key, issued at once
 * (https://www.last.fm/api/account/create).
 *
 * Signed in with the password too (#51), Loved tracks is also a target: Trackhop loves and unloves
 * tracks for the user (track.love / track.unlove). The password is sent once, to
 * auth.getMobileSession, and only the session key Last.fm gives back is kept; sessions do not
 * expire unless the user revokes them. Signed calls need the API key's shared secret.
 */
class LastFmProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Last.fm"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteName: String? get() = "route_note_lastfm"
    override val brandColor = 0xFFD51007
    override val noteName = "provider_note_lastfm"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    // A target only for Loved tracks, and only once signed in with the password (see [canLove]):
    // Last.fm has no playlists to make.
    override val canWrite = true
    override val canCreatePlaylists = false
    override val canRemoveTracks = true
    override val supportsLikedSongs = true
    override val supportsRecent = true
    override val loginForm = LoginForm(
        needsUrl = false, needsUser = true, needsSecret = true, secretLabelName = "login_password_lastfm",
        hintName = "login_hint_lastfm", secretOptional = true,
    )
    override val setupGuide = SetupGuide(
        dashboardUrl = "https://www.last.fm/api/account/create",
        stepUrls = listOf("https://www.last.fm/api/account/create", null, "https://www.last.fm/api/accounts"),
        whyName = "setup_why_lastfm",
        stepsArrayName = "setup_steps_lastfm",
        fieldLabelName = "setup_field_api_key",
        needsSecret = true,
        secretOptional = true,
        secretLabelName = "setup_field_shared_secret",
    )

    private fun apiKey(ctx: Env): String =
        ctx.store.data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildDefaults["LASTFM_API_KEY"]

    /** The API key's shared secret, for signed calls (session, love, unlove): the user's own, or the build's. */
    private fun sharedSecret(ctx: Env): String =
        ctx.store.data.settings.clientSecrets[serviceId]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildDefaults["LASTFM_SHARED_SECRET"]

    /** The session key, when the account was signed in with its password: loving tracks needs it. */
    private fun session(ctx: Env): String? = ctx.tokens.get(id)?.extra?.get(SESSION)?.takeIf { it.isNotBlank() }

    /** True when Trackhop can love and unlove tracks for this account. */
    fun canLove(ctx: Env) = session(ctx) != null

    override fun isConfigured(ctx: Env) = apiKey(ctx).isNotBlank()
    override fun usesOwnCredentials(ctx: Env) = !ctx.store.data.settings.clientIds[serviceId].isNullOrBlank()

    private suspend fun call(ctx: Env, params: Map<String, String>): JsonElement {
        val key = apiKey(ctx)
        if (key.isBlank()) throw ProviderException(ctx.text("not_configured"))
        val resp = Http.send("GET", "$BASE?" + Http.query(params + mapOf("api_key" to key, "format" to "json")))
        val j = runCatching { parseJson(resp.body) }.getOrElse { if (resp.ok) throw it else kotlinx.serialization.json.JsonNull }
        j["error"].int?.let { throw ProviderException("$displayName: ${j["message"].str ?: "error $it"}") }
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        return j
    }

    /**
     * A signed write or session call: POST, with api_sig as Last.fm wants it (see [signature]). An
     * ended session (error 9) is dropped, so the account reads as read-only again and says how to
     * love tracks; a rate limit (29) or a busy service (16) is waited out once.
     */
    private suspend fun signedCall(ctx: Env, params: Map<String, String>, retry: Boolean = true): JsonElement {
        val key = apiKey(ctx)
        if (key.isBlank()) throw ProviderException(ctx.text("not_configured"))
        val secret = sharedSecret(ctx)
        if (secret.isBlank()) throw ProviderException(ctx.text("lastfm_no_secret"))
        val all = params + ("api_key" to key)
        val body = all + mapOf("api_sig" to signature(all, secret), "format" to "json")
        val resp = Http.send("POST", BASE, body = Http.form(body))
        val j = runCatching { parseJson(resp.body) }.getOrElse { if (resp.ok) throw it else kotlinx.serialization.json.JsonNull }
        when (val code = j["error"].int) {
            null -> Unit
            9 -> {
                ctx.tokens.get(id)?.let { t -> save(ctx, t.copy(extra = t.extra - SESSION)) }
                throw ProviderException(ctx.text("lastfm_session_expired"))
            }
            16, 29 -> {
                if (!retry) throw ProviderException("$displayName: ${j["message"].str ?: "error $code"}")
                Http.pause(BACKOFF_MS)
                return signedCall(ctx, params, retry = false)
            }
            else -> throw ProviderException("$displayName: ${j["message"].str ?: "error $code"}")
        }
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        return j
    }

    /**
     * Reads the profile (as before); with a password, also asks Last.fm for a session
     * (auth.getMobileSession) and keeps only its key, never the password.
     */
    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val name = call(ctx, mapOf("method" to "user.getinfo", "user" to user.trim()))["user"]["name"].str
            ?: throw ProviderException("$displayName: user not found")
        if (secret.isEmpty()) return save(ctx, Tokens(accessToken = name, userName = name))
        val s = signedCall(ctx, mapOf("method" to "auth.getMobileSession", "username" to user.trim(), "password" to secret), retry = false)["session"]
        val sk = s["key"].str ?: throw ProviderException("$displayName: no session")
        val sessionName = s["name"].str ?: name
        save(ctx, Tokens(accessToken = sessionName, userName = sessionName, extra = mapOf(SESSION to sk)))
    }

    /** Last.fm has no playlists: what it keeps per user, named and explained as Last.fm calls it. */
    override fun libraryEntries(ctx: Env, asTarget: Boolean): List<Playlist> = if (asTarget) listOf(
        // Offered even before the password is given, saying it is needed: the way to find out.
        Playlist(MusicProvider.LIKED_ID, ctx.text("lastfm_loved"), description = ctx.text(if (canLove(ctx)) "lastfm_loved_target_desc" else "lastfm_needs_password")),
    ) else listOf(
        Playlist(MusicProvider.LIKED_ID, ctx.text("lastfm_loved"), description = ctx.text("lastfm_loved_desc")),
        Playlist(MusicProvider.RECENT_ID, ctx.text("recently_played"), description = ctx.text("lastfm_recent_desc")),
    )

    /** The one "playlist": the user's most played tracks of the last month. */
    override suspend fun playlists(ctx: Env): List<Playlist> =
        listOf(Playlist(TOP_MONTH_ID, ctx.text("lastfm_top_month"), ownedByMe = false, description = ctx.text("lastfm_top_month_desc")))

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val user = creds(ctx).userName
        if (playlistId == MusicProvider.RECENT_ID) {
            val j = call(ctx, mapOf("method" to "user.getrecenttracks", "user" to user, "limit" to "200"))
            return list(j["recenttracks"]["track"]).mapNotNull { t ->
                if (t["@attr"]["nowplaying"].str == "true") return@mapNotNull null
                val title = t["name"].str ?: return@mapNotNull null
                val artist = t["artist"]["#text"].str ?: t["artist"]["name"].str
                Track(
                    id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist",
                    title = title, artists = listOfNotNull(artist), album = t["album"]["#text"].str ?: "",
                    addedAt = (t["date"]["uts"].str?.toLongOrNull() ?: 0) * 1000,
                )
            }.distinctBy { it.id }
        }
        if (playlistId == TOP_MONTH_ID) {
            val j = call(ctx, mapOf("method" to "user.gettoptracks", "user" to user, "period" to "1month", "limit" to "100"))
            return list(j["toptracks"]["track"]).mapNotNull { t ->
                val title = t["name"].str ?: return@mapNotNull null
                val artist = t["artist"]["name"].str
                Track(id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist", title = title, artists = listOfNotNull(artist))
            }.distinctBy { it.id }
        }
        if (playlistId != MusicProvider.LIKED_ID) return emptyList()
        val out = ArrayList<Track>()
        var page = 1
        var pages = 1
        do {
            val j = call(ctx, mapOf("method" to "user.getlovedtracks", "user" to user, "limit" to "200", "page" to page.toString()))
            val root = j["lovedtracks"]
            pages = root["@attr"]["totalPages"].str?.toIntOrNull() ?: 1
            for (t in list(root["track"])) {
                val title = t["name"].str ?: continue
                val artist = t["artist"]["name"].str
                out += Track(id = trackId(t, title, artist), title = title, artists = listOfNotNull(artist))
            }
            page++
        } while (page <= pages && page <= 50)
        return out
    }

    /**
     * What the user played most in [period], for the Wrapped: the top songs and artists with their
     * scrobbles, the period's plays and the artists of the period before (for its discovery; one
     * weekly chart spanning it). The last two are extras: without them the Wrapped still stands.
     */
    suspend fun listening(ctx: Env, period: Listening.Period): Listening.Report {
        val p = Listening.lastFmPeriod(period) ?: unsupported(ctx)
        val user = creds(ctx).userName
        val (tracks, totalTracks) = Listening.lastFmTracks(call(ctx, mapOf("method" to "user.gettoptracks", "user" to user, "period" to p, "limit" to "${Listening.TOP}")))
        val (artists, totalArtists) = Listening.lastFmArtists(call(ctx, mapOf("method" to "user.gettopartists", "user" to user, "period" to p, "limit" to "${Listening.TOP}")))
        val now = System.currentTimeMillis() / 1000
        val span = period.days * 86_400L
        val plays = extra { Listening.lastFmTotal(call(ctx, mapOf("method" to "user.getrecenttracks", "user" to user, "from" to "${now - span}", "limit" to "1"))) } ?: 0
        val before = extra { Listening.lastFmBefore(call(ctx, mapOf("method" to "user.getweeklyartistchart", "user" to user, "from" to "${now - 2 * span}", "to" to "${now - span}"))) }
        return Listening.Report(tracks, artists, totalTracks, totalArtists, plays, before)
    }

    private suspend fun <T> extra(block: suspend () -> T): T? =
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    /** Last.fm's JSON comes from its XML: a list of one arrives as the object itself, not an array of one. */
    private fun list(e: JsonElement?): List<JsonElement> = if (e is kotlinx.serialization.json.JsonObject) listOf(e) else e.arr

    /** The same id a track has in the loved list: its MusicBrainz id, else its page, else its names. */
    private fun trackId(t: JsonElement, title: String, artist: String?): String =
        t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist"

    /**
     * Last.fm's own spelling of a track (track.search): what loving it should name, so the loved list
     * and its ids match what the search gave.
     */
    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val artist = track.artists.firstOrNull().orEmpty()
        val params = mapOf("method" to "track.search", "track" to track.title, "limit" to "10") + (if (artist.isNotEmpty()) mapOf("artist" to artist) else emptyMap())
        return list(call(ctx, params)["results"]["trackmatches"]["track"]).mapNotNull { t ->
            val title = t["name"].str ?: return@mapNotNull null
            val by = t["artist"].str ?: t["artist"]["name"].str
            Track(id = trackId(t, title, by), title = title, artists = listOfNotNull(by))
        }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = unsupported(ctx)

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = love(ctx, playlistId, tracks, "track.love")
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = love(ctx, playlistId, tracks, "track.unlove")

    /** Loves or unloves each track, gently paced (Last.fm asks clients to stay well under five calls a second). */
    private suspend fun love(ctx: Env, playlistId: String, tracks: List<Track>, method: String) {
        if (playlistId != MusicProvider.LIKED_ID) unsupported(ctx)
        val sk = session(ctx) ?: throw ProviderException(ctx.text("lastfm_needs_password"))
        tracks.forEachIndexed { i, t ->
            if (i > 0) Http.pause(LOVE_SPACING_MS)
            signedCall(ctx, mapOf("method" to method, "track" to t.title, "artist" to t.artists.firstOrNull().orEmpty(), "sk" to sk))
        }
    }

    companion object {
        const val SERVICE = "lastfm"
        private const val BASE = "https://ws.audioscrobbler.com/2.0/"
        private const val SESSION = "sk"
        private const val LOVE_SPACING_MS = 300L
        private const val BACKOFF_MS = 5_000L

        /**
         * Last.fm's api_sig: the md5 of every parameter but format and callback, sorted by name, as
         * name and value one after the other, then the shared secret (last.fm/api/authspec).
         */
        fun signature(params: Map<String, String>, secret: String): String {
            val text = params.filterKeys { it != "format" && it != "callback" }.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
            return java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        /** The most played tracks of the last month (user.gettoptracks, period 1month). */
        const val TOP_MONTH_ID = "lastfm:top:1month"
    }
}

package com.xlollx.songport.providers

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.GameTitles
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * osu! (osu.ppy.sh/docs): the songs of a player's favourite and most played beatmaps, and of the
 * Featured Artist packs, as playlists to copy to a music service. Read-only, through the official
 * API v2 with the user's own OAuth application (a client ID and secret made in seconds in the osu!
 * account settings; a secret cannot ship in an open-source app) and the client-credentials grant:
 * only public data, no osu! password ever. The API asks for at most 60 requests a minute and no
 * tight polling: a listing reads 100 maps a request, a second apart, at most 1,000 maps.
 */
class OsuProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "osu!"
    override val brandColor = 0xFFFF66AA
    override val noteName = "provider_note_osu"
    override val beta = true
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1
    override val category get() = ServiceCatalog.Category.GAMES
    override val aliases get() = listOf("osu!lazer", "Featured Artists")
    override val loginForm = LoginForm(needsUrl = false, needsUser = true, needsSecret = false, hintName = "login_hint_osu")
    override val setupGuide = SetupGuide(
        dashboardUrl = "https://osu.ppy.sh/home/account/edit#oauth",
        stepUrls = listOf("https://osu.ppy.sh/home/account/edit#oauth", null, null),
        whyName = "setup_why_osu",
        stepsArrayName = "setup_steps_osu",
        fieldLabelName = "setup_field_client_id",
        needsSecret = true,
    )

    private fun clientId(ctx: Env) = ctx.store.data.settings.clientIds[serviceId]?.trim().orEmpty()
    private fun clientSecret(ctx: Env) = ctx.store.data.settings.clientSecrets[serviceId]?.trim().orEmpty()

    override fun isConfigured(ctx: Env) = clientId(ctx).isNotEmpty() && clientSecret(ctx).isNotEmpty()
    override fun usesOwnCredentials(ctx: Env) = true
    override fun webSearchUrl(ctx: Env, query: String): String = "https://osu.ppy.sh/beatmapsets?q=${Http.enc(query)}"
    override fun trackUrl(ctx: Env, trackId: String): String? =
        trackId.removePrefix(SET).takeIf { it != trackId }?.let { "https://osu.ppy.sh/beatmapsets/$it" }

    /** A guest token of the application (client credentials), kept until a minute before it ends. */
    private suspend fun token(ctx: Env): String {
        val id = clientId(ctx)
        val secret = clientSecret(ctx)
        if (id.isEmpty() || secret.isEmpty()) throw ProviderException(ctx.text("not_configured"))
        val key = "$id:${secret.hashCode()}"
        guestTokens[key]?.let { (t, until) -> if (System.currentTimeMillis() < until) return t }
        val r = Http.send(
            "POST", TOKEN_URL, mapOf("Accept" to "application/json"),
            Http.form(mapOf("client_id" to id, "client_secret" to secret, "grant_type" to "client_credentials", "scope" to "public")),
        )
        if (r.code == 400 || r.code == 401) throw ProviderException(ctx.text("osu_bad_key"))
        if (!r.ok) throw ProviderException("$displayName: HTTP ${r.code}")
        val j = parseJson(r.body)
        val t = j["access_token"].str ?: throw ProviderException(ctx.text("osu_bad_key"))
        val life = (j["expires_in"].long ?: 3600L).coerceAtLeast(120L)
        guestTokens[key] = t to System.currentTimeMillis() + (life - 60) * 1000
        return t
    }

    private suspend fun api(ctx: Env, path: String): JsonElement {
        val r = Http.send("GET", "$API$path", mapOf("Accept" to "application/json", "Authorization" to "Bearer ${token(ctx)}"))
        if (r.code == 401) {
            guestTokens.clear()
            throw ProviderException(ctx.text("osu_bad_key"))
        }
        if (r.code == 404) throw ProviderException(ctx.text("osu_not_found"), null, 404)
        if (!r.ok) throw ProviderException("$displayName: HTTP ${r.code}")
        return parseJson(r.body)
    }

    /** The player by name ("@name": the API's lookup by user name), or by id from a profile link (osu.ppy.sh/users/2). */
    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val typed = user.trim()
        val fromLink = typed.contains("/users/") || typed.contains("/u/")
        val name = typed.removePrefix("@").substringAfterLast("/users/").substringAfterLast("/u/").substringBefore('/').substringBefore('?').trim()
        if (name.isEmpty()) throw ProviderException(ctx.text("osu_not_found"))
        val who = if (fromLink && name.all { it.isDigit() }) name else "@" + Http.enc(name)
        val j = api(ctx, "/users/$who")
        val id = j["id"].long?.toString() ?: throw ProviderException(ctx.text("osu_not_found"))
        save(ctx, Tokens(accessToken = id, userId = id, userName = j["username"].str ?: name))
    }

    private fun userId(ctx: Env) = creds(ctx).userId.ifBlank { creds(ctx).accessToken }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val me = api(ctx, "/users/${userId(ctx)}")
        val out = arrayListOf(
            Playlist(FAVOURITES, ctx.text("osu_favourites"), me["favourite_beatmapset_count"].int ?: -1),
            Playlist(MOST_PLAYED, ctx.text("osu_most_played"), -1),
        )
        // The Featured Artist packs, newest first: songs osu! licensed from their artists. One page is plenty.
        runCatching { api(ctx, "/beatmaps/packs?type=featured") }.onSuccess { j ->
            j["beatmap_packs"].arr.take(MAX_PACKS).forEach { p ->
                val tag = p["tag"].str ?: return@forEach
                out += Playlist(PACK + tag, p["name"].str ?: tag, -1, ownedByMe = false)
            }
        }.onFailure { ctx.log("osu", "Featured Artist packs not listed: ${it.message}") }
        return out
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = when {
        playlistId == FAVOURITES -> Playlist(playlistId, ctx.text("osu_favourites"))
        playlistId == MOST_PLAYED -> Playlist(playlistId, ctx.text("osu_most_played"))
        playlistId.startsWith(PACK) -> Playlist(playlistId, playlistId.removePrefix(PACK), ownedByMe = false)
        else -> Playlist(playlistId, playlistId)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = when {
        playlistId == FAVOURITES -> pages(ctx, "/users/${userId(ctx)}/beatmapsets/favourite").mapNotNull { songOf(it) }
        playlistId == MOST_PLAYED -> pages(ctx, "/users/${userId(ctx)}/beatmapsets/most_played").mapNotNull { songOf(it["beatmapset"]) }
        playlistId.startsWith(PACK) -> api(ctx, "/beatmaps/packs/${Http.enc(playlistId.removePrefix(PACK))}")["beatmapsets"].arr.mapNotNull { songOf(it) }
        else -> throw ProviderException(ctx.text("osu_not_found"), null, 404)
    }.distinctBy { it.id }

    /** Every item of a listing read by offset, [PAGE] at a time, a second apart, up to [MAX_ITEMS]. */
    private suspend fun pages(ctx: Env, path: String): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        var offset = 0
        while (offset < MAX_ITEMS) {
            if (offset > 0) Http.pause(SPACING_MS)
            val items = api(ctx, "$path?limit=$PAGE&offset=$offset").arr
            out += items
            if (items.size < PAGE) break
            offset += items.size
        }
        return out
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = unsupported(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)

    companion object {
        const val SERVICE = "osu"
        private const val API = "https://osu.ppy.sh/api/v2"
        private const val TOKEN_URL = "https://osu.ppy.sh/oauth/token"
        const val FAVOURITES = "favourite"
        const val MOST_PLAYED = "most_played"
        const val PACK = "pack:"
        const val SET = "set:"
        private const val PAGE = 100
        private const val MAX_ITEMS = 1000
        private const val MAX_PACKS = 40
        private const val SPACING_MS = 1000L
        private val guestTokens = ConcurrentHashMap<String, Pair<String, Long>>()

        /** Tests: forget the guest tokens. */
        internal fun forgetTokens() = guestTokens.clear()

        /**
         * A beatmapset as the song it is: the title and artist in their own script where osu! has
         * it, without a game cut's mark ([GameTitles]). No length: a map is often a cut of the song.
         */
        internal fun songOf(set: JsonElement?): Track? {
            val id = set["id"].long ?: return null
            val title = set["title_unicode"].str?.takeIf { it.isNotBlank() } ?: set["title"].str ?: return null
            val artist = set["artist_unicode"].str?.takeIf { it.isNotBlank() } ?: set["artist"].str.orEmpty()
            return Track(SET + id, GameTitles.clean(title), GameTitles.artists(artist))
        }
    }
}

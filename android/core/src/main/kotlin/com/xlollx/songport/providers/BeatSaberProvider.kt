package com.xlollx.songport.providers

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.GameTitles
import kotlinx.serialization.json.JsonElement

/**
 * Beat Saber: the songs a player played, as playlists to copy to a music service. Read from the
 * public, documented APIs of the two score sites the game's community uses, BeatLeader
 * (api.beatleader.com: most played, recent, best) and ScoreSaber (scoresaber.com/api: recent, best),
 * with the player's id or profile link; no account, no key. Read-only: Trackhop never makes a
 * .bplist, which would have the game download maps with their audio.
 */
class BeatSaberProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Beat Saber"
    override val brandColor = 0xFFE0234E
    override val noteName = "provider_note_beatsaber"
    override val beta = true
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1
    override val category get() = ServiceCatalog.Category.GAMES
    override val aliases get() = listOf("BeatLeader", "ScoreSaber", "BeatSaver")
    override val loginForm = LoginForm(needsUrl = false, needsUser = true, needsSecret = false, hintName = "login_hint_beatsaber")

    override fun webSearchUrl(ctx: Env, query: String): String = "https://beatsaver.com/?q=${Http.enc(query)}"
    override fun trackUrl(ctx: Env, trackId: String): String? = null

    private suspend fun fetch(ctx: Env, url: String): JsonElement? {
        val r = Http.send("GET", url, mapOf("Accept" to "application/json", "User-Agent" to "Trackhop"))
        if (r.code == 404) return null
        if (!r.ok) throw ProviderException("$displayName: HTTP ${r.code}")
        return parseJson(r.body)
    }

    /** The player on BeatLeader first (it lists the most played), else on ScoreSaber. */
    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val id = playerId(user) ?: throw ProviderException(ctx.text("beatsaber_not_found"))
        fetch(ctx, "$BEATLEADER/player/$id")?.let { p ->
            val name = p["name"].str
            if (!name.isNullOrBlank()) return save(ctx, Tokens(accessToken = id, userId = p["id"].str ?: id, userName = name, extra = mapOf(SITE to BL)))
        }
        fetch(ctx, "$SCORESABER/player/$id/basic")?.let { p ->
            val name = p["name"].str
            if (!name.isNullOrBlank()) return save(ctx, Tokens(accessToken = id, userId = p["id"].str ?: id, userName = name, extra = mapOf(SITE to SS)))
        }
        throw ProviderException(ctx.text("beatsaber_not_found"))
    }

    private fun site(ctx: Env) = creds(ctx).extra[SITE] ?: BL
    private fun player(ctx: Env) = creds(ctx).userId.ifBlank { creds(ctx).accessToken }

    override suspend fun playlists(ctx: Env): List<Playlist> = lists(site(ctx)).map { Playlist(it, ctx.text("beatsaber_$it")) }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = Playlist(playlistId, ctx.text("beatsaber_$playlistId"))

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val id = Http.enc(player(ctx))
        val out = ArrayList<Track>()
        if (site(ctx) == BL) {
            val sort = when (playlistId) { MOST_PLAYED -> "playCount"; RECENT -> "date"; BEST -> "pp"; else -> throw ProviderException(ctx.text("beatsaber_not_found"), null, 404) }
            for (page in 1..PAGES) {
                val items = fetch(ctx, "$BEATLEADER/player/$id/scores?sortBy=$sort&order=desc&page=$page&count=$PAGE")?.get("data").arr
                items.mapNotNullTo(out) { beatLeaderSong(it["leaderboard"]["song"]) }
                if (items.size < PAGE) break
            }
        } else {
            val sort = when (playlistId) { RECENT -> "recent"; BEST -> "top"; else -> throw ProviderException(ctx.text("beatsaber_not_found"), null, 404) }
            for (page in 1..PAGES) {
                val items = fetch(ctx, "$SCORESABER/player/$id/scores?sort=$sort&limit=$PAGE&page=$page")?.get("playerScores").arr
                items.mapNotNullTo(out) { scoreSaberSong(it["leaderboard"]) }
                if (items.size < PAGE) break
            }
        }
        return out.distinctBy { it.id }
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = unsupported(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)

    companion object {
        const val SERVICE = "beatsaber"
        private const val BEATLEADER = "https://api.beatleader.com"
        private const val SCORESABER = "https://scoresaber.com/api"
        private const val SITE = "site"
        private const val BL = "beatleader"
        private const val SS = "scoresaber"
        const val MOST_PLAYED = "most_played"
        const val RECENT = "recent"
        const val BEST = "best"
        private const val PAGE = 100
        private const val PAGES = 5

        /** The lists each site has: ScoreSaber does not count plays. */
        fun lists(site: String): List<String> = if (site == SS) listOf(RECENT, BEST) else listOf(MOST_PLAYED, RECENT, BEST)

        /** The player's id from what was typed: the id itself, or a ScoreSaber or BeatLeader profile link. */
        fun playerId(typed: String): String? {
            val t = typed.trim().trimEnd('/')
            val id = Regex("""/u/([0-9A-Za-z_-]+)""").find(t)?.groupValues?.get(1) ?: t.substringBefore('?')
            return id.takeIf { it.matches(Regex("[0-9A-Za-z_-]{1,40}")) }
        }

        /** The song of a map: its name and sub-name (a cut's mark dropped), its artist; the map's hash as id. */
        private fun song(hash: String?, name: String?, subName: String?, author: String?): Track? {
            val h = hash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val title = listOfNotNull(name, subName).joinToString(" ").trim().takeIf { it.isNotEmpty() } ?: return null
            return Track("bs:$h", GameTitles.clean(title), GameTitles.artists(author.orEmpty()))
        }

        internal fun beatLeaderSong(s: JsonElement?): Track? = song(s["hash"].str, s["name"].str, s["subName"].str, s["author"].str)

        internal fun scoreSaberSong(l: JsonElement?): Track? = song(l["songHash"].str, l["songName"].str, l["songSubName"].str, l["songAuthorName"].str)
    }
}

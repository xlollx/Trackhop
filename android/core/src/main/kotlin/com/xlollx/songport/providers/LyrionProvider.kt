package com.xlollx.songport.providers

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * Lyrion Music Server, the former Logitech Media Server / Squeezebox Server (https://lyrion.org): a
 * personal server. Its documented command interface (https://lyrion.org/reference/cli/) is spoken as
 * JSON-RPC over the server's own web port: POST /jsonrpc.js with
 * `{"id":1,"method":"slim.request","params":["0",[command…]]}`. No registration, no quota.
 *
 * Most servers ask for no sign-in; one with "password protection" on takes the user and password as
 * HTTP Basic. Saved playlists are read with `playlists` and `playlists tracks`, and written with
 * `playlists new`, `playlists edit` (add by the track's url, delete by position), `rename` and `delete`.
 */
class LyrionProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Lyrion Music Server"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFF3F6FB5
    override val noteName = "provider_note_lyrion"
    override val beta = true
    // User and password only where the server has password protection on.
    override val loginForm = LoginForm(
        needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_lyrion",
        secretOptional = true, userOptional = true,
    )

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val t = Tokens(accessToken = secret, userName = user.trim(), extra = mapOf("url" to normalizeUrl(url)))
        // Any other web server answers something else to this: the address is then not a Lyrion server.
        if (rpc(ctx, t, "version", "?")["_version"].str.isNullOrBlank()) throw ProviderException(ctx.text("error_lyrion_not_found"))
        save(ctx, t)
    }

    /** One command, as the CLI documents it: the words, then "tag:value" parameters. Answers its "result". */
    private suspend fun rpc(ctx: Env, t: Tokens, vararg command: Any): JsonElement? {
        val words = command.map { if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
        val body = JsonObject(mapOf(
            "id" to JsonPrimitive(1), "method" to JsonPrimitive("slim.request"),
            // "0": no player, the command is for the server.
            "params" to JsonArray(listOf(JsonPrimitive("0"), JsonArray(words))),
        ))
        val headers = buildMap {
            put("Accept", "application/json")
            if (t.userName.isNotBlank()) {
                put("Authorization", "Basic " + Base64.getEncoder().encodeToString("${t.userName}:${t.accessToken}".toByteArray(Charsets.UTF_8)))
            }
        }
        // Every command goes out as a POST; reads opt in to the one retry on a 502/503/504.
        val read = command.none { it == "new" || it == "edit" || it == "rename" || it == "delete" }
        val resp = Http.send("POST", "${t.extra["url"]}/jsonrpc.js", headers, Http.jsonBody(body.toString()), retryServerErrors = read)
        if (resp.code == 401) throw ProviderException(ctx.text("error_lyrion_password"))
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        return runCatching { parseJson(resp.body) }.getOrNull()["result"]
    }

    private suspend fun rpc(ctx: Env, vararg command: Any): JsonElement? = rpc(ctx, creds(ctx), *command)

    private fun toTrack(s: JsonElement?): Track? {
        val id = s["id"].str ?: return null
        return Track(
            id = id,
            title = s["title"].str ?: "",
            artists = listOfNotNull(s["artist"].str?.takeIf { it.isNotBlank() }),
            album = s["album"].str ?: "",
            // Seconds, with decimals, as a number or as text.
            durationMs = ((s["duration"].str?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            // What "playlists edit cmd:add" wants: the track's url on the server.
            uri = s["url"].str,
            itemId = s["playlist index"].str,
            year = s["year"].int ?: 0,
        )
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        paged { start -> rpc(ctx, "playlists", start, PAGE).let { it["count"].int to it["playlists_loop"].arr } }
            // A folder of playlists is listed with its path as id: not a playlist to read or write.
            .mapNotNull { p -> Playlist(p["id"].str?.takeIf { it.toLongOrNull() != null } ?: return@mapNotNull null, p["playlist"].str ?: "") }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val r = rpc(ctx, "playlists", "tracks", 0, 1, "playlist_id:$playlistId")
        return Playlist(playlistId, r["__playlistTitle"].str ?: playlistId, r["count"].int ?: -1)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> =
        paged { start ->
            rpc(ctx, "playlists", "tracks", start, PAGE, "playlist_id:$playlistId", "tags:$TAGS").let { it["count"].int to it["playlisttracks_loop"].arr }
        }.mapNotNull { toTrack(it) }

    /** Every page of a listing: the server says how many entries there are in all. */
    private suspend fun paged(page: suspend (start: Int) -> Pair<Int?, List<JsonElement>>): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        while (true) {
            val (count, items) = page(out.size)
            out += items
            if (items.isEmpty() || out.size >= (count ?: 0)) return out
        }
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Albums and artists are not something a Lyrion playlist holds.
        if (track.kind.isNotEmpty()) return emptyList()
        suspend fun find(term: String) = rpc(ctx, "titles", 0, 25, "search:$term", "tags:$TAGS")["titles_loop"].arr.mapNotNull { toTrack(it) }
        val title = Matcher.searchTitle(track.title)
        val plain = Matcher.plainTitle(track.title)
        val both = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + title).joinToString(" ")
        // With the full-text search (on by default) artist and title find the song; without it the
        // term is matched against titles alone, so the title follows, as folded and as written.
        val found = find(both)
            .ifEmpty { if (both == title) emptyList() else find(title) }
            .ifEmpty { if (plain.equals(title, ignoreCase = true)) emptyList() else find(plain) }
            .ifEmpty { Matcher.baseTitle(track.title)?.let { find(it) }.orEmpty() }
        return Matcher.byArtist(track, found).sortedByDescending { Matcher.score(track, it) }.take(5)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val r = rpc(ctx, "playlists", "new", "name:$name")
        // Playlists are files named after them: the server makes none when the name is taken.
        if (r["overwritten_playlist_id"].str != null) throw ProviderException(ctx.text("error_server_name_taken", name))
        return Playlist(r["playlist_id"].str ?: throw ProviderException("$displayName: playlist not created"), name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        // A rename onto a taken name replaces that playlist: asked first, without doing it.
        val taken = rpc(ctx, "playlists", "rename", "playlist_id:$playlistId", "newname:$name", "dry_run:1")["overwritten_playlist_id"].str
        if (taken != null && taken != playlistId) throw ProviderException(ctx.text("error_server_name_taken", name))
        rpc(ctx, "playlists", "rename", "playlist_id:$playlistId", "newname:$name")
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        rpc(ctx, "playlists", "delete", "playlist_id:$playlistId")
    }

    /** A track is added by its url: the one read with it, or asked for when only the id is known. */
    private suspend fun urlOf(ctx: Env, track: Track): String? =
        track.uri ?: rpc(ctx, "titles", 0, 1, "track_id:${track.id}", "tags:u")["titles_loop"].arr.firstOrNull()["url"].str

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        addTracksReportingPresent(ctx, playlistId, tracks)
    }

    /**
     * The server's "add" leaves out a track the playlist already holds, and says nothing: such a
     * track is reported as present, so a sync counts it once instead of adding it again every run.
     */
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        val held = tracks(ctx, playlistId).mapTo(HashSet()) { it.id }
        val present = HashSet<String>()
        for (t in tracks) {
            if (!held.add(t.id)) { present += t.id; continue }
            val url = urlOf(ctx, t) ?: throw ProviderException(ctx.text("error_server_track_gone", t.toString()))
            rpc(ctx, "playlists", "edit", "cmd:add", "playlist_id:$playlistId", "url:$url")
        }
        return present
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        // By position, as read from the playlist; from the last, so the earlier positions still hold.
        tracks.mapNotNull { it.itemId?.toIntOrNull() }.distinct().sortedDescending().forEach { index ->
            rpc(ctx, "playlists", "edit", "cmd:delete", "playlist_id:$playlistId", "index:$index")
        }
    }

    companion object {
        const val SERVICE = "lyrion"
        private const val PAGE = 500
        /** artist, album, duration, year, url (the letters of the CLI's "songinfo"). */
        private const val TAGS = "aldyu"
    }
}

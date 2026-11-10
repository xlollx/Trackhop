package com.xlollx.songport.providers

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.obj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mopidy (https://mopidy.com): a personal music server. Its documented core API
 * (https://docs.mopidy.com/stable/api/core/) is spoken as JSON-RPC 2.0 over HTTP
 * (https://docs.mopidy.com/stable/api/http/): POST /mopidy/rpc. No registration, no quota, and no
 * sign-in: Mopidy has none, whoever reaches the server may use it.
 *
 * A track is its uri ("local:track:…"), a playlist is its uri ("m3u:Road%20trip.m3u8"). A playlist
 * is written whole: read with `core.playlists.lookup`, changed, handed back to `core.playlists.save`.
 */
class MopidyProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Mopidy"
    override val brandColor = 0xFF00897B
    override val noteName = "provider_note_mopidy"
    override val beta = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = false, needsSecret = false, hintName = "login_hint_mopidy")
    // Saving a playlist under a new name gives it a new uri (it is a file named after it): the syncs
    // pointing at the old one would lose it.
    override val canRenamePlaylists: Boolean get() = false
    override val canDeletePlaylists: Boolean get() = true

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val t = Tokens(accessToken = "", extra = mapOf("url" to normalizeUrl(url).removeSuffix("/mopidy")))
        val version = runCatching { rpc(ctx, t, "core.get_version", read = true).str }.getOrNull()
        if (version.isNullOrBlank()) throw ProviderException(ctx.text("error_mopidy_not_found"))
        save(ctx, t)
    }

    private suspend fun rpc(ctx: Env, t: Tokens, method: String, params: JsonObject? = null, read: Boolean = false): JsonElement? {
        val body = JsonObject(buildMap {
            put("jsonrpc", JsonPrimitive("2.0")); put("id", JsonPrimitive(1)); put("method", JsonPrimitive(method))
            params?.let { put("params", it) }
        })
        // Every call is a POST; reads opt in to the one retry on a 502/503/504.
        val resp = Http.send("POST", "${t.extra["url"]}/mopidy/rpc", mapOf("Accept" to "application/json"), Http.jsonBody(body.toString()), retryServerErrors = read)
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        val j = runCatching { parseJson(resp.body) }.getOrNull() ?: throw ProviderException("$displayName: unexpected response")
        j["error"].obj?.let { e ->
            throw ProviderException("$displayName: ${e["data"]["message"].str ?: e["message"].str ?: "error ${e["code"].int}"}")
        }
        return j["result"]
    }

    private suspend fun read(ctx: Env, method: String, params: JsonObject? = null) = rpc(ctx, creds(ctx), method, params, read = true)
    private suspend fun write(ctx: Env, method: String, params: JsonObject? = null) = rpc(ctx, creds(ctx), method, params)

    private fun toTrack(t: JsonElement?, index: Int? = null): Track? {
        val uri = t["uri"].str ?: return null
        return Track(
            id = uri,
            title = t["name"].str ?: "",
            artists = t["artists"].arr.mapNotNull { it["name"].str?.takeIf { n -> n.isNotBlank() } },
            album = t["album"]["name"].str ?: "",
            durationMs = t["length"].long ?: 0,
            itemId = index?.toString(),
            // "1986-03-03" or "1986".
            year = (t["date"].str ?: t["album"]["date"].str)?.take(4)?.toIntOrNull() ?: 0,
        )
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        read(ctx, "core.playlists.as_list").arr.mapNotNull { r -> Playlist(r["uri"].str ?: return@mapNotNull null, r["name"].str ?: "") }

    /** The playlist as the server holds it, to read or to hand back changed. */
    private suspend fun playlist(ctx: Env, uri: String): JsonObject =
        read(ctx, "core.playlists.lookup", jsonObj("uri" to uri)).obj ?: throw ProviderException("$displayName: playlist not found")

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        playlist(ctx, playlistId).let { Playlist(playlistId, it["name"].str ?: playlistId, it["tracks"].arr.size) }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val listed = playlist(ctx, playlistId)["tracks"].arr
        // A playlist file keeps a track's uri and little else: the library says the rest.
        val known = HashMap<String, JsonElement>()
        listed.mapNotNull { it["uri"].str }.distinct().chunked(100).forEach { chunk ->
            read(ctx, "core.library.lookup", jsonObj("uris" to chunk)).obj?.forEach { (uri, found) -> found.arr.firstOrNull()?.let { known[uri] = it } }
        }
        return listed.mapIndexedNotNull { i, t -> toTrack(known[t["uri"].str] ?: t, i)?.let { full -> if (full.title.isBlank()) full.copy(title = t["name"].str ?: "") else full } }
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        // Albums and artists are not something a Mopidy playlist holds.
        if (track.kind.isNotEmpty()) return emptyList()
        suspend fun find(vararg query: Pair<String, String>) =
            read(ctx, "core.library.search", jsonObj("query" to JsonObject(query.associate { (k, v) -> k to JsonArray(listOf(JsonPrimitive(v))) })))
                .arr.flatMap { it["tracks"].arr }.mapNotNull { toTrack(it) }.distinctBy { it.id }
        // As written (the library matches text as it is), then folded, then without its numbered part.
        val plain = Matcher.plainTitle(track.title)
        val folded = Matcher.searchTitle(track.title)
        val artist = track.artists.firstOrNull()?.takeIf { it.isNotBlank() }
        val found = (artist?.let { find("track_name" to plain, "artist" to it) }.orEmpty())
            .ifEmpty { find("track_name" to plain) }
            .ifEmpty { if (folded.equals(plain, ignoreCase = true)) emptyList() else find("track_name" to folded) }
            .ifEmpty { Matcher.baseTitle(track.title)?.let { find("track_name" to it) }.orEmpty() }
        return Matcher.byArtist(track, found).sortedByDescending { Matcher.score(track, it) }.take(5)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        // Playlists are files named after them, and a new one replaces a file of the same name.
        if (playlists(ctx).any { it.name.trim().equals(name.trim(), ignoreCase = true) }) throw ProviderException(ctx.text("error_server_name_taken", name))
        val made = write(ctx, "core.playlists.create", jsonObj("name" to name))
        return Playlist(made["uri"].str ?: throw ProviderException("$displayName: playlist not created"), made["name"].str ?: name, 0)
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        if (write(ctx, "core.playlists.delete", jsonObj("uri" to playlistId)).bool == false) throw ProviderException("$displayName: playlist not deleted")
    }

    private suspend fun save(ctx: Env, playlist: JsonObject, tracks: List<JsonElement>) {
        val saved = write(ctx, "core.playlists.save", JsonObject(mapOf("playlist" to JsonObject(playlist + ("tracks" to JsonArray(tracks))))))
        if (saved.isNullish) throw ProviderException("$displayName: playlist not saved")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val playlist = playlist(ctx, playlistId)
        save(ctx, playlist, playlist["tracks"].arr + tracks.map { jsonObj("__model__" to "Track", "uri" to it.id, "name" to it.title) })
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val playlist = playlist(ctx, playlistId)
        val listed = playlist["tracks"].arr
        // By position, and only where the playlist still holds that track there.
        val gone = tracks.mapNotNull { t -> t.itemId?.toIntOrNull()?.takeIf { listed.getOrNull(it)["uri"].str == t.id } }.toSet()
        if (gone.isEmpty()) return
        save(ctx, playlist, listed.filterIndexed { i, _ -> i !in gone })
    }

    companion object {
        const val SERVICE = "mopidy"
    }
}

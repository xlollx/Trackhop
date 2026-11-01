package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Jellyfin (https://api.jellyfin.org): server personale, nessuna registrazione, nessuna quota. */
class JellyfinProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Jellyfin"
    override val routeNoteName: String? get() = "route_note_server"
    override val brandColor = 0xFF00A4DC
    override val noteName = "provider_note_jellyfin"
    // Out of beta (01/11/2026): used against real servers and accounts, and passing LiveServersIT.
    override val beta = false
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    // A Jellyfin user may have no password (the server allows it, as its public demo does): may stay empty.
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintName = "login_hint_jellyfin", secretOptional = true)

    // Client and DeviceId stay "Songport" after the rename to Trackhop: they are identifiers, not a
    // name the user reads, and Jellyfin files existing sign-ins under them in its Devices list.
    private fun authHeader(token: String?) =
        "MediaBrowser Client=\"Songport\", Device=\"Android\", DeviceId=\"songport-android\", Version=\"1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val resp = Http.send(
            "POST", "$base/Users/AuthenticateByName",
            mapOf("Authorization" to authHeader(null), "Accept" to "application/json"),
            Http.jsonBody(jsonObj("Username" to user.trim(), "Pw" to secret).toString()),
        )
        if (!resp.ok) throw ProviderException("$displayName: login failed (HTTP ${resp.code})")
        val j = parseJson(resp.body)
        val token = j["AccessToken"].str ?: throw ProviderException("$displayName: no token")
        save(ctx, Tokens(accessToken = token, userId = j["User"]["Id"].str ?: "", userName = user.trim(), extra = mapOf("url" to base)))
    }

    private suspend fun api(ctx: Env, method: String, path: String, body: JsonElement? = null): JsonElement {
        val t = creds(ctx)
        val resp = Http.send(
            method, "${t.extra["url"]}$path",
            mapOf("Authorization" to authHeader(t.accessToken), "Accept" to "application/json"),
            body?.let { Http.jsonBody(it.toString()) },
        )
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        return parseJson(resp.body)
    }

    private fun uid(ctx: Env) = creds(ctx).userId

    /** The search page of the server's own web client, where the user finds the track and copies its link. */
    override fun webSearchUrl(ctx: Env, query: String): String? =
        ctx.tokens.get(id)?.extra?.get("url")?.let { searchUrl(it, query) }

    private fun toTrack(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        return Track(
            id = id,
            title = i["Name"].str ?: "",
            artists = i["Artists"].arr.mapNotNull { it.str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) },
            album = i["Album"].str ?: "",
            durationMs = (i["RunTimeTicks"].long ?: 0) / 10_000,
            itemId = i["PlaylistItemId"].str,
            year = i["ProductionYear"].int ?: 0,
            addedAt = com.xlollx.songport.sync.Durations.parseInstant(i["DateCreated"].str),
        )
    }

    override suspend fun playlists(ctx: Env): List<Playlist> =
        api(ctx, "GET", "/Users/${uid(ctx)}/Items?IncludeItemTypes=Playlist&Recursive=true&Fields=ChildCount")["Items"].arr
            .mapNotNull { p -> Playlist(p["Id"].str ?: return@mapNotNull null, p["Name"].str ?: "", p["ChildCount"].int ?: -1) }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val p = api(ctx, "GET", "/Users/${uid(ctx)}/Items/$playlistId")
        return Playlist(playlistId, p["Name"].str ?: playlistId, p["ChildCount"].int ?: -1)
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val path = when (playlistId) {
            MusicProvider.LIKED_ID -> "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=Audio&Recursive=true&Limit=5000"
            MusicProvider.ALBUMS_ID -> "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=5000"
            MusicProvider.ARTISTS_ID -> "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=MusicArtist&Recursive=true&Limit=5000"
            else -> "/Playlists/$playlistId/Items?UserId=${uid(ctx)}"
        }
        val items = api(ctx, "GET", path)["Items"].arr
        return when (playlistId) {
            MusicProvider.ALBUMS_ID -> items.mapNotNull { toAlbum(it) }
            MusicProvider.ARTISTS_ID -> items.mapNotNull { toArtist(it) }
            else -> items.mapNotNull { toTrack(it) }
        }
    }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        when (track.kind) {
            // Jellyfin's search term matches names: the album title alone finds the album, the artist filters after.
            Track.KIND_ALBUM -> return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(Matcher.searchTitle(track.title))}&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=8")["Items"].arr
                .mapNotNull { toAlbum(it) }
            Track.KIND_ARTIST -> return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(track.title)}&IncludeItemTypes=MusicArtist&Recursive=true&Limit=5")["Items"].arr
                .mapNotNull { toArtist(it) }
        }
        // The search term matches names only ("Metallica Battery" finds nothing): the title, then the artist's songs first.
        suspend fun find(term: String) = api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(term)}&IncludeItemTypes=Audio&Recursive=true&Limit=25")["Items"].arr
            .mapNotNull { toTrack(it) }
        // Jellyfin 10.8 does not fold accents: "energie" misses "Énergie". The title as written, then.
        val folded = Matcher.searchTitle(track.title)
        val plain = Matcher.plainTitle(track.title)
        val found = find(folded).ifEmpty { if (plain.equals(folded, ignoreCase = true)) emptyList() else find(plain) }
        return Matcher.byArtist(track, found).take(5)
    }

    private fun toAlbum(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        val artists = i["AlbumArtists"].arr.mapNotNull { it["Name"].str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) }
        return Track(id = id, title = i["Name"].str ?: "", artists = artists, kind = Track.KIND_ALBUM)
    }

    private fun toArtist(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        val name = i["Name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "/Playlists", jsonObj("Name" to name, "Ids" to emptyList<String>(), "UserId" to uid(ctx), "MediaType" to "Audio"))
        return Playlist(j["Id"].str ?: throw ProviderException("$displayName: playlist not created"), name, 0)
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        try {
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Name" to name))
        } catch (e: ProviderException) {
            // Before 10.9 there is no playlist update: the item itself is written back, renamed, whole.
            if (e.message?.contains(" 404") != true && e.message?.contains(" 405") != true) throw e
            val item = api(ctx, "GET", "/Users/${uid(ctx)}/Items/$playlistId").jsonObject
            api(ctx, "POST", "/Items/$playlistId", JsonObject(item + ("Name" to JsonPrimitive(name))))
        }
    }

    override suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        api(ctx, "DELETE", "/Items/$playlistId")
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) {
            // Favourites work the same for songs, albums and artists.
            tracks.forEach { api(ctx, "POST", "/Users/${uid(ctx)}/FavoriteItems/${it.id}") }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            api(ctx, "POST", "/Playlists/$playlistId/Items?Ids=${chunk.joinToString(",") { it.id }}&UserId=${uid(ctx)}")
        }
    }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        if (MusicProvider.isLibrary(playlistId)) {
            tracks.forEach { api(ctx, "DELETE", "/Users/${uid(ctx)}/FavoriteItems/${it.id}") }
            return
        }
        val gone = tracks.mapNotNull { it.itemId }
        // Jellyfin 10.9+ gives every copy of a song the same entry id, and deleting it drops them all:
        // when a copy has to stay, the list is written back whole.
        keptAfterRemoving(tracks(ctx, playlistId), gone)?.let { kept ->
            api(ctx, "POST", "/Playlists/$playlistId", jsonObj("Ids" to kept.map { it.id }))
            return
        }
        gone.chunked(50).forEach { chunk ->
            api(ctx, "DELETE", "/Playlists/$playlistId/Items?EntryIds=${chunk.joinToString(",")}")
        }
    }

    companion object {
        const val SERVICE = "jellyfin"

        /**
         * jellyfin-web's search page with [query] filled in. The web client lives under /web with hash
         * routing; since 10.9 its routes drop ".html" (#/search; "search.html" only redirects, deprecated),
         * and 10.8 is past its end of life.
         */
        internal fun searchUrl(base: String, query: String): String = "$base/web/#/search?query=" + Http.enc(query)

        /**
         * The entries left once [gone] (entry ids, one per copy to remove) are removed, or null when
         * deleting those entry ids removes exactly them. With shared ids the first copies stay.
         */
        internal fun keptAfterRemoving(entries: List<Track>, gone: List<String>): List<Track>? {
            val toDrop = gone.groupingBy { it }.eachCount().toMutableMap()
            if (toDrop.all { (id, n) -> entries.count { it.itemId == id } <= n }) return null
            return entries.asReversed().filter { e ->
                val left = e.itemId?.let { toDrop[it] } ?: 0
                if (left > 0) toDrop[e.itemId!!] = left - 1
                left == 0
            }.asReversed()
        }
    }
}

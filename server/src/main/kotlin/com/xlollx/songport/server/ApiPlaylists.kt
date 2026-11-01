package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.TrackLinks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * /api/v1/services/<service>/playlists…: a connected service's playlists and their tracks, for
 * scripts and automations. List, create, rename and delete playlists; list, add and remove tracks.
 * A track to add is named by its title and artist (searched and matched as a sync would), by a
 * link from any service, or by its id on that service; a track to remove by its id or by title and
 * artist, matched against what the playlist holds.
 */
internal object ApiPlaylists {
    private const val MAX_BODY = 2_000_000

    fun handle(web: Web, ex: HttpExchange, method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
        val env = web.env
        val p = Providers.byId(rest[1]) ?: return error(404, "No service with that id: see GET /api/v1/services.")
        if (p.requiresAuth && !p.isConnected(env)) return error(409, "${p.label(env)} is not connected.")
        val sub = rest.drop(2)
        val result = runCatching<Unit> {
            runBlocking {
                when {
                    sub == listOf("playlists") && method == "GET" -> json(200, JsonArray(
                        (p.libraryEntries(env, asTarget = false).filter { it.id != MusicProvider.RECENT_ID } + p.playlists(env)).map { pl ->
                            buildJsonObject { put("id", pl.id); put("name", pl.name); put("tracks", pl.trackCount); put("owned", pl.ownedByMe) }
                        },
                    ))
                    sub == listOf("playlists") && method == "POST" -> {
                        if (!p.canCreatePlaylists) return@runBlocking error(405, "${p.label(env)} cannot create playlists.")
                        val body = body(ex) ?: return@runBlocking error(400, "Send JSON: {\"name\": \"…\"}")
                        val name = body["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { return@runBlocking error(400, "The playlist needs a name.") }
                        val made = p.createPlaylist(env, name, body["description"]?.jsonPrimitive?.contentOrNull ?: MusicProvider.description("Trackhop"))
                        env.log("api", "${p.label(env)}: created playlist $name")
                        json(201, buildJsonObject { put("id", made.id); put("name", made.name) })
                    }
                    sub.size == 2 && sub[0] == "playlists" && method == "GET" -> json(200, JsonArray(p.tracks(env, sub[1]).map { track(it) }))
                    sub.size == 2 && sub[0] == "playlists" && method == "PATCH" -> {
                        if (!p.canRenamePlaylists) return@runBlocking error(405, "${p.label(env)} cannot rename playlists.")
                        val name = body(ex)?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { return@runBlocking error(400, "Send JSON: {\"name\": \"…\"}") }
                        p.renamePlaylist(env, sub[1], name)
                        json(200, buildJsonObject { put("id", sub[1]); put("name", name) })
                    }
                    sub.size == 2 && sub[0] == "playlists" && method == "DELETE" -> {
                        if (!p.canDeletePlaylists) return@runBlocking error(405, "${p.label(env)} cannot delete playlists.")
                        p.deletePlaylist(env, sub[1])
                        env.log("api", "${p.label(env)}: deleted playlist ${sub[1]}")
                        json(200, buildJsonObject { put("deleted", sub[1]) })
                    }
                    sub.size == 3 && sub[0] == "playlists" && sub[2] == "tracks" && method == "GET" -> json(200, JsonArray(p.tracks(env, sub[1]).map { track(it) }))
                    sub.size == 3 && sub[0] == "playlists" && sub[2] == "tracks" && method == "POST" -> add(env, p, sub[1], ex, json, error)
                    sub.size == 3 && sub[0] == "playlists" && sub[2] == "tracks" && method == "DELETE" -> remove(env, p, sub[1], ex, json, error)
                    // For tools that cannot send a body with DELETE.
                    sub.size == 4 && sub[0] == "playlists" && sub[2] == "tracks" && sub[3] == "remove" && method == "POST" -> remove(env, p, sub[1], ex, json, error)
                    else -> error(404, "Unknown call. See https://trackhop.org/guide/docker#api")
                }
            }
        }
        result.exceptionOrNull()?.let { error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun track(t: Track) = buildJsonObject {
        put("id", t.id); put("title", t.title)
        put("artists", buildJsonArray { t.artists.forEach { add(it) } })
        put("album", t.album)
        put("durationMs", t.durationMs)
        put("isrc", t.isrc?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun body(ex: HttpExchange): JsonObject? {
        val bytes = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.isEmpty() || bytes.size > MAX_BODY) return null
        return runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }.getOrNull()
    }

    /** The "tracks" array of the body: objects with title/artist, link or id, or plain strings ("Artist - Title" or a link). */
    private fun wanted(body: JsonObject): List<JsonObject> = body["tracks"]?.jsonArray?.mapNotNull { e ->
        when (e) {
            is JsonPrimitive -> e.contentOrNull?.let { s ->
                if (TrackLinks.parse(s) != null) buildJsonObject { put("link", s) }
                else s.split(" - ", limit = 2).let { parts -> buildJsonObject { if (parts.size == 2) { put("artist", parts[0]); put("title", parts[1]) } else put("title", s) } }
            }
            is JsonObject -> e
            else -> null
        }
    }.orEmpty()

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun describe(o: JsonObject) = o.str("link") ?: o.str("id") ?: listOfNotNull(o.str("artist"), o.str("title")).joinToString(" - ")

    /** What [o] names, as a track to look for: from a link (read on its own service), or title and artist. */
    private suspend fun source(env: ServerEnv, o: JsonObject): Track? {
        o.str("link")?.let { link ->
            val ref = TrackLinks.parse(link) ?: return null
            val from = Providers.connectors().firstOrNull { it.serviceId == ref.service && (!it.requiresAuth || it.isConnected(env)) } ?: return null
            return from.track(env, ref.trackId)
        }
        val title = o.str("title") ?: return null
        return Track(id = "", title = title, artists = listOfNotNull(o.str("artist")), album = o.str("album").orEmpty(), isrc = o.str("isrc"))
    }

    private suspend fun add(env: ServerEnv, p: MusicProvider, playlistId: String, ex: HttpExchange, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
        if (!p.canWrite) return error(405, "${p.label(env)} is read-only.")
        val body = body(ex) ?: return error(400, "Send JSON: {\"tracks\": [{\"title\": \"…\", \"artist\": \"…\"}, {\"link\": \"https://…\"}, {\"id\": \"…\"}]}")
        val asked = wanted(body).ifEmpty { return error(400, "No tracks in the body.") }
        val duplicates = body["allowDuplicates"]?.jsonPrimitive?.contentOrNull == "true"
        val present = if (duplicates) emptySet() else p.tracks(env, playlistId).map { it.id }.toSet()
        val found = ArrayList<Track>(); val notFound = ArrayList<String>(); val already = ArrayList<String>()
        for (o in asked) {
            val hit = o.str("id")?.let { id -> p.track(env, id) ?: Track(id = id, title = id) }
                ?: source(env, o)?.let { src -> Matcher.best(src, p.search(env, src)) }
            when {
                hit == null -> notFound += describe(o)
                hit.id in present || found.any { it.id == hit.id } && !duplicates -> already += "${hit.artistLine} - ${hit.title}"
                else -> found += hit
            }
        }
        if (found.isNotEmpty()) p.addTracks(env, playlistId, found)
        env.log("api", "${p.label(env)}: added ${found.size} tracks to $playlistId, ${notFound.size} not found")
        json(200, buildJsonObject {
            put("added", JsonArray(found.map { track(it) }))
            put("alreadyThere", buildJsonArray { already.forEach { add(it) } })
            put("notFound", buildJsonArray { notFound.forEach { add(it) } })
        })
    }

    private suspend fun remove(env: ServerEnv, p: MusicProvider, playlistId: String, ex: HttpExchange, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
        if (!p.canWrite || !p.canRemoveTracks) return error(405, "${p.label(env)} cannot remove tracks.")
        val body = body(ex) ?: return error(400, "Send JSON: {\"tracks\": [{\"id\": \"…\"}, {\"title\": \"…\", \"artist\": \"…\"}]}")
        val asked = wanted(body).ifEmpty { return error(400, "No tracks in the body.") }
        val current = p.tracks(env, playlistId)
        val gone = ArrayList<Track>(); val notFound = ArrayList<String>()
        for (o in asked) {
            val hits = o.str("id")?.let { id -> current.filter { it.id == id } }
                ?: source(env, o)?.let { src -> Matcher.best(src, current, Matcher.EXISTING_THRESHOLD)?.let { m -> current.filter { it.id == m.id } } }
                ?: emptyList()
            if (hits.isEmpty()) notFound += describe(o) else hits.filter { h -> gone.none { it === h } }.forEach { gone += it }
        }
        if (gone.isNotEmpty()) p.removeTracks(env, playlistId, gone)
        env.log("api", "${p.label(env)}: removed ${gone.size} tracks from $playlistId")
        json(200, buildJsonObject {
            put("removed", JsonArray(gone.map { track(it) }))
            put("notFound", buildJsonArray { notFound.forEach { add(it) } })
        })
    }
}

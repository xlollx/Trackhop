package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Duplicates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Library scan (the app's LibraryScan): a look at a service's whole library, read only: the songs in
 * more than one of your playlists (different editions of a song too, as Duplicates finds them) and
 * the liked songs in none. One request per playlist, so on YouTube it weighs on the quota as much as
 * a full backup. Each list can be saved as a File playlist.
 */
internal object LibraryScanServer {
    data class Repeat(val track: Track, val playlists: List<String>)
    /** [playlistsUnread]: a playlist was not read, so a liked song shown in none may be in it (not a list to save). */
    data class Result(val service: String, val playlists: Int, val repeats: List<Repeat>, val orphanLiked: List<Track>, val failed: List<String>, val playlistsUnread: Boolean = false) {
        /** This scan, for the Save buttons of its page: a later scan does not answer them. */
        val scanId: String = java.util.UUID.randomUUID().toString()
    }

    const val LIKED = "Liked songs"
    /** Who owns the liked songs in a scan: no playlist id is this. */
    private const val LIKED_KEY = "\u0000liked"

    /** The last scan of each service, for its Save buttons. */
    val last = ConcurrentHashMap<String, Result>()

    suspend fun run(env: Env, provider: MusicProvider, onProgress: (String) -> Unit = {}): Result {
        val lists = provider.playlists(env).filter { it.ownedByMe }
        // Each song's playlist by id: two playlists of one name are two, and a playlist named Liked songs is a playlist.
        val owner = IdentityHashMap<Track, String>()
        val names = HashMap<String, String>()
        val all = ArrayList<Track>()
        val failed = ArrayList<String>()
        var playlistsUnread = false
        var liked: List<Track> = emptyList()
        if (provider.supportsLikedSongs) {
            onProgress(LIKED)
            liked = try { provider.tracks(env, MusicProvider.LIKED_ID) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed += "$LIKED (${e.message})"; emptyList() }
            liked.forEach { owner[it] = LIKED_KEY; all += it }
        }
        for (pl in lists) {
            onProgress(pl.name)
            val tracks = try { provider.tracks(env, pl.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed += "${pl.name} (${e.message})"; playlistsUnread = true; continue }
            names[pl.id] = pl.name
            tracks.forEach { owner[it] = pl.id; all += it }
        }
        val repeats = ArrayList<Repeat>()
        val likedCovered = java.util.Collections.newSetFromMap(IdentityHashMap<Track, Boolean>())
        for (g in Duplicates.groups(all)) {
            val inPlaylists = g.mapNotNull { owner[it] }.filter { it != LIKED_KEY }.distinct()
            // The same song twice in one playlist is a duplicate, not a repeat across playlists.
            if (inPlaylists.size >= 2) repeats += Repeat(g.first { owner[it] != LIKED_KEY }, inPlaylists.map { names.getValue(it) })
            if (inPlaylists.isNotEmpty()) g.filter { owner[it] == LIKED_KEY }.forEach { likedCovered.add(it) }
        }
        return Result(provider.id, lists.size, repeats.sortedByDescending { it.playlists.size }, liked.filter { it !in likedCovered }, failed, playlistsUnread)
    }
}

private fun Web.scannable() = (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly }

internal fun Web.scanPage(r: Web.Req) {
    val services = scannable()
    val title = "Library scan"
    if (r.method == "POST") {
        val p = services.firstOrNull { it.id == r.form["service"] } ?: return redirect(r.ex, "/tools/scan?err=${q("Pick a connected service.")}")
        when (r.form["action"]) {
            "save" -> {
                val res = LibraryScanServer.last[env.owner + p.id]?.takeIf { it.scanId == r.form["scan"] } ?: return redirect(r.ex, "/tools/scan?err=${q("That scan is no longer kept: scan again.")}")
                if (r.form["list"] != "repeats" && res.playlistsUnread) return redirect(r.ex, "/tools/scan?err=${q("A playlist was not read, so those liked songs may be in it: scan again.")}")
                val (name, tracks) = if (r.form["list"] == "repeats") "${p.label(env)} in several playlists" to res.repeats.map { it.track } else "${p.label(env)} liked, in no playlist" to res.orphanLiked
                val id = LocalFilesProvider.importTracks(env, name, tracks)
                return redirect(r.ex, "/connectors/csv?ok=${q("Saved as the File playlist $id (${tracks.size} songs).")}")
            }
            else -> {
                val task = Tasks.startOnce("$title of ${p.label(env)}", "/tools/scan", { env.log("scan", it) }, env.owner, env.marks) { t ->
                    val res = LibraryScanServer.run(env, p) { t.progress = "Reading $it…" }
                    LibraryScanServer.last[env.owner + p.id] = res
                    scanHtml(p, res)
                } ?: return redirect(r.ex, "/tools/scan?err=${q("That library is already being scanned.")}")
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">A look at a service's whole library, changing nothing: the songs that are in more than one of your playlists, and the liked songs that are in none. Each list can be saved as a File playlist. It reads every playlist once (on YouTube, as much quota as a full backup).</p>""")
    if (services.isEmpty()) body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
    else {
        val opts = services.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="post" action="/tools/scan" class="card"><label>Service</label><select name="service">$opts</select><p><button>Scan</button></p></form>""")
    }
    send(r.ex, 200, page(title, body.toString()))
}

private fun Web.scanHtml(p: MusicProvider, res: LibraryScanServer.Result): String = buildString {
    fun save(list: String) = Html.postButton("/tools/scan", "Save as a File playlist", "secondary", mapOf("action" to "save", "service" to p.id, "list" to list, "scan" to res.scanId))
    append("""<p class="muted">${h(p.label(env))}: ${res.playlists} playlists read.</p>""")
    if (res.failed.isNotEmpty()) append("""<div class="card err">Not read: ${h(res.failed.joinToString(", "))}</div>""")
    append("""<div class="card"><h3>In several playlists (${res.repeats.size})</h3>""")
    if (res.repeats.isEmpty()) append("""<p class="muted">No song is in more than one playlist.</p>""")
    else {
        append("<ul>")
        res.repeats.take(300).forEach { append("""<li>${h(it.track.artistLine)} – ${h(it.track.title)} <span class="muted">· ${h(it.playlists.joinToString(", "))}</span></li>""") }
        if (res.repeats.size > 300) append("""<li class="muted">…and ${res.repeats.size - 300} more</li>""")
        append("</ul>").append(save("repeats"))
    }
    append("</div>")
    if (p.supportsLikedSongs) {
        append("""<div class="card"><h3>Liked, in no playlist (${res.orphanLiked.size})</h3>""")
        if (res.orphanLiked.isEmpty()) append("""<p class="muted">Every liked song is in a playlist.</p>""")
        else {
            append("<ul>")
            res.orphanLiked.take(300).forEach { append("""<li>${h(it.artistLine)} – ${h(it.title)}</li>""") }
            if (res.orphanLiked.size > 300) append("""<li class="muted">…and ${res.orphanLiked.size - 300} more</li>""")
            append("</ul>")
            if (!res.playlistsUnread) append(save("liked"))
        }
        append("</div>")
    }
}

/** GET /services/{service}/scan: the scan, read now (a large library takes a while). */
internal fun Web.apiScan(method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!(rest.size == 3 && rest[0] == "services" && rest[2] == "scan" && method == "GET")) return false
    val p = scannable().firstOrNull { it.id == rest[1] } ?: return true.also { error(404, "No connected service with that id: see GET /api/v1/services.") }
    val res = runCatching { runBlocking { LibraryScanServer.run(env, p) } }.getOrElse { return true.also { _ -> error(502, it.message ?: it.javaClass.simpleName) } }
    LibraryScanServer.last[env.owner + p.id] = res
    json(200, buildJsonObject {
        put("service", p.id); put("playlists", res.playlists)
        put("repeats", JsonArray(res.repeats.map { r -> buildJsonObject { put("track", trackRef(r.track)); put("playlists", JsonArray(r.playlists.map { JsonPrimitive(it) })) } }))
        put("likedInNoPlaylist", JsonArray(res.orphanLiked.map { trackRef(it) }))
        put("notRead", JsonArray(res.failed.map { JsonPrimitive(it) }))
    })
    return true
}

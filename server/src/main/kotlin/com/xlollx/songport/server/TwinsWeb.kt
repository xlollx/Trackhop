package com.xlollx.songport.server

import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.PlaylistTwins
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Playlist twins (the app's PlaylistTwinsPage, [PlaylistTwins]): the copies of one playlist on a
 * service (same name, or nine songs in ten the same). Keep one; the songs the others have that it
 * lacks can be added to it first; the others are saved in Files, then deleted. A copy a sync uses
 * is never deleted.
 */
internal object TwinsServer {
    /** The last reading of each service, for the page's Delete buttons. */
    val found = ConcurrentHashMap<String, List<PlaylistTwins.Group>>()
}

private fun Web.twinServices() = Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && it.canDeletePlaylists && !it.linkOnly && it !is LocalFilesProvider }

/** Deletes the copies of [group] but [keep]; the ids deleted. */
private fun Web.twinsDelete(p: MusicProvider, group: PlaylistTwins.Group, keep: String, addMissing: Boolean): List<String> {
    val kept = group.copies.firstOrNull { it.playlist.id == keep } ?: throw IllegalArgumentException("Keep one of the group's playlists.")
    val gone = runBlocking { PlaylistTwins.delete(env, p, kept, group.copies.filter { it !== kept }, addMissing) { env.text("twins_deleted_copy", it) } }
    TwinsServer.found[env.owner + p.id] = TwinsServer.found[env.owner + p.id].orEmpty().filter { it.key != group.key }
    env.log("twins", "${p.label(env)}: kept ${kept.playlist.name}, deleted ${gone.size}")
    return gone
}

internal fun Web.twinsPage(r: Web.Req) {
    val services = twinServices()
    val title = "Playlist twins"
    if (r.method == "POST") {
        val p = services.firstOrNull { it.id == r.form["service"] } ?: return redirect(r.ex, "/tools/twins?err=${q("Pick a connected service.")}")
        if (r.form["action"] == "delete") {
            val group = TwinsServer.found[env.owner + p.id]?.firstOrNull { it.key == r.form["group"] }
                ?: return redirect(r.ex, "/tools/twins?err=${q("Those copies are no longer kept: look again.")}")
            val gone = runCatching { twinsDelete(p, group, r.form["keep"].orEmpty(), r.form["add"] == "on") }.getOrElse { return redirect(r.ex, "/tools/twins?err=${q(it.message ?: "Error")}") }
            return redirect(r.ex, "/tools/twins?ok=${q("${gone.size} ${if (gone.size == 1) "copy" else "copies"} deleted; each is kept in Files (File playlists).")}")
        }
        val task = Tasks.startOnce("$title on ${p.label(env)}", "/tools/twins", { env.log("twins", it) }, env.owner, env.marks) { t ->
            val groups = PlaylistTwins.find(env, p) { x -> t.progress = "Reading playlist ${x.done + 1} of ${x.total}" }
            TwinsServer.found[env.owner + p.id] = groups
            twinsHtml(p, groups)
        } ?: return redirect(r.ex, "/tools/twins?err=${q("That service is already being read.")}")
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The copies of one playlist on a service: the same name, or nine songs in ten the same. Keep one; the songs only the others have can be added to it; the others are saved in Files, then deleted. A copy a sync uses is never deleted.</p>""")
    if (services.isEmpty()) body.append("""<div class="card">No connected service lets Trackhop delete playlists. <a href="/connectors">Services</a></div>""")
    else {
        val opts = services.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="post" action="/tools/twins" class="card"><label>Service</label><select name="service">$opts</select><p><button>Look for copies</button></p></form>""")
    }
    send(r.ex, 200, page(title, body.toString()))
}

private fun Web.twinsHtml(p: MusicProvider, groups: List<PlaylistTwins.Group>): String = buildString {
    if (groups.isEmpty()) { append("""<div class="card ok">No playlist on ${h(p.label(env))} has a copy.</div>"""); return@buildString }
    for (g in groups) {
        val kept = g.copies[g.keep]
        append("""<form method="post" action="/tools/twins" class="card"><input type="hidden" name="action" value="delete"><input type="hidden" name="service" value="${h(p.id)}"><input type="hidden" name="group" value="${h(g.key)}">""")
        append("""<h3>${h(kept.playlist.name)} · ${g.copies.size} copies</h3>""")
        g.copies.forEachIndexed { i, c ->
            val missing = if (c === kept) 0 else PlaylistTwins.missing(kept.tracks, c.tracks).size
            val notes = listOfNotNull(
                "${c.tracks.size} songs",
                c.syncs.takeIf { it.isNotEmpty() }?.let { "used by ${it.joinToString(", ")}: never deleted" },
                missing.takeIf { it > 0 }?.let { "$it not in the one proposed" },
            ).joinToString(" · ")
            append("""<label class="check"><input type="radio" name="keep" value="${h(c.playlist.id)}"${if (i == g.keep) " checked" else ""}> ${h(c.playlist.name)} <span class="muted">· ${h(notes)}</span></label>""")
        }
        append("""<label class="check"><input type="checkbox" name="add" checked> First add to the one kept the songs only the others have</label>""")
        append("""<p><button class="danger" onclick="return confirm('Delete the other copies? Each is saved in Files first.')">Keep the ticked one, delete the others</button></p></form>""")
    }
}

/** GET /services/{service}/twins (read now) and POST (keep one, delete the others). */
internal fun Web.apiTwins(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!(rest.size == 3 && rest[0] == "services" && rest[2] == "twins")) return false
    val p = twinServices().firstOrNull { it.id == rest[1] } ?: return true.also { error(404, "No connected service with that id that lets Trackhop delete playlists.") }
    when (method) {
        "GET" -> {
            val groups = runCatching { runBlocking { PlaylistTwins.find(env, p) } }.getOrElse { return true.also { _ -> error(502, it.message ?: it.javaClass.simpleName) } }
            TwinsServer.found[env.owner + p.id] = groups
            json(200, JsonArray(groups.map { g ->
                buildJsonObject {
                    put("group", g.key); put("sameName", g.sameName); put("keep", g.copies[g.keep].playlist.id)
                    put("copies", JsonArray(g.copies.map { c ->
                        buildJsonObject {
                            put("id", c.playlist.id); put("name", c.playlist.name); put("tracks", c.tracks.size)
                            put("syncs", JsonArray(c.syncs.map { JsonPrimitive(it) }))
                            put("notInKept", if (c === g.copies[g.keep]) 0 else PlaylistTwins.missing(g.copies[g.keep].tracks, c.tracks).size)
                        }
                    }))
                }
            }))
        }
        "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"group\", \"keep\", \"addMissing\"?}.") }
            val group = TwinsServer.found[env.owner + p.id]?.firstOrNull { it.key == b.text("group") } ?: return true.also { error(409, "Read the copies first: GET /services/${p.id}/twins.") }
            val gone = runCatching { twinsDelete(p, group, b.text("keep").orEmpty(), b.flag("addMissing") ?: true) }.getOrElse { return true.also { _ -> error(400, it.message ?: "Error") } }
            json(200, buildJsonObject { put("deleted", JsonArray(gone.map { JsonPrimitive(it) })) })
        }
        else -> error(405, "GET or POST.")
    }
    return true
}

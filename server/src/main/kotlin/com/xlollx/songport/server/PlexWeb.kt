package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Plex (2.2): "Sounds like" and "Sonic adventure" from the server's sonic analysis (Plex Pass), and a
 * playlist copied to a user of the server. Tools › Sounds like (Plex), the API under /plex/{account},
 * and `trackhop plex` (CliConn.kt).
 */

internal fun Web.plexAccounts(): List<PlexProvider> = Providers.connectors().filterIsInstance<PlexProvider>().filter { it.isConnected(env) }

/** The tool's card under Discover, when a Plex server is connected. */
internal fun Web.plexToolCard(): String = if (plexAccounts().isEmpty()) "" else
    """<div class="card"><h3><a href="/tools/plex">Sounds like (Plex)</a></h3><p class="muted">Songs that sound like one you pick, or a journey from one song to another, from your Plex server's sonic analysis. Also: copy a playlist to a user of the server.</p></div>"""

private fun line(t: Track) = "${h(t.artistLine)} — ${h(t.title)}"

/** GET /tools/plex: the form, then the songs; POST /tools/plex/save and /tools/plex/copy. */
internal fun Web.plexPage(r: Web.Req, rest: List<String>) {
    val accounts = plexAccounts()
    if (accounts.isEmpty()) return send(r.ex, 200, page("Sounds like (Plex)", """<div class="card muted">Connect a Plex server first (Services).</div>"""))
    val asked = r.form["account"] ?: r.query["account"]
    // A link, save or copy that names its server: never another one in its place (the same ids are other
    // songs and playlists there, and the page's copy form would then send that other server).
    val plex = accounts.firstOrNull { it.id == asked }
        ?: if (asked != null) return redirect(r.ex, "/tools/plex?err=${q("That Plex server is no longer connected.")}") else accounts.first()
    when (rest.firstOrNull()) {
        "save" -> {
            val ids = r.form["ids"].orEmpty().split(',').filter { it.isNotBlank() }
            val name = r.form["name"]?.trim().orEmpty().ifEmpty { "Sounds like" }
            val made = runCatching { runBlocking { plex.createPlaylist(env, name, "Made by Trackhop from Plex's sonic analysis").also { p -> plex.addTracks(env, p.id, ids.map { Track(id = it, title = "") }) } } }
            return made.fold(
                { redirect(r.ex, "/tools/plex?account=${q(plex.id)}&ok=${q("Saved as \"$name\" on Plex (${ids.size} songs).")}") },
                { redirect(r.ex, "/tools/plex?account=${q(plex.id)}&err=${q(it.message ?: it.javaClass.simpleName)}") },
            )
        }
        "copy" -> {
            val made = runCatching { runBlocking { plex.copyToUser(env, r.form["playlist"].orEmpty(), r.form["user"].orEmpty()) } }
            return made.fold(
                { redirect(r.ex, "/tools/plex?account=${q(plex.id)}&ok=${q("Copied: \"${it.name}\".")}") },
                { redirect(r.ex, "/tools/plex?account=${q(plex.id)}&err=${q(it.message ?: it.javaClass.simpleName)}") },
            )
        }
        null -> {}
        else -> return send(r.ex, 404, errorPage(404))
    }
    val adventure = r.query["mode"] == "adventure"
    val song = r.query["song"]?.trim().orEmpty()
    val to = r.query["to"]?.trim().orEmpty()
    val body = StringBuilder()
    r.query["ok"]?.let { body.append("""<div class="card ok">${h(it)}</div>""") }
    r.query["err"]?.let { body.append("""<div class="card err">${h(it)}</div>""") }
    val accountField = if (accounts.size > 1) """<label>Server</label><select name="account">${accounts.joinToString("") { """<option value="${h(it.id)}"${if (it.id == plex.id) " selected" else ""}>${h(it.label(env))}</option>""" }}</select>"""
        else """<input type="hidden" name="account" value="${h(plex.id)}">"""
    body.append(
        """<form method="get" action="/tools/plex" class="card">$accountField
<label>Song</label><input type="text" name="song" value="${h(song)}" placeholder="Teardrop" required>
<p><label><input type="radio" name="mode" value="like"${if (!adventure) " checked" else ""}> Sounds like</label>
<label><input type="radio" name="mode" value="adventure"${if (adventure) " checked" else ""}> Sonic adventure to</label></p>
<input type="text" name="to" value="${h(to)}" placeholder="Roads (Sonic adventure only)">
<p><button>Find</button></p><p class="muted">Needs a Plex Pass server that analysed its music (sonic analysis).</p></form>""",
    )
    if (song.isNotEmpty()) {
        val result = runCatching {
            runBlocking {
                val from = r.query["fromId"]?.let { id -> Track(id = id, title = song) } ?: plex.findSongs(env, song).firstOrNull()
                    ?: throw IllegalStateException("No song called \"$song\" on ${plex.label(env)}.")
                val list = if (adventure) {
                    val end = plex.findSongs(env, to.ifEmpty { throw IllegalStateException("Say where the adventure ends.") }).firstOrNull()
                        ?: throw IllegalStateException("No song called \"$to\" on ${plex.label(env)}.")
                    plex.sonicAdventure(env, from.id, end.id)
                } else plex.soundsLike(env, from.id)
                from to list
            }
        }
        result.exceptionOrNull()?.let { body.append("""<div class="card err">${h(it.message ?: it.javaClass.simpleName)}</div>""") }
        result.getOrNull()?.let { (from, list) ->
            body.append("""<div class="card"><h3>${if (adventure) "From " + line(from) else "Sounds like " + line(from)}</h3>""")
            if (list.isEmpty()) body.append("""<p class="muted">Plex gave nothing: the server may not have analysed this song yet.</p>""")
            else body.append("<ol>" + list.joinToString("") { "<li>${line(it)}</li>" } + "</ol>")
            if (list.isNotEmpty()) body.append(
                """<form method="post" action="/tools/plex/save"><input type="hidden" name="account" value="${h(plex.id)}"><input type="hidden" name="ids" value="${h(list.joinToString(",") { it.id })}">
<label>Name</label><input type="text" name="name" value="${h(if (adventure) "${from.title} → $to" else "Sounds like ${from.title}")}"><p><button>Save as a Plex playlist</button></p></form>""",
            )
            body.append("</div>")
        }
    }
    // Copy a playlist to a user of the server.
    val users = runCatching { runBlocking { plex.homeUsers(env) } }
    val playlists = runCatching { runBlocking { plex.playlists(env) } }.getOrDefault(emptyList())
    body.append("""<h2 id="copy">Copy a playlist to a Plex Home user</h2>""")
    users.exceptionOrNull()?.let { body.append("""<div class="card muted">${h(it.message ?: it.javaClass.simpleName)}</div>""") }
    users.getOrNull()?.let { list ->
        if (list.isEmpty()) body.append("""<div class="card muted">This server is not shared with anyone yet.</div>""")
        else body.append(
            """<form method="post" action="/tools/plex/copy" class="card"><input type="hidden" name="account" value="${h(plex.id)}">
<label>Playlist</label><select name="playlist">${playlists.joinToString("") { """<option value="${h(it.id)}"${if (it.id == r.query["copy"]) " selected" else ""}>${h(it.name)}</option>""" }}</select>
<label>To</label><select name="user">${list.joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }}</select>
<p><button>Copy</button></p><p class="muted">The copy goes in their library on this server, with the same songs.</p></form>""",
        )
    }
    body.append("""<p class="muted">API: GET /api/v1/plex/{account}/sounds-like?track=…, /sonic-adventure?from=…&amp;to=…, /home-users; POST /plex/{account}/playlists/{id}/copy · CLI: trackhop plex</p>""")
    send(r.ex, 200, page("Sounds like (Plex)", body.toString()))
}

/** The API: /plex/{account}/search|sounds-like|sonic-adventure|home-users, POST /plex/{account}/playlists/{id}/copy. */
internal fun Web.apiPlex(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "plex" || rest.size < 3) return false
    val plex = (Providers.byId(rest[1]) as? PlexProvider)?.takeIf { it.isConnected(env) }
        ?: return true.also { error(404, "No connected Plex account with that id: see GET /api/v1/services.") }
    fun tracks(list: List<Track>) = JsonArray(list.map { trackRef(it) })
    fun call(block: suspend () -> JsonElement) {
        runCatching { runBlocking { block() } }.fold({ json(200, it) }, { error(502, "${plex.label(env)}: ${it.message ?: it.javaClass.simpleName}") })
    }
    when {
        rest.size == 3 && rest[2] == "search" && method == "GET" -> {
            val q = query["q"]?.trim().orEmpty().ifEmpty { return true.also { error(400, "Say what to look for: ?q=Title.") } }
            call { tracks(plex.findSongs(env, q)) }
        }
        rest.size == 3 && rest[2] == "sounds-like" && method == "GET" -> {
            val id = query["track"].orEmpty().ifEmpty { return true.also { error(400, "Say which song: ?track=<its id on Plex> (GET …/search?q= finds it).") } }
            call { tracks(plex.soundsLike(env, id, query["limit"]?.toIntOrNull() ?: 25)) }
        }
        rest.size == 3 && rest[2] == "sonic-adventure" && method == "GET" -> {
            val from = query["from"].orEmpty(); val to = query["to"].orEmpty()
            if (from.isEmpty() || to.isEmpty()) return true.also { error(400, "Say both ends: ?from=<id>&to=<id>.") }
            call { tracks(plex.sonicAdventure(env, from, to)) }
        }
        rest.size == 3 && rest[2] == "home-users" && method == "GET" ->
            call { JsonArray(plex.homeUsers(env).map { u -> buildJsonObject { put("id", u.id); put("name", u.name) } }) }
        rest.size == 5 && rest[2] == "playlists" && rest[4] == "copy" && method == "POST" -> {
            val user = apiJson(ex)?.text("user") ?: return true.also { error(400, "Send JSON: {\"user\": \"<id from home-users>\"}.") }
            call {
                val p = plex.copyToUser(env, rest[3], user)
                buildJsonObject { put("id", p.id); put("name", p.name); put("tracks", p.trackCount) }
            }
        }
        else -> return false
    }
    return true
}

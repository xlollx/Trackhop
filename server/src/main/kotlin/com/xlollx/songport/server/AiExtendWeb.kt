package com.xlollx.songport.server

import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.AiMatch
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LibraryTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Extend with AI" on any playlist of a service (the app's AiExpandPlaylistDialog): the description
 * (or, left empty, the spirit of the songs already there) goes to the user's own AI with the songs
 * the playlist holds; what it proposes is searched on the service, and only the songs found are
 * added, at the end of the same playlist or into a new one. Nothing the playlist already has twice.
 */
internal object AiExtend {
    const val TITLE = "Extend with AI"

    /** The playlists that can grow: on a service that writes (File too). */
    fun canExtend(env: ServerEnv, p: MusicProvider) = p.canWrite && !p.linkOnly && (!p.requiresAuth || p.isConnected(env))

    data class Done(val playlist: String, val added: Int, val failed: List<String>, val tracks: List<Track>, val newPlaylist: Boolean)

    suspend fun run(env: ServerEnv, c: AiClient.Config, p: MusicProvider, playlistId: String, description: String, count: Int, newName: String?, progress: (String) -> Unit): Done {
        progress("Reading the playlist…")
        val existing = p.tracks(env, playlistId)
        val names = existing.map { it.toString() }.take(300)
        val prompt = AiClient.Prompt(description, count, emptyList(), "", names)
        progress("Asking ${c.vendor.label} (${c.model})…")
        val found = if (p is LocalFilesProvider) AiMatch.fresh(AiClient.generateAll(c, prompt), existing)
            else AiMatch.available(env, c, p, prompt, existing) { f, want -> progress("Found on ${p.label(env)}: $f of $want") }.found
        if (found.isEmpty()) throw IllegalStateException("The AI gave nothing new that ${p.label(env)} has: try a description.")
        if (newName != null) {
            if (!p.canCreatePlaylists) throw IllegalStateException("${p.label(env)} cannot create playlists.")
            progress("Creating “$newName”…")
            val made = LibraryTools.createWith(env, p, newName, description, found) { x -> progress("Adding ${x.done}/${x.total}") }
            return Done(made.playlist.name, made.added, made.failed, found, true)
        }
        progress("Adding ${found.size} songs…")
        p.addTracks(env, playlistId, found)
        return Done(playlistId, found.size, emptyList(), found, false)
    }

    fun html(env: ServerEnv, p: MusicProvider, name: String, d: Done): String = buildString {
        append("""<div class="card ok">${d.added} ${if (d.added == 1) "song" else "songs"} added to <b>${h(if (d.newPlaylist) d.playlist else name)}</b>${if (d.newPlaylist) " (a new playlist)" else ""} on ${h(p.label(env))}.</div>""")
        if (d.failed.isNotEmpty()) append("""<div class="card err">Not added: ${h(d.failed.take(20).joinToString(", "))}</div>""")
        append("<div class=\"card\"><ol>")
        d.tracks.take(200).forEach { append("<li>${h(it.artistLine)} — ${h(it.title)}</li>") }
        append("</ol></div>")
    }
}

/** The generator page's "Extend a playlist" card: a service first, then its playlist, the words and how many. */
internal fun Web.aiExtendCard(r: Web.Req, config: AiClient.Config?): String {
    if (config?.complete != true) return ""
    val services = Providers.connectors().filter { AiExtend.canExtend(env, it) }
    if (services.isEmpty()) return ""
    val chosen = r.query["extend"]?.let { id -> services.firstOrNull { it.id == id } }
    if (chosen == null) {
        val opts = services.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        return """<h2 id="extend">Extend a playlist</h2><form method="get" action="/tools/ai#extend" class="card"><p class="muted">More songs in the same spirit, added at the end of one of your playlists or into a new one.</p><label>Service</label><select name="extend">$opts</select><p><button class="secondary">Next</button></p></form>"""
    }
    val lists = runCatching { runBlocking { chosen.playlists(env).filter { it.ownedByMe } } }.getOrElse {
        return """<h2 id="extend">Extend a playlist</h2><div class="card err">${h(chosen.label(env))}: ${h(it.message)}</div>"""
    }
    val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
    return """<h2 id="extend">Extend a playlist</h2><form method="post" action="/tools/ai" class="card"><input type="hidden" name="action" value="extend"><input type="hidden" name="service" value="${h(chosen.id)}">
<label>Playlist on ${h(chosen.label(env))}</label><select name="playlist" required>$opts</select>
<label>What to add (optional)</label><textarea name="description" rows="2" placeholder="Leave empty for more of the same"></textarea>
<label>How many songs</label><input type="text" name="count" value="10" inputmode="numeric">
<label class="check"><input type="radio" name="into" value="end" checked> Add them at the end of this playlist</label>
${if (chosen.canCreatePlaylists) """<label class="check"><input type="radio" name="into" value="new"> Into a new playlist</label>""" else ""}
<p class="muted">The names of the songs already there go to ${h(config.vendor.label)} with your words; what it proposes is searched on ${h(chosen.label(env))}, and only the songs found are added.</p>
<p><button>Extend</button> <a href="/tools/ai#extend">Another service</a></p></form>"""
}

/** Starts an extension as a background task; the task's id, or an error in words. */
internal fun Web.startAiExtend(serviceId: String, playlistId: String, description: String, count: Int, intoNew: Boolean, newName: String?): Result<Tasks.Task> = runCatching {
    val c = AiClient.config(env)?.takeIf { it.complete } ?: throw IllegalArgumentException("Set up your AI first (Tools › Playlist generator).")
    val p = Providers.byId(serviceId)?.takeIf { AiExtend.canExtend(env, it) } ?: throw IllegalArgumentException("Pick a connected service that can take songs.")
    val lists = runBlocking { p.playlists(env) }
    val pl = lists.firstOrNull { it.id == playlistId } ?: lists.filter { it.name.equals(playlistId, ignoreCase = true) }.singleOrNull()
        ?: throw IllegalArgumentException("${p.label(env)} has no playlist \"$playlistId\".")
    val name = if (intoNew) newName?.trim()?.takeIf { it.isNotEmpty() } ?: "${pl.name} (more)" else null
    Tasks.start(AiExtend.TITLE, "/tools/ai", { env.log("ai", it) }, env.owner, env.marks) { t ->
        val d = AiExtend.run(env, c, p, pl.id, description, count.coerceIn(1, 200), name) { t.progress = it }
        env.log("ai", "${p.label(env)}: ${pl.name} extended with ${d.added} songs")
        AiExtend.html(env, p, pl.name, d)
    }
}

internal fun Web.aiExtendPost(r: Web.Req) {
    val count = r.form["count"]?.toIntOrNull() ?: 10
    startAiExtend(r.form["service"].orEmpty(), r.form["playlist"].orEmpty(), r.form["description"].orEmpty().trim(), count, r.form["into"] == "new", null)
        .fold({ redirect(r.ex, "/tools/task/${q(it.id)}") }, { redirect(r.ex, "/tools/ai?err=${q(it.message ?: "Error")}") })
}

internal fun Web.apiAiExtend(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tools", "ai", "extend") || method != "POST") return false
    val b = body() ?: return true.also { error(400, "Send JSON: {\"service\", \"playlist\", \"description\"?, \"count\"?, \"into\"?: \"end\" | \"new\", \"name\"?}.") }
    val into = b.text("into") ?: "end"
    if (into !in setOf("end", "new")) return true.also { error(400, "\"into\" is end or new.") }
    startAiExtend(b.text("service").orEmpty(), b.text("playlist").orEmpty(), b.text("description").orEmpty(), b.number("count") ?: 10, into == "new", b.text("name"))
        .fold({ json(202, buildJsonObject { put("task", it.id) }) }, { error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") })
    return true
}

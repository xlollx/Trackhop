package com.xlollx.songport.server

import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.AiRoast
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Roast my playlist (the app's roast card, [AiRoast]): your own AI teases a playlist, gently,
 * spicy or savage; explicit words only when asked. Only the playlist's name and a summary of its
 * songs go to the AI.
 */
private fun Web.roastSources() = Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly }

/** The roast of [playlistId] on [serviceId], in English; throws IllegalArgumentException for what the caller got wrong. */
internal fun Web.roast(serviceId: String, playlistId: String, level: AiRoast.Level, explicit: Boolean): Pair<String, String> {
    val c = AiClient.config(env)?.takeIf { it.complete } ?: throw IllegalArgumentException("Set up your AI first (Tools › Playlist generator).")
    val p = roastSources().firstOrNull { it.id == serviceId } ?: throw IllegalArgumentException("Pick a connected service.")
    return runBlocking {
        val all = p.libraryEntries(env, false) + p.playlists(env)
        val pl = all.firstOrNull { it.id == playlistId } ?: all.filter { it.name.equals(playlistId, ignoreCase = true) }.singleOrNull() ?: throw IllegalArgumentException("${p.label(env)} has no playlist \"$playlistId\".")
        val tracks = p.tracks(env, pl.id)
        if (tracks.isEmpty()) throw IllegalArgumentException("${pl.name} is empty: nothing to roast.")
        pl.name to try { AiRoast.roast(c, pl.name, tracks, "English", level, explicit) } catch (e: AiRoast.Refused) { throw IllegalStateException("Your AI would not write a roast of it: try another level or model.") }
    }
}

internal fun Web.roastPage(r: Web.Req) {
    val title = "Roast my playlist"
    val config = AiClient.config(env)
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">Your own AI teases one of your playlists: gently, spicy or savage. Only the playlist's name and a summary of its songs go to it.</p>""")
    if (config?.complete != true) {
        body.append("""<div class="card">Set up your AI first: <a href="/tools/ai">Playlist generator</a> (free tiers and local models work).</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val sources = roastSources()
    val p = sources.firstOrNull { it.id == (r.form["service"] ?: r.query["service"]) }
    if (r.method == "POST" && p != null) {
        val level = AiRoast.Level.entries.firstOrNull { it.name == r.form["level"] } ?: AiRoast.Level.DEFAULT
        val task = Tasks.start(title, "/tools/roast?service=${q(p.id)}", { env.log("roast", it) }, env.owner, env.marks) { t ->
            t.progress = "Asking ${config.vendor.label}…"
            val (name, text) = roast(p.id, r.form["playlist"].orEmpty(), level, r.form["explicit"] == "on")
            """<div class="card"><h3>${h(name)} <span class="badge">${h(AiRoast.badge(level.label, level))}</span></h3><p style="white-space:pre-line">${h(text)}</p><p class="muted">Written by ${h(config.vendor.label)} (${h(config.model)}).</p></div>"""
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    if (p == null) {
        val opts = sources.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/roast" class="card"><label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val lists = runCatching { runBlocking { p.libraryEntries(env, false) + p.playlists(env) } }.getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(it.message)}</div>""")) }
    val opts = lists.joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }
    val levels = AiRoast.Level.entries.joinToString("") { l -> """<option value="${l.name}"${if (l == AiRoast.Level.DEFAULT) " selected" else ""}>${h(AiRoast.badge(l.label, l))}</option>""" }
    body.append("""<form method="post" action="/tools/roast" class="card"><input type="hidden" name="service" value="${h(p.id)}">
<label>Playlist on ${h(p.label(env))}</label><select name="playlist">$opts</select>
<label>How hard</label><select name="level">$levels</select>
<label class="check"><input type="checkbox" name="explicit"> Swear words allowed</label>
<p><button>Roast it</button> <a href="/tools/roast">Another service</a></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

/** POST /tools/roast */
internal fun Web.apiRoast(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tools", "roast") || method != "POST") return false
    val b = body() ?: return true.also { error(400, "Send JSON: {\"service\", \"playlist\", \"level\"?, \"explicit\"?}.") }
    val level = b.text("level")?.let { l -> AiRoast.Level.entries.firstOrNull { it.name.equals(l, ignoreCase = true) } ?: return true.also { error(400, "\"level\" is gentle, spicy or savage.") } } ?: AiRoast.Level.DEFAULT
    val (name, text) = runCatching { roast(b.text("service").orEmpty(), b.text("playlist").orEmpty(), level, b.flag("explicit") == true) }
        .getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
    json(200, buildJsonObject { put("playlist", name); put("level", level.name.lowercase()); put("roast", text) })
    return true
}

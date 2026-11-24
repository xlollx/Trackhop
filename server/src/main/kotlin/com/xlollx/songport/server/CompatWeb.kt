package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Compatibility
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.PlaylistLinks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Compatibility (the app's Cards › Compatibility, [Compatibility]): two playlists, yours and a
 * friend's (one of yours, or a public link), side by side: a score, the songs and artists in
 * common, and what each would likely enjoy from the other. The songs in common can become a playlist.
 */
internal object Compat {
    data class Side(val provider: MusicProvider, val playlistId: String, val name: String)
    data class Done(val a: Side, val b: Side, val result: Compatibility.Result)
    val last = ConcurrentHashMap<String, Done>()

    fun readable(env: ServerEnv) = (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" }

    /** A side from a service and a playlist (its id or name), or from a playlist's public link. */
    suspend fun side(env: ServerEnv, service: String?, playlist: String?, link: String?): Side {
        if (!link.isNullOrBlank()) {
            val ref = PlaylistLinks.parse(link) ?: throw IllegalArgumentException("Not a link to a playlist.")
            val p = Providers.byId(ref.providerId)?.takeIf { it.linkOnly || !it.requiresAuth || it.isConnected(env) }
                ?: throw IllegalArgumentException("${Providers.byId(ref.providerId)?.label(env) ?: ref.providerId} is not connected here: connect it to read that link.")
            val info = runCatching { p.playlistInfo(env, ref.playlistId) }.getOrNull()
            return Side(p, info?.id ?: ref.playlistId, info?.name ?: "Their playlist")
        }
        val p = readable(env).firstOrNull { it.id == service } ?: throw IllegalArgumentException("Pick a connected service.")
        val all = p.libraryEntries(env, false) + p.playlists(env)
        val pl = all.firstOrNull { it.id == playlist } ?: all.filter { it.name.equals(playlist.orEmpty(), ignoreCase = true) }.singleOrNull()
            ?: throw IllegalArgumentException("${p.label(env)} has no playlist \"${playlist.orEmpty()}\".")
        return Side(p, pl.id, pl.name)
    }

    suspend fun run(env: ServerEnv, a: Side, b: Side): Done = Done(a, b, Compatibility.compare(a.provider.tracks(env, a.playlistId), b.provider.tracks(env, b.playlistId)))
}

private fun songs(list: List<Track>) = list.joinToString("") { "<li>${h(it.artistLine)} – ${h(it.title)}</li>" }

internal fun Web.compatPage(r: Web.Req) {
    val title = "Compatibility"
    val services = Compat.readable(env)
    if (r.method == "POST") {
        if (r.form["action"] == "make") {
            val d = Compat.last[env.owner] ?: return redirect(r.ex, "/tools/compat?err=${q("That comparison is no longer kept: compare again.")}")
            val p = d.a.provider.takeIf { it.canWrite && it.canCreatePlaylists } ?: LocalFilesProvider
            val task = Tasks.start(title, "/tools/compat", { env.log("compat", it) }, env.owner, env.marks) { t ->
                val made = LibraryTools.createWith(env, p, "${d.a.name} + ${d.b.name}", "Songs in common", d.result.common.map { it.a }) { x -> t.progress = "Adding ${x.done}/${x.total}" }
                """<div class="card ok"><b>${h(made.playlist.name)}</b> on ${h(p.label(env))}: ${made.added} songs in common.</div>"""
            }
            return redirect(r.ex, "/tools/task/${q(task.id)}")
        }
        val task = Tasks.startOnce(title, "/tools/compat", { env.log("compat", it) }, env.owner, env.marks) { t ->
            t.progress = "Reading both playlists…"
            val a = Compat.side(env, r.form["a"], r.form["pa"], null)
            val b = Compat.side(env, r.form["b"], r.form["pb"], r.form["link"])
            val d = Compat.run(env, a, b)
            Compat.last[env.owner] = d
            compatHtml(d)
        } ?: return redirect(r.ex, "/tools/compat?err=${q("A comparison is already running.")}")
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">Your playlist and a friend's side by side: a score, the songs and artists in common, and what each would likely enjoy from the other. Theirs can be one of yours or a public link.</p>""")
    if (services.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val a = services.firstOrNull { it.id == r.query["a"] }
    val b = services.firstOrNull { it.id == r.query["b"] } ?: a
    fun opts(sel: MusicProvider?) = services.joinToString("") { """<option value="${h(it.id)}"${if (it === sel) " selected" else ""}>${h(it.label(env))}</option>""" }
    if (a == null) {
        body.append("""<form method="get" action="/tools/compat" class="card"><label>Yours, on</label><select name="a">${opts(null)}</select><label>Theirs, on (or a link on the next page)</label><select name="b">${opts(null)}</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    fun lists(p: MusicProvider) = runCatching { runBlocking { p.libraryEntries(env, false) + p.playlists(env) } }.getOrDefault(emptyList()).joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }
    body.append("""<form method="post" action="/tools/compat" class="card"><input type="hidden" name="a" value="${h(a.id)}"><input type="hidden" name="b" value="${h(b!!.id)}">
<label>Yours, on ${h(a.label(env))}</label><select name="pa">${lists(a)}</select>
<label>Theirs, on ${h(b.label(env))}</label><select name="pb">${lists(b)}</select>
<label for="clink">Or their playlist's public link</label><input type="url" id="clink" name="link" placeholder="https://open.spotify.com/playlist/…">
<p><button>Compare</button> <a href="/tools/compat">Other services</a></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

private fun Web.compatHtml(d: Compat.Done): String = buildString {
    val res = d.result
    append("""<div class="card"><div class="stats"><p class="stat"><b>${res.score}%</b><span class="muted">compatible</span></p><p class="stat"><b>${res.common.size}</b><span class="muted">songs in common (${res.commonPercent}%)</span></p><p class="stat"><b>${res.sharedArtists.size}</b><span class="muted">artists in common</span></p></div>""")
    append("""<p class="muted">${h(d.a.name)} (${res.sizeA}) and ${h(d.b.name)} (${res.sizeB}).</p>""")
    if (res.sharedArtists.isNotEmpty()) append("""<p>Artists in common: ${h(res.sharedArtists.take(20).joinToString(", "))}</p>""")
    if (res.forA.isNotEmpty()) append("""<h3>For you, from theirs</h3><ul>${songs(res.forA)}</ul>""")
    if (res.forB.isNotEmpty()) append("""<h3>For them, from yours</h3><ul>${songs(res.forB)}</ul>""")
    if (res.common.isNotEmpty()) append("""<details><summary>The ${res.common.size} songs in common</summary><ul>${songs(res.common.map { it.a })}</ul></details><p>${Html.postButton("/tools/compat", "Make a playlist of the songs in common", "secondary", mapOf("action" to "make"))}</p>""")
    append("</div>")
}

/** POST /tools/compatibility */
internal fun Web.apiCompat(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tools", "compatibility") || method != "POST") return false
    val b = body() ?: return true.also { error(400, "Send JSON: {\"a\": {\"service\", \"playlist\"}, \"b\": {\"service\", \"playlist\"} | {\"link\"}}.") }
    val sa = b["a"] as? JsonObject ?: return true.also { error(400, "\"a\" is {\"service\", \"playlist\"}.") }
    val sb = b["b"] as? JsonObject ?: return true.also { error(400, "\"b\" is {\"service\", \"playlist\"} or {\"link\"}.") }
    val d = runCatching {
        runBlocking { Compat.run(env, Compat.side(env, sa.text("service"), sa.text("playlist"), sa.text("link")), Compat.side(env, sb.text("service"), sb.text("playlist"), sb.text("link"))) }
    }.getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
    Compat.last[env.owner] = d
    val res = d.result
    json(200, buildJsonObject {
        put("score", res.score); put("songsA", res.sizeA); put("songsB", res.sizeB); put("commonPercent", res.commonPercent)
        put("common", JsonArray(res.common.map { trackRef(it.a) }))
        put("sharedArtists", JsonArray(res.sharedArtists.map { JsonPrimitive(it) }))
        put("forA", JsonArray(res.forA.map { trackRef(it) })); put("forB", JsonArray(res.forB.map { trackRef(it) }))
    })
    return true
}

package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.PlaylistOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * "New playlist from…" (the app's MakePlaylist, [PlaylistOps]): a copy, several merged, one split
 * into parts, sorted or shuffled. Always a new playlist on the same service, so the songs' ids stay
 * valid (nothing is searched) and the original does not change.
 */
internal object PlaylistMake {
    val OPS = listOf("copy", "merge", "split", "sort", "shuffle")
    val SORTS = PlaylistOps.SortKey.entries.associateBy { it.name.lowercase() }

    /** The playlists to make: name and songs, from [sources] (the first is the playlist itself). */
    fun parts(op: String, name: String, sources: List<List<Track>>, size: Int?, sort: PlaylistOps.SortKey, dropDuplicates: Boolean): List<Pair<String, List<Track>>> = when (op) {
        "copy" -> listOf(name to sources.first())
        "merge" -> { require(sources.size >= 2) { "Pick at least one more playlist to merge with." }; listOf(name to PlaylistOps.merge(sources, dropDuplicates)) }
        "split" -> { val n = size?.takeIf { it > 0 } ?: throw IllegalArgumentException("Say how many songs in each part."); PlaylistOps.split(sources.first(), n).mapIndexed { i, part -> "$name (part ${i + 1})" to part } }
        "sort" -> listOf(name to PlaylistOps.sort(sources.first(), sort))
        "shuffle" -> listOf(name to PlaylistOps.shuffle(sources.first()))
        else -> throw IllegalArgumentException("\"op\" is one of ${OPS.joinToString(", ")}.")
    }

    fun defaultName(op: String, first: String, sort: PlaylistOps.SortKey): String = when (op) {
        "copy" -> "$first (copy)"
        "merge" -> "$first (merged)"
        "sort" -> "$first (by ${sort.name.lowercase()})"
        "shuffle" -> "$first (shuffled)"
        else -> first
    }
}

/** Starts the making as a background task; the task, or an error in words. */
internal fun Web.startMake(p: MusicProvider, first: Playlist, op: String, with: List<String>, size: Int?, sortKey: String?, dropDuplicates: Boolean, name: String?): Result<Tasks.Task> = runCatching {
    if (!p.canWrite || !p.canCreatePlaylists) throw IllegalArgumentException("${p.label(env)} cannot create playlists.")
    if (op !in PlaylistMake.OPS) throw IllegalArgumentException("\"op\" is one of ${PlaylistMake.OPS.joinToString(", ")}.")
    val sort = sortKey?.let { PlaylistMake.SORTS[it.lowercase()] ?: throw IllegalArgumentException("\"by\" is one of ${PlaylistMake.SORTS.keys.joinToString(", ")}.") } ?: PlaylistOps.SortKey.ARTIST
    val all = runBlocking { p.libraryEntries(env, false) + p.playlists(env) }
    val others = with.filter { it != first.id }.map { w -> all.firstOrNull { it.id == w } ?: all.filter { it.name.equals(w, ignoreCase = true) }.singleOrNull() ?: throw IllegalArgumentException("${p.label(env)} has no playlist \"$w\".") }
    if (op == "merge" && others.isEmpty()) throw IllegalArgumentException("Pick at least one more playlist to merge with.")
    if (op == "split" && (size ?: 0) <= 0) throw IllegalArgumentException("Say how many songs in each part.")
    val finalName = name?.trim()?.takeIf { it.isNotEmpty() } ?: PlaylistMake.defaultName(op, first.name, sort)
    Tasks.start("New playlist from ${first.name}", "/playlists/${q(p.id)}/${q(first.id)}", { env.log("playlists", it) }, env.owner, env.marks) { t ->
        t.progress = "Reading the songs…"
        val sources = (listOf(first) + if (op == "merge") others else emptyList()).map { p.tracks(env, it.id) }
        val parts = PlaylistMake.parts(op, finalName, sources, size, sort, dropDuplicates)
        val made = parts.map { (n, tracks) ->
            t.progress = "Creating “$n”…"
            LibraryTools.createWith(env, p, n, MusicProvider.description("Trackhop"), tracks) { x -> t.progress = "“$n”: adding ${x.done}/${x.total}" }
        }
        env.log("playlists", "${p.label(env)}: ${made.size} playlists made from ${first.name} ($op)")
        made.joinToString("") { m ->
            """<div class="card ok"><a href="/playlists/${q(p.id)}/${q(m.playlist.id)}"><b>${h(m.playlist.name)}</b></a> on ${h(p.label(env))}: ${m.added} songs.${if (m.failed.isNotEmpty()) """ <span class="err">Not added: ${h(m.failed.take(10).joinToString(", "))}</span>""" else ""}</div>"""
        }
    }
}

/** The playlist page's card: "New playlist from this one". */
internal fun Web.makeCard(p: MusicProvider, playlist: Playlist, back: String): String {
    if (!p.canWrite || !p.canCreatePlaylists) return ""
    val others = runCatching { runBlocking { p.playlists(env) } }.getOrDefault(emptyList()).filter { it.id != playlist.id }
    val otherOpts = others.joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }
    val sorts = PlaylistMake.SORTS.keys.joinToString("") { """<option value="$it">${it.replaceFirstChar { c -> c.uppercase() }.let { s -> if (s == "Reverse") "Reverse order" else if (s == "Added") "Date added" else s }}</option>""" }
    fun op(v: String, text: String, checked: Boolean = false) = """<label class="check"><input type="radio" name="op" value="$v"${if (checked) " checked" else ""}> $text</label>"""
    return """<details class="card"><summary><b>New playlist from this one</b></summary><form method="post" action="$back"><input type="hidden" name="action" value="make">
<p class="muted">Always a new playlist on ${h(p.label(env))}: this one stays as it is.</p>
${op("copy", "Copy", true)}
${if (others.isNotEmpty()) op("merge", """Merge with <select name="with" multiple size="3" style="width:auto;vertical-align:top" aria-label="The playlists to merge with">$otherOpts</select> <label class="check inline"><input type="checkbox" name="dedupe" checked> without doubles</label>""") else ""}
${op("split", """Split every <input type="text" name="size" value="50" inputmode="numeric" style="width:5em" aria-label="Songs in each part"> songs""")}
${op("sort", """Sort by <select name="by" style="width:auto" aria-label="Sort by">$sorts</select>""")}
${op("shuffle", "Shuffle (never two songs of the same artist in a row, where it can)")}
<label>Name (optional)</label><input type="text" name="name" placeholder="${h(playlist.name)} (copy)">
<p><button>Make it</button></p></form></details>"""
}

internal fun Web.makePost(r: Web.Req, p: MusicProvider, playlist: Playlist, back: String) {
    val with = r.rawForm.filter { it.first == "with" }.map { it.second }
    startMake(p, playlist, r.form["op"].orEmpty(), with, r.form["size"]?.trim()?.toIntOrNull(), r.form["by"], r.form["dedupe"] == "on", r.form["name"])
        .fold({ redirect(r.ex, "/tools/task/${q(it.id)}") }, { redirect(r.ex, "$back?err=${q(it.message ?: "Error")}") })
}

/** POST /services/{service}/playlists/{playlist}/new */
internal fun Web.apiMake(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!(rest.size == 5 && rest[0] == "services" && rest[2] == "playlists" && rest[4] == "new" && method == "POST")) return false
    val p = com.xlollx.songport.providers.Providers.byId(rest[1])?.takeIf { !it.requiresAuth || it.isConnected(env) } ?: return true.also { error(404, "No connected service with that id: see GET /api/v1/services.") }
    val b = body() ?: return true.also { error(400, "Send JSON: {\"op\": \"copy\" | \"merge\" | \"split\" | \"sort\" | \"shuffle\", ...}.") }
    val all = runCatching { runBlocking { p.libraryEntries(env, false) + p.playlists(env) } }.getOrElse { return true.also { _ -> error(502, it.message ?: "Error") } }
    val first = all.firstOrNull { it.id == rest[3] } ?: all.filter { it.name.equals(rest[3], ignoreCase = true) }.singleOrNull() ?: return true.also { error(404, "${p.label(env)} has no playlist \"${rest[3]}\".") }
    val with = (b["with"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: listOfNotNull(b.text("with"))
    startMake(p, first, b.text("op") ?: "copy", with, b.number("size"), b.text("by"), b.flag("dropDuplicates") ?: true, b.text("name"))
        .fold({ json(202, buildJsonObject { put("task", it.id) }) }, { error(400, it.message ?: "Error") })
    return true
}

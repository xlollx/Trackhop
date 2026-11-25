package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Vanished
import com.xlollx.songport.sync.Wrapped
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Vanished songs (the app's Discover › Vanished songs, [Vanished]): what left your playlists, from
 * what this Trackhop already keeps: the dated backup copies of each playlist (Tools › Backup) and
 * the songs mirror syncs removed (their runs). Not the playlists that renew themselves (a daylist,
 * Discover Weekly): there songs come and go by design. A sync can be hidden from the list.
 */
internal object VanishedServer {
    data class Row(val track: Track, val where: String, val at: Long, val jobId: String? = null)

    fun rows(env: ServerEnv): List<Row> {
        val out = ArrayList<Row>()
        val sections = setOf("Saved albums", "Followed artists", "Podcasts") + listOf("saved_albums", "followed_artists", "followed_podcasts").map { env.text(it) }
        val groups = runCatching { LocalFilesProvider.entries(env) }.getOrDefault(emptyList()).filter { it.service != null && it.stamped }.groupBy { it.base }
        for ((_, versions) in groups) {
            if (versions.size < 2 || versions.first().title in sections) continue
            val copies = versions.mapNotNull { v -> runCatching { Vanished.Version(v.writtenAt, kotlinx.coroutines.runBlocking { LocalFilesProvider.tracks(env, v.id) }) }.getOrNull() }
            val first = versions.first()
            Vanished.between(first.title, first.service, copies).forEach { g -> out += Row(g.track, listOfNotNull(g.playlist, g.service).joinToString(" · "), g.lastSeen) }
        }
        val d = env.store.data
        val rotating = Vanished.rotatingSyncs(d.jobs, d.reports, d.settings.vanishedHiddenSyncs)
        for (r in d.reports) if (r.jobId !in rotating) for (t in r.removedTracks) out += Row(t, r.jobName, r.startedEpoch, r.jobId)
        return out.distinctBy { Wrapped.key(it.track) + "|" + it.where }.sortedByDescending { it.at }.take(300)
    }
}

internal fun Web.vanishedPage(r: Web.Req) {
    val back = "/tools/vanished"
    if (r.method == "POST") {
        when (r.form["action"]) {
            "hide" -> r.form["job"]?.takeIf { it.isNotBlank() }?.let { id -> env.store.updateSettings { it.copy(vanishedHiddenSyncs = it.vanishedHiddenSyncs + id) } }
            "show" -> env.store.updateSettings { it.copy(vanishedHiddenSyncs = emptySet()) }
            "save" -> {
                val rows = VanishedServer.rows(env).filter { r.form["where"].isNullOrEmpty() || it.where == r.form["where"] }
                if (rows.isEmpty()) return redirect(r.ex, "$back?err=${q("Nothing to save.")}")
                val id = LocalFilesProvider.importTracks(env, "Vanished songs", rows.map { it.track })
                return redirect(r.ex, "/connectors/csv?ok=${q("Saved as the File playlist $id (${rows.size} songs).")}")
            }
        }
        return redirect(r.ex, back)
    }
    val all = VanishedServer.rows(env)
    val place = r.query["where"]?.takeIf { w -> all.any { it.where == w } }
    val shown = all.filter { place == null || it.where == place }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The songs that left your playlists: from the dated copies of your backups (Tools › Backup) and from what mirror syncs removed. Not the playlists that renew themselves, such as a daylist or Discover Weekly.</p>""")
    if (env.store.data.settings.vanishedHiddenSyncs.isNotEmpty()) body.append("""<p class="muted">Some syncs are hidden from this list. ${Html.postButton(back, "Show them again", "link", mapOf("action" to "show"))}</p>""")
    if (all.isEmpty()) {
        body.append("""<div class="card">No song has vanished yet. Back up your playlists on a schedule, and the songs that leave them show here: <a href="/tools/backup">Backup</a>.</div>""")
        return send(r.ex, 200, page("Vanished songs", body.toString()))
    }
    val places = all.map { it.where }.distinct()
    if (places.size > 1) {
        fun chip(text: String, on: Boolean, href: String) = """<a class="chip${if (on) " on" else ""}" href="${h(href)}"${if (on) " aria-current=\"true\"" else ""}>${h(text)}</a>"""
        body.append("""<p class="chips">${chip("All (${all.size})", place == null, back)}${places.take(20).joinToString("") { w -> chip(w, w == place, "$back?where=${q(w)}") }}</p>""")
    }
    body.append("""<div class="card"><p><b>${shown.size}</b> ${if (shown.size == 1) "song" else "songs"} gone ${Html.postButton(back, "Save as a File playlist", "secondary", mapOf("action" to "save", "where" to (place ?: "")))}</p><ul>""")
    shown.forEach { row ->
        val hide = row.jobId?.let { " " + Html.postButton(back, "Not gone: this playlist renews itself", "link", mapOf("action" to "hide", "job" to it)) }.orEmpty()
        body.append("""<li><s>${h(row.track.artistLine)} – ${h(row.track.title)}</s> <span class="muted">· ${h(row.where)} · last seen ${Html.date(row.at)}</span>$hide</li>""")
    }
    body.append("</ul></div>")
    send(r.ex, 200, page("Vanished songs", body.toString()))
}

internal fun Web.apiVanished(method: String, rest: List<String>, json: (Int, JsonElement) -> Unit): Boolean {
    if (rest != listOf("vanished") || method != "GET") return false
    json(200, JsonArray(VanishedServer.rows(env).map { r ->
        buildJsonObject { put("track", trackRef(r.track)); put("where", r.where); put("lastSeen", r.at); put("sync", r.jobId?.let { JsonPrimitive(it) } ?: JsonNull) }
    }))
    return true
}

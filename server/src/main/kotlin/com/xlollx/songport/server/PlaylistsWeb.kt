package com.xlollx.songport.server

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * A service's playlists, as the app's Tools › Manage playlists and Transfer: list them, open one to
 * see its tracks, rename or delete it, take tracks out; or pick several and copy them to another
 * service at once (one sync each, run now, kept for later runs).
 */
internal fun Web.playlists(r: Web.Req, rest: List<String>) {
    val services = Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly }
    val service = (rest.getOrNull(0) ?: r.query["service"] ?: r.form["service"])?.let { id -> services.firstOrNull { it.id == id } }
    if (service == null) {
        val body = StringBuilder()
        if (services.isEmpty()) body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        services.forEach { p -> body.append("""<div class="card"><div class="who">${logo(p.id, 40)}<div><a href="/playlists/${q(p.id)}"><b>${h(p.label(env))}</b></a></div></div></div>""") }
        return send(r.ex, 200, page("Playlists", body.toString()))
    }
    val lists = runCatching { runBlocking { service.libraryEntries(env, false) + service.playlists(env) } }
        .getOrElse { return send(r.ex, 200, page(service.label(env), """<div class="card err">${h(it.message)}</div>""")) }
    val base = "/playlists/${q(service.id)}"
    if (rest.size >= 2 && rest[1] == "transfer" && r.method == "POST") return transfer(r, service, lists.map { it.id to it.name })
    val playlist = rest.getOrNull(1)?.let { id -> lists.firstOrNull { it.id == id } }
    if (rest.size >= 2 && playlist == null) return redirect(r.ex, "$base?err=${q("No such playlist.")}")
    if (playlist == null) {
        val targets = services.filter { it.canWrite && it.id != service.id }
        val body = StringBuilder(notice(r))
        body.append("""<form method="post" action="$base/transfer"><div class="card"><div class="who">${logo(service.id, 40)}<div><b>${h(service.label(env))}</b><br><span class="muted">${lists.size} playlists. Open one to see and change it, or tick several to copy them to another service.</span></div></div></div>""")
        for (p in lists) {
            body.append(
                """<div class="card row"><label class="check" style="margin:0"><input type="checkbox" name="p" value="${h(p.id)}"> <a href="$base/${q(p.id)}"><b>${h(p.name)}</b></a></label>""" +
                    """<span class="muted">${if (p.trackCount >= 0) "${p.trackCount} tracks" else ""}${if (!p.ownedByMe) " · followed" else ""}</span></div>""",
            )
        }
        if (targets.isNotEmpty()) {
            val opts = targets.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
            body.append("""<div class="card"><label>Copy the ticked playlists to</label><select name="to">$opts</select>
<label class="check"><input type="checkbox" name="keep"> Keep them in sync (daily)</label><p><button>Copy</button></p></div>""")
        }
        body.append("</form>")
        return send(r.ex, 200, page("Playlists on ${service.label(env)}", body.toString()))
    }
    val back = "$base/${q(playlist.id)}"
    if (r.method == "POST") {
        val result = runCatching {
            runBlocking {
                when (r.form["action"]) {
                    "rename" -> {
                        val name = r.form["name"].orEmpty().trim().ifEmpty { error("Give it a name.") }
                        service.renamePlaylist(env, playlist.id, name); "Renamed to $name."
                    }
                    "delete" -> { service.deletePlaylist(env, playlist.id); "Deleted ${playlist.name}." }
                    "remove" -> {
                        val ids = r.form.filterKeys { it.startsWith("t_") }.keys.map { it.removePrefix("t_") }.toSet()
                        if (ids.isEmpty()) error("Tick the tracks to take out.")
                        val chosen = service.tracks(env, playlist.id).filter { (it.itemId ?: it.id) in ids }
                        service.removeTracks(env, playlist.id, chosen); "Took out ${chosen.size} tracks."
                    }
                    else -> error("Unknown action.")
                }
            }
        }
        val to = if (r.form["action"] == "delete" && result.isSuccess) base else back
        return redirect(r.ex, "$to?" + result.fold({ "ok=${q(it)}" }, { "err=${q(it.message ?: "Error")}" }))
    }
    val tracks = runCatching { runBlocking { service.tracks(env, playlist.id) } }
        .getOrElse { return send(r.ex, 200, page(playlist.name, """<div class="card err">${h(it.message)}</div>""")) }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted"><a href="$base">${h(service.label(env))}</a> · ${tracks.size} tracks · <a href="/tools/export?service=${q(service.id)}&playlist=${q(playlist.id)}">Export</a> · <a href="/syncs/new?src=${q(service.id)}&amp;srcPlaylist=${q(playlist.id)}">Sync it to another service</a></p>""")
    val owned = playlist.ownedByMe && !MusicProvider.isLibrary(playlist.id)
    if (owned && service.canRenamePlaylists) {
        body.append("""<form method="post" action="$back" class="card"><input type="hidden" name="action" value="rename"><label>Name</label><input type="text" name="name" value="${h(playlist.name)}"><p><button class="secondary">Rename</button></p></form>""")
    }
    val removable = playlist.ownedByMe && service.canWrite && service.canRemoveTracks
    body.append("""<form method="post" action="$back" class="card"><input type="hidden" name="action" value="remove"><ol>""")
    tracks.take(1000).forEach { t ->
        val key = t.itemId ?: t.id
        body.append("<li>${if (removable) """<label class="check" style="margin:0;display:inline-flex"><input type="checkbox" name="t_${h(key)}"> """ else ""}${h(t.artistLine)} — <b>${h(t.title)}</b>${if (t.available == false) " <span class=\"badge\">unplayable</span>" else ""}${if (removable) "</label>" else ""}</li>")
    }
    if (tracks.size > 1000) body.append("<li class=\"muted\">… ${tracks.size - 1000} more</li>")
    body.append("</ol>")
    if (removable && tracks.isNotEmpty()) body.append("""<p><button class="danger" onclick="return confirm('Take the ticked tracks out of ${h(playlist.name)}?')">Take out the ticked tracks</button></p>""")
    body.append("</form>")
    if (owned && service.canDeletePlaylists) {
        body.append("<p>" + Html.postButton(back, "Delete this playlist", "danger", fields = mapOf("action" to "delete"), confirm = "Delete ${playlist.name} on ${service.label(env)}? This cannot be undone here.") + "</p>")
    }
    send(r.ex, 200, page(playlist.name, body.toString()))
}

/** One sync per ticked playlist to [r]'s "to" service, a new playlist there, run now. */
private fun Web.transfer(r: Web.Req, from: MusicProvider, known: List<Pair<String, String>>) {
    val to = Providers.byId(r.form["to"].orEmpty())?.takeIf { it.canWrite && it.canCreatePlaylists && (!it.requiresAuth || it.isConnected(env)) }
        ?: return redirect(r.ex, "/playlists/${q(from.id)}?err=${q("Pick a service that can create playlists.")}")
    // The ticked ids, as the multipart or urlencoded form sent them: "p" repeated keeps only the last in a map, so the page names each.
    val ticked = r.rawForm.filter { it.first == "p" }.map { it.second }.toSet()
    val chosen = known.filter { it.first in ticked }
    if (chosen.isEmpty()) return redirect(r.ex, "/playlists/${q(from.id)}?err=${q("Tick the playlists to copy.")}")
    val keep = r.form["keep"] == "on"
    for ((id, name) in chosen) {
        val job = SyncJob(
            id = UUID.randomUUID().toString(), name = name,
            source = PlaylistRef(from.id, id, name), target = PlaylistRef(to.id, null, name),
            schedule = if (keep) Schedule.DAILY else Schedule.MANUAL,
        )
        env.store.upsertJob(job)
        runner.start(job)
    }
    env.log("transfer", "${chosen.size} playlists from ${from.label(env)} to ${to.label(env)}")
    redirect(r.ex, "/?ok=${q("Copying ${chosen.size} playlists to ${to.label(env)}: each is a sync below.")}")
}

package com.xlollx.songport.server

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.NewReleasesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.NewReleases
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Tools › New releases, as the app's Discover page: a playlist of a connected service, a window of
 * 7, 30 or 90 days and, if wanted, what is announced; the albums, EPs and singles its artists put
 * out ([NewReleases]). The search runs as a [Tasks] task (a minute for fifty artists: MusicBrainz
 * answers once a second). From the result: the tracks saved as a File playlist to send to any
 * service, or a weekly sync that fills a new playlist there ([NewReleasesProvider]).
 */
internal fun Web.releasesTool(r: Web.Req) {
    val title = "New releases"
    val note = "The new albums, EPs and singles of the artists of one of your playlists, from Deezer and MusicBrainz (no account needed). Make a playlist of them on any service, or let a sync fill one every week."
    val sources = Providers.connectors().filter { it.requiresAuth && it.isConnected(env) && !it.linkOnly && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" }
    val params = if (r.method == "POST") r.form else r.query
    val service = params["service"]?.let { id -> sources.firstOrNull { it.id == id } }
        ?: return send(r.ex, 200, page(title, notice(r) + """<p class="muted">${h(note)}</p>""" + serviceFormFor("/tools/releases", sources)))
    val lists = runCatching { runBlocking { service.libraryEntries(env, false) + service.playlists(env) } }
        .getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(service.label(env))}: ${h(it.message)}</div>""")) }
    val playlist = params["playlist"]?.let { id -> lists.firstOrNull { it.id == id } }
    val days = params["days"]?.toIntOrNull()?.takeIf { it in NewReleases.WINDOWS } ?: 30
    val upcoming = params["upcoming"] == "on" || params["upcoming"] == "true"
    if (playlist == null) {
        val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
        val windows = NewReleases.WINDOWS.joinToString("") { d -> """<option value="$d"${if (d == days) " selected" else ""}>Last $d days</option>""" }
        return send(r.ex, 200, page(title, notice(r) + """<form method="post" action="/tools/releases" class="card"><p class="muted">${h(note)}</p>
<input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">$opts</select>
<label>Released in the</label><select name="days">$windows</select>
<label><input type="checkbox" name="upcoming"> Also what is announced (coming soon)</label>
<p><button>Find new releases</button> <a class="button secondary" href="/tools/releases">Back</a></p></form>"""))
    }
    if (r.method != "POST") return redirect(r.ex, "/tools/releases?service=${q(service.id)}")
    val name = env.text("releases_playlist_name", playlist.name)
    val back = "/tools/releases?service=${q(service.id)}"
    when (r.form["action"]) {
        // Every week: a sync from the "New releases" source to a new playlist, keeping every track it ever added.
        "weekly" -> {
            val dst = Providers.byId(r.form["target"].orEmpty())?.takeIf { usable(it) && it.canWrite && it.canCreatePlaylists }
                ?: return redirect(r.ex, "$back&err=${q("Pick a service that can make playlists.")}")
            val job = SyncJob(
                id = UUID.randomUUID().toString(), name = name,
                source = PlaylistRef(NewReleasesProvider.SERVICE, NewReleasesProvider.id(days, service.id, playlist.id), name),
                target = PlaylistRef(dst.id, null, name),
                schedule = Schedule.WEEKLY, archive = true,
            )
            env.store.upsertJob(job)
            env.log("releases", "weekly sync ${job.name} to ${dst.label(env)}")
            return redirect(r.ex, "/syncs/${q(job.id)}?ok=${q("Sync created: every week it adds what these artists release to a new playlist on ${dst.label(env)}. Run it now, or let the schedule start it.")}")
        }
        // Now: the tracks of the releases, as a File playlist that "Send to a service" takes anywhere.
        "file" -> {
            val task = Tasks.start(title, back, { env.log("releases", it) }, env.owner) { t ->
                val found = NewReleases.find(env, NewReleases.artists(service.tracks(env, playlist.id)), days, upcoming = false) { done, total -> t.progress = "Checking artists: $done of $total" }
                val tracks = NewReleases.tracksOf(env, found.releases) { done, total -> t.progress = "Reading the tracks: $done of $total" }
                if (tracks.isEmpty()) return@start """<div class="card">These releases have no tracks to add yet.</div>"""
                val id = LocalFilesProvider.importTracks(env, name, tracks)
                env.log("releases", "${tracks.size} tracks saved as $id")
                """<div class="card ok"><p>${tracks.size} tracks saved as the File playlist <b>${h(id)}</b>.</p>
<p><a class="button" href="/syncs/new?src=${q(LocalFilesProvider.id)}&srcPlaylist=${q(id)}">Send it to a service</a></p></div>"""
            }
            return redirect(r.ex, "/tools/task/${q(task.id)}")
        }
    }
    val targets = Providers.connectors().filter { usable(it) && it.canWrite && it.canCreatePlaylists && it.id != LocalFilesProvider.id }
    val task = Tasks.start(title, back, { env.log("releases", it) }, env.owner) { t ->
        val artists = NewReleases.artists(service.tracks(env, playlist.id))
        val found = NewReleases.find(env, artists, days, upcoming) { done, total -> t.progress = "Checking artists: $done of $total" }
        releasesResult(service.id, playlist.id, playlist.name, days, found, targets.map { it.id to it.label(env) })
    }
    redirect(r.ex, "/tools/task/${q(task.id)}")
}

private fun Web.serviceFormFor(action: String, providers: List<com.xlollx.songport.providers.MusicProvider>): String {
    if (providers.isEmpty()) return """<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>"""
    val opts = providers.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
    return """<form method="get" action="$action" class="card"><label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>"""
}

private fun releasesResult(
    service: String, playlist: String, playlistName: String, days: Int, found: NewReleases.Result, targets: List<Pair<String, String>>,
): String {
    val sb = StringBuilder()
    if (found.unanswered > 0) sb.append("""<div class="card err">${found.unanswered} of ${found.artists.size} artists could not be checked right now: try again later.</div>""")
    if (found.releases.isEmpty()) sb.append("""<div class="card">Nothing new from the ${found.artists.size} artists of ${h(playlistName)} in the last $days days.</div>""")
    else {
        sb.append("""<div class="card"><p><b>${found.releases.size}</b> new releases from the ${found.artists.size} artists of ${h(playlistName)}, last $days days:</p><ul>""")
        found.releases.forEach { r ->
            val tag = if (NewReleases.isUpcoming(r)) """ <span class="badge">coming soon</span>""" else ""
            sb.append("""<li><a href="${h(r.link)}" target="_blank" rel="noopener">${h(r.title)}</a> — ${h(r.artist)} <span class="muted">${r.type.name.lowercase()} · ${h(r.date)}</span>$tag</li>""")
        }
        sb.append("</ul>")
        val fields = mapOf("service" to service, "playlist" to playlist, "days" to days.toString())
        sb.append(Html.postButton("/tools/releases", "Save the tracks as a File playlist", fields = fields + ("action" to "file")))
        sb.append("</div>")
    }
    if (targets.isNotEmpty()) {
        val opts = targets.joinToString("") { (id, label) -> """<option value="${h(id)}">${h(label)}</option>""" }
        sb.append("""<form method="post" action="/tools/releases" class="card"><p>Every week, a sync adds what these artists release (last $days days) to a new playlist; nothing is ever removed from it.</p>
<input type="hidden" name="action" value="weekly"><input type="hidden" name="service" value="${h(service)}"><input type="hidden" name="playlist" value="${h(playlist)}"><input type="hidden" name="days" value="$days">
<label>On</label><select name="target">$opts</select><p><button class="secondary">Every week</button></p></form>""")
    }
    return sb.toString()
}

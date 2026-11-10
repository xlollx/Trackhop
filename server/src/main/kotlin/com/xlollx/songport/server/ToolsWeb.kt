package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.Unplayable
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's Tools on the web page, on [LibraryTools] as the app's: Full backup (by hand, on a
 * schedule, copied to a folder), Duplicates (find and remove a playlist's doubles) and Unplayable
 * tracks (the entries a service no longer plays, replaced or removed, with an undo) and MusicBrainz
 * tags (the songs of a personal server without them, read-only). Long runs go through [Tasks]: /tools/task/<id> shows them.
 */
internal fun Web.toolPage(r: Web.Req, tool: String) {
    when (tool) {
        "backup" -> backupTool(r)
        "dedupe" -> dedupeTool(r)
        "unplayable" -> unplayableTool(r)
        "mbtags" -> mbTagsTool(r)
        else -> send(r.ex, 404, page("Not found", """<p><a href="/tools">Back</a></p>"""))
    }
}

internal fun Web.taskPage(r: Web.Req, id: String) {
    val t = Tasks.get(id) ?: return send(r.ex, 404, page("Finished", """<div class="card">This run is no longer kept. <a href="/tools">Tools</a></div>"""))
    val body = StringBuilder()
    when {
        !t.done -> body.append("""<div class="card"><p>${h(t.progress)}</p><progress></progress><p class="muted">This page refreshes by itself; you can leave it, the run goes on.</p></div>""")
        t.error != null -> body.append("""<div class="card err">${h(t.error)}</div>""")
        else -> body.append(t.resultHtml.orEmpty())
    }
    body.append("""<p><a href="${h(t.back)}">Back</a></p>""")
    send(r.ex, 200, page(t.title, body.toString(), refresh = if (t.done) null else 3))
}

private fun Web.readable(): List<MusicProvider> =
    Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly }

private fun Web.serviceForm(action: String, providers: List<MusicProvider>, note: String): String {
    if (providers.isEmpty()) return """<div class="card">No connected service can do this. <a href="/connectors">Services</a></div>"""
    val opts = providers.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
    return """<form method="get" action="$action" class="card"><p class="muted">${h(note)}</p><label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>"""
}

// ------------------------------------------------------------------ full backup

private fun Web.backupTool(r: Web.Req) {
    val settings = env.store.data.settings
    if (r.method == "POST") {
        when (r.form["action"]) {
            "settings" -> {
                val schedule = Schedule.entries.firstOrNull { it.name == r.form["schedule"] && it in BACKUP_SCHEDULES } ?: Schedule.MANUAL
                val folder = r.form["folder"].orEmpty().trim()
                if (folder.isNotEmpty()) backupFolderProblem(folder)?.let { return redirect(r.ex, "/tools/backup?err=${q(it)}") }
                env.store.updateSettings { it.copy(backupSchedule = schedule, backupFolder = folder) }
                return redirect(r.ex, "/tools/backup?ok=${q("Saved.")}")
            }
            else -> {
                if (Tasks.running(Backups.TITLE)) return redirect(r.ex, "/tools/backup?err=${q("A backup is already running.")}")
                val chosen = r.form["service"]?.let { id -> Backups.services(env).filter { it.id == id } } ?: Backups.services(env)
                val task = Tasks.start(Backups.TITLE, "/tools/backup", { env.log("backup", it) }) { t -> Backups.run(env, chosen) { t.progress = it } }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append(configCards())
    val services = Backups.services(env)
    body.append("""<h2>Your playlists</h2><div class="card"><p class="muted">Saves every playlist and the library (liked songs, albums, artists) of a service as File playlists, one dated version per run; the last ${LocalFilesProvider.KEEP_VERSIONS} versions of each are kept, and an unchanged playlist is not written again. Nothing changes on the service.</p>""")
    val last = Backups.lastRun(env)
    body.append("""<p class="muted">Last backup: ${Html.date(last)}</p>""")
    if (services.isEmpty()) body.append("""<p>Connect a service first: <a href="/connectors">Services</a>.</p>""")
    else {
        body.append("""<div class="actions">${Html.postButton("/tools/backup", "Back up all playlists now (${services.size} services)")}""")
        services.forEach { body.append(Html.postButton("/tools/backup", it.label(env), "secondary", fields = mapOf("service" to it.id))) }
        body.append("</div>")
    }
    body.append("</div>")
    val schedules = BACKUP_SCHEDULES.joinToString("") { s -> """<option value="${s.name}"${if (s == settings.backupSchedule) " selected" else ""}>${scheduleLabel(s)}</option>""" }
    body.append(
        """<h2>Automatically</h2><form method="post" action="/tools/backup" class="card"><input type="hidden" name="action" value="settings">
<p class="muted">On the schedule, every connected service is backed up. With a folder, the new files and the set-up of the day are copied there too.</p>
<label>Back up every connected service</label><select name="schedule">$schedules</select>
${if (env.may(Permissions.FOLDERS)) """<label>Also copy the backups and the set-up to this folder (optional)</label><input type="text" name="folder" value="${h(settings.backupFolder)}" placeholder="/backups">""" else """<input type="hidden" name="folder" value=""><p class="muted">Copies to a folder of the server are not turned on for you: download the backups from File playlists instead.</p>"""}
${if (env.may(Permissions.FOLDERS)) """<p class="muted">A folder this ${if (env.dataDir.path.startsWith("/data")) "container" else "computer"} can write to: with Docker, mount one (for example <code>-v /mnt/nas/trackhop:/backups</code>)${account?.takeIf { sharedRoot != null }?.let { ". The server is shared: use a folder of your own, such as <code>/backups/${h(it.name)}</code>" } ?: ""}. Only files the folder does not have yet are copied, so it keeps every version.</p>""" else ""}
<p><button class="secondary">Save</button></p></form>""",
    )
    send(r.ex, 200, page("Backup & restore", body.toString()))
}

private val BACKUP_SCHEDULES = listOf(Schedule.MANUAL, Schedule.DAILY, Schedule.WEEKLY)

private fun scheduleLabel(s: Schedule) = when (s) {
    Schedule.MANUAL -> "Only when I ask"
    Schedule.DAILY -> "Every day"
    Schedule.WEEKLY -> "Every week"
    else -> s.name.lowercase()
}

// ------------------------------------------------------------------ duplicates

private fun Web.dedupeTool(r: Web.Req) {
    val params = if (r.method == "POST") r.form else r.query
    val service = params["service"]?.let { id -> readable().firstOrNull { it.id == id } }
        ?: return send(r.ex, 200, page("Duplicates", notice(r) + serviceForm("/tools/dedupe", readable().filter { it.canWrite }, "Finds the tracks a playlist has more than once, and removes the extra copies.")))
    val lists = runCatching { runBlocking { service.libraryEntries(env, true) + service.playlists(env).filter { it.ownedByMe } } }
        .getOrElse { return send(r.ex, 200, page("Duplicates", """<div class="card err">${h(it.message)}</div>""")) }
    val playlist = params["playlist"]?.let { id -> lists.firstOrNull { it.id == id } }
    if (playlist == null) {
        val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
        return send(r.ex, 200, page("Duplicates", notice(r) + """<form method="get" action="/tools/dedupe" class="card"><input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">$opts</select><p><button>Look for duplicates</button> <a class="button secondary" href="/tools/dedupe">Back</a></p></form>"""))
    }
    val back = "/tools/dedupe?service=${q(service.id)}&playlist=${q(playlist.id)}"
    if (r.method == "POST") {
        val task = Tasks.start("Remove duplicates", back, { env.log("dedupe", it) }) { t ->
            val n = LibraryTools.removeDuplicates(env, service, playlist.id) { p -> t.progress = "${p.done}/${p.total}" }
            env.log("dedupe", "${service.label(env)} / ${playlist.name}: $n removed")
            """<div class="card ok">Removed $n extra copies from ${h(playlist.name)}.</div>"""
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val groups = runCatching { runBlocking { LibraryTools.findDuplicates(env, service, playlist.id) } }
        .getOrElse { return send(r.ex, 200, page("Duplicates", """<div class="card err">${h(it.message)}</div>""")) }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">${h(service.label(env))} · ${h(playlist.name)} · <a href="/tools/dedupe?service=${q(service.id)}">Another playlist</a></p>""")
    if (groups.isEmpty()) body.append("""<div class="card ok">No duplicates in ${h(playlist.name)}.</div>""")
    else {
        val extras = groups.sumOf { it.size - 1 }
        body.append("""<div class="card"><p>${groups.size} tracks are there more than once: $extras extra copies. The first of each stays.</p><ul>""")
        groups.take(200).forEach { g -> body.append("<li>${h(g.first().artistLine)} — ${h(g.first().title)} <span class=\"badge\">×${g.size}</span></li>") }
        body.append("</ul>")
        if (service.canRemoveTracks) body.append(Html.postButton("/tools/dedupe", "Remove $extras extra copies", "danger", fields = mapOf("service" to service.id, "playlist" to playlist.id), confirm = "Remove $extras extra copies from ${playlist.name}?"))
        else body.append("""<p class="muted">${h(service.label(env))} does not let apps remove tracks.</p>""")
        body.append("</div>")
    }
    send(r.ex, 200, page("Duplicates", body.toString()))
}

// ------------------------------------------------------------------ unplayable tracks

/** A scan's findings and the proposals for them, kept for the apply form (by the task's id). */
private class UnplayableFindings(val service: String, val playlists: List<Pair<Playlist, List<Pair<UnplayableEntry, Track?>>>>)

private val findings = ConcurrentHashMap<String, UnplayableFindings>()

private fun Web.unplayableTool(r: Web.Req) {
    val candidates = readable().filter { it.detectsUnplayable }
    val params = if (r.method == "POST") r.form else r.query
    val service = params["service"]?.let { id -> candidates.firstOrNull { it.id == id } }
        ?: return send(
            r.ex, 200,
            page("Unplayable tracks", notice(r) + serviceForm("/tools/unplayable", candidates, "The tracks a service still lists but can no longer play (greyed out: pulled from the catalogue, not licensed in your country). Trackhop finds them and proposes a playable version.")),
        )
    val back = "/tools/unplayable?service=${q(service.id)}"
    if (r.method == "POST") {
        when (r.form["action"]) {
            "scan" -> {
                val task = Tasks.start("Unplayable tracks", back, { env.log("unplayable", it) }) { t ->
                    val targets = LibraryTools.unplayableTargets(env, service)
                    val scan = LibraryTools.scanUnplayable(env, service, targets) { t.progress = "Reading $it…" }
                    val withProposals = scan.found.map { u ->
                        t.progress = "Looking for replacements in ${u.playlist.name}…"
                        u.playlist to u.entries.map { e ->
                            val p = LibraryTools.proposeReplacement(env, service, e)
                            e to p?.takeIf { Unplayable.confident(it) }?.track
                        }
                    }
                    findings[t.id] = UnplayableFindings(service.id, withProposals)
                    unplayableResult(service, t.id, scan.playlists, withProposals, scan.failed.map { "${it.name} (${it.reason})" })
                }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
            "apply" -> {
                val f = findings[r.form["scan"].orEmpty()]?.takeIf { it.service == service.id } ?: return redirect(r.ex, "$back&err=${q("That scan is no longer kept: scan again.")}")
                val (playlist, entries) = f.playlists.firstOrNull { it.first.id == r.form["playlist"] } ?: return redirect(r.ex, "$back&err=${q("Unknown playlist.")}")
                val replace = entries.filter { (e, p) -> p != null && r.form["e_${e.key}"] == "replace" }.map { (e, p) -> e to p!! }
                val remove = entries.filter { (e, _) -> r.form["e_${e.key}"] == "remove" }.map { it.first }
                val res = runCatching { runBlocking { LibraryTools.applyUnplayable(env, service, playlist, replace, remove) } }
                    .getOrElse { return redirect(r.ex, "$back&err=${q(it.message ?: "Error")}") }
                return redirect(r.ex, "$back&ok=${q("${playlist.name}: ${res.replaced} replaced, ${res.removed} removed.")}")
            }
            "undo" -> {
                val fix = LibraryTools.lastUnplayableFix(env, service, r.form["playlist"].orEmpty()) ?: return redirect(r.ex, "$back&err=${q("Nothing to undo.")}")
                val n = runCatching { runBlocking { LibraryTools.undoUnplayable(env, service, fix) } }.getOrElse { return redirect(r.ex, "$back&err=${q(it.message ?: "Error")}") }
                return redirect(r.ex, "$back&ok=${q("${fix.playlistName}: $n entries back.")}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p>Looks through your playlists and liked songs on ${h(service.label(env))} for tracks it can no longer play. Nothing changes until you choose.</p>""")
    body.append(Html.postButton("/tools/unplayable", "Scan ${service.label(env)}", fields = mapOf("service" to service.id, "action" to "scan")))
    body.append("</div>")
    // The last fixes made, to undo.
    val lists = runCatching { runBlocking { LibraryTools.unplayableTargets(env, service) } }.getOrDefault(emptyList())
    val fixes = lists.mapNotNull { pl -> LibraryTools.lastUnplayableFix(env, service, pl.id)?.takeIf { it.swaps.isNotEmpty() } }
    if (fixes.isNotEmpty()) {
        body.append("<h2>Last fixes</h2>")
        fixes.forEach { fix ->
            body.append("""<div class="card row"><div><b>${h(fix.playlistName)}</b><br><span class="muted">${Html.date(fix.at)} · ${fix.swaps.size} entries</span></div>""" +
                Html.postButton("/tools/unplayable", "Undo", "secondary", fields = mapOf("service" to service.id, "action" to "undo", "playlist" to fix.playlistId), confirm = "Put the ${fix.swaps.size} entries back as they were?") + "</div>")
        }
    }
    send(r.ex, 200, page("Unplayable tracks", body.toString()))
}

private fun Web.unplayableResult(
    service: MusicProvider, scanId: String, scanned: Int,
    found: List<Pair<Playlist, List<Pair<UnplayableEntry, Track?>>>>, failed: List<String>,
): String {
    val sb = StringBuilder()
    val count = found.sumOf { it.second.size }
    sb.append("""<p class="muted">${h(service.label(env))}: $scanned playlists read, $count unplayable entries.</p>""")
    if (failed.isNotEmpty()) sb.append("""<div class="card err">Not read: ${h(failed.joinToString(", "))}</div>""")
    if (count == 0) sb.append("""<div class="card ok">Everything plays.</div>""")
    for ((pl, entries) in found) {
        sb.append("""<form method="post" action="/tools/unplayable" class="card"><input type="hidden" name="service" value="${h(service.id)}"><input type="hidden" name="action" value="apply">
<input type="hidden" name="scan" value="${h(scanId)}"><input type="hidden" name="playlist" value="${h(pl.id)}"><h3>${h(pl.name)}</h3>""")
        for ((e, proposal) in entries) {
            val name = "e_${e.key}"
            sb.append("""<div class="card" style="background:var(--surface-2)"><b>${h(e.track.title)}</b> <span class="muted">${h(e.track.artistLine)}${if (e.reason.isNotBlank()) " · " + h(e.reason) else ""}</span><br>""")
            if (proposal != null) sb.append("""<label class="check"><input type="radio" name="$name" value="replace" checked> Replace with ${h(proposal.artistLine)} — ${h(proposal.title)}</label>""")
            if (service.canRemoveTracks) sb.append("""<label class="check"><input type="radio" name="$name" value="remove"${if (proposal == null) " checked" else ""}> Remove it</label>""")
            sb.append("""<label class="check"><input type="radio" name="$name" value="keep"${if (proposal == null && !service.canRemoveTracks) " checked" else ""}> Leave it</label></div>""")
        }
        sb.append("""<p><button>Apply to ${h(pl.name)}</button></p></form>""")
    }
    return sb.toString()
}

// ------------------------------------------------------------------ MusicBrainz tags

/** The songs of a personal server whose files carry no MusicBrainz tag: a count and a list. Nothing is written. */
private fun Web.mbTagsTool(r: Web.Req) {
    val title = "MusicBrainz tags"
    val note = "When your files carry MusicBrainz tags, Trackhop finds the exact recording on your server, even if the title is written differently. This lists the songs without them. Your files are never changed."
    val candidates = readable().filter { it.matchesByMbid }
    val params = if (r.method == "POST") r.form else r.query
    val service = params["service"]?.let { id -> candidates.firstOrNull { it.id == id } }
        ?: return send(r.ex, 200, page(title, notice(r) + serviceForm("/tools/mbtags", candidates, note)))
    if (r.method == "POST") {
        val task = Tasks.start(title, "/tools/mbtags?service=${q(service.id)}", { env.log("mbtags", it) }) { t ->
            val tags = LibraryTools.untaggedSongs(env, service) { t.progress = "$it songs read…" }
            env.log("mbtags", "${service.label(env)}: ${tags.untagged.size} of ${tags.total} songs without MusicBrainz tags")
            mbTagsResult(service, tags)
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p>${h(note)}</p>""")
    body.append(Html.postButton("/tools/mbtags", "Check ${service.label(env)}", fields = mapOf("service" to service.id)))
    body.append("""<p class="muted">$PICARD_LINK</p></div>""")
    send(r.ex, 200, page(title, body.toString()))
}

private const val MBTAGS_SHOWN = 500
private val PICARD_LINK = """Tags are written by a tagger such as MusicBrainz Picard: <a href="${LibraryTools.PICARD_GUIDE}" target="_blank" rel="noopener">Picard's quick start</a>. Rescan the library on your server afterwards."""

private fun Web.mbTagsResult(service: MusicProvider, tags: LibraryTools.MbidTags): String {
    val name = h(service.label(env))
    if (tags.total == 0) return """<div class="card err">$name did not list its songs.</div>"""
    if (tags.untagged.isEmpty()) return """<div class="card ok">All ${tags.total} songs on $name have MusicBrainz tags.</div>"""
    val sb = StringBuilder("""<div class="card"><p><b>${tags.untagged.size}</b> of ${tags.total} songs on $name have no MusicBrainz tags. A sync finds these by title, artist and duration.</p><p class="muted">$PICARD_LINK</p><ul>""")
    tags.untagged.take(MBTAGS_SHOWN).forEach { t -> sb.append("<li>${h(t.artistLine)} — ${h(t.title)}${if (t.album.isNotBlank()) """ <span class="muted">${h(t.album)}</span>""" else ""}</li>") }
    sb.append("</ul>")
    if (tags.untagged.size > MBTAGS_SHOWN) sb.append("""<p class="muted">…and ${tags.untagged.size - MBTAGS_SHOWN} more.</p>""")
    return sb.append("</div>").toString()
}

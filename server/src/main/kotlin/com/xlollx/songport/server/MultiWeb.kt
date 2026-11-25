package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayChanges
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.shared.NwayHistory
import com.xlollx.songport.shared.NwayRun
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.Timing
import kotlinx.coroutines.runBlocking

/**
 * Multi syncs on Desktop and Docker (the app's "Several targets", shared/Nway): one source playlist
 * kept on several targets, each following one of three ways. The same groups as the app's, made and
 * changed with the engine's own [NwayEdit] and run as one batch in [Nway.order] (Runner.startGroup):
 * the home page's card, the group's page under /multi/<id>, its forms, and the lists of what its last
 * run added and removed, song by song ([NwayChanges]). Plain forms, no JavaScript needed.
 */

/** The ways a target follows, as the app says them (nway_mode_*): its name and what it does. */
internal fun wayName(m: NwayEdit.Mode): String = when (m) {
    NwayEdit.Mode.EXACT -> "Exact copy"
    NwayEdit.Mode.KEEP -> "Keep my additions"
    NwayEdit.Mode.TWO_WAY -> "Two-way"
}

internal fun wayDesc(m: NwayEdit.Mode, liked: Boolean = false): String = when (m) {
    NwayEdit.Mode.EXACT -> "Always the same as the source: extra songs here are removed."
    NwayEdit.Mode.KEEP -> if (liked) "The sync's songs are liked here, and unliked when they leave it. Your other liked songs stay as they are." else "Songs you add here stay here, and go nowhere else."
    NwayEdit.Mode.TWO_WAY -> "What you add or remove here reaches the source and all the other targets."
}

/** A way as the API and the forms name it: exact, keep, two_way (two-way too). */
internal fun wayOf(text: String?): NwayEdit.Mode? = when (text?.trim()?.lowercase()?.replace('-', '_')) {
    "exact" -> NwayEdit.Mode.EXACT
    "keep" -> NwayEdit.Mode.KEEP
    "two_way", "twoway" -> NwayEdit.Mode.TWO_WAY
    else -> null
}

internal fun wayKey(m: NwayEdit.Mode) = m.name.lowercase()

/** What a multi sync's form or call asks for: its name, source, targets (a null playlist: a new one, named after the multi sync) and timing. */
internal data class MultiSpec(
    val name: String, val source: NwayEdit.Item, val targets: List<NwayEdit.Item>,
    val every: Int = 1440, val at: String? = null, val days: List<Int> = emptyList(), val enabled: Boolean = true,
    /** The id asked for (a phone's hand-over keeps its group's): one already here is answered as it is. */
    val id: String? = null,
)

private const val BUSY = "This multi sync is running: you can save once the run has finished."

/** A multi sync of this user's by id, or null. */
internal fun Web.multiGroup(id: String): SharedGroup? = env.store.data.groups.firstOrNull { it.id == id && Nway.isGroup(it) }

/** [group]'s playlists with its source first, as the app's page lists them. */
internal fun Web.multiMembers(group: SharedGroup): List<Nway.Member> {
    val all = Nway.members(env.store.data.jobs, group.id)
    return NwayRun.sourceFirst(all, Nway.source(group, all)?.down?.id)
}

/** A playlist of a multi sync by its service ("Spotify"), and "Liked songs" for that list. */
internal fun Web.memberName(provider: String, playlistId: String?): String =
    label(provider) + if (NwayEdit.isLiked(playlistId)) " · Liked songs" else ""

internal fun Web.memberName(m: Nway.Member): String = memberName(m.playlist.provider, m.playlist.playlistId)

/** Where the multi sync's run stands now, or null when it does not run. */
internal fun Web.multiRun(group: SharedGroup, members: List<Nway.Member>): NwayRun.State? =
    NwayRun.of(members, SyncState.running.value, runner.groupBatch(group.id))

/**
 * A new multi sync from [spec], as the app's editor saves one: the new playlists made first (named
 * after the multi sync), then the group and all its syncs as one change ([NwayEdit.create]). A
 * multi sync with the asked id that is here already is answered as it is. Throws
 * IllegalArgumentException for what the caller must change.
 */
internal fun Web.createMulti(spec: MultiSpec): SharedGroup {
    spec.id?.let { id -> multiGroup(id)?.let { return it } }
    require(spec.id == null || Regex("[0-9a-f]{16}").matches(spec.id)) { "\"id\" is a multi sync's id: 16 characters, 0-9 and a-f." }
    val name = spec.name.trim().ifEmpty { spec.source.name }.take(com.xlollx.songport.shared.Invite.MAX_NAME)
    var draft = NwayEdit.Draft(name, spec.source, spec.targets)
    when (NwayEdit.missing(draft)) {
        NwayEdit.Missing.SOURCE, NwayEdit.Missing.NEW_SOURCE -> throw IllegalArgumentException("Choose the source playlist.")
        NwayEdit.Missing.TARGET -> throw IllegalArgumentException("Add at least one target.")
        NwayEdit.Missing.TWICE -> throw IllegalArgumentException("The same playlist is there twice.")
        NwayEdit.Missing.LIKED -> throw IllegalArgumentException("Liked songs can't be used this way here: let it keep your additions, with another playlist as the source.")
        null -> Unit
    }
    for (item in draft.items) {
        val p = Providers.byId(item.provider) ?: throw IllegalArgumentException("No service with the id ${item.provider}.")
        require(usable(p)) { "${p.label(env)} is not connected." }
        require(item === draft.source || (p.canWrite && !p.linkOnly)) { "${p.label(env)} cannot be a target." }
        require(item.playlistId != null || p.canCreatePlaylists) { "${p.label(env)} cannot create playlists: pick one of yours." }
    }
    // The new playlists first, each kept in the draft as soon as it exists, as the app does.
    for ((i, t) in draft.targets.withIndex()) if (t.playlistId == null) {
        val p = Providers.byId(t.provider)!!
        val made = runBlocking { p.createPlaylist(env, name, MusicProvider.description("Trackhop")) }
        draft = draft.copy(targets = draft.targets.toMutableList().also { it[i] = t.copy(playlistId = made.id, name = made.name, count = 0) })
    }
    val timed: (SyncJob) -> SyncJob = { j -> Timing.apply(j, spec.every, spec.at, spec.days).copy(enabled = spec.enabled) }
    val group = NwayEdit.create(env, draft, timed, id = spec.id)
    env.log("web", "multi sync group=${group.id} created with ${draft.targets.size} targets")
    return group
}

/** [group] saved as [draft] says ([NwayEdit.applied]), as one change; refused while it runs. Throws for what the caller must change. */
internal fun Web.saveMulti(group: SharedGroup, draft: NwayEdit.Draft) {
    if (runner.isGroupRunning(group.id)) throw IllegalStateException(BUSY)
    when (NwayEdit.missing(draft)) {
        NwayEdit.Missing.TARGET -> throw IllegalArgumentException("A multi sync keeps at least one target: delete it instead.")
        NwayEdit.Missing.TWICE -> throw IllegalArgumentException("This playlist is already in the multi sync.")
        NwayEdit.Missing.LIKED -> throw IllegalArgumentException("Liked songs can't follow this way here.")
        NwayEdit.Missing.SOURCE, NwayEdit.Missing.NEW_SOURCE -> throw IllegalArgumentException("Choose the source playlist.")
        null -> Unit
    }
    val before = Nway.members(env.store.data.jobs, group.id)
    val changes = NwayEdit.changes(group, before, draft)
    if (!env.store.commit { d -> NwayEdit.applied(d, group.id, draft) }) throw IllegalStateException("The change could not be saved.")
    Nway.log(env, group.id, NwayEdit.logLine(changes))
}

/** A target added to [group]: [playlistId] null makes a new playlist named after the multi sync. */
internal fun Web.addTarget(group: SharedGroup, provider: String, playlistId: String?, way: NwayEdit.Mode) {
    val p = Providers.byId(provider) ?: throw IllegalArgumentException("No service with the id $provider.")
    require(usable(p) && p.canWrite && !p.linkOnly) { "${p.label(env)} cannot be a target." }
    val draft = NwayEdit.draftOf(group, Nway.members(env.store.data.jobs, group.id))
    if (playlistId != null && draft.items.any { it.provider == provider && it.playlistId == playlistId }) throw IllegalArgumentException("This playlist is already in the multi sync.")
    val name = if (playlistId == null) group.name else runBlocking { targetLists(p).firstOrNull { it.id == playlistId }?.name }
        ?: throw IllegalArgumentException("${p.label(env)} has no playlist of yours with the id $playlistId.")
    val item = NwayEdit.Item(provider, playlistId, name, mode = way)
    require(NwayEdit.allowed(item, asSource = false)) { "Liked songs can't follow this way here." }
    if (runner.isGroupRunning(group.id)) throw IllegalStateException(BUSY)
    val made = if (playlistId != null) item else {
        require(p.canCreatePlaylists) { "${p.label(env)} cannot create playlists: pick one of yours." }
        val pl = runBlocking { p.createPlaylist(env, group.name, MusicProvider.description("Trackhop")) }
        item.copy(playlistId = pl.id, name = pl.name, count = 0)
    }
    saveMulti(group, draft.copy(targets = draft.targets + made))
}

/** [group] without its target [downId] (its playlist stays as it is). */
internal fun Web.removeTarget(group: SharedGroup, downId: String) {
    val draft = NwayEdit.draftOf(group, Nway.members(env.store.data.jobs, group.id))
    if (draft.targets.none { it.downId == downId }) throw NoSuchElementException("No such target in this multi sync.")
    saveMulti(group, draft.copy(targets = draft.targets.filter { it.downId != downId }))
}

/** [group]'s target [downId] following [way]. */
internal fun Web.setWay(group: SharedGroup, downId: String, way: NwayEdit.Mode) {
    val draft = NwayEdit.draftOf(group, Nway.members(env.store.data.jobs, group.id))
    if (draft.targets.none { it.downId == downId }) throw NoSuchElementException("No such target in this multi sync.")
    saveMulti(group, draft.copy(targets = draft.targets.map { if (it.downId == downId) it.copy(mode = way) else it }))
}

/** [group]'s target [downId] as its source; the old source goes on as a two-way target, as in the app. */
internal fun Web.setSource(group: SharedGroup, downId: String) {
    val draft = NwayEdit.draftOf(group, Nway.members(env.store.data.jobs, group.id))
    val at = draft.targets.indexOfFirst { it.downId == downId }
    if (at < 0) throw NoSuchElementException("No such target in this multi sync.")
    require(NwayEdit.canBeSource(draft.targets[at].provider, draft.targets[at].playlistId)) { "Liked songs on this service can't be the source." }
    saveMulti(group, draft.withSource(at))
}

/** A service's own playlists a multi sync can write to (Liked songs among them where it can be read and written). */
internal suspend fun Web.targetLists(p: MusicProvider): List<Playlist> = p.libraryEntries(env, true) + p.playlists(env).filter { it.ownedByMe }

/** The services added here (and a test's own, which only tests have). */
private fun multiConnectors(): List<MusicProvider> = (Providers.testDoubles + Providers.connectors()).distinctBy { it.id }

/** The services a multi sync can write to here. */
internal fun Web.writableServices(): List<MusicProvider> = multiConnectors().filter { usable(it) && it.canWrite && !it.linkOnly }

// ------------------------------------------------------------------ the home page's card

/** "Spotify: Mix → Deezer, TIDAL, YouTube Music": the source and its targets. */
private fun Web.route(members: List<Nway.Member>): String {
    val src = members.firstOrNull() ?: return ""
    return "${h(memberName(src))}: ${h(src.playlist.playlistName)} → " + members.drop(1).joinToString(", ") { h(memberName(it)) }
}

/** The run state in words: "Syncing Deezer · 2 of 4", or the last run's failure, or nothing. */
private fun Web.runLine(run: NwayRun.State?): String? {
    run ?: return null
    val now = run.now?.let { memberName(it.member) }
    return when {
        now != null && run.place > 0 -> "Syncing ${h(now)} · ${run.place} of ${run.total}"
        now != null -> "Syncing ${h(now)}"
        run.place > 0 -> "Syncing · ${run.place} of ${run.total} services"
        else -> "Syncing…"
    }
}

/** The last run of [members] as one report ([NwayHistory]): the syncs into the group's own list not counted in added and removed. */
internal fun Web.lastMultiRun(members: List<Nway.Member>): SyncReport? = NwayHistory.runs(env.store.data.reports, members, 1).lastOrNull()

/**
 * A multi sync's one card on the home page, in place of its syncs': its services' logos, the
 * route, the last run's tiles (added and removed open the lists, song by song), its timing, last run
 * and state, Sync now and Open.
 */
internal fun Web.multiCard(group: SharedGroup): String {
    val members = multiMembers(group)
    val anchor = members.firstOrNull()?.down
    val run = multiRun(group, members)
    val last = lastMultiRun(members)
    val id = q(group.id)
    val logos = members.take(5).joinToString("") { logo(it.playlist.provider, 36) } + if (members.size > 5) """<span class="badge">+${members.size - 5}</span>""" else ""
    val tiles = if (last == null) "" else """<div class="actions">""" +
        """<a class="badge" href="/multi/$id/added" title="Songs added on the targets">+${last.added} added</a> """ +
        """<a class="badge" href="/multi/$id/removed" title="Songs removed from the targets">−${last.removed} removed</a> """ +
        """<span class="badge">${last.unmatched.size} not found</span> """ +
        """<span class="badge">${last.sourceCount} in source</span></div>"""
    val status = runLine(run)?.let { """<span>$it</span>""" + (run?.fraction?.let { f -> """<progress max="100" value="${(f * 100).toInt()}"></progress>""" } ?: "") }
        ?: when {
            last == null -> """<span class="muted">Never run</span>"""
            last.error != null && last.netError != null -> """<span class="net">☁︎ ${h(last.error)}</span>"""
            last.error != null -> """<span class="err">${h(last.error)}</span>"""
            else -> """<span class="ok">+${last.added} −${last.removed}</span>"""
        }
    val timing = anchor?.let { Timing.describe(it) }.orEmpty()
    val paused = members.isNotEmpty() && members.flatMap { listOfNotNull(it.down, it.up) }.none { it.enabled }
    val next = anchor?.let { Runner.nextRun(it) }?.let { " · next ${Html.date(it)}" } ?: ""
    val lastRun = members.flatMap { listOfNotNull(it.down, it.up) }.maxOfOrNull { it.lastRunEpoch } ?: 0
    val button = if (run != null) """<span class="muted">Running…</span>""" else Html.postButton("/multi/$id/run", "Sync now")
    return """<div class="card" id="multi-${h(group.id)}"${Html.jobStyle(anchor?.color)}><div class="row"><div class="who"><span class="pair">$logos</span><div><a href="/multi/$id">${if (anchor?.favorite == true) """<span title="Favourite" aria-label="Favourite">★</span> """ else ""}<b>${h(group.name)}</b> <span class="muted">· multi sync</span></a><br>
<span class="muted">${route(members)}</span></div></div></div>
$tiles<div><span class="badge">${h(timing)}</span>${if (paused) " <span class=\"badge\">paused</span>" else ""}
<span class="muted">Last run ${Html.date(lastRun)}$next</span>
<div class="row"><div>$status</div><div class="actions">$button <a class="button secondary" href="/multi/$id">Open</a></div></div></div></div>"""
}

// ------------------------------------------------------------------ pages

internal fun Web.multiPages(r: Web.Req, rest: List<String>) {
    if (rest.isEmpty()) {
        if (r.method == "POST") return multiCreate(r)
        return redirect(r.ex, "/")
    }
    if (rest == listOf("new")) return multiNew(r)
    val group = multiGroup(rest[0]) ?: return send(r.ex, 404, page("Unknown multi sync", "<p><a href=\"/\">Back</a></p>"))
    val back = "/multi/${q(group.id)}"
    fun fail(e: Throwable, to: String = back) = redirect(r.ex, "$to${if ('?' in to) "&" else "?"}err=${q(e.message ?: e.javaClass.simpleName)}")
    val member = r.form["member"] ?: r.query["member"]
    when (rest.getOrNull(1)) {
        null -> multiPage(r, group)
        "added", "removed" -> multiChanges(r, group, rest[1] == "added")
        "add" -> if (r.method == "POST") {
            val way = wayOf(r.form["way"]) ?: NwayEdit.Mode.KEEP
            runCatching { addTarget(group, r.form["service"].orEmpty(), r.form["playlist"]?.ifBlank { null }, way) }
                .fold({ redirect(r.ex, "$back?ok=${q("Target added: it gets the songs at the next run.")}") }, { fail(it, "$back/add?service=${q(r.form["service"].orEmpty())}") })
        } else multiAddPage(r, group)
        "remove" -> {
            val m = multiMembers(group).drop(1).firstOrNull { it.down.id == member } ?: return redirect(r.ex, "$back?err=${q("No such target in this multi sync.")}")
            if (r.method != "POST") return send(r.ex, 200, page("Remove a target", """<div class="card"><h3>Remove ${h(memberName(m))} from ${h(group.name)}?</h3>
<p>${h(m.playlist.playlistName)} on ${h(memberName(m))} leaves this sync; the playlist stays as it is.</p>
<p>${Html.postButton("$back/remove", "Remove from this sync", "danger", mapOf("member" to m.down.id))} <a class="button secondary" href="$back">Cancel</a></p></div>"""))
            runCatching { removeTarget(group, m.down.id) }.fold({ redirect(r.ex, "$back?ok=${q("${memberName(m)} removed. Its playlist stays as it is.")}") }, { fail(it) })
        }
        "way" -> {
            if (r.method != "POST") return redirect(r.ex, back)
            val way = wayOf(r.form["way"]) ?: return redirect(r.ex, "$back?err=${q("Pick a way.")}")
            runCatching { setWay(group, member.orEmpty(), way) }.fold({ redirect(r.ex, "$back?ok=${q("Saved: ${wayName(way)}.")}") }, { fail(it) })
        }
        "source" -> {
            if (r.method != "POST") return redirect(r.ex, back)
            runCatching { setSource(group, member.orEmpty()) }.fold({ redirect(r.ex, "$back?ok=${q("Source changed. The old source stays as a two-way target.")}") }, { fail(it) })
        }
        "schedule" -> {
            if (r.method != "POST") return redirect(r.ex, back)
            if (runner.isGroupRunning(group.id)) return redirect(r.ex, "$back?err=${q(BUSY)}")
            val anchor = multiMembers(group).firstOrNull()?.down ?: return redirect(r.ex, back)
            val timed = withTiming(anchor, r)
            val on = r.form["paused"] == null
            val name = r.form["name"]?.trim().orEmpty()
            val saved = env.store.commit { d ->
                val n = NwayEdit.scheduled(if (name.isNotEmpty()) NwayEdit.renamed(d, group.id, name) else d, group.id, null, Schedules.specOf(timed))
                n.copy(jobs = n.jobs.map { if (com.xlollx.songport.shared.SharedGroups.sharedSide(it) == group.id) it.copy(enabled = on, scheduledOn = if (on) null else it.scheduledOn) else it })
            }
            // Not written (a full disk): the store kept what was there, and the page says so.
            if (!saved) return redirect(r.ex, "$back?err=${q("The change could not be saved.")}")
            redirect(r.ex, "$back?ok=${q("Saved.")}")
        }
        "run" -> {
            if (r.method != "POST") return redirect(r.ex, back)
            if (!runner.startGroup(group)) return redirect(r.ex, "$back?err=${q("It is running already.")}")
            redirect(r.ex, back)
        }
        "stop" -> {
            if (r.method != "POST") return redirect(r.ex, back)
            Nway.jobsOf(env.store.data, group.id).filter { runner.isRunning(it.id) }.forEach { SyncState.stop(it.id) }
            redirect(r.ex, back)
        }
        "delete" -> {
            if (r.method != "POST") return send(r.ex, 200, page("Delete a multi sync", """<div class="card"><h3>Delete ${h(group.name)}?</h3>
<p>The multi sync and its syncs are deleted. Every playlist stays as it is.</p>
<p>${Html.postButton("$back/delete", "Delete", "danger")} <a class="button secondary" href="$back">Cancel</a></p></div>"""))
            if (runner.isGroupRunning(group.id)) return redirect(r.ex, "$back?err=${q(BUSY)}")
            runCatching { Nway.delete(env, group) }.fold({ redirect(r.ex, "/?ok=${q("${group.name} deleted. Its playlists stay as they are.")}") }, { fail(it) })
        }
        else -> send(r.ex, 404, errorPage(404))
    }
}

/** The way select of a target ([provider], [playlistId]): the ways it can follow ([NwayEdit.modes]), [chosen] selected. */
private fun waySelect(provider: String, playlistId: String?, chosen: NwayEdit.Mode, name: String = "way"): String =
    """<select name="${h(name)}" aria-label="How it follows">""" + NwayEdit.modes(provider, playlistId).joinToString("") { m ->
        """<option value="${wayKey(m)}"${if (m == chosen) " selected" else ""}>${h(wayName(m))}</option>"""
    } + "</select>"

/** The group's page: its source, its targets with their way and count, what runs now, and its forms. */
private fun Web.multiPage(r: Web.Req, group: SharedGroup) {
    val members = multiMembers(group)
    val run = multiRun(group, members)
    val id = q(group.id)
    val back = "/multi/$id"
    val d = env.store.data
    fun report(j: SyncJob?) = j?.lastReportId?.let { rid -> d.reports.firstOrNull { it.id == rid } }
    val body = StringBuilder(notice(r))
    body.append(multiCard(group))
    val busy = run != null
    if (busy) body.append("""<div class="card actions">${Html.postButton("$back/stop", "Stop", "secondary")}</div>""")
    val src = members.firstOrNull()
    if (src != null) {
        // The source's size as the last run read it, as the app's page says it.
        val size = lastMultiRun(members)?.sourceCount?.takeIf { it > 0 }
        body.append("""<h2>Source${size?.let { " · $it songs" } ?: ""}</h2>""")
        body.append("""<div class="card"><div class="who">${logo(src.playlist.provider, 32)}<div><b>${h(memberName(src))}</b>: ${h(src.playlist.playlistName)}<br>
<span class="muted">What you change in the source reaches every target.</span>${phaseLine(run, src)}</div></div></div>""")
    }
    body.append("<h2>Targets</h2>")
    for (m in members.drop(1)) {
        val way = NwayEdit.modeOf(m)
        val count = NwayChanges.targetSize(report(m.down))
        val liked = NwayEdit.isLiked(m.playlist.playlistId)
        body.append("""<div class="card" id="target-${h(m.down.id)}"><div class="who">${logo(m.playlist.provider, 32)}<div><b>${h(memberName(m))}</b>: ${h(m.playlist.playlistName)}${count?.let { " <span class=\"muted\">· $it songs</span>" } ?: ""}<br>
<span class="badge">${h(wayName(way))}</span> <span class="muted">${h(wayDesc(way, liked))}</span>${phaseLine(run, m)}</div></div>""")
        if (!busy) {
            body.append("""<form method="post" action="$back/way" class="row"><input type="hidden" name="member" value="${h(m.down.id)}"><label>Way</label>${waySelect(m.playlist.provider, m.playlist.playlistId, way)} <button class="secondary">Change</button></form>""")
            body.append("""<div class="actions"><a class="button secondary" href="$back/remove?member=${q(m.down.id)}">Remove target</a>""")
            if (NwayEdit.canBeSource(m.playlist.provider, m.playlist.playlistId)) body.append(" " + Html.postButton("$back/source", "Make it the source", "secondary", mapOf("member" to m.down.id)))
            body.append("</div>")
        }
        body.append("</div>")
    }
    if (!busy) body.append("""<p class="actions"><a class="button" href="$back/add">+ Add a target</a></p>""")
    // The ways explained once, as the app's editor shows them.
    body.append("""<details class="card"><summary>The ways a target follows</summary><ul>${NwayEdit.Mode.entries.joinToString("") { "<li><b>${h(wayName(it))}</b>: ${h(wayDesc(it))}</li>" }}</ul></details>""")
    val anchor = src?.down
    if (anchor != null && !busy) {
        val paused = members.flatMap { listOfNotNull(it.down, it.up) }.none { it.enabled }
        body.append("""<h2>Schedule</h2><form method="post" action="$back/schedule" class="card"><label>Name</label><input type="text" name="name" value="${h(group.name)}" maxlength="${com.xlollx.songport.shared.Invite.MAX_NAME}">
${timingFields(anchor)}
<label class="check"><input type="checkbox" name="paused"${if (paused) " checked" else ""}> Pause: the multi sync does not run by its schedule until you turn this off</label>
<p><button class="secondary">Save</button></p></form>""")
        body.append("""<p class="actions"><a class="button danger" href="$back/delete">Delete multi sync</a></p>""")
    }
    // Its last runs, each its syncs' reports added up as the app's history shows them.
    val runs = NwayHistory.runs(d.reports, members).reversed()
    if (runs.isNotEmpty()) body.append("<h2>Last runs</h2>")
    for (rep in runs) {
        body.append("""<div class="card"><div class="row"><b>${Html.date(rep.startedEpoch)}</b><span class="muted">${rep.durationMs / 1000} s · source ${rep.sourceCount}</span></div>""")
        body.append(if (rep.error != null) """<p class="${if (rep.netError != null) "net" else "err"}">${h(rep.error)}</p>""" else """<p class="ok">+${rep.added} added · −${rep.removed} removed${if (rep.unmatched.isNotEmpty()) " · ${rep.unmatched.size} not found" else ""}</p>""")
        body.append("</div>")
    }
    // A target's review (not found, to check) is its own sync's, as in the app.
    val reviews = members.mapNotNull { m -> report(m.down)?.takeIf { it.hasReview() }?.let { m to it } }
    if (reviews.isNotEmpty()) body.append("<h2>Review</h2><div class=\"card\"><ul>" + reviews.joinToString("") { (m, rep) ->
        """<li><a href="/syncs/${q(m.down.id)}/review/${q(rep.id)}">${h(memberName(m))}</a>: ${rep.unmatchedTracks.size} not found, ${rep.reviewTracks.size} to check</li>"""
    } + "</ul></div>")
    send(r.ex, 200, page(group.name, body.toString(), refresh = if (busy) 5 else null))
}

/** While it runs, where [m] stands: Waiting, Syncing, Done +a −r, Failed (the app's NwayRun phases). */
private fun Web.phaseLine(run: NwayRun.State?, m: Nway.Member): String {
    val s = run?.services?.firstOrNull { it.member.down.id == m.down.id } ?: return ""
    val text = when (s.phase) {
        NwayRun.Phase.WAITING -> """<span class="muted">Waiting</span>"""
        NwayRun.Phase.NOW -> """<span>Syncing${s.job?.id?.let { SyncState.running.value[it] }?.let { p -> " · " + h(p.step.name.lowercase().replace('_', ' ')) + (if (p.total > 0) " ${p.done}/${p.total}" else "") }.orEmpty()}</span>"""
        NwayRun.Phase.DONE -> """<span class="ok">Done${s.result?.takeIf { it.added > 0 || it.removed > 0 }?.let { " +${it.added} −${it.removed}" }.orEmpty()}</span>"""
        NwayRun.Phase.FAILED -> """<span class="err">Failed${s.result?.error?.let { " · " + h(it) }.orEmpty()}</span>"""
        NwayRun.Phase.IDLE -> return ""
    }
    return """<br><span class="phase" data-phase="${s.phase.name.lowercase()}">$text</span>"""
}

/** "+ Add a target": the service first, then its playlist (or a new one named after the multi sync) and the way. */
private fun Web.multiAddPage(r: Web.Req, group: SharedGroup) {
    val back = "/multi/${q(group.id)}"
    val body = StringBuilder(notice(r))
    val service = r.query["service"]?.let { Providers.byId(it) }?.takeIf { it in writableServices() }
    if (service == null) {
        val options = writableServices().joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="$back/add" class="card"><label>Service</label><select name="service">$options</select><p><button>Next</button> <a class="button secondary" href="$back">Cancel</a></p></form>""")
        return send(r.ex, 200, page("Add a target", body.toString()))
    }
    val lists = runCatching { runBlocking { targetLists(service) } }
    lists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(service.label(env))}: ${h(it.message)}</div>""") }
    val taken = Nway.members(env.store.data.jobs, group.id).filter { it.playlist.provider == service.id }.mapNotNull { it.playlist.playlistId }.toSet()
    val opts = lists.getOrDefault(emptyList()).filter { it.id !in taken }.joinToString("") { p ->
        """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>"""
    }
    body.append("""<form method="post" action="$back/add" class="card"><input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">${if (service.canCreatePlaylists) """<option value="">New playlist: ${h(group.name)}</option>""" else ""}$opts</select>
<label>Way</label><select name="way">${NwayEdit.Mode.entries.joinToString("") { """<option value="${wayKey(it)}"${if (it == NwayEdit.Mode.KEEP) " selected" else ""}>${h(wayName(it))}: ${h(wayDesc(it))}</option>""" }}</select>
<p><button>Add</button> <a class="button secondary" href="$back">Cancel</a></p></form>""")
    send(r.ex, 200, page("Add a target", body.toString()))
}

/**
 * What the last run added or removed on the targets, one row per song ([NwayChanges.group]):
 * "removed from 2: Deezer, TIDAL" or "removed from all 3", and per service the name it used.
 */
private fun Web.multiChanges(r: Web.Req, group: SharedGroup, added: Boolean) {
    val members = multiMembers(group)
    val d = env.store.data
    val batch = NwayHistory.batches(d.reports, members).lastOrNull().orEmpty()
    val kind = if (added) NwayChanges.Kind.ADDED else NwayChanges.Kind.REMOVED
    val songs = NwayChanges.group(NwayChanges.changes(d, batch, members, kind), members.map { it.down.id })
    val byId = members.associateBy { it.down.id }
    val targets = members.drop(1).map { it.down.id }
    val title = "${group.name}: ${if (added) "added" else "removed"}"
    val body = StringBuilder("""<p><a href="/multi/${q(group.id)}">‹ ${h(group.name)}</a></p>""")
    if (songs.isEmpty()) body.append("""<p class="muted">Nothing ${if (added) "added" else "removed"} at the last run.</p>""")
    else body.append("""<p class="muted">${songs.size} ${if (songs.size == 1) "song" else "songs"}, ${songs.sumOf { it.changes.size }} ${if (added) "additions" else "removals"} at the last run (${Html.date(batch.first().startedEpoch)}).</p>""")
    for (song in songs) {
        val n = song.members.size
        val summary = if (NwayChanges.everyTarget(song, targets)) "${if (added) "added to" else "removed from"} all $n"
            else "${if (added) "added to" else "removed from"} $n: " + song.members.joinToString(", ") { id -> byId[id]?.let { memberName(it) } ?: id }
        body.append("""<div class="card song"><b>${h(song.name.artistLine)} — ${h(song.name.title)}</b><br><span class="muted">${h(summary)}</span>""")
        body.append("<details><summary>On each service</summary><ul>")
        for (c in song.changes) {
            val m = c.member?.let { byId[it] } ?: continue
            body.append("<li>${h(memberName(m))}: ${h(c.track.artistLine)} — ${h(c.track.title)}</li>")
        }
        body.append("</ul></details></div>")
    }
    send(r.ex, 200, page(title, body.toString()))
}

// ------------------------------------------------------------------ making one

/** "Several targets" of the New sync form: the source's service first, then its playlist and the targets. */
private fun Web.multiNew(r: Web.Req) {
    val body = StringBuilder(notice(r))
    body.append("""<p class="actions"><a class="button secondary" href="/syncs/new">One target</a> <b>Several targets</b></p>""")
    val sources = multiConnectors().filter { usable(it) && !it.linkOnly }
    val src = r.query["src"]?.let { Providers.byId(it) }?.takeIf { it in sources }
    if (src == null) {
        if (sources.isEmpty()) body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        else body.append("""<form method="get" action="/multi/new" class="card"><label>Source</label><select name="src">${sources.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }}</select>
<p class="muted">One source playlist kept on several services. Each target follows one of three ways.</p><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page("New multi sync", body.toString()))
    }
    val srcLists = runCatching { runBlocking { src.libraryEntries(env, false) + src.playlists(env) } }
    srcLists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(src.label(env))}: ${h(it.message)}</div>""") }
    val sb = StringBuilder("""<form method="post" action="/multi" class="card"><input type="hidden" name="src" value="${h(src.id)}">
<label>Source playlist on ${h(src.label(env))}</label><select name="srcPlaylist" required>${srcLists.getOrDefault(emptyList()).filter { NwayEdit.canBeSource(src.id, it.id) }.joinToString("") { p ->
        """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>"""
    }}</select>
<label>Name (optional: the source playlist's)</label><input type="text" name="name" maxlength="${com.xlollx.songport.shared.Invite.MAX_NAME}">
<h3>Targets</h3><p class="muted">Tick the services to keep the playlist on, each with its playlist and its way.</p>""")
    for (p in writableServices()) {
        val read = runCatching { runBlocking { targetLists(p) } }
        val lists = read.getOrNull()
        if (lists == null) { sb.append("""<p class="err">${h(p.label(env))}: ${h(read.exceptionOrNull()?.message)}</p>"""); continue }
        val k = h(p.id)
        sb.append("""<fieldset class="card"><label class="check"><input type="checkbox" name="target" value="$k"> ${logo(p.id, 24)} <b>${h(p.label(env))}</b></label>
<label>Playlist</label><select name="pl.$k">${if (p.canCreatePlaylists) """<option value="">New playlist of the same name</option>""" else ""}${lists.joinToString("") { pl -> """<option value="${h(pl.id)}">${h(pl.name)}</option>""" }}</select>
<label>Way</label><select name="way.$k">${NwayEdit.Mode.entries.joinToString("") { """<option value="${wayKey(it)}"${if (it == NwayEdit.Mode.KEEP) " selected" else ""}>${h(wayName(it))}: ${h(wayDesc(it))}</option>""" }}</select></fieldset>""")
    }
    sb.append(timingFields(null))
    sb.append("""<p><button>Create and sync</button> <a class="button secondary" href="/multi/new">Back</a></p></form>""")
    body.append(sb)
    send(r.ex, 200, page("New multi sync", body.toString()))
}

/** The form of [multiNew] as a [MultiSpec]: playlists checked against what the services list. */
internal fun Web.multiSpecOf(r: Web.Req): MultiSpec {
    val src = Providers.byId(r.form["src"].orEmpty())?.takeIf { usable(it) } ?: throw IllegalArgumentException("Pick a source.")
    val srcId = r.form["srcPlaylist"].orEmpty().ifBlank { throw IllegalArgumentException("Choose the source playlist.") }
    val srcName = runBlocking { (src.libraryEntries(env, false) + src.playlists(env)).firstOrNull { it.id == srcId }?.name } ?: throw IllegalArgumentException("Choose the source playlist.")
    val ticked = r.rawForm.filter { it.first == "target" }.map { it.second }.distinct()
    val targets = ticked.map { sid ->
        val p = Providers.byId(sid)?.takeIf { it in writableServices() } ?: throw IllegalArgumentException("$sid cannot be a target.")
        val pl = r.form["pl.$sid"]?.ifBlank { null }
        val name = if (pl == null) "" else runBlocking { targetLists(p).firstOrNull { it.id == pl }?.name } ?: throw IllegalArgumentException("${p.label(env)} has no playlist of yours with the id $pl.")
        NwayEdit.Item(p.id, pl, name, mode = wayOf(r.form["way.$sid"]) ?: NwayEdit.Mode.KEEP)
    }
    val timed = withTiming(SyncJob("", "", com.xlollx.songport.model.PlaylistRef("", null, ""), com.xlollx.songport.model.PlaylistRef("", null, "")), r)
    return MultiSpec(r.form["name"].orEmpty().ifBlank { srcName }, NwayEdit.Item(src.id, srcId, srcName, mode = NwayEdit.Mode.TWO_WAY), targets,
        Timing.interval(timed), timed.at, timed.days)
}

private fun Web.multiCreate(r: Web.Req) {
    val back = "/multi/new?src=${q(r.form["src"].orEmpty())}"
    val group = runCatching { createMulti(multiSpecOf(r)) }.getOrElse { return redirect(r.ex, "$back&err=${q(it.message ?: it.javaClass.simpleName)}") }
    // The first sync, as the app's "Create and sync".
    runner.startGroup(group)
    redirect(r.ex, "/multi/${q(group.id)}?ok=${q("Multi sync created: the first sync has started.")}")
}

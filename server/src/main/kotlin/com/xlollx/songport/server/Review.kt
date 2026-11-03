package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * After a run, what the app's review screens do: the tracks not found (take the suggestion, search
 * by hand and pick, or ignore), the uncertain matches (confirm, or search for the right one), and
 * the tracks a mirror sync removed (put them back). Under /syncs/<id>/review/<report id>. Ticked
 * rows are kept, removed or ignored together (issue #26).
 */
private val json = Json { ignoreUnknownKeys = true }

private fun encode(t: Track) = json.encodeToString(Track.serializer(), t)

internal fun Web.review(r: Web.Req, job: SyncJob, rest: List<String>) {
    val report = rest.getOrNull(0)?.let { id -> env.store.data.reports.firstOrNull { it.id == id && it.jobId == job.id } }
        ?: return send(r.ex, 404, page("Unknown run", """<p><a href="/syncs/${q(job.id)}">Back</a></p>"""))
    val base = "/syncs/${q(job.id)}/review/${q(report.id)}"
    val action = rest.getOrNull(1)
    if (r.method != "POST") {
        return when (action) {
            null -> reviewPage(r, job, report)
            "search" -> searchPage(r, job, report, base)
            else -> send(r.ex, 404, page("Not found", ""))
        }
    }
    val engine = SyncEngine(env)
    val srcId = r.form["track"].orEmpty()
    // The chosen track comes from this server's own search page; only its source track is looked up here.
    fun chosen(): Track? = r.form["chosen"]?.let { runCatching { json.decodeFromString(Track.serializer(), it) }.getOrNull() }
    val unmatched = report.unmatchedTracks.firstOrNull { it.id == srcId }
    val uncertain = report.reviewTracks.firstOrNull { it.source.id == srcId }
    // The ticked rows of a section ("pick" repeated: a map would keep only the last).
    val ticked = r.rawForm.filter { it.first == "pick" }.map { it.second }.toSet()
    val tickedReviews = report.reviewTracks.filter { it.source.id in ticked }
    val tickedTracks = report.unmatchedTracks.filter { it.id in ticked }
    fun tracks(n: Int) = if (n == 1) "1 track" else "$n tracks"
    val result = runCatching {
        when (action) {
            "resolve" -> {
                val c = chosen() ?: error("No track chosen.")
                runBlocking { engine.resolveManually(job, report.id, unmatched ?: error("That track is no longer in the list."), c) }
                "Added: ${c.artistLine} — ${c.title}."
            }
            "ignore" -> {
                engine.ignore(job, report.id, unmatched ?: error("That track is no longer in the list."))
                "Ignored: it will not be searched for again on this service."
            }
            "ignore-many" -> {
                if (tickedTracks.isEmpty()) error("Tick the tracks first.")
                engine.ignore(job, report.id, tickedTracks)
                "Ignored ${tracks(tickedTracks.size)}: not searched for again on this service."
            }
            "keep-many" -> {
                if (tickedReviews.isEmpty()) error("Tick the matches first.")
                engine.confirmMatches(report.id, tickedReviews)
                "Kept ${tracks(tickedReviews.size)}."
            }
            "discard", "discard-many" -> {
                val list = if (action == "discard") listOf(uncertain ?: error("That match is no longer in the list.")) else tickedReviews
                if (list.isEmpty()) error("Tick the matches first.")
                val n = runBlocking { engine.discardMatches(job, report.id, list) }
                if (Providers.byId(job.target.provider)?.canRemoveTracks == false) "No longer synced: ${tracks(list.size)}. ${label(job.target.provider)} does not let Trackhop remove tracks: delete them there if you want."
                else "Removed ${tracks(n)} from ${label(job.target.provider)}; this sync no longer adds them."
            }
            "confirm" -> {
                engine.confirmMatch(report.id, uncertain ?: error("That match is no longer in the list."))
                "Kept."
            }
            "replace" -> {
                val c = chosen() ?: error("No track chosen.")
                runBlocking { engine.replaceMatch(job, report.id, uncertain ?: error("That match is no longer in the list."), c) }
                "Replaced with ${c.artistLine} — ${c.title}."
            }
            "restore" -> {
                runBlocking { engine.restoreRemoved(job, report) }
                "Put back ${report.removedTracks.size} tracks."
            }
            else -> error("Unknown action.")
        }
    }
    env.store.flush()
    result.fold(
        onSuccess = { redirect(r.ex, "$base?ok=${q(it)}") },
        onFailure = { redirect(r.ex, "$base?err=${q(it.message ?: it.javaClass.simpleName)}") },
    )
}

private fun Web.reviewPage(r: Web.Req, job: SyncJob, report: SyncReport) {
    val base = "/syncs/${q(job.id)}/review/${q(report.id)}"
    val canWrite = job.target.playlistId != null
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">${h(label(job.source.provider))}: ${h(job.source.playlistName)} → ${h(label(job.target.provider))}: ${h(job.target.playlistName)} · run of ${Html.date(report.startedEpoch)} · <a href="/syncs/${q(job.id)}">Back to the sync</a></p>""")
    if (report.unmatchedTracks.isEmpty() && report.reviewTracks.isEmpty() && report.removedTracks.isEmpty()) {
        body.append("""<div class="card">Nothing left to review in this run.</div>""")
    }
    if (report.unmatchedTracks.isNotEmpty()) {
        body.append("""<h2>Not found (${report.unmatchedTracks.size})</h2><p class="muted">Pick the right one once: Trackhop remembers it for the next syncs. Or ignore the track.</p>""")
        if (report.unmatchedTracks.size > 1) {
            body.append(bulkBar("bulk-u", "$base/ignore-many", listOf("""<button class="secondary">Ignore the ticked</button>""")))
        }
        for (t in report.unmatchedTracks) {
            val sug = report.suggestions[t.id]
            body.append("""<div class="card"><div class="row">${tick("bulk-u", t.id, report.unmatchedTracks.size)}<div style="flex:1"><b>${h(t.title)}</b><br><span class="muted">${h(t.artistLine)}${if (t.album.isNotBlank()) " · " + h(t.album) else ""}</span>""")
            if (t.id in report.absent) body.append("""<br><span class="muted">${h(label(job.target.provider))} had no result at all.</span>""")
            if (sug != null) body.append("""<br>Closest: ${h(sug.artistLine)} — ${h(sug.title)}""")
            body.append("</div><div class=\"actions\">")
            if (canWrite && sug != null) body.append(Html.postButton("$base/resolve", "Use it", fields = mapOf("track" to t.id, "chosen" to encode(sug))))
            if (canWrite) body.append(""" <a class="button secondary" href="$base/search?track=${q(t.id)}&mode=resolve">Search</a> """)
            body.append(Html.postButton("$base/ignore", "Ignore", "secondary", fields = mapOf("track" to t.id)))
            body.append("</div></div></div>")
        }
    }
    if (report.reviewTracks.isNotEmpty()) {
        body.append("""<h2>To check (${report.reviewTracks.size})</h2><p class="muted">Matches Trackhop was not sure of: keep them, pick the right one, or remove them.</p>""")
        val dstName = label(job.target.provider)
        val removes = Providers.byId(job.target.provider)?.canRemoveTracks != false
        val ask = if (removes) "Take the ticked matches out of $dstName? This sync will no longer add these songs."
            else "$dstName does not let Trackhop remove tracks: they stay in the playlist, but this sync will no longer add these songs."
        if (report.reviewTracks.size > 1) {
            body.append(
                bulkBar(
                    "bulk-r", "$base/keep-many",
                    listOf(
                        """<button>Keep the ticked</button>""",
                        if (canWrite) """<button class="secondary" formaction="$base/discard-many" onclick="return confirm('${h(ask.replace("'", "\\'"))}')">Remove the ticked</button>""" else "",
                    ),
                ),
            )
        }
        for (m in report.reviewTracks) {
            body.append(
                """<div class="card"><div class="row">${tick("bulk-r", m.source.id, report.reviewTracks.size)}<div style="flex:1">${h(m.source.artistLine)} — <b>${h(m.source.title)}</b><br>
<span class="muted">became</span> ${h(m.chosen.artistLine)} — ${h(m.chosen.title)} <span class="badge">${(m.score * 100).toInt()}%</span></div><div class="actions">""",
            )
            body.append(Html.postButton("$base/confirm", "Right", fields = mapOf("track" to m.source.id)))
            if (canWrite) body.append(""" <a class="button secondary" href="$base/search?track=${q(m.source.id)}&mode=replace">Change</a> """)
            if (canWrite) body.append(Html.postButton("$base/discard", "Remove", "secondary", fields = mapOf("track" to m.source.id), confirm = if (removes) "Take this match out of $dstName? This sync will no longer add the song." else ask))
            body.append("</div></div></div>")
        }
    }
    if (report.removedTracks.isNotEmpty()) {
        body.append("""<h2>Removed (${report.removedTracks.size})</h2><div class="card"><p class="muted">Mirror removals took these out of the destination.</p><ul>""")
        report.removedTracks.take(100).forEach { body.append("<li>${h(it.artistLine)} — ${h(it.title)}</li>") }
        if (report.removedTracks.size > 100) body.append("<li class=\"muted\">…</li>")
        body.append("</ul>")
        if (canWrite) body.append(Html.postButton("$base/restore", "Put them back", "secondary", confirm = "Add these ${report.removedTracks.size} tracks back to the destination?"))
        body.append("</div>")
    }
    send(r.ex, 200, page("Review: ${job.name}", body.toString()))
}

private fun Web.searchPage(r: Web.Req, job: SyncJob, report: SyncReport, base: String) {
    val srcId = r.query["track"].orEmpty()
    val replace = r.query["mode"] == "replace"
    val source: Track = (if (replace) report.reviewTracks.firstOrNull { it.source.id == srcId }?.source else report.unmatchedTracks.firstOrNull { it.id == srcId })
        ?: return redirect(r.ex, "$base?err=${q("That track is no longer in the list.")}")
    val query = r.query["q"]?.takeIf { it.isNotBlank() } ?: listOf(source.artistLine, source.title).filter { it.isNotBlank() }.joinToString(" - ")
    val body = StringBuilder()
    body.append("""<p class="muted">Looking for <b>${h(source.title)}</b> · ${h(source.artistLine)}${if (source.album.isNotBlank()) " · " + h(source.album) else ""} on ${h(label(job.target.provider))}. <a href="$base">Back to the review</a></p>""")
    body.append(
        """<form method="get" action="$base/search" class="card"><input type="hidden" name="track" value="${h(srcId)}"><input type="hidden" name="mode" value="${if (replace) "replace" else "resolve"}">
<label>Search (artist - title, the title alone, or a link to the track)</label><input type="text" name="q" value="${h(query)}"><p><button>Search</button></p></form>""",
    )
    val result = runCatching { runBlocking { SyncEngine(env).searchOnTarget(job, query, source) } }
    result.exceptionOrNull()?.let { body.append("""<div class="card err">${h(it.message ?: it.javaClass.simpleName)}</div>""") }
    result.getOrNull()?.let { found ->
        if (found.hits.isEmpty()) body.append("""<div class="card muted">No results. Try the title alone, or paste a link to the track.</div>""")
        if (found.albumFound == false) body.append("""<p class="muted">The album “${h(found.album)}” is not on ${h(label(job.target.provider))}.</p>""")
        for (hit in found.hits.take(25)) {
            val where = when (hit.kind) {
                TargetSearch.Kind.ALBUM -> "from the album"
                TargetSearch.Kind.VIDEO -> "video"
                TargetSearch.Kind.SONG -> null
            }
            body.append(
                """<div class="card"><div class="row"><div><b>${h(hit.track.title)}</b><br><span class="muted">${h(hit.track.artistLine)}${if (hit.track.album.isNotBlank()) " · " + h(hit.track.album) else ""}${duration(hit.track.durationMs)}</span>
${where?.let { """<span class="badge">$it</span> """ } ?: ""}<span class="badge">${(hit.score * 100).toInt()}%</span></div><div class="actions">""" +
                    Html.postButton("$base/${if (replace) "replace" else "resolve"}", "Pick", fields = mapOf("track" to srcId, "chosen" to encode(hit.track))) +
                    "</div></div></div>",
            )
        }
    }
    send(r.ex, 200, page("Find the right track", body.toString()))
}

/** The form a section's ticked rows go with, its "tick all" box and its buttons. */
private fun bulkBar(form: String, action: String, buttons: List<String>): String =
    """<form id="$form" method="post" action="${h(action)}"></form><div class="card row"><label class="check" style="margin:0"><input type="checkbox" """ +
        """onchange="for(const c of document.querySelectorAll('input[name=pick][form=$form]'))c.checked=this.checked"> Tick all</label>""" +
        """<div class="actions">${buttons.filter { it.isNotEmpty() }.joinToString(" ") { it.replace("<button", "<button form=\"$form\"") }}</div></div>"""

/** A row's box for its section's form; none in a section of one. */
private fun tick(form: String, id: String, rows: Int): String =
    if (rows < 2) "" else """<input type="checkbox" name="pick" value="${h(id)}" form="$form" aria-label="Tick" style="margin-right:10px">"""

private fun duration(ms: Long): String = if (ms <= 0) "" else " · %d:%02d".format(ms / 60000, ms / 1000 % 60)

/** Whether a run left anything to review. */
internal fun SyncReport.hasReview(): Boolean = unmatchedTracks.isNotEmpty() || reviewTracks.isNotEmpty() || removedTracks.isNotEmpty()

package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking

/**
 * Removals a run held back, under /syncs/<id>/removals: what the app's preview does with its tick.
 * A run that would take away a quarter or more of the destination (or of what a two-way pair had
 * in common) keeps those tracks, since a playlist the service listed only in part looks the same.
 * Here the person sees them and confirms: the next run started by hand removes exactly these, once,
 * within twelve hours. A scheduled run never does.
 */
internal fun Web.heldRemovals(r: Web.Req, job: SyncJob) {
    val back = "/syncs/${q(job.id)}"
    val title = "Removals held back"
    if (r.method == "POST") {
        val ids = r.rawForm.filter { it.first == "id" }.map { it.second }
        if (r.form["confirm"] != "on" || ids.isEmpty()) return redirect(r.ex, "$back/removals?err=${q("Tick the box to confirm the removals.")}")
        if (runner.isRunning(job.id)) return redirect(r.ex, "$back?err=${q("${job.name} is running: confirm when it has ended.")}")
        env.store.confirmRemovals(job.id, ids)
        runner.start(job)
        return redirect(r.ex, "$back?ok=${q("Confirmed: this run removes them.")}")
    }
    if (job.radio != null) return redirect(r.ex, back)
    val plan = runCatching { runBlocking { SyncEngine(env).plan(job, createTarget = false, interactive = true) } }
        .getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(it.message ?: it.javaClass.simpleName)}</div><p><a href="$back">Back</a></p>""")) }
    val body = StringBuilder(notice(r))
    val unlikes = job.target.playlistId == MusicProvider.LIKED_ID
    if (!plan.removalsNeedConfirmation || plan.toRemove.isEmpty()) {
        body.append("""<div class="card ok">${h(job.name)}: nothing is held back now${if (plan.toRemove.isNotEmpty()) " (a run would remove ${plan.toRemove.size}, without asking)" else ""}.</div>""")
        plan.notes.forEach { body.append("""<p class="muted">${h(it)}</p>""") }
        body.append("""<p><a class="button secondary" href="$back">Back</a></p>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val n = plan.toRemove.size
    body.append(
        """<div class="card"><p>A run of <b>${h(job.name)}</b> would ${if (unlikes) "unlike" else "remove"} <b>$n</b> of the ${plan.targetCount} ${if (unlikes) "songs in Liked songs" else "tracks in the destination"}. Check that the source really lists that few (${plan.sourceCount} now): a playlist the service listed only in part looks exactly like this. Until you confirm, runs add tracks and keep these.</p>
<form method="post" action="$back/removals">""",
    )
    plan.toRemove.forEach { body.append("""<input type="hidden" name="id" value="${h(it.id)}">""") }
    body.append(
        """<label class="check"><input type="checkbox" name="confirm" required> ${if (unlikes) "Unlike these $n songs" else "Remove these $n tracks from the destination"}</label>
<p><button class="danger">Confirm and run now</button> <a class="button secondary" href="$back">Back</a></p>
<p class="muted">Valid for one run started by hand, within 12 hours; a scheduled run never removes them.</p></form><ul>""",
    )
    plan.toRemove.forEach { t -> body.append("<li>${h(t.artistLine)} — ${h(t.title)}</li>") }
    body.append("</ul></div>")
    send(r.ex, 200, page(title, body.toString()))
}

package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.server.Html.h

/**
 * Several syncs at once, from the home page's Select: the ticked ones run one after another (as Sync
 * all runs them), are turned on or off, or deleted after a confirmation page. Plain links and forms,
 * no JavaScript: the boxes on the cards belong to the bar's form through their form= attribute, since
 * the cards hold forms of their own. Each sync goes through the same code as its own buttons, so a
 * two-way pair is turned on, off or deleted together; on a shared server [env] is this person's own
 * Trackhop, so only their syncs are found.
 */

private const val FORM = "bulk-syncs"

/** Starts [jobs] in this order; the runner's slots take them one after another. What Sync all does. */
internal fun Web.startSyncs(jobs: List<SyncJob>) { runner.startAll(jobs) }

private fun syncCount(n: Int) = if (n == 1) "1 sync" else "$n syncs"

/** [link] (the home page as filtered now, "/" or "/?…") with more of the address after it. */
private fun homeWith(link: String, more: String) = if (link == "/") "/?$more" else "$link&$more"

/** Above the cards: "N syncs · Select", or in select mode its links and the bar's buttons. */
internal fun Web.bulkSyncsBar(home: String, shown: Int, selecting: Boolean): String {
    if (!selecting) return """<p class="muted">${syncCount(shown)} · <a href="${h(homeWith(home, "select=1"))}">Select</a></p>"""
    return """<p class="muted"><a href="${h(homeWith(home, "select=1&all=1"))}">Select all</a> · <a href="${h(homeWith(home, "select=1"))}">None</a> · <a href="${h(home)}">Done</a></p>""" +
        """<form id="$FORM" method="post" action="/syncs/bulk" class="card"><div class="actions">""" +
        """<button name="action" value="run">Run now</button> <button class="secondary" name="action" value="on">Turn on</button> """ +
        """<button class="secondary" name="action" value="off">Turn off</button> <button class="secondary" name="action" value="edit">Edit…</button> <button class="danger" name="action" value="delete">Delete…</button></div></form>"""
}

/** A card's box in select mode, ticked with Select all. */
internal fun bulkSyncTick(job: SyncJob, on: Boolean): String =
    """<input type="checkbox" name="job" value="${h(job.id)}" form="$FORM" aria-label="Select ${h(job.name)}"${if (on) " checked" else ""} style="margin-right:10px">"""

/** POST /syncs/bulk: what the bar's buttons (and the delete confirmation) ask for the ticked syncs. */
internal fun Web.bulkSyncs(r: Web.Req) {
    // Ids not in this person's store (another user's, or deleted since) are passed over.
    val ticked = r.rawForm.filter { it.first == "job" }.map { it.second }.distinct().mapNotNull { env.store.job(it) }
    if (ticked.isEmpty()) return redirect(r.ex, "/?select=1&err=${q("Tick at least one sync.")}")
    when (r.form["action"]) {
        "run" -> {
            // In the list's order, as Sync all; off ones too, as a sync's own Run now.
            val ids = ticked.map { it.id }.toSet()
            startSyncs(env.store.data.jobs.filter { it.id in ids })
            redirect(r.ex, "/?ok=${q("Running ${syncCount(ticked.size)}")}")
        }
        "on", "off" -> {
            val on = r.form["action"] == "on"
            // As each sync's own toggle: its two-way half with it; turned on, it is scheduled here.
            ticked.forEach { j -> env.store.job(j.id)?.let { enablePair(it, on) } }
            redirect(r.ex, "/?ok=${q("${syncCount(ticked.size)} turned ${if (on) "on" else "off"}")}")
        }
        // Their schedule, colour or label, and removals, at once (SchedulesWeb.kt).
        "edit" -> bulkEditPage(r, ticked)
        "edit-apply" -> bulkEditApply(r, ticked)
        "delete" -> confirmDelete(r, goners(ticked))
        "delete-confirmed" -> {
            var gone = 0
            ticked.forEach { j -> env.store.job(j.id)?.let { job -> gone += halves(job).size; deletePair(job) } }
            redirect(r.ex, "/?ok=${q("${syncCount(gone)} deleted")}")
        }
        else -> redirect(r.ex, "/?select=1")
    }
}

/** The ticked syncs and, for a two-way pair, its other half: what a delete takes away, each once. */
private fun Web.goners(ticked: List<SyncJob>): List<SyncJob> = ticked.flatMap { halves(it) }.distinctBy { it.id }

private fun Web.confirmDelete(r: Web.Req, jobs: List<SyncJob>) {
    val n = syncCount(jobs.size)
    val hidden = jobs.joinToString("") { """<input type="hidden" name="job" value="${h(it.id)}">""" }
    val names = jobs.joinToString("") { "<li>${h(it.name)}</li>" }
    send(
        r.ex, 200,
        page(
            "Delete $n?",
            """<div class="card"><h2>Delete $n?</h2><ul>$names</ul><p class="muted">The playlists on the services stay as they are; only the syncs go.</p>
<form method="post" action="/syncs/bulk">$hidden<input type="hidden" name="action" value="delete-confirmed"><div class="actions"><button class="danger">Delete $n</button> <a class="button secondary" href="/?select=1">Cancel</a></div></form></div>""",
        ),
    )
}

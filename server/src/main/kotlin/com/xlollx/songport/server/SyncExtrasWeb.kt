package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SyncEngine

/**
 * A sync's page beyond its timing and changes, as the app's editor and sync menu: the matching
 * choices (how close a match must be, explicit versions, same album, studio versions only) and the
 * source's order kept; and the preview, what a run would do now, without changing anything.
 */

/** The "Matching" part of a sync's form; nothing for a radio sync, whose songs come as they play. */
internal fun Web.matchingFields(job: SyncJob?): String {
    if (job?.radio != null) return ""
    val p = job?.policy ?: com.xlollx.songport.model.MatchPolicy()
    fun radio(name: String, value: Int, now: Int, text: String) =
        """<label class="check"><input type="radio" name="$name" value="$value"${if (value == now) " checked" else ""}> $text</label>"""
    return """
<input type="hidden" name="matching" value="1"><fieldset style="border:0;padding:0;margin:12px 0 0"><legend style="font-weight:600;font-size:14px">Matching</legend>
<label>How close a match must be</label>
${radio("strictness", -1, p.strictness, "Lenient: more songs found, more of them to check")}
${radio("strictness", 0, p.strictness, "Normal")}
${radio("strictness", 1, p.strictness, "Strict: only very close matches")}
<label>Explicit versions</label>
${radio("explicit", -1, p.explicit, "Prefer the clean version")}
${radio("explicit", 0, p.explicit, "Either")}
${radio("explicit", 1, p.explicit, "Prefer the explicit version")}
<label class="check"><input type="checkbox" name="sameAlbum"${if (p.sameAlbum) " checked" else ""}> Same album: a song from another album counts much less</label>
<label class="check"><input type="checkbox" name="studioOnly"${if (p.studioOnly) " checked" else ""}> Studio versions only: never a live, remix, acoustic or karaoke version in place of one that is not</label>
<label class="check"><input type="checkbox" name="artistNamesCanDiffer"${if (p.artistsMayDiffer) " checked" else ""}> Artist names can differ: the same title, length and album is enough (as for 周杰倫 and Jay Chou); without an album, to review</label>
<label class="check"><input type="checkbox" name="keepOrder"${if (job?.keepOrder == true) " checked" else ""}> Keep the source's order in the destination (a little slower on some services)</label></fieldset>"""
}

/** [job] with the choices of [matchingFields], when its form had them. */
internal fun withMatching(job: SyncJob, form: Map<String, String>): SyncJob {
    if (form["matching"] != "1" || job.radio != null) return job
    fun level(k: String, now: Int) = form[k]?.toIntOrNull()?.takeIf { it in -1..1 } ?: now
    val p = job.policy.copy(
        strictness = level("strictness", job.policy.strictness),
        explicit = level("explicit", job.policy.explicit),
        sameAlbum = form["sameAlbum"] == "on",
        studioOnly = form["studioOnly"] == "on",
        artistsMayDiffer = form["artistNamesCanDiffer"] == "on",
    )
    return job.copy(policy = p, keepOrder = form["keepOrder"] == "on")
}

/** Preview on a sync's page: what a run would do now, read in the background (both playlists are read), shown on the task's page. */
internal fun Web.startPreview(r: Web.Req, job: SyncJob) {
    val back = "/syncs/${q(job.id)}"
    if (job.radio != null) return redirect(r.ex, "$back?err=${q("A radio sync's songs come from the station as it plays: there is nothing to preview.")}")
    val task = Tasks.startOnce("Preview of ${job.name}", back, { env.log("preview", it) }, env.owner) { t ->
        t.progress = "Reading both playlists…"
        // As the app's preview: every removal is shown, with whether a run would hold it back; nothing is changed.
        val plan = SyncEngine(env).plan(job, createTarget = false, interactive = true)
        previewHtml(job, plan)
    } ?: return redirect(r.ex, "$back?err=${q("Its preview is already being read.")}")
    redirect(r.ex, "/tools/task/${q(task.id)}")
}

private fun Web.previewHtml(job: SyncJob, plan: com.xlollx.songport.model.SyncPlan): String {
    fun songs(title: String, tracks: List<Track>, open: Boolean = true): String {
        if (tracks.isEmpty()) return ""
        val shown = tracks.take(200).joinToString("") { "<li>${h(it.artistLine)} – ${h(it.title)}</li>" }
        val more = if (tracks.size > 200) """<li class="muted">…and ${tracks.size - 200} more</li>""" else ""
        return """<details${if (open) " open" else ""}><summary><b>$title (${tracks.size})</b></summary><ul>$shown$more</ul></details>"""
    }
    val id = q(job.id)
    val held = if (plan.toRemove.isNotEmpty() && plan.removalsNeedConfirmation) """<p class="muted">A run holds these removals back until you confirm them (they are many, or the source changed a lot).</p>""" else ""
    val shrunk = plan.targetShrunkFrom.takeIf { it > 0 }?.let { """<p class="err">The destination had $it songs at the last run and has ${plan.targetCount} now: a run stops to ask first.</p>""" }.orEmpty()
    val nothing = if (plan.toAdd.isEmpty() && plan.toRemove.isEmpty() && plan.unmatched.isEmpty() && plan.uncertain.isEmpty()) """<p class="ok">Nothing to do: the destination is up to date.</p>""" else ""
    return """<div class="card"><h2 style="margin-top:0">${h(job.name)}</h2><p class="muted">${h(label(job.source.provider))}: ${h(job.source.playlistName)} → ${h(label(job.target.provider))}: ${h(job.target.playlistName.ifBlank { "new playlist" })}</p>
<div class="stats"><p class="stat"><b>${plan.sourceCount}</b><span class="muted">in the source</span></p><p class="stat"><b>${plan.alreadyPresent}</b><span class="muted">already there</span></p><p class="stat"><b>${plan.ignored}</b><span class="muted">ignored</span></p></div>
$nothing$shrunk${songs("Will add", plan.toAdd)}${songs("Will remove", plan.toRemove)}$held${songs("Not found", plan.unmatched, open = false)}${songs("To check", plan.uncertain.map { it.source }, open = false)}
${plan.notes.joinToString("") { """<p class="muted">${h(it)}</p>""" }}
<p class="muted">Nothing was changed. A run may still find a little more or less: the services can change in the meantime.</p>
<div class="actions">${Html.postButton("/syncs/$id/run", "Run now")} <a class="button secondary" href="/syncs/$id">Back to the sync</a></div></div>"""
}

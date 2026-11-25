package com.xlollx.songport.server

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.OneOffs
import com.xlollx.songport.sync.Setlists
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Recent transfers (the app's Tools › Recent transfers, [OneOffs]): a File playlist (a setlist, a
 * pasted list) sent once to a service. Not a sync: it never shows or runs as one; its songs not
 * found are reviewed from here, and it goes by itself after a week (the playlist made stays).
 */

/** The services a one-off transfer can write to. */
private fun Web.transferTargets() = Providers.connectors().filter { it.canWrite && it.canCreatePlaylists && !it.linkOnly && it !is LocalFilesProvider && (!it.requiresAuth || it.isConnected(env)) }

/** "Make it a playlist on …" for the File playlist [fileId]; nothing when no service can take it. */
internal fun Web.transferForm(fileId: String, name: String): String {
    val targets = transferTargets()
    if (targets.isEmpty()) return ""
    val opts = targets.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
    return """<form method="post" action="/tools/transfers" class="finder"><input type="hidden" name="action" value="transfer"><input type="hidden" name="file" value="${h(fileId)}"><input type="hidden" name="name" value="${h(name)}"><label for="to-${h(fileId)}" style="margin:0">Make it a playlist on</label> <select id="to-${h(fileId)}" name="to" style="width:auto">$opts</select> <button>Send</button></form>"""
}

/** Starts the one-off transfer of a File playlist to a service: a new playlist there, run now. */
internal fun Web.transferOnce(fileId: String, name: String, to: String): SyncJob {
    if (fileId.isBlank() || '/' in fileId || '\\' in fileId || !java.io.File(LocalFilesProvider.dir(env), "$fileId.csv").isFile) throw IllegalArgumentException("No File playlist \"$fileId\".")
    val target = transferTargets().firstOrNull { it.id == to } ?: throw IllegalArgumentException("Pick a connected service that can make playlists.")
    val job = Setlists.job(UUID.randomUUID().toString(), name.ifBlank { fileId }, fileId, PlaylistRef(target.id, null, ""))
    env.store.upsertJob(job)
    runner.start(job)
    env.log("transfer", "$fileId sent once to ${target.label(env)}")
    return job
}

internal fun Web.startTransfer(r: Web.Req) {
    runCatching { transferOnce(r.form["file"].orEmpty(), r.form["name"].orEmpty(), r.form["to"].orEmpty()) }
        .getOrElse { return redirect(r.ex, "/tools/transfers?err=${q(it.message ?: "Error")}") }
    redirect(r.ex, "/tools/transfers?ok=${q("Sending it: it shows below when done.")}")
}

internal fun Web.transfersPage(r: Web.Req) {
    if (r.method == "POST") {
        when (r.form["action"]) {
            "transfer" -> return startTransfer(r)
            "delete" -> {
                env.store.job(r.form["id"].orEmpty())?.takeIf { it.oneOff }?.let { env.store.deleteJob(it.id) }
                return redirect(r.ex, "/tools/transfers?ok=${q("Taken off the list. The playlist stays as it is.")}")
            }
        }
        return redirect(r.ex, "/tools/transfers")
    }
    env.store.dropOldOneOffs()
    val d = env.store.data
    val list = OneOffs.recent(d)
    val running = com.xlollx.songport.sync.SyncState.running.value
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The playlists sent once to a service (a setlist, a pasted list): what each did, and the songs to review. Each goes from this list once reviewed, or after a week; the playlist made stays.</p>""")
    if (list.isEmpty()) body.append("""<div class="card">No transfers in the last week.</div>""")
    for (job in list) {
        val report = job.lastReportId?.let { id -> d.reports.firstOrNull { it.id == id } }
        val where = label(job.target.provider)
        val state = when {
            job.id in running || report == null -> """<span class="muted">Sending…</span>"""
            report.error != null -> """<span class="err">${h(report.error)}</span>"""
            else -> Setlists.summary(report).let { s -> """<span class="ok">+${s.added}</span> <span class="muted">· ${s.alreadyThere} already there · ${s.notFound} not found</span>""" }
        }
        val toReview = report?.let { OneOffs.toReview(it) } ?: 0
        val review = if (toReview > 0) """<a class="button" href="/syncs/${q(job.id)}/review/${q(report!!.id)}">Review ($toReview)</a> """ else ""
        body.append("""<div class="card"><div class="row"><span><b>${h(job.name)}</b> <span class="muted">→ ${h(where)} · ${Html.date(job.createdEpoch)}</span><br>$state</span><div class="actions">$review${Html.postButton("/tools/transfers", "Remove", "danger", mapOf("action" to "delete", "id" to job.id))}</div></div></div>""")
    }
    send(r.ex, 200, page("Recent transfers", body.toString(), refresh = if (list.any { it.id in running }) 5 else null))
}

internal fun Web.transferJson(job: SyncJob): JsonObject {
    val report = job.lastReportId?.let { env.store.report(it) }
    return buildJsonObject {
        put("id", job.id); put("name", job.name); put("created", job.createdEpoch)
        put("source", job.source.playlistId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("target", buildJsonObject { put("service", job.target.provider); put("playlist", job.target.playlistId?.let { JsonPrimitive(it) } ?: JsonNull) })
        put("running", runner.isRunning(job.id))
        put("result", report?.let { r ->
            val s = Setlists.summary(r)
            buildJsonObject {
                put("runId", r.id); put("added", s.added); put("alreadyThere", s.alreadyThere); put("notFound", s.notFound); put("toReview", OneOffs.toReview(r))
                put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        } ?: JsonNull)
    }
}

internal fun Web.apiTransfers(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "transfers" || rest.size > 2) return false
    when {
        rest.size == 1 && method == "GET" -> { env.store.dropOldOneOffs(); json(200, JsonArray(OneOffs.recent(env.store.data).map { transferJson(it) })) }
        rest.size == 1 && method == "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"file\": \"<File playlist>\", \"to\": \"<service>\"}.") }
            val job = runCatching { transferOnce(b.text("file").orEmpty(), b.text("name").orEmpty(), b.text("to").orEmpty()) }.getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
            json(202, transferJson(job))
        }
        rest.size == 2 && method == "GET" -> json(200, transferJson(env.store.job(rest[1])?.takeIf { it.oneOff } ?: return true.also { error(404, "No recent transfer with that id.") }))
        rest.size == 2 && method == "DELETE" -> {
            val job = env.store.job(rest[1])?.takeIf { it.oneOff } ?: return true.also { error(404, "No recent transfer with that id.") }
            env.store.deleteJob(job.id)
            json(200, buildJsonObject { put("deleted", job.id) })
        }
        else -> error(405, "GET, POST or DELETE.")
    }
    return true
}

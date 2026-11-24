package com.xlollx.songport.server

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.BulkEdit
import com.xlollx.songport.sync.Labels
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.Timing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Saved schedules (the app's Settings › Schedules, [Schedules]): a timing kept once, with a name,
 * used by any number of syncs, which follow it when it changes; and several syncs changed at once
 * from the home page's Select ([BulkEdit]): their schedule, their colour or label, what they do
 * with removals. The store keeps linked syncs in line by itself (Schedules.normalized).
 */

// ------------------------------------------------------------------ the schedules

/** A schedule as the API gives it. */
internal fun Web.scheduleJson(s: SavedSchedule, d: StoreData = env.store.data) = buildJsonObject {
    put("id", s.id); put("name", s.name); put("timing", Schedules.describe(s))
    put("every", s.everyMinutes); put("at", s.at?.let { JsonPrimitive(it) } ?: JsonNull)
    put("days", buildJsonArray { s.days.forEach { add(it) } })
    put("syncs", buildJsonArray { Schedules.usedBy(d, s.id).forEach { add(it.id) } })
    put("nextRun", Schedules.nextRun(s, System.currentTimeMillis())?.let { JsonPrimitive(it) } ?: JsonNull)
}

/** The schedule [ask] names: its id, or its name (case ignored). */
internal fun Web.findSchedule(ask: String): SavedSchedule? = env.store.data.settings.savedSchedules.let { all ->
    all.firstOrNull { it.id == ask } ?: all.firstOrNull { it.name.equals(ask.trim(), ignoreCase = true) }
}

private val AT = Regex("""([01]?\d|2[0-3]):[0-5]\d""")

/** A timing from words: every (minutes), at (HH:MM), days (1 = Monday); a weekly one without a day runs today's. */
private fun timing(every: Int, at: String?, days: List<Int>): Triple<Int, String?, List<Int>> {
    if (every !in setOf(0, 60, 360, 1440, 10080) && every !in 60..10080) throw IllegalArgumentException("\"every\" is minutes: 60 to 10080 (60 hourly, 360 every 6 h, 1440 daily, 10080 weekly).")
    val a = at?.let { if (!AT.matches(it)) throw IllegalArgumentException("\"at\" is a time as HH:MM."); it.padStart(5, '0') }
    if (days.any { it !in 1..7 }) throw IllegalArgumentException("\"days\" are numbers from 1 (Monday) to 7 (Sunday).")
    val d = if (every >= 10080 && days.isEmpty()) listOf(java.time.LocalDate.now().dayOfWeek.value) else days
    return Triple(every, a, d)
}

/** Makes a schedule, or changes [old]; throws IllegalArgumentException for a timing that would be "manual only". */
internal fun Web.saveSchedule(old: SavedSchedule?, name: String?, every: Int?, at: String?, days: List<Int>?): SavedSchedule {
    val base = old ?: SavedSchedule("")
    val (e, a, d) = timing(every ?: base.everyMinutes.takeIf { it > 0 } ?: 1440, at ?: base.at ?: "09:00", days ?: base.days)
    val n = (name ?: base.name).trim().take(Schedules.MAX_NAME)
    val made = Schedules.make(n, e, a, d) ?: throw IllegalArgumentException("A schedule runs by itself: \"manual only\" is not one.")
    val s = if (old != null) made.copy(id = old.id) else made
    env.store.update { Schedules.save(it, s) }
    return env.store.data.settings.savedSchedules.first { it.id == s.id }
}

internal fun Web.schedulesPage(r: Web.Req) {
    val back = "/settings/schedules"
    if (r.method == "POST") {
        val old = r.form["id"]?.let { id -> env.store.data.settings.savedSchedules.firstOrNull { it.id == id } }
        if (r.form["action"] == "delete") {
            if (old != null) env.store.update { Schedules.delete(it, old.id) }
            return redirect(r.ex, "$back?ok=${q("Deleted. Its syncs keep running at the same times.")}")
        }
        val days = r.rawForm.filter { it.first == "day" }.mapNotNull { it.second.toIntOrNull() }
        val saved = runCatching { saveSchedule(old, r.form["name"].orEmpty(), r.form["every"]?.toIntOrNull(), r.form["at"]?.takeIf { it.isNotBlank() }, days) }
            .getOrElse { return redirect(r.ex, "$back?err=${q(it.message ?: "Error")}") }
        val n = Schedules.usedBy(env.store.data, saved.id).size
        return redirect(r.ex, "$back?ok=${q("Saved: ${Schedules.title(saved)}." + if (old != null && n > 0) " Its $n ${if (n == 1) "sync follows" else "syncs follow"} it." else "")}")
    }
    val d = env.store.data
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">A timing kept once and used by any number of syncs: change it here and every sync that uses it follows. Pick one on a sync's page, or for several syncs at once from Select on the home page.</p>""")
    val editing = r.query["edit"]?.let { id -> d.settings.savedSchedules.firstOrNull { it.id == id } }
    for (s in d.settings.savedSchedules) {
        val used = Schedules.usedBy(d, s.id)
        body.append("""<div class="card"><div class="row"><span><b>${h(Schedules.title(s))}</b><br><span class="muted">${h(Schedules.describe(s))} · ${if (used.isEmpty()) "no sync uses it" else "${used.size} ${if (used.size == 1) "sync" else "syncs"}: ${h(used.joinToString(", ") { it.name })}"}</span></span>
<div class="actions"><a class="button secondary" href="$back?edit=${q(s.id)}#form">Edit</a> ${Html.postButton(back, "Delete", "danger", mapOf("action" to "delete", "id" to s.id), confirm = "Delete this schedule? Its syncs keep running at the same times, as their own.")}</div></div></div>""")
    }
    if (d.settings.savedSchedules.isEmpty()) body.append("""<p class="muted">No saved schedule yet.</p>""")
    val probe = editing?.let { Schedules.probe(it) }
    body.append("""<h2 id="form">${if (editing != null) "Edit ${h(Schedules.title(editing))}" else "New schedule"}</h2><form method="post" action="$back" class="card">${editing?.let { """<input type="hidden" name="id" value="${h(it.id)}">""" } ?: ""}
<label for="sname">Name</label><input type="text" id="sname" name="name" maxlength="${Schedules.MAX_NAME}" value="${h(editing?.name.orEmpty())}" placeholder="Mornings">
${timingFields(probe).replace("""<option value="0"""", """<option disabled value="0"""")}
<p><button>${if (editing != null) "Save" else "Save the schedule"}</button>${if (editing != null) """ <a href="$back">Cancel</a>""" else ""}</p></form>""")
    send(r.ex, 200, page("Schedules", body.toString()))
}

/** On a sync's form: the saved schedule it follows, or its own timing ("by hand"). Nothing when no schedule is saved. */
internal fun Web.savedScheduleField(job: SyncJob?): String {
    val all = env.store.data.settings.savedSchedules
    if (all.isEmpty() || job?.radio != null) return ""
    val opts = all.joinToString("") { s -> """<option value="${h(s.id)}"${if (job?.scheduleId == s.id) " selected" else ""}>${h(Schedules.title(s))} (${h(Schedules.describe(s))})</option>""" }
    return """<label for="scheduleId">Saved schedule</label><select id="scheduleId" name="scheduleId"><option value="">None: the timing below</option>$opts</select><p class="muted">With one picked, the sync follows it (<a href="/settings/schedules">Schedules</a>) and the timing below is not used.</p>"""
}

/** [job] linked to the saved schedule its form picked, or set free of it ("None"). Unchanged when the form had no such field. */
internal fun Web.withSavedSchedule(job: SyncJob, form: Map<String, String>): SyncJob {
    if (!form.containsKey("scheduleId") || job.radio != null) return job
    val s = form["scheduleId"]?.takeIf { it.isNotEmpty() }?.let { id -> env.store.data.settings.savedSchedules.firstOrNull { it.id == id } }
    return if (s != null) Schedules.link(job, s) else Schedules.detach(job)
}

// ------------------------------------------------------------------ several syncs at once

/** The bar's "Edit…": a page with what can be changed for the ticked syncs, each "leave as it is" at first. */
internal fun Web.bulkEditPage(r: Web.Req, ticked: List<SyncJob>) {
    val d = env.store.data
    val jobs = BulkEdit.selected(d, ticked.map { it.id }.toSet())
    if (jobs.isEmpty()) return redirect(r.ex, "/?select=1&err=${q("These syncs are changed from their shared playlist.")}")
    val n = if (jobs.size == 1) "1 sync" else "${jobs.size} syncs"
    val hidden = jobs.joinToString("") { """<input type="hidden" name="job" value="${h(it.id)}">""" }
    val common = BulkEdit.common(jobs) { Timing.describe(it) }
    val schedules = d.settings.savedSchedules.joinToString("") { s -> """<option value="saved:${h(s.id)}">${h(Schedules.title(s))} (${h(Schedules.describe(s))})</option>""" }
    val steps = Timing.STEPS.joinToString("") { m -> """<option value="every:$m">${h(Timing.stepName(m))}</option>""" }
    val labels = d.settings.labels
    val colours = Html.JOB_COLORS.keys.joinToString("") { k -> """<option value="$k">${h(labels.firstOrNull { it.id == k }?.name?.takeIf { it.isNotEmpty() } ?: k)}</option>""" } +
        labels.filter { !Labels.isBase(it.id) }.joinToString("") { l -> """<option value="${h(l.id)}">${h(l.name)}</option>""" }
    val removable = jobs.count { BulkEdit.takesRemovals(it) }
    send(r.ex, 200, page("Edit $n", """<form method="post" action="/syncs/bulk" class="card">$hidden<input type="hidden" name="action" value="edit-apply">
<p class="muted">${h(jobs.joinToString(", ") { it.name })}</p>
<label for="when">Schedule</label><select id="when" name="when"><option value="">Leave as they are${common?.let { " (${h(it)})" } ?: " (they differ)"}</option>$schedules$steps</select>
<label for="at">At (with a timing picked here: daily or longer)</label><input type="time" id="at" name="at" value="">
<label for="pick">Colour or label</label><select id="pick" name="pick"><option value="keep">Leave as they are</option><option value="none">None</option>$colours</select>
${if (removable > 0) """<label for="changes">When the source changes</label><select id="changes" name="changes"><option value="">Leave as they are</option><option value="add">Only add new tracks</option><option value="mirror">Copy the latest version (removals too)</option></select><p class="muted">For $removable of them: two-way syncs and archives keep their own.</p>""" else ""}
<div class="actions"><button>Apply to $n</button> <a class="button secondary" href="/?select=1">Cancel</a></div></form>"""))
}

/** The edit page's form, applied. */
internal fun Web.bulkEditApply(r: Web.Req, ticked: List<SyncJob>) {
    val ids = ticked.map { it.id }.toSet()
    val change = runCatching { bulkChange(r.form["when"].orEmpty(), r.form["at"]?.takeIf { it.isNotBlank() }) }.getOrElse { return redirect(r.ex, "/?select=1&err=${q(it.message ?: "Error")}") }
    val pick = r.form["pick"].orEmpty().let { if (it == "keep" || it.isEmpty()) null else it }
    val n = applyBulk(ids, change, pick, r.form["changes"].orEmpty().ifEmpty { null })
    redirect(r.ex, "/?ok=${q("$n ${if (n == 1) "sync" else "syncs"} changed")}")
}

/** "saved:<id>" or "every:<minutes>" (and a time), as the edit page and the API name them. */
private fun Web.bulkChange(whenValue: String, at: String?): BulkEdit.ScheduleChange? = when {
    whenValue.startsWith("saved:") -> {
        val s = env.store.data.settings.savedSchedules.firstOrNull { it.id == whenValue.removePrefix("saved:") } ?: throw IllegalArgumentException("No such saved schedule.")
        BulkEdit.ScheduleChange(scheduleId = s.id, at = at)
    }
    whenValue.startsWith("every:") -> BulkEdit.ScheduleChange(every = whenValue.removePrefix("every:").toIntOrNull()?.takeIf { it in Timing.STEPS } ?: throw IllegalArgumentException("Pick how often."), at = at)
    at != null -> BulkEdit.ScheduleChange(at = at)
    else -> null
}

/** Applies a schedule change, a colour or label ("none" takes it off) and a removals choice to [ids] in one change; how many syncs it reached. */
private fun Web.applyBulk(ids: Set<String>, change: BulkEdit.ScheduleChange?, pick: String?, changes: String?): Int {
    if (changes != null && changes !in setOf("add", "mirror")) throw IllegalArgumentException("\"changes\" is add or mirror.")
    if (pick != null && pick != "none" && Labels.find(env.store.data.settings.labels, pick) == null && pick !in Html.JOB_COLORS) throw IllegalArgumentException("No label or colour called \"$pick\": see GET /api/v1/labels.")
    var reached = 0
    env.store.update { d0 ->
        var d = d0
        reached = BulkEdit.selected(d, ids).size
        if (change != null) d = BulkEdit.schedule(d, ids, change, java.time.LocalDate.now().dayOfWeek.value)
        if (pick != null) d = BulkEdit.color(d, ids, if (pick == "none") null else pick)
        if (changes != null) d = BulkEdit.removals(d, ids, changes == "mirror")
        d
    }
    return reached
}

/** POST /syncs/bulk: several syncs changed at once, as the page's Edit. */
internal fun Web.apiBulkEdit(b: JsonObject, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    val ids = (b["syncs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
        ?: return error(400, "\"syncs\" is the list of the syncs' ids.")
    val known = ids.filter { env.store.job(it) != null }.toSet()
    if (known.isEmpty()) return error(404, "None of those syncs is here.")
    val change = runCatching {
        val at = b.text("at")
        when {
            b.text("schedule") != null -> "saved:" + (findSchedule(b.text("schedule")!!)?.id ?: throw IllegalArgumentException("No saved schedule called \"${b.text("schedule")}\": see GET /api/v1/schedules."))
            b.number("every") != null -> "every:${b.number("every")}"
            else -> ""
        }.let { bulkChange(it, at) }
    }.getOrElse { return error(400, it.message ?: "Wrong request.") }
    val pick = if (b.containsKey("label")) b.text("label") ?: "none" else null
    val n = runCatching { applyBulk(known, change, pick, b.text("changes")) }.getOrElse { return error(400, it.message ?: "Wrong request.") }
    b.flag("enabled")?.let { on -> known.forEach { id -> env.store.job(id)?.let { enablePair(it, on) } } }
    json(200, buildJsonObject {
        put("changed", n)
        put("syncs", JsonArray(known.mapNotNull { env.store.job(it) }.map { Api.job(env, it, runner.isRunning(it.id)) }))
    })
}

/** The schedule calls; false for any other. */
internal fun Web.apiSchedules(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest == listOf("syncs", "bulk") && method == "POST") {
        val b = body() ?: return true.also { error(400, "Send JSON.") }
        apiBulkEdit(b, json, error)
        return true
    }
    if (rest.firstOrNull() != "schedules" || rest.size > 2) return false
    fun days(b: JsonObject) = (b["days"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("\"days\" are numbers from 1 (Monday) to 7 (Sunday).") }
    when {
        rest.size == 1 && method == "GET" -> json(200, JsonArray(env.store.data.settings.savedSchedules.map { scheduleJson(it) }))
        rest.size == 1 && method == "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"name\", \"every\", \"at\"?, \"days\"?}.") }
            val s = runCatching { saveSchedule(null, b.text("name").orEmpty(), b.number("every") ?: 1440, b.text("at"), days(b) ?: emptyList()) }
                .getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
            json(201, scheduleJson(s))
        }
        rest.size == 2 -> {
            val s = findSchedule(rest[1]) ?: return true.also { error(404, "No saved schedule with that id or name.") }
            when (method) {
                "GET" -> json(200, scheduleJson(s))
                "PATCH", "PUT" -> {
                    val b = body() ?: return true.also { error(400, "Send JSON.") }
                    val saved = runCatching { saveSchedule(s, b.text("name") ?: if (b.containsKey("name")) "" else null, b.number("every"), b.text("at"), days(b)) }
                        .getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
                    json(200, scheduleJson(saved))
                }
                "DELETE" -> {
                    env.store.update { Schedules.delete(it, s.id) }
                    json(200, buildJsonObject { put("deleted", s.id) })
                }
                else -> error(405, "GET, PATCH or DELETE.")
            }
        }
        else -> return false
    }
    return true
}

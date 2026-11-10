package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Timing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Many syncs reaching one service at about the same time (issue #56), as the app's Syncs screen
 * warns about it: the card on the home page, its "Got it" remembered in the page's settings until
 * the group changes, and the new times to confirm (Timing.crowded and Timing.spread, from :core).
 */
object Crowds {
    private const val SEEN = "crowdSeen"

    /** The services where many syncs at once can be turned away (MusicProvider.spreadSchedules). */
    fun sensitive(id: String): Boolean = Providers.byId(id)?.spreadSchedules == true

    /** The group as the app remembers it: the service and the syncs' ids, sorted. */
    fun key(c: Timing.Crowd) = c.service + ":" + c.jobIds.sorted().joinToString(",")

    fun find(env: ServerEnv): Timing.Crowd? = Timing.crowded(env.store.data.jobs, sensitive = ::sensitive)

    fun dismissed(env: ServerEnv, c: Timing.Crowd) = env.keyValues("ui")[SEEN] == key(c)

    fun dismiss(env: ServerEnv, c: Timing.Crowd) { env.keyValues("ui")[SEEN] = key(c) }

    /** The crowd the home page shows: none once "Got it" was said for this same group. */
    fun shown(env: ServerEnv): Timing.Crowd? = find(env)?.takeIf { !dismissed(env, it) }

    /** The new times, by sync id: only the syncs whose time changes. */
    fun plan(env: ServerEnv, c: Timing.Crowd): Map<String, String> = Timing.spread(env.store.data.jobs, c, sensitive = ::sensitive)

    /** Saves [times]; the syncs moved. The scheduler reads each sync's time as it goes. */
    fun apply(env: ServerEnv, times: Map<String, String>): List<SyncJob> =
        env.store.data.jobs.filter { it.id in times }.map { j -> j.copy(at = times.getValue(j.id)).also { env.store.upsertJob(it) } }

    fun serviceName(env: ServerEnv, c: Timing.Crowd) = Providers.byId(c.service)?.label(env) ?: c.service

    fun name(j: SyncJob) = j.name.ifBlank { j.source.playlistName }

    /** GET /api/v1/syncs/crowded. */
    fun json(env: ServerEnv): JsonObject {
        val c = find(env) ?: return buildJsonObject { put("crowded", false) }
        val jobs = env.store.data.jobs.filter { it.id in c.jobIds }
        return buildJsonObject {
            put("crowded", true)
            put("service", c.service)
            put("serviceName", serviceName(env, c))
            put("at", c.at)
            put("key", key(c))
            // "Got it" was said on the page for this same group: the page no longer shows it.
            put("dismissed", dismissed(env, c))
            put("canSpread", plan(env, c).isNotEmpty())
            put("syncs", buildJsonArray { jobs.forEach { j -> add(buildJsonObject { put("id", j.id); put("name", name(j)); put("timing", Timing.describe(j)) }) } })
        }
    }

    /** POST /api/v1/syncs/spread: the new times, saved only with [apply]. */
    fun spreadJson(env: ServerEnv, apply: Boolean): JsonObject {
        val c = find(env) ?: return buildJsonObject { put("crowded", false); put("applied", false); put("changes", buildJsonArray { }) }
        val times = plan(env, c)
        val group = env.store.data.jobs.filter { it.id in c.jobIds }
        if (apply && times.isNotEmpty()) {
            apply(env, times)
            env.log("syncs", "spread ${times.size} syncs with ${c.service} through the API")
        }
        return buildJsonObject {
            put("crowded", true)
            put("service", c.service)
            put("serviceName", serviceName(env, c))
            put("at", c.at)
            put("applied", apply && times.isNotEmpty())
            put("changes", buildJsonArray { group.filter { it.id in times }.forEach { j -> add(buildJsonObject { put("id", j.id); put("name", name(j)); put("from", j.at.orEmpty()); put("to", times.getValue(j.id)) }) } })
            // Those every few hours keep their rhythm.
            put("stays", buildJsonArray { group.filter { it.id !in times }.forEach { j -> add(buildJsonObject { put("id", j.id); put("name", name(j)); put("timing", Timing.describe(j)) }) } })
        }
    }
}

/** The card on the home page, above the syncs; empty when no crowd is to be told. */
internal fun Web.crowdCard(): String {
    val c = Crowds.shown(env) ?: return ""
    val canSpread = Crowds.plan(env, c).isNotEmpty()
    return """<div class="card crowd"><b>Syncs with ${h(Crowds.serviceName(env, c))} all start around ${h(c.at)}</b>""" +
        """<p class="muted">When many syncs reach the same service at the same moment, the service can take it for unusual traffic and turn some of them away for a while, so a few syncs fail. Spread them over the day, even a few minutes or an hour apart: each sync's time is on its page.</p>""" +
        """<div class="actions">${Html.postButton("/syncs/crowd", "Got it", "secondary", mapOf("key" to Crowds.key(c)))} """ +
        """<a class="button secondary" href="/?ids=${q(c.jobIds.joinToString(","))}">Show them</a>""" +
        (if (canSpread) """ <a class="button" href="/syncs/spread">Spread them</a>""" else "") + "</div></div>"
}

/** /syncs/crowd (POST: "Got it") and /syncs/spread (GET: the new times to confirm; POST: saved). */
internal fun Web.crowdPages(r: Web.Req, what: String) {
    val c = Crowds.find(env)
    if (what == "crowd") {
        if (r.method != "POST") return redirect(r.ex, "/")
        if (c != null && r.form["key"] == Crowds.key(c)) Crowds.dismiss(env, c)
        return redirect(r.ex, "/")
    }
    val times = c?.let { Crowds.plan(env, it) }.orEmpty()
    if (c == null || times.isEmpty()) return send(r.ex, 200, page("Spread the syncs", """<p class="muted">No syncs to spread now.</p><p><a href="/">Back</a></p>"""))
    if (r.method == "POST") {
        // Confirmed for the group the page showed: if the syncs changed meanwhile, they are shown again.
        if (r.form["key"] != Crowds.key(c)) return redirect(r.ex, "/syncs/spread")
        val moved = Crowds.apply(env, times)
        env.log("syncs", "spread ${moved.size} syncs with ${c.service}")
        return redirect(r.ex, "/?ok=${q("New times saved for ${moved.size} ${if (moved.size == 1) "sync" else "syncs"}.")}")
    }
    val rows = env.store.data.jobs.filter { it.id in c.jobIds }.joinToString("") { j ->
        val new = times[j.id]
        val right = if (new == null) """<span class="muted">${h(if (Timing.timed(j)) j.at.orEmpty() else Timing.describe(j))} (stays)</span>"""
            else """<span><s class="muted">${h(j.at.orEmpty())}</s> → <b>${h(new)}</b></span>"""
        """<div class="row"><span>${h(Crowds.name(j))}</span>$right</div>"""
    }
    send(r.ex, 200, page("Spread the syncs", """<div class="card"><h3>Spread the syncs with ${h(Crowds.serviceName(env, c))}?</h3>
<p class="muted">Each sync starts a little after the one before. Days, frequency and everything else stay the same.</p>$rows
<div class="actions">${Html.postButton("/syncs/spread", "Confirm", fields = mapOf("key" to Crowds.key(c)))} <a class="button secondary" href="/">Cancel</a></div></div>"""))
}

package com.xlollx.songport.server

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.ToFix
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * To fix (the app's Saved matches › To fix, [ToFix]): every song the last run of every sync left
 * not found or matched loosely, once per pair of services. A choice holds for every sync between
 * the two services and is written to their playlists at once ([SyncEngine.fixEverywhere]); Later
 * sets a song aside until its sync's next run. Several at once: the boxes and the bar's buttons
 * (plain forms, no JavaScript).
 */
internal object ToFixActions {
    /** Ids for the actions the page, the API and the command line take. */
    val ACTIONS = setOf("use", "ignore", "later", "back", "pick")

    data class Done(val done: Int, val syncs: Int, val failed: List<String>, val left: Int)
}

/** The service a row is missing on: the destination of the first sync that lists it. */
private fun Web.targetOf(item: ToFix.Item): MusicProvider? = item.places.firstNotNullOfOrNull { p -> env.store.job(p.jobId)?.let { Providers.byId(it.target.provider) } }

private fun Web.familyName(family: String): String =
    Providers.connectors().firstOrNull { FileStore.family(it.id) == family }?.label(env) ?: Providers.byId(family)?.label(env) ?: family

/**
 * Applies [action] to the rows named by [keys] (null: every row): use (the suggestion), ignore,
 * later, back (a row set aside returns), pick ([choice], the song to use instead, for one row).
 */
internal fun Web.toFixApply(action: String, keys: Set<String>?, choice: JsonElement? = null): ToFixActions.Done {
    val all = ToFix.items(env.store.data, withLater = action == "back")
    val rows = if (keys == null) all else all.filter { it.key in keys }
    if (rows.isEmpty()) throw IllegalArgumentException("Name rows of the list (their keys, from GET /tofix).")
    val engine = SyncEngine(env)
    var syncs = 0
    val failed = ArrayList<String>()
    var done = 0
    runBlocking {
        when (action) {
            "use" -> rows.filter { it.suggestion != null }.ifEmpty { throw IllegalArgumentException("None of them has a suggestion: pick a song, or ignore them.") }.forEach { item ->
                val o = engine.fixEverywhere(item, item.suggestion!!); syncs += o.syncs.size; failed += o.failed; done++
            }
            "ignore" -> rows.forEach { item -> val o = engine.ignoreEverywhere(item); syncs += o.syncs.size; failed += o.failed; done++ }
            "later" -> rows.forEach { ToFix.later(env.store, it); done++ }
            "back" -> rows.forEach { ToFix.undoLater(env.store, it); done++ }
            "pick" -> {
                val item = rows.singleOrNull() ?: throw IllegalArgumentException("Name one row to pick a song for.")
                val asked = choice?.let { ApiPlaylists.asWanted(it) } ?: throw IllegalArgumentException("Say the song to use: a link, \"Artist - Title\", or its id on the service.")
                val dst = targetOf(item) ?: throw IllegalArgumentException("The sync's destination is not on this server.")
                val chosen: Track = ApiPlaylists.find(env, dst, asked) ?: throw IllegalArgumentException("${dst.label(env)} has nothing for ${ApiPlaylists.describe(asked)}.")
                val o = engine.fixEverywhere(item, chosen); syncs += o.syncs.size; failed += o.failed; done++
            }
            else -> throw IllegalArgumentException("\"action\" is one of ${ToFixActions.ACTIONS.joinToString(", ")}.")
        }
    }
    env.store.flush()
    return ToFixActions.Done(done, syncs, failed.distinct(), ToFix.count(env.store.data))
}

internal fun Web.toFixJson(by: String?): JsonObject {
    val d = env.store.data
    val items = ToFix.items(d)
    val later = ToFix.items(d, withLater = true).filter { a -> items.none { it.key == a.key } }
    fun item(i: ToFix.Item) = buildJsonObject {
        put("key", i.key)
        put("kind", if (i.kind == ToFix.Kind.NOT_FOUND) "not_found" else "to_check")
        put("track", trackRef(i.track))
        put("suggestion", i.suggestion?.let { trackRef(it) } ?: JsonNull)
        put("score", if (i.suggestion != null) JsonPrimitive(Math.round(i.score * 100)) else JsonNull)
        put("absent", i.absent)
        put("on", familyName(i.targetService))
        put("syncs", buildJsonArray { i.places.forEach { p -> add(buildJsonObject { put("id", p.jobId); put("name", p.jobName); put("run", p.reportId) }) } })
    }
    val groups = if (by == "service") ToFix.byService(items) { familyName(it) } else ToFix.bySync(items)
    return buildJsonObject {
        put("count", items.size)
        put("groups", buildJsonArray { groups.forEach { g -> add(buildJsonObject { put("id", g.id); put("name", g.name); put("items", JsonArray(g.items.map(::item))) }) } })
        put("later", JsonArray(later.map(::item)))
    }
}

internal fun Web.apiToFix(method: String, rest: List<String>, query: Map<String, String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tofix")) return false
    when (method) {
        "GET" -> {
            if (query["by"] != null && query["by"] !in setOf("sync", "service")) return true.also { error(400, "by= is sync or service.") }
            json(200, toFixJson(query["by"]))
        }
        "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"action\": \"use\", \"keys\": [\"<key>\"]}.") }
            val action = b.text("action") ?: return true.also { error(400, "\"action\" is one of ${ToFixActions.ACTIONS.joinToString(", ")}.") }
            val keys: Set<String>? = when (val k = b["keys"]) {
                is JsonArray -> k.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                is JsonPrimitive -> if (k.contentOrNull == "all") null else setOfNotNull(k.contentOrNull)
                else -> return true.also { error(400, "\"keys\" names the rows (from GET /tofix), or \"all\".") }
            }
            val done = runCatching { toFixApply(action, keys, b["choice"]) }.getOrElse { e ->
                return true.also { if (e is IllegalArgumentException) error(400, e.message ?: "Wrong request.") else error(502, e.message ?: e.javaClass.simpleName) }
            }
            json(200, buildJsonObject {
                put("done", done.done); put("syncs", done.syncs); put("left", done.left)
                put("failed", buildJsonArray { done.failed.forEach { add(it) } })
            })
        }
        else -> error(405, "GET or POST.")
    }
    return true
}

// ------------------------------------------------------------------ the page

private const val FORM = "tofix"

internal fun Web.toFixPage(r: Web.Req) {
    if (r.method == "POST") {
        val action = r.form["action"].orEmpty()
        val keys = r.rawForm.filter { it.first == "key" }.map { it.second }.toSet()
        val back = "/tofix" + (r.form["by"]?.takeIf { it == "service" }?.let { "?by=service" } ?: "")
        val sep = if ('?' in back) "&" else "?"
        if (keys.isEmpty()) return redirect(r.ex, "$back${sep}err=${q("Tick at least one song.")}")
        val choice = r.form["choice"]?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
        val done = runCatching { toFixApply(action, keys, choice) }.getOrElse { return redirect(r.ex, "$back${sep}err=${q(it.message ?: "Error")}") }
        val what = when (action) {
            "use", "pick" -> "${done.done} fixed" + if (done.syncs > 0) " in ${done.syncs} ${if (done.syncs == 1) "playlist" else "playlists"}" else ""
            "ignore" -> "${done.done} ignored: not searched for again"
            "later" -> "${done.done} set aside until their sync runs again"
            else -> "${done.done} back in the list"
        }
        val failed = if (done.failed.isEmpty()) "" else " Could not be written now: ${done.failed.joinToString(", ")} (the next run does it)."
        return redirect(r.ex, "$back${sep}ok=${q("$what.$failed")}")
    }
    val byService = r.query["by"] == "service"
    val d = env.store.data
    val items = ToFix.items(d)
    val later = ToFix.items(d, withLater = true).filter { a -> items.none { it.key == a.key } }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The songs the last run of your syncs could not find, or matched loosely. A choice here holds for every sync between the same two services, and goes into their playlists now. <a href="/tools/matches">Saved matches</a></p>""")
    if (items.isEmpty() && later.isEmpty()) {
        body.append("""<div class="card ok">Nothing to fix: every song of your syncs was found.</div>""")
        return send(r.ex, 200, page("To fix", body.toString()))
    }
    fun chip(text: String, on: Boolean, href: String) = """<a class="chip${if (on) " on" else ""}" href="$href"${if (on) " aria-current=\"true\"" else ""}>$text</a>"""
    body.append("""<p class="chips">${chip("By sync", !byService, "/tofix")}${chip("By service", byService, "/tofix?by=service")}</p>""")
    val by = if (byService) """<input type="hidden" name="by" value="service">""" else ""
    if (items.isNotEmpty()) {
        body.append("""<form id="$FORM" method="post" action="/tofix" class="card">$by<p class="muted">Ticked songs:</p><div class="actions"><button name="action" value="use">Use the suggestions</button> <button class="secondary" name="action" value="later">Later</button> <button class="danger" name="action" value="ignore">Ignore</button></div></form>""")
    }
    val groups = if (byService) ToFix.byService(items) { familyName(it) } else ToFix.bySync(items)
    for (g in groups) {
        val sub = if (byService) "Missing on ${h(g.name)}" else env.store.job(g.id)?.let { j -> "${h(label(j.source.provider))} → ${h(label(j.target.provider))}" }.orEmpty()
        body.append("""<h2>${h(if (byService) g.name else g.name)} <span class="muted">${if (byService) "" else sub}</span></h2>""")
        for (i in g.items) body.append(toFixRow(i, by))
    }
    if (later.isNotEmpty()) {
        body.append("""<h2>Later (${later.size})</h2><p class="muted">Set aside until their sync runs again.</p>""")
        for (i in later) body.append("""<div class="card"><div class="row"><span><b>${h(i.track.artistLine)} – ${h(i.track.title)}</b><br><span class="muted">${h(i.places.joinToString(", ") { it.jobName })}</span></span>${Html.postButton("/tofix", "Back to the list", "secondary", mapOf("action" to "back", "key" to i.key))}</div></div>""")
    }
    send(r.ex, 200, page("To fix", body.toString()))
}

private fun Web.toFixRow(i: ToFix.Item, by: String): String {
    val what = when {
        i.suggestion != null -> """Suggested: ${h(i.suggestion!!.artistLine)} – ${h(i.suggestion!!.title)}${i.suggestion!!.album.takeIf { it.isNotBlank() }?.let { " <span class=\"muted\">(${h(it)})</span>" } ?: ""} · ${Math.round(i.score * 100)}%"""
        i.absent -> "Not found: ${h(familyName(i.targetService))} returned nothing"
        else -> "Not found on ${h(familyName(i.targetService))}"
    }
    val syncs = i.places.joinToString(", ") { it.jobName }
    val key = mapOf("key" to i.key) + (if (by.isNotEmpty()) mapOf("by" to "service") else emptyMap())
    val use = if (i.suggestion != null) Html.postButton("/tofix", "Use", "", key + ("action" to "use")) + " " else ""
    return """<div class="card"><div class="row"><label class="check" style="margin:0;align-items:flex-start"><input type="checkbox" name="key" value="${h(i.key)}" form="$FORM" aria-label="Select ${h(i.track.title)}"> <span><b>${h(i.track.artistLine)} – ${h(i.track.title)}</b><br><span class="muted">$what</span><br><span class="muted">${if (i.places.size > 1) "In ${i.places.size} syncs: " else ""}${h(syncs)}</span></span></label>
<div class="actions">$use${Html.postButton("/tofix", "Later", "secondary", key + ("action" to "later"))} ${Html.postButton("/tofix", "Ignore", "danger", key + ("action" to "ignore"))}</div></div>
<details><summary>Choose another song…</summary><form method="post" action="/tofix" class="finder">$by<input type="hidden" name="action" value="pick"><input type="hidden" name="key" value="${h(i.key)}"><input type="search" name="choice" placeholder="A link from any service, or Artist - Title" aria-label="The song to use" required> <button class="secondary">Use this</button></form></details></div>"""
}

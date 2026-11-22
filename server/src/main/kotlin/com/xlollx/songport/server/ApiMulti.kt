package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayChanges
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.shared.SharedGroup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The multi syncs in the API (MultiWeb.kt): list, read, make, run, add or remove a target and
 * change its way, delete. A multi sync's syncs are in GET /syncs too; these calls change it whole,
 * with the engine's own rules ([NwayEdit]), and run it as one batch ([Runner.startGroup]). POST
 * /multi with an id is what a phone's "Hand over to the server" sends for a multi sync.
 */
internal fun Web.multiJson(group: SharedGroup): JsonObject {
    val members = multiMembers(group)
    val d = env.store.data
    val run = multiRun(group, members)
    fun member(m: Nway.Member, source: Boolean) = buildJsonObject {
        put("syncId", m.down.id)
        put("twinId", m.up?.id?.let { JsonPrimitive(it) } ?: JsonNull)
        put("service", m.playlist.provider)
        put("serviceName", memberName(m))
        put("playlistId", m.playlist.playlistId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("playlist", m.playlist.playlistName)
        if (!source) put("way", wayKey(NwayEdit.modeOf(m)))
        put("count", NwayChanges.targetSize(m.down.lastReportId?.let { id -> d.reports.firstOrNull { it.id == id } })?.let { JsonPrimitive(it) } ?: JsonNull)
        put("enabled", m.down.enabled)
        run?.services?.firstOrNull { it.member.down.id == m.down.id }?.let { s ->
            put("phase", s.phase.name.lowercase())
            s.result?.let { r -> put("result", buildJsonObject { put("ok", r.ok); put("added", r.added); put("removed", r.removed); put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull) }) }
        }
    }
    val anchor = members.firstOrNull()?.down
    val last = lastMultiRun(members)
    return buildJsonObject {
        put("id", group.id)
        put("name", group.name)
        members.firstOrNull()?.let { put("source", member(it, true)) }
        put("targets", JsonArray(members.drop(1).map { member(it, false) }))
        put("timing", anchor?.let { com.xlollx.songport.sync.Timing.describe(it) }.orEmpty())
        put("every", anchor?.let { com.xlollx.songport.sync.Timing.interval(it) } ?: 0)
        put("at", anchor?.at?.let { JsonPrimitive(it) } ?: JsonNull)
        put("days", buildJsonArray { anchor?.days?.forEach { add(it) } })
        put("enabled", members.flatMap { listOfNotNull(it.down, it.up) }.any { it.enabled })
        put("running", run != null)
        put("nextRun", anchor?.let { Runner.nextRun(it) }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("syncs", buildJsonArray { Nway.order(env, group, manual = true).forEach { add(it.id) } })
        // The last run, its syncs added up: added and removed on the targets' playlists only (NwayHistory).
        put("lastResult", last?.let { r ->
            buildJsonObject {
                put("started", r.startedEpoch); put("ok", r.ok); put("added", r.added); put("removed", r.removed)
                put("notFound", r.unmatched.size); put("sourceCount", r.sourceCount); put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        } ?: JsonNull)
    }
}

/** A side of POST /multi: {"service", "playlist"?, "way"?}, its playlist checked against what the service lists (null: a new one). */
private fun Web.itemOf(o: JsonObject, asSource: Boolean): NwayEdit.Item {
    val p = o.text("service")?.let { Providers.byId(it) } ?: throw IllegalArgumentException("No service with that id: see GET /api/v1/services.")
    if (!usable(p)) throw IllegalStateException("${p.label(env)} is not connected.")
    val id = o.text("playlist")
    if (asSource && id == null) throw IllegalArgumentException("\"source.playlist\" is needed: an id from GET /api/v1/services/${p.id}/playlists.")
    val name = id?.let { pid ->
        runBlocking { (if (asSource) p.libraryEntries(env, false) + p.playlists(env) else targetLists(p)).firstOrNull { it.id == pid }?.name }
            ?: throw IllegalArgumentException("${p.label(env)} has no playlist${if (asSource) "" else " of yours"} with the id $pid.")
    }.orEmpty()
    val way = if (asSource) NwayEdit.Mode.TWO_WAY else o.text("way")?.let { wayOf(it) ?: throw IllegalArgumentException("\"way\" is exact, keep or two_way.") } ?: NwayEdit.Mode.KEEP
    return NwayEdit.Item(p.id, id, name, mode = way)
}

internal fun Web.apiMulti(ex: HttpExchange, method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "multi") return false
    fun failed(e: Throwable) = when (e) {
        is NoSuchElementException -> error(404, e.message ?: "Not found.")
        is IllegalArgumentException -> error(400, e.message ?: "Wrong request.")
        is IllegalStateException -> error(409, e.message ?: "Not now.")
        else -> error(502, e.message ?: e.javaClass.simpleName)
    }
    if (rest.size == 1) {
        when (method) {
            "GET" -> json(200, JsonArray(Nway.groups(env).map { multiJson(it) }))
            "POST" -> {
                val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"source\": {\"service\", \"playlist\"}, \"targets\": [{\"service\", \"playlist\"?, \"way\"?}], \"name\"?}.") }
                val given = b.text("id")
                // Asked again (a hand-over whose answer was lost): the one made then, as it is.
                given?.let { id -> multiGroup(id)?.let { return true.also { _ -> json(200, multiJson(it)) } } }
                val spec = runCatching {
                    val s = b["source"] as? JsonObject ?: throw IllegalArgumentException("\"source\" is needed: {\"service\": \"spotify\", \"playlist\": \"<id>\"}.")
                    val targets = (b["targets"] as? JsonArray ?: throw IllegalArgumentException("\"targets\" is needed: [{\"service\": \"deezer\", \"playlist\"?: \"<id>\", \"way\"?: \"keep\"}].")).map {
                        itemOf(it as? JsonObject ?: throw IllegalArgumentException("Each target is {\"service\", \"playlist\"?, \"way\"?}."), false)
                    }
                    val source = itemOf(s, true)
                    MultiSpec(b.text("name") ?: source.name, source, targets, b.number("every") ?: 1440, b.text("at"),
                        (b["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { d -> d in 1..7 } }.orEmpty(),
                        b.flag("enabled") ?: true, given)
                }.getOrElse { return true.also { _ -> failed(it) } }
                val group = runCatching { createMulti(spec) }.getOrElse { return true.also { _ -> failed(it) } }
                env.log("api", "created multi sync group=${group.id}")
                if (b.flag("run") == true) runner.startGroup(group)
                json(201, multiJson(group))
            }
            else -> error(405, "GET or POST.")
        }
        return true
    }
    val group = multiGroup(rest[1]) ?: return true.also { error(404, "No multi sync with that id.") }
    when {
        rest.size == 2 && method == "GET" -> json(200, multiJson(group))
        rest.size == 2 && method == "DELETE" -> {
            if (runner.isGroupRunning(group.id)) return true.also { error(409, "It is running: try again once the run has finished.") }
            runCatching { Nway.delete(env, group) }.getOrElse { return true.also { _ -> failed(it) } }
            env.log("api", "deleted multi sync group=${group.id}")
            json(200, buildJsonObject { put("deleted", group.id) })
        }
        rest.size == 3 && rest[2] == "run" && method == "POST" -> {
            if (!runner.startGroup(group)) return true.also { error(409, "${group.name} is running already.") }
            json(202, buildJsonObject { put("started", group.id); put("syncs", buildJsonArray { Nway.order(env, group, manual = true).forEach { add(it.id) } }) })
        }
        rest.size == 3 && rest[2] == "targets" && method == "POST" -> {
            val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"service\", \"playlist\"?, \"way\"?}.") }
            val way = b.text("way")?.let { wayOf(it) ?: return true.also { _ -> error(400, "\"way\" is exact, keep or two_way.") } } ?: NwayEdit.Mode.KEEP
            val service = b.text("service") ?: return true.also { error(400, "\"service\" is needed: see GET /api/v1/services.") }
            runCatching { addTarget(group, service, b.text("playlist"), way) }.getOrElse { return true.also { _ -> failed(it) } }
            json(201, multiJson(multiGroup(group.id) ?: group))
        }
        rest.size == 4 && rest[2] == "targets" && (method == "DELETE" || method == "PATCH") -> {
            val member = rest[3]
            if (method == "DELETE") runCatching { removeTarget(group, member) }.getOrElse { return true.also { _ -> failed(it) } }
            else {
                val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"way\": \"exact\" | \"keep\" | \"two_way\"} or {\"source\": true}.") }
                runCatching {
                    if (b.flag("source") == true) setSource(group, member)
                    else setWay(group, member, wayOf(b.text("way")) ?: throw IllegalArgumentException("\"way\" is exact, keep or two_way."))
                }.getOrElse { return true.also { _ -> failed(it) } }
            }
            json(200, multiJson(multiGroup(group.id) ?: group))
        }
        else -> error(404, "Unknown call. See GET /api/v1/openapi.json")
    }
    return true
}

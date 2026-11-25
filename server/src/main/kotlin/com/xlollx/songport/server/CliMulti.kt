package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * `trackhop multi`: the multi syncs (one source playlist kept on several targets, ApiMulti.kt), as
 * the other commands ([CliCommands]): calls to the API, plain text for people, the API's JSON with --json.
 */
internal class CliMulti(private val c: CliCommands) {
    companion object {
        val command = Cli.Cmd("multi", "Syncs", listOf(
            "multi [list]",
            "multi show <multi>",
            "multi run <multi> [--wait]",
            "multi add-target <multi> <service> [<playlist>] [--way exact|keep|two_way]",
            "multi remove-target <multi> <target>",
            "multi way <multi> <target> exact|keep|two_way",
        ), "multi syncs: one source playlist kept on several targets",
            "A <multi> is its name or id; a <target> its service (or the id of its sync, from show).\n" +
                "add-target without <playlist> makes a new playlist named after the multi sync; --way: exact (an exact copy of the source),\n" +
                "keep (keeps what you add there, the default) or two_way (its changes reach the source and the others).\n" +
                "run runs its syncs one after the other, as one run (--wait: until it ends, with the result).",
            listOf("list", "show", "run", "add-target", "remove-target", "way"))

        private val pretty = Json { prettyPrint = true }
    }

    private val api get() = c.api
    private val out get() = c.out
    private fun print(e: JsonElement) = out.println(pretty.encodeToString(JsonElement.serializer(), e))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str().orEmpty()
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { it.jsonObject }

    private fun all() = api.get("multi").jsonArray.map { it.jsonObject }

    /** The multi sync named [ask]: its id, its exact name, or the only one whose name starts with or holds it. */
    private fun multi(ask: String): JsonObject {
        val list = all()
        list.firstOrNull { it.s("id") == ask }?.let { return it }
        val found = list.filter { it.s("name").equals(ask, ignoreCase = true) }
            .ifEmpty { list.filter { it.s("name").startsWith(ask, ignoreCase = true) } }
            .ifEmpty { list.filter { it.s("name").contains(ask, ignoreCase = true) } }
        return when (found.size) {
            1 -> found[0]
            0 -> throw Cli.Failure(1, "no multi sync called \"$ask\". See: trackhop multi")
            else -> throw Cli.Failure(1, "\"$ask\" could be ${found.joinToString(", ") { "\"${it.s("name")}\" (${it.s("id")})" }}: say which, by its name or id")
        }
    }

    /** The target named [ask] of [m]: the id of its sync, its service's id or name. */
    private fun target(m: JsonObject, ask: String): JsonObject {
        val targets = m.list("targets")
        return targets.firstOrNull { it.s("syncId") == ask }
            ?: targets.filter { it.s("service").equals(ask, ignoreCase = true) || it.s("serviceName").equals(ask, ignoreCase = true) }.let {
                if (it.size > 1) throw Cli.Failure(1, "more than one target is on \"$ask\": use its id (trackhop multi show \"${m.s("name")}\")") else it.firstOrNull()
            }
            ?: throw Cli.Failure(1, "no target \"$ask\" in ${m.s("name")}. See: trackhop multi show \"${m.s("name")}\"")
    }

    private fun way(w: String?): String {
        val k = w?.trim()?.lowercase()?.replace('-', '_')
        if (k !in setOf("exact", "keep", "two_way")) throw Cli.Failure(2, "a way is exact, keep or two_way")
        return k!!
    }

    private fun result(r: JsonObject?): String = when {
        r == null -> "no runs yet"
        !r.b("ok") -> "failed: ${r.s("error")}"
        else -> "+${r.n("added")} -${r.n("removed")}" + (r.n("notFound").takeIf { it > 0 }?.let { ", $it not found" } ?: "") + ", ${r.n("sourceCount")} in source"
    }

    private fun describe(m: JsonObject, full: Boolean) {
        val src = m["source"] as? JsonObject
        out.println("${m.s("name")}  [multi sync${if (m.b("running")) ", running" else if (!m.b("enabled")) ", paused" else ", " + m.s("timing")}]${if (full) "  (${m.s("id")})" else ""}")
        out.println("  ${src?.let { "${it.s("serviceName")}: ${it.s("playlist")}" }.orEmpty()} -> ${m.list("targets").joinToString(", ") { it.s("serviceName") }}")
        out.println("  last: ${result(m["lastResult"] as? JsonObject)}")
        if (!full) return
        for (t in m.list("targets")) {
            val count = (t["count"] as? JsonPrimitive)?.intOrNull?.let { ", $it songs" }.orEmpty()
            val phase = t.s("phase").takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
            out.println("  ${t.s("serviceName")}: ${t.s("playlist")}  [${t.s("way")}$count$phase]  ${t.s("syncId")}")
        }
    }

    fun run(p: List<String>, o: Cli.Opts): Int {
        val sub = p.firstOrNull() ?: "list"
        fun need(n: Int, form: String) { if (p.size < n) throw Cli.Failure(2, "usage: trackhop multi $form") }
        when (sub) {
            "list", "ls" -> {
                val list = all()
                if (c.asJson) return print(JsonArray(list)).let { 0 }
                if (list.isEmpty()) out.println("No multi syncs yet: make one on Trackhop's page (New sync, Several targets).")
                list.forEach { describe(it, false) }
            }
            "show" -> {
                need(2, "show <multi>")
                val m = multi(p.drop(1).joinToString(" "))
                if (c.asJson) print(m) else describe(m, true)
            }
            "run" -> {
                need(2, "run <multi> [--wait]")
                val m = multi(p.drop(1).joinToString(" "))
                val before = (m["lastResult"] as? JsonObject)?.get("started")?.str()
                api.post("multi/${enc(m.s("id"))}/run")
                if (!o.has("wait")) {
                    if (c.asJson) print(buildJsonObject { put("started", m.s("id")) }) else out.println("Started: ${m.s("name")}")
                    return 0
                }
                if (!c.asJson) out.println("Running ${m.s("name")}...")
                val deadline = System.currentTimeMillis() + 6 * 3600_000L
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000)
                    val now = api.get("multi/${enc(m.s("id"))}").jsonObject
                    val last = now["lastResult"] as? JsonObject
                    if (!now.b("running") && last != null && last["started"].str() != before) {
                        if (c.asJson) print(now) else out.println("${now.s("name")}: ${result(last)}")
                        return if (last.b("ok")) 0 else 1
                    }
                }
                throw Cli.Failure(1, "still running after 6 hours: ${m.s("name")}")
            }
            "add-target" -> {
                need(3, "add-target <multi> <service> [<playlist>] [--way exact|keep|two_way]")
                val m = multi(p[1])
                val service = c.service(p[2]).s("id")
                val playlist = p.getOrNull(3)?.let { c.playlist(service, p.drop(3).joinToString(" ")).s("id") }
                val body = buildJsonObject {
                    put("service", service)
                    playlist?.let { put("playlist", it) }
                    put("way", way(o.value("way") ?: "keep"))
                }
                val r = api.post("multi/${enc(m.s("id"))}/targets", body).jsonObject
                if (c.asJson) print(r) else out.println("${r.s("name")}: target added. It gets the songs at the next run.")
            }
            "remove-target" -> {
                need(3, "remove-target <multi> <target>")
                val m = multi(p[1])
                val t = target(m, p.drop(2).joinToString(" "))
                val r = api.send("DELETE", "multi/${enc(m.s("id"))}/targets/${enc(t.s("syncId"))}").jsonObject
                if (c.asJson) print(r) else out.println("${m.s("name")}: ${t.s("serviceName")} removed. Its playlist stays as it is.")
            }
            "way" -> {
                need(4, "way <multi> <target> exact|keep|two_way")
                val m = multi(p[1])
                val t = target(m, p[2])
                val r = api.send("PATCH", "multi/${enc(m.s("id"))}/targets/${enc(t.s("syncId"))}", buildJsonObject { put("way", way(p[3])) }).jsonObject
                if (c.asJson) print(r) else out.println("${m.s("name")}: ${t.s("serviceName")} now follows as ${way(p[3])}.")
            }
            else -> throw Cli.Failure(2, "unknown: trackhop multi $sub. See: trackhop help multi")
        }
        return 0
    }
}

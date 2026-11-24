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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * The commands of the app's features brought to Desktop and Docker in 2.2, as the others
 * ([CliCommands]): calls to the API, plain text for people, the API's JSON with --json.
 */
internal class CliParity(private val c: CliCommands) {
    companion object {
        val commands: List<Cli.Cmd> = listOf(
            Cli.Cmd("unplayable", "Tools", listOf("unplayable weekly [on|off|now] [--wait]"), "the weekly look for tracks a service no longer plays",
                "On, every service that can tell them apart is asked each week; when some are found, your notification channels that\n" +
                    "take \"Tracks to review\" are told. Nothing is changed: Tools, Unplayable tracks on the page replaces them. now looks at once.",
                listOf("weekly")),
        )

        val valued: Set<String> = emptySet()

        private val pretty = Json { prettyPrint = true }
    }

    private val api get() = c.api
    private val out get() = c.out
    private fun print(e: JsonElement) = out.println(pretty.encodeToString(JsonElement.serializer(), e))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str().orEmpty()
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { it.jsonObject }
    private fun date(ms: Long) = if (ms <= 0) "never" else java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' ')

    /** The exit code, or null for a command that is not one of these. */
    fun run(command: String, p: List<String>, o: Cli.Opts): Int? = when (command) {
        "unplayable" -> unplayable(p, o)
        else -> null
    }

    /** Waits for a background task; its end in words, the exit code. */
    private fun waitTask(task: String, what: String): Int {
        if (!c.asJson) out.println("$what...")
        while (true) {
            Thread.sleep(1500)
            val t = api.get("tasks/${enc(task)}").jsonObject
            if (!t.b("done")) continue
            if (c.asJson) print(t) else out.println(t["error"].str()?.let { "Failed: $it" } ?: "Done.")
            return if (t["error"].str() == null) 0 else 1
        }
    }

    // ------------------------------------------------------------------ unplayable

    private fun unplayable(p: List<String>, o: Cli.Opts): Int {
        if (p.firstOrNull() != "weekly") throw Cli.Failure(2, "usage: trackhop unplayable weekly [on|off|now] [--wait]")
        when (p.getOrNull(1)) {
            "on", "off" -> api.send("PATCH", "tools/unplayable/weekly", buildJsonObject { put("enabled", p[1] == "on") })
            "now" -> {
                val task = api.post("tools/unplayable/weekly/run").jsonObject.s("task")
                if (o.has("wait")) return waitTask(task, "Looking for unplayable tracks")
                if (c.asJson) print(buildJsonObject { put("task", task) }) else out.println("Looking for unplayable tracks: trackhop task $task")
                return 0
            }
            null -> {}
            else -> throw Cli.Failure(2, "usage: trackhop unplayable weekly [on|off|now] [--wait]")
        }
        val r = api.get("tools/unplayable/weekly").jsonObject
        if (c.asJson) return print(r).let { 0 }
        out.println("Weekly look for unplayable tracks: ${if (r.b("enabled")) "on" else "off"}")
        out.println("Services asked: ${(r["services"] as? JsonArray).orEmpty().joinToString(", ") { it.str().orEmpty() }.ifEmpty { "none connected that can tell" }}")
        out.println("Channels told: ${r.n("channels")}${if (r.n("channels") == 0) " (none takes \"Tracks to review\": trackhop notify)" else ""}")
        (r["last"] as? JsonObject)?.let { out.println("Last look: ${date(it.l("at"))}: ${it.s("summary")}") }
        return 0
    }
}

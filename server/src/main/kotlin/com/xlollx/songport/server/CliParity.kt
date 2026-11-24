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
            Cli.Cmd("tofix", "Runs and review", listOf(
                "tofix [--by sync|service]",
                "tofix use|ignore|later <n>...|all",
                "tofix pick <n> <track>",
                "tofix back <n>...|all",
            ), "the songs your syncs could not find or matched loosely, all in one list, and what to do with them",
                "One row per song and pair of services: a choice holds for every sync between them, and goes into their playlists now.\n" +
                    "use: take the suggestion; ignore: not on that service, not searched for again; later: set aside until its sync runs again;\n" +
                    "pick: use <track> instead (a link from any service, \"Artist - Title\", or its id there); back: a song set aside returns.\n" +
                    "<n> is a row's number from the list (back: from the Later list).",
                listOf("use", "ignore", "later", "pick", "back")),
        )

        val valued: Set<String> = setOf("by")

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
        "tofix" -> toFix(p, o)
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

    // ------------------------------------------------------------------ tofix

    private fun toFix(p: List<String>, o: Cli.Opts): Int {
        val by = o.value("by")
        val r = api.get("tofix" + (by?.let { "?by=${enc(it)}" } ?: "")).jsonObject
        val rows = r.list("groups").flatMap { it.list("items") }
        val later = r.list("later")
        fun keys(list: List<JsonObject>, ask: List<String>): kotlinx.serialization.json.JsonElement =
            if (ask == listOf("all")) JsonPrimitive("all")
            else JsonArray(ask.map { a ->
                val n = a.toIntOrNull()
                JsonPrimitive(n?.let { list.getOrNull(it - 1)?.s("key") } ?: list.firstOrNull { it.s("key") == a }?.s("key") ?: throw Cli.Failure(1, "no row $a: see trackhop tofix"))
            })
        val action = p.firstOrNull()
        if (action == null) {
            if (c.asJson) return print(r).let { 0 }
            if (rows.isEmpty() && later.isEmpty()) return out.println("Nothing to fix: every song of your syncs was found.").let { 0 }
            var n = 0
            for (g in r.list("groups")) {
                out.println(g.s("name"))
                for (i in g.list("items")) {
                    n++
                    val t = i["track"] as JsonObject
                    val sug = (i["suggestion"] as? JsonObject)?.let { "suggested: ${it.s("artist")} - ${it.s("title")} (${i.n("score")}%)" } ?: "not found on ${i.s("on")}"
                    out.println("  $n. ${t.s("artist")} - ${t.s("title")}  [$sug]${i.list("syncs").size.takeIf { it > 1 }?.let { "  in $it syncs" } ?: ""}")
                }
            }
            if (later.isNotEmpty()) {
                out.println("Later (back <n> returns them):")
                later.forEachIndexed { k, i -> val t = i["track"] as JsonObject; out.println("  ${k + 1}. ${t.s("artist")} - ${t.s("title")}") }
            }
            return 0
        }
        val body = when (action) {
            "use", "ignore", "later" -> { if (p.size < 2) throw Cli.Failure(2, "usage: trackhop tofix $action <n>...|all"); buildJsonObject { put("action", action); put("keys", keys(rows, p.drop(1))) } }
            "back" -> { if (p.size < 2) throw Cli.Failure(2, "usage: trackhop tofix back <n>...|all"); buildJsonObject { put("action", "back"); put("keys", keys(later, p.drop(1))) } }
            "pick" -> { if (p.size < 3) throw Cli.Failure(2, "usage: trackhop tofix pick <n> <track>"); buildJsonObject { put("action", "pick"); put("keys", keys(rows, listOf(p[1]))); put("choice", p.drop(2).joinToString(" ")) } }
            else -> throw Cli.Failure(2, "usage: trackhop tofix [use|ignore|later|pick|back]")
        }
        val done = api.post("tofix", body).jsonObject
        if (c.asJson) return print(done).let { 0 }
        out.println("Done: ${done.n("done")}${if (done.n("syncs") > 0) ", ${done.n("syncs")} playlists written" else ""}. ${done.n("left")} left to fix.")
        (done["failed"] as? JsonArray).orEmpty().takeIf { it.isNotEmpty() }?.let { f -> out.println("Not written now (the next run does it): ${f.joinToString(", ") { it.str().orEmpty() }}") }
        return 0
    }
}

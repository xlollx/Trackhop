package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The commands of `trackhop` (Cli.kt), each a call or a few to the API: plain text for people, the
 * API's own JSON with --json (and for the MCP tools, Mcp.kt, which run these same commands). Syncs,
 * runs and their review, services and playlists, files and their name patterns, the set-up, the
 * notifications and the webhook, the settings, the numbers and the background tasks.
 */
internal class CliCommands(val api: Cli.Client, val out: PrintStream, val asJson: Boolean) {
    fun run(command: String, p: List<String>, o: Cli.Opts = Cli.Opts()): Int = when (command) {
        "status" -> status()
        "syncs", "list", "ls" -> syncs(o)
        "show" -> show(one(p, "show <sync>"))
        "run" -> runSyncs(p, o.has("all"), o.has("wait"), o.has("anyway"), group(o), o.has("confirm-removals"))
        "enable", "disable" -> toggle(one(p, "$command <sync>"), command == "enable")
        "new" -> newSync(p, o)
        "edit" -> editSync(p, o)
        "delete", "rm" -> deleteSync(one(p, "delete <sync>"))
        "move" -> { need(p, 2, "move <sync> <position|up|down|top|bottom>"); move(p.dropLast(1).joinToString(" "), p.last()) }
        "stop", "pause", "resume" -> runState(one(p, "$command <sync>"), command)
        "preview" -> preview(one(p, "preview <sync>"))
        "runs" -> runs(p.takeIf { it.isNotEmpty() }?.joinToString(" "), o)
        "report" -> report(one(p, "report <run>"))
        "review" -> review(p, o)
        "services" -> services()
        "playlists" -> playlists(one(p, "playlists <service>"))
        "tracks" -> { need(p, 2, "tracks <service> <playlist>"); tracks(p[0], p[1]) }
        "add", "remove" -> { need(p, 3, "$command <service> <playlist> <track>..."); edit(command, p[0], p[1], p.drop(2)) }
        "create" -> { need(p, 2, "create <service> <name>"); create(p[0], p.drop(1).joinToString(" ")) }
        "search" -> { need(p, 2, "search <service> <words>..."); search(p[0], p.drop(1).joinToString(" "), o) }
        "convert" -> { need(p, 2, "convert <service> <track>..."); convert(p[0], p.drop(1)) }
        "duplicates" -> { need(p, 2, "duplicates <service> <playlist> [--remove]"); duplicates(p[0], p[1], o) }
        "mbtags" -> mbTags(p.joinToString(" ").takeIf { it.isNotEmpty() })
        "export" -> { need(p, 2, "export <service> <playlist> [<file>]"); export(p[0], p[1], p.getOrNull(2), o) }
        "files" -> filesCommand(p, o)
        "import" -> import(one(p, "import <file>"))
        "patterns" -> patterns(p, o)
        "backup" -> backup(o.has("wait"))
        "config" -> if (p.firstOrNull() == "import") configImport(p.drop(1).joinToString(" ").ifEmpty { throw Cli.Failure(2, "usage: trackhop config import <file> [--dry-run]") }, o.has("dry-run")) else config(p.firstOrNull())
        "notify" -> notify(p, o)
        "webhook" -> webhook(p, o)
        "settings" -> settings(p)
        "stats" -> stats()
        "tasks" -> tasks()
        "task" -> task(one(p, "task <id>"))
        "log" -> log(o)
        else -> throw Cli.Failure(2, "unknown command \"$command\". See: trackhop help")
    }

    private fun need(p: List<String>, n: Int, form: String) { if (p.size < n) throw Cli.Failure(2, "usage: trackhop $form") }
    private fun one(p: List<String>, form: String): String { need(p, 1, form); return p.joinToString(" ") }
    private fun print(e: JsonElement) = out.println(pretty.encodeToString(JsonElement.serializer(), e))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str().orEmpty()
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun whenAt(ms: Long?) = if (ms == null || ms <= 0) "never" else time.format(Instant.ofEpochMilli(ms))

    private fun status(): Int {
        val s = api.get("status").jsonObject
        if (asJson) return print(s).let { 0 }
        val running = s["running"]?.jsonArray?.size ?: 0
        out.println("Trackhop ${s.s("version")}")
        out.println("${s.n("syncs")} syncs, ${if (running == 0) "none running" else "$running running"}, last run ${whenAt(s.l("lastRun"))}")
        return 0
    }

    private fun allSyncs() = api.get("syncs").jsonArray.map { it.jsonObject }

    /** The sync named [ask]: its id, its exact name, or the only one whose name starts with or holds it. */
    private fun sync(ask: String): JsonObject {
        val list = allSyncs()
        list.firstOrNull { it.s("id") == ask }?.let { return it }
        val exact = list.filter { it.s("name").equals(ask, ignoreCase = true) }
        if (exact.size == 1) return exact[0]
        val partial = exact.ifEmpty { list.filter { it.s("name").startsWith(ask, ignoreCase = true) } }
            .ifEmpty { list.filter { it.s("name").contains(ask, ignoreCase = true) } }
        return when (partial.size) {
            1 -> partial[0]
            0 -> throw Cli.Failure(1, "no sync called \"$ask\". See: trackhop syncs")
            else -> throw Cli.Failure(1, "\"$ask\" could be ${partial.joinToString(", ") { "\"${it.s("name")}\" (${it.s("id")})" }}: say which, by its name or id")
        }
    }

    private fun route(o: JsonObject): String {
        fun side(k: String) = o[k]?.jsonObject?.let { "${it.s("serviceName")}: ${it.s("playlist")}" }.orEmpty()
        val radio = o["radio"] as? JsonObject
        val from = if (radio != null) "Radio: ${radio.s("station")}" else side("source")
        return "$from -> ${side("target")}"
    }

    private fun result(r: JsonObject?): String {
        if (r == null) return "no runs yet"
        if (!r.b("ok")) return "failed: ${r.s("error")}"
        val extra = listOfNotNull(
            r.n("notFound").takeIf { it > 0 }?.let { "$it not found" },
            r.n("toCheck").takeIf { it > 0 }?.let { "$it to check" },
        )
        return (listOf("+${r.n("added")} -${r.n("removed")}") + extra + listOfNotNull(r.n("heldRemovals").takeIf { it > 0 }?.let { "$it removals held back (trackhop preview, then run --confirm-removals)" })).joinToString(", ")
    }

    /**
     * The group --tag, --label and --favorite ask for, as the API's query ("" for none): a tag is
     * KEY=VALUE (or KEY:VALUE), or a key alone.
     */
    private fun group(o: Cli.Opts): String {
        val tags = o.all("tag").map { t -> com.xlollx.songport.sync.Labels.parseTag(t)?.let { (k, v) -> if (v == null) k else "$k:$v" } ?: throw Cli.Failure(2, "--tag takes KEY=VALUE, or a key alone") }
        return listOfNotNull(
            tags.takeIf { it.isNotEmpty() }?.let { "tag=" + enc(it.joinToString(",")) },
            o.value("label")?.let { "label=" + enc(it) },
            "favorite=true".takeIf { o.has("favorite") },
        ).joinToString("&")
    }

    /** A sync's label and tags on one line, "" without them. */
    private fun groups(o: JsonObject): String = (
        listOfNotNull(o["labelName"].str()?.let { "label $it" }, "starred".takeIf { o.b("favorite") }) +
            (o["tags"] as? JsonObject)?.entries.orEmpty().map { (k, v) -> v.str().orEmpty().let { if (it.isEmpty()) k else "$k=$it" } }
        ).joinToString(", ")

    private fun syncs(o: Cli.Opts = Cli.Opts()): Int {
        val wanted = group(o)
        val list = if (wanted.isEmpty()) allSyncs() else api.get("syncs?$wanted").jsonArray.map { it.jsonObject }
        if (asJson) return print(JsonArray(list)).let { 0 }
        if (list.isEmpty()) out.println(if (wanted.isEmpty()) "No syncs yet: make one on Trackhop's page." else "No sync matches.")
        for (o in list) {
            val state = when { o.b("running") -> "running"; !o.b("enabled") -> "off"; else -> o.s("schedule") }
            out.println("${o.s("name")}  [$state]")
            out.println("  ${route(o)}")
            groups(o).takeIf { it.isNotEmpty() }?.let { out.println("  $it") }
            out.println("  last ${whenAt(o.l("lastRun"))}: ${result(o["lastResult"] as? JsonObject)}" +
                (o.l("nextRun")?.let { ", next ${whenAt(it)}" } ?: ""))
        }
        return 0
    }

    private fun show(ask: String): Int {
        val o = api.get("syncs/${enc(sync(ask).s("id"))}").jsonObject
        if (asJson) return print(o).let { 0 }
        out.println("${o.s("name")}  (${o.s("id")})")
        out.println("  ${route(o)}")
        out.println("  schedule ${o.s("schedule")}${if (o.b("archive")) ", keeps every track (archive)" else if (o.b("mirrorRemovals")) ", mirrors removals" else ""}${pair(o)}${if (!o.b("enabled")) ", off" else ""}${if (o.b("running")) ", running now" else ""}")
        (o["radio"] as? JsonObject)?.let { out.println("  listens ${it.s("window")}") }
        groups(o).takeIf { it.isNotEmpty() }?.let { out.println("  $it") }
        o.l("nextRun")?.let { out.println("  next run ${whenAt(it)}") }
        val runs = o["runs"]?.jsonArray.orEmpty()
        if (runs.isNotEmpty()) out.println("Runs:")
        for (r in runs.map { it.jsonObject }) out.println("  ${whenAt(r.l("started"))}  ${result(r)}")
        return 0
    }

    private fun runSyncs(p: List<String>, all: Boolean, wait: Boolean, anyway: Boolean = false, group: String = "", confirmRemovals: Boolean = false): Int {
        if (!all && p.isEmpty() && group.isEmpty()) throw Cli.Failure(2, "usage: trackhop run <sync>... | --all | --tag KEY=VALUE [--wait]")
        // Held removals are confirmed sync by sync, by its name: never for a whole group at once.
        if (confirmRemovals && (all || group.isNotEmpty())) throw Cli.Failure(2, "--confirm-removals is for syncs you name: trackhop run <sync> --confirm-removals")
        if (group.isNotEmpty() && (all || p.isNotEmpty())) throw Cli.Failure(2, "name the syncs, or say --all, or give a group (--tag, --label, --favorite): one of the three")
        // A group: the syncs with that tag or label, those turned on (as Run all).
        val targets = when {
            group.isNotEmpty() -> api.get("syncs?$group").jsonArray.map { it.jsonObject }.filter { it.b("enabled") }
            all -> allSyncs().filter { it.b("enabled") }
            else -> p.map { sync(it) }
        }
        if (group.isNotEmpty()) { if (targets.isNotEmpty()) api.post("syncs/run?$group") }
        else if (all) api.post("syncs/run-all") else targets.forEach { api.post("syncs/${enc(it.s("id"))}/run" + (if (anyway) "?anyway=true" else ""), if (confirmRemovals) buildJsonObject { put("confirmRemovals", true) } else null) }
        if (!wait) {
            if (asJson) print(buildJsonObject { put("started", buildJsonArray { targets.forEach { add(it.s("id")) } }) })
            else out.println("Started: ${targets.joinToString(", ") { it.s("name") }.ifEmpty { if (group.isEmpty()) "nothing (no syncs turned on)" else "nothing (no sync turned on matches)" }}")
            return 0
        }
        if (!asJson) out.println("Running ${targets.joinToString(", ") { it.s("name") }}...")
        val before = targets.associate { it.s("id") to (it["lastResult"] as? JsonObject)?.s("id") }
        val done = HashMap<String, JsonObject>()
        val deadline = System.currentTimeMillis() + 6 * 3600_000L
        while (done.size < targets.size && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            for (t in targets) {
                val id = t.s("id")
                if (id in done) continue
                val o = api.get("syncs/${enc(id)}").jsonObject
                val last = o["lastResult"] as? JsonObject
                if (!o.b("running") && last != null && last.s("id") != before[id]) {
                    done[id] = o
                    if (!asJson) out.println("${o.s("name")}: ${result(last)}")
                }
            }
        }
        if (asJson) print(JsonArray(done.values.map { o -> buildJsonObject { put("id", o.s("id")); put("name", o.s("name")); put("result", o["lastResult"] ?: JsonNull) } }))
        if (done.size < targets.size) throw Cli.Failure(1, "still running after 6 hours: ${targets.filter { it.s("id") !in done }.joinToString(", ") { it.s("name") }}")
        return if (done.values.all { (it["lastResult"] as? JsonObject)?.b("ok") == true }) 0 else 1
    }

    private fun toggle(ask: String, on: Boolean): Int {
        val o = api.post("syncs/${enc(sync(ask).s("id"))}/${if (on) "enable" else "disable"}").jsonObject
        if (asJson) print(o) else out.println("${o.s("name")}: ${if (on) "on (${o.s("schedule")})" else "off"}")
        return 0
    }

    private fun allServices() = api.get("services").jsonArray.map { it.jsonObject }

    private fun service(ask: String): JsonObject {
        val list = allServices()
        return list.firstOrNull { it.s("id") == ask }
            ?: list.firstOrNull { it.s("name").equals(ask, ignoreCase = true) }
            ?: list.filter { it.s("id").startsWith(ask, ignoreCase = true) || it.s("name").startsWith(ask, ignoreCase = true) }.singleOrNull()
            // A service by its id even when it is not on the list (File, a public-link source).
            ?: runCatching { api.get("services/${enc(ask.lowercase())}").jsonObject }.getOrNull()?.takeIf { it.s("id").isNotEmpty() }
            ?: throw Cli.Failure(1, "no service \"$ask\" here. See: trackhop services")
    }

    private fun services(): Int {
        val list = allServices()
        if (asJson) return print(JsonArray(list)).let { 0 }
        if (list.isEmpty()) out.println("No services added yet: add them on Trackhop's page, Services.")
        for (o in list) out.println("${o.s("name")}  (${o.s("id")})  ${if (o.b("connected")) "connected" + (o["account"].str()?.let { " as $it" } ?: "") else "not connected"}")
        return 0
    }

    private fun allPlaylists(serviceId: String) = api.get("services/${enc(serviceId)}/playlists").jsonArray.map { it.jsonObject }

    private fun playlist(serviceId: String, ask: String): JsonObject {
        val list = allPlaylists(serviceId)
        return list.firstOrNull { it.s("id") == ask }
            ?: list.filter { it.s("name").equals(ask, ignoreCase = true) }.let { if (it.size > 1) throw Cli.Failure(1, "more than one playlist is called \"$ask\": use its id (trackhop playlists $serviceId)") else it.firstOrNull() }
            ?: throw Cli.Failure(1, "no playlist \"$ask\". See: trackhop playlists $serviceId")
    }

    private fun playlists(ask: String): Int {
        val s = service(ask)
        val list = allPlaylists(s.s("id"))
        if (asJson) return print(JsonArray(list)).let { 0 }
        for (o in list) out.println("${o.s("name")}  (${o.n("tracks")} tracks)  ${o.s("id")}")
        return 0
    }

    private fun line(t: JsonObject) = "${t["artists"]?.jsonArray?.mapNotNull { it.str() }.orEmpty().joinToString(", ")} - ${t.s("title")}"

    private fun tracks(serviceAsk: String, playlistAsk: String): Int {
        val s = service(serviceAsk)
        val pl = playlist(s.s("id"), playlistAsk)
        val list = api.get("services/${enc(s.s("id"))}/playlists/${enc(pl.s("id"))}/tracks").jsonArray.map { it.jsonObject }
        if (asJson) return print(JsonArray(list)).let { 0 }
        list.forEachIndexed { i, t -> out.println("${i + 1}. ${line(t)}") }
        return 0
    }

    private fun edit(command: String, serviceAsk: String, playlistAsk: String, wanted: List<String>): Int {
        val s = service(serviceAsk)
        val pl = playlist(s.s("id"), playlistAsk)
        val body = buildJsonObject { put("tracks", buildJsonArray { wanted.forEach { add(it) } }) }
        val path = "services/${enc(s.s("id"))}/playlists/${enc(pl.s("id"))}/tracks" + if (command == "remove") "/remove" else ""
        val r = api.post(path, body).jsonObject
        if (asJson) return print(r).let { 0 }
        val changed = r[if (command == "add") "added" else "removed"]?.jsonArray.orEmpty().map { it.jsonObject }
        changed.forEach { out.println("${if (command == "add") "+" else "-"} ${line(it)}") }
        r["alreadyThere"]?.jsonArray?.forEach { out.println("= ${it.str()} (already there)") }
        r["notFound"]?.jsonArray?.forEach { out.println("? ${it.str()} (not found)") }
        return 0
    }

    private fun create(serviceAsk: String, name: String): Int {
        val s = service(serviceAsk)
        val r = api.post("services/${enc(s.s("id"))}/playlists", buildJsonObject { put("name", name) }).jsonObject
        if (asJson) print(r) else out.println("Created \"${r.s("name")}\" on ${s.s("name")}: ${r.s("id")}")
        return 0
    }

    private fun files(): Int {
        val list = api.get("files").jsonArray
        if (asJson) return print(list).let { 0 }
        list.forEach { out.println(it.str()) }
        return 0
    }

    private fun import(path: String): Int {
        val f = File(path)
        if (!f.isFile) throw Cli.Failure(2, "no file at $path")
        val r = Json.parseToJsonElement(api.raw("PUT", "files/${enc(f.name)}", f.readBytes())).jsonObject
        if (asJson) print(r) else out.println("Imported ${f.name}: ${r.n("tracks")} tracks")
        return 0
    }

    private fun backup(wait: Boolean): Int {
        val task = api.post("backup").jsonObject.s("task")
        if (!wait) {
            if (asJson) print(buildJsonObject { put("task", task) }) else out.println("Backup started.")
            return 0
        }
        if (!asJson) out.println("Backing up...")
        while (true) {
            Thread.sleep(2000)
            val t = api.get("tasks/${enc(task)}").jsonObject
            if (!t.b("done")) continue
            if (asJson) print(t) else out.println(t["error"].str()?.let { "Backup failed: $it" } ?: "Backup done.")
            return if (t["error"].str() == null) 0 else 1
        }
    }

    private fun allChannels() = api.get("notifications").jsonArray.map { it.jsonObject }

    /** The channel named [ask]: its id, or its name (exact, then the only one that starts with it). */
    private fun channel(ask: String): JsonObject {
        val list = allChannels()
        return list.firstOrNull { it.s("id") == ask }
            ?: list.filter { it.s("name").equals(ask, ignoreCase = true) }.let { if (it.size > 1) throw Cli.Failure(1, "more than one channel is called \"$ask\": use its id (trackhop notify)") else it.firstOrNull() }
            ?: list.filter { it.s("name").startsWith(ask, ignoreCase = true) }.singleOrNull()
            ?: throw Cli.Failure(1, "no notification channel \"$ask\". See: trackhop notify")
    }

    /** --name, --set key=value (repeated) and --events a,b as the API's channel body. */
    private fun channelBody(kind: String?, o: Cli.Opts) = buildJsonObject {
        kind?.let { put("kind", it) }
        o.value("name")?.let { put("name", it) }
        o.value("events")?.let { e -> put("events", buildJsonArray { e.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }) }
        val set = o.all("set")
        if (set.isNotEmpty()) put("settings", buildJsonObject {
            set.forEach { kv ->
                if ('=' !in kv) throw Cli.Failure(2, "--set takes key=value (see: trackhop notify kinds)")
                put(kv.substringBefore('=').trim(), kv.substringAfter('='))
            }
        })
    }

    private fun showChannel(o: JsonObject) {
        val events = o["events"]?.jsonArray?.mapNotNull { it.str() }.orEmpty()
        val state = when { !o.b("ready") -> "needs its token"; o.b("enabled") -> "on"; else -> "off" }
        out.println("${o.s("name")}  (${o.s("id")})  ${o.s("kindName")}  [$state]")
        out.println("  when: ${events.joinToString(", ").ifEmpty { "nothing chosen" }}")
    }

    private fun notify(p: List<String>, o: Cli.Opts): Int {
        val rest = p.drop(1).joinToString(" ")
        when (p.firstOrNull()) {
            null, "list", "ls" -> {
                val list = allChannels()
                if (asJson) return print(JsonArray(list)).let { 0 }
                if (list.isEmpty()) out.println("No notification channels yet: trackhop notify add, or Trackhop's page, Settings > Notifications.")
                list.forEach { showChannel(it) }
                return 0
            }
            "kinds" -> {
                val k = api.get("notifications/kinds").jsonObject
                if (asJson) return print(k).let { 0 }
                for (kind in k["kinds"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                    out.println("${kind.s("id")}  ${kind.s("name")}")
                    out.println("  ${kind.s("help")}")
                    for (f in kind["settings"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                        out.println("  --set ${f.s("key")}=…  ${f.s("label")}${if (f.b("optional")) " (optional)" else ""}${f.s("default").takeIf { it.isNotEmpty() }?.let { ", default $it" } ?: ""}")
                    }
                }
                out.println("Events (--events): ${k["events"]?.jsonArray.orEmpty().joinToString(", ") { it.jsonObject.s("key") }}")
                return 0
            }
            "add" -> {
                val kind = p.getOrNull(1) ?: throw Cli.Failure(2, "usage: trackhop notify add <kind> [--name N] [--set key=value]... (see: trackhop notify kinds)")
                val r = api.send("POST", "notifications", channelBody(kind, o)).jsonObject
                if (asJson) print(r) else { out.print("Added: "); showChannel(r); out.println("Send it a test: trackhop notify test ${r.s("id")}") }
                return 0
            }
            "edit" -> {
                val c = channel(rest.ifEmpty { throw Cli.Failure(2, "usage: trackhop notify edit <channel> [--name N] [--set key=value]... [--events ...]") })
                val body = channelBody(null, o)
                if (body.isEmpty()) throw Cli.Failure(2, "say what to change: --name, --set key=value, --events")
                val r = api.send("PATCH", "notifications/${enc(c.s("id"))}", body).jsonObject
                if (asJson) print(r) else showChannel(r)
                return 0
            }
            "on", "off" -> {
                val c = channel(rest.ifEmpty { throw Cli.Failure(2, "usage: trackhop notify ${p[0]} <channel>") })
                val r = api.send("PATCH", "notifications/${enc(c.s("id"))}", buildJsonObject { put("enabled", p[0] == "on") }).jsonObject
                if (asJson) print(r) else showChannel(r)
                return 0
            }
            "rm", "remove", "delete" -> {
                val c = channel(rest.ifEmpty { throw Cli.Failure(2, "usage: trackhop notify rm <channel>") })
                val r = api.send("DELETE", "notifications/${enc(c.s("id"))}")
                if (asJson) print(r) else out.println("Removed ${c.s("name")}.")
                return 0
            }
            "test" -> {
                val c = channel(rest.ifEmpty { throw Cli.Failure(2, "usage: trackhop notify test <channel>") })
                val r = api.post("notifications/${enc(c.s("id"))}/test").jsonObject
                if (asJson) print(r) else out.println("Sent a test to ${c.s("name")}: check that it arrived.")
                return 0
            }
            else -> throw Cli.Failure(2, "usage: trackhop notify [kinds | add <kind> | edit <channel> | on|off|rm|test <channel>]")
        }
    }

    private fun config(file: String?): Int {
        val text = api.raw("GET", "config", null)
        if (file == null) out.println(text) else { File(file).writeText(text); out.println("Saved the set-up to $file") }
        return 0
    }

    // ------------------------------------------------------------------ syncs: make, change, remove

    private val dayNames = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private fun day(d: String): Int = d.trim().lowercase().let { w -> w.toIntOrNull()?.takeIf { it in 1..7 } ?: (dayNames.indexOfFirst { w.startsWith(it) } + 1).takeIf { it > 0 } }
        ?: throw Cli.Failure(2, "--days takes days as mon,tue… or 1 (Monday) to 7")

    /** The sync options (--name, --schedule, --every, --at, --days, --changes, --follows, --color) as the API's body fields. */
    private fun JsonObjectBuilder.syncFields(o: Cli.Opts) {
        o.value("name")?.let { put("name", it) }
        o.value("schedule")?.let { put("schedule", it) }
        o.value("every")?.let { put("every", it.toIntOrNull() ?: throw Cli.Failure(2, "--every takes minutes, e.g. 180")) }
        o.value("at")?.let { put("at", it) }
        o.value("days")?.let { d -> put("days", buildJsonArray { d.split(',').filter { it.isNotBlank() }.forEach { add(day(it)) } }) }
        o.value("changes")?.let { put("changes", it) }
        if (o.has("follows")) put("follows", true)
        o.value("color")?.let { put("color", if (it == "none") "" else it) }
        o.value("label")?.let { put("label", if (it == "none") "" else it) }
        if (o.has("favorite")) put("favorite", true)
        if (o.has("unfavorite")) put("favorite", false)
        // Two-way: the reverse sync made or deleted, and whether removals travel across the pair.
        if (o.has("two-way") && o.has("one-way")) throw Cli.Failure(2, "--two-way or --one-way, not both")
        if (o.has("two-way")) put("twoWay", true)
        if (o.has("one-way")) put("twoWay", false)
        if (o.has("two-way-removals")) put("twoWayRemovals", true)
        if (o.has("no-two-way-removals")) put("twoWayRemovals", false)
    }

    /** [now] (a sync's tags as the API gives them) with --tag KEY=VALUE added or changed and --untag KEY taken off; null when neither is given. */
    private fun tagsField(o: Cli.Opts, now: JsonObject?): JsonObject? {
        if (o.all("tag").isEmpty() && o.all("untag").isEmpty()) return null
        val fold = com.xlollx.songport.sync.Labels::fold
        val tags = LinkedHashMap<String, String>()
        now?.forEach { (k, v) -> tags[k] = v.str().orEmpty() }
        for (t in o.all("tag")) {
            val (k, v) = com.xlollx.songport.sync.Labels.parseTag(t) ?: throw Cli.Failure(2, "--tag takes KEY=VALUE, or a key alone")
            tags.keys.firstOrNull { fold(it) == fold(k) }?.let { tags.remove(it) }
            tags[k] = v.orEmpty()
        }
        for (k in o.all("untag")) tags.keys.firstOrNull { fold(it) == fold(k) }?.let { tags.remove(it) }
        return buildJsonObject { tags.forEach { (k, v) -> put(k, v) } }
    }

    private fun isLink(s: String) = s.startsWith("http://") || s.startsWith("https://")

    private fun newSync(p: List<String>, o: Cli.Opts): Int {
        need(p, 3, "new <service> <playlist> <service> [<playlist>] [options]")
        val src = service(p[0])
        val dst = service(p[2])
        val body = buildJsonObject {
            put("source", buildJsonObject {
                put("service", src.s("id"))
                if (isLink(p[1]) || src.b("linkOnly")) put("link", p[1]) else put("playlist", playlist(src.s("id"), p[1]).s("id"))
            })
            put("target", buildJsonObject {
                put("service", dst.s("id"))
                p.drop(3).joinToString(" ").takeIf { it.isNotEmpty() }?.let { put("playlist", playlist(dst.s("id"), it).s("id")) }
            })
            syncFields(o)
            tagsField(o, null)?.let { put("tags", it) }
        }
        val r = api.send("POST", "syncs", body).jsonObject
        if (asJson) return print(r).let { 0 }
        out.println("Made \"${r.s("name")}\" (${r.s("id")}): ${route(r)}, ${r.s("timing")}${pair(r)}")
        r["warning"].str()?.let { out.println("Note: $it") }
        return 0
    }

    private fun editSync(p: List<String>, o: Cli.Opts): Int {
        val target = sync(one(p, "edit <sync> [options]"))
        val body = buildJsonObject { syncFields(o); tagsField(o, target["tags"] as? JsonObject)?.let { put("tags", it) } }
        if (body.isEmpty()) throw Cli.Failure(2, "say what to change: --name, --schedule or --every, --at, --days, --changes, --follows, --color, --label, --tag, --untag, --favorite, --two-way (trackhop help edit)")
        val r = api.send("PATCH", "syncs/${enc(target.s("id"))}", body).jsonObject
        if (asJson) return print(r).let { 0 }
        out.println("${r.s("name")}: ${route(r)}, ${r.s("timing")}${if (r.b("archive")) ", archive" else if (r.b("mirrorRemovals")) ", mirrors removals" else ""}${pair(r)}${groups(r).let { if (it.isEmpty()) "" else ", $it" }}")
        r["warning"].str()?.let { out.println("Note: $it") }
        return 0
    }

    /** ", two-way" for half of a pair (with its removals when they travel too), "" for a sync on its own. */
    private fun pair(o: JsonObject) = if (o.b("twoWay")) ", two-way" + (if (o.b("twoWayRemovals")) " with removals" else "") else ""

    private fun deleteSync(ask: String): Int {
        val target = sync(ask)
        val r = api.send("DELETE", "syncs/${enc(target.s("id"))}")
        if (asJson) print(r) else out.println("Deleted ${target.s("name")}. Its playlists stay as they are.")
        return 0
    }

    private fun move(ask: String, to: String): Int {
        val target = sync(ask)
        val r = api.post("syncs/${enc(target.s("id"))}/move", buildJsonObject { to.toIntOrNull()?.let { put("to", it) } ?: put("to", to) }).jsonObject
        if (asJson) print(r) else out.println("${target.s("name")}: now ${r.n("position")} of ${r.n("of")}")
        return 0
    }

    private fun runState(ask: String, what: String): Int {
        val target = sync(ask)
        val r = api.post("syncs/${enc(target.s("id"))}/$what").jsonObject
        if (asJson) print(r) else out.println("${target.s("name")}: ${when (what) { "stop" -> "stopping"; "pause" -> "paused"; else -> "running again" }}")
        return 0
    }

    private fun preview(ask: String): Int {
        val target = sync(ask)
        if (!asJson) out.println("Reading ${route(target)}...")
        val r = api.get("syncs/${enc(target.s("id"))}/preview").jsonObject
        if (asJson) return print(r).let { 0 }
        fun list(k: String) = r[k]?.jsonArray.orEmpty().map { it.jsonObject }
        val add = list("toAdd"); val remove = list("toRemove"); val missing = list("notFound"); val check = list("toCheck")
        out.println("Would add ${add.size}, remove ${remove.size}; ${missing.size} not found, ${check.size} to check (${r.n("alreadyThere")} already there)")
        add.take(MAX_LIST).forEach { out.println("+ ${ref(it)}") }
        remove.take(MAX_LIST).forEach { out.println("- ${ref(it)}") }
        missing.take(MAX_LIST).forEach { out.println("? ${ref(it)}") }
        check.take(MAX_LIST).forEach { out.println("~ ${ref(it["source"]!!.jsonObject)}  ->  ${ref(it["match"]!!.jsonObject)}") }
        if (listOf(add, remove, missing, check).any { it.size > MAX_LIST }) out.println("(the first $MAX_LIST of each: --json for all)")
        if (r.b("removalsNeedConfirmation")) out.println("A normal run would hold the removals back for your confirmation: trackhop run \"${target.s("name")}\" --confirm-removals makes them.")
        r["notes"]?.jsonArray?.forEach { out.println("Note: ${it.str()}") }
        return 0
    }

    private fun ref(t: JsonObject) = "${t.s("artist")} - ${t.s("title")}"

    // ------------------------------------------------------------------ runs and review

    private fun runLine(r: JsonObject) = "${whenAt(r.l("started"))}  ${r.s("syncName")}  ${result(r)}  (${r.s("id")})"

    private fun runs(ask: String?, o: Cli.Opts): Int {
        val q = listOfNotNull(o.value("limit")?.let { "limit=${enc(it)}" }, o.value("offset")?.let { "offset=${enc(it)}" }).joinToString("&")
        val path = (if (ask != null) "syncs/${enc(sync(ask).s("id"))}/runs" else "runs") + if (q.isNotEmpty()) "?$q" else ""
        val r = api.get(path).jsonObject
        if (asJson) return print(r).let { 0 }
        val list = r["runs"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (list.isEmpty()) out.println("No runs yet.")
        list.forEach { out.println(runLine(it)) }
        if (r.n("total") > r.n("offset") + list.size) out.println("(${r.n("total")} in all: --offset ${r.n("offset") + list.size} for the next)")
        return 0
    }

    private fun report(id: String): Int {
        val r = api.get("runs/${enc(id)}").jsonObject
        if (asJson) return print(r).let { 0 }
        out.println(runLine(r))
        r["error"].str()?.let { out.println("Error: $it") }
        r["addedTracks"]?.jsonArray?.forEach { out.println("+ ${ref(it.jsonObject)}") }
        r["removedTracks"]?.jsonArray?.forEach { out.println("- ${ref(it.jsonObject)}") }
        r["notFoundTracks"]?.jsonArray?.forEach { out.println("? ${ref(it.jsonObject)} (not found)") }
        r["toCheckTracks"]?.jsonArray?.forEach { m -> out.println("~ ${ref(m.jsonObject["source"]!!.jsonObject)}  ->  ${ref(m.jsonObject["match"]!!.jsonObject)} (to check)") }
        r["notes"]?.jsonArray?.forEach { out.println("Note: ${it.str()}") }
        return 0
    }

    /** The run to review: --run, else the newest of [syncId]'s with something in its review. */
    private fun reviewRun(syncId: String, o: Cli.Opts): String {
        o.value("run")?.let { return it }
        val list = api.get("syncs/${enc(syncId)}/runs?limit=50").jsonObject["runs"]?.jsonArray.orEmpty().map { it.jsonObject }
        return (list.firstOrNull { it.n("notFound") > 0 || it.n("toCheck") > 0 || it.n("removed") > 0 } ?: list.firstOrNull())?.s("id")
            ?: throw Cli.Failure(1, "that sync has not run yet")
    }

    private fun review(p: List<String>, o: Cli.Opts): Int {
        need(p, 1, "review <sync> [ignore|keep|discard|pick|replace|restore ...]")
        val actions = setOf("ignore", "keep", "discard", "pick", "replace", "restore")
        val at = p.indexOfFirst { it in actions }.takeIf { it > 0 } ?: p.size
        val target = sync(p.take(at).joinToString(" "))
        val run = reviewRun(target.s("id"), o)
        val action = p.getOrNull(at)
        val args = p.drop(at + 1)
        val now = api.get("runs/${enc(run)}/review").jsonObject
        fun section(k: String) = now[k]?.jsonArray.orEmpty().map { it.jsonObject }
        val notFound = section("notFound"); val toCheck = section("toCheck"); val removed = section("removed")
        if (action == null) {
            if (asJson) return print(now).let { 0 }
            out.println("Run of ${whenAt(now.l("started"))} ($run)")
            if (notFound.isEmpty() && toCheck.isEmpty() && removed.isEmpty()) out.println("Nothing to review.")
            if (notFound.isNotEmpty()) out.println("Not found:")
            notFound.forEachIndexed { i, t -> out.println("  ${i + 1}. ${ref(t)}") }
            if (toCheck.isNotEmpty()) out.println("To check:")
            toCheck.forEachIndexed { i, m -> out.println("  ${i + 1}. ${ref(m["source"]!!.jsonObject)}  ->  ${ref(m["match"]!!.jsonObject)} (${((m["score"].str()?.toDoubleOrNull() ?: 0.0) * 100).toInt()}%)") }
            if (removed.isNotEmpty()) out.println("Removed (trackhop review ... restore puts them back):")
            removed.forEachIndexed { i, t -> out.println("  ${i + 1}. ${ref(t)}") }
            return 0
        }
        // A number from the list, "all", or an id as the API shows it.
        fun ids(rows: List<JsonObject>, refs: List<String>, idOf: (JsonObject) -> String): JsonElement {
            if (refs.isEmpty()) throw Cli.Failure(2, "say which: numbers from trackhop review, or all")
            if (refs == listOf("all")) return JsonPrimitive("all")
            // An id of the list first: some services' ids are numbers too, and "12345" is then no row.
            val known = rows.map(idOf).toSet()
            return buildJsonArray { refs.forEach { r -> add(if (r in known) r else r.toIntOrNull()?.let { n -> rows.getOrNull(n - 1)?.let(idOf) ?: throw Cli.Failure(2, "no row $n in that list") } ?: r) } }
        }
        val body = buildJsonObject {
            put("action", action)
            when (action) {
                "ignore" -> put("tracks", ids(notFound, args) { it.s("id") })
                "keep", "discard" -> put("tracks", ids(toCheck, args) { it["source"]!!.jsonObject.s("id") })
                "pick", "replace" -> {
                    if (args.size < 2) throw Cli.Failure(2, "usage: trackhop review <sync> $action <n> <track>")
                    put("tracks", ids(if (action == "pick") notFound else toCheck, args.take(1)) { if (action == "pick") it.s("id") else it["source"]!!.jsonObject.s("id") })
                    put("choice", args.drop(1).joinToString(" "))
                }
            }
        }
        val r = api.post("runs/${enc(run)}/review", body).jsonObject
        if (asJson) print(r) else out.println(r.s("done"))
        return 0
    }

    // ------------------------------------------------------------------ services

    private fun search(serviceAsk: String, words: String, o: Cli.Opts): Int {
        val s = service(serviceAsk)
        val list = api.get("services/${enc(s.s("id"))}/search?q=${enc(words)}" + (o.value("limit")?.let { "&limit=${enc(it)}" } ?: "")).jsonArray.map { it.jsonObject }
        if (asJson) return print(JsonArray(list)).let { 0 }
        if (list.isEmpty()) out.println("Nothing found on ${s.s("name")}.")
        list.forEachIndexed { i, t -> out.println("${i + 1}. ${line(t)}${t.s("album").takeIf { it.isNotEmpty() }?.let { "  ($it)" } ?: ""}  ${t.s("id")}") }
        return 0
    }

    private fun convert(serviceAsk: String, tracks: List<String>): Int {
        val s = service(serviceAsk)
        val r = api.post("convert", buildJsonObject { put("to", s.s("id")); put("tracks", buildJsonArray { tracks.forEach { add(it) } }) }).jsonArray.map { it.jsonObject }
        if (asJson) return print(JsonArray(r)).let { 0 }
        for (a in r) {
            val m = a["match"] as? JsonObject
            out.println("${a.s("asked")}  ->  ${if (m == null) "not found on ${s.s("name")}" else "${line(m)}  ${m.s("id")} (${((a["score"].str()?.toDoubleOrNull() ?: 0.0) * 100).toInt()}%)"}")
        }
        return if (r.all { it["match"] is JsonObject }) 0 else 1
    }

    private fun duplicates(serviceAsk: String, playlistAsk: String, o: Cli.Opts): Int {
        val s = service(serviceAsk)
        val pl = playlist(s.s("id"), playlistAsk)
        val path = "services/${enc(s.s("id"))}/playlists/${enc(pl.s("id"))}/duplicates"
        if (o.has("remove")) {
            val task = api.post("$path/remove").jsonObject.s("task")
            return followTask(task, o.has("wait"), "Removing the extra copies from ${pl.s("name")}")
        }
        val r = api.get(path).jsonObject
        if (asJson) return print(r).let { 0 }
        val groups = r["groups"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (groups.isEmpty()) out.println("No duplicates in ${pl.s("name")}.")
        groups.forEach { g -> out.println("${ref(g["track"]!!.jsonObject)}  x${g.n("copies")}") }
        if (groups.isNotEmpty()) out.println("${r.n("extraCopies")} extra copies${if (r.b("canRemove")) ": trackhop duplicates ${s.s("id")} \"${pl.s("name")}\" --remove" else ""}")
        return 0
    }

    private fun mbTags(serviceAsk: String?): Int {
        val s = serviceAsk?.let { service(it) }
        if (!asJson) out.println("Reading ${s?.s("name") ?: "the library"}...")
        val r = api.get("tools/mbtags" + (s?.let { "?service=${enc(it.s("id"))}" } ?: "")).jsonObject
        if (asJson) return print(r).let { 0 }
        val tracks = r["tracks"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (r.n("songs") == 0) out.println("The server did not list its songs.")
        else if (tracks.isEmpty()) out.println("All ${r.n("songs")} songs have MusicBrainz tags.")
        else {
            out.println("${r.n("untagged")} of ${r.n("songs")} songs have no MusicBrainz tags (a sync finds these by title, artist and duration):")
            tracks.forEach { t -> out.println("  ${ref(t)}${t["album"].str()?.let { "  [$it]" } ?: ""}") }
            out.println("Tags are written by a tagger such as MusicBrainz Picard: ${r.s("guide")}")
        }
        return 0
    }

    private fun export(serviceAsk: String, playlistAsk: String, file: String?, o: Cli.Opts): Int {
        val s = service(serviceAsk)
        val pl = playlist(s.s("id"), playlistAsk)
        val format = o.value("format") ?: file?.substringAfterLast('.', "")?.takeIf { it in setOf("csv", "m3u", "xspf", "jspf", "txt") } ?: "csv"
        val text = api.raw("GET", "services/${enc(s.s("id"))}/playlists/${enc(pl.s("id"))}/export?format=${enc(format)}", null)
        if (file == null) out.print(text) else { File(file).writeText(text); out.println("Saved ${pl.s("name")} to $file") }
        return 0
    }

    // ------------------------------------------------------------------ files

    private fun file(ask: String): String {
        val names = api.get("files").jsonArray.mapNotNull { it.str() }
        return names.firstOrNull { it == ask } ?: names.firstOrNull { it.removeSuffix(".csv").equals(ask.removeSuffix(".csv"), ignoreCase = true) }
            ?: throw Cli.Failure(1, "no file \"$ask\". See: trackhop files")
    }

    private fun filesCommand(p: List<String>, o: Cli.Opts): Int = when (p.firstOrNull()) {
        null, "list", "ls" -> files()
        "get" -> {
            val name = file(p.getOrNull(1) ?: throw Cli.Failure(2, "usage: trackhop files get <name> [<file>] [--format F]"))
            val to = p.getOrNull(2)
            val format = o.value("format") ?: to?.substringAfterLast('.', "")?.takeIf { it in setOf("csv", "m3u", "xspf", "jspf", "txt") } ?: "csv"
            val text = api.raw("GET", "files/${enc(name)}?format=${enc(format)}", null)
            if (to == null) out.print(text) else { File(to).writeText(text); out.println("Saved $name to $to") }
            0
        }
        "rm", "remove", "delete" -> {
            val names = p.drop(1).ifEmpty { throw Cli.Failure(2, "usage: trackhop files rm <name>...") }.map { file(it) }
            val done = names.map { n -> api.send("DELETE", "files/${enc(n)}") }
            if (asJson) print(JsonArray(done)) else names.forEach { out.println("Deleted $it") }
            0
        }
        "zip" -> {
            val to = p.getOrNull(1) ?: throw Cli.Failure(2, "usage: trackhop files zip <file.zip> [<name>...] [--format F]")
            val names = p.drop(2).map { file(it) }
            val bytes = api.bytes("POST", "files/zip", buildJsonObject {
                if (names.isNotEmpty()) put("files", buildJsonArray { names.forEach { add(it) } })
                o.value("format")?.let { put("format", it) }
            })
            File(to).writeBytes(bytes)
            if (asJson) print(buildJsonObject { put("saved", to); put("bytes", bytes.size) }) else out.println("Saved ${if (names.isEmpty()) "every file" else "${names.size} files"} to $to")
            0
        }
        else -> throw Cli.Failure(2, "usage: trackhop files [get <name> | rm <name>... | zip <file.zip> [<name>...]]")
    }

    private fun patterns(p: List<String>, o: Cli.Opts): Int {
        fun show(r: JsonObject): Int {
            if (asJson) return print(r).let { 0 }
            out.println("Names read as: ${r.s("layout").lowercase()}")
            val list = r["patterns"]?.jsonArray?.mapNotNull { it.str() }.orEmpty()
            if (list.isEmpty()) out.println("No patterns of your own.") else { out.println("Your patterns, tried in order:"); list.forEach { out.println("  $it") } }
            return 0
        }
        return when (p.firstOrNull()) {
            null, "show" -> show(api.get("files/settings").jsonObject)
            "layout" -> show(api.send("PUT", "files/settings", buildJsonObject { put("layout", (p.getOrNull(1) ?: throw Cli.Failure(2, "usage: trackhop patterns layout auto|artist_title|artist_album_title|custom")).uppercase()) }).jsonObject)
            "set" -> {
                val lines = p.drop(1).ifEmpty { throw Cli.Failure(2, "usage: trackhop patterns set <pattern>... (each in quotes)") }
                show(api.send("PUT", "files/settings", buildJsonObject { put("layout", "CUSTOM"); put("patterns", buildJsonArray { lines.forEach { add(it) } }) }).jsonObject)
            }
            "clear" -> show(api.send("PUT", "files/settings", buildJsonObject { put("layout", "AUTO"); put("patterns", buildJsonArray { }) }).jsonObject)
            "try" -> {
                val sample = p.drop(1).joinToString(" ").ifEmpty { throw Cli.Failure(2, "usage: trackhop patterns try <line> [--pattern P]...") }
                val r = api.post("files/try", buildJsonObject {
                    put("sample", sample)
                    o.all("pattern").takeIf { it.isNotEmpty() }?.let { l -> put("patterns", buildJsonArray { l.forEach { add(it) } }) }
                }).jsonObject
                if (asJson) print(r)
                else if (!r.b("matched")) out.println("No pattern reads it: it would be read as usual.")
                else out.println("Artist: ${r.s("artist").ifEmpty { "-" }}\nAlbum: ${r.s("album").ifEmpty { "-" }}\nTitle: ${r.s("title")}")
                if (r.b("matched")) 0 else 1
            }
            else -> throw Cli.Failure(2, "usage: trackhop patterns [layout <name> | set <pattern>... | clear | try <line>]")
        }
    }

    private fun configImport(path: String, dryRun: Boolean): Int {
        val f = File(path)
        if (!f.isFile) throw Cli.Failure(2, "no file at $path")
        val r = Json.parseToJsonElement(api.raw("POST", "config" + if (dryRun) "?dryRun=true" else "", f.readBytes())).jsonObject
        if (asJson) return print(r).let { 0 }
        val what = "${r.n("syncs")} syncs, ${r.n("matches")} matches chosen by hand, ${r.n("files")} File playlists, ${r.n("channels")} notification channels"
        out.println(if (r.b("imported")) "Imported $what. Connect the services again: the syncs carry on from there." else "${f.name} holds $what${r.s("writtenBy").takeIf { it.isNotEmpty() }?.let { ", from $it" } ?: ""}. Nothing changed (--dry-run).")
        return 0
    }

    // ------------------------------------------------------------------ webhook, settings, numbers, tasks, log

    private fun webhook(p: List<String>, o: Cli.Opts): Int {
        val r = when (p.firstOrNull()) {
            null, "show" -> api.get("webhook").jsonObject
            "set" -> api.send("PUT", "webhook", buildJsonObject {
                put("url", p.getOrNull(1) ?: throw Cli.Failure(2, "usage: trackhop webhook set <url> [--secret S] [--events a,b]"))
                o.value("secret")?.let { put("secret", it) }
                o.value("events")?.let { e -> put("events", buildJsonArray { e.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }) }
            }).jsonObject
            "off" -> api.send("DELETE", "webhook").jsonObject
            else -> throw Cli.Failure(2, "usage: trackhop webhook [set <url> | off]")
        }
        if (asJson) return print(r).let { 0 }
        if (r.s("url").isEmpty()) out.println("No webhook.")
        else out.println("${r.s("url")}${if (r.b("secret")) " (signed)" else ""}\n  when: ${r["events"]?.jsonArray?.mapNotNull { it.str() }.orEmpty().joinToString(", ").ifEmpty { "nothing chosen" }}")
        return 0
    }

    private fun settings(p: List<String>): Int {
        val r = when (p.firstOrNull()) {
            null, "show" -> api.get("settings").jsonObject
            "set" -> {
                if (p.size < 3) throw Cli.Failure(2, "usage: trackhop settings set <key> <value>")
                val v = p.drop(2).joinToString(" ")
                api.send("PATCH", "settings", buildJsonObject { if (v == "true" || v == "false") put(p[1], v.toBoolean()) else put(p[1], v) }).jsonObject
            }
            else -> throw Cli.Failure(2, "usage: trackhop settings [set <key> <value>]")
        }
        if (asJson) return print(r).let { 0 }
        r.forEach { (k, v) -> out.println("$k: ${(v as? JsonArray)?.mapNotNull { it.str() }?.joinToString(" | ") ?: v.str().orEmpty()}") }
        return 0
    }

    private fun stats(): Int {
        val r = api.get("stats").jsonObject
        if (asJson) return print(r).let { 0 }
        if (r.n("runs") == 0) return out.println("No sync has run yet: the numbers start with the first one.").let { 0 }
        val minutes = (r.l("secondsSaved") ?: 0) / 60
        out.println("${r.n("added")} tracks moved in ${r.n("runs")} runs since ${whenAt(r.l("since")).substringBefore(' ')}")
        out.println("${r.n("addedThisMonth")} added this month; about ${if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"} saved")
        (r["busiestRoute"] as? JsonObject)?.let { out.println("Busiest route: ${it.s("source")} -> ${it.s("target")} (${it.n("runs")} runs)") }
        (r["biggestPlaylist"] as? JsonObject)?.let { out.println("Biggest playlist: ${it.s("name")} (${it.n("tracks")} tracks)") }
        val top = r["topArtists"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (top.isNotEmpty()) out.println("Most added: ${top.joinToString(", ") { "${it.s("name")} (${it.n("tracks")})" }}")
        return 0
    }

    private fun tasks(): Int {
        val list = api.get("tasks").jsonArray.map { it.jsonObject }
        if (asJson) return print(JsonArray(list)).let { 0 }
        if (list.isEmpty()) out.println("No background tasks in the last hour.")
        list.forEach { t -> out.println("${t.s("title")}  ${if (!t.b("done")) "running: ${t.s("progress")}" else t["error"].str()?.let { "failed: $it" } ?: "done"}  (${t.s("id")})") }
        return 0
    }

    private fun task(id: String): Int {
        val t = api.get("tasks/${enc(id)}").jsonObject
        if (asJson) return print(t).let { 0 }
        out.println("${t.s("title")}: ${if (!t.b("done")) "running: ${t.s("progress")}" else t["error"].str()?.let { "failed: $it" } ?: "done"}")
        return 0
    }

    /** Follows a background task to its end with --wait, or says it started. */
    private fun followTask(task: String, wait: Boolean, what: String): Int {
        if (!wait) {
            if (asJson) print(buildJsonObject { put("task", task) }) else out.println("$what: started (trackhop task $task).")
            return 0
        }
        if (!asJson) out.println("$what...")
        while (true) {
            Thread.sleep(1000)
            val t = api.get("tasks/${enc(task)}").jsonObject
            if (!t.b("done")) continue
            if (asJson) print(t) else out.println(t["error"].str()?.let { "Failed: $it" } ?: "Done.")
            return if (t["error"].str() == null) 0 else 1
        }
    }

    private fun log(o: Cli.Opts): Int {
        val lines = api.get("log" + (o.value("lines")?.let { "?lines=${enc(it)}" } ?: "")).jsonArray
        if (asJson) return print(lines).let { 0 }
        lines.forEach { out.println(it.str()) }
        return 0
    }

    private companion object {
        const val MAX_LIST = 20
        val pretty = Json { prettyPrint = true }
        val time: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}

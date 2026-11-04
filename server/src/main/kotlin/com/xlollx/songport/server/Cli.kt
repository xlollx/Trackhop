package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * `trackhop`, the command line: a client of the API (Api.kt, ApiPlaylists.kt) of a running Trackhop.
 * On the same computer, or inside the server's container (`docker exec trackhop trackhop …`), it
 * finds it through the key that Trackhop leaves in its data folder (LocalKey.kt), with nothing to
 * set up; any other server with --url and --token (or TRACKHOP_URL and TRACKHOP_TOKEN), the token
 * made on its page, Tools › API. Plain text for people (ASCII arrows and signs: Windows consoles
 * often lack the others), --json for scripts. Exit codes: 0 done,
 * 1 failed (a sync that ended with an error, a call refused), 2 wrong use, 3 no Trackhop reached.
 */
object Cli {
    class Failure(val code: Int, message: String) : Exception(message)

    private val USAGE = """
        Usage: trackhop [--json] [--user NAME] [--url URL --token TOKEN] <command> [arguments]

        Syncs
          syncs                          list the syncs, with their last result
          show <sync>                    a sync's details and its last runs
          run <sync>... | --all [--wait] start syncs (--wait: until they end, with the result)
          enable <sync> | disable <sync> turn a sync's schedule on or off
        Services and playlists
          services                       the services and whether they are connected
          playlists <service>            a service's playlists
          tracks <service> <playlist>    a playlist's tracks
          add <service> <playlist> <track>...     add tracks: "Artist - Title", a link from any service, or an id
          remove <service> <playlist> <track>...  remove tracks, named the same way
          create <service> <name>        a new playlist
        Files and backups
          files                          the playlist files (File service)
          import <file>                  a playlist file into the File service (CSV, M3U, XSPF, JSPF, JSON, text)
          backup [--wait]                back up every playlist now
          config [<file>]                the whole set-up as one file (to stdout without <file>)
        Notifications
          notify                         the notification channels (their tokens masked)
          notify test <channel>          send a test message through a channel (its name or id)
        Other
          status                         version, syncs, what is running
          mcp                            serve Trackhop's tools to an AI assistant (MCP, over stdin/stdout)
          help                           this text

        A <sync> is its name (or the start of it) or its id; a <service> its name or id; a <playlist>
        its name or id. Without --url, trackhop talks to the Trackhop running on this computer; on a
        server shared by several people, --user NAME (or TRACKHOP_USER) picks whose, the admin's without.
    """.trimIndent()

    private val json = Json { prettyPrint = true }
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** Runs [args]; what it prints goes to [out] and [err]. Returns the exit code. */
    fun run(args: List<String>, env: Map<String, String>, out: PrintStream, err: PrintStream, dataDir: () -> File = { defaultDataDir(env) }): Int {
        val rest = ArrayList<String>()
        var asJson = false; var wait = false; var all = false
        var url = env["TRACKHOP_URL"]?.takeIf { it.isNotBlank() }; var token = env["TRACKHOP_TOKEN"]?.takeIf { it.isNotBlank() }
        var user = env["TRACKHOP_USER"]?.takeIf { it.isNotBlank() }
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--json" -> asJson = true
                "--wait" -> wait = true
                "--all" -> all = true
                "--url" -> url = args.getOrNull(++i) ?: return usage(err, "--url needs an address")
                "--token" -> token = args.getOrNull(++i) ?: return usage(err, "--token needs the API token")
                "--user" -> user = args.getOrNull(++i) ?: return usage(err, "--user needs a user name")
                "-h", "--help" -> rest.add(0, "help")
                "-V", "--version" -> { out.println("trackhop ${cliVersion()}"); return 0 }
                else -> if (a.startsWith("--")) return usage(err, "unknown option $a") else rest += a
            }
            i++
        }
        val command = rest.firstOrNull() ?: "help"
        val params = rest.drop(1)
        if (command == "help") { out.println(USAGE); return 0 }
        return try {
            val adminName = System.getenv("SONGPORT_USER")?.trim()?.takeIf { it.isNotEmpty() }?.let { MultiUser.normalize(it) }
            val name = user?.let { MultiUser.normalize(it) }?.takeIf { it != MultiUser.ADMIN && it != adminName }
            if (name != null && !MultiUser.NAME.matches(name)) return usage(err, "no user can be called \"$user\"")
            // A person's Trackhop on a shared server keeps its key in their own folder (MultiUser.kt).
            val dir: () -> File = if (name == null) dataDir else { { File(File(dataDir(), MultiUser.USERS_DIR), name) } }
            // An MCP server for an AI assistant: it reaches Trackhop at the first tool call, so the
            // assistant can start it before Trackhop; stdout carries only the protocol.
            if (command == "mcp") {
                Mcp.serveStdio(System.`in`.bufferedReader(Charsets.UTF_8), out, cliVersion()) { connect(url, token, dir) }
                return 0
            }
            val api = connect(url, token, dir)
            Commands(api, out, asJson).run(command, params, wait, all)
        } catch (f: Failure) {
            err.println("trackhop: ${f.message}")
            f.code
        }
    }

    private fun usage(err: PrintStream, problem: String): Int {
        err.println("trackhop: $problem\nSee: trackhop help")
        return 2
    }

    private fun cliVersion(): String = Cli::class.java.getResourceAsStream("/songport-version.txt")?.use { String(it.readBytes()).trim() } ?: "dev"

    /** Where Trackhop keeps its data: SONGPORT_DATA (the container sets it), else Desktop's folder. */
    fun defaultDataDir(env: Map<String, String>): File =
        env["SONGPORT_DATA"]?.takeIf { it.isNotBlank() }?.let { File(it) } ?: DesktopApp.dataDir(env)

    internal fun connect(url: String?, token: String?, dataDir: () -> File): Client {
        if (url != null) {
            if (token == null) throw Failure(2, "--url needs --token too (the API's token, made on the page: Tools › API)")
            return Client(url.trimEnd('/'), token)
        }
        val dir = dataDir()
        val (local, key) = LocalKey.read(dir)
            ?: throw Failure(3, "no Trackhop is running on this computer (nothing in ${dir.path}). Start Trackhop, or use --url and --token for a server elsewhere.")
        return Client(local, key)
    }

    /** The API at [base], with [token]. */
    class Client(private val base: String, private val token: String) {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        fun get(path: String): JsonElement = call("GET", path, null)
        fun post(path: String, body: JsonElement? = null): JsonElement = call("POST", path, body?.toString())
        fun raw(method: String, path: String, body: ByteArray?): String = send(method, path, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) }, null)

        private fun call(method: String, path: String, body: String?): JsonElement {
            val text = send(method, path, body?.let { HttpRequest.BodyPublishers.ofString(it) }, if (body != null) "application/json" else null)
            return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        }

        private fun send(method: String, path: String, body: HttpRequest.BodyPublisher?, type: String?): String {
            val req = HttpRequest.newBuilder(URI("$base/api/v1/$path")).timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer $token")
                .apply { if (type != null) header("Content-Type", type) }
                .method(method, body ?: HttpRequest.BodyPublishers.noBody()).build()
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: ConnectException) {
                throw Failure(3, "Trackhop does not answer at $base: is it running?")
            } catch (e: java.io.IOException) {
                throw Failure(3, "Trackhop at $base: ${e.message ?: e.javaClass.simpleName}")
            }
            if (res.statusCode() in 200..299) return res.body()
            val message = runCatching { Json.parseToJsonElement(res.body()).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw Failure(if (res.statusCode() == 401 || res.statusCode() == 403) 3 else 1, message ?: "HTTP ${res.statusCode()}")
        }
    }

    internal class Commands(val api: Client, val out: PrintStream, val asJson: Boolean) {
        fun run(command: String, p: List<String>, wait: Boolean, all: Boolean): Int = when (command) {
            "status" -> status()
            "syncs", "list", "ls" -> syncs()
            "show" -> show(one(p, "show <sync>"))
            "run" -> runSyncs(p, all, wait)
            "enable", "disable" -> toggle(one(p, "$command <sync>"), command == "enable")
            "services" -> services()
            "playlists" -> playlists(one(p, "playlists <service>"))
            "tracks" -> { need(p, 2, "tracks <service> <playlist>"); tracks(p[0], p[1]) }
            "add", "remove" -> { need(p, 3, "$command <service> <playlist> <track>..."); edit(command, p[0], p[1], p.drop(2)) }
            "create" -> { need(p, 2, "create <service> <name>"); create(p[0], p.drop(1).joinToString(" ")) }
            "files" -> files()
            "import" -> import(one(p, "import <file>"))
            "backup" -> backup(wait)
            "config" -> config(p.firstOrNull())
            "notify" -> notify(p)
            else -> throw Failure(2, "unknown command \"$command\". See: trackhop help")
        }

        private fun need(p: List<String>, n: Int, form: String) { if (p.size < n) throw Failure(2, "usage: trackhop $form") }
        private fun one(p: List<String>, form: String): String { need(p, 1, form); return p.joinToString(" ") }
        private fun print(e: JsonElement) = out.println(json.encodeToString(JsonElement.serializer(), e))
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
                0 -> throw Failure(1, "no sync called \"$ask\". See: trackhop syncs")
                else -> throw Failure(1, "\"$ask\" could be ${partial.joinToString(", ") { "\"${it.s("name")}\" (${it.s("id")})" }}: say which, by its name or id")
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
            return (listOf("+${r.n("added")} -${r.n("removed")}") + extra).joinToString(", ")
        }

        private fun syncs(): Int {
            val list = allSyncs()
            if (asJson) return print(JsonArray(list)).let { 0 }
            if (list.isEmpty()) out.println("No syncs yet: make one on Trackhop's page.")
            for (o in list) {
                val state = when { o.b("running") -> "running"; !o.b("enabled") -> "off"; else -> o.s("schedule") }
                out.println("${o.s("name")}  [$state]")
                out.println("  ${route(o)}")
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
            out.println("  schedule ${o.s("schedule")}${if (o.b("archive")) ", keeps every track (archive)" else if (o.b("mirrorRemovals")) ", mirrors removals" else ""}${if (!o.b("enabled")) ", off" else ""}${if (o.b("running")) ", running now" else ""}")
            (o["radio"] as? JsonObject)?.let { out.println("  listens ${it.s("window")}") }
            o.l("nextRun")?.let { out.println("  next run ${whenAt(it)}") }
            val runs = o["runs"]?.jsonArray.orEmpty()
            if (runs.isNotEmpty()) out.println("Runs:")
            for (r in runs.map { it.jsonObject }) out.println("  ${whenAt(r.l("started"))}  ${result(r)}")
            return 0
        }

        private fun runSyncs(p: List<String>, all: Boolean, wait: Boolean): Int {
            if (!all && p.isEmpty()) throw Failure(2, "usage: trackhop run <sync>... | --all [--wait]")
            val targets = if (all) allSyncs().filter { it.b("enabled") } else p.map { sync(it) }
            if (all) api.post("syncs/run-all") else targets.forEach { api.post("syncs/${enc(it.s("id"))}/run") }
            if (!wait) {
                if (asJson) print(buildJsonObject { put("started", buildJsonArray { targets.forEach { add(it.s("id")) } }) })
                else out.println("Started: ${targets.joinToString(", ") { it.s("name") }.ifEmpty { "nothing (no syncs turned on)" }}")
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
            if (done.size < targets.size) throw Failure(1, "still running after 6 hours: ${targets.filter { it.s("id") !in done }.joinToString(", ") { it.s("name") }}")
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
                ?: throw Failure(1, "no service \"$ask\" here. See: trackhop services")
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
                ?: list.filter { it.s("name").equals(ask, ignoreCase = true) }.let { if (it.size > 1) throw Failure(1, "more than one playlist is called \"$ask\": use its id (trackhop playlists $serviceId)") else it.firstOrNull() }
                ?: throw Failure(1, "no playlist \"$ask\". See: trackhop playlists $serviceId")
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
            if (!f.isFile) throw Failure(2, "no file at $path")
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
                ?: list.filter { it.s("name").equals(ask, ignoreCase = true) }.let { if (it.size > 1) throw Failure(1, "more than one channel is called \"$ask\": use its id (trackhop notify)") else it.firstOrNull() }
                ?: list.filter { it.s("name").startsWith(ask, ignoreCase = true) }.singleOrNull()
                ?: throw Failure(1, "no notification channel \"$ask\". See: trackhop notify")
        }

        private fun notify(p: List<String>): Int {
            when (p.firstOrNull()) {
                null, "list", "ls" -> {
                    val list = allChannels()
                    if (asJson) return print(JsonArray(list)).let { 0 }
                    if (list.isEmpty()) out.println("No notification channels yet: add them on Trackhop's page, Settings > Notifications.")
                    for (o in list) {
                        val events = o["events"]?.jsonArray?.mapNotNull { it.str() }.orEmpty()
                        val state = when { !o.b("ready") -> "needs its token"; o.b("enabled") -> "on"; else -> "off" }
                        out.println("${o.s("name")}  (${o.s("id")})  ${o.s("kindName")}  [$state]")
                        out.println("  when: ${events.joinToString(", ").ifEmpty { "nothing chosen" }}")
                    }
                    return 0
                }
                "test" -> {
                    val c = channel(p.drop(1).joinToString(" ").ifEmpty { throw Failure(2, "usage: trackhop notify test <channel>") })
                    val r = api.post("notifications/${enc(c.s("id"))}/test").jsonObject
                    if (asJson) print(r) else out.println("Sent a test to ${c.s("name")}: check that it arrived.")
                    return 0
                }
                else -> throw Failure(2, "usage: trackhop notify [test <channel>]")
            }
        }

        private fun config(file: String?): Int {
            val text = api.raw("GET", "config", null)
            if (file == null) out.println(text) else { File(file).writeText(text); out.println("Saved the set-up to $file") }
            return 0
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), System.getenv(), System.out, System.err))
}

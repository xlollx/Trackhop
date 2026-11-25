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
            Cli.Cmd("schedules", "Syncs", listOf(
                "schedules",
                "schedules add <name> [--every MINUTES] [--at HH:MM] [--days mon,fri]",
                "schedules edit <schedule> [--name NAME] [--every MINUTES] [--at HH:MM] [--days mon,fri]",
                "schedules rm <schedule>",
            ), "saved schedules: a timing kept once, with a name, for many syncs",
                "A sync follows one with trackhop edit <sync> --use-schedule <schedule>; when the schedule changes, every sync that follows it\n" +
                    "changes too. Deleted, its syncs keep running at the same times. --every: 60 to 10080 minutes (1440 daily, the default).",
                listOf("add", "edit", "rm")),
            Cli.Cmd("transfers", "Services and playlists", listOf(
                "transfers",
                "transfers send <file> <service>",
                "transfers rm <transfer>",
            ), "recent transfers: File playlists sent once to a service (a setlist, a pasted list)",
                "send makes a new playlist on <service> from the File playlist <file>, once: not a sync. Its songs not found are\n" +
                    "reviewed with trackhop review; each transfer leaves the list after a week (the playlist stays).",
                listOf("send", "rm")),
            Cli.Cmd("extend", "Tools", listOf("extend <service> <playlist> [--count N] [--describe TEXT] [--into-new NAME] [--wait]"),
                "more songs in a playlist's spirit from your own AI, added at its end (or into a new playlist)",
                "Your AI is the one set up on the page (Tools, Playlist generator). The names of the songs already there go to it with --describe;\n" +
                    "what it proposes is searched on the service and only the songs found are added. --into-new makes a new playlist instead."),
            Cli.Cmd("newfrom", "Services and playlists", listOf("newfrom <service> <playlist> copy|merge|split|sort|shuffle [--with PLAYLIST] [--size N] [--by artist|title|album|year|added|duration|reverse] [--name NAME] [--wait]"),
                "a new playlist on the same service from one: a copy, merged with others, split into parts, sorted or shuffled",
                "The original never changes, and nothing is searched: the songs are the service's own. --with (merge) can be given more than once."),
            Cli.Cmd("scan", "Services and playlists", listOf("scan <service>"), "a service's whole library: songs in several of your playlists, liked songs in none",
                "Read only. It reads every playlist once (on YouTube, as much quota as a full backup)."),
            Cli.Cmd("twins", "Services and playlists", listOf("twins <service>", "twins <service> keep <group> <copy> [--no-add]"),
                "the copies of one playlist on a service; keep one, delete the others",
                "keep: <group> and <copy> are numbers from the list, or the ids --json shows (they stay put when the list changes). Before deleting, the songs only the others have are added to the one\n" +
                    "kept (--no-add: not), and each deleted copy is saved in Files. A copy a sync uses is never deleted.", listOf("keep")),
            Cli.Cmd("add-link", "Services and playlists", listOf("add-link <link> <service> [<playlist>]"), "a song's link from any music app, found on a service and added to one of your playlists",
                "Without <playlist>: what was found (the song, the same recording there, the candidates). With it: the best is added."),
            Cli.Cmd("test", "Services and playlists", listOf("test <service>"), "test a service's connection: who is signed in, the playlists, one search",
                "Three calls, one after the other, each with what the service answered: the first thing to look at when a sync stops.\nExit code 1 when one failed."),
            Cli.Cmd("roast", "Tools", listOf("roast <service> <playlist> [--level gentle|spicy|savage] [--explicit-words]"), "your own AI teases a playlist",
                "Your AI is the one set up on the page (Tools, Playlist generator). Only the playlist's name and a summary of its songs go to it."),
            Cli.Cmd("vanished", "Services and playlists", listOf("vanished"), "the songs that left your playlists (from your backups and mirror syncs)"),
            Cli.Cmd("link", "Services and playlists", listOf("link <service> <playlist>"), "a playlist as a link that opens it on any service",
                "The songs travel in the link itself (trackhop.org/open), nothing is uploaded; a long playlist takes several links."),
            Cli.Cmd("overlap", "Tools", listOf("overlap <service> <service>"), "two subscriptions: how much of your music on two services is on both",
                "Your liked songs and your own playlists (up to sixty each) are read on both: it can take minutes."),
            Cli.Cmd("compat", "Tools", listOf("compat <service> <playlist> (<link> | <service> <playlist>)"), "the compatibility of two playlists: yours and a friend's (one of yours, or a public link)"),
            Cli.Cmd("listened", "Discover", listOf("listened <service> <playlist> [--at 2026-11-24T21:00] [--started]"), "mark a playlist as listened: its songs sent to Last.fm and ListenBrainz",
                "One song after the other, the last ending at --at (now without it; --started: the first starting then). Last.fm takes the last 14 days only."),
            Cli.Cmd("my-records", "Discover", listOf("my-records [wantlist] [--refresh]"), "your Discogs collection (or wantlist), with your Discogs token"),
            Cli.Cmd("venues", "Discover", listOf("venues [--nights]", "venues find <name> [--country IT]", "venues follow <id> <name>", "venues unfollow <id>"), "the venues you follow and their coming nights",
                "find gives each venue's id; follow and unfollow take it.", listOf("find", "follow", "unfollow")),
            Cli.Cmd("diary", "Discover", listOf("diary [--setlistfm-user NAME] [--refresh]"), "your concert diary from setlist.fm: the concerts you went to"),
            Cli.Cmd("shared", "Shared playlists", listOf("shared", "shared join <link> <service> [--name NAME]", "shared leave <group>"), "shared playlists this Trackhop is in as a member",
                "join takes the invite link a member sent (an encrypted ntfy channel's or a Trackhop server's mailbox; a shared folder's\n" +
                    "needs the phone) and keeps the group in a new playlist on <service>.", listOf("join", "leave")),
            Cli.Cmd("edit-many", "Syncs", listOf("edit-many <sync>... [--use-schedule NAME | --every MINUTES] [--at HH:MM] [--label NAME|none] [--changes add|mirror] [--on|--off]"),
                "change several syncs at once: their schedule, label, what they do with removals",
                "Both halves of a two-way pair, and all of a multi sync, follow. --changes leaves two-way syncs and archives as they are."),
        )

        val valued: Set<String> = setOf("by", "use-schedule", "count", "describe", "into-new", "with", "size", "by", "level", "artists", "setlistfm-user")
        val flags: Set<String> = setOf("on", "off", "no-add", "explicit-words", "started", "refresh", "nights")

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
        "schedules" -> schedules(p, o)
        "edit-many" -> editMany(p, o)
        "transfers" -> transfers(p)
        "extend" -> extend(p, o)
        "newfrom" -> newFrom(p, o)
        "scan" -> scan(p)
        "twins" -> twins(p, o)
        "add-link" -> addLink(p)
        "test" -> testService(p)
        "roast" -> roastCmd(p, o)
        "vanished" -> vanished()
        "link" -> shareLink(p)
        "overlap" -> overlap(p)
        "compat" -> compat(p)
        "listened" -> listened(p, o)
        "my-records" -> myRecords(p, o)
        "venues" -> venues(p, o)
        "diary" -> diary(o)
        "shared" -> shared(p, o)
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

    // ------------------------------------------------------------------ schedules

    private fun scheduleBody(o: Cli.Opts, name: String?) = buildJsonObject {
        (name ?: o.value("name"))?.let { put("name", it) }
        o.value("every")?.let { put("every", it.toIntOrNull() ?: throw Cli.Failure(2, "--every takes minutes, e.g. 1440")) }
        o.value("at")?.let { put("at", it) }
        o.value("days")?.let { d -> put("days", JsonArray(d.split(',').filter { it.isNotBlank() }.map { JsonPrimitive(c.day(it)) })) }
    }

    private fun schedules(p: List<String>, o: Cli.Opts): Int {
        fun show(s: JsonObject) = out.println("${s.s("name").ifEmpty { s.s("timing") }}  (${s.s("id")})  ${s.s("timing")}, ${(s["syncs"] as? JsonArray).orEmpty().size} syncs")
        val r: JsonElement = when (p.firstOrNull()) {
            null -> api.get("schedules").also { list ->
                if (!c.asJson) { val all = (list as JsonArray).map { it.jsonObject }; if (all.isEmpty()) out.println("No saved schedule: trackhop schedules add <name> --every 1440 --at 07:30"); all.forEach(::show); return 0 }
            }
            "add" -> api.post("schedules", scheduleBody(o, p.drop(1).joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop schedules add <name> [--every MINUTES] [--at HH:MM] [--days mon,fri]") }))
            "edit" -> api.send("PATCH", "schedules/${enc(p.drop(1).joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop schedules edit <schedule> [options]") })}", scheduleBody(o, null))
            "rm", "delete" -> api.send("DELETE", "schedules/${enc(p.drop(1).joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop schedules rm <schedule>") })}")
                .also { if (!c.asJson) { out.println("Deleted. Its syncs keep running at the same times."); return 0 } }
            else -> throw Cli.Failure(2, "usage: trackhop schedules [add|edit|rm]")
        }
        if (c.asJson) print(r) else show(r.jsonObject)
        return 0
    }

    private fun editMany(p: List<String>, o: Cli.Opts): Int {
        if (p.isEmpty()) throw Cli.Failure(2, "usage: trackhop edit-many <sync>... [options]")
        val ids = p.map { c.sync(it).s("id") }
        val body = buildJsonObject {
            put("syncs", JsonArray(ids.map { JsonPrimitive(it) }))
            o.value("use-schedule")?.let { put("schedule", it) }
            o.value("every")?.let { put("every", it.toIntOrNull() ?: throw Cli.Failure(2, "--every takes minutes, e.g. 1440")) }
            o.value("at")?.let { put("at", it) }
            o.value("label")?.let { put("label", if (it == "none") JsonNull else JsonPrimitive(it)) }
            o.value("changes")?.let { put("changes", it) }
            if (o.has("on")) put("enabled", true)
            if (o.has("off")) put("enabled", false)
        }
        if (body.size == 1) throw Cli.Failure(2, "say what to change: --use-schedule, --every, --at, --label, --changes, --on or --off")
        val r = api.post("syncs/bulk", body).jsonObject
        if (c.asJson) return print(r).let { 0 }
        out.println("${r.n("changed")} syncs changed.")
        r.list("syncs").forEach { out.println("  ${it.s("name")}: ${it.s("timing")}") }
        return 0
    }

    // ------------------------------------------------------------------ transfers

    private fun transfers(p: List<String>): Int {
        val r: JsonElement = when (p.firstOrNull()) {
            null -> api.get("transfers")
            "send" -> { if (p.size < 3) throw Cli.Failure(2, "usage: trackhop transfers send <file> <service>"); api.post("transfers", buildJsonObject { put("file", p[1].removeSuffix(".csv")); put("to", c.service(p[2]).s("id")) }) }
            "rm", "delete" -> { if (p.size < 2) throw Cli.Failure(2, "usage: trackhop transfers rm <transfer>"); api.send("DELETE", "transfers/${enc(p[1])}") }
            else -> throw Cli.Failure(2, "usage: trackhop transfers [send|rm]")
        }
        if (c.asJson) return print(r).let { 0 }
        when (p.firstOrNull()) {
            null -> {
                val all = (r as JsonArray).map { it.jsonObject }
                if (all.isEmpty()) out.println("No transfers in the last week.")
                all.forEach { t ->
                    val res = t["result"] as? JsonObject
                    val what = when {
                        res == null -> "sending"
                        res["error"].str() != null -> "failed: ${res.s("error")}"
                        else -> "+${res.n("added")}, ${res.n("notFound")} not found" + if (res.n("toReview") > 0) " (trackhop review ${t.s("id")})" else ""
                    }
                    out.println("${t.s("name")}  (${t.s("id")})  to ${(t["target"] as JsonObject).s("service")}: $what")
                }
            }
            "send" -> out.println("Sending ${r.jsonObject.s("name")}: trackhop transfers shows how it went.")
            else -> out.println("Taken off the list; the playlist stays.")
        }
        return 0
    }

    // ------------------------------------------------------------------ extend

    private fun extend(p: List<String>, o: Cli.Opts): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop extend <service> <playlist> [--count N] [--describe TEXT] [--into-new NAME] [--wait]")
        val s = c.service(p[0]).s("id")
        val pl = c.playlist(s, p.drop(1).joinToString(" ")).s("id")
        val task = api.post("tools/ai/extend", buildJsonObject {
            put("service", s); put("playlist", pl)
            o.value("count")?.let { put("count", it.toIntOrNull() ?: throw Cli.Failure(2, "--count takes a number")) }
            o.value("describe")?.let { put("description", it) }
            o.value("into-new")?.let { put("into", "new"); put("name", it) }
        }).jsonObject.s("task")
        if (o.has("wait")) return waitTask(task, "Asking your AI and searching the songs")
        if (c.asJson) print(buildJsonObject { put("task", task) }) else out.println("Extending: trackhop task $task")
        return 0
    }

    // ------------------------------------------------------------------ newfrom

    private fun newFrom(p: List<String>, o: Cli.Opts): Int {
        if (p.size < 3 || p.last() !in PlaylistMake.OPS) throw Cli.Failure(2, "usage: trackhop newfrom <service> <playlist> copy|merge|split|sort|shuffle [options]")
        val s = c.service(p[0]).s("id")
        val pl = c.playlist(s, p.drop(1).dropLast(1).joinToString(" ")).s("id")
        val task = api.post("services/${enc(s)}/playlists/${enc(pl)}/new", buildJsonObject {
            put("op", p.last())
            val with = o.all("with").map { c.playlist(s, it).s("id") }
            if (with.isNotEmpty()) put("with", JsonArray(with.map { JsonPrimitive(it) }))
            o.value("size")?.let { put("size", it.toIntOrNull() ?: throw Cli.Failure(2, "--size takes a number")) }
            o.value("by")?.let { put("by", it) }
            o.value("name")?.let { put("name", it) }
        }).jsonObject.s("task")
        if (o.has("wait")) return waitTask(task, "Making the playlist")
        if (c.asJson) print(buildJsonObject { put("task", task) }) else out.println("Making it: trackhop task $task")
        return 0
    }

    // ------------------------------------------------------------------ scan

    private fun scan(p: List<String>): Int {
        val s = c.service(p.joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop scan <service>") })
        if (!c.asJson) out.println("Reading every playlist of ${s.s("name")}...")
        val r = api.get("services/${enc(s.s("id"))}/scan").jsonObject
        if (c.asJson) return print(r).let { 0 }
        val repeats = r.list("repeats")
        out.println("In several playlists: ${repeats.size}")
        repeats.forEach { x -> val t = x["track"] as JsonObject; out.println("  ${t.s("artist")} - ${t.s("title")}  [${(x["playlists"] as JsonArray).joinToString(", ") { it.str().orEmpty() }}]") }
        val liked = r.list("likedInNoPlaylist")
        out.println("Liked, in no playlist: ${liked.size}")
        liked.forEach { out.println("  ${it.s("artist")} - ${it.s("title")}") }
        (r["notRead"] as? JsonArray).orEmpty().takeIf { it.isNotEmpty() }?.let { out.println("Not read: ${it.joinToString(", ") { e -> e.str().orEmpty() }}") }
        return 0
    }

    // ------------------------------------------------------------------ twins

    private fun twins(p: List<String>, o: Cli.Opts): Int {
        val s = c.service(p.firstOrNull() ?: throw Cli.Failure(2, "usage: trackhop twins <service> [keep <group> <copy>]")).s("id")
        if (!c.asJson) out.println("Reading every playlist...")
        val groups = (api.get("services/${enc(s)}/twins") as JsonArray).map { it.jsonObject }
        if (p.getOrNull(1) == "keep") {
            // By number from the list, or by the ids --json shows: an id names the same group and copy however the list changed.
            val ga = p.getOrNull(2)
            val g = byIdOrNumber(groups, "group", ga) ?: throw Cli.Failure(1, "no such group: see trackhop twins $s")
            val ca = p.getOrNull(3)
            val copy = byIdOrNumber(g.list("copies"), "id", ca) ?: throw Cli.Failure(1, "no such copy in that group")
            val r = api.post("services/${enc(s)}/twins", buildJsonObject { put("group", g.s("group")); put("keep", copy.s("id")); put("addMissing", !o.has("no-add")) }).jsonObject
            if (c.asJson) print(r) else out.println("Kept ${copy.s("name")}; ${(r["deleted"] as JsonArray).size} deleted (each saved in Files).")
            return 0
        }
        if (c.asJson) return print(JsonArray(groups)).let { 0 }
        if (groups.isEmpty()) out.println("No playlist has a copy.")
        groups.forEachIndexed { i, g ->
            out.println("${i + 1}.")
            g.list("copies").forEachIndexed { k, cp ->
                out.println("  ${k + 1}. ${cp.s("name")}  ${cp.n("tracks")} songs${if (cp.s("id") == g.s("keep")) "  (proposed to keep)" else ""}${(cp["syncs"] as JsonArray).takeIf { it.isNotEmpty() }?.let { "  used by a sync" } ?: ""}")
            }
        }
        return 0
    }

    // ------------------------------------------------------------------ add-link

    private fun addLink(p: List<String>): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop add-link <link> <service> [<playlist>]")
        val s = c.service(p[1]).s("id")
        val pl = p.drop(2).joinToString(" ").takeIf { it.isNotBlank() }?.let { c.playlist(s, it).s("id") }
        val r = api.post("tracks/link", buildJsonObject { put("link", p[0]); put("service", s); pl?.let { put("playlist", it) } }).jsonObject
        if (c.asJson) return print(r).let { 0 }
        (r["source"] as? JsonObject)?.let { out.println("The link: ${it.s("artist")} - ${it.s("title")}") }
        (r["added"] as? JsonObject)?.let { out.println("Added: ${it.s("artist")} - ${it.s("title")}"); return 0 }
        (r["exact"] as? JsonObject)?.let { out.println("The same recording: ${it.s("artist")} - ${it.s("title")}  (${it.s("id")})") }
        r.list("candidates").forEach { x -> val t = x["track"] as JsonObject; out.println("  ${x.n("score")}%  ${t.s("artist")} - ${t.s("title")}  (${t.s("id")})") }
        if (r["best"] !is JsonObject) out.println("Not found there.")
        return 0
    }

    // ------------------------------------------------------------------ test

    private fun testService(p: List<String>): Int {
        val s = c.service(p.joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop test <service>") })
        val r = api.post("services/${enc(s.s("id"))}/test").jsonObject
        if (c.asJson) print(r) else r.list("steps").forEach { st -> out.println("${if (st.b("ok")) "ok  " else "FAIL"}  ${st.s("step")}: ${st.s("detail")}") }
        return if (r.b("ok")) 0 else 1
    }

    // ------------------------------------------------------------------ roast

    private fun roastCmd(p: List<String>, o: Cli.Opts): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop roast <service> <playlist> [--level gentle|spicy|savage] [--explicit-words]")
        val s = c.service(p[0]).s("id")
        val pl = c.playlist(s, p.drop(1).joinToString(" ")).s("id")
        if (!c.asJson) out.println("Asking your AI...")
        val r = api.post("tools/roast", buildJsonObject { put("service", s); put("playlist", pl); o.value("level")?.let { put("level", it) }; if (o.has("explicit-words")) put("explicit", true) }).jsonObject
        if (c.asJson) print(r) else out.println(r.s("roast"))
        return 0
    }

    // ------------------------------------------------------------------ vanished

    private fun vanished(): Int {
        val r = api.get("vanished") as JsonArray
        if (c.asJson) return print(r).let { 0 }
        if (r.isEmpty()) out.println("No song has vanished yet (back up your playlists on a schedule: trackhop backup).")
        r.map { it.jsonObject }.forEach { v -> val t = v["track"] as JsonObject; out.println("${t.s("artist")} - ${t.s("title")}  [${v.s("where")}, last seen ${date(v.l("lastSeen"))}]") }
        return 0
    }

    // ------------------------------------------------------------------ link

    private fun shareLink(p: List<String>): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop link <service> <playlist>")
        val s = c.service(p[0]).s("id")
        val pl = c.playlist(s, p.drop(1).joinToString(" ")).s("id")
        val r = api.get("services/${enc(s)}/playlists/${enc(pl)}/link").jsonObject
        if (c.asJson) return print(r).let { 0 }
        val links = r.list("links")
        links.forEach { b -> out.println((if (links.size > 1) "${b.n("part")}/${b.n("parts")} (songs ${b.n("first")}-${b.n("last")}): " else "") + b.s("url")) }
        return 0
    }

    // ------------------------------------------------------------------ overlap

    private fun overlap(p: List<String>): Int {
        if (p.size != 2) throw Cli.Failure(2, "usage: trackhop overlap <service> <service>")
        val a = c.service(p[0]); val b = c.service(p[1])
        if (!c.asJson) out.println("Reading both libraries (it can take minutes)...")
        val r = api.post("tools/overlap", buildJsonObject { put("a", a.s("id")); put("b", b.s("id")) }).jsonObject
        if (c.asJson) return print(r).let { 0 }
        out.println("${a.s("name")}: ${r.n("songsA")} songs; ${b.s("name")}: ${r.n("songsB")} songs; on both: ${r.n("both")} (${r.n("percent")}%)")
        out.println(r.s("summary"))
        return 0
    }

    // ------------------------------------------------------------------ compat

    private fun compat(p: List<String>): Int {
        if (p.size < 3) throw Cli.Failure(2, "usage: trackhop compat <service> <playlist> (<link> | <service> <playlist>)")
        val sa = c.service(p[0]).s("id")
        val linkAt = p.indexOfFirst { it.startsWith("http://") || it.startsWith("https://") }
        val body = buildJsonObject {
            if (linkAt > 1) {
                put("a", buildJsonObject { put("service", sa); put("playlist", c.playlist(sa, p.subList(1, linkAt).joinToString(" ")).s("id")) })
                put("b", buildJsonObject { put("link", p[linkAt]) })
            } else {
                if (p.size < 4) throw Cli.Failure(2, "usage: trackhop compat <service> <playlist> <service> <playlist> (one word each, or ids)")
                val sb = c.service(p[2]).s("id")
                put("a", buildJsonObject { put("service", sa); put("playlist", c.playlist(sa, p[1]).s("id")) })
                put("b", buildJsonObject { put("service", sb); put("playlist", c.playlist(sb, p.drop(3).joinToString(" ")).s("id")) })
            }
        }
        val r = api.post("tools/compatibility", body).jsonObject
        if (c.asJson) return print(r).let { 0 }
        out.println("${r.n("score")}% compatible: ${(r["common"] as JsonArray).size} songs and ${(r["sharedArtists"] as JsonArray).size} artists in common.")
        r.list("forA").takeIf { it.isNotEmpty() }?.let { l -> out.println("For you, from theirs:"); l.forEach { out.println("  ${it.s("artist")} - ${it.s("title")}") } }
        r.list("forB").takeIf { it.isNotEmpty() }?.let { l -> out.println("For them, from yours:"); l.forEach { out.println("  ${it.s("artist")} - ${it.s("title")}") } }
        return 0
    }

    // ------------------------------------------------------------------ listened

    private fun listened(p: List<String>, o: Cli.Opts): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop listened <service> <playlist> [--at 2026-11-24T21:00] [--started]")
        val s = c.service(p[0]).s("id")
        val pl = c.playlist(s, p.drop(1).joinToString(" ")).s("id")
        val r = api.post("listens", buildJsonObject { put("service", s); put("playlist", pl); o.value("at")?.let { put("at", it) }; if (o.has("started")) put("finished", false) }).jsonObject
        if (c.asJson) print(r) else out.println(r.s("summary"))
        return 0
    }

    // ------------------------------------------------------------------ records, venues, diary

    private fun myRecords(p: List<String>, o: Cli.Opts): Int {
        val wants = p.firstOrNull() == "wantlist"
        val r = api.get("records/mine?kind=${if (wants) "wantlist" else "collection"}${if (o.has("refresh")) "&refresh=true" else ""}").jsonObject
        if (c.asJson) return print(r).let { 0 }
        val items = r.list("items")
        out.println("${r.s("user")}: ${items.size} ${if (wants) "wanted" else "records"}")
        items.forEach { out.println("  ${it.s("artist")} - ${it.s("title")}${it["year"].str()?.let { y -> " ($y)" } ?: ""}") }
        return 0
    }

    private fun venues(p: List<String>, o: Cli.Opts): Int {
        val r: JsonElement = when (p.firstOrNull()) {
            null -> api.get("venues" + if (o.has("nights")) "?nights=true" else "")
            "find" -> api.get("venues/search?q=${enc(p.drop(1).joinToString(" "))}" + (o.value("country")?.let { "&country=${enc(it)}" } ?: ""))
            "follow" -> { if (p.size < 3) throw Cli.Failure(2, "usage: trackhop venues follow <id> <name>"); api.post("venues", buildJsonObject { put("id", p[1]); put("name", p.drop(2).joinToString(" ")) }) }
            "unfollow" -> { if (p.size < 2) throw Cli.Failure(2, "usage: trackhop venues unfollow <id>"); api.send("DELETE", "venues/${enc(p[1])}") }
            else -> throw Cli.Failure(2, "usage: trackhop venues [find|follow|unfollow]")
        }
        if (c.asJson) return print(r).let { 0 }
        when (p.firstOrNull()) {
            null -> {
                val v = r.jsonObject
                v.list("venues").forEach { out.println("${it.s("name")}  (${it.s("id")})  ${it.s("city")}") }
                if (v.list("venues").isEmpty()) out.println("No venue followed: trackhop venues find <name>")
                (v["nights"] as? JsonArray)?.map { it.jsonObject }?.forEach { out.println("  ${it.s("date")}  ${it.s("artist")}  ${it.s("venue")}, ${it.s("city")}") }
            }
            "find" -> (r as JsonArray).map { it.jsonObject }.forEach { out.println("${it.s("name")}  (${it.s("id")})  ${it.s("city")} ${it.s("country")}") }
            else -> out.println("Done.")
        }
        return 0
    }

    private fun diary(o: Cli.Opts): Int {
        val r = api.get("diary?" + listOfNotNull(o.value("setlistfm-user")?.let { "user=${enc(it)}" }, "refresh=true".takeIf { o.has("refresh") }).joinToString("&")).jsonObject
        if (c.asJson) return print(r).let { 0 }
        out.println("${r.s("user")}: ${r.n("concerts")} concerts, ${r.n("artists")} artists, ${r.n("venues")} venues")
        r.list("setlists").forEach { out.println("  ${it.s("date")}  ${it.s("artist")}  ${it.s("venue")}, ${it.s("city")}") }
        return 0
    }

    // ------------------------------------------------------------------ shared

    private fun shared(p: List<String>, o: Cli.Opts): Int {
        val r: JsonElement = when (p.firstOrNull()) {
            null -> api.get("shared")
            "join" -> { if (p.size < 3) throw Cli.Failure(2, "usage: trackhop shared join <link> <service> [--name NAME]"); api.post("shared", buildJsonObject { put("link", p[1]); put("service", c.service(p[2]).s("id")); o.value("name")?.let { put("name", it) } }) }
            "leave" -> { if (p.size < 2) throw Cli.Failure(2, "usage: trackhop shared leave <group>"); api.send("DELETE", "shared/${enc(p[1])}") }
            else -> throw Cli.Failure(2, "usage: trackhop shared [join|leave]")
        }
        if (c.asJson) return print(r).let { 0 }
        when (p.firstOrNull()) {
            null -> { val all = (r as JsonArray).map { it.jsonObject }; if (all.isEmpty()) out.println("In no shared playlist: trackhop shared join <link> <service>"); all.forEach { g -> out.println("${g.s("name")}  (${g.s("id")})  ${g.list("members").joinToString(", ") { it.s("name") }}") } }
            "join" -> out.println("Joined ${r.jsonObject.s("name")}: its songs are coming.")
            else -> out.println("Left. Your playlist stays as it is.")
        }
        return 0
    }
}

/**
 * The item of [items] whose [key] is exactly [arg], else the one at that number (from 1) in the list:
 * an id first, so a copy whose id is "1" is never taken for the first one in the list.
 */
internal fun byIdOrNumber(items: List<JsonObject>, key: String, arg: String?): JsonObject? {
    if (arg == null) return null
    return items.firstOrNull { (it[key] as? JsonPrimitive)?.contentOrNull == arg }
        ?: arg.toIntOrNull()?.let { items.getOrNull(it - 1) }
}

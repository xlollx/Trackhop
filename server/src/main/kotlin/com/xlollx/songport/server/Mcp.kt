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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Trackhop as an MCP server (Model Context Protocol), so an AI assistant (Claude Desktop, Claude
 * Code, any MCP client) can list the syncs, run them, read and edit playlists, on the user's word.
 *
 * Two ways in, one set of tools:
 * - `trackhop mcp`: over stdin/stdout, started by the assistant on the same computer; it reaches the
 *   running Trackhop as the command line does (the local key, or --url and --token);
 * - `POST /mcp` on the server (Docker, Desktop): the Streamable HTTP transport, with the API's token
 *   (Tools › API) as `Authorization: Bearer …`, answered in plain JSON.
 *
 * Every tool is a command of the command line ([CliCommands]) run with JSON output: the same names
 * accepted, the same checks, the same errors. Nothing here reaches a service the API does not.
 */
object Mcp {
    /** The protocol versions this server speaks, newest first. */
    val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    /** Tool output beyond this is cut, with a note: an assistant's context is not a file. */
    private const val MAX_TEXT = 200_000

    private class Tool(
        val name: String,
        val title: String,
        val description: String,
        val params: List<Param>,
        val readOnly: Boolean,
        val destructive: Boolean = false,
        /** The command line's words for a call with these arguments. */
        val command: (JsonObject) -> Call,
    )

    private data class Param(val name: String, val type: String, val description: String, val required: Boolean = true)

    /** A command of the command line: its name, its words, its switches (--wait, --all…) and its options (--name…). */
    class Call(
        val command: String,
        val params: List<String> = emptyList(),
        val wait: Boolean = false,
        val all: Boolean = false,
        val flags: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
        /** Options given more than once (--pattern P --pattern Q). */
        val multi: Map<String, List<String>> = emptyMap(),
    ) {
        val opts: Cli.Opts get() = Cli.Opts(flags + setOfNotNull("wait".takeIf { wait }, "all".takeIf { all }), values.mapValues { listOf(it.value) } + multi)
    }

    private val SYNC = Param("sync", "string", "The sync's name (or the start of it) or its id, as list_syncs shows them.")
    private val SERVICE = Param("service", "string", "The service's name or id, as list_services shows them (for example \"Spotify\" or \"spotify\").")
    private val PLAYLIST = Param("playlist", "string", "The playlist's name or id, as list_playlists shows them.")
    private val TRACKS = Param("tracks", "array", "Each track as \"Artist - Title\", a link to it on any service, or its id on this service.")
    private val RUN = Param("run", "string", "A run's id (list_runs); left out, the newest run with something to review.", required = false)
    private val WAIT = Param("wait", "boolean", "Wait until it ends and answer with the result (it can take minutes). Default false: answer at once.", required = false)

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: throw Cli.Failure(2, "missing \"$key\"")

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    /** An optional argument as text (a number or a flag given as JSON too), or null. */
    private fun JsonObject.opt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** The options of a call that the command line takes as --name value, from the same-named arguments. */
    private fun JsonObject.values(vararg keys: String): Map<String, String> = keys.mapNotNull { k -> opt(k)?.let { k to it } }.toMap()

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()

    private fun JsonObject.list(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() } ?: throw Cli.Failure(2, "\"$key\" needs at least one track")

    private val SYNC_OPTIONS = listOf(
        Param("name", "string", "The sync's name.", required = false),
        Param("schedule", "string", "manual, hourly, every_6h, daily or weekly.", required = false),
        Param("every", "integer", "Instead of schedule: minutes between runs (60 to 10080).", required = false),
        Param("at", "string", "For daily and weekly: the time, HH:MM.", required = false),
        Param("days", "string", "Days of the week, e.g. \"mon,fri\".", required = false),
        Param("changes", "string", "add (only add), mirror (also remove what leaves the source) or archive (keep every track ever seen).", required = false),
        Param("follows", "boolean", "With mirror: the source changes by itself (a daylist), its big removals need no confirmation.", required = false),
        Param("color", "string", "A colour: red, orange, yellow, green, teal, blue, purple, pink, #RRGGBB, or none.", required = false),
        Param("label", "string", "The sync's label: a label's name or id (the card takes its colour), or none.", required = false),
        Param("tags", "string", "Tags to add or change, as \"genre=metal, mood=gym\"; a key alone (\"kids\") is a tag too.", required = false),
        Param("untag", "string", "Tag keys to take off, e.g. \"genre, mood\" (edit_sync).", required = false),
        Param("favorite", "boolean", "true to star the sync (shown first), false to take the star off.", required = false),
        Param("two_way", "boolean", "true: also make the reverse sync (marked ↔) between two existing playlists of the user's, so what is added on either side reaches the other; false (edit_sync): delete the reverse sync.", required = false),
        Param("two_way_removals", "boolean", "With two_way: a track removed on one side is removed on the other too (false: a pair only adds).", required = false),
    )

    private val GROUP = listOf(
        Param("tag", "string", "Only the syncs with these tags: \"genre=metal\", a key alone (\"gym\"), or several split by commas (all wanted).", required = false),
        Param("label", "string", "Only the syncs wearing this label: its name, its id or a colour.", required = false),
        Param("favorite", "boolean", "true: only the starred syncs.", required = false),
    )

    private fun String.parts(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The group a call names (tag, label, favorite), as the command line's options. */
    private fun JsonObject.group(wait: Boolean = false, command: String): Call {
        val tags = opt("tag")?.parts().orEmpty()
        return Call(command, wait = wait, flags = setOfNotNull("favorite".takeIf { flag("favorite") }), values = values("label"), multi = if (tags.isEmpty()) emptyMap() else mapOf("tag" to tags))
    }

    private val TOOLS = listOf(
        Tool("status", "Trackhop status", "Trackhop's version, how many syncs it has, which are running now and when the last one ran.",
            emptyList(), readOnly = true) { Call("status") },
        Tool("list_syncs", "List syncs", "Every sync with its source and target playlists, its schedule, whether it is on, its label, tags and star, and its last result (added, removed, not found, to check). With tag, label or favorite: only those syncs.",
            GROUP, readOnly = true) { a -> a.group(command = "syncs") },
        Tool("get_sync", "Show a sync", "One sync's details and its last runs.",
            listOf(SYNC), readOnly = true) { a -> Call("show", listOf(a.text("sync"))) },
        Tool("run_sync", "Run a sync", "Starts a sync now. With wait, answers when it ends, with what it added, removed and could not find. A result with heldRemovals kept that many removals back: show them to the user with preview_sync, and only if they say so run again with confirm_removals.",
            listOf(SYNC, WAIT, Param("confirm_removals", "boolean", "true: this run makes the removals a run holds back (a quarter or more of the destination). Ask the user first, after preview_sync.", required = false)), readOnly = false) { a ->
            Call("run", listOf(a.text("sync")), wait = a.flag("wait"), flags = setOfNotNull("confirm-removals".takeIf { a.flag("confirm_removals") }))
        },
        Tool("run_syncs_by_tag", "Run a group of syncs", "Starts the syncs with a tag (\"the playlists tagged gym\"), wearing a label, or starred; only those turned on. Give at least one of tag, label, favorite.",
            GROUP + WAIT, readOnly = false) { a ->
            if (a.opt("tag") == null && a.opt("label") == null && !a.flag("favorite")) throw Cli.Failure(2, "say which syncs: \"tag\", \"label\" or \"favorite\"")
            a.group(wait = a.flag("wait"), command = "run")
        },
        Tool("run_all_syncs", "Run all syncs", "Starts every sync that is turned on.",
            listOf(WAIT), readOnly = false) { a -> Call("run", wait = a.flag("wait"), all = true) },
        Tool("set_sync_enabled", "Turn a sync on or off", "Turns a sync's schedule on or off (the sync itself is kept).",
            listOf(SYNC, Param("enabled", "boolean", "true to turn it on, false to turn it off.")), readOnly = false) { a ->
            val on = (a["enabled"] as? JsonPrimitive)?.booleanOrNull ?: throw Cli.Failure(2, "missing \"enabled\"")
            Call(if (on) "enable" else "disable", listOf(a.text("sync")))
        },
        Tool("list_services", "List services", "The music services added to Trackhop and whether each is connected.",
            emptyList(), readOnly = true) { Call("services") },
        Tool("list_playlists", "List playlists", "A service's playlists (and its liked songs), with their ids and sizes.",
            listOf(SERVICE), readOnly = true) { a -> Call("playlists", listOf(a.text("service"))) },
        Tool("get_playlist_tracks", "Read a playlist", "A playlist's tracks: title, artists, album, duration and ISRC where known.",
            listOf(SERVICE, PLAYLIST), readOnly = true) { a -> Call("tracks", listOf(a.text("service"), a.text("playlist"))) },
        Tool("create_playlist", "Create a playlist", "A new, empty playlist on a service.",
            listOf(SERVICE, Param("name", "string", "The new playlist's name.")), readOnly = false) { a ->
            Call("create", listOf(a.text("service"), a.text("name")))
        },
        Tool("add_tracks", "Add tracks", "Finds tracks on the service and adds them to a playlist; says which were already there or not found.",
            listOf(SERVICE, PLAYLIST, TRACKS), readOnly = false) { a ->
            Call("add", listOf(a.text("service"), a.text("playlist")) + a.list("tracks"))
        },
        Tool("remove_tracks", "Remove tracks", "Removes tracks from a playlist on the service. Ask the user first: this changes their playlist.",
            listOf(SERVICE, PLAYLIST, TRACKS), readOnly = false, destructive = true) { a ->
            Call("remove", listOf(a.text("service"), a.text("playlist")) + a.list("tracks"))
        },
        Tool("backup_playlists", "Back up playlists", "Backs up every playlist of every connected service now, as files in Trackhop.",
            listOf(WAIT), readOnly = false) { a -> Call("backup", wait = a.flag("wait")) },
        Tool("create_sync", "Make a sync", "Makes a sync from a playlist (or a link to a public one) to a playlist; without target_playlist, a new playlist is made at the first run.",
            listOf(
                Param("source_service", "string", "The source's service, as list_services shows it."),
                Param("source_playlist", "string", "The source playlist's name or id (list_playlists), or a link to it."),
                Param("target_service", "string", "The destination's service."),
                Param("target_playlist", "string", "The destination playlist's name or id; leave out for a new playlist.", required = false),
            ) + SYNC_OPTIONS, readOnly = false) { a ->
            Call("new", listOfNotNull(a.text("source_service"), a.text("source_playlist"), a.text("target_service"), a.opt("target_playlist")), values = a.syncValues(), flags = a.syncFlags(), multi = a.syncMulti())
        },
        Tool("edit_sync", "Change a sync", "Changes a sync's name, timing, what it does with removals, colour, label, tags or star, and whether it is two-way. Only what is given changes.",
            listOf(SYNC) + SYNC_OPTIONS, readOnly = false) { a -> Call("edit", listOf(a.text("sync")), values = a.syncValues(), flags = a.syncFlags(), multi = a.syncMulti()) },
        Tool("delete_sync", "Delete a sync", "Deletes a sync (its playlists stay as they are). Ask the user first.",
            listOf(SYNC), readOnly = false, destructive = true) { a -> Call("delete", listOf(a.text("sync"))) },
        Tool("move_sync", "Move a sync in the list", "Moves a sync in the list (the order of Run all and of the widgets).",
            listOf(SYNC, Param("to", "string", "A position from 1, or up, down, top, bottom.")), readOnly = false) { a -> Call("move", listOf(a.text("sync"), a.text("to"))) },
        Tool("stop_sync", "Stop a running sync", "Stops a sync that is running now.", listOf(SYNC), readOnly = false) { a -> Call("stop", listOf(a.text("sync"))) },
        Tool("pause_sync", "Pause a running sync", "Pauses a sync that is running now.", listOf(SYNC), readOnly = false) { a -> Call("pause", listOf(a.text("sync"))) },
        Tool("resume_sync", "Resume a paused sync", "Resumes a paused sync.", listOf(SYNC), readOnly = false) { a -> Call("resume", listOf(a.text("sync"))) },
        Tool("preview_sync", "Preview a sync", "What a run of the sync would add, remove, not find and want checked now, without changing anything.",
            listOf(SYNC), readOnly = true) { a -> Call("preview", listOf(a.text("sync"))) },
        Tool("list_runs", "List runs", "The runs, newest first: every sync's, or one's.",
            listOf(SYNC.copy(required = false), Param("limit", "integer", "How many (default 50).", required = false), Param("offset", "integer", "From which (default 0).", required = false)), readOnly = true) { a ->
            Call("runs", listOfNotNull(a.opt("sync")), values = a.values("limit", "offset"))
        },
        Tool("get_run", "Read a run", "One run: the tracks it added, removed, could not find, wants checked, and its notes.",
            listOf(Param("run", "string", "The run's id, as list_runs shows it.")), readOnly = true) { a -> Call("report", listOf(a.text("run"))) },
        Tool("get_review", "Read a run's review", "The tracks a sync's run could not find, the matches to check and the tracks it removed, with their ids.",
            listOf(SYNC, RUN), readOnly = true) { a -> Call("review", listOf(a.text("sync")), values = a.values("run")) },
        Tool("review_tracks", "Review tracks", "Acts on a run's review: ignore tracks not found, keep or discard matches to check, pick the track for one not found, replace a match, or restore what the run removed. Ask the user first.",
            listOf(
                SYNC,
                Param("action", "string", "ignore, keep, discard, pick, replace or restore."),
                Param("tracks", "array", "The rows: their ids from get_review, or [\"all\"]. One for pick and replace; none for restore.", required = false),
                Param("choice", "string", "For pick and replace: the right track, as \"Artist - Title\", a link, or its id on the destination.", required = false),
                RUN,
            ), readOnly = false, destructive = true) { a ->
            val action = a.text("action")
            val rows = a.strings("tracks")
            val words = when (action) {
                "pick", "replace" -> rows.take(1) + listOfNotNull(a.opt("choice") ?: throw Cli.Failure(2, "missing \"choice\""))
                else -> rows
            }
            Call("review", listOf(a.text("sync"), action) + words, values = a.values("run"))
        },
        Tool("search_tracks", "Search a service", "Looks for a track on a service: the candidates with their ids and how well each matches.",
            listOf(SERVICE, Param("query", "string", "\"Artist - Title\", or a link from any service."), Param("limit", "integer", "How many (default 20).", required = false)), readOnly = true) { a ->
            Call("search", listOf(a.text("service"), a.text("query")), values = a.values("limit"))
        },
        Tool("convert_tracks", "Find tracks on another service", "The same tracks on another service, as a sync would match them: their ids there and how sure each match is.",
            listOf(SERVICE, TRACKS), readOnly = true) { a -> Call("convert", listOf(a.text("service")) + a.list("tracks")) },
        Tool("find_duplicates", "Find duplicates", "The tracks a playlist holds more than once.",
            listOf(SERVICE, PLAYLIST), readOnly = true) { a -> Call("duplicates", listOf(a.text("service"), a.text("playlist"))) },
        Tool("find_untagged_songs", "Songs without MusicBrainz tags", "The songs on the user's Navidrome, Jellyfin, Emby or Plex server whose files carry no MusicBrainz tags (a sync finds those by title only). Reads the whole library: it can take a while. Nothing is changed.",
            listOf(SERVICE.copy(required = false, description = "The server's name or id (list_services); left out when only one such server is connected.")), readOnly = true) { a -> Call("mbtags", listOfNotNull(a.opt("service"))) },
        Tool("list_missing_on_server", "Tracks missing on my server", "The tracks of the user's streaming playlists that their own server (Navidrome, Jellyfin, Emby, Plex, Lyrion, Mopidy) does not have, each once: artist, title, album, length, ISRC, MusicBrainz id where known, the syncs that miss each and when it was first seen. Made from the syncs' last runs: nothing is searched, downloaded or bought.",
            listOf(
                SYNC.copy(required = false, description = "Only what this sync misses (its name or id)."),
                SERVICE.copy(required = false, description = "Only tracks of syncs from or to this service (its id, e.g. spotify or jellyfin)."),
                Param("since", "string", "Only entries first seen since: 24h, 7d, 2w or a date like 2026-11-01.", required = false),
                Param("format", "string", "json (default), csv, m3u, text or lidarr (Lidarr's Custom List: artists with a MusicBrainz id).", required = false),
                Param("albums", "boolean", "Grouped by album.", required = false),
            ), readOnly = true) { a -> Call("wanted", values = a.values("sync", "service", "since", "format"), flags = setOfNotNull("albums".takeIf { a.flag("albums") })) },
        Tool("remove_duplicates", "Remove duplicates", "Removes a playlist's extra copies (the first of each stays). Ask the user first.",
            listOf(SERVICE, PLAYLIST, WAIT), readOnly = false, destructive = true) { a ->
            Call("duplicates", listOf(a.text("service"), a.text("playlist")), wait = a.flag("wait"), flags = setOf("remove"))
        },
        Tool("export_playlist", "Export a playlist", "A playlist as a file's text: csv, m3u, xspf, jspf or txt.",
            listOf(SERVICE, PLAYLIST, Param("format", "string", "csv (default), m3u, xspf, jspf or txt.", required = false)), readOnly = true) { a ->
            Call("export", listOf(a.text("service"), a.text("playlist")), values = a.values("format"))
        },
        Tool("list_files", "List playlist files", "The File playlists kept in Trackhop (imported files and backups).", emptyList(), readOnly = true) { Call("files") },
        Tool("delete_files", "Delete playlist files", "Deletes File playlists. Ask the user first.",
            listOf(Param("files", "array", "The files' names, as list_files shows them.")), readOnly = false, destructive = true) { a ->
            Call("files", listOf("rm") + a.list("files"))
        },
        Tool("get_file_patterns", "Read the name patterns", "How track names in imported files are read: the layout and the user's own patterns.", emptyList(), readOnly = true) { Call("patterns") },
        Tool("set_file_patterns", "Set the name patterns", "Sets the user's own patterns for names in imported files, e.g. {artist}/{album}/{track} {title}; they are tried in order.",
            listOf(Param("patterns", "array", "The patterns, one per entry.")), readOnly = false) { a -> Call("patterns", listOf("set") + a.list("patterns")) },
        Tool("try_file_patterns", "Try name patterns", "Reads a line or a path from a playlist file with the saved patterns, or with the ones given; saves nothing.",
            listOf(Param("sample", "string", "A line or a path from a playlist file."), Param("patterns", "array", "Patterns to try instead of the saved ones.", required = false)), readOnly = true) { a ->
            Call("patterns", listOf("try", a.text("sample")), multi = mapOf("pattern" to a.strings("patterns")).filterValues { it.isNotEmpty() })
        },
        Tool("get_stats", "Read the numbers", "What the syncs did: tracks moved, time saved, busiest route, biggest playlist, top artists.", emptyList(), readOnly = true) { Call("stats") },
        Tool("get_settings", "Read the settings", "The settings Trackhop's page offers.", emptyList(), readOnly = true) { Call("settings") },
        Tool("change_setting", "Change a setting", "Changes one setting: theme (system, light, dark), backupSchedule (manual, daily, weekly), backupFolder, updatesCheck (true, false).",
            listOf(Param("key", "string", "The setting's name."), Param("value", "string", "Its new value.")), readOnly = false) { a ->
            Call("settings", listOf("set", a.text("key"), a.text("value")))
        },
        Tool("list_notification_channels", "List notification channels", "The channels Trackhop tells about syncs (their tokens masked).", emptyList(), readOnly = true) { Call("notify") },
        Tool("test_notification", "Send a test notification", "Sends a test message through a channel.",
            listOf(Param("channel", "string", "The channel's name or id.")), readOnly = false) { a -> Call("notify", listOf("test", a.text("channel"))) },
        Tool("list_tasks", "List background tasks", "Backups and other long tasks, running or ended in the last hour.", emptyList(), readOnly = true) { Call("tasks") },
        Tool("get_task", "Read a background task", "One background task: its progress, whether it is done, its error.",
            listOf(Param("task", "string", "The task's id.")), readOnly = true) { a -> Call("task", listOf(a.text("task"))) },
    )

    private fun JsonObject.syncValues() = values("name", "schedule", "every", "at", "days", "changes", "color", "label")
    private fun JsonObject.syncFlags() = setOfNotNull(
        "follows".takeIf { flag("follows") },
        (this["favorite"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) "favorite" else "unfavorite" },
        (this["two_way"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) "two-way" else "one-way" },
        (this["two_way_removals"] as? JsonPrimitive)?.booleanOrNull?.let { if (it) "two-way-removals" else "no-two-way-removals" },
    )
    private fun JsonObject.syncMulti() = listOfNotNull(
        opt("tags")?.parts()?.takeIf { it.isNotEmpty() }?.let { "tag" to it },
        opt("untag")?.parts()?.takeIf { it.isNotEmpty() }?.let { "untag" to it },
    ).toMap()

    private fun schema(t: Tool) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            t.params.forEach { p ->
                putJsonObject(p.name) {
                    put("type", p.type)
                    put("description", p.description)
                    if (p.type == "array") putJsonObject("items") { put("type", "string") }
                }
            }
        }
        putJsonArray("required") { t.params.filter { it.required }.forEach { add(it.name) } }
        put("additionalProperties", false)
    }

    private fun toolList() = buildJsonObject {
        putJsonArray("tools") {
            TOOLS.forEach { t ->
                add(buildJsonObject {
                    put("name", t.name)
                    put("title", t.title)
                    put("description", t.description)
                    put("inputSchema", schema(t))
                    putJsonObject("annotations") {
                        put("title", t.title)
                        put("readOnlyHint", t.readOnly)
                        if (!t.readOnly) put("destructiveHint", t.destructive)
                        put("openWorldHint", true)
                    }
                })
            }
        }
    }

    /** Runs [call] through the command line's commands against [api]: its JSON output, or why it failed. */
    fun run(api: Cli.Client, call: Call): Pair<Boolean, String> {
        val buffer = ByteArrayOutputStream()
        val out = PrintStream(buffer, true, Charsets.UTF_8)
        return try {
            val code = CliCommands(api, out, asJson = true).run(call.command, call.params, call.opts)
            val text = buffer.toString(Charsets.UTF_8).trim().ifEmpty { "{}" }
            (code == 0) to text
        } catch (f: Cli.Failure) {
            false to (f.message ?: "failed")
        }
    }

    private fun result(ok: Boolean, text: String) = buildJsonObject {
        val shown = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "\n… (cut: ${text.length} characters in all; ask for less, e.g. one playlist)" else text
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", shown) }) }
        put("isError", !ok)
    }

    private fun error(id: JsonElement?, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    private fun reply(id: JsonElement?, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }

    /**
     * Answers one JSON-RPC message; null for a notification (no id), which gets no answer.
     * [api] is asked for the client to the running Trackhop only when a tool is called.
     */
    fun handle(message: JsonElement, version: String, api: () -> Cli.Client): JsonObject? {
        val m = message as? JsonObject ?: return error(null, -32600, "Invalid request")
        val id = m["id"]
        val method = m["method"]?.jsonPrimitive?.contentOrNull ?: return if (id == null) null else error(id, -32600, "Invalid request")
        if (id == null || id is JsonNull) return null
        val params = m["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> {
                val asked = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
                reply(id, buildJsonObject {
                    put("protocolVersion", asked?.takeIf { it in VERSIONS } ?: VERSIONS.first())
                    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                    putJsonObject("serverInfo") { put("name", "trackhop"); put("title", "Trackhop"); put("version", version) }
                    put("instructions", "Trackhop keeps music playlists in sync between services (Spotify, YouTube Music, Apple Music, TIDAL, Deezer, Jellyfin, Plex and more). " +
                        "Use list_syncs and list_services first to learn the names. Ask the user before remove_tracks or before running many syncs.")
                })
            }
            "ping" -> reply(id, JsonObject(emptyMap()))
            "tools/list" -> reply(id, toolList())
            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32602, "Missing tool name")
                val tool = TOOLS.firstOrNull { it.name == name } ?: return error(id, -32602, "Unknown tool: $name")
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val call = try { tool.command(args) } catch (f: Cli.Failure) { return reply(id, result(false, f.message ?: "wrong arguments")) }
                val (ok, text) = try { run(api(), call) } catch (f: Cli.Failure) { false to (f.message ?: "failed") }
                reply(id, result(ok, text))
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    /** A message, or a batch of them: the answers (one array for a batch), or null when none is due. */
    fun handleAny(text: String, version: String, api: () -> Cli.Client): JsonElement? {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrElse { return error(null, -32700, "Parse error") }
        return if (parsed is JsonArray) {
            parsed.mapNotNull { handle(it, version, api) }.takeIf { it.isNotEmpty() }?.let { JsonArray(it) }
        } else handle(parsed, version, api)
    }

    /** `trackhop mcp`: one JSON-RPC message per line on [input], each answer as one line on [output]. */
    fun serveStdio(input: BufferedReader, output: PrintStream, version: String, api: () -> Cli.Client) {
        // Connected once, at the first tool call: an assistant may start the server before Trackhop.
        var client: Cli.Client? = null
        val lazyApi = { client ?: api().also { client = it } }
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val answer = handleAny(line, version, lazyApi) ?: continue
            output.println(answer.toString())
            output.flush()
        }
    }

    /** For tests and the docs: the tools' names. */
    val toolNames: List<String> get() = TOOLS.map { it.name }

    /** The arguments a tool call turns into, for tests. */
    internal fun callFor(tool: String, args: JsonObject): Call = TOOLS.first { it.name == tool }.command(args)
}

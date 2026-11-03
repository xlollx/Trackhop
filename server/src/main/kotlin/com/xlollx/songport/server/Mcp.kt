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
 * Every tool is a command of the command line ([Cli.Commands]) run with JSON output: the same names
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

    private class Param(val name: String, val type: String, val description: String, val required: Boolean = true)

    /** A command of the command line: its name, its words, and the --wait / --all switches. */
    class Call(val command: String, val params: List<String> = emptyList(), val wait: Boolean = false, val all: Boolean = false)

    private val SYNC = Param("sync", "string", "The sync's name (or the start of it) or its id, as list_syncs shows them.")
    private val SERVICE = Param("service", "string", "The service's name or id, as list_services shows them (for example \"Spotify\" or \"spotify\").")
    private val PLAYLIST = Param("playlist", "string", "The playlist's name or id, as list_playlists shows them.")
    private val TRACKS = Param("tracks", "array", "Each track as \"Artist - Title\", a link to it on any service, or its id on this service.")
    private val WAIT = Param("wait", "boolean", "Wait until it ends and answer with the result (it can take minutes). Default false: answer at once.", required = false)

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: throw Cli.Failure(2, "missing \"$key\"")

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.list(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() } ?: throw Cli.Failure(2, "\"$key\" needs at least one track")

    private val TOOLS = listOf(
        Tool("status", "Trackhop status", "Trackhop's version, how many syncs it has, which are running now and when the last one ran.",
            emptyList(), readOnly = true) { Call("status") },
        Tool("list_syncs", "List syncs", "Every sync with its source and target playlists, its schedule, whether it is on, and its last result (added, removed, not found, to check).",
            emptyList(), readOnly = true) { Call("syncs") },
        Tool("get_sync", "Show a sync", "One sync's details and its last runs.",
            listOf(SYNC), readOnly = true) { a -> Call("show", listOf(a.text("sync"))) },
        Tool("run_sync", "Run a sync", "Starts a sync now. With wait, answers when it ends, with what it added, removed and could not find.",
            listOf(SYNC, WAIT), readOnly = false) { a -> Call("run", listOf(a.text("sync")), wait = a.flag("wait")) },
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
    )

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
            val code = Cli.Commands(api, out, asJson = true).run(call.command, call.params, call.wait, call.all)
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

package com.xlollx.songport.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * GET /api/v1/openapi.json: every call of the API, as an OpenAPI 3 description, for the tools that
 * read one (Home Assistant's RESTful integrations, n8n, Postman, a generated client). Written by hand
 * from [CALLS], one line per call, so a new call is one more line here; the guide lists the same.
 */
internal object ApiDocs {
    class Call(val method: String, val path: String, val tag: String, val summary: String, val query: List<String> = emptyList(), val body: String? = null)

    val CALLS = listOf(
        Call("GET", "/status", "General", "Version, how many syncs, which are running, the last run."),
        Call("GET", "/metrics", "General", "The syncs as Prometheus metrics (text)."),
        Call("GET", "/stats", "General", "Your numbers: tracks moved, time saved, busiest route, top artists."),
        Call("GET", "/tasks", "General", "The background tasks (backups, duplicates, lyrics), running or ended in the last hour."),
        Call("GET", "/tasks/{task}", "General", "One background task: its progress, whether it is done, its error."),
        Call("GET", "/log", "General", "The server's last log lines (admin only).", listOf("lines")),
        Call("GET", "/syncs", "Syncs", "Every sync with its source, target, timing and last result."),
        Call("POST", "/syncs", "Syncs", "Make a sync.", body = "{\"source\": {\"service\", \"playlist\" | \"link\"}, \"target\": {\"service\", \"playlist\"?}, \"name\"?, \"schedule\"? | \"every\"?, \"at\"?, \"days\"?, \"changes\"? (add, mirror, archive), \"follows\"?, \"color\"?}"),
        Call("POST", "/syncs/run-all", "Syncs", "Start every sync that is turned on."),
        Call("GET", "/syncs/{sync}", "Syncs", "One sync and its last runs."),
        Call("PATCH", "/syncs/{sync}", "Syncs", "Change a sync: any of name, enabled, schedule, every, at, days, changes, follows, color.", body = "{\"name\"?, \"enabled\"?, \"schedule\"?, \"every\"?, \"at\"?, \"days\"?, \"changes\"?, \"follows\"?, \"color\"?}"),
        Call("DELETE", "/syncs/{sync}", "Syncs", "Delete a sync (the playlists stay as they are)."),
        Call("POST", "/syncs/{sync}/run", "Syncs", "Start a sync now; anyway=true after a run held back by the target's size check.", listOf("anyway")),
        Call("POST", "/syncs/{sync}/stop", "Syncs", "Stop a running sync."),
        Call("POST", "/syncs/{sync}/pause", "Syncs", "Pause a running sync."),
        Call("POST", "/syncs/{sync}/resume", "Syncs", "Resume a paused sync."),
        Call("POST", "/syncs/{sync}/enable", "Syncs", "Turn a sync's schedule on."),
        Call("POST", "/syncs/{sync}/disable", "Syncs", "Turn a sync's schedule off."),
        Call("POST", "/syncs/{sync}/move", "Syncs", "Move a sync in the list: a position from 1, or up, down, top, bottom.", body = "{\"to\": 3 | \"up\" | \"down\" | \"top\" | \"bottom\"}"),
        Call("GET", "/syncs/{sync}/preview", "Syncs", "What a run would do now, without changing anything."),
        Call("GET", "/syncs/{sync}/runs", "Runs", "A sync's runs, newest first.", listOf("limit", "offset")),
        Call("GET", "/runs", "Runs", "Every run, newest first; sync= for one sync's.", listOf("sync", "limit", "offset")),
        Call("GET", "/runs/{run}", "Runs", "One run with its tracks (added, removed, not found, to check) and its notes."),
        Call("GET", "/runs/{run}/review", "Runs", "A run's review: not found, to check, removed."),
        Call("POST", "/runs/{run}/review", "Runs", "Review a run: ignore, keep, discard, pick, replace or restore.", body = "{\"action\", \"tracks\": [source ids] | \"all\", \"choice\"?: track}"),
        Call("GET", "/services", "Services", "The services added, and whether each is connected."),
        Call("GET", "/services/{service}", "Services", "One service: connected, account, and what it can do."),
        Call("GET", "/services/{service}/search", "Services", "Look for a track: q is \"Artist - Title\" or a link.", listOf("q", "limit")),
        Call("GET", "/services/{service}/playlists", "Playlists", "A service's playlists and library entries."),
        Call("POST", "/services/{service}/playlists", "Playlists", "Make a playlist.", body = "{\"name\", \"description\"?}"),
        Call("PATCH", "/services/{service}/playlists/{playlist}", "Playlists", "Rename a playlist.", body = "{\"name\"}"),
        Call("DELETE", "/services/{service}/playlists/{playlist}", "Playlists", "Delete a playlist."),
        Call("GET", "/services/{service}/playlists/{playlist}/tracks", "Playlists", "A playlist's tracks."),
        Call("POST", "/services/{service}/playlists/{playlist}/tracks", "Playlists", "Add tracks (by title and artist, link or id).", body = "{\"tracks\": [\"Artist - Title\" | link | {\"title\", \"artist\"} | {\"id\"}], \"allowDuplicates\"?}"),
        Call("DELETE", "/services/{service}/playlists/{playlist}/tracks", "Playlists", "Remove tracks.", body = "{\"tracks\": […]}"),
        Call("POST", "/services/{service}/playlists/{playlist}/tracks/remove", "Playlists", "Remove tracks (for tools that cannot send a body with DELETE).", body = "{\"tracks\": […]}"),
        Call("GET", "/services/{service}/playlists/{playlist}/duplicates", "Playlists", "The tracks a playlist holds more than once."),
        Call("POST", "/services/{service}/playlists/{playlist}/duplicates/remove", "Playlists", "Remove the extra copies (a background task)."),
        Call("GET", "/services/{service}/playlists/{playlist}/export", "Playlists", "A playlist as a file: csv, m3u, xspf, jspf or txt.", listOf("format")),
        Call("GET", "/convert", "Services", "A track on another service: track is \"Artist - Title\" or a link, to the service's id.", listOf("track", "to")),
        Call("POST", "/convert", "Services", "Several tracks on another service.", body = "{\"tracks\": […], \"to\"}"),
        Call("GET", "/files", "Files", "The File playlists."),
        Call("GET", "/files/{file}", "Files", "A File playlist, in a format: csv, m3u, xspf, jspf or txt.", listOf("format")),
        Call("PUT", "/files/{file}", "Files", "Bring in a playlist file (CSV, M3U, PLS, XSPF, JSPF, iTunes XML, JSON, text) as the body."),
        Call("POST", "/files/{file}", "Files", "The same as PUT, for tools that only POST."),
        Call("DELETE", "/files/{file}", "Files", "Delete a File playlist."),
        Call("POST", "/files/zip", "Files", "Several File playlists in one .zip, in a format.", body = "{\"files\"?: [names], \"format\"?}"),
        Call("GET", "/files/settings", "Files", "How names in imported files are read: the layout and your own patterns."),
        Call("PUT", "/files/settings", "Files", "Set the layout (AUTO, ARTIST_TITLE, ARTIST_ALBUM_TITLE, CUSTOM) and the patterns.", body = "{\"layout\"?, \"patterns\"?: [lines]}"),
        Call("POST", "/files/try", "Files", "Try patterns on a line from a file, without saving them.", body = "{\"sample\", \"patterns\"?: [lines]}"),
        Call("GET", "/backup", "Backups", "The full backup: running, last run, schedule."),
        Call("POST", "/backup", "Backups", "Back up every playlist now (a background task)."),
        Call("GET", "/config", "Backups", "The whole set-up as a .trackhop file; history=true with the runs.", listOf("history")),
        Call("POST", "/config", "Backups", "Bring a .trackhop file back in (the body); dryRun=true only says what it holds.", listOf("dryRun")),
        Call("GET", "/settings", "Settings", "The settings the page offers."),
        Call("PATCH", "/settings", "Settings", "Change settings: theme, backupSchedule, backupFolder, updatesCheck.", body = "{\"theme\"?, \"backupSchedule\"?, \"backupFolder\"?, \"updatesCheck\"?}"),
        Call("GET", "/webhook", "Notifications", "The webhook: address, whether a secret is set, its events."),
        Call("PUT", "/webhook", "Notifications", "Set the webhook.", body = "{\"url\", \"secret\"?, \"events\"?: [sync.started, sync.finished, sync.failed, backup.finished]}"),
        Call("DELETE", "/webhook", "Notifications", "Turn the webhook off."),
        Call("GET", "/notifications", "Notifications", "The notification channels, their tokens masked."),
        Call("GET", "/notifications/kinds", "Notifications", "The kinds of channel, their settings and the events."),
        Call("POST", "/notifications", "Notifications", "Add a channel.", body = "{\"kind\", \"name\"?, \"enabled\"?, \"events\"?, \"settings\": {…}}"),
        Call("PATCH", "/notifications/{channel}", "Notifications", "Change a channel (a secret left out keeps its value).", body = "{\"name\"?, \"enabled\"?, \"events\"?, \"settings\"?}"),
        Call("DELETE", "/notifications/{channel}", "Notifications", "Remove a channel."),
        Call("POST", "/notifications/{channel}/test", "Notifications", "Send a test message."),
        Call("GET", "/lyrics", "Lyrics", "Lyrics for the music folder (Docker, admin): state and last run."),
        Call("POST", "/lyrics", "Lyrics", "Look for lyrics now: folder, synced=true, retry=true.", listOf("folder", "synced", "retry")),
    )

    fun openApi(version: String, baseUrl: String): JsonObject = buildJsonObject {
        put("openapi", "3.0.3")
        putJsonObject("info") {
            put("title", "Trackhop API")
            put("version", version)
            put("description", "Trackhop's JSON API. Make a token on Trackhop's page, Tools › API, and send it as Authorization: Bearer <token>. Guide: https://trackhop.org/guide/docker#api")
        }
        putJsonArray("servers") { add(buildJsonObject { put("url", "${baseUrl.trimEnd('/')}/api/v1") }) }
        putJsonObject("components") {
            putJsonObject("securitySchemes") { putJsonObject("token") { put("type", "http"); put("scheme", "bearer") } }
        }
        putJsonArray("security") { add(buildJsonObject { putJsonArray("token") {} }) }
        putJsonObject("paths") {
            CALLS.groupBy { it.path }.forEach { (path, calls) ->
                putJsonObject(path) {
                    calls.forEach { c ->
                        putJsonObject(c.method.lowercase()) {
                            put("summary", c.summary)
                            putJsonArray("tags") { add(c.tag) }
                            put("operationId", operationId(c))
                            putJsonArray("parameters") {
                                Regex("""\{(\w+)\}""").findAll(c.path).forEach { m ->
                                    add(buildJsonObject { put("name", m.groupValues[1]); put("in", "path"); put("required", true); putJsonObject("schema") { put("type", "string") } })
                                }
                                c.query.forEach { q ->
                                    add(buildJsonObject { put("name", q); put("in", "query"); put("required", false); putJsonObject("schema") { put("type", "string") } })
                                }
                            }
                            c.body?.let { b ->
                                putJsonObject("requestBody") {
                                    put("description", b)
                                    putJsonObject("content") { putJsonObject("application/json") { putJsonObject("schema") { put("type", "object") } } }
                                }
                            }
                            putJsonObject("responses") {
                                putJsonObject("200") { put("description", "Done.") }
                                putJsonObject("4XX") { put("description", "Refused: {\"error\": \"why\"}.") }
                            }
                        }
                    }
                }
            }
        }
    }

    /** "GET /syncs/{sync}/runs" → "getSyncsSyncRuns". */
    private fun operationId(c: Call): String =
        c.method.lowercase() + c.path.split('/', '-', '.').filter { it.isNotEmpty() }
            .joinToString("") { part -> part.trim('{', '}').replaceFirstChar { it.uppercase() } }
}

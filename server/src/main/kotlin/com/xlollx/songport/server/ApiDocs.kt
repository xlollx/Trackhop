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
        Call("GET", "/diagnostics", "General", "A report for asking for help (admin only): version, system, services, syncs, the last runs and the log since a time (since=24h; 90m, 7d, 2w), without tokens, cookies, e-mails, your servers' addresses or your user name in paths; names of syncs, playlists and tracks only with names=true. With the last error and its signature.", listOf("since", "names")),
        Call("POST", "/report", "General", "A GitHub issue made ready, never sent (admin only): title, body with the diagnostics and the error's signature, newIssueUrl prefilled. GitHub is searched for the signature first: suggestion is new, comment (an open issue has it: comment and commentUrl), update (fixed in a newer version, latest) or comment_closed (closed, but it still happens here).", body = "{\"since\"?: \"24h\", \"names\"?: false, \"title\"?, \"description\"?}"),
        Call("GET", "/syncs", "Syncs", "Every sync with its source, target, timing, label, tags and last result; tag=genre:metal (a key alone, or several split by commas), label= and favorite=true narrow the list.", listOf("tag", "label", "favorite")),
        Call("POST", "/syncs", "Syncs", "Make a sync; twoWay=true also makes the reverse sync (marked ↔) between two existing playlists of yours, twoWayRemovals=true lets removals travel too.", body = "{\"source\": {\"service\", \"playlist\" | \"link\"}, \"target\": {\"service\", \"playlist\"?}, \"name\"?, \"schedule\"? | \"every\"?, \"at\"?, \"days\"?, \"changes\"? (add, mirror, archive), \"follows\"?, \"color\"?, \"label\"?, \"tags\"?: {key: value}, \"favorite\"?, \"twoWay\"?, \"twoWayRemovals\"?}"),
        Call("POST", "/syncs/run-all", "Syncs", "Start every sync that is turned on."),
        Call("POST", "/syncs/run", "Syncs", "Start a group of syncs: those with a tag (tag=genre:metal), wearing a label (label=) or starred (favorite=true), turned on.", listOf("tag", "label", "favorite")),
        Call("GET", "/syncs/crowded", "Syncs", "Whether many syncs reach one service at about the same time (it can turn some away): the service, the time, the syncs, whether the page's card was dismissed and whether they can be spread."),
        Call("POST", "/syncs/spread", "Syncs", "New times for the crowded syncs, each a little after the one before (days and frequency stay): only shown, saved with apply=true.", listOf("apply"), body = "{\"apply\"?: true}"),
        Call("GET", "/tags", "Syncs", "The tag keys the syncs use, each with its values."),
        Call("GET", "/labels", "Syncs", "The labels: the ready colours and your own, each with its name and how many syncs wear it."),
        Call("POST", "/labels", "Syncs", "Name a ready colour, or save a colour of your own as a label.", body = "{\"color\": \"teal\" | \"#RRGGBB\", \"name\"}"),
        Call("PATCH", "/labels/{label}", "Syncs", "Rename a label or give it another colour: its syncs follow.", body = "{\"name\"?, \"color\"?: \"#RRGGBB\"}"),
        Call("DELETE", "/labels/{label}", "Syncs", "Delete a label: its syncs keep their colour, without the name."),
        Call("GET", "/syncs/{sync}", "Syncs", "One sync and its last runs."),
        Call("PATCH", "/syncs/{sync}", "Syncs", "Change a sync: any of name, enabled, schedule, every, at, days, changes, follows, color, label, tags, favorite; twoWay makes the reverse sync (true) or deletes it (false), twoWayRemovals lets a pair's removals travel too (a track removed on one side is removed on the other). The reverse sync follows this one's timing, colour, label, tags and star.", body = "{\"name\"?, \"enabled\"?, \"schedule\"?, \"every\"?, \"at\"?, \"days\"?, \"changes\"?, \"follows\"?, \"color\"?, \"label\"?, \"tags\"?: {key: value}, \"favorite\"?, \"twoWay\"?, \"twoWayRemovals\"?}"),
        Call("DELETE", "/syncs/{sync}", "Syncs", "Delete a sync (the playlists stay as they are); the other half of a two-way pair goes with it."),
        Call("POST", "/syncs/{sync}/run", "Syncs", "Start a sync now; anyway=true after a run held back by the target's size check. confirmRemovals lets this one run make the removals a run holds back (heldRemovals in its result): true for what the preview shows now, or the preview's toRemove ids to remove nothing else. Scheduled runs never make them.", listOf("anyway"), body = "{\"confirmRemovals\"?: true | [track ids]}"),
        Call("POST", "/syncs/{sync}/stop", "Syncs", "Stop a running sync."),
        Call("POST", "/syncs/{sync}/pause", "Syncs", "Pause a running sync."),
        Call("POST", "/syncs/{sync}/resume", "Syncs", "Resume a paused sync."),
        Call("POST", "/syncs/{sync}/enable", "Syncs", "Turn a sync's schedule on (both halves of a two-way pair)."),
        Call("POST", "/syncs/{sync}/disable", "Syncs", "Turn a sync's schedule off (both halves of a two-way pair)."),
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
        Call("GET", "/tools/mbtags", "Services", "The songs on a Navidrome, Jellyfin, Emby or Plex server without MusicBrainz tags (read only; it can take a while on a large library). service= its id, not needed when only one is connected.", listOf("service")),
        Call("GET", "/wanted", "Services", "Missing on my server: the tracks of your streaming playlists that your own server (Navidrome and other Subsonic servers, Jellyfin, Emby, Plex, Lyrion, Mopidy) does not have, each once, from your syncs' last runs to it: artist, title, album, durationMs, ISRC, musicBrainzId (the recording, when your syncs' MusicBrainz lookups found it), the syncs that miss it, firstSeen, got. format=json (default), csv, m3u, text, or lidarr (Lidarr's Custom List: [{\"MusicBrainzId\": artist MBID}], only artists with a known id); group=album groups the JSON by album; all=true lists the ticked ones too; sync= (id or name), service= (a source or target service) and since= (24h, 7d, 2026-11-01 or epoch ms) narrow it. Besides the token, key= (shown on the page, Tools › Missing on my server) opens this call alone, for Lidarr.", listOf("format", "group", "all", "sync", "service", "since", "key")),
        Call("GET", "/concerts", "Services", "Concerts of a playlist's artists: the upcoming dates (date, time, city, venue, country, url, source) of the artists the playlist holds most, in one country, from Ticketmaster (the server's own key, Settings, or TICKETMASTER_API_KEY; none = MusicBrainz only) and MusicBrainz events; a date both know comes once. Artists with no date get search links (ticket sites of the country, the official site). Asked only on this call, answers cached a day per artist and country; quotaReached says Ticketmaster's day is over. service= (id), playlist= (id or exact name), country= (two letters; default the last used on the page, or the server's language's). Show the attribution with the dates.", listOf("service", "playlist", "country")),
        Call("POST", "/wanted/got", "Services", "Tick entries of Missing on my server as got (hidden from then on), or got=false to put them back.", body = "{\"ids\": [entry ids], \"got\"?: true}"),
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
        Call("POST", "/config", "Backups", "Bring a .trackhop file back in (the body); dryRun=true only says what it holds. Scheduled syncs written by another device arrive turned off (\"paused\" counts them), so they do not run on both.", listOf("dryRun")),
        Call("GET", "/settings", "Settings", "The settings the page offers."),
        Call("PATCH", "/settings", "Settings", "Change settings: theme, backupSchedule, backupFolder, updatesCheck.", body = "{\"theme\"?, \"backupSchedule\"?, \"backupFolder\"?, \"updatesCheck\"?}"),
        Call("GET", "/webhook", "Notifications", "The webhook: address, whether a secret is set, its events."),
        Call("PUT", "/webhook", "Notifications", "Set the webhook.", body = "{\"url\", \"secret\"?, \"events\"?: [sync.started, sync.finished, sync.failed, backup.finished, wanted.added]}"),
        Call("DELETE", "/webhook", "Notifications", "Turn the webhook off."),
        Call("GET", "/notifications", "Notifications", "The notification channels, their tokens masked."),
        Call("GET", "/notifications/kinds", "Notifications", "The kinds of channel, their settings and the events."),
        Call("POST", "/notifications", "Notifications", "Add a channel.", body = "{\"kind\", \"name\"?, \"enabled\"?, \"events\"?, \"settings\": {…}}"),
        Call("PATCH", "/notifications/{channel}", "Notifications", "Change a channel (a secret left out keeps its value).", body = "{\"name\"?, \"enabled\"?, \"events\"?, \"settings\"?}"),
        Call("DELETE", "/notifications/{channel}", "Notifications", "Remove a channel."),
        Call("POST", "/notifications/{channel}/test", "Notifications", "Send a test message."),
        Call("POST", "/locks", "Locks", "Reserve a playlist before writing to it, for ttl seconds (default 600, from 30 to 3600; renew it while you write): 200 with the reservation's id and until (epoch ms), 409 with who holds it and until when. This server's syncs skip a playlist reserved by someone else, and a playlist one of them writes to is refused. target is the service and the playlist's id: \"spotify|37i9dQZF1DX…\" (the service, not the account: spotify, youtube, deezer, tidal…).", body = "{\"target\": \"service|playlist\", \"holder\": \"Pixel 8\", \"ttl\"?: 600}"),
        Call("PUT", "/locks/{lock}", "Locks", "Renew a reservation for ttl seconds from now; 404 once it ended (given back or expired): reserve it again.", body = "{\"ttl\"?: 600}"),
        Call("DELETE", "/locks/{lock}", "Locks", "Give a reservation back."),
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

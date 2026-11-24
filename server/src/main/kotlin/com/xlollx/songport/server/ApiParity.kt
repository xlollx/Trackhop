package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.server.Html.h
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The API calls of the app's features brought to Desktop and Docker in 2.2 (the weekly unplayable
 * check, To fix, saved schedules, recent transfers and the rest): the same engine as the app's
 * screens and as this server's pages. False for any other call.
 */
internal fun Web.apiParity(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    fun body(): JsonObject? = apiJson(ex)
    if (apiToFix(method, rest, query, ::body, json, error)) return true
    if (apiSchedules(method, rest, ::body, json, error)) return true
    if (apiTransfers(method, rest, ::body, json, error)) return true
    if (apiAiExtend(method, rest, ::body, json, error)) return true
    if (apiMake(method, rest, ::body, json, error)) return true
    if (apiScan(method, rest, json, error)) return true
    if (apiTwins(method, rest, ::body, json, error)) return true
    if (apiTrackLink(method, rest, ::body, json, error)) return true
    if (apiConnectionTest(method, rest, json, error)) return true
    if (apiRoast(method, rest, ::body, json, error)) return true
    if (apiVanished(method, rest, json)) return true
    if (apiShareLink(method, rest, json, error)) return true
    if (apiOverlap(method, rest, ::body, json, error)) return true
    if (apiCompat(method, rest, ::body, json, error)) return true
    if (apiListens(method, rest, ::body, json, error)) return true
    if (apiLifetime(ex, method, rest, json, error)) return true
    if (apiMyRecords(method, rest, query, json, error)) return true
    if (apiVenues(method, rest, query, ::body, json, error)) return true
    if (apiCover(ex, method, rest, json, error)) return true
    if (apiShared(method, rest, ::body, json, error)) return true
    if (rest == listOf("files", "text") && method == "POST") {
        val b = body() ?: return true.also { error(400, "Send JSON: {\"name\"?, \"text\"}.") }
        val (id, n) = runCatching { importText(b.text("name").orEmpty(), b.text("text").orEmpty()) }.getOrElse { return true.also { _ -> error(400, it.message ?: "Error") } }
        json(201, buildJsonObject { put("name", "$id.csv"); put("tracks", n) })
        return true
    }
    when {
        rest == listOf("tools", "unplayable", "weekly") && method == "GET" -> json(200, unplayableWeeklyJson())
        rest == listOf("tools", "unplayable", "weekly") && (method == "PATCH" || method == "PUT") -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"enabled\": true}.") }
            val on = b.flag("enabled") ?: return true.also { error(400, "Send JSON: {\"enabled\": true}.") }
            UnplayableWeekly.set(env, on)
            json(200, unplayableWeeklyJson())
        }
        rest == listOf("tools", "unplayable", "weekly", "run") && method == "POST" -> {
            if (Tasks.running(UnplayableWeekly.TITLE)) return true.also { error(409, "The check is already running.") }
            val t = Tasks.start(UnplayableWeekly.TITLE, "/tools/unplayable", { env.log("unplayable", it) }, env.owner, env.marks) { t ->
                """<div class="card">${h(UnplayableWeekly.run(env) { t.progress = it })}</div>"""
            }
            json(202, buildJsonObject { put("task", t.id) })
        }
        else -> return false
    }
    return true
}

private fun Web.unplayableWeeklyJson() = buildJsonObject {
    put("enabled", UnplayableWeekly.on(env))
    put("services", buildJsonArray { UnplayableWeekly.services(env).forEach { add(JsonPrimitive(it.id)) } })
    put("channels", UnplayableWeekly.channels(env).size)
    put("last", UnplayableWeekly.last(env)?.let { l ->
        buildJsonObject {
            put("at", l.at); put("unplayable", l.count); put("playlists", l.playlists); put("services", l.services); put("notRead", l.failed)
            put("summary", UnplayableWeekly.summary(l))
        }
    } ?: JsonNull)
    put("next", if (UnplayableWeekly.on(env)) JsonPrimitive((UnplayableWeekly.last(env)?.at ?: System.currentTimeMillis()) + UnplayableWeekly.EVERY_MS) else JsonNull)
}

/** Their lines in the API's description (ApiDocs), and so in openapi.json and the guide's test. */
internal val PARITY_CALLS: List<ApiDocs.Call> = listOf(
    ApiDocs.Call("GET", "/shared", "Shared playlists", "The shared playlists this Trackhop is in as a member: id, name, transport, syncId (the sync that keeps it here) and the members (name, service, curator, me)."),
    ApiDocs.Call("POST", "/shared", "Shared playlists", "Join a shared playlist from its invite link (one meeting on an encrypted ntfy channel or in a Trackhop server's mailbox), kept on a service here (a new playlist, or playlist: one of yours); the first run starts at once.", body = "{\"link\", \"service\", \"name\"?, \"playlist\"?}"),
    ApiDocs.Call("DELETE", "/shared/{group}", "Shared playlists", "Leave a shared playlist: the others are told; your playlist stays as it is."),
    ApiDocs.Call("PUT", "/services/{service}/playlists/{playlist}/cover", "Playlists", "Put a cover on a playlist of yours, where the service takes one (canSetCover): a drawn one ({\"variant\": N}: from its title and artists, no AI; another number, another cover) or an image of yours sent as the body (Content-Type image/jpeg or image/png).", body = "{\"variant\"?} | (an image)"),
    ApiDocs.Call("GET", "/venues", "Discover", "The venues you follow and your city; nights=true adds the dates of the coming weeks there (date, artist, venue, city, url).", listOf("nights")),
    ApiDocs.Call("GET", "/venues/search", "Discover", "Find a venue to follow: q= its name, country= a two-letter code (Ticketmaster with your key, and MusicBrainz): id, name, city, country.", listOf("q", "country")),
    ApiDocs.Call("POST", "/venues", "Discover", "Follow a venue (as GET /venues/search found it).", body = "{\"id\", \"name\", \"city\"?, \"country\"?}"),
    ApiDocs.Call("DELETE", "/venues/{venue}", "Discover", "Stop following a venue."),
    ApiDocs.Call("GET", "/diary", "Discover", "Your concert diary from setlist.fm (a key needed): user= your setlist.fm user name (kept), refresh=true reads it again. concerts, artists, venues, topArtist and the setlists, the latest first.", listOf("user", "refresh")),
    ApiDocs.Call("GET", "/records/mine", "Discover", "My records, with your Discogs token (Settings): kind=collection (the default: user, items with id, title, artist, year, formats; value: minimum, median, maximum) or wantlist (items and markets: forSale, lowest, currency). As last read; refresh=true reads Discogs again.", listOf("kind", "refresh")),
    ApiDocs.Call("GET", "/tools/lifetime", "Discover", "All-time stats, as last read from your service's export of your listening history: sources, from, to, plays, ms, timed, artists, tracks, clock (per hour of the day), topArtists, topTracks, years; null when none was read."),
    ApiDocs.Call("PUT", "/tools/lifetime", "Discover", "Read an export of your listening history (Spotify's extended streaming history, YouTube's Takeout, Deezer's export: the .zip, a .json or the .xlsx) sent as the body: the sums are kept and answered; the listens are not written anywhere.", body = "(the file)"),
    ApiDocs.Call("DELETE", "/tools/lifetime", "Discover", "Forget the all-time stats."),
    ApiDocs.Call("POST", "/listens", "Discover", "Mark as listened: an album (album: a Deezer album id, with albumTitle and albumArtist), a playlist (service, playlist) or a list (text, \"Artist - Title\" lines) sent to Last.fm and ListenBrainz (lastfm, listenbrainz: both connected ones by default) as listens, one song after the other, ending at at (2026-11-24T21:00 or epoch ms; now by default), or starting then with finished false. Last.fm takes the last 14 days only.", body = "{\"album\"? | \"service\"?, \"playlist\"? | \"text\"?, \"at\"?, \"finished\"?, \"lastfm\"?, \"listenbrainz\"?}"),
    ApiDocs.Call("POST", "/tools/compatibility", "Tools", "Compatibility of two playlists (yours and a friend's: one of yours, or a public link): score (0-100), songsA, songsB, commonPercent, common, sharedArtists, forA (from B, what A would likely enjoy), forB.", body = "{\"a\": {\"service\", \"playlist\"} | {\"link\"}, \"b\": {\"service\", \"playlist\"} | {\"link\"}}"),
    ApiDocs.Call("POST", "/tools/overlap", "Tools", "Two subscriptions: how much of your libraries (liked songs and your own playlists, up to sixty each) on two connected services is on both: songsA, songsB, both, percent, coveredA, coveredB, verdict (same, a_in_b, b_in_a, partial, apart), summary, onlyA and onlyB. Reads every playlist: it can take minutes.", body = "{\"a\": \"<service>\", \"b\": \"<service>\"}"),
    ApiDocs.Call("GET", "/services/{service}/playlists/{playlist}/link", "Playlists", "The playlist as universal links (trackhop.org/open), which open it on any service: the songs travel in the link itself. A long playlist takes several: url, part, parts, first, last, songs, total; qr is the link as a QR code in SVG where it is short enough for one (else null)."),
    ApiDocs.Call("GET", "/vanished", "Playlists", "Vanished songs: what left your playlists, from the dated backup copies (Tools › Backup) and what mirror syncs removed, newest first: track, where, lastSeen, sync (for a sync's removals). Not the playlists that renew themselves."),
    ApiDocs.Call("POST", "/tools/roast", "Tools", "Roast a playlist with your own AI (set up in Tools › Playlist generator): level gentle, spicy (the default) or savage; explicit allows swear words. Only the playlist's name and a summary of its songs go to the AI. Answers playlist, level and roast (the text).", body = "{\"service\", \"playlist\": \"<id or name>\", \"level\"?, \"explicit\"?}"),
    ApiDocs.Call("POST", "/services/{service}/test", "Services", "Test the connection: three calls, one after the other (who is signed in, the playlist list, one search), each with ok and what the service answered (detail); ok when all three worked. Read only."),
    ApiDocs.Call("POST", "/tracks/link", "Playlists", "A song's link from any service, on one of yours: source (the song the link is about), exact (the same recording there, where song.link knows it), candidates (a search's best, with their score in %), best. With playlist (one of yours, id or name) the best, or choice (a candidate's id), is added to it: added.", body = "{\"link\", \"service\", \"playlist\"?, \"choice\"?}"),
    ApiDocs.Call("POST", "/files/text", "Files", "A playlist pasted as text, one song per line (Artist - Title; numbers in front and your own name patterns are understood), saved as a File playlist: its file name and how many songs.", body = "{\"name\"?, \"text\"}"),
    ApiDocs.Call("GET", "/services/{service}/twins", "Playlists", "Playlist twins: the copies of one playlist on the service (same name, or nine songs in ten the same), each group with its copies (id, name, tracks, the syncs that use it, notInKept) and the one proposed to keep. Reads every playlist."),
    ApiDocs.Call("POST", "/services/{service}/twins", "Playlists", "Keep one copy of a group (as GET just read it) and delete the others, each saved in Files first; addMissing (true by default) adds to the one kept the songs only the others have. A copy a sync uses is never deleted.", body = "{\"group\", \"keep\": \"<playlist id>\", \"addMissing\"?}"),
    ApiDocs.Call("GET", "/services/{service}/scan", "Playlists", "Library scan, read only: the songs in more than one of your playlists on the service (repeats: track and the playlists), and the liked songs in no playlist (likedInNoPlaylist); notRead names what could not be read. Reads every playlist once: a large library takes a while."),
    ApiDocs.Call("POST", "/services/{service}/playlists/{playlist}/new", "Playlists", "A new playlist on the same service from this one: op copy, merge (with: other playlists' ids or names; dropDuplicates, true by default), split (size: songs in each part), sort (by: artist, title, album, year, added, duration, reverse) or shuffle; name optional. The original never changes. A background task.", body = "{\"op\", \"with\"?, \"size\"?, \"by\"?, \"dropDuplicates\"?, \"name\"?}"),
    ApiDocs.Call("POST", "/tools/ai/extend", "Tools", "Extend a playlist with your own AI (Tools › Playlist generator's): more songs in the spirit of the description, or of the songs already there when left out; only those found on the service are added, at the end (into: end) or into a new playlist (into: new, name). A background task: GET /tasks/{task}.", body = "{\"service\", \"playlist\": \"<id or name>\", \"description\"?, \"count\"?: 10, \"into\"?: \"end\" | \"new\", \"name\"?}"),
    ApiDocs.Call("GET", "/transfers", "Playlists", "Recent transfers: the File playlists sent once to a service (a setlist, a pasted list), newest first, for a week: id, name, created, target, running, result (added, alreadyThere, notFound, toReview, runId: its review is GET /runs/{run}/review)."),
    ApiDocs.Call("POST", "/transfers", "Playlists", "Send a File playlist once to a service, as a new playlist there: not a sync, it shows in GET /transfers.", body = "{\"file\": \"<File playlist>\", \"to\": \"<service>\", \"name\"?}"),
    ApiDocs.Call("GET", "/transfers/{transfer}", "Playlists", "One recent transfer."),
    ApiDocs.Call("DELETE", "/transfers/{transfer}", "Playlists", "Take a transfer off the list; the playlist it made stays."),
    ApiDocs.Call("GET", "/schedules", "Syncs", "The saved schedules: id, name, timing (in words), every (minutes), at, days, the syncs that follow each, nextRun. A sync follows one with \"savedSchedule\" (PATCH /syncs/{sync})."),
    ApiDocs.Call("POST", "/schedules", "Syncs", "Save a schedule: a timing that runs by itself (every 60 to 10080 minutes; at HH:MM; days 1 = Monday), with a name.", body = "{\"name\"?, \"every\", \"at\"?, \"days\"?}"),
    ApiDocs.Call("GET", "/schedules/{schedule}", "Syncs", "One saved schedule, by its id or name."),
    ApiDocs.Call("PATCH", "/schedules/{schedule}", "Syncs", "Change a saved schedule: every sync that follows it takes the new timing at once.", body = "{\"name\"?, \"every\"?, \"at\"?, \"days\"?}"),
    ApiDocs.Call("DELETE", "/schedules/{schedule}", "Syncs", "Delete a saved schedule: its syncs keep running at the same times, as their own timing."),
    ApiDocs.Call("POST", "/syncs/bulk", "Syncs", "Change several syncs at once, as the page's Select › Edit: schedule (a saved schedule's id or name) or every (minutes) and at, label (a label or colour; null takes it off), changes (add or mirror; two-way syncs and archives keep their own), enabled. Both halves of a two-way pair and all of a multi sync follow.", body = "{\"syncs\": [\"<id>\"], \"schedule\"? | \"every\"?, \"at\"?, \"label\"?, \"changes\"?, \"enabled\"?}"),
    ApiDocs.Call("GET", "/tofix", "Runs", "To fix: every song the last run of every sync left not found or matched loosely (to check), once per pair of services: key, kind (not_found or to_check), track, suggestion with its score (%), on (the service it is missing on), syncs. Grouped by sync, or by service with by=service; later lists those set aside until their sync runs again.", listOf("by")),
    ApiDocs.Call("POST", "/tofix", "Runs", "Fix songs of To fix, for every sync between the same two services, in their playlists at once: action use (the suggestion), ignore (not searched for again), later, back (a song set aside returns), pick (one song, choice: a link, \"Artist - Title\" or its id there). keys: the rows' keys, or \"all\". Answers done, syncs (playlists written), failed (written by their next run), left.", body = "{\"action\": \"use\" | \"ignore\" | \"later\" | \"back\" | \"pick\", \"keys\": [\"<key>\"] | \"all\", \"choice\"?}"),
    ApiDocs.Call("GET", "/tools/unplayable/weekly", "Tools", "The weekly look for unplayable tracks: whether it is on, the services it asks, how many notification channels take its news (those with \"Tracks to review\"), the last look (unplayable, playlists, notRead, summary) and the next."),
    ApiDocs.Call("PATCH", "/tools/unplayable/weekly", "Tools", "Turn the weekly look for unplayable tracks on or off. Nothing is ever changed by it: your channels are told, and Tools › Unplayable tracks fixes them.", body = "{\"enabled\": true}"),
    ApiDocs.Call("POST", "/tools/unplayable/weekly/run", "Tools", "Look for unplayable tracks now (a background task, as GET /tasks/{task} follows), telling the channels as the weekly look does."),
)

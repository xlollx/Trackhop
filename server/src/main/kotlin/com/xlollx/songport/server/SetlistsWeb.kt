package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Setlists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Setlists of a playlist's artists ([Setlists]) on the page (Tools › Setlists), in the API
 * (GET /api/v1/setlists) and, through it, the command line and MCP. Only when asked: nothing runs
 * on a schedule. The setlist.fm key is the server's own setting (Settings), or the container's
 * SETLISTFM_API_KEY; without one there is nothing to ask. A setlist picked on the page becomes a
 * File playlist that "Send it to a service" takes anywhere.
 */
internal object SetlistTexts {
    const val QUOTA = "Today's setlist.fm limit for this key is reached: try again tomorrow."
    const val REFUSED = "setlist.fm refused the key: check it in Settings › setlist.fm key."
    const val NO_KEY = "Setlists need a setlist.fm key: add your own (free) in Settings › setlist.fm key, or set SETLISTFM_API_KEY."
    /** setlist.fm's terms: the source, with a link, wherever its data is shown. */
    const val ATTRIBUTION = "${Setlists.ATTRIBUTION} (${Setlists.HOME})"

    fun limited(shown: Int, total: Int) = "Only the $shown artists the playlist holds most were looked up, of $total."
    fun unanswered(n: Int) = "setlist.fm did not answer for $n ${if (n == 1) "artist" else "artists"}: try again later."

    fun notes(r: Setlists.Result): List<String> = listOfNotNull(
        NO_KEY.takeIf { !r.hasKey }, QUOTA.takeIf { r.quotaHit }, REFUSED.takeIf { r.keyRefused },
        limited(r.artists.size, r.total).takeIf { r.hasKey && r.total > r.artists.size },
        unanswered(r.unanswered).takeIf { r.unanswered > 0 },
    )
}

private fun Web.canReadForSetlists(p: MusicProvider) =
    (!p.requiresAuth || p.isConnected(env)) && !p.linkOnly && p.serviceId != "lastfm" && p.serviceId != "listenbrainz"

/** The services a playlist can come from: the connected ones, and File. */
private fun Web.readableForSetlists(): List<MusicProvider> =
    (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { canReadForSetlists(it) }

private suspend fun Web.playlistsFor(p: MusicProvider): List<Playlist> = p.libraryEntries(env, false) + p.playlists(env)

private suspend fun Web.setlistsOf(p: MusicProvider, playlistId: String, progress: (Int, Int) -> Unit = { _, _ -> }): Setlists.Result {
    val r = Setlists.find(env, Setlists.artistsOf(p.tracks(env, playlistId)), progress)
    env.log("setlists", "${p.label(env)} / $playlistId: ${r.artists.count { it.setlists.isNotEmpty() }} of ${r.artists.size} artists with setlists")
    return r
}

internal fun setlistJson(s: Setlists.Setlist): JsonObject = buildJsonObject {
    put("id", s.id); put("artist", s.artist); put("date", s.date)
    put("venue", s.venue); put("city", s.city); put("country", s.country); put("tour", s.tour)
    put("url", s.url)
    put("songs", JsonArray(s.songs.map { song ->
        buildJsonObject {
            put("title", song.title)
            song.by?.let { put("coverOf", it) }
            put("tape", song.tape); put("encore", song.encore)
        }
    }))
    // What a playlist gets: the songs played, tapes left out, "Artist - Title".
    put("tracks", JsonArray(Setlists.tracks(s).map { JsonPrimitive("${it.artists.joinToString(", ")} - ${it.title}") }))
}

internal fun setlistsJson(r: Setlists.Result): JsonObject = buildJsonObject {
    put("artistsInPlaylist", r.total)
    put("artistsLookedUp", r.artists.size)
    put("key", r.hasKey)
    put("ownKey", r.ownKey)
    put("quotaReached", r.quotaHit)
    put("keyRefused", r.keyRefused)
    put("notes", JsonArray(SetlistTexts.notes(r).map { JsonPrimitive(it) }))
    put("artists", JsonArray(r.artists.map { a ->
        buildJsonObject {
            put("artist", a.artist)
            put("setlists", JsonArray(a.setlists.map { setlistJson(it) }))
        }
    }))
    put("attribution", SetlistTexts.ATTRIBUTION)
}

/** GET /api/v1/setlists?service=&playlist= */
internal fun Web.apiSetlists(method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("setlists") || method != "GET") return false
    val able = readableForSetlists()
    val asked = query["service"] ?: query["connector"]
    val p = if (asked != null) Providers.byId(asked)?.takeIf { canReadForSetlists(it) } ?: return true.also { error(404, "No connected service \"$asked\": see GET /api/v1/services.") }
        else able.singleOrNull() ?: return true.also { error(400, "Say which service: ?service= one of ${able.joinToString(", ") { it.id }}.") }
    val wanted = query["playlist"]?.trim()?.takeIf { it.isNotEmpty() } ?: return true.also { error(400, "Say which playlist: ?playlist= its id or name (GET /api/v1/services/${p.id}/playlists).") }
    val r = runCatching {
        runBlocking {
            val lists = playlistsFor(p)
            val pl = lists.firstOrNull { it.id == wanted } ?: lists.filter { it.name.equals(wanted, ignoreCase = true) }.singleOrNull()
                ?: return@runBlocking null
            setlistsOf(p, pl.id)
        }
    }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
        ?: return true.also { error(404, "No playlist \"$wanted\" on ${p.label(env)} (or more than one with that name: use its id).") }
    json(200, setlistsJson(r))
    return true
}

/** Tools › Setlists: a service and a playlist, then the setlists as a background task; one saved as a File playlist. */
internal fun Web.setlistsPage(r: Web.Req) {
    val title = "Setlists"
    val params = if (r.method == "POST") r.form else r.query
    val body = StringBuilder(notice(r))
    // A setlist picked from a result: its songs as a File playlist, to send to any service.
    if (r.method == "POST" && r.form["action"] == "file") {
        val id = r.form["setlist"].orEmpty()
        val task = Tasks.start(title, "/tools/setlists", { env.log("setlists", it) }, env.owner) { _ ->
            val s = Setlists.byId(env, id) ?: return@start """<div class="card err">${h(SetlistTexts.NO_KEY)}</div>"""
            val tracks = Setlists.tracks(s)
            val file = LocalFilesProvider.importTracks(env, Setlists.playlistName(s), tracks)
            env.log("setlists", "${tracks.size} songs of setlist ${s.id} saved as $file")
            """<div class="card ok"><p>${tracks.size} songs saved as the File playlist <b>${h(file)}</b>.</p>
<p><a class="button" href="/syncs/new?src=${q(LocalFilesProvider.id)}&srcPlaylist=${q(file)}">Send it to a service</a></p>
<p class="muted">${attributionHtml()}</p></div>"""
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val able = readableForSetlists()
    if (able.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val intro = """<p class="muted">The latest setlists of a playlist's artists, from setlist.fm: pick a concert and make it a playlist on any service. Only when you ask: nothing is checked on a schedule. ${setlistKeyLine()}</p>"""
    val service = params["service"]?.let { id -> able.firstOrNull { it.id == id } }
    if (service == null) {
        val opts = able.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/setlists" class="card">$intro<label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val lists = runCatching { runBlocking { playlistsFor(service) } }
        .getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(it.message)}</div>""")) }
    val playlist = params["playlist"]?.let { id -> lists.firstOrNull { it.id == id } }
    if (r.method == "POST" && playlist != null) {
        val task = Tasks.start(title, "/tools/setlists?service=${q(service.id)}&playlist=${q(playlist.id)}", { env.log("setlists", it) }, env.owner) { t ->
            t.progress = "Reading the playlist"
            val res = setlistsOf(service, playlist.id) { done, total -> t.progress = "Artist ${minOf(done + 1, total)} of $total" }
            setlistsHtml(playlist.name, res)
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}"${if (p.id == playlist?.id) " selected" else ""}>${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
    body.append("""<form method="post" action="/tools/setlists" class="card">$intro<input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">$opts</select>
<p><button>Find setlists</button> <a class="button secondary" href="/tools/setlists">Another service</a></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

private fun Web.setlistKeyLine(): String = when {
    Setlists.ownKey(env) != null -> "setlist.fm: your own key."
    Setlists.key(env) != null -> "setlist.fm: the key given to this server."
    else -> """No setlist.fm key yet: <a href="/settings#setlistfm">add a free key</a>."""
}

/** setlist.fm's terms: the source with a followed link (no nofollow). */
private fun attributionHtml() = """<a href="${Setlists.HOME}" target="_blank" rel="noopener">${h(Setlists.ATTRIBUTION)}</a>"""

/** The result of a search, as the task's page shows it. */
private fun setlistsHtml(playlist: String, r: Setlists.Result): String = buildString {
    append("""<p class="muted">${h(playlist)}</p>""")
    val notes = SetlistTexts.notes(r)
    if (notes.isNotEmpty()) {
        append("""<div class="card">""")
        notes.forEach { append("""<p class="muted">${h(it)}</p>""") }
        append("""<p><a href="/settings#setlistfm">setlist.fm key</a></p></div>""")
    }
    if (!r.hasKey) return@buildString
    val (found, without) = r.artists.partition { it.setlists.isNotEmpty() }
    if (found.isEmpty()) append("""<div class="card">No recent setlists found for these artists.</div>""")
    found.forEach { a ->
        append("""<div class="card"><h3>${h(a.artist)}</h3>""")
        a.setlists.forEach { s ->
            val where = listOf(s.venue, s.city, s.country).filter { it.isNotBlank() }.joinToString(", ")
            val n = Setlists.tracks(s).size
            append("""<details><summary><strong>${h(s.date)}</strong> ${h(where)}${if (s.tour.isNotBlank()) " · " + h(s.tour) else ""} · $n ${if (n == 1) "song" else "songs"}</summary><ol>""")
            var encore = 0
            s.songs.forEach { song ->
                if (song.encore != encore) { encore = song.encore; if (encore > 0) append("""</ol><p class="muted">Encore</p><ol>""") }
                val cover = song.by?.let { " (${h(it)} cover)" }.orEmpty()
                if (song.tape) append("""<li class="muted">${h(song.title)}$cover · from tape, left out</li>""") else append("""<li>${h(song.title)}$cover</li>""")
            }
            append("</ol><p>")
            if (n > 0) append(Html.postButton("/tools/setlists", "Save as a File playlist", fields = mapOf("action" to "file", "setlist" to s.id)))
            if (s.url.isNotBlank()) append(""" <a href="${h(s.url)}" target="_blank" rel="noopener">On setlist.fm</a>""")
            append("</p></details>")
        }
        append("</div>")
    }
    if (without.isNotEmpty()) append("""<p class="muted">No setlists for: ${h(without.joinToString(", ") { it.artist })}</p>""")
    append("""<p class="muted">${attributionHtml()}</p>""")
}

/** Settings › setlist.fm key: the card, and its form's handling. */
internal fun Web.setlistfmKeyCard(): String {
    val own = Setlists.ownKey(env) != null
    val state = when {
        own -> "Your own key is saved."
        Setlists.key(env) != null -> "Using the key given to this server (SETLISTFM_API_KEY)."
        else -> "No key: Tools › Setlists needs one."
    }
    return """<form method="post" action="/settings" class="card" id="setlistfm"><input type="hidden" name="action" value="setlistfm"><h3>setlist.fm key</h3>
<p class="muted">For Tools › Setlists and setlist.fm links. Free for non-commercial use: with a setlist.fm account, ask for one on <a href="${Setlists.KEY_URL}" rel="noopener noreferrer" target="_blank">setlist.fm's API page</a>. A key has 2 calls a second and 1440 a day; each artist's setlists are kept a week. $state</p>
<label>API key</label><input name="key" autocomplete="off" placeholder="${if (own) "saved: leave empty to keep it" else ""}">
<p class="actions"><button>Save</button>${if (own) """ <button name="remove" value="1" class="secondary">Remove</button>""" else ""}</p></form>"""
}

internal fun Web.saveSetlistfmKey(form: Map<String, String>) {
    when {
        form["remove"] == "1" -> Setlists.saveKey(env, "")
        !form["key"].isNullOrBlank() -> Setlists.saveKey(env, form["key"]!!)
    }
}

package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.PlaylistFiles
import com.xlollx.songport.sync.Scrobbles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Mark as listened (the app's MarkListenedPage, [Scrobbles]): an album (Deezer's catalogue, no
 * account), a playlist or a pasted list, sent to Last.fm and ListenBrainz as listens, one song after
 * the other, ending (or starting) at the time given. Last.fm takes the last fourteen days only.
 */
internal object Listened {
    fun lastFm(env: ServerEnv) = Providers.connectors().filterIsInstance<LastFmProvider>().firstOrNull { it.isConnected(env) }
    fun listenBrainz(env: ServerEnv) = Providers.connectors().filterIsInstance<ListenBrainzProvider>().firstOrNull { it.isConnected(env) }

    /** The songs of a source: an album (Deezer id, title, artist), a service's playlist, or text. */
    suspend fun songs(env: ServerEnv, album: String?, albumTitle: String?, albumArtist: String?, service: String?, playlist: String?, text: String?): List<Track> = Scrobbles.songs(when {
        !album.isNullOrBlank() -> DeezerCatalog.albumTracks(DeezerCatalog.Album(album, albumTitle.orEmpty(), albumArtist.orEmpty()))
        !service.isNullOrBlank() -> {
            val p = (Providers.connectors() + LocalFilesProvider).firstOrNull { it.id == service && (!it.requiresAuth || it.isConnected(env)) } ?: throw IllegalArgumentException("Pick a connected service.")
            val all = p.libraryEntries(env, false) + p.playlists(env)
            val pl = all.firstOrNull { it.id == playlist } ?: all.filter { it.name.equals(playlist.orEmpty(), ignoreCase = true) }.singleOrNull() ?: throw IllegalArgumentException("${p.label(env)} has no playlist \"${playlist.orEmpty()}\".")
            p.tracks(env, pl.id)
        }
        !text.isNullOrBlank() -> PlaylistFiles.parse("pasted.txt", text)
        else -> throw IllegalArgumentException("Say what was listened to: an album, a playlist or a list.")
    })

    fun send(env: ServerEnv, songs: List<Track>, at: Long, finished: Boolean, toLastFm: Boolean, toListenBrainz: Boolean): Scrobbles.Sent {
        if (songs.isEmpty()) throw IllegalArgumentException("No songs to mark.")
        val lf = if (toLastFm) lastFm(env) ?: throw IllegalArgumentException("Last.fm is not connected.") else null
        val lb = if (toListenBrainz) listenBrainz(env) ?: throw IllegalArgumentException("ListenBrainz is not connected.") else null
        if (lf == null && lb == null) throw IllegalArgumentException("Pick Last.fm, ListenBrainz or both.")
        return runBlocking { Scrobbles.send(env, Scrobbles.lay(songs, at, finished), lf, lb) }
    }

    fun summary(s: Scrobbles.Sent): String = listOfNotNull(
        s.lastFm?.let { "Last.fm took $it" }, s.listenBrainz?.let { "ListenBrainz took $it" },
        s.tooOldForLastFm.takeIf { it > 0 }?.let { "$it too old for Last.fm (over ${Scrobbles.LASTFM_DAYS} days)" },
    ).joinToString("; ") + "."

    /** "2026-11-24T21:00" (the form's) or epoch ms; now when blank. */
    fun time(text: String?): Long = when {
        text.isNullOrBlank() -> System.currentTimeMillis()
        text.all { it.isDigit() } -> text.toLong()
        else -> runCatching { LocalDateTime.parse(text.trim().replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrElse { throw IllegalArgumentException("A time as 2026-11-24T21:00.") }
    }
}

internal fun Web.listenedPage(r: Web.Req) {
    val title = "Mark as listened"
    val params = if (r.method == "POST") r.form else r.query
    val back = "/tools/listened"
    val lf = Listened.lastFm(env)
    val lb = Listened.listenBrainz(env)
    if (r.method == "POST" && r.form["action"] == "send") {
        val res = runCatching {
            val songs = runBlocking { Listened.songs(env, params["album"], params["albumTitle"], params["albumArtist"], params["service"], params["playlist"], params["text"]) }
            Listened.send(env, songs, Listened.time(params["at"]), params["when"] != "start", params["lastfm"] == "on", params["listenbrainz"] == "on")
        }
        return redirect(r.ex, "$back?" + res.fold({ "ok=${q(Listened.summary(it))}" }, { "err=${q(it.message ?: "Error")}" }))
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">An album, a playlist or a list you listened to away from your services (a record, the radio, a friend's speakers) sent to Last.fm and ListenBrainz as listens, one song after the other. Last.fm takes the last ${Scrobbles.LASTFM_DAYS} days only.</p>""")
    if (lf == null && lb == null) {
        body.append("""<div class="card">Connect Last.fm or ListenBrainz first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    // A source chosen: its songs and the final form.
    val chosen = params["album"]?.isNotBlank() == true || (params["service"]?.isNotBlank() == true && params["playlist"]?.isNotBlank() == true) || (r.method == "POST" && params["text"]?.isNotBlank() == true)
    if (chosen) {
        val songs = runCatching { runBlocking { Listened.songs(env, params["album"], params["albumTitle"], params["albumArtist"], params["service"], params["playlist"], params["text"]) } }
            .getOrElse { return redirect(r.ex, "$back?err=${q(it.message ?: "Error")}") }
        val keep = listOf("album", "albumTitle", "albumArtist", "service", "playlist", "text").mapNotNull { k -> params[k]?.takeIf { it.isNotBlank() }?.let { """<input type="hidden" name="$k" value="${h(it)}">""" } }.joinToString("")
        val now = LocalDateTime.now().withSecond(0).withNano(0).toString()
        body.append("""<form method="post" action="$back" class="card">$keep<input type="hidden" name="action" value="send"><h3>${songs.size} ${if (songs.size == 1) "song" else "songs"}</h3><ol>${songs.take(200).joinToString("") { "<li>${h(it.artistLine)} – ${h(it.title)}</li>" }}</ol>
<label for="at">Listened</label><input type="datetime-local" id="at" name="at" value="$now" style="max-width:16em"> <label class="check inline"><input type="radio" name="when" value="end" checked> ending then</label><label class="check inline"><input type="radio" name="when" value="start"> starting then</label>
<label>To</label>${if (lf != null) """<label class="check inline"><input type="checkbox" name="lastfm" checked> Last.fm</label>""" else ""}${if (lb != null) """<label class="check inline"><input type="checkbox" name="listenbrainz" checked> ListenBrainz</label>""" else ""}
<p><button>Mark ${songs.size} ${if (songs.size == 1) "song" else "songs"}</button> <a href="$back">Something else</a></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    // An album: Deezer's catalogue.
    val query = r.query["q"].orEmpty()
    val albums = if (query.isNotBlank()) runCatching { runBlocking { DeezerCatalog.albums(query) } }.getOrDefault(emptyList()) else emptyList()
    body.append("""<form method="get" action="$back" class="card"><h3>An album</h3><input type="search" name="q" value="${h(query)}" placeholder="Artist or album" aria-label="Find an album"> <p><button class="secondary">Find</button></p>""")
    if (query.isNotBlank() && albums.isEmpty()) body.append("""<p class="muted">Nothing found.</p>""")
    albums.take(15).forEach { a -> body.append("""<p><a href="$back?album=${q(a.id)}&amp;albumTitle=${q(a.title)}&amp;albumArtist=${q(a.artist)}">${h(a.title)}</a> <span class="muted">· ${h(a.artist)}</span></p>""") }
    body.append("</form>")
    // A playlist.
    val sources = (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" }
    val svc = sources.firstOrNull { it.id == r.query["service"] }
    if (svc == null) body.append("""<form method="get" action="$back" class="card"><h3>A playlist</h3><select name="service" aria-label="Service">${sources.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }}</select><p><button class="secondary">Next</button></p></form>""")
    else {
        val lists = runCatching { runBlocking { svc.libraryEntries(env, false) + svc.playlists(env) } }.getOrDefault(emptyList())
        body.append("""<form method="get" action="$back" class="card"><h3>A playlist on ${h(svc.label(env))}</h3><input type="hidden" name="service" value="${h(svc.id)}"><select name="playlist" aria-label="Playlist">${lists.joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }}</select><p><button class="secondary">Next</button></p></form>""")
    }
    // A list.
    body.append("""<form method="post" action="$back" class="card"><h3>A list</h3><textarea name="text" rows="4" placeholder="Artist - Title, one per line" aria-label="The songs"></textarea><p><button class="secondary">Next</button></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

/** POST /listens */
internal fun Web.apiListens(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("listens") || method != "POST") return false
    val b = body() ?: return true.also { error(400, "Send JSON (see GET /api/v1/openapi.json).") }
    val sent = runCatching {
        val songs = runBlocking { Listened.songs(env, b.text("album"), b.text("albumTitle"), b.text("albumArtist"), b.text("service"), b.text("playlist"), b.text("text")) }
        Listened.send(env, songs, Listened.time(b.text("at")), b.flag("finished") ?: true, b.flag("lastfm") ?: (Listened.lastFm(env) != null), b.flag("listenbrainz") ?: (Listened.listenBrainz(env) != null))
    }.getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
    json(200, buildJsonObject {
        put("lastfm", sent.lastFm?.let { JsonPrimitive(it) } ?: JsonNull); put("listenbrainz", sent.listenBrainz?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tooOldForLastFm", sent.tooOldForLastFm); put("summary", Listened.summary(sent))
    })
    return true
}

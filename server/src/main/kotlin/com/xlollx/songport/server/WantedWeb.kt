package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.MusicBrainz
import com.xlollx.songport.sync.Wanted
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * "Missing on my server" ([Wanted]) on the page (Tools) and in the API: the tracks of the user's
 * streaming playlists that their own server lacks, one per line, by album or not, ticked when got,
 * downloaded as JSON, CSV, M3U, text or Lidarr's Custom List.
 */
internal fun Web.wantedList(query: Map<String, String>, withGot: Boolean): List<Wanted.Want> {
    val since = query["since"]?.takeIf { it.isNotBlank() }?.let { Wanted.parseSince(it) ?: throw IllegalArgumentException("\"since\" is a date (2026-11-01), a span back (24h, 7d, 2w) or epoch milliseconds.") } ?: 0
    val scope = Wanted.Scope.of(query["scope"]) ?: throw IllegalArgumentException("\"scope\" is servers (the default: Missing on my server) or all (the songs not found on every service).")
    return Wanted.list(env.store.data, { MusicBrainz.known(env, it) }, Wanted.Filter(query["sync"], query["service"], since, withGot, scope, query["on"]))
}

/**
 * "Save as File playlist" (issue #73): the list [query] asks for (as GET /wanted reads it) as a new
 * File playlist named [name]. Only ever File: never a playlist on the service the songs are missing on.
 */
internal fun Web.wantedToFilePlaylist(query: Map<String, String>, name: String?): Pair<com.xlollx.songport.model.Playlist, Int> {
    val list = wantedList(query, withGot = false)
    if (list.isEmpty()) throw IllegalArgumentException("Nothing to save: no song is missing there.")
    val pl = kotlinx.coroutines.runBlocking { Wanted.saveAsFilePlaylist(env, name ?: "", list) }
    return pl to list.size
}

/** GET /api/v1/wanted (the list, in a format) and POST /api/v1/wanted/got (tick or untick entries). */
internal fun Web.apiWanted(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    when {
        rest == listOf("wanted") && method == "GET" -> {
            val format = Wanted.Format.of(query["format"]) ?: return true.also { error(400, "\"format\" is json, csv, m3u, text or lidarr.") }
            val list = runCatching { wantedList(query, query["all"] == "true") }.getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
            if (format != Wanted.Format.JSON) return true.also { send(ex, 200, format.encode(list), format.mime) }
            json(200, buildJsonObject {
                put("count", list.size)
                if (query["group"] == "album") put("albums", JsonArray(Wanted.albums(list).map { a ->
                    buildJsonObject { put("artist", a.artist); put("album", a.album); put("tracks", Wanted.json(a.wants)) }
                }))
                else put("tracks", Wanted.json(list))
            })
        }
        rest == listOf("wanted", "file-playlist") && method == "POST" -> {
            val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"name\": \"Not found on Spotify\", \"scope\": \"all\", \"on\": \"spotify\"}.") }
            val asked = listOf("scope", "on", "sync", "service", "since").mapNotNull { k -> b.text(k)?.let { k to it } }.toMap()
            val (pl, n) = runCatching { wantedToFilePlaylist(asked, b.text("name")) }.getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
            json(200, buildJsonObject {
                put("playlist", buildJsonObject { put("service", com.xlollx.songport.providers.LocalFilesProvider.id); put("id", pl.id); put("name", pl.name) })
                put("count", n)
            })
        }
        rest == listOf("wanted", "got") && method == "POST" -> {
            val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"ids\": [...], \"got\": true}.") }
            val ids = (b["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return true.also { error(400, "\"ids\" is the list of entry ids (GET /api/v1/wanted).") }
            json(200, buildJsonObject { put("changed", Wanted.mark(env.store, ids, b.flag("got") ?: true)) })
        }
        else -> return false
    }
    return true
}

private val WANTED_FORMATS = listOf(Wanted.Format.CSV to "CSV", Wanted.Format.M3U to "M3U", Wanted.Format.TEXT to "Text", Wanted.Format.JSON to "JSON", Wanted.Format.LIDARR to "Lidarr list")

/** Tools › Missing on my server. */
internal fun Web.wantedPage(r: Web.Req) {
    val title = "Missing on my server"
    if (r.method == "POST") {
        val ids = r.rawForm.filter { it.first == "id" }.map { it.second }
        val got = r.form["action"] != "untick"
        val n = Wanted.mark(env.store, ids, got)
        val back = "/tools/wanted?" + listOfNotNull(r.form["view"]?.let { "view=${q(it)}" }, r.form["all"]?.let { "all=${q(it)}" }, r.form["sync"]?.takeIf { it.isNotEmpty() }?.let { "sync=${q(it)}" }).joinToString("&")
        return redirect(r.ex, "$back&ok=${q(if (got) "$n ticked: got them." else "$n back on the list.")}")
    }
    val all = r.query["all"] == "true"
    val list = runCatching { wantedList(r.query, all) }.getOrElse { return redirect(r.ex, "/tools/wanted?err=${q(it.message ?: "Wrong request.")}") }
    r.query["format"]?.let { f ->
        val format = Wanted.Format.of(f) ?: Wanted.Format.CSV
        val name = if (format == Wanted.Format.LIDARR) "missing-on-my-server-lidarr.json" else "missing-on-my-server.${format.ext}"
        return attachment(r, name, format.encode(list), format.mime)
    }
    val byAlbum = r.query["view"] == "albums"
    val sync = r.query["sync"].orEmpty()
    val servers = env.store.data.jobs.filter { Wanted.isServer(it.target.provider) }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p class="muted">The tracks of your playlists that your own server (Navidrome, Jellyfin, Emby, Plex, Lyrion, Mopidy) does not have yet: what your syncs to it could not find, each once. Tick what you got: it leaves the list. Nothing is downloaded or bought here.</p>""")
    if (servers.isEmpty()) {
        body.append("""<p>None of your syncs goes to your own server yet.</p></div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    // The choices are links (GET): the filter is part of the address, so a download keeps it.
    fun link(view: String? = r.query["view"], showAll: Boolean = all, s: String = sync, format: String? = null, tick: String? = null) =
        "/tools/wanted?" + listOfNotNull(view?.let { "view=${q(it)}" }, "all=$showAll".takeIf { showAll }, s.takeIf { it.isNotEmpty() }?.let { "sync=${q(it)}" }, format?.let { "format=$it" }, tick?.let { "tick=${q(it)}" }).joinToString("&")
    val opts = """<option value="">All syncs</option>""" + servers.joinToString("") { j -> """<option value="${h(j.id)}"${if (j.id == sync) " selected" else ""}>${h(j.name)}</option>""" }
    body.append("""<form method="get" action="/tools/wanted" class="row"><select name="sync" aria-label="Sync">$opts</select>""")
    if (byAlbum) body.append("""<input type="hidden" name="view" value="albums">""")
    if (all) body.append("""<input type="hidden" name="all" value="true">""")
    body.append("""<button class="secondary">Show</button></form>""")
    body.append("""<p>${if (byAlbum) """<a href="${h(link(view = null))}">One line per track</a>""" else """<a href="${h(link(view = "albums"))}">By album</a>"""} · """)
    // Only the ones ticked as got are added: with none ticked there is nothing to show, so no link, only the count.
    val ticked = runCatching { wantedList(r.query, true).count { it.got } }.getOrDefault(0)
    body.append(when {
        all -> """<a href="${h(link(showAll = false))}">Hide ticked ones ($ticked)</a>"""
        ticked > 0 -> """<a href="${h(link(showAll = true))}">Show ticked ones too ($ticked)</a>"""
        else -> """<span class="muted" title="Nothing is ticked as got yet">Show ticked ones too (0)</span>"""
    })
    body.append("""</p><p class="muted">Download: ${WANTED_FORMATS.joinToString(" · ") { (f, label) -> """<a href="${h(link(format = f.name.lowercase()))}" download>$label</a>""" }}</p>""")
    Api.listKey(env)?.takeIf { env.may(Permissions.API) }?.let { key ->
        body.append("""<p class="muted">Lidarr: Settings › Import Lists › Custom List, List URL <code>${h(env.baseUrl.trimEnd('/'))}/api/v1/wanted?format=lidarr&amp;key=$key</code> (this key opens this list only; a new API token changes it). Lidarr adds the artists whose MusicBrainz id is known.</p>""")
    }
    body.append("</div>")
    body.append("""<p class="muted">The songs not found on every service, not only your server: <a href="/tools/notfound">Songs not found</a>.</p>""")
    if (list.isEmpty()) {
        body.append("""<div class="card ok">Nothing missing${if (all) "" else " (or you got it all)"}.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val zone = ZoneId.systemDefault()
    // Select all / None and an album's All: plain links (the page works without JavaScript). "tick" is
    // "all", or the id of one entry: its album's rows come ticked (an id names one entry, so one album).
    val tick = r.query["tick"].orEmpty()
    val tickedAlbum = if (byAlbum && tick.isNotEmpty() && tick != "all") Wanted.albums(list).firstOrNull { a -> a.wants.any { it.id == tick } } else null
    fun ticked(w: Wanted.Want) = !w.got && (tick == "all" || tickedAlbum?.wants?.contains(w) == true)
    fun row(w: Wanted.Want, withAlbum: Boolean): String {
        val from = w.syncs.joinToString(", ") { h(it.syncName) }
        val services = w.syncs.map { it.source }.distinct().joinToString(", ") { Providers.byId(it)?.label(env) ?: it }
        val bits = listOfNotNull(
            w.album.takeIf { withAlbum && it.isNotBlank() }?.let { h(it) },
            w.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) },
            w.isrc?.let { "ISRC ${h(it)}" },
            w.recordingMbid?.let { """<a href="https://musicbrainz.org/recording/$it">MusicBrainz</a>""" },
            "from ${h(services)} ($from)",
            "since ${Instant.ofEpochMilli(w.firstSeen).atZone(zone).toLocalDate()}",
        )
        return """<label class="check"><input type="checkbox" form="wanted" name="id" value="${h(w.id)}"${if (w.got) " disabled" else if (ticked(w)) " checked" else ""}> <span><b>${h(w.artistLine)}</b> — ${h(w.title)}${if (w.got) " ✓" else ""}<br><span class="muted">${bits.joinToString(" · ")}</span></span></label>"""
    }
    body.append("""<form id="wanted" method="post" action="/tools/wanted">""")
    r.query["view"]?.let { body.append("""<input type="hidden" name="view" value="${h(it)}">""") }
    if (all) body.append("""<input type="hidden" name="all" value="true">""")
    if (sync.isNotEmpty()) body.append("""<input type="hidden" name="sync" value="${h(sync)}">""")
    body.append("""<p><b>${list.size}</b> tracks <button name="action" value="tick">Got the ticked ones</button></p></form>""")
    if (list.count { !it.got } > 1) body.append("""<p class="muted"><a href="${h(link(tick = "all"))}">Select all</a> · <a href="${h(link())}">None</a></p>""")
    if (byAlbum) Wanted.albums(list).forEachIndexed { i, a ->
        val open = a.wants.filter { !it.got }
        val pick = if (open.size > 1) """ <a href="${h(link(tick = open.first().id))}#album-$i" class="muted">All</a>""" else ""
        body.append("""<div class="card" id="album-$i"><h3>${h(a.artist)} — ${if (a.album.isBlank()) "<span class=\"muted\">no album</span>" else h(a.album)} <span class="muted">${a.wants.size}</span>$pick</h3>""")
        a.wants.forEach { body.append(row(it, withAlbum = false)) }
        body.append("</div>")
    } else {
        body.append("""<div class="card">""")
        list.forEach { body.append(row(it, withAlbum = true)) }
        body.append("</div>")
    }
    if (all && list.any { it.got }) {
        body.append("""<div class="card"><p class="muted">Put back on the list what you ticked by mistake:</p><form method="post" action="/tools/wanted">""")
        if (all) body.append("""<input type="hidden" name="all" value="true">""")
        list.filter { it.got }.forEach { w -> body.append("""<label class="check"><input type="checkbox" name="id" value="${h(w.id)}"> ${h(w.toString())}</label>""") }
        body.append("""<p><button class="secondary" name="action" value="untick">Not got after all</button></p></form></div>""")
    }
    send(r.ex, 200, page(title, body.toString()))
}

/**
 * Tools › Songs not found (issue #73): what every sync's last run could not find, on any service or
 * on one, downloaded in the formats of Missing on my server or saved as a new File playlist to sync
 * on anywhere (never a playlist on the service the songs are missing on). Works without JavaScript.
 */
internal fun Web.notFoundPage(r: Web.Req) {
    val title = "Songs not found"
    if (r.method == "POST") {
        val on = r.form["on"].orEmpty()
        val back = "/tools/notfound" + (if (on.isEmpty()) "?" else "?on=${q(on)}&")
        val (pl, n) = runCatching { wantedToFilePlaylist(mapOf("scope" to "all", "on" to on), r.form["name"]) }
            .getOrElse { return redirect(r.ex, back + "err=${q(it.message ?: "Wrong request.")}") }
        return redirect(r.ex, back + "ok=${q("Saved $n tracks in the File playlist \"${pl.name}\": sync it to any service from File playlists.")}")
    }
    val services = Wanted.missingOn(env.store.data)
    val on = r.query["on"].orEmpty().takeIf { s -> services.any { it.first == s } }.orEmpty()
    val list = Wanted.list(env.store.data, { MusicBrainz.known(env, it) }, Wanted.Filter(scope = Wanted.Scope.ALL, on = on.ifEmpty { null }))
    fun label(id: String) = Providers.all().firstOrNull { it.serviceId == id }?.displayName ?: id
    r.query["format"]?.let { f ->
        val format = Wanted.Format.of(f) ?: Wanted.Format.CSV
        val base = if (on.isEmpty()) "songs-not-found" else "songs-not-found-on-$on"
        return attachment(r, if (format == Wanted.Format.LIDARR) "$base-lidarr.json" else "$base.${format.ext}", format.encode(list), format.mime)
    }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p class="muted">The songs your syncs' last runs could not find, on every service, each once. Download them, or save them as a File playlist and sync it to any other service. For your own server's list, with ticks and Lidarr, see <a href="/tools/wanted">Missing on my server</a>.</p>""")
    if (services.isEmpty()) {
        body.append("""<p>Nothing missing: your syncs found every song.</p></div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val all = if (on.isEmpty()) list.size else Wanted.list(env.store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL)).size
    val opts = """<option value="">All services ($all)</option>""" + services.joinToString("") { (id, n) -> """<option value="${h(id)}"${if (id == on) " selected" else ""}>${h(label(id))} ($n)</option>""" }
    body.append("""<form method="get" action="/tools/notfound" class="row"><label>Missing on <select name="on">$opts</select></label> <button class="secondary">Show</button></form>""")
    fun link(format: String) = "/tools/notfound?" + listOfNotNull(on.takeIf { it.isNotEmpty() }?.let { "on=${q(it)}" }, "format=$format").joinToString("&")
    body.append("""<p class="muted">Download: ${WANTED_FORMATS.joinToString(" · ") { (f, l) -> """<a href="${h(link(f.name.lowercase()))}" download>$l</a>""" }}</p>""")
    val name = if (on.isEmpty()) "Not found" else "Not found on ${label(on)}"
    body.append("""<form method="post" action="/tools/notfound" class="row"><input type="hidden" name="on" value="${h(on)}"><label>Save as File playlist <input name="name" value="${h(name)}" required maxlength="200"></label> <button>Save</button></form></div>""")
    body.append("""<div class="card"><p><b>${list.size}</b> tracks</p>""")
    list.forEach { w ->
        val bits = listOfNotNull(w.album.takeIf { it.isNotBlank() }?.let { h(it) }, "from ${w.syncs.joinToString(", ") { h(it.syncName) }}")
        body.append("""<p><b>${h(w.artistLine)}</b> — ${h(w.title)}<br><span class="muted">${bits.joinToString(" · ")}</span></p>""")
    }
    body.append("</div>")
    send(r.ex, 200, page(title, body.toString()))
}

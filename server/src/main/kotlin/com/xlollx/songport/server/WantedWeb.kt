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
    return Wanted.list(env.store.data, { MusicBrainz.known(env, it) }, Wanted.Filter(query["sync"], query["service"], since, withGot))
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
    fun link(view: String? = r.query["view"], showAll: Boolean = all, s: String = sync, format: String? = null) =
        "/tools/wanted?" + listOfNotNull(view?.let { "view=${q(it)}" }, "all=$showAll".takeIf { showAll }, s.takeIf { it.isNotEmpty() }?.let { "sync=${q(it)}" }, format?.let { "format=$it" }).joinToString("&")
    val opts = """<option value="">All syncs</option>""" + servers.joinToString("") { j -> """<option value="${h(j.id)}"${if (j.id == sync) " selected" else ""}>${h(j.name)}</option>""" }
    body.append("""<form method="get" action="/tools/wanted" class="row"><select name="sync" aria-label="Sync">$opts</select>""")
    if (byAlbum) body.append("""<input type="hidden" name="view" value="albums">""")
    if (all) body.append("""<input type="hidden" name="all" value="true">""")
    body.append("""<button class="secondary">Show</button></form>""")
    body.append("""<p>${if (byAlbum) """<a href="${h(link(view = null))}">One line per track</a>""" else """<a href="${h(link(view = "albums"))}">By album</a>"""} · """)
    body.append(if (all) """<a href="${h(link(showAll = false))}">Hide what I got</a>""" else """<a href="${h(link(showAll = true))}">Show what I got too</a>""")
    body.append("""</p><p class="muted">Download: ${WANTED_FORMATS.joinToString(" · ") { (f, label) -> """<a href="${h(link(format = f.name.lowercase()))}" download>$label</a>""" }}</p>""")
    Api.listKey(env)?.takeIf { env.may(Permissions.API) }?.let { key ->
        body.append("""<p class="muted">Lidarr: Settings › Import Lists › Custom List, List URL <code>${h(env.baseUrl.trimEnd('/'))}/api/v1/wanted?format=lidarr&amp;key=$key</code> (this key opens this list only; a new API token changes it). Lidarr adds the artists whose MusicBrainz id is known.</p>""")
    }
    body.append("</div>")
    if (list.isEmpty()) {
        body.append("""<div class="card ok">Nothing missing${if (all) "" else " (or you got it all)"}.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val zone = ZoneId.systemDefault()
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
        return """<label class="check"><input type="checkbox" form="wanted" name="id" value="${h(w.id)}"${if (w.got) " disabled" else ""}> <span><b>${h(w.artistLine)}</b> — ${h(w.title)}${if (w.got) " ✓" else ""}<br><span class="muted">${bits.joinToString(" · ")}</span></span></label>"""
    }
    body.append("""<form id="wanted" method="post" action="/tools/wanted">""")
    r.query["view"]?.let { body.append("""<input type="hidden" name="view" value="${h(it)}">""") }
    if (all) body.append("""<input type="hidden" name="all" value="true">""")
    if (sync.isNotEmpty()) body.append("""<input type="hidden" name="sync" value="${h(sync)}">""")
    body.append("""<p><b>${list.size}</b> tracks <button name="action" value="tick">Got the ticked ones</button></p></form>""")
    if (byAlbum) Wanted.albums(list).forEach { a ->
        body.append("""<div class="card"><h3>${h(a.artist)} — ${if (a.album.isBlank()) "<span class=\"muted\">no album</span>" else h(a.album)} <span class="muted">${a.wants.size}</span></h3>""")
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

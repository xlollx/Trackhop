package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.QrCode
import com.xlollx.songport.sync.UniversalLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The universal link (the app's Discover › Share as a link, [UniversalLink]): a playlist as a
 * trackhop.org link that opens on any service, the songs in the link itself (nothing is uploaded);
 * a long playlist takes several links. A QR code to show it, where the link is short enough for one.
 */
internal object ShareLink {
    /** [text] as a QR code in SVG, dark on light whatever the theme; null when it is too long for one. */
    fun qrSvg(text: String, px: Int = 180): String? {
        val s = runCatching { QrCode.encode(text) }.getOrNull() ?: return null
        val quiet = 4
        val n = s.size + quiet * 2
        val path = StringBuilder()
        for (y in 0 until s.size) for (x in 0 until s.size) if (s[x, y]) path.append("M${x + quiet} ${y + quiet}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $n $n" width="$px" height="$px" role="img" aria-label="QR code of the link" shape-rendering="crispEdges"><rect width="$n" height="$n" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }

    fun parts(name: String, tracks: List<Track>) = UniversalLink.buildParts(name.ifBlank { "Playlist" }, tracks)
}

/** The playlist page's "Share as a link" card. */
internal fun Web.shareLinkCard(p: MusicProvider, playlist: Playlist, tracks: List<Track>): String {
    val parts = ShareLink.parts(playlist.name, tracks)
    if (parts.isEmpty() || parts.first().total == 0) return ""
    val rows = parts.joinToString("") { b ->
        val qr = ShareLink.qrSvg(b.url)
        """<div class="row" style="align-items:flex-start;flex-wrap:nowrap"><div style="flex:1;min-width:0">${if (parts.size > 1) """<p class="muted">Part ${b.part} of ${b.parts}: songs ${b.first}–${b.last}</p>""" else ""}<input type="text" readonly value="${h(b.url)}" aria-label="The link" onclick="this.select()"><p><a href="${h(b.url)}" target="_blank" rel="noopener">Open it</a></p></div>${qr ?: ""}</div>"""
    }
    return """<details class="card"><summary><b>Share as a link</b></summary><p class="muted">A link that opens ${h(playlist.name)} on any service: the songs travel in the link itself, nothing is uploaded.${if (parts.first().total > parts.sumOf { it.included }) " Up to ${UniversalLink.MAX_TRACKS} songs in each." else ""}</p>$rows</details>"""
}

/** GET /services/{service}/playlists/{playlist}/link */
internal fun Web.apiShareLink(method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!(rest.size == 5 && rest[0] == "services" && rest[2] == "playlists" && rest[4] == "link" && method == "GET")) return false
    val p = com.xlollx.songport.providers.Providers.byId(rest[1])?.takeIf { !it.requiresAuth || it.isConnected(env) } ?: return true.also { error(404, "No connected service with that id.") }
    val r = runCatching {
        runBlocking {
            val all = p.libraryEntries(env, false) + p.playlists(env)
            val pl = all.firstOrNull { it.id == rest[3] } ?: all.filter { it.name.equals(rest[3], ignoreCase = true) }.singleOrNull() ?: return@runBlocking null
            pl to p.tracks(env, pl.id)
        }
    }.getOrElse { return true.also { _ -> error(502, it.message ?: "Error") } } ?: return true.also { error(404, "${p.label(env)} has no playlist \"${rest[3]}\".") }
    json(200, buildJsonObject {
        put("name", r.first.name)
        put("links", JsonArray(ShareLink.parts(r.first.name, r.second).map { b ->
            buildJsonObject { put("url", b.url); put("part", b.part); put("parts", b.parts); put("first", b.first); put("last", b.last); put("songs", b.included); put("total", b.total); put("qr", ShareLink.qrSvg(b.url)?.let { JsonPrimitive(it) } ?: JsonNull) }
        }))
    })
    return true
}

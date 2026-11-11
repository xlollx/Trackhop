package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Merch
import com.xlollx.songport.sync.Records
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Records and merch of a playlist's artists ([Records]) on the page (Tools › Records and merch),
 * in the API (GET /api/v1/records) and, through it, the command line and MCP. Only when asked.
 * The Discogs token is optional, the server's own setting (Settings › Discogs token), kept with
 * the sign-ins and never shown again.
 */
internal object RecordTexts {
    const val SLOWED = "Discogs asked to slow down: some artists have no records this time. Try again in a minute."
    const val REFUSED = "Discogs refused the token: check it in Settings › Discogs token."
    const val NO_TOKEN = "Add your own Discogs token (Settings) to see how many copies are for sale and to look up more artists."
    const val ATTRIBUTION = "${Records.ATTRIBUTION} (records) and MusicBrainz (links); merch from the artists' own shops. Plain links, no affiliate tags."
    const val NO_MERCH = "No merch found in their shops."

    fun limited(shown: Int, total: Int) = "Only the $shown artists the playlist holds most were looked up, of $total."

    fun notes(r: Records.Result): List<String> = listOfNotNull(
        SLOWED.takeIf { r.slowedDown }, REFUSED.takeIf { r.tokenRefused }, NO_TOKEN.takeIf { !r.token && r.look.records },
        limited(r.artists.size, r.total).takeIf { r.total > r.artists.size },
    )

    fun kindName(k: Records.Kind) = when (k) {
        Records.Kind.OFFICIAL -> "Official site"
        Records.Kind.SHOP -> "Official shop"
        Records.Kind.BANDCAMP -> "Bandcamp"
        Records.Kind.DOWNLOAD -> "Download store"
        Records.Kind.SOCIAL -> "Social"
        Records.Kind.DISCOGS -> "Discogs"
    }

    fun forSale(rec: Records.Record): String? {
        val n = rec.forSale ?: return null
        if (n == 0 || rec.lowestPrice == null) return "None for sale right now"
        return "$n for sale from ${String.format(Locale.ROOT, "%.2f", rec.lowestPrice)} ${rec.currency.orEmpty()}".trim()
    }

    fun sourceName(s: Merch.Source) = if (s == Merch.Source.BANDCAMP) "Bandcamp" else "Official shop"

    /** "35.00 GBP", or the bare amount when the shop does not say its currency. */
    fun price(i: Merch.Item): String? = i.price?.let { "${String.format(Locale.ROOT, "%.2f", it)} ${i.currency.orEmpty()}".trim() }

    /** "all", "records" or "merch" for the API and the page. */
    fun lookName(l: Records.Look) = l.name.lowercase()
}

private fun Web.canReadForRecords(p: MusicProvider) =
    (!p.requiresAuth || p.isConnected(env)) && !p.linkOnly && p.serviceId != "lastfm" && p.serviceId != "listenbrainz"

private fun Web.readableForRecords(): List<MusicProvider> =
    (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { canReadForRecords(it) }

private suspend fun Web.recordPlaylists(p: MusicProvider): List<Playlist> = p.libraryEntries(env, false) + p.playlists(env)

private suspend fun Web.recordsOf(p: MusicProvider, playlistId: String, currency: String, look: Records.Look, progress: (Int, Int) -> Unit = { _, _ -> }): Records.Result {
    val artists = Concerts.artistsOf(p.tracks(env, playlistId))
    val r = Records.find(env, artists, currency, look = look, progress = progress)
    env.log("records", "${p.label(env)} / $playlistId: ${r.artists.count { !it.records.isNullOrEmpty() }} of ${r.artists.size} artists with records")
    return r
}

internal fun recordsJson(r: Records.Result): JsonObject = buildJsonObject {
    put("artistsInPlaylist", r.total)
    put("artistsLookedUp", r.artists.size)
    put("discogsToken", r.token)
    put("tokenRefused", r.tokenRefused)
    put("slowedDown", r.slowedDown)
    put("currency", r.currency)
    put("kind", RecordTexts.lookName(r.look))
    put("notes", JsonArray(RecordTexts.notes(r).map { JsonPrimitive(it) }))
    put("artists", JsonArray(r.artists.map { a ->
        buildJsonObject {
            put("artist", a.artist)
            put("records", a.records?.let { list ->
                JsonArray(list.map { rec ->
                    buildJsonObject {
                        put("title", rec.title); put("year", rec.year?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("formats", JsonArray(rec.formats.map { JsonPrimitive(it.label) }))
                        put("url", rec.url)
                        if (rec.forSale != null) {
                            put("forSale", rec.forSale); put("lowestPrice", rec.lowestPrice?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("currency", rec.currency?.let { JsonPrimitive(it) } ?: JsonNull); put("sellUrl", rec.sellUrl)
                        }
                    }
                })
            } ?: JsonNull)
            put("discogsPage", a.discogsPage)
            put("links", JsonArray(a.links.map { l -> buildJsonObject { put("kind", RecordTexts.kindName(l.kind)); put("site", l.label); put("url", l.url) } }))
            put("searches", JsonArray(a.searches.map { s -> buildJsonObject { put("site", s.site ?: "Web"); put("url", s.url) } }))
            put("merch", a.merch?.let { list ->
                JsonArray(list.map { i ->
                    buildJsonObject {
                        put("title", i.title); put("type", i.type); put("source", RecordTexts.sourceName(i.source))
                        put("image", i.image); put("price", i.price?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("currency", i.currency?.let { JsonPrimitive(it) } ?: JsonNull); put("url", i.url)
                    }
                })
            } ?: JsonNull)
        }
    }))
    put("attribution", RecordTexts.ATTRIBUTION)
}

/** GET /api/v1/records?service=&playlist=&currency=&kind= */
internal fun Web.apiRecords(method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("records") || method != "GET") return false
    val able = readableForRecords()
    val asked = query["service"] ?: query["connector"]
    val p = if (asked != null) Providers.byId(asked)?.takeIf { canReadForRecords(it) } ?: return true.also { error(404, "No connected service \"$asked\": see GET /api/v1/services.") }
        else able.singleOrNull() ?: return true.also { error(400, "Say which service: ?service= one of ${able.joinToString(", ") { it.id }}.") }
    val wanted = query["playlist"]?.trim()?.takeIf { it.isNotEmpty() } ?: return true.also { error(400, "Say which playlist: ?playlist= its id or name (GET /api/v1/services/${p.id}/playlists).") }
    val currency = query["currency"]?.uppercase()?.let { c -> c.takeIf { it in Records.CURRENCIES } ?: return true.also { error(400, "\"currency\" is one of ${Records.CURRENCIES.sorted().joinToString(", ")}.") } }
        ?: Records.currencyFor()
    val look = query["kind"]?.let { k -> Records.Look.of(k) ?: return true.also { error(400, "\"kind\" is one of all, records, merch.") } } ?: Records.Look.ALL
    val r = runCatching {
        runBlocking {
            val lists = recordPlaylists(p)
            val pl = lists.firstOrNull { it.id == wanted } ?: lists.filter { it.name.equals(wanted, ignoreCase = true) }.singleOrNull()
                ?: return@runBlocking null
            recordsOf(p, pl.id, currency, look)
        }
    }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
        ?: return true.also { error(404, "No playlist \"$wanted\" on ${p.label(env)} (or more than one with that name: use its id).") }
    json(200, recordsJson(r))
    return true
}

/** Tools › Records and merch: a service and a playlist, then the records as a background task. */
internal fun Web.recordsPage(r: Web.Req) {
    val title = "Records and merch"
    val params = if (r.method == "POST") r.form else r.query
    val body = StringBuilder(notice(r))
    val able = readableForRecords()
    if (able.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val tokenLine = if (Records.token(env) != null) "Discogs: your own token." else """No Discogs token: records without prices. <a href="/settings#discogs">Add yours</a>."""
    val intro = """<p class="muted">The CDs, vinyl and cassettes of a playlist's artists on Discogs, the merch in their own shops (official shop on Shopify, Bandcamp), their official sites and shops from MusicBrainz, and searches on shops. Only links, no affiliate tags: you buy on their sites. $tokenLine</p>"""
    val service = params["service"]?.let { id -> able.firstOrNull { it.id == id } }
    if (service == null) {
        val opts = able.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/records" class="card">$intro<label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val lists = runCatching { runBlocking { recordPlaylists(service) } }
        .getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(it.message)}</div>""")) }
    val playlist = params["playlist"]?.let { id -> lists.firstOrNull { it.id == id } }
    val look = Records.Look.of(params["kind"]) ?: Records.Look.ALL
    if (r.method == "POST" && playlist != null) {
        val task = Tasks.start(title, "/tools/records?service=${q(service.id)}&playlist=${q(playlist.id)}&kind=${RecordTexts.lookName(look)}", { env.log("records", it) }, env.owner) { t ->
            t.progress = "Reading the playlist"
            val res = recordsOf(service, playlist.id, Records.currencyFor(), look) { done, total -> t.progress = "Artist ${minOf(done + 1, total)} of $total" }
            recordsHtml(playlist.name, res)
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}"${if (p.id == playlist?.id) " selected" else ""}>${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
    body.append("""<form method="post" action="/tools/records" class="card">$intro<input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">$opts</select>
<label>Show</label><select name="kind">${Records.Look.entries.joinToString("") { l -> """<option value="${RecordTexts.lookName(l)}"${if (l == look) " selected" else ""}>${when (l) { Records.Look.ALL -> "All"; Records.Look.RECORDS -> "CD & vinyl"; Records.Look.MERCH -> "Merch" }}</option>""" }}</select>
<p><button>Find records</button> <a class="button secondary" href="/tools/records">Another service</a></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

private fun a(url: String, text: String) = """<a href="${h(url)}" rel="noopener noreferrer" target="_blank">${h(text)}</a>"""

/** The result, as the task's page shows it. */
private fun recordsHtml(playlist: String, r: Records.Result): String = buildString {
    append("""<p class="muted">${h(playlist)}</p>""")
    val notes = RecordTexts.notes(r)
    if (notes.isNotEmpty()) {
        append("""<div class="card">""")
        notes.forEach { append("""<p class="muted">${h(it)}</p>""") }
        if (r.look.records) append("""<p><a href="/settings#discogs">Discogs token</a></p>""")
        append("</div>")
    }
    r.artists.forEach { ar ->
        append("""<div class="card"><h3>${h(ar.artist)}</h3>""")
        val recs = ar.records
        if (r.look.records) when {
            recs == null -> append("""<p class="muted">${if (r.slowedDown) "Not looked up this time: try again in a minute." else "Discogs didn't answer: try again later."}</p>""")
            recs.isEmpty() -> append("""<p class="muted">Nothing on CD, vinyl or cassette on Discogs.</p>""")
            else -> {
                append("<ul>")
                recs.forEach { rec ->
                    val what = listOfNotNull(rec.year?.toString()).plus(rec.formats.map { it.label }).joinToString(" · ")
                    append("""<li>${a(rec.url, rec.title)} <span class="muted">${h(what)}</span>""")
                    RecordTexts.forSale(rec)?.let { s -> append(" · ").append(if (rec.forSale!! > 0) a(rec.sellUrl, s) else h(s)) }
                    append("</li>")
                }
                append("</ul>")
                append("""<p class="muted">${a(ar.discogsPage, Records.ATTRIBUTION)}</p>""")
            }
        }
        ar.merch?.let { items ->
            append("<p>Merch:</p>")
            if (items.isEmpty()) append("""<p class="muted">${RecordTexts.NO_MERCH}</p>""")
            else {
                append("<ul>")
                items.forEach { i ->
                    val what = listOfNotNull(i.type.takeIf { it.isNotBlank() }, RecordTexts.price(i), RecordTexts.sourceName(i.source)).joinToString(" · ")
                    append("""<li>${a(i.url, i.title)} <span class="muted">${h(what)}</span></li>""")
                }
                append("</ul>")
            }
        }
        if (ar.links.isNotEmpty()) append("<p>Official pages: ").append(ar.links.joinToString(" · ") { l ->
            a(l.url, when (l.kind) { Records.Kind.OFFICIAL -> "Official site"; Records.Kind.SHOP -> "Official shop"; else -> l.label })
        }).append("</p>")
        append("<p>Search the shops: ").append(ar.searches.joinToString(" · ") { s -> a(s.url, s.site ?: "Web") }).append("</p></div>")
    }
    append("""<p class="muted">${h(RecordTexts.ATTRIBUTION)}</p>""")
}

/** Settings › Discogs token: the card, and its form's handling. */
internal fun Web.discogsTokenCard(): String {
    val own = Records.token(env) != null
    return """<form method="post" action="/settings" class="card" id="discogs"><input type="hidden" name="action" value="discogs"><h3>Discogs token</h3>
<p class="muted">Optional, for Tools › Records and merch. A personal access token from <a href="${Records.TOKEN_URL}" rel="noopener noreferrer" target="_blank">your Discogs account (Settings › Developers)</a> shows how many copies are for sale and from what price, and looks up more artists (Discogs allows 60 requests a minute with it, 25 without). Trackhop only reads with it. ${if (own) "Your token is saved." else "None saved."}</p>
<label>Personal access token</label><input name="token" autocomplete="off" placeholder="${if (own) "saved: leave empty to keep it" else ""}">
<p class="actions"><button>Save</button>${if (own) """ <button name="remove" value="1" class="secondary">Remove</button>""" else ""}</p></form>"""
}

internal fun Web.saveDiscogsToken(form: Map<String, String>) {
    when {
        form["remove"] == "1" -> Records.saveToken(env, "")
        !form["token"].isNullOrBlank() -> Records.saveToken(env, form["token"]!!)
    }
}

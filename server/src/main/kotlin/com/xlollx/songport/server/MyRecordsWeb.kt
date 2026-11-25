package com.xlollx.songport.server

import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Discogs
import com.xlollx.songport.sync.Records
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * My records (the app's MyRecordsPage, [Discogs]): your Discogs collection, with what it is worth,
 * and your wantlist, with the copies for sale, read with your own Discogs token. The collection's
 * songs can become a playlist.
 */
private fun discogsLink(i: Discogs.Item, extra: String = "") = """<li><a href="${h(i.url)}" target="_blank" rel="noopener">${h(i.title)}</a> <span class="muted">· ${h(i.artist)}${i.year?.let { " · $it" } ?: ""}${if (i.formats.isNotEmpty()) " · ${h(i.formats.joinToString(", "))}" else ""}$extra</span></li>"""

internal fun Web.myRecordsPage(r: Web.Req) {
    val back = "/tools/myrecords"
    val wants = (if (r.method == "POST") r.form else r.query)["kind"] == "wantlist"
    val here = if (wants) "$back?kind=wantlist" else back
    if (Records.token(env) == null) {
        return send(r.ex, 200, page("My records", """<div class="card"><p>Your Discogs collection and wantlist, read with your own Discogs token.</p><p><a href="/settings#discogs">Add your Discogs token</a> (Discogs › Settings › Developers › Generate new token).</p></div>"""))
    }
    if (r.method == "POST") {
        val currency = Records.currencyFor()
        val title = if (r.form["action"] == "playlist") "My records as a playlist" else "Reading Discogs"
        val task = Tasks.startOnce(title, here, { env.log("discogs", it) }, env.owner, env.marks) { t ->
            if (r.form["action"] == "playlist") {
                val tracks = Discogs.collectionTracks(env) { d, n -> t.progress = "Record $d of $n" }
                val id = LocalFilesProvider.importTracks(env, "My records", tracks)
                """<div class="card ok">${tracks.size} songs of your records saved as the File playlist <b>${h(id)}</b>. <a href="/syncs/new?src=csv&amp;srcPlaylist=${q(id)}">Send it to a service</a></div>"""
            } else {
                if (wants) Discogs.wantlist(env, currency, env.store.data.settings.discogsWantAlerts.keys, refresh = true) { d, n -> t.progress = "Page $d of $n" }
                else Discogs.collection(env, refresh = true) { d, n -> t.progress = "Page $d of $n" }
                """<div class="card ok">Read. <a href="${h(here)}">See them</a></div>"""
            }
        } ?: return redirect(r.ex, "$here${if ('?' in here) "&" else "?"}err=${q("Already reading.")}")
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val body = StringBuilder(notice(r))
    fun chip(text: String, on: Boolean, href: String) = """<a class="chip${if (on) " on" else ""}" href="$href"${if (on) " aria-current=\"true\"" else ""}>$text</a>"""
    val c = Discogs.keptCollection(env)
    val w = Discogs.keptWantlist(env)
    body.append("""<p class="chips">${chip("Collection${c?.let { " (${it.items.size})" } ?: ""}", !wants, back)}${chip("Wantlist${w?.let { " (${it.items.size})" } ?: ""}", wants, "$back?kind=wantlist")}</p>""")
    val refresh = Html.postButton(back, if ((if (wants) w else c) == null) "Read it from Discogs" else "Read it again", "secondary", if (wants) mapOf("kind" to "wantlist") else emptyMap())
    if (!wants) {
        if (c == null) body.append("""<div class="card"><p>Your Discogs collection, with what it is worth.</p>$refresh</div>""")
        else {
            body.append("""<div class="card"><p><b>${h(c.user)}</b>: ${c.items.size} records${c.value?.let { v -> " · worth ${h(v.minimum)} to ${h(v.maximum)} (median ${h(v.median)})" } ?: ""}. <span class="muted">Read ${Html.date(c.at)}.</span></p>
<div class="actions">$refresh ${Html.postButton(back, "Make a playlist of their songs", "secondary", mapOf("action" to "playlist"))}</div><ul>${c.items.take(500).joinToString("") { discogsLink(it) }}</ul></div>""")
        }
    } else {
        if (w == null) body.append("""<div class="card"><p>Your Discogs wantlist, with the copies for sale.</p>$refresh</div>""")
        else body.append("""<div class="card"><p><b>${h(w.user)}</b>: ${w.items.size} wanted. <span class="muted">Read ${Html.date(w.at)}.</span></p>$refresh<ul>${w.items.take(500).joinToString("") { i ->
            discogsLink(i, w.markets[i.id.toString()]?.let { m -> " · ${m.forSale} for sale" + (m.lowest?.let { p -> " from ${"%.2f".format(p)} ${h(m.currency.orEmpty())}" } ?: "") } ?: "")
        }}</ul></div>""")
    }
    send(r.ex, 200, page("My records", body.toString()))
}

/** GET /records/mine?kind=collection|wantlist&refresh=true */
internal fun Web.apiMyRecords(method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("records", "mine") || method != "GET") return false
    val kind = query["kind"] ?: "collection"
    if (kind !in setOf("collection", "wantlist")) return true.also { error(400, "kind= is collection or wantlist.") }
    if (Records.token(env) == null) return true.also { error(409, "Add your Discogs token first (Settings).") }
    val refresh = query["refresh"] == "true"
    val r = runCatching {
        runBlocking {
            if (kind == "wantlist") Json.encodeToJsonElement(Discogs.Wantlist.serializer(), if (!refresh) Discogs.keptWantlist(env) ?: Discogs.wantlist(env, Records.currencyFor(), env.store.data.settings.discogsWantAlerts.keys) else Discogs.wantlist(env, Records.currencyFor(), env.store.data.settings.discogsWantAlerts.keys, refresh = true))
            else Json.encodeToJsonElement(Discogs.Collection.serializer(), if (!refresh) Discogs.keptCollection(env) ?: Discogs.collection(env) else Discogs.collection(env, refresh = true))
        }
    }.getOrElse { return true.also { _ -> error(502, it.message ?: "Error") } }
    json(200, r)
    return true
}

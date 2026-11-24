package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.TrackLinks
import com.xlollx.songport.sync.TrackLookup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A song's link from any music app into one of your playlists (the app's ShareTrackSheet): the link
 * is read (your own account of its service, else song.link towards a connected one, else the
 * service's public page), the same recording is looked for on the service picked (its own id where
 * song.link knows it, else the best of a search, with the other candidates), and the one chosen is
 * added to a playlist there.
 */
internal object TrackLink {
    data class Found(val source: Track?, val exact: Track?, val candidates: List<Pair<Track, Double>>) {
        val best: Track? get() = exact ?: candidates.firstOrNull()?.takeIf { it.second >= Matcher.DEFAULT_THRESHOLD }?.first
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /** The song behind [link]: from a connected account of its own service, else via song.link, else public pages. */
    suspend fun describe(env: Env, ref: TrackLinks.Ref, link: String): Track? {
        val connected = Providers.connectors().filter { it.requiresAuth && it.isConnected(env) }
        connected.firstOrNull { TrackLinks.matches(ref.service, it.serviceId) }?.let { own ->
            attempt { own.track(env, ref.trackId) }?.takeIf { it.title.isNotBlank() }?.let { return it }
        }
        for (p in connected.filter { !TrackLinks.matches(ref.service, it.serviceId) }) {
            val there = attempt { TrackLinks.viaOdesli(link, p.serviceId) } ?: continue
            attempt { p.track(env, there.trackId) }?.takeIf { it.title.isNotBlank() }?.let { return it }
        }
        return TrackLookup.describe(ref, link)
    }

    suspend fun find(env: Env, link: String, dst: MusicProvider): Found {
        val ref = TrackLinks.parse(link) ?: throw IllegalArgumentException("Not a link to a song.")
        val source = describe(env, ref, link)
        val exact = if (TrackLinks.matches(ref.service, dst.serviceId)) attempt { dst.track(env, ref.trackId) }
            else attempt { TrackLinks.viaOdesli(link, dst.serviceId) }?.let { r -> attempt { dst.track(env, r.trackId) } }
        if (exact != null || source == null || source.title.isBlank()) return Found(source, exact, emptyList())
        val hits = withTimeoutOrNull(30_000) { attempt { dst.search(env, source) } }.orEmpty()
        val scored = hits.map { it to Matcher.score(source, it) }.sortedByDescending { it.second }.take(5)
        return Found(source, null, scored)
    }
}

private fun Web.linkTargets() = Providers.connectors().filter { it.canWrite && !it.linkOnly && (!it.requiresAuth || it.isConnected(env)) }

private fun Web.ownPlaylists(p: MusicProvider) = runBlocking { p.libraryEntries(env, true) + p.playlists(env).filter { it.ownedByMe } }

internal fun Web.addLinkPage(r: Web.Req) {
    val title = "Add a song from a link"
    val targets = linkTargets()
    if (r.method == "POST") {
        val p = targets.firstOrNull { it.id == r.form["service"] } ?: return redirect(r.ex, "/tools/addlink?err=${q("Pick a service.")}")
        val playlist = r.form["playlist"].orEmpty()
        val chosen = r.form["track"].orEmpty()
        val result = runCatching {
            runBlocking {
                val pl = ownPlaylists(p).firstOrNull { it.id == playlist } ?: throw IllegalArgumentException("Pick one of your playlists.")
                val t = ApiPlaylists.find(env, p, buildJsonObject { put("id", chosen) }) ?: throw IllegalArgumentException("Pick the song.")
                p.addTracks(env, pl.id, listOf(t))
                "Added ${t.artistLine} – ${t.title} to ${pl.name}."
            }
        }
        return redirect(r.ex, "/tools/addlink?" + result.fold({ "ok=${q(it)}" }, { "err=${q(it.message ?: "Error")}" }))
    }
    val body = StringBuilder(notice(r))
    val link = r.query["link"].orEmpty().trim()
    val p = targets.firstOrNull { it.id == r.query["service"] }
    val opts = targets.joinToString("") { """<option value="${h(it.id)}"${if (it === p) " selected" else ""}>${h(it.label(env))}</option>""" }
    body.append("""<p class="muted">A song's link from any music app (a friend's Spotify link, a YouTube video) into one of your playlists on any service.</p>""")
    if (targets.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    body.append("""<form method="get" action="/tools/addlink" class="card"><label for="al">Link</label><input type="url" id="al" name="link" value="${h(link)}" placeholder="https://open.spotify.com/track/…" required><label>On</label><select name="service">$opts</select><p><button>Find it</button></p></form>""")
    if (link.isNotEmpty() && p != null) {
        val found = runCatching { runBlocking { TrackLink.find(env, link, p) } }.getOrElse { e ->
            body.append("""<div class="card err">${h(e.message)}</div>""")
            return send(r.ex, 200, page(title, body.toString()))
        }
        val src = found.source
        body.append("""<div class="card"><p>${if (src != null && src.title.isNotBlank()) "<b>${h(src.artistLine)} – ${h(src.title)}</b>" else "The link's song could not be read"}</p>""")
        val choices = (listOfNotNull(found.exact?.let { it to 1.0 }) + found.candidates).distinctBy { it.first.id }
        if (choices.isEmpty()) body.append("""<p class="muted">Not found on ${h(p.label(env))}.</p></div>""")
        else {
            val lists = runCatching { ownPlaylists(p) }.getOrDefault(emptyList())
            body.append("""<form method="post" action="/tools/addlink"><input type="hidden" name="service" value="${h(p.id)}"><label>On ${h(p.label(env))}</label>""")
            choices.forEachIndexed { i, (t, s) ->
                body.append("""<label class="check"><input type="radio" name="track" value="${h(t.id)}"${if (i == 0) " checked" else ""}> ${h(t.artistLine)} – ${h(t.title)}${t.album.takeIf { it.isNotBlank() }?.let { " <span class=\"muted\">(${h(it)})</span>" } ?: ""} <span class="muted">· ${Math.round(s * 100)}%</span></label>""")
            }
            val plOpts = lists.joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }
            body.append("""<label>Into</label><select name="playlist" required>$plOpts</select><p><button>Add</button></p></form></div>""")
        }
    }
    send(r.ex, 200, page(title, body.toString()))
}

/** POST /tracks/link: the song a link is about on a service, and, with a playlist, added there. */
internal fun Web.apiTrackLink(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tracks", "link") || method != "POST") return false
    val b = body() ?: return true.also { error(400, "Send JSON: {\"link\", \"service\", \"playlist\"?, \"choice\"?}.") }
    val link = b.text("link") ?: return true.also { error(400, "\"link\" is needed: a song's link from any service.") }
    val p = linkTargets().firstOrNull { it.id == b.text("service") } ?: return true.also { error(404, "No connected service that takes songs with that id.") }
    val found = runCatching { runBlocking { TrackLink.find(env, link, p) } }.getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
    val playlist = b.text("playlist")
    var added: com.xlollx.songport.model.Track? = null
    if (playlist != null) {
        val r = runCatching {
            runBlocking {
                val pl = ownPlaylists(p).let { all -> all.firstOrNull { it.id == playlist } ?: all.filter { it.name.equals(playlist, ignoreCase = true) }.singleOrNull() }
                    ?: throw IllegalArgumentException("${p.label(env)} has no playlist of yours \"$playlist\".")
                val t = b.text("choice")?.let { ApiPlaylists.find(env, p, buildJsonObject { put("id", it) }) } ?: found.best ?: throw IllegalArgumentException("Not found on ${p.label(env)}: name a candidate's id as \"choice\".")
                p.addTracks(env, pl.id, listOf(t))
                t
            }
        }
        added = r.getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
    }
    json(200, buildJsonObject {
        put("source", found.source?.let { trackRef(it) } ?: JsonNull)
        put("exact", found.exact?.let { trackRef(it) } ?: JsonNull)
        put("candidates", JsonArray(found.candidates.map { (t, s) -> buildJsonObject { put("track", trackRef(t)); put("score", Math.round(s * 100)) } }))
        put("best", found.best?.let { trackRef(it) } ?: JsonNull)
        put("added", added?.let { trackRef(it) } ?: JsonNull)
    })
    return true
}

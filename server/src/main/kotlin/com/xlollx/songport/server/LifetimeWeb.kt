package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.ListeningExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * All-time stats (the app's LifetimePage, [ListeningExport]): the listening history you asked your
 * service for (Spotify's extended streaming history, YouTube's Takeout, Deezer's export), added up:
 * hours, songs, artists, the years, the hours of the day. Only the sums are kept; the listens
 * themselves stay in memory until Trackhop restarts, to be sent to ListenBrainz if you ask.
 */
internal object Lifetime {
    /** The last export's listens, per person, for "Send to ListenBrainz": never written to disk. */
    val plays = ConcurrentHashMap<String, List<ListeningExport.Play>>()

    fun read(env: ServerEnv, file: java.io.File): ListeningExport.Stats {
        val builder = ListeningExport.Builder()
        val all = ArrayList<ListeningExport.Play>()
        file.inputStream().use { ListeningExport.read(it) { p -> builder.add(p); all += p } }
        plays[env.owner] = all
        return builder.build().also { ListeningExport.keep(env, it) }
    }

    fun amount(s: ListeningExport.Stats, ms: Long, n: Int) = if (s.timed) "${"%,d".format(ms / 3_600_000)} h" else "×${"%,d".format(n)}"
}

/** POST /tools/lifetime from the page: the export goes to disk as it arrives (it can be big), is read, and is deleted. */
internal fun Web.lifetimeUpload(ex: HttpExchange, type: String) {
    val dir = LibraryUploads.dir(env)
    val parts = try { MultipartToDisk.read(type, ex.requestBody, dir, LibraryUploads.budget(dir)) } catch (e: Exception) {
        return redirect(ex, "/tools/lifetime?err=${q(if (e is MultipartToDisk.TooLarge) "This file is too big for the space left here." else "The upload stopped before the end: try again.")}")
    }
    val to = try {
        val f = parts.files["file"]
        if (f == null) "/tools/lifetime?err=${q("Choose your export's file.")}"
        else runCatching { Lifetime.read(env, f.file) }.fold(
            { s -> "/tools/lifetime?ok=${q("Read: ${"%,d".format(s.plays)} listens.")}" },
            { "/tools/lifetime?err=${q(if (it is ListeningExport.NotAnExport) "No listening history in this file: upload the .zip (or the .json files) of your service's export." else it.message ?: "Error")}" },
        )
    } finally {
        parts.files.values.forEach { it.file.delete() }
    }
    redirect(ex, to)
}

internal fun Web.lifetimePage(r: Web.Req) {
    val back = "/tools/lifetime"
    if (r.method == "POST") {
        when (r.form["action"]) {
            "forget" -> { ListeningExport.keep(env, null); Lifetime.plays.remove(env.owner); return redirect(r.ex, "$back?ok=${q("Forgotten.")}") }
            "playlist" -> {
                val s = ListeningExport.kept(env) ?: return redirect(r.ex, back)
                val id = LocalFilesProvider.importTracks(env, "All-time top songs", ListeningExport.topTracks(s))
                return redirect(r.ex, "/connectors/csv?ok=${q("Saved as the File playlist $id.")}")
            }
            "listenbrainz" -> {
                val token = ArtistMixSource.listenBrainzToken(env) ?: return redirect(r.ex, "$back?err=${q("Connect ListenBrainz with your user token first.")}")
                val plays = Lifetime.plays[env.owner] ?: return redirect(r.ex, "$back?err=${q("Upload the export again: its listens are not kept once Trackhop restarts.")}")
                val task = Tasks.startOnce("Sending to ListenBrainz", back, { env.log("lifetime", it) }, env.owner, env.marks) { t ->
                    val n = ListeningExport.submit(token, plays) { done, total -> t.progress = "Sent ${"%,d".format(done)} of ${"%,d".format(total)}" }
                    """<div class="card ok">${"%,d".format(n)} listens sent to ListenBrainz.</div>"""
                } ?: return redirect(r.ex, "$back?err=${q("Already sending.")}")
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
        return redirect(r.ex, back)
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">Ask your service for your data (Spotify: Privacy › Extended streaming history; YouTube: Google Takeout, YouTube and YouTube Music history; Deezer: My personal data), then upload what it sends: the .zip, or the .json files. Only the sums are kept here; the listens are not written anywhere.</p>""")
    body.append("""<form method="post" action="$back" enctype="multipart/form-data" class="card"><label>Your export (.zip, .json or .xlsx)</label><input type="file" name="file" required><p><button>Read it</button></p></form>""")
    val s = ListeningExport.kept(env)
    if (s != null) {
        val from = if (s.from > 0) java.time.Instant.ofEpochMilli(s.from).atZone(java.time.ZoneId.systemDefault()).year.toString() else "?"
        body.append("""<div class="card"><h3>${h(s.sources.joinToString(", "))}, since $from</h3><div class="stats">""")
        body.append(if (s.timed) """<p class="stat"><b>${"%,d".format(s.hours)} h</b><span class="muted">played</span></p>""" else """<p class="stat"><b>${"%,d".format(s.plays)}</b><span class="muted">listens</span></p>""")
        body.append("""<p class="stat"><b>${"%,d".format(s.tracks)}</b><span class="muted">songs</span></p><p class="stat"><b>${"%,d".format(s.artists)}</b><span class="muted">artists</span></p></div>""")
        body.append("""<h3>Top artists</h3><ol>${s.topArtists.take(10).joinToString("") { "<li>${h(it.name)} <span class=\"muted\">· ${Lifetime.amount(s, it.ms, it.plays)}</span></li>" }}</ol>""")
        body.append("""<h3>Top songs</h3><ol>${s.topTracks.take(10).joinToString("") { "<li>${h(it.artist)} – ${h(it.title)} <span class=\"muted\">· ${Lifetime.amount(s, it.ms, it.plays)}</span></li>" }}</ol>""")
        if (s.years.isNotEmpty()) body.append("""<h3>Year by year</h3><ul>${s.years.joinToString("") { y -> "<li><b>${y.year}</b> ${Lifetime.amount(s, y.ms, y.plays)}${if (y.artist.isNotBlank()) " · ${h(y.artist)}" else ""}${y.song?.let { " · ${h(it.title)}" } ?: ""}</li>" }}</ul>""")
        body.append("""<div class="actions">${Html.postButton(back, "Save the top songs as a File playlist", "secondary", mapOf("action" to "playlist"))}""")
        if (ArtistMixSource.listenBrainzToken(env) != null && Lifetime.plays[env.owner] != null) body.append(" " + Html.postButton(back, "Send the listens to ListenBrainz", "secondary", mapOf("action" to "listenbrainz"), confirm = "Send every listen of the export to your ListenBrainz account?"))
        body.append(" " + Html.postButton(back, "Forget", "danger", mapOf("action" to "forget")) + "</div></div>")
    }
    send(r.ex, 200, page("All-time stats", body.toString()))
}

/** GET /tools/lifetime (the sums kept), PUT /tools/lifetime (an export as the body), DELETE (forget). */
internal fun Web.apiLifetime(ex: HttpExchange, method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tools", "lifetime")) return false
    when (method) {
        "GET" -> json(200, ListeningExport.kept(env)?.let { Json.encodeToJsonElement(ListeningExport.Stats.serializer(), it) } ?: JsonNull)
        "PUT", "POST" -> {
            val dir = LibraryUploads.dir(env).apply { mkdirs() }
            val tmp = java.io.File.createTempFile("export", ".bin", dir)
            try {
                ex.requestBody.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                val s = runCatching { Lifetime.read(env, tmp) }.getOrElse { return true.also { _ -> error(400, if (it is ListeningExport.NotAnExport) "No listening history in this file." else it.message ?: "Error") } }
                json(200, Json.encodeToJsonElement(ListeningExport.Stats.serializer(), s))
            } finally { tmp.delete() }
        }
        "DELETE" -> { ListeningExport.keep(env, null); Lifetime.plays.remove(env.owner); json(200, buildJsonObject { put("forgotten", true) }) }
        else -> error(405, "GET, PUT or DELETE.")
    }
    return true
}

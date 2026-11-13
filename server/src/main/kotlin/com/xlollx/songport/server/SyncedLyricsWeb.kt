package com.xlollx.songport.server

import com.xlollx.songport.providers.LyricsSupport
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LrcLib
import com.xlollx.songport.sync.SyncedLyrics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * Tools › Synced lyrics (1.18, Features.v2): the songs of a connected personal server without lyrics,
 * their lyrics looked up on LRCLIB and shown, then added once the user says so: through Jellyfin's
 * API, or as `.lrc`/`.txt` files next to the audio for Navidrome, Emby and Plex, where the server's
 * music folder is mapped to a folder here ([ServerLyrics.folder]). The engine's [SyncedLyrics] does
 * the work, as in the app. Not to be confused with Tools › Lyrics (Docker's SONGPORT_MUSIC folder,
 * Lyrics.kt), which stays as it is.
 */
internal object ServerLyrics {
    const val TITLE = "Synced lyrics"
    private const val KV = "lyrics_folders"
    /** A report kept this long for the look-up that follows it. */
    private const val FRESH_MS = 60 * 60_000L

    /** The last report of a service, and the lyrics found for its songs (null until looked up). */
    class Pending(val at: Long, val report: SyncedLyrics.Report, @Volatile var proposals: List<SyncedLyrics.Proposal>? = null)

    private val pending = ConcurrentHashMap<String, Pending>()
    /** LRCLIB's answers of this server's runs, by user and service: a song is asked once. */
    private val sessions = ConcurrentHashMap<String, LrcLib.Run>()

    private fun key(env: ServerEnv, p: MusicProvider) = "${env.owner}|${p.id}"

    fun pendingOf(env: ServerEnv, p: MusicProvider): Pending? = pending[key(env, p)]?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }
    fun keep(env: ServerEnv, p: MusicProvider, r: SyncedLyrics.Report): Pending = Pending(System.currentTimeMillis(), r).also { pending[key(env, p)] = it }
    fun session(env: ServerEnv, p: MusicProvider): LrcLib.Run = sessions.getOrPut(key(env, p)) { LrcLib.Run() }

    /** The personal servers connected: those whose lyrics the tool reads, and Mopidy, to say it has none. */
    fun candidates(env: ServerEnv): List<MusicProvider> =
        Providers.connectors().filter { it.lyricsSupport != LyricsSupport.NONE && (!it.requiresAuth || it.isConnected(env)) }

    /** The server's music folder as the server names it, and the same folder here; null when not set. */
    fun folder(env: ServerEnv, p: MusicProvider): Pair<String, String>? {
        val v = env.keyValues(KV)[p.id]?.takeIf { it.isNotBlank() } ?: return null
        val parts = v.split('\n', limit = 2)
        return if (parts.size == 2 && parts[1].isNotBlank()) parts[0] to parts[1] else null
    }

    /** What is wrong with [local] as the folder to write into; null when it will do. */
    fun folderProblem(local: String): String? {
        val f = File(local.trim())
        if (local.isBlank() || !f.isAbsolute) return "Give the full path of the music folder on this computer (or in this container)."
        if (!f.isDirectory) return "$local is not a folder here. On Docker, mount the music folder into the container first (read-write)."
        if (!java.nio.file.Files.isWritable(f.toPath())) return "Trackhop cannot write into $local: on Docker, mount it without :ro and let the container's user write there."
        return null
    }

    fun setFolder(env: ServerEnv, p: MusicProvider, serverPrefix: String, local: String) {
        env.keyValues(KV)[p.id] = if (local.isBlank()) "" else serverPrefix.trim() + "\n" + local.trim()
    }

    /** Where the lyrics of [p] can be written, and by whom: the [SyncedLyrics.Sidecars] for SIDECAR servers (admin only). */
    fun sidecars(env: ServerEnv, p: MusicProvider): SyncedLyrics.Sidecars? {
        if (p.lyricsSupport != LyricsSupport.SIDECAR || !env.may(Permissions.ADMIN)) return null
        val (prefix, local) = folder(env, p) ?: return null
        if (folderProblem(local) != null) return null
        return SyncedLyrics.Sidecars(prefix, File(local))
    }

    /** Lyrics can be added to [p] from here: through its API, or into its mapped folder. */
    fun canFill(env: ServerEnv, p: MusicProvider): Boolean =
        p.lyricsSupport == LyricsSupport.SERVER_API || sidecars(env, p) != null

    fun supportName(s: LyricsSupport): String = when (s) {
        LyricsSupport.SERVER_API -> "api"
        LyricsSupport.SIDECAR -> "files"
        LyricsSupport.REPORT_ONLY -> "report"
        LyricsSupport.UNSUPPORTED -> "unsupported"
        LyricsSupport.NONE -> "none"
    }

    fun unsupported(p: MusicProvider, env: ServerEnv) = "${p.label(env)} has no lyrics: not supported by this server."

    fun kindName(k: SyncedLyrics.Kind) = when (k) {
        SyncedLyrics.Kind.SYNCED -> "synced"
        SyncedLyrics.Kind.PLAIN -> "plain"
        SyncedLyrics.Kind.NONE -> "none"
        SyncedLyrics.Kind.ERROR -> "error"
    }

    fun savedLine(s: SyncedLyrics.Saved) =
        "${s.saved} lyrics added (${s.synced} synced, ${s.plain} plain), ${s.kept} had lyrics already, ${s.failed} failed, ${s.notFound} not found."
}

// ------------------------------------------------------------------ the page

private const val SHOWN = 500
private const val NOTE = "Lists the songs your server has no lyrics for. Trackhop can look them up on <a href=\"https://lrclib.net\" target=\"_blank\" rel=\"noopener\">LRCLIB</a>, a free archive written by its users, and add them, synced where it can. You see what was found before anything is written; lyrics already there are never changed."

/** Tools › Synced lyrics: see [ServerLyrics]. */
internal fun Web.syncedLyricsPage(r: Web.Req) {
    val title = ServerLyrics.TITLE
    val candidates = ServerLyrics.candidates(env)
    val params = if (r.method == "POST") r.form else r.query
    val service = params["service"]?.let { id -> candidates.firstOrNull { it.id == id } }
    if (service == null) {
        if (candidates.isEmpty()) return send(r.ex, 200, page(title, notice(r) + """<div class="card"><p>$NOTE</p><p>Connect your Navidrome, Jellyfin, Emby, Plex or Lyrion server first: <a href="/connectors">Services</a>.</p></div>"""))
        val opts = candidates.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        return send(r.ex, 200, page(title, notice(r) + """<form method="get" action="/tools/nolyrics" class="card"><p class="muted">$NOTE</p><label>Server</label><select name="service">$opts</select><p><button>Next</button></p></form>"""))
    }
    val back = "/tools/nolyrics?service=${q(service.id)}"
    val name = h(service.label(env))
    if (service.lyricsSupport == LyricsSupport.UNSUPPORTED) {
        return send(r.ex, 200, page(title, """<div class="card err">${h(ServerLyrics.unsupported(service, env))}</div><p><a href="/tools/nolyrics">Back</a></p>"""))
    }
    if (r.method == "POST") {
        when (r.form["action"]) {
            "folder" -> {
                if (!env.may(Permissions.ADMIN)) return send(r.ex, 403, page(title, NOT_ALLOWED))
                val local = r.form["local"].orEmpty().trim()
                if (local.isNotEmpty()) ServerLyrics.folderProblem(local)?.let { return redirect(r.ex, "$back&err=${q(it)}") }
                ServerLyrics.setFolder(env, service, r.form["server"].orEmpty(), local)
                return redirect(r.ex, "$back&ok=${q("Saved.")}")
            }
            "check" -> {
                val task = Tasks.start(title, back, { env.log("lyrics", it) }, env.owner) { t ->
                    val report = SyncedLyrics.report(env, service) { t.progress = "$it songs read…" }
                    ServerLyrics.keep(env, service, report)
                    env.log("lyrics", "${service.label(env)}: ${report.missing.size} of ${report.total} songs without lyrics")
                    reportHtml(service, report)
                }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
            "lookup" -> {
                val p = ServerLyrics.pendingOf(env, service) ?: return redirect(r.ex, "$back&err=${q("Check the library first.")}")
                if (Tasks.running(title)) return redirect(r.ex, "$back&err=${q("Lyrics are already being looked up.")}")
                val songs = p.report.missing
                val task = Tasks.start(title, back, { env.log("lyrics", it) }, env.owner) { t ->
                    val found = SyncedLyrics.lookUp(songs, ServerLyrics.session(env, service)) { t.progress = "$it of ${songs.size} looked up on LRCLIB…" }
                    p.proposals = found
                    previewHtml(service, found)
                }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
            "save" -> {
                val p = ServerLyrics.pendingOf(env, service)
                val found = p?.proposals ?: return redirect(r.ex, "$back&err=${q("Look the lyrics up first.")}")
                if (!ServerLyrics.canFill(env, service)) return redirect(r.ex, "$back&err=${q("Lyrics cannot be added to ${service.label(env)} from here.")}")
                val sidecars = ServerLyrics.sidecars(env, service)
                p.proposals = null
                val task = Tasks.start(title, back, { env.log("lyrics", it) }, env.owner) { t ->
                    val s = SyncedLyrics.save(env, service, found, sidecars) { t.progress = "$it of ${found.count { f -> f.text != null }} added…" }
                    """<div class="card ok"><h3>${h(ServerLyrics.TITLE)}</h3><p>${h(ServerLyrics.savedLine(s))}</p>""" +
                        (if (service.lyricsSupport == LyricsSupport.SIDECAR) """<p class="muted">$name shows them after its next library scan.</p>""" else "") +
                        (if (s.failed > 0) """<p class="muted">The songs that failed are named in the log; they were left as they were.</p>""" else "") + "</div>"
                }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p>$NOTE</p>""")
    when (service.lyricsSupport) {
        LyricsSupport.SERVER_API -> body.append("""<p class="muted">$name takes lyrics through its API (Jellyfin 10.9 or newer, an account allowed to manage lyrics).</p>""")
        LyricsSupport.REPORT_ONLY -> body.append("""<p class="muted">$name gives apps no way to add lyrics: the songs are only listed.</p>""")
        LyricsSupport.SIDECAR -> body.append("""<p class="muted">$name reads lyrics from files next to the music: synced as <code>.lrc</code>, plain as <code>.txt</code>, named like the audio file. Trackhop writes them there when the server's music folder is reachable from here (below).</p>""")
        else -> Unit
    }
    body.append("""<p class="muted">Each song's artist, title, album and length are sent to LRCLIB, one song a second. Lyrics are protected by copyright: they are for your own listening.</p>""")
    body.append(Html.postButton("/tools/nolyrics", "Check ${service.label(env)}", fields = mapOf("service" to service.id, "action" to "check")))
    body.append("</div>")
    if (service.lyricsSupport == LyricsSupport.SIDECAR) body.append(folderCard(service))
    send(r.ex, 200, page(title, body.toString()))
}

/** The music folder of a server whose lyrics are files: as the server names it, and here. */
private fun Web.folderCard(service: MusicProvider): String {
    if (!env.may(Permissions.ADMIN)) return """<div class="card"><p class="muted">Only the server's admin can set where the music files are.</p></div>"""
    val (prefix, local) = ServerLyrics.folder(env, service) ?: ("" to (Lyrics.musicDir?.path ?: ""))
    val state = ServerLyrics.folder(env, service)?.let { (_, l) -> ServerLyrics.folderProblem(l)?.let { """<p class="err">${h(it)}</p>""" } ?: """<p class="ok">Lyrics will be saved into <code>${h(l)}</code>.</p>""" }
        ?: """<p class="muted">Not set: the songs are listed and looked up, nothing is written.</p>"""
    val hint = if (service.serviceId == com.xlollx.songport.providers.SubsonicProvider.SERVICE) "<p class=\"muted\">Navidrome reports the files' real paths only with <code>ND_SUBSONIC_DEFAULTREPORTREALPATH=true</code> (or \"Report real path\" on Trackhop's player in its settings); without it no file is found and nothing is written.</p>" else ""
    return """<form method="post" action="/tools/nolyrics" class="card"><h3>Music folder</h3>$state
<input type="hidden" name="service" value="${h(service.id)}"><input type="hidden" name="action" value="folder">
<label>Path of the music folder on the server, as ${h(service.label(env))} reports it (for example <code>/music</code>)</label><input type="text" name="server" value="${h(prefix)}" placeholder="/music">
<label>The same folder here (on Docker, mounted read-write)</label><input type="text" name="local" value="${h(local)}" placeholder="/music">
$hint<p class="muted">Only new files are made: a song with a <code>.lrc</code> or <code>.txt</code> next to it is left alone, and nothing is ever replaced or deleted.</p>
<p><button class="secondary">Save</button></p></form>"""
}

private fun Web.reportHtml(service: MusicProvider, report: SyncedLyrics.Report): String {
    val name = h(service.label(env))
    if (report.total == 0) return """<div class="card err">$name did not list its songs.</div>"""
    if (report.missing.isEmpty()) return """<div class="card ok">All ${report.total} songs on $name have lyrics.</div>"""
    val sb = StringBuilder("""<div class="card"><p><b>${report.missing.size}</b> of ${report.total} songs on $name have no lyrics.</p>""")
    if (ServerLyrics.canFill(env, service) || service.lyricsSupport == LyricsSupport.SIDECAR) {
        val minutes = (report.missing.size * 2 + 59) / 60
        sb.append("""<p class="muted">Looking them up asks LRCLIB about one song a second: about $minutes min at most. Nothing is written yet.</p>""")
        sb.append(Html.postButton("/tools/nolyrics", "Look up ${report.missing.size} songs on LRCLIB", fields = mapOf("service" to service.id, "action" to "lookup")))
    }
    sb.append("<ul>")
    report.missing.take(SHOWN).forEach { s -> sb.append("<li>${h(s.track.artistLine)} — ${h(s.track.title)}${if (s.track.album.isNotBlank()) """ <span class="muted">${h(s.track.album)}</span>""" else ""}</li>") }
    sb.append("</ul>")
    if (report.missing.size > SHOWN) sb.append("""<p class="muted">…and ${report.missing.size - SHOWN} more.</p>""")
    return sb.append("</div>").toString()
}

private fun Web.previewHtml(service: MusicProvider, found: List<SyncedLyrics.Proposal>): String {
    val synced = found.count { it.kind == SyncedLyrics.Kind.SYNCED }
    val plain = found.count { it.kind == SyncedLyrics.Kind.PLAIN }
    val sb = StringBuilder("""<div class="card"><p>Found <b>$synced</b> synced and <b>$plain</b> plain lyrics; ${found.size - synced - plain} not found.</p>""")
    if (synced + plain > 0) {
        if (ServerLyrics.canFill(env, service)) {
            sb.append(Html.postButton("/tools/nolyrics", "Add ${synced + plain} lyrics to ${service.label(env)}", fields = mapOf("service" to service.id, "action" to "save"),
                confirm = "Add ${synced + plain} lyrics? Songs that have lyrics by now are left as they are."))
        } else {
            sb.append("""<p class="muted">Nothing can be written yet: set the music folder on <a href="/tools/nolyrics?service=${q(service.id)}">the tool's page</a>, then look them up again (the answers are kept, so LRCLIB is not asked twice).</p>""")
        }
    }
    sb.append("<ul>")
    found.take(SHOWN).forEach { p ->
        val kind = when (p.kind) {
            SyncedLyrics.Kind.SYNCED -> "synced"
            SyncedLyrics.Kind.PLAIN -> "plain"
            SyncedLyrics.Kind.NONE -> """<span class="muted">not found</span>"""
            SyncedLyrics.Kind.ERROR -> """<span class="muted">no answer, try later</span>"""
        }
        sb.append("<li>${h(p.song.track.artistLine)} — ${h(p.song.track.title)}: $kind</li>")
    }
    sb.append("</ul>")
    if (found.size > SHOWN) sb.append("""<p class="muted">…and ${found.size - SHOWN} more.</p>""")
    return sb.append("</div>").toString()
}

/** The card under Tools › Clean up; empty while the release switch is off. */
internal fun v2CleanUpCards(): String = if (!Features.v2) "" else
    """<div class="card"><h3><a href="/tools/nolyrics">Synced lyrics</a></h3><p class="muted">The songs on your Navidrome, Jellyfin, Emby, Plex or Lyrion server without lyrics, looked up on LRCLIB and added where the server allows it.</p></div>"""

// ------------------------------------------------------------------ the API

/** GET /tools/nolyrics, POST /tools/nolyrics/lookup and /save; false for any other call. */
internal fun Web.apiSyncedLyrics(method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.size !in 2..3 || rest[0] != "tools" || rest[1] != "nolyrics") return false
    val able = ServerLyrics.candidates(env)
    val asked = query["service"] ?: query["connector"]
    val p = if (asked != null) able.firstOrNull { it.id == asked } ?: return true.also { error(404, "No connected personal server \"$asked\" (Navidrome, Jellyfin, Emby, Plex, Lyrion): see GET /api/v1/services.") }
        else able.singleOrNull() ?: return true.also {
            error(400, if (able.isEmpty()) "No connected server keeps lyrics (Navidrome, Jellyfin, Emby, Plex, Lyrion)." else "Say which service: ?service= one of ${able.joinToString(", ") { it.id }}.")
        }
    if (p.lyricsSupport == LyricsSupport.UNSUPPORTED) return true.also { error(400, ServerLyrics.unsupported(p, env)) }
    fun fail(e: Throwable) = error(502, "${p.label(env)}: ${e.message ?: e.javaClass.simpleName}")
    when (rest.getOrNull(2)) {
        null -> {
            if (method != "GET") return false
            val report = runCatching { runBlocking { SyncedLyrics.report(env, p) } }.getOrElse { return true.also { _ -> fail(it) } }
            ServerLyrics.keep(env, p, report)
            env.log("lyrics", "${p.label(env)}: ${report.missing.size} of ${report.total} songs without lyrics")
            json(200, buildJsonObject {
                put("service", p.id)
                put("support", ServerLyrics.supportName(p.lyricsSupport))
                put("canAdd", ServerLyrics.canFill(env, p))
                put("songs", report.total)
                put("withoutLyrics", report.missing.size)
                put("tracks", JsonArray(report.missing.map { s -> songJson(s.track, s.path) }))
            })
        }
        "lookup" -> {
            if (method != "POST") return false
            val limit = (query["limit"]?.toIntOrNull() ?: 25).coerceIn(1, 200)
            val offset = (query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val pending = ServerLyrics.pendingOf(env, p)
                ?: runCatching { runBlocking { SyncedLyrics.report(env, p) } }.getOrElse { return true.also { _ -> fail(it) } }.let { ServerLyrics.keep(env, p, it) }
            val batch = pending.report.missing.drop(offset).take(limit)
            val found = runCatching { runBlocking { SyncedLyrics.lookUp(batch, ServerLyrics.session(env, p)) } }.getOrElse { return true.also { _ -> fail(it) } }
            pending.proposals = found
            val next = offset + batch.size
            json(200, buildJsonObject {
                put("service", p.id)
                put("withoutLyrics", pending.report.missing.size)
                put("offset", offset)
                put("next", if (next < pending.report.missing.size) JsonPrimitive(next) else JsonNull)
                put("canAdd", ServerLyrics.canFill(env, p))
                put("found", JsonArray(found.map { f -> buildJsonObject {
                    songJson(f.song.track, f.song.path).forEach { (k, v) -> put(k, v) }
                    put("lyrics", ServerLyrics.kindName(f.kind))
                } }))
            })
        }
        "save" -> {
            if (method != "POST") return false
            val pending = ServerLyrics.pendingOf(env, p)
            val found = pending?.proposals ?: return true.also { error(409, "Nothing looked up: POST /api/v1/tools/nolyrics/lookup first, check what it found, then save.") }
            if (!ServerLyrics.canFill(env, p)) return true.also {
                error(409, when (p.lyricsSupport) {
                    LyricsSupport.SIDECAR -> "${p.label(env)} reads lyrics from files next to the music: the admin sets its music folder on the page (Tools › Synced lyrics) first."
                    else -> "${p.label(env)} gives apps no way to add lyrics."
                })
            }
            pending.proposals = null
            val s = runCatching { runBlocking { SyncedLyrics.save(env, p, found, ServerLyrics.sidecars(env, p)) } }.getOrElse { return true.also { _ -> fail(it) } }
            json(200, buildJsonObject {
                put("service", p.id)
                put("saved", s.saved); put("synced", s.synced); put("plain", s.plain)
                put("kept", s.kept); put("failed", s.failed); put("notFound", s.notFound)
            })
        }
        else -> return false
    }
    return true
}

private fun songJson(t: com.xlollx.songport.model.Track, path: String?) = buildJsonObject {
    trackRef(t).forEach { (k, v) -> put(k, v) }
    if (!path.isNullOrBlank()) put("path", path)
}

package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.OAuthProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.SoundCloudPublic
import com.xlollx.songport.sync.SyncState
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/** A page this person's permissions on a shared server leave out (Permissions.kt). */
internal const val NOT_ALLOWED = """<div class="card"><p>This is not turned on for you on this server: ask its admin, on the Users page.</p><p><a href="/">Back</a></p></div>"""

/** The largest request body accepted: a playlist file is far smaller. */
private const val MAX_BODY = 20_000_000

/**
 * True when a POST comes from this server's own pages: its Origin is the address the browser used
 * (Host) or [baseUrl] (SONGPORT_URL, for a reverse proxy that rewrites Host). "Origin: null" is
 * refused. Without an Origin (older browsers), the fetch metadata or the Referer decide; with none of
 * them it is not a browser's cross-site form, and the password is still asked.
 */
internal fun sameOrigin(header: (String) -> String?, baseUrl: String): Boolean {
    fun hostOf(url: String) = url.substringAfter("://", "").substringBefore('/').lowercase()
    val allowed = setOfNotNull(header("Host")?.lowercase(), hostOf(baseUrl).ifEmpty { null })
    header("Origin")?.let { return hostOf(it) in allowed }
    header("Sec-Fetch-Site")?.let { return it == "same-origin" || it == "none" }
    header("Referer")?.let { return hostOf(it) in allowed }
    return true
}

/**
 * The web interface: services, syncs, runs and the log, as plain pages and forms. Served by the
 * JDK's own HTTP server; with SONGPORT_PASSWORD every page asks for it (HTTP basic auth).
 */
class Web(
    internal val env: ServerEnv,
    internal val runner: Runner,
    private val auth: Auth,
    private val password: String?,
    internal val version: String,
    internal val edition: Html.Edition = Html.Edition.SERVER,
    /** Desktop: stops the app (the page's "Quit" button). */
    private val onQuit: (() -> Unit)? = null,
    /** Desktop: brings its window up (a second start asks the running one). */
    private val onShow: (() -> Unit)? = null,
    /** Several people on one server (MultiUser.kt): whose pages these are; null on a single-user one. */
    internal val account: Html.Account? = null,
    /**
     * Several people: the data volume shared by everyone, which this user's folders (a backup's) may
     * not point into except their own part. Null for the admin and on a single-user server.
     */
    internal val sharedRoot: java.io.File? = null,
) {
    /** The addresses a desktop install answers to; null (a server) answers to any. */
    @Volatile private var localHosts: Set<String>? = null

    /**
     * Serves the pages on [port] (0: any free port, for tests); returns the running server.
     * [loopbackOnly] (desktop): listens on 127.0.0.1 only and answers only requests addressed to it
     * by that name or as localhost, so a web page that points its own domain at 127.0.0.1 (DNS
     * rebinding) cannot reach it.
     */
    fun start(port: Int, loopbackOnly: Boolean = false): HttpServer {
        val address = if (loopbackOnly) InetSocketAddress(InetAddress.getLoopbackAddress(), port) else InetSocketAddress(port)
        val server = HttpServer.create(address, 0)
        if (loopbackOnly) server.address.port.let { p -> localHosts = setOf("127.0.0.1:$p", "localhost:$p") }
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/", handler())
        server.start()
        env.log("web", "listening on port ${server.address.port}, reachable as ${env.baseUrl}")
        return server
    }

    /** Every request, answered and closed: what [start] serves, and what a shared server hands each user's requests to. */
    fun handler(): HttpHandler = HttpHandler { ex ->
        try {
            handle(ex)
        } catch (e: Exception) {
            env.log("web", "${ex.requestMethod} ${ex.requestURI.path}: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { send(ex, 500, page("Something went wrong", """<div class="card err">${h(e.message ?: e.javaClass.simpleName)}</div><p><a href="/">Back</a></p>""")) }
        } finally {
            ex.close()
        }
    }

    /** True when this server's API would take [header] from [from]: its token, or the command line's key. */
    fun acceptsApi(header: String?, from: InetAddress?): Boolean = env.may(Permissions.API) && (LocalKey.matches(header, from) || Api.authorized(env, header))

    // ------------------------------------------------------------------ plumbing

    internal class Req(
        val ex: HttpExchange, val method: String, val path: List<String>, val query: Map<String, String>, val form: Map<String, String>,
        /** Uploaded files (multipart forms): field name to file name and content. */
        val files: Map<String, Multipart.File> = emptyMap(),
        /** The urlencoded form as sent, with repeated names (several ticked checkboxes). */
        val rawForm: List<Pair<String, String>> = emptyList(),
    )

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path.trim('/').split('/').filter { it.isNotEmpty() }.map { decode(it) }
        // Health checks (Docker, a reverse proxy) need no password.
        localHosts?.let { hosts ->
            if (ex.requestHeaders.getFirst("Host")?.lowercase() !in hosts) return send(ex, 403, "Unknown host", "text/plain")
        }
        if (path == listOf("health")) return send(ex, 200, "ok", "text/plain")
        // The installable app's manifest, icons and service worker (Pwa.kt): nothing private, and the
        // browser fetches them on its own, without the password.
        if (path.size == 1 && path[0] in Pwa.files && ex.requestMethod.uppercase() == "GET") {
            val (bytes, type) = Pwa.file(path[0]) ?: return send(ex, 404, "Not found", "text/plain")
            ex.responseHeaders.set("Content-Type", type)
            ex.responseHeaders.set("Cache-Control", if (path[0] == "sw.js") "no-cache" else "max-age=86400")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            return ex.responseBody.use { it.write(bytes) }
        }
        // The API has its own token (Tools › API) instead of the page's password, and no cookies to forge.
        if (path.size >= 2 && path[0] == "api" && path[1] == "v1") return api(ex, ex.requestMethod.uppercase(), path, parseForm(ex.requestURI.rawQuery ?: ""))
        if (!authorized(ex)) {
            ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Trackhop\", charset=\"UTF-8\"")
            return send(ex, 401, "Password required", "text/plain")
        }
        val method = ex.requestMethod.uppercase()
        // A form posted from another site is refused: every change comes from these pages.
        if (method == "POST" && !sameOrigin({ ex.requestHeaders.getFirst(it) }, env.baseUrl)) return send(ex, 403, "Cross-site request refused", "text/plain")
        val type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
        val bytes = if (method == "POST") ex.requestBody.readNBytes(MAX_BODY + 1) else ByteArray(0)
        if (bytes.size > MAX_BODY) return send(ex, 413, page("Too large", "<p>Files up to ${MAX_BODY / 1_000_000} MB.</p>"))
        val parts = if (type.startsWith("multipart/form-data")) Multipart.parse(type, bytes) else null
        val raw = if (method == "POST" && parts == null) parsePairs(String(bytes, StandardCharsets.UTF_8)) else emptyList()
        val form = parts?.fields ?: raw.toMap()
        val r = Req(ex, method, path, parseForm(ex.requestURI.rawQuery ?: ""), form, parts?.files.orEmpty(), raw)
        currentPath.set(path.joinToString("/"))
        when {
            path.isEmpty() -> home(r)
            path == listOf("style.css") -> send(ex, 200, Html.CSS, "text/css")
            path == listOf("log") -> log(r)
            path == listOf("callback") -> callback(r)
            path == listOf("quit") && onQuit != null && method == "POST" -> quit(r, onQuit)
            path == listOf("show") && onShow != null && method == "POST" -> { onShow(); send(ex, 200, "ok", "text/plain") }
            path[0] == "connectors" -> connectors(r, path.drop(1))
            path.size == 2 && path[0] == "files" && method == "GET" -> download(r, path[1])
            path.size == 2 && path[0] == "files" && method == "POST" -> fileAction(r, path[1])
            path == listOf("files") && method == "POST" -> upload(r)
            path[0] == "tools" -> tools(r, path.drop(1))
            path == listOf("settings") -> settingsPage(r)
            path[0] == "playlists" -> playlists(r, path.drop(1))
            path[0] == "syncs" -> syncs(r, path.drop(1))
            else -> send(ex, 404, page("Not found", "<p><a href=\"/\">Back</a></p>"))
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val pw = password ?: return true
        val header = ex.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Basic ")) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val given = decoded.substringAfter(':', "")
        return MessageDigest.isEqual(given.toByteArray(), pw.toByteArray())
    }


    private fun decode(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)

    private fun parsePairs(body: String): List<Pair<String, String>> = body.split('&').filter { it.isNotEmpty() }.map {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }

    private fun parseForm(body: String): Map<String, String> = parsePairs(body).toMap()

    internal fun send(ex: HttpExchange, code: Int, body: String, type: String = "text/html") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "$type; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.responseHeaders.set("X-Frame-Options", "DENY")
        // Not "no-referrer": with it browsers send "Origin: null" on this page's own forms, which
        // [sameOrigin] must refuse. "same-origin" still tells other sites nothing.
        ex.responseHeaders.set("Referrer-Policy", "same-origin")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    internal fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.set("Location", to)
        ex.sendResponseHeaders(303, -1)
    }

    /** "Trackhop server" or "Trackhop Desktop". */
    internal val productName: String get() = edition.product

    internal fun page(title: String, body: String, refresh: Int? = null) =
        Html.page(title, body, version, refresh, edition, currentPath.get().orEmpty(), account, theme = Theme.of(env), banner = updateBanner())

    /** The path being answered, for the header's current tab. */
    private val currentPath = ThreadLocal<String>()

    /** A service's mark, as the app shows it. */
    internal fun logo(id: String, size: Int = 32): String {
        val p = Providers.byId(id)
        return Html.logo(p?.serviceId ?: id, p?.brandColor, p?.label(env) ?: id, size)
    }

    private fun quit(r: Req, stop: () -> Unit) {
        send(r.ex, 200, page("Trackhop has stopped", """<p>Scheduled syncs wait until you open Trackhop again. You can close this tab.</p>"""))
        // After the answer has left: stopping closes this server.
        Thread { Thread.sleep(300); stop() }.apply { isDaemon = false }.start()
    }

    internal fun notice(r: Req): String =
        (r.query["ok"]?.let { """<div class="card ok">${h(it)}</div>""" } ?: "") +
            (r.query["err"]?.let { """<div class="card err">${h(it)}</div>""" } ?: "")

    internal fun q(s: String) = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

    // ------------------------------------------------------------------ home: the syncs

    private fun home(r: Req) {
        val jobs = env.store.data.jobs
        val running = SyncState.running.value
        val connected = Providers.connectors().filter { it.isConnected(env) || !it.requiresAuth }
        val body = StringBuilder(notice(r))
        val lastRun = jobs.maxOfOrNull { it.lastRunEpoch } ?: 0
        body.append(
            """<div class="hero"><h2>Your playlists, everywhere</h2><p class="muted">${jobs.size} ${if (jobs.size == 1) "sync" else "syncs"} · ${connected.size} ${if (connected.size == 1) "service" else "services"}${if (lastRun > 0) " · last sync ${Html.date(lastRun)}" else ""}</p>
<div class="actions">${if (jobs.any { it.enabled }) Html.postButton("/syncs/all", if (running.isNotEmpty()) "Syncing…" else "Sync all now") else ""} <a class="hero-link" href="/tools/stats">Your numbers ›</a></div></div>""",
        )
        if (connected.size < 1) {
            body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        }
        body.append("""<p class="actions"><a class="button" href="/syncs/new">New sync</a> ${if (env.may(Permissions.RADIO)) """<a class="button secondary" href="/syncs/radio">$RADIO_ICON New radio sync</a> """ else ""}<a class="button secondary" href="/syncs/shazam">$SHAZAM_ICON New Shazam sync</a></p>""")
        if (jobs.isEmpty()) body.append("""<p class="muted">No syncs yet.</p>""")
        for (job in jobs) body.append(jobCard(job, running.containsKey(job.id)))
        send(r.ex, 200, page("Syncs", body.toString(), refresh = if (running.isNotEmpty()) 5 else null))
    }

    internal fun label(id: String): String = Providers.byId(id)?.label(env) ?: id

    private fun jobCard(job: SyncJob, isRunning: Boolean): String {
        val last = job.lastReportId?.let { env.store.report(it) }
        val status = when {
            isRunning -> progressText(job.id)
            last == null -> """<span class="muted">Never run</span>"""
            last.error != null -> """<span class="err">${h(last.error)}</span>"""
            else -> """<span class="ok">+${last.added} −${last.removed}</span>""" +
                (if (last.unmatched.isNotEmpty()) """ <span class="muted">· ${last.unmatched.size} not found</span>""" else "") +
                (if (last.sourceEmpty && job.radio == null) """ <span class="err">· the source is empty</span>""" else "") +
                (if (last.hasReview()) """ · <a href="/syncs/${q(job.id)}/review/${q(last.id)}">Review</a>""" else "")
        }
        val next = Runner.nextRun(job)?.let { " · next ${Html.date(it)}" } ?: ""
        val radio = job.radio
        val onAir = radio?.let { runner.radio.status[job.id] }?.let { st ->
            if (st.error != null) """<br><span class="err">${h(st.error)}</span>""" else """<br><span class="ok">On air: ${h(st.lastTitle ?: "listening…")}</span>"""
        } ?: ""
        // A playlist written to File: its CSV, a click away.
        val file = if (Providers.byId(job.target.provider) is LocalFilesProvider) csvFiles().firstOrNull { it.nameWithoutExtension == job.target.playlistId } else null
        val download = file?.let { """ <a class="button secondary" href="/files/${q(it.name)}" download>Download CSV</a>""" } ?: ""
        return """<div class="card"${Html.jobStyle(job.color)}><div class="row"><div class="who"><span class="pair">${logo(job.source.provider, 36)}${logo(job.target.provider, 36)}</span><div><a href="/syncs/${q(job.id)}">${if (radio != null) "$RADIO_ICON " else ""}${if (job.shazam) "$SHAZAM_ICON " else ""}${if (job.aiPrompt != null) "$AI_ICON " else ""}<b>${h(job.name)}</b></a><br>
<span class="muted">${if (radio != null) "Radio: ${h(radio.station)}" else "${h(label(job.source.provider))}: ${h(job.source.playlistName)}"} → ${h(label(job.target.provider))}: ${h(job.target.playlistName.ifBlank { "new playlist" })}</span></div></div></div>
<div>
<span class="badge">${h(if (radio != null) com.xlollx.songport.sync.RadioTitles.describe(radio) else scheduleName(job.schedule))}</span>${if (job.mirrorRemovals) " <span class=\"badge\">mirror</span>" else ""}${if (!job.enabled) " <span class=\"badge\">off</span>" else ""}
<span class="muted">Last run ${Html.date(job.lastRunEpoch)}$next</span>$onAir
<div class="row"><div>$status</div><div class="actions">${if (isRunning) Html.postButton("/syncs/${q(job.id)}/stop", "Stop", "secondary") else Html.postButton("/syncs/${q(job.id)}/run", "Run now")}${if (!isRunning && last?.targetShrunkFrom?.let { it > 0 } == true) " " + Html.postButton("/syncs/${q(job.id)}/run-anyway", "Sync anyway", "secondary") else ""}$download</div></div></div></div>"""
    }

    private fun progressText(jobId: String): String {
        val p = SyncState.running.value[jobId] ?: return """<span class="muted">Starting…</span>"""
        val step = p.step.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        val count = if (p.total > 0) " ${p.done}/${p.total}" else ""
        val paused = when (SyncState.run(jobId)) {
            SyncState.Run.PAUSING -> " (pausing after this step…)"
            SyncState.Run.PAUSED -> " (paused)"
            else -> ""
        }
        val bar = p.percent?.let { """<progress max="100" value="$it"></progress>""" } ?: ""
        return """<span>${h(step)}$count$paused${p.label?.let { " · " + h(it) } ?: ""}</span>$bar"""
    }

    internal fun scheduleName(s: Schedule) = when (s) {
        Schedule.MANUAL -> "manual"
        Schedule.HOURLY -> "hourly"
        Schedule.EVERY_6H -> "every 6 h"
        Schedule.DAILY -> "daily"
        Schedule.WEEKLY -> "weekly"
    }

    // ------------------------------------------------------------------ services

    private fun connectors(r: Req, rest: List<String>) {
        val store = env.store
        if (rest.isEmpty()) return connectorList(r)
        if (rest == listOf("add") && r.method == "POST") {
            val service = r.form["service"].orEmpty()
            if (Providers.byId(service) != null && service !in store.data.settings.connectors) {
                store.updateSettings { it.copy(connectors = it.connectors + service, connectorsInit = true) }
            }
            return redirect(r.ex, "/connectors/${q(service)}")
        }
        val provider = Providers.byId(rest[0]) ?: return send(r.ex, 404, page("Unknown service", "<p><a href=\"/connectors\">Back</a></p>"))
        val back = "/connectors/${q(provider.id)}"
        // Every action changes something: POST only, so a link or an image on another site can't.
        if (rest.size > 1 && r.method != "POST") return send(r.ex, 405, page("Not allowed", "<p><a href=\"$back\">Back</a></p>"))
        when (rest.getOrNull(1)) {
            null -> connectorPage(r, provider)
            "settings" -> {
                val id = provider.serviceId
                store.updateSettings { s ->
                    var n = s
                    r.form["clientId"]?.let { v -> n = n.copy(clientIds = if (v.isBlank()) n.clientIds - id else n.clientIds + (id to v.trim())) }
                    r.form["clientSecret"]?.let { v -> n = n.copy(clientSecrets = if (v.isBlank()) n.clientSecrets - id else n.clientSecrets + (id to v.trim())) }
                    r.form["redirect"]?.let { v -> n = n.copy(deezerRedirectUrl = v.trim()) }
                    n
                }
                redirect(r.ex, "$back?ok=${q("Saved.")}")
            }
            "connect" -> {
                if (provider is CookieConnector) {
                    val result = runCatching { runBlocking { provider.connect(env, r.form["cookies"].orEmpty(), r.form["userAgent"]) } }
                    result.exceptionOrNull()?.let { return redirect(r.ex, "$back?err=${q(it.message ?: it.javaClass.simpleName)}") }
                    env.log("auth", "${provider.id} connected (pasted cookies)")
                    return redirect(r.ex, "$back?ok=${q("Connected.")}")
                }
                if (provider is CredentialsProvider) {
                    val result = runCatching { runBlocking { provider.login(env, r.form["url"].orEmpty(), r.form["user"].orEmpty(), r.form["secret"].orEmpty()) } }
                    result.exceptionOrNull()?.let { return redirect(r.ex, "$back?err=${q(it.message ?: it.javaClass.simpleName)}") }
                    env.log("auth", "${provider.id} connected")
                    return redirect(r.ex, "$back?ok=${q("Connected.")}")
                }
                val url = runCatching { auth.begin(provider) }.getOrElse { return redirect(r.ex, "$back?err=${q(it.message ?: "")}") }
                    ?: return redirect(r.ex, "$back?err=${q("This service can't sign in from the server.")}")
                redirect(r.ex, url)
            }
            "disconnect" -> { provider.disconnect(env); redirect(r.ex, "$back?ok=${q("Disconnected.")}") }
            "remove" -> {
                store.updateSettings { it.copy(connectors = it.connectors - provider.id) }
                redirect(r.ex, "/connectors")
            }
            else -> send(r.ex, 404, page("Not found", ""))
        }
    }

    private fun connectorList(r: Req) {
        val added = Providers.connectors()
        val body = StringBuilder(notice(r))
        for (p in added) {
            val state = when {
                !p.requiresAuth -> """<span class="ok">ready</span>"""
                p.isConnected(env) -> """<span class="ok">connected</span>""" + (p.accountName(env)?.let { " · " + h(it) } ?: "")
                !p.isConfigured(env) -> """<span class="muted">needs your key</span>"""
                else -> """<span class="muted">not connected</span>"""
            }
            body.append("""<div class="card"><div class="who">${logo(p.id, 40)}<div><a href="/connectors/${q(p.id)}"><b>${h(p.label(env))}</b></a>${unofficialBadges(p)}<br>$state</div></div></div>""")
        }
        // A link-only source (SoundCloud) is not added: it is offered when a sync is created.
        val available = Providers.services().filter { s -> !s.linkOnly && added.none { it.id == s.id } }
        if (available.isNotEmpty()) {
            body.append("""<h2>Add a service</h2><form method="post" action="/connectors/add" class="card"><select name="service">""")
            available.forEach { body.append("""<option value="${h(it.id)}">${h(it.displayName)}${if (it is CookieConnector) " (unofficial, alpha)" else ""}</option>""") }
            body.append("""</select><p><button>Add</button></p></form>""")
        }
        send(r.ex, 200, page("Services", body.toString()))
    }

    /** The web connectors are not an official API: said next to their name wherever they are listed. */
    private fun unofficialBadges(p: MusicProvider): String = when {
        p is CookieConnector -> """ <span class="badge">unofficial</span> <span class="alpha">alpha</span>"""
        p.linkOnly -> """ <span class="badge">unofficial</span>"""
        else -> ""
    }

    private fun connectorPage(r: Req, p: MusicProvider) {
        val body = StringBuilder(notice(r))
        if (p is CookieConnector) body.append(if (p is YtmServerProvider) ytmNote() else webPlayerNote(p))
        // The app's note for File points to its own screens ("Save as file", Tools): here the list below says it.
        else if (p !is LocalFilesProvider) {
            body.append("""<div class="card"><p>${h(env.text(p.noteName))}</p>""")
            if (p.beta) body.append("""<p><span class="badge">beta</span></p>""")
            body.append("</div>")
        }
        when {
            p is LocalFilesProvider -> body.append(fileList())
            !p.requiresAuth -> Unit
            p.isConnected(env) -> body.append(
                """<div class="card row"><div><span class="ok">Connected</span>${p.accountName(env)?.let { " as <b>" + h(it) + "</b>" } ?: ""}</div>""" +
                    Html.postButton("/connectors/${q(p.id)}/disconnect", "Disconnect", "secondary") + "</div>",
            )
            p is CookieConnector -> body.append(cookieForm(p))
            p is CredentialsProvider -> {
                val f = p.loginForm
                body.append("""<form method="post" action="/connectors/${q(p.id)}/connect" class="card">""")
                body.append("""<p class="muted">${h(env.text(f.hintName))}</p>""")
                if (f.needsUrl) body.append("""<label>${h(env.text("login_url"))}</label><input type="url" name="url" required>""")
                if (f.needsUser) body.append("""<label>${h(env.text("login_user"))}</label><input type="text" name="user" autocomplete="off" required>""")
                if (f.needsSecret) body.append(
                    """<label>${h(env.text(f.secretLabelName))}</label><input type="password" name="secret" autocomplete="new-password"${if (f.secretOptional) "" else " required"}>""",
                )
                body.append("""<p><button>${h(env.text("login_submit"))}</button></p></form>""")
            }
            p is OAuthProvider -> body.append(oauthSetup(p))
            else -> body.append("""<div class="card muted">This service can't sign in from the server.</div>""")
        }
        body.append("""<p>${Html.postButton("/connectors/${q(p.id)}/remove", "Remove from the list", "danger", confirm = "Remove this service from the list?")}</p>""")
        send(r.ex, 200, page(p.label(env), body.toString()))
    }

    private fun ytmNote(): String = """<div class="card"><p><span class="badge">unofficial</span> <span class="alpha">alpha</span></p>
<p>YouTube Music through the music.youtube.com web player, signed in with the cookies of a browser where you are signed in. Not an official API: Google can change it, or block it, at any time.</p>
<p class="muted">Without the app's hidden browser every call is plain HTTP, which Google's abuse detection stops sooner, above all from a datacenter address: a server at home works best. When Google blocks this network, open music.youtube.com in a browser on the same network as this server, pass Google's check, then paste fresh cookies here. Signing out in that browser ends these cookies too. <a href="https://trackhop.org/guide/docker#youtube-music-unofficial">How to copy them</a></p></div>"""

    /**
     * Spotify and Deezer without a key: the cookie that names the account is HttpOnly, so it is copied
     * from the developer tools' cookie list (or the Cookie header of a request) rather than from a script.
     */
    private fun webPlayerNote(p: CookieConnector): String {
        val (service, about, cookie) = when (p) {
            is SpotifyWebServerProvider -> Triple(
                "Spotify",
                "Spotify without a developer key, through the open.spotify.com web player. The official route with your own key stays available as “Spotify”.",
                "<code>sp_dc</code> (and <code>sp_key</code>, <code>sp_t</code> when listed)",
            )
            else -> Triple(
                "Deezer",
                "Deezer without an app id (Deezer no longer registers new ones), through the deezer.com web player.",
                "<code>arl</code>",
            )
        }
        return """<div class="card"><p><span class="badge">unofficial</span> <span class="alpha">alpha</span></p>
<p>${h(about)} Signed in with the cookies of a browser where you are signed in. Not an official API: against $service's terms, it can stop working whenever the player changes.</p>
<p class="muted">In a desktop browser signed in to ${h(p.cookieSite)}, open the developer tools (F12), then <b>Application</b> (<b>Storage</b> in Firefox) › <b>Cookies</b> › <code>https://${h(p.cookieSite)}</code>, and copy $cookie as <code>name=value; name=value</code>. The <code>cookie:</code> header of any request to the site, a cookies.txt or a cookie-editor JSON export work too. They are as good as your password; signing out in that browser ends them: paste fresh ones then. <a href="https://trackhop.org/guide/docker#spotify-and-deezer-without-a-key-unofficial">How to copy them</a></p></div>"""
    }

    /** The pasted cookies: a Cookie header, cookies.txt or a JSON export, and optionally the browser's user agent. */
    private fun cookieForm(p: CookieConnector): String =
        """<form method="post" action="/connectors/${q(p.id)}/connect" class="card">
<label>Cookies of ${h(p.cookieSite)}</label>
<textarea name="cookies" rows="6" required autocomplete="off" spellcheck="false" placeholder="${h(p.cookieExample)}  (the Cookie header, cookies.txt or a JSON export)"></textarea>
<label>Your browser's user agent (optional)</label>
<input type="text" name="userAgent" autocomplete="off" placeholder="Mozilla/5.0 … Chrome/… Safari/537.36">
<p class="muted">Stored only in this server's data folder, readable by its owner only, and never written to the log.</p>
<p><button>Connect</button></p></form>"""

    /** Your own key for an OAuth service: the setup steps of the app, with this server's redirect address. */
    private fun oauthSetup(p: OAuthProvider): String {
        val guide = p.setupGuide
        val sb = StringBuilder("""<form method="post" action="/connectors/${q(p.id)}/settings" class="card">""")
        if (guide != null) {
            sb.append("""<p>${h(env.text(guide.whyName))}</p><ol>""")
            env.textArray(guide.stepsArrayName).forEachIndexed { i, step ->
                val link = guide.stepUrls.getOrNull(i)?.let { """ <a href="${h(it)}" target="_blank" rel="noopener">open</a>""" } ?: ""
                sb.append("<li>${h(step)}$link</li>")
            }
            sb.append("</ol>")
        }
        val redirect = if (guide?.needsRedirectUrl == true) env.store.data.settings.deezerRedirectUrl.ifBlank { env.redirectUri } else env.redirectUri
        sb.append("""<p>Redirect address for this server: <code>${h(redirect)}</code></p>""")
        val label = guide?.let { env.text(it.fieldLabelName) } ?: "Client ID"
        sb.append("""<label>${h(label)}</label><input type="text" name="clientId" value="${h(p.ownClientId(env).orEmpty())}" autocomplete="off">""")
        if (guide?.needsSecret == true) {
            sb.append("""<label>Client secret</label><input type="password" name="clientSecret" placeholder="${if (p.ownClientSecret(env) != null) "saved" else ""}" autocomplete="new-password">""")
        }
        if (guide?.needsRedirectUrl == true) {
            sb.append("""<label>Redirect URL (https)</label><input type="url" name="redirect" value="${h(env.store.data.settings.deezerRedirectUrl)}" placeholder="${h(env.redirectUri)}">""")
        }
        sb.append("""<p><button class="secondary">Save</button></p></form>""")
        sb.append(
            if (p.isConfigured(env)) """<div class="card">${Html.postButton("/connectors/${q(p.id)}/connect", "Connect")} <span class="muted">You'll sign in on ${h(p.authDomain ?: p.displayName)} and come back here.</span></div>"""
            else """<div class="card muted">Save your key first, then connect.</div>""",
        )
        return sb.toString()
    }

    private fun callback(r: Req) {
        // Deezer puts the token in the fragment, which never reaches a server: the page moves it into the query.
        if (r.query.isEmpty()) {
            return send(
                r.ex, 200,
                page("Signing in…", """<p class="muted">One moment…</p><script>
if (location.hash.length > 1) location.replace('/callback?' + location.hash.substring(1)); else location.replace('/connectors?err=' + encodeURIComponent('Nothing came back from the service.'));
</script>"""),
            )
        }
        val result = runCatching { runBlocking { auth.complete(r.query) } }
        result.onSuccess { p ->
            env.log("auth", "${p.id} connected")
            redirect(r.ex, "/connectors/${q(p.id)}?ok=${q("Connected.")}")
        }.onFailure { e ->
            env.log("auth", "sign-in failed: ${e.message}")
            redirect(r.ex, "/connectors?err=${q(e.message ?: e.javaClass.simpleName)}")
        }
    }

    // ------------------------------------------------------------------ syncs

    private fun syncs(r: Req, rest: List<String>) {
        when {
            rest == listOf("new") -> newSync(r)
            rest == listOf("radio") && !env.may(Permissions.RADIO) -> send(r.ex, 403, page("Radio sync", NOT_ALLOWED))
            rest == listOf("radio") -> if (r.method == "POST") radioCreate(r) else radioNew(r)
            rest == listOf("shazam") -> if (r.method == "POST") shazamCreate(r) else shazamNew(r)
            rest == listOf("all") && r.method == "POST" -> {
                env.store.data.jobs.filter { it.enabled }.forEach { runner.start(it) }
                redirect(r.ex, "/")
            }
            rest.isEmpty() && r.method == "POST" -> createSync(r)
            rest.isEmpty() -> redirect(r.ex, "/")
            else -> {
                val job = env.store.job(rest[0]) ?: return send(r.ex, 404, page("Unknown sync", "<p><a href=\"/\">Back</a></p>"))
                val back = "/syncs/${q(job.id)}"
                if (rest.getOrNull(1) == "review") return review(r, job, rest.drop(2))
                if (r.method != "POST") return syncPage(r, job)
                when (rest.getOrNull(1)) {
                    "run" -> { runner.start(job); redirect(r.ex, back) }
                    // After a run stopped by the target's size check: the user says the smaller target is real.
                    "run-anyway" -> { env.store.confirmTargetShrink(job.id); runner.start(job); redirect(r.ex, back) }
                    "stop" -> { SyncState.stop(job.id); redirect(r.ex, back) }
                    "pause" -> { SyncState.pause(job.id); redirect(r.ex, back) }
                    "resume" -> { SyncState.resume(job.id); redirect(r.ex, back) }
                    "toggle" -> { env.store.upsertJob(job.copy(enabled = !job.enabled)); redirect(r.ex, back) }
                    "schedule" -> {
                        val s = runCatching { Schedule.valueOf(r.form["schedule"].orEmpty()) }.getOrDefault(job.schedule)
                        val name = r.form["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: job.name
                        val color = r.form["color"]?.takeIf { it in Html.JOB_COLORS }
                        env.store.upsertJob(job.copy(name = name, schedule = s, mirrorRemovals = r.form["mirror"] == "on", color = color))
                        redirect(r.ex, "$back?ok=${q("Saved.")}")
                    }
                    "radio" -> {
                        if (!env.may(Permissions.RADIO)) return send(r.ex, 403, page("Radio sync", NOT_ALLOWED))
                        val base = job.radio ?: return redirect(r.ex, back)
                        val (radio, problem) = radioWindow(r.form, base)
                        if (radio == null) return redirect(r.ex, "$back?err=${q(problem!!)}")
                        env.store.upsertJob(job.copy(radio = radio))
                        runner.radio.tick(java.time.LocalDateTime.now())
                        redirect(r.ex, "$back?ok=${q("Saved: ${com.xlollx.songport.sync.RadioTitles.describe(radio)}.")}")
                    }
                    "delete" -> { env.store.deleteJob(job.id); redirect(r.ex, "/") }
                    else -> send(r.ex, 404, page("Not found", ""))
                }
            }
        }
    }

    private fun usable(p: MusicProvider) = !p.requiresAuth || p.isConnected(env)

    /** What a link to a playlist on [src] looks like, for the form's hint. */
    private fun linkHint(src: MusicProvider): String = when (src.serviceId) {
        com.xlollx.songport.sync.NeteasePublic.SERVICE -> "https://music.163.com/#/playlist?id=…"
        com.xlollx.songport.sync.QqMusicPublic.SERVICE -> "https://y.qq.com/n/ryqq/playlist/…"
        else -> "https://soundcloud.com/user/sets/…"
    }

    /** The playlist a pasted link names on [src]: the link itself, or where a short link lands. */
    private suspend fun linkedPlaylist(src: MusicProvider, text: String): String? {
        PlaylistLinks.parse(text)?.takeIf { it.providerId == src.serviceId }?.let { return it.playlistId }
        if (src.serviceId == SoundCloudPublic.SERVICE) return SoundCloudPublic.pathFromLink(text)
        val url = Regex("""https?://[^\s<>"']+""").find(text)?.value ?: return null
        val landing = runCatching { com.xlollx.songport.net.Http.follow(url) }.getOrNull() ?: return null
        return (PlaylistLinks.parse(landing.url) ?: PlaylistLinks.parseAll(landing.page).firstOrNull())
            ?.takeIf { it.providerId == src.serviceId }?.playlistId
    }

    private fun newSync(r: Req) {
        val connected = Providers.connectors().filter(::usable)
        // Link-only sources need nothing added or connected: they are always offered as a source.
        val list = (connected + Providers.services().filter { it.linkOnly }).distinctBy { it.id }
        val src = r.query["src"]?.let { Providers.byId(it) }
        val dst = r.query["dst"]?.let { Providers.byId(it) }
        val body = StringBuilder(notice(r))
        if (connected.isEmpty()) {
            body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
            return send(r.ex, 200, page("New sync", body.toString()))
        }
        if (src == null || dst == null) {
            fun options(writable: Boolean) = list.filter { !writable || it.canWrite }.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
            body.append(
                """<form method="get" action="/syncs/new" class="card"><label>From</label><select name="src">${options(false)}</select>
<label>To</label><select name="dst">${options(true)}</select><p><button>Next</button></p></form>""",
            )
            return send(r.ex, 200, page("New sync", body.toString()))
        }
        val srcLists = runCatching { if (src.linkOnly) emptyList() else runBlocking { src.libraryEntries(env, false) + src.playlists(env) } }
        val dstLists = runCatching { runBlocking { dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe } } }
        srcLists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(src.label(env))}: ${h(it.message)}</div>""") }
        dstLists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(dst.label(env))}: ${h(it.message)}</div>""") }
        fun opts(ps: List<Playlist>) = ps.joinToString("") { p ->
            """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>"""
        }
        val schedules = Schedule.values().joinToString("") { """<option value="${it.name}"${if (it == Schedule.DAILY) " selected" else ""}>${h(scheduleName(it))}</option>""" }
        body.append(
            """<form method="post" action="/syncs" class="card">
<input type="hidden" name="src" value="${h(src.id)}"><input type="hidden" name="dst" value="${h(dst.id)}">
${if (src.linkOnly) """<label>Public link on ${h(src.label(env))}</label><input type="url" name="srcLink" required placeholder="${linkHint(src)}">"""
            else """<label>From ${h(src.label(env))}</label><select name="srcPlaylist" required>${opts(srcLists.getOrDefault(emptyList()))}</select>"""}
<label>To ${h(dst.label(env))}</label><select name="dstPlaylist">${if (dst.canCreatePlaylists) "<option value=\"\">A new playlist with the same name</option>" else ""}${opts(dstLists.getOrDefault(emptyList()))}</select>
<label>Name of the sync (optional)</label><input type="text" name="name">
<label>Schedule</label><select name="schedule">$schedules</select>
<label class="check"><input type="checkbox" name="mirror"> Mirror removals (tracks removed from the source are removed from the destination)</label>
<p><button>Create</button> <a class="button secondary" href="/syncs/new">Back</a></p></form>""",
        )
        send(r.ex, 200, page("New sync", body.toString()))
    }

    private fun createSync(r: Req) {
        val src = Providers.byId(r.form["src"].orEmpty()) ?: return redirect(r.ex, "/syncs/new?err=${q("Pick a source.")}")
        val dst = Providers.byId(r.form["dst"].orEmpty()) ?: return redirect(r.ex, "/syncs/new?err=${q("Pick a destination.")}")
        val back = "/syncs/new?src=${q(src.id)}&dst=${q(dst.id)}"
        // A link-only source (SoundCloud, NetEase, QQ Music): the pasted link, short links followed,
        // is the playlist, and reading its name now says at once whether it is public.
        val linked = if (src.linkOnly) {
            val path = runBlocking { linkedPlaylist(src, r.form["srcLink"].orEmpty()) }
                ?: return redirect(r.ex, "$back&err=${q("Not a link to a public playlist on ${src.label(env)}.")}")
            runCatching { runBlocking { src.playlistInfo(env, path) } }.getOrElse { return redirect(r.ex, "$back&err=${q(it.message ?: "Error")}") }
        } else null
        val srcId = linked?.id ?: r.form["srcPlaylist"].orEmpty().ifBlank { return redirect(r.ex, "$back&err=${q("Pick a playlist.")}") }
        val dstId = r.form["dstPlaylist"].orEmpty().ifBlank { null }
        // Only playlists the services list, as the form offered them: an id is also a path or a URL
        // part for some connectors (a CSV file name), never something typed in.
        val srcName = linked?.name ?: runCatching { runBlocking { (src.libraryEntries(env, false) + src.playlists(env)).firstOrNull { it.id == srcId }?.name } }
            .getOrElse { return redirect(r.ex, "$back&err=${q("${src.label(env)}: ${it.message}")}") }
            ?: return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
        if (dstId == null && !dst.canCreatePlaylists) return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
        val dstName = dstId?.let { id ->
            runCatching { runBlocking { (dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe }).firstOrNull { it.id == id }?.name } }
                .getOrElse { return redirect(r.ex, "$back&err=${q("${dst.label(env)}: ${it.message}")}") }
                ?: return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
        } ?: srcName
        val job = SyncJob(
            id = UUID.randomUUID().toString(),
            name = r.form["name"].orEmpty().ifBlank { srcName },
            source = PlaylistRef(src.id, srcId, srcName),
            target = PlaylistRef(dst.id, dstId, dstName),
            schedule = runCatching { Schedule.valueOf(r.form["schedule"].orEmpty()) }.getOrDefault(Schedule.MANUAL),
            mirrorRemovals = r.form["mirror"] == "on",
        )
        env.store.upsertJob(job)
        redirect(r.ex, "/syncs/${q(job.id)}?ok=${q("Sync created. Run it now, or let the schedule start it.")}")
    }

    private fun syncPage(r: Req, job: SyncJob) {
        val running = runner.isRunning(job.id)
        val body = StringBuilder(notice(r))
        body.append(jobCard(job, running))
        body.append(radioPanel(job))
        val id = q(job.id)
        body.append("""<div class="card actions">""")
        if (running) {
            body.append(if (SyncState.isPaused(job.id)) Html.postButton("/syncs/$id/resume", "Resume") else Html.postButton("/syncs/$id/pause", "Pause", "secondary"))
        }
        body.append(Html.postButton("/syncs/$id/toggle", if (job.enabled) "Turn off" else "Turn on", "secondary"))
        body.append(Html.postButton("/syncs/$id/delete", "Delete", "danger", confirm = "Delete this sync? The playlists stay as they are."))
        body.append("</div>")
        val schedules = Schedule.values().joinToString("") { """<option value="${it.name}"${if (it == job.schedule) " selected" else ""}>${h(scheduleName(it))}</option>""" }
        body.append(
            """<form method="post" action="/syncs/$id/schedule" class="card"><label>Name</label><input type="text" name="name" value="${h(job.name)}">${if (job.radio != null) """<input type="hidden" name="schedule" value="MANUAL">""" else """<label>Schedule</label><select name="schedule">$schedules</select>
<label class="check"><input type="checkbox" name="mirror"${if (job.mirrorRemovals) " checked" else ""}> Mirror removals</label>"""}
<label>Colour</label><div class="swatches"><label title="None"><input type="radio" name="color" value=""${if (job.color == null) " checked" else ""} aria-label="None"><span class="dot none"></span></label>${Html.JOB_COLORS.entries.joinToString("") { (k, v) -> """<label title="$k"><input type="radio" name="color" value="$k"${if (job.color == k) " checked" else ""} aria-label="$k"><span class="dot" style="background:${v.first}"></span></label>""" }}</div>
<p><button class="secondary">Save</button></p></form>""",
        )
        val reports = env.store.data.reports.filter { it.jobId == job.id }.take(10)
        if (reports.isNotEmpty()) body.append("<h2>Last runs</h2>")
        for (rep in reports) {
            body.append("""<div class="card"><div class="row"><b>${Html.date(rep.startedEpoch)}</b><span class="muted">${rep.durationMs / 1000} s · source ${rep.sourceCount} · destination ${rep.targetCount}</span></div>""")
            body.append(if (rep.error != null) """<p class="err">${h(rep.error)}</p>""" else """<p class="ok">+${rep.added} added · −${rep.removed} removed</p>""")
            rep.notes.forEach { body.append("""<p class="muted">${h(it)}</p>""") }
            if (rep.hasReview()) {
                val what = listOfNotNull(
                    rep.unmatchedTracks.size.takeIf { it > 0 }?.let { "$it not found" },
                    rep.reviewTracks.size.takeIf { it > 0 }?.let { "$it to check" },
                    rep.removedTracks.size.takeIf { it > 0 }?.let { "$it removed" },
                ).joinToString(", ")
                body.append("""<p><a class="button" href="/syncs/${q(job.id)}/review/${q(rep.id)}">Review ($what)</a></p>""")
            }
            if (rep.unmatched.isNotEmpty()) {
                body.append("""<details><summary>${rep.unmatched.size} not found</summary><ul>""")
                rep.unmatchedTracks.forEach { t -> body.append("<li>${h(t.artistLine)} — ${h(t.title)}</li>") }
                // After the tracks not found, the ones the destination refused (with its error).
                rep.unmatched.drop(rep.unmatchedTracks.size).forEach { body.append("<li>${h(it)}</li>") }
                body.append("</ul></details>")
            }
            body.append("</div>")
        }
        send(r.ex, 200, page(job.name, body.toString(), refresh = if (running) 5 else null))
    }

    // ------------------------------------------------------------------ log

    private fun log(r: Req) {
        val lines = Log.recent().takeLast(500).reversed()
        send(r.ex, 200, page("Log", """<div class="card row"><div><b>Something wrong?</b><br><span class="muted">Opens a report on GitHub with the version and the last lines of this log filled in. Read it before you send it: remove anything you would rather not share.</span></div><a class="button" href="${h(issueUrl())}" target="_blank" rel="noopener">Report an issue on GitHub</a></div>
<p class="muted">Newest first. The full log is <code>songport.log</code> in the data folder${if (edition == Html.Edition.SERVER) ", and <code>docker logs</code> shows it too" else ""}.</p><pre>${h(lines.joinToString("\n"))}</pre>"""))
    }

    /** A new GitHub issue with the version, the system and the log's last lines, short enough for a link. */
    internal fun issueUrl(): String {
        val head = "**${edition.product} $version**, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}\n\n" +
            "What happened, and what you expected:\n\n\n<details><summary>Log (last lines)</summary>\n\n```\n"
        val tail = "\n```\n</details>\n"
        val lines = ArrayList<String>()
        var size = head.length + tail.length
        // Newest last, and no more than fits a link: GitHub refuses addresses past about 8 000 characters.
        for (line in Log.recent().asReversed()) {
            val cost = q(line).length + 3
            if (size + cost > 5_500 || lines.size >= 80) break
            lines.add(0, line); size += cost
        }
        val title = "${edition.product} ${version.substringBefore(' ')}: "
        return "https://github.com/xlollx/Trackhop/issues/new?title=${q(title)}&labels=${q("server")}&body=${q(head + lines.joinToString("\n") + tail)}"
    }
}

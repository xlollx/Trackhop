package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.server.Html.h
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BiPredicate
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Several people on one server (SONGPORT_MULTIUSER): each signs in with a user name and a password
 * and has a Trackhop of their own, with their own services, syncs, files, backups and log, in
 * `<data>/users/<name>`. The admin ([ADMIN], with SONGPORT_PASSWORD) adds and removes the others and
 * keeps the data folder's own Trackhop, so a server that was used by one person carries on as the
 * admin's.
 *
 * Each person's Trackhop is the whole engine in a class loader of its own ([UserEngine]): the engine
 * keeps its state in objects shared by the process (the connectors, the store, the running syncs),
 * as the app does, and a loader of its own gives each person their own copy of all of them. Only
 * JDK types cross between them. Their scheduled syncs run whether or not they are signed in.
 */
class MultiUser private constructor(
    private val config: Config,
    private val baseEnv: Map<String, String>,
    private val admin: Launched,
    private val users: UserStore,
    private val sessions: Sessions,
    private val invites: Invites,
) {
    /** One person's Trackhop as the shared server reaches it. [loader] is null for the admin's, which lives in this one. */
    private class Engine(val handler: HttpHandler, val acceptsApi: BiPredicate<String?, InetAddress?>, val stop: Runnable, val loader: URLClassLoader?)

    private val root = config.dataDir
    private val adminEngine = Engine(admin.web.handler(), { h, a -> admin.web.acceptsApi(h, a) }, { admin.stop() }, null)
    private val engines = ConcurrentHashMap<String, Engine>()
    /** Why a person's Trackhop could not start, shown to them instead of their pages. */
    private val broken = ConcurrentHashMap<String, String>()
    private val classPath = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
        .map { File(it).toURI().toURL() }.toTypedArray()

    private fun log(message: String) = admin.env.log("users", message)

    private fun startEngine(name: String) {
        val dir = userDir(name).apply { mkdirs() }
        val settings = baseEnv - "SONGPORT_PASSWORD" - "SONGPORT_MULTIUSER" + mapOf(
            "SONGPORT_DATA" to dir.path, ACCOUNT to name, ROOT to root.path, PORT to config.port.toString(),
            PERMISSIONS to Permissions.format(users.permissions(name)),
        )
        val loader = URLClassLoader("trackhop-$name", classPath, ClassLoader.getPlatformClassLoader())
        try {
            val made = withLoader(loader) {
                loader.loadClass(UserEngine::class.java.name).getMethod("start", Map::class.java).invoke(null, settings) as Map<*, *>
            }
            @Suppress("UNCHECKED_CAST")
            engines[name] = Engine(made["handler"] as HttpHandler, made["accepts"] as BiPredicate<String?, InetAddress?>, made["stop"] as Runnable, loader)
            broken.remove(name)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            broken[name] = "${cause.javaClass.simpleName}: ${cause.message}"
            log("$name's Trackhop did not start: ${broken[name]}")
            runCatching { loader.close() }
        }
    }

    private fun stopEngine(name: String) {
        val e = engines.remove(name) ?: return
        runCatching { withLoader(e.loader) { e.stop.run() } }
        // Closed a little later: a thread of that engine still winding down may need a class of it.
        e.loader?.let { loader -> closer.schedule({ runCatching { loader.close() } }, 2, TimeUnit.MINUTES) }
    }

    /** New permissions take effect in a new copy of the person's Trackhop (a run under way stops). */
    private fun restartEngine(name: String) {
        stopEngine(name)
        startEngine(name)
    }

    private val closer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trackhop-users").apply { isDaemon = true } }

    /** The admin, or a co-admin ([Permissions.ADMIN]): who sees the Users page. */
    private fun manages(name: String) = name == ADMIN || Permissions.ADMIN in users.permissions(name)

    private fun engineOf(name: String): Engine? = if (name == ADMIN) adminEngine else engines[name]

    private fun userDir(name: String) = File(File(root, USERS_DIR), name)

    // ------------------------------------------------------------------ requests

    private val handler = HttpHandler { ex ->
        try {
            route(ex)
        } catch (e: Exception) {
            log("${ex.requestMethod} ${ex.requestURI.path}: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { send(ex, 500, page("Something went wrong", """<div class="card err">${h(e.message ?: e.javaClass.simpleName)}</div><p><a href="/">Back</a></p>""")) }
        } finally {
            ex.close()
        }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod.uppercase()
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        // What any Trackhop answers without a password: the health check, the installable app's files.
        if (path == "/health" || (method == "GET" && parts.size == 1 && parts[0] in Pwa.files)) return adminEngine.handler.handle(ex)
        if (path == "/style.css") return send(ex, 200, Html.CSS, "text/css")
        // The API and the command line: the token (or the command line's key) says whose Trackhop it is.
        if (parts.size >= 2 && parts[0] == "api" && parts[1] == "v1") {
            val header = ex.requestHeaders.getFirst("Authorization")
            val from = ex.remoteAddress?.address
            val engine = listOf(adminEngine).plus(engines.values).firstOrNull { it.acceptsApi.test(header, from) } ?: adminEngine
            return delegate(engine, ex)
        }
        if (path == "/login") return login(ex, method)
        if (parts.size == 2 && parts[0] == "invite") return acceptInvite(ex, method, parts[1])
        val name = signedIn(ex) ?: return askToSignIn(ex, method)
        if (method == "POST" && !sameOrigin({ ex.requestHeaders.getFirst(it) }, config.baseUrl)) return send(ex, 403, "Cross-site request refused", "text/plain")
        when {
            path == "/logout" && method == "POST" -> logout(ex)
            path == "/account" -> account(ex, method, name)
            path == "/users" && manages(name) -> usersPage(ex, method, name)
            else -> {
                val engine = engineOf(name) ?: return send(ex, 503, page(
                    "Your Trackhop did not start",
                    """<div class="card err">${h(broken[name] ?: "It is starting: try again in a moment.")}</div><p class="muted">The admin finds the details in the log.</p>""",
                    Html.Account(name, manages(name)),
                ))
                delegate(engine, ex)
            }
        }
    }

    /** Hands the request to [engine], with its own loader as the thread's while it answers. */
    private fun delegate(engine: Engine, ex: HttpExchange) = withLoader(engine.loader) { engine.handler.handle(ex) }

    private fun <T> withLoader(loader: ClassLoader?, block: () -> T): T {
        if (loader == null) return block()
        val thread = Thread.currentThread()
        val before = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            return block()
        } finally {
            thread.contextClassLoader = before
        }
    }

    // ------------------------------------------------------------------ signing in

    /** Who sent [ex]: their session cookie, or a user name and password sent with the request (scripts, older bookmarks). */
    private fun signedIn(ex: HttpExchange): String? {
        cookie(ex, COOKIE)?.let { token -> sessions.user(token, config.password!!)?.let { if (exists(it)) return it } }
        val header = ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Basic ") } ?: return null
        val key = sha256(header)
        basicCache[key]?.let { (name, until) -> if (System.currentTimeMillis() < until && exists(name)) return name }
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val name = normalize(decoded.substringBefore(':'))
        if (!verify(name, decoded.substringAfter(':', ""))) return null
        basicCache[key] = name to System.currentTimeMillis() + BASIC_CACHE_MS
        return name
    }

    private val basicCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun exists(name: String) = name == ADMIN || users.has(name)

    private fun verify(name: String, password: String): Boolean =
        if (name == ADMIN) MessageDigest.isEqual(password.toByteArray(), config.password!!.toByteArray()) else users.check(name, password)

    private fun askToSignIn(ex: HttpExchange, method: String) {
        if (method != "GET") return send(ex, 401, "Sign in first", "text/plain")
        val wanted = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
        redirect(ex, if (wanted == "/") "/login" else "/login?next=${enc(wanted)}")
    }

    /** Failed sign-ins by address: after [MAX_FAILURES] in [FAILURE_WINDOW_MS], that address waits. */
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()

    private fun login(ex: HttpExchange, method: String) {
        val query = form(ex.requestURI.rawQuery.orEmpty())
        val next = safeNext(query["next"])
        if (method != "POST") return send(ex, 200, loginPage(next, null))
        if (!sameOrigin({ ex.requestHeaders.getFirst(it) }, config.baseUrl)) return send(ex, 403, "Cross-site request refused", "text/plain")
        val f = form(String(ex.requestBody.readNBytes(MAX_FORM), StandardCharsets.UTF_8))
        val ip = ex.remoteAddress?.address?.hostAddress.orEmpty()
        val now = System.currentTimeMillis()
        failures[ip]?.let { (count, since) ->
            if (now - since > FAILURE_WINDOW_MS) failures.remove(ip)
            else if (count >= MAX_FAILURES) return send(ex, 429, loginPage(next, "Too many wrong passwords from here: wait a few minutes and try again."))
        }
        val name = normalize(f["user"].orEmpty())
        if (!verify(name, f["password"].orEmpty())) {
            failures.merge(ip, 1 to now) { old, _ -> old.first + 1 to old.second }
            log("wrong password for \"${name.take(32)}\" from $ip")
            return send(ex, 401, loginPage(safeNext(f["next"]), "Wrong user name or password."))
        }
        failures.remove(ip)
        val token = sessions.create(name, config.password!!)
        ex.responseHeaders.add("Set-Cookie", cookieHeader(token, SESSION_DAYS * 86_400L))
        redirect(ex, safeNext(f["next"]))
    }

    private fun logout(ex: HttpExchange) {
        cookie(ex, COOKIE)?.let { sessions.remove(it) }
        ex.responseHeaders.add("Set-Cookie", cookieHeader("", 0))
        redirect(ex, "/login")
    }

    /**
     * Someone opening the link the admin sent them: they choose their password, and the account the
     * admin prepared (name, what they may use) is theirs, signed in. A link works once, for [Invites.DAYS] days.
     */
    private fun acceptInvite(ex: HttpExchange, method: String, token: String) {
        val invite = invites.find(token)
        if (invite == null || users.has(invite.name)) return send(ex, 404, page(
            "This invitation does not work",
            """<div class="card err">The link was already used, has expired or was cancelled.</div><p class="muted">Ask whoever sent it for a new one. Already have an account? <a href="/login">Sign in</a>.</p>""",
        ))
        var error: String? = null
        if (method == "POST") {
            if (!sameOrigin({ ex.requestHeaders.getFirst(it) }, config.baseUrl)) return send(ex, 403, "Cross-site request refused", "text/plain")
            val f = form(String(ex.requestBody.readNBytes(MAX_FORM), StandardCharsets.UTF_8))
            val password = f["password"].orEmpty()
            when {
                password.length < MIN_PASSWORD -> error = "Use at least $MIN_PASSWORD characters."
                password != f["repeat"] -> error = "The two passwords are different."
                invites.take(token) == null -> return send(ex, 404, page("This invitation does not work", """<div class="card err">The link was just used.</div><p><a href="/login">Sign in</a></p>"""))
                else -> {
                    users.add(invite.name, password, invite.permissions)
                    startEngine(invite.name)
                    log("${invite.name} joined with the invitation from ${invite.by}")
                    val session = sessions.create(invite.name, config.password!!)
                    ex.responseHeaders.add("Set-Cookie", cookieHeader(session, SESSION_DAYS * 86_400L))
                    return redirect(ex, "/")
                }
            }
        }
        val body = """${error?.let { """<div class="card err">${h(it)}</div>""" } ?: ""}<form method="post" class="card">
<p>${h(invite.by)} invited you to this Trackhop: you get one of your own, with your services and syncs. Nobody else on this server sees them.</p>
<label for="user">Your user name</label><input type="text" id="user" name="user" value="${h(invite.name)}" autocomplete="username" readonly>
<label for="password">Choose a password</label><input type="password" id="password" name="password" autocomplete="new-password" minlength="$MIN_PASSWORD" required autofocus>
<label for="repeat">Password again</label><input type="password" id="repeat" name="repeat" autocomplete="new-password" minlength="$MIN_PASSWORD" required>
<p><button>Start</button></p></form>
<p class="muted">You sign in with this name and password from now on; you can change the password under your name.</p>"""
        send(ex, 200, Html.page("Welcome", body, admin.version, null, Html.Edition.SERVER, "invite", null, showNav = false))
    }

    private fun cookieHeader(value: String, maxAge: Long): String =
        "$COOKIE=$value; Path=/; Max-Age=$maxAge; HttpOnly; SameSite=Lax" + if (config.baseUrl.startsWith("https://")) "; Secure" else ""

    private fun loginPage(next: String, error: String?): String = Html.page(
        "Sign in",
        """${error?.let { """<div class="card err">${h(it)}</div>""" } ?: ""}<form method="post" action="/login" class="card">
<input type="hidden" name="next" value="${h(next)}">
<label for="user">User name</label><input type="text" id="user" name="user" autocomplete="username" autocapitalize="none" required autofocus>
<label for="password">Password</label><input type="password" id="password" name="password" autocomplete="current-password" required>
<p><button>Sign in</button></p></form>
<p class="muted">Each person on this Trackhop has their own services and syncs. Forgot your password? Ask the admin: they set a new one on the Users page. The admin's own is <code>SONGPORT_PASSWORD</code> in the container's settings.</p>""",
        admin.version, null, Html.Edition.SERVER, "login", null, showNav = false,
    )

    // ------------------------------------------------------------------ pages

    private fun account(ex: HttpExchange, method: String, name: String) {
        val me = Html.Account(name, manages(name))
        var message: String? = null
        var error: String? = null
        if (method == "POST" && name != ADMIN) {
            val f = form(String(ex.requestBody.readNBytes(MAX_FORM), StandardCharsets.UTF_8))
            val fresh = f["new"].orEmpty()
            when {
                !users.check(name, f["current"].orEmpty()) -> error = "Your current password is not that one."
                fresh.length < MIN_PASSWORD -> error = "Use at least $MIN_PASSWORD characters."
                fresh != f["repeat"] -> error = "The two new passwords are different."
                else -> {
                    users.setPassword(name, fresh)
                    // Other browsers signed in as this person sign in again; this one stays.
                    sessions.removeUser(name, keep = cookie(ex, COOKIE))
                    basicCache.clear()
                    log("$name changed their password")
                    message = "Password changed. Other devices signed in as you have to sign in again."
                }
            }
        }
        val passwordCard = if (name == ADMIN)
            """<div class="card"><p>You are the admin: your password is <code>SONGPORT_PASSWORD</code> in the container's settings (the Compose file, or the container's environment in Portainer, Unraid, Synology…). To change it, or if you forgot it, read or set it there and restart the container.</p><p><a class="button secondary" href="/users">Users</a></p></div>"""
        else """<form method="post" action="/account" class="card"><h2>Change your password</h2>
<label for="current">Current password</label><input type="password" id="current" name="current" autocomplete="current-password" required>
<label for="new">New password</label><input type="password" id="new" name="new" autocomplete="new-password" minlength="$MIN_PASSWORD" required>
<label for="repeat">New password again</label><input type="password" id="repeat" name="repeat" autocomplete="new-password" minlength="$MIN_PASSWORD" required>
<p><button>Change password</button></p></form>"""
        val body = """${message?.let { """<div class="card ok">${h(it)}</div>""" } ?: ""}${error?.let { """<div class="card err">${h(it)}</div>""" } ?: ""}
<div class="card"><p>Signed in as <b>${h(name)}</b>.</p><p class="muted">Your services, syncs, files and backups are yours alone: the others on this server do not see them.</p>
${if (name == ADMIN) "" else {
            val mine = users.permissions(name)
            val (yes, no) = Permissions.ALL.partition { it.first in mine }
            fun list(ps: List<Triple<String, String, String>>) = ps.joinToString("") { "<li>${h(it.second)}</li>" }
            (if (yes.isNotEmpty()) "<p>✓ Turned on for you:</p><ul>${list(yes)}</ul>" else "") +
                (if (no.isNotEmpty()) "<p>✗ Not turned on for you:</p><ul>${list(no)}</ul>" else "") +
                "<p class=\"muted\">The admin sets these on the Users page.</p>"
        }}
${Html.postButton("/logout", "Sign out", "secondary")}</div>
$passwordCard"""
        send(ex, 200, page("Your account", body, me, "account"))
    }

    private fun inviteLink(token: String) = "${config.baseUrl}/invite/$token"

    private fun usersPage(ex: HttpExchange, method: String, me: String) {
        var message: String? = null
        var error: String? = null
        /** An invitation just made: the person and their link, shown this once (only its hash is kept). */
        var newLink: Pair<String, String>? = null
        if (method == "POST") {
            val pairs = formPairs(String(ex.requestBody.readNBytes(MAX_FORM), StandardCharsets.UTF_8))
            val f = pairs.toMap()
            val name = normalize(f["name"].orEmpty())
            val password = f["password"].orEmpty()
            val chosen = Permissions.parse(pairs.filter { it.first == "perm" }.joinToString(",") { it.second })
            when (f["action"]) {
                "add" -> when {
                    !NAME.matches(name) -> error = "A user name is 1 to 32 lowercase letters, digits, - or _, starting with a letter or a digit."
                    name in RESERVED -> error = "\"$name\" is taken: choose another name."
                    users.has(name) -> error = "There is already a user called $name."
                    // The page's own button says which; a script sending only a password adds as before.
                    f["how"] == "invite" || (f["how"] == null && password.isEmpty()) -> {
                        // Invited: the name is kept for them until they choose their password.
                        val token = invites.create(name, chosen, me)
                        log("$me invited $name (${Permissions.format(chosen).ifEmpty { "no permissions" }})")
                        newLink = name to inviteLink(token)
                    }
                    password.length < MIN_PASSWORD -> error = "Use at least $MIN_PASSWORD characters for the password."
                    else -> {
                        users.add(name, password, chosen)
                        invites.cancel(name)
                        startEngine(name)
                        log("$me added $name (${Permissions.format(chosen).ifEmpty { "no permissions" }})")
                        message = "Added $name: they sign in with that name and password, and start with an empty Trackhop."
                    }
                }
                "password" -> when {
                    !users.has(name) -> error = "There is no user called $name."
                    password.length < MIN_PASSWORD -> error = "Use at least $MIN_PASSWORD characters for the password."
                    else -> {
                        users.setPassword(name, password)
                        sessions.removeUser(name)
                        basicCache.clear()
                        log("$me set a new password for $name")
                        message = "New password for $name: tell them, they sign in again with it."
                    }
                }
                "permissions" -> when {
                    !users.has(name) -> error = "There is no user called $name."
                    // Nobody takes their own co-admin away by mistake: another admin does it.
                    name == me && Permissions.ADMIN !in chosen -> error = "You cannot take co-admin away from yourself: the admin or another co-admin can."
                    else -> {
                        users.setPermissions(name, chosen)
                        restartEngine(name)
                        log("$me set $name's permissions: ${Permissions.format(chosen).ifEmpty { "none" }}")
                        message = "Saved $name's permissions. Their Trackhop restarted with them: a sync that was running starts again at its next turn."
                    }
                }
                "renew" -> when (val old = invites.pending().firstOrNull { it.name == name }) {
                    null -> error = "There is no invitation for $name."
                    else -> {
                        newLink = name to inviteLink(invites.create(name, old.permissions, me))
                        log("$me made a new invitation link for $name")
                    }
                }
                "cancel" -> {
                    invites.cancel(name)
                    log("$me cancelled the invitation for $name")
                    message = "Cancelled the invitation for $name: the link no longer works."
                }
                "remove" -> when {
                    !users.has(name) -> error = "There is no user called $name."
                    name == me -> error = "You cannot remove yourself: the admin or another co-admin can."
                    else -> {
                        stopEngine(name)
                        users.remove(name)
                        sessions.removeUser(name)
                        basicCache.clear()
                        broken.remove(name)
                        val kept = moveAway(name)
                        log("$me removed $name, data kept in ${kept?.path ?: "(nothing)"}")
                        message = "Removed $name." + (kept?.let { " Their data is kept in ${it.path}: delete that folder when you no longer need it." } ?: "")
                    }
                }
            }
        }
        fun boxes(selected: Set<String>) = Permissions.ALL.joinToString("") { (k, label, help) ->
            """<label class="check"><input type="checkbox" name="perm" value="$k"${if (k in selected) " checked" else ""}> <span><b>${h(label)}</b><br><span class="muted">${h(help)}</span></span></label>"""
        }
        val pending = invites.pending().joinToString("") { inv ->
            """<div class="card"><p><b>${h(inv.name)}</b> <span class="badge">invited</span> <span class="muted">by ${h(inv.by)}, link valid until ${Html.date(inv.until)}</span></p>
<p class="row">${Html.postButton("/users", "New link", "secondary", mapOf("action" to "renew", "name" to inv.name))} ${Html.postButton("/users", "Cancel invitation", "secondary", mapOf("action" to "cancel", "name" to inv.name))}</p></div>"""
        }
        val rows = users.names().joinToString("") { n ->
            val state = broken[n]?.let { """<span class="err">did not start: ${h(it)}</span>""" } ?: if (engines.containsKey(n)) "" else "starting…"
            val perms = users.permissions(n)
            """<div class="card"><p><b>${h(n)}</b>${if (Permissions.ADMIN in perms) " <span class=\"badge\">co-admin</span>" else ""}${if (n == me) " <span class=\"badge\">you</span>" else ""} <span class="muted">since ${Html.date(users.created(n))}</span> $state</p>
<details><summary>Permissions</summary><form method="post" action="/users"><input type="hidden" name="action" value="permissions"><input type="hidden" name="name" value="${h(n)}">${boxes(perms)}<p><button class="secondary">Save permissions</button></p></form></details>
<details><summary>Forgot the password?</summary><form method="post" action="/users"><input type="hidden" name="action" value="password"><input type="hidden" name="name" value="${h(n)}"><label>New password for ${h(n)}</label><input type="password" name="password" autocomplete="new-password" minlength="$MIN_PASSWORD" required><p class="muted">They are signed out everywhere and sign in again with it; they can change it afterwards under their name.</p><p><button class="secondary">Set the new password</button></p></form></details>
${if (n == me) "" else Html.postButton("/users", "Remove $n", "secondary", mapOf("action" to "remove", "name" to n), confirm = "Remove $n? Their Trackhop stops; their data is kept in a folder you can delete afterwards.")}</div>"""
        }.ifEmpty { if (pending.isEmpty()) """<p class="muted">Nobody else yet.</p>""" else "" }
        val linkCard = newLink?.let { (n, link) -> """<div class="card ok"><p><b>Invitation for ${h(n)}</b>: send them this link (a message, an email…). It works once, for ${Invites.DAYS} days: they open it, choose their password and are in.</p>
<p class="row"><input type="text" id="invite-link" value="${h(link)}" readonly onfocus="this.select()"> <button type="button" class="secondary" onclick="var i=document.getElementById('invite-link');i.select();(navigator.clipboard?navigator.clipboard.writeText(i.value):Promise.reject()).catch(function(){document.execCommand('copy')});this.textContent='Copied'">Copy</button></p>
<p class="muted">It is shown only now. Lost it? Make a new one below: the old one stops working.</p></div>""" } ?: ""
        val body = """${message?.let { """<div class="card ok">${h(it)}</div>""" } ?: ""}${error?.let { """<div class="card err">${h(it)}</div>""" } ?: ""}$linkCard
<p class="muted">Each person has a Trackhop of their own on this server: their own services, syncs, files, backups and log, in <code>${h(File(root, USERS_DIR).path)}/&lt;name&gt;</code>. Their scheduled syncs run whether or not they are signed in. <b>$ADMIN</b> keeps this server's own Trackhop and may do everything.</p>
<form method="post" action="/users" class="card"><h2>Add someone</h2><input type="hidden" name="action" value="add">
<label for="name">User name</label><input type="text" id="name" name="name" autocapitalize="none" pattern="[a-zA-Z0-9][a-zA-Z0-9_\-]{0,31}" required>
<p><b>They may use</b></p>${boxes(Permissions.DEFAULT)}
<p><button name="how" value="invite">Create an invitation link</button></p>
<p class="muted">They open the link, choose their own password and are in: you never need to know it.</p>
<details><summary>Or choose their password yourself</summary>
<label for="password">Password</label><input type="password" id="password" name="password" autocomplete="new-password" minlength="$MIN_PASSWORD">
<p class="muted">Then tell them their name and this password.</p>
<p><button name="how" value="password" class="secondary">Add with this password</button></p></details></form>
<h2>People</h2>
$pending$rows
<div class="card"><p><b>The admin's password</b> is <code>SONGPORT_PASSWORD</code> in the container's settings (the Compose file, or the container's environment in Portainer, Unraid, Synology…). Forgot it? Read it there, or set a new one and restart the container: nothing else changes.</p></div>"""
        send(ex, 200, page("Users", body, Html.Account(me, true), "users"))
    }

    /** A removed person's folder, moved out of the way (`users-removed/<name>-<time>`), or null when there was none. */
    private fun moveAway(name: String): File? {
        val dir = userDir(name)
        if (!dir.exists()) return null
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = File(File(root, REMOVED_DIR), "$name-$stamp").apply { parentFile.mkdirs() }
        return if (dir.renameTo(target)) target else dir
    }

    private fun page(title: String, body: String, account: Html.Account? = null, path: String = "") =
        Html.page(title, body, admin.version, null, Html.Edition.SERVER, path, account)

    // ------------------------------------------------------------------ plumbing

    private fun send(ex: HttpExchange, code: Int, body: String, type: String = "text/html") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "$type; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.responseHeaders.set("X-Frame-Options", "DENY")
        ex.responseHeaders.set("Referrer-Policy", "same-origin")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.set("Location", to)
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(303, -1)
    }

    companion object {
        /** The admin's user name: SONGPORT_PASSWORD is their password. */
        const val ADMIN = "admin"
        const val USERS_DIR = "users"
        const val REMOVED_DIR = "users-removed"
        internal const val COOKIE = "trackhop_session"
        /** Settings a person's engine receives besides the container's: their name, the shared data folder, the server's port. */
        internal const val ACCOUNT = "TRACKHOP_ACCOUNT"
        internal const val ROOT = "TRACKHOP_ROOT"
        internal const val PORT = "TRACKHOP_PORT"
        internal const val PERMISSIONS = "TRACKHOP_PERMISSIONS"
        private const val MIN_PASSWORD = 8
        private const val SESSION_DAYS = 30
        private const val MAX_FORM = 64 * 1024
        private const val MAX_FAILURES = 10
        private const val FAILURE_WINDOW_MS = 15 * 60_000L
        private const val BASIC_CACHE_MS = 10 * 60_000L
        internal val NAME = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        private val RESERVED = setOf(ADMIN, "root", "users", "api", "login", "logout")

        internal fun normalize(name: String) = name.trim().lowercase()

        /** Where to go after signing in: a path of this server only. */
        internal fun safeNext(next: String?): String = next?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") } ?: "/"

        private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

        private fun formPairs(body: String): List<Pair<String, String>> = body.split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
        }

        private fun form(body: String): Map<String, String> = formPairs(body).toMap()

        private fun cookie(ex: HttpExchange, name: String): String? = ex.requestHeaders["Cookie"].orEmpty()
            .flatMap { it.split(';') }.map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

        internal fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * Starts the shared server on [config]'s port: the admin's Trackhop in the data folder, one
         * Trackhop per person added, and the sign-in in front of them. Returns once it is serving.
         */
        fun serve(config: Config, env: Map<String, String>): MultiUser {
            val admin = launch(Config(env - "SONGPORT_PASSWORD"), Html.Edition.SERVER, account = Html.Account(ADMIN, true))
            val hub = MultiUser(config, env, admin, UserStore(File(config.dataDir, "users.json")), Sessions(File(config.dataDir, "sessions.json")), Invites(File(config.dataDir, "invites.json")))
            hub.users.names().forEach { hub.startEngine(it) }
            val server = HttpServer.create(InetSocketAddress(config.port), 0)
            server.executor = Executors.newFixedThreadPool(16)
            server.createContext("/", hub.handler)
            server.start()
            admin.started(false, config.port)
            hub.log("several people on this server: $ADMIN and ${hub.users.names().size} more; listening on port ${server.address.port}, reachable as ${config.baseUrl}")
            hub.server = server
            return hub
        }
    }

    /** The running server (tests stop it). */
    @Volatile var server: HttpServer? = null
        private set

    /** Stops serving and every person's Trackhop (tests). */
    fun stop() {
        server?.stop(0)
        engines.keys.toList().forEach { stopEngine(it) }
        admin.stop()
    }
}

/**
 * One person's Trackhop, started inside the class loader [MultiUser] made for it: its own copy of
 * the engine, set up on its own folder, without its own web server (the shared one hands it the
 * requests). Settings in, JDK types out.
 */
object UserEngine {
    @JvmStatic
    fun start(settings: Map<String, String>): Map<String, Any> {
        val name = settings.getValue(MultiUser.ACCOUNT)
        val permissions = Permissions.parse(settings[MultiUser.PERMISSIONS])
        Log.who = name
        val app = launch(
            Config(settings), Html.Edition.SERVER,
            // Without the unofficial connectors, as the Microsoft Store's build: not even listed.
            officialOnly = Permissions.UNOFFICIAL !in permissions,
            account = Html.Account(name, Permissions.ADMIN in permissions),
            sharedRoot = File(settings.getValue(MultiUser.ROOT)),
            permissions = permissions,
        )
        app.started(false, settings.getValue(MultiUser.PORT).toInt())
        return mapOf(
            "handler" to app.web.handler(),
            "accepts" to BiPredicate<String?, InetAddress?> { header, from -> app.web.acceptsApi(header, from) },
            "stop" to Runnable { app.stop() },
        )
    }
}

/** The people of a shared server and their passwords (PBKDF2, salted), in `users.json`, readable by its owner only. */
internal class UserStore(private val file: File) {
    private class Entry(val salt: String, val hash: String, val iterations: Int, val created: Long, val permissions: Set<String>)

    private val lock = Any()
    private var entries: Map<String, Entry> = load()

    private fun load(): Map<String, Entry> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            // Added before permissions existed (0.6.0): what a new person gets.
            val perms = o["permissions"]?.jsonPrimitive?.contentOrNull?.let { Permissions.parse(it) } ?: Permissions.DEFAULT
            Entry(o.str("salt"), o.str("hash"), o["iterations"]!!.jsonPrimitive.int, o["created"]?.jsonPrimitive?.long ?: 0, perms)
        }
    }.getOrDefault(emptyMap())

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun save() = writePrivate(file, buildJsonObject {
        entries.toSortedMap().forEach { (name, e) ->
            put(name, buildJsonObject { put("salt", e.salt); put("hash", e.hash); put("iterations", e.iterations); put("created", e.created); put("permissions", Permissions.format(e.permissions)) })
        }
    }.toString())

    fun names(): List<String> = synchronized(lock) { entries.keys.sorted() }
    fun has(name: String) = synchronized(lock) { name in entries }
    fun created(name: String): Long = synchronized(lock) { entries[name]?.created ?: 0 }

    fun permissions(name: String): Set<String> = synchronized(lock) { entries[name]?.permissions.orEmpty() }

    fun add(name: String, password: String, permissions: Set<String> = Permissions.DEFAULT) = synchronized(lock) {
        entries = entries + (name to entry(password, System.currentTimeMillis(), permissions))
        save()
    }

    fun setPassword(name: String, password: String) = synchronized(lock) {
        val old = entries[name] ?: return
        entries = entries + (name to entry(password, old.created, old.permissions))
        save()
    }

    fun setPermissions(name: String, permissions: Set<String>) = synchronized(lock) {
        val old = entries[name] ?: return
        entries = entries + (name to Entry(old.salt, old.hash, old.iterations, old.created, permissions))
        save()
    }

    fun remove(name: String) = synchronized(lock) {
        entries = entries - name
        save()
    }

    fun check(name: String, password: String): Boolean {
        val e = synchronized(lock) { entries[name] } ?: run {
            // As long as a real check, so the time taken does not tell which names exist.
            hash(password, ByteArray(16), ITERATIONS)
            return false
        }
        val given = hash(password, Base64.getDecoder().decode(e.salt), e.iterations)
        return MessageDigest.isEqual(given.toByteArray(), e.hash.toByteArray())
    }

    private fun entry(password: String, created: Long, permissions: Set<String>): Entry {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Entry(Base64.getEncoder().encodeToString(salt), hash(password, salt, ITERATIONS), ITERATIONS, created, permissions)
    }

    private fun hash(password: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    companion object {
        private const val ITERATIONS = 210_000
    }
}

/**
 * Who is signed in where: a random token in the browser's cookie, and here only its SHA-256 with the
 * person and when it ends, in `sessions.json` (signing in survives a restart of the container).
 */
internal class Sessions(private val file: File) {
    /**
     * [check]: for the admin, a hash of the session with SONGPORT_PASSWORD, so a new password in the
     * container's settings signs every admin session out (without keeping the password itself here).
     */
    private class Session(val user: String, val until: Long, val check: String = "")

    private val lock = Any()
    private var all: Map<String, Session> = load()

    private fun load(): Map<String, Session> = runCatching {
        val now = System.currentTimeMillis()
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Session(o["user"]!!.jsonPrimitive.content, o["until"]!!.jsonPrimitive.long, o["check"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.filterValues { it.until > now }
    }.getOrDefault(emptyMap())

    private fun save() = writePrivate(file, buildJsonObject {
        all.forEach { (k, s) -> put(k, buildJsonObject { put("user", s.user); put("until", s.until); if (s.check.isNotEmpty()) put("check", s.check) }) }
    }.toString())

    private fun check(user: String, key: String, adminPassword: String) =
        if (user == MultiUser.ADMIN) MultiUser.sha256("$key:$adminPassword") else ""

    fun create(user: String, adminPassword: String): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val key = MultiUser.sha256(token)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            all = all.filterValues { it.until > now } + (key to Session(user, now + DAYS * 86_400_000L, check(user, key, adminPassword)))
            save()
        }
        return token
    }

    fun user(token: String, adminPassword: String): String? {
        val key = MultiUser.sha256(token)
        val s = synchronized(lock) { all[key] } ?: return null
        if (s.until <= System.currentTimeMillis()) return null
        if (!MessageDigest.isEqual(s.check.toByteArray(), check(s.user, key, adminPassword).toByteArray())) return null
        return s.user
    }

    fun remove(token: String) = synchronized(lock) {
        all = all - MultiUser.sha256(token)
        save()
    }

    /** Signs [user] out everywhere, except the browser holding [keep]. */
    fun removeUser(user: String, keep: String? = null) = synchronized(lock) {
        val kept = keep?.let { MultiUser.sha256(it) }
        all = all.filter { (k, s) -> s.user != user || k == kept }
        save()
    }

    companion object {
        private const val DAYS = 30
    }
}

/**
 * Invitations to a shared server: the admin prepares a person (name, what they may use) and sends
 * them a link; opening it, they choose their password. Here only the link's SHA-256 is kept, in
 * `invites.json`, with whose it is and until when. One per name: a new link replaces the old one.
 */
internal class Invites(private val file: File) {
    class Invite(val name: String, val permissions: Set<String>, val by: String, val until: Long)

    private val lock = Any()
    private var all: Map<String, Invite> = load()

    private fun load(): Map<String, Invite> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Invite(o["name"]!!.jsonPrimitive.content, Permissions.parse(o["permissions"]?.jsonPrimitive?.contentOrNull), o["by"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["until"]!!.jsonPrimitive.long)
        }
    }.getOrDefault(emptyMap())

    private fun save() = writePrivate(file, buildJsonObject {
        all.forEach { (k, i) -> put(k, buildJsonObject { put("name", i.name); put("permissions", Permissions.format(i.permissions)); put("by", i.by); put("until", i.until) }) }
    }.toString())

    private fun live(now: Long = System.currentTimeMillis()) = all.filterValues { it.until > now }

    /** A new link for [name] (any earlier one stops working); returns its token, for the link only. */
    fun create(name: String, permissions: Set<String>, by: String): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        synchronized(lock) {
            all = live().filterValues { it.name != name } + (MultiUser.sha256(token) to Invite(name, permissions, by, System.currentTimeMillis() + DAYS * 86_400_000L))
            save()
        }
        return token
    }

    fun find(token: String): Invite? = synchronized(lock) { live()[MultiUser.sha256(token)] }

    /** Uses up [token]: the invitation it was, or null when it no longer works (two tabs, a second click). */
    fun take(token: String): Invite? = synchronized(lock) {
        val key = MultiUser.sha256(token)
        live()[key]?.also { all = all - key; save() }
    }

    fun cancel(name: String) = synchronized(lock) {
        if (all.values.any { it.name == name }) { all = all.filterValues { it.name != name }; save() }
    }

    fun pending(): List<Invite> = synchronized(lock) { live().values.sortedBy { it.name } }

    companion object {
        const val DAYS = 7
    }
}

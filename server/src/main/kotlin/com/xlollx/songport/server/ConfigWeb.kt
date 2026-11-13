package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.EncryptedBackup
import com.xlollx.songport.server.Html.h

/**
 * Tools › Configuration: the whole set-up as a .trackhop file (ConfigBackup, as the app's
 * Settings › Configuration), downloaded, or brought in from another Trackhop (the app, Docker,
 * Desktop): the file is read and summed up first, then imported on confirmation.
 */
internal fun Web.configPage(r: Web.Req) {
    if (r.method == "GET" && r.query["download"] != null) {
        val text = Notify.intoBackup(env, ConfigBackup.export(env, "$productName $version", history = r.query["history"] == "on"))
        return attachment(r, ConfigBackup.fileName(), text, "application/json")
    }
    if (r.method == "POST") {
        when (r.form["action"]) {
            // 1.18 (Features.v2): the same file with the sign-ins, sealed with the password typed twice.
            "encrypted" -> {
                if (!Features.v2) return redirect(r.ex, "/tools/backup")
                val password = r.form["password"].orEmpty()
                val problem = EncryptedBackup.passwordProblem(password.toCharArray(), r.form["password2"].orEmpty().toCharArray())
                if (problem != null) return redirect(r.ex, "/tools/backup?err=${q(if (problem == EncryptedBackup.PasswordProblem.SHORT) "The password needs at least ${EncryptedBackup.MIN_PASSWORD} characters." else "The two passwords are not the same.")}")
                val (text, _) = FullBackupServer.export(env, "$productName $version", history = r.form["history"] == "on", password = password.toCharArray())
                return attachment(r, ConfigBackup.fileName(), text, "application/json")
            }
            "unlock" -> {
                if (!Features.v2) return redirect(r.ex, "/tools/backup?err=${q(FullBackupServer.NEWER)}")
                val locked = Pending.takeLocked(r.form["pending"].orEmpty()) ?: return redirect(r.ex, "/tools/backup?err=${q("Choose the file again.")}")
                val opened = try {
                    FullBackupServer.open(env, locked.second, r.form["password"].orEmpty().toCharArray())
                } catch (e: EncryptedBackup.WrongPassword) {
                    return send(r.ex, 200, page("Import a configuration", unlockForm(locked.first, Pending.putLocked(locked.first, locked.second), wrong = true)))
                } catch (e: IllegalArgumentException) {
                    return redirect(r.ex, "/tools/backup?err=${q(e.message ?: "Not a Trackhop configuration file.")}")
                }
                val s = ConfigBackup.summary(opened.snapshot)
                val token = Pending.putOpened(opened)
                val web = opened.secrets.web
                val webNote = when {
                    web == null -> ""
                    web.platform != FullBackupServer.webHere(env) -> "<li>${web.accounts.size} web sign-ins that are not used here (they come from a phone's Trackhop Bridge, or the unofficial connectors are not allowed to you): paste those services' cookies again</li>"
                    else -> "<li>${web.accounts.size} web sign-ins (${h(web.accounts.joinToString(", "))})</li>"
                }
                val body = """<div class="card"><p><b>${h(locked.first)}</b>${if (s.writtenBy.isNotBlank()) ", from ${h(s.writtenBy)}" else ""}${if (s.writtenAt > 0) " on ${Html.date(s.writtenAt)}" else ""}:</p>
<ul><li>${s.syncs} syncs</li><li>${s.matches} matches chosen by hand</li><li>${s.files} File playlists</li>${if (s.runs > 0) "<li>${s.runs} past runs</li>" else ""}${if (opened.channels.isNotEmpty()) "<li>${opened.channels.size} notification channels</li>" else ""}<li>${opened.secrets.vault.size + opened.secrets.clientSecrets.size} sign-ins and client secrets</li>$webNote</ul>
<p class="muted">Syncs with the same id are replaced, the others added; File playlists already here are kept. A service may still ask you to sign in again: some sessions do not move to another device.</p>
<form method="post" action="/tools/config"><input type="hidden" name="action" value="import"><input type="hidden" name="pending" value="${h(token)}">
<label class="check"><input type="checkbox" name="replacesDevice"> This server replaces the other device</label>
<p class="muted">Ticked: its scheduled syncs stay on here (then turn off or uninstall Trackhop on the other device). Not ticked: they arrive turned off, so they do not run on both.</p>
${if (opened.conflicts.isEmpty()) "" else "<label class=\"check\"><input type=\"checkbox\" name=\"replaceSignIns\"> Replace the sign-ins already here: ${h(opened.conflicts.joinToString(", "))}</label><p class=\"muted\">Not ticked, they stay as they are.</p>"}
<p><button>Import</button> <a class="button secondary" href="/tools/backup">Cancel</a></p></form></div>"""
                return send(r.ex, 200, page("Import a configuration", body))
            }
            "read" -> {
                val file = r.files["file"] ?: return redirect(r.ex, "/tools/backup?err=${q("Choose a .trackhop file.")}")
                val text = String(file.bytes, Charsets.UTF_8)
                // An encrypted file: its password first (1.18); without the feature, a clear word.
                if (EncryptedBackup.isEncrypted(text)) {
                    if (!Features.v2) return redirect(r.ex, "/tools/backup?err=${q(FullBackupServer.NEWER)}")
                    return send(r.ex, 200, page("Import a configuration", unlockForm(file.name, Pending.putLocked(file.name, text), wrong = false)))
                }
                val snapshot = runCatching { ConfigBackup.read(text) }
                    .getOrElse { return redirect(r.ex, "/tools/backup?err=${q(it.message ?: "Not a Trackhop configuration file.")}") }
                val s = ConfigBackup.summary(snapshot)
                // The channels ride next to the engine's set-up: ConfigBackup does not know them.
                val channels = Notify.inBackup(text)
                val token = Pending.put(snapshot, channels)
                val body = """<div class="card"><p><b>${h(file.name)}</b>${if (s.writtenBy.isNotBlank()) ", from ${h(s.writtenBy)}" else ""}${if (s.writtenAt > 0) " on ${Html.date(s.writtenAt)}" else ""}:</p>
<ul><li>${s.syncs} syncs</li><li>${s.matches} matches chosen by hand</li><li>${s.files} File playlists</li>${if (s.runs > 0) "<li>${s.runs} past runs</li>" else ""}${if (channels.isNotEmpty()) "<li>${channels.size} notification channels (enter their tokens again)</li>" else ""}</ul>
<p class="muted">Syncs with the same id are replaced, the others added; File playlists already here are kept. Sign-ins are not in the file: connect the services again afterwards.</p>
${Html.postButton("/tools/config", "Import", fields = mapOf("action" to "import", "pending" to token))} <a class="button secondary" href="/tools/backup">Cancel</a></div>"""
                return send(r.ex, 200, page("Import a configuration", body))
            }
            "import" -> {
                val token = r.form["pending"].orEmpty()
                Pending.takeOpened(token)?.let { opened ->
                    val replacesDevice = r.form["replacesDevice"] == "on"
                    val done = FullBackupServer.import(env, opened, replacesDevice, replaceSignIns = r.form["replaceSignIns"] == "on", channels = env.may(Permissions.API))
                    val s = done.summary
                    val paused = if (s.paused > 0) " ${s.paused} scheduled ${if (s.paused == 1) "sync was" else "syncs were"} turned off here: if ${if (s.paused == 1) "it still runs" else "they still run"} on ${s.from.ifBlank { "the other device" }}, leave ${if (s.paused == 1) "it" else "them"} off here, otherwise turn ${if (s.paused == 1) "it" else "them"} on." else ""
                    return redirect(r.ex, "/?ok=${q("Imported ${s.syncs} syncs." + FullBackupServer.notes(done, replacesDevice) + paused)}")
                }
                val (snapshot, channels) = Pending.take(token) ?: return redirect(r.ex, "/tools/backup?err=${q("Choose the file again.")}")
                val s = ConfigBackup.import(env, snapshot)
                if (env.may(Permissions.API)) Notify.restore(env, channels)
                env.log("config", "imported ${s.syncs} syncs, ${s.files} files from ${s.writtenBy}")
                // Scheduled syncs from another device arrive turned off, so they do not run here and there at once.
                val paused = if (s.paused > 0) " ${s.paused} scheduled ${if (s.paused == 1) "sync was" else "syncs were"} turned off here: if ${if (s.paused == 1) "it still runs" else "they still run"} on ${s.from.ifBlank { "the other device" }}, leave ${if (s.paused == 1) "it" else "them"} off here, otherwise turn ${if (s.paused == 1) "it" else "them"} on." else ""
                return redirect(r.ex, "/?ok=${q("Imported ${s.syncs} syncs. Connect the services again in Services: the syncs carry on from there.$paused")}")
            }
        }
    }
    redirect(r.ex, "/tools/backup")
}

/** The set-up's two cards, on the Backup & restore page (with the encrypted one's form from 1.18). */
internal fun configCards(): String =
    """<h2>Your set-up</h2><div class="card"><p class="muted">Everything Trackhop knows, in one .trackhop file: the syncs, the services added, the matches you chose, the tracks you ignored, the settings, the notification channels and the File playlists. Not your sign-ins, passwords or tokens. The app, Docker and Trackhop Desktop all read it.</p>
<form method="get" action="/tools/config"><input type="hidden" name="download" value="1"><label class="check"><input type="checkbox" name="history"> Also the run history</label><p><button>Save the set-up to a file</button></p></form></div>
${if (Features.v2) encryptedCard() else ""}<form method="post" action="/tools/config" enctype="multipart/form-data" class="card"><input type="hidden" name="action" value="read">
<p class="muted">Restore one, from this or another Trackhop, phone or computer: you see what is in it before anything changes.</p>
<input type="file" name="file" accept=".trackhop,application/json" required><p><button class="secondary">Restore a set-up from a file</button></p></form>"""

/** "Include sign-ins and schedules (encrypted)": the password twice, and what losing it means. */
private fun encryptedCard(): String =
    """<form method="post" action="/tools/config" class="card"><input type="hidden" name="action" value="encrypted">
<p><b>Include sign-ins and schedules (encrypted)</b></p>
<p class="muted">The same file, plus the sign-ins, client secrets and pasted web cookies, locked with a password of yours (at least ${EncryptedBackup.MIN_PASSWORD} characters). Keep the password safe: if you lose it, the file can never be opened, and nobody can recover it, not even Trackhop. Anyone with the file and the password can use your accounts. A service may still ask you to sign in again after a restore.</p>
<p><input type="password" name="password" minlength="${EncryptedBackup.MIN_PASSWORD}" autocomplete="new-password" placeholder="Password" required> <input type="password" name="password2" minlength="${EncryptedBackup.MIN_PASSWORD}" autocomplete="new-password" placeholder="Password again" required></p>
<label class="check"><input type="checkbox" name="history"> Also the run history</label><p><button>Save the encrypted set-up to a file</button></p></form>
"""

/** The password of an encrypted file being restored. */
private fun unlockForm(name: String, token: String, wrong: Boolean): String =
    """<form method="post" action="/tools/config" class="card"><input type="hidden" name="action" value="unlock"><input type="hidden" name="pending" value="${h(token)}">
<p><b>${h(name)}</b> is an encrypted set-up: it holds sign-ins. Enter its password.</p>
${if (wrong) "<p class=\"err\">Wrong password, or the file was changed. Nothing was imported.</p>" else ""}
<p><input type="password" name="password" autocomplete="current-password" placeholder="Password" required autofocus></p>
<p><button>Open</button> <a class="button secondary" href="/tools/backup">Cancel</a></p></form>"""

/** A read file waiting for its confirmation (in memory, a few minutes): a plain one, an encrypted one before or after its password. */
private object Pending {
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<ConfigBackup.Snapshot, List<Notify.Channel>>>>()
    private val locked = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, String>>>()
    private val opened = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, FullBackupServer.Opened>>()
    private fun token() = java.util.UUID.randomUUID().toString()
    private fun <T> tidy(m: java.util.concurrent.ConcurrentHashMap<String, Pair<Long, T>>, now: Long, ms: Long) = m.values.removeIf { now - it.first > ms }
    fun put(s: ConfigBackup.Snapshot, channels: List<Notify.Channel>): String {
        val now = System.currentTimeMillis()
        waiting.values.removeIf { now - it.first > 15 * 60_000 }
        return java.util.UUID.randomUUID().toString().also { waiting[it] = now to (s to channels) }
    }
    fun take(token: String): Pair<ConfigBackup.Snapshot, List<Notify.Channel>>? = waiting.remove(token)?.second

    /** An encrypted file's name and text, until its password is typed. */
    fun putLocked(name: String, text: String): String {
        val now = System.currentTimeMillis()
        tidy(locked, now, 15 * 60_000)
        return token().also { locked[it] = now to (name to text) }
    }
    fun takeLocked(token: String): Pair<String, String>? = locked.remove(token)?.second

    /** An opened one, its secrets in memory for a few minutes at most. */
    fun putOpened(o: FullBackupServer.Opened): String {
        val now = System.currentTimeMillis()
        tidy(opened, now, 5 * 60_000)
        return token().also { opened[it] = now to o }
    }
    fun takeOpened(token: String): FullBackupServer.Opened? = opened.remove(token)?.let { (at, o) -> o.takeIf { System.currentTimeMillis() - at <= 5 * 60_000 } }
}

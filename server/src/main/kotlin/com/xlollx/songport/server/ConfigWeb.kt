package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.data.ConfigBackup
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
            "read" -> {
                val file = r.files["file"] ?: return redirect(r.ex, "/tools/backup?err=${q("Choose a .trackhop file.")}")
                val text = String(file.bytes, Charsets.UTF_8)
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
                val (snapshot, channels) = Pending.take(r.form["pending"].orEmpty()) ?: return redirect(r.ex, "/tools/backup?err=${q("Choose the file again.")}")
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

/** The set-up's two cards, on the Backup & restore page. */
internal fun configCards(): String =
    """<h2>Your set-up</h2><div class="card"><p class="muted">Everything Trackhop knows, in one .trackhop file: the syncs, the services added, the matches you chose, the tracks you ignored, the settings, the notification channels and the File playlists. Not your sign-ins, passwords or tokens. The app, Docker and Trackhop Desktop all read it.</p>
<form method="get" action="/tools/config"><input type="hidden" name="download" value="1"><label class="check"><input type="checkbox" name="history"> Also the run history</label><p><button>Save the set-up to a file</button></p></form></div>
<form method="post" action="/tools/config" enctype="multipart/form-data" class="card"><input type="hidden" name="action" value="read">
<p class="muted">Restore one, from this or another Trackhop, phone or computer: you see what is in it before anything changes.</p>
<input type="file" name="file" accept=".trackhop,application/json" required><p><button class="secondary">Restore a set-up from a file</button></p></form>"""

/** A read file waiting for its confirmation (in memory, a few minutes). */
private object Pending {
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<ConfigBackup.Snapshot, List<Notify.Channel>>>>()
    fun put(s: ConfigBackup.Snapshot, channels: List<Notify.Channel>): String {
        val now = System.currentTimeMillis()
        waiting.values.removeIf { now - it.first > 15 * 60_000 }
        return java.util.UUID.randomUUID().toString().also { waiting[it] = now to (s to channels) }
    }
    fun take(token: String): Pair<ConfigBackup.Snapshot, List<Notify.Channel>>? = waiting.remove(token)?.second
}

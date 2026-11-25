package com.xlollx.songport.server

import com.xlollx.songport.notify.NotifTarget
import com.xlollx.songport.notify.NotifyHistory
import com.xlollx.songport.server.Html.h
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Notifications page: what was sent to the notification channels (NotifyHistory, kept by
 * Notify.afterRun and Notify.sendAll), newest first, each with the channels that took it and a link
 * to its page. The app's bell, on the server. Also /api/v1/notifications/history and
 * `trackhop notifications` (GET /api/v1/notifications stays the list of channels).
 */
internal object NotifyPages {
    private fun q(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /** Where an entry's link leads on these pages; null when it has none. */
    fun url(target: String?): String? = when (val t = NotifTarget.parse(target)) {
        null -> null
        is NotifTarget.Sync -> "/syncs/${q(t.jobId)}"
        is NotifTarget.Review -> "/syncs/${q(t.jobId)}/review/${q(t.reportId)}"
        is NotifTarget.Group -> "/multi/${q(t.groupId)}"
        is NotifTarget.Unplayable -> "/tools/unplayable"
        NotifTarget.Followed -> "/tools/alerts"
        is NotifTarget.Link -> t.url
        is NotifTarget.Account -> "/connectors/${q(t.connectorId)}"
        NotifTarget.Settings -> "/settings"
        NotifTarget.Log -> "/log"
        NotifTarget.Tools -> "/tools"
        is NotifTarget.Update -> t.url ?: Updates.RELEASES
    }

    private val TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.ENGLISH)

    fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    fun json(e: NotifyHistory.Entry): JsonObject = buildJsonObject {
        put("id", e.id); put("at", e.at); put("kind", e.kind); put("title", e.title); put("text", e.text); put("read", e.read)
        url(e.target)?.let { put("url", it) }
        put("sent", buildJsonArray { e.sent.forEach { s -> add(buildJsonObject { put("channel", s.channel); s.error?.let { put("error", it) } }) } })
    }
}

internal fun Web.notificationHistoryPage(r: Web.Req) {
    if (!env.may(Permissions.API)) return send(r.ex, 403, page("Notifications", NOT_ALLOWED))
    val history = NotifyHistory.of(env)
    if (r.method == "POST") {
        when (r.form["action"]) {
            "read" -> r.form["id"]?.takeIf { it.isNotEmpty() }?.let { history.markRead(it) } ?: history.markAllRead()
            "clear" -> history.clear()
        }
        history.settle()
        return redirect(r.ex, "/notifications")
    }
    history.settle()
    val list = history.entries.value
    val body = StringBuilder()
    body.append(
        """<div class="card"><p class="muted">What Trackhop sent to your notification channels, newest first. Kept for ${NotifyHistory.KEEP_DAYS} days (up to ${NotifyHistory.MAX}). <a href="/settings/notifications">Channels</a></p>""",
    )
    if (list.isNotEmpty()) {
        body.append("""<div class="actions">""")
        if (list.any { !it.read }) body.append(Html.postButton("/notifications", "Mark all read", "secondary", mapOf("action" to "read")) + " ")
        body.append(Html.postButton("/notifications", "Clear", "secondary", mapOf("action" to "clear"), confirm = "Clear the notifications' history?"))
        body.append("</div>")
    }
    body.append("</div>")
    if (list.isEmpty()) {
        body.append("""<p class="muted">Nothing yet. What Trackhop sends to your channels shows up here.</p>""")
        return send(r.ex, 200, page("Notifications", body.toString()))
    }
    body.append("""<div class="card"><table><thead><tr><th>When</th><th>What</th><th>Sent to</th><th></th></tr></thead><tbody>""")
    for (e in list) {
        val sent = e.sent.joinToString(" · ") { s -> h(s.channel) + if (s.error == null) " ✓" else """ <span class="err">✗ ${h(s.error)}</span>""" }
        val link = NotifyPages.url(e.target)?.let { u ->
            val ext = !u.startsWith("/")
            """<a href="${h(u)}"${if (ext) " rel=\"noopener noreferrer\" target=\"_blank\"" else ""}>Open</a>"""
        }.orEmpty()
        val mark = if (e.read) "" else " " + Html.postButton("/notifications", "Mark read", "secondary", mapOf("action" to "read", "id" to e.id))
        val title = if (e.read) h(e.title) else "<b>${h(e.title)}</b>"
        body.append(
            """<tr${if (e.read) "" else " class=\"unread\""}><td>${h(NotifyPages.time(e.at))}</td><td>$title${if (e.text.isNotBlank()) "<br><span class=\"muted\">${h(e.text).replace("\n", "<br>")}</span>" else ""}</td><td>$sent</td><td>$link$mark</td></tr>""",
        )
    }
    body.append("</tbody></table></div>")
    send(r.ex, 200, page("Notifications", body.toString()))
}

/** /api/v1/notifications/history: GET the list, POST …/read (all, or {"id": …}), DELETE to clear. */
internal fun Web.apiNotifyHistory(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.size < 2 || rest[0] != "notifications" || rest[1] != "history") return false
    val history = NotifyHistory.of(env)
    when {
        rest.size == 2 && method == "GET" -> {
            history.settle()
            val l = history.entries.value
            json(200, buildJsonObject { put("unread", NotifyHistory.unread(l)); put("entries", JsonArray(l.map { NotifyPages.json(it) })) })
        }
        rest.size == 3 && rest[2] == "read" && method == "POST" -> {
            // Not JSON (or too large) is an error, never "all read"; an empty body is the explicit mark-all.
            val b = body() ?: return true.also { error(400, "Send JSON: {\"id\": \"<notification id>\"}, or an empty body to mark all read.") }
            val id = (b["id"] as? JsonPrimitive)?.contentOrNull
            if (id != null) history.markRead(id) else history.markAllRead()
            history.settle()
            json(200, buildJsonObject { put("unread", NotifyHistory.unread(history.entries.value)) })
        }
        rest.size == 2 && method == "DELETE" -> {
            history.clear(); history.settle()
            json(200, buildJsonObject { put("cleared", true) })
        }
        else -> error(404, "Unknown call.")
    }
    return true
}

/** `trackhop notifications [read [<id>]|clear]`: the history of what was sent to the channels. */
internal class CliNotifications(private val c: CliCommands) {
    companion object {
        val commands: List<Cli.Cmd> = listOf(
            Cli.Cmd("notifications", "Notifications", listOf("notifications", "notifications read [<id>]", "notifications clear"),
                "what was sent to your notification channels, newest first, and where each one leads",
                "read marks one (or all) as read; clear empties the history. The channels themselves: trackhop notify.",
                listOf("read", "clear")),
        )
    }

    fun run(command: String, p: List<String>, @Suppress("UNUSED_PARAMETER") o: Cli.Opts): Int? {
        if (command != "notifications") return null
        val out = c.out
        fun print(e: JsonElement) = out.println(kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), e))
        when (p.firstOrNull()) {
            null, "list", "ls" -> {
                val r = c.api.get("notifications/history").jsonObject
                if (c.asJson) { print(r); return 0 }
                val entries = (r["entries"] as? JsonArray).orEmpty().map { it.jsonObject }
                if (entries.isEmpty()) { out.println("Nothing sent to your channels yet."); return 0 }
                for (e in entries) {
                    fun s(k: String) = (e[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val read = (e["read"] as? JsonPrimitive)?.booleanOrNull == true
                    val at = (e["at"] as? JsonPrimitive)?.longOrNull ?: 0L
                    out.println("${if (read) " " else "*"} ${NotifyPages.time(at)}  ${s("title")}  [${s("id")}]")
                    s("text").lines().filter { it.isNotBlank() }.forEach { out.println("    $it") }
                    val sent = (e["sent"] as? JsonArray).orEmpty().map { it.jsonObject }.joinToString(", ") { x ->
                        val err = (x["error"] as? JsonPrimitive)?.contentOrNull
                        (x["channel"] as? JsonPrimitive)?.contentOrNull.orEmpty() + if (err == null) " (sent)" else " (failed: $err)"
                    }
                    if (sent.isNotEmpty()) out.println("    Sent to: $sent")
                    s("url").takeIf { it.isNotEmpty() }?.let { out.println("    Open: ${if (it.startsWith("/")) it else it}") }
                }
                out.println("${(r["unread"] as? JsonPrimitive)?.contentOrNull ?: "0"} unread (* marks them).")
                return 0
            }
            "read" -> {
                val id = p.getOrNull(1)
                val r = c.api.send("POST", "notifications/history/read", id?.let { buildJsonObject { put("id", it) } })
                if (c.asJson) print(r) else out.println(if (id == null) "All marked as read." else "Marked as read.")
                return 0
            }
            "clear" -> {
                val r = c.api.send("DELETE", "notifications/history")
                if (c.asJson) print(r) else out.println("Cleared.")
                return 0
            }
            else -> throw Cli.Failure(2, "usage: trackhop notifications [read [<id>]|clear]")
        }
    }
}

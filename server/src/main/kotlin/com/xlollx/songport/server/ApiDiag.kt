package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * /api/v1/diagnostics and /api/v1/report (Diagnostics.kt, Issues.kt: the report for asking for
 * help, and a GitHub issue made ready but never sent), and /api/v1/syncs/crowded and
 * /api/v1/syncs/spread (Crowds.kt). False for any other call.
 */
internal fun Web.apiDiag(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    when {
        rest == listOf("syncs", "crowded") && method == "GET" -> json(200, Crowds.json(env))
        rest == listOf("syncs", "spread") && method == "POST" -> {
            // A dry run unless asked: the new times to show the user first.
            val apply = query["apply"] == "true" || apiJson(ex)?.flag("apply") == true
            json(200, Crowds.spreadJson(env, apply))
        }
        (rest == listOf("diagnostics") && method == "GET") || (rest == listOf("report") && method == "POST") -> {
            // The log is the whole server's, other people's lines too: the admin's, as GET /log.
            if (!env.may(Permissions.ADMIN)) return true.also { error(403, "The log is the admin's.") }
            val b = if (method == "POST") apiJson(ex) ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
            val sinceText = b.text("since") ?: query["since"]
            val since = Diagnostics.duration(sinceText) ?: return true.also { error(400, "\"since\" is a time back such as 90m, 24h, 7d or 2w.") }
            val names = b.flag("names") ?: (query["names"] == "true")
            val from = System.currentTimeMillis() - since
            if (method == "GET") {
                // The signature from the error without names, as in an issue: the same for everyone.
                val bare = Diagnostics.lastError(env, from, names = false)
                json(200, buildJsonObject {
                    put("since", from)
                    put("names", names)
                    put("error", (if (names) Diagnostics.lastError(env, from, true) else bare)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("signature", bare?.let { JsonPrimitive(Diagnostics.signature(it)) } ?: JsonNull)
                    put("report", Diagnostics.report(env, productName, version, from, names))
                })
            } else {
                json(200, Issues.prepare(env, productName, version, from, names, b.text("title"), b.text("description")))
            }
        }
        else -> return false
    }
    return true
}

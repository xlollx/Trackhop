package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.server.Html.h
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The API calls of the app's features brought to Desktop and Docker in 2.2 (the weekly unplayable
 * check, To fix, saved schedules, recent transfers and the rest): the same engine as the app's
 * screens and as this server's pages. False for any other call.
 */
internal fun Web.apiParity(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    fun body(): JsonObject? = apiJson(ex)
    if (apiToFix(method, rest, query, ::body, json, error)) return true
    when {
        rest == listOf("tools", "unplayable", "weekly") && method == "GET" -> json(200, unplayableWeeklyJson())
        rest == listOf("tools", "unplayable", "weekly") && (method == "PATCH" || method == "PUT") -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"enabled\": true}.") }
            val on = b.flag("enabled") ?: return true.also { error(400, "Send JSON: {\"enabled\": true}.") }
            UnplayableWeekly.set(env, on)
            json(200, unplayableWeeklyJson())
        }
        rest == listOf("tools", "unplayable", "weekly", "run") && method == "POST" -> {
            if (Tasks.running(UnplayableWeekly.TITLE)) return true.also { error(409, "The check is already running.") }
            val t = Tasks.start(UnplayableWeekly.TITLE, "/tools/unplayable", { env.log("unplayable", it) }, env.owner, env.marks) { t ->
                """<div class="card">${h(UnplayableWeekly.run(env) { t.progress = it })}</div>"""
            }
            json(202, buildJsonObject { put("task", t.id) })
        }
        else -> return false
    }
    return true
}

private fun Web.unplayableWeeklyJson() = buildJsonObject {
    put("enabled", UnplayableWeekly.on(env))
    put("services", buildJsonArray { UnplayableWeekly.services(env).forEach { add(JsonPrimitive(it.id)) } })
    put("channels", UnplayableWeekly.channels(env).size)
    put("last", UnplayableWeekly.last(env)?.let { l ->
        buildJsonObject {
            put("at", l.at); put("unplayable", l.count); put("playlists", l.playlists); put("services", l.services); put("notRead", l.failed)
            put("summary", UnplayableWeekly.summary(l))
        }
    } ?: JsonNull)
    put("next", if (UnplayableWeekly.on(env)) JsonPrimitive((UnplayableWeekly.last(env)?.at ?: System.currentTimeMillis()) + UnplayableWeekly.EVERY_MS) else JsonNull)
}

/** Their lines in the API's description (ApiDocs), and so in openapi.json and the guide's test. */
internal val PARITY_CALLS: List<ApiDocs.Call> = listOf(
    ApiDocs.Call("GET", "/tofix", "Runs", "To fix: every song the last run of every sync left not found or matched loosely (to check), once per pair of services: key, kind (not_found or to_check), track, suggestion with its score (%), on (the service it is missing on), syncs. Grouped by sync, or by service with by=service; later lists those set aside until their sync runs again.", listOf("by")),
    ApiDocs.Call("POST", "/tofix", "Runs", "Fix songs of To fix, for every sync between the same two services, in their playlists at once: action use (the suggestion), ignore (not searched for again), later, back (a song set aside returns), pick (one song, choice: a link, \"Artist - Title\" or its id there). keys: the rows' keys, or \"all\". Answers done, syncs (playlists written), failed (written by their next run), left.", body = "{\"action\": \"use\" | \"ignore\" | \"later\" | \"back\" | \"pick\", \"keys\": [\"<key>\"] | \"all\", \"choice\"?}"),
    ApiDocs.Call("GET", "/tools/unplayable/weekly", "Tools", "The weekly look for unplayable tracks: whether it is on, the services it asks, how many notification channels take its news (those with \"Tracks to review\"), the last look (unplayable, playlists, notRead, summary) and the next."),
    ApiDocs.Call("PATCH", "/tools/unplayable/weekly", "Tools", "Turn the weekly look for unplayable tracks on or off. Nothing is ever changed by it: your channels are told, and Tools › Unplayable tracks fixes them.", body = "{\"enabled\": true}"),
    ApiDocs.Call("POST", "/tools/unplayable/weekly/run", "Tools", "Look for unplayable tracks now (a background task, as GET /tasks/{task} follows), telling the channels as the weekly look does."),
)

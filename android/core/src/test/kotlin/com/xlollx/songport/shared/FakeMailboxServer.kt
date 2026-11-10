package com.xlollx.songport.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.security.SecureRandom
import java.util.Base64

/**
 * A Trackhop server's group mailboxes in memory, as much of them as a group uses (server/…/
 * GroupMailboxes.kt): made with the API token, read and written with the group's key, items kept
 * once each and read from a cursor, [page] items at a time. Unlike the real server it checks no
 * signature: what it stores unchecked tests the members' own checks.
 */
class FakeMailboxServer(private val apiToken: String, var page: Int = 1000) : Dispatcher() {
    class Box(var key: String) {
        val items = ArrayList<Triple<Long, String, String>>()
        var seq = 0L
    }

    val boxes = java.util.Collections.synchronizedMap(HashMap<String, Box>())
    @Volatile var reads = 0
    @Volatile var writes = 0

    /** Puts [json] in [group]'s mailbox as one of [field] (cards, rosters, events), as anyone with the key could. */
    fun inject(group: String, field: String, json: String) = synchronized(boxes) {
        val b = boxes.getValue(group)
        if (b.items.none { it.second == field && it.third == json }) b.items += Triple(++b.seq, field, json)
    }

    private fun newKey() = "thg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun answer(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return answer(400, "{}")
        val s = url.pathSegments.filter { it.isNotEmpty() }
        val auth = request.getHeader("Authorization")?.removePrefix("Bearer ")
        if (s.size < 3 || s[0] != "api" || s[1] != "v1" || s[2] != "groups") return answer(404, """{"error":"Unknown call."}""")
        synchronized(boxes) {
            if (s.size == 3 && request.method == "POST") {
                if (auth != apiToken) return answer(401, """{"error":"Send the API token"}""")
                val o = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = (o["group"] as JsonPrimitive).content
                if (id in boxes) return answer(409, """{"error":"A mailbox for this group is here already."}""")
                val key = newKey()
                boxes[id] = Box(key)
                return answer(201, """{"group":"$id","key":"$key"}""")
            }
            val b = boxes[s.getOrNull(3)] ?: return answer(if (auth == apiToken) 404 else 401, """{"error":"No"}""")
            if (s.size == 4 && request.method == "DELETE" && auth == apiToken) { boxes.remove(s[3]); return answer(200, """{"deleted":true}""") }
            if (s.size == 5 && s[4] == "key" && request.method == "POST" && auth == apiToken) {
                b.key = newKey()
                return answer(200, """{"group":"${s[3]}","key":"${b.key}"}""")
            }
            if (s.size != 5 || s[4] != "items") return answer(403, """{"error":"A group's key opens that group's items only."}""")
            if (auth != b.key && auth != apiToken) return answer(401, """{"error":"This key does not open this group."}""")
            if (request.method == "GET") {
                reads++
                val since = url.queryParameter("since")?.toLongOrNull() ?: 0
                val after = b.items.filter { it.first > since }
                val taken = after.take(page)
                fun of(field: String) = taken.filter { it.second == field }.joinToString(",", "[", "]") { it.third }
                val cursor = taken.lastOrNull()?.first ?: since
                return answer(200, """{"cursor":$cursor,"more":${after.size > taken.size},"cards":${of("cards")},"rosters":${of("rosters")},"events":${of("events")}}""")
            }
            if (request.method == "POST") {
                writes++
                val o = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                var added = 0
                for (field in listOf("cards", "rosters", "events")) {
                    for (e in (o[field] as? JsonArray).orEmpty()) {
                        val text = (e as JsonObject).toString()
                        if (b.items.none { it.second == field && it.third == text }) { b.items += Triple(++b.seq, field, text); added++ }
                    }
                }
                return answer(200, """{"added":$added,"cursor":${b.seq}}""")
            }
        }
        return answer(405, """{"error":"GET or POST."}""")
    }
}

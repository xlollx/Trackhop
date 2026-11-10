package com.xlollx.songport.shared

import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * An ntfy server in memory, as much of it as a group uses: publishing a message to a topic (4 KB at
 * most) and polling a topic since a time or from the start, with messages kept [retentionS] seconds
 * on [now]'s clock (ntfy.sh: 12 hours).
 */
class FakeNtfy(private val now: () -> Long, private val retentionS: Long = 12 * 3600) : Dispatcher() {
    data class Msg(val id: String, val time: Long, val topic: String, val text: String)

    val messages: MutableList<Msg> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile var published = 0
    private var next = 0

    /** Puts [text] on [topic] as anyone could (garbage, a replay). */
    fun inject(topic: String, text: String) = synchronized(messages) { messages += Msg("x${++next}", now() / 1000, topic, text) }

    fun topics(): Set<String> = synchronized(messages) { messages.mapTo(HashSet()) { it.topic } }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val t = now() / 1000
        synchronized(messages) {
            messages.removeAll { it.time <= t - retentionS }
            if (request.method == "POST" && segments.size == 1) {
                val body = request.body.readUtf8()
                if (body.toByteArray().size > 4096) return MockResponse().setResponseCode(413)
                val m = Msg("m${++next}", t, segments[0], body)
                messages += m
                published++
                return MockResponse().setBody("""{"id":"${m.id}","time":$t,"expires":${t + retentionS},"event":"message","topic":"${m.topic}","message":${JsonPrimitive(body)}}""")
            }
            if (request.method == "GET" && segments.size == 2 && segments[1] == "json" && url.queryParameter("poll") == "1") {
                val since = url.queryParameter("since")
                val from = if (since == null || since == "all") 0 else since.toLongOrNull() ?: return MockResponse().setResponseCode(400)
                val lines = messages.filter { it.topic == segments[0] && it.time >= from }.joinToString("") {
                    """{"id":"${it.id}","time":${it.time},"event":"message","topic":"${it.topic}","message":${JsonPrimitive(it.text)}}""" + "\n"
                }
                return MockResponse().setBody(lines)
            }
        }
        return MockResponse().setResponseCode(404)
    }
}

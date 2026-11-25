package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.QuotaMeter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Test the connection" (the app's ConnectionTestDialog): three calls to a connected service, one
 * after the other: who is signed in, the playlist list, one search. Each says whether it worked and,
 * if not, what the service answered: the first thing to look at when a sync stops. And YouTube's
 * quota (the app's QuotaRing, [QuotaMeter]): the day's units and searches this Trackhop used.
 */
internal object ConnectionTest {
    data class Step(val name: String, val ok: Boolean, val detail: String)

    private val SAMPLE = Track(id = "", title = "Bohemian Rhapsody", artists = listOf("Queen"), album = "A Night at the Opera")

    private fun words(e: Throwable) = e.message ?: e.javaClass.simpleName

    suspend fun run(env: Env, p: MusicProvider): List<Step> = listOf(
        runCatching { p.accountName(env) }.fold({ Step("Signed in", true, it?.let { n -> "as $n" } ?: "yes") }, { Step("Signed in", false, words(it)) }),
        runCatching { p.playlists(env) }.fold({ Step("Playlists read", true, "${it.size} playlists") }, { Step("Playlists read", false, words(it)) }),
        runCatching { p.search(env, SAMPLE) }.fold({ r -> Step("Search", r.isNotEmpty(), r.firstOrNull()?.toString() ?: "it answered, but found nothing for Queen – Bohemian Rhapsody") }, { Step("Search", false, words(it)) }),
    )

    /** YouTube's (official API) quota used today: units of the 10,000 and searches of the 100. */
    fun quota(env: ServerEnv, p: MusicProvider): Pair<Long, Long>? =
        if (p.serviceId != YouTubeProvider.SERVICE) null else QuotaMeter.used(env.store.data, YouTubeProvider.SERVICE) to QuotaMeter.searchesUsed(env.store.data, YouTubeProvider.SERVICE)
}

/** The quota's card on YouTube's page; nothing for the other services. */
internal fun Web.quotaCard(p: MusicProvider): String {
    val (units, searches) = ConnectionTest.quota(env, p) ?: return ""
    val pct = (100 * units / QuotaMeter.YOUTUBE_DAILY).coerceIn(0, 100)
    val warn = if (pct >= 80 || searches >= QuotaMeter.YOUTUBE_SEARCH_DAILY * 8 / 10) """<p class="err">⚠ Almost used up: syncs to YouTube may stop until it starts again.</p>""" else ""
    return """<div class="card"><h3>Today's quota</h3><progress max="${QuotaMeter.YOUTUBE_DAILY}" value="$units" aria-label="Quota used"></progress>
<p class="muted">${"%,d".format(units)} of ${"%,d".format(QuotaMeter.YOUTUBE_DAILY)} units used · ${searches} of ${QuotaMeter.YOUTUBE_SEARCH_DAILY} searches. A limit of Google's, counted from what this Trackhop did (with the built-in key others share it, so it can run out sooner); it starts again at midnight Pacific time.</p>$warn</div>"""
}

internal fun Web.connectionTestPage(r: Web.Req, p: MusicProvider) {
    val back = "/connectors/${q(p.id)}"
    val steps = runBlocking { ConnectionTest.run(env, p) }
    val rows = steps.joinToString("") { s ->
        """<p><span class="${if (s.ok) "ok" else "err"}">${if (s.ok) "✓" else "✗"} ${h(s.name)}</span> <span class="muted">· ${h(s.detail)}</span></p>"""
    }
    send(r.ex, 200, page("Test ${p.label(env)}", """<div class="card">$rows<p class="muted">Three calls, one after the other: who is signed in, the playlist list, one search. When a sync stops, look here first.</p></div><p>${Html.postButton("$back/test", "Test again", "secondary")} <a href="$back">Back</a></p>"""))
}

/** POST /services/{service}/test */
internal fun Web.apiConnectionTest(method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!(rest.size == 3 && rest[0] == "services" && rest[2] == "test" && method == "POST")) return false
    val p = com.xlollx.songport.providers.Providers.byId(rest[1]) ?: return true.also { error(404, "No service with that id: see GET /api/v1/services.") }
    if (p.requiresAuth && !p.isConnected(env)) return true.also { error(409, "${p.label(env)} is not connected.") }
    val steps = runBlocking { ConnectionTest.run(env, p) }
    json(200, buildJsonObject {
        put("ok", steps.all { it.ok })
        put("steps", JsonArray(steps.map { s -> buildJsonObject { put("step", s.name); put("ok", s.ok); put("detail", s.detail) } }))
    })
    return true
}

/** YouTube's quota in GET /services/{service}. */
internal fun Web.quotaJson(p: MusicProvider): JsonObject? = ConnectionTest.quota(env, p)?.let { (units, searches) ->
    buildJsonObject { put("units", units); put("unitsDaily", QuotaMeter.YOUTUBE_DAILY); put("searches", searches); put("searchesDaily", QuotaMeter.YOUTUBE_SEARCH_DAILY) }
}

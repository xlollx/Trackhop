package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LibraryOverlap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Two subscriptions (the app's TwoSubscriptions, [LibraryOverlap]): how much of the music in your
 * libraries on two services is on both (liked songs and your own playlists, up to sixty of them each),
 * with what they cost a month when you say it, and the verdict. The songs only on one side can be
 * saved as a File playlist before you leave the other.
 */
internal object Overlap {
    private const val MAX_PLAYLISTS = 60
    val last = ConcurrentHashMap<String, Pair<String, LibraryOverlap.Result>>()

    /** The connected music services (not the scrobblers, not files). */
    fun services(env: Env) = Providers.connectors().filter { it.requiresAuth && it.isConnected(env) && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" && it !is LocalFilesProvider }

    suspend fun read(env: Env, p: MusicProvider, progress: (String) -> Unit): List<Track> {
        val liked = p.libraryEntries(env, asTarget = false).filter { it.id == MusicProvider.LIKED_ID }
        val lists = (liked + p.playlists(env).filter { it.ownedByMe }).take(MAX_PLAYLISTS)
        val out = ArrayList<Track>()
        lists.forEachIndexed { i, pl ->
            progress("${p.label(env)}: playlist ${i + 1} of ${lists.size}")
            try { out += p.tracks(env, pl.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        return out
    }

    suspend fun compare(env: ServerEnv, a: MusicProvider, b: MusicProvider, progress: (String) -> Unit = {}): LibraryOverlap.Result {
        val ta = read(env, a, progress)
        val tb = read(env, b, progress)
        return LibraryOverlap.compute(ta, tb) { t -> env.store.cachedMatch(a.id, t.id, b.id) }
    }

    fun verdict(r: LibraryOverlap.Result, a: String, b: String): String = when (LibraryOverlap.verdict(r)) {
        LibraryOverlap.Verdict.SAME -> "Almost the same music on both: one subscription is enough."
        LibraryOverlap.Verdict.A_IN_B -> "Nearly all of $a is on $b too: $b alone would do."
        LibraryOverlap.Verdict.B_IN_A -> "Nearly all of $b is on $a too: $a alone would do."
        LibraryOverlap.Verdict.PARTIAL -> "A good part is on both, and each has music of its own."
        LibraryOverlap.Verdict.APART -> "Two different libraries: little is on both."
        null -> "One of them has no songs: nothing to compare."
    }
}

private fun money(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

internal fun Web.overlapPage(r: Web.Req) {
    val title = "Two subscriptions"
    val services = Overlap.services(env)
    if (r.method == "POST") {
        val key = env.owner
        if (r.form["action"] == "save") {
            val (names, res) = Overlap.last[key] ?: return redirect(r.ex, "/tools/overlap?err=${q("That comparison is no longer kept: compare again.")}")
            val a = r.form["side"] == "a"
            val (na, nb) = names.split('\u0000').let { it[0] to it.getOrElse(1) { "" } }
            val tracks = if (a) res.onlyA else res.onlyB
            val id = LocalFilesProvider.importTracks(env, "Only on ${if (a) na else nb}", tracks)
            return redirect(r.ex, "/connectors/csv?ok=${q("Saved as the File playlist $id (${tracks.size} songs).")}")
        }
        val a = services.firstOrNull { it.id == r.form["a"] }
        val b = services.firstOrNull { it.id == r.form["b"] }
        if (a == null || b == null || a.id == b.id) return redirect(r.ex, "/tools/overlap?err=${q("Pick two different services.")}")
        val pa = r.form["priceA"]?.let { LibraryOverlap.parsePrice(it) }?.let { LibraryOverlap.monthly(it, r.form["yearlyA"] == "on") }
        val pb = r.form["priceB"]?.let { LibraryOverlap.parsePrice(it) }?.let { LibraryOverlap.monthly(it, r.form["yearlyB"] == "on") }
        val task = Tasks.startOnce(title, "/tools/overlap", { env.log("overlap", it) }, env.owner, env.marks) { t ->
            val res = Overlap.compare(env, a, b) { t.progress = "Reading $it" }
            Overlap.last[key] = (a.label(env) + "\u0000" + b.label(env)) to res
            overlapHtml(a.label(env), b.label(env), res, pa, pb)
        } ?: return redirect(r.ex, "/tools/overlap?err=${q("A comparison is already running.")}")
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">How much of the music in your libraries on two services is on both: your liked songs and your own playlists. Say what each costs, and see whether one subscription would do. Nothing changes on the services.</p>""")
    if (services.size < 2) {
        body.append("""<div class="card">Connect two music services first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    fun opts(sel: Int) = services.mapIndexed { i, p -> """<option value="${h(p.id)}"${if (i == sel) " selected" else ""}>${h(p.label(env))}</option>""" }.joinToString("")
    body.append("""<form method="post" action="/tools/overlap" class="card">
<label>One</label><select name="a">${opts(0)}</select><label>Price (optional)</label><input type="text" name="priceA" placeholder="10.99" style="max-width:10em"> <label class="check inline"><input type="checkbox" name="yearlyA"> a year</label>
<label>The other</label><select name="b">${opts(1)}</select><label>Price (optional)</label><input type="text" name="priceB" placeholder="10.99" style="max-width:10em"> <label class="check inline"><input type="checkbox" name="yearlyB"> a year</label>
<p class="muted">Up to sixty playlists of each are read: it can take a few minutes.</p><p><button>Compare</button></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

private fun overlapHtml(a: String, b: String, r: LibraryOverlap.Result, pa: Double?, pb: Double?): String = buildString {
    val (sa, both, sb) = r.shares
    append("""<div class="card"><div class="stats"><p class="stat"><b>${r.sizeA}</b><span class="muted">songs on ${h(a)}</span></p><p class="stat"><b>${r.sizeB}</b><span class="muted">songs on ${h(b)}</span></p><p class="stat"><b>${r.percent}%</b><span class="muted">on both (${r.common.size})</span></p></div>""")
    append("""<p>${h(Overlap.verdict(r, a, b))}</p>""")
    append("""<p class="muted">Only on ${h(a)}: $sa% · on both: $both% · only on ${h(b)}: $sb%. ${r.coveredA}% of ${h(a)} is on ${h(b)}; ${r.coveredB}% of ${h(b)} is on ${h(a)}.</p>""")
    if (pa != null || pb != null) append("""<p class="muted">A month: ${listOfNotNull(pa?.let { "${h(a)} ${money(it)}" }, pb?.let { "${h(b)} ${money(it)}" }).joinToString(" + ")}${if (pa != null && pb != null) " = ${money(pa + pb)}" else ""}.</p>""")
    append("<div class=\"actions\">")
    if (r.onlyA.isNotEmpty()) append(Html.postButton("/tools/overlap", "Save the ${r.onlyA.size} only on $a as a File playlist", "secondary", mapOf("action" to "save", "side" to "a")))
    if (r.onlyB.isNotEmpty()) append(" " + Html.postButton("/tools/overlap", "Save the ${r.onlyB.size} only on $b as a File playlist", "secondary", mapOf("action" to "save", "side" to "b")))
    append("</div></div>")
}

/** POST /tools/overlap */
internal fun Web.apiOverlap(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("tools", "overlap") || method != "POST") return false
    val b = body() ?: return true.also { error(400, "Send JSON: {\"a\": \"<service>\", \"b\": \"<service>\"}.") }
    val services = Overlap.services(env)
    val pa = services.firstOrNull { it.id == b.text("a") }
    val pb = services.firstOrNull { it.id == b.text("b") }
    if (pa == null || pb == null || pa.id == pb.id) return true.also { error(400, "\"a\" and \"b\" are two different connected music services.") }
    val res = runCatching { runBlocking { Overlap.compare(env, pa, pb) } }.getOrElse { return true.also { _ -> error(502, it.message ?: "Error") } }
    Overlap.last[env.owner] = (pa.label(env) + "\u0000" + pb.label(env)) to res
    json(200, buildJsonObject {
        put("a", pa.id); put("b", pb.id); put("songsA", res.sizeA); put("songsB", res.sizeB); put("both", res.common.size); put("percent", res.percent)
        put("coveredA", res.coveredA); put("coveredB", res.coveredB)
        put("verdict", LibraryOverlap.verdict(res)?.name?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("summary", Overlap.verdict(res, pa.label(env), pb.label(env)))
        put("onlyA", JsonArray(res.onlyA.map { trackRef(it) })); put("onlyB", JsonArray(res.onlyB.map { trackRef(it) }))
    })
    return true
}

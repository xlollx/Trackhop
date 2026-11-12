package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.json as coreJson
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.CoverArt
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.PosterPalette
import com.xlollx.songport.sync.Wrapped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Calendar
import javax.imageio.ImageIO

/**
 * "Your Wrapped, whenever you want", from what was really listened to (Features.v2), on the
 * server as in the app: a listening service connected here (Last.fm, ListenBrainz, a personal
 * server's play counts, Spotify, Spotify (web), Deezer, TIDAL, YouTube Music's recent plays), a
 * period, and the top artists and songs, the musical age and the decades, made by :core's
 * [Wrapped.fromListening]. Kept in the user's own history (the app's, [Wrapped.remember]).
 *
 * The page (Tools › Wrapped), the API (GET and POST /api/v1/wrapped, the card as a PNG), the
 * command line (`trackhop wrapped`) and MCP (get_wrapped, make_wrapped). The card is drawn here
 * with Java's own 2D drawing (the app's renderer is Android's Canvas): a plainer one, the same
 * figures; the page also shows it as HTML, with the covers.
 */
internal object ServerWrapped {
    const val TITLE = "Wrapped"
    const val NO_SOURCE = "Connect a listening service first (Last.fm, ListenBrainz, your Navidrome, Jellyfin, Emby, Plex or Lyrion server, Spotify, Deezer, TIDAL or YouTube Music) in Services: the Wrapped is made from what you played there."

    /** Said to the person: the source, the period or the service refused. */
    class Refused(message: String, val code: Int = 400) : Exception(message)

    /** Tests: stands in for the connected listening services and what they say. */
    internal class Fake(val sources: List<Pair<Listening.Source, MusicProvider>>, val report: (Listening.Source, Listening.Period) -> Listening.Report)
    @Volatile internal var fake: Fake? = null

    /** The year and cover of a song the service did not date (DeezerCatalog, as the app); tests replace it. */
    @Volatile internal var details: suspend (Track) -> Listening.Details? = { DeezerCatalog.details(it) }

    fun sources(env: Env): List<Pair<Listening.Source, MusicProvider>> = fake?.sources ?: ServerListening.choices(env)

    private suspend fun read(env: Env, s: Listening.Source, p: MusicProvider, period: Listening.Period): Listening.Report =
        fake?.report?.invoke(s, period) ?: ServerListening.read(env, s, p, period)

    fun history(env: ServerEnv): List<Wrapped.Saved> = env.store.data.wrapped

    /** A Wrapped kept: by its id, or "latest". */
    fun find(env: ServerEnv, id: String): Wrapped.Saved? =
        history(env).let { h -> if (id == "latest") h.firstOrNull() else h.firstOrNull { it.id == id } }

    fun period(asked: String?): Listening.Period? = asked?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { a ->
        Listening.Period.of(a.replace('-', '_')) ?: throw Refused("\"period\" is one of ${Listening.Period.entries.joinToString(", ") { it.key }}.")
    }

    /**
     * A Wrapped of what was played on [asked] (a source's key, its service or connector id; the
     * best one connected when null) over [period] (the nearest the service can tell), kept in the
     * history. Throws [Refused] with words for the person.
     */
    suspend fun make(env: ServerEnv, asked: String?, period: Listening.Period?, progress: (String) -> Unit = {}): Wrapped.Saved {
        val all = sources(env)
        if (all.isEmpty()) throw Refused(NO_SOURCE, 409)
        val (s, p) = if (asked.isNullOrBlank()) all.first() else all.firstOrNull { (s, p) -> sourceMatches(s, p, asked) }
            ?: throw Refused("\"$asked\" is not a listening service connected here: ${all.joinToString(", ") { ServerListening.key(it.first) }}.")
        val per = Listening.period(s, period)
        progress("Reading ${ServerListening.brand(p)}")
        val saved = try {
            if (s.artistsOnly) fromArtists(env, s, p, per, all, progress) else listened(env, s, p, per, read(env, s, p, per), progress)
        } catch (e: CancellationException) { throw e } catch (e: Refused) { throw e } catch (_: Listening.MissingScope) {
            throw Refused("${ServerListening.brand(p)}: the sign-in predates reading your top songs. Sign in again in Services, then try again.", 409)
        } catch (e: Exception) {
            throw Refused("${p.label(env)}: ${e.message ?: e.javaClass.simpleName}", 502)
        }
        env.store.update { d -> d.copy(wrapped = Wrapped.remember(d.wrapped, saved)) }
        env.log("wrapped", "made from ${p.displayName} (${per.key}): ${saved.topTracks.size} songs, ${saved.topArtists.size} artists")
        return saved
    }

    private fun sourceMatches(s: Listening.Source, p: MusicProvider, asked: String): Boolean {
        val k = asked.trim().lowercase()
        return ServerListening.key(s) == k || s.serviceId == k || p.id.lowercase() == k
    }

    private fun connector(p: MusicProvider) = Wrapped.services(listOf(Wrapped.Connector(p.id, p.serviceId, p.displayName, p.familyName, p.brandColor))).first()

    private suspend fun listened(
        env: Env, s: Listening.Source, p: MusicProvider, period: Listening.Period, report: Listening.Report, progress: (String) -> Unit,
        artists: List<Listening.ArtistPlays> = emptyList(), artistsFrom: MusicProvider? = null,
    ): Wrapped.Saved {
        if (report.tracks.isEmpty() && artists.isEmpty()) throw Refused("${ServerListening.brand(p)} has no plays to tell for this period.", 404)
        val filled = Listening.fill(report, onProgress = { done, total -> if (total > 0) progress("Looking up years and covers: ${minOf(done + 1, total)} of $total") }) { details(it) }
        val w = Wrapped.fromListening(filled, connector(p), period, Calendar.getInstance().get(Calendar.YEAR), System.currentTimeMillis())
        return if (artistsFrom != null && artists.isNotEmpty()) Wrapped.withArtists(w, artists, connector(artistsFrom)) else w
    }

    /** TIDAL tells artists only: the songs come from the next listening service that has some, else the Wrapped has the artists alone. */
    private suspend fun fromArtists(
        env: Env, s: Listening.Source, p: MusicProvider, period: Listening.Period, all: List<Pair<Listening.Source, MusicProvider>>, progress: (String) -> Unit,
    ): Wrapped.Saved {
        val artists = try { read(env, s, p, period).artists } catch (e: CancellationException) { throw e } catch (_: Listening.Unavailable) { emptyList() }
        val by = p.takeIf { artists.isNotEmpty() }
        for ((o, op) in all) {
            if (o.artistsOnly) continue
            val wanted = Listening.period(o, period)
            val r = try { read(env, o, op, wanted) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                env.log("wrapped", "${op.displayName}: songs not read: ${e.message ?: e.javaClass.simpleName}"); null
            }
            if (r != null && r.tracks.isNotEmpty()) return listened(env, o, op, if (by != null) period else wanted, r, progress, artists, by)
        }
        if (artists.isEmpty()) throw Refused("${ServerListening.brand(p)} has no plays to tell for this period.", 404)
        return listened(env, s, p, period, Listening.Report(emptyList(), artists), progress)
    }

    // ------------------------------------------------------------------ words

    fun eyebrow(w: Wrapped.Saved): String = when (Listening.Period.of(w.period)) {
        Listening.Period.MONTH -> "My last month"
        Listening.Period.HALF_YEAR -> "My last six months"
        Listening.Period.YEAR -> "My last twelve months"
        Listening.Period.RECENT -> "My latest plays"
        Listening.Period.ALL -> "My top songs"
        null -> "My ${w.year} so far"
    }

    fun sourceLine(w: Wrapped.Saved): String =
        w.services.firstOrNull()?.takeIf { w.listening }?.let { "From your listening on ${it.name}" } ?: "From your playlists"

    fun periodLabel(p: Listening.Period): String = when (p) {
        Listening.Period.MONTH -> "Last month"; Listening.Period.HALF_YEAR -> "Six months"; Listening.Period.YEAR -> "Twelve months"
        Listening.Period.RECENT -> "Latest plays"; Listening.Period.ALL -> "All time"
    }

    fun headline(w: Wrapped.Saved): String = w.age?.let { "Musical age $it" } ?: w.topArtist ?: eyebrow(w)

    fun json(w: Wrapped.Saved): JsonObject = buildJsonObject {
        coreJson.encodeToJsonElement(Wrapped.Saved.serializer(), w).jsonObject.forEach { (k, v) -> put(k, v) }
        put("eyebrow", eyebrow(w))
        put("from", sourceLine(w))
        put("card", "/api/v1/wrapped/${w.id}/card")
    }

    fun sourcesJson(list: List<Pair<Listening.Source, MusicProvider>>): JsonArray = JsonArray(list.map { (s, p) ->
        buildJsonObject {
            put("source", ServerListening.key(s)); put("service", ServerListening.brand(p)); put("connector", p.id)
            put("periods", JsonArray(s.periods.map { JsonPrimitive(it.key) })); put("countsPlays", s.counts)
        }
    })

    // ------------------------------------------------------------------ the card

    /** The card as a PNG, drawn with Java's 2D drawing; null where it cannot be drawn (a system without fonts). */
    fun png(w: Wrapped.Saved): ByteArray? = try {
        draw(w)
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        null
    }

    private const val W = 1080
    private const val H = 1350
    private const val M = 84f

    private fun color(c: Int, alpha: Int = 255) = Color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, alpha)

    private fun draw(w: Wrapped.Saved): ByteArray {
        val tones = PosterPalette.tones(IntArray(0), w.topArtist ?: w.id, PosterPalette.Layout.CLASSIC)
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, color(tones.ground), 0f, H.toFloat(), color(tones.groundEnd))
            g.fillRect(0, 0, W, H)
            val ink = color(tones.ink)
            val soft = color(tones.ink, 170)
            val accent = color(tones.accent)
            val width = W - 2 * M
            var y = M + 40f
            y = text(g, eyebrow(w).uppercase(), M, y, width, Font(Font.SANS_SERIF, Font.BOLD, 36), accent) + 30f
            val headFont = Font(Font.SANS_SERIF, Font.BOLD, 92)
            g.font = headFont
            CoverArt.wrap(headline(w), width) { g.fontMetrics.stringWidth(it).toFloat() }.take(2).forEach { line ->
                y = text(g, line, M, y, width, headFont, ink) + 8f
            }
            val sub = w.bornIn?.let { "You listen like someone born in $it" } ?: w.topArtist?.takeIf { w.age != null }?.let { "Top artist: $it" }
            if (sub != null) y = text(g, sub, M, y + 6f, width, Font(Font.SANS_SERIF, Font.PLAIN, 38), soft) + 24f
            g.color = color(tones.ink, 60); g.stroke = BasicStroke(2f); g.drawLine(M.toInt(), y.toInt(), (W - M).toInt(), y.toInt())
            y += 56f
            if (w.topArtists.isNotEmpty()) {
                y = text(g, "TOP ARTISTS", M, y, width, Font(Font.SANS_SERIF, Font.BOLD, 30), accent) + 16f
                w.topArtists.take(5).forEachIndexed { i, a ->
                    y = row(g, "${i + 1}", a.name, a.plays.takeIf { it > 0 }?.let { plays(it) }, M, y, width, 44, ink, soft) + 10f
                }
                y += 30f
            }
            if (w.topTracks.isNotEmpty()) {
                y = text(g, "TOP SONGS", M, y, width, Font(Font.SANS_SERIF, Font.BOLD, 30), accent) + 16f
                w.topTracks.take(5).forEachIndexed { i, s ->
                    if (y > H - 220) return@forEachIndexed
                    y = row(g, "${i + 1}", s.title, s.plays.takeIf { it > 0 }?.let { plays(it) }, M, y, width, 40, ink, soft)
                    if (s.artist.isNotBlank()) y = text(g, s.artist, M + 64f, y + 2f, width - 64f, Font(Font.SANS_SERIF, Font.PLAIN, 28), soft)
                    y += 14f
                }
            }
            val facts = listOfNotNull(
                w.plays.takeIf { it > 0 }?.let { plays(it) },
                w.artistCount.takeIf { it > 0 }?.let { "$it artists" },
                w.discovery?.let { "Discovery: $it" },
            ).joinToString("  ·  ")
            if (facts.isNotEmpty()) text(g, facts, M, H - M - 70f, width, Font(Font.SANS_SERIF, Font.PLAIN, 30), soft)
            text(g, sourceLine(w), M, H - M, width - 220f, Font(Font.SANS_SERIF, Font.PLAIN, 30), soft)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 32)
            val brand = "Trackhop"
            g.color = accent
            g.drawString(brand, W - M - g.fontMetrics.stringWidth(brand), H - M)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(img, "png", out)) throw IllegalStateException("no PNG writer")
        return out.toByteArray()
    }

    private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

    /** One line of [s] at [y] (its baseline), cut at [width]; the next line's top. */
    private fun text(g: Graphics2D, s: String, x: Float, y: Float, width: Float, font: Font, c: Color): Float {
        g.font = font; g.color = c
        val fm = g.fontMetrics
        val line = CoverArt.cutAtWord(s, width, force = true) { fm.stringWidth(it).toFloat() }
        g.drawString(line, x, y + fm.ascent)
        return y + fm.height
    }

    /** "1  Name ……… 120 plays". */
    private fun row(g: Graphics2D, n: String, name: String, right: String?, x: Float, y: Float, width: Float, size: Int, ink: Color, soft: Color): Float {
        val small = Font(Font.SANS_SERIF, Font.PLAIN, (size * 0.7).toInt())
        g.font = small
        val rightWidth = right?.let { g.fontMetrics.stringWidth(it) + 24 } ?: 0
        text(g, n, x, y + size * 0.15f, 60f, Font(Font.SANS_SERIF, Font.BOLD, (size * 0.8).toInt()), soft)
        if (right != null) {
            g.font = small; g.color = soft
            g.drawString(right, x + width - g.fontMetrics.stringWidth(right), y + size * 0.15f + g.fontMetrics.ascent)
        }
        return text(g, name, x + 64f, y, width - 64f - rightWidth, Font(Font.SANS_SERIF, Font.BOLD, size), ink)
    }

    /** The same card as HTML, with the covers kept with the Wrapped (loaded by the browser from the services' own addresses). */
    fun html(w: Wrapped.Saved, png: String?): String {
        val t = PosterPalette.tones(IntArray(0), w.topArtist ?: w.id, PosterPalette.Layout.CLASSIC)
        fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
        val ink = hex(t.ink); val accent = hex(t.accent)
        return buildString {
            append("""<div class="card" style="max-width:540px;background:linear-gradient(${hex(t.ground)},${hex(t.groundEnd)});color:$ink;padding:28px;border:0">""")
            append("""<div style="color:$accent;font-weight:700;letter-spacing:.08em;text-transform:uppercase;font-size:.85em">${h(eyebrow(w))}</div>""")
            append("""<h2 style="color:$ink;margin:.3em 0 .2em;font-size:2em">${h(headline(w))}</h2>""")
            w.bornIn?.let { append("""<p style="opacity:.75;margin:0 0 1em">You listen like someone born in $it</p>""") }
            if (w.covers.isNotEmpty()) {
                append("""<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:6px;margin:12px 0">""")
                w.covers.take(Wrapped.MAX_COVERS).forEach { append("""<img src="${h(it)}" alt="" loading="lazy" referrerpolicy="no-referrer" style="width:100%;aspect-ratio:1;object-fit:cover;border-radius:6px">""") }
                append("</div>")
            }
            if (w.topArtists.isNotEmpty()) {
                append("""<div style="color:$accent;font-weight:700;margin-top:1em">TOP ARTISTS</div><ol style="margin:.4em 0 0;padding-left:1.4em">""")
                w.topArtists.forEach { a -> append("<li><b>${h(a.name)}</b>${if (a.plays > 0) """ <span style="opacity:.7">${plays(a.plays)}</span>""" else ""}</li>") }
                append("</ol>")
            }
            if (w.topTracks.isNotEmpty()) {
                append("""<div style="color:$accent;font-weight:700;margin-top:1em">TOP SONGS</div><ol style="margin:.4em 0 0;padding-left:1.4em">""")
                w.topTracks.forEach { s -> append("<li><b>${h(s.title)}</b> <span style=\"opacity:.75\">${h(s.artist)}${if (s.plays > 0) " · ${plays(s.plays)}" else ""}</span></li>") }
                append("</ol>")
            }
            if (w.decades.isNotEmpty()) append("""<p style="opacity:.8;margin-top:1em">${w.decades.joinToString(" · ") { "${it.decade}s ${it.percent}%" }}</p>""")
            val facts = listOfNotNull(w.plays.takeIf { it > 0 }?.let { plays(it) }, w.artistCount.takeIf { it > 0 }?.let { "$it artists" }, w.discovery?.let { "Discovery: ${h(it)}" })
            if (facts.isNotEmpty()) append("""<p style="opacity:.8">${facts.joinToString(" · ")}</p>""")
            append("""<p style="opacity:.7;margin-bottom:0">${h(sourceLine(w))} · Trackhop</p></div>""")
            if (png != null) append("""<p><a class="button" href="${h(png)}" download="wrapped-${h(w.id)}.png">Download the card (PNG)</a></p>""")
        }
    }
}

// ------------------------------------------------------------------ the page

/** Tools › Wrapped: the source and the period, the Wrapped made as a task, the history, one Wrapped and its card. */
internal fun Web.wrappedPage(r: Web.Req, rest: List<String>) {
    val title = "Wrapped"
    rest.firstOrNull()?.let { id ->
        val w = ServerWrapped.find(env, id) ?: return send(r.ex, 404, page(title, """<div class="card">No such Wrapped. <a href="/tools/wrapped">Wrapped</a></div>"""))
        if (rest.getOrNull(1) == "card.png") {
            val png = ServerWrapped.png(w) ?: return send(r.ex, 503, page(title, """<div class="card err">The card cannot be drawn on this system (no fonts): the page shows it instead.</div>"""))
            return sendBytes(r.ex, 200, png, "image/png")
        }
        return send(r.ex, 200, page(title, notice(r) + ServerWrapped.html(w, "/tools/wrapped/${q(w.id)}/card.png") + """<p><a href="/tools/wrapped">Back</a></p>"""))
    }
    if (r.method == "POST") {
        val period = runCatching { ServerWrapped.period(r.form["period"]) }.getOrNull()
        val source = r.form["source"]?.takeIf { it.isNotBlank() }
        if (Tasks.all(env.owner).any { it.title == ServerWrapped.TITLE && !it.done }) return redirect(r.ex, "/tools/wrapped?err=${q("A Wrapped is already being made.")}")
        val task = Tasks.start(ServerWrapped.TITLE, "/tools/wrapped", { env.log("wrapped", it) }, env.owner) { t ->
            val w = try { ServerWrapped.make(env, source, period) { t.progress = it } } catch (e: ServerWrapped.Refused) { throw IllegalStateException(e.message) }
            ServerWrapped.html(w, "/tools/wrapped/${q(w.id)}/card.png")
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val body = StringBuilder(notice(r))
    val sources = ServerWrapped.sources(env)
    body.append("""<p class="muted">Your top artists and songs, your musical age and your decades, from what you really played on a listening service: any time, not only in December.</p>""")
    if (sources.isEmpty()) body.append("""<div class="card">${h(ServerWrapped.NO_SOURCE)} <a href="/connectors">Services</a></div>""")
    else {
        val chosen = env.store.data.settings.wrappedSource
        val opts = sources.joinToString("") { (s, p) ->
            val k = ServerListening.key(s)
            """<option value="$k"${if (k == chosen || s.serviceId == chosen) " selected" else ""}>${h(ServerListening.brand(p))}${if (s.server) " (play counts, all time)" else ""}</option>"""
        }
        val periods = listOf(Listening.Period.MONTH, Listening.Period.HALF_YEAR, Listening.Period.YEAR)
            .joinToString("") { p -> """<option value="${p.key}"${if (p == Listening.Period.HALF_YEAR) " selected" else ""}>${h(ServerWrapped.periodLabel(p))}</option>""" }
        body.append("""<form method="post" action="/tools/wrapped" class="card"><label>From</label><select name="source">$opts</select>
<label>Period</label><select name="period">$periods</select>
<p class="muted">Each service tells only some periods: a personal server counts all time, Deezer does not say, YouTube Music knows the latest plays; the nearest one is used.</p>
<p><button>Make my Wrapped</button></p></form>""")
    }
    val history = ServerWrapped.history(env)
    if (history.isNotEmpty()) {
        body.append("""<h2>Made before</h2><div class="card"><ul>""")
        history.forEach { w ->
            body.append("""<li><a href="/tools/wrapped/${q(w.id)}">${h(ServerWrapped.eyebrow(w))}</a> <span class="muted">${h(Html.date(w.madeAt))} · ${h(w.services.firstOrNull()?.name ?: "playlists")}${w.topArtist?.let { " · " + h(it) } ?: ""}</span></li>""")
        }
        body.append("</ul></div>")
    }
    send(r.ex, 200, page(title, body.toString()))
}

// ------------------------------------------------------------------ the API

/** GET /api/v1/wrapped, POST /api/v1/wrapped, GET /api/v1/wrapped/{wrapped}, GET /api/v1/wrapped/{wrapped}/card. */
internal fun Web.apiWrapped(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit, bytes: (ByteArray, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "wrapped") return false
    when {
        rest.size == 1 && method == "GET" -> json(200, buildJsonObject {
            put("sources", ServerWrapped.sourcesJson(ServerWrapped.sources(env)))
            put("wrapped", JsonArray(ServerWrapped.history(env).map { ServerWrapped.json(it) }))
        })
        rest.size == 1 && method == "POST" -> {
            val b = body() ?: JsonObject(emptyMap())
            val w = try {
                val period = ServerWrapped.period(b.text("period"))
                runBlocking { ServerWrapped.make(env, b.text("source"), period) }
            } catch (e: ServerWrapped.Refused) {
                return true.also { error(e.code, e.message ?: "Refused.") }
            }
            json(200, ServerWrapped.json(w))
        }
        rest.size == 2 && method == "GET" -> {
            val w = ServerWrapped.find(env, rest[1]) ?: return true.also { error(404, "No Wrapped with that id: see GET /api/v1/wrapped.") }
            json(200, ServerWrapped.json(w))
        }
        rest.size == 3 && rest[2] == "card" && method == "GET" -> {
            val w = ServerWrapped.find(env, rest[1]) ?: return true.also { error(404, "No Wrapped with that id: see GET /api/v1/wrapped.") }
            val png = ServerWrapped.png(w) ?: return true.also { error(503, "The card cannot be drawn on this system (no fonts): GET /api/v1/wrapped/${w.id} has its figures.") }
            bytes(png, "image/png")
        }
        else -> return false
    }
    return true
}

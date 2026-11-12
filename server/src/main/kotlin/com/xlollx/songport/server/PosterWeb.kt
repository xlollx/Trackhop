package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.PlayCounts
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.CoverArt
import com.xlollx.songport.sync.CoverPrompt
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.PosterPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

/**
 * The playlist poster (Features.v2), as the app's (Cards › Poster): the songs it lists, those the
 * user plays most first ([CoverPrompt.mostPlayed], or [CoverPrompt.byArtists] for a service that
 * tells artists only), and its colours from the playlist's cover ([PosterPalette]), in one of the
 * three layouts. The app draws it with Android's Canvas; the server gives the same picks, palette
 * and header (GET /api/v1/poster, `trackhop poster`, MCP get_playlist_poster) and the page draws
 * a plainer poster in HTML and CSS, to print or save as PDF from the browser.
 */
internal object ServerPoster {
    /** Songs on a poster, as the app's. */
    const val SONGS = 9

    /** Where the plays came from: [from] with its songs ([plays]) or artists ([artists]); [failed] a service that refused; [signIn] one to sign in again. */
    class Heard(
        val from: MusicProvider?, val plays: List<Listening.Play> = emptyList(), val artists: List<Listening.ArtistPlays> = emptyList(),
        val failed: Pair<MusicProvider, String>? = null, val signIn: MusicProvider? = null, val sources: Int = 0,
    )

    class Poster(
        val title: String, val service: String, val description: String, val trackCount: Int, val durationMs: Long,
        val layout: PosterPalette.Layout, val tones: PosterPalette.Tones, val lineup: List<String>,
        val songs: List<Track>, val fromPlays: Int, val byArtists: Boolean, val playsFrom: String?, val note: String, val signIn: String?,
    )

    fun layout(asked: String?): PosterPalette.Layout? =
        if (asked.isNullOrBlank()) PosterPalette.Layout.CLASSIC else PosterPalette.Layout.entries.firstOrNull { it.name.equals(asked.trim(), ignoreCase = true) }

    /** The period read for the poster, as the app's: a year where the service tells it, else the longest it can. */
    fun period(s: Listening.Source): Listening.Period = when {
        s.artistsOnly -> s.periods.first()
        Listening.Period.YEAR in s.periods -> Listening.Period.YEAR
        else -> s.periods.last()
    }

    /**
     * The songs the user plays most: first the playlist's own server's counts ([own] is a
     * personal server), then the listening services in order, the first that answers with songs
     * (or, TIDAL, with artists). A service that fails is told, and the poster goes on without it.
     */
    suspend fun heard(env: Env, own: MusicProvider, playlistId: String, fresh: Boolean): Heard {
        if (own is PlayCounts && !MusicProvider.isLibrary(playlistId)) {
            try {
                val plays = withContext(Dispatchers.IO) { own.playlistPlays(env, playlistId) }.filter { it.plays > 0 }.sortedByDescending { it.plays }
                if (plays.isNotEmpty()) return Heard(own, plays, sources = 1)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                env.log("poster", "${own.displayName}: the playlist's play counts not read: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val sources = ServerWrapped.fake?.sources ?: ServerListening.sources(env)
        var failed: Pair<MusicProvider, String>? = null
        var signIn: MusicProvider? = null
        for ((s, p) in sources) {
            if (p.id == own.id && own is PlayCounts) continue
            val report = try {
                ServerWrapped.fake?.report?.invoke(s, period(s)) ?: ServerListening.cached(env, s, p, period(s), fresh)
            } catch (e: CancellationException) { throw e } catch (_: Listening.Unavailable) { null } catch (_: Listening.MissingScope) {
                signIn = signIn ?: p; null
            } catch (e: Exception) {
                env.log("poster", "${p.displayName}: most played songs not read: ${e.message ?: e.javaClass.simpleName}")
                failed = failed ?: (p to (e.message ?: e.javaClass.simpleName)); null
            } ?: continue
            if (report.tracks.isNotEmpty()) return Heard(p, report.tracks, signIn = signIn, sources = sources.size)
            if (s.artistsOnly && report.artists.isNotEmpty()) return Heard(p, artists = report.artists, signIn = signIn, sources = sources.size)
        }
        return Heard(null, failed = failed, signIn = signIn, sources = sources.size)
    }

    suspend fun make(env: Env, p: MusicProvider, playlist: Playlist, layout: PosterPalette.Layout, fresh: Boolean = false): Poster {
        val tracks = p.tracks(env, playlist.id).filter { it.kind.isEmpty() && it.title.isNotBlank() }
        val title = playlist.name.ifBlank { "Untitled playlist" }
        val heard = heard(env, p, playlist.id, fresh)
        val byArtists = heard.artists.isNotEmpty()
        val listed = if (byArtists) CoverPrompt.byArtists(tracks, heard.artists, SONGS) else CoverPrompt.mostPlayed(tracks, heard.plays, SONGS)
        val from = heard.from?.let { ServerListening.brand(it) }
        val note = when {
            from != null && listed.fromPlays > 0 -> if (byArtists) "Songs by the artists you play most on $from first." else "The songs you play most on $from first."
            from != null -> "None of what you play most on $from is in this playlist: the songs of its most present artists instead."
            heard.sources == 0 && p !is PlayCounts -> "Connect a listening service (Last.fm, ListenBrainz, Spotify, Deezer, TIDAL or your own server) to list the songs you play most first; for now, the songs of the playlist's most present artists."
            heard.failed != null -> "${ServerListening.brand(heard.failed.first)} did not answer (${heard.failed.second}): the songs of the playlist's most present artists instead."
            else -> "No plays found for these songs: the songs of the playlist's most present artists."
        }
        val tones = PosterPalette.tones(sampled(playlist.imageUrl), title, layout)
        return Poster(
            title = title, service = p.label(env), description = playlist.description, trackCount = tracks.size,
            durationMs = tracks.sumOf { it.durationMs.coerceAtLeast(0) }, layout = layout, tones = tones,
            lineup = CoverPrompt.topArtists(tracks, 12), songs = listed.tracks, fromPlays = listed.fromPlays, byArtists = byArtists,
            playsFrom = from?.takeIf { listed.fromPlays > 0 }, note = note, signIn = heard.signIn?.let { ServerListening.brand(it) },
        )
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    /** The main colours of the playlist's cover, when the service lists one; none (the name's colours then) when it cannot be read. */
    private suspend fun sampled(url: String?): IntArray {
        val u = url?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return IntArray(0)
        return withContext(Dispatchers.IO) {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI(u)).timeout(Duration.ofSeconds(10)).header("User-Agent", "Trackhop").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = res.body().use { it.readNBytes(MAX_COVER + 1) }
                if (res.statusCode() !in 200..299 || bytes.size > MAX_COVER) return@runCatching IntArray(0)
                val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching IntArray(0)
                val small = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                small.createGraphics().apply { drawImage(src, 0, 0, 32, 32, null); dispose() }
                CoverArt.dominant(small.getRGB(0, 0, 32, 32, null, 0, 32))
            }.getOrDefault(IntArray(0))
        }
    }

    private const val MAX_COVER = 5_000_000

    fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    fun json(pl: Playlist, p: Poster): JsonObject = buildJsonObject {
        put("title", p.title); put("playlist", pl.id); put("service", p.service)
        put("trackCount", p.trackCount); put("durationMs", p.durationMs)
        put("layout", p.layout.name.lowercase())
        put("palette", buildJsonObject { put("ground", hex(p.tones.ground)); put("groundEnd", hex(p.tones.groundEnd)); put("ink", hex(p.tones.ink)); put("accent", hex(p.tones.accent)) })
        put("lineup", JsonArray(p.lineup.map { JsonPrimitive(it) }))
        put("tracks", JsonArray(p.songs.mapIndexed { i, t ->
            buildJsonObject { put("title", t.title); put("artist", t.artists.joinToString(", ")); put("id", t.id); put("fromPlays", i < p.fromPlays) }
        }))
        put("fromPlays", p.fromPlays)
        put("byArtists", p.byArtists)
        put("playsFrom", p.playsFrom?.let { JsonPrimitive(it) } ?: JsonNull)
        put("note", p.note)
        put("signInAgain", p.signIn?.let { JsonPrimitive(it) } ?: JsonNull)
        put("coverPrompt", CoverPrompt.build(p.title, p.lineup.take(5), p.description))
    }

    private fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    /** The poster in HTML and CSS: a 4:5 sheet in the palette's colours, laid out as [Poster.layout] asks. */
    fun html(p: Poster): String = buildString {
        val t = p.tones
        val bold = p.layout == PosterPalette.Layout.BOLD
        val minimal = p.layout == PosterPalette.Layout.MINIMAL
        val font = if (minimal) "Georgia,'Times New Roman',serif" else "system-ui,-apple-system,'Segoe UI',Roboto,sans-serif"
        append("""<div class="poster" style="max-width:540px;aspect-ratio:4/5;overflow:hidden;margin:0 auto 16px;padding:36px 34px;box-sizing:border-box;border-radius:6px;""")
        append("""background:linear-gradient(${hex(t.ground)},${hex(t.groundEnd)});color:${hex(t.ink)};font-family:$font;display:flex;flex-direction:column">""")
        val lineup = p.lineup.take(if (bold) 4 else 6).joinToString(if (bold) " " else "  ·  ") { h(it) }
        if (bold) {
            append("""<div style="font-weight:900;text-transform:uppercase;font-size:2.1em;line-height:1;letter-spacing:-.01em">$lineup</div>""")
            append("""<div style="margin:.6em 0 .8em;font-size:1.1em;font-weight:700;opacity:.85">${h(p.title)}</div>""")
        } else {
            append("""<div style="color:${hex(t.accent)};font-size:.72em;letter-spacing:.14em;text-transform:uppercase;font-weight:700">${h(p.service)}</div>""")
            append("""<div style="font-size:${if (minimal) "2.2em" else "2.4em"};font-weight:${if (minimal) "400" else "800"};line-height:1.05;margin:.25em 0 .35em">${h(p.title)}</div>""")
            append("""<div style="color:${hex(t.accent)};font-size:.8em;text-transform:uppercase;letter-spacing:.06em;margin-bottom:1em">$lineup</div>""")
        }
        append("""<ol style="margin:0;padding-left:1.6em;flex:1;font-size:.95em;line-height:1.45">""")
        p.songs.forEach { s ->
            append("""<li style="color:${hex(t.accent)}"><span style="color:${hex(t.ink)}"><b>${h(s.title)}</b> <span style="opacity:.7">${h(s.artists.joinToString(", "))}</span></span></li>""")
        }
        append("</ol>")
        append("""<div style="display:flex;justify-content:space-between;font-size:.75em;opacity:.75;margin-top:1em"><span>${p.trackCount} songs${if (p.durationMs > 0) " · " + minutes(p.durationMs) else ""}</span><span>Trackhop</span></div></div>""")
    }
}

// ------------------------------------------------------------------ the page

private fun Web.posterSources(): List<MusicProvider> =
    (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }
        .filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" }

private suspend fun Web.posterLists(p: MusicProvider): List<Playlist> = p.libraryEntries(env, false) + p.playlists(env)

/** Tools › Poster: a service, a playlist and a layout, then the poster drawn in the page. */
internal fun Web.posterPage(r: Web.Req) {
    val title = "Playlist poster"
    val body = StringBuilder(notice(r))
    val able = posterSources()
    if (able.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val intro = """<p class="muted">A poster of a playlist: its name, its artists and the songs you play most (from a listening service connected here), in its cover's colours. Print it, or save it as a PDF from your browser.</p>"""
    val service = r.query["service"]?.let { id -> able.firstOrNull { it.id == id } }
    if (service == null) {
        val opts = able.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/poster" class="card">$intro<label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val lists = runCatching { runBlocking { posterLists(service) } }
        .getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(it.message)}</div>""")) }
    val playlist = r.query["playlist"]?.let { id -> lists.firstOrNull { it.id == id } }
    val layout = ServerPoster.layout(r.query["layout"]) ?: PosterPalette.Layout.CLASSIC
    val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}"${if (p.id == playlist?.id) " selected" else ""}>${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
    val layouts = PosterPalette.Layout.entries.joinToString("") { l -> """<option value="${l.name.lowercase()}"${if (l == layout) " selected" else ""}>${l.name.lowercase().replaceFirstChar { it.uppercase() }}</option>""" }
    body.append("""<form method="get" action="/tools/poster" class="card">$intro<input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">$opts</select>
<label>Layout</label><select name="layout">$layouts</select>
<p><button>Make the poster</button> <a class="button secondary" href="/tools/poster">Another service</a></p></form>""")
    if (playlist != null) {
        val poster = runCatching { runBlocking { ServerPoster.make(env, service, playlist, layout) } }
            .getOrElse { return send(r.ex, 200, page(title, body.toString() + """<div class="card err">${h(service.label(env))}: ${h(it.message)}</div>""")) }
        body.append(ServerPoster.html(poster))
        body.append("""<div class="card"><p class="muted">${h(poster.note)}</p>""")
        poster.signIn?.let { body.append("""<p class="muted">${h(it)}: the sign-in predates reading your top songs. <a href="/connectors">Sign in again</a> to use it.</p>""") }
        body.append("""<p><button class="secondary" onclick="window.print()">Print or save as PDF</button></p></div>""")
    }
    send(r.ex, 200, page(title, body.toString(), wide = true))
}

// ------------------------------------------------------------------ the API

/** GET /api/v1/poster?service=&playlist=&layout= */
internal fun Web.apiPoster(method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("poster") || method != "GET") return false
    val able = posterSources()
    val asked = query["service"] ?: query["connector"]
    val p = if (asked != null) able.firstOrNull { it.id == asked } ?: return true.also { error(404, "No connected service \"$asked\": see GET /api/v1/services.") }
        else able.singleOrNull() ?: return true.also { error(400, "Say which service: ?service= one of ${able.joinToString(", ") { it.id }}.") }
    val wanted = query["playlist"]?.trim()?.takeIf { it.isNotEmpty() } ?: return true.also { error(400, "Say which playlist: ?playlist= its id or name (GET /api/v1/services/${p.id}/playlists).") }
    val layout = ServerPoster.layout(query["layout"]) ?: return true.also { error(400, "\"layout\" is classic, minimal or bold.") }
    val out = runCatching {
        runBlocking {
            val lists = posterLists(p)
            val pl = lists.firstOrNull { it.id == wanted } ?: lists.filter { it.name.equals(wanted, ignoreCase = true) }.singleOrNull() ?: return@runBlocking null
            pl to ServerPoster.make(env, p, pl, layout, fresh = query["fresh"] == "true")
        }
    }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
        ?: return true.also { error(404, "No playlist \"$wanted\" on ${p.label(env)} (or more than one with that name: use its id).") }
    json(200, ServerPoster.json(out.first, out.second))
    return true
}

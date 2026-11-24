package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.sync.CoverArt
import com.xlollx.songport.sync.Covers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color
import java.awt.Font
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Playlist covers (the app's GeneratedCovers and "an image of yours"): a cover drawn here from the
 * playlist's title and its top artists, in colours from its name (no AI, nothing sent anywhere;
 * "Another one" changes the seed), or an image of your own, squared and put on the playlist, on the
 * services that take one ([MusicProvider.canSetCover]). The drawn style is the app's Gradient.
 */
internal object CoverDraw {
    const val SIDE = 640

    private fun c(argb: Int, alpha: Int = 255) = Color((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, alpha.coerceIn(0, 255))

    fun gradient(title: String, artists: List<String>, variant: Int, side: Int = SIDE): BufferedImage {
        val img = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val pal = CoverArt.namePalette(title, variant)
        val rnd = Random(CoverArt.seed(title, variant) xor 7919L)
        val w = side.toFloat()
        val s = side / 1080f
        g.color = c(pal[0]); g.fillRect(0, 0, side, side)
        for (k in intArrayOf(pal[1], pal[3], pal[2], pal[1], pal[4])) {
            val cx = w * (-0.15f + rnd.nextFloat() * 1.3f)
            val cy = w * (-0.15f + rnd.nextFloat() * 1.3f)
            val r = w * (0.55f + rnd.nextFloat() * 0.45f)
            g.paint = RadialGradientPaint(Point2D.Float(cx, cy), r, floatArrayOf(0f, 0.5f, 1f), arrayOf(c(k, 235), c(k, 110), c(k, 0)), MultipleGradientPaint.CycleMethod.NO_CYCLE)
            g.fillOval((cx - r).toInt(), (cy - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
        }
        g.paint = RadialGradientPaint(Point2D.Float(w / 2, w / 2), w * 0.62f, floatArrayOf(0f, 0.6f, 1f), arrayOf(Color(0, 0, 0, 0x66), Color(0, 0, 0, 0x33), Color(0, 0, 0, 0)), MultipleGradientPaint.CycleMethod.NO_CYCLE)
        g.fillRect(0, 0, side, side)
        val m = 90f * s
        fun font(size: Float, bold: Boolean = true) = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
        val fit = CoverArt.fitTitle(title, w - 2 * m, w * 0.52f, 3, 170f * s, 54f * s, 1.16f) { str, size -> g.getFontMetrics(font(size)).stringWidth(str).toFloat() }
        val f = font(fit.size)
        val fm = g.getFontMetrics(f)
        val lh = fit.size * 1.16f
        val h = fit.lines.size * lh
        val top = (w - h) / 2 - 20f * s
        g.font = f
        fit.lines.forEachIndexed { i, l ->
            val x = (w - fm.stringWidth(l)) / 2
            val y = top + i * lh + fm.ascent * 0.98f
            g.color = Color(0, 0, 0, 0x55); g.drawString(l, x, y + fit.size * 0.03f)
            g.color = Color.WHITE; g.drawString(l, x, y)
        }
        val small = font(34f * s, bold = false)
        val sfm = g.getFontMetrics(small)
        val names = CoverArt.fitNames(artists, w - 2 * m) { str, k -> g.getFontMetrics(small.deriveFont(small.size2D * k)).stringWidth(str).toFloat() }
        if (names.text.isNotEmpty()) {
            g.font = small.deriveFont(small.size2D * names.scale)
            g.color = Color(255, 255, 255, 230)
            g.drawString(names.text, (w - g.fontMetrics.stringWidth(names.text)) / 2, top + h + 64f * s)
        }
        g.dispose()
        return img
    }

    fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    fun artists(tracks: List<Track>) = ArtistMix.topArtists(tracks, 4)
}

/** The playlist page's "Cover" card, where the service takes one. */
internal fun Web.coverCard(p: MusicProvider, playlist: Playlist, back: String): String {
    if (!p.canSetCover || !playlist.ownedByMe) return ""
    val v = 0
    return """<details class="card"><summary><b>Cover</b></summary><p class="muted">A cover drawn from the playlist's title and artists (no AI, nothing sent anywhere), or an image of yours.</p>
<p><img src="$back/cover.png?variant=$v" alt="The drawn cover" width="200" height="200" style="border-radius:12px"></p>
<form method="get" action="$back/cover" style="display:inline"><button class="secondary">See others</button></form> ${Html.postButton(back, "Use this one", "", mapOf("action" to "cover", "variant" to "$v"))}
<form method="post" action="$back" enctype="multipart/form-data"><input type="hidden" name="action" value="coverimage"><label>Or an image of yours</label><input type="file" name="image" accept="image/*" required><p><button class="secondary">Use my image</button></p></form></details>"""
}

/** /playlists/{service}/{playlist}/cover: several drawn covers to pick from (another seed each). */
internal fun Web.coverChoices(r: Web.Req, p: MusicProvider, playlist: Playlist, back: String) {
    val from = r.query["from"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val tiles = (from until from + 6).joinToString("") { v ->
        """<div style="display:inline-block;margin:0 10px 10px 0;text-align:center"><img src="$back/cover.png?variant=$v" alt="Cover $v" width="160" height="160" style="border-radius:12px;display:block">${Html.postButton(back, "Use", "secondary", mapOf("action" to "cover", "variant" to "$v"))}</div>"""
    }
    send(r.ex, 200, page("Cover of ${playlist.name}", """<div class="card">$tiles<p><a class="button secondary" href="$back/cover?from=${from + 6}">Others</a> <a href="$back">Back</a></p></div>"""))
}

internal fun Web.coverPng(r: Web.Req, p: MusicProvider, playlist: Playlist) {
    val tracks = runCatching { runBlocking { p.tracks(env, playlist.id) } }.getOrDefault(emptyList())
    val img = CoverDraw.gradient(playlist.name, CoverDraw.artists(tracks), r.query["variant"]?.toIntOrNull() ?: 0, 360)
    sendBytes(r.ex, 200, CoverDraw.png(img), "image/png")
}

/** Puts a drawn cover ([variant]) or [image]'s bytes on the playlist; the outcome in words. */
internal fun Web.setCover(p: MusicProvider, playlist: Playlist, variant: Int?, image: ByteArray?): String {
    if (!p.canSetCover) throw IllegalArgumentException("${p.label(env)} does not take covers from Trackhop.")
    val raw = image ?: run {
        val tracks = runBlocking { p.tracks(env, playlist.id) }
        CoverDraw.png(CoverDraw.gradient(playlist.name, CoverDraw.artists(tracks), variant ?: 0))
    }
    val jpeg = env.squareJpeg(raw, Covers.MIN_SIDE, Covers.MAX_SIDE, Covers.MAX_BYTES) ?: throw IllegalArgumentException("That image could not be read (a JPEG or PNG works).")
    runBlocking { p.setPlaylistCover(env, playlist.id, jpeg) }
    env.log("covers", "${p.label(env)}: cover set on ${playlist.name}")
    return "The cover is on ${playlist.name}: the service may take a minute to show it."
}

/** PUT /services/{service}/playlists/{playlist}/cover: {"variant": N} for a drawn one, or an image as the body. */
internal fun Web.apiCover(ex: com.sun.net.httpserver.HttpExchange, method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!(rest.size == 5 && rest[0] == "services" && rest[2] == "playlists" && rest[4] == "cover" && method == "PUT")) return false
    val p = com.xlollx.songport.providers.Providers.byId(rest[1])?.takeIf { !it.requiresAuth || it.isConnected(env) } ?: return true.also { error(404, "No connected service with that id.") }
    val pl = runCatching { runBlocking { p.playlists(env).firstOrNull { it.id == rest[3] } } }.getOrNull() ?: return true.also { error(404, "${p.label(env)} has no playlist with that id.") }
    val type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
    val bytes = ex.requestBody.readNBytes(15_000_001)
    if (bytes.size > 15_000_000) return true.also { error(413, "Images up to 15 MB.") }
    val image = if (type.startsWith("image/")) bytes else null
    val variant = if (image == null) runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(bytes).ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject }.getOrNull()?.number("variant") ?: 0 else null
    val done = runCatching { setCover(p, pl, variant, image) }.getOrElse { return true.also { _ -> error(if (it is IllegalArgumentException) 400 else 502, it.message ?: "Error") } }
    json(200, buildJsonObject { put("done", done) })
    return true
}

package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.xlollx.songport.R
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.sync.Compatibility
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * The shareable cards: the compatibility card and the playlist poster, drawn with Canvas into a
 * PNG in the app's own palette, as a story (1080×1920) or a square (1080×1080). One renderer for
 * both, so they look like they belong together. Covers come from the services or from Deezer's
 * public catalogue ([DeezerCatalog]) and are fetched here, best effort: a missing one is a tile in
 * a brand colour, never an error.
 */
object Cards {
    enum class Format(val width: Int, val height: Int) { STORY(1080, 1920), SQUARE(1080, 1080) }

    private const val INDIGO = 0xFF4338CA.toInt()
    private const val VIOLET = 0xFF7C3AED.toInt()
    private const val MAGENTA = 0xFFDB2777.toInt()
    private const val INK = 0xFF14121A.toInt()
    private const val PANEL = 0x2EFFFFFF
    private const val PANEL_LINE = 0x40FFFFFF
    private const val SITE = "trackhop.org"

    /** What the compatibility card shows: names, score, the common tracks with covers. */
    class CompatibilityCard(val nameA: String, val nameB: String, val result: Compatibility.Result, val covers: Map<String, Bitmap?>)

    /** What the poster shows. [qr] is the playlist's web address, null when the service has none. */
    class PosterCard(val title: String, val owner: String, val service: String, val trackCount: Int, val durationMs: Long, val covers: List<Bitmap?>, val qr: String?)

    /** A service on the stats card: its name, its colour (ARGB) and the id that picks its glyph. */
    class Mark(val name: String, val color: Int, val serviceId: String?)

    /**
     * "Trackhop in numbers": the headline figure with its label and period, up to three more
     * figures (value, label), and the route used most with its caption. Everything is already
     * formatted and translated: the renderer only lays it out.
     */
    class StatsCard(
        val title: String,
        val headline: String,
        val headlineLabel: String,
        val since: String?,
        val figures: List<Pair<String, String>>,
        val routeLabel: String,
        val route: Pair<Mark, Mark>?,
    )

    // ------------------------------------------------------------------ fetching covers

    private val coverCache = HashMap<String, Bitmap?>()

    /** The image at [url], decoded and shrunk to about [side] pixels; null when it cannot be read. */
    suspend fun bitmap(url: String, side: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(coverCache) { if (coverCache.containsKey(url)) return@withContext coverCache[url] }
        val bmp = runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val bytes = r.body?.bytes() ?: return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
        synchronized(coverCache) { if (coverCache.size > 60) coverCache.clear(); coverCache[url] = bmp }
        bmp
    }

    /** A cover for [t]: the service's own picture when free (YouTube), else Deezer's or iTunes' catalogue; null when none loads. */
    suspend fun trackCover(t: Track, serviceId: String): Bitmap? {
        for (url in DeezerCatalog.ownCover(t.id, serviceId)) bitmap(url, 400)?.let { return it }
        val found = DeezerCatalog.coverUrl(t) ?: DeezerCatalog.itunesCover(t) ?: return null
        return bitmap(found, 400)
    }

    /**
     * Up to [want] covers of different albums, in playlist order: a playlist often opens with
     * several tracks of one album, and a mosaic of the same picture says nothing. Tracks of an
     * album already shown are skipped, and so is an image identical to one already picked; at
     * most [maxLookups] tracks are looked up, four at a time. Fewer than [want] found: the card
     * shows the first one alone.
     */
    suspend fun distinctCovers(tracks: List<Track>, serviceId: String, want: Int = 4, maxLookups: Int = 16): List<Bitmap> {
        val candidates = tracks.distinctBy { com.xlollx.songport.sync.CoverPicks.albumKey(it) }.take(maxLookups)
        val picked = ArrayList<Bitmap>()
        for (batch in candidates.chunked(4)) {
            val got = kotlinx.coroutines.coroutineScope { batch.map { t -> async { trackCover(t, serviceId) } }.map { it.await() } }
            for (b in got) {
                if (b == null || picked.any { it === b || it.sameAs(b) }) continue
                picked += b
                if (picked.size == want) return picked
            }
        }
        return picked
    }

    // ------------------------------------------------------------------ drawing

    private fun paint(color: Int, size: Float, bold: Boolean = false, alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; this.alpha = alpha
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun background(c: Canvas, w: Int, h: Int) {
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(INDIGO, VIOLET, MAGENTA), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        // A soft dark wash so white text reads on the brightest corner too.
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = INK; alpha = 70 })
    }

    /**
     * The brand beam ([Brand.BeamStops], as the Syncs header and the icon): the band's edge
     * through the right border at [edgeY] of the height, so it crosses the top of the mark's tile
     * and all the text stays on the dark side.
     */
    private fun beam(c: Canvas, w: Int, h: Int, edgeY: Float) = paintBeam(c, w, h, edgeY)

    private fun panel(c: Canvas, r: RectF, radius: Float = 36f) {
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL })
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_LINE; style = Paint.Style.STROKE; strokeWidth = 2f })
    }

    /** The app's name (the build's own: Trackhop on Google Play) and the site, along the bottom edge. */
    private fun footer(ctx: Context, c: Canvas, w: Int, h: Int) {
        val name = ctx.getString(R.string.app_name)
        val p = paint(Color.WHITE, 30f, alpha = 200)
        val brand = paint(Color.WHITE, 30f, bold = true, alpha = 230)
        val text = "  ·  $SITE"
        val total = brand.measureText(name) + p.measureText(text)
        val x = (w - total) / 2
        c.drawText(name, x, h - 56f, brand)
        c.drawText(text, x + brand.measureText(name), h - 56f, p)
    }

    /** One line, cut with an ellipsis to [maxWidth]. */
    private fun line(c: Canvas, text: String, x: Float, y: Float, p: TextPaint, maxWidth: Float, align: Paint.Align = Paint.Align.LEFT) {
        val cut = TextUtils.ellipsize(text, p, maxWidth, TextUtils.TruncateAt.END).toString()
        p.textAlign = align
        c.drawText(cut, x, y, p)
        p.textAlign = Paint.Align.LEFT
    }

    /** Up to [maxLines] wrapped lines; returns the height used. */
    private fun block(c: Canvas, text: String, x: Float, y: Float, p: TextPaint, width: Int, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(align).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()
        c.save(); c.translate(x, y); layout.draw(c); c.restore()
        return layout.height.toFloat()
    }

    private fun cover(c: Canvas, bmp: Bitmap?, r: RectF, radius: Float, seed: Int) {
        val path = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.save(); c.clipPath(path)
        if (bmp != null) {
            val scale = maxOf(r.width() / bmp.width, r.height() / bmp.height)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            val dst = RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2)
            c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val tones = intArrayOf(INDIGO, VIOLET, MAGENTA, 0xFF0EA5E9.toInt(), 0xFF14B8A6.toInt())
            c.drawRect(r, Paint().apply { color = tones[Math.floorMod(seed, tones.size)] })
            val note = paint(Color.WHITE, r.height() * 0.42f, bold = true, alpha = 150)
            note.textAlign = Paint.Align.CENTER
            c.drawText("♪", r.centerX(), r.centerY() + note.textSize * 0.36f, note)
        }
        c.restore()
    }

    fun compatibility(ctx: Context, card: CompatibilityCard, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(c, w, h)
        val margin = 72f
        val story = format == Format.STORY
        var y = if (story) 200f else 88f
        val eyebrow = paint(Color.WHITE, 34f, alpha = 210)
        eyebrow.textAlign = Paint.Align.CENTER
        c.drawText(ctx.getString(R.string.card_compat_eyebrow).uppercase(), w / 2f, y, eyebrow)
        y += if (story) 150f else 110f
        // The score, as big as it gets.
        val score = paint(Color.WHITE, if (story) 300f else 220f, bold = true)
        score.textAlign = Paint.Align.CENTER
        c.drawText("${card.result.score}%", w / 2f, y + (if (story) 110f else 80f), score)
        y += if (story) 190f else 140f
        val sub = paint(Color.WHITE, 36f, alpha = 220)
        sub.textAlign = Paint.Align.CENTER
        c.drawText(ctx.getString(R.string.card_compat_subtitle, card.result.common.size, card.result.sharedArtists.size), w / 2f, y, sub)
        y += if (story) 90f else 70f
        // The two names, side by side, with a cross between them.
        val names = paint(Color.WHITE, 40f, bold = true)
        val half = (w - 2 * margin - 80f) / 2
        line(c, card.nameA, margin + half, y, names, half, Paint.Align.RIGHT)
        val cross = paint(Color.WHITE, 40f, alpha = 170); cross.textAlign = Paint.Align.CENTER
        c.drawText("×", w / 2f, y, cross)
        line(c, card.nameB, w - margin - half, y, names, half, Paint.Align.LEFT)
        y += if (story) 90f else 60f
        // Common tracks, with covers; the square fits three, the story five.
        val rows = card.result.common.take(if (story) 5 else 3)
        if (rows.isNotEmpty()) {
            val rowH = if (story) 132f else 112f
            val gap = 14f
            val panelRect = RectF(margin, y, w - margin, y + rows.size * (rowH + gap) + 40f)
            panel(c, panelRect)
            var ry = y + 20f
            rows.forEachIndexed { i, common ->
                val t = common.a
                val art = RectF(margin + 24f, ry, margin + 24f + rowH - 8f, ry + rowH - 8f)
                cover(c, card.covers[t.id], art, 18f, i + t.title.length)
                val tx = art.right + 24f
                val maxW = w - margin - 24f - tx
                line(c, t.title, tx, ry + rowH * 0.42f, paint(Color.WHITE, 36f, bold = true), maxW)
                line(c, t.artistLine, tx, ry + rowH * 0.80f, paint(Color.WHITE, 30f, alpha = 200), maxW)
                ry += rowH + gap
            }
            y = panelRect.bottom + 36f
        }
        if (card.result.sharedArtists.isNotEmpty() && (story || rows.size < 3)) {
            val label = ctx.getString(R.string.card_compat_artists) + "  " + card.result.sharedArtists.take(6).joinToString("  ·  ")
            block(c, label, margin, y, paint(Color.WHITE, 30f, alpha = 210), (w - 2 * margin).toInt(), 2, Layout.Alignment.ALIGN_CENTER)
        }
        footer(ctx, c, w, h)
        return bmp
    }

    fun poster(ctx: Context, card: PosterCard, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(c, w, h)
        val margin = 72f
        val story = format == Format.STORY
        // The art: one cover, or a 2×2 mosaic of the first four tracks'.
        val artSide = if (story) w - 2 * margin else 520f
        val artLeft = if (story) margin else margin
        val artTop = if (story) 150f else 100f
        val art = RectF(artLeft, artTop, artLeft + artSide, artTop + artSide)
        val covers = card.covers.take(4)
        if (covers.size >= 4) {
            val half = artSide / 2
            val path = Path().apply { addRoundRect(art, 40f, 40f, Path.Direction.CW) }
            c.save(); c.clipPath(path)
            for (i in 0 until 4) {
                val r = RectF(art.left + (i % 2) * half, art.top + (i / 2) * half, art.left + (i % 2 + 1) * half, art.top + (i / 2 + 1) * half)
                cover(c, covers[i], r, 0f, i)
            }
            c.restore()
        } else cover(c, covers.firstOrNull(), art, 40f, card.title.length)

        val textLeft = if (story) margin else art.right + 40f
        val textWidth = if (story) (w - 2 * margin).toInt() else (w - margin - textLeft).toInt()
        var y = if (story) art.bottom + 70f else art.top + 10f
        y += block(c, card.title, textLeft, y, paint(Color.WHITE, if (story) 68f else 54f, bold = true), textWidth, 2) + 18f
        val owner = listOf(card.owner, card.service).filter { it.isNotBlank() }.joinToString("  ·  ")
        y += block(c, owner, textLeft, y, paint(Color.WHITE, 34f, alpha = 210), textWidth, 1) + 14f
        val counts = buildString {
            append(ctx.resources.getQuantityString(R.plurals.card_tracks, card.trackCount, card.trackCount))
            if (card.durationMs > 0) append("  ·  ").append(duration(card.durationMs))
        }
        block(c, counts, textLeft, y, paint(Color.WHITE, 32f, alpha = 190), textWidth, 1)

        // The QR, bottom right, on a white tile so any reader gets it.
        card.qr?.let { url ->
            val symbol = runCatching { QrCode.encode(url) }.getOrNull() ?: return@let
            val tile = if (story) 360f else 300f
            val quiet = 28f
            val tr = RectF(w - margin - tile, h - 120f - tile, w - margin, h - 120f)
            c.drawRoundRect(tr, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val module = (tile - 2 * quiet) / symbol.size
            val dark = Paint().apply { color = INK }
            for (yy in 0 until symbol.size) for (xx in 0 until symbol.size) if (symbol[xx, yy]) {
                c.drawRect(tr.left + quiet + xx * module, tr.top + quiet + yy * module, tr.left + quiet + (xx + 1) * module + 0.5f, tr.top + quiet + (yy + 1) * module + 0.5f, dark)
            }
            val hint = paint(Color.WHITE, 30f, alpha = 210)
            val hintW = (tr.left - margin - 30f).toInt()
            if (hintW > 200) block(c, ctx.getString(R.string.card_poster_scan, card.service), margin, tr.bottom - 80f, hint, hintW, 2)
        }
        footer(ctx, c, w, h)
        return bmp
    }

    /** [p] at the largest size up to [size] at which [text] fits in [maxWidth]. */
    private fun fit(text: String, p: TextPaint, size: Float, maxWidth: Float): TextPaint {
        p.textSize = size
        while (p.textSize > size * 0.4f && p.measureText(text) > maxWidth) p.textSize *= 0.94f
        return p
    }

    /**
     * The Trackhop mark (the app's own vector), in white, [side] wide, centred on [cx] on the
     * glass tile of the icon (white at 18 %, a faint lighter edge, corners at 0.24 of the side);
     * the tile is side / 0.8 wide and its top is [top]. Returns the tile's height.
     */
    private fun brandMark(ctx: Context, c: Canvas, cx: Float, top: Float, side: Float): Float {
        val tile = side / 0.8f
        val r = RectF(cx - tile / 2, top, cx + tile / 2, top + tile)
        val radius = tile * 0.24f
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL })
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF; style = Paint.Style.STROKE; strokeWidth = 3f })
        val d = ContextCompat.getDrawable(ctx, R.drawable.ic_brand_mark)?.mutate() ?: return tile
        d.setTint(Color.WHITE)
        val markTop = top + (tile - side) / 2
        d.setBounds((cx - side / 2).toInt(), markTop.toInt(), (cx + side / 2).toInt(), (markTop + side).toInt())
        d.draw(c)
        return tile
    }

    /** A service's mark as the app draws it ([ServiceLogo]): the glyph, or the initial, on a disc of its colour. */
    private fun mark(c: Canvas, m: Mark, cx: Float, cy: Float, d: Float) {
        c.drawCircle(cx, cy, d / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.color })
        // A near-black mark (TIDAL) gets a light edge, so it does not sink into the dark corner.
        if (Color.luminance(m.color) < 0.03f) {
            c.drawCircle(cx, cy, d / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_LINE; style = Paint.Style.STROKE; strokeWidth = 3f })
        }
        val fg = if (Color.luminance(m.color) > 0.45f) INK else Color.WHITE
        val glyph = ServiceLogos.pathData(m.serviceId)?.let { data ->
            runCatching { PathParser().parsePathString(data).toPath().asAndroidPath() }.getOrNull()
        }
        if (glyph != null) {
            val side = d * 0.56f
            glyph.transform(Matrix().apply { setScale(side / 24f, side / 24f); postTranslate(cx - side / 2, cy - side / 2) })
            c.drawPath(glyph, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg })
        } else {
            val p = paint(fg, d * 0.46f, bold = true)
            p.textAlign = Paint.Align.CENTER
            c.drawText(m.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", cx, cy + p.textSize * 0.36f, p)
        }
    }

    fun stats(ctx: Context, card: StatsCard, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val story = format == Format.STORY
        beam(c, w, h, if (story) 0.14f else 0.15f)
        val margin = 72f
        val inner = w - 2 * margin
        val cx = w / 2f
        // The mark on its tile, and the title.
        val markSide = if (story) 124f else 84f
        val markTop = if (story) 130f else 50f
        val tile = brandMark(ctx, c, cx, markTop, markSide)
        var y = markTop + tile + (if (story) 72f else 54f)
        val eyebrow = paint(Color.WHITE, if (story) 38f else 32f, bold = true, alpha = 225).apply { letterSpacing = 0.08f }
        line(c, card.title.uppercase(), cx, y, eyebrow, inner, Paint.Align.CENTER)
        // The headline figure, as big as it fits.
        y += if (story) 340f else 210f
        val big = fit(card.headline, paint(Color.WHITE, 10f, bold = true), if (story) 270f else 190f, inner)
        big.textAlign = Paint.Align.CENTER
        c.drawText(card.headline, cx, y, big)
        y += if (story) 100f else 66f
        line(c, card.headlineLabel, cx, y, paint(Color.WHITE, if (story) 54f else 42f, bold = true), inner, Paint.Align.CENTER)
        card.since?.let {
            y += if (story) 64f else 48f
            line(c, it, cx, y, paint(Color.WHITE, if (story) 36f else 30f, alpha = 215), inner, Paint.Align.CENTER)
        }
        // Up to three more figures, side by side on glass panels.
        val figures = card.figures.take(3)
        if (figures.isNotEmpty()) {
            y += if (story) 100f else 44f
            val gap = 24f
            val pw = (inner - gap * (figures.size - 1)) / figures.size
            val ph = if (story) 300f else 210f
            figures.forEachIndexed { i, (value, label) ->
                val left = margin + i * (pw + gap)
                val r = RectF(left, y, left + pw, y + ph)
                panel(c, r)
                val vp = fit(value, paint(Color.WHITE, 10f, bold = true), if (story) 84f else 64f, pw - 40f)
                vp.textAlign = Paint.Align.CENTER
                c.drawText(value, r.centerX(), y + (if (story) 130f else 92f), vp)
                block(c, label, left + 20f, y + (if (story) 166f else 118f), paint(Color.WHITE, if (story) 30f else 26f, alpha = 215), (pw - 40f).toInt(), if (story) 3 else 2, Layout.Alignment.ALIGN_CENTER)
            }
            y += ph
        }
        // The route used most: the two services' marks with an arrow between them.
        card.route?.let { (from, to) ->
            val caption = paint(Color.WHITE, if (story) 32f else 28f, bold = true, alpha = 210).apply { letterSpacing = 0.06f }
            val arrow = paint(Color.WHITE, if (story) 84f else 56f, bold = true, alpha = 230)
            arrow.textAlign = Paint.Align.CENTER
            if (story) {
                y += 135f
                line(c, card.routeLabel.uppercase(), cx, y, caption, inner, Paint.Align.CENTER)
                val d = 190f
                val cy = y + 60f + d / 2
                val dx = 220f
                mark(c, from, cx - dx, cy, d)
                mark(c, to, cx + dx, cy, d)
                c.drawText("→", cx, cy + arrow.textSize * 0.32f, arrow)
                val names = paint(Color.WHITE, 36f, bold = true)
                line(c, from.name, cx - dx, cy + d / 2 + 70f, names, 2 * dx - 40f, Paint.Align.CENTER)
                line(c, to.name, cx + dx, cy + d / 2 + 70f, names, 2 * dx - 40f, Paint.Align.CENTER)
            } else {
                // One row: mark, name, arrow, mark, name, centred.
                y += 56f
                line(c, card.routeLabel.uppercase(), cx, y, caption, inner, Paint.Align.CENTER)
                val d = 92f
                val cy = y + 34f + d / 2
                val names = paint(Color.WHITE, 32f, bold = true)
                val arrowW = 90f
                val nameMax = (inner - 2 * (d + 16f) - arrowW) / 2
                val a = TextUtils.ellipsize(from.name, names, nameMax, TextUtils.TruncateAt.END).toString()
                val b = TextUtils.ellipsize(to.name, names, nameMax, TextUtils.TruncateAt.END).toString()
                val total = 2 * (d + 16f) + names.measureText(a) + names.measureText(b) + arrowW
                var x = (w - total) / 2
                val base = cy + names.textSize * 0.36f
                mark(c, from, x + d / 2, cy, d); x += d + 16f
                c.drawText(a, x, base, names); x += names.measureText(a)
                c.drawText("→", x + arrowW / 2, cy + arrow.textSize * 0.32f, arrow); x += arrowW
                mark(c, to, x + d / 2, cy, d); x += d + 16f
                c.drawText(b, x, base, names)
            }
        }
        footer(ctx, c, w, h)
        return bmp
    }

    /**
     * "Your Wrapped": the musical age as the headline (or the track count, when the services did
     * not say when the songs came out), then label/value rows. Already formatted and translated.
     */
    class WrappedCard(val eyebrow: String, val headline: String, val subline: String?, val rows: List<Pair<String, String>>)

    /** A story card (9:16) of the user's own figures; the square is laid out the same, tighter. */
    fun wrapped(ctx: Context, card: WrappedCard, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val story = format == Format.STORY
        background(c, w, h)
        val margin = 80f
        val inner = w - 2 * margin
        var y = if (story) 220f else 110f
        val eyebrow = paint(Color.WHITE, if (story) 38f else 32f, bold = true, alpha = 215).apply { letterSpacing = 0.12f }
        line(c, card.eyebrow.uppercase(), margin, y, eyebrow, inner)
        y += if (story) 60f else 40f
        y += block(c, card.headline, margin, y, paint(Color.WHITE, if (story) 112f else 84f, bold = true), inner.toInt(), 2)
        card.subline?.let {
            y += 14f
            y += block(c, it, margin, y, paint(Color.WHITE, if (story) 44f else 36f, alpha = 225), inner.toInt(), 2)
        }
        y += if (story) 110f else 50f
        val rowH = if (story) 150f else 104f
        val label = paint(Color.WHITE, if (story) 36f else 30f, alpha = 200)
        val value = paint(Color.WHITE, if (story) 44f else 36f, bold = true)
        for ((l, v) in card.rows) {
            if (y + rowH > h - 150f) break
            line(c, l, margin, y + rowH * 0.42f, label, inner * 0.42f)
            line(c, v, w - margin, y + rowH * 0.42f, value, inner * 0.56f, Paint.Align.RIGHT)
            c.drawRect(margin, y + rowH * 0.72f, w - margin, y + rowH * 0.72f + 2f, Paint().apply { color = PANEL_LINE })
            y += rowH
        }
        footer(ctx, c, w, h)
        return bmp
    }

    /** "Roast of a playlist": the eyebrow, the playlist's name and the AI's words on a glass panel. */
    fun roast(ctx: Context, eyebrow: String, title: String, text: String, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val story = format == Format.STORY
        background(c, w, h)
        val margin = 72f
        val inner = (w - 2 * margin).toInt()
        var y = if (story) 220f else 90f
        val flame = paint(Color.WHITE, if (story) 150f else 90f)
        flame.textAlign = Paint.Align.CENTER
        c.drawText("🔥", w / 2f, y + flame.textSize * 0.8f, flame)
        y += flame.textSize + (if (story) 60f else 30f)
        val eb = paint(Color.WHITE, if (story) 36f else 30f, bold = true, alpha = 215).apply { letterSpacing = 0.1f }
        line(c, eyebrow.uppercase(), w / 2f, y, eb, inner.toFloat(), Paint.Align.CENTER)
        y += 24f
        y += block(c, title, margin, y, paint(Color.WHITE, if (story) 64f else 50f, bold = true), inner, 2, Layout.Alignment.ALIGN_CENTER) + 40f
        val body = paint(Color.WHITE, if (story) 44f else 34f)
        val pad = 48f
        val bottom = h - 140f
        // As much of the text as fits on the panel, in lines of the body's size.
        val lines = ((bottom - y - 2 * pad) / (body.fontSpacing * 1.05f)).toInt().coerceAtLeast(3)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, body, inner - (2 * pad).toInt())
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()
        panel(c, RectF(margin, y, w - margin, y + layout.height + 2 * pad))
        c.save(); c.translate(margin + pad, y + pad); layout.draw(c); c.restore()
        footer(ctx, c, w, h)
        return bmp
    }

    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    // ------------------------------------------------------------------ out of the app

    /** Writes the PNG in the cache and opens the system share sheet on it. */
    fun share(ctx: Context, bmp: Bitmap, name: String) {
        val dir = File(ctx.cacheDir, "cards").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val file = File(dir, name.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").take(60) + ".png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".cards", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Writes the PNG where the system picker said. */
    fun save(ctx: Context, bmp: Bitmap, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } != null
    }.getOrDefault(false)
}

/** The cards' own provider (see the manifest): one class per authority, so the merger keeps both. */
class CardsFileProvider : FileProvider()

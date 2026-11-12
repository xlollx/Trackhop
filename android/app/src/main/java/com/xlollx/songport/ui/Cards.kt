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
import com.xlollx.songport.sync.CoverArt
import com.xlollx.songport.sync.DeezerCatalog
import com.xlollx.songport.sync.PosterPalette
import com.xlollx.songport.sync.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * The shareable cards: the compatibility card and the playlist poster, drawn with Canvas into a
 * PNG in the app's own palette (the poster in its art's), as a story (1080×1920) or a square (1080×1080). One renderer for
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

    /**
     * What the poster shows. [qr] is the playlist's web address, null when the service has none;
     * [artists] all of its artists, the ones that appear most first (the poster prints the first
     * that fit, whole, then "+N"), [tracks] the songs it lists (title, artist). [titledArt]:
     * the art already carries the title (a cover drawn on the phone), so the sheet does not print
     * it twice. [owner] is blank unless the user chose to sign the poster.
     */
    class PosterCard(
        val title: String, val owner: String, val service: String, val trackCount: Int, val durationMs: Long, val covers: List<Bitmap?>, val qr: String?,
        val artists: List<String> = emptyList(), val tracks: List<Pair<String, String>> = emptyList(),
        val layout: PosterPalette.Layout = PosterPalette.Layout.CLASSIC, val titledArt: Boolean = false,
    ) {
        /** The same poster laid out as [layout], signed [owner], with its QR code (and its caption) or, [withQr] false, without. */
        fun with(layout: PosterPalette.Layout, owner: String, withQr: Boolean = true) =
            PosterCard(title, owner, service, trackCount, durationMs, covers, qr.takeIf { withQr }, artists, tracks, layout, titledArt)
    }

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
    suspend fun trackCover(t: Track, serviceId: String): Bitmap? = foundCover(t, serviceId)?.second

    /** Like [trackCover], with the address the picture came from. */
    private suspend fun foundCover(t: Track, serviceId: String): Pair<String, Bitmap>? {
        for (url in DeezerCatalog.ownCover(t.id, serviceId)) bitmap(url, 400)?.let { return url to it }
        val found = DeezerCatalog.coverUrl(t) ?: DeezerCatalog.itunesCover(t) ?: return null
        return bitmap(found, 400)?.let { found to it }
    }

    /**
     * Up to [want] covers of different albums that stand for the playlist: the artists that
     * appear most first, one album each, then the others ([com.xlollx.songport.sync.CoverPicks.order];
     * [variant] varies the picks a little). A cover that does not load, or repeats an image already
     * picked, is skipped; at most [maxLookups] tracks are looked up, four at a time. Fewer than
     * four found: the poster shows the first one alone. [preferred] tracks are looked up first.
     */
    suspend fun distinctCovers(tracks: List<Track>, serviceId: String, want: Int = 4, maxLookups: Int = 16, variant: Int = 0, preferred: List<Track> = emptyList()): List<Bitmap> {
        // The [preferred] tracks first (the user's most played), varied with the rest.
        val first = if (variant > 0) preferred.shuffled(java.util.Random(variant.toLong())) else preferred
        val order = (first + com.xlollx.songport.sync.CoverPicks.order(tracks, variant)).distinct()
        return pickDistinct(order.take(maxLookups).map { it to serviceId }, want).map { it.second }
    }

    /**
     * The addresses of up to [want] different covers for [candidates] (a track and the service it
     * was read from), looked up in their order, four at a time, as [distinctCovers] picks them.
     * What was found so far goes to [found] as it comes, so a caller that stops waiting keeps it.
     */
    suspend fun distinctCoverUrls(candidates: List<Pair<Track, String>>, want: Int, found: MutableList<String> = ArrayList()): List<String> {
        pickDistinct(candidates, want) { synchronized(found) { found += it } }
        return synchronized(found) { found.toList() }
    }

    private suspend fun pickDistinct(candidates: List<Pair<Track, String>>, want: Int, onPick: (String) -> Unit = {}): List<Pair<String, Bitmap>> {
        val picked = ArrayList<Pair<String, Bitmap>>()
        for (batch in candidates.chunked(4)) {
            val got = kotlinx.coroutines.coroutineScope { batch.map { (t, sid) -> async { foundCover(t, sid) } }.map { it.await() } }
            for (f in got) {
                if (f == null || picked.any { (_, b) -> b === f.second || b.sameAs(f.second) }) continue
                picked += f; onPick(f.first)
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
        var cut = TextUtils.ellipsize(text, p, maxWidth, TextUtils.TruncateAt.END).toString()
        // Measured again: where the system's cut still runs over (it happens off-device), cut by hand.
        if (p.measureText(cut) > maxWidth) {
            val room = (maxWidth - p.measureText("…")).coerceAtLeast(0f)
            cut = text.substring(0, p.breakText(text, true, room, null)).trimEnd() + "…"
        }
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

    /**
     * The playlist poster, printed in the colours of its own art ([PosterPalette]): the cover
     * sharp and large, the title (unless the art carries it), the artists that appear most, the
     * numbers in small capitals, the first songs, and the QR on a white tile when there is one.
     */
    fun poster(ctx: Context, card: PosterCard, format: Format): Bitmap {
        val bmp = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tones = PosterPalette.tones(GeneratedCovers.sampled(card.covers.filterNotNull()), card.title, card.layout)
        val s = Sheet(ctx, c, format.width.toFloat(), format.height.toFloat(), tones, card)
        posterGround(s)
        when (card.layout) {
            PosterPalette.Layout.CLASSIC -> posterClassic(s)
            PosterPalette.Layout.MINIMAL -> posterMinimal(s)
            PosterPalette.Layout.BOLD -> posterBold(s)
        }
        // The app's name and the site, small and quiet along the bottom edge.
        val name = ctx.getString(R.string.app_name)
        val brand = paint(s.t.ink, 24f, bold = true, alpha = 150)
        val site = paint(s.t.ink, 24f, alpha = 120)
        val rest = "  ·  $SITE"
        val x = (s.w - brand.measureText(name) - site.measureText(rest)) / 2
        val base = s.h - (if (s.story) 58f else 42f)
        c.drawText(name, x, base, brand)
        c.drawText(rest, x + brand.measureText(name), base, site)
        return bmp
    }

    /** What every layout of the poster draws with: the sheet, its colours, and the card's words ready to print. */
    private class Sheet(val ctx: Context, val c: Canvas, val w: Float, val h: Float, val t: PosterPalette.Tones, val card: PosterCard) {
        val story = h > w
        val symbol: QrCode.Symbol? = card.qr?.let { runCatching { QrCode.encode(it) }.getOrNull() }
        val kind: String = ctx.getString(R.string.editor_playlist).uppercase()
        val service: String = card.service.uppercase()
        val counts: String = buildString {
            append(ctx.resources.getQuantityString(R.plurals.card_tracks, card.trackCount, card.trackCount))
            if (card.durationMs > 0) append("  ·  ").append(duration(card.durationMs))
        }.uppercase()
        val owner: String = card.owner.trim().uppercase()
        /** Where the content ends: the footer has the rest. */
        val bottom = h - (if (story) 130f else 96f)
    }

    /** Small capitals, spaced: the poster's captions. */
    private fun caps(color: Int, size: Float, alpha: Int = 255, bold: Boolean = true) = paint(color, size, bold, alpha).apply { letterSpacing = 0.14f }

    private fun condensed(color: Int, size: Float, alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; this.alpha = alpha
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    /** The sheet: the ground's colour deepening to the bottom, and a light grain, as ink on paper. */
    private fun posterGround(s: Sheet) {
        s.c.drawRect(0f, 0f, s.w, s.h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, s.h, s.t.ground, s.t.groundEnd, Shader.TileMode.CLAMP) })
        val rnd = java.util.Random(CoverArt.seed(s.card.title))
        val light = Paint().apply { color = 0x0CFFFFFF }
        val dark = Paint().apply { color = 0x0C000000 }
        repeat(if (s.story) 30_000 else 17_000) {
            val x = rnd.nextFloat() * s.w; val y = rnd.nextFloat() * s.h
            s.c.drawRect(x, y, x + 2f, y + 2f, if (it % 2 == 0) light else dark)
        }
    }

    /**
     * The art in [r], sharp: one cover, or four as a 2×2. Under it a soft shadow of [shadow]
     * (ARGB), or with [block] a flat offset block of that colour, as a second ink.
     */
    private fun posterArt(s: Sheet, r: RectF, radius: Float, shadow: Int = 0x73000000, block: Int? = null) {
        val c = s.c
        if (block != null) c.drawRect(r.left + 16f, r.top + 16f, r.right + 16f, r.bottom + 16f, Paint().apply { color = block })
        else c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.t.groundEnd; setShadowLayer(r.width() * 0.045f, 0f, r.width() * 0.02f, shadow) })
        val covers = s.card.covers.take(4)
        if (covers.size >= 4) {
            val half = r.width() / 2
            c.save(); c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
            for (i in 0 until 4) cover(c, covers[i], RectF(r.left + (i % 2) * half, r.top + (i / 2) * half, r.left + (i % 2 + 1) * half, r.top + (i / 2 + 1) * half), 0f, i)
            c.restore()
        } else cover(c, covers.firstOrNull(), r, radius, s.card.title.length)
    }

    /**
     * [text] fitted into [box] (the largest size up to [maxSize], at most [maxLines] lines) and
     * drawn with its capitals' top on the box's; returns where the last line ends.
     */
    private fun posterTitle(c: Canvas, text: String, box: RectF, p: TextPaint, maxLines: Int, maxSize: Float, minSize: Float, lineHeight: Float = 1.08f, align: Paint.Align = Paint.Align.LEFT): Float {
        val fit = CoverArt.fitTitle(text, box.width(), box.height(), maxLines, maxSize, minSize, lineHeight) { str, size -> p.textSize = size; p.measureText(str) }
        p.textSize = fit.size
        p.textAlign = align
        val x = when (align) { Paint.Align.LEFT -> box.left; Paint.Align.CENTER -> box.centerX(); else -> box.right }
        val first = box.top + fit.size * 0.73f
        fit.lines.forEachIndexed { i, l -> c.drawText(l, x, first + i * fit.size * lineHeight, p) }
        p.textAlign = Paint.Align.LEFT
        return first + (fit.lines.size - 1) * fit.size * lineHeight + fit.size * 0.2f
    }

    /**
     * The songs, numbered, one a line from [top], as many (at most [max]) as fit above [bottom];
     * returns where they end. The artist is never cut: whole (a little smaller if need be) after
     * the title, or left out when it has no room; the title gives way, cut after a whole word.
     */
    private fun posterTracks(s: Sheet, x: Float, top: Float, bottom: Float, width: Float, size: Float, max: Int = 8): Float {
        val rowH = size * 1.9f
        val n = minOf(s.card.tracks.size, max, ((bottom - top) / rowH).toInt())
        if (n < 2) return top
        val num = caps(s.t.accent, size * 0.74f)
        val title = paint(s.t.ink, size, bold = true)
        val artist = paint(s.t.ink, size, alpha = 165)
        val numW = num.measureText("00") + size * 0.8f
        val gap = size * 0.55f
        for (i in 0 until n) {
            val (name, by) = s.card.tracks[i]
            val base = top + i * rowH + size * 1.15f
            s.c.drawText(String.format(java.util.Locale.ROOT, "%02d", i + 1), x, base, num)
            val room = width - numW
            // The title keeps at least about half the row; the artist has the rest, whole or not at all.
            val titleW = title.measureText(name)
            val artistRoom = room - gap - minOf(titleW, room * 0.52f)
            val fit = if (by.isBlank()) null else CoverArt.fitNames(listOf(by), artistRoom) { str, k -> artist.textSize = size * k; artist.measureText(str) }
                .takeIf { it.more == 0 }
            artist.textSize = size * (fit?.scale ?: 1f)
            val artistW = fit?.let { artist.measureText(it.text) } ?: 0f
            val nameRoom = if (fit != null) room - gap - artistW else room
            val shown = CoverArt.cutAtWord(name, nameRoom, force = true) { title.measureText(it) }
            s.c.drawText(shown, x + numW, base, title)
            if (fit != null) s.c.drawText(fit.text, x + numW + minOf(title.measureText(shown), nameRoom) + gap, base, artist)
        }
        return top + n * rowH
    }

    /**
     * The artists, the ones that appear most first, one a line in capitals (the first one larger
     * when [big] differs from [small]): each whole, set smaller rather than cut, as many as fit
     * above [bottom] (at most [max]), the last line saying how many are not shown ("+N").
     * Returns where they end.
     */
    private fun posterLineup(s: Sheet, x: Float, top: Float, bottom: Float, width: Float, big: Float, small: Float, max: Int = 5, upper: Boolean = true, condensedType: Boolean = true, alpha: Int = 225, align: Paint.Align = Paint.Align.LEFT): Float {
        fun p(i: Int) = (if (condensedType) condensed(s.t.ink, if (i == 0) big else small, if (i == 0) 255 else alpha) else paint(s.t.ink, if (i == 0) big else small, alpha = alpha))
        var rows = 0
        var h = 0f
        while (rows < max && top + h + (if (rows == 0) big else small) * 1.12f <= bottom) { h += (if (rows == 0) big else small) * 1.12f; rows++ }
        val names = s.card.artists.map { if (upper) it.uppercase() else it }
        val stack = CoverArt.stackNames(names, rows, width) { str, i, k -> val q = p(i); q.textSize *= k; q.measureText(str) }
        var y = top
        stack.forEachIndexed { i, row ->
            val q = p(i)
            val full = q.textSize
            q.textSize = full * row.scale
            q.textAlign = align
            s.c.drawText(row.text, x, y + full * 0.86f, q)
            y += full * 1.12f
        }
        return y
    }

    /** The artists on one line from its top at [top]: whole names, as many as fit, then "+N". Returns whether it drew anything. */
    private fun posterNames(s: Sheet, x: Float, top: Float, width: Float, p: TextPaint, align: Paint.Align = Paint.Align.LEFT): Boolean {
        val full = p.textSize
        val fit = CoverArt.fitNames(s.card.artists, width) { str, k -> p.textSize = full * k; p.measureText(str) }
        p.textSize = full
        if (fit.text.isEmpty()) return false
        val ascent = -p.ascent()
        p.textSize = full * fit.scale
        p.textAlign = align
        s.c.drawText(fit.text, x, top + ascent, p)
        p.textAlign = Paint.Align.LEFT
        p.textSize = full
        return true
    }

    /** The QR on a white tile, with its quiet margin, so any reader gets it. */
    private fun posterQr(c: Canvas, symbol: QrCode.Symbol, tr: RectF) {
        c.drawRoundRect(tr, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val quiet = tr.width() * 0.093f
        val module = (tr.width() - 2 * quiet) / symbol.size
        val dark = Paint().apply { color = INK }
        for (yy in 0 until symbol.size) for (xx in 0 until symbol.size) if (symbol[xx, yy]) {
            c.drawRect(tr.left + quiet + xx * module, tr.top + quiet + yy * module, tr.left + quiet + (xx + 1) * module + 0.5f, tr.top + quiet + (yy + 1) * module + 0.5f, dark)
        }
    }

    /** "Scan to open on …" in small capitals, its last line on [bottom]. */
    private fun posterHint(s: Sheet, x: Float, bottom: Float, width: Float, center: Boolean = false) {
        if (width < 220f) return
        val text = s.ctx.getString(R.string.card_poster_scan, s.card.service).uppercase() + if (center) "" else "  →"
        val l = textLayout(text, caps(s.t.accent, 22f), width.toInt(), 2, if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
        l.drawAt(s.c, x, bottom - l.height)
    }

    /** Classic: a dark print in the art's hue. The cover across the sheet, the words under it, the songs beside the QR. */
    private fun posterClassic(s: Sheet) = with(s) {
        val m = if (story) 96f else 72f
        val right = w - m
        val headBase = if (story) 126f else 100f
        line(c, kind, m, headBase, caps(t.accent, 25f), (right - m) * 0.5f)
        line(c, service, right, headBase, caps(t.ink, 25f, alpha = 190), (right - m) * 0.45f, Paint.Align.RIGHT)
        val rule = headBase + 22f
        c.drawRect(m, rule, right, rule + 2f, Paint().apply { color = t.ink; alpha = 55 })
        val artTop = rule + (if (story) 36f else 30f)
        val qr = symbol?.let { RectF(right - 300f, bottom - 300f, right, bottom) }
        val listRight = qr?.let { it.left - 44f } ?: right
        // A short bar of the accent, then the numbers and, when the poster is signed, the name: on one line across the sheet, on two in a column.
        fun numbers(x: Float, y: Float, width: Float): Float {
            c.drawRect(x, y, x + 88f, y + 6f, Paint().apply { color = t.accent })
            var top = y + 30f
            for (l in if (story) listOf(listOf(counts, owner).filter { it.isNotBlank() }.joinToString("  ·  ")) else listOf(counts, owner).filter { it.isNotBlank() }) {
                lineTop(c, l, x, top, caps(t.ink, 24f, alpha = 200), width); top += 38f
            }
            return top - 8f
        }
        if (story) {
            val art = RectF(m, artTop, right, artTop + (right - m))
            posterArt(s, art, 10f)
            var y = art.bottom + 62f
            if (!card.titledArt) y = posterTitle(c, card.title, RectF(m, y, right, y + 200f), paint(t.ink, 88f, bold = true), 2, 88f, 52f) + 24f
            if (posterNames(s, m, y, right - m, paint(t.ink, 34f, alpha = 215))) y += 64f
            y = numbers(m, y, right - m) + 30f
            posterTracks(s, m, y, bottom - (if (qr != null) 76f else 0f), listRight - m, 30f)
        } else {
            val art = RectF(m, artTop, m + 470f, artTop + 470f)
            posterArt(s, art, 8f)
            val colX = art.right + 44f
            var y = art.top + 2f
            if (!card.titledArt) {
                y = posterTitle(c, card.title, RectF(colX, y, right, y + 250f), paint(t.ink, 76f, bold = true), 4, 76f, 38f) + 22f
                if (posterNames(s, colX, y, right - colX, paint(t.ink, 27f, alpha = 215))) y += 27f * 1.2f + 26f
            } else y = posterLineup(s, colX, y, art.bottom - 80f, right - colX, 58f, 42f) + 24f
            numbers(colX, y, right - colX)
            posterTracks(s, m, art.bottom + 40f, bottom - (if (qr != null) 70f else 0f), listRight - m, 27f, max = 5)
        }
        if (qr != null) { posterQr(c, symbol!!, qr); posterHint(s, m, bottom, listRight - m) }
    }

    /** Minimal: ink on paper. The cover alone in the middle, a light title, one line of small capitals. */
    private fun posterMinimal(s: Sheet) = with(s) {
        val cx = w / 2
        val shadow = 0x38000000
        val light = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = t.ink; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val bar = Paint().apply { color = t.accent }
        if (story) {
            val side = if (symbol != null) 780f else 840f
            // Without a QR the cover and its words sit in the middle of the sheet.
            val words = (if (card.titledArt) 0f else 150f) + 90f + (if (owner.isNotBlank()) 42f else 0f) + (if (card.artists.isNotEmpty()) 50f else 0f)
            val top = if (symbol != null) 170f else ((120f + bottom - side - 86f - words) / 2).coerceAtLeast(170f)
            val art = RectF(cx - side / 2, top, cx + side / 2, top + side)
            posterArt(s, art, 4f, shadow)
            val width = 840f
            var y = art.bottom + 86f
            if (!card.titledArt) y = posterTitle(c, card.title, RectF(cx - width / 2, y, cx + width / 2, y + 170f), light, 2, 78f, 44f, align = Paint.Align.CENTER) + 38f
            c.drawRect(cx - 36f, y, cx + 36f, y + 5f, bar); y += 44f
            lineTop(c, listOf(service, counts).filter { it.isNotBlank() }.joinToString("  ·  "), cx, y, caps(t.ink, 24f, alpha = 200), width, Paint.Align.CENTER); y += 46f
            if (owner.isNotBlank()) { lineTop(c, owner, cx, y, caps(t.ink, 22f, alpha = 150, bold = false), width, Paint.Align.CENTER); y += 42f }
            posterNames(s, cx, y + 8f, width, paint(t.ink, 30f, alpha = 170), Paint.Align.CENTER)
            if (symbol != null) {
                val q = RectF(cx - 150f, bottom - 62f - 300f, cx + 150f, bottom - 62f)
                c.drawRoundRect(RectF(q.left - 2f, q.top - 2f, q.right + 2f, q.bottom + 2f), 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.ink; alpha = 40 })
                posterQr(c, symbol, q)
                posterHint(s, cx - 260f, bottom, 520f, center = true)
            }
        } else if (symbol == null) {
            val side = 560f
            val art = RectF(cx - side / 2, 104f, cx + side / 2, 104f + side)
            posterArt(s, art, 4f, shadow)
            val width = 860f
            var y = art.bottom + 64f
            if (!card.titledArt) y = posterTitle(c, card.title, RectF(cx - width / 2, y, cx + width / 2, y + 124f), light, 2, 60f, 34f, align = Paint.Align.CENTER) + 30f
            else y += 20f
            c.drawRect(cx - 36f, y, cx + 36f, y + 5f, bar); y += 38f
            lineTop(c, listOf(service, counts, owner).filter { it.isNotBlank() }.joinToString("  ·  "), cx, y, caps(t.ink, 22f, alpha = 200), width, Paint.Align.CENTER)
        } else {
            val m = 84f
            val right = w - m
            val art = RectF(m, 120f, m + 480f, 600f)
            posterArt(s, art, 4f, shadow)
            val colX = art.right + 48f
            var y = art.top + 4f
            if (!card.titledArt) y = posterTitle(c, card.title, RectF(colX, y, right, y + 250f), light, 4, 64f, 34f) + 30f
            c.drawRect(colX, y, colX + 72f, y + 5f, bar); y += 40f
            for (l in listOf(service, counts, owner).filter { it.isNotBlank() }) { lineTop(c, l, colX, y, caps(t.ink, 22f, alpha = 200), right - colX); y += 40f }
            val q = RectF(right - 300f, bottom - 300f, right, bottom)
            c.drawRoundRect(RectF(q.left - 2f, q.top - 2f, q.right + 2f, q.bottom + 2f), 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.ink; alpha = 40 })
            posterQr(c, symbol, q)
            val leftW = q.left - 44f - m
            posterLineup(s, m, q.top + 4f, bottom - 80f, leftW, 30f, 30f, max = 4, upper = false, condensedType = false, alpha = 185)
            posterHint(s, m, bottom, leftW)
        }
    }

    /** Bold: a gig poster in two inks. The art's brightest colour as the ground, the title huge in capitals, the artists as a line-up. */
    private fun posterBold(s: Sheet) = with(s) {
        val m = if (story) 84f else 72f
        val right = w - m
        val inkPaint = Paint().apply { color = t.ink }
        val barTop = if (story) 84f else 62f
        c.drawRect(m, barTop, right, barTop + 14f, inkPaint)
        val headBase = barTop + 14f + (if (story) 52f else 46f)
        val head = condensed(t.ink, if (story) 32f else 28f).apply { letterSpacing = 0.1f }
        // Whose it is (when signed) and where it lives on the left, the numbers on the right.
        val whose = "${owner.ifBlank { kind }}  ·  $service"
        val countsW = minOf(head.measureText(counts), (right - m) * 0.45f)
        val whoseW = minOf(head.measureText(whose), right - m - countsW - 40f)
        line(c, whose, m, headBase, head, whoseW + 1f)
        line(c, counts, right, headBase, head, right - m - whoseW - 40f, Paint.Align.RIGHT)
        var y = headBase + (if (story) 40f else 30f)
        if (!card.titledArt) {
            y = posterTitle(c, card.title.uppercase(), RectF(m, y, right, y + (if (story) 470f else 250f)), condensed(t.ink, 100f), 3, if (story) 300f else 170f, if (story) 70f else 56f, lineHeight = 0.98f)
            // Capitals have nothing under the line: the gap is measured from it.
            y += if (story) 10f else 2f
        }
        val qr = symbol?.let { RectF(right - 300f, bottom - 300f, right, bottom) }
        if (story) {
            val side = minOf(right - m - 16f, bottom - 350f - y)
            val art = RectF(m, y, m + side, y + side)
            posterArt(s, art, 0f, block = t.ink)
            y = art.bottom + 16f + 44f
            c.drawRect(m, y, right, y + 8f, inkPaint); y += 8f + 30f
            val colRight = qr?.let { it.left - 44f } ?: right
            val end = bottom - (if (qr != null) 76f else 0f)
            y = posterLineup(s, m, y, end, colRight - m, 70f, 48f) + 22f
            posterTracks(s, m, y, end, colRight - m, 28f, max = 6)
            if (qr != null) posterHint(s, m, bottom, colRight - m)
        } else {
            val side = minOf(520f, bottom - 16f - y)
            val art = RectF(m, y, m + side, y + side)
            posterArt(s, art, 0f, block = t.ink)
            val colX = art.right + 16f + 40f
            c.drawRect(colX, art.top, right, art.top + 8f, inkPaint)
            posterLineup(s, colX, art.top + 34f, qr?.let { it.top - 20f } ?: bottom, right - colX, 54f, 38f)
            posterTracks(s, m, art.bottom + 16f + 34f, bottom, side, 25f, max = 5)
        }
        if (qr != null) posterQr(c, symbol!!, qr)
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
     * "Your Wrapped", already formatted and translated: the musical age as the headline (or the
     * song count, when the services did not say when the songs came out) with its line, the top
     * artists (name, songs), the decades (label, share in %), up to three figures (value, label),
     * a few facts (label, value: the oldest song, the last added) and the services read.
     */
    class WrappedCard(
        val eyebrow: String,
        val headline: String,
        val subline: String?,
        val artistsTitle: String,
        val artists: List<Pair<String, String>>,
        val decadesTitle: String,
        val decades: List<Pair<String, Int>>,
        val tiles: List<Pair<String, String>>,
        val facts: List<Pair<String, String>>,
        val servicesTitle: String,
        val services: List<Mark>,
    )

    /** A part of a card laid out top to bottom: its height, and how to draw it with its top at a given y. */
    private class Block(val height: Float, val draw: (Canvas, Float) -> Unit)

    /** Wrapped lines of [text] in [width], at most [maxLines], the last one cut at its end. */
    private fun textLayout(text: String, p: TextPaint, width: Int, maxLines: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(align).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()

    private fun StaticLayout.drawAt(c: Canvas, x: Float, y: Float) { c.save(); c.translate(x, y); draw(c); c.restore() }

    /** One line whose top (not baseline) is at [top]. */
    private fun lineTop(c: Canvas, text: String, x: Float, top: Float, p: TextPaint, maxWidth: Float, align: Paint.Align = Paint.Align.LEFT) =
        line(c, text, x, top - p.ascent(), p, maxWidth, align)

    private fun captionPaint(s: Float, story: Boolean) =
        paint(Color.WHITE, (if (story) 30f else 24f) * s, bold = true, alpha = 190).apply { letterSpacing = 0.1f }

    /** A section's caption above [body]: one block. */
    private fun captioned(title: String, x: Float, width: Float, s: Float, story: Boolean, body: Block): Block {
        val cap = captionPaint(s, story)
        val capH = cap.fontSpacing + (if (story) 14f else 8f) * s
        return Block(capH + body.height) { c, y ->
            lineTop(c, title.uppercase(), x, y, cap, width)
            body.draw(c, y + capH)
        }
    }

    private fun wrappedHeader(card: WrappedCard, x: Float, width: Float, s: Float, story: Boolean, eyebrow: Boolean = true): Block {
        val eb = paint(Color.WHITE, (if (story) 38f else 30f) * s, bold = true, alpha = 215).apply { letterSpacing = 0.12f }
        // The headline on one line where it fits at three quarters of its size or more, else on two.
        val size = (if (story) 112f else 76f) * s
        val hp = paint(Color.WHITE, size, bold = true)
        while (hp.textSize > size * 0.75f && hp.measureText(card.headline) > width) hp.textSize *= 0.95f
        val head = textLayout(card.headline, hp, width.toInt(), 2)
        val sub = card.subline?.let { textLayout(it, paint(Color.WHITE, (if (story) 44f else 32f) * s, alpha = 225), width.toInt(), if (story) 2 else 1) }
        val gap1 = (if (story) 16f else 10f) * s
        val gap2 = (if (story) 12f else 6f) * s
        val ebH = if (eyebrow) eb.fontSpacing + gap1 else 0f
        val h = ebH + head.height + (sub?.let { gap2 + it.height } ?: 0f)
        return Block(h) { c, y ->
            if (eyebrow) lineTop(c, card.eyebrow.uppercase(), x, y, eb, width)
            var yy = y + ebH
            head.drawAt(c, x, yy); yy += head.height
            sub?.let { it.drawAt(c, x, yy + gap2) }
        }
    }

    /** The top artists, numbered, the first one bigger; a long name is cut at its end, the count goes first when room is short. */
    private fun wrappedArtists(card: WrappedCard, x: Float, width: Float, s: Float, story: Boolean, max: Int = 5): Block? {
        val rows = card.artists.take(max)
        if (rows.isEmpty()) return null
        class Row(val name: String, val count: String, val nameP: TextPaint, val numP: TextPaint, val cntP: TextPaint, val h: Float)
        val laid = rows.mapIndexed { i, (name, count) ->
            val size = (if (i == 0) (if (story) 60f else 40f) else (if (story) 40f else 28f)) * s
            val nameP = paint(Color.WHITE, size, bold = true, alpha = if (i == 0) 255 else 235)
            val numP = paint(Color.WHITE, size, bold = true, alpha = 140)
            val cntP = paint(Color.WHITE, (if (story) 30f else 22f) * s, alpha = 185)
            Row(name, count, nameP, numP, cntP, nameP.fontSpacing * (if (i == 0) 1.18f else 1.1f))
        }
        val numW = laid.maxOf { it.numP.measureText("${rows.size}") } + 20f * s
        return captioned(card.artistsTitle, x, width, s, story, Block(laid.sumOf { it.h.toDouble() }.toFloat()) { c, y ->
            var top = y
            laid.forEachIndexed { i, r ->
                val base = top - r.nameP.ascent()
                c.drawText("${i + 1}", x, base, r.numP)
                val countW = r.cntP.measureText(r.count)
                var nameMax = width - numW - countW - 24f * s
                val showCount = r.count.isNotEmpty() && nameMax >= width * 0.45f
                if (!showCount) nameMax = width - numW
                line(c, r.name, x + numW, base, r.nameP, nameMax)
                if (showCount) line(c, r.count, x + width, base, r.cntP, countW + 1f, Paint.Align.RIGHT)
                top += r.h
            }
        })
    }

    /** The decades as bars: "2010s ▇▇▇▇▁▁ 48%", the share of the dated songs. */
    private fun wrappedDecades(card: WrappedCard, x: Float, width: Float, s: Float, story: Boolean, max: Int = 4): Block? {
        val rows = card.decades.take(max)
        if (rows.isEmpty()) return null
        val lab = paint(Color.WHITE, (if (story) 34f else 24f) * s, bold = true)
        val pct = paint(Color.WHITE, (if (story) 34f else 24f) * s, bold = true, alpha = 230)
        val labW = minOf(rows.maxOf { lab.measureText(it.first) }, width * 0.38f)
        val pctW = pct.measureText("100%")
        val barH = (if (story) 26f else 18f) * s
        val rowH = maxOf(lab.fontSpacing, barH) + (if (story) 20f else 12f) * s
        val gap = 18f * s
        return captioned(card.decadesTitle, x, width, s, story, Block(rowH * rows.size) { c, y ->
            rows.forEachIndexed { i, (label, share) ->
                val cy = y + i * rowH + rowH / 2
                val base = cy - (lab.ascent() + lab.descent()) / 2
                line(c, label, x, base, lab, labW)
                val left = x + labW + gap
                val right = x + width - pctW - gap
                if (right - left > barH) {
                    val track = RectF(left, cy - barH / 2, right, cy + barH / 2)
                    c.drawRoundRect(track, barH / 2, barH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
                    val fill = RectF(left, track.top, left + maxOf(barH, (right - left) * share.coerceIn(0, 100) / 100f), track.bottom)
                    c.drawRoundRect(fill, barH / 2, barH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 235 })
                }
                line(c, "$share%", x + width, base, pct, pctW, Paint.Align.RIGHT)
            }
        })
    }

    /** Up to three figures side by side on glass panels: the value as big as it fits, its label under it. */
    private fun wrappedTiles(card: WrappedCard, x: Float, width: Float, s: Float, story: Boolean): Block? {
        val tiles = card.tiles.take(3)
        if (tiles.isEmpty()) return null
        val gap = (if (story) 22f else 16f) * s
        val pw = (width - gap * (tiles.size - 1)) / tiles.size
        val pad = (if (story) 22f else 14f) * s
        val values = tiles.map { (v, _) -> fit(v, paint(Color.WHITE, 10f, bold = true), (if (story) 68f else 48f) * s, pw - 2 * pad) }
        val labels = tiles.map { (_, l) -> textLayout(l, paint(Color.WHITE, (if (story) 27f else 20f) * s, alpha = 215), (pw - 2 * pad).toInt(), 2, Layout.Alignment.ALIGN_CENTER) }
        val valueH = (if (story) 68f else 48f) * s * 1.2f
        val ph = pad * 1.4f + valueH + 4f * s + labels.maxOf { it.height } + pad
        return Block(ph) { c, y ->
            tiles.indices.forEach { i ->
                val left = x + i * (pw + gap)
                val r = RectF(left, y, left + pw, y + ph)
                panel(c, r, 30f * s)
                val vp = values[i]; vp.textAlign = Paint.Align.CENTER
                c.drawText(tiles[i].first, r.centerX(), y + pad * 1.4f + valueH * 0.8f, vp)
                vp.textAlign = Paint.Align.LEFT
                labels[i].drawAt(c, left + pad, y + pad * 1.4f + valueH + 4f * s)
            }
        }
    }

    /** A few facts on one glass panel: the label small and spaced, the value under it (two lines at most, cut at the end). */
    private fun wrappedFacts(facts: List<Pair<String, String>>, x: Float, width: Float, s: Float, story: Boolean): Block? {
        if (facts.isEmpty()) return null
        val pad = (if (story) 30f else 20f) * s
        val cap = captionPaint(s, story)
        val values = facts.map { (_, v) -> textLayout(v, paint(Color.WHITE, (if (story) 36f else 26f) * s, bold = true), (width - 2 * pad).toInt(), if (story) 2 else 1) }
        val between = (if (story) 22f else 12f) * s
        val each = values.map { cap.fontSpacing + 4f * s + it.height }
        val h = 2 * pad + each.sum() + between * (facts.size - 1)
        return Block(h) { c, y ->
            panel(c, RectF(x, y, x + width, y + h), 30f * s)
            var yy = y + pad
            facts.forEachIndexed { i, (label, _) ->
                lineTop(c, label.uppercase(), x + pad, yy, cap, width - 2 * pad)
                values[i].drawAt(c, x + pad, yy + cap.fontSpacing + 4f * s)
                yy += each[i] + between
            }
        }
    }

    /** The services read: their marks in a row (two at most) and, when [names], their names packed whole in up to two lines. */
    private fun wrappedServices(card: WrappedCard, x: Float, width: Float, s: Float, story: Boolean, names: Boolean): Block? {
        if (card.services.isEmpty()) return null
        val d = (if (story) 72f else 52f) * s
        val gap = (if (story) 18f else 12f) * s
        val perRow = ((width + gap) / (d + gap)).toInt().coerceAtLeast(1)
        val markRows = card.services.chunked(perRow).take(if (story) 2 else 1)
        val np = paint(Color.WHITE, (if (story) 30f else 22f) * s, alpha = 215)
        val lines = if (names) com.xlollx.songport.sync.Wrapped.packNames(card.services.map { it.name }, width, 2, { np.measureText(it) }) else emptyList()
        val marksH = markRows.size * d + (markRows.size - 1) * gap
        val namesTop = marksH + (if (lines.isEmpty()) 0f else 14f * s)
        val h = namesTop + lines.size * np.fontSpacing
        return captioned(card.servicesTitle, x, width, s, story, Block(h) { c, y ->
            markRows.forEachIndexed { r, row ->
                row.forEachIndexed { i, m -> mark(c, m, x + i * (d + gap) + d / 2, y + r * (d + gap) + d / 2, d) }
            }
            lines.forEachIndexed { i, l -> lineTop(c, l, x, y + namesTop + i * np.fontSpacing, np, width) }
        })
    }

    /**
     * The art of a Wrapped: a cover drawn on the phone ([GeneratedCovers], from the eyebrow, the top
     * artists and their covers) at the top of the story, and its palette for the background of
     * both formats. Without art the card keeps the app's own purple.
     */
    class WrappedArt(val style: GeneratedCovers.Style, val input: GeneratedCovers.Input, val variant: Int = 0) {
        val palette: IntArray by lazy { GeneratedCovers.palette(input, variant) }
    }

    /**
     * The background in [pal]'s colours: its deep shade to its darkest along the diagonal, two soft
     * glows (its second colour at the top, its accent low on the left), a dark wash over all. The
     * glows are darkened first ([CoverArt.deepen]), so white text reads on them whatever the covers.
     */
    private fun background(c: Canvas, w: Int, h: Int, pal: IntArray) {
        val wf = w.toFloat(); val hf = h.toFloat()
        c.drawRect(0f, 0f, wf, hf, Paint().apply {
            shader = LinearGradient(0f, 0f, wf, hf, intArrayOf(pal[0], CoverArt.mix(pal[0], pal[4], 0.5f), pal[4]), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        fun glow(cx: Float, cy: Float, r: Float, color: Int, a: Int) = c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val k = CoverArt.deepen(color, 5.0) and 0x00FFFFFF
            shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(k or (a shl 24), k or ((a / 2) shl 24), k), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        glow(wf * 0.5f, hf * 0.18f, maxOf(wf, hf) * 0.6f, pal[1], 200)
        glow(wf * 0.05f, hf * 0.85f, maxOf(wf, hf) * 0.5f, pal[3], 150)
        c.drawRect(0f, 0f, wf, hf, Paint().apply { color = INK; alpha = 40 })
    }

    /** The drawn cover, [side] wide, centred, with rounded corners, a soft shadow and a faint edge. */
    private fun wrappedArtBlock(art: WrappedArt, left: Float, side: Float): Block = Block(side) { c, y ->
        val r = RectF(left, y, left + side, y + side)
        val radius = side * 0.06f
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; setShadowLayer(side * 0.05f, 0f, side * 0.02f, 0x8C000000.toInt()) })
        val bmp = GeneratedCovers.draw(art.style, art.input, art.variant, side.toInt().coerceAtLeast(1))
        c.save(); c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
        c.drawBitmap(bmp, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f })
    }

    /** Two blocks side by side (each already placed at its own x), as tall as the taller one. */
    private fun columns(left: Block, right: Block): Block =
        Block(maxOf(left.height, right.height)) { c, y -> left.draw(c, y); right.draw(c, y) }

    /**
     * "Your Wrapped" as a card. The story, top to bottom: the eyebrow, the headline and its line,
     * the top five artists, the decades as bars, three figures, the oldest song and the last one
     * added, the services' marks with their names, the footer. The square puts the artists and the
     * decades side by side and leaves the facts and the names out. Each part is measured first:
     * when all of them do not fit, the card is drawn a little smaller, then the least telling parts
     * go (the facts, the names, the figures); what is left is spread over the height.
     *
     * With [art], the background takes the art's palette, the story opens on the drawn cover
     * (which says the eyebrow, so the header starts at the headline; the cover gets smaller before
     * a part goes, and goes last) and the square shows it as a thumbnail beside the header (the
     * first thing to go when room is short).
     */
    fun wrapped(ctx: Context, card: WrappedCard, format: Format, art: WrappedArt? = null): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val story = format == Format.STORY
        if (art != null) background(c, w, h, art.palette) else background(c, w, h)
        val margin = if (story) 84f else 72f
        val inner = w - 2 * margin
        val top = if (story) 120f else 64f
        val bottom = h - (if (story) 130f else 104f)
        val avail = bottom - top
        val minGap = if (story) 36f else 22f
        val maxGap = if (story) 84f else 44f

        fun build(s: Float, level: Int, artSide: Float): List<Block> = buildList {
            when {
                art == null || artSide <= 0f -> add(wrappedHeader(card, margin, inner, s, story))
                // The story's art says the eyebrow in big letters: the header then starts at the headline.
                story -> { add(wrappedArtBlock(art, (w - artSide) / 2, artSide)); add(wrappedHeader(card, margin, inner, s, story, eyebrow = false)) }
                // The square's is a thumbnail at the right of the header, as tall as it.
                else -> {
                    val gap = 40f * s
                    val head = wrappedHeader(card, margin, inner - artSide * s - gap, s, story)
                    val side = head.height.coerceIn(artSide * s * 0.8f, artSide * s)
                    add(columns(head, wrappedArtBlock(art, w - margin - side, side)))
                }
            }
            if (story) {
                wrappedArtists(card, margin, inner, s, story)?.let(::add)
                wrappedDecades(card, margin, inner, s, story)?.let(::add)
                if (level < 4) wrappedTiles(card, margin, inner, s, story)?.let(::add)
                val facts = when (level) { 0 -> card.facts; 1 -> card.facts.take(1); else -> emptyList() }
                wrappedFacts(facts, margin, inner, s, story)?.let(::add)
                wrappedServices(card, margin, inner, s, story, names = level < 3)?.let(::add)
            } else {
                val half = (inner - 48f) / 2
                val a = wrappedArtists(card, margin, if (card.decades.isEmpty()) inner else half, s, story)
                val d = wrappedDecades(card, if (a == null) margin else margin + half + 48f, if (a == null) inner else half, s, story)
                when {
                    a != null && d != null -> add(columns(a, d))
                    a != null -> add(a)
                    d != null -> add(d)
                }
                if (level < 2) wrappedTiles(card, margin, inner, s, story)?.let(::add)
                if (level < 1) wrappedServices(card, margin, inner, s, story, names = false)?.let(::add)
            }
        }
        fun height(b: List<Block>, s: Float) = b.sumOf { it.height.toDouble() }.toFloat() + minGap * s * (b.size - 1)

        var blocks = emptyList<Block>()
        var scale = 1f
        // The art (story only) gets smaller before the least telling parts go, and goes last of all.
        // In the square, the art goes before anything else does.
        val levels = 0..(if (story) 4 else 2)
        val tries = when {
            art == null -> levels.map { it to 0f }
            story -> levels.flatMap { l -> listOf(560f, 480f, 400f).map { l to it } } + levels.map { it to 0f }
            else -> listOf(0 to 250f) + levels.map { it to 0f }
        }
        search@ for ((level, artSide) in tries) for (s in floatArrayOf(1f, 0.94f, 0.88f, 0.82f)) {
            blocks = build(s, level, artSide); scale = s
            if (height(blocks, s) <= avail) break@search
        }
        val used = blocks.sumOf { it.height.toDouble() }.toFloat()
        val gap = if (blocks.size > 1) ((avail - used) / (blocks.size - 1)).coerceIn(minGap * scale, maxGap) else 0f
        val total = used + gap * (blocks.size - 1)
        // Short content (no dates, few artists) sits in the middle rather than stuck to the top.
        var y = top + ((avail - total) / 2).coerceAtLeast(0f)
        // Nothing drawn below the footer's line, whatever the content.
        c.save(); c.clipRect(0f, 0f, w.toFloat(), bottom + 20f)
        for (b in blocks) { b.draw(c, y); y += b.height + gap }
        c.restore()
        footer(ctx, c, w, h)
        return bmp
    }

    /**
     * What a roast card shows: the eyebrow, the level's badge ("Savage 🔥🔥🔥"), the playlist's name
     * and the AI's words; [cover] is the playlist's own picture (null: one is drawn from the title),
     * [covers] the album covers of its top artists (empty when none loaded: the app's purple then).
     */
    class RoastCard(
        val eyebrow: String, val badge: String?, val title: String, val text: String,
        val cover: Bitmap?, val covers: List<Bitmap>, val artists: List<String>,
    )

    /** A picture [side] wide at [left], rounded, with a soft shadow and a faint edge (as the Wrapped's art). */
    private fun artTile(c: Canvas, bmp: Bitmap?, left: Float, top: Float, side: Float, seed: Int = 0) {
        val r = RectF(left, top, left + side, top + side)
        val radius = side * 0.06f
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; setShadowLayer(side * 0.05f, 0f, side * 0.02f, 0x8C000000.toInt()) })
        cover(c, bmp, r, radius, seed)
        c.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f })
    }

    /** The level's badge: a small pill, centred on [cx] or starting at [left]; returns its height. */
    private fun badgeBlock(text: String, size: Float, x: Float, maxWidth: Float, centered: Boolean): Block {
        val p = paint(Color.WHITE, size, bold = true)
        val padX = size * 0.7f
        val tw = minOf(p.measureText(text), maxWidth - 2 * padX)
        val h = size * 1.8f
        return Block(h) { c, y ->
            val bw = tw + 2 * padX
            val left = if (centered) x - bw / 2 else x
            panel(c, RectF(left, y, left + bw, y + h), h / 2)
            line(c, text, left + padX, y + h / 2 + size * 0.36f, p, tw + 1f)
        }
    }

    /** "Roast of a playlist": the playlist's cover, the eyebrow and level, its name, the AI's words and its top artists' covers. */
    fun roast(ctx: Context, card: RoastCard, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val story = format == Format.STORY
        val input = GeneratedCovers.Input(card.title, card.artists, card.covers)
        // The covers' colours when there are covers, else the app's purple (offline, nothing found).
        if (card.covers.isNotEmpty()) background(c, w, h, GeneratedCovers.palette(input)) else background(c, w, h)
        // No picture of its own (liked songs, a service that lists none): one drawn from the title.
        val playlistCover = card.cover ?: GeneratedCovers.draw(GeneratedCovers.Style.GRADIENT, input, 0, 720)
        val margin = 72f
        val inner = w - 2 * margin
        val top = if (story) 120f else 64f
        val bottom = h - (if (story) 130f else 104f)
        val avail = bottom - top
        val gap = if (story) 44f else 28f
        val tileGap = if (story) 28f else 22f
        val maxRows = if (story) 2 else 1
        val maxTile = if (story) 300f else 200f
        val minTile = if (story) 150f else 100f

        fun header(s: Float): Block {
            val eb = paint(Color.WHITE, (if (story) 36f else 28f) * s, bold = true, alpha = 215).apply { letterSpacing = 0.1f }
            val titleP = paint(Color.WHITE, (if (story) 64f else 50f) * s, bold = true)
            if (story) {
                val side = 380f * s
                val badge = card.badge?.let { badgeBlock(it, 32f * s, w / 2f, inner, centered = true) }
                val bGap = if (badge != null) badge.height + 24f * s else 6f * s
                val title = textLayout(card.title, titleP, inner.toInt(), 2, Layout.Alignment.ALIGN_CENTER)
                val ebH = eb.textSize * 1.3f
                val hh = side + 40f * s + ebH + 18f * s + bGap + title.height
                return Block(hh) { cv, y ->
                    artTile(cv, playlistCover, (w - side) / 2, y, side)
                    var yy = y + side + 40f * s
                    line(cv, card.eyebrow.uppercase(), w / 2f, yy + eb.textSize, eb, inner, Paint.Align.CENTER)
                    yy += ebH + 18f * s
                    badge?.draw(cv, yy); yy += bGap
                    title.drawAt(cv, margin, yy)
                }
            }
            // The square: the cover at the left, the words beside it.
            val side = 200f * s
            val tx = margin + side + 36f * s
            val tw = w - margin - tx
            val badge = card.badge?.let { badgeBlock(it, 26f * s, tx, tw, centered = false) }
            val bGap = if (badge != null) badge.height + 16f * s else 4f * s
            val title = textLayout(card.title, titleP, tw.toInt(), 2)
            val ebH = eb.textSize * 1.3f
            val textH = ebH + 14f * s + bGap + title.height
            return Block(maxOf(side, textH)) { cv, y ->
                artTile(cv, playlistCover, margin, y, side)
                var yy = y + ((side - textH) / 2).coerceAtLeast(0f)
                line(cv, card.eyebrow.uppercase(), tx, yy + eb.textSize, eb, tw)
                yy += ebH + 14f * s
                badge?.draw(cv, yy); yy += bGap
                title.drawAt(cv, tx, yy)
            }
        }

        fun textPanel(s: Float, maxLines: Int): Block {
            val body = paint(Color.WHITE, (if (story) 44f else 34f) * s)
            val pad = 48f * s
            val layout = StaticLayout.Builder.obtain(card.text, 0, card.text.length, body, (inner - 2 * pad).toInt())
                .setMaxLines(maxLines.coerceAtLeast(1)).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()
            return Block(layout.height + 2 * pad) { cv, y ->
                panel(cv, RectF(margin, y, w - margin, y + layout.height + 2 * pad))
                layout.drawAt(cv, margin + pad, y + pad)
            }
        }

        // The covers in up to [maxRows] rows, as big as [room] lets them be; null when they would be too small.
        fun coversBlock(room: Float): Block? {
            val n = card.covers.size
            if (n == 0) return null
            var best = 0f; var bestCols = 1; var bestRows = 1
            for (rows in 1..minOf(maxRows, n)) {
                val cols = (n + rows - 1) / rows
                val tile = minOf((inner - (cols - 1) * tileGap) / cols, (room - (rows - 1) * tileGap) / rows, maxTile)
                if (tile > best + 0.5f) { best = tile; bestCols = cols; bestRows = rows }
            }
            if (best < minTile) return null
            val shown = card.covers.take(bestCols * bestRows)
            return Block(bestRows * best + (bestRows - 1) * tileGap) { cv, y ->
                shown.chunked(bestCols).forEachIndexed { r, row ->
                    val rowW = row.size * best + (row.size - 1) * tileGap
                    row.forEachIndexed { i, b -> artTile(cv, b, (w - rowW) / 2 + i * (best + tileGap), y + r * (best + tileGap), best, i) }
                }
            }
        }

        // Smaller type and cover until the text and a row of covers fit; past that the text is cut.
        var blocks = emptyList<Block>()
        for (s in floatArrayOf(1f, 0.92f, 0.84f, 0.76f)) {
            val head = header(s)
            val bodyP = paint(Color.WHITE, (if (story) 44f else 34f) * s)
            val room = avail - head.height - gap - 96f * s
            val lines = (room / (bodyP.fontSpacing * 1.05f)).toInt().coerceAtLeast(3)
            val text = textPanel(s, lines)
            val rest = avail - head.height - text.height - 2 * gap
            val art = coversBlock(rest)
            blocks = listOfNotNull(head, text, art)
            if (art != null || card.covers.isEmpty()) break
        }
        val used = blocks.sumOf { it.height.toDouble() }.toFloat() + gap * (blocks.size - 1)
        // Short content sits in the middle rather than stuck to the top.
        var y = top + ((avail - used) / 2).coerceAtLeast(0f)
        c.save(); c.clipRect(0f, 0f, w.toFloat(), bottom + 20f)
        for (b in blocks) { b.draw(c, y); y += b.height + gap }
        c.restore()
        footer(ctx, c, w, h)
        return bmp
    }

    // ------------------------------------------------------------------ two subscriptions

    /**
     * What the "Two subscriptions?" card shows: the two services by their plain names, how much of
     * the music is on both, and the three counts under their labels. No prices: those stay on the phone.
     */
    class OverlapCard(
        val eyebrow: String, val nameA: String, val nameB: String, val percent: Int, val caption: String,
        val venn: com.xlollx.songport.sync.LibraryOverlap.Venn,
        /** Only on A, on both, only on B: the figure and what it counts. */
        val stats: List<Pair<String, String>>,
    )

    private val OVERLAP_PALETTE = intArrayOf(0xFF1B5E20.toInt(), 0xFF2E7D32.toInt(), 0xFF00796B.toInt())

    /**
     * The two circles of [v] in a box [width] by [height] centred on ([cx], [cy]): the first filled
     * and drawn with a whole line, the second with a dashed one, so they are told apart without colour.
     */
    private fun venn(c: Canvas, v: com.xlollx.songport.sync.LibraryOverlap.Venn, cx: Float, cy: Float, width: Float, height: Float) {
        val k = minOf(width / v.span, height / (2 * maxOf(v.rA, v.rB))).toFloat()
        val ax = cx + (v.centreA * k).toFloat(); val bx = cx + (v.centreB * k).toFloat()
        val ra = (v.rA * k).toFloat(); val rb = (v.rB * k).toFloat()
        c.drawCircle(ax, cy, ra, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 70 })
        c.drawCircle(bx, cy, rb, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 110 })
        c.drawCircle(ax, cy, ra, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f })
        c.drawCircle(bx, cy, rb, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(22f, 16f), 0f)
        })
    }

    fun overlap(ctx: Context, card: OverlapCard, format: Format): Bitmap {
        val w = format.width; val h = format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), OVERLAP_PALETTE, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = INK; alpha = 70 })
        val margin = 72f
        val story = format == Format.STORY
        var y = if (story) 220f else 96f
        val eyebrow = paint(Color.WHITE, 34f, alpha = 210)
        line(c, card.eyebrow.uppercase(), w / 2f, y, eyebrow, w - 2 * margin, Paint.Align.CENTER)
        y += if (story) 90f else 50f
        // The two circles, then the names under them: the first on the left, the second on the right.
        val vennH = if (story) 520f else 330f
        venn(c, card.venn, w / 2f, y + vennH / 2, w - 2 * margin - 120f, vennH)
        y += vennH + (if (story) 80f else 56f)
        val names = paint(Color.WHITE, 40f, bold = true)
        val half = (w - 2 * margin - 80f) / 2
        line(c, card.nameA, margin + half, y, names, half, Paint.Align.RIGHT)
        val cross = paint(Color.WHITE, 40f, alpha = 170); cross.textAlign = Paint.Align.CENTER
        c.drawText("+", w / 2f, y, cross)
        line(c, card.nameB, w - margin - half, y, names, half, Paint.Align.LEFT)
        y += if (story) 260f else 190f
        val score = paint(Color.WHITE, if (story) 260f else 180f, bold = true)
        score.textAlign = Paint.Align.CENTER
        c.drawText("${card.percent}%", w / 2f, y, score)
        y += if (story) 80f else 60f
        line(c, card.caption, w / 2f, y, paint(Color.WHITE, 38f, alpha = 225), w - 2 * margin, Paint.Align.CENTER)
        y += if (story) 100f else 44f
        if (card.stats.isNotEmpty()) {
            val rect = RectF(margin, y, w - margin, y + (if (story) 200f else 150f))
            panel(c, rect)
            val col = rect.width() / card.stats.size
            card.stats.forEachIndexed { i, (value, label) ->
                val x = rect.left + col * i + col / 2
                line(c, value, x, rect.top + rect.height() * 0.46f, paint(Color.WHITE, if (story) 60f else 50f, bold = true), col - 24f, Paint.Align.CENTER)
                line(c, label, x, rect.top + rect.height() * 0.78f, paint(Color.WHITE, 28f, alpha = 210), col - 24f, Paint.Align.CENTER)
            }
        }
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

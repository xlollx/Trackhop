package com.xlollx.songport.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The arithmetic behind the covers drawn on the phone (no AI, nothing sent anywhere): a stable seed
 * from the playlist's name, a palette from its covers' pixels or from that seed, a text colour
 * that reads on a background, and a title fitted to a box (largest size, two or three lines,
 * never cut off). Colours are ARGB ints; the drawing itself is the app's.
 */
object CoverArt {

    /** A seed that is the same for the same name on every device and every run (FNV-1a, 64 bit), varied by [variant]. */
    fun seed(name: String, variant: Int = 0): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (ch in name.trim().lowercase()) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        return h xor (variant.toLong() * -0x61c8864680b583ebL)
    }

    // ------------------------------------------------------------------ colour

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    /** Hue in degrees, saturation and lightness 0..1, to an opaque colour. */
    fun hsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return argb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    /** Hue (degrees), saturation and lightness (0..1) of [c]. */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) { r -> (g - b) / d + (if (g < b) 6f else 0f); g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return floatArrayOf(h, s, l)
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    /** WCAG contrast ratio between two colours, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    const val WHITE = -0x1 // 0xFFFFFFFF
    const val INK = -0xebede6 // 0xFF14121A

    /** White or near-black, whichever reads better on [background]. */
    fun textOn(background: Int): Int = if (contrast(WHITE, background) >= contrast(INK, background)) WHITE else INK

    /** [c], darkened (same hue) until white text on it reaches [ratio] contrast. */
    fun deepen(c: Int, ratio: Double = 4.6): Int {
        val h = toHsl(c)
        var l = h[2]
        var out = c
        while (contrast(WHITE, out) < ratio && l > 0.02f) { l -= 0.02f; out = hsl(h[0], h[1], l) }
        return out
    }

    /** [c] mixed with [other] by [t] (0 = c, 1 = other). */
    fun mix(c: Int, other: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(a: Int, b: Int) = (a + (b - a) * k).toInt()
        return argb(m(red(c), red(other)), m(green(c), green(other)), m(blue(c), blue(other)))
    }

    /**
     * Five colours from the playlist's name alone (no covers): a hue picked by the seed, two
     * neighbours, a deep shade for the background and an accent across the wheel. Deep enough
     * for white text on the first one.
     */
    fun namePalette(name: String, variant: Int = 0): IntArray {
        val rnd = java.util.Random(seed(name, variant))
        val h = rnd.nextInt(360).toFloat()
        val spread = 25f + rnd.nextInt(30)
        return intArrayOf(
            deepen(hsl(h, 0.62f, 0.34f)),
            hsl(h + spread, 0.70f, 0.50f),
            hsl(h - spread, 0.66f, 0.44f),
            hsl(h + 180f + rnd.nextInt(40) - 20, 0.75f, 0.58f),
            hsl(h, 0.55f, 0.14f),
        )
    }

    /**
     * The main colours of some pictures' pixels (ARGB, transparent ones ignored), most present
     * first, at most [k], none too close to one already chosen. Colourful pixels count more than
     * greys, so a cover with a red logo on white gives red rather than white.
     */
    fun dominant(pixels: IntArray, k: Int = 5): IntArray {
        if (pixels.isEmpty() || k <= 0) return IntArray(0)
        // 4 bits per channel: 4096 boxes, each keeping its pixel sum and a weight.
        val weight = DoubleArray(4096); val sr = LongArray(4096); val sg = LongArray(4096); val sb = LongArray(4096); val n = IntArray(4096)
        for (p in pixels) {
            if (alpha(p) < 128) continue
            val r = red(p); val g = green(p); val b = blue(p)
            val box = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val s = toHsl(p)[1]
            weight[box] += 0.35 + s
            sr[box] += r.toLong(); sg[box] += g.toLong(); sb[box] += b.toLong(); n[box]++
        }
        val boxes = (0 until 4096).filter { n[it] > 0 }.sortedByDescending { weight[it] }
        val out = ArrayList<Int>()
        for (box in boxes) {
            val c = argb((sr[box] / n[box]).toInt(), (sg[box] / n[box]).toInt(), (sb[box] / n[box]).toInt())
            if (out.any { distance(it, c) < 60.0 }) continue
            out += c
            if (out.size == k) break
        }
        return out.toIntArray()
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = (red(a) - red(b)).toDouble(); val dg = (green(a) - green(b)).toDouble(); val db = (blue(a) - blue(b)).toDouble()
        return sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db) / 3.0
    }

    /**
     * The five colours a drawn cover uses: from [sampled] (the covers' main colours) when there
     * are any, the name's own palette otherwise. The first is always a deep shade (white text reads
     * on it), the fourth a bright accent; missing colours come from the name's palette. The
     * [variant] turns the order a little, so "another version" changes the look.
     */
    fun palette(sampled: IntArray, name: String, variant: Int = 0): IntArray {
        val fallback = namePalette(name, variant)
        if (sampled.isEmpty()) return fallback
        val rot = Math.floorMod(variant, sampled.size)
        val cs = (sampled.drop(rot) + sampled.take(rot)).toMutableList()
        while (cs.size < 4) cs += fallback[cs.size]
        val vivid = cs.maxByOrNull { toHsl(it)[1] * (1f - abs(toHsl(it)[2] - 0.55f)) } ?: cs[0]
        val base = cs[0]
        val hb = toHsl(base)
        val deep = deepen(hsl(hb[0], min(hb[1], 0.7f), min(hb[2], 0.30f).coerceAtLeast(0.12f)))
        val hv = toHsl(vivid)
        val accent = hsl(hv[0], max(hv[1], 0.55f), hv[2].coerceIn(0.45f, 0.65f))
        val darkest = hsl(hb[0], min(hb[1], 0.5f), 0.10f)
        return intArrayOf(deep, cs[1], cs[2], accent, darkest)
    }

    // ------------------------------------------------------------------ title

    /** A title laid out: the text size and its lines. [cut] when it did not fit whole even at the smallest size. */
    class Fit(val size: Float, val lines: List<String>, val cut: Boolean)

    /** How far under [fitTitle]'s smallest size a title still shrinks so that its longest word fits whole. */
    const val WORD_FLOOR = 0.7f

    /**
     * [text] at the largest size from [maxSize] down to [minSize] at which it wraps into at most
     * [maxLines] lines no wider than [maxWidth], whose height (lines × size × [lineHeight]) fits in
     * [maxHeight]. Lines break only where [atoms] allows (spaces, after "/", "-", "&", "+", between
     * CJK characters), and are balanced: as even as the same number of lines allows. A word wider
     * than the box shrinks the title further (to [WORD_FLOOR] of [minSize]) before it is ever split.
     * When the words do not fit even then, the last line ends in "…" after a whole word.
     * [measure] gives a string's width at a size.
     */
    fun fitTitle(
        text: String, maxWidth: Float, maxHeight: Float, maxLines: Int, maxSize: Float, minSize: Float,
        lineHeight: Float = 1.08f, measure: (String, Float) -> Float,
    ): Fit {
        val clean = text.trim().replace(Regex("\\s+"), " ").ifEmpty { "♪" }
        val parts = atoms(clean)
        fun widest(size: Float) = parts.maxOf { measure(it.text, size) }
        var size = maxSize
        while (true) {
            val lines = wrap(clean, maxWidth) { measure(it, size) }
            if (lines.size <= maxLines && lines.size * size * lineHeight <= maxHeight && widest(size) <= maxWidth) {
                return Fit(size, balance(clean, lines.size, maxWidth) { measure(it, size) }, false)
            }
            if (size <= minSize) break
            size = max(minSize, size * 0.93f)
        }
        // A word wider than the line: smaller still, down to the floor, rather than split.
        val floor = minSize * WORD_FLOOR
        while (size > floor && widest(size) > maxWidth) size = max(floor, size * 0.95f)
        val lines = wrap(clean, maxWidth) { measure(it, size) }
        if (lines.size <= maxLines && lines.size * size * lineHeight <= maxHeight) return Fit(size, balance(clean, lines.size, maxWidth) { measure(it, size) }, false)
        // As many lines as the box takes, the last one cut with "…" after a whole word.
        val room = max(1, min(maxLines, (maxHeight / (size * lineHeight)).toInt()))
        val kept = lines.take(room).toMutableList()
        val rest = lines.drop(room - 1).reduce { a, b -> if (a.isEmpty() || a.last() in "/-&+" || cjk(a.last()) || cjk(b.first())) a + b else "$a $b" }
        kept[kept.size - 1] = cutAtWord(rest, maxWidth, force = true) { measure(it, size) }
        return Fit(size, kept, true)
    }

    /**
     * A piece of text that is never broken inside: a word, a word with the separator it ends with
     * ("Trap/"), or one CJK character. [space]: a space comes before it (dropped at a line's start).
     */
    class Atom(val text: String, val space: Boolean)

    private fun cjk(ch: Char): Boolean = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF
    }

    /** Where [text] may break: at spaces, after "/", "-", "&" and "+", and around every CJK character. */
    fun atoms(text: String): List<Atom> {
        val out = ArrayList<Atom>()
        val cur = StringBuilder()
        var space = false
        fun flush() { if (cur.isNotEmpty()) { out += Atom(cur.toString(), space && out.isNotEmpty()); cur.clear(); space = false } }
        for (ch in text) {
            when {
                ch.isWhitespace() -> { flush(); space = true }
                cjk(ch) -> { flush(); cur.append(ch); flush() }
                else -> { cur.append(ch); if (ch == '/' || ch == '-' || ch == '&' || ch == '+') flush() }
            }
        }
        flush()
        return out
    }

    private fun join(line: String, a: Atom): String = if (line.isEmpty()) a.text else if (a.space) "$line ${a.text}" else line + a.text

    /**
     * Greedy wrap of [text] into lines no wider than [width], breaking only where [atoms] allows;
     * a piece wider than the whole line is split by characters (the last resort: [fitTitle] shrinks first).
     */
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (a in atoms(text)) {
            val candidate = join(cur, a)
            if (measure(candidate) <= width) { cur = candidate; continue }
            if (cur.isNotEmpty()) { lines += cur; cur = "" }
            if (measure(a.text) <= width) { cur = a.text; continue }
            // Longer than a whole line: split it where it fills the line.
            var rest = a.text
            while (rest.isNotEmpty()) {
                var cut = rest.length
                while (cut > 1 && measure(rest.substring(0, cut)) > width) cut--
                val piece = rest.substring(0, cut)
                rest = rest.substring(cut)
                if (rest.isEmpty()) cur = piece else lines += piece
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    /**
     * [text] wrapped into [count] lines as even as they come: the narrowest measure (down to half
     * of [width]) that still gives [count] lines, so a title does not leave one word alone on its
     * last line. One line, or no narrower measure that keeps the count: the plain wrap at [width].
     */
    fun balance(text: String, count: Int, width: Float, measure: (String) -> Float): List<String> {
        val plain = wrap(text, width, measure)
        if (count <= 1 || plain.size != count) return plain
        val widest = atoms(text).maxOf { measure(it.text) }
        if (widest > width) return plain
        var lo = max(width * 0.5f, widest); var hi = width
        var best = plain
        repeat(12) {
            val mid = (lo + hi) / 2
            val l = wrap(text, mid, measure)
            if (l.size == count) { best = l; hi = mid } else lo = mid
        }
        return best
    }

    /**
     * [text] whole when it fits [width], else cut after its last whole word that fits, with "…"
     * (a trailing separator or space is dropped first). When even the first word does not fit:
     * cut inside it only with [force], else nothing ("").
     */
    fun cutAtWord(text: String, width: Float, force: Boolean = false, measure: (String) -> Float): String {
        val t = text.trim()
        if (t.isEmpty() || measure(t) <= width) return t
        var line = ""
        var best = ""
        for (a in atoms(t)) {
            line = join(line, a)
            val shown = line.trimEnd(' ', '/', '-', '&', '+', ',', '·', ':', ';', '(') + "…"
            if (measure(shown) <= width) best = shown else break
        }
        if (best.isNotEmpty() || !force) return best
        var cut = t
        while (cut.length > 1 && measure("$cut…") > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    // ------------------------------------------------------------------ names

    /** One line of names: [text] as drawn, the names shown, the ones left out (said as "+N"), the size scale it needs. */
    class Names(val text: String, val shown: List<String>, val more: Int, val scale: Float)

    /** Down to how small a line of names is set before a name that alone does not fit is left out. */
    const val NAMES_FLOOR = 0.85f

    /** The mark of names left out. */
    fun more(n: Int): String = "+$n"

    private val SCALES = floatArrayOf(1f, 0.95f, 0.9f, NAMES_FLOOR)

    /**
     * As many whole [names] as fit on one line of [width], in order, joined by [sep], then "+N" for
     * the ones left out ([more]); never a name cut. When not even the first fits, the line is set
     * smaller (to [NAMES_FLOOR]); a name too long even then is left out (counted in "+N") and the
     * next ones tried. [measure] gives a string's width at a scale of the line's size. No names, or
     * none that fits: an empty line.
     */
    fun fitNames(names: List<String>, width: Float, sep: String = "  ·  ", measure: (String, Float) -> Float): Names {
        val all = names.map { it.trim() }.filter { it.isNotEmpty() }
        var dropped = 0
        while (dropped < all.size) {
            val rest = all.drop(dropped)
            for (scale in SCALES) {
                // The longest run of whole names that fits with its "+N".
                var k = 0
                while (k < rest.size && measure(rest.take(k + 1).joinToString(sep), scale) <= width) k++
                while (k > 0) {
                    val left = all.size - k
                    val text = rest.take(k).joinToString(sep) + if (left > 0) sep + more(left) else ""
                    if (measure(text, scale) <= width) return Names(text, rest.take(k), left, scale)
                    k--
                }
            }
            dropped++
        }
        return Names("", emptyList(), all.size, 1f)
    }

    /**
     * As [fitNames] over up to [maxLines] lines: whole names packed line after line, the last line
     * ending with "+N" for the ones left out. One line is [fitNames] itself. No names: no lines.
     */
    fun fitNameLines(names: List<String>, maxLines: Int, width: Float, sep: String = "  ·  ", measure: (String, Float) -> Float): List<Names> {
        val all = names.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Names>()
        var i = 0
        while (i < all.size && out.size < maxLines - 1) {
            var k = 0
            while (i + k < all.size && measure(all.subList(i, i + k + 1).joinToString(sep), 1f) <= width) k++
            // A name too long for a line alone: the last line deals with it (smaller, else left out).
            if (k == 0) break
            out += Names(all.subList(i, i + k).joinToString(sep), all.subList(i, i + k), 0, 1f)
            i += k
        }
        if (i >= all.size) return out
        val last = fitNames(all.drop(i), width, sep, measure)
        val left = all.size - i - last.shown.size
        out += if (last.text.isNotEmpty()) Names(last.text, last.shown, left, last.scale) else Names(more(left), emptyList(), left, 1f)
        return out
    }

    /** A row of a stack of names: the text, the scale it is set at, the name it belongs to (its place among those shown), and whether it is the "+N". */
    class Row(val text: String, val scale: Float, val name: Int, val isMore: Boolean = false)

    /**
     * [names] stacked one under the other, in order, at most [maxNames] of them, in a column of
     * [width] and [room] high: each whole, set smaller (to [NAMES_FLOOR]) when it does not fit a
     * row, else on two rows broken between words; a name that cannot be set whole even so is
     * left out. When names are left out, a last row says how many ("+N"). [height] gives the
     * height of a row of the name at a place (the first may be larger), [measure] the width of a
     * text in such a row at a scale.
     */
    fun stackNames(
        names: List<String>, maxNames: Int, width: Float, room: Float,
        height: (Int) -> Float, measure: (String, Int, Float) -> Float,
    ): List<Row> {
        val all = names.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Row>()
        var used = 0f
        var shown = 0
        for (n in all) {
            if (shown >= maxNames) break
            val at = shown
            val one = SCALES.firstOrNull { measure(n, at, it) <= width }
            val rows = if (one != null) listOf(Row(n, one, at)) else {
                val lines = wrap(n, width) { measure(it, at, NAMES_FLOOR) }
                if (lines.size <= 2 && lines.all { measure(it, at, NAMES_FLOOR) <= width } && lines.joinToString(" ") == n) lines.map { Row(it, NAMES_FLOOR, at) } else null
            } ?: continue
            val h = rows.size * height(at)
            if (used + h > room) break
            out += rows; used += h; shown++
        }
        if (shown < all.size) {
            // Room for the "+N": the last names give way to it.
            while (shown > 0 && used + height(shown) > room) {
                val last = shown - 1
                val gone = out.count { it.name == last }
                repeat(gone) { out.removeAt(out.size - 1) }
                used -= gone * height(last); shown--
            }
            if (shown == 0) return emptyList()
            out += Row(more(all.size - shown), 1f, shown, isMore = true)
        }
        return out
    }
}

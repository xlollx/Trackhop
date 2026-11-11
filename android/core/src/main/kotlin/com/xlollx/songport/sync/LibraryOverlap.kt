package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlin.math.roundToInt

/**
 * "Two subscriptions?": how much of the user's music two services share (liked songs and playlist
 * tracks, each side counted once), matched like a sync matches: first the match cache of earlier
 * syncs, then ISRC, title, artist and duration ([Matcher.TrackIndex]). Pure Kotlin, covered by tests.
 *
 * It only measures: it never says which service is better, and the prices are the user's own.
 * What it says at the end ([verdict]) is about the user's two libraries, never about the catalogues.
 */
object LibraryOverlap {

    data class Result(
        val sizeA: Int,
        val sizeB: Int,
        val common: List<Compatibility.Common>,
        val onlyA: List<Track>,
        val onlyB: List<Track>,
    ) {
        /** Tracks on either side, the common ones once. */
        val union: Int get() = sizeA + sizeB - common.size
        /** Share of all the music (either side) that is on both, 0–100. */
        val percent: Int get() = if (union == 0) 0 else (100.0 * common.size / union).roundToInt()
        /** Share of A's songs that are on B too, 0–100; [coveredB] the other way round. */
        val coveredA: Int get() = if (sizeA == 0) 0 else (100.0 * common.size / sizeA).roundToInt()
        val coveredB: Int get() = if (sizeB == 0) 0 else (100.0 * common.size / sizeB).roundToInt()
        /** Only on A, on both, only on B as shares of all the music: whole numbers that add up to 100 (all 0 when there is none). */
        val shares: Triple<Int, Int, Int> get() {
            if (union == 0) return Triple(0, 0, 0)
            val a = (100.0 * onlyA.size / union).roundToInt().coerceAtMost(100 - percent)
            return Triple(a, percent, 100 - percent - a)
        }
    }

    /** What the two libraries are to each other. */
    enum class Verdict {
        /** Nearly the same songs on both. */
        SAME,
        /** Nearly all of A is on B too (B has more); [B_IN_A] the other way round. */
        A_IN_B, B_IN_A,
        /** A good part is on both, each has songs of its own. */
        PARTIAL,
        /** Two different libraries. */
        APART,
    }

    /** A library counts as inside the other from this share of its songs. */
    const val COVERED = 90
    private const val HALF = 50

    /** The verdict on [r]; null when a side has no songs (nothing to compare). */
    fun verdict(r: Result): Verdict? {
        if (r.sizeA == 0 || r.sizeB == 0) return null
        val a = r.coveredA; val b = r.coveredB
        return when {
            a >= COVERED && b >= COVERED -> Verdict.SAME
            a >= COVERED -> Verdict.A_IN_B
            b >= COVERED -> Verdict.B_IN_A
            maxOf(a, b) >= HALF -> Verdict.PARTIAL
            else -> Verdict.APART
        }
    }

    /**
     * Two circles for a drawing of the overlap: their radii ([rA], [rB]; the larger is 1) and the
     * distance between their centres ([distance]).
     */
    data class Venn(val rA: Double, val rB: Double, val distance: Double) {
        private val left: Double get() = minOf(-rA, distance - rB)
        private val right: Double get() = maxOf(rA, distance + rB)
        /** How wide the two circles are together. */
        val span: Double get() = right - left
        /** Where the centres are, from the middle of the drawing: [centreA] on the left, [centreB] on the right. */
        val centreA: Double get() = -(left + right) / 2
        val centreB: Double get() = distance - (left + right) / 2
    }

    /** The smallest a circle is drawn, next to a much larger library. */
    private const val MIN_RADIUS = 0.35

    /**
     * The circles of [sizeA] and [sizeB] songs with [common] on both: areas in proportion to the
     * sizes (never smaller than [MIN_RADIUS] of the larger, so a small library is still seen), and
     * as much of the smaller circle inside the other as the share of its songs that are on both.
     * Nothing in common: the circles apart; everything: one inside the other.
     */
    fun venn(sizeA: Int, sizeB: Int, common: Int): Venn {
        val max = maxOf(sizeA, sizeB, 1).toDouble()
        val rA = Math.sqrt(sizeA.coerceAtLeast(0) / max).coerceAtLeast(MIN_RADIUS)
        val rB = Math.sqrt(sizeB.coerceAtLeast(0) / max).coerceAtLeast(MIN_RADIUS)
        val small = minOf(sizeA, sizeB)
        val share = if (small <= 0) 0.0 else (common.toDouble() / small).coerceIn(0.0, 1.0)
        val apart = rA + rB; val inside = Math.abs(rA - rB)
        if (share <= 0.0) return Venn(rA, rB, apart * 1.08)
        if (share >= 1.0) return Venn(rA, rB, inside)
        val target = share * Math.PI * minOf(rA, rB) * minOf(rA, rB)
        // The lens shrinks as the centres part: halved until the distance is found.
        var lo = inside; var hi = apart
        repeat(40) { val mid = (lo + hi) / 2; if (lens(rA, rB, mid) > target) lo = mid else hi = mid }
        return Venn(rA, rB, (lo + hi) / 2)
    }

    /** The area two circles of radius [r] and [s] share with their centres [d] apart. */
    internal fun lens(r: Double, s: Double, d: Double): Double {
        if (d >= r + s) return 0.0
        if (d <= Math.abs(r - s)) return Math.PI * minOf(r, s) * minOf(r, s)
        val a = r * r * Math.acos(((d * d + r * r - s * s) / (2 * d * r)).coerceIn(-1.0, 1.0))
        val b = s * s * Math.acos(((d * d + s * s - r * r) / (2 * d * s)).coerceIn(-1.0, 1.0))
        val c = 0.5 * Math.sqrt(((-d + r + s) * (d + r - s) * (d - r + s) * (d + r + s)).coerceAtLeast(0.0))
        return a + b - c
    }

    /** What [price] is a month, typed as a month's or as a year's ([yearly]). */
    fun monthly(price: Double, yearly: Boolean): Double = if (yearly) price / 12 else price

    /** One copy of each song: the same track in two playlists (or liked and in a playlist) counts once. */
    fun distinct(tracks: List<Track>): List<Track> =
        tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.distinctBy { it.id.ifBlank { Wrapped.key(it) } }.distinctBy { Wrapped.key(it) }

    /**
     * [cached] gives, for a track of A, the id an earlier sync matched it to on B's service (null
     * when none): those count as common without a search.
     */
    fun compute(a: List<Track>, b: List<Track>, cached: (Track) -> String? = { null }): Result {
        val da = distinct(a)
        val db = distinct(b)
        val byId = db.associateBy { it.id }
        val index = Matcher.TrackIndex(db)
        val usedB = HashSet<String>()
        val common = ArrayList<Compatibility.Common>()
        val onlyA = ArrayList<Track>()
        for (t in da) {
            val m = cached(t)?.let { byId[it] }?.takeIf { it.id !in usedB } ?: index.best(t)?.takeIf { it.id !in usedB }
            if (m != null) { usedB += m.id; common += Compatibility.Common(t, m) } else onlyA += t
        }
        val onlyB = db.filter { it.id !in usedB }
        return Result(da.size, db.size, common, onlyA, onlyB)
    }

    /** A price as the user types it ("10,99", "€ 9.99", "11"); null when there is no number. */
    fun parsePrice(text: String): Double? {
        val digits = Regex("""\d+(?:[.,]\d{1,2})?""").find(text.replace(" ", ""))?.value ?: return null
        return digits.replace(',', '.').toDoubleOrNull()
    }
}

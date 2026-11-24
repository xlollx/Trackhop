package com.xlollx.songport.sync

import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import kotlin.math.abs

/**
 * "Why wasn't this matched?" (2.2): a candidate's score as the matcher gives it ([Matcher.score], the
 * one that decides), and beside it the parts that make it up, each read with the matcher's own
 * public measures: how alike the titles and the artists are, and the factors for the length, the
 * version (live, remix…) and the album. Nothing here decides anything; it only tells.
 */
object MatchExplain {
    /** Something about a candidate that pulled its score down, or settled it. */
    enum class Reason { SAME_ISRC, SAME_RECORDING, OTHER_TITLE, OTHER_ARTIST, LENGTH, VERSION, ALBUM }

    /**
     * [title] and [artist] are similarities (0 to 1); [length], [version] and [album] are factors the
     * score is multiplied by (1 = no effect). [lengthDiffMs]: the candidate's length minus the
     * source's, 0 where either is unknown. [score] is the matcher's; [matched] whether it reaches [threshold].
     */
    data class Explained(
        val track: Track,
        val score: Double,
        val threshold: Double,
        val title: Double,
        val artist: Double?,
        val length: Double,
        val lengthDiffMs: Long,
        val version: Double,
        val album: Double,
        val reasons: List<Reason>,
    ) {
        val matched: Boolean get() = score >= threshold
    }

    fun explain(src: Track, cand: Track, policy: MatchPolicy = MatchPolicy()): Explained {
        val score = Matcher.score(src, cand, policy)
        val title = Matcher.similarity(Matcher.normalizeTitle(src.title), Matcher.normalizeTitle(cand.title))
        val artist = if (src.artists.isEmpty() || cand.artists.isEmpty()) null else Matcher.artistScore(src.artists, cand.artists)
        val length = Matcher.durationFactor(src.durationMs, cand.durationMs)
        val diff = if (src.durationMs > 0 && cand.durationMs > 0) cand.durationMs - src.durationMs else 0L
        val version = Matcher.versionFactor(src.title, cand.title)
        val album = Matcher.albumFactor(src.album, cand.album, policy) * Matcher.liveAlbumFactor(src.album, cand.album, policy)
        val reasons = buildList {
            val sameIsrc = src.isrcNorm != null && src.isrcNorm == cand.isrcNorm
            if (sameIsrc) add(Reason.SAME_ISRC)
            if (src.mbids.isNotEmpty() && cand.mbids.any { it in src.mbids }) add(Reason.SAME_RECORDING)
            if (!sameIsrc) {
                if (title < 0.8) add(Reason.OTHER_TITLE)
                if (artist != null && artist < 0.7) add(Reason.OTHER_ARTIST)
                if (length < 1.0 && diff != 0L) add(Reason.LENGTH)
                if (version < 1.0) add(Reason.VERSION)
                if (album < 1.0) add(Reason.ALBUM)
            }
        }
        return Explained(cand, score, policy.acceptThreshold, title, artist, length, diff, version, album, reasons)
    }

    /** Each of [candidates] explained, the best first, at most [limit]. */
    fun explainAll(src: Track, candidates: List<Track>, policy: MatchPolicy = MatchPolicy(), limit: Int = 5): List<Explained> =
        candidates.distinctBy { it.id }.map { explain(src, it, policy) }.sortedByDescending { it.score }.take(limit)

    /** "+0:50" / "-1:05": a length difference as people read it. */
    fun signedLength(ms: Long): String {
        val s = abs(ms) / 1000
        return (if (ms < 0) "-" else "+") + "%d:%02d".format(s / 60, s % 60)
    }

    /** One line in English for the reasons (the pages and the CLI; the app says them in the user's language). */
    fun reasonsLine(e: Explained): String = e.reasons.joinToString(" · ") { r ->
        when (r) {
            Reason.SAME_ISRC -> "same ISRC"
            Reason.SAME_RECORDING -> "same MusicBrainz recording"
            Reason.OTHER_TITLE -> "another title"
            Reason.OTHER_ARTIST -> "another artist"
            Reason.LENGTH -> "length ${signedLength(e.lengthDiffMs)}"
            Reason.VERSION -> "another version (live, remix…)"
            Reason.ALBUM -> "another album"
        }
    }
}

package com.xlollx.songport.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The legacy encoding of a playlist file that is not UTF-8. Windows players write Windows-1252; apps
 * and players in China, Japan, Korea and Russia write GB18030, Shift_JIS, EUC-KR or Windows-1251
 * (a file decoded as Windows-1252 then reads "Ïðèâåò" instead of "Привет"). Each candidate decodes
 * the bytes strictly; the one whose text reads as its own script wins, Windows-1252 when none does.
 */
internal object TextCharsets {
    val WESTERN: Charset = charset("windows-1252")

    /** In the order they are tried, the first that reads right winning: the stricter encodings (that refuse most foreign bytes) first. */
    private val candidates: List<Pair<String, (Char) -> Boolean?>> = listOf(
        "Shift_JIS" to ::japanese,
        "EUC-KR" to ::korean,
        "windows-1251" to ::cyrillic,
        "GB18030" to ::chinese,
    )

    /** From this share of its own script among the letters that are not ASCII, a candidate reads right. */
    private const val MIN_SHARE = 0.9

    /**
     * The charset [bytes] are most likely written in, when they are not UTF-8. [complete]: false when
     * [bytes] are the start of a longer file, so a character cut at the end does not count against it.
     */
    fun guess(bytes: ByteArray, complete: Boolean = true): Charset {
        if (readsWestern(bytes, complete)) return WESTERN
        for ((name, own) in candidates) {
            val cs = runCatching { charset(name) }.getOrNull() ?: continue
            val text = strict(bytes, cs, complete) ?: continue
            if ((share(text, own) ?: continue) >= MIN_SHARE) return cs
        }
        return WESTERN
    }

    /**
     * Western text written as Windows-1252: its letters outside ASCII stand alone between ASCII ones
     * ("Beyoncé", "Sigur Rós"). Cyrillic or CJK bytes read that way come out as runs of them.
     */
    private fun readsWestern(bytes: ByteArray, complete: Boolean): Boolean {
        val text = strict(bytes, WESTERN, complete) ?: return false
        var high = 0
        var inRuns = 0
        var run = 0
        fun close() { if (run >= 3) inRuns += run; run = 0 }
        for (c in text) {
            if (c.code < 0x80) { close(); continue }
            high++
            if (c.isLetter()) run++ else close()
        }
        close()
        return high == 0 || inRuns * 5 < high
    }

    /** The text, or null when [bytes] are not valid in [cs]. */
    private fun strict(bytes: ByteArray, cs: Charset, complete: Boolean): String? {
        val decoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(bytes.size * 2 + 16)
        val r = decoder.decode(ByteBuffer.wrap(bytes), out, complete)
        if (r.isError) return null
        if (complete && decoder.flush(out).isError) return null
        out.flip()
        return out.toString()
    }

    /**
     * Of the characters outside ASCII that [own] has a view on (null: neutral, like quotes and
     * dashes), the share it holds as its script's own. Null when there are none to judge.
     */
    private fun share(text: String, own: (Char) -> Boolean?): Double? {
        var judged = 0
        var good = 0
        for (c in text) {
            if (c.code < 0x80) continue
            val v = own(c) ?: continue
            judged++
            if (v) good++
        }
        return if (judged == 0) null else good.toDouble() / judged
    }

    private fun punctuation(c: Char) = c in '‐'..'⁯' || c in '　'..'〿' || c == '«' || c == '»' || c == '·'

    private fun japanese(c: Char): Boolean? = when {
        punctuation(c) -> null
        c in '぀'..'ヿ' -> true // hiragana, katakana
        c in '一'..'鿿' -> true
        c in '！'..'｠' -> true // full-width letters and signs
        else -> false // half-width katakana among them: one-byte Shift_JIS, what other bytes turn into
    }

    private fun korean(c: Char): Boolean? = when {
        punctuation(c) -> null
        c in '가'..'힣' -> true
        // Hanja are rare in Korean titles and what Chinese text read as EUC-KR comes out as.
        else -> false
    }

    private fun cyrillic(c: Char): Boolean? = when {
        punctuation(c) || c == '№' -> null // № too
        c in 'А'..'я' || c == 'Ё' || c == 'ё' -> true
        else -> false
    }

    private val gb2312: Charset? = runCatching { charset("GB2312") }.getOrNull()

    private fun chinese(c: Char): Boolean? = when {
        punctuation(c) -> null
        c in '！'..'｠' -> null
        // Common Chinese is in GB2312; Japanese or Korean bytes read as GB18030 land outside it.
        c in '一'..'鿿' -> gb2312?.newEncoder()?.canEncode(c) ?: true
        else -> false
    }
}

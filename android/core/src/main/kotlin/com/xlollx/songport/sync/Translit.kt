package com.xlollx.songport.sync

/**
 * Names in another writing system, in Latin letters, to compare a song a service lists as "Кино —
 * Группа крови" with the "Kino — Gruppa krovi" of another. Built in and small on purpose: the core
 * also runs on a plain JVM, where Android's ICU is not there and ICU4J would weigh 13 MB.
 * Cyrillic, Greek, kana and Hangul are read letter by letter; Hebrew and Arabic write no vowels, so
 * they are compared by consonants only ([skeleton]); kanji and hanzi have no reading without a
 * dictionary: [latin] answers null and the song is left to its ISRC and MusicBrainz names.
 */
object Translit {
    /** [s] has letters of a script other than Latin (accented and Vietnamese Latin letters are Latin). */
    fun foreign(s: String): Boolean = s.any { it.code >= 0x370 && it.code !in 0x1E00..0x1EFF && it.isLetter() }

    /** [s] has Hebrew or Arabic letters: to be compared by [skeleton]. */
    fun abjad(s: String): Boolean = s.any { it.code in 0x5D0..0x6FF && it.isLetter() }

    private fun table(vararg rows: String): Map<Char, String> =
        rows.flatMap { it.split(' ') }.associate { it[0] to it.substring(1) }

    private val CYRILLIC = table(
        "аa бb вv гg дd еe ёe жzh зz иi йy кk лl мm нn оo пp рr сs тt уu фf хkh цts чch шsh щshch ъ ыy ь эe юyu яya",
        "єye іi їyi ґg ўu јj љlj њnj ћc ђdj џdz",
    )
    private val GREEK = table("αa βv γg δd εe ζz ηi θth ιi κk λl μm νn ξx οo πp ρr σs ςs τt υy φf χch ψps ωo")
    /** αυ and ευ end in "f" before these, in "v" otherwise ("Ελευθερία" is Eleftheria, "Εύα" Eva). */
    private const val GREEK_VOICELESS = "θκξπστφχψς"
    private val HEBREW = table("א בb גg דd ה ו זz ח טt י ךk כk לl םm מm ןn נn סs ע ףp פp ץts צts קk רr שs תt")
    private val ARABIC = table(
        "ء آ أ ؤ إ ئ ا بb ة تt ثt جg ح خk دd ذd رr زz سs شs صs ضd طt ظz ع غg ـ فf قk كk لl مm نn ه و ى ي",
        "پp چk ژz کk گg ی",
    )
    private val KANA: Map<Char, String> = HashMap<Char, String>().apply {
        "あいうえお: かきくけこ:k さしすせそ:s たちつてと:t なにぬねの:n はひふへほ:h まみむめも:m らりるれろ:r がぎぐげご:g ざじずぜぞ:z だぢづでど:d ばびぶべぼ:b ぱぴぷぺぽ:p"
            .split(' ').forEach { row -> row.substringBefore(':').forEachIndexed { i, ch -> put(ch, row.substringAfter(':') + "aiueo"[i]) } }
        putAll(table("しshi ちchi つtsu ふfu じji ぢji づzu やya ゆyu よyo わwa をo んn ゔvu ぁa ぃi ぅu ぇe ぉo ゃya ゅyu ょyo"))
    }
    private const val SMALL_KANA = "ぁぃぅぇぉゃゅょ"

    private val LEAD = "g kk n d tt r m b pp s ss  j jj ch k t p h".split(' ')
    private val VOWEL = "a ae ya yae eo e yeo ye o wa wae oe yo u wo we wi yu eu ui i".split(' ')
    private val TAIL = " k k k n n n t l k m l l l p l m p p t t ng t t k t p t".split(' ')

    private val GREEK_RUN = Regex("[\\u0370-\\u03ff\\u1f00-\\u1fff]+")

    /**
     * [s] with its Cyrillic, Greek, kana, Hangul, Hebrew and Arabic letters in Latin ones, the rest as
     * it is; null when it has a letter this cannot read (kanji, hanzi, Thai…).
     */
    fun latin(s: String): String? {
        if (!foreign(s)) return s
        // Greek accents only: stripping marks everywhere would turn "й" into "и" and "が" into "か".
        val t = GREEK_RUN.replace(s.lowercase()) { Matcher.stripDiacritics(it.value) }
        val sb = StringBuilder()
        var double = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            val next = t.getOrNull(i + 1)
            val start = i == 0 || !t[i - 1].isLetter()
            i++
            val code = c.code
            val out: String = when {
                code < 0x370 || code in 0x1E00..0x1EFF || !c.isLetter() -> c.toString()
                code in 0x370..0x3FF -> when {
                    c == 'ο' && next == 'υ' -> { i++; "ou" }
                    (c == 'α' || c == 'ε') && next == 'υ' -> {
                        i++
                        val after = t.getOrNull(i)
                        GREEK.getValue(c) + if (after == null || !after.isLetter() || after in GREEK_VOICELESS) "f" else "v"
                    }
                    c == 'μ' && next == 'π' -> { i++; if (start) "b" else "mp" }
                    c == 'ν' && next == 'τ' -> { i++; if (start) "d" else "nt" }
                    c == 'γ' && next == 'κ' -> { i++; if (start) "g" else "gk" }
                    c == 'γ' && next == 'γ' -> { i++; "ng" }
                    else -> GREEK[c] ?: return null
                }
                code in 0x400..0x4FF -> CYRILLIC[c] ?: return null
                code in 0x5D0..0x5FF -> HEBREW[c] ?: return null
                code in 0x600..0x6FF -> ARABIC[c] ?: return null
                c == 'っ' || c == 'ッ' -> { double = true; continue }
                c == 'ー' -> ""
                // Katakana sits 0x60 after hiragana, letter for letter.
                code in 0x3041..0x3096 || code in 0x30A1..0x30F6 -> {
                    val h = if (code >= 0x30A1) (code - 0x60).toChar() else c
                    val r = KANA[h] ?: return null
                    // "しゃ" is "sha", "きょ" is "kyo", "ファ" is "fa": the small kana takes the place of the vowel before.
                    if (h in SMALL_KANA && sb.isNotEmpty() && sb.last() in "aiueo") {
                        val sibilant = sb.endsWith("shi") || sb.endsWith("chi") || sb.endsWith("ji")
                        sb.setLength(sb.length - 1)
                        if (sibilant || sb.isEmpty() || sb.last() == 'y') r.takeLast(1) else r
                    } else r
                }
                code in 0xAC00..0xD7A3 -> {
                    val n = code - 0xAC00
                    LEAD[n / 588] + VOWEL[n % 588 / 28] + TAIL[n % 28]
                }
                else -> return null
            }
            if (double && out.isNotEmpty()) { sb.append(out[0]); double = false }
            sb.append(out)
        }
        return sb.toString()
    }

    private val DOUBLED = Regex("""(\p{L})\1+""")

    /**
     * The consonants of a normalised Latin name, one letter per sound, for a name whose other side is
     * Hebrew or Arabic: "omer adam" and "עומר אדם" are both "mr dm", "fairouz" and "فيروز" both "prs".
     */
    fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            'a', 'e', 'i', 'o', 'u', 'y', 'w', 'h' -> {}
            'v' -> sb.append('b'); 'f' -> sb.append('p'); 'q', 'c' -> sb.append('k'); 'z' -> sb.append('s'); 'j' -> sb.append('g')
            else -> sb.append(c)
        }
        return DOUBLED.replace(sb, "$1").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }
}

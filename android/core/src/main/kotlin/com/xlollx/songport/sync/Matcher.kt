package com.xlollx.songport.sync

import com.xlollx.songport.Features
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Abbinamento di brani tra servizi diversi. Kotlin puro (nessuna dipendenza Android) per i test.
 *
 * Strategia: ISRC identico = stesso brano (1.0). Altrimenti punteggio 0..1 da titolo normalizzato,
 * artisti e differenza di durata. Le versioni ("Remastered 2011", "Radio Edit", "(feat. X)") vengono
 * rimosse dal titolo; "remix" viene invece mantenuto per non confondere un remix con l'originale.
 */
object Matcher {
    const val DEFAULT_THRESHOLD = 0.70
    /** Soglia piu' alta quando si confronta con brani GIA' presenti nella destinazione (niente ricerca). */
    const val EXISTING_THRESHOLD = 0.82
    /** Sotto questo punteggio l'abbinamento viene accettato ma segnalato "da verificare". */
    const val REVIEW_THRESHOLD = 0.86
    /**
     * Raised whenever matching or searching changes so that a track missed before may be found now:
     * the misses remembered under another revision are searched again on the next run (#57).
     * 3 with the 1.18 switch, where names are compared across scripts too; 1.17 keeps its misses.
     * 4 with 2.2: traditional and simplified Chinese as one, artists' other names from MusicBrainz,
     * title and album when the artists are written in two scripts, version words in more languages.
     */
    val REVISION: Int get() = if (Features.v2) 4 else 2

    /**
     * Like [REVISION], for the misses remembered on a personal server only (the other services keep
     * theirs): raised when matching against a server's songs changes.
     * 1 with 2.2.7: titles taken from file names ("06 In the End"), see [fileTitles].
     */
    const val SERVER_REVISION = 1

    /** How a personal server's own search ranks its songs: their titles may be file names ([MatchPolicy.fileNames]). */
    val SERVER_POLICY = MatchPolicy(fileNames = true)

    private val BRACKET_NOISE = Regex(
        """\s*[(\[][^)\]]*\b(feat\.?|ft\.?|featuring|remaster\w*|rimasterizzat\w*|version|versione|edit|(?<!re)mix|live|mono|stereo|deluxe|bonus|explicit|clean|official|video|audio|lyrics?|hd|4k|visuali[sz]er|radio|single|album|original|from|soundtrack|ost|anniversary|edition|\d{4})\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Credits written into the title instead of the artists: "(with Justin Bieber)", "(con Bresh)",
     * "(w/ X)", "(prod. Metro Boomin)". Only a bracket that opens with the credit, so a subtitle that
     * merely contains the word ("(Dancing with Myself)") stays.
     */
    private val CREDIT_BRACKET = Regex(
        """\s*[(\[]\s*(with|con|w/|avec|part\.|prod\.?|produced\s+by)\s[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_TAIL = Regex("""\s+([-–—]\s+)?(feat\.?|ft\.?|featuring|w/|prod\.?\s+by|prod\.)\s+.*$""", RegexOption.IGNORE_CASE)
    private val DASH_TAIL = Regex(
        """\s*[-–—]\s*(((\d{4}|digital(ly)?|single|new(ly)?|\d+[- ]?bit|versi[oó]n)\s+)*remast[eé]r.*|radio edit.*|single version.*|album version.*|live.*|mono.*|stereo.*|deluxe.*|bonus track.*|extended.*|original mix.*|rimasterizzat.*|versione\s.*|[^-–—]*\bversion|(from|de|dal)\s.*|\d{4}\s.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val THE_LAST = Regex(""",\s*the\s*$""")
    private val ARTIST_TAIL = Regex("""\s+(feat\.?|ft\.?|featuring|vs\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}\s]""")
    /** Initials with dots, "U.S.A", "R.E.M.", "L.A.": one word, as most libraries write them ("USA"). */
    private val ACRONYM = Regex("""(?<![\p{L}\p{N}.])\p{L}(?:\.\p{L})+\.?(?![\p{L}\p{N}])""")
    private fun joinInitials(s: String) = ACRONYM.replace(s) { it.value.replace(".", "") }
    /** "Don't" is one word to a search engine: "dont", never "don t". */
    private val APOSTROPHES = Regex("""[’'`´]""")
    private val ARTIST_PARTS = Regex("""\s*(?:,|&|\+|/|\s+x\s+|\s+and\s+|\s+e\s+)\s*""", RegexOption.IGNORE_CASE)

    /**
     * Parole che indicano una versione diversa dallo studio: se compaiono solo nel candidato
     * e non nell'originale, quasi sempre e' il brano sbagliato (il classico "(Live)" o la cover
     * karaoke che YouTube propone per prima). Controllate sul titolo grezzo, prima che la
     * normalizzazione tolga le parentesi.
     */
    private val VERSION_WORDS: List<Pair<String, Regex>> = listOf(
        "live" to """\blive\b""",
        "karaoke" to """\bkaraoke\b|カラオケ|노래방|卡拉\s*ok|\bin the style of\b|\bmade famous by\b|\boriginally performed by\b|\bbacking track\b""",
        "instrumental" to """\binstrumental(e|es)?\b|\binstrumentaal\b|\benstrumantal\b|インスト|伴奏""",
        "cover" to """\bcover\b""",
        "tribute" to """\btribute\b""",
        "spedup" to """\bspe+d\s*up\b""",
        "slowed" to """\bslowed\b""",
        "nightcore" to """\bnightcore\b""",
        "8d" to """\b8d\b""",
        "acoustic" to """\bacoustic\b|\bacustic[oa]s?\b|\bakusti(k|sch|sche|scher|cna|czna|czny)\b|\bacoustique\b|\bakoestisch\b|アコースティック|어쿠스틱""",
        "unplugged" to """\bunplugged\b""",
        "demo" to """\bdemo\b""",
        "remix" to """\bremix(ed)?\b|\brmx\b|\bremezcla\b|\brework\b|\bbootleg\b|リミックス|리믹스""",
        "acapella" to """\ba\s*cappella\b|\bacapella\b""",
        "reverb" to """\breverb\b""",
        "lofi" to """\blo-?fi\b""",
        "parody" to """\bparody\b""",
        "reaction" to """\breaction\b""",
        "remaster" to """\bremaster(ed)?\b|\bremasteris[e]e?\b|\bremasterizad[oa]\b|\brimasterizzat[oa]\b|\bremasterisiert\b|リマスター""",
        "mono" to """\bmono\b""",
        "stereo" to """\bstereo\b""",
        // Another recording of the same song by the same artist: "Love Story (Taylor's Version)".
        "rerecord" to """\bre-?record(ed|ing)?\b|\b\w+['’]s version\b""",
    ).map { (k, v) -> k to Regex(v, RegexOption.IGNORE_CASE) }

    /**
     * A concert said by where it was played, without the word "live": "SING (iTunes Festival '11)",
     * "(BBC Session)", "(Live Lounge)", "[MTV Unplugged]", "(On Tour)". Read in the subtitle only, so a
     * song called "Festival" or "Concerto" is not taken for one.
     */
    private val CONCERT = Regex(
        """\b(festival|concerts?|sessions?|lounge|mtv|on tour|tour edition|on stage|en vivo|ao vivo|dal vivo|en directo|en concierto|en concert|in concert|konzert|live at|live from|live in|live on|na zywo)\b|\bcanl[iı](?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    /** The same in scripts whose marks the folding of accents would break (ライブ would lose its dakuten). */
    private val CONCERT_RAW = Regex("""ライブ|라이브|现场|現場|演唱会|演唱會|концерт""", RegexOption.IGNORE_CASE)

    /**
     * An album recorded at a concert, by whole phrases only: "Live at Wembley", "MTV Unplugged in New
     * York", "iTunes Festival: London 2011", "En Vivo", "(Live)". Never "Live Forever", "Live Through
     * This", "Alive" (a word "live" alone, not followed by at/from/in/on, is a title). A collection
     * ("Greatest Hits", "Best of", "Essential") is never one: it gathers songs of every kind.
     */
    private val LIVE_ALBUM = Regex(
        """(^|[\s(\[:-])(live\s+(at|from|in|on)\b|\(live\)|\[live\]|-\s*live\s*$|unplugged|in concert|en concert|en concierto|en vivo|ao vivo|dal vivo|en directo|konzert|itunes festival|mtv\b|bbc sessions?|live lounge|on tour)""",
        RegexOption.IGNORE_CASE,
    )
    private val COLLECTION = Regex("""\b(greatest hits|best of|the best|essential|collection|anthology|hits)\b""", RegexOption.IGNORE_CASE)

    /**
     * [cand] is a live recording where [src] is not: "live" in its title or subtitle only, or a live
     * album in place of a studio one. For matches made before these rules knew a concert.
     */
    fun liveInPlaceOf(src: Track, cand: Track): Boolean {
        val extra = (versionMarkers(cand.title) - NEUTRAL_VERSIONS) - (versionMarkers(src.title) - NEUTRAL_VERSIONS)
        return "live" in extra || liveAlbumFactor(src.album, cand.album) < 1.0
    }

    fun albumIsLive(album: String): Boolean = album.isNotBlank() && LIVE_ALBUM.containsMatchIn(stripDiacritics(album))

    /**
     * A live album in place of the source's studio one: 0.75 (0.3 with "studio versions only"), so the
     * studio take wins and a lone live one goes to the review instead of in. Nothing when the source's
     * album is unknown, a collection, live itself, or the same album.
     */
    fun liveAlbumFactor(srcAlbum: String, candAlbum: String, policy: MatchPolicy = MatchPolicy()): Double {
        if (srcAlbum.isBlank() || !albumIsLive(candAlbum) || albumIsLive(srcAlbum) || COLLECTION.containsMatchIn(srcAlbum)) return 1.0
        if (normalizeAlbum(srcAlbum) == normalizeAlbum(candAlbum)) return 1.0
        return if (policy.studioOnly) 0.3 else 0.75
    }

    /**
     * The versions [title] names, each by one word whatever the language: "live", "acoustic" ("Acústico",
     * "Akustik"), "remix" ("RMX", "Remezcla"), "karaoke" ("In the Style of"), "rerecord" ("Taylor's
     * Version")… A concert said by its place ("iTunes Festival", "En Vivo", "Canlı") counts as "live",
     * in the subtitle only.
     */
    fun versionMarkers(title: String): Set<String> {
        val folded = stripDiacritics(title).lowercase()
        val raw = title.lowercase()
        val words = VERSION_WORDS.filter { (_, re) -> re.containsMatchIn(folded) || re.containsMatchIn(raw) }.mapTo(HashSet()) { it.first }
        val subtitle = SUBTITLE.find(title)?.value.orEmpty()
        return if (CONCERT.containsMatchIn(stripDiacritics(subtitle)) || CONCERT_RAW.containsMatchIn(subtitle)) words + "live" else words
    }

    /**
     * Fattore 0..1 legato alle versioni: marcatori presenti solo nel candidato pesano molto
     * (0.55), presenti solo nell'originale un po' meno (0.8), perche' una sorgente "Live" senza
     * versione live sulla destinazione e' comunque un'approssimazione accettabile.
     * "remaster/mono/stereo" sono neutri: e' lo stesso brano.
     */
    fun versionFactor(srcTitle: String, candTitle: String): Double =
        versionFactorN(versionMarkers(srcTitle) - NEUTRAL_VERSIONS, versionMarkers(candTitle) - NEUTRAL_VERSIONS)

    private fun versionFactorN(a: Set<String>, b: Set<String>): Double {
        return when {
            a == b -> 1.0
            (b - a).isNotEmpty() -> 0.55
            else -> 0.8
        }
    }
    private val SPACES = Regex("""\s+""")
    private val MARKS = Regex("""\p{Mn}+""")

    fun stripDiacritics(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

    fun normalizeTitle(s: String): String {
        var t = stripDiacritics(s).lowercase()
        t = BRACKET_NOISE.replace(t, " ")
        t = CREDIT_BRACKET.replace(t, " ")
        t = FEAT_TAIL.replace(t, " ")
        t = DASH_TAIL.replace(t, " ")
        t = t.replace("&", " and ").replace("$", "s")
        t = APOSTROPHES.replace(t, "")
        t = joinInitials(t)
        t = NON_ALNUM.replace(t, " ")
        t = SPACES.replace(t, " ").trim()
        // "Oxygene, Pt. 4", "Oxygene (Part IV)" and "Oxygene Part 4" are one title (issue #57).
        return PART.replace(t) { m -> "part " + (m.groupValues[1].toIntOrNull() ?: roman(m.groupValues[1]) ?: return@replace m.value) }
    }

    /** A numbered part: "pt 4", "part iv", "teil 2", once punctuation is gone. */
    private val PART = Regex("""\b(?:pts?|parts?|parte|partie|teil)\s+(\d+|[ivxlc]+)\b""")

    /** A Roman numeral's value ("iv" → 4), or null when it is not one written the usual way. */
    private fun roman(s: String): Int? {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (i in s.indices) {
            val a = v[s[i]] ?: return null
            val b = s.getOrNull(i + 1)?.let { v[it] } ?: 0
            total += if (a < b) -a else a
        }
        return total.takeIf { it in 1..199 && toRoman(it) == s }
    }

    private fun toRoman(n: Int): String {
        var x = n
        val sb = StringBuilder()
        for ((value, sym) in listOf(100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i"))
            while (x >= value) { sb.append(sym); x -= value }
        return sb.toString()
    }

    /** [title]'s subtitle as written (" - Live", " (Remastered)"), or "" when it has none. */
    fun subtitleOf(title: String): String = SUBTITLE.find(title)?.value.orEmpty()

    /** A subtitle as written: from the first bracket or spaced dash on ("Song (Live)", "Song - Mono"). */
    private val SUBTITLE = Regex("""\s*(?:[(\[]|\s[-–—]\s).*$""")
    /** A numbered part as written, with what follows: ", Pt. 4", " Part IV", " - Teil 2". */
    private val PART_AS_WRITTEN = Regex("""[\s,:;.\-–—]*\b(?:pts?\.?|parts?|parte|partie|teil)\s*(?:\d+|[ivxlc]+)\b.*$""", RegexOption.IGNORE_CASE)

    /**
     * The title alone, as written: without its subtitle, numbered part, featuring or version
     * ("Oxygène, Pt. 4" → "Oxygène", "Song (Something Else) - Mono" → "Song"), or null when that
     * leaves the title as it was or too short to search. Only an extra search: the scoring still
     * compares the whole titles, so another part or another version stays another song.
     */
    fun bareTitle(title: String): String? {
        val t = PART_AS_WRITTEN.replace(SUBTITLE.replace(title, ""), "").trim().trimEnd(',', ':', ';', '-', '–', '—', ' ')
        return t.takeIf { normalizeTitle(it).length >= 3 && !it.equals(title.trim(), ignoreCase = true) }
    }

    /**
     * What a server whose search matches names only is asked for a song, in turn: the title folded,
     * as written (servers that do not fold accents), then the bare title folded and as written.
     */
    fun titleTerms(title: String): List<String> {
        val bare = bareTitle(title)
        return listOfNotNull(searchTitle(title), plainTitle(title), bare?.let { searchTitle(it) }, bare)
    }

    /**
     * The candidates a server whose search matches names only finds for [track]: [found] (what it
     * answered already, if anything), then each of [terms] in turn, until one candidate is close
     * enough to be the track. Each term is asked once, and only when the answers so far are only
     * other songs: a first search that brings unrelated names ("Oxygene part 4" finding another
     * piece) no longer stops the bare title from being tried (#57). The candidates come back
     * without repeats, in the order found.
     */
    suspend fun searchByTitles(
        track: Track,
        terms: List<String> = titleTerms(track.title),
        found: List<Track> = emptyList(),
        find: suspend (String) -> List<Track>,
    ): List<Track> {
        val s = norm(track)
        val all = LinkedHashMap<String, Track>()
        var good = false
        fun take(tracks: List<Track>) = tracks.forEach { c ->
            if (all.putIfAbsent(c.id, c) == null && !good) good = scoreN(s, norm(c), SERVER_POLICY) >= REVIEW_THRESHOLD
        }
        take(found)
        for (term in terms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }) {
            if (good) break
            take(find(term))
        }
        return all.values.toList()
    }

    /** A numbered part in a normalised title ("part 4"), its stem before it. */
    private val PART_N = Regex("""\bpart (\d+)\b""")
    /** A title ending in a bare number, Arabic or Roman: "magnetic fields iii", "oxygene 4". */
    private val TRAILING_N = Regex("""^(.+) (\d+|[ivxlc]+)$""")

    /**
     * [t] with its bare trailing number written as a part ("magnetic fields iii" → "magnetic fields
     * part 3") when [other] names a part of the same title ("magnetic fields part 2"): the number
     * then counts as the part, so another part stays another piece and the same part is the same
     * title (#57). [t] as it is otherwise.
     */
    internal fun asPart(t: String, other: String): String {
        if (PART_N.containsMatchIn(t)) return t
        val p = PART_N.find(other) ?: return t
        val m = TRAILING_N.matchEntire(t) ?: return t
        if (m.groupValues[1] != other.substring(0, p.range.first).trim()) return t
        val n = m.groupValues[2].toIntOrNull() ?: roman(m.groupValues[2]) ?: return t
        return m.groupValues[1] + " part " + n
    }

    private fun partOf(t: String): String? = PART_N.find(t)?.groupValues?.get(1)

    /** The part a title names ("Oxygene, Pt. 4", "Magnetic Fields III" → "4", "3"), or null when it names none. */
    fun partNumber(title: String): String? {
        val n = normalizeTitle(title)
        return partOf(n) ?: TRAILING_N.matchEntire(n)?.groupValues?.get(2)?.let { it.toIntOrNull()?.toString() ?: roman(it)?.toString() }
    }

    /** The numbers in a normalised title, as words: two titles with different ones are different pieces. */
    private fun numbers(t: String): Set<String> = t.split(' ').filter { w -> w.isNotEmpty() && w.all { it.isDigit() } }.toSet()

    fun normalizeArtist(s: String): String {
        var a = stripDiacritics(s).lowercase()
        a = ARTIST_TAIL.replace(a, " ")
        // "Beatles, The": a library's sort name, the same band.
        a = THE_LAST.replace(a, "")
        a = a.replace("&", " and ").replace("$", "s")
        a = APOSTROPHES.replace(a, "")
        a = joinInitials(a)
        a = NON_ALNUM.replace(a, " ")
        a = SPACES.replace(a, " ").trim()
        return a.removePrefix("the ").trim()
    }

    /**
     * For servers whose search matches titles only (Jellyfin, Plex, Gonic, Ampache): the songs by the
     * searched artist first, the rest after, each group in the server's order.
     */
    fun byArtist(track: Track, found: List<Track>): List<Track> {
        val want = track.artists.map { Hanzi.simplified(normalizeArtist(it)) }.filter { it.isNotEmpty() }.toSet()
        return found.sortedByDescending { t -> t.artists.any { Hanzi.simplified(normalizeArtist(it)) in want } }
    }

    /** Versioni compatte per costruire le query di ricerca. */
    fun searchTitle(title: String): String = normalizeTitle(title).ifBlank { title.trim() }
    fun searchArtist(artist: String): String = normalizeArtist(artist).ifBlank { artist.trim() }

    /**
     * The title without the decorations [searchTitle] drops (feat., remaster, version brackets) but
     * with its accents and case: for servers whose search does not fold accents (Jellyfin 10.8 finds
     * "Café Noir" by "Café", never by "cafe").
     */
    fun plainTitle(title: String): String =
        SPACES.replace(DASH_TAIL.replace(FEAT_TAIL.replace(CREDIT_BRACKET.replace(BRACKET_NOISE.replace(title, " "), " "), " "), " "), " ")
            .trim().trimEnd('-', '–', '—', ' ').ifBlank { title.trim() }

    /**
     * The first name of an artist field that holds several ("Calvin Harris, Dua Lipa", "Mahmood &
     * Blanco"), or null when it holds one. It also splits real names ("Earth, Wind & Fire"), so it
     * only feeds an extra search: what comes back is still scored against the whole original field.
     */
    fun primaryArtist(artist: String): String? {
        val first = splitArtists(ARTIST_TAIL.replace(artist, ""), ARTIST_PARTS).firstOrNull()?.trim().orEmpty()
        return first.takeIf { it.isNotEmpty() && !it.equals(artist.trim(), ignoreCase = true) }
    }

    /** One act whose name reads as several ("Of Mice & Men", "Simon & Garfunkel", "Mumford & Sons"): never split. */
    fun oneAct(name: String): Boolean = normalizeArtist(name) in BANDS

    /**
     * [field] split where [separators] says, except where the pieces together are [oneAct]: "Of Mice
     * & Men, Bring Me the Horizon" is two names, not three. Blank pieces are left out.
     */
    fun splitArtists(field: String, separators: Regex): List<String> {
        val pieces = ArrayList<IntRange>()
        var from = 0
        for (m in separators.findAll(field)) {
            if (m.range.isEmpty()) continue
            pieces += from until m.range.first
            from = m.range.last + 1
        }
        pieces += from until field.length
        val out = ArrayList<String>()
        var i = 0
        while (i < pieces.size) {
            val j = (pieces.size - 1 downTo i + 1).firstOrNull { j -> oneAct(field.substring(pieces[i].first, pieces[j].last + 1)) } ?: i
            out += field.substring(pieces[i].first, pieces[j].last + 1).trim()
            i = j + 1
        }
        return out.filter { it.isNotEmpty() }
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** Similarita' 0..1 tra due stringhe gia' normalizzate: max tra ratio di Levenshtein, Jaccard sui token e contenimento. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val lev = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val jaccard = if (ta.isEmpty() || tb.isEmpty()) 0.0 else inter / ta.union(tb).size
        val contained = (ta.isNotEmpty() && tb.containsAll(ta)) || (tb.isNotEmpty() && ta.containsAll(tb))
        val containment = if (contained) 0.9 else 0.0
        return maxOf(lev, jaccard, containment)
    }

    fun artistScore(a: List<String>, b: List<String>): Double =
        artistScoreN(a.map { Hanzi.simplified(normalizeArtist(it)) }.filter { it.isNotEmpty() }, b.map { Hanzi.simplified(normalizeArtist(it)) }.filter { it.isNotEmpty() })

    private fun artistScoreN(na: List<String>, nb: List<String>): Double {
        if (na.isEmpty() || nb.isEmpty()) return 0.5 // sconosciuto: neutro
        var best = 0.0
        for (x in na) for (y in nb) best = max(best, similarity(x, y))
        val ja = na.joinToString(" "); val jb = nb.joinToString(" ")
        if (ja.contains(jb) || jb.contains(ja)) best = max(best, 0.9)
        if (best < 1.0 && na.any { x -> nb.any { y -> sameName(x, y) } }) best = 1.0
        // Another name of the same artist, from MusicBrainz (周杰倫 and Jay Chou, #81).
        if (best < 0.95 && na.any { x -> nb.any { y -> ArtistAliases.same(x, y) } }) best = 0.95
        return best
    }

    /** A YouTube channel's or a label's way of naming an artist: "Adele - Topic", "AdeleVEVO", "Adele Official", "Adele Music". */
    private val CHANNEL_TAIL = Regex("""(\s+(official|music|official music|topic|band official))+$|vevo$""")

    /**
     * Two normalised artist names that are one written apart or joined ("AC/DC" and "ACDC", "Jay-Z" and
     * "Jay Z"), or the same with a channel's tail ("Adele - Topic", "AdeleVEVO", "Daft Punk Music").
     */
    internal fun sameName(a: String, b: String): Boolean {
        fun forms(n: String): Set<String> = setOf(n, CHANNEL_TAIL.replace(n, "")).map { it.replace(" ", "") }.filter { it.length >= 2 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val fb = forms(b)
        return forms(a).any { it in fb }
    }

    /**
     * The lengths: the same within 2.5 s, or 5 s when [loose] (a remaster, a clean or explicit
     * edition, where another fade or a muted word moves the end a little).
     */
    fun durationFactor(a: Long, b: Long, loose: Boolean = false): Double {
        if (a <= 0 || b <= 0) return 0.95
        val d = abs(a - b)
        return when {
            d <= (if (loose) 5_000 else 2_500) -> 1.0
            d <= 10_000 -> 0.93
            d <= 25_000 -> 0.8
            else -> 0.5
        }
    }

    /**
     * What a title in Hebrew or Arabic counts against a Latin one with the same consonants: with the
     * same artist it is accepted, but under [REVIEW_THRESHOLD], so the user is asked to check it.
     */
    private const val ABJAD_TITLE = 0.75

    /**
     * How alike [a] and [b] are once both are in Latin letters, when exactly one of them is written in
     * another script that [Translit] reads; 0 for every other pair, and for names too short to tell.
     * Hebrew and Arabic write no vowels: the consonants must be the same ones, and that counts [abjad].
     */
    private fun acrossScripts(a: String, b: String, abjad: Double, normalize: (String) -> String): Double {
        if (Translit.foreign(a) == Translit.foreign(b)) return 0.0
        val x = normalize(Translit.latin(a) ?: return 0.0)
        val y = normalize(Translit.latin(b) ?: return 0.0)
        if (Translit.abjad(a) || Translit.abjad(b)) {
            val kx = Translit.skeleton(x)
            return if (kx.length >= 3 && similarity(kx, Translit.skeleton(y)) >= 1.0) abjad else 0.0
        }
        return if (minOf(x.length, y.length) >= 3) similarity(x, y) else 0.0
    }

    /** Brano con titolo/artisti gia' normalizzati (evita di rifare le regex a ogni confronto). */
    class Norm(val track: Track, val title: String, val artists: List<String>, val isrc: String?) {
        /** Something of it is written in another script than Latin: only then is it compared across scripts. */
        val foreign = Translit.foreign(track.title) || track.artists.any { Translit.foreign(it) }
        /** What a candidate is compared by, read once however many it meets (an index's every track). */
        internal val versions: Set<String> by lazy { versionMarkers(track.title) }
        internal val markers: Set<String> by lazy { versions - NEUTRAL_VERSIONS }
        internal val remixers: Set<String> by lazy { remixers(track.title) }
        internal val people: List<String> by lazy { people(track) }
        internal val imitator: Boolean by lazy { track.artists.any { IMITATOR.containsMatchIn(stripDiacritics(it).lowercase()) } }
    }

    /** A track's names folded for comparing: the normalised forms, traditional Chinese as simplified (#81). */
    fun norm(t: Track) = Norm(
        t, Hanzi.simplified(normalizeTitle(t.title)),
        t.artists.map { Hanzi.simplified(normalizeArtist(it)) }.filter { it.isNotEmpty() }, t.isrcNorm,
    )

    /** Versions that are the same recording: "remaster", "mono", "stereo". */
    private val NEUTRAL_VERSIONS = setOf("remaster", "mono", "stereo")

    // ------------------------------------------------------------------ who else sings (feat.)

    /** Where a title credits someone: "(feat. X)", "[ft. X]", "(with X)", "(con X)", "(part. X)", "(avec X)". */
    private val TITLE_CREDIT = Regex(
        """[(\[]\s*(?:feat\.?|ft\.?|featuring|with|con|w/|avec|mit|com|part\.|participa[cç][aã]o(?:\s+especial)?(?:\s+de)?)\s+([^)\]]+)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    /** "Song feat. X", "Song - ft. X": up to a bracket or a spaced dash. */
    private val TITLE_CREDIT_TAIL = Regex("""\s(?:[-–—]\s+)?(?:feat\.?|ft\.?|featuring)\s+([^(\[]+?)(?=\s+[-–—]\s|\s*[(\[]|$)""", RegexOption.IGNORE_CASE)
    /** An artist field's guests, always: "A feat. B", "A ft. B", "A featuring B", "A with B", "A vs. B". */
    private val FIELD_CREDIT = Regex("""\s+(?:feat\.?|ft\.?|featuring|with|vs\.?)\s+""", RegexOption.IGNORE_CASE)
    /** Several names in one field: "A, B", "A; B" (Navidrome), "A & B", "A + B", "A / B", "A x B", "A and B", "A、B". */
    private val FIELD_SPLIT = Regex("""\s*[,;、，]\s*|\s+(?:&|\+|/|x|and)\s+""", RegexOption.IGNORE_CASE)

    /**
     * Bands whose name reads as several people. Most are told by their "the" ("Bob Marley & The
     * Wailers", "Tyler, The Creator": a name after the first one that starts with "the" keeps the
     * field whole) or by a short tail (", Jr."); these by name.
     */
    private val BANDS = setOf(
        "simon and garfunkel", "earth wind and fire", "hall and oates", "daryl hall and john oates", "crosby stills and nash",
        "crosby stills nash and young", "emerson lake and palmer", "blood sweat and tears", "peter paul and mary",
        "mumford and sons", "belle and sebastian", "iron and wine", "chase and status", "above and beyond", "years and years",
        "angus and julia stone", "brooks and dunn", "big and rich", "dan and shay", "sam and dave", "ike and tina turner",
        "ashford and simpson", "peaches and herb", "womack and womack", "of monsters and men", "coheed and cambria",
        "seals and crofts", "loggins and messina", "captain and tennille", "she and him", "matt and kim", "love and rockets",
        "jorge and mateus", "henrique and juliano", "chitaozinho and xororo", "ze neto and cristiano", "maiara and maraisa",
        "simone and simaria", "matheus and kauan", "bruno and marrone", "zeze di camargo and luciano", "victor and leo",
        "of mice and men", "dimitri vegas and like mike", "tegan and sara", "axwell and ingrosso", "w and w", "macklemore and ryan lewis",
        "for king and country", "nina and frederik", "zager and evans", "jan and dean", "chad and jeremy", "flatt and scruggs",
    )

    /**
     * The people a track names, normalised, the main artist first: every name of its artist fields
     * (split where a field holds several) and those its title credits ("(feat. X)", "(with X)").
     * A producer ("prod. X") is not one. The same for every service, wherever it writes them.
     */
    internal fun people(t: Track): List<String> {
        val out = LinkedHashSet<String>()
        fun add(name: String) {
            val n = Hanzi.simplified(normalizeArtist(name))
            // "Pt. 2" is not a guest: a credit that is only a number.
            if (n.isNotEmpty() && !n.all { it.isDigit() || it == ' ' } && roman(n) == null) out += n
        }
        fun addField(field: String) {
            for (piece in field.split(FIELD_CREDIT)) {
                val parts = piece.split(FIELD_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
                val whole = parts.size < 2 || normalizeArtist(piece) in BANDS ||
                    parts.drop(1).any { it.lowercase().startsWith("the ") || it.trimEnd('.').length <= 2 }
                if (whole) add(piece) else splitArtists(piece, FIELD_SPLIT).forEach(::add)
            }
        }
        t.artists.forEach(::addField)
        (TITLE_CREDIT.findAll(t.title) + TITLE_CREDIT_TAIL.findAll(t.title)).forEach { addField(it.groupValues[1]) }
        return out.toList()
    }

    /** Two normalised names of one person, as far as can be told; true when it cannot be told (scripts that do not compare). */
    private fun samePerson(a: String, b: String): Boolean {
        if (similarity(a, b) >= 0.85 || sameName(a, b) || ArtistAliases.same(a, b)) return true
        if (Translit.foreign(a) == Translit.foreign(b)) return false
        val x = Translit.latin(a)?.let(::normalizeArtist) ?: return true
        val y = Translit.latin(b)?.let(::normalizeArtist) ?: return true
        return similarity(x, y) >= 0.85
    }

    /**
     * Both tracks credit guests, and not one of them is the same: "Old Town Road (feat. Billy Ray
     * Cyrus)" against "Old Town Road (feat. RM)", most likely another version. The main artists of
     * either side are no guests, nor is a name that is part of one ("Jorge & Mateus" written as one
     * name on one side and as two on the other). One side naming no guest says nothing: services put
     * them in different places, or leave them out.
     */
    private fun guestsDiffer(s: Norm, c: Norm): Boolean {
        val ps = s.people; val pc = c.people
        if (ps.size < 2 || pc.size < 2) return false
        val mains = listOfNotNull(ps.first(), pc.first(), s.artists.firstOrNull(), c.artists.firstOrNull())
        fun guest(p: String) = mains.none { m -> samePerson(p, m) || " $m ".contains(" $p ") }
        val gs = ps.filter(::guest); val gc = pc.filter(::guest)
        if (gs.isEmpty() || gc.isEmpty()) return false
        return gs.none { a -> gc.any { b -> samePerson(a, b) } }
    }

    // ------------------------------------------------------------------ remixes, tributes, karaoke

    /** A bracket's or a spaced dash's words: "(Tiësto Remix)", "[Kygo Edit]", " - David Guetta Remix". */
    private val SEGMENT = Regex("""[(\[]([^)\]]*)[)\]]|\s[-–—]\s+([^-–—(\[]+)""")
    private val REMIX_WORD = Regex("""\b(remix(ed)?|rmx|rework|bootleg|flip|edit|mix|dub|vip|remezcla)\b""", RegexOption.IGNORE_CASE)
    /** The words of a remix's name that are not who made it. */
    private val REMIX_GENERIC = setOf(
        "remix", "remixed", "rmx", "rework", "bootleg", "flip", "edit", "mix", "dub", "vip", "remezcla", "radio", "extended",
        "original", "club", "version", "official", "short", "long", "instrumental", "vocal", "main", "album", "single", "clean",
        "explicit", "dirty", "edition", "intro", "outro", "by", "and", "x", "vs", "feat", "ft", "featuring", "with", "remastered",
        "remaster", "mixed", "re", "new", "full", "video", "audio", "deluxe", "live", "acoustic", "mono", "stereo", "de", "del",
        "la", "el", "di", "mashup", "special", "bonus", "track", "uk", "us", "inch", "the", "a", "an", "of", "slowed", "sped",
        "up", "reverb", "nightcore", "8d", "remasterizado", "remasterizada",
    )

    /** Who made the remix or edit [title] names ("Song (Tiësto Remix)" → tiesto), empty for a plain one ("Radio Edit", "Extended Mix"). */
    internal fun remixers(title: String): Set<String> = SEGMENT.findAll(title).flatMap { m ->
        val seg = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (!REMIX_WORD.containsMatchIn(seg)) emptySequence()
        else Hanzi.simplified(normalizeArtist(seg)).split(' ').asSequence().filter { it.length > 1 && it !in REMIX_GENERIC && !it.all(Char::isDigit) }
    }.toSet()

    /** An artist that only plays others' songs: tribute and karaoke acts, "in the style of", lullaby and string covers. */
    private val IMITATOR = Regex(
        """\b(tribute|karaoke|cover band|covers band|in the style of|made famous by|originally performed|sing king|ameritz|party tyme|the hit crew|vitamin string quartet|rockabye baby|twinkle twinkle little rock star|8-bit arcade|kidz bop|piano dreamers|lullaby renditions)\b""",
    )

    fun score(src: Track, cand: Track, policy: MatchPolicy = MatchPolicy()): Double = scoreN(norm(src), norm(cand), policy)

    fun scoreN(s: Norm, c: Norm, policy: MatchPolicy = MatchPolicy()): Double {
        // A show with a known feed or Apple id (an OPML file, Apple's directory) is told by them, then by
        // title and publisher (see Podcasts). Shows with neither are scored as they always were.
        if ((s.track.kind == Track.KIND_PODCAST || c.track.kind == Track.KIND_PODCAST) && Podcasts.knowsMore(s.track, c.track)) return Podcasts.score(s.track, c.track)
        if (s.isrc != null && c.isrc != null && s.isrc == c.isrc) return 1.0 * policyFactor(s.track, c.track, policy)
        // The same MusicBrainz recording (a tagged file on a personal server): exact, whatever the title says.
        if (s.track.mbids.isNotEmpty() && c.track.mbids.any { it in s.track.mbids }) return 1.0 * policyFactor(s.track, c.track, policy)
        // "Magnetic Fields III" against "Magnetic Fields, Pt. 2": the bare number is the part (#57).
        val st = asPart(s.title, c.title); val ct = asPart(c.title, s.title)
        // Two numbered parts: "part 4" on both says nothing of the piece. "Chants Magnetiques, Pt. 4" is
        // not "Equinoxe, Part 4", not even as the closest one (#57, 14/11): the titles are compared without it.
        // A title that is nothing but its part ("Pt. 2", "Part II") has nothing left to compare: as it is.
        val sb = PART_N.replace(st, " ").trim(); val cb = PART_N.replace(ct, " ").trim()
        var title = if (partOf(st) != null && partOf(ct) != null && !(sb.isEmpty() && cb.isEmpty())) similarity(sb, cb).let { bare ->
            if (bare >= 1.0 && partOf(st) == partOf(ct)) similarity(st, ct) else minOf(similarity(st, ct), bare)
        } else similarity(st, ct)
        var artist = artistScoreN(s.artists, c.artists)
        val artistKnown = s.artists.isNotEmpty() && c.artists.isNotEmpty()
        // One side in another writing system ("Кино — Группа крови" for "Kino — Gruppa krovi"): compared
        // in Latin letters, and only to raise a score. As strict as the other names of #57: the artist
        // must be the same one once both are in Latin letters, the lengths within a fifth where both are
        // known; a shared title alone is another song. Two Latin names never come here.
        var byConsonants = false
        val sameLength = s.track.durationMs <= 0 || c.track.durationMs <= 0 ||
            abs(s.track.durationMs - c.track.durationMs) * 5 <= max(s.track.durationMs, c.track.durationMs)
        if ((s.foreign || c.foreign) && artistKnown && sameLength && Features.v2) {
            val across = max(artist, s.track.artists.maxOf { a -> c.track.artists.maxOf { b -> acrossScripts(a, b, 1.0, ::normalizeArtist) } })
            if (across >= 0.9) {
                // The artist told by consonants alone (Hebrew, Arabic) can be another one: never taken unchecked.
                byConsonants = artist < 0.9 && (s.track.artists + c.track.artists).any { Translit.abjad(it) }
                artist = across
                title = max(title, acrossScripts(s.track.title, c.track.title, ABJAD_TITLE, ::normalizeTitle))
            }
        }
        val base = if (artistKnown) 0.6 * title + 0.4 * artist else 0.85 * title + 0.15 * artist
        // "Part 4" against "Part 5", "No. 1" against "No. 2": close titles, different pieces.
        val na = numbers(st); val nb = numbers(ct)
        // A real conflict only: "1 5" against "1" (a range written two ways) is the same piece.
        val numberFactor = if (na.isNotEmpty() && nb.isNotEmpty() && !na.containsAll(nb) && !nb.containsAll(na)) 0.6 else 1.0
        // Another part of the same piece is never the track, nor even proposed as the closest one.
        val pa = partOf(st); val pb = partOf(ct)
        val partFactor = if (pa != null && pb != null && pa != pb) 0.3 else 1.0
        // The same part of the same title by the same artist: another edition of the piece, whose
        // length often differs (a remaster, another fade). Checked, not refused (#57).
        val duration = durationFactor(s.track.durationMs, c.track.durationMs, looseLength(s, c)).let { d ->
            // Only within a third of the longer length: a 4-minute "Pt. 1" is not the 26-minute one.
            val near = s.track.durationMs > 0 && c.track.durationMs > 0 &&
                abs(s.track.durationMs - c.track.durationMs) * 3 <= max(s.track.durationMs, c.track.durationMs)
            if (pa != null && pa == pb && st == ct && artist >= 0.9 && near) max(d, 0.8) else d
        }
        // "Song (Tiësto Remix)" against "Song (David Guetta Remix)": both remixes, by different people.
        val remix = if (s.remixers.isNotEmpty() && c.remixers.isNotEmpty() && s.remixers.none { it in c.remixers }) 0.6 else 1.0
        // A tribute or karaoke act is never the artist it imitates, however much of the name it holds.
        val imitation = if (s.imitator != c.imitator && artistKnown) 0.3 else 1.0
        val version = versionFactorN(s.markers, c.markers)
        val clean = numberFactor * partFactor * version * remix * imitation * liveAlbumFactor(s.track.album, c.track.album, policy)
        var score = base * duration * clean * albumFactor(s.track.album, c.track.album, policy) * policyFactor(s.track, c.track, policy)
        if (artistKnown && artist < 0.9 && clean >= 1.0) score = otherNames(s, c, title, score, policy)
        // Both name guests, and none is the same: another version, most likely. Never refused for it: checked.
        if (guestsDiffer(s, c)) score = minOf(score, policy.reviewThreshold - 0.01)
        if (byConsonants) score = minOf(score, REVIEW_THRESHOLD - 0.01)
        if (policy.fileNames && score < 1.0) fileTitles(c.track.title, s.track.title).takeIf { it.isNotEmpty() && fileNameSafe(s, c) }?.let { titles ->
            val plain = policy.copy(fileNames = false)
            for (t in titles) score = max(score, scoreN(s, norm(c.track.copy(title = t)), plain))
        }
        return score
    }

    /** A track number before a title taken from a file's name: "06 In the End", "13 - In the End", "1-06. In the End". */
    private val TRACK_NUMBER = Regex("""^\d{1,3}(?:-\d{1,3})?(?:\s*[.\-_)]\s*|\s+)""")
    /** The " 1" iTunes puts after the name of a file it copied twice: "04 - Ide Were Were 1". */
    private val COPY_ONE = Regex("""\s+1$""")

    /**
     * The titles a personal server's song [title] may stand for when the server took it from the
     * file's name (files without tags): without its track number ("06 In the End" → "In the End"),
     * and without iTunes' " 1" of a second copy, unless the source's [srcTitle] ends in it too.
     * Empty when it has neither. Only tried where the artist, the album and the length agree
     * ([fileNameSafe]): "7 Rings", "22", "1999" and "99 Problems" stay what they are.
     */
    internal fun fileTitles(title: String, srcTitle: String): List<String> {
        val t = title.trim()
        val noNumber = TRACK_NUMBER.find(t)?.let { t.substring(it.range.last + 1).trim() }?.takeIf { normalizeTitle(it).isNotEmpty() }
        val oneOk = !COPY_ONE.containsMatchIn(srcTitle.trim())
        fun noOne(x: String) = if (oneOk) COPY_ONE.replace(x, "").trim().takeIf { it != x && normalizeTitle(it).isNotEmpty() } else null
        return listOfNotNull(noNumber, noNumber?.let(::noOne), noOne(t)).distinct()
    }

    /**
     * Nothing tells [c] from the source but its title: the artists agree where both are known, the
     * albums where both are known, and the lengths within 5 s where both are known; with no length,
     * the artist and the album must both agree.
     */
    private fun fileNameSafe(s: Norm, c: Norm): Boolean {
        val artistKnown = s.artists.isNotEmpty() && c.artists.isNotEmpty()
        val artistOk = artistKnown && artistScoreN(s.artists, c.artists) >= 0.9
        if (artistKnown && !artistOk) return false
        val albums = s.track.album.isNotBlank() && c.track.album.isNotBlank()
        val albumOk = albums && sameAlbum(s.track.album, c.track.album)
        if (albums && !albumOk) return false
        val a = s.track.durationMs; val b = c.track.durationMs
        return if (a > 0 && b > 0) abs(a - b) <= 5_000 && (artistOk || albumOk) else artistOk && albumOk
    }

    /** A remaster, a clean or explicit edition on either side: its length may differ by a few seconds more. */
    private val EDITION_WORDS = Regex("""\b(remaster\w*|clean|explicit|censored|uncensored|radio edit)\b""", RegexOption.IGNORE_CASE)

    private fun looseLength(s: Norm, c: Norm): Boolean =
        "remaster" in s.versions || "remaster" in c.versions ||
            EDITION_WORDS.containsMatchIn(s.track.title) || EDITION_WORDS.containsMatchIn(c.track.title) ||
            EDITION_WORDS.containsMatchIn(s.track.album) || EDITION_WORDS.containsMatchIn(c.track.album) ||
            (s.track.explicit != null && c.track.explicit != null && s.track.explicit != c.track.explicit)

    /**
     * The same song whose artists are written so differently that their names do not meet (#81):
     * - in two scripts ("周杰倫" against "Jay Chou"): a title alike (0.8), an album alike (0.8) and the
     *   lengths within 2 s make it the song, to check: accepted, in the review;
     * - with the sync's "artist names can differ" on: the same title (0.95) and lengths within 3 s
     *   make it the song with the same album, and a song to check where an album is unknown.
     * Otherwise [score] as it is. Only for a candidate with nothing else against it.
     */
    private fun otherNames(s: Norm, c: Norm, title: Double, score: Double, policy: MatchPolicy): Double {
        val a = s.track.durationMs; val b = c.track.durationMs
        if (a <= 0 || b <= 0) return score
        val d = abs(a - b)
        val sa = s.track.album; val ca = c.track.album
        val check = policy.reviewThreshold - 0.01
        if (policy.artistsMayDiffer && title >= 0.95 && d <= 3_000) {
            return when {
                sa.isBlank() || ca.isBlank() -> max(score, check)
                sameAlbum(sa, ca) -> max(score, policy.reviewThreshold)
                else -> score
            }
        }
        if (title >= 0.8 && d <= 2_000 && sa.isNotBlank() && ca.isNotBlank() && scriptsDiffer(s.track.artists, c.track.artists) &&
            similarity(normalizeAlbum(sa), normalizeAlbum(ca)) >= 0.8) return max(score, check)
        return score
    }

    /** One side's artists are written in another script than Latin, the other side's all in Latin letters. */
    fun scriptsDiffer(a: List<String>, b: List<String>): Boolean {
        val fa = a.any { Translit.foreign(it) }; val fb = b.any { Translit.foreign(it) }
        return (fa && b.isNotEmpty() && b.none { Translit.foreign(it) }) || (fb && a.isNotEmpty() && a.none { Translit.foreign(it) })
    }

    /**
     * Le preferenze della sync sopra le regole di base. Una preferenza (esplicito/pulito) e' un
     * fattore leggero: fa vincere il candidato giusto fra due, non scarta l'unico che c'e'. "Solo
     * studio" invece esclude: un live al posto dello studio non passa nemmeno da solo.
     */
    fun policyFactor(src: Track, cand: Track, policy: MatchPolicy): Double {
        var f = 1.0
        if (policy.explicit != 0 && cand.explicit != null) {
            val wantExplicit = policy.explicit > 0
            if (cand.explicit != wantExplicit) f *= 0.9
        }
        if (policy.studioOnly) {
            val extra = (versionMarkers(cand.title) - NEUTRAL_VERSIONS) - (versionMarkers(src.title) - NEUTRAL_VERSIONS)
            if (extra.isNotEmpty()) f *= 0.3
        }
        return f
    }

    /**
     * Stesso titolo e artista ma album diverso (singolo contro album, riedizione, compilation): quasi
     * sempre e' la stessa registrazione, ma non sempre, ed e' il cambio piu' lamentato dagli utenti
     * degli altri strumenti perche' passa in silenzio. Un piccolo fattore fa preferire il candidato
     * dello stesso album quando ce n'e' uno, e manda al riesame gli abbinamenti gia' al limite.
     */
    fun albumFactor(srcAlbum: String, candAlbum: String, policy: MatchPolicy = MatchPolicy()): Double {
        if (srcAlbum.isBlank() || candAlbum.isBlank()) return 1.0
        if (normalizeAlbum(srcAlbum).isEmpty() || normalizeAlbum(candAlbum).isEmpty()) return 1.0
        return if (sameAlbum(srcAlbum, candAlbum)) 1.0 else if (policy.sameAlbum) 0.8 else 0.97
    }

    /** An edition of an album, in brackets or after a dash: "(Deluxe)", "[Expanded Edition]", "- 2011 Remaster", "(Super Deluxe)". */
    private val ALBUM_EDITION = Regex(
        """\s*([(\[][^)\]]*\b(expanded|reissue|re-issue|special|collector'?s?|legacy|platinum|super|complete|japan(ese)?|international|tour|bonus|deluxe|remaster\w*|edition|anniversary|version|\d{4})\b[^)\]]*[)\]]|\s[-–—:]\s*[^-–—:]*\b(edition|deluxe|expanded|remaster\w*|reissue|version)\b.*$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * An album's name for comparing: without its edition ("Abbey Road (Super Deluxe Edition)" is
     * "abbey road", "Rumours - Expanded Edition" is "rumours"), traditional Chinese as simplified.
     */
    fun normalizeAlbum(album: String): String {
        val bare = ALBUM_EDITION.replace(album, " ").trim()
        return Hanzi.simplified(normalizeTitle(bare.ifEmpty { album }))
    }

    /** One album under either name, edition or of its names ("Les Chants Magnétiques / Magnetic Fields"). */
    fun sameAlbum(srcAlbum: String, candAlbum: String): Boolean {
        val a = normalizeAlbum(srcAlbum)
        val b = normalizeAlbum(candAlbum)
        if (a.isEmpty() || b.isEmpty()) return false
        // "Les Chants Magnétiques / Magnetic Fields": one album under either of its names.
        // The whole names as before (one containing the other). The halves of an "A / B" name: whole words
        // only and at least two of them, so "A / B" is not "Banana" and "Blue / Green" is not "Red / Green".
        fun halves(x: String, y: String): Boolean {
            val (short, long) = if (x.length <= y.length) x to y else y to x
            return short.split(' ').size >= 2 && " $long ".contains(" $short ")
        }
        return a == b || a.contains(b) || b.contains(a) ||
            albumNames(srcAlbum, a).any { x -> albumNames(candAlbum, b).any { y -> halves(x, y) } }
    }

    /** An album's normalised name [n] and, for one written "A / B", each of its names. */
    private fun albumNames(album: String, n: String): List<String> =
        listOf(n) + album.split(" / ").takeIf { it.size > 1 }.orEmpty().map { normalizeAlbum(it) }.filter { it.isNotEmpty() }

    fun best(src: Track, candidates: List<Track>, threshold: Double = DEFAULT_THRESHOLD, policy: MatchPolicy = MatchPolicy()): Track? =
        bestScored(src, candidates, threshold, policy)?.track

    data class Scored(val track: Track, val score: Double)

    /** Come best(), ma con il punteggio: sotto REVIEW_THRESHOLD l'abbinamento va mostrato per conferma. */
    fun bestScored(src: Track, candidates: List<Track>, threshold: Double = DEFAULT_THRESHOLD, policy: MatchPolicy = MatchPolicy()): Scored? {
        if (candidates.isEmpty()) return null
        val s = norm(src)
        val best = candidates.map { Scored(it, scoreN(s, norm(it), policy)) }.maxByOrNull { it.score } ?: return null
        return best.takeIf { it.score >= threshold }
    }

    /**
     * Indice dei brani di una playlist per cercare velocemente il "gia' presente": i candidati sono
     * solo i brani che condividono almeno un token del titolo (o l'ISRC), non tutta la lista.
     */
    class TrackIndex(tracks: List<Track>) {
        private val items = tracks.map { norm(it) }
        private val byToken = HashMap<String, MutableList<Int>>()
        private val byIsrc = HashMap<String, Int>()
        /** Shows by feed and by Apple id: a renamed show shares no word of its title with itself. */
        private val byShow = HashMap<String, Int>()

        init {
            items.forEachIndexed { i, n ->
                tokens(n).forEach { byToken.getOrPut(it) { ArrayList() }.add(i) }
                n.isrc?.let { byIsrc.putIfAbsent(it, i) }
                showKeys(n.track).forEach { byShow.putIfAbsent(it, i) }
            }
        }

        /** The words of a title, and in Latin letters too for one in another script: "Группа крови" meets "Gruppa krovi". */
        private fun tokens(n: Norm): Set<String> {
            val own = n.title.split(' ').filter { it.isNotEmpty() }.toSet()
            if (!Translit.foreign(n.track.title) || !Features.v2) return own
            return own + normalizeTitle(Translit.latin(n.track.title) ?: return own).split(' ').filter { it.isNotEmpty() }
        }

        private fun showKeys(t: Track): List<String> = listOfNotNull(
            Podcasts.feedKey(t.feedUrl)?.let { "feed:$it" }, Podcasts.itunesId(t.itunesId)?.let { "itunes:$it" },
        )

        fun best(src: Track, threshold: Double = EXISTING_THRESHOLD, policy: MatchPolicy = MatchPolicy()): Track? {
            val s = norm(src)
            // An identical ISRC already in the target is the same recording, whatever the policy says.
            s.isrc?.let { isrc -> byIsrc[isrc]?.let { return items[it].track } }
            showKeys(src).firstNotNullOfOrNull { byShow[it] }?.let { return items[it].track }
            val candIdx = LinkedHashSet<Int>()
            tokens(s).forEach { tok -> byToken[tok]?.let { candIdx.addAll(it) } }
            var bestScore = 0.0
            var best: Track? = null
            for (i in candIdx) {
                val sc = scoreN(s, items[i], policy)
                if (sc > bestScore) { bestScore = sc; best = items[i].track }
            }
            return if (bestScore >= threshold) best else null
        }
    }
}

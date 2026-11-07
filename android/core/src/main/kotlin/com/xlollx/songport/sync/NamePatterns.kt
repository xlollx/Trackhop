package com.xlollx.songport.sync

/**
 * The user's own ways to read a track from a name or a path in M3U, PLS and text files (#49), one
 * per line, tried in order; a line no pattern matches is read as usual.
 *
 * A pattern is a template with placeholders, matched against the end of the path or name without
 * its extension:
 *
 *     {artist}/{album}/{track} {title}
 *     {artist} - {album} - {track} - {title}
 *     {title} ({artist})
 *
 * `{artist}`, `{album}`, `{title}` are read; `{track}` (a number), `{year}`, `{disc}` and `{any}`
 * (or `*`) are matched and left out; spaces match any spaces. Or a regular expression after `re:`
 * with named groups `artist`, `album`, `title` (required) — `re:^(?<artist>.+?) _ (?<title>.+)$`.
 */
object NamePatterns {

    /** One compiled line: [source] as written. */
    class Pattern(val source: String, val regex: Regex)

    /** What a pattern read from a name; blank fields were not in the pattern. */
    data class Reading(val artist: String, val album: String, val title: String) {
        fun track() = com.xlollx.songport.model.Track(id = "", title = title, artists = CsvCodec.splitArtists(artist), album = album)
    }

    private val PLACEHOLDER = Regex("""\{(artist|album|title|track|year|disc|any)\}|\*|\s+""")

    /** A template or `re:` line as a regex, or the reason it cannot be one. */
    fun compile(line: String): Result<Pattern> = runCatching {
        val src = line.trim()
        require(src.isNotEmpty()) { "Empty pattern" }
        val regex = if (src.startsWith("re:")) {
            Regex(src.removePrefix("re:").trim(), RegexOption.IGNORE_CASE)
        } else {
            val body = StringBuilder()
            var last = 0
            for (m in PLACEHOLDER.findAll(src)) {
                if (m.range.first > last) body.append(Regex.escape(src.substring(last, m.range.first)))
                body.append(
                    when (m.value) {
                        "{artist}" -> "(?<artist>[^/]+?)"
                        "{album}" -> "(?<album>[^/]+?)"
                        "{title}" -> "(?<title>[^/]+?)"
                        "{track}" -> "\\d{1,3}\\.?"
                        "{year}" -> "\\d{4}(?:[.-]\\d{1,2}){0,2}"
                        "{disc}", "{any}", "*" -> "[^/]*?"
                        else -> "\\s+" // a run of spaces
                    }
                )
                last = m.range.last + 1
            }
            if (last < src.length) body.append(Regex.escape(src.substring(last)))
            // Anchored at the end of the path; the start is the start of a folder or of the name.
            Regex("(?:^|/)" + body + "$", RegexOption.IGNORE_CASE)
        }
        require(regex.pattern.contains("(?<title>")) { "A pattern needs {title}" }
        Pattern(src, regex)
    }

    /** The lines that compile; the others are dropped (the editor shows why). */
    fun compileAll(lines: List<String>): List<Pattern> = lines.mapNotNull { compile(it).getOrNull() }

    /**
     * The first pattern that reads [name] (a path or a name; its extension is dropped, backslashes
     * are folders), or null.
     */
    fun read(name: String, patterns: List<Pattern>): Reading? {
        if (patterns.isEmpty()) return null
        val clean = name.trim().replace('\\', '/').let { s ->
            val last = s.substringAfterLast('/')
            if (last.contains('.') && last.substringAfterLast('.').length in 2..4) s.substringBeforeLast('.') else s
        }
        for (p in patterns) {
            val m = p.regex.find(clean) ?: continue
            fun g(n: String) = runCatching { m.groups[n]?.value }.getOrNull()?.replace('_', ' ')?.trim().orEmpty()
            val title = g("title")
            if (title.isNotEmpty()) return Reading(g("artist"), g("album"), title)
        }
        return null
    }
}

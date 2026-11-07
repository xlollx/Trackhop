package com.xlollx.songport.sync

/**
 * The user's own ways to read a track from a name or a path in M3U, PLS and text files (#49), one
 * per line, tried in order; a line no pattern matches is read as usual. Everyone names their files
 * their own way, so the templates are loose on purpose:
 *
 *     {artist}/{album}/{track} {title}
 *     {artist}/{year} - {album}/[Disc {disc}/]{track}. {title}
 *     {artist} - {album} - {track} - {title}
 *     {title} ({artist})
 *
 * - `{artist}`, `{album}`, `{title}` (needed) and `{albumartist}` are read; any other `{name}` —
 *   `{track}`, `{year}`, `{disc}`, `{genre}`, `{any}`… — is matched and left out; `*` is any text in
 *   one folder or name, two stars followed by a slash any number of folders (a discography
 *   folder between the artist and the album, say: then start with the folder above the artist,
 *   `Music/{artist}/` and two stars, or the first folder of the path is taken as the artist).
 * - `[ … ]` is optional (`\[` and `\]` are brackets); `/` separates folders.
 * - Spaces match any spaces or underscores; a dash matches -, – or —, with or without spaces.
 * - Case does not matter; the pattern is matched against the end of the path, without the file's
 *   extension, so the folders above it do not need to be written.
 * - A placeholder used twice, `{artist}/{album}/{artist} - {title}`, is read the first time.
 *
 * Or a regular expression after `re:` with named groups `artist`, `album`, `albumartist`, `title`.
 */
object NamePatterns {

    /** One compiled line: [source] as written. */
    class Pattern(val source: String, val regex: Regex)

    /** What a pattern read from a name; blank fields were not in the pattern. */
    data class Reading(val artist: String, val album: String, val title: String) {
        fun track() = com.xlollx.songport.model.Track(id = "", title = title, artists = CsvCodec.splitArtists(artist), album = album)
    }

    private val READ = setOf("artist", "album", "title", "albumartist")
    private val TOKEN = Regex("""\\[\[\]]|\*\*/?|\*|\{([A-Za-z]+)\}|\s*[-–—]\s*|\s+|\[|\]""")
    private val AUDIO = setOf("mp3", "m4a", "flac", "ogg", "oga", "opus", "wav", "aac", "wma", "alac", "aif", "aiff", "ape", "wv", "mka", "mp4", "m4b", "webm", "dsf", "dff", "mpc")

    /** A template or `re:` line as a regex, or the reason it cannot be one. */
    fun compile(line: String): Result<Pattern> = runCatching {
        val src = line.trim()
        require(src.isNotEmpty()) { "Empty pattern" }
        val regex = if (src.startsWith("re:")) Regex(src.removePrefix("re:").trim(), RegexOption.IGNORE_CASE) else Regex(template(src), RegexOption.IGNORE_CASE)
        require(regex.pattern.contains("(?<title>")) { "A pattern needs {title}" }
        Pattern(src, regex)
    }

    private fun template(src: String): String {
        val body = StringBuilder()
        val seen = HashSet<String>()
        var open = 0
        var last = 0
        for (m in TOKEN.findAll(src)) {
            if (m.range.first > last) body.append(Regex.escape(src.substring(last, m.range.first)))
            val t = m.value
            val name = m.groups[1]?.value?.lowercase()
            body.append(
                when {
                    t == "\\[" || t == "\\]" -> Regex.escape(t.substring(1))
                    t == "[" -> { open++; "(?:" }
                    t == "]" -> { require(open > 0) { "A ] without its [" }; open--; ")?" }
                    t.startsWith("**") -> if (t.endsWith("/")) "(?:[^/]*/)*?" else ".*?"
                    t == "*" -> "[^/]*?"
                    name != null && name in READ && seen.add(name) -> "(?<$name>[^/]+?)"
                    name == "track" -> "(?:\\d{1,2}[-.])?\\d{1,3}\\.?"
                    name == "year" -> "\\d{4}(?:[.-]\\d{1,2}){0,2}"
                    name != null -> "[^/]*?"
                    t.isBlank() -> "[\\s_]+"
                    else -> "[\\s_]*[-–—][\\s_]*" // a dash, spaced or not
                }
            )
            last = m.range.last + 1
        }
        require(open == 0) { "A [ without its ]" }
        if (last < src.length) body.append(Regex.escape(src.substring(last)))
        // Anchored at the end of the path; the start is the start of a folder or of the name.
        return "(?:^|/)$body$"
    }

    /** The lines that compile; the others are dropped (the editor shows why). */
    fun compileAll(lines: List<String>): List<Pattern> = lines.mapNotNull { compile(it).getOrNull() }

    /**
     * The first pattern that reads [name] (a path or a name; an audio file's extension is dropped,
     * backslashes are folders), or null.
     */
    fun read(name: String, patterns: List<Pattern>): Reading? {
        if (patterns.isEmpty()) return null
        val clean = name.trim().replace('\\', '/').let { s ->
            val ext = s.substringAfterLast('/').substringAfterLast('.', "")
            if (ext.lowercase() in AUDIO) s.substringBeforeLast('.') else s
        }
        for (p in patterns) {
            val m = p.regex.find(clean) ?: continue
            fun g(n: String) = runCatching { m.groups[n]?.value }.getOrNull()?.replace('_', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val title = g("title")
            if (title.isNotEmpty()) return Reading(g("artist").ifEmpty { g("albumartist") }, g("album"), title)
        }
        return null
    }
}

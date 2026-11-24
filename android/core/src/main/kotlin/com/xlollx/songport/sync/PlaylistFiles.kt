package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Import/export di playlist come file, in tutti i formati che i servizi musicali (o i loro
 * esportatori) producono davvero. E' il ponte per i servizi senza API aperta — Amazon Music,
 * Qobuz, SoundCloud — e per i backup: qualunque cosa si riesca a esportare da li' entra qui.
 *
 * Formati riconosciuti in lettura:
 *  - **CSV / TSV / CSV con `;`** (Exportify, TuneMyMusic, Soundiiz, "Esporta playlist" di Apple Music)
 *  - **M3U / M3U8** (la maggior parte dei player desktop e Android)
 *  - **XML plist** (Apple Music / iTunes → File › Libreria › Esporta libreria)
 *  - **rekordbox XML** (File › Export Collection in xml format), see [RekordboxXml]
 *  - **WPL / ZPL** (Windows Media Player, Groove, Zune)
 *  - **JSON** (export di Amazon "Request My Data", Soundiiz, backup vari)
 *  - **testo semplice**, una riga per brano: "Artista - Titolo"
 *
 * In scrittura: CSV (completo, con ISRC) e M3U (per i player).
 * Kotlin puro, niente Android: coperto dai test.
 */
object PlaylistFiles {

    enum class Format { CSV, M3U, PLS, ITUNES_XML, REKORDBOX_XML, WPL, XSPF, OPML, JSON, TEXT }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TITLE_KEYS = listOf("title", "trackname", "name", "track", "song", "songname", "tracktitle", "titolo", "brano")
    private val ARTIST_KEYS = listOf("artist", "artists", "artistname", "artistnames", "albumartist", "artista", "artisti", "performer", "creator")
    private val ALBUM_KEYS = listOf("album", "albumname", "albumtitle")
    private val DURATION_KEYS = listOf("durationms", "duration", "length", "time", "totaltime", "durata")
    private val ISRC_KEYS = listOf("isrc")

    fun detect(fileName: String, text: String): Format {
        val name = fileName.lowercase()
        val head = text.take(4000).trimStart().removePrefix("﻿")
        return when {
            head.startsWith("#EXTM3U") || name.endsWith(".m3u") || name.endsWith(".m3u8") -> Format.M3U
            name.endsWith(".pls") || head.startsWith("[playlist]", ignoreCase = true) -> Format.PLS
            name.endsWith(".xspf") || (head.startsWith("<") && head.contains("xspf.org")) -> Format.XSPF
            name.endsWith(".opml") || (head.startsWith("<") && head.contains("<opml", ignoreCase = true)) -> Format.OPML
            // Told by what is inside, whatever the name: rekordbox writes a plain ".xml", as Apple does.
            RekordboxXml.looksLike(head) -> Format.REKORDBOX_XML
            looksLikeWpl(name, head) -> Format.WPL
            head.contains("<plist") || (name.endsWith(".xml") && head.startsWith("<")) -> Format.ITUNES_XML
            head.startsWith("{") || head.startsWith("[") || name.endsWith(".json") -> Format.JSON
            // Shazam's export opens with a title line above the header: look at the first few lines.
            name.endsWith(".csv") || name.endsWith(".tsv") ||
                text.lineSequence().filter { it.isNotBlank() }.take(3).any { l -> l.any { it == ',' || it == ';' || it == '\t' } } -> Format.CSV
            else -> Format.TEXT
        }
    }

    /**
     * A picked file's bytes as text: UTF-8 (a BOM is dropped), UTF-16 with a BOM, and otherwise
     * Windows-1252, what Windows players write in a plain .m3u (where "Beyoncé" is not valid UTF-8),
     * or the Chinese, Japanese, Korean or Cyrillic encoding the text reads right in ([TextCharsets]).
     */
    fun decode(bytes: ByteArray): String {
        fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                String(bytes, TextCharsets.guess(bytes))
            }
        }
    }

    /**
     * How a name with three parts, "A - B - C", reads in the formats that only carry a name (M3U,
     * PLS, plain text): [ARTIST_TITLE] keeps "B - C" as the title, as for "Artist - Song - Radio
     * Edit"; [ARTIST_ALBUM_TITLE] takes B as the album; [AUTO] decides per file, the album layout
     * when most names have three parts (#49); [CUSTOM] reads with the user's [NamePatterns] first.
     */
    enum class NameLayout { AUTO, ARTIST_TITLE, ARTIST_ALBUM_TITLE, CUSTOM;
        companion object { fun of(name: String?): NameLayout = entries.firstOrNull { it.name == name } ?: AUTO }
    }

    /** As [decode], for a file read as it comes: the bytes of [input] as text, a piece at a time. */
    fun reader(input: java.io.InputStream): java.io.Reader = DecodingReader(input)

    fun parse(fileName: String, text: String, layout: NameLayout = NameLayout.AUTO, patterns: List<NamePatterns.Pattern> = emptyList()): List<Track> =
        readStream(fileName, text.reader(), layout, patterns)

    /**
     * The tracks of a playlist file of any size, read a piece at a time (#67): the format is told
     * from the name and the first few KB, then the same stream goes on, a record at a time. The
     * memory taken is the one of the tracks, never of the file. [progress] gets the characters read
     * so far, about every MB; [cancelled] is asked as often and stops the reading with a
     * [java.util.concurrent.CancellationException].
     *
     * A file that ends before its list does (XML, JSON: cut short by an upload or a copy that
     * stopped) throws [ItunesLibrary.Incomplete], so nothing is taken from it. CSV, M3U, PLS and
     * plain text have no end to look for: what is there is read.
     */
    fun readStream(
        fileName: String,
        reader: java.io.Reader,
        layout: NameLayout = NameLayout.AUTO,
        patterns: List<NamePatterns.Pattern> = emptyList(),
        progress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): List<Track> {
        val source = CharSource(reader, progress, cancelled)
        val format = detect(fileName, source.head())
        val custom = if (layout == NameLayout.CUSTOM) patterns else emptyList()
        val tracks = when (format) {
            Format.M3U -> readM3u(source, custom)
            Format.PLS -> readPls(source, custom)
            Format.ITUNES_XML, Format.REKORDBOX_XML -> ItunesLibrary.read(source).asOneList()
            Format.WPL -> readWpl(source, strict = true)
            Format.XSPF -> readXspf(source, strict = true)
            Format.OPML -> readOpml(source, strict = true)
            Format.JSON -> readJson(source, strict = true)
            Format.CSV -> CsvCodec.read(source)
            Format.TEXT -> readText(source, custom)
        }
        return when {
            format != Format.M3U && format != Format.PLS && format != Format.TEXT -> tracks
            layout == NameLayout.CUSTOM -> tracks
            else -> withLayout(tracks, layout)
        }
    }

    private fun incomplete() = ItunesLibrary.Incomplete("The file ends before its list does: it is incomplete. Export it again, or upload it again.")

    /**
     * The tracks read from names, with "Album - Title" titles split when [layout] says so (AUTO: at
     * least two names and four in five of them have a third part). Tracks with an album already (a
     * library path) or without an artist are left as they are.
     */
    fun withLayout(tracks: List<Track>, layout: NameLayout): List<Track> {
        if (layout == NameLayout.ARTIST_TITLE || layout == NameLayout.CUSTOM) return tracks
        fun splittable(t: Track) = t.artists.isNotEmpty() && t.album.isBlank() && DASH.split(t.title, 2).let { it.size == 2 && it.all(String::isNotBlank) }
        if (layout == NameLayout.AUTO) {
            val named = tracks.count { it.artists.isNotEmpty() && it.album.isBlank() }
            val three = tracks.count(::splittable)
            if (three < 2 || three * 5 < named * 4) return tracks
        }
        return tracks.map { t ->
            if (!splittable(t)) return@map t
            val (album, rest) = DASH.split(t.title, 2)
            val title = withoutTrackNumber(rest.trim()).ifBlank { rest.trim() }
            CsvCodec.withStableId(t.copy(title = title, album = album.trim()))
        }
    }

    // ---------------------------------------------------------------- M3U

    private val EXTINF = Regex("""^#EXTINF:\s*(-?\d+(?:\.\d+)?)?\s*(?:[^,]*)?,\s*(.*)$""")

    fun parseM3u(text: String, patterns: List<NamePatterns.Pattern> = emptyList()): List<Track> = readM3u(CharSource(text.reader()), patterns)

    /** A line at a time: an #EXTINF line waits for the path it describes. */
    private fun readM3u(source: CharSource, patterns: List<NamePatterns.Pattern>): List<Track> {
        val out = ArrayList<Track>()
        var pendingSeconds = 0L
        var pendingLabel: String? = null
        while (true) {
            val line = (source.line() ?: break).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val m = EXTINF.find(line) ?: continue
                pendingSeconds = m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }?.toLong() ?: 0
                pendingLabel = m.groupValues[2].trim().ifBlank { null }
                continue
            }
            val t = (pendingLabel?.let { NamePatterns.read(it, patterns) } ?: NamePatterns.read(line, patterns))?.track()
                ?: pendingLabel?.let { label -> splitArtistTitle(label).let { (artist, title) -> Track(id = "", title = title, artists = artist) } }
                ?: fromPath(line)
            if (t.title.isNotBlank()) out += CsvCodec.withStableId(t.copy(durationMs = pendingSeconds * 1000))
            pendingSeconds = 0
            pendingLabel = null
        }
        return out
    }

    // ---------------------------------------------------------------- PLS

    private val PLS_ENTRY = Regex("""^(file|title|length)(\d+)\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** PLS (Winamp, VLC, Clementine): File1=…, Title1=Artist - Title, Length1=seconds. */
    fun parsePls(text: String, patterns: List<NamePatterns.Pattern> = emptyList()): List<Track> = readPls(CharSource(text.reader()), patterns)

    /** How many entries stay open at once: the lines of one entry are together, or nearly. */
    private const val PLS_OPEN = 4096

    /**
     * A line at a time, by entry number. An entry becomes its track once [PLS_OPEN] newer ones have
     * come, so only those are held as written; in a bigger file whose lines are not grouped by entry
     * (every File, then every Title), a line that comes that late makes the entry again on its own.
     */
    private fun readPls(source: CharSource, patterns: List<NamePatterns.Pattern>): List<Track> {
        val open = LinkedHashMap<Int, MutableMap<String, String>>()
        val done = java.util.TreeMap<Int, Track>()
        fun finish(n: Int, e: Map<String, String>) { plsTrack(e, patterns)?.let { done[n] = it } }
        while (true) {
            val m = PLS_ENTRY.find((source.line() ?: break).trim()) ?: continue
            val n = m.groupValues[2].toIntOrNull() ?: continue
            open.getOrPut(n) { HashMap() }[m.groupValues[1].lowercase()] = m.groupValues[3].trim()
            if (open.size > PLS_OPEN) open.entries.iterator().let { it.next().also { e -> finish(e.key, e.value) }; it.remove() }
        }
        open.forEach { (n, e) -> finish(n, e) }
        return ArrayList(done.values)
    }

    private fun plsTrack(e: Map<String, String>, patterns: List<NamePatterns.Pattern>): Track? {
        val t = (e["title"]?.takeIf { it.isNotBlank() }?.let { NamePatterns.read(it, patterns) } ?: e["file"]?.let { NamePatterns.read(it, patterns) })?.track()
            ?: e["title"]?.takeIf { it.isNotBlank() }?.let { label -> splitArtistTitle(label).let { (artist, title) -> Track(id = "", title = title, artists = artist) } }
            ?: e["file"]?.let { fromPath(it) } ?: return null
        val secs = e["length"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0
        return t.takeIf { it.title.isNotBlank() }?.let { CsvCodec.withStableId(it.copy(durationMs = secs * 1000)) }
    }

    /** Folder names that are a music root, not an artist or an album. */
    private val ROOT_FOLDERS = setOf(
        "music", "my music", "musik", "musique", "musica", "música", "muziek", "mp3", "audio", "songs", "download", "downloads",
        "storage", "emulated", "sdcard", "media", "mnt", "home", "users", "volumes", "various artists", "various", "va", "compilations",
    )
    /** What comes before the folders: "http://host", "file://", "file:", "C:". */
    private val URL_OR_DRIVE = Regex("""^(?:[A-Za-z][\w+.-]*://[^/]*|file:|[A-Za-z]:(?=/))""")
    // The track number a file name starts with: "01 - ", "01. ", "1-01-" always; "01 " before
    // "Artist - Title" only when zero-padded ("50 Cent - …" is an artist); "12 " before a bare title.
    private val NUMBER_DASH = Regex("""^(?:\d{1,2}[-.])?\d{1,3}\s*(?:[-.)]\s+|-(?!\d))(?=.*\p{L})""")
    private val PADDED_NUMBER = Regex("""^(?:\d{1,2}[-.])?0\d\s+(?=.*\p{L})""")
    private val NUMBER = Regex("""^(?:\d{1,2}[-.])?\d{1,3}\s+(?=.*\p{L})""")

    private fun withoutTrackNumber(name: String): String = when {
        NUMBER_DASH.containsMatchIn(name) -> NUMBER_DASH.replace(name, "")
        DASH.containsMatchIn(name) -> PADDED_NUMBER.replace(name, "")
        else -> NUMBER.replace(name, "")
    }.trim()

    /** The folder a music library starts in: the next folder is the artist's. */
    private val MUSIC_ROOTS = setOf("music", "my music", "musik", "musique", "musica", "música", "muziek", "mp3")
    /** "(Disc 1) …", "CD2", "Disk 3": a disc of the album above it, not an album. */
    private val DISC_FOLDER = Regex("""^[(\[]?\s*(?:disc|disk|cd)\s*\d+\b""", RegexOption.IGNORE_CASE)
    /** "1977 - ", "2016.12 - ", "[1993] ": the year a folder name starts with, before the album's. */
    private val YEAR_PREFIX = Regex("""^(?:\d{4}(?:[.-]\d{1,2}){0,2}\s+[-–—]\s+|[\[(]\d{4}[\])]\s*[-–—]?\s*)(?=\S)""")

    private fun same(a: String, b: String): Boolean {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return key(a).isNotEmpty() && key(a) == key(b)
    }

    /**
     * A track from a path without #EXTINF: "Artist - Title.mp3" as named, or a library layout:
     * Music/Artist/Album/01 Title.mp3, with folders between the artist and the album (a
     * discography, "Demo albums") or a disc folder below the album, "1977 - " years before album
     * names, and files named "Artist - Album - 02 Title" (#49). Relative or absolute, a file: URI or
     * percent-encoded.
     */
    fun fromPath(path: String): Track {
        val p = (if (path.contains("://") || path.startsWith("file:")) runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path) else path)
            .replace('\\', '/').replace(URL_OR_DRIVE, "")
        val parts = p.split('/').filter { it.isNotBlank() }
        val name = withoutTrackNumber(parts.lastOrNull().orEmpty().substringBeforeLast('.').replace('_', ' ').trim())
        val folders = parts.dropLast(1)
        // "0" of /storage/emulated/0 and drive letters are not names; "1989" can be an album.
        val generic = { f: String -> f.lowercase() in ROOT_FOLDERS || (f.length < 4 && f.all { it.isDigit() || it == ':' }) }

        // The artist's and the album's folders: below a music root the first folder is the artist;
        // otherwise the two folders above the file, as before.
        val root = folders.indexOfLast { it.lowercase() in MUSIC_ROOTS }
        val below = if (root >= 0) folders.drop(root + 1) else folders
        var albumAt = below.lastIndex
        if (albumAt >= 1 && DISC_FOLDER.containsMatchIn(below[albumAt])) albumAt--
        val artistFolder = (if (root >= 0) below.firstOrNull()?.takeIf { albumAt >= 1 } else below.getOrNull(albumAt - 1))
            ?.takeUnless(generic)
        val albumFolder = below.getOrNull(albumAt)?.takeIf { artistFolder != null && !generic(it) }?.let { f ->
            val noYear = YEAR_PREFIX.replace(f, "").trim()
            // "SAULT - 'NINE'" in the folder of SAULT: the album is 'NINE'.
            DASH.split(noYear, 2).let { d -> if (d.size == 2 && same(d[0], artistFolder!!)) d[1].trim() else noYear }
        }.orEmpty()

        // "Artist - Album - 02 Title": three parts whose artist or album the folders confirm.
        val dashed = DASH.split(name)
        if (dashed.size >= 3 && artistFolder != null && (same(dashed[0], artistFolder) || same(dashed[1], albumFolder))) {
            val title = withoutTrackNumber(dashed.drop(2).joinToString(" - ")).ifBlank { dashed.drop(2).joinToString(" - ") }
            return Track(id = "", title = title, artists = CsvCodec.splitArtists(dashed[0]), album = dashed[1].trim())
        }
        val (artists, title) = splitArtistTitle(name)
        if (artists.isNotEmpty()) {
            val album = if (artistFolder != null && artists.any { same(it, artistFolder) }) albumFolder else ""
            return Track(id = "", title = title, artists = artists, album = album)
        }
        if (artistFolder == null || albumFolder.isEmpty()) return Track(id = "", title = title)
        return Track(id = "", title = title, artists = listOf(artistFolder), album = albumFolder)
    }

    fun toM3u(tracks: List<Track>): String = buildString {
        append("#EXTM3U\r\n")
        for (t in tracks) {
            val secs = if (t.durationMs > 0) t.durationMs / 1000 else -1
            append("#EXTINF:").append(secs).append(',')
            if (t.artists.isNotEmpty()) append(t.artistLine).append(" - ")
            append(t.title).append("\r\n")
            // Nessun file locale: lasciamo una riga di ricerca, i player la ignorano o la mostrano.
            append(t.artistLine).append(if (t.artists.isEmpty()) "" else " - ").append(t.title).append("\r\n")
        }
    }

    /** Testo semplice, una riga "Artista - Titolo" per brano: si incolla ovunque, anche in una chat. */
    fun toText(tracks: List<Track>): String = tracks.joinToString("\n", postfix = "\n") { t ->
        if (t.artists.isEmpty()) t.title else t.artistLine + " - " + t.title
    }

    /** The key of Trackhop's own fields in a JSPF track's "extension" (an application URI, as JSPF asks). */
    const val JSPF_EXTENSION = "https://trackhop.org/jspf/track"

    /** JSPF (XSPF in JSON), il formato di ListenBrainz: creator/title/album/duration e l'ISRC fra gli identificatori. */
    fun toJspf(name: String, tracks: List<Track>): String {
        val list = tracks.map { t ->
            buildJsonObject {
                put("title", t.title)
                if (t.artists.isNotEmpty()) put("creator", t.artistLine)
                if (t.album.isNotBlank()) put("album", t.album)
                if (t.durationMs > 0) put("duration", t.durationMs)
                val ids = listOfNotNull(t.isrcNorm?.let { "isrc:$it" }, Origins.url(t.origin))
                if (ids.isNotEmpty()) put("identifier", JsonArray(ids.map { JsonPrimitive(it) }))
                // Where the track came from and what its service said of it, as in the CSV (see Origins).
                if (t.origin != null || t.addedAt > 0 || t.explicit != null || t.year > 0) put("extension", buildJsonObject {
                    put(JSPF_EXTENSION, buildJsonObject {
                        Origins.split(t.origin)?.let { (service, id) -> put("service", service); put("service_id", id) }
                        if (t.addedAt > 0) put("added_at", java.time.Instant.ofEpochMilli(t.addedAt).toString())
                        t.explicit?.let { put("explicit", it) }
                        if (t.year > 0) put("year", t.year)
                    })
                })
            }
        }
        val root = buildJsonObject { put("playlist", buildJsonObject { put("title", name); put("track", JsonArray(list)) }) }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    /** XSPF, la playlist XML aperta che leggono VLC, Strawberry, Clementine e altri. */
    fun toXspf(name: String, tracks: List<Track>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n")
        append("  <title>").append(escapeXml(name)).append("</title>\n  <trackList>\n")
        for (t in tracks) {
            append("    <track>")
            append("<title>").append(escapeXml(t.title)).append("</title>")
            if (t.artists.isNotEmpty()) append("<creator>").append(escapeXml(t.artistLine)).append("</creator>")
            if (t.album.isNotBlank()) append("<album>").append(escapeXml(t.album)).append("</album>")
            if (t.durationMs > 0) append("<duration>").append(t.durationMs).append("</duration>")
            t.isrcNorm?.let { append("<identifier>isrc:").append(escapeXml(it)).append("</identifier>") }
            append("</track>\n")
        }
        append("  </trackList>\n</playlist>\n")
    }

    private val OPML_ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val NUMERIC_ENTITY = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    /** An attribute's value as written in XML: entities read, line breaks and runs of spaces as one space. */
    private fun xmlText(s: String): String = NUMERIC_ENTITY.replace(s) { m ->
        val v = m.groupValues[1]
        val code = if (v.startsWith("x") || v.startsWith("X")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
        code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
    }.let(XmlTokens::unescape).replace(Regex("""\s+"""), " ").trim()

    /**
     * OPML delle iscrizioni ai podcast (AntennaPod, Pocket Casts, Podcast Addict, Apple Podcasts…): un
     * show per outline con feed (`xmlUrl`, o `url` per un outline di tipo rss), con il suo indirizzo e,
     * dove l'outline ne porta uno, l'id di Apple (`htmlUrl` verso podcasts.apple.com, `itunes:id`).
     * Gruppi, commenti e outline senza nome si saltano.
     */
    fun parseOpml(text: String): List<Track> = readOpml(CharSource(text.reader()), strict = false)

    /** An outline at a time. With [strict], a file that ends before its </opml> is refused. */
    private fun readOpml(source: CharSource, strict: Boolean): List<Track> {
        val out = ArrayList<Track>()
        val xml = XmlTokens(source)
        xml.run(object : XmlTokens.Handler {
            override fun open(name: String, attrs: String, selfClosing: Boolean) {
                if (name.equals("outline", ignoreCase = true)) opmlShow(attrs)?.let(out::add)
            }
            override fun close(name: String) {}
        })
        if (strict && !xml.whole) throw incomplete()
        return out
    }

    private fun opmlShow(tag: String): Track? {
        val attrs = OPML_ATTR.findAll(tag).associate { a ->
            a.groupValues[1].lowercase() to xmlText(a.groups[2]?.value ?: a.groupValues[3])
        }
        // Group headers are outlines too; only the ones pointing at a feed are shows.
        val feed = attrs["xmlurl"]?.takeIf { it.isNotBlank() }
            ?: attrs["url"]?.takeIf { it.isNotBlank() && attrs["type"].equals("rss", ignoreCase = true) }
            ?: return null
        val title = listOf(attrs["text"], attrs["title"]).firstOrNull { !it.isNullOrBlank() } ?: return null
        val itunes = Podcasts.itunesId(attrs["itunes:id"]) ?: Podcasts.itunesId(attrs["htmlurl"])
        val publisher = listOf(attrs["itunes:author"], attrs["author"]).firstOrNull { !it.isNullOrBlank() }
        return CsvCodec.withStableId(Track(id = "", title = title, artists = listOfNotNull(publisher), kind = Track.KIND_PODCAST, feedUrl = feed, itunesId = itunes))
    }

    /** Characters XML 1.0 does not allow, even escaped (control characters but tab and line breaks). */
    private val XML_INVALID = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]""")

    private fun xmlAttr(s: String): String =
        escapeXml(XML_INVALID.replace(s, "").replace(Regex("""\s+"""), " ").trim())

    /**
     * OPML 2.0 subscriptions, as podcast apps import them: one `rss` outline per show with its feed.
     * A show with no known feed (see [Podcasts.withFeeds]) is written as a plain outline with its name,
     * which apps pass over, rather than left out without a word.
     */
    fun toOpml(name: String, shows: List<Track>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head><title>").append(xmlAttr(name)).append("</title></head>\n")
        append("  <body>\n")
        for (s in shows) {
            val title = xmlAttr(s.title)
            append("    <outline")
            if (s.feedUrl.isNullOrBlank()) {
                append(" text=\"").append(title).append("\" title=\"").append(title).append('"')
            } else {
                append(" type=\"rss\" text=\"").append(title).append("\" title=\"").append(title).append('"')
                append(" xmlUrl=\"").append(xmlAttr(s.feedUrl!!)).append('"')
            }
            Podcasts.itunesId(s.itunesId)?.let { append(" htmlUrl=\"https://podcasts.apple.com/podcast/id").append(it).append('"') }
            append("/>\n")
        }
        append("  </body>\n</opml>\n")
    }

    private val XSPF_FIELDS = setOf("title", "creator", "album", "duration", "identifier")
    private val XSPF_ISRC = Regex("""isrc:([A-Za-z0-9]+)""")

    fun parseXspf(text: String): List<Track> = readXspf(CharSource(text.reader()), strict = false)

    /**
     * A <track> at a time: only its title, creator, album, duration and identifiers are kept, the
     * rest of the file goes by. With [strict], a file that ends before its </playlist> is refused.
     */
    private fun readXspf(source: CharSource, strict: Boolean): List<Track> {
        val out = ArrayList<Track>()
        val xml = XmlTokens(source)
        var fields: HashMap<String, String>? = null // inside a <track>
        var open: String? = null // the field whose text is being read
        xml.run(object : XmlTokens.Handler {
            override fun open(name: String, attrs: String, selfClosing: Boolean) {
                if (selfClosing) return
                if (name == "track") { fields = HashMap(); open = null; xml.keep = false; return }
                val f = fields ?: return
                // The first of each, as before; every identifier, for the one that is an ISRC.
                if (open == null && name in XSPF_FIELDS && (name == "identifier" || name !in f)) { open = name; xml.text.setLength(0); xml.keep = true }
            }

            override fun close(name: String) {
                val f = fields ?: return
                if (name == open) {
                    val value = XmlTokens.unescape(xml.text.toString()).trim()
                    open = null; xml.keep = false
                    if (name != "identifier") f[name] = value
                    else if ("isrc" !in f) XSPF_ISRC.matchEntire(value)?.let { f["isrc"] = it.groupValues[1] }
                } else if (name == "track") {
                    fields = null; open = null; xml.keep = false
                    val title = f["title"]?.takeIf { it.isNotEmpty() } ?: return
                    out += CsvCodec.withStableId(
                        Track(
                            id = "",
                            title = title,
                            artists = CsvCodec.splitArtists(f["creator"].orEmpty()),
                            album = f["album"].orEmpty(),
                            durationMs = f["duration"]?.toLongOrNull() ?: 0,
                            isrc = f["isrc"],
                        )
                    )
                }
            }
        })
        if (strict && !xml.whole) throw incomplete()
        return out
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------------------------------------------------------- rekordbox XML

    /** A rekordbox collection or playlist export as one list ([ItunesLibrary.asOneList]), read a piece at a time. */
    fun parseRekordboxXml(text: String): List<Track> = RekordboxXml.read(text).asOneList()

    // ---------------------------------------------------------------- WPL / ZPL

    /**
     * A Windows Media Player playlist (.wpl) or a Zune one (.zpl): both open with their own
     * processing instruction, `<?wpl version="1.0"?>` or `<?zpl version="2.0"?>`, above a `smil`
     * element. A ".zpl" that is not XML is Zoom Player's (a text list): not this format.
     */
    private fun looksLikeWpl(name: String, head: String): Boolean {
        if (name.endsWith(".wpl")) return true
        if (!head.startsWith("<")) return false
        return head.startsWith("<?wpl", ignoreCase = true) || head.startsWith("<?zpl", ignoreCase = true) ||
            ((name.endsWith(".wpl") || name.endsWith(".zpl") || head.take(400).contains("<smil", ignoreCase = true)) && head.contains("<smil", ignoreCase = true))
    }

    fun parseWpl(text: String): List<Track> = readWpl(CharSource(text.reader()), strict = false)

    /**
     * WPL (learn.microsoft.com/windows/win32/wmp/windows-media-playlists) and ZPL, the same SMIL:
     * `smil › body › seq › media`, a `media` at a time. Windows Media Player writes only `src`, the
     * file's path, read as a path ([fromPath]); Groove and Zune add `trackTitle`, `trackArtist`,
     * `albumTitle`, `albumArtist` and `duration` (ms), taken as they are. A `media` outside `seq` (a
     * `smartPlaylist`'s rules hold none) and the head's `meta` go by. With [strict], a file that
     * ends before its `</smil>` is refused.
     */
    private fun readWpl(source: CharSource, strict: Boolean): List<Track> {
        val out = ArrayList<Track>()
        val xml = XmlTokens(source)
        var inSeq = 0
        xml.run(object : XmlTokens.Handler {
            override fun open(name: String, attrs: String, selfClosing: Boolean) {
                if (name.equals("seq", ignoreCase = true)) { if (!selfClosing) inSeq++; return }
                if (inSeq == 0 || !name.equals("media", ignoreCase = true)) return
                val a = XmlTokens.attributes(attrs).mapKeys { it.key.lowercase() }
                wplTrack(a)?.let(out::add)
            }
            override fun close(name: String) { if (name.equals("seq", ignoreCase = true) && inSeq > 0) inSeq-- }
        })
        if (strict && !xml.whole) throw incomplete()
        return out
    }

    private fun wplTrack(a: Map<String, String>): Track? {
        val title = a["tracktitle"]?.trim().orEmpty()
        val src = a["src"]?.trim().orEmpty()
        if (title.isEmpty()) return src.takeIf { it.isNotEmpty() }?.let { fromPath(it) }?.takeIf { it.title.isNotBlank() }?.let(CsvCodec::withStableId)
        val artist = a["trackartist"]?.trim().orEmpty().ifEmpty { a["albumartist"]?.trim().orEmpty() }
        return CsvCodec.withStableId(
            Track(
                id = "", title = title, artists = CsvCodec.splitArtists(artist), album = a["albumtitle"]?.trim().orEmpty(),
                durationMs = a["duration"]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
            )
        )
    }

    /**
     * [tracks] as a Windows Media Player playlist, or with [zune] as a Zune one (the same list under
     * Zune's header). Each `media` carries the song's title, artist, album and length, which Groove
     * and Zune write too; there is no file behind a streaming playlist, so `src`, which the format
     * requires, is the same search line an M3U gets ("Artist - Title").
     */
    fun toWpl(name: String, tracks: List<Track>, zune: Boolean = false): String = buildString {
        append(if (zune) "<?zpl version=\"2.0\"?>\r\n" else "<?wpl version=\"1.0\"?>\r\n")
        append("<smil>\r\n  <head>\r\n")
        append("    <meta name=\"Generator\" content=\"Trackhop\"/>\r\n")
        append("    <meta name=\"ItemCount\" content=\"").append(tracks.size).append("\"/>\r\n")
        append("    <title>").append(xmlAttr(name)).append("</title>\r\n")
        append("  </head>\r\n  <body>\r\n    <seq>\r\n")
        for (t in tracks) {
            val line = if (t.artists.isEmpty()) t.title else t.artistLine + " - " + t.title
            append("      <media src=\"").append(xmlAttr(line)).append('"')
            append(" trackTitle=\"").append(xmlAttr(t.title)).append('"')
            if (t.artists.isNotEmpty()) append(" trackArtist=\"").append(xmlAttr(t.artistLine)).append('"')
            if (t.album.isNotBlank()) append(" albumTitle=\"").append(xmlAttr(t.album)).append('"')
            if (t.durationMs > 0) append(" duration=\"").append(t.durationMs).append('"')
            append("/>\r\n")
        }
        append("    </seq>\r\n  </body>\r\n</smil>\r\n")
    }

    // ---------------------------------------------------------------- iTunes / Apple Music XML

    /** A library or playlist from Apple Music / iTunes as one list ([ItunesLibrary.asOneList]), read a piece at a time. */
    fun parseItunesXml(text: String): List<Track> = ItunesLibrary.read(text).asOneList()

    // ---------------------------------------------------------------- JSON

    /**
     * The tracks of a JSON text. One that does not read to its end (cut off by a token limit: an AI's
     * answer) still gives every object in it that parses on its own; a file is read with [readStream],
     * which refuses one cut short.
     */
    fun parseJson(text: String): List<Track> =
        try { readJson(CharSource(text.reader()), strict = true) } catch (e: ItunesLibrary.Incomplete) { salvageJson(text) }

    private fun salvageJson(text: String): List<Track> =
        Regex("""\{[^{}]*\}""").findAll(text).mapNotNull { m ->
            try { (json.parseToJsonElement(m.value) as? JsonObject)?.let(::jsonTrack) } catch (e: Exception) { null }
        }.map(CsvCodec::withStableId).toList()

    /** The track an object describes, without its id yet, or null when it has no title. */
    private fun jsonTrack(o: JsonObject): Track? {
        val title = pick(o, TITLE_KEYS)?.trim().orEmpty()
        if (title.isEmpty()) return null
        return Track(
            id = "",
            title = title,
            artists = CsvCodec.splitArtists(pick(o, ARTIST_KEYS).orEmpty()),
            album = pick(o, ALBUM_KEYS).orEmpty(),
            durationMs = CsvCodec.parseDuration(pick(o, DURATION_KEYS).orEmpty()),
            // JSPF (ListenBrainz, ours) names it among the identifiers, "isrc:…".
            isrc = pick(o, ISRC_KEYS)?.takeIf { it.isNotBlank() } ?: runCatching {
                (o["identifier"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { v -> v.startsWith("isrc:", true) }?.substring(5) }
                    ?: (o["identifier"] as? JsonPrimitive)?.content?.takeIf { v -> v.startsWith("isrc:", true) }?.substring(5)
            }.getOrNull()?.takeIf { it.isNotBlank() },
        ).let { t ->
            // Trackhop's own JSPF: the origin and what the service said of the track (see toJspf).
            val ext = runCatching { o["extension"]?.jsonObject?.get(JSPF_EXTENSION)?.jsonObject }.getOrNull() ?: return@let t
            fun str(k: String) = runCatching { ext[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            t.copy(
                origin = Origins.of(str("service"), str("service_id")),
                addedAt = str("added_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
                explicit = str("explicit")?.toBooleanStrictOrNull(),
                year = str("year")?.toIntOrNull()?.takeIf { it in 1..9999 } ?: 0,
            )
        }
    }

    /** One object of a list is parsed on its own, up to this size; a bigger one is not a track. */
    private const val MAX_JSON_ELEMENT = 2 * 1024 * 1024

    /**
     * JSON read a piece at a time: brackets and strings are followed, and each object that sits in a
     * list is parsed on its own when it closes. As before, the tracks are the ones of the list with
     * the most objects among those where an object has a title (the first of them when two have as
     * many), wherever it is in the file. Lines of objects without a list around them count as one.
     * With [strict], a file that ends inside a list, an object or a string is refused.
     */
    private fun readJson(source: CharSource, strict: Boolean): List<Track> {
        class Frame(val list: Boolean, val order: Int) {
            var objects = 0
            val tracks = ArrayList<Track>()
            /** Where this object starts in [text], or -1 when it is not kept (not in a list, or too big). */
            var start = -1
        }
        // The file itself is the last list to win: only when it is lines of objects, at least two.
        val root = Frame(list = true, order = Int.MAX_VALUE)
        val stack = ArrayList<Frame>().apply { add(root) }
        var best: Frame? = null
        var opened = 0
        // The text of the outermost object being kept; the ones inside it are parts of it.
        val text = StringBuilder()
        var kept = 0

        fun consider(f: Frame) {
            if (f.tracks.isEmpty() || (f === root && f.objects < 2)) return
            val b = best
            if (b == null || f.objects > b.objects || (f.objects == b.objects && f.order < b.order)) best = f
        }
        fun tooBig() {
            // The outermost kept object is dropped; the text now starts at the next one inside it.
            val outer = stack.first { it.start >= 0 }
            outer.start = -1; kept--
            val shift = stack.firstOrNull { it.start >= 0 }?.start ?: text.length
            text.delete(0, shift)
            stack.forEach { if (it.start >= 0) it.start -= shift }
        }
        fun keep(c: Char) {
            if (kept == 0) return
            text.append(c)
            if (text.length > MAX_JSON_ELEMENT) tooBig()
        }
        fun close() {
            if (stack.size == 1) return
            val f = stack.removeAt(stack.size - 1)
            if (f.list) return consider(f)
            if (f.start < 0) return
            val parsed = try { json.parseToJsonElement(text.substring(f.start)) as? JsonObject } catch (e: Exception) { null }
            kept--
            if (kept == 0) text.setLength(0)
            parsed?.let(::jsonTrack)?.let { stack.last().tracks += it }
        }

        var cut = false
        while (true) {
            val i = source.next()
            if (i < 0) break
            val c = i.toChar()
            when (c) {
                '"' -> {
                    keep(c)
                    var escaped = false
                    while (true) {
                        val n = source.next()
                        if (n < 0) { cut = true; break }
                        keep(n.toChar())
                        if (escaped) escaped = false else if (n == '\\'.code) escaped = true else if (n == '"'.code) break
                    }
                }
                '{' -> {
                    val parent = stack.last()
                    val f = Frame(list = false, order = 0)
                    if (parent.list) { parent.objects++; f.start = text.length; kept++ }
                    stack += f
                    keep(c)
                }
                '[' -> { stack += Frame(list = true, order = opened++); keep(c) }
                '}', ']' -> { keep(c); close() }
                else -> keep(c)
            }
        }
        if (strict && (cut || stack.size > 1)) throw incomplete()
        consider(root)
        return best?.tracks.orEmpty().map(CsvCodec::withStableId)
    }

    /** Valore della prima chiave riconosciuta (confronto senza maiuscole/spazi/underscore). */
    private fun pick(o: JsonObject, keys: List<String>): String? {
        val normalized = o.entries.associate { (k, v) -> k.lowercase().replace(Regex("""[^a-z]"""), "") to v }
        for (k in keys) {
            val v = normalized[k] ?: continue
            val s = flatten(v)
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    /** Un campo puo' essere stringa, numero, lista di stringhe o lista di oggetti {name: …}. */
    private fun flatten(v: JsonElement): String? = when (v) {
        is JsonPrimitive -> v.content.takeIf { it != "null" }
        is JsonArray -> v.mapNotNull { flatten(it) }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        is JsonObject -> (v["name"] ?: v["title"] ?: v["artistName"])?.let { flatten(it) }
    }

    // ---------------------------------------------------------------- testo semplice

    private val LIST_PREFIX = Regex("""^\s*\d+\s*[.)\-]\s+""")

    fun parseText(text: String, patterns: List<NamePatterns.Pattern> = emptyList()): List<Track> = readText(CharSource(text.reader()), patterns)

    private fun readText(source: CharSource, patterns: List<NamePatterns.Pattern>): List<Track> {
        val out = ArrayList<Track>()
        while (true) {
            val line = LIST_PREFIX.replace((source.line() ?: break).trim(), "").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // JSON punctuation or a JSON object is not a track name.
            if (line.first() in "[]{}" || line == "```" || line.startsWith("```")) continue
            val t = NamePatterns.read(line, patterns)?.track() ?: splitArtistTitle(line).let { (artists, title) -> Track(id = "", title = title, artists = artists) }
            if (t.title.isNotBlank()) out += CsvCodec.withStableId(t)
        }
        return out
    }

    private val DASH = Regex("""\s+[-–—]\s+""")

    /** "Artista - Titolo" (il formato di gran lunga piu' diffuso negli export testuali). */
    fun splitArtistTitle(label: String): Pair<List<String>, String> {
        val parts = DASH.split(label.trim(), 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            CsvCodec.splitArtists(parts[0]) to parts[1].trim()
        } else {
            emptyList<String>() to label.trim()
        }
    }
}

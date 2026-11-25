package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import java.security.MessageDigest

/**
 * CSV di playlist. Scrive `title,artist,album,duration_ms,isrc`; in lettura riconosce anche le
 * intestazioni degli export piu' comuni (Exportify, TuneMyMusic, Soundiiz, Apple Music via terze parti).
 */
object CsvCodec {
    private val HEADER = listOf("title", "artist", "album", "duration_ms", "isrc")

    private val TITLE_KEYS = setOf("title", "trackname", "name", "track", "song", "songname", "tracktitle", "titolo", "brano")
    private val ARTIST_KEYS = setOf("artist", "artists", "artistname", "artistnames", "artista", "artisti", "performer")
    private val ALBUM_KEYS = setOf("album", "albumname", "albumtitle")
    private val DURATION_KEYS = setOf("durationms", "duration", "length", "time", "durata")
    private val ISRC_KEYS = setOf("isrc")
    /** A followed podcast's feed and Apple id: written only when a row has one, so a file of songs stays as it was. */
    private val PODCAST_HEADER = listOf("feed_url", "itunes_id")
    // Only these exact columns (Trackhop's own): a file of songs from elsewhere is read as it always was.
    private val FEED_KEYS = setOf("feedurl")
    private val ITUNES_KEYS = setOf("itunesid")
    /**
     * Star ratings (1..5, empty = none), from an iTunes library: written only when a track has one,
     * and read only from a file Trackhop wrote (its own header first), so any other file reads as it did.
     */
    private val RATING_HEADER = listOf("rating", "album_rating")

    fun encode(tracks: List<Track>): String = buildString {
        val shows = tracks.any { !it.feedUrl.isNullOrBlank() || !it.itunesId.isNullOrBlank() }
        val rated = tracks.any { it.rating > 0 || it.albumRating > 0 }
        append((HEADER + (if (shows) PODCAST_HEADER else emptyList()) + (if (rated) RATING_HEADER else emptyList())).joinToString(",")).append("\r\n")
        fun stars(n: Int) = if (n > 0) n.toString() else ""
        for (t in tracks) {
            var cells = listOf(t.title, t.artistLine, t.album, if (t.durationMs > 0) t.durationMs.toString() else "", t.isrc ?: "")
            if (shows) cells = cells + listOf(t.feedUrl.orEmpty(), t.itunesId.orEmpty())
            if (rated) cells = cells + listOf(stars(t.rating), stars(t.albumRating))
            append(cells.joinToString(",") { quote(it) }).append("\r\n")
        }
    }

    fun parse(text: String): List<Track> = read(CharSource(text.reader()))

    /** The tracks of a CSV read a row at a time (#67): the file is never held whole. */
    internal fun read(source: CharSource): List<Track> {
        val rows = Rows()
        eachRow(source, rows::add)
        return rows.finish()
    }

    /** Which column holds what, read off the header row; without one: title,artist,album,duration,isrc. */
    private class Columns(headerRow: List<String>) {
        private val header = headerRow.map { norm(it) }
        private fun col(keys: Set<String>) = header.indexOfFirst { it in keys }.takeIf { it >= 0 }
        private val iTitle = col(TITLE_KEYS)
        private val iArtist = col(ARTIST_KEYS)
        private val iAlbum = col(ALBUM_KEYS)
        private val iDur = col(DURATION_KEYS)
        private val iIsrc = col(ISRC_KEYS)
        private val iFeed = col(FEED_KEYS)
        private val iItunes = col(ITUNES_KEYS)
        private val ours = headerRow.take(HEADER.size).map { it.trim().lowercase() } == HEADER
        private val raw = headerRow.map { it.trim().lowercase() }
        private val iRating = if (ours) raw.indexOf(RATING_HEADER[0]).takeIf { it >= 0 } else null
        private val iAlbumRating = if (ours) raw.indexOf(RATING_HEADER[1]).takeIf { it >= 0 } else null
        // "duration_ms" (ours, Exportify's "Duration (ms)") is milliseconds whatever the size: an 8-second
        // interlude is 8000 ms, not 8000 seconds.
        private val inMs = iDur != null && header[iDur].endsWith("ms")
        // Senza intestazione riconoscibile: assumiamo title,artist,album,duration,isrc.
        val hasHeader = iTitle != null

        fun track(r: List<String>): Track? {
            fun at(i: Int?) = i?.let { r.getOrNull(it) }?.trim() ?: ""
            val title = if (hasHeader) at(iTitle) else r.getOrNull(0)?.trim() ?: ""
            if (title.isEmpty()) return null
            val artist = if (hasHeader) at(iArtist) else r.getOrNull(1)?.trim() ?: ""
            val album = if (hasHeader) at(iAlbum) else r.getOrNull(2)?.trim() ?: ""
            val rawDur = if (hasHeader) at(iDur) else r.getOrNull(3)?.trim() ?: ""
            val dur = if (inMs) rawDur.toDoubleOrNull()?.toLong() ?: parseDuration(rawDur) else parseDuration(rawDur)
            val isrc = (if (hasHeader) at(iIsrc) else r.getOrNull(4)?.trim() ?: "").ifEmpty { null }
            val feed = if (hasHeader) at(iFeed).ifEmpty { null } else null
            val itunes = if (hasHeader) at(iItunes).ifEmpty { null } else null
            fun stars(i: Int?) = at(i).toIntOrNull()?.takeIf { it in 1..5 } ?: 0
            return withStableId(Track(
                id = "", title = title, artists = splitArtists(artist), album = album, durationMs = dur, isrc = isrc, feedUrl = feed, itunesId = itunes,
                rating = stars(iRating), albumRating = stars(iAlbumRating),
            ))
        }
    }

    /** Rows turned into tracks as they come: the header is known once the second row is. */
    private class Rows {
        private var first: List<String>? = null
        private var columns: Columns? = null
        private val out = ArrayList<Track>()

        private fun startWith(header: List<String>): Columns =
            Columns(header).also { columns = it; if (!it.hasHeader) it.track(header)?.let(out::add) }

        fun add(row: List<String>) {
            val c = columns
            val f = first
            when {
                c != null -> c.track(row)?.let(out::add)
                f == null -> first = row
                // Shazam's export opens with a one-cell line ("Shazam Library") above the real header.
                f.size == 1 && row.size > 1 && row.map { norm(it) }.any { it in TITLE_KEYS } -> startWith(row)
                else -> startWith(f).track(row)?.let(out::add)
            }
        }

        fun finish(): List<Track> {
            if (columns == null) first?.let(::startWith)
            return out
        }
    }

    fun splitArtists(s: String): List<String> =
        s.split(Regex("""\s*[,;/]\s*|\s+&\s+""")).map { it.trim() }.filter { it.isNotEmpty() }

    fun parseDuration(s: String): Long {
        if (s.isBlank()) return 0
        if (':' in s) {
            val p = s.split(':').map { it.trim().toDoubleOrNull() ?: 0.0 }
            return when (p.size) {
                2 -> ((p[0] * 60 + p[1]) * 1000).toLong()
                3 -> (((p[0] * 60 + p[1]) * 60 + p[2]) * 1000).toLong()
                else -> 0
            }
        }
        if (s.startsWith("P")) return Durations.parseIso8601(s)
        val n = s.toDoubleOrNull() ?: return 0
        return if (n > 10_000) n.toLong() else (n * 1000).toLong() // ms vs secondi
    }

    /** Id stabile derivato da titolo+artista, cosi' le sync ripetute riconoscono lo stesso brano. */
    fun withStableId(t: Track): Track {
        // A show with a feed is that feed, whatever its name: two shows of one name stay two.
        val key = Podcasts.feedKey(t.feedUrl)?.let { "feed|$it" }
            ?: (Matcher.normalizeTitle(t.title) + "|" + t.artists.map { Matcher.normalizeArtist(it) }.sorted().joinToString(","))
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        return t.copy(id = id, uri = null, itemId = null)
    }

    fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.').take(80).ifBlank { "playlist" }

    private fun norm(h: String) = h.lowercase().replace(Regex("""[^a-z]"""), "")

    internal fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * Parser RFC 4180 (virgolette, campi multilinea). Il separatore (`,`, `;` o TAB) e' dedotto
     * dalla prima riga: il TAB serve per gli export "Esporta playlist" di Apple Music/iTunes.
     */
    fun parseRows(text: String): List<List<String>> = ArrayList<List<String>>().also { rows -> eachRow(CharSource(text.reader())) { rows.add(it) } }

    /** No row keeps more cells than this: a file that is not a table cannot fill the memory. */
    private const val MAX_CELLS = 4096

    private fun eachRow(source: CharSource, each: (List<String>) -> Unit) {
        // The separator is read off the first lines that have one (a lone title line may come first).
        val firstLine = source.head().lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n")
        val sep = listOf(',', ';', '\t')
            .map { c -> c to firstLine.count { it == c } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first ?: ','
        var row = ArrayList<String>()
        val field = StringBuilder()
        fun cell() { if (row.size < MAX_CELLS) row.add(field.toString()); field.setLength(0) }
        fun keep(c: Char) { if (field.length < CharSource.MAX_TEXT) field.append(c) }
        var inQuotes = false
        while (true) {
            val i = source.next()
            if (i < 0) break
            val c = i.toChar()
            if (inQuotes) {
                if (c == '"') {
                    if (source.peek() == '"'.code) { keep('"'); source.next() } else inQuotes = false
                } else keep(c)
            } else when (c) {
                '"' -> inQuotes = true
                sep -> cell()
                '\r' -> { }
                '\n' -> { cell(); if (row.any { it.isNotBlank() }) each(row); row = ArrayList() }
                else -> keep(c)
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { cell(); if (row.any { it.isNotBlank() }) each(row) }
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.json
import com.xlollx.songport.net.long
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * "Lifetime statistics" (Discover): the file with every listen that a service hands over on request
 * (GDPR), read on the device and never sent anywhere, never logged:
 * - Spotify's Extended streaming history (a .zip of JSON arrays: ts, ms_played,
 *   master_metadata_track_name/album_artist_name/album_album_name, spotify_track_uri), and the
 *   shorter "account data" history (endTime, artistName, trackName, msPlayed);
 * - Google Takeout's YouTube watch history as JSON (header, title, subtitles, time): the YouTube
 *   Music entries only, which say no duration, so those count plays, not hours;
 * - Deezer's "my data" workbook (.xlsx): the sheet whose first row names a song title and an artist
 *   (read by its headers, [Xlsx]).
 *
 * The file is read as a stream, one listen at a time ([read]); [Builder] adds them up into [Stats].
 */
object ListeningExport {
    enum class Source(val label: String, val site: String) {
        SPOTIFY("Spotify", "spotify.com"), YOUTUBE("YouTube Music", "music.youtube.com"), DEEZER("Deezer", "deezer.com"),
    }

    /** One listen: [at] epoch millis (0 not known), [ms] how long it played (0 where the export does not say). */
    class Play(val at: Long, val ms: Long, val title: String, val artist: String, val album: String = "", val isrc: String? = null, val uri: String? = null, val source: Source)

    /** Under this a song was skipped, not listened to (Spotify's own rule). */
    const val MIN_MS = 30_000L
    /** One part of a workbook bigger than this is not read into memory. */
    private const val MAX_PART = 150L * 1024 * 1024

    class NotAnExport : Exception("no listening history in this file")

    /**
     * Reads the export in [input] (a .zip, a .json or an .xlsx; told by its content, not its name)
     * and hands every listen to [each]. Returns how many there were; throws [NotAnExport] when none.
     */
    fun read(input: InputStream, each: (Play) -> Unit): Int {
        val n = readAny(BufferedInputStream(input), each, depth = 0)
        if (n == 0) throw NotAnExport()
        return n
    }

    private fun readAny(input: BufferedInputStream, each: (Play) -> Unit, depth: Int): Int {
        input.mark(4)
        val head = ByteArray(2).also { input.read(it) }
        input.reset()
        if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) return readJson(input, each)
        var n = 0
        val workbook = HashMap<String, ByteArray>()
        val whole = ZipEnd(input)
        val zip = ZipInputStream(whole)
        var entries = 0
        while (true) {
            val e = zip.nextEntry ?: break
            entries++
            val name = e.name.lowercase()
            when {
                e.isDirectory -> {}
                name.endsWith(".json") -> n += readJson(zip, each)
                // A workbook inside the archive, or this archive is the workbook.
                name.endsWith(".xlsx") && depth == 0 -> n += readAny(BufferedInputStream(ByteArrayInputStream(bytes(zip))), each, 1)
                name == "xl/sharedstrings.xml" || (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) -> workbook[name] = bytes(zip)
            }
        }
        // An archive cut between two of its files ends without an error: only its closing list says
        // whether these were all of them. Half a history is not shown as a lifetime.
        if (!whole.complete(entries)) throw ItunesLibrary.Incomplete("The file ends before the archive does: it is incomplete. Download it again, or copy it again.")
        if (workbook.isNotEmpty()) n += readWorkbook(workbook, each)
        return n
    }

    /**
     * The archive's stream, remembering its last bytes: a whole .zip closes with its list of files
     * (the "end of central directory" record, PK 05 06), which says how many there are.
     */
    private class ZipEnd(input: InputStream) : FilterInputStream(input) {
        // The record is 22 bytes and may be followed by a comment of up to 65535.
        private val tail = ByteArray(22 + 65_535)
        private var at = 0
        private var filled = 0

        private fun keep(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) { tail[at] = b[i]; at = (at + 1) % tail.size }
            filled = minOf(tail.size, filled + len)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) keep(b, off, it) }

        // Skipped bytes would not be remembered: read instead.
        override fun skip(n: Long): Long {
            val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
            return read(buf, 0, buf.size).coerceAtLeast(0).toLong()
        }

        override fun markSupported() = false

        /** Reads what is left, then: is the closing record there, and does it count no more files than the [entries] read? */
        fun complete(entries: Int): Boolean {
            val buf = ByteArray(16 * 1024)
            while (read(buf, 0, buf.size) >= 0) { /* to the end */ }
            val end = ByteArray(filled) { tail[(at - filled + it + tail.size * 2) % tail.size] }
            var i = end.size - 22
            while (i >= 0) {
                if (end[i] == 'P'.code.toByte() && end[i + 1] == 'K'.code.toByte() && end[i + 2] == 5.toByte() && end[i + 3] == 6.toByte()) {
                    val total = (end[i + 10].toInt() and 0xff) or ((end[i + 11].toInt() and 0xff) shl 8)
                    // 0xFFFF: more than fit here (a zip64 archive), the count is elsewhere.
                    return total == 0xffff || entries >= total
                }
                i--
            }
            return false
        }
    }

    private fun bytes(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_PART) throw ProviderException("The file is too big to read")
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- JSON (Spotify, YouTube)

    /** A JSON array of objects, read one object at a time; anything else is not ours and gives none. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun readJson(input: InputStream, each: (Play) -> Unit): Int {
        // The archive's stream goes on to the next file: the JSON reader must not close it.
        val open = object : FilterInputStream(input) { override fun close() {} }
        var n = 0
        var first = true
        try {
            for (o in json.decodeToSequence(open, JsonObject.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
                if (first) { first = false; if (kindOf(o) == null) return 0 }
                play(o)?.let { each(it); n++ }
            }
        } catch (_: kotlinx.serialization.SerializationException) {
            // Not an array of objects (another file of the archive), or cut short: what was read stays.
        } catch (_: IllegalArgumentException) {
        }
        return n
    }

    private fun kindOf(o: JsonObject): Source? = when {
        "ms_played" in o || "msPlayed" in o -> Source.SPOTIFY
        "header" in o && "time" in o -> Source.YOUTUBE
        else -> null
    }

    private val SHORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** One entry of a Spotify or Takeout history as a listen; null for a podcast, a video, an ad, a removed video. */
    fun play(o: JsonObject): Play? = when {
        "ms_played" in o -> {
            val title = o["master_metadata_track_name"].str?.takeIf { it.isNotBlank() }
            val artist = o["master_metadata_album_artist_name"].str?.takeIf { it.isNotBlank() }
            if (title == null || artist == null) null else Play(
                at = o["ts"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0, ms = o["ms_played"].long ?: 0,
                title = title, artist = artist, album = o["master_metadata_album_album_name"].str.orEmpty(),
                uri = o["spotify_track_uri"].str, source = Source.SPOTIFY,
            )
        }
        "msPlayed" in o -> {
            val title = o["trackName"].str?.takeIf { it.isNotBlank() }
            val artist = o["artistName"].str?.takeIf { it.isNotBlank() }
            val ms = o["msPlayed"].long ?: 0
            // endTime is when it ended, in UTC.
            val end = o["endTime"].str?.let { runCatching { LocalDateTime.parse(it, SHORT_TIME).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
            if (title == null || artist == null) null else Play(at = end?.let { it - ms } ?: 0, ms = ms, title = title, artist = artist, source = Source.SPOTIFY)
        }
        o["header"].str == "YouTube Music" -> {
            val artist = o["subtitles"].arr.firstOrNull()?.get("name").str?.removeSuffix(" - Topic")?.trim()?.takeIf { it.isNotEmpty() }
            val title = o["title"].str?.let { watched(it) }?.takeIf { it.isNotBlank() }
            if (title == null || artist == null) null else Play(
                at = o["time"].str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0, ms = 0,
                title = title, artist = artist, source = Source.YOUTUBE,
            )
        }
        else -> null
    }

    // Takeout writes "Watched <title>" in the account's language.
    private val WATCHED_BEFORE = listOf("Watched ", "Hai guardato ", "Has visto ", "Vous avez regardé ", "Você assistiu a ", "Assistiu a ", "Obejrzano: ", "Просмотрено: ", "Вы посмотрели ", "观看了", "已观看 ")
    private val WATCHED_AFTER = listOf(" angesehen", " を視聴しました", "を視聴しました", " 을(를) 시청했습니다.", " adlı videoyu izlediniz")

    internal fun watched(title: String): String {
        WATCHED_BEFORE.firstOrNull { title.startsWith(it) }?.let { return title.removePrefix(it).trim() }
        WATCHED_AFTER.firstOrNull { title.endsWith(it) }?.let { return title.removeSuffix(it).trim() }
        return title.trim()
    }

    // ---------------------------------------------------------------- workbook (Deezer)

    private val DATE_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** The sheets whose first row names a song title and an artist: each next row a listen. */
    private fun readWorkbook(parts: Map<String, ByteArray>, each: (Play) -> Unit): Int {
        val shared = parts["xl/sharedstrings.xml"]?.let { Xlsx.sharedStrings(it) }.orEmpty()
        var n = 0
        for ((name, sheet) in parts) {
            if (!name.startsWith("xl/worksheets/")) continue
            var cols: Map<String, Int>? = null
            var header = true
            Xlsx.rows(sheet, shared) { row ->
                if (header) { header = false; cols = deezerColumns(row); return@rows }
                val c = cols ?: return@rows
                deezerPlay(row, c)?.let { each(it); n++ }
            }
        }
        return n
    }

    /** Where the columns are, by the header's names; null when it is not a listening history. */
    internal fun deezerColumns(header: List<String>): Map<String, Int>? {
        val h = header.map { it.trim().lowercase() }
        fun find(vararg names: String) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } }
        val title = find("song title", "track title", "title", "song", "track") ?: return null
        val artist = find("artist", "artist name") ?: return null
        return buildMap {
            put("title", title); put("artist", artist)
            find("album title", "album")?.let { put("album", it) }
            find("isrc")?.let { put("isrc", it) }
            find("listening time", "listening time (s)", "duration")?.let { put("time", it) }
            find("date", "listening date")?.let { put("date", it) }
        }
    }

    internal fun deezerPlay(row: List<String>, c: Map<String, Int>): Play? {
        fun cell(k: String) = c[k]?.let { row.getOrNull(it) }?.trim().orEmpty()
        val title = cell("title"); val artist = cell("artist")
        if (title.isEmpty() || artist.isEmpty()) return null
        val date = cell("date")
        val at = date.toDoubleOrNull()?.let { ((it - 25569.0) * 86_400_000).toLong() } // a spreadsheet's day number
            ?: runCatching { LocalDateTime.parse(date.take(19).replace('T', ' '), DATE_TEXT).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: 0
        return Play(at, ((cell("time").toDoubleOrNull() ?: 0.0) * 1000).toLong(), title, artist, cell("album"), cell("isrc").takeIf { it.isNotEmpty() }, source = Source.DEEZER)
    }

    // ---------------------------------------------------------------- statistics

    @Serializable
    data class Top(val name: String, val ms: Long, val plays: Int)

    @Serializable
    data class TopTrack(val title: String, val artist: String, val album: String = "", val isrc: String? = null, val ms: Long, val plays: Int)

    /** One year: its listening, and the song and artist played most in it. */
    @Serializable
    data class Year(val year: Int, val ms: Long, val plays: Int, val song: TopTrack? = null, val artist: String = "")

    /**
     * [timed]: the export says how long each listen lasted, so the page speaks of hours and ranks by
     * time; else by plays. [clock]: the listening per hour of the day (0 to 23), in the device's time
     * zone. [obscurity] 0 to 100 once asked ([obscurity]); null before, or when it could not be told.
     */
    @Serializable
    data class Stats(
        val sources: List<String>, val from: Long, val to: Long, val plays: Int, val ms: Long, val timed: Boolean,
        val artists: Int, val tracks: Int, val clock: List<Long>, val topArtists: List<Top>, val topTracks: List<TopTrack>,
        val years: List<Year>, val obscurity: Int? = null,
    ) {
        val hours: Long get() = ms / 3_600_000
        fun weight(t: Top): Long = if (timed) t.ms else t.plays.toLong()
    }

    const val TOP_ARTISTS = 50
    const val TOP_TRACKS = 200

    /** Adds the listens up; nothing of the file is kept but the sums. */
    class Builder(private val zone: ZoneId = ZoneId.systemDefault()) {
        private class A(val name: String) { var ms = 0L; var plays = 0 }
        private class T(val title: String, val artist: String, val album: String, val isrc: String?) { var ms = 0L; var plays = 0 }
        private class Y { var ms = 0L; var plays = 0; val tracks = HashMap<String, LongArray>(); val artists = HashMap<String, LongArray>() }

        private val artists = HashMap<String, A>()
        private val tracks = HashMap<String, T>()
        private val years = java.util.TreeMap<Int, Y>()
        private val sources = LinkedHashSet<Source>()
        private val clockMs = LongArray(24)
        private val clockPlays = LongArray(24)
        private var from = Long.MAX_VALUE
        private var to = 0L
        private var plays = 0
        private var ms = 0L
        private var untimed = 0

        fun add(p: Play) {
            if (p.ms in 1 until MIN_MS) return
            val ak = Matcher.normalizeArtist(p.artist).ifEmpty { p.artist.lowercase() }
            val tk = ak + "|" + Matcher.normalizeTitle(p.title).ifEmpty { p.title.lowercase() }
            plays++; ms += p.ms; if (p.ms == 0L) untimed++
            sources += p.source
            artists.getOrPut(ak) { A(p.artist) }.let { it.ms += p.ms; it.plays++ }
            tracks.getOrPut(tk) { T(p.title, p.artist, p.album, p.isrc) }.let { it.ms += p.ms; it.plays++ }
            if (p.at <= 0) return
            if (p.at < from) from = p.at
            if (p.at > to) to = p.at
            val t = Instant.ofEpochMilli(p.at).atZone(zone)
            clockMs[t.hour] += p.ms; clockPlays[t.hour]++
            val y = years.getOrPut(t.year) { Y() }
            y.ms += p.ms; y.plays++
            y.tracks.getOrPut(tk) { LongArray(2) }.let { it[0] += p.ms; it[1]++ }
            y.artists.getOrPut(ak) { LongArray(2) }.let { it[0] += p.ms; it[1]++ }
        }

        val count: Int get() = plays

        fun build(): Stats {
            val timed = ms > 0 && untimed == 0
            fun w(ms: Long, plays: Long) = if (timed) ms else plays
            fun top(t: T) = TopTrack(t.title, t.artist, t.album, t.isrc, t.ms, t.plays)
            return Stats(
                sources = sources.map { it.label }, from = if (to == 0L) 0 else from, to = to, plays = plays, ms = ms, timed = timed,
                artists = artists.size, tracks = tracks.size, clock = (if (timed) clockMs else clockPlays).toList(),
                topArtists = artists.values.sortedByDescending { w(it.ms, it.plays.toLong()) }.take(TOP_ARTISTS).map { Top(it.name, it.ms, it.plays) },
                topTracks = tracks.values.sortedByDescending { w(it.ms, it.plays.toLong()) }.take(TOP_TRACKS).map { top(it) },
                years = years.map { (year, y) ->
                    val song = y.tracks.maxByOrNull { w(it.value[0], it.value[1]) }?.let { (k, v) -> tracks[k]?.let { t -> top(t).copy(ms = v[0], plays = v[1].toInt()) } }
                    Year(year, y.ms, y.plays, song, y.artists.maxByOrNull { w(it.value[0], it.value[1]) }?.let { artists[it.key]?.name }.orEmpty())
                },
            )
        }
    }

    /** The songs played most of all time, the most played first, as the tracks of a playlist. */
    fun topTracks(stats: Stats, max: Int = 100): List<Track> = stats.topTracks.take(max).map {
        CsvCodec.withStableId(Track(id = "", title = it.title, artists = listOf(it.artist), album = it.album, isrc = it.isrc))
    }

    // ---------------------------------------------------------------- kept

    private const val KV = "lifetime"

    /** The statistics last made, for the page opened again: the sums only, never the file's listens. */
    fun kept(env: Env): Stats? = env.keyValues(KV)["stats"]?.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(Stats.serializer(), it) }.getOrNull() }

    fun keep(env: Env, stats: Stats?) { env.keyValues(KV)["stats"] = stats?.let { json.encodeToString(Stats.serializer(), it) }.orEmpty() }

    // ---------------------------------------------------------------- how niche

    /** The artists the score looks at: the ones listened to most. */
    const val OBSCURITY_ARTISTS = 20
    /** A song with this many listeners on ListenBrainz is as well known as it gets there. */
    const val FAMOUS = 300_000.0

    /**
     * 0 (everyone's artists) to 100 (nobody's): each artist's share of the listening ([weights], with
     * the listeners of their best known song on ListenBrainz) against a logarithmic scale up to
     * [FAMOUS]. Null with fewer than three artists known.
     */
    fun obscurityScore(weights: List<Pair<Long, Long>>): Int? {
        val known = weights.filter { it.first > 0 }
        if (known.size < 3) return null
        val total = known.sumOf { it.first }.toDouble()
        val fame = known.sumOf { (w, listeners) -> w / total * (log10(listeners + 1.0) / log10(FAMOUS)).coerceIn(0.0, 1.0) }
        return ((1 - fame) * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * [obscurityScore] for [stats]' top artists: each one's MusicBrainz id (kept, else asked, one a
     * second) and the listeners of their most played recording on ListenBrainz's popularity API, the
     * same call as the playlist generator's. An artist neither knows is left out.
     */
    suspend fun obscurity(env: Env, stats: Stats, progress: (Int, Int) -> Unit = { _, _ -> }): Int? {
        val token = ArtistMixSource.listenBrainzToken(env)
        val top = stats.topArtists.take(OBSCURITY_ARTISTS)
        val out = ArrayList<Pair<Long, Long>>()
        top.forEachIndexed { i, a ->
            progress(i, top.size)
            try {
                val artist = ArtistIds.mbid(env, a.name)?.let { ArtistMix.Artist(it, a.name) } ?: ArtistMixSource.findArtist(a.name)
                val listeners = artist?.let { ArtistMixSource.listeners(it, token) }
                if (listeners != null) out += stats.weight(a) to listeners
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
            }
        }
        return obscurityScore(out)
    }

    // ---------------------------------------------------------------- to ListenBrainz

    @Volatile internal var listenBrainz = "https://api.listenbrainz.org"
    /** ListenBrainz takes 1000 listens a request at most; fewer keeps a request small. */
    const val BATCH = 500

    /** A batch as ListenBrainz's "import" listens; a listen with no time cannot be sent and is left out. */
    fun listenPayload(plays: List<Play>): String = buildJsonObject {
        put("listen_type", "import")
        put("payload", buildJsonArray {
            for (p in plays) if (p.at > 0) add(buildJsonObject {
                put("listened_at", p.at / 1000)
                putJsonObject("track_metadata") {
                    put("artist_name", p.artist); put("track_name", p.title)
                    if (p.album.isNotBlank()) put("release_name", p.album)
                    putJsonObject("additional_info") {
                        put("submission_client", "Trackhop"); put("music_service", p.source.site)
                        if (p.ms > 0) put("duration_played", p.ms / 1000)
                        p.isrc?.let { put("isrc", it) }
                        p.uri?.takeIf { it.startsWith("spotify:track:") }?.let { put("spotify_id", "https://open.spotify.com/track/" + it.removePrefix("spotify:track:")) }
                    }
                }
            })
        })
    }.toString()

    /**
     * Sends [plays] (real listens only, with their time) to the user's ListenBrainz with [token], in
     * batches, waiting where ListenBrainz's window says so. Returns how many were sent. ListenBrainz
     * drops a listen it already has, so sending the same file twice adds nothing.
     */
    suspend fun submit(token: String, plays: List<Play>, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val real = plays.filter { it.at > 0 && (it.ms == 0L || it.ms >= MIN_MS) }
        var sent = 0
        for (batch in real.chunked(BATCH)) {
            progress(sent, real.size)
            val r = Http.send(
                "POST", "$listenBrainz/1/submit-listens", mapOf("Authorization" to "Token $token", "Accept" to "application/json"),
                Http.jsonBody(listenPayload(batch)), maxRateLimitRetries = 8,
            )
            if (r.header("X-RateLimit-Remaining") == "0") Http.pause((r.header("X-RateLimit-Reset-In")?.toLongOrNull()?.coerceIn(1, 60) ?: 5) * 1000 + 250)
            if (r.code == 401) throw ProviderException("ListenBrainz refused the token", code = 401)
            if (!r.ok) throw ProviderException("ListenBrainz ${r.code}")
            sent += batch.size
        }
        progress(sent, real.size)
        return sent
    }
}

/**
 * Just enough of a workbook (.xlsx: a zip of XML) to read a sheet's rows as text: the shared
 * strings and the cells of each row, by their column letters. No formulas, dates or styles.
 */
internal object Xlsx {
    private fun parse(xml: ByteArray, handler: org.xml.sax.helpers.DefaultHandler) {
        val f = javax.xml.parsers.SAXParserFactory.newInstance()
        // No DTD, no outside entity: the file comes from outside the app.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.newSAXParser().parse(ByteArrayInputStream(xml), handler)
    }

    fun sharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        parse(xml, object : org.xml.sax.helpers.DefaultHandler() {
            val sb = StringBuilder(); var inT = false
            override fun startElement(uri: String?, local: String?, q: String, a: org.xml.sax.Attributes?) { if (q == "si") sb.setLength(0) else if (q == "t") inT = true }
            override fun endElement(uri: String?, local: String?, q: String) { if (q == "t") inT = false else if (q == "si") out += sb.toString() }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) sb.append(ch, start, length) }
        })
        return out
    }

    /** "C7" is column 2. */
    private fun column(ref: String): Int {
        var n = 0
        for (c in ref) if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1) else break
        return n - 1
    }

    fun rows(xml: ByteArray, shared: List<String>, each: (List<String>) -> Unit) {
        parse(xml, object : org.xml.sax.helpers.DefaultHandler() {
            var row = ArrayList<String>(); var col = -1; var type = ""; val sb = StringBuilder(); var inV = false
            override fun startElement(uri: String?, local: String?, q: String, a: org.xml.sax.Attributes?) {
                when (q) {
                    "row" -> row = ArrayList()
                    "c" -> { col = a?.getValue("r")?.let { column(it) }?.takeIf { it >= 0 } ?: (col + 1); type = a?.getValue("t").orEmpty(); sb.setLength(0) }
                    "v", "t" -> inV = true
                }
            }
            override fun endElement(uri: String?, local: String?, q: String) {
                when (q) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val v = sb.toString()
                        while (row.size < col) row += ""
                        row += if (type == "s") shared.getOrNull(v.trim().toIntOrNull() ?: -1).orEmpty() else v
                    }
                    "row" -> { col = -1; if (row.any { it.isNotBlank() }) each(row) }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inV) sb.append(ch, start, length) }
        })
    }
}

package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs

/**
 * Lyrics for a music library on the server (Docker only): each audio file of the folder given as
 * SONGPORT_MUSIC is read for its artist, title, album and length; LRCLIB (lrclib.net, a free archive
 * written by its users) is asked for its lyrics; they are saved next to the file, synced as
 * `<name>.lrc`, plain as `<name>.txt`, where Navidrome, Jellyfin and Plex read them.
 *
 * Nothing is overwritten: a file with lyrics already (beside it or in its tags) is left alone, and a
 * file LRCLIB did not know is asked again only after [RETRY_MS] (or when the user asks).
 */
object Lyrics {
    const val TITLE = "Lyrics"
    /** The library, as mounted in the container (SONGPORT_MUSIC); null where the feature is off. */
    @Volatile var musicDir: File? = null
    /** LRCLIB's address; tests point it at a local stand-in. */
    @Volatile var api: String = "https://lrclib.net"

    val AUDIO = setOf("mp3", "flac", "m4a", "mp4", "aac", "alac", "ogg", "oga", "opus", "wma", "wav", "aif", "aiff", "dsf", "wv", "ape")
    private val SIDE = listOf("lrc", "txt")
    private const val RETRY_MS = 30L * 24 * 3600_000
    private const val WEEK_MS = 7L * 24 * 3600_000
    private const val KV = "lyrics_state"
    private const val MISSES = "lyrics-misses.tsv"
    /** jaudiotagger reports every odd frame on the log; kept here, or the setting is collected away. */
    private val quiet: Logger = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    /** The Docker server with a music folder; never Desktop, never the app. */
    fun available(edition: Html.Edition): Boolean = edition == Html.Edition.SERVER && musicDir != null

    /** From the environment: the folder, if it is one. */
    fun configure(env: Map<String, String>) {
        musicDir = env["SONGPORT_MUSIC"]?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isDirectory }
    }

    class Options(val folder: String = "", val syncedOnly: Boolean = false, val retryMissing: Boolean = false)

    data class Summary(
        val at: Long, val files: Int, val had: Int, val saved: Int, val synced: Int, val plain: Int,
        val instrumental: Int, val missing: Int, val unreadable: Int, val missingPaths: List<String> = emptyList(),
    )

    class Tags(val artist: String, val title: String, val album: String, val seconds: Int)

    class Found(val synced: String?, val plain: String?, val instrumental: Boolean)

    // ------------------------------------------------------------------ state

    fun lastRun(env: ServerEnv): Summary? {
        val kv = env.keyValues(KV)
        val at = kv["at"]?.toLongOrNull() ?: return null
        fun n(k: String) = kv[k]?.toIntOrNull() ?: 0
        return Summary(at, n("files"), n("had"), n("saved"), n("synced"), n("plain"), n("instrumental"), n("missing"), n("unreadable"))
    }

    private fun save(env: ServerEnv, s: Summary) {
        val kv = env.keyValues(KV)
        kv["files"] = s.files.toString(); kv["had"] = s.had.toString(); kv["saved"] = s.saved.toString()
        kv["synced"] = s.synced.toString(); kv["plain"] = s.plain.toString(); kv["instrumental"] = s.instrumental.toString()
        kv["missing"] = s.missing.toString(); kv["unreadable"] = s.unreadable.toString()
        kv["at"] = s.at.toString()
    }

    fun weekly(env: ServerEnv): Boolean = env.keyValues(KV)["weekly"] == "true"
    fun setWeekly(env: ServerEnv, on: Boolean) { env.keyValues(KV)["weekly"] = on.toString() }

    /** The weekly pass for new files, from the scheduler. */
    fun isDue(env: ServerEnv, edition: Html.Edition, now: Long): Boolean =
        available(edition) && weekly(env) && now - (lastRun(env)?.at ?: 0) >= WEEK_MS && !Tasks.running(TITLE)

    /** The files LRCLIB did not know (or knew as instrumentals), by path under the library, with when. */
    private fun misses(env: ServerEnv): MutableMap<String, Long> {
        val f = File(env.dataDir, MISSES)
        if (!f.isFile) return HashMap()
        return f.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0) } }.toMap(HashMap())
    }

    private fun saveMisses(env: ServerEnv, m: Map<String, Long>) {
        File(env.dataDir, MISSES).writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" })
    }

    // ------------------------------------------------------------------ the run

    /** Where a run looks: the library or a folder inside it, never outside. */
    fun target(root: File, folder: String): File? {
        val dir = if (folder.isBlank()) root else File(root, folder.trim().trimStart('/'))
        val c = dir.canonicalFile
        return c.takeIf { it.isDirectory && (it == root.canonicalFile || it.startsWith(root.canonicalFile)) }
    }

    suspend fun run(env: ServerEnv, options: Options, progress: (String) -> Unit): Summary {
        val root = musicDir ?: throw IllegalStateException("No music folder: set SONGPORT_MUSIC.")
        val dir = target(root, options.folder) ?: throw IllegalArgumentException("${options.folder} is not a folder of the library.")
        progress("Looking for audio files…")
        val files = dir.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() in AUDIO && !it.name.startsWith(".") }.sorted().toList()
        if (files.isNotEmpty() && !java.nio.file.Files.isWritable(files.first().parentFile.toPath())) throw IllegalStateException(READ_ONLY)
        val misses = misses(env)
        val now = System.currentTimeMillis()
        var had = 0; var saved = 0; var synced = 0; var plain = 0; var instrumental = 0; var unreadable = 0
        var failedInARow = 0
        val missing = ArrayList<String>()
        val rootPath = root.canonicalFile.toPath()
        try {
            files.forEachIndexed { i, f ->
                if (i % 10 == 0) progress("${i + 1} of ${files.size}: ${f.name}")
                val rel = rootPath.relativize(f.canonicalFile.toPath()).toString()
                if (hasLyrics(f)) { had++; return@forEachIndexed }
                val tried = misses[rel]
                if (tried != null && !options.retryMissing && now - tried < RETRY_MS) { missing += rel; return@forEachIndexed }
                val tags = readTags(f) ?: run { unreadable++; return@forEachIndexed }
                // LRCLIB busy or unreachable: the file is not "not found", it is asked again next time;
                // several in a row and the run stops instead of going through the library for nothing.
                val found = try { fetch(tags).also { failedInARow = 0 } } catch (e: CancellationException) { throw e } catch (e: Busy) {
                    // What LRCLIB does not have it looks for elsewhere, and says "busy" when that is
                    // overloaded: most often a track it does not know. Asked again in a week, not a month.
                    missing += rel; misses[rel] = now - RETRY_MS + WEEK_MS
                    return@forEachIndexed
                } catch (e: Exception) {
                    env.log("lyrics", "${f.name}: ${e.message}")
                    if (++failedInARow >= MAX_FAILURES) throw IllegalStateException("LRCLIB is not answering (${e.message}): try again later. $saved lyrics saved so far.")
                    return@forEachIndexed
                }
                when {
                    found == null -> { missing += rel; misses[rel] = now }
                    found.instrumental -> { instrumental++; misses[rel] = now }
                    found.synced != null -> { write(f, "lrc", found.synced); saved++; synced++; misses.remove(rel) }
                    found.plain != null && !options.syncedOnly -> { write(f, "txt", found.plain); saved++; plain++; misses.remove(rel) }
                    else -> { missing += rel; misses[rel] = now }
                }
            }
        } finally {
            saveMisses(env, misses)
        }
        val s = Summary(System.currentTimeMillis(), files.size, had, saved, synced, plain, instrumental, missing.size, unreadable, missing)
        save(env, s)
        env.log("lyrics", "${files.size} files: $saved saved ($synced synced), $had had lyrics, ${missing.size} not found")
        return s
    }

    private fun write(audio: File, ext: String, text: String) {
        val out = File(audio.parentFile, audio.nameWithoutExtension + "." + ext)
        try {
            out.writeText(text.trimEnd() + "\n")
        } catch (e: java.io.IOException) {
            throw IllegalStateException("$READ_ONLY (${out.parentFile.name}: ${e.message})")
        }
    }

    private const val MAX_FAILURES = 5
    private const val READ_ONLY = "Trackhop cannot write into the music folder: mount it without :ro, and let the container's user write there (see the guide, Lyrics)."

    /** Lyrics beside the file (`.lrc`, `.txt`) or in its tags. */
    fun hasLyrics(f: File): Boolean {
        if (SIDE.any { File(f.parentFile, f.nameWithoutExtension + "." + it).isFile }) return true
        return runCatching { AudioFileIO.read(f).tag?.getFirst(FieldKey.LYRICS)?.isNotBlank() == true }.getOrDefault(false)
    }

    /**
     * Artist, title, album and length from the tags; without tags, from the names ("Artist - Title.mp3"
     * in an album folder, or Artist/Album/01 Title.mp3).
     */
    fun readTags(f: File): Tags? {
        val read = runCatching { AudioFileIO.read(f) }.getOrNull()
        val tag = read?.tag
        val seconds = read?.audioHeader?.trackLength ?: 0
        val artist = tag?.getFirst(FieldKey.ARTIST)?.trim().orEmpty().ifEmpty { tag?.getFirst(FieldKey.ALBUM_ARTIST)?.trim().orEmpty() }
        val title = tag?.getFirst(FieldKey.TITLE)?.trim().orEmpty()
        val album = tag?.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
        if (artist.isNotEmpty() && title.isNotEmpty()) return Tags(artist, title, album, seconds)
        val name = f.nameWithoutExtension.replace(Regex("""^\d{1,3}[\s.\-_]+"""), "").trim()
        val parts = name.split(Regex("""\s+[-–]\s+"""), limit = 2)
        return when {
            parts.size == 2 -> Tags(parts[0].trim(), parts[1].trim(), album.ifEmpty { f.parentFile?.name.orEmpty() }, seconds)
            f.parentFile?.parentFile != null && name.isNotEmpty() ->
                Tags(f.parentFile.parentFile.name, name, f.parentFile.name, seconds).takeIf { it.artist.isNotBlank() }
            else -> null
        }
    }

    // ------------------------------------------------------------------ LRCLIB

    private val UA = mapOf("User-Agent" to "Trackhop (https://github.com/xlollx/Trackhop)", "Accept" to "application/json")

    /** LRCLIB's "The server is busy, please retry in a moment" (503), after [Http.send]'s own second try. */
    class Busy : Exception("LRCLIB: busy")

    private suspend fun get(path: String): JsonElement? {
        val r = Http.send("GET", "$api$path", UA, maxRateLimitRetries = 4)
        return when (r.code) {
            200 -> parseJson(r.body)
            404 -> null
            503 -> throw Busy()
            else -> throw IllegalStateException("LRCLIB: HTTP ${r.code}")
        }
    }

    /** A lookup that may come back busy: the next way of asking is tried instead. */
    private suspend fun lookup(path: String): JsonElement? = try { get(path) } catch (e: Busy) { null }

    private fun found(j: JsonElement?): Found? {
        if (j == null) return null
        val synced = j["syncedLyrics"].str?.takeIf { it.isNotBlank() }
        val plain = j["plainLyrics"].str?.takeIf { it.isNotBlank() }
        val instrumental = j["instrumental"].bool == true
        return Found(synced, plain, instrumental).takeIf { synced != null || plain != null || instrumental }
    }

    /**
     * The exact record first (title, artist, album, length as tagged), then the title without its
     * decorations and the first artist, then a search scored by the matcher with the length within
     * a few seconds: a remaster's lyrics are the original's, a live version's often not.
     */
    suspend fun fetch(t: Tags): Found? {
        fun q(title: String, artist: String, album: String?, seconds: Int) =
            "/api/get?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}" +
                (album?.takeIf { it.isNotBlank() }?.let { "&album_name=${Http.enc(it)}" } ?: "") +
                (if (seconds > 0) "&duration=$seconds" else "")
        // LRCLIB's exact lookup wants the length; without it (an untagged file) only title and artist.
        found(lookup(q(t.title, t.artist, t.album.takeIf { t.seconds > 0 }, t.seconds)))?.let { return it }
        val title = Matcher.plainTitle(t.title)
        val artist = Matcher.primaryArtist(t.artist) ?: t.artist
        if (title != t.title || artist != t.artist || t.album.isNotBlank()) found(lookup(q(title, artist, null, t.seconds)))?.let { return it }
        val results = get("/api/search?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}").arr
        val src = Track(id = "", title = t.title, artists = listOf(t.artist), album = t.album, durationMs = t.seconds * 1000L)
        val best = results.mapNotNull { j ->
            val seconds = j["duration"].long ?: 0
            if (t.seconds > 0 && seconds > 0 && abs(seconds - t.seconds) > 3) return@mapNotNull null
            val cand = Track(id = "", title = j["trackName"].str ?: return@mapNotNull null, artists = listOfNotNull(j["artistName"].str), durationMs = seconds * 1000)
            val score = Matcher.score(src, cand) + if (j["syncedLyrics"].str.isNullOrBlank()) 0.0 else 0.01
            (score to j).takeIf { score >= Matcher.DEFAULT_THRESHOLD }
        }.maxByOrNull { it.first }
        return found(best?.second)
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LyricsSong
import com.xlollx.songport.providers.LyricsSupport
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.math.abs

/**
 * LRCLIB (https://lrclib.net/docs), a free archive of lyrics written by its users: no key, no account.
 * Asked as its documentation asks: a User-Agent naming the application, its version and its page
 * ([userAgent]); one request at a time, at most one a second ([pace]); Retry-After honoured on a 429
 * (by [Http.send]). Each run keeps what it asked ([Run]), misses too, so a song is never asked twice.
 */
object LrcLib {
    /** LRCLIB's address; tests point it elsewhere. */
    @Volatile var api: String = "https://lrclib.net"

    /** "Trackhop/<version> (https://github.com/xlollx/Trackhop)": set at start by the app and the server, which know the version. */
    @Volatile var userAgent: String = "Trackhop (https://github.com/xlollx/Trackhop)"

    /** The gap between two requests: one a second at most. */
    const val GAP_MS = 1_000L

    /** One request a second, across every run (tests set a shorter one). */
    @Volatile internal var pace = Pacer(GAP_MS)

    /** What LRCLIB has for a song: its synced lyrics (LRC), its plain ones, or both. */
    class Found(val synced: String?, val plain: String?)

    /** The answers of one run, by song, the songs LRCLIB did not know included (null). */
    class Run { internal val seen = HashMap<String, Found?>() }

    /** LRCLIB did not answer (busy, too many requests, a server error): the song is left for another time. */
    class Unanswered(message: String) : Exception(message)

    private suspend fun ask(path: String): JsonElement? {
        val r = pace.paced { Http.send("GET", "$api$path", mapOf("User-Agent" to userAgent, "Accept" to "application/json")) }
        return when (r.code) {
            200 -> parseJson(r.body)
            404 -> null
            else -> throw Unanswered("LRCLIB: HTTP ${r.code}")
        }
    }

    /** Lyrics in [j], synced or plain; null for none (an instrumental, an empty record). */
    internal fun found(j: JsonElement?): Found? {
        if (j == null || j["instrumental"].bool == true) return null
        val synced = j["syncedLyrics"].str?.takeIf { it.isNotBlank() }
        val plain = j["plainLyrics"].str?.takeIf { it.isNotBlank() }
        return if (synced == null && plain == null) null else Found(synced, plain)
    }

    /**
     * [track]'s lyrics: the exact record first (`/api/get` with title, artist, album and length), then a
     * search (`/api/search`) scored by the matcher, its length within 3 seconds. Null when LRCLIB has
     * none; [Unanswered] when it did not answer.
     */
    suspend fun find(track: Track, session: Run): Found? {
        val artist = track.artists.firstOrNull()?.trim().orEmpty()
        val title = track.title.trim()
        if (artist.isEmpty() || title.isEmpty()) return null
        val seconds = (track.durationMs / 1000).toInt().takeIf { it in 1..3600 }
        val key = "${Matcher.normalizeArtist(artist)}|${title.lowercase()}|${seconds ?: 0}"
        if (session.seen.containsKey(key)) return session.seen[key]
        val q = buildString {
            append("/api/get?track_name=").append(Http.enc(title)).append("&artist_name=").append(Http.enc(artist))
            if (track.album.isNotBlank()) append("&album_name=").append(Http.enc(track.album.trim()))
            if (seconds != null) append("&duration=").append(seconds)
        }
        val exact = found(ask(q))
        val result = exact ?: kotlin.run {
            val plainTitle = Matcher.plainTitle(title)
            val firstArtist = Matcher.primaryArtist(artist) ?: artist
            val results = ask("/api/search?track_name=${Http.enc(plainTitle)}&artist_name=${Http.enc(firstArtist)}").arr
            val best = results.mapNotNull { j ->
                val len = j["duration"].long ?: 0
                if (seconds != null && len > 0 && abs(len - seconds) > 3) return@mapNotNull null
                val cand = Track(id = "", title = j["trackName"].str ?: return@mapNotNull null, artists = listOfNotNull(j["artistName"].str), durationMs = len * 1000)
                val f = found(j) ?: return@mapNotNull null
                val score = Matcher.score(track, cand) + if (f.synced != null) 0.01 else 0.0
                (score to f).takeIf { score >= Matcher.DEFAULT_THRESHOLD }
            }.maxByOrNull { it.first }
            best?.second
        }
        session.seen[key] = result
        return result
    }
}

/**
 * Tools › Synced lyrics (1.18): the songs of a personal server without lyrics ([report]), their lyrics
 * looked up on LRCLIB ([lookUp]) and shown before anything is written, then, once the user says so,
 * handed to the server ([save]): through its API where it has one (Jellyfin), else as a `.lrc` or
 * `.txt` file next to the audio file ([Sidecars], Desktop and Docker only).
 *
 * Nothing is ever replaced or deleted: a song with lyrics is skipped, a file is only ever created new.
 * A song that fails is left as it was, and the run goes on.
 */
object SyncedLyrics {
    /** The library: how many songs it has, and those the server has no lyrics for. */
    data class Report(val total: Int, val missing: List<LyricsSong>)

    /** What LRCLIB had for a song. */
    enum class Kind { SYNCED, PLAIN, NONE, ERROR }

    /** A song and the lyrics found for it ([text] null unless [kind] is SYNCED or PLAIN). */
    data class Proposal(val song: LyricsSong, val kind: Kind, val text: String? = null)

    /** What [save] did. */
    data class Saved(val saved: Int, val synced: Int, val plain: Int, val kept: Int, val failed: Int, val notFound: Int)

    /** A server the report can read: its lyrics are known to the API. */
    fun reads(p: MusicProvider): Boolean = p.lyricsSupport in setOf(LyricsSupport.REPORT_ONLY, LyricsSupport.SERVER_API, LyricsSupport.SIDECAR)

    /** The songs of [provider]'s library without lyrics. Read only. */
    suspend fun report(ctx: Env, provider: MusicProvider, onProgress: (Int) -> Unit = {}): Report {
        val songs = provider.lyricsSongs(ctx, onProgress)
        return Report(songs.size, songs.filter { !it.hasLyrics })
    }

    /** LRCLIB answering nothing this many songs in a row: the look-up stops instead of going on for nothing. */
    private const val MAX_UNANSWERED = 5

    /**
     * Each of [songs] looked up on LRCLIB, synced lyrics first, else plain ones. Writes nothing.
     * [onProgress] gets how many were looked up.
     */
    suspend fun lookUp(songs: List<LyricsSong>, session: LrcLib.Run = LrcLib.Run(), onProgress: (Int) -> Unit = {}): List<Proposal> {
        val out = ArrayList<Proposal>()
        var unanswered = 0
        for (s in songs) {
            val p = try {
                val f = LrcLib.find(s.track, session)
                unanswered = 0
                when {
                    f == null -> Proposal(s, Kind.NONE)
                    f.synced != null -> Proposal(s, Kind.SYNCED, f.synced)
                    else -> Proposal(s, Kind.PLAIN, f.plain)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (++unanswered >= MAX_UNANSWERED) throw LrcLib.Unanswered("LRCLIB is not answering (${e.message}): try again later.")
                Proposal(s, Kind.ERROR)
            }
            out += p
            onProgress(out.size)
        }
        return out
    }

    /**
     * Writes the lyrics of [proposals] to [provider]: through its API (SERVER_API), or as files next to
     * the audio through [sidecars] (SIDECAR, required then). Songs with lyrics by now are kept as they
     * are; one that fails is left untouched and the next one goes. One line of summary in the log.
     */
    suspend fun save(ctx: Env, provider: MusicProvider, proposals: List<Proposal>, sidecars: Sidecars? = null, onProgress: (Int) -> Unit = {}): Saved {
        val support = provider.lyricsSupport
        require(support == LyricsSupport.SERVER_API || (support == LyricsSupport.SIDECAR && sidecars != null)) {
            if (support == LyricsSupport.SIDECAR) "${provider.displayName}: lyrics are files next to the music; set the music folder first."
            else "${provider.displayName}: lyrics cannot be added to this server."
        }
        var saved = 0; var synced = 0; var plain = 0; var kept = 0; var failed = 0; var done = 0
        val notFound = proposals.count { it.text == null }
        for (p in proposals) {
            val text = p.text ?: continue
            val isSynced = p.kind == Kind.SYNCED
            try {
                val wrote = if (support == LyricsSupport.SERVER_API) provider.saveLyrics(ctx, p.song.track, text, isSynced)
                else when (sidecars!!.write(p.song.path, text, isSynced)) {
                    Sidecars.Outcome.WRITTEN -> true
                    Sidecars.Outcome.HAS_LYRICS -> false
                    Sidecars.Outcome.NO_FILE -> throw IllegalStateException("${p.song.path ?: p.song.track}: no such file in the music folder")
                }
                if (wrote) { saved++; if (isSynced) synced++ else plain++ } else kept++
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                failed++
                ctx.log("lyrics", "${p.song.track}: ${e.message ?: e.javaClass.simpleName}")
            }
            onProgress(++done)
        }
        ctx.log("lyrics", "${provider.label(ctx)}: $saved lyrics saved ($synced synced, $plain plain), $kept had lyrics already, $failed failed, $notFound not found")
        return Saved(saved, synced, plain, kept, failed, notFound)
    }

    /**
     * The server's music folder as this computer sees it: a song's path as the server reports it
     * ([serverPrefix], e.g. `/music` in Navidrome's container) becomes a file under [localDir] (e.g.
     * `/data/music`, mounted read-write in Trackhop's). A path outside [serverPrefix], or one that
     * leads outside [localDir], has no file here.
     */
    class Sidecars(val serverPrefix: String, val localDir: File) {
        enum class Outcome { WRITTEN, HAS_LYRICS, NO_FILE }

        /** The audio file for the server's [serverPath], when it is under the folder and exists. */
        fun audioFile(serverPath: String?): File? {
            if (serverPath.isNullOrBlank()) return null
            val path = serverPath.replace('\\', '/')
            val prefix = serverPrefix.trim().replace('\\', '/').trimEnd('/')
            val rel = when {
                prefix.isEmpty() -> path.trimStart('/')
                path.startsWith("$prefix/") -> path.removePrefix("$prefix/")
                else -> return null
            }
            if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
            val root = localDir.canonicalFile
            val f = File(root, rel).canonicalFile
            if (!f.toPath().startsWith(root.toPath())) return null
            return f.takeIf { it.isFile }
        }

        /** The lyrics files the servers read for [audio]: `<name>.lrc` and `<name>.txt`, any case. */
        private fun sidecarsOf(audio: File): List<File> {
            val base = audio.nameWithoutExtension
            val names = setOf("$base.lrc", "$base.txt")
            return audio.parentFile?.listFiles()?.filter { f -> names.any { it.equals(f.name, ignoreCase = true) } }.orEmpty()
        }

        /**
         * [text] next to the audio file of [serverPath]: synced as `<name>.lrc`, plain as `<name>.txt`, in
         * UTF-8. Only ever a new file: with either one there already, nothing is written.
         */
        fun write(serverPath: String?, text: String, synced: Boolean): Outcome {
            val audio = audioFile(serverPath) ?: return Outcome.NO_FILE
            if (sidecarsOf(audio).isNotEmpty()) return Outcome.HAS_LYRICS
            val out = File(audio.parentFile, audio.nameWithoutExtension + if (synced) ".lrc" else ".txt")
            return try {
                Files.write(out.toPath(), (text.trimEnd() + "\n").toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                Outcome.WRITTEN
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                Outcome.HAS_LYRICS
            }
        }
    }
}

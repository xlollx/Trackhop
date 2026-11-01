package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** Tools › Lyrics against a stand-in for LRCLIB: what is saved where, what is left alone, what is asked again. */
class LyricsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var lrclib: HttpServer
    private lateinit var music: File
    private lateinit var env: ServerEnv
    private val calls = AtomicInteger()
    private val asked = mutableListOf<String>()

    private val synced = "[00:01.00] Tonight I'm gonna have myself a real good time"

    @Before fun start() {
        lrclib = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        lrclib.createContext("/api/") { ex ->
            calls.incrementAndGet()
            val q = ex.requestURI.rawQuery.orEmpty().split('&').associate { p ->
                p.substringBefore('=') to URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
            }
            synchronized(asked) { asked += "${ex.requestURI.path} ${q["artist_name"]} / ${q["track_name"]}" }
            val (code, body) = when {
                ex.requestURI.path == "/api/get" && q["artist_name"] == "Queen" && q["track_name"] == "Don't Stop Me Now" ->
                    200 to """{"trackName":"Don't Stop Me Now","artistName":"Queen","duration":209,"instrumental":false,"plainLyrics":"Tonight","syncedLyrics":"$synced"}"""
                // Only the cleaned title is known: "Hey Jude", not "Hey Jude - Remastered 2015".
                ex.requestURI.path == "/api/get" && q["artist_name"] == "The Beatles" && q["track_name"] == "Hey Jude" ->
                    200 to """{"trackName":"Hey Jude","artistName":"The Beatles","instrumental":false,"plainLyrics":"Hey Jude, don't make it bad","syncedLyrics":null}"""
                ex.requestURI.path == "/api/get" && q["artist_name"] == "Vangelis" ->
                    200 to """{"trackName":"Chariots of Fire","artistName":"Vangelis","instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""
                ex.requestURI.path == "/api/search" && q["artist_name"] == "Mahmood" ->
                    200 to """[{"trackName":"Tuta gold","artistName":"Mahmood","duration":0,"plainLyrics":"Tuta gold","syncedLyrics":"[00:02.00] Tuta gold"}]"""
                q["artist_name"] == "Busy" -> 503 to """{"name":"ServerOverloaded"}"""
                ex.requestURI.path == "/api/search" -> 200 to "[]"
                else -> 404 to """{"code":404,"name":"TrackNotFound"}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        lrclib.start()
        Lyrics.api = "http://127.0.0.1:${lrclib.address.port}"
        music = tmp.newFolder("music")
        Lyrics.musicDir = music
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://localhost")
    }

    @After fun stop() {
        lrclib.stop(0)
        Lyrics.musicDir = null
        Lyrics.api = "https://lrclib.net"
    }

    private fun file(path: String) = File(music, path).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    /** A real WAV with tags, read by jaudiotagger as a library would be. */
    private fun taggedWav(path: String, artist: String, title: String, album: String): File {
        val f = File(music, path).apply { parentFile.mkdirs() }
        val format = AudioFormat(8000f, 8, 1, true, false)
        val silence = ByteArray(8000 * 2)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(silence), format, silence.size.toLong()), AudioFileFormat.Type.WAVE, f)
        val af = AudioFileIO.read(f)
        val tag = af.tagOrCreateAndSetDefault
        tag.setField(FieldKey.ARTIST, artist); tag.setField(FieldKey.TITLE, title); tag.setField(FieldKey.ALBUM, album)
        af.commit()
        return f
    }

    @Test fun savesSyncedAsLrcAndPlainAsTxtAndLeavesTheRestAlone() = runBlocking {
        val queen = file("Queen/Jazz/Queen - Don't Stop Me Now.mp3")
        val jude = file("The Beatles/Hey Jude/The Beatles - Hey Jude - Remastered 2015.flac")
        val had = file("Other/Artist - Has Lyrics.mp3")
        File(had.parentFile, "Artist - Has Lyrics.lrc").writeText("[00:00.00] mine")
        file("Vangelis/Chariots/Vangelis - Chariots of Fire.mp3")
        file("Nobody/Nothing/Nobody - Unknown Song.mp3")
        file("cover.jpg")

        val s = Lyrics.run(env, Lyrics.Options()) {}

        assertEquals(5, s.files)
        assertEquals(synced + "\n", File(queen.parentFile, "Queen - Don't Stop Me Now.lrc").readText())
        assertEquals("Hey Jude, don't make it bad\n", File(jude.parentFile, "The Beatles - Hey Jude - Remastered 2015.txt").readText())
        assertEquals("[00:00.00] mine", File(had.parentFile, "Artist - Has Lyrics.lrc").readText())
        assertEquals(2, s.saved); assertEquals(1, s.synced); assertEquals(1, s.plain)
        assertEquals(1, s.had); assertEquals(1, s.instrumental); assertEquals(1, s.missing)
        assertEquals(listOf("Nobody/Nothing/Nobody - Unknown Song.mp3"), s.missingPaths)
        assertFalse(File(music, "Vangelis/Chariots/Vangelis - Chariots of Fire.lrc").exists())
    }

    @Test fun whatWasNotFoundIsNotAskedAgainUntilTheUserSays() = runBlocking {
        file("Nobody/Nothing/Nobody - Unknown Song.mp3")
        Lyrics.run(env, Lyrics.Options()) {}
        val first = calls.get()
        assertTrue(first > 0)
        val again = Lyrics.run(env, Lyrics.Options()) {}
        assertEquals(first, calls.get())
        assertEquals(1, again.missing)
        Lyrics.run(env, Lyrics.Options(retryMissing = true)) {}
        assertTrue(calls.get() > first)
    }

    @Test fun busyMeansNotFoundForAWeekAndDoesNotStopTheRun() = runBlocking {
        (1..2).forEach { file("Busy/A/Busy - Song $it.mp3") }
        file("Queen - Don't Stop Me Now.mp3")
        val s = Lyrics.run(env, Lyrics.Options()) {}
        assertEquals(2, s.missing)
        assertEquals(1, s.synced)
        // Not asked again the next day…
        val before = calls.get()
        Lyrics.run(env, Lyrics.Options()) {}
        assertEquals(before, calls.get())
        // …but a week on, sooner than a plain "not found".
        val misses = File(env.dataDir, "lyrics-misses.tsv")
        misses.writeText(misses.readLines().joinToString("") { l -> l.substringBefore('\t') + "\t" + (l.substringAfter('\t').toLong() - 7L * 24 * 3600_000 - 1) + "\n" })
        Lyrics.run(env, Lyrics.Options()) {}
        assertTrue(calls.get() > before)
    }

    @Test fun syncedOnlySkipsPlainLyrics() = runBlocking {
        val jude = file("The Beatles - Hey Jude.mp3")
        val s = Lyrics.run(env, Lyrics.Options(syncedOnly = true)) {}
        assertEquals(0, s.saved)
        assertFalse(File(jude.parentFile, "The Beatles - Hey Jude.txt").exists())
    }

    @Test fun tagsWinOverTheFileNameAndASearchFindsTheRest() = runBlocking {
        val wav = taggedWav("x/track01.wav", "Mahmood & Blanco", "Tuta gold (con Bresh)", "Single")
        val tags = Lyrics.readTags(wav)!!
        assertEquals("Mahmood & Blanco", tags.artist)
        assertEquals(2, tags.seconds)
        val s = Lyrics.run(env, Lyrics.Options()) {}
        assertEquals(1, s.synced)
        assertEquals("[00:02.00] Tuta gold\n", File(wav.parentFile, "track01.lrc").readText())
        // The exact record, the cleaned one with the first artist, then the search.
        assertTrue(asked.any { it == "/api/get Mahmood / Tuta gold" })
    }

    @Test fun aFolderOutsideTheLibraryIsRefused() {
        assertNull(Lyrics.target(music, "../data"))
        assertNull(Lyrics.target(music, "nope"))
        File(music, "Queen").mkdirs()
        assertEquals(File(music, "Queen").canonicalFile, Lyrics.target(music, "Queen"))
    }

    @Test fun theApiStartsARunAndSaysHowItWent() {
        file("Queen - Don't Stop Me Now.mp3")
        val token = Api.newToken(env)
        val server = Web(env, Runner(env), Auth(env), null, "test").start(0)
        try {
            val base = "http://127.0.0.1:${server.address.port}/api/v1/lyrics"
            val client = HttpClient.newHttpClient()
            fun call(method: String, url: String) = client.send(
                HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token").method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val started = call("POST", base)
            assertEquals(202, started.statusCode())
            val task = Regex(""""task":"([^"]+)"""").find(started.body())!!.groupValues[1]
            repeat(100) { if (Tasks.get(task)?.done != true) Thread.sleep(50) }
            assertTrue(Tasks.get(task)!!.done)
            val status = call("GET", base).body()
            assertTrue(status, status.contains(""""synced":1"""))
            assertEquals(400, call("POST", "$base?folder=..%2F..").statusCode())
        } finally {
            server.stop(0)
        }
    }
}

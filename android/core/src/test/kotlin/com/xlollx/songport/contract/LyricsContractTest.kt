package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.providers.EmbyProvider
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.LyricsSong
import com.xlollx.songport.providers.LyricsSupport
import com.xlollx.songport.providers.LyrionProvider
import com.xlollx.songport.providers.MopidyProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.SubsonicProvider
import com.xlollx.songport.sync.LrcLib
import com.xlollx.songport.sync.SyncedLyrics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tools › Synced lyrics against stand-ins of LRCLIB and of each personal server, as their documented
 * APIs answer: what is asked, in which order, with which headers, and that nothing is replaced.
 * Artists and songs are made up.
 */
class LyricsContractTest : ContractTest() {
    private val song = Track(id = "s1", title = "Paper Lanterns", artists = listOf("The Velvet Orchard"), album = "Night Garden", durationMs = 214_000)

    @Before fun fastPace() {
        LrcLib.pace = Pacer(0)
        LrcLib.userAgent = "Trackhop/1.18.0 (https://github.com/xlollx/Trackhop)"
    }

    @After fun normalPace() { LrcLib.pace = Pacer(LrcLib.GAP_MS) }

    private fun connect(id: String, extra: Map<String, String> = emptyMap()) =
        env.tokens.set(id, Tokens(accessToken = "server-token", userId = "user-1", userName = "me", extra = mapOf("url" to "https://music.example") + extra))

    private fun record(synced: String?, plain: String?, instrumental: Boolean = false, title: String = "Paper Lanterns", duration: Int = 214) =
        """{"id":7,"trackName":${q(title)},"artistName":"The Velvet Orchard","albumName":"Night Garden","duration":$duration,"instrumental":$instrumental,""" +
            """"plainLyrics":${plain?.let { q(it) } ?: "null"},"syncedLyrics":${synced?.let { q(it) } ?: "null"}}"""

    // ------------------------------------------------------------------ LRCLIB

    @Test fun lrclibAsksTheExactRecordWithAnHonestUserAgent() = runBlocking {
        json(record("[00:01.00] Light the way", "Light the way"))
        val f = LrcLib.find(song, LrcLib.Run())!!
        assertEquals("[00:01.00] Light the way", f.synced)
        val r = next()
        assertCall(r, "GET", "lrclib.net", "/api/get")
        assertEquals("Paper Lanterns", r.query("track_name"))
        assertEquals("The Velvet Orchard", r.query("artist_name"))
        assertEquals("Night Garden", r.query("album_name"))
        assertEquals("214", r.query("duration"))
        assertEquals("Trackhop/1.18.0 (https://github.com/xlollx/Trackhop)", r.getHeader("User-Agent"))
        noMoreRequests()
    }

    @Test fun lrclibSearchesAfterA404AndKeepsTheClosestLength() = runBlocking {
        text("""{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}""", 404, "application/json")
        json("[" + record(null, "Wrong length", duration = 300) + "," + record("[00:02.00] Close enough", "Close enough", duration = 215) + "]")
        val f = LrcLib.find(song, LrcLib.Run())!!
        assertEquals("[00:02.00] Close enough", f.synced)
        next()
        val s = next()
        assertCall(s, "GET", "lrclib.net", "/api/search")
        assertEquals("Paper Lanterns", s.query("track_name"))
    }

    @Test fun lrclibMissesAreAskedOncePerRun() = runBlocking {
        text("{}", 404, "application/json")
        json("[]")
        val run = LrcLib.Run()
        assertNull(LrcLib.find(song, run))
        assertNull(LrcLib.find(song.copy(id = "s2"), run))
        next(); next()
        noMoreRequests()
    }

    @Test fun lrclibInstrumentalsAndEmptyRecordsAreNone() = runBlocking {
        json(record(null, null, instrumental = true))
        json("[]")
        assertNull(LrcLib.find(song, LrcLib.Run()))
    }

    @Test fun lrclibPlainWhenNoSynced() = runBlocking {
        json(record(null, "Only words"))
        val p = SyncedLyrics.lookUp(listOf(LyricsSong(song, false))).single()
        assertEquals(SyncedLyrics.Kind.PLAIN, p.kind)
        assertEquals("Only words", p.text)
    }

    @Test fun lrclibBusyLeavesTheSongAndGoesOn() = runBlocking {
        text("busy", 500, "text/plain")
        json(record("[00:01.00] Second", "Second", title = "Glass Harbour"))
        val other = song.copy(id = "s2", title = "Glass Harbour")
        val out = SyncedLyrics.lookUp(listOf(LyricsSong(song, false), LyricsSong(other, false)))
        assertEquals(listOf(SyncedLyrics.Kind.ERROR, SyncedLyrics.Kind.SYNCED), out.map { it.kind })
    }

    @Test fun lrclibIsPacedOneRequestASecond() = runBlocking {
        LrcLib.pace = Pacer(LrcLib.GAP_MS)
        text("{}", 404, "application/json")
        json("[]")
        val t0 = System.currentTimeMillis()
        LrcLib.find(song, LrcLib.Run())
        assertTrue(System.currentTimeMillis() - t0 >= 900)
    }

    // ------------------------------------------------------------------ Subsonic / Navidrome

    private val subsonicSongs = """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{"song":[
        {"id":"s1","title":"Paper Lanterns","artist":"The Velvet Orchard","album":"Night Garden","duration":214,"path":"/music/Velvet Orchard/Night Garden/01 Paper Lanterns.flac"},
        {"id":"s2","title":"Glass Harbour","artist":"The Velvet Orchard","album":"Night Garden","duration":190,"path":"/music/Velvet Orchard/Night Garden/02 Glass Harbour.flac"}]}}}"""

    @Test fun subsonicAsksStructuredLyricsWhereTheServerHasThem() = runBlocking {
        connect("subsonic")
        val p = SubsonicProvider()
        assertEquals(LyricsSupport.SIDECAR, p.lyricsSupport)
        json(subsonicSongs)
        json("""{"subsonic-response":{"status":"ok","openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]}}""")
        json("""{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{"synced":true,"line":[{"start":0,"value":"Light the way"}]}]}}}""")
        json("""{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[]}}}""")
        val songs = p.lyricsSongs(env)
        assertEquals(listOf(true, false), songs.map { it.hasLyrics })
        assertEquals("/music/Velvet Orchard/Night Garden/02 Glass Harbour.flac", songs[1].path)
        assertEquals("search3", next().pathOnly().substringAfterLast('/'))
        assertEquals("getOpenSubsonicExtensions", next().pathOnly().substringAfterLast('/'))
        val l = next()
        assertEquals("/rest/getLyricsBySongId", l.pathOnly())
        assertEquals("s1", l.query("id"))
        next()
        noMoreRequests()
    }

    @Test fun subsonicFallsBackToGetLyrics() = runBlocking {
        connect("subsonic")
        json(subsonicSongs)
        json("""{"subsonic-response":{"status":"ok","openSubsonicExtensions":[]}}""")
        json("""{"subsonic-response":{"status":"ok","lyrics":{"artist":"The Velvet Orchard","title":"Paper Lanterns","value":"Light the way"}}}""")
        json("""{"subsonic-response":{"status":"ok","lyrics":{}}}""")
        val songs = SubsonicProvider().lyricsSongs(env)
        assertEquals(listOf(true, false), songs.map { it.hasLyrics })
        next(); next()
        val l = next()
        assertEquals("/rest/getLyrics", l.pathOnly())
        assertEquals("The Velvet Orchard", l.query("artist"))
        assertEquals("Paper Lanterns", l.query("title"))
    }

    // ------------------------------------------------------------------ Jellyfin

    @Test fun jellyfinReadsHasLyricsAndPaths() = runBlocking {
        connect("jellyfin")
        json("""{"Items":[{"Id":"a1","Name":"Paper Lanterns","Artists":["The Velvet Orchard"],"HasLyrics":true,"Path":"/media/music/a.flac"},
            {"Id":"a2","Name":"Glass Harbour","Artists":["The Velvet Orchard"],"HasLyrics":false,"Path":"/media/music/b.flac"}]}""")
        val songs = JellyfinProvider().lyricsSongs(env)
        assertEquals(listOf(true, false), songs.map { it.hasLyrics })
        assertEquals("/media/music/b.flac", songs[1].path)
        val r = next()
        assertEquals("/Items", r.pathOnly())
        assertEquals("Path", r.query("Fields"))
        assertEquals("Audio", r.query("IncludeItemTypes"))
    }

    @Test fun jellyfinBefore109SaysSoInsteadOfAnEmptyReport() = runBlocking {
        connect("jellyfin")
        json("""{"Items":[{"Id":"a1","Name":"Paper Lanterns","Artists":["The Velvet Orchard"]}]}""")
        val e = runCatching { JellyfinProvider().lyricsSongs(env) }.exceptionOrNull()
        assertEquals("lyrics_error_old_jellyfin", e?.message)
    }

    @Test fun jellyfinUploadsThroughItsApiOnlyWhereThereAreNone() = runBlocking {
        connect("jellyfin")
        val p = JellyfinProvider()
        assertEquals(LyricsSupport.SERVER_API, p.lyricsSupport)
        text("""{"message":"not found"}""", 404, "application/json")
        json("""{"Lyrics":[{"Text":"Light the way","Start":10000000}]}""")
        assertTrue(p.saveLyrics(env, song.copy(id = "a2"), "[00:01.00] Light the way", synced = true))
        assertCall(next(), "GET", "music.example", "/Audio/a2/Lyrics")
        val up = next()
        assertCall(up, "POST", "music.example", "/Audio/a2/Lyrics")
        assertEquals("a2.lrc", up.query("fileName"))
        assertTrue(up.getHeader("Content-Type")!!.startsWith("text/plain"))
        assertEquals("[00:01.00] Light the way", up.bodyText())
        assertTrue(up.getHeader("Authorization")!!.contains("server-token"))

        // Lyrics there by now: kept, nothing sent.
        json("""{"Lyrics":[{"Text":"Already here"}]}""")
        assertFalse(p.saveLyrics(env, song.copy(id = "a1"), "Other words", synced = false))
        next()
        noMoreRequests()
    }

    @Test fun jellyfinPlainLyricsGoAsTxt() = runBlocking {
        connect("jellyfin")
        text("{}", 404, "application/json")
        json("{}")
        JellyfinProvider().saveLyrics(env, song.copy(id = "a3"), "Only words", synced = false)
        next()
        assertEquals("a3.txt", next().query("fileName"))
    }

    @Test fun jellyfinFillSkipsAFailureAndGoesOn() = runBlocking {
        connect("jellyfin")
        val a = LyricsSong(song.copy(id = "a1"), false)
        val b = LyricsSong(song.copy(id = "a2", title = "Glass Harbour"), false)
        text("{}", 404, "application/json"); text("forbidden", 403, "text/plain")
        text("{}", 404, "application/json"); json("{}")
        val saved = SyncedLyrics.save(env, JellyfinProvider(), listOf(
            SyncedLyrics.Proposal(a, SyncedLyrics.Kind.SYNCED, "[00:01.00] A"),
            SyncedLyrics.Proposal(b, SyncedLyrics.Kind.PLAIN, "B"),
            SyncedLyrics.Proposal(b.copy(track = song.copy(id = "a3")), SyncedLyrics.Kind.NONE),
        ))
        assertEquals(SyncedLyrics.Saved(saved = 1, synced = 0, plain = 1, kept = 0, failed = 1, notFound = 1), saved)
        assertTrue(env.logs.last().contains("1 lyrics saved (0 synced, 1 plain), 0 had lyrics already, 1 failed, 1 not found"))
    }

    // ------------------------------------------------------------------ Emby

    @Test fun embyReadsSubtitleStreamsAndHasNoUpload() = runBlocking {
        connect("emby")
        val p = EmbyProvider()
        assertEquals(LyricsSupport.SIDECAR, p.lyricsSupport)
        json("""{"Items":[{"Id":"e1","Name":"Paper Lanterns","Artists":["The Velvet Orchard"],"Path":"D:\\Music\\a.flac","MediaStreams":[{"Type":"Audio"},{"Type":"Subtitle","Codec":"lrc"}]},
            {"Id":"e2","Name":"Glass Harbour","Artists":["The Velvet Orchard"],"Path":"D:\\Music\\b.flac","MediaStreams":[{"Type":"Audio"}]}]}""")
        val songs = p.lyricsSongs(env)
        assertEquals(listOf(true, false), songs.map { it.hasLyrics })
        val r = next()
        assertEquals("/emby/Users/user-1/Items", r.pathOnly())
        assertEquals("Path,MediaStreams", r.query("Fields"))
        assertTrue(runCatching { p.saveLyrics(env, song, "x", true) }.isFailure)
        noMoreRequests()
    }

    // ------------------------------------------------------------------ Plex

    @Test fun plexReadsFilesThenLyricStreams() = runBlocking {
        connect("plex")
        json("""{"MediaContainer":{"Directory":[{"key":"3","type":"artist"},{"key":"1","type":"movie"}]}}""")
        json("""{"MediaContainer":{"Metadata":[
            {"ratingKey":"101","title":"Paper Lanterns","grandparentTitle":"The Velvet Orchard","Media":[{"Part":[{"file":"/data/music/a.flac"}]}]},
            {"ratingKey":"102","title":"Glass Harbour","grandparentTitle":"The Velvet Orchard","Media":[{"Part":[{"file":"/data/music/b.flac"}]}]}]}}""")
        json("""{"MediaContainer":{"Metadata":[
            {"ratingKey":"101","Media":[{"Part":[{"file":"/data/music/a.flac","Stream":[{"streamType":2},{"streamType":4,"codec":"lrc"}]}]}]},
            {"ratingKey":"102","Media":[{"Part":[{"file":"/data/music/b.flac","Stream":[{"streamType":2}]}]}]}]}}""")
        val songs = PlexProvider().lyricsSongs(env)
        assertEquals(listOf(true, false), songs.map { it.hasLyrics })
        assertEquals("/data/music/b.flac", songs[1].path)
        next()
        assertEquals("/library/sections/3/all", next().pathOnly())
        assertEquals("/library/metadata/101,102", next().pathOnly())
        noMoreRequests()
    }

    // ------------------------------------------------------------------ Lyrion, Mopidy

    @Test fun lyrionReadsTheLyricsTagAndOnlyReports() = runBlocking {
        connect("lyrion")
        val p = LyrionProvider()
        assertEquals(LyricsSupport.REPORT_ONLY, p.lyricsSupport)
        json("""{"result":{"count":2,"titles_loop":[{"id":1,"title":"Paper Lanterns","artist":"The Velvet Orchard","lyrics":"Light the way"},{"id":2,"title":"Glass Harbour","artist":"The Velvet Orchard"}]}}""")
        assertEquals(listOf(true, false), p.lyricsSongs(env).map { it.hasLyrics })
        val words = next().bodyText()
        assertTrue(words, words.contains("\"titles\"") && words.contains("\"tags:aldyuw\""))
        val r = runCatching { SyncedLyrics.save(env, p, listOf(SyncedLyrics.Proposal(LyricsSong(song, false), SyncedLyrics.Kind.PLAIN, "x"))) }
        assertTrue(r.isFailure)
    }

    @Test fun mopidyHasNoLyrics() {
        assertEquals(LyricsSupport.UNSUPPORTED, MopidyProvider().lyricsSupport)
        assertFalse(SyncedLyrics.reads(MopidyProvider()))
    }

    // ------------------------------------------------------------------ files next to the music

    private fun library(): File = Files.createTempDirectory("lyrics-lib").toFile().apply {
        File(this, "Velvet Orchard/Night Garden").mkdirs()
        File(this, "Velvet Orchard/Night Garden/01 Paper Lanterns.flac").writeBytes(ByteArray(4))
        File(this, "Velvet Orchard/Night Garden/02 Glass Harbour.flac").writeBytes(ByteArray(4))
        deleteOnExit()
    }

    @Test fun sidecarsMapTheServersPathAndOnlyCreateNewFiles() {
        val root = library()
        val s = SyncedLyrics.Sidecars("/music", root)
        val path = "/music/Velvet Orchard/Night Garden/01 Paper Lanterns.flac"
        assertEquals(SyncedLyrics.Sidecars.Outcome.WRITTEN, s.write(path, "[00:01.00] Light the way", synced = true))
        val lrc = File(root, "Velvet Orchard/Night Garden/01 Paper Lanterns.lrc")
        assertEquals("[00:01.00] Light the way\n", lrc.readText())
        // Never replaced, and a plain .txt is not added next to it either.
        assertEquals(SyncedLyrics.Sidecars.Outcome.HAS_LYRICS, s.write(path, "Other", synced = true))
        assertEquals(SyncedLyrics.Sidecars.Outcome.HAS_LYRICS, s.write(path, "Other", synced = false))
        assertEquals("[00:01.00] Light the way\n", lrc.readText())
        assertFalse(File(root, "Velvet Orchard/Night Garden/01 Paper Lanterns.txt").exists())
    }

    @Test fun sidecarsRefuseWhatIsOutsideOrMissing() {
        val root = library()
        val s = SyncedLyrics.Sidecars("/music", root)
        assertEquals(SyncedLyrics.Sidecars.Outcome.NO_FILE, s.write("/other/Velvet Orchard/Night Garden/01 Paper Lanterns.flac", "x", true))
        assertEquals(SyncedLyrics.Sidecars.Outcome.NO_FILE, s.write("/music/../etc/passwd", "x", true))
        assertEquals(SyncedLyrics.Sidecars.Outcome.NO_FILE, s.write("/music/Velvet Orchard/Night Garden/03 Missing.flac", "x", true))
        assertEquals(SyncedLyrics.Sidecars.Outcome.NO_FILE, s.write(null, "x", true))
        // A Windows server's path, and a plain one as .txt.
        val w = SyncedLyrics.Sidecars("D:\\Music\\", root)
        assertEquals(SyncedLyrics.Sidecars.Outcome.WRITTEN, w.write("D:\\Music\\Velvet Orchard\\Night Garden\\02 Glass Harbour.flac", "Words", synced = false))
        assertEquals("Words\n", File(root, "Velvet Orchard/Night Garden/02 Glass Harbour.txt").readText())
    }

    @Test fun sidecarFillGoesOnPastAMissingFile() = runBlocking {
        val root = library()
        val ok = LyricsSong(song, false, "/music/Velvet Orchard/Night Garden/01 Paper Lanterns.flac")
        val gone = LyricsSong(song.copy(id = "s9"), false, "/music/elsewhere.flac")
        val saved = SyncedLyrics.save(env, SubsonicProvider(), listOf(
            SyncedLyrics.Proposal(gone, SyncedLyrics.Kind.SYNCED, "[00:01.00] X"),
            SyncedLyrics.Proposal(ok, SyncedLyrics.Kind.SYNCED, "[00:01.00] Y"),
        ), SyncedLyrics.Sidecars("/music", root))
        assertEquals(1, saved.saved)
        assertEquals(1, saved.failed)
        assertTrue(File(root, "Velvet Orchard/Night Garden/01 Paper Lanterns.lrc").isFile)
        noMoreRequests()
    }

    @Test fun sidecarServersNeedTheFolderToFill() = runBlocking {
        val r = runCatching { SyncedLyrics.save(env, PlexProvider(), listOf(SyncedLyrics.Proposal(LyricsSong(song, false, "/a.flac"), SyncedLyrics.Kind.PLAIN, "x"))) }
        assertTrue(r.isFailure)
    }
}

package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.net.long
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.ZoneId
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The listening exports, from small files written by hand from each service's documented shape
 * (never a real person's): what is read of each, what is left out, the sums, the score, and the
 * listens as ListenBrainz takes them.
 */
class ListeningExportTest {
    private fun res(name: String) = javaClass.getResource("/lifetime/$name")!!.readBytes()

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): List<ListeningExport.Play> = ArrayList<ListeningExport.Play>().also { l -> ListeningExport.read(ByteArrayInputStream(bytes)) { l += it } }

    private fun stats(plays: List<ListeningExport.Play>, zone: String = "UTC") = ListeningExport.Builder(ZoneId.of(zone)).also { b -> plays.forEach(b::add) }.build()

    // A workbook as Deezer's: the listening history on its own sheet, texts as shared strings, the date as text.
    private val shared = listOf("Song Title", "Artist", "ISRC", "Album Title", "IP Address", "Listening Time", "Platform Name", "Platform Model", "Date",
        "Teardrop", "Massive Attack", "GBAAA9800322", "Mezzanine", "203.0.113.7", "web", "2024-05-06 22:10:00", "Angel")
    private fun sst() = ("""<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>").toByteArray()
    private fun sheet() = ("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
        """<row r="1">""" + (0..8).joinToString("") { """<c r="${'A' + it}1" t="s"><v>$it</v></c>""" } + "</row>" +
        """<row r="2"><c r="A2" t="s"><v>9</v></c><c r="B2" t="s"><v>10</v></c><c r="C2" t="s"><v>11</v></c><c r="D2" t="s"><v>12</v></c><c r="E2" t="s"><v>13</v></c><c r="F2"><v>329</v></c><c r="G2" t="s"><v>14</v></c><c r="I2" t="s"><v>15</v></c></row>""" +
        // No ISRC, an inline string, the date as a spreadsheet's day number (2024-05-07 00:00 UTC).
        """<row r="3"><c r="A3" t="s"><v>16</v></c><c r="B3" t="inlineStr"><is><t>Massive Attack</t></is></c><c r="F3"><v>379</v></c><c r="I3"><v>45419</v></c></row>""" +
        "</sheetData></worksheet>").toByteArray()
    private fun other() = """<?xml version="1.0"?><worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Email</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>someone@example.com</t></is></c></row></sheetData></worksheet>""".toByteArray()
    private fun workbook() = zip("xl/sharedStrings.xml" to sst(), "xl/worksheets/sheet1.xml" to other(), "xl/worksheets/sheet10.xml" to sheet())

    @Test fun `Spotify's extended history is read from its zip, songs only`() {
        val plays = read(zip("Spotify Extended Streaming History/ReadMeFirst_ExtendedStreamingHistory.pdf" to byteArrayOf(1, 2, 3),
            "Spotify Extended Streaming History/Streaming_History_Audio_2014-2015_0.json" to res("spotify-extended.json")))
        // The podcast episode is not a song; the 12 seconds of Creep are read, and not counted later.
        assertEquals(listOf("Karma Police", "Karma Police", "Creep", "Teardrop", "Holocene"), plays.map { it.title })
        val p = plays.first()
        assertEquals("Radiohead", p.artist); assertEquals("OK Computer", p.album); assertEquals(240_000, p.ms)
        assertEquals(java.time.Instant.parse("2014-03-01T20:15:00Z").toEpochMilli(), p.at)
        assertEquals("spotify:track:63OQupATfueTdZMWTxW03A", p.uri)
    }

    @Test fun `the short account history and a bare json file are read too`() {
        val plays = read(res("spotify-short.json"))
        assertEquals(2, plays.size)
        // endTime is the end, in UTC.
        assertEquals(java.time.Instant.parse("2026-01-05T21:00:00Z").toEpochMilli(), plays[0].at)
    }

    @Test fun `Takeout's watch history gives the YouTube Music entries, whatever the account's language`() {
        val plays = read(zip("Takeout/YouTube and YouTube Music/history/watch-history.json" to res("watch-history.json"), "Takeout/archive_browser.html" to "<html>".toByteArray()))
        assertEquals(listOf("Paranoid Android", "Paranoid Android"), plays.map { it.title })
        assertTrue(plays.all { it.artist == "Radiohead" && it.ms == 0L && it.source == ListeningExport.Source.YOUTUBE })
        val s = stats(plays)
        assertFalse(s.timed)
        assertEquals(2, s.topArtists.single().plays)
    }

    @Test fun `Deezer's workbook is read by its headers, alone or inside a zip`() {
        for (bytes in listOf(workbook(), zip("deezer-data_123.xlsx" to workbook()))) {
            val plays = read(bytes)
            assertEquals(listOf("Teardrop", "Angel"), plays.map { it.title })
            assertEquals(329_000, plays[0].ms)
            assertEquals("GBAAA9800322", plays[0].isrc)
            assertEquals("Mezzanine", plays[0].album)
            assertEquals(java.time.Instant.parse("2024-05-06T22:10:00Z").toEpochMilli(), plays[0].at)
            assertEquals(java.time.Instant.parse("2024-05-07T00:00:00Z").toEpochMilli(), plays[1].at)
            assertNull(plays[1].isrc)
        }
    }

    @Test fun `a file with no listening history is said`() {
        for (bytes in listOf(zip("notes.txt" to "hi".toByteArray(), "data.json" to """{"a":1}""".toByteArray()), """[{"name":"x"}]""".toByteArray(), "hello".toByteArray())) {
            assertTrue(runCatching { read(bytes) }.exceptionOrNull() is ListeningExport.NotAnExport)
        }
    }

    @Test fun `the sums - hours, artists, songs, the clock, the years and each year's song`() {
        val s = stats(read(res("spotify-extended.json")), "Europe/Rome")
        assertTrue(s.timed)
        assertEquals(4, s.plays) // Creep's 12 seconds are a skip
        assertEquals(990_000, s.ms)
        assertEquals(3, s.artists); assertEquals(3, s.tracks)
        assertEquals(listOf("Radiohead", "Massive Attack", "Bon Iver"), s.topArtists.map { it.name })
        assertEquals(490_000, s.topArtists[0].ms)
        assertEquals("Karma Police", s.topTracks[0].title); assertEquals(2, s.topTracks[0].plays)
        // 20:15 and 20:19 UTC on a winter day are 21 in Rome; 23:30 UTC in June is 01 the day after.
        assertEquals(490_000, s.clock[21]); assertEquals(300_000, s.clock[1]); assertEquals(200_000, s.clock[12])
        assertEquals(listOf(2014, 2015), s.years.map { it.year })
        assertEquals("Karma Police", s.years[0].song?.title); assertEquals("Radiohead", s.years[0].artist)
        assertEquals("Teardrop", s.years[1].song?.title); assertEquals("Massive Attack", s.years[1].artist)
        assertEquals(java.time.Instant.parse("2014-03-01T20:15:00Z").toEpochMilli(), s.from)
        assertEquals(listOf("Spotify"), s.sources)
        // The playlist: the most played first, with ids a sync can tell apart.
        val tracks = ListeningExport.topTracks(s, 2)
        assertEquals(listOf("Karma Police", "Teardrop"), tracks.map { it.title })
        assertTrue(tracks.all { it.id.isNotEmpty() })
    }

    @Test fun `the statistics are kept as sums and forgotten on request`() {
        val env = com.xlollx.songport.contract.ContractEnv()
        assertNull(ListeningExport.kept(env))
        val s = stats(read(res("spotify-extended.json")))
        ListeningExport.keep(env, s.copy(obscurity = 41))
        assertEquals(41, ListeningExport.kept(env)?.obscurity)
        assertEquals(s.topTracks, ListeningExport.kept(env)?.topTracks)
        ListeningExport.keep(env, null)
        assertNull(ListeningExport.kept(env))
    }

    @Test fun `how niche - famous artists near 0, unknown ones near 100, by their share`() {
        assertNull(ListeningExport.obscurityScore(listOf(10L to 5L, 10L to 5L)))
        val famous = ListeningExport.obscurityScore(List(3) { 100L to 300_000L })!!
        val niche = ListeningExport.obscurityScore(List(3) { 100L to 20L })!!
        assertEquals(0, famous)
        assertTrue("niche $niche", niche in 70..80)
        // Mostly the famous one: closer to it.
        val mixed = ListeningExport.obscurityScore(listOf(900L to 300_000L, 50L to 20L, 50L to 20L))!!
        assertTrue("mixed $mixed", mixed in 1..15)
        // The listeners come from the answer the playlist generator already reads.
        val j = parseJson(javaClass.getResource("/artistmix/top-recordings-radiohead.json")!!.readText())
        assertEquals(j.arr.maxOf { it["total_user_count"].long ?: 0 }, ArtistMix.parseListeners(j, j.arr.first()["artist_mbids"].arr.first().str!!))
    }

    @Test fun `the listens go to ListenBrainz as an import, in batches, with the token`() {
        val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
        val auth: MutableList<String?> = Collections.synchronizedList(ArrayList())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/1/submit-listens") { ex ->
                auth += ex.requestHeaders.getFirst("Authorization")
                bodies += ex.requestBody.readBytes().decodeToString()
                val b = """{"status":"ok"}""".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        ListeningExport.listenBrainz = "http://127.0.0.1:${server.address.port}"
        try {
            val one = read(res("spotify-extended.json"))
            val plays = List(300) { one }.flatten() // 1500 listens, 300 of them skips
            val progress = ArrayList<Pair<Int, Int>>()
            val sent = runBlocking { ListeningExport.submit("TOKEN", plays) { a, b -> progress += a to b } }
            assertEquals(1200, sent)
            assertEquals(3, bodies.size)
            assertTrue(auth.all { it == "Token TOKEN" })
            assertEquals(1200 to 1200, progress.last())
            val first = parseJson(bodies[0])
            assertEquals("import", first["listen_type"].str)
            assertEquals(ListeningExport.BATCH, first["payload"].arr.size)
            val l = first["payload"].arr.first()
            assertEquals(java.time.Instant.parse("2014-03-01T20:15:00Z").epochSecond, l["listened_at"].long)
            assertEquals("Radiohead", l["track_metadata"]["artist_name"].str)
            assertEquals("Karma Police", l["track_metadata"]["track_name"].str)
            assertEquals("OK Computer", l["track_metadata"]["release_name"].str)
            assertEquals("https://open.spotify.com/track/63OQupATfueTdZMWTxW03A", l["track_metadata"]["additional_info"]["spotify_id"].str)
            assertEquals("spotify.com", l["track_metadata"]["additional_info"]["music_service"].str)
        } finally {
            server.stop(0)
            ListeningExport.listenBrainz = "https://api.listenbrainz.org"
        }
    }
}

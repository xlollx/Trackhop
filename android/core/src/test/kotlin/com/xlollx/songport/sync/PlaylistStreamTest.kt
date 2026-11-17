package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Reader

/** #67: every playlist format is read a piece at a time, whatever the file's size. */
class PlaylistStreamTest {

    /** A generated file far bigger than the read buffer, never held as a whole: chars come from a Reader. */
    private class Big(private val head: String, private val count: Int, private val tail: String, private val item: (Int) -> String) : Reader() {
        private var i = 0
        private var cur = head
        private var at = 0
        var chars = 0L
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            while (at == cur.length) {
                cur = when { i < count -> item(i); i == count -> tail; else -> return -1 }
                i++; at = 0
            }
            val n = minOf(len, cur.length - at)
            cur.toCharArray(cbuf, off, at, at + n)
            at += n; chars += n
            return n
        }
        override fun close() = Unit
    }

    private val pad = "x".repeat(60)

    /** Reads [big] as [name] and checks it was over 40 MB, read to its end, and gave [count] tracks from "Song 0" to the last. */
    private fun readsAll(name: String, big: Big, count: Int) {
        var reported = 0L
        val t = PlaylistFiles.readStream(name, big, progress = { reported = it })
        assertTrue("generated more than 40 MB: ${big.chars}", big.chars > 40_000_000)
        assertTrue(reported > 0)
        assertEquals(count, t.size)
        assertEquals("Song 0", t.first().title)
        assertEquals("Song ${count - 1}", t.last().title)
        assertEquals(listOf("Band 7"), t[7].artists)
    }

    @Test fun aBigCsv() = readsAll("big.csv", Big("Shazam Library\nIndex,Title,Artist,Note\n", 500_000, "") {
        // A quoted cell with the separator, a quote and a line break in it.
        "$it,Song $it,Band ${it % 300},\"$pad, \"\"live\"\"\nsecond line\"\r\n"
    }, 500_000)

    @Test fun aBigM3u() = readsAll("big.m3u8", Big("#EXTM3U\n", 400_000, "") {
        "#EXTINF:200,Band ${it % 300} - Song $it\n/storage/emulated/0/Music/$pad/$it.mp3\n"
    }, 400_000)

    @Test fun aBigPls() = readsAll("big.pls", Big("[playlist]\n", 400_000, "NumberOfEntries=400000\nVersion=2\n") {
        "File${it + 1}=/music/$pad/$it.mp3\nTitle${it + 1}=Band ${it % 300} - Song $it\nLength${it + 1}=200\n"
    }, 400_000)

    @Test fun aBigTextList() = readsAll("big.txt", Big("", 500_000, "") { "${it + 1}. Band ${it % 300} - Song $it\n# $pad $pad\n" }, 500_000)

    @Test fun aBigXspf() = readsAll(
        "big.xspf",
        Big("<?xml version=\"1.0\"?>\n<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><title>Mine</title><trackList>\n", 300_000, "</trackList></playlist>\n") {
            "<track><location>file:///music/$pad/$it.mp3</location><title>Song $it</title><creator>Band ${it % 300}</creator><duration>200000</duration><identifier>isrc:USAAA${1000000 + it}</identifier></track>\n"
        },
        300_000,
    )

    @Test fun aBigJson() = readsAll(
        "big.json",
        Big("{\"name\":\"Mine\",\"playlists\":[{\"name\":\"All\",\"tracks\":[", 300_000, "]}]}") {
            (if (it == 0) "" else ",") + "{\"title\":\"Song $it\",\"artists\":[{\"name\":\"Band ${it % 300}\"}],\"album\":{\"name\":\"$pad\"},\"note\":\"a } and a ] and a \\\" in a string\"}\n"
        },
        300_000,
    )

    @Test fun aBigOpml() {
        val big = Big("<?xml version=\"1.0\"?><opml version=\"2.0\"><body>\n", 300_000, "</body></opml>") {
            "<outline type=\"rss\" text=\"Show $it\" description=\"$pad > $pad\" xmlUrl=\"https://example.com/feed/$it.xml\"/>\n"
        }
        val t = PlaylistFiles.readStream("big.opml", big)
        assertTrue(big.chars > 40_000_000)
        assertEquals(300_000, t.size)
        assertEquals("https://example.com/feed/299999.xml", t.last().feedUrl)
    }

    // ---------------------------------------------------------------- cut short, stopped

    private fun refused(name: String, text: String) {
        try { PlaylistFiles.parse(name, text); fail("$name was read: $text") } catch (e: ItunesLibrary.Incomplete) { /* refused */ }
    }

    @Test fun anXmlOrJsonFileCutShortIsRefusedNotHalfRead() {
        val xspf = PlaylistFiles.toXspf("Mine", (1..50).map { com.xlollx.songport.model.Track("$it", "Song $it", listOf("Band")) })
        assertEquals(50, PlaylistFiles.parse("a.xspf", xspf).size)
        for (cut in listOf(xspf.length / 3, xspf.length / 2, xspf.length - 14, xspf.length - 3)) refused("a.xspf", xspf.substring(0, cut))
        refused("a.xspf", "")

        val jspf = PlaylistFiles.toJspf("Mine", (1..50).map { com.xlollx.songport.model.Track("$it", "Song $it", listOf("Band")) })
        assertEquals(50, PlaylistFiles.parse("a.jspf", jspf).size)
        for (cut in listOf(jspf.length / 3, jspf.length / 2, jspf.length - 4)) refused("a.jspf", jspf.substring(0, cut))
        // Cut inside a string, and between two objects of the list.
        refused("a.json", "[{\"title\":\"One\"},{\"title\":\"Tw")
        refused("a.json", "[{\"title\":\"One\"},")

        val opml = "<?xml version=\"1.0\"?><opml><body><outline type=\"rss\" text=\"A\" xmlUrl=\"https://a.example/f\"/><outline type=\"rss\" text=\"B\" xmlUrl=\"https://b.example/f\"/></body></opml>"
        assertEquals(2, PlaylistFiles.parse("a.opml", opml).size)
        refused("a.opml", opml.substring(0, opml.length - 30))
        refused("a.opml", opml.substring(0, opml.length - 7))
    }

    @Test fun anAnswerCutOffStillGivesItsWholeObjects() {
        // Not a file: an AI's answer that stopped at its token limit.
        assertEquals(listOf("One", "Two"), PlaylistFiles.parseJson("[{\"title\":\"One\"},{\"title\":\"Two\"},{\"title\":\"Thr").map { it.title })
    }

    @Test fun readingStopsWhenCancelled() {
        val files = listOf(
            "a.csv" to Big("title,artist\n", 100_000, "") { "Song $it,Band\n" },
            "a.m3u" to Big("#EXTM3U\n", 100_000, "") { "Band - Song $it $pad\n" },
            "a.json" to Big("[", 100_000, "]") { "{\"title\":\"Song $it\"}," },
            "a.xspf" to Big("<playlist>", 100_000, "</playlist>") { "<track><title>Song $it</title></track>" },
        )
        for ((name, big) in files) {
            try { PlaylistFiles.readStream(name, big, cancelled = { true }); fail("$name was read to its end") }
            catch (e: java.util.concurrent.CancellationException) { /* stopped: nothing is given */ }
        }
    }

    // ---------------------------------------------------------------- the same as a file held whole

    @Test fun jsonKeepsTheListWithTheMostObjects() {
        // Two lists of tracks: the longer one, wherever it is; the artists inside a track never win over it.
        val json = """{"recent":[{"title":"Old"}],"tracks":[
            {"title":"A","artists":[{"name":"X"},{"name":"Y"},{"name":"Z"}]},{"title":"B"},{"title":"C"},{"name":"D"}],"n":[1,2,3,4,5,6]}"""
        assertEquals(listOf("A", "B", "C", "D"), PlaylistFiles.parse("x.json", json).map { it.title })
        assertEquals(listOf("X", "Y", "Z"), PlaylistFiles.parse("x.json", json)[0].artists)
        // One object per line, no list around them.
        assertEquals(listOf("A", "B"), PlaylistFiles.parse("x.json", "{\"title\":\"A\"}\n{\"title\":\"B\",\"artist\":\"X\"}\n").map { it.title })
        // A trailing comma is not a reason to refuse a file that is whole.
        assertEquals(listOf("A", "B"), PlaylistFiles.parse("x.json", "[{\"title\":\"A\"},{\"title\":\"B\"},]").map { it.title })
        assertEquals(emptyList<String>(), PlaylistFiles.parse("x.json", "{\"title\":\"Not a list\"}").map { it.title })
    }

    @Test fun anObjectTooBigToBeATrackIsPassedOverAndItsListsStillRead() {
        // A playlist object of several MB: not a track itself, its tracks are.
        val big = Big("[{\"name\":\"All\",\"tracks\":[", 60_000, "]},{\"name\":\"Empty\",\"tracks\":[]}]") {
            (if (it == 0) "" else ",") + "{\"title\":\"Song $it\",\"note\":\"$pad\"}"
        }
        val t = PlaylistFiles.readStream("x.json", big)
        assertTrue(big.chars > 4_000_000)
        assertEquals(60_000, t.size)
        assertEquals("Song 59999", t.last().title)
    }

    @Test fun plsEntriesFarApartStillComeInOrder() {
        // Every File first, then every Title: more entries than are held open at once.
        val n = 6_000
        val text = "[playlist]\n" + (n downTo 1).joinToString("") { "File$it=/m/$it.mp3\n" } + (1..n).joinToString("") { "Title$it=Band - Song $it\n" }
        val t = PlaylistFiles.parse("x.pls", text)
        assertEquals(n, t.size)
        assertEquals((1..n).map { "Song $it" }, t.map { it.title })
    }

    @Test fun csvRowsAcrossTheBuffer() {
        // Quotes, doubled quotes and line breaks that fall on the edge of the read buffer.
        val rows = (0 until 3_000).map { "\"Song \"\"$it\"\"\nline\",Band $it,${"y".repeat(it % 97)}" }
        val t = CsvCodec.parse("title,artist,album\r\n" + rows.joinToString("\r\n"))
        assertEquals(3_000, t.size)
        assertEquals("Song \"2999\"\nline", t.last().title)
        assertEquals(CsvCodec.parseRows("a;b\n1;\"2;3\"\n"), listOf(listOf("a", "b"), listOf("1", "2;3")))
    }

    @Test fun bytesAreDecodedAsTheyCome() {
        fun read(bytes: ByteArray, chunk: Int = 7): String {
            // A stream that gives a few bytes at a time: characters split across reads.
            val slow = object : java.io.InputStream() {
                var at = 0
                override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= bytes.size) return -1
                    val n = minOf(len, chunk, bytes.size - at)
                    System.arraycopy(bytes, at, b, off, n); at += n
                    return n
                }
            }
            return PlaylistFiles.reader(slow).readText()
        }
        val s = "Beyoncé - Café Noir 😀 — Måneskin\n".repeat(5_000)
        assertEquals(s, read(s.toByteArray()))
        assertEquals(s, read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray()))
        assertEquals(s, read(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
        assertEquals(s, read(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)))
        // A plain .m3u from a Windows player: not UTF-8, however far its first accent is.
        val w = "Metallica - Battery\n".repeat(10_000) + "Beyoncé - Café Noir\n"
        assertEquals(w, read(w.toByteArray(charset("windows-1252")), chunk = 4096))
        assertEquals(PlaylistFiles.decode(w.toByteArray(charset("windows-1252"))), read(w.toByteArray(charset("windows-1252"))))
        assertEquals("", read(ByteArray(0)))
        // One character at a time, as some readers ask.
        val r = PlaylistFiles.reader("a😀é".toByteArray().inputStream())
        val one = CharArray(1)
        val got = StringBuilder()
        while (r.read(one, 0, 1) == 1) got.append(one[0])
        assertEquals("a😀é", got.toString())
    }
}

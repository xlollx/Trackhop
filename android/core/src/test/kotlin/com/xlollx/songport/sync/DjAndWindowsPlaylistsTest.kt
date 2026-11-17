package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Reader

/**
 * rekordbox XML (AlphaTheta's "XML file format for playlists sharing") and Windows Media Player's
 * WPL with Zune's ZPL (Microsoft's Windows Media Playlist reference): told by their content, read a
 * piece at a time and refused when cut short; rekordbox XML is only read (a list of songs has no
 * file locations for rekordbox to load), WPL and ZPL are written too. The files are written by hand from
 * the two specifications, as the programs lay them out; none comes from anyone's library.
 */
class DjAndWindowsPlaylistsTest {
    private fun res(name: String) = javaClass.getResource("/playlists/$name")!!.readText()

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

    private fun refused(name: String, text: String) {
        try { PlaylistFiles.parse(name, text); fail("$name was read: ${text.takeLast(60)}") } catch (e: ItunesLibrary.Incomplete) { /* refused */ }
    }

    // ---------------------------------------------------------------- rekordbox XML

    @Test fun rekordboxIsToldByItsContentNotItsName() {
        val xml = res("rekordbox-collection.xml")
        assertEquals(PlaylistFiles.Format.REKORDBOX_XML, PlaylistFiles.detect("collection.xml", xml))
        assertEquals(PlaylistFiles.Format.REKORDBOX_XML, PlaylistFiles.detect("export", xml))
        assertEquals(PlaylistFiles.Format.REKORDBOX_XML, PlaylistFiles.detect("list.txt", "\uFEFF" + xml))
        assertTrue(ItunesLibrary.looksLike(xml.take(4096)))
        // The other XML files keep their readers.
        assertEquals(PlaylistFiles.Format.ITUNES_XML, PlaylistFiles.detect("Library.xml", "<?xml version=\"1.0\"?>\n<plist version=\"1.0\"><dict/></plist>"))
        assertEquals(PlaylistFiles.Format.XSPF, PlaylistFiles.detect("a.xml", "<playlist xmlns=\"http://xspf.org/ns/0/\"/>"))
        assertFalse(RekordboxXml.looksLike("DJ_PLAYLISTS,title,artist\n"))
    }

    @Test fun rekordboxCollectionGivesItsSongsAndItsPlaylists() {
        val lib = RekordboxXml.read(res("rekordbox-collection.xml"))
        // The track without a name is not a song; the others are, beat grids and cue points passed over.
        assertEquals(5, lib.trackCount)
        assertEquals(listOf("Gigs", "Warm up", "Peak <time>", "By file", "Empty"), lib.playlists.map { it.name })
        assertEquals(listOf(ItunesLibrary.Kind.FOLDER, ItunesLibrary.Kind.USER, ItunesLibrary.Kind.USER, ItunesLibrary.Kind.USER, ItunesLibrary.Kind.USER), lib.playlists.map { it.kind })
        assertEquals(listOf(null, "Gigs", "Gigs", null, null), lib.playlists.map { it.folder })
        assertEquals(listOf("Warm up", "Peak <time>", "By file", "Empty"), lib.choices.map { it.name })
        assertTrue(lib.worthPicking)

        // In the playlist's order, a song that is there twice kept twice.
        val warm = lib.tracksOf(lib.choices[0])
        assertEquals(listOf("Can't Get You Out of My Head", "Señorita", "Can't Get You Out of My Head"), warm.map { it.title })
        assertEquals(listOf("Kylie Minogue"), warm[0].artists)
        assertEquals("Fever", warm[0].album)
        // TotalTime is in seconds, whole or with decimals.
        assertEquals(230_400L, warm[0].durationMs)
        assertEquals(295_000L, warm[1].durationMs)
        // An entry whose track is not in the collection is skipped.
        assertEquals(listOf("Blue Monday", "Around the World"), lib.tracksOf(lib.choices[1]).map { it.title })
        // KeyType 1: the entries name their tracks by Location; the nameless file is not a song.
        val byFile = lib.tracksOf(lib.choices[2])
        assertEquals(listOf("Rhythm & Sound (Dub)", "Blue Monday"), byFile.map { it.title })
        assertEquals(listOf("Basic Channel", "Tikiman"), byFile[0].artists)
        assertEquals("Rhythm & Sound w/ the Artists", byFile[0].album)
        assertEquals(0, lib.tracksOf(lib.choices[3]).size)

        // As one list (several playlists): every song of the collection, once, in the file's order.
        val all = PlaylistFiles.parse("collection.xml", res("rekordbox-collection.xml"))
        assertEquals(listOf("Around the World", "Rhythm & Sound (Dub)", "Can't Get You Out of My Head", "Blue Monday", "Señorita"), all.map { it.title })
        assertTrue(all.all { it.id.isNotEmpty() })
    }

    @Test fun rekordboxWithOnePlaylistIsThatPlaylist() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<DJ_PLAYLISTS Version="1.0.0">
  <PRODUCT Name="rekordbox" Version="7.0.4" Company="AlphaTheta"/>
  <COLLECTION Entries="3">
    <TRACK TrackID="1" Name="One" Artist="A" Album="" TotalTime="100" Location="file://localhost/M/1.mp3"/>
    <TRACK TrackID="2" Name="Two" Artist="B" Album="" TotalTime="200" Location="file://localhost/M/2.mp3"/>
    <TRACK TrackID="3" Name="Three" Artist="C" Album="" TotalTime="300" Location="file://localhost/M/3.mp3"/>
  </COLLECTION>
  <PLAYLISTS>
    <NODE Type="0" Name="ROOT" Count="1">
      <NODE Name="Set" Type="1" KeyType="0" Entries="2"><TRACK Key="3"/><TRACK Key="1"/></NODE>
    </NODE>
  </PLAYLISTS>
</DJ_PLAYLISTS>"""
        val lib = RekordboxXml.read(xml)
        assertFalse(lib.worthPicking)
        assertEquals(listOf("Three", "One"), PlaylistFiles.parse("set.xml", xml).map { it.title })
        // No playlist at all: the collection.
        val none = xml.substringBefore("<PLAYLISTS>") + "<PLAYLISTS><NODE Type=\"0\" Name=\"ROOT\" Count=\"0\"/></PLAYLISTS></DJ_PLAYLISTS>"
        assertEquals(listOf("One", "Two", "Three"), PlaylistFiles.parse("set.xml", none).map { it.title })
    }

    private val sample = listOf(
        Track("1", "Around the World", listOf("Daft Punk"), album = "Homework", durationMs = 429_000, year = 1997),
        Track("2", "Rhythm & Sound <Dub> \"x\" 'y'", listOf("Basic Channel", "Tikiman"), album = "R&S", durationMs = 388_500),
        Track("3", "No artist\u0001 here"),
        Track("1", "Around the World", listOf("Daft Punk"), album = "Homework", durationMs = 429_000, year = 1997),
    )

    @Test fun aRekordboxFileCutShortIsRefused() {
        val xml = res("rekordbox-collection.xml")
        for (cut in listOf(xml.length / 4, xml.length / 2, xml.indexOf("</COLLECTION>") + 5, xml.indexOf("<NODE Name=\"By file\"") + 30, xml.length - 20, xml.length - 3)) {
            refused("collection.xml", xml.substring(0, cut))
            try { ItunesLibrary.read(xml.substring(0, cut)); fail("read at $cut") } catch (e: ItunesLibrary.Incomplete) { assertFalse(e.apple) }
        }
        // An Apple library cut short still says it is Apple's.
        try { ItunesLibrary.read("<plist version=\"1.0\"><dict><key>Tracks</key><dict>"); fail() } catch (e: ItunesLibrary.Incomplete) { assertTrue(e.apple) }
    }

    @Test fun aBigRekordboxCollectionIsReadAPieceAtATime() {
        val n = 150_000
        val pad = "x".repeat(60)
        val big = Big("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<DJ_PLAYLISTS Version=\"1.0.0\">\n<PRODUCT Name=\"rekordbox\" Version=\"6.8.5\" Company=\"AlphaTheta\"/>\n<COLLECTION Entries=\"$n\">\n", n + 1, "</NODE></NODE></PLAYLISTS></DJ_PLAYLISTS>\n") {
            if (it < n) "<TRACK TrackID=\"${it + 1}\" Name=\"Song $it\" Artist=\"Band ${it % 300}\" Album=\"$pad\" Kind=\"MP3 File\" TotalTime=\"200\" Location=\"file://localhost/C:/Music/$pad/$it.mp3\" Comments=\"$pad $pad\">" +
                "<TEMPO Inizio=\"0.025\" Bpm=\"128.00\" Metro=\"4/4\" Battito=\"1\"/><POSITION_MARK Name=\"$pad\" Type=\"0\" Start=\"12.5\" Num=\"0\"/></TRACK>\n"
            else "</COLLECTION><PLAYLISTS><NODE Type=\"0\" Name=\"ROOT\" Count=\"1\"><NODE Name=\"All\" Type=\"1\" KeyType=\"0\" Entries=\"$n\">" +
                (n downTo 1).joinToString("") { k -> "<TRACK Key=\"$k\"/>" }
        }
        var reported = 0L
        val t = PlaylistFiles.readStream("collection.xml", big, progress = { reported = it })
        assertTrue("generated more than 40 MB: ${big.chars}", big.chars > 40_000_000)
        assertTrue(reported > 0)
        assertEquals(n, t.size)
        // The one playlist, in its own order (the collection's, backwards).
        assertEquals("Song ${n - 1}", t.first().title)
        assertEquals("Song 0", t.last().title)
        assertEquals(listOf("Band 7"), t[n - 8].artists)
        try {
            PlaylistFiles.readStream("c.xml", Big("<DJ_PLAYLISTS><COLLECTION>", 100_000, "</COLLECTION></DJ_PLAYLISTS>") { "<TRACK TrackID=\"$it\" Name=\"Song $it $pad\"/>" }, cancelled = { true })
            fail("read to its end")
        } catch (e: java.util.concurrent.CancellationException) { /* stopped: nothing is given */ }
    }

    // ---------------------------------------------------------------- WPL / ZPL

    @Test fun wplAndZplAreToldByTheirContent() {
        assertEquals(PlaylistFiles.Format.WPL, PlaylistFiles.detect("Sunday.wpl", res("wmp.wpl")))
        assertEquals(PlaylistFiles.Format.WPL, PlaylistFiles.detect("download", res("wmp.wpl")))
        assertEquals(PlaylistFiles.Format.WPL, PlaylistFiles.detect("Road trip.zpl", res("zune.zpl")))
        assertEquals(PlaylistFiles.Format.WPL, PlaylistFiles.detect("x.xml", res("zune.zpl")))
        assertEquals(PlaylistFiles.Format.WPL, PlaylistFiles.detect("x.wpl", "<smil><body><seq/></body></smil>"))
        // Zoom Player's .zpl is a text list, not Zune's XML.
        val zoom = "AC=C:\\Music\\Daft Punk - Around the World.mp3\nNM=Daft Punk - Around the World\nBR!\n"
        assertFalse(PlaylistFiles.detect("list.zpl", zoom) == PlaylistFiles.Format.WPL)
        // XSPF and Apple's XML are not taken for it.
        assertEquals(PlaylistFiles.Format.XSPF, PlaylistFiles.detect("a.xspf", "<playlist xmlns=\"http://xspf.org/ns/0/\"/>"))
        assertEquals(PlaylistFiles.Format.ITUNES_XML, PlaylistFiles.detect("a.xml", "<plist version=\"1.0\"/>"))
    }

    @Test fun windowsMediaPlayerListsAreReadFromTheirPaths() {
        val t = PlaylistFiles.parse("Sunday.wpl", res("wmp.wpl"))
        assertEquals(listOf("Around the World", "Blue Monday", "Can't Get You Out of My Head", "Rhythm & Sound"), t.map { it.title })
        assertEquals(listOf(listOf("Daft Punk"), listOf("New Order"), listOf("Kylie Minogue")), t.take(3).map { it.artists })
        assertEquals(listOf("Homework", "Substance"), t.take(2).map { it.album })
        assertTrue(t.all { it.id.isNotEmpty() })
        // An auto playlist: its rules are passed over, the files it lists are read.
        assertEquals(listOf("Can't Get You Out of My Head"), PlaylistFiles.parse("Fresh.wpl", res("wmp-auto.wpl")).map { it.title })
    }

    @Test fun zuneListsCarryTitleArtistAlbumAndLength() {
        val t = PlaylistFiles.parse("Road trip.zpl", res("zune.zpl"))
        assertEquals(listOf("Around the World", "King in My Empire", "Blue Monday"), t.map { it.title })
        assertEquals(listOf("Cornell Campbell", "Paul St. Hilaire"), t[1].artists)
        assertEquals("Rhythm & Sound w/ the Artists", t[1].album)
        assertEquals(listOf(429_000L, 290_000L, 448_000L), t.map { it.durationMs })
        // No track artist: the album's.
        assertEquals(listOf("New Order"), t[2].artists)
    }

    @Test fun wplAndZplAreWrittenAndReadBack() {
        val wpl = PlaylistFiles.toWpl("Sunday & <coffee>", sample)
        assertTrue(wpl.startsWith("<?wpl version=\"1.0\"?>\r\n<smil>"))
        assertTrue(wpl.contains("<title>Sunday &amp; &lt;coffee&gt;</title>"))
        assertTrue(wpl.contains("<meta name=\"ItemCount\" content=\"4\"/>"))
        assertTrue(wpl.contains("<media src=\"Daft Punk - Around the World\" trackTitle=\"Around the World\" trackArtist=\"Daft Punk\" albumTitle=\"Homework\" duration=\"429000\"/>"))
        assertFalse(wpl.contains("\u0001"))
        assertEquals(PlaylistFiles.Format.WPL, PlaylistFiles.detect("x", wpl))
        val back = PlaylistFiles.parse("x.wpl", wpl)
        assertEquals(listOf("Around the World", "Rhythm & Sound <Dub> \"x\" 'y'", "No artist here", "Around the World"), back.map { it.title })
        assertEquals(listOf("Basic Channel", "Tikiman"), back[1].artists)
        assertEquals(388_500L, back[1].durationMs)
        assertEquals(emptyList<String>(), back[2].artists)

        val zpl = PlaylistFiles.toWpl("Sunday", sample, zune = true)
        assertTrue(zpl.startsWith("<?zpl version=\"2.0\"?>\r\n<smil>"))
        assertEquals(wpl.substringAfter("<body>"), zpl.substringAfter("<body>"))
        assertEquals(back.map { it.title }, PlaylistFiles.parse("x.zpl", zpl).map { it.title })
        assertEquals(wpl, LocalFilesProvider.Export.WPL.encode("Sunday & <coffee>", sample))
        assertEquals(zpl, LocalFilesProvider.Export.ZPL.encode("Sunday", sample))
        assertEquals(listOf("wpl", "zpl"), listOf(LocalFilesProvider.Export.WPL.extension, LocalFilesProvider.Export.ZPL.extension))
        assertEquals(0, PlaylistFiles.parse("e.wpl", PlaylistFiles.toWpl("", emptyList())).size)
    }

    @Test fun aWplFileCutShortIsRefused() {
        for (name in listOf("wmp.wpl", "zune.zpl")) {
            val xml = res(name)
            for (cut in listOf(xml.length / 3, xml.length / 2, xml.lastIndexOf("</seq>") + 3, xml.trimEnd().length - 4)) refused(name, xml.substring(0, cut))
        }
        refused("a.wpl", "")
        assertNull(runCatching { PlaylistFiles.parse("wmp.wpl", res("wmp.wpl")) }.exceptionOrNull())
    }

    @Test fun aBigWplIsReadAPieceAtATime() {
        val pad = "x".repeat(60)
        val big = Big("<?wpl version=\"1.0\"?>\r\n<smil><head><title>All</title></head><body><seq>\r\n", 300_000, "</seq></body></smil>\r\n") {
            "<media src=\"C:\\Music\\$pad\\$it.mp3\" albumTitle=\"$pad\" trackTitle=\"Song $it\" trackArtist=\"Band ${it % 300}\" duration=\"200000\" cid=\"{$pad}\"/>\r\n"
        }
        var reported = 0L
        val t = PlaylistFiles.readStream("big.wpl", big, progress = { reported = it })
        assertTrue("generated more than 40 MB: ${big.chars}", big.chars > 40_000_000)
        assertTrue(reported > 0)
        assertEquals(300_000, t.size)
        assertEquals("Song 0", t.first().title)
        assertEquals("Song 299999", t.last().title)
        assertEquals(listOf("Band 7"), t[7].artists)
    }
}

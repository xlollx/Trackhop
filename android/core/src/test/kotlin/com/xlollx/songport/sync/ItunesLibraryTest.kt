package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader

class ItunesLibraryTest {

    private fun track(id: Int, name: String, artist: String, ms: Long = 200_000) = """
      <key>$id</key>
      <dict>
        <key>Track ID</key><integer>$id</integer>
        <key>Name</key><string>$name</string>
        <key>Artist</key><string>$artist</string>
        <key>Total Time</key><integer>$ms</integer>
        <key>Compilation</key><true/>
      </dict>"""

    private fun playlist(name: String, ids: List<Int>, extra: String = "") = """
      <dict>
        <key>Name</key><string>$name</string>
        <key>Playlist Persistent ID</key><string>P-$name</string>
        $extra
        <key>Playlist Items</key>
        <array>${ids.joinToString("") { "<dict><key>Track ID</key><integer>$it</integer></dict>" }}</array>
      </dict>"""

    private val library = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Major Version</key><integer>1</integer>
  <!-- a comment with <dict> inside -->
  <key>Tracks</key>
  <dict>${track(1, "Volare", "Domenico Modugno")}${track(2, "Rock &amp; Roll", "Led Zeppelin")}${track(3, "Caf&#233;", "Sigur R&#xF3;s")}</dict>
  <key>Playlists</key>
  <array>
    ${playlist("Library", listOf(1, 2, 3), "<key>Master</key><true/><key>Visible</key><false/>")}
    ${playlist("Music", listOf(1, 2, 3), "<key>Distinguished Kind</key><integer>4</integer>")}
    <dict><key>Name</key><string>Gym</string><key>Playlist Persistent ID</key><string>F1</string><key>Folder</key><true/></dict>
    ${playlist("Run", listOf(3, 1), "<key>Parent Persistent ID</key><string>F1</string>")}
    ${playlist("Top 25", listOf(2), "<key>Smart Info</key><data>\n\tAQEAAwAAAAIAAAAZAAAAAAAAAAcAAAAB\n</data>")}
    ${playlist("Road Trip", listOf(2, 99))}
  </array>
</dict>
</plist>"""

    @Test fun aLibraryIsReadWithItsPlaylistsAndKinds() {
        val lib = ItunesLibrary.read(library)
        assertEquals(3, lib.trackCount)
        assertTrue(lib.isLibrary)
        assertTrue(lib.worthPicking)
        assertEquals(listOf("Run", "Top 25", "Road Trip"), lib.choices.map { it.name })
        assertEquals(ItunesLibrary.Kind.SMART, lib.choices[1].kind)
        assertEquals("Gym", lib.choices[0].folder)
        // In the playlist's order; an item whose song is missing is skipped.
        assertEquals(listOf("Café", "Volare"), lib.tracksOf(lib.choices[0]).map { it.title })
        assertEquals(listOf("Sigur Rós"), lib.tracksOf(lib.choices[0])[0].artists)
        assertEquals(listOf("Rock & Roll"), lib.tracksOf(lib.choices[2]).map { it.title })
        // As one list, a library is every song, as before 2.1.
        assertEquals(listOf("Volare", "Rock & Roll", "Café"), lib.asOneList().map { it.title })
        assertEquals(listOf("Volare", "Rock & Roll", "Café"), PlaylistFiles.parse("Library.xml", library).map { it.title })
    }

    @Test fun oneExportedPlaylistKeepsItsOrderAndIsNotPicked() {
        val xml = """<plist version="1.0"><dict><key>Tracks</key><dict>${track(1, "A", "X")}${track(2, "B", "Y")}</dict>
            <key>Playlists</key><array>${playlist("Mine", listOf(2, 1))}</array></dict></plist>"""
        val lib = ItunesLibrary.read(xml)
        assertFalse(lib.isLibrary)
        assertFalse(lib.worthPicking)
        assertEquals(listOf("B", "A"), lib.asOneList().map { it.title })
    }

    @Test fun looksLikeAPlist() {
        assertTrue(ItunesLibrary.looksLike("﻿<?xml version=\"1.0\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"x\">"))
        assertTrue(ItunesLibrary.looksLike("<plist version=\"1.0\">"))
        assertFalse(ItunesLibrary.looksLike("#EXTM3U\n"))
        assertFalse(ItunesLibrary.looksLike("<?xml version=\"1.0\"?><playlist xmlns=\"http://xspf.org/ns/0/\">"))
    }

    /** A generated library far bigger than the read buffer, never held as a whole: chars come from a Reader. */
    private class BigLibrary(private val songs: Int, private val lists: Int) : Reader() {
        private val parts = sequence {
            yield("<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>Tracks</key><dict>")
            for (i in 1..songs) yield(
                "<key>$i</key><dict><key>Track ID</key><integer>$i</integer><key>Name</key><string>Song $i</string>" +
                    "<key>Artist</key><string>Artist ${i % 500}</string><key>Album</key><string>Album ${i % 2000}</string>" +
                    "<key>Total Time</key><integer>${180_000 + i % 60_000}</integer><key>Location</key><string>file:///Users/me/Music/Media/Artist%20${i % 500}/Album%20${i % 2000}/$i%20Song.m4a</string></dict>",
            )
            yield("</dict><key>Playlists</key><array>")
            yield("<dict><key>Name</key><string>Library</string><key>Master</key><true/><key>Playlist Items</key><array>")
            for (i in 1..songs) yield("<dict><key>Track ID</key><integer>$i</integer></dict>")
            yield("</array></dict>")
            for (p in 1..lists) {
                yield("<dict><key>Name</key><string>List $p</string><key>Playlist Items</key><array>")
                for (i in 1..200) yield("<dict><key>Track ID</key><integer>${(p * 37 + i * 11) % songs + 1}</integer></dict>")
                yield("</array></dict>")
            }
            yield("</array></dict></plist>")
        }.iterator()
        private var cur = ""
        private var at = 0
        var chars = 0L
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            while (at == cur.length) { if (!parts.hasNext()) return -1; cur = parts.next(); at = 0 }
            val n = minOf(len, cur.length - at)
            cur.toCharArray(cbuf, off, at, at + n)
            at += n; chars += n
            return n
        }
        override fun close() = Unit
    }

    @Test fun aBigLibraryIsReadAPieceAtATime() {
        val big = BigLibrary(songs = 120_000, lists = 40)
        var reported = 0L
        val lib = ItunesLibrary.read(big, progress = { reported = it })
        assertTrue("generated more than 40 MB of XML: ${big.chars}", big.chars > 40_000_000)
        assertTrue(reported > 0)
        assertEquals(120_000, lib.trackCount)
        assertEquals(40, lib.choices.size)
        assertEquals(200, lib.tracksOf(lib.choices[0]).size)
    }

    @Test fun aFileCutShortIsRefusedNotHalfRead() {
        for (cut in listOf(library.length / 3, library.length / 2, library.length - 20)) {
            try { ItunesLibrary.read(library.substring(0, cut)); org.junit.Assert.fail("cut at $cut was read") }
            catch (e: ItunesLibrary.Incomplete) { /* refused */ }
        }
        // An empty or non-plist file is refused the same way.
        try { ItunesLibrary.read(""); org.junit.Assert.fail() } catch (e: ItunesLibrary.Incomplete) { }
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun readingStopsWhenCancelled() {
        ItunesLibrary.read(BigLibrary(songs = 50_000, lists = 1), cancelled = { true })
    }
}

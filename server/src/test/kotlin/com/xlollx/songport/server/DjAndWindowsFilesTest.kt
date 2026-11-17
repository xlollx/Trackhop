package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** rekordbox XML and WPL/ZPL on the server: the download formats, and a rekordbox collection taken as a library to pick from. */
class DjAndWindowsFilesTest {
    private val tracks = listOf(Track("1", "Blue Monday", listOf("New Order"), album = "Substance", durationMs = 448_000), Track("2", "Solo <&>"))

    @Test fun theNewFormatsAreOfferedByTheirExtension() {
        assertEquals(ExportFormat.WPL, ExportFormat.of("wpl"))
        assertEquals(ExportFormat.ZPL, ExportFormat.of("ZPL"))
        assertEquals(ExportFormat.REKORDBOX, ExportFormat.of("xml"))
        assertTrue(ExportFormat.offered().containsAll(listOf(ExportFormat.WPL, ExportFormat.ZPL, ExportFormat.REKORDBOX)))
        assertTrue(ExportFormat.WPL.encode("Mix", tracks).startsWith("<?wpl version=\"1.0\"?>"))
        assertTrue(ExportFormat.ZPL.encode("Mix", tracks).startsWith("<?zpl version=\"2.0\"?>"))
        assertTrue(ExportFormat.REKORDBOX.encode("Mix", tracks).contains("<DJ_PLAYLISTS Version=\"1.0.0\">"))
    }

    @Test fun aRekordboxCollectionIsALibraryWithPlaylistsToTick() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<DJ_PLAYLISTS Version="1.0.0">
  <PRODUCT Name="rekordbox" Version="6.8.5" Company="AlphaTheta"/>
  <COLLECTION Entries="3">
    <TRACK TrackID="1" Name="Blue Monday" Artist="New Order" Album="Substance" TotalTime="448" Location="file://localhost/M/1.wav"/>
    <TRACK TrackID="2" Name="Around the World" Artist="Daft Punk" Album="Homework" TotalTime="429" Location="file://localhost/M/2.mp3"/>
    <TRACK TrackID="3" Name="Teardrop" Artist="Massive Attack" Album="Mezzanine" TotalTime="330" Location="file://localhost/M/3.mp3"/>
  </COLLECTION>
  <PLAYLISTS>
    <NODE Type="0" Name="ROOT" Count="2">
      <NODE Name="Warm up" Type="1" KeyType="0" Entries="2"><TRACK Key="3"/><TRACK Key="1"/></NODE>
      <NODE Name="Peak" Type="1" KeyType="0" Entries="1"><TRACK Key="2"/></NODE>
    </NODE>
  </PLAYLISTS>
</DJ_PLAYLISTS>
"""
        val file = File.createTempFile("rekordbox", ".xml").apply { writeText("﻿" + xml) }
        assertTrue(LibraryUploads.isLibrary(file))
        val lib = LibraryUploads.readLibrary(file)
        assertTrue(!file.exists())
        assertTrue(lib.worthPicking)
        assertEquals(listOf("Warm up", "Peak"), lib.choices.map { it.name })
        assertEquals(listOf("Teardrop", "Blue Monday"), lib.tracksOf(lib.choices[0]).map { it.title })
        // Cut short: refused, and the upload is deleted all the same.
        val cut = File.createTempFile("rekordbox", ".xml").apply { writeText(xml.substring(0, xml.length - 40)) }
        assertTrue(runCatching { LibraryUploads.readLibrary(cut) }.exceptionOrNull() is com.xlollx.songport.sync.ItunesLibrary.Incomplete)
        assertTrue(!cut.exists())
    }
}

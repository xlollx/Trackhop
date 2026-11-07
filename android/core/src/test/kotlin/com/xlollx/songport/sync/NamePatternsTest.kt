package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamePatternsTest {

    private fun read(name: String, vararg lines: String) = NamePatterns.read(name, NamePatterns.compileAll(lines.toList()))

    @Test fun templatesReadPathsFromTheEnd() {
        val r = read("F9FF-3A77/Music/Sault/SAULT - 'NINE'/SAULT - 'NINE' - 02 London Gangs.mp3", "{artist} - {album} - {track} {title}")!!
        assertEquals(NamePatterns.Reading("SAULT", "'NINE'", "London Gangs"), r)
        val p = read("Music/Childish Gambino/2016.12 - Awaken, My Love!/06 Redbone.m4a", "{artist}/{year} - {album}/{track} {title}")!!
        assertEquals(NamePatterns.Reading("Childish Gambino", "Awaken, My Love!", "Redbone"), p)
        assertEquals("Pretty Vacant", read("x/10. Pretty Vacant.mp3", "{track} {title}")!!.title)
    }

    @Test fun theFirstMatchingPatternWinsAndOthersFallThrough() {
        val lines = arrayOf("{title} ({artist})", "{artist} - {title}")
        assertEquals(NamePatterns.Reading("Muse", "", "Hysteria"), read("Hysteria (Muse)", *lines))
        assertEquals(NamePatterns.Reading("Muse", "", "Hysteria"), read("Muse - Hysteria", *lines))
        assertNull(read("Hysteria", *lines))
    }

    @Test fun regularExpressionsWithNamedGroups() {
        val r = read("Muse _ Hysteria", "re:^(?<artist>.+?) _ (?<title>.+)$")!!
        assertEquals(NamePatterns.Reading("Muse", "", "Hysteria"), r)
    }

    @Test fun badPatternsSayWhy() {
        assertTrue(NamePatterns.compile("{artist} - {album}").exceptionOrNull()!!.message!!.contains("{title}"))
        assertTrue(NamePatterns.compile("re:(?<title>[").isFailure)
        assertEquals(1, NamePatterns.compileAll(listOf("", "{artist} - {title}", "re:((")).size)
    }

    @Test fun customLayoutInAFile() {
        val m3u = "#EXTM3U\nMusic/Muse/Absolution/Muse - Absolution - 03 Hysteria.mp3\nMusic/Odd/odd name.mp3\n"
        val t = PlaylistFiles.parse("x.m3u", m3u, PlaylistFiles.NameLayout.CUSTOM, NamePatterns.compileAll(listOf("{artist} - {album} - {track} {title}")))
        assertEquals(listOf("Hysteria", "odd name"), t.map { it.title })
        assertEquals("Absolution", t[0].album)
        // Patterns are used only in CUSTOM.
        val auto = PlaylistFiles.parse("x.txt", "Hysteria (Muse)\n", PlaylistFiles.NameLayout.AUTO, NamePatterns.compileAll(listOf("{title} ({artist})")))
        assertEquals("Hysteria (Muse)", auto[0].title)
    }
}

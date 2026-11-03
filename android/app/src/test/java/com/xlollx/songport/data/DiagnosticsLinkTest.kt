package com.xlollx.songport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** The report in a "Report on GitHub" link: short enough once encoded, the newest log lines kept. */
class DiagnosticsLinkTest {
    private fun encoded(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").length

    @Test fun aLongReportFitsTheLinkOnceEncoded() {
        val head = "Trackhop 1.9.6 (139)\nAndroid 17 · samsung\n\nSyncs: 7\n  Metal & More: spotify_bridge -> amazon\n\nLog:\n"
        val log = (1..400).joinToString("\n") { "  2026-11-03 22:55:${it % 60} [spotify] HTTP 403 PUT https://api.spotify.com/v1/me/tracks: Forbidden – ñ € 日本 $it" }
        val out = Diagnostics.forLink(head + log, 5500)
        assertTrue(encoded(out).toString(), encoded(out) <= 5500)
        assertTrue("head", out.startsWith(head))
        assertTrue("tail: " + out.takeLast(80), out.trimEnd().endsWith("日本 400"))
        assertTrue("note", out.contains("older lines left out"))
    }

    @Test fun aShortReportStaysWhole() {
        val r = "Trackhop\n\nLog:\n  one line"
        assertEquals(r, Diagnostics.forLink(r, 5500))
    }
}

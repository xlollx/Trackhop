package com.xlollx.songport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    @Test fun tokensCookiesAndPasswordsAreHidden() {
        val log = """
            [subsonic] GET https://music.example.org/rest/ping?u=lorenzo&t=4f3a9c&s=salt12&v=1.16.1&c=Trackhop -> 200
            [plex] GET http://192.168.1.20:32400/playlists?X-Plex-Token=abcDEF123456 -> 401
            Authorization: Bearer BQ-very-long-access-token-0123456789
            {"AccessToken":"tok-0123456789abcdef","User":{"Id":"u1"},"password":"hunter22"}
            cookie: sp_dc=AQB-secret-cookie; sp_t=device-1; arl=deadbeefcafe
            signed in as someone@example.com, jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
        """.trimIndent()
        val out = Redact.text(log, servers = listOf("https://music.example.org"))
        for (secret in listOf("4f3a9c", "salt12", "lorenzo", "abcDEF123456", "BQ-very-long-access-token", "tok-0123456789abcdef",
                "hunter22", "AQB-secret-cookie", "deadbeefcafe", "someone@example.com", "eyJhbGciOiJIUzI1NiJ9", "music.example.org", "192.168.1.20")) {
            assertFalse("$secret still in:\n$out", out.contains(secret))
        }
        assertTrue(out, out.contains("/rest/ping"))
        assertTrue(out, out.contains("-> 401"))
        assertTrue(out, out.contains("v=1.16.1"))
    }

    @Test fun anOrdinaryLogLineIsLeftAsItIs() {
        val line = "2026-11-06 10:34:59 [engine] Apple Music (web): 150 searches in 26s (173 ms each), 0 waits"
        assertEquals(line, Redact.text(line))
        val sync = "[sync] end Quirktronica: +143 -0 nf=7 src=150 dst=0"
        assertEquals(sync, Redact.text(sync))
        val plan = "[engine] Malayalam Trending: plan ready in 115s: source 199, not found 0, to review 1, removals held back 0"
        assertEquals(plan, Redact.text(plan))
    }
}

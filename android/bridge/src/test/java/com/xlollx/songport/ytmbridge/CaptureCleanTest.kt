package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** The capture's addresses: personal query fields blanked by their decoded name, the rest kept. */
class CaptureCleanTest {
    @Test fun personalFieldsBlankedOthersKept() {
        assertEquals(
            "https://www.qobuz.com/api.json/0.2/user/login?email=[redacted]&password=[redacted]&app_id=798273057",
            AmazonCaptureActivity.cleanUrl("https://www.qobuz.com/api.json/0.2/user/login?email=a%40b.c&password=x&app_id=798273057"),
        )
        assertEquals(
            "https://www.qobuz.com/signup?register%5B_password%5D=[redacted]&page=2",
            AmazonCaptureActivity.cleanUrl("https://www.qobuz.com/signup?register%5B_password%5D=x&page=2"),
        )
        val plain = "https://www.qobuz.com/api.json/0.2/playlist/addTracks?playlist_id=1&track_ids=2,3"
        assertEquals(plain, AmazonCaptureActivity.cleanUrl(plain))
    }

    // #53: Anghami's session travels as sid (and fingerprint): its value never reaches the file, its name does.
    @Test fun sessionValuesBlankedNamesKept() {
        assertEquals(
            "https://coussa.anghami.com/gateway.php?type=GETplaylists&sid=[redacted]&fingerprint=[redacted]&lang=en",
            AmazonCaptureActivity.cleanUrl("https://coussa.anghami.com/gateway.php?type=GETplaylists&sid=abc123&fingerprint=f-1&lang=en"),
        )
        val line = """{"url":"https://coussa.anghami.com/gateway.php?type=GETx\u0026sid=abc123","body":"{\"sid\":\"abc123\",\"udid\": \"d9\"}","response":{"sid":"zz9","name":"Ok"}}"""
        val clean = AmazonCaptureActivity.cleanLine(line)
        assertEquals(false, clean.contains("abc123") || clean.contains("zz9"))
        assertEquals(true, clean.contains("\"sid\":\"[redacted]\"") && clean.contains("sid=[redacted]") && clean.contains("\"name\":\"Ok\""))
    }
}

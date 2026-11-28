package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.SubsonicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a personal server last scanned its library (#103, 2.2.7): a song missed there before that is
 * searched again. One light call per server; an answer without the time is null, never a guess.
 */
class LibraryScanContractTest : ContractTest() {
    private fun connect(id: String) =
        env.tokens.set(id, Tokens(accessToken = "server-token", userId = "user-1", userName = "me", extra = mapOf("url" to "https://music.example")))

    @Test fun navidromeSaysItsLastScan() = runBlocking {
        connect("subsonic")
        json("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","scanStatus":{"scanning":false,"count":1200,"folderCount":80,"lastScan":"2025-11-26T20:15:03.123456789+01:00"}}}""")
        assertEquals(java.time.Instant.parse("2025-11-26T19:15:03.123Z").toEpochMilli(), SubsonicProvider().lastScan(env))
        val r = next()
        assertEquals("/rest/getScanStatus", r.pathOnly())
        noMoreRequests()
    }

    @Test fun aSubsonicServerWithoutTheTimeSaysNothing() = runBlocking {
        connect("subsonic")
        json("""{"subsonic-response":{"status":"ok","version":"1.16.1","scanStatus":{"scanning":false,"count":1200}}}""")
        assertNull(SubsonicProvider().lastScan(env))
        // Navidrome before its first scan: Go's zero time.
        json("""{"subsonic-response":{"status":"ok","version":"1.16.1","scanStatus":{"scanning":true,"lastScan":"0001-01-01T00:00:00Z"}}}""")
        assertNull(SubsonicProvider().lastScan(env))
    }

    @Test fun jellyfinTakesTheLaterOfTheScanTaskAndTheNewestSong() = runBlocking {
        connect("jellyfin")
        json("""[{"Name":"Scan Media Library","Key":"RefreshLibrary","LastExecutionResult":{"EndTimeUtc":"2025-11-27T10:00:00.0000000Z"}},
            {"Name":"Clean Cache","Key":"DeleteCacheFiles","LastExecutionResult":{"EndTimeUtc":"2025-11-28T03:00:00Z"}}]""")
        json("""{"Items":[{"Id":"a1","Name":"06 In the End","DateCreated":"2025-11-27T16:33:00.1234567Z"}],"TotalRecordCount":1}""")
        assertEquals(java.time.Instant.parse("2025-11-27T16:33:00.123Z").toEpochMilli(), JellyfinProvider().lastScan(env))
        assertEquals("/ScheduledTasks", next().pathOnly())
        val items = next()
        assertEquals("DateCreated", items.query("SortBy"))
        assertEquals("Descending", items.query("SortOrder"))
        assertEquals("1", items.query("Limit"))
    }

    @Test fun jellyfinForAnAccountThatIsNoAdministratorReadsTheNewestSongAlone() = runBlocking {
        connect("jellyfin")
        text("", 403, "text/plain")
        json("""{"Items":[{"Id":"a1","Name":"Numb","DateCreated":"2025-11-20T08:00:00Z"}],"TotalRecordCount":1}""")
        assertEquals(java.time.Instant.parse("2025-11-20T08:00:00Z").toEpochMilli(), JellyfinProvider().lastScan(env))
    }

    @Test fun plexTakesItsMusicLibrariesScannedAt() = runBlocking {
        env.tokens.set("plex", Tokens("OWNER", userName = "plex.lan:32400", extra = mapOf("url" to "https://plex.lan:32400", "machine" to "MACH")))
        json("""{"MediaContainer":{"Directory":[{"key":"3","type":"artist","scannedAt":1764200000},{"key":"5","type":"artist","scannedAt":1764100000},{"key":"4","type":"movie","scannedAt":1764300000}]}}""")
        assertEquals(1_764_200_000_000L, PlexProvider().lastScan(env))
        assertEquals("/library/sections", next().pathOnly())
    }
}

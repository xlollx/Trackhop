package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A service's ids carried through a CSV or JSPF file, and a file synced back restoring them without a search. */
class OriginsTest {
    private val spotify = MemoryService("spotify")
    private val added = java.time.Instant.parse("2024-03-01T10:15:30Z").toEpochMilli()
    private val read = listOf(
        Track(id = "4uLU6hMCjMI75M1A2tKUQC", title = "Never Gonna Give You Up", artists = listOf("Rick Astley"), album = "Whenever You Need Somebody",
            durationMs = 213_000, isrc = "GBARL9300135", explicit = false, year = 1987, addedAt = added),
        Track(id = "x", title = "No id kept for a show", kind = Track.KIND_PODCAST),
    )

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun csvKeepsTheServiceIdAndWhatTheServiceSaid() {
        val text = CsvCodec.encode(Origins.stamp(spotify, read))
        assertTrue(text, text.lineSequence().first().endsWith("service,service_id,url,added_at,explicit,year"))
        assertTrue(text, "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC" in text)
        val back = CsvCodec.parse(text)
        assertEquals("spotify:4uLU6hMCjMI75M1A2tKUQC", back[0].origin)
        assertEquals(added, back[0].addedAt)
        assertEquals(false, back[0].explicit)
        assertEquals(1987, back[0].year)
        assertNull(back[1].origin)
    }

    @Test fun aPlainFileReadsAsBefore() {
        val text = CsvCodec.encode(listOf(Track(id = "", title = "A", artists = listOf("B"))))
        assertEquals("title,artist,album,duration_ms,isrc", text.lineSequence().first())
        // Columns of that name in a file from elsewhere are not taken as ours.
        assertNull(CsvCodec.parse("Track Name,Artist Name,service,service_id\nA,B,spotify,123\n")[0].origin)
    }

    @Test fun jspfKeepsThemToo() {
        val text = PlaylistFiles.toJspf("Mix", Origins.stamp(spotify, read.take(1)))
        val back = PlaylistFiles.parse("mix.jspf", text)
        assertEquals("spotify:4uLU6hMCjMI75M1A2tKUQC", back[0].origin)
        assertEquals(added, back[0].addedAt)
        assertEquals(1987, back[0].year)
        assertEquals("GBARL9300135", back[0].isrc)
    }

    @Test fun onlyServicesWithSharedIdsAreStamped() {
        assertNull(Origins.stamp(MemoryService("jellyfin"), read)[0].origin)
        assertEquals("youtube:abc", Origins.stamp(MemoryService("ytm_bridge"), listOf(Track(id = "abc", title = "t")))[0].origin)
        assertEquals("abc", Origins.idOn(MemoryService("youtube"), Track(id = "", title = "t", origin = "youtube:abc")))
        assertNull(Origins.idOn(MemoryService("deezer"), Track(id = "", title = "t", origin = "youtube:abc")))
    }

    @Test fun aBackupSyncedBackToItsServiceTakesTheIdsWithoutASearch() = runBlocking {
        val env = ContractEnv()
        val files = LocalFilesProvider
        Providers.testDoubles = listOf(spotify)
        val fileId = files.importTracks(env, "Backup", CsvCodec.parse(CsvCodec.encode(Origins.stamp(spotify, read.take(1)))))
        // The service's search finds nothing (the catalogue is empty): only the id can put it back.
        spotify.lists["t"] = mutableListOf()
        val job = SyncJob(id = "restore", name = "restore", source = PlaylistRef(files.id, fileId, fileId), target = PlaylistRef("spotify", "t", "t"))
            .also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(job)
        assertTrue("$r", r.unmatchedTracks.isEmpty())
        assertEquals("$r", 1, r.added)
        assertEquals(listOf("4uLU6hMCjMI75M1A2tKUQC"), spotify.lists["t"]!!.map { it.id })
    }
}

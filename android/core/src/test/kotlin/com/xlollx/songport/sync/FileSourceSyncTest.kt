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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A CSV imported on the phone and sent to a service: the way a playlist file reaches Apple Music.
 *
 * The file is the source (the app keeps it in its own folder), the service is the target: the
 * engine has to read the file, find every song and add all of them, not just the first block.
 */
class FileSourceSyncTest {
    private lateinit var env: ContractEnv
    private lateinit var dst: MemoryService

    @Before
    fun setUp() {
        env = ContractEnv()
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(LocalFilesProvider, dst)
        dst.lists["t"] = ArrayList()
    }

    @After
    fun tearDown() {
        Providers.testDoubles = emptyList()
    }

    /** A CSV as a phone export writes it: the artist first, then the title, one row per song. */
    private fun csv(count: Int): String = buildString {
        append("Artist,Title\r\n")
        for (i in 1..count) append("Artist $i,Song $i\r\n")
    }

    private fun serviceKnows(count: Int) {
        for (i in 1..count) dst.catalogue += Track(id = "d$i", title = "Song $i", artists = listOf("Artist $i"), durationMs = 200_000)
    }

    @Test
    fun aCsvImportedOnThePhoneReachesTheService() = runBlocking {
        val pl = LocalFilesProvider.importFileAsPlaylist(env, "Premios.csv", csv(144))
        assertEquals(144, pl.trackCount)
        serviceKnows(144)

        val job = SyncJob(
            id = "j", name = "Premios",
            source = PlaylistRef(LocalFilesProvider.id, pl.id, pl.name),
            target = PlaylistRef("mdst", "t", "t"),
        )
        env.store.upsertJob(job)

        val report = SyncEngine(env).run(job)

        assertNull(report.error)
        assertEquals(144, report.added)
        assertEquals(144, dst.lists["t"]!!.size)
        assertTrue(report.unmatched.isEmpty())
    }

    /** A big list arrives whole: the blocks the engine adds in are not lost on the way. */
    @Test
    fun aBigListIsAddedWhole() = runBlocking {
        val pl = LocalFilesProvider.importFileAsPlaylist(env, "Big.csv", csv(144))
        serviceKnows(144)
        val job = SyncJob(
            id = "j", name = "Big",
            source = PlaylistRef(LocalFilesProvider.id, pl.id, pl.name),
            target = PlaylistRef("mdst", "t", "t"),
        )
        env.store.upsertJob(job)

        val report = SyncEngine(env).run(job)

        assertEquals(144, report.added)
        assertEquals(144, dst.lists["t"]!!.size)
    }

    /** Two imports of the same name are two lists: the second one does not overwrite the first. */
    @Test
    fun importingTheSameNameTwiceKeepsBothLists() = runBlocking {
        val first = LocalFilesProvider.importFileAsPlaylist(env, "Premios.csv", csv(3))
        val second = LocalFilesProvider.importFileAsPlaylist(env, "Premios.csv", csv(4))

        assertNotEquals(first.id, second.id)
        assertEquals(3, first.trackCount)
        assertEquals(4, second.trackCount)
        assertEquals(2, LocalFilesProvider.playlists(env).size)
    }
}

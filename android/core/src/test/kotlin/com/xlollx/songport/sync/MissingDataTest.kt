package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the "Missing on my server" list keeps of the source track: album, length, ISRC (item 9b). */
class MissingDataTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    private val a = Track(id = "a", title = "Amber Lantern", artists = listOf("Cobalt Raven"), album = "Glass Hours", durationMs = 201_000, isrc = "USABC2300001")

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        src.lists["p"] = mutableListOf(a)
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    @Test fun theReportKeepsTheSourceTracksData() = runBlocking {
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(job)
        assertEquals(listOf(a), r.unmatchedTracks)
        assertEquals(listOf(a), env.store.data.reports.first().unmatchedTracks)
        // Out as the CSV and the JSON a person downloads.
        val want = Wanted.list(env.store.data, { null }, Wanted.Filter(scope = Wanted.Scope.ALL))
        assertEquals(1, want.size)
        val csv = Wanted.Format.CSV.encode(want)
        assertTrue(csv, csv.contains("Amber Lantern,Cobalt Raven,Glass Hours,201000,USABC2300001"))
        assertTrue(Wanted.Format.JSON.encode(want).contains("USABC2300001"))
    }
}

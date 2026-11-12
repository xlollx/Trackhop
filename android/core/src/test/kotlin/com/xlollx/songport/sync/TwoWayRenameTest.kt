package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A two-way pair is two syncs, each the other's reverse: a rename on either side reaches the other
 * side through the half whose source it is, and both halves' stored names follow at once, so the
 * other half neither renames back nor takes the rename for a playlist that names itself.
 */
class TwoWayRenameTest {
    private lateinit var env: ContractEnv
    private lateinit var a: MemoryService
    private lateinit var b: MemoryService
    private lateinit var engine: SyncEngine
    private lateinit var forward: SyncJob
    private lateinit var back: SyncJob

    @Before fun setUp() {
        env = ContractEnv()
        a = MemoryService("msrc"); b = MemoryService("mdst")
        Providers.testDoubles = listOf(a, b)
        engine = SyncEngine(env)
        val t = Track(id = "x", title = "Xylo", artists = listOf("Band"), durationMs = 200_000)
        a.lists["p"] = mutableListOf(t); b.lists["t"] = mutableListOf(t.copy(id = "bx"))
        a.catalogue += t; b.catalogue += t.copy(id = "bx")
        a.names["p"] = "Road trip"; b.names["t"] = "Road trip"
        pair(targetName = "Road trip")
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun pair(targetName: String) {
        forward = SyncJob(id = "job-f", name = "Road trip", source = PlaylistRef("msrc", "p", "Road trip"), target = PlaylistRef("mdst", "t", targetName), linkedJobId = "job-b")
        back = SyncJob(id = "job-b", name = "Road trip ↔", source = PlaylistRef("mdst", "t", targetName), target = PlaylistRef("msrc", "p", "Road trip"), linkedJobId = "job-f", twin = true)
        env.store.upsertJob(forward); env.store.upsertJob(back)
    }

    private fun run(id: String) = runBlocking { engine.run(env.store.job(id)!!) }.also { assertNull(it.error) }
    private fun job(id: String) = env.store.job(id)!!

    @Test fun aRenameOnTheFirstSideReachesTheOtherAndBothHalvesKnowIt() {
        a.names["p"] = "Night drive"
        run("job-f")
        assertEquals(listOf("t" to "Night drive"), b.renames)
        val f = job("job-f"); val r = job("job-b")
        assertEquals("Night drive", f.source.playlistName); assertEquals("Night drive", f.target.playlistName); assertEquals("Night drive", f.name)
        assertEquals("Night drive", r.source.playlistName); assertEquals("Night drive", r.target.playlistName)
        assertEquals("the twin follows the sync's name", "Night drive ↔", r.name)
        assertNull("not taken for a playlist that renames itself", r.sourceRenamedAt)
        // The way back runs: nothing to rename, nothing renamed back.
        run("job-b")
        assertTrue(a.renames.isEmpty())
        assertEquals(1, b.renames.size)
        assertNull(job("job-b").sourceRenamedAt)
    }

    @Test fun aRenameOnTheOtherSideReachesTheFirstThroughTheWayBack() {
        b.names["t"] = "Night drive"
        // The first half reads its own source only: unchanged, nothing to do.
        run("job-f")
        assertTrue(a.renames.isEmpty() && b.renames.isEmpty())
        run("job-b")
        assertEquals(listOf("p" to "Night drive"), a.renames)
        val f = job("job-f"); val r = job("job-b")
        assertEquals("Night drive", r.source.playlistName); assertEquals("Night drive", r.target.playlistName)
        assertEquals("Night drive", f.source.playlistName); assertEquals("Night drive", f.target.playlistName)
        assertEquals("the sync named after its playlist follows", "Night drive", f.name)
        assertNull(f.sourceRenamedAt)
        run("job-f")
        assertEquals(1, a.renames.size)
        assertTrue(b.renames.isEmpty())
    }

    @Test fun aNameTheUserGaveTheOtherSideIsKept() {
        b.names["t"] = "My own name"
        pair(targetName = "My own name")
        a.names["p"] = "Night drive"
        run("job-f")
        assertTrue(b.renames.isEmpty())
        val f = job("job-f"); val r = job("job-b")
        assertEquals("Night drive", f.source.playlistName); assertEquals("My own name", f.target.playlistName)
        assertEquals("My own name", r.source.playlistName); assertEquals("Night drive", r.target.playlistName)
        run("job-b")
        assertTrue(a.renames.isEmpty() && b.renames.isEmpty())
    }

    @Test fun runsAfterARenameNeverRenameAgain() {
        a.names["p"] = "Night drive"
        repeat(3) { run("job-f"); run("job-b") }
        assertEquals(listOf("t" to "Night drive"), b.renames)
        assertTrue(a.renames.isEmpty())
        // Then a rename on the other side: one rename again, the other way.
        b.names["t"] = "Dawn"
        repeat(3) { run("job-b"); run("job-f") }
        assertEquals(listOf("p" to "Dawn"), a.renames)
        assertEquals(1, b.renames.size)
        assertEquals("Dawn", job("job-f").target.playlistName)
        assertEquals("Dawn", job("job-b").target.playlistName)
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Two syncs writing to one playlist: told apart when saved, run one after the other, no removal tug of war. */
class SharedTargetTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private val a = Track(id = "a", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val b = Track(id = "b", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p1"] = mutableListOf(a)
        src.lists["p2"] = mutableListOf(b)
        dst.catalogue += Track(id = "da", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
        dst.catalogue += Track(id = "db", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(id: String, source: String, mirror: Boolean) =
        SyncJob(id = id, name = id, source = PlaylistRef("msrc", source, source), target = PlaylistRef("mdst", "t", "t"), mirrorRemovals = mirror)

    @Test fun conflictsAreToldApart() {
        val one = job("one", "p1", mirror = false)
        val same = job("same", "p1", mirror = false)
        val other = job("other", "p2", mirror = false)
        assertTrue(JobConflicts.of(listOf(one), same) is JobConflicts.Conflict.Duplicate)
        // Two sources merged into one playlist, no mirror: a choice, not a conflict.
        assertNull(JobConflicts.of(listOf(one), other))
        assertTrue(JobConflicts.of(listOf(one), other.copy(mirrorRemovals = true)) is JobConflicts.Conflict.SharedTarget)
        // A target still to create shares nothing.
        assertNull(JobConflicts.of(listOf(one), same.copy(target = PlaylistRef("mdst", null, "new"))))
    }

    @Test fun aMirrorDoesNotTakeAwayWhatAnotherSyncAdds() = runBlocking {
        val one = job("one", "p1", mirror = true).also { env.store.upsertJob(it) }
        val two = job("two", "p2", mirror = true).also { env.store.upsertJob(it) }
        engine.run(one)
        engine.run(two)
        val again = engine.run(one)
        assertEquals(setOf("da", "db"), dst.lists["t"]!!.map { it.id }.toSet())
        assertEquals(0, again.removed)
        assertTrue(again.notes.toString(), again.notes.any { it.contains("note_shared_target_no_removals") || it.contains("two") })
    }

    @Test fun twoSyncsToOnePlaylistRunOneAfterTheOther() = runBlocking {
        val one = job("one", "p1", mirror = false).also { env.store.upsertJob(it) }
        val same = job("same", "p1", mirror = false).also { env.store.upsertJob(it) }
        val r1 = async { engine.run(one) }
        val r2 = async { SyncEngine(env).run(same) }
        r1.await(); r2.await()
        // At once they could have added the same track twice.
        assertEquals(listOf("da"), dst.lists["t"]!!.map { it.id })
    }
}

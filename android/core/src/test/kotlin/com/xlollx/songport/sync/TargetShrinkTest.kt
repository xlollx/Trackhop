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
 * The target's size check: a target listed far smaller than the last run left it is read again, and
 * still that small the run stops before writing (a listing read in part would add everything again,
 * thousands of writes). The preview goes on and says so; its confirmation lets one run through.
 */
class TargetShrinkTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    // Forty songs that look nothing alike: distinct words, artists and lengths.
    private val words = listOf("Amber", "Bridge", "Cobalt", "Desert", "Ember", "Falcon", "Glacier", "Harbor", "Indigo", "Jungle",
        "Kettle", "Lantern", "Meadow", "Nebula", "Orchid", "Pepper", "Quartz", "Raven", "Saffron", "Thunder",
        "Umbrella", "Velvet", "Willow", "Xenon", "Yonder", "Zephyr", "Anchor", "Blossom", "Canyon", "Dynamo",
        "Eclipse", "Fjord", "Granite", "Horizon", "Island", "Jasmine", "Kayak", "Lagoon", "Mirage", "Nectar")
    private fun t(id: String, n: Int) = Track(id = id, title = words[n - 1] + " Song", artists = listOf("Artist ${words[n - 1]}"),
        album = words[n - 1] + " Album", durationMs = 120_000L + n * 9_000L)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        shrinkRereadMs = 0
        src.lists["p"] = (1..40).map { t("s$it", it) }.toMutableList()
        dst.catalogue += (1..40).map { t("d$it", it) }
        dst.lists["t"] = (1..40).map { t("d$it", it) }.toMutableList()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList(); shrinkRereadMs = 3_000L }

    private fun job(): SyncJob =
        SyncJob(id = "job-" + System.nanoTime(), name = "test", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))
            .also { env.store.upsertJob(it) }

    @Test fun aTargetReadInPartTwiceStopsTheRunBeforeAnyWrite() = runBlocking {
        val j = job()
        assertNull(engine.run(j).error)
        dst.shortReads += listOf(3, 3)
        val r = engine.run(j)
        assertEquals(40, r.targetShrunkFrom)
        assertTrue(r.error, r.error!!.startsWith("error_target_shrunk(mdst,3,40)"))
        assertEquals(0, r.added)
        assertEquals(40, dst.lists["t"]!!.size)
    }

    @Test fun aSecondReadThatIsWholeLetsTheRunGoOn() = runBlocking {
        val j = job()
        engine.run(j)
        dst.shortReads += 3
        val r = engine.run(j)
        assertNull(r.error)
        assertEquals(0, r.added)
        assertEquals(0, r.targetShrunkFrom)
    }

    @Test fun thePreviewSaysItAndItsConfirmationLetsOneRunThrough() = runBlocking {
        val j = job()
        engine.run(j)
        // Really emptied down to 5.
        dst.lists["t"] = dst.lists["t"]!!.take(5).toMutableList()
        val plan = engine.plan(j, createTarget = false, interactive = true)
        assertEquals(40, plan.targetShrunkFrom)
        assertEquals(35, plan.toAdd.size)
        assertTrue(plan.notes.toString(), plan.notes.any { it == "note_target_shrunk(mdst,5,40)" })
        assertEquals(40, engine.run(j).targetShrunkFrom)
        env.store.confirmTargetShrink(j.id)
        val r = engine.run(j)
        assertNull(r.error)
        assertEquals(35, r.added)
        // Scheduled runs never use a confirmation.
        env.store.confirmTargetShrink(j.id)
        dst.lists["t"] = dst.lists["t"]!!.take(5).toMutableList()
        assertEquals(40, engine.run(j, unattended = true).targetShrunkFrom)
    }

    @Test fun smallTargetsAreNotChecked() = runBlocking {
        src.lists["p"] = src.lists["p"]!!.take(10).toMutableList()
        dst.lists["t"] = dst.lists["t"]!!.take(10).toMutableList()
        val j = job()
        engine.run(j)
        dst.lists["t"] = dst.lists["t"]!!.take(1).toMutableList()
        val r = engine.run(j)
        assertNull(r.error)
        assertEquals(9, r.added)
    }
}

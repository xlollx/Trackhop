package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A scheduled run skips a source that did not change (its snapshot or modification date, one light
 * call) instead of reading it whole (2.2): also towards a target without a version when the sync
 * only adds, never under another matching, and never for more than a day.
 */
class UnchangedSourceSkipTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine
    private var reads = 0
    private var onRead: () -> Unit = {}

    @Before fun setUp() {
        env = ContractEnv()
        src = object : MemoryService("msrc") {
            override suspend fun tracks(ctx: com.xlollx.songport.platform.Env, playlistId: String): List<Track> { reads++; onRead(); return super.tracks(ctx, playlistId) }
        }
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(Track("a", "Alpha", listOf("Artist"), "", 200_000))
        src.versions["p"] = "snap-1"
        dst.catalogue += Track("da", "Alpha", listOf("Artist"), "", 200_000)
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(mirror: Boolean = false) = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"), mirrorRemovals = mirror)

    private fun skipped(r: com.xlollx.songport.model.SyncReport) = r.notes.any { "note_unchanged" in it }

    @Test fun anUnchangedSourceIsNotReadAgainByAnAddOnlySync() = runBlocking {
        env.store.upsertJob(job())
        assertEquals(1, engine.run(job()).added)
        assertEquals(1, reads)
        val saved = env.store.job("j")!!
        assertEquals("snap-1", saved.sourceVersion)
        assertEquals(engine.runKey(saved), saved.versionsKey)
        assertTrue(saved.fullRunEpoch > 0)
        // No version on the target: the sync only adds, and the source is as it was.
        assertTrue(skipped(engine.run(saved, unattended = true)))
        assertEquals(1, reads)
        // Run by hand, it always reads.
        assertFalse(skipped(engine.run(env.store.job("j")!!)))
        assertEquals(2, reads)
        // The source changed: read.
        src.versions["p"] = "snap-2"
        assertFalse(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        assertEquals(3, reads)
    }

    @Test fun aMirrorWithoutATargetVersionAlwaysReads() = runBlocking {
        env.store.upsertJob(job(mirror = true))
        engine.run(job(mirror = true))
        assertFalse(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        assertEquals(2, reads)
        // With both versions unchanged it skips, as before.
        dst.versions["t"] = "t-1"
        engine.run(env.store.job("j")!!)
        assertTrue(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        assertEquals(3, reads)
    }

    @Test fun anotherMatchingOrADayLaterReadsTheSourceWhole() = runBlocking {
        env.store.upsertJob(job())
        engine.run(job())
        val saved = env.store.job("j")!!
        // Saved under another matching (an update since): read once, then skipped again.
        env.store.upsertJob(saved.copy(versionsKey = saved.versionsKey + 1))
        assertFalse(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        assertTrue(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        // A rule of the sync changed (artist names can differ, now): read.
        env.store.upsertJob(env.store.job("j")!!.let { it.copy(policy = it.policy.copy(artistsMayDiffer = true)) })
        assertFalse(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        assertTrue(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        // The last whole read more than a day ago.
        env.store.upsertJob(env.store.job("j")!!.let { it.copy(fullRunEpoch = it.fullRunEpoch - 25L * 3_600_000) })
        assertFalse(skipped(engine.run(env.store.job("j")!!, unattended = true)))
        assertEquals(4, reads)
    }

    @Test fun rulesChangedWhileARunReadsAreNotCertifiedByIt() = runBlocking {
        env.store.upsertJob(job())
        // The matching rules are changed while the run reads the source: it went by the old ones.
        onRead = { onRead = {}; env.store.upsertJob(env.store.job("j")!!.let { it.copy(policy = it.policy.copy(artistsMayDiffer = true)) }) }
        engine.run(job())
        val saved = env.store.job("j")!!
        assertTrue(saved.policy.artistsMayDiffer)
        assertTrue(saved.versionsKey != engine.runKey(saved))
        assertFalse(skipped(engine.run(saved, unattended = true)))
    }

    @Test fun aTargetMadeByTheRunIsCertifiedByIt() = runBlocking {
        val made = job().copy(target = PlaylistRef("mdst", null, "New"))
        env.store.upsertJob(made)
        engine.run(made)
        val saved = env.store.job("j")!!
        assertTrue(saved.target.playlistId != null)
        assertEquals(engine.runKey(saved), saved.versionsKey)
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import com.xlollx.songport.platform.env
import com.xlollx.songport.data.Store
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
import java.util.UUID

/**
 * The sync engine against two in-memory services: what gets added, removed, skipped and reordered.
 * Texts come back as "s:<name>" so notes can be checked by name.
 */
class SyncEngineTest {
    private val ctx = FakeContext()
    private lateinit var src: FakeProvider
    private lateinit var dst: FakeProvider
    private lateinit var engine: SyncEngine

    // The store (and its match cache) is one for the whole JVM: ids carry a per-test prefix so a
    // match remembered by one test never answers another.
    private val run = UUID.randomUUID().toString().take(6)
    private fun sid(id: String) = "$run-$id"
    private fun t(id: String, title: String, artist: String = "Band", isrc: String? = null) =
        Track(id = sid(id), title = title, artists = listOf(artist), album = "Album", durationMs = 200_000, isrc = isrc?.let { "$run-$it" })

    @Before fun setUp() {
        // Each test starts from no syncs: the store outlives a test, and syncs left on the same
        // target would be read as another sync writing there (JobConflicts).
        Store.get(ctx).let { st -> st.data.jobs.forEach { st.deleteJob(it.id) } }
        src = FakeProvider("fsrc")
        dst = FakeProvider("fdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(ctx.env) { name, args -> "s:$name" + (if (args.isEmpty()) "" else args.joinToString(",", "(", ")")) }
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(mirror: Boolean = false, keepOrder: Boolean = false, target: String? = "t"): SyncJob {
        val j = SyncJob(
            id = UUID.randomUUID().toString(), name = "test",
            source = PlaylistRef("fsrc", "p", "p"), target = PlaylistRef("fdst", target, target ?: "new"),
            mirrorRemovals = mirror, keepOrder = keepOrder,
        )
        Store.get(ctx).upsertJob(j)
        return j
    }

    @Test fun firstRunAddsWhatTheTargetHas() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"), t("c", "Gamma"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = mutableListOf()
        val r = engine.run(job())
        val why = "error=${r.error} notes=${r.notes} unmatched=${r.unmatched} added=${r.added}"
        assertEquals(why, 2, r.added)
        assertEquals(why, listOf(sid("da"), sid("db")), dst.lists["t"]!!.map { it.id })
        // The card lists what was added, in the source's order.
        assertEquals(why, listOf(sid("da"), sid("db")), r.addedTracks.map { it.id })
        assertEquals(why, listOf(sid("c")), r.unmatchedTracks.map { it.id })
        assertEquals(why, listOf(sid("c")), r.absent)
    }

    @Test fun secondRunAddsNothingAndAnUnattendedRunIsSkipped() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf()
        val j = job()
        val first = engine.run(j)
        assertEquals("error=${first.error} notes=${first.notes}", 1, first.added)
        val again = engine.run(Store.get(ctx).job(j.id)!!)
        assertEquals("error=${again.error} notes=${again.notes}", 0, again.added)
        assertEquals(1, dst.lists["t"]!!.size)
        val saved = Store.get(ctx).job(j.id)!!
        val skipped = engine.run(saved, unattended = true)
        assertTrue("versions=${saved.sourceVersion}/${saved.targetVersion} notes=${skipped.notes} error=${skipped.error}", skipped.notes.any { it == "s:note_unchanged" })
        assertEquals("searches=${dst.searches}", 1, dst.searches.size)
    }

    @Test fun mirrorRemovesWhatTheSourceLost() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf(t("da", "Alpha"), t("dx", "Extra"))
        val r = engine.run(job(mirror = true))
        assertEquals(1, r.removed)
        assertEquals(listOf(sid("da")), dst.lists["t"]!!.map { it.id })
    }

    @Test fun bigRemovalsWaitForThePreview() = runBlocking {
        // The source lists 2 of the target's 20 tracks, as a service that read it only partly would.
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = (listOf(t("da", "Alpha"), t("db", "Beta")) + (1..18).map { t("dx$it", "Extra $it") }).toMutableList()
        val j = job(mirror = true)
        val r = engine.run(j)
        assertEquals("error=${r.error} notes=${r.notes}", 0, r.removed)
        assertEquals(20, dst.lists["t"]!!.size)
        assertTrue(r.notes.toString(), r.notes.any { it == "s:note_removals_capped(18,20)" })
        assertEquals(20, r.targetCount)
        // The preview shows them all; applying it is the confirmation.
        val plan = engine.plan(j, createTarget = false, interactive = true)
        assertEquals(18, plan.toRemove.size)
        assertTrue(plan.notes.isEmpty())
    }

    @Test fun anEmptySourceNeverEmptiesTheTarget() = runBlocking {
        src.lists["p"] = mutableListOf()
        dst.lists["t"] = mutableListOf(t("dx", "Extra"), t("dy", "More"))
        val r = engine.run(job(mirror = true))
        assertEquals(0, r.removed)
        assertEquals(2, dst.lists["t"]!!.size)
    }

    @Test fun sameRecordingUnderAnotherIdIsNotAddedTwice() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha", isrc = "ISRC1"))
        dst.catalogue += t("da", "Alpha", isrc = "ISRC1")
        dst.lists["t"] = mutableListOf(t("da-remaster", "Alpha", isrc = "ISRC1"))
        val r = engine.run(job())
        assertEquals(0, r.added)
        assertEquals(1, dst.lists["t"]!!.size)
    }

    @Test fun keepOrderPutsTheTargetInTheSourcesOrder() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"), t("c", "Gamma"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"), t("dc", "Gamma"))
        dst.lists["t"] = mutableListOf(t("dc", "Gamma"), t("db", "Beta"), t("da", "Alpha"))
        val r = engine.run(job(keepOrder = true))
        assertEquals(0, r.added)
        assertEquals(listOf(listOf(sid("da"), sid("db"), sid("dc"))), dst.reorders)
        assertTrue(r.notes.any { it == "s:note_order_aligned" })
    }

    @Test fun keepOrderDoesNothingWhenAlreadyInOrder() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = mutableListOf(t("da", "Alpha"), t("db", "Beta"))
        engine.run(job(keepOrder = true))
        assertTrue(dst.reorders.isEmpty())
    }

    @Test fun aMissingTargetIsCreatedAndRemembered() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        val j = job(target = null)
        val r = engine.run(j)
        assertEquals(1, r.added)
        val saved = Store.get(ctx).job(j.id)!!
        assertEquals("new", saved.target.playlistId)
        assertEquals(listOf(sid("da")), dst.lists["new"]!!.map { it.id })
    }
}

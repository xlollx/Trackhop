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
 * A service that answers well (200) with fewer tracks than it counts for the playlist: a page it
 * did not send. A sync that removes would take off the target every track left out. The source is
 * read once more; still short, the run stops before writing anything and says it in plain words
 * ("<service> gave only N of M tracks; nothing was removed, try again later").
 */
class ShortListingTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private val reread = shrinkRereadMs

    private fun t(prefix: String, n: Int) = Track(id = "$prefix$n", title = "Song number $n", artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        shrinkRereadMs = 0
        env = ContractEnv()
        src = MemoryService("ssrc")
        dst = MemoryService("sdst")
        Providers.testDoubles = listOf(src, dst)
        src.catalogue += (1..100).map { t("s", it) }
        dst.catalogue += (1..100).map { t("d", it) }
        // Both hold the same hundred songs: a whole run has nothing to do.
        src.lists["p"] = (1..100).map { t("s", it) }.toMutableList()
        dst.lists["t"] = (1..100).map { t("d", it) }.toMutableList()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList(); shrinkRereadMs = reread }

    private fun job(mirror: Boolean = true) = SyncJob(
        id = "j", name = "mirror", source = PlaylistRef("ssrc", "p", "p"), target = PlaylistRef("sdst", "t", "t"), mirrorRemovals = mirror,
    ).also { env.store.upsertJob(it) }

    @Test fun aSourceShortOfItsOwnCountStopsTheRunBeforeAnyRemoval() = runBlocking {
        // The service counts 100 and sends 90, twice (the run reads it again).
        src.shortReads += listOf(90, 90)
        val r = SyncEngine(env).run(job())
        assertFalse(r.ok)
        assertEquals("error_listed_short(ssrc,90,100)", r.error)
        assertEquals(0, r.removed)
        assertEquals("nothing left the target", 100, dst.lists["t"]!!.size)
        assertTrue(env.logs.toString(), env.logs.any { "gave 90 of the 100 tracks it counts" in it && "nothing written" in it })
        // The next run reads it whole and has nothing to do.
        val next = SyncEngine(env).run(job())
        assertTrue(next.error, next.ok)
        assertEquals(0, next.removed)
        assertEquals(100, dst.lists["t"]!!.size)
    }

    @Test fun aSecondReadThatIsWholeLetsTheRunGoOn() = runBlocking {
        // The person really took ten songs out; one read comes short by more.
        repeat(10) { src.lists["p"]!!.removeAt(src.lists["p"]!!.size - 1) }
        src.shortReads += 60
        val r = SyncEngine(env).run(job())
        assertTrue(r.error, r.ok)
        assertEquals("the ten taken out by the person, not the thirty the short answer left out", 10, r.removed)
        assertEquals(90, dst.lists["t"]!!.size)
    }

    @Test fun aSyncThatOnlyAddsIsNotStopped() = runBlocking {
        src.shortReads += listOf(90, 90)
        val r = SyncEngine(env).run(job(mirror = false))
        assertTrue(r.error, r.ok)
        assertEquals(100, dst.lists["t"]!!.size)
    }

    @Test fun thePreviewSaysItAndARunConfirmedThereGoesThrough() = runBlocking {
        // The service hides five songs for good (it counts them and never lists them).
        src.shortReads += listOf(95, 95)
        val j = job()
        val seen = SyncEngine(env).plan(j, createTarget = false, interactive = true)
        assertTrue(seen.notes.toString(), seen.notes.any { it == "error_listed_short(ssrc,95,100)" })
        assertTrue(seen.removalsNeedConfirmation)
        assertEquals(5, seen.toRemove.size)
        env.store.confirmRemovals(j.id, seen.toRemove.map { it.id })
        src.shortReads += listOf(95, 95)
        val r = SyncEngine(env).run(j)
        assertTrue(r.error, r.ok)
        assertEquals(5, r.removed)
        // A scheduled run never takes that way.
        src.shortReads += listOf(94, 94)
        val scheduled = SyncEngine(env).run(j, unattended = true)
        assertEquals("error_listed_short(ssrc,94,100)", scheduled.error)
        assertEquals(95, dst.lists["t"]!!.size)
    }

    @Test fun aGapOfOneInAHundredIsANormalPlaylistReadOnce() = runBlocking {
        // A hidden or local track the service counts and never lists: 99 of 100 is not short.
        src.shortReads += listOf(99, 99)
        val r = SyncEngine(env).run(job())
        assertTrue(r.error, r.ok)
        assertEquals("read once", 1, src.shortReads.size)
        assertFalse(env.logs.toString(), env.logs.any { "reading it again" in it })
        assertEquals(1, r.removed)
    }

    @Test fun aDeletedVideoDoesNotBlockTheRemovalsThePersonMade() = runBlocking {
        // The service counts a deleted video it never lists; the person took song 50 out.
        src.lists["p"]!!.removeAt(49)
        src.lists["p"]!!.add(t("gone", 1).copy(title = "Deleted video"))
        src.shortReads += 99
        val r = SyncEngine(env).run(job())
        assertTrue(r.error, r.ok)
        assertEquals(1, r.removed)
        assertFalse(dst.lists["t"]!!.any { it.id == "d50" })
        assertEquals(99, dst.lists["t"]!!.size)
    }

    @Test fun theToleranceGrowsWithThePlaylist() {
        assertFalse(answeredShort(100, 98))
        assertTrue(answeredShort(100, 97))
        assertFalse(answeredShort(1000, 980))
        assertTrue(answeredShort(1000, 979))
        assertFalse(answeredShort(-1, 0))
    }
}

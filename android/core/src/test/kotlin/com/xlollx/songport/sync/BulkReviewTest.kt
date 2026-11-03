package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.MatchReview
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

/** The review screen acting on several tracks at once: keep, discard (issue #26), ignore. */
class BulkReviewTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private val a = Track(id = "a", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val b = Track(id = "b", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    private val c = Track(id = "c", title = "Paper Comet", artists = listOf("Iron Finch"), durationMs = 190_000)
    private val da = Track(id = "da", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val db = Track(id = "db", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    private val dc = Track(id = "dc", title = "Paper Comet", artists = listOf("Iron Finch"), durationMs = 190_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(a, b, c)
        dst.catalogue += listOf(da, db, dc)
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))

    /** A first run, then its report made to list [reviews] as uncertain. */
    private fun syncedWith(vararg reviews: MatchReview): String = runBlocking {
        env.store.upsertJob(job)
        val report = engine.run(job)
        env.store.updateReport(report.id) { it.copy(reviewTracks = reviews.toList()) }
        report.id
    }

    @Test fun discardedMatchesLeaveTheTargetAndStayOut() = runBlocking {
        val ra = MatchReview(a, da, 0.7)
        val rb = MatchReview(b, db, 0.7)
        val rc = MatchReview(c, dc, 0.7)
        val id = syncedWith(ra, rb, rc)
        assertEquals(listOf("da", "db", "dc"), dst.lists["t"]!!.map { it.id })

        val removed = engine.discardMatches(job, id, listOf(ra, rb))
        assertEquals(2, removed)
        assertEquals(listOf("dc"), dst.lists["t"]!!.map { it.id })
        assertEquals(listOf(rc), env.store.report(id)!!.reviewTracks)

        // The next run does not bring them back.
        engine.run(env.store.job("j")!!)
        assertEquals(listOf("dc"), dst.lists["t"]!!.map { it.id })
    }

    @Test fun aTargetTrackAnotherSourceTrackStillMatchesStays() = runBlocking {
        // b and c both landed on db: discarding b's match must not take db away from c.
        val rb = MatchReview(b, db, 0.6)
        val id = syncedWith(rb)
        env.store.putMatches(mapOf(com.xlollx.songport.data.FileStore.cacheKey("msrc", "c", "mdst") to "db"))
        assertEquals(0, engine.discardMatches(job, id, listOf(rb)))
        assertTrue("db" in dst.lists["t"]!!.map { it.id })
        assertTrue(env.store.report(id)!!.reviewTracks.isEmpty())
    }

    @Test fun keptAndIgnoredInOneGo() = runBlocking {
        val ra = MatchReview(a, da, 0.7)
        val rb = MatchReview(b, db, 0.7)
        val id = syncedWith(ra, rb)
        engine.confirmMatches(id, listOf(ra, rb))
        assertTrue(env.store.report(id)!!.reviewTracks.isEmpty())
        assertEquals(listOf("da", "db", "dc"), dst.lists["t"]!!.map { it.id })

        env.store.updateReport(id) { it.copy(unmatchedTracks = listOf(a, b, c), unmatched = listOf(a, b, c).map(Track::toString)) }
        engine.ignore(job, id, listOf(a, c))
        val r = env.store.report(id)!!
        assertEquals(listOf(b), r.unmatchedTracks)
        assertEquals(listOf(b.toString()), r.unmatched)
        assertEquals(setOf("a", "c"), env.store.job("j")!!.ignoredSourceIds.toSet())
    }
}

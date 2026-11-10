package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The review's choices shared with the user's server: kept with their time, merged newest-first, done when taken in. */
class ReviewDecisionsTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private val a = Track(id = "a", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val b = Track(id = "b", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    private val c = Track(id = "c", title = "Paper Comet", artists = listOf("Iron Finch"), durationMs = 190_000)
    private val da = Track(id = "da", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val db = Track(id = "db", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)

    private val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(a, b, c)
        dst.catalogue += listOf(da, db)
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun d(kind: DecisionKind, at: Long, track: String? = null) = ReviewDecision(kind, track, at)

    @Test fun theNewestChoiceWinsWhicheverSideIsGivenFirst() {
        val phone = mapOf("a" to d(DecisionKind.IGNORE, 10), "b" to d(DecisionKind.MATCH, 30, "db"), "c" to d(DecisionKind.KEEP, 5, "dc"))
        val server = mapOf("a" to d(DecisionKind.MATCH, 20, "da"), "b" to d(DecisionKind.IGNORE, 25), "e" to d(DecisionKind.IGNORE, 1))
        val merged = ReviewDecisions.merge(phone, server)
        assertEquals(merged, ReviewDecisions.merge(server, phone))
        assertEquals(d(DecisionKind.MATCH, 20, "da"), merged["a"])
        assertEquals(d(DecisionKind.MATCH, 30, "db"), merged["b"])
        assertEquals(setOf("a", "b", "c", "e"), merged.keys)
        // Made at the same instant: a fixed order, the same on both sides.
        val x = mapOf("a" to d(DecisionKind.IGNORE, 7))
        val y = mapOf("a" to d(DecisionKind.MATCH, 7, "da"))
        assertEquals(ReviewDecisions.merge(x, y), ReviewDecisions.merge(y, x))
    }

    @Test fun theWireFormatGoesBothWaysAndOddEntriesAreRefused() {
        val mine = mapOf("a" to d(DecisionKind.IGNORE, 10), "b" to d(DecisionKind.KEEP, 20, "db"))
        assertEquals(mine, ReviewDecisions.parse(ReviewDecisions.toJson(mine), now = 1_000))
        assertEquals("""[{"source":"a","kind":"ignore","at":10},{"source":"b","kind":"keep","track":"db","at":20}]""", ReviewDecisions.toJson(mine).toString())
        // A time ahead of this clock counts as now: it cannot win for ever.
        assertEquals(500L, ReviewDecisions.parse(parseJson("""[{"source":"a","kind":"ignore","at":9999999999999}]"""), now = 500)["a"]!!.at)
        for (bad in listOf(
            """{"source":"a"}""",
            """[{"source":"a","kind":"delete","at":1}]""",
            """[{"source":"a","kind":"match","at":1}]""",
            """[{"source":"","kind":"ignore","at":1}]""",
            """[{"source":"a","kind":"ignore","at":"1"}]""",
            """[{"source":"a","kind":"ignore","at":0}]""",
            """[{"source":"a\nb","kind":"ignore","at":1}]""",
            """[{"source":${"\"" + "x".repeat(600) + "\""},"kind":"ignore","at":1}]""",
        )) {
            try { ReviewDecisions.parse(parseJson(bad), now = 1_000); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun theReviewKeepsEachChoiceWithItsTime() = runBlocking {
        env.store.upsertJob(job)
        val report = engine.run(job)
        val ra = MatchReview(a, da, 0.7)
        val rb = MatchReview(b, db, 0.7)
        env.store.updateReport(report.id) { it.copy(reviewTracks = listOf(ra, rb), unmatchedTracks = listOf(c)) }
        engine.confirmMatches(report.id, listOf(ra))
        engine.discardMatches(job, report.id, listOf(rb))
        engine.ignore(job, report.id, listOf(c))
        val mine = ReviewDecisions.ofSync(env.store.data, job)
        assertEquals(DecisionKind.KEEP, mine["a"]!!.kind)
        assertEquals("da", mine["a"]!!.track)
        // Discarded: ignored from now on, and not "kept" on the way out of the list.
        assertEquals(DecisionKind.IGNORE, mine["b"]!!.kind)
        assertEquals(DecisionKind.IGNORE, mine["c"]!!.kind)
        assertTrue(mine.values.all { it.at > 0 })
        // Another pair of services has none of them.
        assertTrue(ReviewDecisions.ofSync(env.store.data, job.copy(target = PlaylistRef("other", "t", "t"))).isEmpty())
    }

    @Test fun aChoiceTakenInIsDoneAsTheReviewWouldAndOnlyIfNewer() {
        env.store.upsertJob(job.copy(lastReportId = "r", ignoredSourceIds = listOf("b")))
        env.store.update { it.copy(reports = listOf(SyncReport(id = "r", jobId = "j", jobName = "j", startedEpoch = 1, durationMs = 1, sourceCount = 3, unmatched = listOf(c.toString()), unmatchedTracks = listOf(c), reviewTracks = listOf(MatchReview(a, da, 0.7))))) }
        env.store.putIgnored("msrc", "b", "mdst")
        env.store.recordDecisions(mapOf(FileStore.cacheKey("msrc", "b", "mdst") to d(DecisionKind.IGNORE, 100)))
        val taken = ReviewDecisions.apply(env.store, "j", mapOf(
            "c" to d(DecisionKind.IGNORE, 200),
            "a" to d(DecisionKind.MATCH, 200, "da2"),
            // Newer than the ignore here: b is matched again.
            "b" to d(DecisionKind.MATCH, 150, "db"),
        ))
        assertEquals(3, taken)
        val s = env.store
        assertTrue(s.isIgnored("msrc", "c", "mdst"))
        assertEquals("da2", s.cachedMatch("msrc", "a", "mdst"))
        assertEquals("db", s.cachedMatch("msrc", "b", "mdst"))
        assertFalse(s.isIgnored("msrc", "b", "mdst"))
        assertEquals(listOf("c"), s.job("j")!!.ignoredSourceIds)
        val r = s.report("r")!!
        assertTrue(r.unmatchedTracks.isEmpty() && r.unmatched.isEmpty() && r.reviewTracks.isEmpty())
        // Older than what this side holds: nothing changes.
        assertEquals(0, ReviewDecisions.apply(s, "j", mapOf("c" to d(DecisionKind.MATCH, 150, "dc"))))
        assertTrue(s.isIgnored("msrc", "c", "mdst"))
        assertNull(s.cachedMatch("msrc", "c", "mdst"))
        // A sync that is not here: nothing at all.
        assertEquals(0, ReviewDecisions.apply(s, "nope", mapOf("x" to d(DecisionKind.IGNORE, 300))))
    }

    @Test fun twoSidesExchangingEndWithTheSameChoices() {
        val other = ContractEnv()
        env.store.upsertJob(job)
        other.store.upsertJob(job.copy(id = "s"))
        env.store.recordDecisions(mapOf(FileStore.cacheKey("msrc", "a", "mdst") to d(DecisionKind.IGNORE, 10), FileStore.cacheKey("msrc", "b", "mdst") to d(DecisionKind.KEEP, 40, "db")))
        other.store.recordDecisions(mapOf(FileStore.cacheKey("msrc", "a", "mdst") to d(DecisionKind.MATCH, 20, "da"), FileStore.cacheKey("msrc", "b", "mdst") to d(DecisionKind.IGNORE, 30)))
        // The phone sends its own, the server takes in what is newer and answers all of its own.
        ReviewDecisions.apply(other.store, "s", ReviewDecisions.ofSync(env.store.data, job))
        ReviewDecisions.apply(env.store, "j", ReviewDecisions.ofSync(other.store.data, other.store.job("s")!!))
        assertEquals(ReviewDecisions.ofSync(env.store.data, job), ReviewDecisions.ofSync(other.store.data, other.store.job("s")!!))
        assertEquals("da", env.store.cachedMatch("msrc", "a", "mdst"))
        assertEquals("db", other.store.cachedMatch("msrc", "b", "mdst"))
        assertFalse(other.store.isIgnored("msrc", "b", "mdst"))
    }

    @Test fun aStoreFromBeforeLoadsAndTheNewFieldsComeBack() {
        val dir = kotlin.io.path.createTempDirectory("store").toFile()
        try {
            // Written by 1.18: none of the server's fields.
            java.io.File(dir, "store.json").writeText("""{"jobs":[{"id":"j","name":"Gym","source":{"provider":"spotify","playlistId":"p"},"target":{"provider":"tidal","playlistId":"t"}}],"matchCache":{"spotify:a>tidal":"da"}}""")
            val old = FileStore(dir)
            assertEquals("Gym", old.job("j")!!.name)
            assertEquals("da", old.data.matchCache["spotify:a>tidal"])
            assertTrue(old.data.handedOver.isEmpty() && old.data.serverPairs.isEmpty() && old.data.reviewDecisions.isEmpty())
            old.update { it.copy(handedOver = mapOf("j" to HandedOver("phone-j", 7, wasEnabled = false)), serverPairs = mapOf("k" to "s2")) }
            old.recordDecisions(mapOf("spotify:a>tidal" to d(DecisionKind.MATCH, 9, "da")))
            old.flush()
            val again = FileStore(dir)
            assertEquals(HandedOver("phone-j", 7, wasEnabled = false), again.data.handedOver["j"])
            assertEquals("s2", again.data.serverPairs["k"])
            assertEquals(d(DecisionKind.MATCH, 9, "da"), again.data.reviewDecisions["spotify:a>tidal"])
            // The card deleted: nothing of it stays behind.
            again.deleteJob("j")
            assertNull(again.data.handedOver["j"])
        } finally {
            dir.deleteRecursively()
        }
    }
}

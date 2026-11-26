package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tools › Saved matches: what the caches remember, read as one list, and changed for every sync at once. */
class SavedMatchesTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService

    private val a = Track(id = "a", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val b = Track(id = "b", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    private val c = Track(id = "c", title = "Paper Comet", artists = listOf("Iron Finch"), durationMs = 190_000)
    private val da = Track(id = "da", title = "Amber Lantern", artists = listOf("Cobalt Raven"), durationMs = 200_000)
    private val db = Track(id = "db", title = "Velvet Nebula", artists = listOf("Quartz Willow"), durationMs = 180_000)
    private val db2 = Track(id = "db2", title = "Velvet Nebula (Live)", artists = listOf("Quartz Willow"), durationMs = 181_000)

    private val job = SyncJob(id = "j", name = "Road Trip", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"))
    private val other = SyncJob(id = "k", name = "Favourites", source = PlaylistRef("msrc", "q", "q"), target = PlaylistRef("mdst", "u", "u"))

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        src.lists["p"] = mutableListOf(a, b, c)
        src.lists["q"] = mutableListOf(b)
        dst.catalogue += listOf(da, db, db2)
        dst.lists["t"] = mutableListOf()
        dst.lists["u"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun entry(id: String) = SavedMatches.entries(env.store.data).single { it.sourceId == id }

    @Test fun aRunsMatchesMissesAndChoicesReadAsOneList() = runBlocking {
        env.store.upsertJob(job)
        SyncEngine(env).run(job)
        assertEquals(SavedMatches.Kind.AUTO, entry("a").kind)
        assertEquals("da", entry("a").chosenId)
        assertEquals(SavedMatches.Kind.NOT_FOUND, entry("c").kind)
        env.store.recordDecisions(mapOf(FileStore.cacheKey("msrc", "b", "mdst") to ReviewDecision(DecisionKind.KEEP, "db", 5)))
        assertEquals(SavedMatches.Kind.CHOSEN, entry("b").kind)
        env.store.putIgnored("msrc", "c", "mdst")
        assertEquals(SavedMatches.Kind.IGNORED, entry("c").kind)
        // One entry per song and service, also for an old key under a connector's own id.
        env.store.update { it.copy(matchCache = it.matchCache + ("msrc@2:a>mdst" to "da")) }
        assertEquals(setOf("msrc:a>mdst", "msrc@2:a>mdst"), entry("a").keys)
        assertEquals(3, SavedMatches.entries(env.store.data).size)
    }

    @Test fun anotherSongChosenHoldsForEverySyncBetweenTheTwoServices() = runBlocking {
        env.store.upsertJob(job)
        env.store.upsertJob(other.copy(ignoredSourceIds = listOf("b")))
        SyncEngine(env).run(job)
        SavedMatches.choose(env.store, entry("b"), "db2", now = 50)
        assertEquals(SavedMatches.Kind.CHOSEN, entry("b").kind)
        assertEquals("db2", env.store.cachedMatch("msrc", "b", "mdst"))
        assertTrue(env.store.hasDecision("msrc", "b", "mdst"))
        // The other sync had left it out: it takes it now, with the song chosen.
        assertTrue(env.store.job("k")!!.ignoredSourceIds.isEmpty())
        SyncEngine(env).run(env.store.job("k")!!)
        assertEquals(listOf("db2"), dst.lists["u"]!!.map { it.id })
        assertEquals(listOf("Road Trip", "Favourites"), SavedMatches.syncsBetween(env.store.data, entry("b")).map { it.name })
    }

    @Test fun notThereAndForgetChangeWhatTheNextSyncDoes() = runBlocking {
        env.store.upsertJob(job)
        SyncEngine(env).run(job)
        SavedMatches.notThere(env.store, entry("a"))
        assertTrue(env.store.isIgnored("msrc", "a", "mdst"))
        assertNull(env.store.cachedMatch("msrc", "a", "mdst"))
        assertEquals(SavedMatches.Kind.IGNORED, entry("a").kind)
        // Forgotten: nothing left of it, and the miss of c is searched again.
        SavedMatches.forget(env.store, entry("a"))
        SavedMatches.forget(env.store, entry("c"))
        assertTrue(SavedMatches.entries(env.store.data).none { it.sourceId == "a" || it.sourceId == "c" })
        assertFalse(env.store.isIgnored("msrc", "a", "mdst"))
        assertFalse(env.store.cachedMiss("msrc", "c", "mdst"))
        dst.catalogue += Track(id = "dc", title = "Paper Comet", artists = listOf("Iron Finch"), durationMs = 190_000)
        SyncEngine(env).run(job)
        assertEquals("dc", env.store.cachedMatch("msrc", "c", "mdst"))
    }

    @Test fun theReportsNameTheSongsAndTheSearchFindsThem() {
        env.store.upsertJob(job)
        env.store.update { it.copy(reports = listOf(SyncReport(id = "r", jobId = "j", jobName = "j", startedEpoch = 1, durationMs = 1,
            reviewTracks = listOf(MatchReview(b, db, 0.8)), unmatchedTracks = listOf(c), addedTracks = listOf(da)))) }
        val names = SavedMatches.names(env.store.data)
        assertEquals("Velvet Nebula", names[SavedMatches.nameKey("msrc", "b")]!!.title)
        assertEquals("db", names[SavedMatches.nameKey("mdst", "db")]!!.id)
        assertEquals("da", names[SavedMatches.nameKey("mdst#album", "da")]!!.id)
        val e = SavedMatches.Entry("msrc", "b", "mdst", SavedMatches.Kind.AUTO, "db", 0, setOf("msrc:b>mdst"))
        assertTrue(SavedMatches.matches(e, names, "quartz"))
        assertTrue(SavedMatches.matches(e, names, " velvet "))
        assertFalse(SavedMatches.matches(e, names, "comet"))
    }

    /** #95: a library imported anew; every match to it goes at once, the other services' stay, ignored songs stay ignored. */
    @Test fun forgetEveryMatchToOneService() {
        env.store.update { d ->
            d.copy(
                matchCache = mapOf(
                    "msrc:a>subsonic" to "n1", "msrc:b>subsonic#album" to "al1", "msrc:c>subsonic@2" to "n3", "msrc:a>mdst" to "da",
                ),
                autoMatches = mapOf("msrc:a>subsonic" to "${Matcher.REVISION}|n1"),
                missCache = mapOf("msrc:d>subsonic" to System.currentTimeMillis(), "msrc:d>mdst" to System.currentTimeMillis()),
                ignoredTracks = setOf("msrc:e>subsonic"),
                reviewDecisions = mapOf("msrc:a>subsonic" to ReviewDecision(DecisionKind.MATCH, "n1", 1), "msrc:e>subsonic" to ReviewDecision(DecisionKind.IGNORE, null, 1)),
                idChecks = mapOf("subsonic" to 1L, "mdst" to 1L),
            )
        }
        assertEquals(3, SavedMatches.countTo(env.store.data, "subsonic"))
        assertEquals(3, SavedMatches.forgetAllTo(env.store, "subsonic"))
        val d = env.store.data
        assertEquals(mapOf("msrc:a>mdst" to "da"), d.matchCache)
        assertTrue(d.autoMatches.isEmpty())
        assertEquals(setOf("msrc:d>mdst"), d.missCache.keys)
        assertEquals(setOf("msrc:e>subsonic"), d.ignoredTracks)
        assertEquals(setOf("msrc:e>subsonic"), d.reviewDecisions.keys)
        assertEquals(setOf("mdst"), d.idChecks.keys)
    }

    /** Several rows forgotten in one write, as each one's own Forget does. */
    @Test fun forgetSeveralAtOnce() {
        env.store.update { d ->
            d.copy(
                matchCache = mapOf("msrc:a>mdst" to "da", "msrc:b>mdst" to "db", "msrc:c>mdst" to "dc"),
                ignoredTracks = setOf("msrc:c>mdst"),
                jobs = listOf(job.copy(ignoredSourceIds = listOf("c"))),
            )
        }
        SavedMatches.forget(env.store, SavedMatches.entries(env.store.data).filter { it.sourceId != "b" })
        assertEquals(mapOf("msrc:b>mdst" to "db"), env.store.data.matchCache)
        assertTrue(env.store.data.ignoredTracks.isEmpty())
        assertTrue(env.store.job("j")!!.ignoredSourceIds.isEmpty())
    }
}

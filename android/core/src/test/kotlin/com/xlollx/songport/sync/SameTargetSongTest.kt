package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #94: two different songs of the source found the same video on YouTube Music (it has a song
 * as several videos), and the second was left out without a word (a Blend of 50 became 42). The
 * second takes the next best video nobody holds, or goes to the review. The same song twice in the
 * source (one song from two albums) still shares one, as before.
 */
class SameTargetSongTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var ytm: MemoryService
    private lateinit var engine: SyncEngine

    private val studio = Track("s1", "Hello", listOf("Adele"), durationMs = 295_000)
    private val live = Track("s2", "Hello (Live)", listOf("Adele"), durationMs = 300_000)
    private val audio = Track("v1", "Hello", listOf("Adele"), durationMs = 295_000)
    private val video = Track("v2", "Hello (Official Video)", listOf("Adele"), durationMs = 290_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        // A catalogue that answers with every video of the title, as YouTube Music does.
        ytm = object : MemoryService("ytm") {
            override suspend fun search(ctx: Env, track: Track): List<Track> {
                val words = Matcher.normalizeTitle(track.title).split(' ').toSet()
                return catalogue.filter { c -> Matcher.normalizeTitle(c.title).split(' ').any { it in words } }
            }
        }
        Providers.testDoubles = listOf(src, ytm)
        engine = SyncEngine(env)
        ytm.lists["t"] = mutableListOf()
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(): SyncJob =
        SyncJob(id = "blend", name = "Blend", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("ytm", "t", "t"), mirrorRemovals = true)
            .also { env.store.upsertJob(it) }

    @Test fun theSecondSongTakesTheNextVideo() = runBlocking {
        ytm.catalogue += listOf(audio, video)
        src.lists["p"] = mutableListOf(studio, live)
        // The live take scores the two videos alike, and the first is the one the studio take holds.
        assertEquals(audio.id, Matcher.best(live, ytm.catalogue)!!.id)
        val j = job()
        val r = engine.run(j)
        assertEquals(listOf("v1", "v2"), ytm.lists["t"]!!.map { it.id })
        assertEquals(2, r.added)
        assertTrue(r.unmatchedTracks.isEmpty())
        assertEquals("v2", env.store.cachedMatch("msrc", "s2", "ytm"))
        // The next run finds both present: nothing added, nothing removed.
        val again = engine.run(j)
        assertEquals(0, again.added)
        assertEquals(0, again.removed)
        assertEquals(2, ytm.lists["t"]!!.size)
    }

    /** Remembered from an older version: both songs saved as the same video. The better match keeps it. */
    @Test fun twoSongsRememberedOnOneVideoAreSetApart() = runBlocking {
        ytm.catalogue += listOf(audio, video)
        src.lists["p"] = mutableListOf(live, studio)
        ytm.lists["t"] = mutableListOf(audio)
        env.store.putMatches(mapOf(FileStore.cacheKey("msrc", "s1", "ytm") to "v1", FileStore.cacheKey("msrc", "s2", "ytm") to "v1"), auto = true)
        val r = engine.run(job())
        assertEquals(1, r.added)
        assertEquals(listOf("v1", "v2"), ytm.lists["t"]!!.map { it.id })
        assertEquals("v1", env.store.cachedMatch("msrc", "s1", "ytm"))
        assertEquals("v2", env.store.cachedMatch("msrc", "s2", "ytm"))
    }

    @Test fun withNoOtherVideoItGoesToTheReview() = runBlocking {
        ytm.catalogue += audio
        src.lists["p"] = mutableListOf(studio, live)
        val plan = engine.plan(job(), createTarget = false, interactive = true)
        assertEquals(listOf("v1"), plan.toAdd.map { it.id })
        assertTrue(plan.unmatched.isEmpty())
        assertEquals(listOf("s2" to "v1"), plan.uncertain.map { it.source.id to it.chosen.id })
    }

    /** A choice of the user is never taken away, whatever scores better. */
    @Test fun theUsersChoiceKeepsItsVideo() = runBlocking {
        ytm.catalogue += listOf(audio, video)
        src.lists["p"] = mutableListOf(studio, live)
        ytm.lists["t"] = mutableListOf(audio)
        val j = job()
        env.store.putMatches(mapOf(FileStore.cacheKey("msrc", "s2", "ytm") to "v1"))
        env.store.update { d -> d.copy(reviewDecisions = d.reviewDecisions + (FileStore.cacheKey("msrc", "s2", "ytm") to ReviewDecision(DecisionKind.MATCH, "v1", 1L))) }
        engine.run(j)
        assertEquals("v1", env.store.cachedMatch("msrc", "s2", "ytm"))
        assertEquals("v2", env.store.cachedMatch("msrc", "s1", "ytm"))
    }

    /** Echo-Phyber's daylist: "Dundy Lion" twice, from two albums. One song: one video, as before. */
    @Test fun theSameSongTwiceSharesItsVideo() = runBlocking {
        val a = Track("d1", "Dundy Lion", listOf("Markus Kienzl"), album = "Smoke", durationMs = 240_000, isrc = "ATA111111111")
        val b = Track("d2", "Dundy Lion", listOf("Markus Kienzl"), album = "Best Of", durationMs = 240_000, isrc = "ATA222222222")
        ytm.catalogue += listOf(Track("y1", "Dundy Lion", listOf("Markus Kienzl"), durationMs = 240_000), Track("y2", "Dundy Lion (Official Video)", listOf("Markus Kienzl"), durationMs = 244_000))
        src.lists["p"] = mutableListOf(a, b)
        val plan = engine.plan(job(), createTarget = false, interactive = true)
        assertEquals(listOf("y1"), plan.toAdd.map { it.id })
        assertTrue(plan.uncertain.isEmpty())
    }
}

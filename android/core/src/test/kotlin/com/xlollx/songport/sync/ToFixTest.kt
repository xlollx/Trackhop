package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Saved matches › To fix (#69): one list across the syncs, a choice made once for all of them, "Later" and "Ignore". */
class ToFixTest {
    /** A target whose playlists in [refusing] take no additions: one sync's playlist failing while the others work. */
    private class Flaky(id: String) : MemoryService(id) {
        val refusing = HashSet<String>()
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
            if (playlistId in refusing) throw ProviderException("$serviceId: refused")
            super.addTracks(ctx, playlistId, tracks)
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: Flaky
    private lateinit var engine: SyncEngine

    private val low = Track(id = "low", title = "Low Tide (Live at Kelso Hall)", artists = listOf("Marlow & Finch"), durationMs = 200_000)
    private val glass = Track(id = "glass", title = "Glasshouse", artists = listOf("Ines Varga"), durationMs = 180_000)
    private val sodium = Track(id = "sodium", title = "Sodium Lights", artists = listOf("North Pier Club"), durationMs = 190_000)
    private val dLow = Track(id = "d-low", title = "Low Tide", artists = listOf("Marlow & Finch"), durationMs = 200_000)
    private val dGlass = Track(id = "d-glass", title = "Glass House", artists = listOf("Ines Varga"), durationMs = 180_000)
    private val dSodium = Track(id = "d-sodium", title = "Sodium Lights (Radio Edit)", artists = listOf("North Pier Club"), durationMs = 185_000)

    private val night = SyncJob(id = "night", name = "Night Drive", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"), lastReportId = "r-night")
    private val kitchen = SyncJob(id = "kitchen", name = "Kitchen Radio", source = PlaylistRef("msrc", "q", "q"), target = PlaylistRef("mdst", "u", "u"), lastReportId = "r-kitchen")
    /** To another service: the same source track there is another match. */
    private val elsewhere = SyncJob(id = "else", name = "Garage Days", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mother", "v", "v"), lastReportId = "r-else")

    private fun report(job: SyncJob, unmatched: List<Track> = emptyList(), reviews: List<MatchReview> = emptyList(), suggestions: Map<String, Track> = emptyMap(), absent: List<String> = emptyList(), partial: Boolean = false) =
        SyncReport(
            id = job.lastReportId!!, jobId = job.id, jobName = job.name, startedEpoch = 1, durationMs = 1, unmatched = unmatched.map { it.toString() },
            unmatchedTracks = unmatched, reviewTracks = reviews, suggestions = suggestions, absent = absent, partial = partial,
        )

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = Flaky("mdst")
        Providers.testDoubles = listOf(src, dst, MemoryService("mother"))
        dst.lists["t"] = mutableListOf()
        dst.lists["u"] = mutableListOf()
        engine = SyncEngine(env)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    /** Night Drive: Low Tide (a candidate), Glasshouse (matched loosely), Sodium Lights (nothing). Kitchen Radio: Low Tide too. */
    private fun seed() {
        dst.lists["t"] = mutableListOf(dGlass)
        env.store.update { d ->
            d.copy(
                jobs = listOf(night, kitchen, elsewhere),
                reports = listOf(
                    report(night, unmatched = listOf(low, sodium), reviews = listOf(MatchReview(glass, dGlass, 0.91)), suggestions = mapOf("low" to dLow), absent = listOf("sodium")),
                    report(kitchen, unmatched = listOf(low), suggestions = mapOf("low" to dLow)),
                    report(elsewhere, unmatched = listOf(low)),
                ),
                matchCache = mapOf(FileStore.cacheKey("msrc", "glass", "mdst") to "d-glass"),
            )
        }
    }

    private fun items() = ToFix.items(env.store.data)
    private fun item(id: String, target: String = "mdst") = items().single { it.track.id == id && it.target == target }

    @Test fun allSyncsReadAsOneListAndASharedTrackIsOneRow() {
        seed()
        assertEquals(4, ToFix.count(env.store.data))
        val shared = item("low")
        assertEquals(listOf("Night Drive", "Kitchen Radio"), shared.places.map { it.jobName })
        assertEquals(ToFix.Kind.NOT_FOUND, shared.kind)
        assertEquals("d-low", shared.suggestion?.id)
        assertTrue("a score for the candidate: ${shared.score}", shared.score > 0.0 && shared.score < 1.0)
        // The same source track to another service is another row: another match.
        assertEquals(listOf("Garage Days"), item("low", "mother").places.map { it.jobName })
        assertNull(item("low", "mother").suggestion)
        val loose = item("glass")
        assertEquals(ToFix.Kind.UNCERTAIN, loose.kind)
        assertEquals("d-glass", loose.suggestion?.id)
        assertEquals(0.91, loose.score, 0.0001)
        assertTrue(item("sodium").absent)
        assertFalse(shared.absent)
    }

    @Test fun groupsBySyncAndByServiceHoldEveryRowOnce() {
        seed()
        val bySync = ToFix.bySync(items())
        assertEquals(listOf("Night Drive" to 3, "Garage Days" to 1), bySync.map { it.name to it.items.size })
        val byService = ToFix.byService(items()) { it.uppercase() }
        assertEquals(listOf("MDST" to 3, "MOTHER" to 1), byService.map { it.name to it.items.size })
        assertEquals(ToFix.count(env.store.data), bySync.sumOf { it.items.size })
    }

    @Test fun aRunInProgressAndAOneOffTransferAreLeftOut() {
        val once = SyncJob(id = "once", name = "Copy", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"), lastReportId = "r-once", oneOff = true)
        env.store.update { d ->
            d.copy(jobs = listOf(night, once), reports = listOf(report(night, unmatched = listOf(low), partial = true), report(once, unmatched = listOf(sodium))))
        }
        assertEquals(emptyList<ToFix.Item>(), items())
    }

    @Test fun acceptingOnceFixesEverySyncWithTheTrack() = runBlocking {
        seed()
        val outcome = engine.fixEverywhere(item("low"), dLow)
        assertEquals(listOf("Night Drive", "Kitchen Radio"), outcome.syncs)
        assertEquals(emptyList<String>(), outcome.failed)
        assertEquals("d-low", env.store.cachedMatch("msrc", "low", "mdst"))
        assertEquals(DecisionKind.MATCH, env.store.data.reviewDecisions.getValue(FileStore.cacheKey("msrc", "low", "mdst")).kind)
        assertEquals(listOf("d-glass", "d-low"), dst.lists.getValue("t").map { it.id })
        assertEquals(listOf("d-low"), dst.lists.getValue("u").map { it.id })
        // Gone from both syncs' lists; the row to the other service stays.
        assertEquals(listOf("mother"), items().filter { it.track.id == "low" }.map { it.target })
        assertFalse(env.store.report("r-kitchen")!!.let { r -> r.unmatchedTracks.any { it.id == "low" } || "low" in r.suggestions || r.unmatched.isNotEmpty() })
        // In the saved matches as chosen by hand.
        assertEquals(SavedMatches.Kind.CHOSEN, SavedMatches.entries(env.store.data).single { it.sourceId == "low" && it.target == "mdst" }.kind)
    }

    @Test fun aSecondAcceptAddsNothingTwice() = runBlocking {
        seed()
        val row = item("low")
        engine.fixEverywhere(row, dLow)
        // The same row again (a double tap, a retry after the screen was made anew).
        val again = engine.fixEverywhere(row, dLow)
        assertEquals(emptyList<String>(), again.syncs)
        assertEquals(1, dst.lists.getValue("t").count { it.id == "d-low" })
        assertEquals(1, dst.lists.getValue("u").count { it.id == "d-low" })
    }

    @Test fun twoSyncsWritingToOnePlaylistAddTheSongOnce() = runBlocking {
        val twin = kitchen.copy(target = night.target)
        env.store.update { d ->
            d.copy(jobs = listOf(night, twin), reports = listOf(report(night, unmatched = listOf(low)), report(twin, unmatched = listOf(low))))
        }
        val outcome = engine.fixEverywhere(item("low"), dLow)
        assertEquals(2, outcome.syncs.size)
        assertEquals(listOf("d-low"), dst.lists.getValue("t").map { it.id })
    }

    @Test fun theMatchIsSavedEvenWhenAPlaylistRefusesAndTheNextRunAddsIt() = runBlocking {
        seed()
        src.lists["q"] = mutableListOf(low)
        dst.catalogue += dLow
        dst.refusing += "u"
        val outcome = engine.fixEverywhere(item("low"), dLow)
        assertEquals(listOf("Kitchen Radio"), outcome.failed)
        // Saved, and out of the list: nothing to do again by hand.
        assertEquals("d-low", env.store.cachedMatch("msrc", "low", "mdst"))
        assertTrue(items().none { it.track.id == "low" && it.target == "mdst" })
        assertEquals(listOf("d-glass", "d-low"), dst.lists.getValue("t").map { it.id })
        assertEquals(emptyList<String>(), dst.lists.getValue("u").map { it.id })
        // The sync's next run puts it there, once.
        dst.refusing.clear()
        val run = engine.run(env.store.job("kitchen")!!)
        assertEquals("error=${run.error} notes=${run.notes}", listOf("d-low"), dst.lists.getValue("u").map { it.id })
        engine.run(env.store.job("kitchen")!!)
        assertEquals(listOf("d-low"), dst.lists.getValue("u").map { it.id })
    }

    @Test fun acceptingAnUncertainMatchKeepsItWhereItIs() = runBlocking {
        seed()
        val outcome = engine.fixEverywhere(item("glass"), dGlass)
        assertEquals(emptyList<String>(), outcome.failed)
        assertEquals(listOf("d-glass"), dst.lists.getValue("t").map { it.id })
        assertEquals(0, dst.addCalls)
        assertEquals(DecisionKind.KEEP, env.store.data.reviewDecisions.getValue(FileStore.cacheKey("msrc", "glass", "mdst")).kind)
        assertTrue(env.store.report("r-night")!!.reviewTracks.isEmpty())
    }

    @Test fun anotherSongForAnUncertainMatchTakesItsPlace() = runBlocking {
        seed()
        val other = Track(id = "d-glass2", title = "Glasshouse", artists = listOf("Ines Varga"))
        engine.fixEverywhere(item("glass"), other)
        assertEquals(listOf("d-glass2"), dst.lists.getValue("t").map { it.id })
        assertEquals("d-glass2", env.store.cachedMatch("msrc", "glass", "mdst"))
        assertEquals(DecisionKind.MATCH, env.store.data.reviewDecisions.getValue(FileStore.cacheKey("msrc", "glass", "mdst")).kind)
    }

    @Test fun anotherSongKeepsTheOldOneWhenAnotherSourceTrackIsMatchedToIt() = runBlocking {
        seed()
        // A second source track (the studio take) is matched to the same song on the target.
        env.store.update { d -> d.copy(matchCache = d.matchCache + (FileStore.cacheKey("msrc", "glass-studio", "mdst") to "d-glass")) }
        val other = Track(id = "d-glass2", title = "Glasshouse", artists = listOf("Ines Varga"))
        engine.fixEverywhere(item("glass"), other)
        assertEquals(listOf("d-glass", "d-glass2"), dst.lists.getValue("t").map { it.id })
        assertEquals("d-glass2", env.store.cachedMatch("msrc", "glass", "mdst"))
    }

    @Test fun laterHidesARowUntilTheNextRunOfItsSync() = runBlocking {
        seed()
        src.lists["q"] = mutableListOf(low)
        ToFix.later(env.store, item("low"))
        assertTrue(items().none { it.track.id == "low" && it.target == "mdst" })
        assertEquals(3, ToFix.count(env.store.data))
        // Nothing else was written: no match, no choice.
        assertNull(env.store.cachedMatch("msrc", "low", "mdst"))
        assertTrue(env.store.data.reviewDecisions.isEmpty())
        // Kitchen Radio runs again and still does not find it: back in the list, from that sync only.
        engine.run(env.store.job("kitchen")!!)
        assertEquals(listOf("Kitchen Radio"), item("low").places.map { it.jobName })
        // What was set aside for a report that is gone is forgotten at the next "Later".
        ToFix.later(env.store, item("sodium"))
        assertEquals(setOf("r-night"), env.store.data.toFixLater.keys)
    }

    @Test fun laterCanBeTakenBack() {
        seed()
        val row = item("low")
        ToFix.later(env.store, row)
        ToFix.undoLater(env.store, row)
        assertEquals(listOf("Night Drive", "Kitchen Radio"), item("low").places.map { it.jobName })
        assertTrue(env.store.data.toFixLater.isEmpty())
    }

    @Test fun aChoiceAlsoReachesTheSyncsWhereTheRowWasSetAside() = runBlocking {
        seed()
        // Set aside, then Kitchen Radio's row is back after a run of it; the choice made there reaches Night Drive too.
        ToFix.later(env.store, item("low"))
        env.store.update { d -> d.copy(toFixLater = d.toFixLater - "r-kitchen") }
        val row = item("low")
        assertEquals(listOf("Kitchen Radio"), row.places.map { it.jobName })
        val outcome = engine.fixEverywhere(row, dLow)
        assertEquals(listOf("Night Drive", "Kitchen Radio"), outcome.syncs)
        assertTrue(dst.lists.getValue("t").any { it.id == "d-low" })
        assertTrue(env.store.data.toFixLater.isEmpty())
        assertTrue(ToFix.items(env.store.data, withLater = true).none { it.track.id == "low" && it.target == "mdst" })
    }

    @Test fun ignoringOnceKeepsTheTrackOutOfEverySync() = runBlocking {
        seed()
        src.lists["q"] = mutableListOf(low)
        dst.catalogue += Track(id = "d-exact", title = low.title, artists = low.artists, durationMs = low.durationMs)
        val outcome = engine.ignoreEverywhere(item("low"))
        assertEquals(listOf("Night Drive", "Kitchen Radio"), outcome.syncs)
        assertTrue(env.store.isIgnored("msrc", "low", "mdst"))
        assertFalse(env.store.isIgnored("msrc", "low", "mother"))
        assertTrue(listOf("night", "kitchen").all { "low" in env.store.job(it)!!.ignoredSourceIds })
        assertFalse("low" in env.store.job("else")!!.ignoredSourceIds)
        assertTrue(items().none { it.track.id == "low" && it.target == "mdst" })
        assertEquals(SavedMatches.Kind.IGNORED, SavedMatches.entries(env.store.data).single { it.sourceId == "low" && it.target == "mdst" }.kind)
        // A later run leaves it alone, though the service now has the very song.
        val run = engine.run(env.store.job("kitchen")!!)
        assertEquals("error=${run.error}", emptyList<String>(), dst.lists.getValue("u").map { it.id })
        assertTrue(run.unmatchedTracks.isEmpty())
    }

    @Test fun ignoringAnUncertainMatchTakesItOffThePlaylist() = runBlocking {
        seed()
        engine.ignoreEverywhere(item("glass"))
        assertEquals(emptyList<String>(), dst.lists.getValue("t").map { it.id })
        assertNull(env.store.cachedMatch("msrc", "glass", "mdst"))
        assertTrue(env.store.isIgnored("msrc", "glass", "mdst"))
    }

    @Test fun aChoiceAfterIgnoreTakesTheTrackBack() = runBlocking {
        seed()
        val row = item("low")
        engine.ignoreEverywhere(row)
        ToFix.settle(env.store, row, "d-low")
        assertFalse(env.store.isIgnored("msrc", "low", "mdst"))
        assertFalse("low" in env.store.job("night")!!.ignoredSourceIds)
        assertEquals("d-low", env.store.cachedMatch("msrc", "low", "mdst"))
    }
}

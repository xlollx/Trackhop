package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
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

/**
 * A copy that removes (Mirror): a target track close to a source track the search did not find is
 * not removed but asked about in the review; once then it is the match, as an unsure match found by a search is.
 */
class LookalikeRemovalTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private fun t(id: String, title: String, artist: String = "Band") = Track(id = id, title = title, artists = listOf(artist), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job() = SyncJob(
        id = "job-look", name = "test", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"), mirrorRemovals = true,
    ).also { env.store.upsertJob(it) }

    @Test fun aTargetTrackLikeAMissingSourceTrackGoesToTheReviewNotAway() = runBlocking {
        // "Yesterday" is on the target with the artist misspelt (too far to count as present), and the
        // service's search does not return it (not in the catalogue).
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("y", "Yesterday"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf(t("da", "Alpha"), t("dy", "Yesterday", "Bnad"), t("dz", "Something Else Entirely", "Other Act"))
        val j = job()
        val r = engine.run(j)
        assertEquals(r.notes.toString(), 1, r.removed)
        assertEquals(listOf("da", "dy"), dst.lists["t"]!!.map { it.id })
        assertEquals(listOf("y" to "dy"), r.reviewTracks.map { it.source.id to it.chosen.id })
        assertTrue(r.unmatchedTracks.none { it.id == "y" })
        assertTrue(r.notes.toString(), r.notes.any { it == "note_removals_similar_review(1)" })

        // Remembered as the match, as an unsure match found by a search is: asked once, never removed.
        val again = engine.run(j)
        assertTrue(again.reviewTracks.isEmpty())
        assertEquals(0, again.removed)
        assertEquals(listOf("da", "dy"), dst.lists["t"]!!.map { it.id })
    }

    @Test fun thePreviewDoesNotRememberIt() = runBlocking {
        src.lists["p"] = mutableListOf(t("y", "Yesterday"))
        dst.lists["t"] = mutableListOf(t("dy", "Yesterday", "Bnad"))
        val j = job()
        val plan = engine.plan(j, createTarget = false, interactive = true)
        assertTrue(plan.toRemove.isEmpty())
        assertEquals(listOf("dy"), plan.uncertain.map { it.chosen.id })
        assertTrue(env.store.data.matchCache.isEmpty())
    }

    @Test fun eachSourceTrackHoldsOneTargetTrackAtMost() {
        val got = engine.similarToUnmatched(
            listOf(t("d1", "Yesterday"), t("d2", "Yesterday - Live")),
            listOf(t("y", "Yesterday")),
            com.xlollx.songport.model.MatchPolicy(),
        )
        assertEquals(listOf("d1"), got.keys.map { it.id })
    }

    @Test fun unrelatedTracksAreNotHeld() {
        val got = engine.similarToUnmatched(listOf(t("d", "Hello", "Adele")), listOf(t("y", "Yesterday", "The Beatles")), com.xlollx.songport.model.MatchPolicy())
        assertTrue(got.isEmpty())
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The guards around Mirror removals: the cap on large removals and the preview's confirmation that
 * lifts it once, the two-way pair that never removes, and the preview's unsure matches, which the
 * run reviews again unless the preview settled them. Two services in memory; notes read by name.
 */
class RemovalSafetyTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(mirror: Boolean = true, linked: String? = null, follows: Boolean = false): SyncJob =
        SyncJob(
            id = "job-" + System.nanoTime(), name = "test",
            source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"),
            mirrorRemovals = mirror, linkedJobId = linked, followsChanges = follows,
        ).also { env.store.upsertJob(it) }

    /** The source lists 2 of the target's 20 tracks: 18 removals, held back by the cap. */
    private fun largeRemoval() {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = (listOf(t("da", "Alpha"), t("db", "Beta")) + (1..18).map { t("dx$it", "Extra $it") }).toMutableList()
    }

    @Test fun largeRemovalsAreHeldBackAndThePreviewAsksForConfirmation() = runBlocking {
        largeRemoval()
        val j = job()
        val r = engine.run(j)
        assertEquals(r.notes.toString(), 0, r.removed)
        assertEquals(18, r.heldBack)
        assertTrue(r.notes.toString(), r.notes.any { it == "note_removals_capped(18,20)" })
        assertEquals(20, dst.lists["t"]!!.size)
        val plan = engine.plan(j, createTarget = false, interactive = true)
        assertEquals(18, plan.toRemove.size)
        assertTrue(plan.removalsNeedConfirmation)
        assertEquals(0, plan.heldBackRemovals)
    }

    /**
     * The owner's case: Liked songs (3 tracks, one not found) into a Liked songs that holds hundreds,
     * Mirror on. The hold is in the report the card reads, next to the not found, and the note speaks
     * of unliking.
     */
    @Test fun likedIntoALargeLikedHoldsTheUnlikesBackNextToANotFound() = runBlocking {
        val liked = MusicProvider.LIKED_ID
        src.lists[liked] = mutableListOf(t("a", "Alpha"), t("b", "Beta"), t("c", "Gamma"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists[liked] = (listOf(t("da", "Alpha")) + (1..300).map { t("dx$it", "Extra $it") }).toMutableList()
        val j = SyncJob(
            id = "liked", name = "Liked songs", source = PlaylistRef("msrc", liked, "Liked songs"),
            target = PlaylistRef("mdst", liked, "Liked songs"), mirrorRemovals = true,
        ).also { env.store.upsertJob(it) }
        val r = engine.run(j)
        assertTrue(r.ok)
        assertEquals(1, r.unmatchedTracks.size)
        assertEquals(1, r.added)
        assertEquals(0, r.removed)
        assertEquals(300, r.heldBack)
        assertTrue(r.notes.toString(), r.notes.any { it == "note_unlikes_capped(300,301)" })
        val stored = env.store.report(env.store.job(j.id)!!.lastReportId!!)!!
        assertEquals(300, stored.heldBack)
        assertEquals(302, dst.lists[liked]!!.size)
    }

    @Test fun aSmallRemovalNeedsNoConfirmation() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        dst.lists["t"] = mutableListOf(t("da", "Alpha"), t("dx", "Extra"))
        val j = job()
        assertFalse(engine.plan(j, createTarget = false, interactive = true).removalsNeedConfirmation)
        val r = engine.run(j)
        assertEquals(1, r.removed)
        assertEquals(0, r.heldBack)
    }

    @Test fun theConfirmationLiftsTheCapForOneRun() = runBlocking {
        largeRemoval()
        val j = job()
        val plan = engine.plan(j, createTarget = false, interactive = true)
        env.store.confirmRemovals(j.id, plan.toRemove.map { it.id })
        val r = engine.run(j)
        assertEquals(r.notes.toString(), 18, r.removed)
        assertEquals(0, r.heldBack)
        assertEquals(listOf("da", "db"), dst.lists["t"]!!.map { it.id })
        assertNull("used once, then gone", env.store.data.removalConfirmations[j.id])
    }

    @Test fun aConfirmationThatDoesNotCoverEveryRemovalIsDropped() = runBlocking {
        largeRemoval()
        val j = job()
        // The user saw 17 of them; one more turned up since.
        env.store.confirmRemovals(j.id, (1..17).map { "dx$it" })
        val r = engine.run(j)
        assertEquals(0, r.removed)
        assertEquals(18, r.heldBack)
        assertNull(env.store.data.removalConfirmations[j.id])
        // And a second run is held back again: the confirmation does not linger.
        assertEquals(0, engine.run(j).removed)
        assertEquals(20, dst.lists["t"]!!.size)
    }

    @Test fun fewerRemovalsThanConfirmedStillPass() = runBlocking {
        largeRemoval()
        val j = job()
        env.store.confirmRemovals(j.id, (1..18).map { "dx$it" } + "gone-since")
        dst.lists["t"]!!.removeAll { it.id == "dx18" }
        val r = engine.run(j)
        assertEquals(17, r.removed)
    }

    /** A daylist renews itself: with "changes by itself" its turnover goes through, scheduled runs included. */
    @Test fun aSourceThatChangesByItselfIsFollowedWithoutConfirmation() = runBlocking {
        largeRemoval()
        val j = job(follows = true)
        val r = engine.run(j, unattended = true)
        assertEquals(r.notes.toString(), 18, r.removed)
        assertEquals(0, r.heldBack)
        assertEquals(2, dst.lists["t"]!!.size)
        largeRemoval()
        assertTrue(!engine.plan(j, createTarget = false, interactive = true).removalsNeedConfirmation)
    }

    @Test fun aScheduledRunKeepsTheCapAndLeavesTheConfirmation() = runBlocking {
        largeRemoval()
        val j = job()
        env.store.confirmRemovals(j.id, (1..18).map { "dx$it" })
        val r = engine.run(j, unattended = true)
        assertEquals(0, r.removed)
        assertEquals(18, r.heldBack)
        assertTrue("left for the run the user started", env.store.data.removalConfirmations.containsKey(j.id))
    }

    @Test fun anOldConfirmationIsIgnored() = runBlocking {
        largeRemoval()
        val j = job()
        env.store.update { d ->
            d.copy(removalConfirmations = mapOf(j.id to com.xlollx.songport.data.RemovalConfirmation(
                (1..18).map { "dx$it" }.toSet(), System.currentTimeMillis() - FileStore.CONFIRMATION_TTL_MS - 1,
            )))
        }
        assertEquals(0, engine.run(j).removed)
    }

    @Test fun deletingTheSyncDropsItsConfirmation() {
        val j = job()
        env.store.confirmRemovals(j.id, listOf("x"))
        env.store.deleteJob(j.id)
        assertTrue(env.store.data.removalConfirmations.isEmpty())
    }

    @Test fun aTwoWayPairNeverRemoves() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"))
        dst.catalogue += t("da", "Alpha")
        // "dn" was just added on the target: the reverse sync has not copied it back yet.
        dst.lists["t"] = mutableListOf(t("da", "Alpha"), t("dn", "New on the target"))
        val reverse = job(mirror = false)
        val j = job(linked = reverse.id)
        val r = engine.run(j)
        assertEquals(0, r.removed)
        assertEquals(2, dst.lists["t"]!!.size)
        assertTrue(r.notes.toString(), r.notes.any { it == "note_two_way_no_removals(1)" })
        // Without its twin (Two-way turned off, twin deleted) the mirror works again.
        env.store.deleteJob(reverse.id)
        assertEquals(1, engine.run(env.store.job(j.id)!!).removed)
    }

    @Test fun unsureMatchesSeenInThePreviewAreReviewedByTheRun() = runBlocking {
        // A weaker match: 20 s longer, so the score lands between the accept and the review lines.
        src.lists["p"] = mutableListOf(Track(id = "a", title = "Alpha", artists = listOf("Band"), album = "Album", durationMs = 200_000))
        dst.catalogue += Track(id = "da", title = "Alpha", artists = listOf("Band"), album = "Album", durationMs = 220_000)
        dst.lists["t"] = mutableListOf()
        val j = job(mirror = false)
        val plan = engine.plan(j, createTarget = false, interactive = true)
        assertEquals(plan.toString(), 1, plan.uncertain.size)
        assertNull("not remembered by the preview", env.store.cachedMatch("msrc", "a", "mdst"))
        val r = engine.run(j)
        assertEquals(1, r.added)
        assertEquals("the run asks again", 1, r.reviewTracks.size)
    }

    @Test fun anUnsureMatchSettledInThePreviewIsNotAskedAgain() = runBlocking {
        src.lists["p"] = mutableListOf(Track(id = "a", title = "Alpha", artists = listOf("Band"), album = "Album", durationMs = 200_000))
        dst.catalogue += Track(id = "da", title = "Alpha", artists = listOf("Band"), album = "Album", durationMs = 220_000)
        dst.lists["t"] = mutableListOf()
        val j = job(mirror = false)
        val review = engine.plan(j, createTarget = false, interactive = true).uncertain.single()
        // "Looks right" in the preview.
        engine.rematchInPlan(j, review, review.chosen)
        val r = engine.run(j)
        assertEquals(1, r.added)
        assertTrue(r.reviewTracks.isEmpty())
    }
}

/** A music service in memory: a catalogue searched by normalised title, and playlists. */
class MemoryService(override val serviceId: String) : MusicProvider {
    /** Versions a test sets per playlist (MusicProvider.playlistVersion); none by default. */
    val versions = HashMap<String, String>()
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = versions[playlistId]
    val catalogue = ArrayList<Track>()
    val lists = LinkedHashMap<String, MutableList<Track>>()

    override val displayName: String get() = serviceId
    override val brandColor: Long = 0xFF888888
    override val noteName: String = ""
    override val requiresAuth: Boolean = false
    override val searchParallelism: Int = 1

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = "tester"
    override fun startAuth(ctx: Env) {}
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
    override fun disconnect(ctx: Env) {}

    override suspend fun playlists(ctx: Env): List<Playlist> = lists.map { (id, t) -> Playlist(id, id, t.size) }
    /** Thrown by [tracks] when set: a service that fails the whole run. */
    var failure: Exception? = null
    /** Sizes the next reads stop at, one per read: a listing the service sends in part. */
    val shortReads = ArrayDeque<Int>()
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        failure?.let { throw it }
        val all = lists[playlistId]?.toList() ?: throw ProviderException("$serviceId: no playlist $playlistId")
        return shortReads.removeFirstOrNull()?.let { all.take(it) } ?: all
    }
    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val wanted = Matcher.normalizeTitle(track.title)
        return catalogue.filter { Matcher.normalizeTitle(it.title) == wanted }
    }
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        lists[name] = ArrayList()
        return Playlist(name, name, 0)
    }
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        lists.getOrPut(playlistId) { ArrayList() } += tracks
    }
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val ids = tracks.map { it.id }.toSet()
        lists[playlistId]?.removeAll { it.id in ids }
    }
}

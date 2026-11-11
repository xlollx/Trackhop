package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A setlist added to one of the user's playlists: only the songs not already there, and the summary says so. */
class SetlistToPlaylistTest {
    private lateinit var env: ContractEnv
    private lateinit var dst: MemoryService

    @Before
    fun setUp() {
        env = ContractEnv()
        dst = MemoryService("mdst")
        Providers.testDoubles = listOf(LocalFilesProvider, dst)
    }

    @After
    fun tearDown() {
        Providers.testDoubles = emptyList()
    }

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Muse"), durationMs = 200_000)

    private val setlist = Setlists.Setlist(
        id = "1", artist = "Muse", date = "2025-06-01", venue = "Forum", city = "Milano",
        songs = listOf("Uprising", "Hysteria", "Starlight", "Unknown Jam").map { Setlists.Song(it) } + Setlists.Song("Intro", tape = true),
    )

    @Test
    fun addsOnlyTheSongsNotAlreadyInThePlaylist() = runBlocking {
        dst.catalogue += listOf(t("d1", "Uprising"), t("d2", "Hysteria"), t("d3", "Starlight"))
        dst.lists["mine"] = arrayListOf(t("d1", "Uprising"), t("d9", "Other Song"))
        val file = LocalFilesProvider.importTracks(env, "Muse at Forum", Setlists.tracks(setlist)) // An ASCII file name: the test machine's file names may not take "·".
        val job = Setlists.job("j", "Muse at Forum", file, PlaylistRef("mdst", "mine", "Mine"))
        assertEquals(false, job.mirrorRemovals)
        env.store.upsertJob(job)

        val report = SyncEngine(env).run(job)

        assertNull(report.error)
        assertEquals(listOf("d1", "d9", "d2", "d3"), dst.lists["mine"]!!.map { it.id })
        assertEquals(Setlists.Summary(added = 2, alreadyThere = 1, notFound = 1), Setlists.summary(report))
    }

    @Test
    fun aNewPlaylistIsNamedAfterTheSetlist() {
        val job = Setlists.job("j", "Muse · Forum", "file", PlaylistRef("mdst"))
        assertEquals("Muse · Forum", job.name)
        assertEquals("Muse · Forum", job.target.playlistName)
        assertNull(job.target.playlistId)
        assertEquals(LocalFilesProvider.id, job.source.provider)
    }

    @Test
    fun aSetlistSentIsAOneOffNotASyncAndGoesOnceReviewed() = runBlocking {
        dst.catalogue += listOf(t("d1", "Uprising"), t("d2", "Hysteria"), t("d3", "Starlight"))
        dst.lists["mine"] = arrayListOf(t("d9", "Other Song"))
        val file = LocalFilesProvider.importTracks(env, "Muse at Forum", Setlists.tracks(setlist))
        val job = Setlists.job("j", "Muse at Forum", file, PlaylistRef("mdst", "mine", "Mine"), now = 1_000L)
        assertTrue(job.oneOff)
        assertEquals(1_000L, job.createdEpoch)
        env.store.upsertJob(job)
        // Not a sync: no list of syncs has it (Syncs, Sync all, widgets, schedules, stats, backups read [syncs]).
        assertTrue(env.store.data.syncs.isEmpty())
        assertEquals(listOf("j"), OneOffs.recent(env.store.data).map { it.id })

        val report = SyncEngine(env).run(job)
        assertEquals(1, OneOffs.toReview(env.store.data.reports.first { it.id == report.id }))
        // The review works as for a sync: the song chosen by hand goes in, and with nothing left to review the one-off goes.
        val missing = report.unmatchedTracks.single()
        SyncEngine(env).resolveManually(env.store.job("j")!!, report.id, missing, t("d7", "Unknown Jam"))
        assertTrue("d7" in dst.lists["mine"]!!.map { it.id })
        assertNull(env.store.job("j"))
        // Its report stays in the log; the playlist is untouched by the deletion.
        assertTrue(env.store.data.reports.any { it.id == report.id })
        assertEquals(listOf("d9", "d1", "d2", "d3", "d7"), dst.lists["mine"]!!.map { it.id })
    }

    @Test
    fun aOneOffGoesAfterAWeekAndOnlyTheNewestTwentyStay() {
        val week = OneOffs.KEEP_MS
        val now = 100 * week
        (1..22).forEach { i -> env.store.upsertJob(Setlists.job("o$i", "Set $i", "file", PlaylistRef("mdst"), now = now - i * 1_000L)) }
        env.store.upsertJob(Setlists.job("old", "Old", "file", PlaylistRef("mdst"), now = now - week))
        val sync = Setlists.job("s", "A sync", "file", PlaylistRef("mdst")).copy(oneOff = false, createdEpoch = 0)
        env.store.upsertJob(sync)
        assertEquals(setOf("o21", "o22", "old"), OneOffs.over(env.store.data, now).toSet())
        env.store.dropOldOneOffs(now)
        assertEquals(OneOffs.MAX, env.store.data.jobs.count { it.oneOff })
        assertFalse(env.store.data.jobs.any { it.id == "old" })
        // A real sync is never touched, and a report reviewed to the end leaves it alone too.
        assertEquals(listOf("s"), env.store.data.syncs.map { it.id })
        val r = com.xlollx.songport.model.SyncReport(id = "r", jobId = "s", jobName = "A sync", startedEpoch = now, durationMs = 1, unmatchedTracks = listOf(t("x", "X")))
        env.store.addReport(r)
        env.store.updateReport("r") { it.copy(unmatchedTracks = emptyList()) }
        assertTrue(env.store.job("s") != null)
    }
}

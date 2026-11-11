package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

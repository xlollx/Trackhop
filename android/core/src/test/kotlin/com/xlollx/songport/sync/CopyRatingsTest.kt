package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.RatingWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Copy star ratings" (#80): an iTunes library's stars, kept in the playlist file, reach a server
 * that takes ratings. Set only where they differ; unrated songs and albums are never touched.
 */
class CopyRatingsTest {
    /** A server in memory that keeps ratings, as Navidrome does through `setRating`. */
    private class RatedService : MemoryService("mdst"), RatingWriter {
        val songSets = ArrayList<Pair<String, Int>>()
        val albumSets = ArrayList<Pair<String, Int>>()
        val albumRatings = HashMap<String, Int>()
        var albumReads = 0
        var refuse = false
        override suspend fun setSongRating(ctx: Env, songId: String, stars: Int) {
            if (refuse) throw ProviderException("mdst: not supported")
            songSets += songId to stars
        }
        override suspend fun albumRating(ctx: Env, albumId: String): Int { albumReads++; return albumRatings[albumId] ?: 0 }
        override suspend fun setAlbumRating(ctx: Env, albumId: String, stars: Int) {
            if (refuse) throw ProviderException("mdst: not supported")
            albumSets += albumId to stars
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var dst: RatedService

    private fun song(id: String, title: String, rating: Int = 0, albumRating: Int = 0, albumId: String? = null) =
        Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000, rating = rating, albumRating = albumRating, albumId = albumId)

    @Before fun setUp() {
        env = ContractEnv()
        dst = RatedService()
        Providers.testDoubles = listOf(LocalFilesProvider, dst)
        // On the server: Beta already with the source's 3 stars, Gamma with 5 the source does not have.
        dst.lists["t"] = mutableListOf(song("db", "Beta", rating = 3, albumId = "al1"), song("dc", "Gamma", rating = 5, albumId = "al2"))
        dst.catalogue += listOf(song("da", "Alpha", albumId = "al1"), song("db", "Beta", rating = 3, albumId = "al1"), song("dc", "Gamma", rating = 5, albumId = "al2"))
        dst.albumRatings["al1"] = 2
        dst.albumRatings["al2"] = 4
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    /** The source as the iTunes import leaves it: a playlist file with the songs' stars. */
    private fun job(copy: Boolean): SyncJob {
        val file = LocalFilesProvider.importTracks(env, "Library", listOf(
            song("", "Alpha", rating = 4, albumRating = 5),
            song("", "Beta", rating = 3, albumRating = 5),
            song("", "Gamma"),
            song("", "Delta", rating = 2),
        ))
        return SyncJob(
            id = "j-$copy", name = "Library", source = PlaylistRef(LocalFilesProvider.id, file, file),
            target = PlaylistRef("mdst", "t", "t"), copyRatings = copy,
        ).also { env.store.upsertJob(it) }
    }

    @Test fun ratingsAreSetWhereTheyDiffer() = runBlocking {
        val r = SyncEngine(env).run(job(copy = true))
        assertNull(r.error)
        assertEquals(1, r.added)
        // Alpha, just added, takes its 4 stars; Beta has its 3 already; Gamma is unrated in the source and keeps its 5.
        assertEquals(listOf("da" to 4), dst.songSets)
        // Their album: 5 stars where the server has 2, read once. Gamma's album is left alone.
        assertEquals(listOf("al1" to 5), dst.albumSets)
        assertEquals(1, dst.albumReads)
        assertEquals(2, r.ratingsSet)
    }

    @Test fun anAlbumWithTheSameStarsIsLeftAlone() = runBlocking {
        dst.albumRatings["al1"] = 5
        val r = SyncEngine(env).run(job(copy = true))
        assertEquals(emptyList<Pair<String, Int>>(), dst.albumSets)
        assertEquals(1, r.ratingsSet)
    }

    @Test fun nothingHappensWithoutTheSwitch() = runBlocking {
        val r = SyncEngine(env).run(job(copy = false))
        assertNull(r.error)
        assertEquals(1, r.added)
        assertTrue(dst.songSets.isEmpty() && dst.albumSets.isEmpty())
        assertEquals(0, dst.albumReads)
        assertEquals(0, r.ratingsSet)
    }

    @Test fun aRefusedRatingIsANoteNotAFailure() = runBlocking {
        dst.refuse = true
        val r = SyncEngine(env).run(job(copy = true))
        assertNull(r.error)
        assertEquals(1, r.added)
        assertEquals(0, r.ratingsSet)
        assertTrue(r.notes.toString(), r.notes.contains("note_ratings_failed(2,mdst)"))
    }
}

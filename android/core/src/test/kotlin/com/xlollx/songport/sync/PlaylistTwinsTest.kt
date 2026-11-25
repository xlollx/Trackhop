package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tools › Duplicate playlists: copies grouped by name or songs, the one to keep, and nothing used by a sync deleted. */
class PlaylistTwinsTest {
    private fun song(i: Int, id: String = "s$i") = Track(id = id, title = "Song $i", artists = listOf("Artist ${i % 7}"), durationMs = 180_000L + i, addedAt = i.toLong())
    private fun songs(r: IntRange) = r.map { song(it) }
    private fun copy(id: String, name: String, tracks: List<Track>, syncs: List<String> = emptyList()) =
        PlaylistTwins.Copy(Playlist(id, name, tracks.size), tracks, syncs, tracks.maxOfOrNull { it.addedAt } ?: 0)

    @Test fun sameNameOrNinetyPercentOfTheSongsMakeAGroup() {
        val road = copy("a", "Road Trip", songs(0 until 68))
        val road2 = copy("b", "road trip", songs(0 until 54))
        val summer = copy("c", "Estate 2025", songs(100 until 141))
        // Another service's ids for most of them: told apart by title and artists.
        val summer2 = copy("d", "Summer 25 (from Deezer)", songs(100 until 140).map { it.copy(id = "dz" + it.id) } + song(500))
        val other = copy("e", "Focus", songs(200 until 210))
        val groups = PlaylistTwins.groups(listOf(road, road2, summer, summer2, other))
        assertEquals(2, groups.size)
        assertTrue(groups[0].sameName)
        assertFalse(groups[1].sameName)
        assertEquals(setOf("c", "d"), groups[1].copies.map { it.playlist.id }.toSet())
        assertEquals(79, PlaylistTwins.same(road.tracks, road2.tracks))
        assertEquals(listOf("Song 500"), PlaylistTwins.missing(summer.tracks, summer2.tracks).map { it.title })
    }

    @Test fun theOneASyncUsesIsKeptElseTheOneChangedLast() {
        val older = copy("a", "Mix", songs(0 until 10))
        val newer = copy("b", "Mix", songs(0 until 12))
        assertEquals(1, PlaylistTwins.suggested(listOf(older, newer)))
        assertEquals(0, PlaylistTwins.suggested(listOf(older.copy(syncs = listOf("Road Trip")), newer)))
    }

    private class Deleting(id: String) : MemoryService(id) {
        val deleted = ArrayList<String>()
        override suspend fun deletePlaylist(ctx: Env, playlistId: String) { deleted += playlistId; lists.remove(playlistId) }
    }

    @Test fun eachDeletedOneIsSavedFirstAndOneASyncUsesStays() = runBlocking {
        val env = ContractEnv()
        val p = Deleting("msvc")
        p.lists["a"] = songs(0 until 10).toMutableList()
        p.lists["b"] = (songs(0 until 9) + song(42)).toMutableList()
        p.lists["c"] = songs(0 until 10).toMutableList()
        p.names["a"] = "Mix"; p.names["b"] = "Mix"; p.names["c"] = "Mix"
        val group = PlaylistTwins.find(env, p).single()
        // A sync started using c after the page read it: c stays.
        env.store.upsertJob(SyncJob("j", "Road Trip", PlaylistRef("msvc", "c", "Mix"), PlaylistRef("other", "t", "t")))
        val kept = group.copies.single { it.playlist.id == "a" }
        val gone = group.copies - kept
        assertEquals(listOf("b"), PlaylistTwins.delete(env, p, kept, gone, addMissing = true) { "$it (deleted copy)" })
        assertEquals(listOf("b"), p.deleted)
        assertTrue(p.lists["a"]!!.any { it.title == "Song 42" })
        val saved = LocalFilesProvider.entries(env).filter { it.base.contains("Mix (deleted copy)") }
        assertEquals(1, saved.size)
        assertEquals(10, saved.single().trackCount)
    }
    @Test fun aSongAddedToACopyAfterTheReadingIsSavedAndAddedNotLost() = runBlocking {
        val env = ContractEnv()
        val p = Deleting("msvc")
        p.lists["a"] = songs(0 until 10).toMutableList()
        p.lists["b"] = songs(0 until 10).toMutableList()
        p.names["a"] = "Mix"; p.names["b"] = "Mix"
        val group = PlaylistTwins.find(env, p).single()
        // Added in the service after the page read the copies.
        p.lists["b"]!!.add(song(77))
        val kept = group.copies.single { it.playlist.id == "a" }
        assertEquals(listOf("b"), PlaylistTwins.delete(env, p, kept, group.copies - kept, addMissing = true) { "$it (deleted copy)" })
        assertTrue(p.lists["a"]!!.any { it.title == "Song 77" })
        assertEquals(11, LocalFilesProvider.entries(env).single { it.base.contains("Mix (deleted copy)") }.trackCount)
    }
}

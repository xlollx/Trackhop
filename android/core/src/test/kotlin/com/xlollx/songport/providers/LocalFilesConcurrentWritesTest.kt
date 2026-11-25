package com.xlollx.songport.providers

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

/** Two writers adding songs to one File playlist at once: no song is dropped. */
class LocalFilesConcurrentWritesTest {
    @Test fun songsAddedAtOnceByTwoWritersAreAllKept() {
        val env = ContractEnv()
        val pl = runBlocking { LocalFilesProvider.createPlaylist(env, "Radio", "") }
        val writers = (0 until 4).map { w ->
            thread {
                repeat(40) { i -> runBlocking { LocalFilesProvider.addTracks(env, pl.id, listOf(Track(id = "w$w-$i", title = "Song $w $i", artists = listOf("Band $w")))) } }
            }
        }
        writers.forEach { it.join() }
        assertEquals(160, runBlocking { LocalFilesProvider.tracks(env, pl.id) }.size)
    }
}

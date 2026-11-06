package com.xlollx.songport.data

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class MoveJobTest {
    private fun job(id: String) = SyncJob(id = id, name = id, source = PlaylistRef("a", id), target = PlaylistRef("b"))

    @Test fun aSyncMovesAndTheOthersKeepTheirOrder() {
        val store = FileStore(Files.createTempDirectory("move").toFile())
        listOf("a", "b", "c", "d").forEach { store.upsertJob(job(it)) }
        store.moveJob("c", 1)
        assertEquals(listOf("a", "c", "b", "d"), store.data.jobs.map { it.id })
        store.moveJob("d", 0)
        assertEquals(listOf("d", "a", "c", "b"), store.data.jobs.map { it.id })
        store.moveJob("d", 99)
        assertEquals(listOf("a", "c", "b", "d"), store.data.jobs.map { it.id })
        store.moveJob("missing", 0)
        assertEquals(listOf("a", "c", "b", "d"), store.data.jobs.map { it.id })
    }
}

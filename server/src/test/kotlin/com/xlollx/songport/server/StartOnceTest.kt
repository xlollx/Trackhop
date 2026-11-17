package com.xlollx.songport.server

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A double click on Continue or on "Add the ticked playlists" starts one run, not two. */
class StartOnceTest {
    @Test fun twoClicksAtOnceStartOneTask() {
        val gate = CompletableDeferred<Unit>()
        val title = "Continue “Road trip ${System.nanoTime()}”"
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val started = (1..8).map { pool.submit<Tasks.Task?> { go.await(); Tasks.startOnce(title, "/tools", {}, "ann") { gate.await(); "done" } } }
        go.countDown()
        val tasks = started.mapNotNull { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, tasks.size)
        // Someone else's of the same name is theirs.
        assertNotNull(Tasks.startOnce(title, "/tools", {}, "bob") { "done" })
        gate.complete(Unit)
        val until = System.currentTimeMillis() + 10_000
        while (!tasks[0].done && System.currentTimeMillis() < until) Thread.sleep(10)
        // Done: it can be started again.
        assertNotNull(Tasks.startOnce(title, "/tools", {}, "ann") { "done" })
    }

    @Test fun aLibraryIsAddedFromByOneRequestAtATime() {
        assertTrue(LibraryUploads.claim("t1"))
        assertFalse(LibraryUploads.claim("t1"))
        assertTrue(LibraryUploads.claim("t2"))
        LibraryUploads.release("t1")
        assertTrue(LibraryUploads.claim("t1"))
        LibraryUploads.release("t1"); LibraryUploads.release("t2")
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record of several syncs run by one worker ([SyncState.Batch]), and how the log names them ([SyncLog]). */
class SyncBatchTest {
    private val hub = PlaylistRef(SharedProvider.ID, "0123456789abcdef", "Metal & More")
    private val up = SyncJob("u1", "Metal & More · Spotify ↔", PlaylistRef("spotify", "p1", "Metal & More"), hub)
    private val down = SyncJob("d1", "Metal & More · TIDAL", hub, PlaylistRef("tidal", "p2", "Metal & More"))
    private val plain = SyncJob("12345678-aaaa", "Brani preferiti", PlaylistRef("spotify", "p3", "Brani preferiti"), PlaylistRef("ytmusic", "p4", "Brani preferiti"))

    @Test fun theRecordKeepsTheOrderWhatFinishedAndEachOutcome() {
        SyncState.batchStart("b1", "g", listOf("u1", "d1", "d2"))
        assertEquals(listOf("u1", "d1", "d2"), SyncState.batchOf("g")!!.left)
        SyncState.batchDone("b1", "u1", SyncState.JobResult(added = 2))
        SyncState.batchDone("b1", "d1", SyncState.JobResult(ok = false, error = "Signed out"))
        // Not one of its syncs: nothing changes.
        SyncState.batchDone("b1", "zz", SyncState.JobResult())
        val b = SyncState.batchOf("g")!!
        assertEquals(listOf("u1", "d1"), b.finished)
        assertEquals(listOf("d2"), b.left)
        assertEquals(3, b.place("d2"))
        assertEquals(0, b.place("zz"))
        assertTrue(b.outcomes["d1"]!!.failed)
        assertFalse(b.outcomes["u1"]!!.failed)
        assertEquals(b, SyncState.batchEnd("b1"))
        assertNull(SyncState.batchOf("g"))
        // Over: a late result makes no record again.
        SyncState.batchDone("b1", "d2", SyncState.JobResult())
        assertTrue(SyncState.batches.value.isEmpty())
    }

    @Test fun noInternetAndASkipAreNotFailures() {
        assertFalse(SyncState.JobResult(ok = false, offline = true, error = "No internet").failed)
        assertFalse(SyncState.JobResult(skipped = "already running").failed)
        assertTrue(SyncState.JobResult(ok = false, error = "HTTP 500").failed)
    }

    @Test fun theLogNamesASyncByItsGroupAndServiceNeverByItsName() {
        assertEquals("group=0123456789abcdef up:spotify", SyncLog.tag(up))
        assertEquals("group=0123456789abcdef down:tidal", SyncLog.tag(down))
        assertEquals("job=12345678 spotify>ytmusic", SyncLog.tag(plain))
        val start = SyncLog.batchStart(SyncLog.groupOf(listOf(up, down)), listOf(up, down), scheduled = false)
        assertEquals("batch start group=0123456789abcdef n=2 (manual) order=up:spotify, down:tidal", start)
        assertNull(SyncLog.groupOf(listOf(up, plain)))
        for (line in listOf(start, SyncLog.tag(up), SyncLog.tag(plain))) {
            assertFalse(line, "Metal" in line || "Brani" in line)
        }
    }

    @Test fun theEndLineSaysWhatWasHeldInterruptedAndTheNetwork() {
        val r = SyncReport("r", "d1", "Metal & More · TIDAL", 0, 0, sourceCount = 10, targetCount = 8, added = 2, removed = 1, heldBack = 3, searchInterrupted = true, netError = "TIMEOUT", error = "Timed out")
        assertEquals("+2 -1 nf=0 src=10 dst=8 held=3 interrupted=true net=TIMEOUT ERR Timed out", SyncLog.end(r))
        assertEquals("+0 -0 nf=0 src=0 dst=0 held=0 interrupted=false net=-", SyncLog.end(SyncReport("r", "d1", "x", 0, 0)))
    }

    @Test fun theSummarySaysHowTheBatchWent() {
        val b = SyncState.Batch(
            "b", "g", listOf("u1", "d1", "d2", "d3"), listOf("u1", "d1", "d2"),
            mapOf("u1" to SyncState.JobResult(added = 2, held = 1), "d1" to SyncState.JobResult(ok = false, error = "x"), "d2" to SyncState.JobResult(skipped = "already running")),
        )
        assertEquals("batch end group=g ran=2/4 ok=1 failed=1 offline=0 skipped=1 left=1 +2 -0 nf=0 held=1", SyncLog.batchEnd(b))
        assertTrue(SyncLog.batchEnd(b, stopped = true).startsWith("batch stopped group=g"))
    }
}

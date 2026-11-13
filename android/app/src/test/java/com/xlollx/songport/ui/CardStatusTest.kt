package com.xlollx.songport.ui

import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which pill a sync card wears: the most important thing the last run left for the user. */
class CardStatusTest {
    private fun report(heldBack: Int = 0, notFound: Int = 0, interrupted: Boolean = false, error: String? = null) = SyncReport(
        id = "r", jobId = "j", jobName = "Liked songs", startedEpoch = 0, durationMs = 0,
        unmatchedTracks = (1..notFound).map { Track(id = "n$it", title = "Song $it") },
        heldBack = heldBack, searchInterrupted = interrupted, error = error,
    )

    @Test fun removalsOnHoldWinOverNotFound() {
        // The owner's Liked songs: one song not found, hundreds of unlikes held back.
        assertEquals(CardStatus.HELD_BACK, cardStatus(isRunning = false, enabled = true, report = report(heldBack = 412, notFound = 1)))
    }

    @Test fun removalsOnHoldWinOverAnIncompleteSearch() {
        assertEquals(CardStatus.HELD_BACK, cardStatus(false, true, report(heldBack = 20, notFound = 3, interrupted = true)))
    }

    @Test fun notFoundWhenNothingIsHeld() {
        assertEquals(CardStatus.NOT_FOUND, cardStatus(false, true, report(notFound = 1)))
        assertEquals(CardStatus.OK, cardStatus(false, true, report()))
    }

    @Test fun runningDisabledNeverAndErrorComeFirst() {
        assertEquals(CardStatus.RUNNING, cardStatus(true, true, report(heldBack = 5)))
        assertEquals(CardStatus.DISABLED, cardStatus(false, false, report(heldBack = 5)))
        assertEquals(CardStatus.NEVER, cardStatus(false, true, null))
        assertEquals(CardStatus.ERROR, cardStatus(false, true, report(heldBack = 5, error = "HTTP 500")))
    }

    /** A paused sync never wears "Syncing": "Pausing" while its step ends, "Paused" once it waits. */
    @Test fun pausedSyncsSaySo() {
        assertEquals(CardStatus.RUNNING, cardStatus(true, true, report(), SyncState.Run.RUNNING))
        assertEquals(CardStatus.PAUSING, cardStatus(true, true, report(), SyncState.Run.PAUSING))
        assertEquals(CardStatus.PAUSED, cardStatus(true, true, report(heldBack = 5), SyncState.Run.PAUSED))
        // Being stopped: still running until it ends (the card's text says it is stopping).
        assertEquals(CardStatus.RUNNING, cardStatus(true, true, null, SyncState.Run.STOPPING))
        // Not running: the run state of a finished sync does not matter.
        assertEquals(CardStatus.OK, cardStatus(false, true, report(), SyncState.Run.PAUSED))
    }

    /** The whole chain: the state SyncState reports maps to the pill the card shows. */
    @Test fun engineStateToCardPill() {
        fun pill(asked: Boolean, parked: Boolean) = cardStatus(true, true, null, SyncState.runOf(asked, parked, stopping = false))
        assertEquals(CardStatus.RUNNING, pill(asked = false, parked = false))
        assertEquals(CardStatus.PAUSING, pill(asked = true, parked = false))
        assertEquals(CardStatus.PAUSED, pill(asked = true, parked = true))
    }

    @Test fun anEmptySourceIsSaidUnlessARadioHasNothingNew() {
        val empty = report().copy(sourceEmpty = true)
        assertEquals(CardStatus.EMPTY_SOURCE, cardStatus(false, true, empty))
        assertEquals(CardStatus.OK, cardStatus(false, true, empty, radio = true))
        assertEquals(CardStatus.NOT_FOUND, cardStatus(false, true, report(notFound = 1).copy(sourceEmpty = true)))
    }

    /** Stopped because the internet went away: its own calm pill, not "Needs attention"; any other failure still is. */
    @Test fun aRunStoppedForTheConnectionIsNotAnError() {
        assertEquals(CardStatus.OFFLINE, cardStatus(false, true, report(error = "Stopped: no internet connection.").copy(netError = "OFFLINE")))
        assertEquals(CardStatus.ERROR, cardStatus(false, true, report(error = "The service didn't answer in time.").copy(netError = "TIMEOUT")))
        assertEquals(CardStatus.ERROR, cardStatus(false, true, report(error = "HTTP 500")))
        assertEquals(CardStatus.RUNNING, cardStatus(true, true, report(error = "x").copy(netError = "OFFLINE")))
    }
}

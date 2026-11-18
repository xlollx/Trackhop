package com.xlollx.songport

import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a finished run's notification says (Notifications.summary), by its parts. */
class NotificationsSummaryTest {
    private fun report(added: Int = 0, notAdded: Int = 0, notFound: Int = 0) =
        SyncReport("r", "j", "Liked songs", 0, 0, added = added, notAdded = (1..notAdded).map { "Song $it" }, unmatched = (1..notFound).map { "Lost $it" })

    @Test fun nothingLeftIsUpToDate() {
        assertEquals(listOf(R.string.result_ok to emptyList<Int>()), Notifications.summaryParts(report()))
    }

    /** 2.1.4: Deezer limited every like of the run; it is not "Up to date". */
    @Test fun songsLeftByALimitAreSaidNeverUpToDate() {
        assertEquals(listOf(R.string.result_not_added to listOf(1776)), Notifications.summaryParts(report(notAdded = 1776)))
        assertEquals(
            listOf(R.string.result_added_removed to listOf(321, 0), R.string.result_not_added to listOf(1776), R.string.result_unmatched to listOf(17)),
            Notifications.summaryParts(report(added = 321, notAdded = 1776, notFound = 17)),
        )
    }
}

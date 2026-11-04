package com.xlollx.songport.ui

import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.net.json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the "Did you know?" pill shows on Syncs, and that saves from before it still load. */
class TipsTest {
    private fun report(error: String? = null, partial: Boolean = false) =
        SyncReport(id = "r1", jobId = "j1", jobName = "Road Trip", startedEpoch = 1L, durationMs = 1L, error = error, partial = partial)

    @Test fun notBeforeAnySync() {
        assertFalse(Tips.pillShown(StoreData()))
    }

    @Test fun notAfterOnlyFailedOrPartialRuns() {
        assertFalse(Tips.pillShown(StoreData(reports = listOf(report(error = "boom"), report(partial = true)))))
    }

    @Test fun afterACompletedSyncUntilDismissed() {
        val data = StoreData(reports = listOf(report()))
        assertTrue(Tips.pillShown(data))
        assertFalse(Tips.pillShown(data.copy(settings = Settings(tipsPillDismissed = true))))
    }

    @Test fun anOldSaveLoadsWithThePillNotDismissed() {
        val old = json.decodeFromString(StoreData.serializer(), """{"settings":{"automation":true,"tourDone":true}}""")
        assertFalse(old.settings.tipsPillDismissed)
        assertTrue(old.settings.automation)
    }
}

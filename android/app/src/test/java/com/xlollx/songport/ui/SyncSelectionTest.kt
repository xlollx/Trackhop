package com.xlollx.songport.ui

import com.xlollx.songport.data.Settings
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.BulkEdit
import com.xlollx.songport.sync.HandedOver
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection on the Syncs tab (#68) and what its Schedule sheet holds: the parts that need no screen. */
class SyncSelectionTest {
    private fun job(id: String, every: Int = 0, at: String? = null, days: List<Int> = emptyList()) =
        Timing.apply(SyncJob(id = id, name = id.replaceFirstChar { it.uppercase() }, source = PlaylistRef("spotify", "s$id", "List $id"), target = PlaylistRef("deezer", "t$id")), every, at, days)

    private val a = job("a", 1440, "07:30")
    private val b = job("b", 10080, "09:00", listOf(7))
    private val c = job("c", 1440, "18:00")
    private val d = job("d")
    private val shared = job("sh").copy(target = PlaylistRef(com.xlollx.songport.shared.SharedProvider.ID, "g"))
    private val data = StoreData(jobs = listOf(a, b, c, d, shared), handedOver = mapOf("d" to HandedOver("srv", 1L)), settings = Settings(savedSchedules = listOf(SavedSchedule("m", "Morning refresh", 1440, "07:30"))))

    @Test fun aTapTicksAndUnticks() {
        assertEquals(setOf("a"), SyncSelection.toggle(emptySet(), "a"))
        assertEquals(setOf("a", "b"), SyncSelection.toggle(setOf("a"), "b"))
        assertEquals(setOf("b"), SyncSelection.toggle(setOf("a", "b"), "a"))
    }

    @Test fun selectShownTakesWhatTheFiltersLeaveOnScreen() {
        // The search shows a, b and the shared one; the shared one and the one on the server cannot be ticked.
        val shown = listOf(a, b, shared, d)
        val tickable = SyncSelection.tickable(data, shown)
        assertEquals(listOf("a", "b"), tickable)
        assertFalse(SyncSelection.allShown(setOf("a"), tickable))
        // c was ticked before the search: it stays ticked.
        val all = SyncSelection.toggleShown(setOf("c"), tickable)
        assertEquals(setOf("a", "b", "c"), all)
        assertTrue(SyncSelection.allShown(all, tickable))
        // Again: those shown are unticked, c stays.
        assertEquals(setOf("c"), SyncSelection.toggleShown(all, tickable))
        assertFalse(SyncSelection.allShown(emptySet(), emptyList()))
    }

    @Test fun aSyncThatIsGoneIsNoLongerTicked() {
        val sel = setOf("a", "c")
        assertSame(sel, SyncSelection.pruned(sel, data))
        assertEquals(setOf("c"), SyncSelection.pruned(sel, data.copy(jobs = data.jobs.filter { it.id != "a" })))
        // Handed over to the server meanwhile.
        assertEquals(setOf("a"), SyncSelection.pruned(sel, data.copy(handedOver = mapOf("c" to HandedOver("srv", 1L)))))
        assertTrue(SyncSelection.pruned(emptySet(), data).isEmpty())
    }

    @Test fun deleteNamesEverySyncThatGoes() {
        val pair = job("p").copy(linkedJobId = "p2")
        val twin = job("p2").copy(name = "P ↔", linkedJobId = "p", twin = true)
        val unnamed = job("u").copy(name = "")
        val all = data.copy(jobs = data.jobs + pair + twin + unnamed)
        // The other half of a two-way pair is deleted with its sync, and named; a sync with no name by its playlist.
        assertEquals(listOf("A", "P", "P ↔", "List u"), SyncSelection.deleteNames(all, setOf("a", "p", "u")))
    }

    @Test fun howOftenAndTheTimesOfTheSelection() {
        assertEquals(1440, SyncSelection.frequency(Schedules.specOf(a)))
        // One day of the week is weekly; several days light no chip.
        assertEquals(10080, SyncSelection.frequency(Schedules.specOf(b)))
        assertEquals(SyncSelection.CHOSEN_DAYS, SyncSelection.frequency(Schedules.spec(1440, "08:00", listOf(1, 3))))
        assertEquals(180, SyncSelection.frequency(Schedules.spec(180, null, emptyList())))
        assertFalse(SyncSelection.CHOSEN_DAYS in BulkEdit.FREQUENCIES)
        // "07:30, 09:00, 18:00": a manual sync has no time.
        assertEquals(listOf("07:30", "09:00", "18:00"), SyncSelection.times(listOf(c, a, b, d)))
    }

    @Test fun theScheduleSheetSendsOnlyWhatWasTouched() {
        var draft = BulkScheduleDraft()
        assertTrue(draft.change.isEmpty)
        // Time of day and Wi-Fi left alone: only "how often" goes.
        draft = draft.copy(every = 360)
        assertEquals(BulkEdit.ScheduleChange(every = 360), draft.change)
        // A saved schedule picked: the by-hand fields are untouched again, Wi-Fi stays as chosen.
        draft = draft.copy(wifi = true).pick("m")
        assertEquals(BulkEdit.ScheduleChange(scheduleId = "m", wifiOnly = true), draft.change)
        assertFalse(draft.change.byHand)
        // A field changed after that: the syncs will get their own timing.
        assertTrue(draft.copy(at = "08:00").change.byHand)
        // Tapped again: unpicked.
        assertNull(draft.pick("m").scheduleId)
        // Applied to the data: a, b and c linked, each keeping its own Wi-Fi unless touched.
        val next = BulkEdit.schedule(data, setOf("a", "b", "c"), BulkScheduleDraft().pick("m").change, weekday = 3)
        assertEquals(listOf("m", "m", "m"), listOf("a", "b", "c").map { id -> next.jobs.first { it.id == id }.scheduleId })
    }

    @Test fun theDraftIsKeptAcrossARotation() {
        for (draft in listOf(BulkScheduleDraft(), BulkScheduleDraft("m", null, null, false), BulkScheduleDraft(null, 0, "06:05", true))) {
            val saved = listOf(draft.scheduleId.orEmpty(), draft.every?.toString().orEmpty(), draft.at.orEmpty(), draft.wifi?.toString().orEmpty())
            assertEquals(draft, BulkScheduleDraft.restore(saved))
            assertEquals(saved, BulkScheduleDraft.save(draft))
        }
    }
}

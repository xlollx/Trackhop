package com.xlollx.songport.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.RadioPlan
import com.xlollx.songport.ui.RADIO_DEFAULT_END
import com.xlollx.songport.ui.RADIO_DEFAULT_START
import com.xlollx.songport.ui.radioWindowLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A radio sync made in the editor: "Radio" as the source, a pasted stream and its name (the
 * emulator is offline, so not the directory search), saved with its File playlist as the source,
 * Wi-Fi only and by its window; its card wears the radio and shows the days and hours. Edited, it
 * opens on its station again.
 */
@RunWith(AndroidJUnit4::class)
class RadioSyncFlowTest : FlowTest() {

    private companion object {
        const val STATION = "Test FM"
        const val STREAM = "http://stream.test-fm.example/live.mp3"
    }

    /** The radio syncs gone, the listening (if the window happened to be open) told to stop. */
    @After
    fun stopListening() {
        store.update { StoreData(settings = baseSettings()) }
        RadioPlan.refresh(app, start = false)
    }

    private fun field(label: Int) =
        compose.onAllNodes(hasSetTextAction() and hasText(s(label))).onFirst().also { it.performScrollTo() }

    @Test
    fun aPastedStreamMakesARadioSyncThatWearsTheRadioOnItsCard() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        clickRadioTile()
        waitText(s(R.string.radio_stream_label))

        field(R.string.radio_stream_address).performTextInput(STREAM)
        field(R.string.radio_station_name).performTextInput(STATION)
        click(s(R.string.radio_use_stream))
        // The station chosen (the sheet closes on it: the field shows it), the way to another, and the days and hours in place of the schedule.
        waitFor("the station in the source field") { compose.onAllNodes(hasText(STATION) and hasText(STREAM) and hasText(s(R.string.share_sheet_change))).fetchSemanticsNodes().isNotEmpty() }
        waitText(s(R.string.radio_days))
        assertFalse(exists(s(R.string.editor_mirror)))
        chooseTarget("Deezer")
        click(s(R.string.save))

        waitFor("the radio sync saved") { store.data.jobs.size == 1 }
        val job = store.data.jobs.single()
        val radio = job.radio!!
        assertEquals(STATION, radio.station)
        assertEquals(STREAM, radio.stream)
        assertEquals((1..7).toList(), radio.days)
        assertEquals(RADIO_DEFAULT_START, radio.startMinute)
        assertEquals(RADIO_DEFAULT_END, radio.endMinute)
        assertEquals(LocalFilesProvider.id, job.source.provider)
        assertEquals("Radio - $STATION", job.source.playlistName)
        assertEquals("deezer", job.target.provider)
        assertTrue("radio syncs listen on Wi-Fi by default", job.wifiOnly)
        assertEquals(Schedule.MANUAL, job.schedule)
        assertFalse(job.mirrorRemovals)
        assertFalse(job.shazam)
        assertEquals(s(R.string.radio_sync_name, STATION), job.name)

        // No preview for a radio sync (its source is still empty): straight to its card.
        waitNode("the radio's mark on the card", hasContentDescription(s(R.string.radio_badge_desc)))
        waitText(radioWindowLabel(resumed() ?: app, radio), substring = true)
        snapshot("radio-card")

        // Edited, it opens on its station, with no other station to pick.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
        waitText(STREAM)
        waitText(s(R.string.radio_days))
        assertFalse(exists(s(R.string.radio_another)))
    }

    @Test
    fun aStreamNeedsAnAddressAndAName() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        // A target chosen first: what is missing is the station alone.
        chooseTarget("Deezer")
        clickRadioTile()
        waitText(s(R.string.radio_stream_label))
        field(R.string.radio_stream_address).performTextInput("not a stream")
        click(s(R.string.radio_use_stream))
        waitText(s(R.string.radio_stream_invalid))
        // No station chosen yet: saving says what is missing.
        click(s(R.string.save))
        waitText(s(R.string.editor_error_incomplete))
        assertTrue(store.data.jobs.isEmpty())
    }

    /** The Radio tile is last in the source row, off screen on a phone: the row scrolls to it first. */
    private fun clickRadioTile() {
        openSourcePicker()
        val tile = hasText(s(R.string.editor_source_radio))
        compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(tile) }
        }
        click(s(R.string.editor_source_radio))
    }
}

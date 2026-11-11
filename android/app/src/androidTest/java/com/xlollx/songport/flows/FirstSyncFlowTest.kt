package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first sync: "New sync" with nothing connected shows the services to connect instead of an
 * empty editor; once one is signed in, it offers the first sync with that service as the source.
 */
@RunWith(AndroidJUnit4::class)
class FirstSyncFlowTest : FlowTest() {
    override val connectedAtStart = emptyList<String>()

    private fun tileSelected(label: String) =
        compose.onAllNodes(hasClickAction() and hasText(label))[0].assertIsSelected()

    @Test
    fun newSyncWithNothingConnectedLeadsToConnectingThenToTheFirstSync() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        // The sheet, not the editor.
        waitText(s(R.string.first_sync_title))
        assertTrue(exists(s(R.string.first_sync_body)))
        assertFalse(exists(s(R.string.editor_source)))
        assertFalse("personal servers are not among the first services", exists("Jellyfin"))

        // Spotify: the same connector and sign-in as Accounts → add.
        click("Spotify")
        waitText(s(R.string.connect_title, "Spotify"))
        click(s(R.string.connect_continue_browser))
        waitFor("Spotify signed in") { "spotify" in FakeWorld.connected }
        assertEquals(listOf("spotify"), store.data.settings.connectors)

        // Then the offer of a first sync, which opens the editor on Spotify.
        waitText(s(R.string.first_sync_ready_title))
        click(s(R.string.first_sync_create))
        waitText(s(R.string.editor_title_new))
        waitText(s(R.string.editor_source))
        openSourcePicker()
        tileSelected("Spotify")
        // One service only: the hint, and a file on the phone as the target.
        waitText(s(R.string.editor_one_service_hint))
        waitText(LocalFilesProvider.label(app))
        click(s(R.string.editor_playlist))
        click("Road Trip")
        click(s(R.string.save))
        waitText(s(R.string.preview_target_new), ms = 30_000)
        val job = store.data.jobs.single()
        assertEquals("spotify", job.source.provider)
        assertEquals(LocalFilesProvider.id, job.target.provider)
    }

    @Test
    fun seeAllServicesGoesToAccounts() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        click(s(R.string.new_sync))
        waitText(s(R.string.first_sync_title))
        click(s(R.string.first_sync_see_all))
        waitText(s(R.string.connector_pick_title))
    }
}

/** With one service signed in, "New sync" opens the editor, which offers a file as the target. */
@RunWith(AndroidJUnit4::class)
class OneServiceFlowTest : FlowTest() {
    override val connectedAtStart = listOf("spotify")

    @Test
    fun filesAreOfferedAsTheTargetAndASecondServiceIsSuggested() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        assertFalse("no sheet with a service connected", exists(s(R.string.first_sync_title)))
        waitText(s(R.string.editor_one_service_hint))
        // Target: Spotify and File, File chosen (the other side of the only service).
        val file = LocalFilesProvider.label(app)
        waitText(file)
        // File is a source too (an imported list), so it is in both rows: the target's is the last one.
        compose.onAllNodes(hasClickAction() and hasText(file)).onLast().assertIsSelected()

        // The hint's button goes to Accounts, with the service grid open.
        click(s(R.string.empty_syncs_cta_accounts))
        waitText(s(R.string.connector_pick_title))
    }
}

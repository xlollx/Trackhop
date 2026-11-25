package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.LocalFilesProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #73: "Export the songs not found", from Tools and from Saved matches › To fix. The sheet,
 * the service chosen and the name typed stay when Android makes the activity anew; the File
 * playlist is then saved.
 */
@RunWith(AndroidJUnit4::class)
class NotFoundExportFlowTest : FlowTest() {

    private val songs = listOf(FakeWorld.song("spotify", 0), FakeWorld.song("spotify", 1))

    @Before fun twoSongsNotFoundOnDeezer() {
        val job = SyncJob("j", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout"), lastReportId = "r")
        store.update { d ->
            d.copy(
                jobs = listOf(job),
                reports = listOf(SyncReport(id = "r", jobId = "j", jobName = "Road Trip", startedEpoch = 1, durationMs = 1, sourceCount = 5,
                    unmatched = songs.map { it.toString() }, unmatchedTracks = songs)),
            )
        }
    }

    private fun file(name: String) = File(LocalFilesProvider.dir(app.env), "$name.csv")

    @Test
    fun fromToolsTheSheetAndItsNameStayAndTheFilePlaylistIsSaved() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        click(s(R.string.not_found_title))
        waitText(s(R.string.not_found_save_playlist))
        // One service: Deezer, with its two songs; its name is the playlist's first name.
        click("Deezer · 2", substring = true)
        waitText(s(R.string.not_found_default_name_on, "Deezer"))
        compose.onNode(hasTestTag("not_found_name"), useUnmergedTree = false).performTextReplacement("Road leftovers")
        waitText("Road leftovers")
        remade()
        waitText(s(R.string.not_found_save_playlist))
        waitText("Road leftovers")
        click(s(R.string.not_found_save_playlist))
        waitFor("the File playlist saved") { file("Road leftovers").isFile }
        val text = file("Road leftovers").readText()
        assertTrue(text, songs.all { text.contains(it.title) })
        waitGone(s(R.string.not_found_save_playlist))
    }

    @Test
    fun fromToFixTheButtonOpensTheSameSheetWhichStays() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        click(s(R.string.saved_matches_title))
        val button = s(R.string.not_found_export_button, 2)
        waitText(button)
        click(button)
        waitText(s(R.string.not_found_save_playlist))
        remade()
        waitText(s(R.string.not_found_save_playlist))
        waitText(s(R.string.not_found_default_name))
    }
}

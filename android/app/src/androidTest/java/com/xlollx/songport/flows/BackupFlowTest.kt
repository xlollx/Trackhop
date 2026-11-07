package com.xlollx.songport.flows

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.importTracks
import com.xlollx.songport.providers.exportText
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.Demo
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.playlists
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Backup and files: the full backup of a service into dated files, a list pasted or a file
 * imported, and a file exported. The system file pickers are answered by Espresso-Intents with a
 * file in the app's cache, so nothing leaves the app.
 */
@RunWith(AndroidJUnit4::class)
class BackupFlowTest : FlowTest() {
    private val exported by lazy { File(app.cacheDir, "ui-test-export.csv") }
    private val toImport by lazy { File(app.cacheDir, "ui-test-import.txt") }

    @Before fun stubPickers() {
        exported.delete()
        Intents.init()
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(exported))))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(toImport))))
    }

    @After fun releaseIntents() = Intents.release()

    private fun openTool(title: Int) {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(title))
        click(s(title))
    }

    @Test
    fun backupNowWritesEveryPlaylistAndASecondRunWritesNothingNew() {
        openTool(R.string.backup_title)
        click(s(R.string.backup_now))
        // Spotify and Deezer are connected: both backed up; Spotify's Road Trip (8), Chill Evening (5), Old Favourites (5) among the files.
        waitText(s(R.string.backup_now_done, 2), ms = 30_000, substring = true)
        val files = runBlocking { LocalFilesProvider.playlists(app) }
        assertTrue(files.size >= 3)
        assertTrue(files.any { it.name.contains("Road Trip") && it.trackCount == 8 })

        click(s(R.string.backup_now))
        waitText(s(R.string.backup_now_done, 2), ms = 30_000, substring = true)
        assertEquals("nothing new written", files.size, runBlocking { LocalFilesProvider.playlists(app) }.size)
    }

    @Test
    fun backFromAToolLandsWhereTheListWas() {
        // Backup & restore is at the end of Tools: opening it scrolls the list down to it.
        openTool(R.string.backup_title)
        waitText(s(R.string.backup_now))
        back()
        waitText(s(R.string.tools_section_safety))
        compose.onNode(hasClickAction() and hasText(s(R.string.backup_title))).assertIsDisplayed()
    }

    @Test
    fun theSetUpIsSavedToAFileAndRestoredFromIt() {
        openTool(R.string.backup_title)
        // Saved: the "save as" answer is a file in the cache.
        click(s(R.string.backup_config_save))
        waitText(s(R.string.backup_config_saved))
        val text = exported.readText()
        assertTrue(text.contains("trackhop-config"))
        // Restored from that file: a summary first, then the import.
        toImport.writeText(text)
        click(s(R.string.backup_config_restore))
        waitText(s(R.string.backup_config_confirm_title))
        click(s(R.string.backup_config_restore_action))
        waitText(s(R.string.backup_config_restored, Store.get(app).data.jobs.size), substring = true)
    }

    @Test
    fun pasteAListThenExportItAsCsv() {
        openTool(R.string.files_title)
        click(s(R.string.paste_list))
        waitText(s(R.string.paste_list_hint))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_name))).performTextReplacement("Pasted")
        val lines = (0 until 3).joinToString("\n") { "${Demo.SONGS[it].artist} - ${Demo.SONGS[it].title}" }
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_text))).performTextReplacement(lines)
        click(s(R.string.import_action))
        waitText(s(R.string.csv_imported, 3))
        waitText(s(R.string.files_summary, 1, 3))

        // Files screen: the file's menu, Export…, CSV; the "save as" answer is a file in the cache.
        click(s(R.string.files_manage))
        waitText("Pasted")
        clickDesc(s(R.string.csv_export))
        click(s(R.string.export_menu))
        waitText(s(R.string.export_choose))
        click("CSV")
        waitText(s(R.string.csv_exported))
        val text = exported.readText()
        (0 until 3).forEach { assertTrue("export has ${Demo.SONGS[it].title}", Demo.SONGS[it].title in text) }
    }

    @Test
    fun importAFileFromThePicker() {
        toImport.writeText((3 until 7).joinToString("\n") { "${Demo.SONGS[it].artist} - ${Demo.SONGS[it].title}" })
        openTool(R.string.files_title)
        click(s(R.string.csv_import))
        waitFor("the picked file imported") { runBlocking { LocalFilesProvider.playlists(app) }.isNotEmpty() }
        waitText(s(R.string.csv_imported, 4))
        val files = runBlocking { LocalFilesProvider.playlists(app) }
        assertEquals(1, files.size)
        assertEquals(4, files.single().trackCount)
    }

    // The Files screen's selection: a long press starts it, taps add, the top bar deletes them at once.
    @Test
    fun severalFilesSelectedAndDeletedAtOnce() {
        listOf("Alpha", "Beta", "Gamma").forEach { LocalFilesProvider.importTracks(app, it, listOf(Track(id = "", title = it, artists = listOf("Band")))) }
        openTool(R.string.files_title)
        click(s(R.string.files_manage))
        waitText("Alpha")
        compose.onAllNodes(hasText("Alpha")).onFirst().performTouchInput { longClick() }
        waitText(plural(R.plurals.files_selected, 1, 1))
        click("Beta")
        waitText(plural(R.plurals.files_selected, 2, 2))
        clickDesc(s(R.string.more_actions))
        click(plural(R.plurals.files_delete_n, 2, 2))
        waitText(s(R.string.files_delete_confirm))
        click(s(R.string.delete))
        waitText(plural(R.plurals.files_deleted_n, 2, 2))
        assertEquals(listOf("Gamma"), runBlocking { LocalFilesProvider.playlists(app) }.map { it.name })
    }

    // #49: own patterns written and tried in the File card's menu, then used by the next import.
    @Test
    fun ownPatternsReadTheNextImport() {
        toImport.writeText("Music/Muse/Absolution/Muse - Absolution - 03 Hysteria.mp3\n")
        openTool(R.string.files_title)
        clickDesc(s(R.string.more_actions))
        click(s(R.string.files_names))
        click(s(R.string.files_names_custom))
        waitText(s(R.string.files_patterns_hint))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.files_patterns_title))).performTextReplacement("{artist} - {album} - {track} {title}")
        compose.onNode(hasSetTextAction() and hasText(s(R.string.files_patterns_try))).performTextReplacement("Muse - Absolution - 03 Hysteria.mp3")
        waitText(s(R.string.files_patterns_result, "Muse", "Absolution", "Hysteria"))
        click(s(R.string.save))
        waitText(s(R.string.files_names_saved))
        click(s(R.string.csv_import))
        waitText(s(R.string.csv_imported, 1))
        val t = LocalFilesProvider.exportText(app, runBlocking { LocalFilesProvider.playlists(app) }.single().id, LocalFilesProvider.Export.CSV)
        assertTrue(t, t.contains("Hysteria") && t.contains("Absolution"))
    }
}

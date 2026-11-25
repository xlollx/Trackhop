package com.xlollx.songport.flows

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.Demo
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.playlists
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #85: Accounts › File › Import file did nothing (its own picker's answer was dropped: Accounts
 * re-keys every card when the app resumes, and coming back from the picker is a resume). Importing
 * and pasting now live only in Tools › File playlists: the Accounts card's buttons open it and begin
 * the same import there. The system picker is answered by Espresso-Intents with a file in the cache.
 */
@RunWith(AndroidJUnit4::class)
class AccountsFileImportFlowTest : FlowTest() {
    /** The file the stubbed picker answers with. */
    @Volatile private var picked: File? = null

    override fun baseSettings() = super.baseSettings().let { it.copy(connectors = listOf(LocalFilesProvider.id) + it.connectors) }

    @Before fun stubPicker() {
        Intents.init()
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWithFunction {
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(picked)))
        }
    }

    @After fun release() = Intents.release()

    private fun writeCsv(name: String, from: Int, n: Int) = File(app.cacheDir, name).also { picked = it }.writeText("Artist,Title\n" + (from until from + n).joinToString("\n") { "\"${Demo.SONGS[it].artist}\",\"${Demo.SONGS[it].title}\"" })

    @Test
    fun importFromAccountsRunsTheToolsImportAndSurvivesRecreation() {
        writeCsv("ui-test-accounts.csv", 0, 3)
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.csv_import))
        click(s(R.string.csv_import))
        // Tools › File playlists opens and its own import runs: the file is added, the same "Send to" offered.
        waitFor("the picked file imported") { runBlocking { LocalFilesProvider.playlists(app) }.isNotEmpty() }
        waitText(s(R.string.csv_imported, 3))
        assertEquals(3, runBlocking { LocalFilesProvider.playlists(app) }.single().trackCount)
        waitText(s(R.string.files_summary, 1, 3))
        assertFalse("on Tools, not Accounts", exists(s(R.string.accounts_search)))

        // Made anew: still on File playlists, and the picker is not asked for a second time.
        remade()
        waitText(s(R.string.files_summary, 1, 3))
        assertFalse("still on Tools", exists(s(R.string.accounts_search)))
        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT), times(1))
        assertEquals(1, runBlocking { LocalFilesProvider.playlists(app) }.size)

        // The next import from there is the Tools one as before.
        writeCsv("ui-test-second.csv", 3, 4)
        click(s(R.string.csv_import))
        waitFor("the second file imported") { runBlocking { LocalFilesProvider.playlists(app) }.size == 2 }
        waitText(s(R.string.files_summary, 2, 7))
        click(s(R.string.files_send_to))
        waitText(s(R.string.editor_source))
    }

    @Test
    fun pasteFromAccountsOpensTheToolsPaste() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.paste_list))
        click(s(R.string.paste_list))
        waitText(s(R.string.paste_list_hint))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_name))).performTextReplacement("Pasted")
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_text))).performTextReplacement("${Demo.SONGS[0].artist} - ${Demo.SONGS[0].title}")
        click(s(R.string.import_action))
        waitText(s(R.string.csv_imported, 1))
        assertEquals(listOf("Pasted"), runBlocking { LocalFilesProvider.playlists(app) }.map { it.name })
    }
}

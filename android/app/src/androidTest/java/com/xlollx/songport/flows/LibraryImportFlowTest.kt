package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.sync.ItunesLibrary
import com.xlollx.songport.ui.LibraryImport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #67: the playlists of an Apple Music/iTunes library to pick stay open, with their ticks, when the
 * activity is made anew (a turned screen, a change of language, a return from the background).
 */
@RunWith(AndroidJUnit4::class)
class LibraryImportFlowTest : FlowTest() {

    private val xml = """<?xml version="1.0"?><plist version="1.0"><dict><key>Tracks</key><dict>""" +
        (1..3).joinToString("") { "<key>$it</key><dict><key>Track ID</key><integer>$it</integer><key>Name</key><string>Song $it</string><key>Artist</key><string>Band</string></dict>" } +
        """</dict><key>Playlists</key><array>""" +
        """<dict><key>Name</key><string>Library</string><key>Master</key><true/><key>Playlist Items</key><array><dict><key>Track ID</key><integer>1</integer></dict></array></dict>""" +
        """<dict><key>Name</key><string>Gym Mix</string><key>Playlist Items</key><array><dict><key>Track ID</key><integer>1</integer></dict><dict><key>Track ID</key><integer>2</integer></dict></array></dict>""" +
        """<dict><key>Name</key><string>Night Drive</string><key>Playlist Items</key><array><dict><key>Track ID</key><integer>3</integer></dict></array></dict>""" +
        """</array></dict></plist>"""

    @After fun clear() = LibraryImport.clear()

    @Test
    fun thePlaylistsToPickSurviveTheActivityBeingMadeAnew() {
        LibraryImport.state.value = LibraryImport.State.Picking(1L, "iTunes Library.xml", ItunesLibrary.read(xml))
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.library_title))
        // Both of the person's own playlists start ticked; one is unticked.
        waitText(plural(R.plurals.library_import, 2, 2))
        click("Night Drive")
        waitText(plural(R.plurals.library_import, 1, 1))

        val before = main()
        instr.runOnMainSync { before.recreate() }
        waitForNewMain(before)
        compose.waitForIdle()

        waitText(s(R.string.library_title))
        assertTrue("the untick is kept", exists(plural(R.plurals.library_import, 1, 1)))
    }

    // Any other big playlist file is read behind the same dialog, with its own words, and can be stopped.
    @Test
    fun aBigPlaylistFileShowsItsReadingAndCanBeStopped() {
        LibraryImport.state.value = LibraryImport.State.Reading("big.csv", 12_000_000, 48_000_000, library = false)
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.file_reading))
        assertTrue(exists(s(R.string.file_reading_note)))
        click(s(android.R.string.cancel))
        compose.waitForIdle()
        assertTrue("stopped: nothing is being read", LibraryImport.state.value == LibraryImport.State.Idle)
    }
}

package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.ConcertDiary
import com.xlollx.songport.sync.Setlists
import com.xlollx.songport.ui.DIARY_SHEET_TAG
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sheets and dialogs of 1.18's Discover pages (concert diary, lifetime statistics, My records,
 * venues) stay as they were when Android makes the activity anew (owner's rule, 14/11). The tests
 * have no network: each page is given what it would have read.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverV2StateKeptFlowTest : FlowTest() {
    override val preview2: Boolean = true

    private fun openConcerts() {
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_wrapped_title))
        clickTile(s(R.string.discover_concerts_title))
    }

    private fun aDiary() {
        Setlists.saveKey(app.env, "KEY")
        ConcertDiary.saveUser(app.env, "tester")
        val songs = listOf(Setlists.Song("Airbag"), Setlists.Song("Karma Police"))
        ConcertDiary.keep(app.env, ConcertDiary.Diary("tester", listOf(
            Setlists.Setlist("63de4613", "Radiohead", "2026-07-12", "Ippodromo SNAI San Siro", "Milan", songs = songs),
            Setlists.Setlist("1bd6f9c4", "Radiohead", "2025-06-08", "Visarno Arena", "Florence", songs = songs),
        ), System.currentTimeMillis()))
    }

    @Test
    fun theDiarysPlaylistSheetAndItsStepSurviveTheActivityMadeAnew() {
        aDiary()
        openConcerts()
        click(s(R.string.diary_tab_attended))
        waitText(s(R.string.diary_top_artist, "Radiohead", 2))
        click(s(R.string.diary_make))
        waitNode("the sheet", hasTestTag(DIARY_SHEET_TAG))
        click(s(R.string.diary_opt_missing))
        waitText(s(R.string.diary_pick_artist))
        remade()
        // Still on the tab, the sheet open on its second step.
        waitNode("the sheet after", hasTestTag(DIARY_SHEET_TAG))
        waitText(s(R.string.diary_pick_artist))
    }

    @Test
    fun theSetlistFmUserBeingTypedSurvivesTheActivityMadeAnew() {
        openConcerts()
        click(s(R.string.diary_tab_attended))
        waitText(s(R.string.diary_user_field))
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("lorenzo")
        remade()
        waitNode("the name kept", androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("lorenzo"))
    }
}

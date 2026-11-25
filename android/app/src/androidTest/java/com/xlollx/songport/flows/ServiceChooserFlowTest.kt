package com.xlollx.songport.flows

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Which service?" (2.2): the accounts connected on top, the category chips (an empty one is not
 * shown), search on other names, what is typed and the chip kept when the activity is remade, and
 * the same chooser opened from the sync editor.
 */
@RunWith(AndroidJUnit4::class)
class ServiceChooserFlowTest : FlowTest() {
    override val connectedAtStart = listOf("spotify")

    private val searchField get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    private fun openChooser() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.connected_as, "alex"))
        click(s(R.string.connector_add))
        waitText(s(R.string.connector_pick_title))
    }

    @Test
    fun yourAccountsComeFirstThenTheCategories() {
        store.updateSettings { it.copy(connectorColors = mapOf("spotify" to "teal")) }
        openChooser()
        waitText(s(R.string.chooser_your_accounts))
        // Spotify, YouTube Music (two routes, one row), Deezer, Jellyfin, Radio, Shazam (2.2: connectors of their own), File.
        assertTrue("seven services in the search's hint", exists(plural(R.plurals.chooser_search, 7, 7)))
        // The chips: All and the categories with something in them (Radio & charts: the radio and Shazam); Games waits for its first member.
        listOf(R.string.chooser_all, R.string.services_group_streaming, R.string.services_group_servers, R.string.chooser_cat_radio, R.string.chooser_cat_files).forEach { assertTrue(s(it), exists(s(it))) }
        assertFalse(exists(s(R.string.chooser_cat_games)))
        assertFalse(exists(s(R.string.chooser_cat_podcasts)))
        // A chip shows that category alone.
        clickInDialog(s(R.string.services_group_servers))
        waitText("Jellyfin")
        assertFalse(exists("Deezer"))
        clickInDialog(s(R.string.chooser_all))
        waitText("Deezer")
    }

    @Test
    fun theSearchFindsOtherNamesAndSaysWhereEachBelongs() {
        openChooser()
        // Jellyfin is also known by its music player's name.
        searchField.performTextInput("finamp")
        waitText("Jellyfin")
        assertFalse(exists("Deezer"))
        // A result says its category; the chips and the accounts step aside while typing.
        assertTrue(exists(s(R.string.services_group_servers)))
        assertFalse(exists(s(R.string.chooser_your_accounts)))
        searchField.performTextReplacement("zzzz")
        waitText(s(R.string.chooser_no_results, "zzzz"))
        clickDesc(s(R.string.chooser_clear_search))
        waitText(s(R.string.chooser_your_accounts))
    }

    @Test
    fun whatIsTypedAndTheChipSurviveTheActivityBeingRemade() {
        openChooser()
        clickInDialog(s(R.string.services_group_servers))
        waitText("Jellyfin")
        remade()
        waitText(s(R.string.connector_pick_title))
        waitText("Jellyfin")
        assertFalse("still on the chip", exists("Deezer"))

        clickInDialog(s(R.string.chooser_all))
        searchField.performTextInput("dee")
        waitText("Deezer")
        remade()
        waitText(s(R.string.connector_pick_title))
        waitText("Deezer")
        assertFalse("the search is still typed", exists("Jellyfin"))
        // And the pick goes on: the first account of a service needs no name, straight to its sign-in.
        click("Deezer")
        waitText(s(R.string.connect_title, "Deezer"))
        assertFalse("no name asked for a first account", exists(s(R.string.connector_name_label)))
        waitFor("Deezer added") { "deezer" in store.data.settings.connectors }
    }

    @Test
    fun theSyncEditorOpensTheSameChooser() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        newSync()
        waitText(s(R.string.editor_title_new))
        openTargetPicker()
        // The way to another service: the same "Which service?", with the accounts at hand on top.
        clickTile(s(R.string.connect))
        waitText(s(R.string.connector_pick_title))
        waitText(s(R.string.chooser_your_accounts))
        click("Jellyfin")
        // Accounts takes it from there: its name, then the connector.
        waitText(s(R.string.connector_name_label))
        click(s(R.string.ok))
        waitFor("Jellyfin added") { "jellyfin" in store.data.settings.connectors }
        assertEquals(listOf("spotify", "jellyfin"), store.data.settings.connectors)
    }
}

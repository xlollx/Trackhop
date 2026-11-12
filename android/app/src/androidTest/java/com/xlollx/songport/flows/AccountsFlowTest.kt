package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.NotificationManager
import com.xlollx.songport.MainActivity
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Accounts: add a (fake) service as a connector, sign in, rename it, remove it; removal refused while a sync uses it. */
@RunWith(AndroidJUnit4::class)
class AccountsFlowTest : FlowTest() {
    override val connectedAtStart = listOf("spotify")

    @Test
    fun addSignInRenameAndRemoveAConnector() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.connected_as, "alex"))

        // "+" -> the service grid -> Deezer -> its name.
        click(s(R.string.connector_add))
        waitText(s(R.string.connector_pick_title))
        click("Deezer")
        waitText(s(R.string.connector_name_label))
        click(s(R.string.ok))
        waitFor("Deezer among the connectors") { "deezer" in store.data.settings.connectors }
        assertEquals(listOf("spotify", "deezer"), store.data.settings.connectors)

        // Not signed in yet: Connect, the explanation, then the (fake) sign-in reports back.
        waitText(s(R.string.connect))
        click(s(R.string.connect))
        waitText(s(R.string.connect_title, "Deezer"))
        click(s(R.string.connect_continue_browser))
        waitFor("both cards connected") {
            compose.onAllNodesWithText(s(R.string.connected_as, "alex"), useUnmergedTree = true).fetchSemanticsNodes().size == 2
        }

        // Rename it from its menu (the second card's "More").
        clickDesc(s(R.string.more_actions), index = 1)
        click(s(R.string.connector_rename))
        compose.onNode(hasSetTextAction()).performTextReplacement("My Deezer")
        click(s(R.string.ok))
        waitFor("the new name stored") { store.data.settings.connectorNames["deezer"] == "My Deezer" }
        waitText("My Deezer")

        // And remove it: gone from the list and signed out.
        clickDesc(s(R.string.more_actions), index = 1)
        click(s(R.string.account_remove))
        waitGone("My Deezer")
        assertEquals(listOf("spotify"), store.data.settings.connectors)
        assertFalse("removing a connector signs it out", "deezer" in FakeWorld.connected)
        compose.onNodeWithText(s(R.string.connected_as, "alex")).assertIsDisplayed()
    }

    @Test
    fun aServiceReachedTwoWaysOffersBothRoutes() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        click(s(R.string.connector_add))
        waitText(s(R.string.connector_pick_title))
        click("YouTube Music")
        // The family page: the quick route first, then the official one.
        waitText(s(R.string.connector_pick_hint))
        assertTrue(exists(s(R.string.route_easy)))
        assertTrue(exists(s(R.string.route_official)))
        click(s(R.string.route_easy))
        waitText(s(R.string.connector_name_label))
        click(s(R.string.ok))
        waitFor("the quick route (ytm) added") { store.data.settings.connectors == listOf("spotify", "ytm") }
    }

    @Test
    fun aConnectorUsedByASyncCannotBeRemoved() {
        store.upsertJob(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("spotify", null, "Copy")))
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.connected_as, "alex"))
        clickDesc(s(R.string.more_actions))
        click(s(R.string.account_remove))
        waitText(s(R.string.account_remove_blocked))
        assertEquals(listOf("spotify"), store.data.settings.connectors)
        assertTrue("spotify" in FakeWorld.connected)
    }

    @Test
    fun disconnectAndConnectAgain() {
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.disconnect))
        click(s(R.string.disconnect))
        waitText(s(R.string.connect))
        assertFalse("spotify" in FakeWorld.connected)
        click(s(R.string.connect))
        click(s(R.string.connect_continue_browser))
        waitText(s(R.string.connected_as, "alex"))
    }

    @Test
    fun theSignInAgainNotificationOpensAccountsOnItsConnector() {
        val nm = app.getSystemService(NotificationManager::class.java)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitFor("the Syncs tab, not Accounts") { !exists(s(R.string.connected_as, "alex")) }

        // A web session found gone: one notification for that connector, tagged with its id.
        assertTrue(Notifications.signInAgain(app, "Spotify", "spotify"))
        val title = s(R.string.notif_signin_again_title, "Spotify")
        waitFor("the notification posted") { nm.activeNotifications.any { it.tag == "spotify" } }
        val posted = nm.activeNotifications.first { it.tag == "spotify" }
        assertEquals(title, posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())

        // A tap opens Accounts, where that connector is.
        posted.notification.contentIntent.send()
        waitText(s(R.string.connected_as, "alex"))

        // Signed in again: it is taken back.
        Notifications.cancelSignInAgain(app, "spotify")
        waitFor("the notification taken back") { nm.activeNotifications.none { it.tag == "spotify" } }
    }
}

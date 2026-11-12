package com.xlollx.songport.flows

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The release switch (com.xlollx.songport.Features): in a 1.17 build the ways into 1.18's features
 * are not offered (Shared playlists in Tools, Discover's new cards with Followed artists, the
 * Wrapped and the poster from real listening, the poster's "Use as playlist cover", YouTube Music's
 * followed podcasts in the picker), and Settings › Developer options › Preview upcoming features brings
 * them back on this phone. [ReleaseSwitchPreviewFlowTest] has them with the preview on.
 */
@RunWith(AndroidJUnit4::class)
open class ReleaseSwitchFlowTest : FlowTest() {
    /** Spotify, YouTube Music (followed podcasts, a 1.18 list) and Jellyfin (play counts, a 1.18 listening source). */
    override val connectedAtStart: List<String> = listOf("spotify", "ytm", "jellyfin")

    /** On: what the test expects to see. */
    protected open val shown: Boolean get() = preview2

    @Before fun aBuildWithoutThem() {
        // A 1.18 build (-PV2=true) has them on anyway: nothing to check here then.
        assumeFalse(BuildConfig.V2)
        app.getSharedPreferences("poster", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Whether Discover's grid has a card titled [title] (a lazy grid: the card asked for may be further down). */
    protected fun inDiscover(title: Int): Boolean {
        val label = s(title)
        compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { compose.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(hasText(label)) }
        }
        compose.waitForIdle()
        return exists(label)
    }

    private fun discover() {
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_wrapped_title))
    }

    protected fun checkToolsAndDiscover() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        assertTrue(exists(s(R.string.tools_dedupe_title)))
        assertTrue("Shared playlists in Tools", exists(s(R.string.shared_title)) == shown)

        discover()
        // The cards of 1.16 stay.
        assertTrue(inDiscover(R.string.discover_link_title))
        assertTrue(inDiscover(R.string.tools_poster_title))
        for (card in listOf(R.string.discover_releases_title, R.string.discover_concerts_title, R.string.discover_setlists_title, R.string.discover_records_title, R.string.alerts_followed_title)) {
            assertTrue("${s(card)} in Discover", inDiscover(card) == shown)
        }
    }

    protected fun checkWrapped() {
        discover()
        clickTile(s(R.string.discover_wrapped_title))
        if (shown) {
            // Where it is read from: Jellyfin's play counts (the best one connected) or the playlists.
            waitText(s(R.string.discover_wrapped_source))
            assertTrue(exists(s(R.string.discover_wrapped_playlists)))
        } else {
            // The Wrapped of the playlists, as in 1.16: their services to tick, no listening source.
            waitText(s(R.string.discover_wrapped_pick))
            assertFalse("the listening sources", exists(s(R.string.discover_wrapped_source)))
        }
    }

    protected fun checkPosterCover() {
        discover()
        clickTile(s(R.string.tools_poster_title))
        waitText(s(R.string.card_cover_title))
        // A cover drawn on the phone, for Road Trip, the user's own playlist on Spotify (which takes covers).
        compose.onNode(isSelectable() and hasText(s(R.string.card_cover_gradient))).also { runCatching { it.performScrollTo() } }.performClick()
        waitText(s(R.string.editor_playlist))
        click(s(R.string.editor_playlist))
        val tile = hasClickAction() and hasText("Spotify")
        waitFor("the picker's services") { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tile).onFirst().performClick()
        waitText("Road Trip")
        click("Road Trip")
        waitText(s(R.string.share_sheet_change))
        click(s(R.string.card_poster_run))
        waitText(s(R.string.card_share), ms = 30_000)
        waitText(s(R.string.card_cover_another))
        assertTrue("Use as playlist cover", exists(s(R.string.card_use_as_cover)) == shown)
        // Without plays to read, the 1.16 poster says nothing of them.
        if (!shown) assertFalse(exists(s(R.string.card_poster_plays_connect)))
    }

    protected fun checkPodcasts() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.new_sync))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        openSourcePicker()
        clickTile("YouTube Music")
        waitText("Metal Mix")
        // YouTube Music's library here is its followed podcasts alone: no Library choice without 1.18.
        assertTrue("the Library choice", exists(s(R.string.editor_section_library)) == shown)
        if (shown) {
            click(s(R.string.editor_section_library))
            waitText(s(R.string.followed_podcasts))
        }
    }

    @Test fun toolsAndDiscover() = checkToolsAndDiscover()

    @Test fun wrapped() = checkWrapped()

    @Test fun posterCover() = checkPosterCover()

    @Test fun podcastsInThePicker() = checkPodcasts()
}

/** The same ways in, with Settings › Developer options › Preview upcoming features on: all there. */
@RunWith(AndroidJUnit4::class)
class ReleaseSwitchPreviewFlowTest : ReleaseSwitchFlowTest() {
    override val preview2: Boolean = true

    /** The toggle itself: off it hides them again, on (from Settings) it shows them, on this phone only. */
    @Test fun theDeveloperToggleTurnsThePreviewOffAndOn() {
        store.updateSettings { it.copy(preview2 = false) }
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_theme))
        // Developer options first; the preview's switch is under them.
        assertFalse(exists(s(R.string.settings_preview2)))
        click(s(R.string.settings_developer_switch))
        waitFor("developer options on") { store.data.settings.developerOptions }
        waitText(s(R.string.settings_preview2))
        click(s(R.string.settings_preview2))
        waitFor("the preview on") { store.data.settings.preview2 }
        assertTrue(com.xlollx.songport.Features.v2)
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.shared_title))
        store.updateSettings { it.copy(preview2 = false) }
        assertFalse(com.xlollx.songport.Features.v2)
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_transfer_title))
        assertFalse(exists(s(R.string.shared_title)))
    }
}

package com.xlollx.songport.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.ui.GROUP_BAR_TAG
import com.xlollx.songport.ui.GROUP_RUN_HEAD_TAG
import com.xlollx.songport.ui.JOB_CARD_TAG
import com.xlollx.songport.ui.JOB_TILE_TAG
import com.xlollx.songport.ui.NwayRun
import com.xlollx.songport.ui.SELECTION_TICK_TAG
import com.xlollx.songport.ui.groupLogoTag
import com.xlollx.songport.ui.groupRowTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A multi sync in the Syncs list (2.1.1, the preview approved on 18/11): a tile and a card like any
 * other sync's, on the sync of its source, with a sync's own menu; while it runs, which service is
 * being synced, on the tile, on the card and on the group's page; and what failed, named and on top.
 */
@RunWith(AndroidJUnit4::class)
class MultiSyncFlowTest : FlowTest() {
    override val preview2: Boolean = true

    private lateinit var group: SharedGroup
    private fun deezer() = Providers.byId("deezer")!!.label(app.env)

    /** "Trip mix": Spotify's Road Trip is its source, Deezer's Workout a target; and a sync of its own beside it. */
    private fun seed(minimal: Boolean) {
        group = Nway.create(app.env, "Trip mix", listOf(
            PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") to true,
            PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") to false,
        ))
        store.update { d ->
            d.copy(
                jobs = d.jobs + SyncJob("plain", "Chill", PlaylistRef("spotify", FakeWorld.SPOTIFY_CHILL, "Chill Evening"), PlaylistRef("deezer", "d1", "Chill")),
                settings = d.settings.copy(minimalSyncs = minimal),
            )
        }
        store.flush()
    }

    private fun members() = Nway.members(app.env, group)
    private fun tiles() = compose.onAllNodes(hasTestTag(JOB_TILE_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false)
    private fun cards() = compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false)
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    @Test
    fun theGroupIsATileLikeAnyOtherWithASyncsMenu() {
        seed(minimal = true)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        // Two tiles: the group's one, and the other sync's. The group's three syncs are not tiles.
        waitFor("two tiles") { tiles().size == 2 }
        assertFalse(exists("Trip mix · Spotify", substring = true))

        // Held: a sync's menu, whole. Starred from it: the star is on the sync that stands for the group.
        compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Trip mix")).onFirst().performTouchInput { longClick() }
        waitText(s(R.string.job_style))
        for (id in listOf(R.string.files_select, R.string.preview, R.string.edit, R.string.sync_menu_disable, R.string.sync_menu_move_to, R.string.delete)) assertTrue(s(id), exists(s(id)))
        snapshot("multi-1-tile-menu")
        click(s(R.string.sync_menu_favorite))
        waitFor("the group's card starred") { store.job(group.jobId)?.favorite == true }

        // Its style dialog, kept when Android makes the activity anew; a colour picked is the card's.
        compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Trip mix")).onFirst().performTouchInput { longClick() }
        click(s(R.string.job_style))
        waitText(s(R.string.job_color))
        remade()
        waitText(s(R.string.job_color))
        click(s(R.string.cancel))
        waitGone(s(R.string.job_color))

        // Disable, from the same menu: every sync of the group, not only the one that stands for it.
        compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Trip mix")).onFirst().performTouchInput { longClick() }
        click(s(R.string.sync_menu_disable))
        waitFor("every sync of the group off") { members().all { !it.down.enabled && it.up?.enabled != true } }
        assertTrue("the other sync stays on", store.job("plain")!!.enabled)
        waitText(s(R.string.disabled))

        // Selected with the other one from its menu, as any sync.
        compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Trip mix")).onFirst().performTouchInput { longClick() }
        click(s(R.string.files_select))
        waitText(plural(R.plurals.files_selected, 1, 1))
        waitFor("its tick, by the group's name") { compose.onAllNodes(hasTestTag(SELECTION_TICK_TAG) and hasContentDescription("Trip mix")).fetchSemanticsNodes().isNotEmpty() }
        clickDesc(s(R.string.cancel))
        waitGone(plural(R.plurals.files_selected, 1, 1))

        // A tap: the group's page, with its services.
        click("Trip mix")
        waitText(s(R.string.nway_delete))
        waitText(s(R.string.nway_targets))
        back()

        // Delete, from the menu: asked first (the question survives the activity made anew), then the whole group goes; the playlists stay.
        waitFor("tiles again") { tiles().size == 2 }
        compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Trip mix")).onFirst().performTouchInput { longClick() }
        click(s(R.string.delete))
        waitText(s(R.string.nway_delete_confirm))
        remade()
        waitText(s(R.string.nway_delete_confirm))
        click(s(R.string.yes))
        waitFor("the group and all its syncs gone") { Nway.groups(app.env).isEmpty() && store.data.jobs.map { it.id } == listOf("plain") }
        assertEquals(8, FakeWorld.playlists.getValue("spotify").first { it.id == FakeWorld.SPOTIFY_ROAD }.tracks.size)
    }

    /** The run of the whole group, as the worker records it: Spotify (the source) done, Deezer being synced. */
    private fun whileDeezerSyncs(block: () -> Unit) {
        val order = Nway.order(app.env, group).map { it.id }
        val deezerDown = members().first { it.playlist.provider == "deezer" }.down.id
        try {
            SyncState.batchStart("batch-test", group.id, order)
            order.filter { it != deezerDown }.forEach { SyncState.batchDone("batch-test", it, SyncState.JobResult(added = 1)) }
            SyncState.start(deezerDown)
            SyncState.progress(deezerDown, Progress(Progress.Step.MATCHING, 3, 8))
            block()
        } finally {
            SyncState.finish(deezerDown)
            SyncState.batchEnd("batch-test")
        }
    }

    private fun theServiceBeingSyncedShows() {
        // "Deezer · 2 of 2", a ring on its logo, a tick on the one done, and its small logo right beside the bar.
        waitText(s(R.string.nway_run_place, deezer(), 2, 2), substring = true)
        waitFor("the logo being synced") { tagged(groupLogoTag(NwayRun.Phase.NOW, "deezer")) }
        waitFor("the logo done") { tagged(groupLogoTag(NwayRun.Phase.DONE, "spotify")) }
        waitFor("the bar") { tagged(GROUP_BAR_TAG) }
        waitFor("the small logo beside the bar") { tagged("$GROUP_BAR_TAG:deezer") }
        // Right beside it: on the bar's own row, at its start.
        val row = compose.onAllNodes(hasTestTag(GROUP_BAR_TAG), useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val logo = compose.onAllNodes(hasTestTag("$GROUP_BAR_TAG:deezer"), useUnmergedTree = true).onFirst().fetchSemanticsNode()
        assertTrue("the logo is on the bar's row", logo.boundsInRoot.top >= row.boundsInRoot.top - 1f && logo.boundsInRoot.bottom <= row.boundsInRoot.bottom + 1f)
        assertTrue("the bar takes the rest of the row", row.size.width > logo.size.width * 3)
    }

    @Test
    fun theTileSaysWhichServiceIsSyncingAndSoDoesThePage() {
        seed(minimal = true)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        whileDeezerSyncs {
            theServiceBeingSyncedShows()
            snapshot("multi-2-tile-running")
            // The other sync's tile is as ever: no ring, no logo by a bar.
            assertEquals(1, compose.onAllNodes(hasTestTag(GROUP_BAR_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)

            // Held while it runs: the group's menu, as at rest, with Select; not the live view of one of its services.
            compose.onAllNodes(hasTestTag(JOB_TILE_TAG) and hasText("Trip mix")).onFirst().performTouchInput { longClick() }
            waitText(s(R.string.files_select))
            assertFalse("no live sheet", compose.onAllNodes(hasContentDescription(s(R.string.live_help))).fetchSemanticsNodes().isNotEmpty())
            // A menu, not a page: no arrow to go back with; Back closes it.
            androidx.test.espresso.Espresso.pressBack()
            waitGone(s(R.string.files_select))

            // The page: one row per service, where each stands.
            click("Trip mix")
            waitFor("the page's head") { tagged(GROUP_RUN_HEAD_TAG) }
            waitText(s(R.string.nway_run_services, 2, 2))
            waitFor("Deezer now") { tagged(groupRowTag(NwayRun.Phase.NOW, "deezer")) }
            waitFor("Spotify done") { tagged(groupRowTag(NwayRun.Phase.DONE, "spotify")) }
            waitText(s(R.string.nway_row_now, ""), substring = true)
            snapshot("multi-3-page-running")
        }
        // Over: the rows are the services at rest again.
        waitFor("at rest") { tagged(groupRowTag(null, "deezer")) && !tagged(GROUP_RUN_HEAD_TAG) }
    }

    @Test
    fun theLargeCardSaysWhichServiceIsSyncingToo() {
        seed(minimal = false)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        waitFor("two cards") { cards().size == 2 }
        whileDeezerSyncs {
            waitText(s(R.string.nway_run_place, deezer(), 2, 2), substring = true)
            waitFor("the logo being synced") { tagged(groupLogoTag(NwayRun.Phase.NOW, "deezer")) }
            waitFor("the small logo beside the bar") { tagged("$GROUP_BAR_TAG:deezer") }
            snapshot("multi-4-card-running")
        }
        waitFor("the bar gone") { !tagged(GROUP_BAR_TAG) }
    }

    /** One service failed: the tile names it, and on the page it is on top and leads to its fix; no internet is not a failure. */
    @Test
    fun aServiceThatFailedIsNamedAndOnTop() {
        seed(minimal = true)
        val down = members().first { it.playlist.provider == "deezer" }.down
        val now = System.currentTimeMillis()
        fun last(r: SyncReport) = store.update { d ->
            d.copy(reports = d.reports.filter { it.jobId != down.id } + r, jobs = d.jobs.map { if (it.id == down.id) it.copy(lastReportId = r.id, lastRunEpoch = now) else it })
        }
        last(SyncReport("r-off", down.id, down.name, now, 1_000, error = "No internet", netError = "OFFLINE"))
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        waitText(s(R.string.pill_stopped_offline) + " · " + deezer())
        assertFalse("no internet is not Needs attention", exists(s(R.string.pill_error), substring = true))

        last(SyncReport("r-err", down.id, down.name, now, 1_000, error = "Signed out"))
        waitText(s(R.string.pill_error) + " · " + deezer())
        snapshot("multi-5-tile-failed")
        click("Trip mix")
        waitFor("Deezer on top") { tagged(groupRowTag(null, "deezer", pinned = true)) }
        val pinned = compose.onAllNodes(hasTestTag(groupRowTag(null, "deezer", pinned = true)), useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val source = compose.onAllNodes(hasTestTag(groupRowTag(null, "spotify")), useUnmergedTree = true).onFirst().fetchSemanticsNode()
        assertTrue("above the source", pinned.positionInRoot.y < source.positionInRoot.y)
        waitText("Signed out", substring = true)
        // Its row leads to the fix: signed in, so the log, where the run says what went wrong.
        tap(compose.onAllNodes(hasTestTag(groupRowTag(null, "deezer", pinned = true))).onFirst(), "the failed service")
        waitText(s(R.string.settings_log))
    }
}

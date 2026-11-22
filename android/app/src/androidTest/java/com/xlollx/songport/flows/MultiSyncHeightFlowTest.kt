package com.xlollx.songport.flows

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.env
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.ui.CardSize
import com.xlollx.songport.ui.JOB_CARD_TAG
import com.xlollx.songport.ui.JOB_TILE_TAG
import com.xlollx.songport.ui.SYNC_SEARCH_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A multi sync beside a classic sync (2.1.2): its tile and its card are as tall as the other's, at
 * rest and while it runs, in the grid and in every card size; its large card has the same figures
 * and run history as a classic one, added up over its services; and the search counts it once.
 */
@RunWith(AndroidJUnit4::class)
class MultiSyncHeightFlowTest : FlowTest() {

    private lateinit var group: SharedGroup
    private fun members() = Nway.members(app.env, group)
    private fun density() = app.resources.displayMetrics.density

    /** "Trip mix" (Spotify's Road Trip to Deezer's Workout) and "Chill", a classic sync, each with two runs behind it. */
    private fun seed(minimal: Boolean) {
        group = Nway.create(app.env, "Trip mix", listOf(
            PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") to true,
            PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") to false,
        ))
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val ids = Nway.order(app.env, group).map { it.id }
        val deezerDown = members().first { it.playlist.provider == "deezer" }.down.id
        // Two runs of the whole group, an hour apart; the last one: +2 on one sync, +3 −1 and 2 not found on Deezer's.
        val groupReports = listOf(2 * hour, hour).flatMapIndexed { run, ago ->
            ids.mapIndexed { i, id ->
                val last = run == 1
                SyncReport(
                    "g$run-$i", id, id, now - ago + i * 2_000L, 1_000, sourceCount = 8,
                    added = if (!last) 1 else if (id == deezerDown) 3 else if (i == 0) 2 else 0,
                    removed = if (last && id == deezerDown) 1 else 0,
                    unmatched = if (last && id == deezerDown) listOf("Song A", "Song B") else emptyList(),
                )
            }
        }
        val plainReports = listOf(
            SyncReport("p0", "plain", "Chill", now - 2 * hour, 1_000, sourceCount = 8, added = 1),
            SyncReport("p1", "plain", "Chill", now - hour, 1_000, sourceCount = 8, added = 1),
        )
        val lastOf = (groupReports + plainReports).groupBy { it.jobId }.mapValues { (_, r) -> r.maxBy { it.startedEpoch } }
        store.update { d ->
            d.copy(
                jobs = (d.jobs + SyncJob("plain", "Chill", PlaylistRef("spotify", FakeWorld.SPOTIFY_CHILL, "Chill Evening"), PlaylistRef("deezer", "d1", "Chill")))
                    .map { j -> lastOf[j.id]?.let { j.copy(lastReportId = it.id, lastRunEpoch = it.startedEpoch) } ?: j },
                reports = d.reports + groupReports + plainReports,
                settings = d.settings.copy(minimalSyncs = minimal, cardSize = CardSize.LARGE.key),
            )
        }
        store.flush()
    }

    /** The group runs, Deezer being synced ("Deezer · 2 of 2 · Matching 3 of 8"); with [plainToo] the classic sync runs as well. */
    private fun whileBothRun(plainToo: Boolean, block: () -> Unit) {
        val order = Nway.order(app.env, group).map { it.id }
        val deezerDown = members().first { it.playlist.provider == "deezer" }.down.id
        try {
            SyncState.batchStart("batch-height", group.id, order)
            order.filter { it != deezerDown }.forEach { SyncState.batchDone("batch-height", it, SyncState.JobResult(added = 1)) }
            SyncState.start(deezerDown)
            SyncState.progress(deezerDown, Progress(Progress.Step.FETCH_TARGET, 0, 0))
            if (plainToo) { SyncState.start("plain"); SyncState.progress("plain", Progress(Progress.Step.FETCH_TARGET, 0, 0)) }
            block()
        } finally {
            SyncState.finish(deezerDown)
            if (plainToo) SyncState.finish("plain")
            SyncState.batchEnd("batch-height")
        }
    }

    private fun node(tag: String, name: String): SemanticsNode =
        compose.onAllNodes(hasTestTag(tag) and hasText(name)).onFirst().fetchSemanticsNode()

    /**
     * The heights (dp) of the group's and the classic sync's [tag] nodes, once both have held them a
     * while (a card animates to a new size, and pops once a run is over).
     */
    private fun settledHeights(tag: String, what: String): Pair<Float, Float> {
        var last = -1f to -1f
        var since = 0L
        waitFor("$what holding their heights") {
            val h = node(tag, "Trip mix").boundsInRoot.height to node(tag, "Chill").boundsInRoot.height
            val now = android.os.SystemClock.uptimeMillis()
            if (h != last) { last = h; since = now }
            h.first > 0f && now - since >= 700
        }
        return last.first / density() to last.second / density()
    }

    private fun assertSameHeight(tag: String, what: String) {
        val (g, c) = settledHeights(tag, what)
        log("$what: multi sync $g dp, classic $c dp")
        assertTrue("$what: the multi sync is $g dp tall, the classic sync $c dp", abs(g - c) <= 1f)
    }

    @Test
    fun theRunningMultiSyncsTileIsAsTallAsAClassicOne() {
        seed(minimal = true)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        waitFor("two tiles") { compose.onAllNodes(hasTestTag(JOB_TILE_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 2 }
        assertSameHeight(JOB_TILE_TAG, "tiles at rest")

        whileBothRun(plainToo = false) {
            // The step folded into the status line: one line, no line of its own.
            waitText(s(R.string.progress_fetch_target), substring = true)
            assertTrue(exists(s(R.string.nway_run_place, com.xlollx.songport.providers.Providers.byId("deezer")!!.label(app.env), 2, 2) + " · " + s(R.string.progress_fetch_target)))
            snapshot("multi-height-tile-running")
            assertSameHeight(JOB_TILE_TAG, "the group's tile running, the other at rest")
        }
        whileBothRun(plainToo = true) {
            waitFor("both running") { SyncState.running.value.containsKey("plain") }
            assertSameHeight(JOB_TILE_TAG, "both tiles running")
        }
        assertSameHeight(JOB_TILE_TAG, "tiles at rest again")

        // The search counts the syncs the list shows (the multi sync once), as the filter's line does.
        waitNode("the search's count", hasText(plural(R.plurals.syncs_find_n, 2, 2)))
        val field = compose.onNode(hasTestTag(SYNC_SEARCH_TAG))
        field.performTextInput("Chill")
        waitText(plural(R.plurals.filter_shown, 2, 1, 2))
        field.performTextClearance()
        waitNode("the search's count again", hasText(plural(R.plurals.syncs_find_n, 2, 2)))
    }

    @Test
    fun theMultiSyncsCardHasAClassicCardsFiguresAndHeightInEverySize() {
        seed(minimal = false)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        waitFor("two cards") { compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 2 }

        // Large: the four figures of its last run, added up over its services, and its last runs.
        val card = node(JOB_CARD_TAG, "Trip mix").boundsInRoot
        fun inCard(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .any { card.contains(it.boundsInRoot.center) }
        waitFor("the group's figures") { inCard("+5") && inCard("−1") && inCard("2") }
        // The classic card counts its playlist; the multi sync's, its source (a word as short, the tile as large).
        assertEquals("an \"in playlist\" figure on the classic card only", 1, compose.onAllNodes(hasText(s(R.string.stat_in_playlist)), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("an \"in source\" figure on the multi sync's card", inCard(s(R.string.stat_in_source)))
        assertTrue("its last runs", inCard(plural(R.plurals.stat_history, 2, 2, 0)))
        snapshot("multi-height-card-large")
        // Each figure opens what it counts: the songs added, the group's page for those not found.
        click("+5")
        waitText(s(R.string.stat_list_added))
        androidx.test.espresso.Espresso.pressBack()
        waitGone(s(R.string.stat_list_added))

        for (size in CardSize.entries) {
            store.updateSettings { it.copy(cardSize = size.key) }
            waitFor("${size.key} stored") { store.data.settings.cardSize == size.key }
            assertSameHeight(JOB_CARD_TAG, "${size.key} cards at rest")
            whileBothRun(plainToo = true) {
                waitFor("both running") { SyncState.running.value.containsKey("plain") }
                assertSameHeight(JOB_CARD_TAG, "${size.key} cards running")
                snapshot("multi-height-card-${size.key}-running")
            }
        }
    }

    /** While the group runs it is not changed: Edit and Preview off in its card's menu, and its editor's save held back, saying why. */
    @Test
    fun aRunningMultiSyncIsNotEdited() {
        seed(minimal = false)
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        waitFor("two cards") { compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 2 }
        fun groupMenu() {
            val card = node(JOB_CARD_TAG, "Trip mix").boundsInRoot
            val more = compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasContentDescription(s(R.string.more_actions)))
            val i = more.fetchSemanticsNodes().indexOfFirst { card.contains(it.boundsInRoot.center) }
            assertTrue("the group's ⋮", i >= 0)
            tap(more[i], "the group's menu")
            waitText(s(R.string.edit))
        }
        fun menuItem(id: Int) = compose.onNode(androidx.compose.ui.test.hasClickAction() and hasText(s(id)))

        // The menu open as the run starts: Edit and Preview go off.
        groupMenu()
        whileBothRun(plainToo = false) {
            waitFor("Edit off") { runCatching { menuItem(R.string.edit).assertIsNotEnabled() }.isSuccess }
            menuItem(R.string.preview).assertIsNotEnabled()
            androidx.test.espresso.Espresso.pressBack()
        }
        waitGone(s(R.string.edit))

        // At rest: Preview opens the group's page, not one of its syncs' previews.
        groupMenu()
        click(s(R.string.preview))
        waitText(s(R.string.nway_delete))
        back()

        // Its editor open as a run starts: no save until it is over.
        waitFor("two cards again") { compose.onAllNodes(hasTestTag(JOB_CARD_TAG)).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 2 }
        groupMenu()
        click(s(R.string.edit))
        waitText(s(R.string.nway_editor_edit))
        val save = compose.onNode(androidx.compose.ui.test.hasClickAction() and hasText(s(R.string.preview)))
        save.assertIsEnabled()
        whileBothRun(plainToo = false) {
            waitText(s(R.string.nway_edit_busy))
            save.assertIsNotEnabled()
        }
        waitGone(s(R.string.nway_edit_busy))
        save.assertIsEnabled()
    }
}

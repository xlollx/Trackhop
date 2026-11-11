package com.xlollx.songport.flows

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's row of services with five accounts: more tiles than fit, so the row says it goes
 * on (a cut tile, a scroll bar, "N services, scroll for more"), and a sync whose source is the
 * fifth service opens with that tile selected and wholly in view.
 */
@RunWith(AndroidJUnit4::class)
class ServiceRowFlowTest : FlowTest() {
    override val connectedAtStart: List<String> = listOf("spotify", "ytm", "youtube", "deezer", "jellyfin")

    @Test
    fun theFifthServiceOpensSelectedAndInView() {
        FakeWorld.playlists["jellyfin"] = mutableListOf(FakeWorld.FakePlaylist("jf1", "Home Mix", FakeWorld.songs("jellyfin", 0 until 4)))
        store.update {
            it.copy(jobs = listOf(SyncJob("job-1", "Home Mix", PlaylistRef("jellyfin", "jf1", "Home Mix"), PlaylistRef("deezer", "d1", "Home Mix"))))
        }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Home Mix")
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
        // The services are in the source picker's sheet.
        openSourcePicker()

        // The row tells TalkBack it scrolls. (The strings first: the matcher runs on the main thread, where plural() cannot.)
        val hints = (2..20).map { k -> plural(R.plurals.service_tiles_scroll, k, k) }.toSet()
        waitNode("the row's scroll hint", SemanticsMatcher("says it scrolls") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it in hints }
        })
        // Jellyfin, the fifth, is the selected source tile, and not cut by the edge.
        val tile = hasClickAction() and isSelected() and hasText("Jellyfin", substring = true)
        // The merged tree: the tile's name is on its children, its click and selection on the tile.
        waitFor("Jellyfin selected") { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
        waitFor("Jellyfin's tile wholly in view") {
            compose.onAllNodes(tile).fetchSemanticsNodes().any { n -> n.boundsInRoot.width >= n.size.width - 1f && n.boundsInRoot.width > 0f }
        }
    }
}

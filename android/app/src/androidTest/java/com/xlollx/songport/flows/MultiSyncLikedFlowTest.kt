package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.ui.NEW_SYNC_MULTI_TAG
import com.xlollx.songport.ui.NWAY_SOURCE_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.1.3: a service's Liked songs in the multi-sync editor, as the classic editor offers it, where it
 * loses nothing. Spotify's lists every like: the source, or any target but an exact copy. YouTube
 * Music's leaves a liked video out of Liked music: shown as a source but not offered, and as a target
 * it only keeps its additions. Deezer's fake has no liked songs: none offered.
 */
@RunWith(AndroidJUnit4::class)
class MultiSyncLikedFlowTest : FlowTest() {
    override val preview2: Boolean = true
    override val connectedAtStart: List<String> = listOf("spotify", "deezer", "ytm")

    private fun label(id: String) = Providers.byId(id)!!.label(app.env)
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    private fun tapTag(tag: String) { waitFor(tag) { tagged(tag) }; tap(compose.onAllNodes(hasTestTag(tag)).onFirst(), tag) }
    private fun service(id: String) { waitText(s(R.string.shared_service)); click(s(R.string.shared_service)); click(label(id)) }

    @Test
    fun likedSongsIsTheSourceOrATargetWhereItLosesNothing() {
        FakeWorld.useLikes()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        tapTag(NEW_SYNC_MULTI_TAG)
        waitText(s(R.string.nway_editor_new))

        // The source: YouTube Music's Liked songs is there, not offered, and says why.
        tapTag(NWAY_SOURCE_ROW_TAG)
        service("ytm")
        click(s(R.string.shared_playlist))
        waitText(s(R.string.nway_liked_target_only, label("ytm")))
        snapshot("multi-liked-1-source-not-here")
        Espresso.pressBack()
        // Spotify's, first, as the classic editor lists it.
        service("spotify")
        click(s(R.string.shared_playlist))
        click(s(R.string.liked_songs))
        click(s(R.string.ok))
        waitText("♥ " + s(R.string.liked_songs))

        // A target: YouTube Music's Liked songs keeps its additions, the only way it can follow.
        click(s(R.string.nway_add_target))
        service("ytm")
        click(s(R.string.shared_playlist))
        click(s(R.string.liked_songs))
        waitText(s(R.string.nway_liked_keep_desc))
        waitText(s(R.string.nway_liked_no_exact))
        waitText(s(R.string.nway_liked_target_only, label("ytm")))
        snapshot("multi-liked-2-target-ways")
        click(s(R.string.ok))
        waitText(label("ytm") + " · " + s(R.string.shared_mode_keep))
        // And a playlist.
        click(s(R.string.nway_add_target))
        service("deezer")
        click(s(R.string.shared_playlist))
        click("Workout")
        click(s(R.string.ok))
        waitText(label("deezer") + " · " + s(R.string.shared_mode_keep))

        click(s(R.string.preview))
        waitText(s(R.string.nway_first_title))
        click(s(R.string.nway_create_sync))
        waitFor("the group made, whole") { Nway.groups(app.env).singleOrNull()?.let { g -> Nway.members(app.env, g).size == 3 } == true }
        val group = Nway.groups(app.env).single()
        val draft = NwayEdit.draftOf(group, Nway.members(app.env, group))
        assertEquals("spotify" to MusicProvider.LIKED_ID, draft.source!!.provider to draft.source!!.playlistId)
        assertEquals(listOf(Triple("ytm", MusicProvider.LIKED_ID, NwayEdit.Mode.KEEP), Triple("deezer", FakeWorld.DEEZER_WORKOUT, NwayEdit.Mode.KEEP)),
            draft.targets.map { Triple(it.provider, it.playlistId, it.mode) })
        // The card names it.
        waitText(label("spotify") + " · ♥ " + s(R.string.liked_songs), substring = true)
        waitFor("nothing running") { com.xlollx.songport.sync.SyncState.running.value.isEmpty() && com.xlollx.songport.sync.SyncState.batches.value.isEmpty() }
        assertTrue("the songs of the group liked on YouTube Music", FakeWorld.playlists.getValue("ytm").first { it.id == MusicProvider.LIKED_ID }.tracks.size >= 4)
    }
}

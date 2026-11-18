package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.ui.EDITOR_TWO_WAY_TAG
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
        // Back closes the list. Its popup belongs to the dialog, which has lost focus to it, so Espresso
        // finds no root to press Back on: the key goes to the focused window instead.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitGone(s(R.string.nway_liked_target_only, label("ytm")))
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

    /** The classic editor of the one sync on Syncs, from its card's menu. */
    private fun editTheSync(name: String) {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(name)
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.editor_title_edit))
    }

    private fun twoWaySwitch() = compose.onNode(hasTestTag(EDITOR_TWO_WAY_TAG), useUnmergedTree = true)

    @Test
    fun theClassicEditorDoesNotOfferTwoWayWithLikedSongsThatLeaveLikesOut() {
        FakeWorld.useLikes()
        store.upsertJob(SyncJob("to-likes", "To my likes", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("ytm", MusicProvider.LIKED_ID, "Liked songs")))
        editTheSync("To my likes")
        // Two-way is there, off and greyed, with why.
        waitText(s(R.string.editor_bidirectional_likes_hidden, label("ytm")))
        waitFor("the switch") { tagged(EDITOR_TWO_WAY_TAG) }
        twoWaySwitch().assertIsNotEnabled().assertIsOff()
        snapshot("classic-liked-two-way-not-offered")
    }

    @Test
    fun aTwoWaySyncSavedBeforeKeepsRunningAndItsEditorSaysWhy() {
        FakeWorld.useLikes()
        store.upsertJob(SyncJob("pair-a", "Likes pair", PlaylistRef("spotify", FakeWorld.SPOTIFY_CHILL, "Chill Evening"), PlaylistRef("ytm", MusicProvider.LIKED_ID, "Liked songs"),
            linkedJobId = "pair-b", twoWayRemovals = true))
        store.upsertJob(SyncJob("pair-b", "Likes pair ↔", PlaylistRef("ytm", MusicProvider.LIKED_ID, "Liked songs"), PlaylistRef("spotify", FakeWorld.SPOTIFY_CHILL, "Chill Evening"),
            linkedJobId = "pair-a", twoWayRemovals = true, twin = true))
        editTheSync("Likes pair")
        // Not switched off behind the user's back: still two-way, and the editor says what the engine holds back.
        waitText(s(R.string.editor_bidirectional_likes_kept, label("ytm")))
        waitFor("the switch") { tagged(EDITOR_TWO_WAY_TAG) }
        twoWaySwitch().assertIsEnabled().assertIsOn()
        remade()
        waitText(s(R.string.editor_bidirectional_likes_kept, label("ytm")))
        assertEquals("pair-b", store.job("pair-a")!!.linkedJobId)
    }
}

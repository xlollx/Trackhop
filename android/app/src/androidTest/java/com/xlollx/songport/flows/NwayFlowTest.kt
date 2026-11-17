package com.xlollx.songport.flows

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.platform.env
import com.xlollx.songport.shared.Nway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One playlist on several services (1.18): made from New sync, with its first-run sheet; one card in
 * Syncs in place of its syncs; its page. Every dialog and sheet on the way is still there, as it
 * was, when Android makes the activity anew (owner's rule, 14/11).
 */
@RunWith(AndroidJUnit4::class)
class NwayFlowTest : FlowTest() {
    override val preview2: Boolean = true

    private fun openCreate() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        click(s(R.string.nway_entry_title))
        waitText(s(R.string.nway_members))
    }

    /** The add dialog: [service] when it is not the first one, then [playlist] by name. */
    private fun pick(service: String?, playlist: String) {
        click(s(R.string.nway_add))
        waitText(s(R.string.shared_service))
        if (service != null) { click(s(R.string.shared_service)); click(service) }
        click(s(R.string.shared_playlist))
        click(playlist)
    }

    @Test
    fun aGroupIsMadeThroughDialogsThatSurviveTheActivityMadeAnew() {
        openCreate()
        compose.onNode(hasSetTextAction() and hasText(s(R.string.nway_name))).performTextInput("Trip mix")
        // No playlist yet: the button waits, and says why.
        waitText(s(R.string.nway_need_two))

        // The add dialog, with a playlist chosen, made anew.
        pick(null, "Road Trip")
        remade()
        waitText(s(R.string.nway_role_main_desc))
        // The field's value and its label are one node only in the merged tree: unmerged, the label is a child of its own.
        waitFor("the playlist kept in the dialog") {
            compose.onAllNodes(hasText("Road Trip") and hasText(s(R.string.shared_playlist))).fetchSemanticsNodes().isNotEmpty()
        }
        click(s(R.string.ok))
        waitText(s(R.string.nway_role_main))

        // A second one, which follows.
        pick("Deezer", "Workout")
        click(s(R.string.nway_role_follows))
        click(s(R.string.ok))
        waitGone(s(R.string.nway_need_two))

        // A member's own dialog (its role, or out of the group), made anew.
        click("Workout")
        waitText(s(R.string.nway_follow_mode))
        remade()
        waitText(s(R.string.nway_follow_mode))
        click(s(R.string.shared_mode_exact))
        click(s(R.string.ok))

        // The first-run sheet, made anew: still there, with the name and the playlists behind it.
        click(s(R.string.preview))
        waitText(s(R.string.nway_first_title))
        remade()
        waitText(s(R.string.nway_first_title))
        waitText(s(R.string.nway_first_tip))
        snapshot("nway-first-run")
        click(s(R.string.nway_create_sync))

        waitFor("the group made") { Nway.groups(app.env).size == 1 }
        val group = Nway.groups(app.env).single()
        assertEquals("Trip mix", group.name)
        val members = Nway.members(app.env, group)
        assertEquals(listOf("spotify", "deezer"), members.map { it.playlist.provider })
        assertEquals(listOf(true, false), members.map { it.main })
        assertFalse("the follower is an exact copy, as chosen", members[1].keepMine)

        // One card for the group; its three syncs are not cards.
        waitText(s(R.string.nway_card_main))
        assertTrue(exists("Trip mix"))
        assertFalse(exists("Trip mix · Spotify"))
        snapshot("nway-card")
    }

    @Test
    fun theGroupsPageKeepsItsDialogsAndDeletesTheGroup() {
        val made = Nway.create(app.env, "Road Trip", listOf(
            PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") to true,
            PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") to false,
        ))
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.nway_card_main))
        click(s(R.string.settings_open))
        waitText(s(R.string.nway_delete))
        snapshot("nway-page")

        // Removing a playlist asks first; the question survives the activity made anew.
        clickDesc(s(R.string.more_actions), index = 1)
        click(s(R.string.nway_remove_member))
        waitText(s(R.string.nway_remove_member_note))
        remade()
        waitText(s(R.string.nway_remove_member_note))
        click(s(R.string.cancel))
        assertEquals(2, Nway.members(app.env, made).size)

        // So does the schedule's choice.
        click(s(R.string.schedule_6h))
        waitText(s(R.string.schedule_daily))
        remade()
        waitText(s(R.string.schedule_daily))
        click(s(R.string.schedule_daily))
        waitFor("the schedule on every sync of the group") { Nway.members(app.env, made).all { it.down.schedule == com.xlollx.songport.model.Schedule.DAILY } }

        // And deleting the group: asked, kept across the activity made anew, then done. The playlists stay.
        click(s(R.string.nway_delete))
        waitText(s(R.string.nway_delete_confirm))
        remade()
        waitText(s(R.string.nway_delete_confirm))
        click(s(R.string.delete))
        waitFor("the group gone") { Nway.groups(app.env).isEmpty() && store.data.jobs.isEmpty() }
        waitText(s(R.string.empty_syncs_title))
        assertEquals(8, FakeWorld.playlists.getValue("spotify").first { it.id == FakeWorld.SPOTIFY_ROAD }.tracks.size)
    }
}

/** With the release switch off nothing leads to a group, and the editor is as it was. */
@RunWith(AndroidJUnit4::class)
class NwayHiddenFlowTest : FlowTest() {
    @Test
    fun theEditorDoesNotOfferAGroupBefore118() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_title_new))
        assertFalse(exists(s(R.string.nway_entry_title)))
    }
}

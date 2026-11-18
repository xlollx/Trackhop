package com.xlollx.songport.flows

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.ui.NEW_SYNC_CLASSIC_TAG
import com.xlollx.songport.ui.NEW_SYNC_MULTI_TAG
import com.xlollx.songport.ui.NWAY_SOURCE_ROW_TAG
import com.xlollx.songport.ui.nwayModeTag
import com.xlollx.songport.ui.nwayTargetTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.1.1, the preview approved on 18/11: "New sync" asks which kind; a multi sync is made and edited
 * in an editor with the classic one's look and steps (1 Source, 2 Targets, 3 Options), each target
 * with one way to follow chosen in its sheet; Preview at the bottom says what saving does, and
 * saving is all of it or nothing. Every sheet and dialog on the way is still there, as it was, when
 * Android makes the activity anew.
 */
@RunWith(AndroidJUnit4::class)
class MultiSyncEditorFlowTest : FlowTest() {
    override val preview2: Boolean = true

    private fun deezer() = Providers.byId("deezer")!!.label(app.env)
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    private fun tapTag(tag: String) { waitFor(tag) { tagged(tag) }; tap(compose.onAllNodes(hasTestTag(tag)).onFirst(), tag) }

    /** The playlist picker: [service] when it is not the first one, then [playlist] by name. */
    private fun pick(service: String?, playlist: String) {
        waitText(s(R.string.shared_service))
        if (service != null) { click(s(R.string.shared_service)); click(service) }
        click(s(R.string.shared_playlist))
        click(playlist)
    }

    @Test
    fun newSyncAsksWhichKindAndEachRowOpensItsEditor() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitFor("the sheet") { tagged(NEW_SYNC_CLASSIC_TAG) && tagged(NEW_SYNC_MULTI_TAG) }
        waitText(s(R.string.new_sync_classic_desc))
        waitText(s(R.string.new_sync_multi_desc))
        snapshot("new-sync-1-which-kind")
        // The sheet is still there when Android makes the activity anew.
        remade()
        waitFor("the sheet, made anew") { tagged(NEW_SYNC_CLASSIC_TAG) && tagged(NEW_SYNC_MULTI_TAG) }

        // Classic sync: the editor as ever, with the two buttons that lead to the other kind.
        tapTag(NEW_SYNC_CLASSIC_TAG)
        waitText(s(R.string.editor_title_new))
        waitText(s(R.string.nway_one_target))
        // "Several targets": the multi-sync editor.
        click(s(R.string.nway_several_targets))
        waitText(s(R.string.nway_editor_new))
        // And back, "One target".
        click(s(R.string.nway_one_target))
        waitText(s(R.string.editor_title_new))
        back()

        // Multi sync, from the sheet.
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        tapTag(NEW_SYNC_MULTI_TAG)
        waitText(s(R.string.nway_editor_new))
        waitText(s(R.string.nway_targets))
        waitText(s(R.string.nway_need_source))
        assertFalse("nothing is made by looking", Nway.groups(app.env).isNotEmpty())
    }

    @Test
    fun aMultiSyncIsMadeThroughTheEditor() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        tapTag(NEW_SYNC_MULTI_TAG)
        waitText(s(R.string.nway_editor_new))

        // 1 Source: its picker, made anew with the playlist chosen.
        tapTag(NWAY_SOURCE_ROW_TAG)
        pick(null, "Road Trip")
        remade()
        waitFor("the playlist kept in the dialog") { compose.onAllNodes(hasText("Road Trip") and hasText(s(R.string.shared_playlist))).fetchSemanticsNodes().isNotEmpty() }
        click(s(R.string.ok))
        waitText(s(R.string.nway_need_target))

        // 2 Targets: one added; it keeps its additions until told otherwise.
        click(s(R.string.nway_add_target))
        pick("Deezer", "Workout")
        click(s(R.string.ok))
        waitText(deezer() + " · " + s(R.string.shared_mode_keep))
        waitGone(s(R.string.nway_need_target))

        // Its sheet: one way among three; still there, with the choice, when Android makes the activity anew.
        tapTag(nwayTargetTag(0))
        waitText(s(R.string.nway_mode_two_way_desc))
        waitText(s(R.string.nway_make_source))
        waitText(s(R.string.nway_remove_target))
        snapshot("multi-editor-1-target-sheet")
        tapTag(nwayModeTag(NwayEdit.Mode.EXACT))
        remade()
        waitText(s(R.string.nway_mode_exact_desc))
        Espresso.pressBack()
        waitGone(s(R.string.nway_mode_exact_desc))
        waitText(deezer() + " · " + s(R.string.shared_mode_exact))
        snapshot("multi-editor-2-filled")

        // Preview, not Save: what the first sync does; made anew, still there. Nothing is made until "Create and sync".
        click(s(R.string.preview))
        waitText(s(R.string.nway_first_title))
        remade()
        waitText(s(R.string.nway_first_title))
        assertTrue("nothing made yet", Nway.groups(app.env).isEmpty() && store.data.jobs.isEmpty())
        click(s(R.string.nway_create_sync))

        waitFor("the group made, whole") { Nway.groups(app.env).singleOrNull()?.let { g -> Nway.members(app.env, g).size == 2 } == true }
        val group = Nway.groups(app.env).single()
        // Named after its source.
        assertEquals("Road Trip", group.name)
        val draft = NwayEdit.draftOf(group, Nway.members(app.env, group))
        assertEquals("spotify", draft.source!!.provider)
        assertEquals(listOf("deezer" to NwayEdit.Mode.EXACT), draft.targets.map { it.provider to it.mode })
        // Hourly, as the editor offered; every sync of the group alike.
        assertEquals(setOf(60), Nway.jobsOf(store.data, group.id).map { com.xlollx.songport.sync.Timing.interval(it) }.toSet())
        // Back on Syncs: one card for it.
        waitFor("its card") { compose.onAllNodes(hasTestTag(com.xlollx.songport.ui.JOB_CARD_TAG)).fetchSemanticsNodes().size == 1 }
        waitFor("nothing running") { com.xlollx.songport.sync.SyncState.running.value.isEmpty() && com.xlollx.songport.sync.SyncState.batches.value.isEmpty() }
    }

    private fun seeded() = Nway.create(app.env, "Trip mix", listOf(
        PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip") to true,
        PlaylistRef("deezer", FakeWorld.DEEZER_WORKOUT, "Workout") to false,
    ))

    @Test
    fun anExistingGroupIsEditedAndOnlyWhatChangedIsSaved() {
        val group = seeded()
        val before = store.data.jobs
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")

        // Edit, from its card's menu: the editor, with the group as it is. Made before 2.1.1: Spotify, the main one, is the source; Deezer follows keeping its additions.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.nway_editor_edit))
        waitText(deezer() + " · " + s(R.string.shared_mode_keep))
        assertFalse("the two buttons are for a new sync only", exists(s(R.string.nway_one_target)))

        // Saved as it is: nothing changes, and the sheet says so.
        click(s(R.string.preview))
        waitText(s(R.string.nway_changes_title))
        waitText(s(R.string.nway_change_none))
        click(s(R.string.save))
        waitText("Trip mix")
        waitFor("back on Syncs") { !exists(s(R.string.nway_editor_edit)) }
        assertEquals("not a sync was touched", before, store.data.jobs)

        // Again: Deezer made two-way. The preview names the change; made anew, it is still there.
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.nway_editor_edit))
        tapTag(nwayTargetTag(0))
        tapTag(nwayModeTag(NwayEdit.Mode.TWO_WAY))
        Espresso.pressBack()
        waitText(deezer() + " · " + s(R.string.editor_bidirectional))
        click(s(R.string.preview))
        waitText(s(R.string.nway_changes_title))
        waitText(s(R.string.shared_mode_keep) + "  →  " + s(R.string.editor_bidirectional))
        snapshot("multi-editor-3-changes")
        remade()
        waitText(s(R.string.nway_changes_title))
        assertEquals("nothing saved by looking", before, store.data.jobs)
        click(s(R.string.save))
        waitFor("Deezer two-way") { Nway.members(app.env, group).first { it.playlist.provider == "deezer" }.main }
        // The source and its syncs as they were.
        assertEquals(before.filter { it.target.provider == "spotify" || it.source.provider == "spotify" }, store.data.jobs.filter { it.target.provider == "spotify" || it.source.provider == "spotify" })

        // "Make it the source", then out with the old one: Deezer is the source and the card.
        waitFor("back on Syncs") { !exists(s(R.string.nway_editor_edit)) }
        clickDesc(s(R.string.more_actions))
        click(s(R.string.edit))
        waitText(s(R.string.nway_editor_edit))
        tapTag(nwayTargetTag(0))
        click(s(R.string.nway_make_source))
        waitGone(s(R.string.nway_make_source))
        // The old source is a two-way target now.
        waitText(Providers.byId("spotify")!!.label(app.env) + " · " + s(R.string.editor_bidirectional))
        click(s(R.string.preview))
        waitText(s(R.string.nway_change_source))
        click(s(R.string.save))
        waitFor("Deezer the source") { Nway.groups(app.env).single().let { g -> Nway.source(g, Nway.members(app.env, g))?.playlist?.provider == "deezer" } }
    }

    @Test
    fun thePageLeadsToTheEditorAndTheEditorBackToThePreviousScreen() {
        seeded()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Trip mix")
        click("Trip mix")
        waitText(s(R.string.nway_delete))
        // Edit, on the page.
        click(s(R.string.edit))
        waitText(s(R.string.nway_editor_edit))
        // "Use the previous screen": the group's page, with each playlist's own menu, as before.
        click(s(R.string.nway_previous_screen))
        waitText(s(R.string.nway_delete))
        clickDesc(s(R.string.more_actions), index = 1)
        waitText(s(R.string.nway_make_main))
        Espresso.pressBack()

        // With something changed in the editor, leaving it asks first; the question survives the activity made anew.
        click(s(R.string.edit))
        waitText(s(R.string.nway_editor_edit))
        tapTag(nwayTargetTag(0))
        click(s(R.string.nway_remove_target))
        waitText(s(R.string.nway_need_target))
        click(s(R.string.cancel))
        waitText(s(R.string.discard_changes_title))
        remade()
        waitText(s(R.string.discard_changes_title))
    }
}

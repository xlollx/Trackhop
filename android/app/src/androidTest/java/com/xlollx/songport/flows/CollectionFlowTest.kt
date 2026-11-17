package com.xlollx.songport.flows

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.platform.env
import com.xlollx.songport.shared.Collections
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedCollection
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.ui.CollectionUi
import com.xlollx.songport.ui.SharedUi
import com.xlollx.songport.ui.writableServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Collections of shared playlists (2.1): a curator on another device shares several playlists with
 * one invite, meeting in a folder; this phone follows. The choice on the way in, the proposal of a
 * playlist added later and the questions before leaving are still there when Android makes the
 * activity anew; the collection is one card in Syncs, with a switch per playlist on its page.
 */
@RunWith(AndroidJUnit4::class)
class CollectionFlowTest : FlowTest() {
    override val preview2: Boolean = true

    /** The curator's device: the app's own services and texts, with a store, a vault and files of its own. */
    private class OtherDevice(base: Env, dir: File) : Env by base {
        override val dataDir: File = dir
        override val store = FileStore(dir)
        private val vault = HashMap<String, Tokens>()
        override val tokens = object : TokenVault {
            override fun get(providerId: String) = vault[providerId]
            override fun set(providerId: String, tokens: Tokens) { vault[providerId] = tokens }
            override fun clear(providerId: String) { vault.remove(providerId) }
            override fun ids(): Set<String> = vault.keys.toSet()
        }
    }

    private lateinit var folder: File
    private lateinit var giulia: Env
    private lateinit var cid: String

    @Before
    fun twoDevices() {
        CollectionUi.said.value = emptyMap()
        CollectionUi.busy.value = emptySet()
        folder = File(app.cacheDir, "collection-folder").apply { deleteRecursively(); mkdirs() }
        giulia = OtherDevice(app.env, File(app.cacheDir, "collection-giulia").apply { deleteRecursively(); mkdirs() })
    }

    private fun spotifyLists(vararg names: String) = runBlocking { FakeWorld.spotify.playlists(giulia) }.filter { it.name in names }

    /** Giulia's collection of [names], her playlists on Spotify; answers its invite link. */
    private fun curated(vararg names: String): String = runBlocking {
        val (c, settled) = Collections.create(giulia, "Giulia's playlists", "Giulia", "spotify", spotifyLists(*names), folder.path, "Trackhop")
        assertTrue(settled.failed.toString(), settled.complete)
        cid = c.id
        Collections.invite(giulia, c).link()
    }

    /** Nothing of the collection is being set up or synced: its buttons and switches are on. */
    private fun idle() = waitFor("the collection settled", 30_000) { cid !in CollectionUi.busy.value && com.xlollx.songport.sync.SyncState.running.value.isEmpty() }

    /**
     * [press] until [reached]: the page reads the collection's list when it opens (and again when the
     * activity is made anew), and its controls are off for that moment. Every action pressed here
     * gives the same result when asked twice.
     */
    private fun pressUntil(what: String, press: () -> Unit, reached: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!runCatching(reached).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what; on screen: ${screenTexts()}")
            runCatching(press)
            Thread.sleep(400)
        }
    }

    @Test
    fun aFollowerPicksOnTwoServicesAndTheCollectionIsOneCardWithASwitchPerPlaylist() {
        val link = curated("Road Trip", "Chill Evening")
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))

        // The invite opened: the join form says it is a collection, also after the activity is made anew.
        SharedUi.request.value = SharedUi.Request.Join(link)
        waitText(s(R.string.collection_join_hint))
        waitText("Giulia's playlists")
        remade()
        waitText(s(R.string.collection_join_hint))
        click(s(R.string.cancel))
        waitGone(s(R.string.collection_join_hint))

        // Its list joined (the folder is picked in the system's picker, which a test cannot drive): the card waits for the choice.
        Collections.join(app.env, Invite.parse(link)!!, folder.path, "Trackhop", "Leo")
        waitText(s(R.string.collection_choose_pending), substring = true)
        click(s(R.string.settings_open))
        waitText(s(R.string.collection_where))
        waitText(s(R.string.collection_all_note))

        // Pick: one of the two left out. On the two services.
        click(s(R.string.collection_pick))
        click("Chill Evening")
        waitText(s(R.string.collection_pick_note, 1, 2))
        val services = writableServices(app, needsCreate = true)
        click(services[1].label(app.env))
        waitText(s(R.string.collection_where_several))
        remade()
        waitText(s(R.string.collection_pick_note, 1, 2))
        waitText(s(R.string.collection_where_several))
        snapshot("collection-choice")
        click(plural(R.plurals.collection_join_n, 1, 1))

        waitFor("the picked playlist set up on both services") { store.data.groups.size == 1 && store.data.jobs.size == 4 }
        idle()
        val c = Collections.get(app.env, cid)!!
        assertEquals(SharedCollection.PICK, c.mode)
        assertEquals(2, c.services.size)
        val group = store.data.groups.single()
        assertEquals("Road Trip", group.name)
        assertTrue("its own additions are kept, the default", group.keepMine && group.collectionId == cid)
        waitText(s(R.string.collection_n_of_m, 1, 2))
        snapshot("collection-page")

        // Its switch: off, the playlist's syncs stop and stay; on again, they run again.
        idle()
        pressUntil("the playlist's syncs off", { clickDesc("Road Trip") }) { store.data.jobs.none { it.enabled } }
        assertEquals(1, store.data.groups.size)
        idle()
        waitText(s(R.string.collection_n_of_m, 0, 2))
        pressUntil("the playlist's syncs on again", { clickDesc("Road Trip") }) { store.data.jobs.all { it.enabled } }
        idle()

        // Giulia adds a playlist: proposed here, not added by itself; the proposal survives the activity made anew.
        runBlocking { assertTrue(Collections.add(giulia, cid, spotifyLists("Old Favourites")).complete) }
        pressUntil("the proposal", { click(s(R.string.shared_sync_now)) }) { exists(s(R.string.collection_new_from, "Giulia")) }
        assertEquals(1, store.data.groups.size)
        remade()
        waitText(s(R.string.collection_new_from, "Giulia"))
        idle()
        pressUntil("the proposed playlist set up", { click(s(R.string.collection_add)) }) { store.data.groups.size == 2 && store.data.jobs.size == 8 }
        idle()
        waitGone(s(R.string.collection_new_from, "Giulia"))

        // In Syncs: one card for the collection, none for its playlists' syncs.
        back()
        waitText(s(R.string.shared_title))
        back()
        waitText(s(R.string.collection_n_of_m, 2, 3), substring = true)
        assertTrue(exists("Giulia's playlists"))
        assertFalse(exists("Road Trip · " + services[1].label(app.env)))
        snapshot("collection-card")

        // Leaving asks first; the question survives the activity made anew. The playlists stay.
        click(s(R.string.settings_open))
        waitText(s(R.string.collection_switch_note))
        idle()
        pressUntil("the question before leaving", { click(s(R.string.collection_leave)) }) { exists(s(R.string.collection_leave_confirm)) }
        remade()
        waitText(s(R.string.collection_leave_confirm))
        tap(compose.onAllNodes(hasClickAction() and hasText(s(R.string.collection_leave))).onLast(), "leave, confirmed")
        waitFor("the collection left") { store.data.collections.isEmpty() && store.data.groups.isEmpty() && store.data.jobs.isEmpty() }
        assertTrue(FakeWorld.playlists.getValue(services[1].id).any { it.name == "Road Trip" })
        waitText(s(R.string.empty_syncs_title))
    }

    @Test
    fun goingBackBeforeChoosingAsksAndLeavesNothing() {
        val link = curated("Road Trip", "Chill Evening")
        val before = FakeWorld.playlists.mapValues { it.value.size }
        Collections.join(app.env, Invite.parse(link)!!, folder.path, "Trackhop", "Leo")
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.collection_choose_pending), substring = true)
        click(s(R.string.settings_open))
        waitText(s(R.string.collection_where))
        back()
        waitText(s(R.string.collection_not_join_note))
        remade()
        waitText(s(R.string.collection_not_join_note))
        click(s(R.string.collection_not_join))
        waitFor("nothing left of the collection") { store.data.collections.isEmpty() && SharedGroups.group(app.env, cid) == null }
        assertEquals(before, FakeWorld.playlists.mapValues { it.value.size })
        waitText(s(R.string.empty_syncs_title))
    }

    @Test
    fun createOffersOnePlaylistOrSeveralAndTheFormKeepsWhatWasChosen() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        SharedUi.request.value = SharedUi.Request.List
        waitText(s(R.string.shared_empty_title))
        click(s(R.string.shared_create))
        waitText(s(R.string.collection_several_desc))
        remade()
        waitText(s(R.string.collection_several_desc))
        click(s(R.string.collection_several))
        waitText(s(R.string.collection_new))
        waitText("Chill Evening")
        // Every playlist is in until one is left out.
        val all = runBlocking { writableServices(app, needsCreate = false).first().playlists(app.env) }.count { it.ownedByMe }
        waitText(s(R.string.collection_chosen_n, all, all))
        click("Chill Evening")
        waitText(s(R.string.collection_chosen_n, all - 1, all))
        remade()
        waitText(s(R.string.collection_chosen_n, all - 1, all))
        waitText(plural(R.plurals.collection_create_n, all - 1, all - 1))
        snapshot("collection-new")
    }
}

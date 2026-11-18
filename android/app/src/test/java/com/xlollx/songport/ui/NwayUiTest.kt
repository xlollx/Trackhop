package com.xlollx.songport.ui

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** The Syncs list with a group of the user's own playlists in the store: its syncs are not cards, and moving a card lands where it was dropped. */
class NwayUiTest {
    private fun job(id: String) = SyncJob(id, id, PlaylistRef("spotify", "p$id", id), PlaylistRef("deezer", "q$id", id))
    private fun down(id: String, group: String) = SyncJob(id, id, PlaylistRef(SharedProvider.ID, group, "g"), PlaylistRef("deezer", "m$id", id))
    private fun group(id: String, transport: String) = SharedGroup(id, "Road trip", "k", "k", "", transport = transport)

    private val data = StoreData(
        jobs = listOf(job("a"), down("g1", "local"), down("g2", "local"), job("b"), down("s1", "friends"), job("c")),
        groups = listOf(group("local", Invite.LOCAL), group("friends", Invite.FOLDER)),
    )

    @Test fun onlyTheSyncsOfAGroupOfOnesOwnPlaylistsAreBehindItsCard() {
        assertEquals(setOf("g1", "g2"), NwayUi.memberJobIds(data))
        assertEquals(listOf("local"), NwayUi.groups(data).map { it.id })
        assertEquals(emptySet<String>(), NwayUi.memberJobIds(data.copy(groups = emptyList())))
    }

    /** A group with a source: the sync of its source is its card in the list, like any other; the rest of its syncs stay behind it. */
    @Test fun theSyncOfAGroupsSourceIsItsCardAndIsNotHidden() {
        val hub = PlaylistRef(SharedProvider.ID, "local", "g")
        val src = SyncJob("src", "Road trip · Spotify", hub, PlaylistRef("spotify", "p", "Road trip"), linkedJobId = "src-up", twoWayRemovals = true)
        val up = SyncJob("src-up", "Road trip · Spotify ↔", PlaylistRef("spotify", "p", "Road trip"), hub, linkedJobId = "src", twin = true)
        val d = data.copy(jobs = listOf(job("a"), src, up, down("g1", "local"), job("b")), groups = listOf(group("local", Invite.LOCAL).copy(jobId = "src", twinId = "src-up")))
        assertEquals(setOf("src", "src-up", "g1"), NwayUi.memberJobIds(d))
        assertEquals(setOf("src-up", "g1"), NwayUi.hiddenJobIds(d))
        assertEquals(listOf("a", "src", "b"), d.jobs.map { it.id }.filter { it !in NwayUi.hiddenJobIds(d) })
        // The card stands for all three syncs; any other for itself.
        assertEquals(listOf("src", "src-up", "g1"), NwayUi.standsFor(d)(src).map { it.id })
        assertEquals(listOf("a"), NwayUi.standsFor(d)(d.jobs[0]).map { it.id })
        // Deleting it names the group once, not its services' syncs.
        assertEquals(listOf("a", "Road trip"), SyncSelection.deleteNames(d, setOf("a", "src")))
        // A card dropped after it lands after it in the store too, past the syncs behind it or not: the list shows the same.
        val store = FileStore(Files.createTempDirectory("nway-ui").toFile())
        store.update { d }
        store.moveJob("src", NwayUi.storeIndex(store.data, "src", 2))
        assertEquals(listOf("a", "b", "src"), store.data.jobs.map { it.id }.filter { it !in NwayUi.hiddenJobIds(store.data) })
    }

    /** The list shows a, b, s1, c: each drop, done on a real store, gives the order the list showed. */
    @Test fun aCardDroppedAmongTheShownOnesLandsThereInTheStore() {
        fun shownAfter(id: String, to: Int): List<String> {
            val store = FileStore(Files.createTempDirectory("nway-ui").toFile())
            store.update { data }
            store.moveJob(id, NwayUi.storeIndex(store.data, id, to))
            val hidden = NwayUi.memberJobIds(store.data)
            assertEquals("the group's syncs stay", 2, hidden.size)
            return store.data.jobs.map { it.id }.filter { it !in hidden }
        }
        assertEquals(listOf("b", "a", "s1", "c"), shownAfter("a", 1))
        assertEquals(listOf("b", "s1", "c", "a"), shownAfter("a", 3))
        assertEquals(listOf("c", "a", "b", "s1"), shownAfter("c", 0))
        assertEquals(listOf("a", "c", "b", "s1"), shownAfter("c", 1))
        assertEquals(listOf("a", "b", "s1", "c"), shownAfter("b", 1))
    }
}

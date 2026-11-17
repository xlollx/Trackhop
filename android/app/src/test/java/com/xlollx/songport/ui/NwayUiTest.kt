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

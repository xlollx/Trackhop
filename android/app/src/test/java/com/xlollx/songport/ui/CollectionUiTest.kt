package com.xlollx.songport.ui

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Collections
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedCollection
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Syncs list with a collection of shared playlists in the store: its playlists' syncs are behind its one card. */
class CollectionUiTest {
    private fun job(id: String) = SyncJob(id, id, PlaylistRef("spotify", "p$id", id), PlaylistRef("deezer", "q$id", id))
    private fun down(id: String, group: String) = SyncJob(id, id, PlaylistRef(SharedProvider.ID, group, "g"), PlaylistRef("deezer", "m$id", id))
    private fun up(id: String, group: String) = SyncJob(id, id, PlaylistRef("deezer", "m$id", id), PlaylistRef(SharedProvider.ID, group, "g"), twin = true)
    private fun group(id: String, collection: String = "", transport: String = Invite.FOLDER) = SharedGroup(id, "Road trip", "k", "k", "", transport = transport, collectionId = collection)
    private val collection = SharedCollection("col", group("col").copy(me = "m"), SharedCollection.PICK, listOf("deezer"), on = setOf("one"))

    private val data = StoreData(
        jobs = listOf(job("a"), down("c1", "one"), up("c2", "one"), down("c3", "two"), job("b"), down("s1", "friends"), down("n1", "local")),
        groups = listOf(group("one", "col"), group("two", "col"), group("friends"), group("local", transport = Invite.LOCAL)),
        collections = listOf(collection),
    )

    @Test fun theSyncsOfACollectionsPlaylistsAreBehindItsCard() {
        assertEquals(setOf("c1", "c2", "c3"), CollectionUi.memberJobIds(data))
        // With the group of one's own playlists: every card that stands for several syncs hides them.
        assertEquals(setOf("c1", "c2", "c3", "n1"), NwayUi.memberJobIds(data))
        // A shared playlist joined on its own keeps its card.
        assertFalse("s1" in NwayUi.memberJobIds(data))
    }

    @Test fun withoutACollectionNothingChanges() {
        assertTrue(CollectionUi.memberJobIds(data.copy(collections = emptyList())).isEmpty())
        assertEquals(setOf("n1"), NwayUi.memberJobIds(data.copy(collections = emptyList())))
        // A playlist whose collection is gone (left, half way) is not hidden behind a card that no longer exists.
        assertTrue(CollectionUi.memberJobIds(data.copy(collections = listOf(collection.copy(id = "other")))).isEmpty())
    }

    @Test fun aCardDroppedAmongTheShownOnesLandsThereWithACollectionInTheStore() {
        val store = com.xlollx.songport.data.FileStore(java.nio.file.Files.createTempDirectory("collection-ui").toFile())
        store.update { data }
        // The list shows a, b, s1: b dropped first.
        store.moveJob("b", NwayUi.storeIndex(store.data, "b", 0))
        val hidden = NwayUi.memberJobIds(store.data)
        assertEquals(listOf("b", "a", "s1"), store.data.jobs.map { it.id }.filter { it !in hidden })
        assertEquals(4, hidden.size)
    }

    @Test fun aFollowerWhoPicksIsProposedOnlyWhatTheyHaveNotAnswered() {
        fun entry(id: String) = Collections.Entry(id, id, "k", "", 3, 0)
        val entries = listOf(entry("one"), entry("two"), entry("three"))
        val c = collection.copy(known = setOf("one", "two"))
        assertEquals(listOf("three"), Collections.proposals(c, entries).map { it.groupId })
        assertTrue(Collections.proposals(c.copy(mode = SharedCollection.ALL), entries).isEmpty())
        assertTrue(Collections.proposals(c.copy(known = setOf("one", "two", "three")), entries).isEmpty())
    }
}

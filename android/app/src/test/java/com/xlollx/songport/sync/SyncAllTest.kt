package com.xlollx.songport.sync

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sync all: the classic syncs as before; each multi sync as one batch, in its group's order (the members' ways up, then the ways down). */
class SyncAllTest {
    private fun job(id: String, enabled: Boolean = true) = SyncJob(id, id, PlaylistRef("spotify", "p$id", id), PlaylistRef("deezer", "q$id", id), enabled = enabled)
    private fun hub(g: String) = PlaylistRef(SharedProvider.ID, g, g)
    private fun down(id: String, g: String, up: String? = null, enabled: Boolean = true) =
        SyncJob(id, id, hub(g), PlaylistRef("tidal", "m$id", id), linkedJobId = up, enabled = enabled)
    private fun up(id: String, g: String, down: String) = SyncJob(id, id, PlaylistRef("tidal", "m$down", down), hub(g), linkedJobId = down, twin = true)
    private fun group(id: String, transport: String = Invite.LOCAL) = SharedGroup(id, id, "k", "k", "", transport = transport)

    private val data = StoreData(
        jobs = listOf(
            job("a"), down("d1", "g", up = "u1"), job("b"), down("d2", "g"), up("u1", "g", "d1"), down("d3", "g", enabled = false),
            job("off", enabled = false), down("x1", "h"), down("f1", "friends"),
        ),
        groups = listOf(group("g"), group("h"), group("friends", Invite.FOLDER), group("empty")),
    )

    @Test fun eachMultiSyncIsOneBatchInItsGroupsOrder() {
        assertEquals(listOf("g" to listOf("u1", "d1", "d2"), "h" to listOf("x1")), SyncAll.groups(data))
    }

    @Test fun theWorkerRunsTheOthersAsBefore() {
        // A shared playlist with friends is not a multi sync: it runs with the classic syncs, as it always has.
        assertEquals(listOf("a", "b", "f1"), SyncAll.classic(data).map { it.id })
    }
}

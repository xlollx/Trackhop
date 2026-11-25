package com.xlollx.songport.shared

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Test

/** The syncs a list shows (the app's Syncs tab, the server's home page): a multi sync is one card, on its source's sync. */
class NwayListedTest {
    private fun job(id: String) = SyncJob(id, id, PlaylistRef("spotify", "p$id", id), PlaylistRef("deezer", "q$id", id))
    private val hub = PlaylistRef(SharedProvider.ID, "local", "g")
    private val src = SyncJob("src", "Road trip · Spotify", hub, PlaylistRef("spotify", "p", "Road trip"), linkedJobId = "src-up", twoWayRemovals = true)
    private val up = SyncJob("src-up", "Road trip · Spotify ↔", PlaylistRef("spotify", "p", "Road trip"), hub, linkedJobId = "src", twin = true)
    private val follower = SyncJob("g1", "Road trip · Deezer", hub, PlaylistRef("deezer", "m", "Road trip"))
    private val friends = SyncJob("s1", "Shared", PlaylistRef(SharedProvider.ID, "friends", "f"), PlaylistRef("deezer", "s", "Shared"))
    private val d = StoreData(
        jobs = listOf(job("a"), src, up, follower, job("b"), friends),
        groups = listOf(
            SharedGroup("local", "Road trip", "k", "k", "", transport = Invite.LOCAL, jobId = "src", twinId = "src-up"),
            SharedGroup("friends", "Shared", "k", "k", "", transport = Invite.FOLDER),
        ),
    )

    @Test fun aMultiSyncIsOneSyncInTheListOnItsSource() {
        assertEquals(setOf("src", "src-up", "g1"), Nway.memberJobIds(d))
        assertEquals(listOf("a", "src", "b", "s1"), Nway.listedSyncs(d).map { it.id })
        // A platform's own cards hide theirs too (the app's collections).
        assertEquals(listOf("a", "src", "s1"), Nway.listedSyncs(d, Nway.memberJobIds(d) + "b").map { it.id })
        // No multi sync: every sync.
        assertEquals(d.jobs.map { it.id }, Nway.listedSyncs(d.copy(groups = emptyList())).map { it.id })
    }
}

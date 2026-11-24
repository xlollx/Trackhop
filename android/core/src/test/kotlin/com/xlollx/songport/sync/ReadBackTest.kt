package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A transfer made once reads its playlist back: what the service answered "added" and did not keep is said. */
class ReadBackTest {
    /** As YouTube now and then: answers every addition, keeps none of [dropped]. */
    private class Forgetful(id: String) : MemoryService(id) {
        val dropped = HashSet<String>()
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = super.addTracks(ctx, playlistId, tracks.filter { it.id !in dropped })
    }

    private lateinit var env: ContractEnv
    private val src = MemoryService("msrc")
    private val dst = Forgetful("mdst")
    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() { env = ContractEnv(); Providers.testDoubles = listOf(src, dst); ReadBack.settleMs = 0 }
    @After fun tearDown() { Providers.testDoubles = emptyList(); ReadBack.settleMs = 4_000 }

    @Test fun aToolsPlaylistListsWhatTheServiceDidNotKeep() = runBlocking {
        dst.dropped += "b"
        val made = LibraryTools.createWith(env, dst, "Mix", "", listOf(t("a", "Alpha"), t("b", "Beta")))
        assertEquals(1, made.added)
        assertEquals(1, made.failed.size)
        assertTrue(made.failed.single(), made.failed.single().startsWith("Band – Beta (readback_not_kept"))
    }

    @Test fun nothingIsSaidWhenAllIsThere() = runBlocking {
        val made = LibraryTools.createWith(env, dst, "Mix", "", listOf(t("a", "Alpha"), t("b", "Beta")))
        assertEquals(2, made.added)
        assertTrue(made.failed.isEmpty())
    }

    @Test fun aOneOffTransferNotesIt() = runBlocking {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("da", "Alpha"), t("db", "Beta"))
        dst.lists["t"] = mutableListOf()
        dst.dropped += "db"
        val job = SyncJob(id = "once", name = "once", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t"), oneOff = true)
            .also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(job)
        assertEquals(1, r.added)
        assertEquals(listOf("Band – Beta"), r.notAdded)
        assertTrue(r.notes.toString(), r.notes.any { it == "note_readback_not_kept(1,mdst)" })
    }

    @Test fun aServiceListingAnotherIdCountsByName() {
        assertTrue(ReadBack.missing(listOf(t("other-id", "Alpha")), listOf(t("a", "Alpha"))).isEmpty())
        assertEquals(listOf("b"), ReadBack.missing(listOf(t("a", "Alpha")), listOf(t("a", "Alpha"), t("b", "Beta"))).map { it.id })
    }
}

package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Issue #32: a source that renames itself (a Spotify daylist) renames the target, unless the user named it. */
class SourceNameTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: MemoryService
    private lateinit var engine: SyncEngine

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc"); dst = MemoryService("mdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        val t = Track(id = "a", title = "Alpha", artists = listOf("Band"), durationMs = 200_000)
        src.lists["p"] = mutableListOf(t); dst.lists["t"] = mutableListOf(t.copy(id = "da"))
        src.names["p"] = "daylist • chill monday morning"
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(targetName: String, name: String = "daylist • sad tuesday night") = SyncJob(
        id = "job-" + System.nanoTime(), name = name,
        source = PlaylistRef("msrc", "p", "daylist • sad tuesday night"), target = PlaylistRef("mdst", "t", targetName),
    ).also { env.store.upsertJob(it) }

    @Test fun anUnnamedTargetFollowsTheSource() = runBlocking {
        dst.names["t"] = "daylist • sad tuesday night"
        val j = job("daylist • sad tuesday night")
        engine.run(j)
        assertEquals(listOf("t" to "daylist • chill monday morning"), dst.renames)
        val saved = env.store.job(j.id)!!
        assertEquals("daylist • chill monday morning", saved.source.playlistName)
        assertEquals("daylist • chill monday morning", saved.target.playlistName)
        assertEquals("daylist • chill monday morning", saved.name)
    }

    @Test fun aTargetTheUserNamedKeepsItsName() = runBlocking {
        dst.names["t"] = "Daylist"
        val j = job("Daylist", name = "My daylist")
        engine.run(j)
        assertTrue(dst.renames.isEmpty())
        val saved = env.store.job(j.id)!!
        assertEquals("daylist • chill monday morning", saved.source.playlistName)
        assertEquals("Daylist", saved.target.playlistName)
        assertEquals("My daylist", saved.name)
    }

    @Test fun aTargetRenamedOnTheServiceKeepsItsName() = runBlocking {
        dst.names["t"] = "Renamed in Spotify"
        engine.run(job("daylist • sad tuesday night"))
        assertTrue(dst.renames.isEmpty())
    }

    @Test fun thePreviewRenamesNothing() = runBlocking {
        dst.names["t"] = "daylist • sad tuesday night"
        engine.plan(job("daylist • sad tuesday night"), createTarget = false, interactive = true)
        assertTrue(dst.renames.isEmpty())
    }
}

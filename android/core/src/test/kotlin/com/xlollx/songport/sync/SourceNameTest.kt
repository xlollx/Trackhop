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

    @Test fun aSourceThatRenamesItselfOftenNoLongerRenamesTheSync() = runBlocking {
        dst.names["t"] = "daylist • sad tuesday night"
        val j = job("daylist • sad tuesday night")
        engine.run(j)
        assertEquals("daylist • chill monday morning", env.store.job(j.id)!!.name)
        src.names["p"] = "daylist • upbeat monday evening"
        engine.run(env.store.job(j.id)!!)
        val saved = env.store.job(j.id)!!
        assertEquals("daylist • upbeat monday evening", saved.source.playlistName)
        assertEquals("daylist • upbeat monday evening", saved.target.playlistName)
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

    @Test fun aNewSourceCoverIsCopiedWhereTheTargetTakesCovers() = runBlocking {
        val web = okhttp3.mockwebserver.MockWebServer()
        web.start()
        try {
            repeat(2) { web.enqueue(okhttp3.mockwebserver.MockResponse().setBody("jpeg-bytes")) }
            env.shrink = { it }
            dst.takesCovers = true
            dst.names["t"] = "Daylist"
            val j = job("Daylist", name = "My daylist")
            src.coverUrls["p"] = web.url("/a.jpg").toString()
            engine.run(j)
            // A sync from before covers were followed only notes the cover it sees: the target's own is kept.
            assertTrue(dst.covers.isEmpty())
            assertEquals(web.url("/a.jpg").toString(), env.store.job(j.id)!!.sourceCover)
            engine.run(env.store.job(j.id)!!)
            assertTrue(dst.covers.isEmpty())
            src.coverUrls["p"] = web.url("/b.jpg").toString()
            engine.run(env.store.job(j.id)!!)
            assertEquals(1, dst.covers.size)
            assertEquals("jpeg-bytes", String(dst.covers[0].second))
            assertEquals(web.url("/b.jpg").toString(), env.store.job(j.id)!!.sourceCover)
        } finally { web.shutdown() }
    }

    @Test fun aCoverTheTargetRefusesIsOneLineInTheLogAndTheSyncGoesOn() = runBlocking {
        val web = okhttp3.mockwebserver.MockWebServer()
        web.start()
        try {
            repeat(2) { web.enqueue(okhttp3.mockwebserver.MockResponse().setBody("jpeg-bytes")) }
            env.shrink = { it }
            dst.takesCovers = true
            dst.coverFailure = com.xlollx.songport.model.ProviderException("cover not set (HTTP 403)")
            dst.names["t"] = "Daylist"
            val j = job("Daylist", name = "My daylist")
            src.coverUrls["p"] = web.url("/a.jpg").toString()
            engine.run(j)
            src.coverUrls["p"] = web.url("/b.jpg").toString()
            src.lists["p"]!! += Track(id = "b", title = "Beta", artists = listOf("Band"), durationMs = 180_000)
            dst.catalogue += Track(id = "db", title = "Beta", artists = listOf("Band"), durationMs = 180_000)
            engine.run(env.store.job(j.id)!!)
            assertTrue(dst.covers.isEmpty())
            assertEquals(listOf("da", "db"), dst.lists["t"]!!.map { it.id })
            assertEquals(1, env.logs.count { it.contains("cover not copied") })
            // Not copied: the next run tries again.
            assertEquals(web.url("/a.jpg").toString(), env.store.job(j.id)!!.sourceCover)
        } finally { web.shutdown() }
    }

    @Test fun aNewTargetIsMadeAndFilledEvenWhenItsCoverIsRefused() = runBlocking {
        val web = okhttp3.mockwebserver.MockWebServer()
        web.start()
        try {
            web.enqueue(okhttp3.mockwebserver.MockResponse().setBody("jpeg-bytes"))
            env.shrink = { it }
            dst.takesCovers = true
            dst.coverFailure = com.xlollx.songport.model.ProviderException("unknown method setCover")
            dst.catalogue += Track(id = "da", title = "Alpha", artists = listOf("Band"), durationMs = 200_000)
            src.coverUrls["p"] = web.url("/a.jpg").toString()
            val j = SyncJob(id = "job-new", name = "New", source = PlaylistRef("msrc", "p", "daylist"), target = PlaylistRef("mdst", null, ""))
                .also { env.store.upsertJob(it) }
            engine.run(j)
            val made = env.store.job(j.id)!!.target.playlistId!!
            assertEquals(listOf("da"), dst.lists[made]!!.map { it.id })
            assertTrue(dst.covers.isEmpty())
            assertEquals(1, env.logs.count { it.contains("cover not set") })
        } finally { web.shutdown() }
    }
}

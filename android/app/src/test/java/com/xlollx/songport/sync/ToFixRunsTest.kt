package com.xlollx.songport.sync

import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Saved matches › To fix over the app's own store and real runs: what two syncs' runs leave not
 * found reads as one row, a search by hand fixes it for both, and "Later" lasts until the next run.
 */
class ToFixRunsTest {
    private val ctx = FakeContext()
    private val store get() = Store.get(ctx)
    private lateinit var src: FakeProvider
    private lateinit var dst: FakeProvider
    private lateinit var engine: SyncEngine

    // The store is one for the JVM: ids carry a per-test prefix (see SyncEngineTest).
    private val run = UUID.randomUUID().toString().take(6)
    private fun t(id: String, title: String) = Track(id = "$run-$id", title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        store.data.jobs.forEach { store.deleteJob(it.id) }
        src = FakeProvider("fsrc")
        dst = FakeProvider("fdst")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(ctx.env) { name, _ -> "s:$name" }
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(name: String, source: String, target: String) =
        SyncJob(id = UUID.randomUUID().toString(), name = name, source = PlaylistRef("fsrc", source, source), target = PlaylistRef("fdst", target, target)).also { store.upsertJob(it) }

    private fun mine() = ToFix.items(store.data).filter { it.track.id.startsWith(run) }

    @Test fun whatTwoRunsMissIsOneRowFixedForBoth() = runBlocking {
        val gamma = t("c", "Gamma")
        src.lists["p"] = mutableListOf(t("a", "Alpha"), gamma)
        src.lists["q"] = mutableListOf(gamma)
        dst.catalogue += listOf(t("da", "Alpha"), t("dc", "Gamma (Live in Kelso)"))
        dst.lists["t"] = mutableListOf()
        dst.lists["u"] = mutableListOf()
        val first = job("First", "p", "t")
        val second = job("Second", "q", "u")
        engine.run(first)
        engine.run(second)

        val row = mine().single()
        assertEquals(listOf("First", "Second"), row.places.map { it.jobName })
        assertEquals(ToFix.Kind.NOT_FOUND, row.kind)

        val outcome = engine.fixEverywhere(row, t("dc", "Gamma (Live in Kelso)"))
        assertEquals(emptyList<String>(), outcome.failed)
        assertEquals(listOf("$run-da", "$run-dc"), dst.lists.getValue("t").map { it.id })
        assertEquals(listOf("$run-dc"), dst.lists.getValue("u").map { it.id })
        assertTrue(mine().isEmpty())
        // The runs after it find the song there already: nothing added twice, nothing to fix again.
        engine.run(store.job(first.id)!!)
        engine.run(store.job(second.id)!!)
        assertEquals(listOf("$run-da", "$run-dc"), dst.lists.getValue("t").map { it.id })
        assertEquals(listOf("$run-dc"), dst.lists.getValue("u").map { it.id })
        assertTrue(mine().isEmpty())
    }

    @Test fun laterLastsUntilTheNextRun() = runBlocking {
        src.lists["p"] = mutableListOf(t("c", "Gamma"))
        dst.lists["t"] = mutableListOf()
        val only = job("Only", "p", "t")
        engine.run(only)
        ToFix.later(store, mine().single())
        assertTrue(mine().isEmpty())
        engine.run(store.job(only.id)!!)
        assertEquals(listOf("Only"), mine().single().places.map { it.jobName })
    }
}

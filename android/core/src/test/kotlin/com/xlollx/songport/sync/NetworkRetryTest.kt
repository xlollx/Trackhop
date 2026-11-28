package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.NetError
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The engine's tries when the connection fails: said as the connection (NETWORK_RETRY, with the
 * service and the try), never as the service limiting requests (WAITING); a name that does not
 * resolve ends the run as offline after two tries.
 */
class NetworkRetryTest {
    private class Flaky(id: String) : MemoryService(id) {
        var failures: Int = 0
        var failWith: () -> Exception = { UnknownHostException("Unable to resolve host \"api.$serviceId.example\"") }
        var reads = 0
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
            reads++
            if (failures > 0) { failures--; throw failWith() }
            return super.tracks(ctx, playlistId)
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: Flaky
    private lateinit var dst: Flaky
    private val retries = NETWORK_RETRY_SECONDS

    private fun t(prefix: String, n: Int) = Track(id = "$prefix$n", title = "Song number $n", artists = listOf("Band"), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        NETWORK_RETRY_SECONDS = intArrayOf(1, 1, 1, 1, 1)
        env = ContractEnv()
        src = Flaky("nsrc")
        dst = Flaky("ndst")
        Providers.testDoubles = listOf(src, dst)
        src.catalogue += (1..3).map { t("s", it) }
        dst.catalogue += (1..3).map { t("d", it) }
        src.lists["p"] = (1..3).map { t("s", it) }.toMutableList()
        dst.lists["t"] = mutableListOf()
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        NETWORK_RETRY_SECONDS = retries
        NetError.networkUp = { null }
    }

    private fun job() = SyncJob(id = "n", name = "net", source = PlaylistRef("nsrc", "p", "p"), target = PlaylistRef("ndst", "t", "t"))
        .also { env.store.upsertJob(it) }

    @Test fun aNameThatDoesNotResolveStopsAfterTwoTriesAsOffline() = runBlocking {
        src.failures = 100
        val seen = ArrayList<Progress>()
        val r = SyncEngine(env).run(job()) { seen += it }
        assertEquals(2, src.reads)
        assertFalse(r.ok)
        assertTrue(r.offline)
        val retry = seen.filter { it.step == Progress.Step.NETWORK_RETRY }
        assertTrue(retry.isNotEmpty())
        assertTrue(retry.all { it.label == "nsrc" && it.attempt == 2 && it.attempts == 2 })
        assertTrue(seen.none { it.step == Progress.Step.WAITING })
    }

    @Test fun aServerAtNeitherAddressStopsAfterTwoTriesAndSaysWhatToCheck() = runBlocking {
        dst.failures = 100
        dst.failWith = { com.xlollx.songport.net.BothAddressesDown("Jellyfin", "home 192.168… no answer, away 100.70… no answer", java.net.ConnectException("refused")) }
        val seen = ArrayList<Progress>()
        val r = SyncEngine(env).run(job()) { seen += it }
        assertEquals(2, dst.reads)
        assertFalse(r.ok)
        assertEquals("server_both_down(Jellyfin)", r.error)
        assertTrue(r.netError != null)
        assertTrue(seen.filter { it.step == Progress.Step.NETWORK_RETRY }.all { it.attempt == 2 && it.attempts == 2 })
    }

    @Test fun aTimeoutIsTriedAgainAndSaidAsTheConnection() = runBlocking {
        dst.failures = 2
        dst.failWith = { SocketTimeoutException("timeout") }
        val seen = ArrayList<Progress>()
        val r = SyncEngine(env).run(job()) { seen += it }
        assertTrue(r.error, r.ok)
        val retry = seen.filter { it.step == Progress.Step.NETWORK_RETRY }
        assertEquals(listOf(2, 3), retry.map { it.attempt }.distinct())
        assertTrue(retry.all { it.label == "ndst" && it.attempts == NETWORK_RETRY_SECONDS.size + 1 && it.total == 1 })
        assertTrue(seen.none { it.step == Progress.Step.WAITING })
    }
}

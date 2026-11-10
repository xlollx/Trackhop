package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * ISRC to MusicBrainz recordings, against a stand-in for musicbrainz.org: what is asked and how
 * often, what is remembered, and that a sync to a personal server never depends on the answer.
 */
class MusicBrainzTest {
    private class Hit(val path: String, val userAgent: String?, val at: Long)

    private val hits: MutableList<Hit> = Collections.synchronizedList(ArrayList())
    /** ISRC to the answer: a status and the recording ids. */
    private var answer: (String) -> Pair<Int, List<String>> = { 404 to emptyList() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits += Hit(ex.requestURI.path, ex.requestHeaders.getFirst("User-Agent"), System.currentTimeMillis())
            val isrc = ex.requestURI.path.substringAfterLast('/')
            val (code, ids) = answer(isrc)
            val body = (if (code == 200) """{"isrc":"$isrc","recordings":[${ids.joinToString(",") { """{"id":"$it","title":"x"}""" }}]}""" else """{"error":"x"}""").toByteArray()
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = 1_700_000_000_000L
    private val a = "5fb524f1-8cc8-4c04-a921-e34c0a911ea7"
    private val b = "11111111-2222-3333-4444-555555555555"

    @Before fun setUp() {
        MusicBrainz.base = "http://127.0.0.1:${server.address.port}/ws/2"
        MusicBrainz.now = { clock }
        MusicBrainz.pace = Pacer(0)
        MusicBrainz.reset()
    }

    @After fun tearDown() {
        server.stop(0)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.now = { System.currentTimeMillis() }
        MusicBrainz.pace = Pacer(1100)
        MusicBrainz.reset()
        Providers.testDoubles = emptyList()
    }

    // ------------------------------------------------------------------ the lookup

    @Test fun asksByIsrcAndNamesItselfWithoutAPersonalContact() = runBlocking {
        answer = { 200 to listOf(a.uppercase(), b) }
        ArtistMixSource.userAgent = "Trackhop/1.17.0 ( https://trackhop.org )"
        assertEquals(listOf(a, b), MusicBrainz.recordings(env, "us-gf1-99-42501"))
        assertEquals("/ws/2/isrc/USGF19942501", hits.single().path)
        val ua = hits.single().userAgent!!
        assertEquals("Trackhop/1.17.0 ( https://trackhop.org )", ua)
        assertFalse(ua, ua.contains('@'))
    }

    @Test fun anAnswerIsRememberedAcrossRestarts() = runBlocking {
        answer = { 200 to listOf(a) }
        assertEquals(listOf(a), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(listOf(a), MusicBrainz.recordings(env, "USGF19942501"))
        // A new process: memory is gone, the saved answer is not.
        MusicBrainz.reset()
        clock += 100L * 24 * 3_600_000
        assertEquals(listOf(a), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(1, hits.size)
        // Months later it is asked again.
        MusicBrainz.reset()
        clock += 100L * 24 * 3_600_000
        MusicBrainz.recordings(env, "USGF19942501")
        assertEquals(2, hits.size)
    }

    @Test fun noRecordingIsRememberedTooForLess() = runBlocking {
        answer = { 404 to emptyList() }
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942501"))
        MusicBrainz.reset()
        clock += 20L * 24 * 3_600_000
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(1, hits.size)
        // MusicBrainz may have learnt the ISRC since: asked again after a month.
        MusicBrainz.reset()
        clock += 20L * 24 * 3_600_000
        answer = { 200 to listOf(a) }
        assertEquals(listOf(a), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(2, hits.size)
    }

    @Test fun whatIsNotAnIsrcIsNeverAsked() = runBlocking {
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "0602445790920")) // an album's UPC
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "../artist"))
        assertEquals(0, hits.size)
    }

    @Test fun aBusyMusicBrainzIsNoAnswerAndIsLeftAloneAfterAFew() = runBlocking {
        answer = { 503 to emptyList() }
        // Never an exception, never remembered as "no recording", never retried on the spot.
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(1, hits.size)
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942502"))
        assertEquals(3, hits.size)
        // Three failures in a row: no more requests for a while, even for other tracks.
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942503"))
        assertEquals(3, hits.size)
        assertTrue(env.logs.toString(), env.logs.any { it.contains("musicbrainz") })
        // Later it is asked again, and the answer counts.
        clock += 16 * 60_000
        answer = { 200 to listOf(a) }
        assertEquals(listOf(a), MusicBrainz.recordings(env, "USGF19942501"))
    }

    @Test fun anUnreachableMusicBrainzIsNoAnswerEither() = runBlocking {
        server.stop(0)
        assertEquals(emptyList<String>(), MusicBrainz.recordings(env, "USGF19942501"))
        assertEquals(0, env.keyValues("musicbrainz_isrc").size)
    }

    @Test fun requestsLeaveAtMostOneASecond() = runBlocking {
        MusicBrainz.pace = Pacer(1100)
        answer = { 200 to listOf(a) }
        // Four searches of a sync ask at once, as the engine's parallel searches do.
        (1..3).map { i -> async { MusicBrainz.recordings(env, "USGF1994250$i") } }.awaitAll()
        val times = hits.map { it.at }.sorted()
        assertEquals(3, times.size)
        // The server stamps arrivals, the pacer spaces departures: a little slack for the trip.
        times.zipWithNext().forEach { (x, y) -> assertTrue("only ${y - x} ms apart", y - x >= 1000) }
    }

    // ------------------------------------------------------------------ in a sync

    /** A personal server in memory: answers a search with the file tagged with the recording, else by title. */
    private class TaggedServer(id: String) : MemoryService(id) {
        override val matchesByMbid = true
        val asked = ArrayList<Track>()
        override suspend fun search(ctx: Env, track: Track): List<Track> {
            asked += track
            return catalogue.filter { c -> c.mbids.any { it in track.mbids } }.ifEmpty { super.search(ctx, track) }
        }
    }

    private fun sync(src: MemoryService, dst: MemoryService): com.xlollx.songport.model.SyncReport = runBlocking {
        Providers.testDoubles = listOf(src, dst)
        dst.lists.getOrPut("t") { ArrayList() }
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef(src.id, "p", "p"), target = PlaylistRef(dst.id, "t", "t")).also { env.store.upsertJob(it) }
        SyncEngine(env).run(job)
    }

    @Test fun aSyncTakesTheFileTaggedWithTheRecordingWhateverItsTitle() {
        answer = { if (it == "FRZ117600040") 200 to listOf(a) else 404 to emptyList() }
        val src = MemoryService("msrc"); val dst = TaggedServer("mdst")
        src.lists["p"] = mutableListOf(Track(id = "s1", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"), durationMs = 204_000, isrc = "FRZ117600040"))
        // The library names it another way, and has a look-alike with the title the source uses.
        dst.catalogue += Track(id = "right", title = "Oxygène (Quatrième partie)", artists = listOf("Jarre"), durationMs = 251_000, mbids = listOf(a))
        dst.catalogue += Track(id = "lookalike", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"), durationMs = 204_000, mbids = listOf(b))
        val r = sync(src, dst)
        assertEquals(listOf("right"), dst.lists["t"]!!.map { it.id })
        assertEquals(emptyList<Track>(), r.unmatched)
        assertEquals(listOf(a), dst.asked.first().mbids)
    }

    @Test fun aSyncMatchesByTitleWhenMusicBrainzDoesNotAnswer() {
        answer = { 503 to emptyList() }
        val src = MemoryService("msrc"); val dst = TaggedServer("mdst")
        src.lists["p"] = mutableListOf(Track(id = "s1", title = "Battery", artists = listOf("Metallica"), durationMs = 312_000, isrc = "USEE10001001"))
        dst.catalogue += Track(id = "d1", title = "Battery", artists = listOf("Metallica"), durationMs = 312_000, mbids = listOf(a))
        val r = sync(src, dst)
        assertEquals(listOf("d1"), dst.lists["t"]!!.map { it.id })
        assertTrue(r.notes.toString(), r.unmatched.isEmpty())
    }

    @Test fun aSyncMatchesByTitleWhenTheRecordingIsNotInTheLibrary() {
        // MusicBrainz knows the ISRC, the library's file is untagged: the title matcher, as before.
        answer = { 200 to listOf(a) }
        val src = MemoryService("msrc"); val dst = TaggedServer("mdst")
        src.lists["p"] = mutableListOf(
            Track(id = "s1", title = "Battery", artists = listOf("Metallica"), durationMs = 312_000, isrc = "USEE10001001"),
            Track(id = "s2", title = "No Isrc Here", artists = listOf("Band"), durationMs = 200_000),
        )
        dst.catalogue += Track(id = "d1", title = "Battery", artists = listOf("Metallica"), durationMs = 312_000)
        dst.catalogue += Track(id = "d2", title = "No Isrc Here", artists = listOf("Band"), durationMs = 200_000)
        sync(src, dst)
        assertEquals(setOf("d1", "d2"), dst.lists["t"]!!.map { it.id }.toSet())
        // Only the track with an ISRC was asked about.
        assertEquals(listOf("/ws/2/isrc/USEE10001001"), hits.map { it.path })
    }

    @Test fun onlyPersonalServersAskMusicBrainz() {
        answer = { 200 to listOf(a) }
        val src = MemoryService("msrc"); val dst = MemoryService("mdst")
        src.lists["p"] = mutableListOf(Track(id = "s1", title = "Battery", artists = listOf("Metallica"), durationMs = 312_000, isrc = "USEE10001001"))
        dst.catalogue += Track(id = "d1", title = "Battery", artists = listOf("Metallica"), durationMs = 312_000)
        sync(src, dst)
        assertEquals(listOf("d1"), dst.lists["t"]!!.map { it.id })
        assertEquals(0, hits.size)
    }

    @Test fun anUntaggedLibraryStopsTheQuestionsAfterAFew() {
        answer = { 200 to listOf(a) }
        val src = MemoryService("msrc"); val dst = TaggedServer("mdst")
        val songs = (1..40).map { Track(id = "s$it", title = "Song number $it", artists = listOf("Band"), durationMs = 200_000, isrc = "USEE1%07d".format(it)) }
        src.lists["p"] = songs.toMutableList()
        songs.forEach { dst.catalogue += it.copy(id = "d${it.id}", isrc = null) }
        sync(src, dst)
        assertEquals(40, dst.lists["t"]!!.size)
        // Twenty searches found files and no tag on any: the other twenty are matched without asking.
        assertEquals(20, hits.size)
    }

    @Test fun aTaggedLibraryKeepsBeingAsked() {
        answer = { 200 to listOf(a) }
        val src = MemoryService("msrc"); val dst = TaggedServer("mdst")
        val songs = (1..30).map { Track(id = "s$it", title = "Song number $it", artists = listOf("Band"), durationMs = 200_000, isrc = "USEE1%07d".format(it)) }
        src.lists["p"] = songs.toMutableList()
        // Tagged with other recordings: the library is tagged, these songs just are not the same ones.
        songs.forEach { dst.catalogue += it.copy(id = "d${it.id}", isrc = null, mbids = listOf(b)) }
        sync(src, dst)
        assertEquals(30, hits.size)
    }
}

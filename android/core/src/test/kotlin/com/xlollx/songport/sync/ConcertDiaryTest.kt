package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.providers.DiscoverSource
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * The concert diary against a stand-in for setlist.fm's "attended" answer (written by hand from the
 * API's documented shape): what is read, the statistics, the three playlists, the kept copy.
 */
class ConcertDiaryTest {
    private val body = javaClass.getResource("/diary/attended.json")!!.readText()
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var answer: (String) -> Pair<Int, String> = { 200 to body }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            hits += ex.requestURI.path + "?" + ex.requestURI.rawQuery.orEmpty()
            val (code, text) = answer(ex.requestURI.path)
            val bytes = text.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        Setlists.base = "http://127.0.0.1:${server.address.port}/rest"
        Setlists.now = { clock }
        Setlists.pace = Pacer(0)
        Setlists.saveKey(env, "MINE")
        ConcertDiary.saveUser(env, "@lorenzo")
    }

    @After fun tearDown() {
        server.stop(0)
        Setlists.base = "https://api.setlist.fm/rest/1.0"
        Setlists.now = { System.currentTimeMillis() }
        Setlists.pace = Pacer(550)
    }

    @Test fun `the attended concerts are read, the latest first, and kept half a day`() = runBlocking {
        val d = ConcertDiary.read(env)
        assertEquals(listOf("2026-07-12", "2026-05-03", "2025-06-08", "2009-09-21"), d.concerts.map { it.date })
        assertEquals("lorenzo", d.user)
        assertEquals(listOf("/rest/user/lorenzo/attended?p=1"), hits.toList())
        ConcertDiary.read(env)
        assertEquals(1, hits.size)
        clock += ConcertDiary.TTL_MS + 1
        ConcertDiary.read(env)
        assertEquals(2, hits.size)
        ConcertDiary.read(env, refresh = true)
        assertEquals(3, hits.size)
        // Another user: the diary kept is not theirs.
        ConcertDiary.saveUser(env, "someone")
        assertNull(ConcertDiary.kept(env))
    }

    @Test fun `no user, no key and an unknown user are said`() {
        ConcertDiary.saveUser(env, "")
        assertTrue(runCatching { runBlocking { ConcertDiary.read(env) } }.exceptionOrNull() is ConcertDiary.NoUser)
        ConcertDiary.saveUser(env, "ghost")
        answer = { 404 to """{"code":404,"status":"Not Found"}""" }
        assertEquals(404, (runCatching { runBlocking { ConcertDiary.read(env) } }.exceptionOrNull() as ProviderException).code)
        Setlists.saveKey(env, "")
        assertTrue(runCatching { runBlocking { ConcertDiary.read(env) } }.exceptionOrNull() is SetlistImport.NoKey)
    }

    @Test fun `the statistics count concerts, artists, venues, the first year and the artist seen most`() {
        val list = ConcertDiary.parseAttended(body).first
        val s = ConcertDiary.stats(list)
        assertEquals(4, s.concerts)
        assertEquals(3, s.artists)
        assertEquals(3, s.venues) // Alcatraz twice
        assertEquals(2009, s.firstYear)
        assertEquals("Radiohead", s.topArtist)
        assertEquals(2, s.topTimes)
        assertEquals(listOf("2026", "2025", "2009"), ConcertDiary.byYear(list).map { it.first })
        assertNull(ConcertDiary.stats(list.take(2)).topArtist)
        assertEquals(ConcertDiary.Stats(0, 0, 0, null, null, 0, 0), ConcertDiary.stats(emptyList()))
    }

    @Test fun `every song heard live once, the most heard first, a cover under its artist, no tape`() {
        val heard = ConcertDiary.heard(ConcertDiary.parseAttended(body).first)
        // Karma Police at two concerts (twice in one night counts once).
        assertEquals("Karma Police", heard.first().title)
        assertEquals(listOf("Karma Police", "Airbag", "Ceremony", "Paranoid Android", "Starburster", "Favourite", "No Surprises"), heard.map { it.title })
        assertEquals(listOf("Joy Division"), heard.first { it.title == "Ceremony" }.artists)
        assertTrue(heard.none { it.title == "Intro" })
        assertTrue(heard.all { it.id.isNotEmpty() })
    }

    @Test fun `not heard live yet leaves out what was played at that artist's concerts`() {
        val list = ConcertDiary.parseAttended(body).first
        val popular = listOf("Creep", "Karma Police", "No Surprises (Remastered)", "High and Dry", "Airbag").map { Track("", it, listOf("Radiohead")) }
        assertEquals(listOf("Creep", "High and Dry"), ConcertDiary.notHeard(list, "radiohead", popular).map { it.title })
        assertEquals(1, ConcertDiary.notHeard(list, "Radiohead", popular, max = 1).size)
    }

    @Test fun `the playlists are a source of a sync, read again at every run`() = runBlocking {
        assertSame(DiscoverSource, Providers.byId(DiscoverSource.SERVICE))
        assertTrue(Providers.services().none { it.id == DiscoverSource.SERVICE })
        assertTrue(DiscoverSource.canRead(env, DiscoverSource.HEARD))
        assertTrue(!DiscoverSource.canRead(env, "show:") && !DiscoverSource.canRead(env, "nope"))
        assertEquals(7, DiscoverSource.tracks(env, DiscoverSource.HEARD).size)
        // One concert, in the order played, encore last: from the diary kept, no other call.
        assertEquals(listOf("Airbag", "Karma Police", "Ceremony", "Paranoid Android"), DiscoverSource.tracks(env, DiscoverSource.SHOW + "63de4613").map { it.title })
        assertEquals(1, hits.size)
        assertEquals("Radiohead · Ippodromo SNAI San Siro, Milan · 2026-07-12", DiscoverSource.playlistInfo(env, DiscoverSource.SHOW + "63de4613").name)
        // setlist.fm not answering: the run fails, the target is not emptied.
        clock += ConcertDiary.TTL_MS + 1
        answer = { 500 to "" }
        assertTrue(runCatching { DiscoverSource.tracks(env, DiscoverSource.HEARD) }.isFailure)
    }
}

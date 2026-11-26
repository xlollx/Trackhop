package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.SubsonicProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * #95: matches saved for a Navidrome whose library was imported anew point to song ids it no longer
 * has; the playlists made with them count the songs and show none. Against a stand-in Navidrome:
 * the lost matches are searched again before adding, a playlist with such entries is repaired, an
 * addition the playlist does not show is searched again, and a healthy library costs a few calls a day.
 */
class LostMatchesTest {
    /** A small Navidrome: songs by id, playlists as the ids they hold (entries that lead nowhere included). */
    private class FakeNavidrome {
        class Song(val title: String)
        val songs = LinkedHashMap<String, Song>()
        /** Songs Navidrome keeps as "missing" after a rescan: getSong still answers, playlists and searches leave them out. */
        val missing = LinkedHashMap<String, Song>()
        val playlists = LinkedHashMap<String, MutableList<String>>()
        val calls = ArrayList<Pair<String, Map<String, List<String>>>>()
        fun count(method: String) = synchronized(calls) { calls.count { it.first == method } }

        private fun ok(extra: String = "") = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome"$extra}}"""
        private fun song(id: String, s: Song) = """{"id":"$id","title":"${s.title}","artist":"Band","album":"Album","duration":200}"""

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map { kv ->
                    URLDecoder.decode(kv.substringBefore('='), "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                }.groupBy({ it.first }, { it.second })
                val method = ex.requestURI.path.substringAfterLast('/')
                val body = synchronized(calls) { calls += method to q; answer(method, q) }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"

        private fun answer(method: String, q: Map<String, List<String>>): String {
            fun one(k: String) = q[k]?.firstOrNull().orEmpty()
            return when (method) {
                "getPlaylists" -> ok(""","playlists":{"playlist":[${playlists.entries.joinToString(",") { (id, ids) -> """{"id":"$id","name":"$id","songCount":${ids.size},"owner":"me"}""" }}]}""")
                "getPlaylist" -> {
                    val ids = playlists[one("id")].orEmpty()
                    ok(""","playlist":{"id":"${one("id")}","songCount":${ids.size},"entry":[${ids.filter { it in songs }.joinToString(",") { song(it, songs.getValue(it)) }}]}""")
                }
                "getSong" -> (songs[one("id")] ?: missing[one("id")])?.let { ok(""","song":${song(one("id"), it)}""") }
                    ?: """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Song not found"}}}"""
                "search3" -> {
                    val query = one("query").lowercase()
                    val hits = songs.filter { (_, s) -> query.isEmpty() || query.contains(s.title.lowercase()) }
                    ok(""","searchResult3":{"song":[${hits.entries.joinToString(",") { (id, s) -> song(id, s) }}]}""")
                }
                // Navidrome takes any id into a playlist: one it no longer has is an entry that leads nowhere.
                "updatePlaylist" -> { playlists.getValue(one("playlistId")) += q["songIdToAdd"].orEmpty(); ok() }
                "createPlaylist" -> {
                    val id = one("playlistId").ifEmpty { one("name") }
                    playlists[id] = q["songId"].orEmpty().toMutableList()
                    ok(""","playlist":{"id":"$id"}""")
                }
                else -> ok()
            }
        }
    }

    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var nd: FakeNavidrome
    private val dst = SubsonicProvider()

    private fun t(id: String, title: String) = Track(id = id, title = title, artists = listOf("Band"), album = "Album", durationMs = 200_000)
    private val titles = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")

    @Before fun setUp() = runBlocking {
        env = ContractEnv()
        nd = FakeNavidrome()
        src = MemoryService("lsrc")
        Providers.testDoubles = listOf(src, dst)
        dst.login(env, nd.url, "me", "pw")
        src.lists["mix"] = titles.mapIndexed { i, title -> t("s$i", title) }.toMutableList()
        // The library as it is now: new ids, given by the import of today.
        titles.forEachIndexed { i, title -> nd.songs["new$i"] = FakeNavidrome.Song(title) }
    }

    @After fun tearDown() { Providers.testDoubles = emptyList(); nd.server.stop(0) }

    private fun job(target: String): SyncJob =
        SyncJob(id = "job-$target", name = target, source = PlaylistRef("lsrc", "mix", "Mix"), target = PlaylistRef(dst.id, target, target))
            .also { env.store.upsertJob(it) }

    /** The matches saved before the import: the old ids. */
    private fun savedOld() = env.store.putMatches(titles.indices.associate { FileStore.cacheKey("lsrc", "s$it", dst.id) to "old$it" }, auto = true)

    private val newIds = titles.indices.map { "new$it" }

    @Test fun savedMatchesTheServerNoLongerHasAreSearchedAgain() = runBlocking {
        savedOld()
        nd.playlists["pl"] = mutableListOf()
        val report = SyncEngine(env).run(job("pl"))
        assertEquals(null, report.error)
        assertEquals(newIds, nd.playlists["pl"])
        assertEquals(6, report.added)
        // No old id ever sent to the playlist, and the cache holds the new ones.
        assertTrue(nd.calls.filter { it.first == "updatePlaylist" }.flatMap { it.second["songIdToAdd"].orEmpty() }.none { it.startsWith("old") })
        titles.indices.forEach { assertEquals("new$it", env.store.cachedMatch("lsrc", "s$it", dst.id)) }
        assertTrue(report.notes.any { it.startsWith("note_lost_matches") })
        assertTrue(env.logs.joinToString("\n"), env.logs.any { it.contains("no longer knows") })
    }

    @Test fun aPlaylistWithEntriesThatLeadNowhereIsRepaired() = runBlocking {
        savedOld()
        // What the user saw: the playlist counts its songs and lists none (one song added by hand stays).
        nd.songs["mine"] = FakeNavidrome.Song("Mine")
        nd.playlists["pl"] = (listOf("mine") + titles.indices.map { "old$it" }).toMutableList()
        val j = job("pl")
        env.store.update { d -> d.copy(reports = listOf(com.xlollx.songport.model.SyncReport(id = "r0", jobId = j.id, jobName = j.name, startedEpoch = 1, durationMs = 1, sourceCount = 6, targetCount = 1, targetId = "pl", added = 6, removed = 0))) }
        val report = SyncEngine(env).run(j)
        assertEquals(null, report.error)
        assertEquals(listOf("mine") + newIds, nd.playlists["pl"])
        assertTrue(nd.calls.any { it.first == "createPlaylist" && it.second["playlistId"] == listOf("pl") })
        assertTrue(report.notes.any { it.startsWith("note_unlisted_repaired") })
    }

    @Test fun anAdditionThePlaylistDoesNotShowIsSearchedAgain() = runBlocking {
        // Navidrome kept the old songs as "missing": getSong still answers, so the check before adding passes.
        titles.forEachIndexed { i, title -> nd.missing["old$i"] = FakeNavidrome.Song(title) }
        savedOld()
        nd.playlists["pl"] = mutableListOf()
        val report = SyncEngine(env).run(job("pl"))
        assertEquals(null, report.error)
        assertEquals(newIds, nd.playlists["pl"])
        assertEquals(6, report.added)
        titles.indices.forEach { assertEquals("new$it", env.store.cachedMatch("lsrc", "s$it", dst.id)) }
    }

    @Test fun aHealthyLibraryCostsAFewCallsADay() = runBlocking {
        env.store.putMatches(titles.indices.associate { FileStore.cacheKey("lsrc", "s$it", dst.id) to "new$it" }, auto = true)
        nd.playlists["one"] = mutableListOf()
        nd.playlists["two"] = mutableListOf()
        SyncEngine(env).run(job("one"))
        assertEquals(newIds, nd.playlists["one"])
        assertTrue("${nd.count("getSong")} getSong calls", nd.count("getSong") <= LostMatches.SAMPLE)
        assertEquals(0, nd.count("search3"))
        val before = nd.count("getSong")
        // Another sync the same day: the check is not made again.
        SyncEngine(env).run(job("two"))
        assertEquals(newIds, nd.playlists["two"])
        assertEquals(before, nd.count("getSong"))
        assertEquals(0, nd.count("createPlaylist"))
    }
}

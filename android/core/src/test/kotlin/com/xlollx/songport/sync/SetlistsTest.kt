package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.BuildDefaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/**
 * Setlists of a playlist's artists and setlist.fm links, against a stand-in for the setlist.fm API
 * and its public page: what is read (encores, tapes, covers), the week-long cache, the per-key
 * limits, and the public page when there is no key or the API cannot answer.
 */
class SetlistsTest {
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val keys: MutableList<String?> = Collections.synchronizedList(ArrayList())
    /** The API's answer to a search for an artist: status and body. */
    private var search: (String) -> Pair<Int, String> = { 404 to """{"code":404,"message":"not found"}""" }
    private var byId: (String) -> Pair<Int, String> = { 404 to "{}" }
    private var page: Pair<Int, String> = 200 to ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += "$path?$query"
            keys += ex.requestHeaders.getFirst("x-api-key")
            val (code, body) = when {
                path == "/rest/search/setlists" -> search(query.substringAfter("artistName=").substringBefore("&"))
                path.startsWith("/rest/setlist/") -> byId(path.substringAfterLast('/'))
                path.startsWith("/setlist/") -> page
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        Setlists.base = "$base/rest"
        Setlists.now = { clock }
        Setlists.pace = Pacer(0)
        BuildDefaults.values = mapOf(Setlists.BUILD_KEY to "SHARED")
    }

    @After fun tearDown() {
        server.stop(0)
        Setlists.base = "https://api.setlist.fm/rest/1.0"
        Setlists.now = { System.currentTimeMillis() }
        Setlists.pace = Pacer(550)
        BuildDefaults.values = emptyMap()
    }

    private fun song(name: String, tape: Boolean = false, cover: String? = null) =
        """{"name":"$name","tape":$tape${cover?.let { ""","cover":{"mbid":"x","name":"$it"}""" } ?: ""}}"""

    private fun setlist(id: String, artist: String, date: String, vararg sets: String) = """
        {"id":"$id","eventDate":"$date","artist":{"mbid":"m","name":"$artist"},
         "venue":{"id":"v","name":"Forum","city":{"id":"1","name":"Milano","country":{"code":"IT","name":"Italy"}}},
         "tour":{"name":"Big Tour"},"url":"https://www.setlist.fm/setlist/x/2026/forum-$id.html",
         "sets":{"set":[${sets.joinToString(",")}]}}"""

    private fun set(vararg songs: String, encore: Int? = null) =
        """{${encore?.let { "\"encore\":$it," } ?: ""}"song":[${songs.joinToString(",")}]}"""

    private fun page(vararg setlists: String) = """{"type":"setlists","itemsPerPage":20,"page":1,"total":${setlists.size},"setlist":[${setlists.joinToString(",")}]}"""

    private val museConcert = setlist(
        "63de4613", "Muse", "08-11-2026",
        set(song("Intro", tape = true), song("Unravelling"), song("Hysteria"), song("Feeling Good", cover = "Nina Simone"), song("")),
        set(song("Uprising"), song("Knights of Cydonia"), encore = 1),
        set(song("Outro music", tape = true), encore = 2),
    )

    // ------------------------------------------------------------------ reading the answers

    @Test fun readsTheSetsEncoresTapesAndCovers() {
        val s = Setlists.parseSetlist(com.xlollx.songport.net.parseJson(museConcert))!!
        assertEquals("2026-11-08", s.date)
        assertEquals("Forum", s.venue); assertEquals("Milano", s.city); assertEquals("IT", s.country); assertEquals("Big Tour", s.tour)
        assertEquals(listOf("Intro", "Unravelling", "Hysteria", "Feeling Good", "Uprising", "Knights of Cydonia", "Outro music"), s.songs.map { it.title })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 2), s.songs.map { it.encore })
        assertEquals(listOf(true, false, false, false, false, false, true), s.songs.map { it.tape })
        assertEquals("Nina Simone", s.songs[3].by)

        // The playlist: no tapes, in the order played with the encores, a cover under its original artist.
        val t = Setlists.tracks(s)
        assertEquals(listOf("Unravelling", "Hysteria", "Feeling Good", "Uprising", "Knights of Cydonia"), t.map { it.title })
        assertEquals(listOf("Muse"), t[0].artists)
        assertEquals(listOf("Nina Simone"), t[2].artists)
        assertTrue(t.all { it.id.isNotEmpty() })
        assertEquals("Muse · Forum, Milano · 2026-11-08", Setlists.playlistName(s))
    }

    @Test fun theDocumentationsBareSetIsReadToo() {
        val body = """{"id":"abc12","eventDate":"23-08-1964","artist":{"name":"The Beatles"},"set":[{"song":[{"name":"Twist and Shout"}]}]}"""
        val s = Setlists.parseSetlist(com.xlollx.songport.net.parseJson(body))!!
        assertEquals("1964-08-23", s.date)
        assertEquals(listOf("Twist and Shout"), s.songs.map { it.title })
    }

    @Test fun aSearchKeepsTheArtistsOwnPlayedSetlistsLatestFirst() {
        val body = page(
            setlist("a1", "Muse", "01-06-2026", set(song("Plug In Baby"))),
            setlist("a2", "Muse Tribute Band", "02-06-2026", set(song("Plug In Baby"))),
            // Announced: no songs yet.
            setlist("a3", "Muse", "01-12-2026"),
            // Only a tape: nothing the band played.
            setlist("a4", "Muse", "03-05-2026", set(song("Intro", tape = true))),
            museConcert,
        )
        val found = Setlists.parseSearch(body, "muse")
        assertEquals(listOf("63de4613", "a1"), found.map { it.id })
    }

    @Test fun theIdOfALink() {
        assertEquals("63de4613", Setlists.idFromLink("https://www.setlist.fm/setlist/muse/2026/forum-milan-italy-63de4613.html"))
        assertEquals("3bd6b4a8", Setlists.idFromLink("https://www.setlist.fm/setlist/the-beatles/1966/candlestick-park-san-francisco-ca-3bd6b4a8.html?x=1"))
        assertNull(Setlists.idFromLink("https://www.setlist.fm/setlists/muse-13d6b5e5"))
    }

    // ------------------------------------------------------------------ looking up a playlist's artists

    @Test fun findsEachArtistsSetlistsWithTheKeyAndKeepsThemAWeek() = runBlocking {
        search = { a -> if (a == "Muse") 200 to page(museConcert) else 404 to """{"code":404}""" }
        val r = Setlists.find(env, listOf("Muse", "Nobody Known"))
        assertTrue(r.hasKey); assertFalse(r.ownKey); assertFalse(r.quotaHit)
        assertEquals(listOf("Muse", "Nobody Known"), r.artists.map { it.artist })
        assertEquals(1, r.artists[0].setlists.size)
        assertTrue(r.artists[1].setlists.isEmpty())
        assertEquals(0, r.unanswered)
        assertEquals(2, hits.size)
        assertTrue(keys.all { it == "SHARED" })

        // Six days on: from the cache, "not found" included.
        clock += 6L * 24 * 3_600_000
        Setlists.find(env, listOf("Muse", "Nobody Known"))
        assertEquals(2, hits.size)
        // Past a week: asked again.
        clock += 2L * 24 * 3_600_000
        Setlists.find(env, listOf("Muse"))
        assertEquals(3, hits.size)
    }

    @Test fun noKeyAsksNothing() = runBlocking {
        BuildDefaults.values = emptyMap()
        val r = Setlists.find(env, listOf("Muse"))
        assertFalse(r.hasKey)
        assertTrue(r.artists.isEmpty())
        assertTrue(hits.isEmpty())
    }

    @Test fun theOwnKeyGoesFirstAndLooksUpMoreArtists() = runBlocking {
        val artists = (1..30).map { "Artist $it" }
        val shared = Setlists.find(env, artists)
        assertEquals(Setlists.MAX_SHARED, shared.artists.size); assertEquals(30, shared.total)
        Setlists.saveKey(env, " MINE ")
        hits.clear(); keys.clear()
        clock += 8L * 24 * 3_600_000
        val own = Setlists.find(env, artists)
        assertTrue(own.ownKey)
        assertEquals(30, own.artists.size)
        assertTrue(keys.all { it == "MINE" })
    }

    @Test fun theDaysLimitStopsAndSaysSoUntilTomorrow() = runBlocking {
        search = { 429 to """{"code":429,"message":"Too Many Requests"}""" }
        val r = Setlists.find(env, listOf("A", "B", "C"))
        assertTrue(r.quotaHit)
        // One retry for the per-second limit, then no more calls that day.
        assertEquals(2, hits.size)
        assertEquals(0, r.unanswered)

        search = { 200 to page(setlist("x1", "A", "01-11-2026", set(song("Song")))) }
        clock += 3_600_000
        assertTrue(Setlists.find(env, listOf("A")).quotaHit)
        assertEquals(2, hits.size)
        // The next day (UTC) it asks again.
        clock += 24L * 3_600_000
        val next = Setlists.find(env, listOf("A"))
        assertFalse(next.quotaHit)
        assertEquals(1, next.artists[0].setlists.size)
    }

    @Test fun aBurstPassesOnTheSecondTry() = runBlocking {
        var n = 0
        search = { if (n++ == 0) 429 to "{}" else 200 to page(museConcert) }
        val r = Setlists.find(env, listOf("Muse"))
        assertFalse(r.quotaHit)
        assertEquals(1, r.artists[0].setlists.size)
    }

    @Test fun theDailyCountStopsBeforeSetlistFmDoes() = runBlocking {
        search = { 404 to "{}" }
        val artists = (1..10).map { "Artist $it" }
        Setlists.saveKey(env, "MINE")
        // As if the day's calls were nearly all used on this device.
        env.keyValues("setlists_state")["used"] = "${clock / 86_400_000}|${"MINE".hashCode()}|${Setlists.DAILY - 3}"
        val r = Setlists.find(env, artists)
        assertTrue(r.quotaHit)
        assertEquals(3, hits.size)
        assertEquals(Setlists.DAILY, Setlists.usedToday(env, "MINE"))
    }

    @Test fun aRefusedKeyIsSaid() = runBlocking {
        search = { 403 to """{"code":403,"message":"Forbidden"}""" }
        val r = Setlists.find(env, listOf("A", "B"))
        assertTrue(r.keyRefused)
        assertEquals(1, hits.size)
    }

    @Test fun anUnansweredArtistIsCountedAndNotKept() = runBlocking {
        search = { 500 to "oops" }
        val r = Setlists.find(env, listOf("A"))
        assertEquals(1, r.unanswered)
        search = { 200 to page(setlist("x1", "A", "01-11-2026", set(song("Song")))) }
        assertEquals(1, Setlists.find(env, listOf("A")).artists[0].setlists.size)
    }

    // ------------------------------------------------------------------ a shared setlist.fm link

    private val link get() = "$base/setlist/muse/2026/forum-milan-italy-63de4613.html"
    private val html = """<html><head><meta property="og:title" content="Muse Setlist at Forum, Milan, Italy"></head>
        <body><a class="songLabel" href="#">Hysteria</a><a class="songLabel" href="#">Uprising</a></body></html>"""

    @Test fun aLinkIsReadThroughTheApiWithAKey() = runBlocking {
        byId = { id -> if (id == "63de4613") 200 to museConcert else 404 to "{}" }
        val s = SetlistImport.fetch(env, link)
        assertEquals("Muse · Forum, Milano · 2026-11-08", s.name)
        assertEquals(5, s.tracks.size)
        assertTrue(hits.none { it.startsWith("/setlist/") })
        // Kept: the same link again asks nothing.
        SetlistImport.fetch(env, link)
        assertEquals(1, hits.size)
    }

    @Test fun withoutAKeyOrWhenTheApiFailsThePublicPage() = runBlocking {
        page = 200 to html
        BuildDefaults.values = emptyMap()
        val s = SetlistImport.fetch(env, link)
        assertEquals("Muse · Forum", s.name)
        assertEquals(listOf("Hysteria", "Uprising"), s.tracks.map { it.title })
        assertTrue(hits.none { it.startsWith("/rest/") })

        BuildDefaults.values = mapOf(Setlists.BUILD_KEY to "SHARED")
        hits.clear()
        byId = { 500 to "oops" }
        assertEquals(2, SetlistImport.fetch(env, link).tracks.size)
        assertEquals(listOf("/rest/setlist/63de4613", "/setlist/muse/2026/forum-milan-italy-63de4613.html"), hits.map { it.substringBefore('?') })
    }

    @Test fun aBrowserCheckOnThePublicPageIsSaidPlainly() = runBlocking {
        BuildDefaults.values = emptyMap()
        page = 202 to ""
        val e = runCatching { SetlistImport.fetch(env, link) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("setlist.fm key"))
    }
}

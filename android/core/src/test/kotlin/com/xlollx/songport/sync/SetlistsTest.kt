package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.BuildDefaults
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
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
 * limits, a link not read without a key, and the page shown in the in-app browser.
 */
class SetlistsTest {
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val keys: MutableList<String?> = Collections.synchronizedList(ArrayList())
    /** The API's answer to a search for an artist: status and body. */
    private var search: (String) -> Pair<Int, String> = { 404 to """{"code":404,"message":"not found"}""" }
    private var byId: (String) -> Pair<Int, String> = { 404 to "{}" }
    private var page: Pair<Int, String> = 200 to ""
    /** MusicBrainz's answers: the artist search and the events browsed by artist. */
    private var mbArtist: Pair<Int, String> = 200 to """{"artists":[]}"""
    private var mbEvents: (String) -> Pair<Int, String> = { 200 to """{"event-count":0,"events":[]}""" }
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
                path == "/ws/artist" -> mbArtist
                path == "/ws/event" -> mbEvents(query)
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
        MusicBrainz.base = "$base/ws"
        MusicBrainz.pace = Pacer(0)
    }

    @After fun tearDown() {
        server.stop(0)
        Setlists.base = "https://api.setlist.fm/rest/1.0"
        Setlists.now = { System.currentTimeMillis() }
        Setlists.pace = Pacer(550)
        BuildDefaults.values = emptyMap()
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
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

    @Test fun theOwnKeyIsKeptWithTheSignInsAndALinkIsAWebPage() {
        Setlists.saveKey(env, "MINE")
        assertEquals("MINE", Setlists.ownKey(env))
        assertFalse(Setlists.KEY_ID in env.store.data.settings.clientIds)
        val s = Setlists.parseSetlist(com.xlollx.songport.net.parseJson(museConcert.replace("https://www.setlist.fm/setlist/x/2026/forum-63de4613.html", "javascript:alert(1)")))!!
        assertEquals("", s.url)
    }

    @Test fun theOwnKeyGoesFirstAndTheCapIsOnlyTheWholePlaylists() = runBlocking {
        val artists = (1..(LookupCaps.SETLISTS + 5)).map { "Artist $it" }
        val shared = Setlists.find(env, artists)
        assertEquals(LookupCaps.SETLISTS, shared.artists.size); assertEquals(LookupCaps.SETLISTS + 5, shared.total)
        Setlists.saveKey(env, " MINE ")
        hits.clear(); keys.clear()
        clock += 8L * 24 * 3_600_000
        val own = Setlists.find(env, artists, whole = false)
        assertTrue(own.ownKey)
        assertEquals(LookupCaps.SETLISTS + 5, own.artists.size)
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

    @Test fun aKeyFixedAfterARefusalIsUsedAtOnceAndNothingRefusedIsKept() = runBlocking {
        val right = "RIGHT"
        // setlist.fm refuses any key but the right one.
        search = { if (keys.lastOrNull() == right) 200 to page(museConcert) else 403 to """{"code":403}""" }
        Setlists.saveKey(env, "WRONG")
        val refused = Setlists.find(env, listOf("Muse"))
        assertTrue(refused.keyRefused)
        assertTrue(refused.artists.single().setlists.isEmpty())

        Setlists.saveKey(env, right)
        val r = Setlists.find(env, listOf("Muse"))
        assertFalse(r.keyRefused)
        assertEquals(0, r.unanswered)
        assertEquals(1, r.artists.single().setlists.size)
        assertEquals(listOf("WRONG", right), keys)
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

    @Test fun withoutAKeyALinkIsNotReadInTheBackground() = runBlocking {
        page = 200 to html
        BuildDefaults.values = emptyMap()
        assertTrue(runCatching { SetlistImport.fetch(env, link) }.exceptionOrNull() is SetlistImport.NoKey)
        assertTrue(hits.isEmpty())
        // With a key whose API cannot answer: said so, the public page is not read either.
        BuildDefaults.values = mapOf(Setlists.BUILD_KEY to "SHARED")
        byId = { 500 to "oops" }
        assertTrue(runCatching { SetlistImport.fetch(env, link) }.isFailure)
        assertTrue(hits.none { it.startsWith("/setlist/") })
    }

    /** The page as the in-app browser hands it back: `document.documentElement.outerHTML`, a JSON string. */
    @Test fun theSetlistIsReadFromThePageShownInTheBrowser() {
        val dom = """<html lang="en"><head><meta property="og:title" content="Muse Setlist at Unipol Forum, Milan, Italy">
            <title>Muse Concert Setlist at Unipol Forum, Milan on November 8, 2026 | setlist.fm</title></head>
            <body><div class="dateBlock"><span class="month">Nov</span><span class="day">8</span><span class="year">2026</span></div>
            <ol class="songsList"><li class="setlistParts song"><a class="songLabel" href="/stats/songs/x.html">Hysteria</a></li>
            <li class="setlistParts song"><a class="songLabel" href="#">Rock &amp; Roll</a> <span class="infoPart">(Led Zeppelin cover)</span></li>
            <li class="setlistParts song"><a class="songLabel" href="#">Feeling Good (Nina Simone cover)</a></li></ol></body></html>"""
        val js = kotlinx.serialization.json.Json.encodeToString(String.serializer(), dom)
        val s = SetlistImport.fromDom(js, "$link?utm=1")
        assertEquals("63de4613", s.id)
        assertEquals("Muse", s.artist)
        assertEquals("Unipol Forum", s.venue)
        assertEquals("Milan", s.city)
        assertEquals("2026-11-08", s.date)
        assertEquals(link, s.url)
        assertEquals(listOf("Hysteria", "Rock & Roll", "Feeling Good"), s.songs.map { it.title })
        assertEquals(listOf("Muse", "Muse", "Nina Simone"), Setlists.tracks(s).map { it.artists.single() })
        assertEquals("Muse · Unipol Forum, Milan · 2026-11-08", Setlists.playlistName(s))
        // Not a setlist page (or not read): said so, nothing made of it.
        assertTrue(runCatching { SetlistImport.fromDom("null", link) }.isFailure)
        assertTrue(runCatching { SetlistImport.fromDom(kotlinx.serialization.json.Json.encodeToString(String.serializer(), "<html></html>"), link) }.isFailure)
    }

    /**
     * The store builds browse setlist.fm in a connector plugin, which hands back only the parts of the
     * page the setlist is read from (the og:title tag, the date block, the song labels), as they
     * appear in the page: read the same way as the whole page.
     */
    @Test fun theSetlistIsReadFromThePartsAPluginHandsBack() {
        val link = "https://www.setlist.fm/setlist/muse/2026/unipol-forum-milan-italy-63de4613.html"
        val parts = listOf(
            """<meta property="og:title" content="Muse Setlist at Unipol Forum, Milan, Italy">""",
            """<div class="dateBlock"><span class="month">Nov</span><span class="day">8</span><span class="year">2026</span></div>""",
            """<a class="songLabel" href="/stats/songs/x.html">Hysteria</a>""",
            """<a class="songLabel" href="#">Rock &amp; Roll</a>""",
            """<a class="songLabel" href="#">Feeling Good (Nina Simone cover)</a>""",
        ).joinToString("\n")
        val s = SetlistImport.fromPage(parts, link)
        assertEquals("Muse", s.artist); assertEquals("Unipol Forum", s.venue); assertEquals("Milan", s.city)
        assertEquals("2026-11-08", s.date); assertEquals(link, s.url)
        assertEquals(listOf("Hysteria", "Rock & Roll", "Feeling Good"), s.songs.map { it.title })
        assertEquals(listOf("Muse", "Muse", "Nina Simone"), Setlists.tracks(s).map { it.artists.single() })
        // No song labels handed back: nothing made of it.
        assertTrue(runCatching { SetlistImport.fromPage(parts.lines().take(2).joinToString("\n"), link) }.isFailure)
    }

    // ---------------------------------------------------------------- without a key: MusicBrainz

    private val muse = "9c9f1380-2516-4fc9-a3e6-f9f61941d090"

    private fun mbEvent(id: String, date: String, setlist: String, cancelled: Boolean = false) = """
        {"id":"$id","name":"Muse at Forum","cancelled":$cancelled,"life-span":{"begin":"$date","end":"$date"},
         "setlist":${kotlinx.serialization.json.JsonPrimitive(setlist)},
         "relations":[{"type":"held at","place":{"id":"p","name":"Forum"}},{"type":"held in","area":{"id":"a","name":"Milano"}}]}"""

    @Test fun musicBrainzSetlistTextKeepsTheArtistsPartEncoresAndTapes() {
        val text = "@ [$muse|Muse]\r\n* Intro\r\n# from tape\r\n* [abc|Uprising] # (live debut)\r\n# Encore:\r\n* Knights of Cydonia\r\n\r\n@ Opener\r\n* Not Ours"
        val songs = Setlists.parseMbSetlist(text, "Muse", muse)
        assertEquals(listOf("Intro", "Uprising", "Knights of Cydonia"), songs.map { it.title })
        assertTrue(songs[0].tape)
        assertEquals(listOf(0, 0, 1), songs.map { it.encore })
        // No performer named: the whole text is the artist's.
        assertEquals(listOf("A", "B"), Setlists.parseMbSetlist("* A\n* B", "Muse", muse).map { it.title })
        // Another performer's part only: nothing.
        assertTrue(Setlists.parseMbSetlist("@ Someone\n* A", "Muse", muse).isEmpty())
    }

    @Test fun withoutAKeyTheAppFindsMusicBrainzSetlists() = runBlocking {
        BuildDefaults.values = emptyMap()
        mbArtist = 200 to """{"artists":[{"id":"other","name":"Muse Tribute"},{"id":"$muse","name":"Muse"}]}"""
        mbEvents = { q ->
            assertTrue(q, "artist=$muse" in q)
            200 to """{"event-count":4,"events":[
                ${mbEvent("e1", "2023-05-01", "* Old Song")},
                ${mbEvent("e2", "2025-06-01", "* New Song\n* Hysteria")},
                ${mbEvent("e3", "2024-01-01", "")},
                ${mbEvent("e4", "2024-02-01", "* Cancelled", cancelled = true)}]}"""
        }
        val r = Setlists.find(env, listOf("Muse", "Muse"), keyless = true)
        assertEquals(Setlists.Source.MUSICBRAINZ, r.source)
        assertFalse(r.hasKey)
        val got = r.artists.single().setlists
        assertEquals(listOf("2025-06-01", "2023-05-01"), got.map { it.date })
        assertEquals("Forum", got[0].venue)
        assertEquals("Milano", got[0].city)
        assertEquals("https://musicbrainz.org/event/e2", got[0].url)
        assertEquals(listOf("New Song", "Hysteria"), Setlists.tracks(got[0]).map { it.title })
        // setlist.fm is never asked without a key, and the answer is kept.
        assertTrue(hits.none { it.startsWith("/rest") || it.startsWith("/setlist") })
        val asked = hits.size
        Setlists.find(env, listOf("Muse"), keyless = true)
        assertEquals(asked, hits.size)
    }

    @Test fun withoutAKeyAnUnknownArtistHasNoneAndASilentMusicBrainzIsCounted() = runBlocking {
        BuildDefaults.values = emptyMap()
        mbArtist = 200 to """{"artists":[{"id":"x","name":"Someone Else"}]}"""
        val none = Setlists.find(env, listOf("Muse"), keyless = true)
        assertTrue(none.artists.single().setlists.isEmpty())
        assertEquals(0, none.unanswered)
        assertTrue(hits.none { it.startsWith("/ws/event") })
        mbArtist = 503 to "{}"
        val busy = Setlists.find(env, listOf("Placebo"), keyless = true)
        assertEquals(1, busy.unanswered)
    }

    @Test fun theKeyStillGoesFirstWhenKeylessIsAllowed() = runBlocking {
        search = { 200 to page(setlist("1", "Muse", "01-06-2025", set(song("Uprising")))) }
        val r = Setlists.find(env, listOf("Muse"), keyless = true)
        assertEquals(Setlists.Source.SETLISTFM, r.source)
        assertTrue(hits.none { it.startsWith("/ws") })
    }
}

package com.xlollx.songport.providers

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.it.TestEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/** What the self-hosted connectors read from their servers' answers, without a server. */
class ServerProvidersTest {
    private fun t(title: String, artist: String) = Track(id = "$artist/$title", title = title, artists = listOf(artist))

    @Test fun subsonicReadsTheOpenSubsonicIsrcList() {
        val song = parseJson("""{"id":"s1","title":"Battery","artist":"Metallica","duration":312,"isrc":["USEE10001001","USEE10001099"]}""")
        assertEquals("USEE10001001", SubsonicProvider().toTrack(song)!!.isrc)
    }

    @Test fun subsonicReadsAPlainIsrcOrNone() {
        assertEquals("USEE10001001", SubsonicProvider().toTrack(parseJson("""{"id":"s1","title":"Battery","isrc":"USEE10001001"}"""))!!.isrc)
        assertNull(SubsonicProvider().toTrack(parseJson("""{"id":"s1","title":"Battery"}"""))!!.isrc)
        assertNull(SubsonicProvider().toTrack(parseJson("""{"id":"s1","title":"Battery","isrc":[]}"""))!!.isrc)
    }

    @Test fun jellyfinPutsTheSearchedArtistFirst() {
        val found = listOf(t("Home", "Måneskin"), t("Home", "Beyoncé"), t("Home (Live)", "Beyonce"), t("Home", "Other"))
        assertEquals(
            listOf("Beyoncé/Home", "Beyonce/Home (Live)", "Måneskin/Home", "Other/Home"),
            Matcher.byArtist(t("Home", "Beyoncé"), found).map { it.id },
        )
    }

    @Test fun jellyfinKeepsTheServerOrderWithoutAnArtist() {
        val found = listOf(t("Home", "Måneskin"), t("Home", "Beyoncé"))
        assertEquals(found, Matcher.byArtist(Track(id = "q", title = "Home"), found))
    }

    private fun e(item: String, entry: String) = Track(id = item, title = item, itemId = entry)

    @Test fun jellyfinDeletesEntriesWhenNoCopyHasToStay() {
        val list = listOf(e("a", "1"), e("b", "2"), e("c", "3"), e("b", "2"))
        assertNull(JellyfinProvider.keptAfterRemoving(list, listOf("3")))
        assertNull(JellyfinProvider.keptAfterRemoving(list, listOf("2", "2")))
    }

    // ------------------------------------------------------------------ "Open in" web search

    @Test fun navidromeSearchOpensTheSongsListFiltered() {
        assertEquals(
            "https://nd.casa.lan:4533/app/#/song?filter=%7B%22title%22%3A%22daft%20punk%20one%20more%20time%22%7D",
            SubsonicProvider.navidromeSearchUrl("https://nd.casa.lan:4533", "daft punk one more time"),
        )
    }

    @Test fun navidromeSearchKeepsQuotesAndAccentsAsJson() {
        val q = "Beyoncé \"Halo\" & co #1"
        val url = SubsonicProvider.navidromeSearchUrl("http://host/music", q)
        assertTrue(url, url.startsWith("http://host/music/app/#/song?filter="))
        val filter = url.substringAfter("filter=")
        assertEquals(q, parseJson(URLDecoder.decode(filter, "UTF-8"))["title"].str)
        // Nothing left raw that would end the hash route or start another parameter.
        assertTrue(url, filter.none { it in "#&\" {}+" })
    }

    @Test fun jellyfinSearchOpensTheWebClientSearch() {
        assertEquals("https://jf.lan/web/#/search?query=daft%20punk", JellyfinProvider.searchUrl("https://jf.lan", "daft punk"))
        assertEquals("https://jf.lan/jellyfin/web/#/search?query=AC%2FDC%20%26%20%231", JellyfinProvider.searchUrl("https://jf.lan/jellyfin", "AC/DC & #1"))
    }

    private fun TestEnv.connect(p: MusicProvider, extra: Map<String, String>) =
        tokens.set(p.id, Tokens(accessToken = "x", userName = "me", extra = extra))

    @Test fun onlyServersWithAWebSearchOfferOne() {
        val ctx = TestEnv()
        val subsonic = SubsonicProvider(); val jellyfin = JellyfinProvider(); val plex = PlexProvider()
        // Not connected: nothing to open.
        assertFalse(subsonic.hasWebSearch(ctx)); assertFalse(jellyfin.hasWebSearch(ctx))
        ctx.connect(subsonic, mapOf("url" to "https://nd.lan", "type" to "navidrome"))
        assertEquals(SubsonicProvider.navidromeSearchUrl("https://nd.lan", "q"), subsonic.webSearchUrl(ctx, "q"))
        assertTrue(subsonic.hasWebSearch(ctx))
        // Gonic, Airsonic, an account from before the kind was stored: no button.
        ctx.connect(subsonic, mapOf("url" to "https://gonic.lan", "type" to "gonic"))
        assertNull(subsonic.webSearchUrl(ctx, "q")); assertFalse(subsonic.hasWebSearch(ctx))
        ctx.connect(subsonic, mapOf("url" to "https://old.lan"))
        assertFalse(subsonic.hasWebSearch(ctx))
        ctx.connect(jellyfin, mapOf("url" to "https://jf.lan"))
        assertEquals("https://jf.lan/web/#/search?query=q", jellyfin.webSearchUrl(ctx, "q"))
        ctx.connect(plex, mapOf("url" to "https://plex.lan", "machine" to "m"))
        assertFalse(plex.hasWebSearch(ctx))
    }

    @Test fun jellyfinRewritesTheListWhenACopyStays() {
        // Jellyfin 10.9+: both copies of b share entry id 2; removing one must keep the other.
        val list = listOf(e("a", "1"), e("b", "2"), e("c", "3"), e("b", "2"))
        assertEquals(listOf("a", "b"), JellyfinProvider.keptAfterRemoving(list, listOf("2", "3"))!!.map { it.id })
        assertEquals(listOf("a", "b", "c"), JellyfinProvider.keptAfterRemoving(list, listOf("2"))!!.map { it.id })
    }
}

/**
 * The connectors against a stand-in server: a local HTTP server answering as the real ones did in
 * LiveServersIT (Airsonic-Advanced, Gonic, Plex, Jellyfin 10.8), so the fixes hold without docker.
 */
class FakeServerProvidersTest {
    private class Req(val method: String, val path: String, val query: Map<String, List<String>>, val body: String)

    private val requests = mutableListOf<Req>()
    private var answer: (Req) -> Pair<Int, String> = { 404 to "" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map { kv ->
                URLDecoder.decode(kv.substringBefore('='), "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            }.groupBy({ it.first }, { it.second })
            val req = Req(ex.requestMethod, ex.requestURI.path, q, ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val (code, body) = synchronized(requests) { requests += req; answer(req) }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val ctx = TestEnv()

    @After fun stop() = server.stop(0)

    // ------------------------------------------------------------------ Subsonic

    private fun subsonic(version: String, extra: String = "") = """{"subsonic-response":{"status":"ok","version":"$version"$extra}}"""

    @Test fun subsonicSpeaksTheVersionOfAnOlderServer() = runBlocking {
        // Airsonic-Advanced 11 (API 1.15.0) refuses 1.16.1 with error 30, and names its own version.
        answer = { r ->
            if (r.query["v"]?.single() == "1.15.0") 200 to subsonic("1.15.0", ""","playlists":{"playlist":[{"id":1,"name":"Mix","songCount":2,"owner":"admin"}]}""")
            else 200 to """{"subsonic-response":{"status":"failed","version":"1.15.0","error":{"code":30,"message":"Incompatible Airsonic REST protocol version. Server must upgrade."}}}"""
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals(listOf("Mix"), p.playlists(ctx).map { it.name })
        // Remembered: after the login, every call goes out with 1.15.0 at once.
        assertEquals(listOf("1.16.1", "1.15.0", "1.15.0"), requests.map { it.query["v"]?.single() })
        assertEquals(listOf(null, null, null), requests.map { it.query["p"] })
    }

    @Test fun subsonicRemovesTheFirstEntryAsAmpacheReadsIt() = runBlocking {
        answer = { 200 to subsonic("1.16.1") }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        p.removeTracks(ctx, "pl", listOf(Track(id = "a", title = "A", itemId = "0"), Track(id = "b", title = "B", itemId = "4")))
        val update = requests.single { it.path.endsWith("updatePlaylist") }
        // Ampache ignores songIndexToRemove=0; 00 is the same index for every server.
        assertEquals(listOf("00", "4"), update.query["songIndexToRemove"])
    }

    @Test fun subsonicStillFailsOnOtherErrors() = runBlocking {
        answer = { 200 to """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password."}}}""" }
        val e = runCatching { SubsonicProvider().login(ctx, url, "admin", "bad") }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("Wrong username") == true)
        assertEquals(1, requests.size)
    }

    @Test fun subsonicExplainsAmpachesSubsonicPassword() = runBlocking {
        // Ampache 8.3, the account password as token: error 40, and the answer says it is Ampache.
        answer = { 200 to """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"ampache","serverVersion":"8.3.0","openSubsonic":true,"error":{"code":40,"message":"Wrong username or password.","helpUrl":"https://ampache.org/api/subsonic"}}}""" }
        val e = runCatching { SubsonicProvider().login(ctx, url, "it", "account-password") }.exceptionOrNull()
        assertEquals("error_ampache_subsonic_password", e?.message)
        assertEquals(1, requests.size)
    }

    @Test fun subsonicSearchesTheTitleAloneWhenArtistAndTitleFindNothing() = runBlocking {
        // Gonic matches the query against titles: "metallica battery" finds nothing, "home" both Homes.
        answer = { r ->
            when (r.path.substringAfterLast('/')) {
                "search3" -> 200 to subsonic("1.16.1", if (r.query["query"]?.single() == "home") {
                    ""","searchResult3":{"song":[{"id":"m","title":"Home","artist":"Måneskin"},{"id":"b","title":"Home","artist":"Beyoncé"}]}"""
                } else ""","searchResult3":{}""")
                else -> 200 to subsonic("1.16.1")
            }
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        val found = p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé")))
        assertEquals(listOf("b", "m"), found.map { it.id })
        assertEquals(listOf("beyonce home", "home"), requests.filter { it.path.endsWith("search3") }.map { it.query["query"]?.single() })
    }

    @Test fun subsonicRemembersANavidromeServerAtLogin() = runBlocking {
        answer = { 200 to subsonic("1.16.1", ""","type":"navidrome","serverVersion":"0.58.0","openSubsonic":true""") }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals("$url/app/#/song?filter=%7B%22title%22%3A%22q%22%7D", p.webSearchUrl(ctx, "q"))
    }

    @Test fun subsonicLearnsTheKindOfAnAccountConnectedBefore() = runBlocking {
        // An account saved by 1.3.1: url and user, no kind. The next answer from Navidrome records it.
        val p = SubsonicProvider()
        ctx.tokens.set(p.id, Tokens(accessToken = "pw", userName = "admin", extra = mapOf("url" to url)))
        answer = { 200 to subsonic("1.16.1", ""","type":"navidrome","playlists":{"playlist":[]}""") }
        assertNull(p.webSearchUrl(ctx, "q"))
        p.playlists(ctx)
        assertTrue(p.hasWebSearch(ctx))
        assertEquals("pw", ctx.tokens.get(p.id)!!.accessToken)
    }

    @Test fun subsonicWithoutAKindOffersNoWebSearch() = runBlocking {
        answer = { 200 to subsonic("1.16.1") }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        assertNull(p.webSearchUrl(ctx, "q"))
    }

    // ------------------------------------------------------------------ Plex

    @Test fun plexSearchesByTitleAndPutsTheArtistFirst() = runBlocking {
        answer = { r ->
            when (r.path) {
                "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                "/hubs/search" -> 200 to if (r.query["query"]?.single() == "home") {
                    """{"MediaContainer":{"Hub":[{"type":"artist","Metadata":[{"ratingKey":"1","title":"Home"}]},{"type":"track","Metadata":[
                        {"ratingKey":"5","title":"Home","grandparentTitle":"Måneskin","duration":8046},
                        {"ratingKey":"7","title":"Home","grandparentTitle":"Beyoncé","duration":8000}]}]}}"""
                } else """{"MediaContainer":{"Hub":[{"type":"track"}]}}"""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        assertEquals(listOf("7", "5"), p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé"))).map { it.id })
        assertEquals(listOf("home"), requests.filter { it.path == "/hubs/search" }.map { it.query["query"]?.single() })
    }

    @Test fun plexTriesTheBareTitleAndPutsTheClosestFirst() = runBlocking {
        // #57 on Plex: the full title brings other songs by the same artist, the bare one every part.
        fun track(key: String, title: String) = """{"ratingKey":"$key","title":"$title","grandparentTitle":"Jean-Michel Jarre","duration":254000}"""
        answer = { r ->
            when (r.path) {
                "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                "/hubs/search" -> 200 to """{"MediaContainer":{"Hub":[{"type":"track","Metadata":[""" + when (r.query["query"]?.single()) {
                    "oxygene" -> (1..6).joinToString(",") { n -> track("o$n", "Oxygene (Part ${listOf("I", "II", "III", "IV", "V", "VI")[n - 1]})") }
                    else -> listOf(track("e4", "Equinoxe (Part IV)"), track("z", "Zoolookologie")).joinToString(",")
                } + "]}]}}"
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        val found = p.search(ctx, Track(id = "q", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"), durationMs = 254_000))
        assertEquals("o4", found.first().id)
        assertEquals(listOf("oxygene part 4", "Oxygene, Pt. 4", "oxygene"), requests.filter { it.path == "/hubs/search" }.map { it.query["query"]?.single() })
    }

    @Test fun plexCreatesAnEmptyPlaylistOnTheLibrary() = runBlocking {
        // Plex 1.43: POST /playlists with the library's uri and no item makes an empty audio playlist.
        answer = { r ->
            when {
                r.path == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                r.path == "/playlists" && r.method == "POST" ->
                    200 to """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"21","type":"playlist","title":"${r.query["title"]?.single()}","smart":false,"playlistType":"audio","leafCount":0}]}}"""
                r.path == "/playlists/21" && r.method == "PUT" -> 200 to ""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        assertTrue(p.canCreatePlaylists && p.canRenamePlaylists && p.canDeletePlaylists)
        p.login(ctx, url, "", "token")
        val pl = p.createPlaylist(ctx, "Café & Co", "Made with Trackhop")
        assertEquals("21" to "Café & Co", pl.id to pl.name)
        val post = requests.single { it.method == "POST" }
        assertEquals(listOf("audio"), post.query["type"])
        assertEquals(listOf("0"), post.query["smart"])
        assertEquals(listOf("Café & Co"), post.query["title"])
        assertEquals(listOf("server://abc/com.plexapp.plugins.library"), post.query["uri"])
        assertEquals(listOf("Made with Trackhop"), requests.single { it.method == "PUT" }.query["summary"])
    }

    @Test fun plexCreatesThePlaylistEvenWhenTheSummaryIsRefused() = runBlocking {
        answer = { r ->
            when {
                r.path == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                r.path == "/playlists" && r.method == "POST" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"9","title":"Mix"}]}}"""
                else -> 400 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        assertEquals("9", p.createPlaylist(ctx, "Mix", "x").id)
    }

    @Test fun plexSaysSoWhenThePlaylistIsNotMade() = runBlocking {
        answer = { r -> if (r.path == "/identity") 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}""" else 400 to "Bad Request" }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        val e = runCatching { p.createPlaylist(ctx, "Mix", "") }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("400") == true)
    }

    // ------------------------------------------------------------------ Jellyfin 10.8

    private fun jellyfin108(r: Req): Pair<Int, String> = when {
        r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
        // No "update playlist" before 10.9.
        r.path == "/Playlists/p1" && r.method == "POST" -> 404 to ""
        r.path == "/Users/u1/Items/p1" -> 200 to """{"Id":"p1","Name":"Old","Type":"Playlist","Genres":[],"Tags":[]}"""
        r.path == "/Items/p1" && r.method == "POST" -> 204 to ""
        // No accent folding before 10.9: "energie" finds nothing, "Énergie" finds the song.
        r.path == "/Items" -> 200 to if (r.query["searchTerm"]?.single() == "Énergie") {
            """{"Items":[{"Id":"e","Name":"Énergie (feat. Pharrell)","Artists":["Beyoncé"],"RunTimeTicks":80000000}]}"""
        } else """{"Items":[]}"""
        else -> 404 to ""
    }

    @Test fun jellyfin108RenamesThroughTheItem() = runBlocking {
        answer = ::jellyfin108
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        p.renamePlaylist(ctx, "p1", "New")
        val write = requests.single { it.path == "/Items/p1" }
        val item = parseJson(write.body)
        assertEquals("New", item["Name"].str)
        assertEquals("p1", item["Id"].str) // the rest of the item goes back as read
        assertEquals("Playlist", item["Type"].str)
    }

    @Test fun jellyfin108FindsAccentedTitlesAsWritten() = runBlocking {
        answer = ::jellyfin108
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val found = p.search(ctx, Track(id = "q", title = "Énergie (feat. Pharrell)", artists = listOf("Beyoncé")))
        assertEquals(listOf("e"), found.map { it.id })
        assertEquals(listOf("energie", "Énergie"), requests.filter { it.path == "/Items" }.map { it.query["searchTerm"]?.single() })
    }

    @Test fun jellyfinFindsNumberedPartsWrittenAnotherWay() = runBlocking {
        // #57: Spotify's "Oxygene, Pt. 4" is "Oxygene (Part IV)" in the library; the server matches names only.
        answer = { r ->
            when {
                r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
                r.path == "/Items" -> 200 to if (r.query["searchTerm"]?.single() == "oxygene") {
                    """{"Items":[""" + (1..6).joinToString(",") { n ->
                        val roman = listOf("I", "II", "III", "IV", "V", "VI")[n - 1]
                        """{"Id":"o$n","Name":"Oxygene (Part $roman)","Artists":["Jean-Michel Jarre"],"RunTimeTicks":${(200 + n) * 10_000_000L}}"""
                    } + "]}"
                } else """{"Items":[]}"""
                else -> 404 to ""
            }
        }
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val src = Track(id = "s", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"), durationMs = 204_000)
        val found = p.search(ctx, src)
        assertEquals("o4", found.first().id)
        assertEquals("oxygene", requests.filter { it.path == "/Items" }.last().query["searchTerm"]?.single())
    }

    /** #57 as the reporter's server answered: names-only search, unrelated songs for the full title. */
    private fun jellyfinNamesOnly(r: Req): Pair<Int, String> = when {
        r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
        r.path == "/Items" -> 200 to when (r.query["searchTerm"]?.single()) {
            "oxygene" -> """{"Items":[""" + listOf("I" to 460, "II" to 488, "III" to 174, "IV" to 254, "V" to 623, "VI" to 400).joinToString(",") { (n, sec) ->
                """{"Id":"o$n","Name":"Oxygene (Part $n)","Album":"Oxygene","Artists":["Jean-Michel Jarre"],"RunTimeTicks":${sec * 10_000_000L}}"""
            } + "]}"
            "usa" -> """{"Items":[{"Id":"usa","Name":"USA","Album":"Punks Not Dead","Artists":["The Exploited"],"RunTimeTicks":1800000000}]}"""
            "oxygene part 4", "Oxygene, Pt. 4", "u s a", "U.S.A" ->
                """{"Items":[{"Id":"junk","Name":"Part of Me","Artists":["Katy Perry"],"RunTimeTicks":2160000000},{"Id":"junk2","Name":"Party 4 U","Artists":["Charli XCX"],"RunTimeTicks":2010000000}]}"""
            else -> """{"Items":[]}"""
        }
        else -> 404 to ""
    }

    @Test fun jellyfinTriesTheBareTitleWhenTheFullOneBringsOnlyOtherSongs() = runBlocking {
        answer = ::jellyfinNamesOnly
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val src = Track(id = "s", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"), album = "Oxygène", durationMs = 254_000)
        val found = p.search(ctx, src)
        assertEquals("oIV", found.first().id)
        assertTrue(Matcher.score(src, found.first()) >= Matcher.REVIEW_THRESHOLD)
        assertEquals(listOf("oxygene part 4", "Oxygene, Pt. 4", "oxygene"), requests.filter { it.path == "/Items" }.map { it.query["searchTerm"]?.single() })
        // Part 4 is never taken for another part, even when the library lacks it.
        assertNull(Matcher.best(src, found.filter { it.id != "oIV" }))
    }

    /** #57, third round: a library with the parts of Magnetic Fields and Chronology, names-only search. */
    private fun jellyfinJarre(chronology: String): (Req) -> Pair<Int, String> = { r ->
        fun song(id: String, name: String, album: String, sec: Int) =
            """{"Id":"$id","Name":"$name","Album":"$album","Artists":["Jean-Michel Jarre"],"RunTimeTicks":${sec * 10_000_000L}}"""
        val magnetic = listOf(song("mf1", "Magnetic Fields, Part 1", "Magnetic Fields", 1079), song("mf2", "Magnetic Fields, Part 2", "Magnetic Fields", 325),
            song("mf3", "Magnetic Fields III", "Essentials", 357), song("mf4", "Magnetic Fields, Part 4", "Magnetic Fields", 373))
        val chrono = listOf(song("c2", "$chronology, Part 2", "Chronologie", 410), song("c4", "$chronology, Part 4", "Chronologie", 200))
        when {
            r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
            r.path == "/Artists" -> 200 to if (r.query["searchTerm"]?.single() == "Jean-Michel Jarre") """{"Items":[{"Id":"jmj","Name":"Jean-Michel Jarre"}]}""" else """{"Items":[]}"""
            r.path == "/Items" && r.query["ArtistIds"]?.single() == "jmj" -> 200 to """{"Items":[${(magnetic + chrono).joinToString(",")}]}"""
            r.path == "/Items" -> 200 to when (val term = r.query["searchTerm"]?.single().orEmpty()) {
                "magnetic fields" -> """{"Items":[${magnetic.joinToString(",")}]}"""
                else -> """{"Items":[${chrono.filter { term.isNotEmpty() && term.lowercase() in it.lowercase() }.joinToString(",")}]}"""
            }
            else -> 404 to ""
        }
    }

    @Test fun jellyfinTakesTheSamePartOverAnotherPartWrittenWithABareNumber() = runBlocking {
        answer = jellyfinJarre("Chronology")
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val src = Track(id = "s", title = "Magnetic Fields, Pt. 2", artists = listOf("Jean-Michel Jarre"), album = "Les Chants Magnétiques / Magnetic Fields", durationMs = 357_000)
        val found = p.search(ctx, src)
        assertEquals("mf2", found.first().id)
        assertEquals("mf2", Matcher.best(src, found)?.id)
        // "Magnetic Fields III" is another part: not even the closest proposal.
        assertTrue(Matcher.score(src, found.single { it.id == "mf3" }) < 0.45)
    }

    @Test fun jellyfinIsNeverAskedForTheRemaster() = runBlocking {
        answer = jellyfinJarre("Chronology")
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val src = Track(id = "s", title = "Chronology, Pt. 4 - Remastered", artists = listOf("Jean-Michel Jarre"), album = "Chronology", durationMs = 200_000)
        val found = p.search(ctx, src)
        assertEquals("c4", found.first().id)
        assertTrue(Matcher.score(src, found.first()) >= Matcher.REVIEW_THRESHOLD)
        val asked = requests.filter { it.path == "/Items" }.mapNotNull { it.query["searchTerm"]?.single() }
        assertEquals(listOf("chronology part 4", "Chronology, Pt. 4", "chronology"), asked)
        // Found by name: the artist's songs are not read.
        assertTrue(requests.none { it.path == "/Artists" })
    }

    @Test fun jellyfinScoresTheArtistsSongsWhenNoNameIsClose() = runBlocking {
        // The library names the piece another way ("Chronologie"): no name search finds it.
        answer = jellyfinJarre("Chronologie")
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val src = Track(id = "s", title = "Chronology, Pt. 4 - Remastered", artists = listOf("Jean-Michel Jarre"), album = "Chronology", durationMs = 200_000)
        val found = p.search(ctx, src)
        assertEquals("c4", found.first().id)
        assertEquals("c4", Matcher.best(src, found)?.id)
        // Part 2 of the same piece is never taken for it.
        assertNull(Matcher.best(src, found.filter { it.id != "c4" }))
        // An artist the library does not have: the search ends with nothing, no error.
        assertTrue(p.search(ctx, Track(id = "x", title = "Unknown Song", artists = listOf("Nobody"))).isEmpty())
    }

    @Test fun jellyfinFindsInitialsWrittenWithoutDots() = runBlocking {
        answer = ::jellyfinNamesOnly
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val src = Track(id = "s", title = "U.S.A", artists = listOf("The Exploited"), durationMs = 180_000)
        val found = p.search(ctx, src)
        assertEquals("usa", found.first().id)
        assertTrue(Matcher.score(src, found.first()) >= Matcher.REVIEW_THRESHOLD)
        assertEquals(listOf("usa"), requests.filter { it.path == "/Items" }.map { it.query["searchTerm"]?.single() })
    }

    // ------------------------------------------------------------------ MusicBrainz ids (#57)

    private val mbid = "5fb524f1-8cc8-4c04-a921-e34c0a911ea7"
    private val other = "11111111-2222-3333-4444-555555555555"
    /** "Oxygene, Pt. 4" as a streaming service names it, with the recording MusicBrainz lists for its ISRC. */
    private val wanted = Track(id = "s", title = "Oxygene, Pt. 4", artists = listOf("Jean-Michel Jarre"), durationMs = 204_000, mbids = listOf(mbid))

    @Test fun subsonicFindsTheFileTaggedWithTheRecording() = runBlocking {
        // Navidrome: a query that is a MusicBrainz id answers with the file tagged with it, whatever its title.
        answer = { r ->
            if (r.path.endsWith("search3") && r.query["query"]?.single() == mbid) {
                200 to subsonic("1.16.1", ""","searchResult3":{"song":[{"id":"n4","title":"Oxygène IV","artist":"Jarre","musicBrainzId":"${mbid.uppercase()}"}]}""")
            } else 200 to subsonic("1.16.1", ""","searchResult3":{}""")
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        val found = p.search(ctx, wanted)
        assertEquals(listOf("n4"), found.map { it.id })
        assertEquals(1.0, Matcher.score(wanted, found.single()), 0.0)
        // One search, by id: the title was never asked.
        assertEquals(listOf(mbid), requests.filter { it.path.endsWith("search3") }.map { it.query["query"]?.single() })
    }

    @Test fun subsonicGoesByTitleWhenNoFileHasTheRecording() = runBlocking {
        // A server that reads the id as words answers with something else: dropped, then the title as ever.
        answer = { r ->
            val query = r.query["query"]?.single().orEmpty()
            when {
                query == mbid -> 200 to subsonic("1.16.1", ""","searchResult3":{"song":[{"id":"x","title":"Something","artist":"Else","musicBrainzId":"$other"}]}""")
                query.contains("oxygene") -> 200 to subsonic("1.16.1", ""","searchResult3":{"song":[{"id":"t4","title":"Oxygene, Pt. 4","artist":"Jean-Michel Jarre","duration":204}]}""")
                else -> 200 to subsonic("1.16.1", ""","searchResult3":{}""")
            }
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        val searched = { requests.filter { it.path.endsWith("search3") }.map { it.query["query"]?.single() } }
        assertEquals(listOf("t4"), p.search(ctx, wanted).map { it.id })
        assertEquals(mbid, searched().first())
        // Without an id in hand nothing changes: the title search alone.
        requests.clear()
        assertEquals(listOf("t4"), p.search(ctx, wanted.copy(mbids = emptyList())).map { it.id })
        assertFalse(searched().toString(), mbid in searched())
    }

    @Test fun subsonicListsTheLibraryWithItsTags() = runBlocking {
        answer = { r ->
            if (r.path.endsWith("search3") && r.query["query"]?.single() == "") {
                val from = r.query["songOffset"]!!.single().toInt()
                // 501 songs: a full page of 500, then the last one. Every other song is tagged.
                val songs = (from until minOf(from + 500, 501)).joinToString(",") { i -> """{"id":"s$i","title":"Song $i"${if (i % 2 == 0) ""","musicBrainzId":"$mbid"""" else ""}}""" }
                200 to subsonic("1.16.1", ""","searchResult3":{"song":[$songs]}""")
            } else 200 to subsonic("1.16.1")
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        val seen = ArrayList<Int>()
        val tags = com.xlollx.songport.sync.LibraryTools.untaggedSongs(ctx, p) { seen += it }
        assertEquals(501 to 250, tags.total to tags.untagged.size)
        assertEquals("s1", tags.untagged.first().id)
        assertEquals(listOf(500, 501), seen)
    }

    private fun jellyfinParts(r: Req, recordingField: String): Pair<Int, String> = when {
        r.path.endsWith("/Users/AuthenticateByName") -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
        r.path.endsWith("/Items") && r.query["searchTerm"]?.single() == "oxygene" -> 200 to """{"Items":[""" + (1..6).joinToString(",") { n ->
            // Every part lasts the same and is named by a word: only the tag tells which is the fourth.
            val name = listOf("Ouverture", "Deuxième", "Troisième", "Quatrième", "Cinquième", "Final")[n - 1]
            """{"Id":"o$n","Name":"Oxygene ($name)","Artists":["Jean-Michel Jarre"],"RunTimeTicks":2040000000,"ProviderIds":{"$recordingField":"${if (n == 4) mbid else other}"}}"""
        } + "]}"
        r.path.endsWith("/Items") -> 200 to """{"Items":[]}"""
        else -> 404 to ""
    }

    @Test fun jellyfinPutsTheFileTaggedWithTheRecordingFirst() = runBlocking {
        answer = { jellyfinParts(it, "MusicBrainzRecording") }
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val found = p.search(ctx, wanted)
        assertEquals("o4", found.first().id)
        assertEquals(1.0, Matcher.score(wanted, found.first()), 0.0)
        assertTrue(Matcher.score(wanted, found[1]) < 1.0)
        // The ids are asked for with the search.
        assertTrue(requests.filter { it.path == "/Items" }.all { it.query["Fields"] == listOf("ProviderIds") })
        // Without the id, the same search cannot tell the parts apart: none of them is exact.
        assertTrue(p.search(ctx, wanted.copy(mbids = emptyList())).none { Matcher.score(wanted.copy(mbids = emptyList()), it) >= 1.0 })
    }

    @Test fun jellyfinReadsTheRecordingFromTheOlderField() = runBlocking {
        // Emby and Jellyfin before 10.9 keep the file's id under MusicBrainzTrack.
        answer = { jellyfinParts(it, "MusicBrainzTrack") }
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals("o4", p.search(ctx, wanted).first().id)
    }

    @Test fun embyAsksForTheItemTaggedWithTheRecording() = runBlocking {
        answer = { r ->
            when {
                r.query["AnyProviderIdEquals"] != null -> 200 to """{"Items":[{"Id":"e4","Name":"Oxygène IV","Artists":["Jarre"],"ProviderIds":{"MusicBrainzTrack":"$mbid"}},{"Id":"junk","Name":"Unrelated","ProviderIds":{"MusicBrainzTrack":"$other"}}]}"""
                else -> jellyfinParts(r, "MusicBrainzTrack")
            }
        }
        val p = EmbyProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals(listOf("e4"), p.search(ctx, wanted).map { it.id })
        val asked = requests.single { it.query["AnyProviderIdEquals"] != null }
        assertEquals("/emby/Items", asked.path)
        assertEquals("MusicBrainzTrack.$mbid,MusicBrainzRecording.$mbid", asked.query["AnyProviderIdEquals"]?.single())
        // The title was never searched.
        assertTrue(requests.none { it.query["searchTerm"] != null })
    }

    @Test fun embyGoesByTitleWhenTheFilterIsRefused() = runBlocking {
        answer = { r -> if (r.query["AnyProviderIdEquals"] != null) 400 to "Bad Request" else jellyfinParts(r, "MusicBrainzTrack") }
        val p = EmbyProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals("o4", p.search(ctx, wanted).first().id)
    }

    @Test fun jellyfinListsTheLibraryWithItsTags() = runBlocking {
        answer = { r ->
            when {
                r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
                r.path == "/Users/u1/Items" && r.query["IncludeItemTypes"] == listOf("Audio") ->
                    200 to """{"Items":[{"Id":"a","Name":"Tagged","ProviderIds":{"MusicBrainzRecording":"$mbid"}},{"Id":"b","Name":"Bare","ProviderIds":{}},{"Id":"c","Name":"Bare too"}]}"""
                else -> 404 to ""
            }
        }
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val tags = com.xlollx.songport.sync.LibraryTools.untaggedSongs(ctx, p)
        assertEquals(3, tags.total)
        assertEquals(listOf("b", "c"), tags.untagged.map { it.id })
        assertEquals(listOf("ProviderIds"), requests.last().query["Fields"])
    }

    @Test fun plexPutsTheTrackMatchedToTheRecordingFirst() = runBlocking {
        answer = { r ->
            when {
                r.path == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                // The search leaves the ids out...
                r.path == "/hubs/search" -> 200 to """{"MediaContainer":{"Hub":[{"type":"track","Metadata":[
                    {"ratingKey":"3","title":"Oxygene (Troisième)","grandparentTitle":"Jean-Michel Jarre","duration":204000},
                    {"ratingKey":"4","title":"Oxygene (Quatrième)","grandparentTitle":"Jean-Michel Jarre","duration":204000}]}]}}"""
                // ...the items read with includeGuids have them.
                r.path == "/library/metadata/3,4" || r.path == "/library/metadata/4,3" -> 200 to """{"MediaContainer":{"Metadata":[
                    {"ratingKey":"3","title":"Oxygene (Troisième)","Guid":[{"id":"plex://track/abc"},{"id":"mbid://$other"}]},
                    {"ratingKey":"4","title":"Oxygene (Quatrième)","Guid":[{"id":"mbid://$mbid"}]}]}}"""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        val found = p.search(ctx, wanted)
        assertEquals(listOf("4", "3"), found.map { it.id })
        assertEquals(1.0, Matcher.score(wanted, found.first()), 0.0)
        assertEquals(listOf("1"), requests.single { it.path.startsWith("/library/metadata/") }.query["includeGuids"])
        // Without an id in hand: searches alone (every form of the title, none being close), the closest first.
        requests.clear()
        val plain = wanted.copy(mbids = emptyList())
        val byTitle = p.search(ctx, plain)
        assertEquals(setOf("3", "4"), byTitle.map { it.id }.toSet())
        assertEquals(byTitle.sortedByDescending { Matcher.score(plain, it) }, byTitle)
        assertEquals(listOf("oxygene part 4", "Oxygene, Pt. 4", "oxygene"), requests.map { assertEquals("/hubs/search", it.path); it.query["query"]?.single() })
    }

    @Test fun plexListsTheMusicLibrariesWithTheirTags() = runBlocking {
        answer = { r ->
            when (r.path) {
                "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                "/library/sections" -> 200 to """{"MediaContainer":{"Directory":[{"key":"1","type":"movie"},{"key":"2","type":"artist"}]}}"""
                "/library/sections/2/all" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"7","title":"Tagged","Guid":[{"id":"mbid://$mbid"}]},{"ratingKey":"8","title":"Bare"}]}}"""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        val tags = com.xlollx.songport.sync.LibraryTools.untaggedSongs(ctx, p)
        assertEquals(2 to listOf("8"), tags.total to tags.untagged.map { it.id })
        assertEquals(listOf("10"), requests.last().query["type"])
    }

    // ------------------------------------------------------------------ Jellyfin Quick Connect (#45)

    @Test fun jellyfinQuickConnectSignsInOnceTheCodeIsApproved() = runBlocking {
        var approved = false
        answer = { r ->
            when {
                r.path == "/QuickConnect/Enabled" -> 200 to "true"
                r.path == "/QuickConnect/Initiate" && r.method == "POST" -> 200 to """{"Secret":"s3cr3t","Code":"123456","Authenticated":false}"""
                r.path == "/QuickConnect/Connect" -> 200 to """{"Secret":"s3cr3t","Code":"123456","Authenticated":$approved}"""
                r.path == "/Users/AuthenticateWithQuickConnect" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1","Name":"lorenzo"}}"""
                else -> 404 to ""
            }
        }
        val p = JellyfinProvider()
        val qc = p.quickConnectStart(ctx, url)
        assertEquals("123456", qc.code)
        assertEquals(false, p.quickConnectPoll(ctx, qc))
        approved = true
        assertEquals(true, p.quickConnectPoll(ctx, qc))
        assertEquals("s3cr3t", requests.last { it.path == "/QuickConnect/Connect" }.query["Secret"]?.single())
        assertEquals("s3cr3t", parseJson(requests.single { it.path == "/Users/AuthenticateWithQuickConnect" }.body)["Secret"].str)
        assertEquals("lorenzo", p.accountName(ctx))
        // Signed in: the next call carries the token.
        answer = { r -> if (r.path == "/Users/u1/Items") 200 to """{"Items":[]}""" else 404 to "" }
        assertEquals(emptyList<String>(), p.playlists(ctx).map { it.name })
    }

    @Test fun jellyfinQuickConnectOffIsSaidPlainly() = runBlocking {
        answer = { r -> if (r.path == "/QuickConnect/Enabled") 200 to "false" else 404 to "" }
        val e = runCatching { JellyfinProvider().quickConnectStart(ctx, url) }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("jellyfin_qc_off") == true)
    }

    // ------------------------------------------------------------------ play counts (poster, Wrapped)

    @Test fun subsonicReadsThePlaysOfAPlaylistAndOfTheStarredSongs() = runBlocking {
        answer = { r ->
            when {
                r.path.endsWith("/getPlaylist") -> 200 to subsonic("1.16.1", ""","playlist":{"id":"p1","entry":[
                    {"id":"s1","title":"Battery","artist":"Metallica","playCount":12},{"id":"s2","title":"One","artist":"Metallica"}]}""")
                r.path.endsWith("/getStarred2") -> 200 to subsonic("1.16.1", ""","starred2":{"song":[{"id":"s3","title":"Home","artist":"Beyoncé","playCount":3}]}""")
                else -> 200 to subsonic("1.16.1")
            }
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals(listOf("s1" to 12, "s2" to 0), p.playlistPlays(ctx, "p1").map { it.track.id to it.plays })
        assertEquals(listOf("s3" to 3), p.playlistPlays(ctx, MusicProvider.LIKED_ID).map { it.track.id to it.plays })
        assertTrue(p.playlistPlays(ctx, MusicProvider.ALBUMS_ID).isEmpty())
    }

    @Test fun subsonicTopSongsComeFromTheMostPlayedAlbumsByTheirOwnCounts() = runBlocking {
        answer = { r ->
            when {
                r.path.endsWith("/getAlbumList2") -> 200 to subsonic("1.16.1", ""","albumList2":{"album":[{"id":"a1"},{"id":"a2"}]}""")
                r.path.endsWith("/getAlbum") && r.query["id"] == listOf("a1") -> 200 to subsonic("1.16.1", ""","album":{"id":"a1","song":[
                    {"id":"s1","title":"Battery","artist":"Metallica","playCount":5},{"id":"s2","title":"Orion","artist":"Metallica","playCount":0}]}""")
                r.path.endsWith("/getAlbum") -> 200 to subsonic("1.16.1", ""","album":{"id":"a2","song":[{"id":"s3","title":"One","artist":"Metallica","playCount":40}]}""")
                else -> 200 to subsonic("1.16.1")
            }
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals(listOf("s3" to 40, "s1" to 5), p.topPlays(ctx, 50).map { it.track.id to it.plays })
        val list = requests.first { it.path.endsWith("/getAlbumList2") }
        assertEquals(listOf("frequent"), list.query["type"])
        assertEquals(listOf("${SubsonicProvider.TOP_ALBUMS}"), list.query["size"])
    }

    @Test fun jellyfinReadsThePlaysOfAPlaylistAndAsksTheServerForTheTopSongs() = runBlocking {
        answer = { r ->
            when {
                r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
                r.path == "/Playlists/p1/Items" -> 200 to """{"Items":[{"Id":"a","Name":"Battery","Artists":["Metallica"],"UserData":{"PlayCount":9}},{"Id":"b","Name":"One","Artists":["Metallica"],"UserData":{"PlayCount":0}}]}"""
                r.path == "/Users/u1/Items" && r.query["SortBy"] == listOf("PlayCount") -> 200 to """{"Items":[{"Id":"c","Name":"Home","Artists":["Beyoncé"],"UserData":{"PlayCount":21}},{"Id":"a","Name":"Battery","Artists":["Metallica"],"UserData":{"PlayCount":9}}]}"""
                else -> 404 to ""
            }
        }
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals(listOf("a" to 9, "b" to 0), p.playlistPlays(ctx, "p1").map { it.track.id to it.plays })
        assertEquals(listOf("u1"), requests.last().query["UserId"])
        assertEquals(listOf("c" to 21, "a" to 9), p.topPlays(ctx, 50).map { it.track.id to it.plays })
        val top = requests.last()
        assertEquals(listOf("Descending"), top.query["SortOrder"])
        assertEquals(listOf("Audio"), top.query["IncludeItemTypes"])
        assertEquals(listOf("50"), top.query["Limit"])
    }

    @Test fun plexReadsTheViewCountsAndTheMostPlayedOfEachMusicLibrary() = runBlocking {
        answer = { r ->
            when (r.path) {
                "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                "/playlists/9/items" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Battery","grandparentTitle":"Metallica","viewCount":4},{"ratingKey":"2","title":"One","grandparentTitle":"Metallica"}]}}"""
                "/library/sections" -> 200 to """{"MediaContainer":{"Directory":[{"key":"3","type":"artist"},{"key":"4","type":"movie"},{"key":"5","type":"artist"}]}}"""
                "/library/sections/3/all" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Battery","grandparentTitle":"Metallica","viewCount":4},{"ratingKey":"2","title":"One","grandparentTitle":"Metallica"}]}}"""
                "/library/sections/5/all" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"8","title":"Home","grandparentTitle":"Beyoncé","viewCount":11}]}}"""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        assertEquals(listOf("1" to 4, "2" to 0), p.playlistPlays(ctx, "9").map { it.track.id to it.plays })
        assertEquals(listOf("8" to 11, "1" to 4), p.topPlays(ctx, 50).map { it.track.id to it.plays })
        assertEquals(listOf("/library/sections/3/all", "/library/sections/5/all"), requests.filter { it.path.endsWith("/all") }.map { it.path })
        assertTrue(requests.filter { it.path.endsWith("/all") }.all { it.query["sort"] == listOf("viewCount:desc") && it.query["type"] == listOf("10") })
    }
}

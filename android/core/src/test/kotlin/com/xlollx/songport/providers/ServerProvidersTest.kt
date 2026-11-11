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
}

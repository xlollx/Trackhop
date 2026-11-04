package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.ytmbridge.BridgeNet
import com.xlollx.songport.ytmbridge.DeezerWebClient
import com.xlollx.songport.ytmbridge.SpotifyWebClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.Collections

/**
 * Spotify and Deezer without a key on the server: the app's web clients (compiled from :bridge) with
 * the server's pasted cookies. Every request the clients make goes to a local stub through the
 * clients' own hook ([BridgeNet.interceptor]), never to Spotify or Deezer; the stub answers in the
 * shapes the app's contract tests use. Whether the services accept these requests can only be seen
 * against real accounts.
 */
class WebConnectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One request as the stub saw it: the host the client meant, and what it sent. */
    class Seen(val host: String, val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private class Answer(val body: String, val code: Int = 200, val setCookie: String? = null)

    private lateinit var stub: HttpServer
    private val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    @Volatile private var answer: (Seen) -> Answer? = { null }

    @Before fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            val s = Seen(
                host = ex.requestHeaders.getFirst(ORIGINAL_HOST) ?: "",
                method = ex.requestMethod,
                path = ex.requestURI.path,
                query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") },
                headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(", ") },
                body = ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            seen += s
            val a = answer(s) ?: Answer("not stubbed", 404)
            a.setCookie?.let { ex.responseHeaders.add("Set-Cookie", it) }
            val bytes = a.body.toByteArray()
            ex.sendResponseHeaders(a.code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        val port = stub.address.port
        // Every host the clients name goes to the stub; the one they meant travels in a header.
        BridgeNet.interceptor = Interceptor { chain ->
            val u = chain.request().url
            chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host("127.0.0.1").port(port).build()).header(ORIGINAL_HOST, u.host).build())
        }
        SpotifyWebClient.dropPage()
        DeezerWebClient.forget()
        DeezerWebClient.pauseMs = 0
    }

    @After fun stop() {
        BridgeNet.interceptor = null
        stub.stop(0)
    }

    private fun env() = ServerEnv(tmp.root, Strings.load(null), "http://localhost:8080")

    private fun failure(block: suspend () -> Unit): Throwable? = runCatching { runBlocking { block() } }.exceptionOrNull()

    // ------------------------------------------------------------------ Spotify

    private val hash = "a".repeat(64)
    private val playlistHash = "b".repeat(64)
    private val bundle = "https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c3d.js"

    private fun playerPage(anonymous: Boolean = false) = """<html><script id="session" data-testid="session" type="application/json">
        {"accessToken":"BQ-web","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":$anonymous,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script>
        <script>var v="1.2.75.312.g5c3ea2d9";</script><script src="$bundle"></script></html>"""

    private fun spotify(anonymous: Boolean = false): (Seen) -> Answer? = { r ->
        when (r.host) {
            "open.spotify.com" -> Answer(playerPage(anonymous))
            "www.spotify.com" -> Answer("""{"profile":{"username":"spuser","name":"Me"}}""")
            "raw.githubusercontent.com" -> Answer("""{"operations":{"libraryV3":{"hash":"$hash"}}}""")
            "clienttoken.spotify.com" -> Answer("""{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"CT-1","expires_after_seconds":1209600,"refresh_after_seconds":1209600}}""")
            "open.spotifycdn.com" -> Answer("""x=[1,"fetchPlaylist","query","$playlistHash",2];""")
            // Only the bundle's hash is current: the one shipped with the app is refused, as when Spotify moves on.
            "api-partner.spotify.com" -> if (r.body.contains("fetchPlaylist") && !r.body.contains(playlistHash)) Answer("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
            else if (r.body.contains("fetchPlaylist")) Answer("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":1,"items":[
                {"uid":"u1","addedAt":{"isoString":"2024-01-02T00:00:00Z"},"itemV2":{"_uri":"spotify:track:t1","data":{"__typename":"Track","uri":"spotify:track:t1","name":"Song",
                "trackDuration":{"totalMilliseconds":200000},"artists":{"items":[{"profile":{"name":"Artist"}}]},"albumOfTrack":{"name":"Album","date":{"year":2020}},
                "playability":{"playable":false},"contentRating":{"label":"EXPLICIT"}}}}]}}}}""") else Answer("""{"data":{"me":{"libraryV3":{"__typename":"LibraryPage","totalCount":2,"items":[
                {"item":{"_uri":"spotify:playlist:A","data":{"__typename":"Playlist","uri":"spotify:playlist:A","name":"Mine","ownerV2":{"data":{"username":"spuser"}}}}},
                {"item":{"_uri":"spotify:playlist:B","data":{"__typename":"Playlist","uri":"spotify:playlist:B","name":"Theirs","ownerV2":{"data":{"username":"other"}}}}}]}}}}""")
            else -> null
        }
    }

    @Test fun spotifyCookiesWithoutSpDcAreRefusedBeforeAnyCall() {
        val env = env()
        val p = SpotifyWebServerProvider()
        val e = failure { p.connect(env, "sp_t=device; sp_key=k", null) }
        assertTrue(e is ProviderException)
        assertTrue(e!!.message!!.contains("sp_dc"))
        assertFalse(p.isConnected(env))
        assertTrue(seen.isEmpty())
    }

    @Test fun spotifyPastedExportIsParsedCheckedAndStored() {
        answer = spotify()
        val env = env()
        val p = SpotifyWebServerProvider()
        // A cookie-editor JSON export: another site's cookies are left out.
        val export = """[{"domain":".spotify.com","name":"sp_dc","value":"dc1"},{"domain":".google.com","name":"SID","value":"g"},
            {"domain":"open.spotify.com","name":"sp_key","value":"k1"},{"domain":".spotify.com","name":"sp_t","value":"dev1"}]"""
        runBlocking { p.connect(env, export, "Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0") }
        val t = env.tokens.get(p.id)!!
        assertEquals("sp_dc=dc1; sp_key=k1; sp_t=dev1", t.accessToken)
        assertEquals("Me", t.userName)
        assertEquals("spuser", t.userId)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0", t.extra[ServerWebHost.UA])
        assertTrue(p.isConnected(env))
        assertEquals("Me", p.accountName(env))
        val page = seen.first { it.host == "open.spotify.com" }
        assertEquals("sp_dc=dc1; sp_key=k1; sp_t=dev1", page.headers["cookie"])
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0", page.headers["user-agent"])
    }

    @Test fun spotifyAnonymousPageSaysTheCookiesExpired() {
        answer = spotify(anonymous = true)
        val env = env()
        val p = SpotifyWebServerProvider()
        val e = failure { p.connect(env, "cookie: sp_dc=old; sp_t=dev", null) }
        assertTrue(e is ProviderException)
        assertTrue(e!!.message, e.message!!.contains("expired") && e.message!!.contains("paste fresh"))
        assertFalse(p.isConnected(env))
        // The same answer for a session saved earlier whose cookies have since ended.
        env.tokens.set(p.id, Tokens(accessToken = "sp_dc=old", userName = "Me"))
        val later = failure { p.playlists(env) }
        assertTrue(later is ProviderException)
        assertTrue(later!!.message, later.message!!.contains(SpotifyWebServerProvider.EXPIRED))
    }

    @Test fun spotifyPageWithoutATokenSaysSo() {
        answer = { r -> if (r.host == "open.spotify.com") Answer("<html><body>player</body></html>") else null }
        val e = failure { SpotifyWebServerProvider().connect(env(), "sp_dc=x", null) }
        assertTrue(e is ProviderException)
        assertTrue(e!!.message, e.message!!.contains("no access token"))
    }

    @Test fun spotifyReadsWithThePageBearerAndAHarvestedHash() {
        answer = spotify()
        val env = env()
        val p = SpotifyWebServerProvider()
        runBlocking { p.connect(env, "sp_dc=dc1; sp_t=dev1", null) }
        val lists = runBlocking { p.playlists(env) }
        assertEquals(listOf("A", "B"), lists.map { it.id })
        assertEquals(listOf(true, false), lists.map { it.ownedByMe })
        val q = seen.last { it.host == "api-partner.spotify.com" }
        assertEquals("Bearer BQ-web", q.headers["authorization"])
        assertEquals("CT-1", q.headers["client-token"])
        assertEquals("1.2.75.312.g5c3ea2d9", q.headers["spotify-app-version"])
        assertTrue(q.body.contains(hash))
        // The client's cached values live in the data folder, and go with a disconnect.
        assertEquals("spuser", env.keyValues("spotify_web")["userId"])
        p.disconnect(env)
        assertFalse(p.isConnected(env))
        assertNull(env.keyValues("spotify_web")["userId"])
    }

    /** A query whose known hash Spotify refuses: the current one is read from the player's bundle, named by the page. */
    @Test fun spotifyHarvestsAMissingHashFromThePlayersBundle() {
        answer = spotify()
        val env = env()
        val p = SpotifyWebServerProvider()
        runBlocking { p.connect(env, "sp_dc=dc1", null) }
        val t = runBlocking { p.tracks(env, "A") }.single()
        assertEquals("t1", t.id)
        assertEquals("u1", t.itemId)
        assertEquals("spotify:track:t1", t.uri)
        assertEquals(false, t.available)
        assertEquals(true, t.explicit)
        assertEquals(1, seen.count { it.host == "open.spotifycdn.com" && it.path == "/cdn/build/web-player/web-player.0a1b2c3d.js" })
        val q = seen.last { it.host == "api-partner.spotify.com" }
        assertTrue(q.body.contains(playlistHash))
        assertEquals("Bearer BQ-web", q.headers["authorization"])
    }

    // ------------------------------------------------------------------ Deezer

    private fun deezer(userId: String = "42", profileError: String? = null): (Seen) -> Answer? = { r ->
        when {
            r.host == "www.deezer.com" && r.query["method"] == "deezer.getUserData" ->
                Answer("""{"error":[],"results":{"USER":{"USER_ID":"$userId","BLOG_NAME":"dz","LOVEDTRACKS_ID":"9"},"checkForm":"tok"}}""", setCookie = "sid=fresh; path=/; domain=.deezer.com")
            r.host == "www.deezer.com" && r.query["method"] == "deezer.pageProfile" ->
                if (profileError != null) Answer("""{"error":{"$profileError":"auth needed"},"results":{}}""")
                else Answer("""{"error":[],"results":{"TAB":{"playlists":{"data":[
                    {"PLAYLIST_ID":"1","TITLE":"Mine","NB_SONG":3,"PARENT_USER_ID":"42"},{"PLAYLIST_ID":"9","TITLE":"Loved","PARENT_USER_ID":"42"},
                    {"PLAYLIST_ID":"5","TITLE":"Theirs","NB_SONG":"12","PARENT_USER_ID":"7"}]}}}}""")
            else -> null
        }
    }

    @Test fun deezerCookiesWithoutArlAreRefusedBeforeAnyCall() {
        val env = env()
        val p = DeezerWebServerProvider()
        val e = failure { p.connect(env, "sid=s; dzr_uniq_id=u", null) }
        assertTrue(e is ProviderException)
        assertTrue(e!!.message!!.contains("arl"))
        assertFalse(p.isConnected(env))
        assertTrue(seen.isEmpty())
    }

    @Test fun deezerPastedCookiesAreCheckedStoredAndUsed() {
        answer = deezer()
        val env = env()
        val p = DeezerWebServerProvider()
        val netscape = "# Netscape HTTP Cookie File\n.deezer.com\tTRUE\t/\tTRUE\t0\tarl\tarl-1\n.example.com\tTRUE\t/\tFALSE\t0\tx\ty\n#HttpOnly_.deezer.com\tTRUE\t/\tTRUE\t0\tdzr_uniq_id\tu1\n"
        runBlocking { p.connect(env, netscape, null) }
        val t = env.tokens.get(p.id)!!
        assertEquals("arl=arl-1; dzr_uniq_id=u1", t.accessToken)
        assertEquals("dz", t.userName)
        assertEquals("42", t.userId)
        assertEquals("null", seen.single().query["api_token"])
        assertEquals("arl=arl-1; dzr_uniq_id=u1", seen.single().headers["cookie"])

        val lists = runBlocking { p.playlists(env) }
        // Loved tracks are the liked slot, not a playlist; a followed playlist is not writable.
        assertEquals(listOf("1", "5"), lists.map { it.id })
        assertEquals(listOf(true, false), lists.map { it.ownedByMe })
        assertEquals(12, lists[1].trackCount)
        val call = seen.last()
        assertEquals("tok", call.query["api_token"])
        // The gateway's own sid rides along with the pasted cookies.
        assertEquals("arl=arl-1; dzr_uniq_id=u1; sid=fresh", call.headers["cookie"])
    }

    @Test fun deezerSignedOutCookiesSaySo() {
        answer = deezer(userId = "0")
        val env = env()
        val p = DeezerWebServerProvider()
        val e = failure { p.connect(env, "arl=old", null) }
        assertTrue(e is ProviderException)
        assertTrue(e!!.message, e.message!!.contains("signed out") && e.message!!.contains("fresh"))
        assertFalse(p.isConnected(env))
    }

    @Test fun deezerExpiredSessionAsksForFreshCookies() {
        answer = deezer(profileError = "NEED_USER_AUTH_REQUIRED")
        val env = env()
        val p = DeezerWebServerProvider()
        runBlocking { p.connect(env, "arl=a", null) }
        val e = failure { p.playlists(env) }
        assertTrue(e is ProviderException)
        assertEquals("Deezer (web): ${DeezerWebServerProvider.EXPIRED}", e!!.message)
    }

    // ------------------------------------------------------------------ Qobuz

    private val qobuzToken = "Ab3_" + "q".repeat(80) + "-Z"

    private fun qobuz(): (Seen) -> Answer? = { r ->
        when {
            r.host != "www.qobuz.com" -> null
            r.path.endsWith("/playlist/getUserPlaylists") -> Answer("""{"user":{"id":1001,"login":"listener"},"playlists":{"offset":0,"limit":500,"total":2,"items":[
                {"id":7001,"name":"Mine","tracks_count":3,"owner":{"id":1001,"name":"listener"}},{"id":7002,"name":"Theirs","tracks_count":9,"owner":{"id":2,"name":"Qobuz"}}]}}""")
            r.path.endsWith("/playlist/deleteTracks") -> Answer("""{"id":7001}""")
            else -> null
        }
    }

    @Test fun qobuzPasteWithoutATokenIsRefusedBeforeAnyCall() {
        val env = env()
        val p = QobuzWebServerProvider()
        val e = failure { p.connect(env, "x-app-id: 798273057", null) }
        assertTrue(e is ProviderException)
        assertTrue(e!!.message!!.contains("X-User-Auth-Token"))
        assertFalse(p.isConnected(env))
        assertTrue(seen.isEmpty())
    }

    @Test fun qobuzPastedRequestIsCheckedStoredAndUsed() {
        answer = qobuz()
        val env = env()
        val p = QobuzWebServerProvider()
        runBlocking { p.connect(env, "curl 'https://www.qobuz.com/api.json/0.2/playlist/getUserPlaylists' -H 'x-app-id: 123456789' -H 'x-user-auth-token: $qobuzToken'", null) }
        assertTrue(p.isConnected(env))
        assertEquals("listener", p.accountName(env))
        assertEquals(qobuzToken, seen.single().headers["x-user-auth-token"])
        assertEquals("123456789", seen.single().headers["x-app-id"])

        val lists = runBlocking { p.playlists(env) }
        assertEquals(listOf("7001", "7002"), lists.map { it.id })
        assertEquals(listOf(true, false), lists.map { it.ownedByMe })
        assertEquals(qobuzToken, seen.last().headers["x-user-auth-token"])

        // Removal by the entries the listing gave.
        runBlocking { p.removeTracks(env, "7001", listOf(com.xlollx.songport.model.Track("11", "One", itemId = "901"))) }
        assertEquals("playlist_id=7001&playlist_track_ids=901", seen.last().body)
    }

    // ------------------------------------------------------------------ the page

    @Test fun pageListsShowsAndConnectsTheCookieConnectors() {
        answer = deezer()
        val data = tmp.newFolder("web")
        installServerConnectors()
        val env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        val server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())
            fun post(path: String, body: String) = client.send(
                HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", base)
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val list = get("/connectors").body()
            assertTrue(list.contains("Spotify (web) (unofficial, alpha)"))
            assertTrue(list.contains("Deezer (web) (unofficial, alpha)"))
            assertTrue(list.contains("Qobuz (web) (unofficial, alpha)"))

            val sp = get("/connectors/spotify_bridge").body()
            assertTrue(sp.contains("Cookies of open.spotify.com"))
            assertTrue(sp.contains("<code>sp_dc</code>"))
            assertTrue(sp.contains("<b>Application</b>"))
            assertTrue(sp.contains("""<span class="badge">unofficial</span> <span class="alpha">alpha</span>"""))
            assertTrue(sp.contains("""action="/connectors/spotify_bridge/connect""""))
            val dz = get("/connectors/deezer_bridge").body()
            assertTrue(dz.contains("Cookies of www.deezer.com"))
            assertTrue(dz.contains("<code>arl</code>"))
            // YouTube Music keeps its own note and the same form.
            val ytm = get("/connectors/ytm").body()
            assertTrue(ytm.contains("Cookies of music.youtube.com"))
            assertTrue(ytm.contains("SAPISID=…; __Secure-3PAPISID=…; SID=…  (the Cookie header, cookies.txt or a JSON export)"))
            assertTrue(ytm.contains("music.youtube.com web player"))

            val r = post("/connectors/deezer_bridge/connect", "cookies=" + java.net.URLEncoder.encode("cookie: arl=web-arl; dzr_uniq_id=u", "UTF-8"))
            assertEquals(303, r.statusCode())
            assertTrue(r.headers().firstValue("Location").orElse(""), r.headers().firstValue("Location").orElse("").contains("ok=Connected"))
            assertEquals("arl=web-arl; dzr_uniq_id=u", env.tokens.get("deezer_bridge")!!.accessToken)
            assertTrue(get("/connectors/deezer_bridge").body().contains("as <b>dz</b>"))
        } finally {
            server.stop(0)
        }
    }

    companion object {
        const val ORIGINAL_HOST = "X-Test-Original-Host"
    }
}

package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.ytmbridge.BridgeNet
import com.xlollx.songport.ytmbridge.DeezerWebClient
import com.xlollx.songport.ytmbridge.SpotifyWebClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Two accounts of one pasted-cookie connector on the server (Accounts.kt): each slot pastes its own
 * cookies, kept in tokens.json under its id ("spotify_bridge@2"), its client's values in a kv file
 * of its own, and every call to the service carries that account's cookies and bearer, never the
 * other's. The primary account keeps the file names it has always had. Every request goes to a
 * local stub ([BridgeNet.interceptor]), never to Spotify or YouTube.
 */
class CookieAccountsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Seen(val host: String, val path: String, val headers: Map<String, String>, val body: String) {
        val cookie: String get() = headers["cookie"].orEmpty()
    }

    private lateinit var stub: HttpServer
    private val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    @Volatile private var answer: (Seen) -> String? = { null }
    private lateinit var env: ServerEnv

    @Before fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            val s = Seen(
                host = ex.requestHeaders.getFirst(WebConnectorsTest.ORIGINAL_HOST) ?: "",
                path = ex.requestURI.path,
                headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(", ") },
                body = ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            seen += s
            val body = answer(s) ?: "not stubbed"
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(if (body == "not stubbed") 404 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        val port = stub.address.port
        BridgeNet.interceptor = Interceptor { chain ->
            val u = chain.request().url
            chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host("127.0.0.1").port(port).build()).header(WebConnectorsTest.ORIGINAL_HOST, u.host).build())
        }
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings.load(null), "http://localhost:8080")
        DeezerWebClient.pauseMs = 0
    }

    @After fun stop() {
        BridgeNet.interceptor = null
        stub.stop(0)
    }

    private fun kv(name: String) = File(env.dataDir, "kv/$name.json")

    // ------------------------------------------------------------------ Spotify (web)

    private val hash = "a".repeat(64)

    /** The player page answers with a bearer named after the sp_dc cookie it was asked with. */
    private fun spDc(r: Seen) = Regex("sp_dc=([^;]+)").find(r.cookie)?.groupValues?.get(1) ?: "none"

    private fun spotify(): (Seen) -> String? = { r ->
        when (r.host) {
            "open.spotify.com" -> """<html><script id="session" type="application/json">{"accessToken":"BQ-${spDc(r)}","accessTokenExpirationTimestampMs":${System.currentTimeMillis() + 3_600_000},"isAnonymous":false,"clientId":"d8a5ed958d274c2e8ee717e6a4b0971d"}</script></html>"""
            "www.spotify.com" -> """{"profile":{"username":"user-${spDc(r)}","name":"Me ${spDc(r)}"}}"""
            "raw.githubusercontent.com" -> """{"operations":{"libraryV3":{"hash":"$hash"}}}"""
            "clienttoken.spotify.com" -> """{"response_type":"RESPONSE_GRANTED_TOKEN_RESPONSE","granted_token":{"token":"CT-1","expires_after_seconds":1209600,"refresh_after_seconds":1209600}}"""
            "api-partner.spotify.com" -> {
                val who = r.headers["authorization"].orEmpty().removePrefix("Bearer BQ-")
                """{"data":{"me":{"libraryV3":{"__typename":"LibraryPage","totalCount":1,"items":[
                {"item":{"_uri":"spotify:playlist:pl-$who","data":{"__typename":"Playlist","uri":"spotify:playlist:pl-$who","name":"Mix $who","ownerV2":{"data":{"username":"user-$who"}}}}}]}}}}"""
            }
            else -> null
        }
    }

    /** The primary and a second Spotify (web) account, each signed in with its own cookies. */
    private fun twoSpotifys(): Pair<SpotifyWebServerProvider, SpotifyWebServerProvider> {
        answer = spotify()
        assertEquals("spotify_bridge", Accounts.add(env.store, "spotify_bridge", another = false, name = null))
        assertEquals("spotify_bridge@2", Accounts.add(env.store, "spotify_bridge", another = true, name = "Work"))
        val one = Providers.byId("spotify_bridge") as SpotifyWebServerProvider
        val two = Providers.byId("spotify_bridge@2") as SpotifyWebServerProvider
        assertEquals("2", two.slot)
        runBlocking { one.connect(env, "sp_dc=one; sp_t=d1", "Mozilla/5.0 (X11) One") }
        runBlocking { two.connect(env, "sp_dc=two; sp_t=d2", "Mozilla/5.0 (X11) Two") }
        return one to two
    }

    @Test fun twoSpotifyAccountsKeepTheirOwnCookiesTokensAndCaches() {
        val (one, two) = twoSpotifys()
        // Each account's cookies and user agent under its own id in tokens.json.
        assertEquals("sp_dc=one; sp_t=d1", env.tokens.get("spotify_bridge")!!.accessToken)
        assertEquals("sp_dc=two; sp_t=d2", env.tokens.get("spotify_bridge@2")!!.accessToken)
        assertEquals("Mozilla/5.0 (X11) One", env.tokens.get("spotify_bridge")!!.extra[ServerWebHost.UA])
        assertEquals("Mozilla/5.0 (X11) Two", env.tokens.get("spotify_bridge@2")!!.extra[ServerWebHost.UA])
        assertEquals("Me one", one.accountName(env)); assertEquals("Me two", two.accountName(env))
        assertEquals("Work", two.label(env))
        // The kv file of the primary is the one every version wrote; the second account's sits next to it.
        assertTrue(kv("spotify_web").exists())
        assertTrue(kv("spotify_web_2").exists())
        assertFalse(kv("spotify_web_3").exists())

        // Each account's calls carry its own cookies and the bearer its own page handed out.
        seen.clear()
        val pl1 = runBlocking { one.playlists(env) }
        val pl2 = runBlocking { two.playlists(env) }
        assertEquals(listOf("pl-one"), pl1.map { it.id })
        assertEquals(listOf("pl-two"), pl2.map { it.id })
        val gql = seen.filter { it.host == "api-partner.spotify.com" }
        assertEquals(setOf("Bearer BQ-one", "Bearer BQ-two"), gql.map { it.headers["authorization"] }.toSet())
        assertTrue(seen.filter { it.host == "open.spotify.com" }.all { it.cookie.startsWith("sp_dc=one") || it.cookie.startsWith("sp_dc=two") })
        assertEquals("Mozilla/5.0 (X11) Two", gql.first { it.headers["authorization"] == "Bearer BQ-two" }.headers["user-agent"])

        // A second listing reuses each account's own cached page: still its own bearer.
        seen.clear()
        runBlocking { two.playlists(env) }
        assertEquals(listOf("Bearer BQ-two"), seen.filter { it.host == "api-partner.spotify.com" }.map { it.headers["authorization"] })
        assertTrue("the page is cached per account", seen.none { it.host == "open.spotify.com" })
    }

    @Test fun disconnectingOneSpotifyAccountLeavesTheOtherSignedIn() {
        val (one, two) = twoSpotifys()
        two.disconnect(env)
        assertFalse(two.isConnected(env))
        assertTrue(one.isConnected(env))
        assertEquals("sp_dc=one; sp_t=d1", env.tokens.get("spotify_bridge")!!.accessToken)
        assertNull(env.tokens.get("spotify_bridge@2"))
        assertTrue(kv("spotify_web").length() > 2)
        assertEquals(listOf("pl-one"), runBlocking { one.playlists(env) }.map { it.id })
        // And the other way round.
        runBlocking { two.connect(env, "sp_dc=two; sp_t=d2", null) }
        one.disconnect(env)
        assertFalse(one.isConnected(env))
        assertTrue(two.isConnected(env))
        assertEquals(listOf("pl-two"), runBlocking { two.playlists(env) }.map { it.id })
    }

    @Test fun anExtraCookieAccountInUseIsNotRemovedAndGoesWithItsSignInOtherwise() {
        val (one, two) = twoSpotifys()
        env.store.upsertJob(SyncJob("j1", "One to two", PlaylistRef("spotify_bridge", "pl-one"), PlaylistRef("spotify_bridge@2", "pl-two")))
        assertFalse(Accounts.remove(env, two))
        assertTrue(two.isConnected(env))
        assertEquals(listOf("spotify_bridge", "spotify_bridge@2"), env.store.data.settings.connectors)

        env.store.deleteJob("j1")
        assertTrue(Accounts.remove(env, two))
        assertEquals(listOf("spotify_bridge"), env.store.data.settings.connectors)
        assertEquals(emptyMap<String, List<String>>(), env.store.data.settings.extraAccounts)
        assertNull(env.store.data.settings.connectorNames["spotify_bridge@2"])
        assertNull(env.tokens.get("spotify_bridge@2"))
        assertNull(Providers.byId("spotify_bridge@2"))
        // The primary stays as it was: signed in, its file untouched.
        assertTrue(one.isConnected(env))
        assertEquals("sp_dc=one; sp_t=d1", env.tokens.get("spotify_bridge")!!.accessToken)
        assertTrue(kv("spotify_web").exists())
    }

    @Test fun theConfigurationFileCarriesACookieSlotWithoutItsCookies() {
        twoSpotifys()
        env.store.upsertJob(SyncJob("j1", "One to two", PlaylistRef("spotify_bridge", "pl-one"), PlaylistRef("spotify_bridge@2", "pl-two")))
        val text = ConfigBackup.export(env, "test")
        assertFalse("no cookies in the file", text.contains("sp_dc"))
        assertTrue(text.contains("spotify_bridge@2"))
        val other = ServerEnv(tmp.newFolder("other"), Strings.load(null), "http://localhost:8080")
        ConfigBackup.import(other, ConfigBackup.read(text))
        val s = other.store.data.settings
        assertEquals(listOf("spotify_bridge", "spotify_bridge@2"), s.connectors)
        assertEquals(mapOf("spotify_bridge" to listOf("2")), s.extraAccounts)
        assertEquals("Work", s.connectorNames["spotify_bridge@2"])
        assertEquals("spotify_bridge@2", other.store.data.jobs.single().target.provider)
        val restored = Providers.byId("spotify_bridge@2")
        assertNotNull(restored)
        assertEquals("Work", restored!!.label(other))
        // Signed in again afterwards, account by account: nothing travelled.
        assertNull(other.tokens.get("spotify_bridge@2"))
        assertFalse(restored.isConnected(other))
        // Importing never touched the exporting server's sign-ins.
        assertNotNull(env.tokens.get("spotify_bridge@2"))
    }

    // ------------------------------------------------------------------ YouTube Music

    /** The player page, the account menu and the playlists, named after the SAPISID cookie of the request. */
    private fun ytm(): (Seen) -> String? = { r ->
        val who = Regex("SAPISID=([^;]+)").find(r.cookie)?.groupValues?.get(1) ?: "anon"
        when {
            r.host != "music.youtube.com" -> null
            r.path == "/" -> """<script>ytcfg.set({"VISITOR_DATA":"visitor-$who","INNERTUBE_CLIENT_VERSION":"1.20251020.01.00"});</script>"""
            r.path.endsWith("/account/account_menu") -> """{"accountName":{"runs":[{"text":"Me $who"}]}}"""
            r.path.endsWith("/browse") && r.body.contains("FEmusic_liked_playlists") -> """{"contents":{"items":[{"musicTwoRowItemRenderer":{
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLpl-$who"}},"title":{"runs":[{"text":"Mix $who"}]},"subtitle":{"runs":[{"text":"3 songs"}]}}}]}}"""
            else -> "{}"
        }
    }

    @Test fun twoYouTubeMusicAccountsCallWithTheirOwnCookiesAndVisitor() {
        answer = ytm()
        assertEquals("ytm", Accounts.add(env.store, "ytm", another = false, name = null))
        assertEquals("ytm@2", Accounts.add(env.store, "ytm", another = true, name = "Second"))
        val one = Providers.byId("ytm") as YtmServerProvider
        val two = Providers.byId("ytm@2") as YtmServerProvider
        runBlocking { one.connect(env, "SAPISID=aaa; __Secure-3PAPISID=aaa; SID=s1", "Mozilla/5.0 One") }
        runBlocking { two.connect(env, "SAPISID=bbb; __Secure-3PAPISID=bbb; SID=s2", "Mozilla/5.0 Two") }
        assertEquals("Me aaa", env.tokens.get("ytm")!!.userName)
        assertEquals("Me bbb", env.tokens.get("ytm@2")!!.userName)
        assertTrue(env.tokens.get("ytm")!!.accessToken.contains("SAPISID=aaa"))
        assertTrue(env.tokens.get("ytm@2")!!.accessToken.contains("SAPISID=bbb"))

        seen.clear()
        assertEquals(listOf("pl-aaa"), runBlocking { one.playlists(env) }.map { it.id })
        assertEquals(listOf("pl-bbb"), runBlocking { two.playlists(env) }.map { it.id })
        val calls = seen.filter { it.path.endsWith("/browse") }
        assertEquals(2, calls.size)
        // Each call signs with its own cookies, and carries the visitor id its own page gave.
        assertTrue(calls[0].cookie.contains("SAPISID=aaa") && !calls[0].cookie.contains("bbb"))
        assertTrue(calls[1].cookie.contains("SAPISID=bbb") && !calls[1].cookie.contains("aaa"))
        assertTrue(calls[0].headers["authorization"]!!.startsWith("SAPISIDHASH "))
        assertTrue(calls[0].headers["authorization"] != calls[1].headers["authorization"])
        assertEquals("visitor-aaa", calls[0].headers["x-goog-visitor-id"])
        assertEquals("visitor-bbb", calls[1].headers["x-goog-visitor-id"])
        assertEquals("Mozilla/5.0 Two", calls[1].headers["user-agent"])

        // Signing out of the second leaves the first as it was; the block store is the shared one, as before.
        two.disconnect(env)
        assertFalse(two.isConnected(env))
        assertTrue(one.isConnected(env))
        assertEquals("Me aaa", one.accountName(env))
        assertFalse("Google's block is the address's, kept once for every account", kv("ytm_verification_2").exists())
    }

    @Test fun withoutExtraAccountsTheFilesAreTheOnesTheyHaveAlwaysBeen() {
        answer = spotify()
        val p = SpotifyWebServerProvider()
        runBlocking { p.connect(env, "sp_dc=one", null) }
        assertEquals("", p.slot)
        assertEquals(setOf("spotify_web.json"), File(env.dataDir, "kv").list()!!.toSet())
        assertNotNull(env.tokens.get("spotify_bridge"))
        assertEquals("spotify_web", ServerWebHost.kvName("spotify_web", ""))
        assertEquals("spotify_web@2", ServerWebHost.kvName("spotify_web", "2"))
        assertEquals("", ServerWebHost.slotOf("spotify_bridge"))
        assertEquals("2", ServerWebHost.slotOf("spotify_bridge@2"))
        // Dropping a page by its scope leaves the other accounts' pages: a fresh page is fetched for that one only.
        SpotifyWebClient.dropPage(ServerWebHost.scope(env, "spotify_bridge"))
        seen.clear()
        runBlocking { p.playlists(env) }
        assertEquals(1, seen.count { it.host == "open.spotify.com" })
    }
}

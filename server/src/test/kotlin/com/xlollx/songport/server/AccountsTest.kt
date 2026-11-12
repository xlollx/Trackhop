package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.net.get
import com.xlollx.songport.providers.Providers
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * More than one account of a service on the web page (Accounts.kt): two Jellyfin servers side by
 * side, each with its own sign-in, synced to each other; Quick Connect; and a data folder written
 * before any of this, which must read exactly as it did.
 */
class AccountsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    /**
     * A Jellyfin server named [name]: its own token and its own playlist, and the token every call
     * came with, to tell which account asked. Quick Connect approves once [approved] is set.
     */
    private class FakeJellyfin(val name: String) {
        val tokens = mutableListOf<String>()
        @Volatile var approved = false
        @Volatile var quickConnect = true
        val qcSignIns = java.util.concurrent.atomic.AtomicInteger()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                ex.requestHeaders.getFirst("Authorization")?.let { a -> Regex("Token=\"([^\"]+)\"").find(a)?.groupValues?.get(1)?.let { synchronized(tokens) { tokens += it } } }
                ex.requestBody.readBytes()
                val (code, body) = when {
                    path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok-$name","User":{"Id":"u-$name"}}"""
                    path == "/Users/u-$name/Items" -> 200 to """{"Items":[{"Id":"pl-$name","Name":"Mix $name","ChildCount":1}]}"""
                    path == "/QuickConnect/Enabled" -> 200 to "$quickConnect"
                    path == "/QuickConnect/Initiate" && quickConnect -> 200 to """{"Secret":"s3cr3t","Code":"123456","Authenticated":false}"""
                    path == "/QuickConnect/Connect" -> 200 to """{"Secret":"s3cr3t","Code":"123456","Authenticated":$approved}"""
                    path == "/Users/AuthenticateWithQuickConnect" -> qcSignIns.incrementAndGet().let { 200 } to """{"AccessToken":"qc-$name","User":{"Id":"u-$name","Name":"qc user"}}"""
                    else -> 404 to ""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${http.address.port}"
        fun stop() = http.stop(0)
    }

    private val home = FakeJellyfin("home")
    private val friend = FakeJellyfin("friend")

    @Before fun start() {
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() {
        server.stop(0)
        home.stop(); friend.stop()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun get(path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, form: Map<String, String>, vararg headers: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Content-Type", "application/x-www-form-urlencoded").header("Origin", base)
            .apply { if (headers.isNotEmpty()) headers(*headers) }
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun HttpResponse<String>.location() = headers().firstValue("Location").orElse("")

    /** Jellyfin, and a second Jellyfin named "Jellyfin di casa", each signed in to its own server. */
    private fun twoJellyfins() {
        assertEquals("/connectors/jellyfin", post("/connectors/add", mapOf("service" to "jellyfin")).location())
        val second = post("/connectors/add", mapOf("service" to "jellyfin@new", "name" to "Jellyfin di casa"))
        assertEquals("/connectors/${enc("jellyfin@2")}", second.location())
        assertTrue(post("/connectors/jellyfin/connect", mapOf("url" to friend.url, "user" to "f", "secret" to "x")).location().contains("ok="))
        assertTrue(post("/connectors/${enc("jellyfin@2")}/connect", mapOf("url" to home.url, "user" to "h", "secret" to "y")).location().contains("ok="))
    }

    @Test fun twoJellyfinAccountsKeepTheirOwnSignIn() {
        twoJellyfins()
        val s = env.store.data.settings
        assertEquals(listOf("jellyfin", "jellyfin@2"), s.connectors)
        assertEquals(mapOf("jellyfin" to listOf("2")), s.extraAccounts)
        assertEquals("Jellyfin di casa", s.connectorNames["jellyfin@2"])
        assertNull(s.connectorNames["jellyfin"])
        assertEquals("tok-friend", env.tokens.get("jellyfin")?.accessToken)
        assertEquals(friend.url, env.tokens.get("jellyfin")?.extra?.get("url"))
        assertEquals("tok-home", env.tokens.get("jellyfin@2")?.accessToken)
        assertEquals(home.url, env.tokens.get("jellyfin@2")?.extra?.get("url"))

        val list = get("/connectors").body()
        assertTrue(list.contains("Jellyfin di casa"))
        assertTrue(list.contains("href=\"/connectors/${enc("jellyfin@2")}\""))
        // A third one is offered; the cookie connectors never are (their sessions are kept per service).
        assertTrue(list.contains("value=\"jellyfin@new\""))
        assertFalse(list.contains("value=\"ytm@new\""))
        assertFalse(list.contains("value=\"spotify_bridge@new\""))

        // Renamed; blank gives the service's name back.
        post("/connectors/${enc("jellyfin@2")}/rename", mapOf("name" to "Jellyfin in salotto"))
        assertEquals("Jellyfin in salotto", Providers.byId("jellyfin@2")!!.label(env))
        post("/connectors/${enc("jellyfin@2")}/rename", mapOf("name" to " "))
        assertEquals("Jellyfin · h", Providers.byId("jellyfin@2")!!.label(env))

        // Signing one out leaves the other.
        post("/connectors/${enc("jellyfin@2")}/disconnect", emptyMap())
        assertNull(env.tokens.get("jellyfin@2"))
        assertNotNull(env.tokens.get("jellyfin"))

        // The same post again (a reload) adds nothing: only "another account" makes a new one.
        post("/connectors/add", mapOf("service" to "jellyfin"))
        assertEquals(listOf("jellyfin", "jellyfin@2"), env.store.data.settings.connectors)
        assertEquals("/connectors/${enc("jellyfin@3")}", post("/connectors/add", mapOf("service" to "jellyfin@new")).location())
        assertEquals("Jellyfin 3", env.store.data.settings.connectorNames["jellyfin@3"])
    }

    @Test fun aSyncBetweenTwoAccountsOfOneServiceIsAcceptedTheSamePlaylistIsNot() {
        twoJellyfins()
        // Each side lists its own server's playlists, asked with its own token.
        val form = get("/syncs/new?src=jellyfin&dst=${enc("jellyfin@2")}").body()
        assertTrue(form, form.contains("Mix friend") && form.contains("Mix home"))
        assertTrue(friend.tokens.all { it == "tok-friend" } && friend.tokens.isNotEmpty())
        assertTrue(home.tokens.all { it == "tok-home" } && home.tokens.isNotEmpty())

        val made = post("/syncs", mapOf("src" to "jellyfin", "dst" to "jellyfin@2", "srcPlaylist" to "pl-friend", "dstPlaylist" to "pl-home", "every" to "1440"))
        assertTrue(made.location(), made.location().startsWith("/syncs/") && made.location().contains("ok="))
        val job = env.store.data.jobs.single()
        assertEquals(PlaylistRef("jellyfin", "pl-friend", "Mix friend"), job.source)
        assertEquals(PlaylistRef("jellyfin@2", "pl-home", "Mix home"), job.target)

        // One playlist onto itself: refused, as in the app.
        val same = post("/syncs", mapOf("src" to "jellyfin", "dst" to "jellyfin", "srcPlaylist" to "pl-friend", "dstPlaylist" to "pl-friend"))
        assertTrue(same.location(), same.location().contains("err=") && same.location().contains("error_same_playlist"))
        // The same account, another playlist (its favourites): fine, as it always was.
        val liked = post("/syncs", mapOf("src" to "jellyfin", "dst" to "jellyfin", "srcPlaylist" to "pl-friend", "dstPlaylist" to "__liked__"))
        assertFalse(liked.location(), liked.location().contains("err="))
        assertEquals(2, env.store.data.jobs.size)
    }

    @Test fun anExtraAccountInUseIsNotRemoved() {
        twoJellyfins()
        post("/syncs", mapOf("src" to "jellyfin", "dst" to "jellyfin@2", "srcPlaylist" to "pl-friend", "dstPlaylist" to "pl-home"))
        val refused = post("/connectors/${enc("jellyfin@2")}/remove", emptyMap())
        assertTrue(refused.location(), refused.location().contains("account_remove_blocked"))
        assertEquals(listOf("jellyfin", "jellyfin@2"), env.store.data.settings.connectors)
        assertNotNull(Providers.byId("jellyfin@2"))

        env.store.deleteJob(env.store.data.jobs.single().id)
        assertEquals("/connectors", post("/connectors/${enc("jellyfin@2")}/remove", emptyMap()).location())
        val s = env.store.data.settings
        assertEquals(listOf("jellyfin"), s.connectors)
        assertEquals(emptyMap<String, List<String>>(), s.extraAccounts)
        assertNull(s.connectorNames["jellyfin@2"])
        assertNull(env.tokens.get("jellyfin@2"))
        assertNull(Providers.byId("jellyfin@2"))
        // The main account goes as it always did: its sign-in kept.
        assertEquals("/connectors", post("/connectors/jellyfin/remove", emptyMap()).location())
        assertNotNull(env.tokens.get("jellyfin"))
    }

    @Test fun theApiAndTheConfigurationFileCarryTheExtraAccounts() {
        twoJellyfins()
        post("/syncs", mapOf("src" to "jellyfin", "dst" to "jellyfin@2", "srcPlaylist" to "pl-friend", "dstPlaylist" to "pl-home"))
        val token = Api.newToken(env)
        val services = client.send(HttpRequest.newBuilder(URI("$base/api/v1/services")).header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofString())
        val list = parseJson(services.body()).jsonArray.map { it.jsonObject }
        val second = list.single { it["id"]?.jsonPrimitive?.content == "jellyfin@2" }
        assertEquals("Jellyfin di casa", second["name"].str)
        assertEquals("jellyfin", second["serviceId"].str)
        assertEquals("2", second["slot"].str)
        assertEquals("", list.single { it["id"].str == "jellyfin" }["slot"].str)
        val syncs = client.send(HttpRequest.newBuilder(URI("$base/api/v1/syncs")).header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals("jellyfin@2", parseJson(syncs.body()).jsonArray.single()["target"]["service"].str)

        // Export, then import into a Trackhop that has never seen them.
        val text = ConfigBackup.export(env, "test")
        assertFalse("no sign-in in the file", text.contains("tok-home") || text.contains("tok-friend"))
        val other = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://localhost:8080")
        ConfigBackup.import(other, ConfigBackup.read(text))
        val s = other.store.data.settings
        assertEquals(listOf("jellyfin", "jellyfin@2"), s.connectors)
        assertEquals(mapOf("jellyfin" to listOf("2")), s.extraAccounts)
        assertEquals("Jellyfin di casa", s.connectorNames["jellyfin@2"])
        assertEquals("jellyfin@2", other.store.data.jobs.single().target.provider)
        assertEquals("Jellyfin di casa", Providers.byId("jellyfin@2")!!.label(other))
        // Signed in again afterwards, account by account.
        assertNull(other.tokens.get("jellyfin@2"))
        // And back again on a restart of that data folder.
        other.store.flush()
        val again = ServerEnv(File(tmp.root, "other"), Strings(emptyMap()), "http://localhost:8080")
        assertEquals(mapOf("jellyfin" to listOf("2")), again.store.data.settings.extraAccounts)
        assertEquals(listOf("jellyfin", "jellyfin@2"), Providers.connectors().map { it.id })
    }

    @Test fun aDataFolderFromBeforeReadsAsItDid() {
        // store.json and tokens.json as servers write them today (and as older ones did, without the
        // newer fields): one account per service, no names.
        val today = """{"jobs":[{"id":"j1","name":"Mix","source":{"provider":"jellyfin","playlistId":"p1","playlistName":"Mix"},"target":{"provider":"spotify","playlistId":"s1","playlistName":"Mix"},"schedule":"DAILY","enabled":true}],
"settings":{"clientIds":{"spotify":"my-client"},"extraAccounts":{},"connectors":["jellyfin","spotify","csv"],"connectorNames":{},"connectorsInit":true}}"""
        val older = """{"jobs":[{"id":"j1","name":"Mix","source":{"provider":"jellyfin","playlistId":"p1","playlistName":"Mix"},"target":{"provider":"spotify","playlistId":"s1","playlistName":"Mix"},"schedule":"DAILY"}],
"settings":{"clientIds":{"spotify":"my-client"},"connectors":["jellyfin","spotify","csv"],"connectorsInit":true}}"""
        val tokens = """{"jellyfin":{"accessToken":"t1","userId":"u1","userName":"lorenzo","extra":{"url":"http://jf.local:8096"}},"spotify":{"accessToken":"a","refreshToken":"r","expiresAt":0,"userName":"me"}}"""
        for ((name, store) in listOf("today" to today, "older" to older)) {
            val dir = tmp.newFolder(name)
            File(dir, "store.json").writeText(store)
            File(dir, "tokens.json").writeText(tokens)
            installServerConnectors()
            val e = ServerEnv(dir, Strings(emptyMap()), "http://localhost:8080")
            val s = e.store.data.settings
            assertEquals(name, listOf("jellyfin", "spotify", "csv"), s.connectors)
            assertEquals(emptyMap<String, List<String>>(), s.extraAccounts)
            assertEquals(listOf("jellyfin", "spotify", "csv"), Providers.connectors().map { it.id })
            assertEquals(1, Providers.accountsOf("jellyfin").size)
            assertEquals("Jellyfin", Providers.byId("jellyfin")!!.label(e))
            assertTrue(Providers.byId("jellyfin")!!.isConnected(e))
            assertEquals("lorenzo", Providers.byId("jellyfin")!!.accountName(e))
            assertTrue(Providers.byId("spotify")!!.isConnected(e))
            assertEquals("jellyfin", e.store.data.jobs.single().source.provider)
            assertEquals("my-client", s.clientIds["spotify"])
            // Written back after a change, it still says the same.
            e.store.updateSettings { it.copy(notifyOnSync = false) }
            e.store.flush()
            val reread = ServerEnv(dir, Strings(emptyMap()), "http://localhost:8080")
            assertEquals(listOf("jellyfin", "spotify", "csv"), reread.store.data.settings.connectors)
            assertEquals(emptyMap<String, List<String>>(), reread.store.data.settings.extraAccounts)
            assertEquals(1, reread.store.data.jobs.size)
            assertEquals("t1", JsonTokenVault(File(dir, "tokens.json")).get("jellyfin")?.accessToken)
        }
    }

    // ------------------------------------------------------------------ OpenSubsonic API key

    /** An OpenSubsonic server that takes API keys (apiKeyAuthentication) and records what each call carried. */
    private class FakeOpenSubsonic {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val method = ex.requestURI.path.substringAfterLast('/')
                val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
                    .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                synchronized(calls) { calls += method to q }
                val ok = """"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true"""
                val body = when (method) {
                    "getOpenSubsonicExtensions" -> """{"subsonic-response":{$ok,"openSubsonicExtensions":[{"name":"apiKeyAuthentication","versions":[1]}]}}"""
                    "tokenInfo" -> if (q["apiKey"] == "k3y") """{"subsonic-response":{$ok,"tokenInfo":{"username":"lorenzo"}}}"""
                        else """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":44,"message":"Invalid API key"}}}"""
                    else -> """{"subsonic-response":{$ok}}"""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url = "http://127.0.0.1:${http.address.port}"
        fun stop() = http.stop(0)
    }

    @Test fun subsonicOffersAnApiKeySignInBesideThePassword() {
        val nav = FakeOpenSubsonic()
        try {
            post("/connectors/add", mapOf("service" to "subsonic"))
            val page = get("/connectors/subsonic").body()
            // The password form as before, and a second one for the key.
            assertTrue(page.contains("""name="secret""""))
            assertTrue(page.contains("""name="mode" value="apikey""""))
            assertTrue(page.contains("""name="apiKey""""))
            assertTrue(page.contains("login_apikey"))

            assertTrue(post("/connectors/subsonic/connect", mapOf("mode" to "apikey", "url" to nav.url, "apiKey" to "wrong")).location().contains("err="))
            assertNull(env.tokens.get("subsonic"))
            val ok = post("/connectors/subsonic/connect", mapOf("mode" to "apikey", "url" to nav.url, "apiKey" to "k3y"))
            assertTrue(ok.location(), ok.location().contains("ok="))
            val t = env.tokens.get("subsonic")!!
            assertEquals("lorenzo", t.userName)
            assertEquals("apikey", t.extra["auth"])
            val info = nav.calls.last()
            assertEquals("tokenInfo", info.first)
            assertEquals("k3y", info.second["apiKey"])
            assertTrue(listOf("u", "t", "s", "p").none { it in info.second })
        } finally { nav.stop() }
    }

    @Test fun subsonicPasswordSignInStaysAsItWas() {
        val nav = FakeOpenSubsonic()
        try {
            post("/connectors/add", mapOf("service" to "subsonic"))
            assertTrue(post("/connectors/subsonic/connect", mapOf("url" to nav.url, "user" to "lorenzo", "secret" to "pw")).location().contains("ok="))
            val ping = nav.calls.single()
            assertEquals("ping", ping.first)
            assertEquals("lorenzo", ping.second["u"])
            assertNull(ping.second["apiKey"])
            assertNull(env.tokens.get("subsonic")!!.extra["auth"])
        } finally { nav.stop() }
    }

    // ------------------------------------------------------------------ Quick Connect

    private fun qcToken(location: String) = location.substringAfter("t=")

    @Test fun quickConnectShowsTheCodeAndSignsInOnceApproved() {
        post("/connectors/add", mapOf("service" to "jellyfin"))
        post("/connectors/add", mapOf("service" to "jellyfin@new"))
        val page = get("/connectors/${enc("jellyfin@2")}").body()
        assertTrue(page.contains("action=\"/connectors/${enc("jellyfin@2")}/quickconnect\""))
        assertTrue(page.contains("login_qc_button"))

        val started = post("/connectors/${enc("jellyfin@2")}/quickconnect", mapOf("url" to home.url))
        assertTrue(started.location(), started.location().startsWith("/connectors/${enc("jellyfin@2")}/quickconnect?t="))
        val waiting = get(started.location()).body()
        assertTrue(waiting.contains("123456") && waiting.contains("login_qc_waiting"))
        val t = qcToken(started.location())
        val json = arrayOf("Accept", "application/json")
        val poll = "/connectors/${enc("jellyfin@2")}/quickconnect/poll"
        assertEquals("waiting", parseJson(post(poll, mapOf("t" to t), *json).body())["state"].str)
        assertNull(env.tokens.get("jellyfin@2"))
        // A plain form (no script): back to the code while it waits.
        assertEquals(started.location(), post(poll, mapOf("t" to t)).location())

        home.approved = true
        val done = parseJson(post(poll, mapOf("t" to t), *json).body())
        assertEquals("done", done["state"].str)
        assertTrue(done["next"].str!!.startsWith("/connectors/${enc("jellyfin@2")}?ok="))
        assertEquals("qc-home", env.tokens.get("jellyfin@2")?.accessToken)
        assertEquals(home.url, env.tokens.get("jellyfin@2")?.extra?.get("url"))
        assertNull("the main account is untouched", env.tokens.get("jellyfin"))
        // Asked again (a second tab): the same answer, one sign-in.
        assertEquals("done", parseJson(post(poll, mapOf("t" to t), *json).body())["state"].str)
        assertEquals(1, home.qcSignIns.get())

        // A code of another connector, or none, is not this one's.
        val wrong = parseJson(post("/connectors/jellyfin/quickconnect/poll", mapOf("t" to t), *json).body())
        assertEquals("error", wrong["state"].str)
        assertTrue(wrong["next"].str!!.contains("jellyfin_qc_expired"))
        assertEquals(405, get(poll).statusCode())
    }

    @Test fun quickConnectOffIsSaidAndEmbyHasNone() {
        post("/connectors/add", mapOf("service" to "jellyfin"))
        home.quickConnect = false
        val off = post("/connectors/jellyfin/quickconnect", mapOf("url" to home.url))
        assertTrue(off.location(), off.location().startsWith("/connectors/jellyfin?err=") && off.location().contains("jellyfin_qc_off"))
        post("/connectors/add", mapOf("service" to "emby"))
        assertFalse(get("/connectors/emby").body().contains("/quickconnect"))
        assertEquals(404, post("/connectors/emby/quickconnect", mapOf("url" to home.url)).statusCode())
        // Not reachable from another site either.
        val cross = client.send(
            HttpRequest.newBuilder(URI("$base/connectors/jellyfin/quickconnect")).header("Authorization", auth).header("Origin", "http://evil.example")
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("url=x")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, cross.statusCode())
    }

    @Test fun lyrionAndMopidyConnectFromThePageWithTheAddressAlone() {
        // One stand-in answering as both servers do to the first call of a sign-in.
        val nas = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.readBytes()
                val (code, body) = when (ex.requestURI.path) {
                    "/jsonrpc.js" -> 200 to """{"id":1,"method":"slim.request","result":{"_version":"9.1.0"}}"""
                    "/mopidy/rpc" -> 200 to """{"jsonrpc":"2.0","id":1,"result":"3.4.2"}"""
                    else -> 404 to ""
                }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${nas.address.port}"
            post("/connectors/add", mapOf("service" to "lyrion"))
            val lyrion = get("/connectors/lyrion").body()
            // User and password are there for a protected server, and may stay empty.
            assertTrue(lyrion.contains("""name="user" autocomplete="off">"""))
            assertTrue(lyrion.contains("""name="url" required"""))
            assertTrue(lyrion.contains("beta"))
            assertTrue(post("/connectors/lyrion/connect", mapOf("url" to url, "user" to "", "secret" to "")).location().contains("?ok="))
            assertEquals(url, env.tokens.get("lyrion")?.extra?.get("url"))

            post("/connectors/add", mapOf("service" to "mopidy"))
            val mopidy = get("/connectors/mopidy").body()
            assertFalse(mopidy.contains("""name="user""""))
            assertFalse(mopidy.contains("""name="secret""""))
            assertTrue(post("/connectors/mopidy/connect", mapOf("url" to url)).location().contains("?ok="))
            assertEquals(url, env.tokens.get("mopidy")?.extra?.get("url"))
            // Somewhere that is neither: said, and nothing saved over the sign-in that works.
            assertTrue(post("/connectors/lyrion/disconnect", emptyMap()).location().contains("?ok="))
            assertTrue(post("/connectors/lyrion/connect", mapOf("url" to "$url/x")).location().contains("?err="))
            assertNull(env.tokens.get("lyrion"))
        } finally { nas.stop(0) }
    }

    @Test fun slotsAreNumberedAsInTheApp() {
        assertEquals("2", Accounts.nextSlot(emptyList()))
        assertEquals("3", Accounts.nextSlot(listOf("2")))
        assertEquals("5", Accounts.nextSlot(listOf("2", "4")))
        assertNotNull(Providers.byId("jellyfin"))
        assertTrue(Accounts.allowsAnother(Providers.byId("jellyfin")!!))
        assertTrue(Accounts.allowsAnother(Providers.byId("plex")!!))
        assertTrue(Accounts.allowsAnother(Providers.byId("subsonic")!!))
        assertTrue(Accounts.allowsAnother(Providers.byId("spotify")!!))
        assertFalse(Accounts.allowsAnother(Providers.byId("csv")!!))
        // The pasted-cookie connectors too: each account has its own cookies, kv file and caches.
        val cookies = Providers.services().filter { it is CookieConnector }
        assertTrue(cookies.size >= 6)
        cookies.forEach { assertTrue(it.id, Accounts.allowsAnother(it)) }
    }
}

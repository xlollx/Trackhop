package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.ytmbridge.BridgeNet
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
import java.util.Collections

/**
 * The YouTube Music channel on the server (issue #41): the switcher read from Google (a local stub
 * here), the choice kept in tokens.json under the account's own id, and every signed-in call of
 * that account made as the brand account, while the other account and a session without a choice
 * call exactly as before.
 */
class YtmChannelServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Seen(val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var stub: HttpServer
    private val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
    private lateinit var env: ServerEnv

    private val accountsList = """{"actions":[{"updateAccountSectionAction":{"contents":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
        {"accountItem":{"accountName":{"runs":[{"text":"Giulia Rossi"}]},"accountByline":{"runs":[{"text":"giulia@example.com"}]},"isSelected":true,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{}}]}}}},
        {"accountItem":{"accountName":{"runs":[{"text":"Rossi Records"}]},"accountByline":{"runs":[{"text":"Brand Account"}]},"isSelected":false,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"112233"}}]}}}}
    ]}}]}}]}}]}"""

    @Before fun start() {
        stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/") { ex ->
            val s = Seen(ex.requestURI.path, ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(", ") }, ex.requestBody.readBytes().toString(Charsets.UTF_8))
            seen += s
            val onBehalf = Regex(""""onBehalfOfUser"\s*:\s*"([^"]+)"""").find(s.body)?.groupValues?.get(1)
            val body = when {
                s.path == "/" -> """<script>ytcfg.set({"VISITOR_DATA":"CgtWSVNJVE9SX0lE","INNERTUBE_CLIENT_VERSION":"1.20251020.01.00"});</script>"""
                s.path.endsWith("/account/accounts_list") -> accountsList
                s.path.endsWith("/account/account_menu") -> """{"accountName":{"runs":[{"text":"${if (onBehalf == "112233") "Rossi Records" else "Giulia Rossi"}"}]}}"""
                s.path.endsWith("/browse") -> """{"contents":{"items":[{"musicTwoRowItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"VLpl-${onBehalf ?: "own"}"}},"title":{"runs":[{"text":"Mix"}]},"subtitle":{"runs":[{"text":"3 songs"}]}}}]}}"""
                else -> "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        stub.start()
        val port = stub.address.port
        BridgeNet.interceptor = Interceptor { chain ->
            val u = chain.request().url
            chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host("127.0.0.1").port(port).build()).build())
        }
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings.load(null), "http://localhost:8080")
    }

    @After fun stop() {
        BridgeNet.interceptor = null
        stub.stop(0)
    }

    @Test fun theChoiceIsKeptUnderTheAccountAndSentWithItsCallsOnly() {
        assertEquals("ytm", Accounts.add(env.store, "ytm", another = false, name = null))
        assertEquals("ytm@2", Accounts.add(env.store, "ytm", another = true, name = "Label"))
        val one = Providers.byId("ytm") as YtmServerProvider
        val two = Providers.byId("ytm@2") as YtmServerProvider
        env.tokens.set("ytm", Tokens(accessToken = "SAPISID=aaa; __Secure-3PAPISID=aaa", userName = "Giulia Rossi", extra = mapOf(YtmServerProvider.UA to "Mozilla/5.0 One")))
        env.tokens.set("ytm@2", Tokens(accessToken = "SAPISID=bbb; __Secure-3PAPISID=bbb", userName = "Giulia Rossi"))
        assertNull(one.channel(env)); assertNull(two.channel(env))

        // The switcher, with the stored choice marked (none yet: the account's own).
        val channels = runBlocking { two.channels(env) }
        assertEquals(listOf("", "112233"), channels.map { it.id })
        assertEquals(listOf("Giulia Rossi", "Rossi Records"), channels.map { it.name })
        assertEquals(listOf(true, false), channels.map { it.selected })

        runBlocking { two.setChannel(env, "112233", "Rossi Records") }
        assertEquals("112233", two.channel(env))
        assertEquals("Rossi Records", two.channelName(env))
        assertEquals("112233", env.tokens.get("ytm@2")!!.extra[YtmServerProvider.CHANNEL])
        // The cookies and the other account are as they were; the account is now named as the brand.
        assertEquals("SAPISID=bbb; __Secure-3PAPISID=bbb", env.tokens.get("ytm@2")!!.accessToken)
        assertEquals("Rossi Records", two.accountName(env))
        assertNull(env.tokens.get("ytm")!!.extra[YtmServerProvider.CHANNEL])
        assertEquals("Mozilla/5.0 One", env.tokens.get("ytm")!!.extra[YtmServerProvider.UA])
        assertEquals(listOf(false, true), runBlocking { two.channels(env) }.map { it.selected })

        seen.clear()
        assertEquals(listOf("pl-own"), runBlocking { one.playlists(env) }.map { it.id })
        assertEquals(listOf("pl-112233"), runBlocking { two.playlists(env) }.map { it.id })
        val browse = seen.filter { it.path.endsWith("/browse") }
        assertEquals(2, browse.size)
        assertFalse(browse[0].body.contains("onBehalfOfUser"))
        assertNull(browse[0].headers["x-goog-pageid"])
        assertTrue(browse[1].body.contains(""""onBehalfOfUser":"112233""""))
        assertEquals("112233", browse[1].headers["x-goog-pageid"])
        assertEquals("0", browse[1].headers["x-goog-authuser"])

        // Back to the account's own channel: the extra goes, the cookies stay.
        runBlocking { two.setChannel(env, "", null) }
        assertNull(two.channel(env))
        assertNull(env.tokens.get("ytm@2")!!.extra[YtmServerProvider.CHANNEL])
        assertEquals("SAPISID=bbb; __Secure-3PAPISID=bbb", env.tokens.get("ytm@2")!!.accessToken)
        seen.clear()
        runBlocking { two.playlists(env) }
        assertFalse(seen.single { it.path.endsWith("/browse") }.body.contains("onBehalfOfUser"))
    }

    @Test fun aChoiceNeedsASignedInAccountAndGoesWithTheSignOut() {
        val p = YtmServerProvider()
        val e = runCatching { runBlocking { p.setChannel(env, "112233", "x") } }.exceptionOrNull()
        assertTrue(e is com.xlollx.songport.model.ProviderException)
        env.tokens.set("ytm", Tokens(accessToken = "SAPISID=aaa", userName = "Me"))
        runBlocking { p.setChannel(env, "112233", "Rossi Records") }
        assertEquals("112233", p.channel(env))
        p.disconnect(env)
        assertNull(p.channel(env))
        assertFalse(p.isConnected(env))
    }
}

package com.xlollx.songport.ytmbridge

import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * The channel a YouTube Music account acts as (issue #41): the switcher's `accounts_list` parsed
 * into the account's own channel and its brand accounts, and the chosen one sent as
 * `onBehalfOfUser` (and `X-Goog-PageId`) on signed-in calls only. Without a choice every call is
 * what it has always been: no such field, no such header.
 */
class YtmChannelsTest {
    private lateinit var server: MockWebServer
    private val calls: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    private val queue = ArrayDeque<MockResponse>()
    @Volatile private var channel: String? = null

    private val verification = object : YtmVerificationStore {
        override fun until() = 0L; override fun seen() = 0L; override fun level() = 0; override fun html(): String? = null
        override fun save(until: Long, seen: Long, level: Int, html: String) {}
        override fun clear() {}
    }

    private lateinit var client: YtmClient

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.requestUrl!!.encodedPath == "/") return MockResponse().setBody(
                    """<script>ytcfg.set({"VISITOR_DATA":"CgtWSVNJVE9SX0lE","INNERTUBE_CLIENT_VERSION":"1.20251020.01.00"});</script>""")
                calls += request
                return synchronized(queue) { queue.removeFirstOrNull() } ?: MockResponse().setResponseCode(404).setBody("not queued")
            }
        }
        server.start()
        val base = server.url("/")
        val host = object : YtmHost {
            override fun cookies() = "SAPISID=sapi-123; SID=sid; __Secure-3PAPISID=sapi-123"
            override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0"
            override val interceptor = Interceptor { chain ->
                val u = chain.request().url
                chain.proceed(chain.request().newBuilder().url(u.newBuilder().scheme("http").host(base.host).port(base.port).build()).build())
            }
            override val web: YtmWebTransport? = null
            override val verification = this@YtmChannelsTest.verification
            override val signInAgain = "sign in again"
            override val blockedHint: String? = null
            override val channel: String? get() = this@YtmChannelsTest.channel
        }
        client = YtmClient(host)
    }

    @After fun stop() { server.shutdown() }

    private fun answer(body: String) { synchronized(queue) { queue.add(MockResponse().setBody(body)) } }
    private fun RecordedRequest.json() = parseJson(body.clone().readUtf8())

    /** The switcher as the player receives it: the account itself, then a brand account with its page id. */
    private val accountsList = """{"actions":[{"updateAccountSectionAction":{"contents":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
        {"accountItem":{"accountName":{"runs":[{"text":"Giulia Rossi"}]},"accountByline":{"runs":[{"text":"giulia@example.com"}]},"isSelected":true,"hasChannel":true,
          "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{"hasChannel":true}},{"offlineCacheKeyToken":{"clientCacheKey":"abc"}}]}}}},
        {"accountItem":{"accountName":{"runs":[{"text":"Rossi Records"}]},"accountByline":{"runs":[{"text":"Brand Account"}]},"isSelected":false,"hasChannel":true,
          "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{"hasChannel":true}},{"pageIdToken":{"pageId":"112233445566778899001"}},{"offlineCacheKeyToken":{"clientCacheKey":"def"}}]}}}}
    ]}}]}}]}}]}"""

    @Test fun theSwitcherIsParsedIntoTheOwnChannelAndTheBrandAccounts() {
        answer(accountsList)
        val channels = client.channels()
        assertEquals(listOf("", "112233445566778899001"), channels.map { it.id })
        assertEquals(listOf("Giulia Rossi", "Rossi Records"), channels.map { it.name })
        assertEquals(listOf("giulia@example.com", "Brand Account"), channels.map { it.byline })
        assertEquals(listOf(true, false), channels.map { it.selected })
        val req = calls.single()
        assertTrue(req.path!!.startsWith("/youtubei/v1/account/accounts_list?"))
        val body = req.json() as JsonObject
        assertEquals("true", body["accountReadMask"]["returnBrandAccounts"].toString())
        assertTrue(req.getHeader("Authorization")!!.startsWith("SAPISIDHASH "))
    }

    @Test fun aSwitcherWithoutBrandAccountsListsJustTheAccount() {
        answer("""{"actions":[{"updateAccountSectionAction":{"contents":[{"accountItem":{"accountName":{"simpleText":"Only Me"},"isSelected":true}}]}}]}""")
        val channels = client.channels()
        assertEquals(1, channels.size)
        assertEquals("", channels[0].id)
        assertEquals("Only Me", channels[0].name)
        assertNull(channels[0].byline)
    }

    @Test fun withoutAChoiceTheCallsAreWhatTheyHaveAlwaysBeen() {
        channel = null
        answer("""{"contents":{}}""")
        client.playlists()
        val req = calls.single()
        val user = (req.json() as JsonObject)["context"]["user"] as JsonObject
        assertEquals(setOf("lockedSafetyMode"), user.keys)
        assertNull(req.getHeader("X-Goog-PageId"))
        assertEquals("0", req.getHeader("X-Goog-AuthUser"))
        // A blank choice is no choice.
        channel = "  "
        answer("""{"contents":{}}""")
        client.playlists()
        assertNull(calls[1].getHeader("X-Goog-PageId"))
        assertFalse(calls[1].body.clone().readUtf8().contains("onBehalfOfUser"))
    }

    @Test fun theChosenBrandAccountGoesWithEverySignedInCallAndNeverWithAnAnonymousOne() {
        channel = "112233445566778899001"
        answer("""{"contents":{}}""")
        client.playlists()
        val req = calls.single()
        val user = (req.json() as JsonObject)["context"]["user"] as JsonObject
        assertEquals("112233445566778899001", user["onBehalfOfUser"].str)
        assertEquals("false", user["lockedSafetyMode"].toString())
        assertEquals("112233445566778899001", req.getHeader("X-Goog-PageId"))
        assertEquals("0", req.getHeader("X-Goog-AuthUser"))
        // A catalogue search is a visitor's: no account, no channel.
        answer("""{"contents":{}}""")
        answer("""{"contents":{}}""")
        client.search("anything")
        val anonymous = calls.drop(1).first { it.getHeader("Authorization") == null }
        assertNull(anonymous.getHeader("X-Goog-PageId"))
        assertFalse(anonymous.body.clone().readUtf8().contains("onBehalfOfUser"))
    }
}

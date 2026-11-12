package com.xlollx.songport.contract

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Base of the contract tests: every request any connector makes goes to a local [MockWebServer],
 * whatever host it was addressed to. Nothing reaches a real music service: [Http.rewrite] sends
 * every host to 127.0.0.1 (it is set before each test and covers the one HTTP client all the
 * engine's connectors share), and the host the connector meant is kept in [ORIGINAL_HOST] so the
 * tests can check it.
 *
 * The answers are queued in the order the connector is expected to ask, so a test also pins the
 * sequence of calls; [next] takes the requests back one by one for the shape assertions.
 */
abstract class ContractTest {
    protected lateinit var server: MockWebServer
    protected lateinit var env: ContractEnv
    /** Every wait [Http.send] asked for (ms), recorded instead of slept. */
    protected val pauses: MutableList<Long> = Collections.synchronizedList(ArrayList())

    @Before fun startMock() {
        server = MockWebServer()
        // An answer the test did not queue is a 404 at once, never a wait.
        (server.dispatcher as okhttp3.mockwebserver.QueueDispatcher).setFailFast(true)
        server.start()
        val base = server.url("/")
        Http.rewrite = { req ->
            val u = req.url
            req.newBuilder()
                .url(u.newBuilder().scheme("http").host(base.host).port(base.port).build())
                .header(ORIGINAL_HOST, u.host)
                .header(ORIGINAL_SCHEME, u.scheme)
                .build()
        }
        Http.pause = { pauses += it }
        env = ContractEnv()
        FileStore.install(env.store)
    }

    @After fun stopMock() {
        Http.rewrite = null
        Http.pause = { delay(it) }
        BuildDefaults.values = emptyMap()
        server.shutdown()
    }

    protected fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)
            .apply { headers.forEach { (k, v) -> setHeader(k, v) } })
    }

    protected fun text(body: String, code: Int, contentType: String = "text/html", vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", contentType).setBody(body)
            .apply { headers.forEach { (k, v) -> setHeader(k, v) } })
    }

    /** The next request the connector made; fails when it made none. */
    protected fun next(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: throw AssertionError("expected one more request")

    /** No request beyond the ones already taken. */
    protected fun noMoreRequests() {
        val extra = server.takeRequest(100, TimeUnit.MILLISECONDS)
        assertEquals("unexpected request ${extra?.method} ${extra?.path}", null, extra)
    }

    protected fun RecordedRequest.host(): String? = getHeader(ORIGINAL_HOST)
    protected fun RecordedRequest.query(name: String): String? = requestUrl!!.queryParameter(name)
    protected fun RecordedRequest.queryAll(name: String): List<String?> = requestUrl!!.queryParameterValues(name)
    protected fun RecordedRequest.pathOnly(): String = requestUrl!!.encodedPath
    protected fun RecordedRequest.bodyText(): String = body.clone().readUtf8()
    protected fun RecordedRequest.jsonBody(): JsonElement = parseJson(bodyText())
    protected fun RecordedRequest.form(): Map<String, String> = bodyText().split('&').filter { it.isNotEmpty() }.associate {
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    /** Asserts method, original host and path (without the query) of [r]. */
    protected fun assertCall(r: RecordedRequest, method: String, host: String, path: String) {
        assertEquals("method of ${r.path}", method, r.method)
        assertEquals("host of ${r.path}", host, r.host())
        assertEquals("https", r.getHeader(ORIGINAL_SCHEME))
        assertEquals(path, r.pathOnly())
    }

    companion object {
        const val ORIGINAL_HOST = "X-Test-Original-Host"
        const val ORIGINAL_SCHEME = "X-Test-Original-Scheme"
        /** A name with accents, CJK, an emoji with a modifier and a zero-width joiner, and quotes. */
        const val UNICODE = "Ñandú – 日本語の歌 👩🏽‍🎤 \"live\" & <b>ß</b>"
        val LONG: String = "Ω".repeat(150) + "🎧".repeat(60)
    }
}

/** An [Env] in memory: tokens in a map, texts as "name(args)", a temporary store. */
class ContractEnv : Env {
    override val dataDir: File = Files.createTempDirectory("songport-contract").toFile().apply { deleteOnExit() }
    override val store = FileStore(dataDir)
    private val vault = HashMap<String, Tokens>()
    override val tokens = object : TokenVault {
        override fun get(providerId: String) = vault[providerId]
        override fun set(providerId: String, tokens: Tokens) { vault[providerId] = tokens }
        override fun clear(providerId: String) { vault.remove(providerId) }
    }
    override val redirectUri = "songport://callback"
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    override fun text(name: String, vararg args: Any) = name + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")
    override fun log(tag: String, message: String) { logs += "[$tag] $message" }
    override fun startAuth(provider: MusicProvider, mode: AuthMode) = Unit
    private val kv = HashMap<String, KeyValues>()
    override fun keyValues(name: String): KeyValues = kv.getOrPut(name) {
        val m = HashMap<String, String>()
        object : KeyValues {
            override fun get(key: String) = m[key]
            override fun set(key: String, value: String) { m[key] = value }
            override val size get() = m.size
            override fun clear() = m.clear()
        }
    }
    /** What a cover becomes once shrunk; none by default (no images in most tests). */
    var shrink: (ByteArray) -> ByteArray? = { null }
    override fun squareJpeg(raw: ByteArray, minSide: Int, maxSide: Int, maxBytes: Int): ByteArray? = shrink(raw)
    /** The server this "device" coordinates with; none by default. */
    var coordinator: com.xlollx.songport.sync.TargetCoordinator? = null
    override val targetCoordinator get() = coordinator
}

/** JSON string literal for [s], escaped. */
fun q(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

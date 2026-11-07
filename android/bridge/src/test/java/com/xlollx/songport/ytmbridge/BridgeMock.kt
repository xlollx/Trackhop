package com.xlollx.songport.ytmbridge

import okhttp3.Interceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.ArrayDeque
import java.util.Collections

/**
 * The local stand-in for every service the web clients talk to. [install] runs before any client
 * of this module is built (each test class calls it from its companion's init): the interceptor it
 * puts in [BridgeNet] sends every request, whatever its host, to the current [MockWebServer], so
 * nothing reaches Spotify, Amazon or GitHub. The host the client meant travels in [ORIGINAL_HOST].
 *
 * Incidental reads (the player page, the hash registry, the profile) are answered by [fixed];
 * everything else is taken from [queue] in order and recorded in [calls].
 */
object BridgeMock {
    const val ORIGINAL_HOST = "X-Test-Original-Host"
    @Volatile private var server: MockWebServer? = null
    val queue = ArrayDeque<MockResponse>()
    val calls: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    val all: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    @Volatile var fixed: (RecordedRequest) -> MockResponse? = { null }

    fun install() {
        // No browser engine in a JVM test: the web clients' calls go by plain HTTP, to the mock server.
        WebEngine.enabled = false
        if (BridgeNet.interceptor != null) return
        BridgeNet.interceptor = Interceptor { chain ->
            val s = server ?: throw java.io.IOException("no mock server: requests never leave the test")
            val base = s.url("/")
            val u = chain.request().url
            chain.proceed(chain.request().newBuilder()
                .url(u.newBuilder().scheme("http").host(base.host).port(base.port).build())
                .header(ORIGINAL_HOST, u.host).build())
        }
    }

    fun start(): MockWebServer {
        install()
        synchronized(queue) { queue.clear() }
        calls.clear(); all.clear()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                all += request
                fixed(request)?.let { return it }
                calls += request
                return synchronized(queue) { queue.pollFirst() } ?: MockResponse().setResponseCode(404).setBody("not queued")
            }
        }
        s.start()
        server = s
        return s
    }

    fun stop() { server?.shutdown(); server = null }

    fun answer(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        synchronized(queue) { queue.add(MockResponse().setResponseCode(code).setBody(body).apply { headers.forEach { (k, v) -> setHeader(k, v) } }) }
    }
}

package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * A web connector's calls made by a real browser engine: a hidden WebView on the service's own site
 * runs each request as a `fetch()`, so it carries Chrome's TLS and HTTP fingerprint, the browser's
 * own `Origin`, `Sec-Fetch-*` and client-hint headers, and the account's cookies, exactly like the
 * service's web player. Bot protections that answer plain HTTP clients (Anghami's 406, #53) let it
 * through. [YtmWeb] did this first for YouTube Music; this is the same idea for any service.
 *
 * One engine per service and account ([Slots]): created lazily on the main thread in that account's
 * profile (the cookie jar its sign-in filled), parked on a small same-origin page ([Config.startUrl]),
 * torn down after [Config.idleMs] without calls. [warm] loads the player's full page once (its
 * scripts may set the cookies a bot check wants) for a call that was refused. Callers block: never
 * on the main thread. Only [Config.hosts] are ever fetched.
 */
class WebEngine(val config: Config) {

    /**
     * @param service the [Slots] profile name of the service (as its sign-in uses), and the log tag
     * @param startUrl a light page of the site the calls are made from (their origin)
     * @param fullUrl the player's page, loaded by [warm]
     * @param hosts the hosts (and their subdomains) a call may go to
     * @param desktop the desktop identity and wide page, as the service's sign-in has
     */
    class Config(
        val service: String,
        val slot: String,
        val startUrl: String,
        val fullUrl: String,
        val hosts: List<String>,
        val desktop: Boolean = true,
        val idleMs: Long = 3 * 60_000L,
    )

    class Response(val code: Int, val body: String, val url: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var web: WebView? = null
    @Volatile private var ready: CountDownLatch? = null
    @Volatile private var broken = false
    @Volatile var warmed = false
        private set
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableFuture<Response>>()
    private val idle = Runnable { if (pending.isEmpty()) teardown("idle") }

    /**
     * Whether calls go through the engine at all (not in JVM tests). A start that failed fails that
     * call only: the next one starts the engine again, so one bad start does not leave the service on
     * plain HTTP for the rest of the process.
     */
    fun usable(): Boolean = enabled

    /**
     * Sends [method] [url] from the site's page. [body] goes as is with [contentType]; with
     * [credentials] the account's cookies go along. Fails with a [BridgeException] on a timeout, a
     * network error or a host outside [Config.hosts]; an HTTP error is an answer, with its code.
     */
    fun fetch(
        ctx: Context, method: String, url: String, headers: Map<String, String> = emptyMap(),
        body: String? = null, contentType: String? = null, credentials: Boolean = true, timeoutMs: Long = 40_000,
    ): Response {
        if (!Scripts.allowed(url, config.hosts)) throw BridgeException("${config.service}: ${Scripts.hostOf(url)} is not a host of this service")
        ensureStarted(ctx.applicationContext, config.startUrl)
        val id = ids.getAndIncrement().toString()
        val future = CompletableFuture<Response>()
        pending[id] = future
        val all = if (contentType != null) headers + ("Content-Type" to contentType) else headers
        val script = Scripts.fetch(IFACE, id, method, url, all, body, credentials)
        main.removeCallbacks(idle)
        main.post { web?.evaluateJavascript(script, null) ?: future.completeExceptionally(IllegalStateException("no engine")) }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw BridgeException("${config.service} did not answer within ${timeoutMs / 1000}s (browser engine)")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw BridgeException("${config.service} request failed in the browser engine: ${e.cause?.message ?: e.message}")
        } finally {
            pending.remove(id)
            main.removeCallbacks(idle)
            main.postDelayed(idle, config.idleMs)
        }
    }

    /**
     * Loads the player's full page once ([Config.fullUrl]) and waits a moment for its scripts: a bot
     * check may want what they leave behind. Later calls are made from that page.
     */
    fun warm(ctx: Context) {
        if (warmed) return
        teardown("warming")
        warmed = true
        ensureStarted(ctx.applicationContext, config.fullUrl)
        Thread.sleep(WARM_SETTLE_MS)
        BridgeNet.log(config.service, "browser engine: the player's page loaded for its calls")
    }

    /** Drops the engine, for instance after a sign-out: the next call starts a fresh one. */
    fun reset() { warmed = false; teardown("reset") }

    private val sink = object {
        @JavascriptInterface
        fun done(id: String, status: Int, text: String, url: String) { pending[id]?.complete(Response(status, text, url)) }
        @JavascriptInterface
        fun fail(id: String, message: String) { pending[id]?.completeExceptionally(RuntimeException(message)) }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureStarted(app: Context, page: String) {
        if (web != null) { ready?.await(INIT_TIMEOUT_S, TimeUnit.SECONDS); if (broken) throw notStarted(); return }
        synchronized(this) {
            if (web != null) { ready?.await(INIT_TIMEOUT_S, TimeUnit.SECONDS); if (broken) throw notStarted(); return }
            broken = false
            val latch = CountDownLatch(1)
            ready = latch
            main.post {
                try {
                    val w = WebView(app)
                    // The account's own profile (its sign-in's cookie jar): set before anything loads.
                    Slots.apply(w, config.service, config.slot)
                    w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                    if (config.desktop) { BridgeUa.apply(app, w); DesktopPage.apply(w) }
                    w.addJavascriptInterface(sink, IFACE)
                    w.webViewClient = object : SafeWebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { latch.countDown() }
                        override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                            if (req.isForMainFrame) { broken = true; latch.countDown() }
                        }
                    }
                    web = w
                    w.loadUrl(page)
                } catch (e: Exception) {
                    broken = true
                    latch.countDown()
                }
            }
            if (!latch.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) broken = true
            if (broken) { teardown("not started"); broken = true; throw notStarted() }
        }
    }

    private fun notStarted() = BridgeException("the browser engine for ${config.service} could not be started")

    private fun teardown(why: String) {
        // The next engine starts from the light page again: the player's page is loaded anew when asked.
        warmed = false
        val w = web ?: return
        web = null; ready = null
        main.post { runCatching { w.destroy() } }
        if (why == "idle") BridgeNet.log(config.service, "browser engine: closed after a while without calls")
    }

    /** The JavaScript and checks, apart from the WebView so they can be tested on the JVM. */
    object Scripts {
        fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: "?"

        fun allowed(url: String, hosts: List<String>): Boolean {
            if (!url.startsWith("https://")) return false
            val host = hostOf(url)
            return hosts.any { host == it || host.endsWith(".$it") }
        }

        fun fetch(iface: String, id: String, method: String, url: String, headers: Map<String, String>, body: String?, credentials: Boolean): String {
            val h = headers.entries.joinToString(",", "{", "}") { (k, v) -> "${js(k)}: ${js(v)}" }
            val b = if (body != null && method != "GET" && method != "HEAD") ", body: ${js(body)}" else ""
            return """
                (function(){
                  fetch(${js(url)}, {method: ${js(method)}, credentials: ${if (credentials) "'include'" else "'omit'"}, headers: $h$b})
                    .then(function(r){ return r.text().then(function(t){ $iface.done(${js(id)}, r.status, t, String(r.url || '')); }); })
                    .catch(function(e){ $iface.fail(${js(id)}, String(e)); });
                })();
            """.trimIndent()
        }

        private fun js(s: String): String = JsonPrimitive(s).toString()
    }

    companion object {
        private const val IFACE = "TrackhopEngine"
        private const val INIT_TIMEOUT_S = 25L
        private const val WARM_SETTLE_MS = 3_000L

        private val engines = ConcurrentHashMap<String, WebEngine>()

        /** Off in JVM tests, where there is no browser engine: the clients use plain HTTP (a mock server). */
        @Volatile var enabled: Boolean = true

        /** The engine of [config]'s service and account, made once and kept. */
        fun of(config: Config): WebEngine = engines.getOrPut("${config.service}#${Slots.valid(config.slot)}") { WebEngine(config) }

        /** Drops [service]'s engine for [slot] (a sign-out, a start over). */
        fun reset(service: String, slot: String) { engines["$service#${Slots.valid(slot)}"]?.reset() }
    }
}

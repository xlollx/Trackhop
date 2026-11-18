package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Renews a web connector's session without the person, when the service refuses the kept one: the
 * service's web player keeps its own session on its site (the cookies and storage the sign-in
 * left), so its page is opened in a hidden WebView of the account's profile and the session is read
 * from it the way the sign-in reads it (from its calls, its storage or its cookies, see
 * [HiddenSessionPage.Spec]). A session other than the refused one is kept and the caller makes its
 * call again ([WebHost.renewing]).
 *
 * Nothing is ever cleared here: the cookies and the site's data stay as they are, whatever happens
 * (only the person's "Start over" or a sign-out clears them). When the page gives nothing new (the
 * site's own session is gone, a page that would not load) the call keeps its "sign in again" error
 * and no page is opened again for that account for [PAUSE_MS], so a sync's many calls do not each
 * open one. One log line each time, never a token.
 *
 * @param service the log tag
 * @param tokenKey the value that is the session: one other than the refused one is a renewal
 * @param alsoNew values whose change alone is a renewal too (Qobuz's app id)
 * @param labels how the log names each value ("token", "app id")
 * @param stored what is kept for the account now (default: [WebSession.get] of each label's key)
 * @param keep keeps what the page gave (default: [WebSession.put] of each value)
 * @param spec the page of an account's slot, and how the session is read from it
 */
class SessionRenewal(
    private val service: String,
    private val session: (String) -> WebSession,
    private val tokenKey: String,
    private val alsoNew: List<String> = emptyList(),
    private val labels: Map<String, String> = mapOf(tokenKey to "token"),
    private val stored: (Context, WebSession) -> Map<String, String?> = { ctx, s -> labels.keys.associateWith { s.get(ctx, it) } },
    private val keep: (Context, WebSession, Map<String, String>) -> Unit = { ctx, s, v -> v.forEach { (k, x) -> s.put(ctx, k, x) } },
    private val spec: (String) -> HiddenSessionPage.Spec,
) {
    /** The hidden page, replaced in tests (a JVM test has no WebView): context, slot, and whether a session read is new. */
    @Volatile var hiddenPage: (Context, String, (Map<String, String>) -> Boolean) -> HiddenSessionPage.Result =
        { ctx, slot, isNew -> HiddenSessionPage.open(ctx, spec(slot), isNew) }

    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Forgets the pause after a failed renewal (a sign-in, a sign-out, tests). */
    fun forget(slot: String? = null) { if (slot == null) failedAt.clear() else failedAt.remove(slot) }

    /**
     * Renews the session of [slot] the service refused with [refused]. True when there is a new one
     * to make the call again with: another call may have renewed it a moment ago, then it is used as
     * it is. False when the account is not signed in, a page renewed nothing a moment ago, or this
     * one gives nothing new.
     */
    @Synchronized
    fun renew(ctx: Context, slot: String, refused: String?): Boolean {
        val app = ctx.applicationContext
        val sess = session(slot)
        val before = stored(app, sess)
        val current = before[tokenKey] ?: return false
        if (refused != null && current != refused) return true
        val now = System.currentTimeMillis()
        failedAt[slot]?.let { if (now - it in 0 until PAUSE_MS) return false }
        val isNew: (Map<String, String>) -> Boolean = { v ->
            v[tokenKey] != null && (v[tokenKey] != current || alsoNew.any { k -> v[k] != null && v[k] != before[k] })
        }
        val page = runCatching { hiddenPage(app, slot, isNew) }.getOrElse { HiddenSessionPage.Result(null, it.message ?: it.javaClass.simpleName) }
        val found = page.values
        if (found == null || !isNew(found)) {
            failedAt[slot] = now
            BridgeNet.log(service, "access not renewed${who(slot)}: ${page.problem ?: "the page gave no other session"}")
            return false
        }
        failedAt.remove(slot)
        keep(app, sess, found)
        val changed = labels.filterKeys { k -> found[k] != null && found[k] != before[k] }.values
        BridgeNet.log(service, "access renewed${who(slot)} in a hidden page (" +
            changed.joinToString(", ") { "new $it" }.ifEmpty { "new session" } + " in ${(System.currentTimeMillis() - now) / 1000} s)")
        return true
    }

    private fun who(slot: String) = if (slot.isEmpty()) "" else " (account $slot)"

    companion object {
        /** After a page that renewed nothing, how long no page is opened again for that account. */
        const val PAUSE_MS = 15 * 60_000L
    }
}

/**
 * A web connector's page in a hidden WebView of the account's profile (the cookie jar its sign-in
 * filled), as the sign-in shows it, every request only observed and left as the page made it: what
 * session its calls, its storage or its cookies show. The first one the caller calls new is taken.
 */
object HiddenSessionPage {
    /** What a page gave: the session's values, or why not. */
    class Result(val values: Map<String, String>?, val problem: String?)

    /**
     * @param profile the [Slots] profile name of the service (as its sign-in uses)
     * @param desktop the desktop user agent ([BridgeUa]); [widePage]: a page at least 1024 px wide ([DesktopPage])
     * @param fromRequest the session a request of the page carries, if any
     * @param storageScript a script answering the page's stored session (a JSON literal), read every second
     * @param fromStorage the session in what [storageScript] answered
     * @param cookieSite the address whose cookies are read every second
     * @param fromCookies the session in those cookies (the `Cookie` header form)
     */
    class Spec(
        val profile: String,
        val slot: String,
        val url: String,
        val desktop: Boolean = true,
        val widePage: Boolean = false,
        val fromRequest: ((WebResourceRequest) -> Map<String, String>?)? = null,
        val storageScript: String? = null,
        val fromStorage: ((String?) -> Map<String, String>?)? = null,
        val cookieSite: String? = null,
        val fromCookies: ((String?) -> Map<String, String>?)? = null,
    )

    private const val TIMEOUT_S = 35L
    /** After the page has loaded, how long a new session is waited for before giving up. */
    private const val SETTLE_MS = 10_000L
    private const val VIEW_W = 1280
    private const val VIEW_H = 900

    @SuppressLint("SetJavaScriptEnabled")
    fun open(app: Context, spec: Spec, isNew: (Map<String, String>) -> Boolean): Result {
        if (Looper.myLooper() == Looper.getMainLooper()) return Result(null, "asked on the main thread")
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val seen = AtomicReference<Map<String, String>?>(null)
        val problem = AtomicReference<String?>(null)
        val loadedAt = AtomicLong(0)
        val over = AtomicBoolean(false)
        var web: WebView? = null

        fun offer(v: Map<String, String>?) {
            if (v != null && isNew(v) && seen.compareAndSet(null, v)) latch.countDown()
        }
        fun watch(request: WebResourceRequest) { spec.fromRequest?.let { f -> offer(runCatching { f(request) }.getOrNull()) } }
        val workers = spec.fromRequest != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)

        main.post {
            try {
                val w = WebView(app)
                web = w
                // The account's own profile: set before anything loads.
                Slots.apply(w, spec.profile, spec.slot)
                Slots.cookies(spec.profile, spec.slot).setAcceptCookie(true)
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                // The sign-in's identity: its desktop user agent, and a desktop-wide page where it has one.
                if (spec.desktop) BridgeUa.apply(app, w)
                if (spec.widePage) DesktopPage.apply(w)
                // A page nobody sees still needs a size to lay itself out and start.
                w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, VIEW_W, VIEW_H)
                if (workers) runCatching {
                    ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? { watch(request); return null }
                    })
                }
                w.webViewClient = object : SafeWebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? { watch(request); return null }
                    override fun onPageFinished(view: WebView, url: String) { loadedAt.compareAndSet(0, System.currentTimeMillis()) }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (req.isForMainFrame) {
                            problem.compareAndSet(null, YtmRenewal.didNotLoad(err.errorCode, err.description?.toString(), req.url.host ?: Scripts.hostOf(spec.url)))
                            latch.countDown()
                        }
                    }
                }
                w.loadUrl(spec.url)
                // The storage and the cookies have no request to show them: looked at every second.
                main.postDelayed(object : Runnable {
                    override fun run() {
                        if (over.get()) return
                        spec.fromCookies?.let { f -> offer(runCatching { f(Slots.cookies(spec.profile, spec.slot).getCookie(spec.cookieSite ?: spec.url)) }.getOrNull()) }
                        val script = spec.storageScript
                        val read = spec.fromStorage
                        if (script != null && read != null && loadedAt.get() > 0) web?.evaluateJavascript(script) { raw ->
                            offer(runCatching { read(runCatching { parseJson(raw ?: "").str }.getOrNull()) }.getOrNull())
                        }
                        val at = loadedAt.get()
                        if (at > 0 && System.currentTimeMillis() - at > SETTLE_MS) {
                            problem.compareAndSet(null, "the page loaded but gave no other session")
                            latch.countDown(); return
                        }
                        main.postDelayed(this, 1_000)
                    }
                }, 1_000)
            } catch (e: Exception) {
                problem.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                latch.countDown()
            }
        }
        val inTime = latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        over.set(true)
        val closed = CountDownLatch(1)
        main.post {
            runCatching { web?.stopLoading(); web?.destroy() }
            if (workers) runCatching {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
                })
            }
            closed.countDown()
        }
        closed.await(10, TimeUnit.SECONDS)
        seen.get()?.let { return Result(it, null) }
        return Result(null, problem.get() ?: if (!inTime) "no session within $TIMEOUT_S s" else "the page gave no other session")
    }

    private object Scripts { fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "?" }
}

package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
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
 * Renews the Qobuz session without the person, when Qobuz refuses the kept token (401): the web
 * player keeps its own session on Qobuz's site (its cookies and storage, the ones the sign-in left),
 * so the Bridge opens the player in a hidden WebView of the account's profile and takes the session
 * its API calls carry, as the sign-in does ([QobuzBridge.fromRequest]): the user token and the app
 * id. A token other than the refused one, or the same with another app id, is kept and the call is
 * made again ([QobuzWebClient]). Nothing is cleared: the cookies and the site's data stay as they
 * are, whatever happens (only the person's "Start over" or a sign-out clears them).
 *
 * When the page gives nothing new (the site's own session is gone, a page that would not load) the
 * call keeps its "sign in again" error and no page is opened again for [PAUSE_MS]: a sync's
 * searches do not each open one. One log line each time, never a token.
 */
object QobuzRenewal {
    /** What a hidden page gave: the token and app id of the player's calls, or why not. */
    class Page(val token: String?, val appId: String?, val problem: String?)

    /** The hidden page, replaced in tests (a JVM test has no WebView): context, slot, refused token. */
    @Volatile var hiddenPage: (Context, String, String?) -> Page = { ctx, slot, old -> openHidden(ctx, slot, old) }

    /** After a page that renewed nothing, how long no page is opened again for that account. */
    const val PAUSE_MS = 15 * 60_000L
    private const val TIMEOUT_S = 35L
    /** After the page has loaded, how long a new session is waited for before giving up. */
    private const val SETTLE_MS = 10_000L
    private const val VIEW_W = 1280
    private const val VIEW_H = 900

    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Forgets the pause (a sign-in, a sign-out, tests). */
    fun forget(slot: String? = null) { if (slot == null) failedAt.clear() else failedAt.remove(slot) }

    /**
     * Renews the session of [slot] that Qobuz refused with [refused]. True when there is a new one
     * to make the call again with: another call may have renewed it a moment ago, then it is used as
     * it is. False when the account is not signed in, a page renewed nothing a moment ago, or this one
     * gives nothing new.
     */
    @Synchronized
    fun renew(ctx: Context, slot: String, refused: String?): Boolean {
        val app = ctx.applicationContext
        val sess = QobuzBridge.session(slot)
        val current = sess.get(app, QobuzWebClient.TOKEN) ?: return false
        if (refused != null && current != refused) return true
        val now = System.currentTimeMillis()
        failedAt[slot]?.let { if (now - it in 0 until PAUSE_MS) return false }
        val oldApp = sess.get(app, QobuzWebClient.APP_ID)
        val page = runCatching { hiddenPage(app, slot, current) }.getOrElse { Page(null, null, it.message ?: it.javaClass.simpleName) }
        val token = page.token
        val appChanged = page.appId != null && page.appId != oldApp
        if (token == null || (token == current && !appChanged)) {
            failedAt[slot] = now
            BridgeNet.log("qobuz", "access not renewed${who(slot)}: ${page.problem ?: "the player's page gave no other session"}")
            return false
        }
        failedAt.remove(slot)
        sess.put(app, QobuzWebClient.TOKEN, token)
        page.appId?.let { sess.put(app, QobuzWebClient.APP_ID, it) }
        BridgeNet.log("qobuz", "access renewed${who(slot)} in a hidden page (" +
            listOfNotNull("new token".takeIf { token != current }, "new app id".takeIf { appChanged }).joinToString(", ") +
            " in ${(System.currentTimeMillis() - now) / 1000} s)")
        return true
    }

    private fun who(slot: String) = if (slot.isEmpty()) "" else " (account $slot)"

    /**
     * The player in a hidden, desktop-sized WebView in the account's profile, as the sign-in shows it;
     * every request only observed. The first session its API calls carry that is not [old] (or that
     * names an app id) is taken.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun openHidden(app: Context, slot: String, old: String?): Page {
        if (Looper.myLooper() == Looper.getMainLooper()) return Page(null, null, "asked on the main thread")
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val seen = AtomicReference<Pair<String, String?>?>(null)
        val sameSeen = AtomicReference<String?>(null)
        val problem = AtomicReference<String?>(null)
        val loadedAt = AtomicLong(0)
        val over = AtomicBoolean(false)
        var web: WebView? = null

        fun watch(request: WebResourceRequest) {
            val (token, appId) = QobuzBridge.fromRequest(request.url.toString(), request.requestHeaders) ?: return
            // The old token with the app id it goes with: kept aside, a new app id alone is a renewal too.
            if (token == old) { appId?.let { sameSeen.compareAndSet(null, it) }; return }
            if (seen.compareAndSet(null, token to appId)) latch.countDown()
        }
        val workers = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)

        main.post {
            try {
                val w = WebView(app)
                web = w
                // The account's own profile (the cookie jar its sign-in filled): set before anything loads.
                Slots.apply(w, "qobuz", slot)
                (if (slot.isEmpty()) CookieManager.getInstance() else Slots.cookies("qobuz", slot)).setAcceptCookie(true)
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                // As the sign-in: the desktop identity and a page at least 1024 px wide (the player's minimum).
                BridgeUa.apply(app, w)
                DesktopPage.apply(w)
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
                            problem.compareAndSet(null, YtmRenewal.didNotLoad(err.errorCode, err.description?.toString(), req.url.host ?: "play.qobuz.com"))
                            latch.countDown()
                        }
                    }
                }
                w.loadUrl(QobuzWebClient.SITE + "/")
                main.postDelayed(object : Runnable {
                    override fun run() {
                        if (over.get()) return
                        val at = loadedAt.get()
                        if (at > 0 && System.currentTimeMillis() - at > SETTLE_MS) {
                            problem.compareAndSet(null, "the player's page loaded but gave no other session")
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
        seen.get()?.let { (token, appId) -> return Page(token, appId, null) }
        // Only the old token, but with an app id: the caller tells whether that app id is new.
        sameSeen.get()?.let { return Page(old, it, null) }
        return Page(null, null, problem.get() ?: if (!inTime) "no session within $TIMEOUT_S s" else "the player's page gave no other session")
    }
}

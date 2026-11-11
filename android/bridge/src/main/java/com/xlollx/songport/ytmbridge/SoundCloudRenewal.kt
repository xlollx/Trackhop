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
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Renews the SoundCloud session without the person: the token the sign-in kept is the mobile site's
 * `oauth_token`, which lasts about an hour, like the site's own access tokens. The site renews it
 * by itself when it is opened, so the Bridge opens it: SoundCloud's mobile site in a hidden WebView,
 * with the WebView's cookie jar (the one the sign-in left), and takes the new token the way the
 * sign-in does ([SoundCloudBridge.pick]), from the Authorization header of the page's own API
 * requests or from the `oauth_token` cookie.
 *
 * The page runs as the sign-in ran (see [WebLoginActivity.identity]): the WebView's own user agent,
 * without the app header, no script injected, every request only observed and left as the page
 * made it. Like [AmazonBridge]'s hidden player: a WebView made on the main thread, a latch, a timeout.
 *
 * When a renewal fails (no new token in time, DataDome's puzzle or block, a page that would not
 * load) the caller keeps the "sign in again" error; the log says why. Tokens are never logged.
 *
 * A page that loads and renews nothing, for a token surely over, may mean the site's own session
 * is gone (a few idle days are enough). Once is not enough to tell (a slow page, a bad moment): the
 * page is not reopened for 15 minutes, and a second such page an hour or more after the first
 * marks the session expired ([expiredAt]): no hidden page is opened again until the next sign-in. Trackhop renews the session in the background twice a day ([keepAlive])
 * so that, in use or not, the site's session never lapses.
 */
object SoundCloudRenewal {
    /** What a hidden page gave: a token other than the old one and the jar as it was then, or why not. */
    class Page(val found: SoundCloudBridge.Found?, val cookies: String?, val problem: String?)

    /** The hidden page, replaced in tests (a JVM test has no WebView). [old] is the token to replace. */
    @Volatile var hiddenPage: (Context, String?) -> Page = { ctx, old -> openHidden(ctx, old) }

    /** A token older than this is renewed before the next call, where its age is known. */
    const val RENEW_AFTER_MS = 50 * 60_000L
    /** One ahead-of-time attempt per this long: a page that renews nothing is not reopened on every call. */
    private const val PROACTIVE_EVERY_MS = 15 * 60_000L
    /** A token older than this is surely over: a page that renews nothing then means the site's session is gone. */
    private const val TOKEN_LIFE_MS = 65 * 60_000L
    private const val TIMEOUT_S = 40L
    /** After the page has loaded, how long a renewal is waited for before giving up early. */
    private const val SETTLE_MS = 12_000L
    private const val VIEW_W = 412
    private const val VIEW_H = 915

    /** What a page that loaded without renewing anything says: the site's own session is gone. */
    const val RENEWED_NOTHING = "the page loaded but renewed nothing"
    private const val EXPIRED_AT = "expiredAt"
    /** The pages that renewed nothing since the last renewal, and when the first and the last were. */
    private const val STRIKES = "renewedNothing"
    private const val FIRST_STRIKE_AT = "renewedNothingFirstAt"
    private const val LAST_STRIKE_AT = "renewedNothingLastAt"
    /** A second page that renews nothing at least this long after the first ends the session. */
    const val SECOND_STRIKE_AFTER_MS = 60 * 60_000L
    /** After a page that renewed nothing, how long no hidden page is opened again. */
    const val STRIKE_PAUSE_MS = 15 * 60_000L
    /** What a page with DataDome's check (and no renewal after it) says: not a proof the session is gone. */
    const val DATADOME_CHECKED = "the bot protection (DataDome) checked the hidden page, which then renewed nothing"

    private val lastProactive = AtomicLong(0)

    fun forget() { lastProactive.set(0) }

    /**
     * When the session was found gone for good (epoch ms, as text), or null while it can still be
     * renewed. Kept with the session: a sign-in ([SoundCloudBridge.rememberToken]) or a sign-out clears it.
     */
    fun expiredAt(ctx: Context): String? = SoundCloudBridge.session.get(ctx, EXPIRED_AT)

    /** The session is gone: said once, and no hidden page is opened for it again until the person signs in. */
    fun markExpired(ctx: Context, why: String) {
        if (expiredAt(ctx) != null) return
        SoundCloudBridge.session.put(ctx, EXPIRED_AT, System.currentTimeMillis().toString())
        BridgeNet.log("soundcloud", "the session has expired ($why): no more renewals until the next sign-in")
    }

    /** Forgets the pages that renewed nothing: a renewal or a sign-in. */
    fun clearStrikes(ctx: Context) {
        val s = SoundCloudBridge.session
        if (s.get(ctx, STRIKES) == null) return
        s.put(ctx, STRIKES, null); s.put(ctx, FIRST_STRIKE_AT, null); s.put(ctx, LAST_STRIKE_AT, null)
    }

    /**
     * A page renewed nothing for a token surely over: the first one is counted, and a later one an
     * hour or more after the first marks the session expired. [now] is passed in by tests.
     */
    internal fun strike(ctx: Context, now: Long = System.currentTimeMillis()) {
        val s = SoundCloudBridge.session
        val first = s.get(ctx, FIRST_STRIKE_AT)?.toLongOrNull()
        val count = (s.get(ctx, STRIKES)?.toIntOrNull() ?: 0) + 1
        s.put(ctx, STRIKES, count.toString())
        s.put(ctx, LAST_STRIKE_AT, now.toString())
        if (first == null) { s.put(ctx, FIRST_STRIKE_AT, now.toString()); return }
        if (now - first >= SECOND_STRIKE_AFTER_MS) markExpired(ctx, "$RENEWED_NOTHING, $count times over ${(now - first) / 60_000} minutes")
    }

    /** Whether a page renewed nothing less than 15 minutes ago: not reopened yet. */
    private fun pausedAfterStrike(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        SoundCloudBridge.session.get(ctx, LAST_STRIKE_AT)?.toLongOrNull()?.let { now - it in 0 until STRIKE_PAUSE_MS } ?: false

    /**
     * The background renewal (Trackhop's keep-alive, twice a day): one hidden page, whatever the
     * token's age, so the site's session and its cookies stay alive between uses. Nothing when not
     * signed in or already expired. One log line. True when the session was renewed.
     */
    fun keepAlive(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!SoundCloudBridge.session.isConnected(app) || expiredAt(app) != null) return false
        // A token renewed less than ~50 minutes ago: the session is alive, nothing to do.
        SoundCloudBridge.tokenAt(app)?.let { if (System.currentTimeMillis() - it < RENEW_AFTER_MS) return false }
        lastProactive.set(System.currentTimeMillis())
        return renew(app, SoundCloudBridge.token(app), "keep-alive", quiet = true, ahead = true)
    }

    /** Whether the kept token has surely run out; a token of unknown age (an older session) is not taken as over. */
    private fun tokenOver(ctx: Context): Boolean =
        SoundCloudBridge.tokenAt(ctx)?.let { System.currentTimeMillis() - it > TOKEN_LIFE_MS } ?: false

    /**
     * Before a call: when the token is older than about 50 minutes ([SoundCloudBridge.tokenAt]),
     * renewed ahead of time, at most once every 15 minutes. A failure here is only logged: the call
     * goes on with the token there is, and a refusal renews again.
     */
    fun renewIfOld(ctx: Context) {
        if (expiredAt(ctx) != null) return
        val at = SoundCloudBridge.tokenAt(ctx) ?: return
        val now = System.currentTimeMillis()
        val age = now - at
        if (age < RENEW_AFTER_MS) return
        val last = lastProactive.get()
        if (now - last < PROACTIVE_EVERY_MS || !lastProactive.compareAndSet(last, now)) return
        renew(ctx, SoundCloudBridge.token(ctx), "the token is ${age / 60_000} minutes old", ahead = true)
    }

    /**
     * Opens the hidden page and keeps the new token it gives. [refused] is the token a call was just
     * refused with: when the kept one is already another (renewed a moment ago by another call), that
     * one is used without opening anything. True when there is a new token to retry with.
     * A session already expired ([expiredAt]) opens nothing. [quiet]: one log line, the outcome.
     * [ahead]: before any refusal, so a page that renews a token still valid for a few minutes
     * proves nothing; only a token surely over, or refused, ends the session when nothing is renewed.
     */
    @Synchronized
    fun renew(ctx: Context, refused: String?, why: String, quiet: Boolean = false, ahead: Boolean = false): Boolean {
        val app = ctx.applicationContext
        val current = SoundCloudBridge.token(app)
        if (current != null && current != refused) return true
        if (expiredAt(app) != null) return false
        // A page renewed nothing a moment ago: not reopened on every call (the owner's log had six in two minutes).
        if (pausedAfterStrike(app)) return false
        val started = System.currentTimeMillis()
        if (!quiet) BridgeNet.log("soundcloud", "renewing the session in a hidden page ($why)")
        val page = runCatching { hiddenPage(app, current) }.getOrElse { Page(null, null, it.message ?: it.javaClass.simpleName) }
        val found = page.found
        if (found == null || found.token == current) {
            val problem = page.problem ?: "the page gave no new token"
            BridgeNet.log("soundcloud", "the session could not be renewed${if (quiet) " ($why)" else ""}: $problem")
            if (problem == RENEWED_NOTHING && (!ahead || tokenOver(app))) strike(app)
            return false
        }
        clearStrikes(app)
        SoundCloudBridge.rememberRenewedToken(app, found)
        page.cookies?.takeIf { it.isNotBlank() }?.let { SoundCloudBridge.session.save(app, it, SoundCloudBridge.session.account(app)) }
        BridgeNet.log("soundcloud", "session renewed in a hidden page${if (quiet) " ($why)" else ""}: new token from ${found.source} in ${(System.currentTimeMillis() - started) / 1000} s")
        return true
    }

    /**
     * The token a renewal found: the page's Authorization header first, then the cookie of any
     * SoundCloud host, as at sign-in, and only one other than [old] (the page may send the old one
     * before it renews).
     */
    fun newToken(header: SoundCloudBridge.Found?, cookies: Map<String, String?>, old: String?): SoundCloudBridge.Found? {
        val fresh = header?.takeIf { it.token != old }
        val jars = cookies.mapValues { (_, jar) -> jar?.takeIf { SoundCloudBridge.oauthToken(it) != old } }
        return SoundCloudBridge.pick(fresh, jars, null)
    }

    /** Why a DataDome page ends a renewal: there is nobody to solve or wait out anything in a hidden page. */
    fun dataDomeProblem(kind: DataDome.Kind): String? = when (kind) {
        DataDome.Kind.CAPTCHA -> "the bot protection (DataDome) showed a puzzle in the hidden page, which only a person can solve"
        DataDome.Kind.BLOCKED -> "the bot protection (DataDome) blocked the hidden page"
        DataDome.Kind.CHECK -> null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openHidden(app: Context, old: String?): Page {
        if (Looper.myLooper() == Looper.getMainLooper()) return Page(null, null, "asked on the main thread")
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val header = AtomicReference<SoundCloudBridge.Found?>(null)
        val problem = AtomicReference<String?>(null)
        val loadedAt = AtomicLong(0)
        val over = AtomicBoolean(false)
        val checked = AtomicBoolean(false)
        var web: WebView? = null
        val cm = CookieManager.getInstance()
        fun jars() = SoundCloudBridge.COOKIE_SITES.associateWith { runCatching { cm.getCookie(it) }.getOrNull() }

        // Only watched, as at sign-in: the client id and the token the page's API calls carry.
        fun watch(request: WebResourceRequest) {
            DataDome.kindOf(request.url.toString())?.let { kind ->
                if (kind == DataDome.Kind.CHECK) checked.set(true)
                dataDomeProblem(kind)?.let { problem.compareAndSet(null, it); latch.countDown() }
            }
            val host = request.url.host
            if (!SoundCloudBridge.isApiHost(host)) return
            runCatching { SoundCloudBridge.rememberClientId(app, request.url.toString()) }
            val auth = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value ?: return
            val token = SoundCloudBridge.tokenOfAuthorization(auth) ?: return
            if (token != old && header.compareAndSet(null, SoundCloudBridge.Found(token, "header:$host"))) latch.countDown()
        }
        val workers = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)

        main.post {
            try {
                cm.setAcceptCookie(true)
                val w = WebView(app)
                web = w
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                cm.setAcceptThirdPartyCookies(w, true)
                // The sign-in's identity: the WebView's own user agent, without the app header, no script.
                BridgeUa.dropAppHeader(w)
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
                    // "Open in app" and the stores: the page stays where it is.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        SoundCloudBridge.isAppHandoff(request.url.toString())
                    override fun onPageFinished(view: WebView, url: String) { loadedAt.compareAndSet(0, System.currentTimeMillis()) }
                    // The site answering with an error (5xx, 403…) is no proof the session is gone.
                    override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                        if (req.isForMainFrame && resp.statusCode >= 400) { problem.compareAndSet(null, "the page did not load (HTTP ${resp.statusCode})"); latch.countDown() }
                    }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (req.isForMainFrame) { problem.compareAndSet(null, "the page did not load (error ${err.errorCode})"); latch.countDown() }
                    }
                }
                w.loadUrl(SoundCloudBridge.MOBILE_SITE + "/")
                // The cookie the page renews has no request to show it: the jar is looked at every second,
                // and a page that has loaded and renewed nothing for a while is given up on.
                main.postDelayed(object : Runnable {
                    override fun run() {
                        if (over.get()) return
                        if (newToken(null, jars(), old) != null) { latch.countDown(); return }
                        val at = loadedAt.get()
                        if (at > 0 && System.currentTimeMillis() - at > SETTLE_MS) { problem.compareAndSet(null, if (checked.get()) DATADOME_CHECKED else RENEWED_NOTHING); latch.countDown(); return }
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
        val result = AtomicReference<Page>()
        val done = CountDownLatch(1)
        main.post {
            try {
                val jars = jars()
                val found = newToken(header.get(), jars, old)
                val why = problem.get() ?: if (!inTime) "no new token within $TIMEOUT_S s" else null
                // DataDome's page means the renewal failed, whatever the jar says.
                val fail = problem.get()?.contains("DataDome") == true
                result.set(Page(if (fail) null else found, SoundCloudBridge.mergeCookies(jars.values.toList()), if (fail || found == null) why else null))
            } finally {
                runCatching { web?.stopLoading(); web?.destroy() }
                if (workers) runCatching {
                    ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
                    })
                }
                done.countDown()
            }
        }
        done.await(10, TimeUnit.SECONDS)
        return result.get() ?: Page(null, null, "the hidden page did not close in time")
    }
}

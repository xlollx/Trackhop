package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Renews a YouTube Music session without the person (issue #62). Google rotates the session's
 * short-lived cookies (`__Secure-1PSIDTS`, `__Secure-3PSIDTS`) from the player's own page, whose
 * scripts call `accounts.google.com/RotateCookies` now and then. The connector never runs that page:
 * its calls go from a bare same-origin document ([YtmWeb]) or plain HTTP, so nothing rotates, and
 * after a while Google stops taking the session (a 401) although the person never signed out.
 * (That this is why is a likely explanation, not one Google states.)
 *
 * So the Bridge opens music.youtube.com in a hidden WebView, in the account's own profile and cookie
 * jar ([Slots]), exactly as the sign-in ran ([LoginActivity]: the WebView's own identity, no script
 * of ours in the page), lets the page run for a while so its scripts can rotate the cookies, and
 * keeps the jar as the sign-in keeps it ([YtmSessionStore.save]); the account's name and chosen
 * channel (#41) stay as they were. The calls made through the browser engine use the same jar.
 *
 * Twice a day from Trackhop's keep-alive ([keepAlive]), and once when a signed-in call is refused
 * with a 401 ([afterRefusal]), at most once per [PAUSE_MS] and one at a time per account. Cookie
 * values never reach the log.
 */
object YtmRenewal {
    /** What the hidden page left: the jar for music.youtube.com, whether the page said it is signed in (null: it did not say), or why it failed. */
    class Page(val cookies: String?, val loggedIn: Boolean?, val problem: String?)

    enum class Outcome { RENEWED, SIGNED_OUT, FAILED, SKIPPED, NOT_CONNECTED }

    /** The hidden page, replaced in tests (a JVM test has no WebView). */
    @Volatile var hiddenPage: (Context, String) -> Page = { ctx, slot -> openHidden(ctx, slot) }

    /**
     * Whether a signed-in call still answers (true), is refused with a 401 (false), or neither
     * (null): asked before the keep-alive says the session is gone, so a page that only looks signed
     * out never makes the person sign in again. Replaced in tests.
     */
    @Volatile var sessionAnswers: (Context, String) -> Boolean? = { ctx, slot -> answers(ctx, slot) }

    /** The clock, replaced in tests. */
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }

    /** After a renewal after a refusal, another one waits this long: one per sync, never a loop. */
    const val PAUSE_MS = 10 * 60_000L
    /** A session renewed less than this long ago is not renewed again by the keep-alive. */
    const val FRESH_MS = 60 * 60_000L
    /** How long the page runs after it has loaded, for its scripts to rotate the cookies. */
    private const val SETTLE_MS = 15_000L
    private const val TIMEOUT_S = 45L
    private const val VIEW_W = 412
    private const val VIEW_H = 915

    private val locks = ConcurrentHashMap<String, Any>()
    private val lastRefusalRenewal = ConcurrentHashMap<String, Long>()
    private val lastRenewed = ConcurrentHashMap<String, Long>()

    private fun lock(slot: String): Any = locks.getOrPut(slot) { Any() }
    private fun who(slot: String) = if (slot.isEmpty()) "" else " (account $slot)"

    /** Forgets when the account was renewed: a sign-out, a new sign-in, a test. */
    fun forget(slot: String? = null) {
        if (slot == null) { lastRefusalRenewal.clear(); lastRenewed.clear() } else { lastRefusalRenewal.remove(slot); lastRenewed.remove(slot) }
    }

    /**
     * Trackhop's background keep-alive: one hidden page, unless the session was renewed less than
     * an hour ago. Nothing when not signed in. One log line.
     */
    fun keepAlive(ctx: Context, slot: String = Slots.PRIMARY): Outcome {
        val app = ctx.applicationContext
        val s = Session.forSlot(slot)
        if (!s.isConnected(app)) return Outcome.NOT_CONNECTED
        return synchronized(lock(slot)) {
            lastRenewed[slot]?.let { if (now() - it < FRESH_MS) return Outcome.SKIPPED }
            var (outcome, why) = reload(app, slot)
            if (outcome == Outcome.SIGNED_OUT) when (runCatching { sessionAnswers(app, slot) }.getOrNull()) {
                false -> why += "; a signed-in call is refused too"
                true -> { outcome = Outcome.FAILED; why += ", but a signed-in call still answers" }
                null -> { outcome = Outcome.FAILED; why += "; whether a signed-in call answers could not be told" }
            }
            BridgeNet.log("ytm", when (outcome) {
                Outcome.RENEWED -> "session renewed in the background${who(slot)}"
                Outcome.SIGNED_OUT -> "the background renewal found the session signed out${who(slot)}: $why"
                else -> "the background renewal failed${who(slot)}: $why"
            })
            outcome
        }
    }

    /**
     * A signed-in call was refused (401) with [refused]: the cookies to make it once more with, or
     * null. When the stored ones are already others (another call renewed them a moment ago, or a
     * new sign-in) they are given without opening anything; within [PAUSE_MS] of the last renewal
     * after a refusal nothing is opened again. Concurrent calls wait for the one page.
     */
    fun afterRefusal(ctx: Context, slot: String, refused: String): String? {
        val app = ctx.applicationContext
        val s = Session.forSlot(slot)
        return synchronized(lock(slot)) {
            val current = s.cookies(app) ?: return null
            if (current != refused) return current
            val last = lastRefusalRenewal[slot]
            if (last != null && now() - last in 0 until PAUSE_MS) {
                BridgeNet.log("ytm", "session refused (401)${who(slot)}; renewed ${(now() - last) / 60_000} minutes ago already: not renewed again")
                return null
            }
            lastRefusalRenewal[slot] = now()
            val (outcome, why) = reload(app, slot)
            if (outcome == Outcome.RENEWED) s.cookies(app)
            else {
                BridgeNet.log("ytm", "session refused (401)${who(slot)}; ${if (outcome == Outcome.SIGNED_OUT) "the player's page is signed out" else "the renewal failed"}: $why")
                null
            }
        }
    }

    /** One hidden page and what it left: the jar kept when the page is still signed in. */
    private fun reload(app: Context, slot: String): Pair<Outcome, String> {
        val s = Session.forSlot(slot)
        val started = now()
        val page = runCatching { hiddenPage(app, slot) }.getOrElse { Page(null, null, it.message ?: it.javaClass.simpleName) }
        val cookies = page.cookies?.takeIf { it.isNotBlank() }
        return when {
            page.loggedIn == false -> Outcome.SIGNED_OUT to "the player's page says it is not signed in"
            page.problem != null -> Outcome.FAILED to page.problem
            cookies == null -> Outcome.FAILED to "the page left no cookies"
            YtmAuth.sapisid(cookies) == null -> Outcome.SIGNED_OUT to "the session's cookies are gone"
            else -> {
                // The account's name and chosen channel are kept apart from the cookies: they stay.
                s.save(app, cookies, s.account(app))
                lastRenewed[slot] = now()
                Outcome.RENEWED to "in ${(now() - started) / 1000} s"
            }
        }
    }

    /** One signed-in call (the account's channels), without a renewal of its own: see [sessionAnswers]. */
    private fun answers(app: Context, slot: String): Boolean? {
        val host = object : YtmHost by AndroidYtmHost(app, slot) {
            override val canRenew: Boolean get() = false
        }
        return try {
            YtmClient(host).channels(); true
        } catch (e: BridgeException) {
            if (e.message?.contains("(401)") == true) false else null
        } catch (e: Exception) { null }
    }

    /** What the page's own configuration says of the visitor: "true", "false", or "" when it says nothing. */
    private const val LOGGED_IN_SCRIPT =
        "(function(){try{var c=window.ytcfg;if(c&&c.get){var v=c.get('LOGGED_IN');if(v!==undefined)return String(v);}" +
            "if(c&&c.data_&&c.data_.LOGGED_IN!==undefined)return String(c.data_.LOGGED_IN);}catch(e){}return '';})()"

    /** "true" (a JSON string as evaluateJavascript gives it) to true, "false" to false, anything else to null. */
    fun loggedIn(result: String?): Boolean? = when (result?.trim()?.trim('"')) {
        "true" -> true
        "false" -> false
        else -> null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openHidden(app: Context, slot: String): Page {
        if (Looper.myLooper() == Looper.getMainLooper()) return Page(null, null, "asked on the main thread")
        val s = Session.forSlot(slot)
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val problem = AtomicReference<String?>(null)
        val logged = AtomicReference<Boolean?>(null)
        val loadedAt = AtomicLong(0)
        val over = AtomicBoolean(false)
        var web: WebView? = null
        val jar = s.jar()

        main.post {
            try {
                val w = WebView(app)
                web = w
                // The account's own profile, set before anything loads; its jar as the sign-in left it.
                Slots.apply(w, Session.PROFILE, slot)
                YtmWeb.seedCookies(app, s)
                jar.setAcceptCookie(true)
                jar.setAcceptThirdPartyCookies(w, true)
                // The sign-in's identity: the WebView's own user agent and client hints, no script of ours.
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
                // A page nobody sees still needs a size to lay itself out and run.
                w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, VIEW_W, VIEW_H)
                w.webViewClient = object : SafeWebViewClient() {
                    // Only YouTube's and Google's pages: "open in app" and the stores stay out.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"
                    override fun onPageFinished(view: WebView, url: String) {
                        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                        // Sent to Google's sign-in: the session is gone.
                        if (host == "accounts.google.com" && url.contains("signin", ignoreCase = true)) { logged.compareAndSet(null, false); latch.countDown(); return }
                        if (url.startsWith(Session.ORIGIN)) loadedAt.compareAndSet(0, System.currentTimeMillis())
                    }
                    override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                        if (req.isForMainFrame && resp.statusCode >= 400) { problem.compareAndSet(null, "the page did not load (HTTP ${resp.statusCode})"); latch.countDown() }
                    }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (req.isForMainFrame) { problem.compareAndSet(null, "the page did not load (error ${err.errorCode})"); latch.countDown() }
                    }
                }
                w.loadUrl("${Session.ORIGIN}/")
                // Once loaded, the page runs for a while; then it is asked whether it is signed in.
                main.postDelayed(object : Runnable {
                    override fun run() {
                        if (over.get()) return
                        val at = loadedAt.get()
                        if (at == 0L || System.currentTimeMillis() - at < SETTLE_MS) { main.postDelayed(this, 1_000); return }
                        val view = web ?: run { latch.countDown(); return }
                        view.evaluateJavascript(LOGGED_IN_SCRIPT) { r -> loggedIn(r)?.let { logged.compareAndSet(null, it) }; latch.countDown() }
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
                runCatching { jar.flush() }
                val cookies = runCatching { jar.getCookie(Session.ORIGIN) }.getOrNull()
                val why = problem.get() ?: if (!inTime) (if (loadedAt.get() == 0L) "the page did not load within $TIMEOUT_S s" else "the page did not answer within $TIMEOUT_S s") else null
                result.set(Page(cookies, logged.get(), why))
            } finally {
                runCatching { web?.stopLoading(); web?.destroy() }
                done.countDown()
            }
        }
        done.await(10, TimeUnit.SECONDS)
        return result.get() ?: Page(null, null, "the hidden page did not close in time")
    }
}

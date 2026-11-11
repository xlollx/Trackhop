package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TicketOne's concert dates for Trackhop's Concerts (Italy, unofficial): the site's normal search page
 * (or an artist's page) opened in a WebView out of sight, as the phone's own browser would open it,
 * and what the page shows read with a script ([Scripts.COLLECT]): its JSON-LD, its event and artist
 * links. Trackhop's core reads the dates out of that answer (`TicketOne.parsePage`). The method
 * `ticketone.read` answers it, in the full build in-process and in the plugin for the Play one.
 *
 * Polite and plain: one page at a time, at least [GAP_MS] between the end of one and the start of
 * the next; only www.ticketone.it's search and artist pages ([Scripts.allowed]); no sign-in, no form,
 * no pictures loaded (their addresses are still read). The phone's own WebView identity is kept
 * (a mobile browser); only the "X-Requested-With" header an app's WebView adds is dropped. A cookie
 * banner is left alone while the page shows its data; when it hides it, the "only necessary"
 * (or "reject all", which leaves only the necessary ones) choice is taken if there is one, else
 * the answer says "consent" and Trackhop stops. A check page (captcha, "access denied") is never
 * answered: "challenge". The cookies the site sets stay in the WebView's own jar, like a browser's.
 *
 * Needs a check on a device (none of this could be run against the real site from here): that the
 * page renders in a WebView that is not on screen, the markers of a check page and of "no results",
 * the cookie banner's buttons, and the shape of the event cards.
 */
object TicketOnePage {
    const val GAP_MS = 1_500L
    private const val LOAD_TIMEOUT_S = 25L
    private const val RENDER_TIMEOUT_MS = 15_000L
    private const val POLL_MS = 750L
    /** Larger than any search page's data: a Bundle across processes holds about 1 MB. */
    private const val MAX_ANSWER = 320_000

    private val lock = Any()
    @Volatile private var lastDone = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** The page at [url] as the collector's JSON, with `state` set (see the class). Blocks: never on the main thread. */
    fun read(ctx: Context, url: String): String {
        if (!Scripts.allowed(url)) throw BridgeException("ticketone: only www.ticketone.it's search and artist pages are read")
        synchronized(lock) {
            val wait = lastDone + GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            try {
                val answer = load(ctx.applicationContext, url)
                if (answer.length > MAX_ANSWER) throw BridgeException("ticketone: the page is too large to read")
                return answer
            } finally {
                lastDone = System.currentTimeMillis()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(app: Context, url: String): String {
        val loaded = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val holder = AtomicReference<WebView?>(null)
        main.post {
            try {
                val w = WebView(app)
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkImage = true
                    allowFileAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                }
                BridgeUa.dropAppHeader(w)
                // Not on screen, but the size of the phone's screen: pages lay out and lazy lists load as in a browser.
                val dm = app.resources.displayMetrics
                w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, dm.widthPixels, dm.heightPixels)
                w.webViewClient = object : SafeWebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        if (Scripts.onSite(request.url.toString())) return false
                        // A waiting room or another site: not followed.
                        failure.compareAndSet(null, "challenge:the page went to ${request.url.host}")
                        loaded.countDown()
                        return true
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (!Scripts.onSite(url)) { failure.compareAndSet(null, "challenge:the page went to ${Scripts.hostOf(url)}"); loaded.countDown() }
                    }
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame) { failure.compareAndSet(null, "error:the page did not load (${err.errorCode})"); loaded.countDown() }
                    }
                    override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                        // 403/429 from a bot shield is a check page; any other error, an error.
                        if (req.isForMainFrame) {
                            val code = res.statusCode
                            failure.compareAndSet(null, (if (code == 403 || code == 429) "challenge" else "error") + ":the site answered $code")
                            loaded.countDown()
                        }
                    }
                }
                holder.set(w)
                w.loadUrl(url)
            } catch (e: Exception) {
                failure.compareAndSet(null, "error:the browser engine could not be started: ${e.message ?: e.javaClass.simpleName}")
                loaded.countDown()
            }
        }
        try {
            if (!loaded.await(LOAD_TIMEOUT_S, TimeUnit.SECONDS)) return done(null, "error", "the page did not load in ${LOAD_TIMEOUT_S}s")
            failure.get()?.let { return done(null, it.substringBefore(':'), it.substringAfter(':')) }
            val web = holder.get() ?: return done(null, "error", "no browser engine")
            val deadline = System.currentTimeMillis() + RENDER_TIMEOUT_MS
            var consentTried = false
            while (true) {
                failure.get()?.let { return done(null, it.substringBefore(':'), it.substringAfter(':')) }
                val seen = eval(web, Scripts.COLLECT)?.let { Scripts.decode(it) }
                val state = seen?.get("state")?.jsonPrimitive?.contentOrNull
                when (state) {
                    "challenge", "empty" -> return done(seen, state)
                    "found" -> return done(seen, "ready")
                    "consent" -> if (!consentTried) {
                        consentTried = true
                        val clicked = eval(web, Scripts.CONSENT)?.let { Scripts.decodeString(it) } == "clicked"
                        BridgeNet.log("ticketone", if (clicked) "cookie banner: only the necessary cookies chosen" else "cookie banner with no \"necessary only\" choice: stopped")
                        if (!clicked) return done(seen, "consent")
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    val artists = (seen?.get("artists") as? JsonArray)?.isNotEmpty() == true
                    return done(seen, when { artists -> "ready"; state == "consent" -> "consent"; else -> "waiting" })
                }
                Thread.sleep(POLL_MS)
            }
        } finally {
            main.post { holder.getAndSet(null)?.let { w -> runCatching { w.stopLoading(); w.destroy() } } }
        }
    }

    /** Runs [script] in the page and waits for its value (a JSON literal), or null. */
    private fun eval(web: WebView, script: String): String? {
        val out = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        main.post {
            try { web.evaluateJavascript(script) { v -> out.set(v); latch.countDown() } } catch (_: Exception) { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        return out.get()
    }

    /** The answer: what the page showed ([seen], or nothing) with its final [state], and why when it failed. */
    private fun done(seen: JsonObject?, state: String, why: String? = null): String {
        BridgeNet.log("ticketone", "page read: $state" + (why?.let { " ($it)" }.orEmpty()) +
            (seen?.let { s -> " · ${(s["ld"] as? JsonArray)?.size ?: 0} JSON-LD, ${(s["cards"] as? JsonArray)?.size ?: 0} event links" }.orEmpty()))
        val fields = LinkedHashMap(seen ?: JsonObject(emptyMap()))
        fields["state"] = JsonPrimitive(state)
        why?.let { fields["error"] = JsonPrimitive(it) }
        return JsonObject(fields).toString()
    }

    /** The scripts and checks, apart from the WebView so they can be tested on the JVM. */
    object Scripts {
        private val PAGES = Regex("""^/(search|artist)/""")

        fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: "?"

        /** A page of the site (any path): where its own navigation (a redirect, a reload after the cookie choice) may go. */
        fun onSite(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val host = hostOf(url)
            return host == "www.ticketone.it" || host == "ticketone.it"
        }

        /** What Trackhop may ask to open: the search and the artists' pages of www.ticketone.it, nothing else. */
        fun allowed(url: String): Boolean {
            if (!url.startsWith("https://") || hostOf(url) != "www.ticketone.it") return false
            val path = runCatching { java.net.URI(url).rawPath }.getOrNull().orEmpty()
            return PAGES.containsMatchIn(path)
        }

        /** The collector's value: evaluateJavascript hands back the returned string as a JSON literal. */
        fun decode(value: String): JsonObject? = decodeString(value)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

        fun decodeString(value: String): String? = runCatching { (Json.parseToJsonElement(value) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()

        /**
         * Reads what the page shows, touching nothing. `state`: "challenge" (a check page), "found"
         * (JSON-LD events or event links), "empty" (the search says no results), "consent" (a cookie
         * banner over a page with nothing yet), "none" (not rendered yet). Assumed markers, all
         * defensive: event pages under /event/, artist pages under /artist/<slug>/; a card is the
         * nearest list item, article or "card"/"item"/"event" box around an event link.
         */
        val COLLECT = """
(function(){
  var out = {v: 1, state: 'none', url: String(location.href), title: String(document.title || ''), ld: [], cards: [], artists: []};
  try {
    var txt = function(el){ return el ? String(el.innerText || el.textContent || '').replace(/[ \t ]+/g, ' ').replace(/\s*\n\s*/g, '\n').trim() : ''; };
    var head = (out.title + '\n' + (document.body ? txt(document.body).slice(0, 3000) : '')).toLowerCase();
    if (document.querySelector('iframe[src*="captcha"],iframe[src*="challenge"],#challenge-form,#challenge-running,.g-recaptcha,.h-captcha,#px-captcha,[id*="captcha"]') ||
        /captcha|access denied|accesso negato|verify you are (a )?human|non sono un robot|pardon our interruption|request unsuccessful|attention required|you have been blocked|sei stato bloccato/.test(head)) {
      out.state = 'challenge'; return JSON.stringify(out);
    }
    var total = 0, i;
    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
    for (i = 0; i < scripts.length && out.ld.length < 30; i++) {
      var t = String(scripts[i].textContent || '');
      if (t.length < 100000 && total + t.length < 150000) { out.ld.push(t); total += t.length; }
    }
    var abs = function(h){ try { return new URL(h, location.href).href; } catch (e) { return ''; } };
    var imgOf = function(el){
      var im = el && el.querySelector('img'); if (!im) return '';
      var s = im.getAttribute('src') || '';
      if (!s || s.indexOf('data:') === 0) s = im.getAttribute('data-src') || String(im.getAttribute('srcset') || im.getAttribute('data-srcset') || '').trim().split(' ')[0] || '';
      return s ? abs(s) : '';
    };
    var seen = {};
    var links = document.querySelectorAll('a[href]');
    for (i = 0; i < links.length; i++) {
      var a = links[i];
      var path = abs(a.getAttribute('href')).split('#')[0];
      if (!/^https:\/\/(www\.)?ticketone\.it\//.test(path)) continue;
      if (/\/artist\/[^\/?#]+\/?(\?.*)?$/.test(path)) {
        if (!seen['a' + path] && out.artists.length < 40) { seen['a' + path] = 1; out.artists.push({href: path, text: txt(a).slice(0, 120)}); }
        continue;
      }
      if (!/\/event\//.test(path) || seen['e' + path] || out.cards.length >= 100) continue;
      seen['e' + path] = 1;
      var box = a.closest('li,article,[class*="card"],[class*="item"],[class*="listing"],[class*="event"]') || a.parentElement || a;
      if (box === document.body || box === document.documentElement) box = a;
      var tm = box.querySelector('time[datetime]');
      var name = box.querySelector('h1,h2,h3,h4,[class*="title"],[class*="name"]');
      out.cards.push({href: path, title: (txt(name) || a.getAttribute('title') || a.getAttribute('aria-label') || txt(a)).slice(0, 200),
        text: txt(box).slice(0, 400), time: tm ? String(tm.getAttribute('datetime') || '') : '', img: imgOf(box)});
    }
    if (/"@type"\s*:\s*(\[[^\]]*)?"[A-Za-z]*Event"/.test(out.ld.join('\n')) || out.cards.length) { out.state = 'found'; return JSON.stringify(out); }
    if (/nessun risultato|nessun evento trovato|non (abbiamo trovato|ci sono) (risultati|eventi)|\b0 risultati|no results found/.test(head)) { out.state = 'empty'; return JSON.stringify(out); }
    var banner = document.querySelector('#onetrust-banner-sdk,#onetrust-consent-sdk,#usercentrics-root,#usercentrics-cmp-ui,#CybotCookiebotDialog,#didomi-host,#cmpbox,#cmpwrapper,[id*="cookie-consent"],[class*="cookie-consent"],[id*="consent-banner"],[class*="consent-banner"],[role="dialog"][aria-label*="ookie"]');
    if (banner) {
      var cs = getComputedStyle(banner);
      if (cs.display !== 'none' && cs.visibility !== 'hidden') out.state = 'consent';
    }
  } catch (e) { out.error = String(e); }
  return JSON.stringify(out);
})();
""".trimIndent()

        /**
         * Picks only the necessary cookies, if the banner offers that (the known banners' "reject
         * all" leaves only those too): "clicked", else "none". Nothing else on the page is touched.
         */
        val CONSENT = """
(function(){
  try {
    var roots = [document];
    var uc = document.querySelector('#usercentrics-root,#usercentrics-cmp-ui');
    if (uc && uc.shadowRoot) roots.push(uc.shadowRoot);
    var known = ['#onetrust-reject-all-handler', '[data-testid="uc-deny-all-button"]', '#CybotCookiebotDialogBodyButtonDecline', '#didomi-notice-disagree-button', '.cmpboxbtnno'];
    var only = /^(accetta |consenti |usa |accept |allow |use )?(solo |soltanto |only )?(i |the )?(cookie |cookies )?(strettamente |strictly )?necessar(i|y)( cookies?)?( only)?$|^rifiuta( tutti| tutto)?$|^reject all$|^continua senza accettare$/i;
    for (var r = 0; r < roots.length; r++) {
      for (var k = 0; k < known.length; k++) { var el = roots[r].querySelector(known[k]); if (el) { el.click(); return 'clicked'; } }
      var buttons = roots[r].querySelectorAll('button,[role="button"],input[type="button"]');
      for (var b = 0; b < buttons.length; b++) {
        var t = String(buttons[b].innerText || buttons[b].value || '').replace(/\s+/g, ' ').trim();
        if (only.test(t)) { buttons[b].click(); return 'clicked'; }
      }
    }
  } catch (e) {}
  return 'none';
})();
""".trimIndent()
    }
}

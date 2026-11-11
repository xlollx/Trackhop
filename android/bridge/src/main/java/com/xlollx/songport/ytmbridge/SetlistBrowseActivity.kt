package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * setlist.fm's own site, browsed by the person, for the Trackhop builds that do not carry this screen
 * themselves (Google Play, AppGallery; the GitHub and F-Droid build has its own and drops this one
 * from its manifest). The same as there: a plain WebView with its normal user agent, on setlist.fm's
 * search for an artist (or a link the person pasted). The person opens a concert's page; while one
 * is shown, a bar offers to make a playlist of it or add it to one. Only that page, as the person
 * sees it, is read (once, when they tap): no other request, nothing in the background, no key.
 *
 * Trackhop starts it for a result with [ACTION] and gets back the parts of the page its setlist is
 * read from ([Page.COLLECT]: the title tag, the date, the song labels, as the page has them) with
 * the page's address and the choice made; Trackhop reads the setlist out of them. Only Trackhop gets
 * that answer: started by any other app ([Page.callerOk]), the screen closes at once.
 */
class SetlistBrowseActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    private lateinit var web: WebView
    private lateinit var actions: LinearLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Page.callerOk(callingActivity?.packageName, packageName)) { setResult(RESULT_CANCELED); finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        bar.addView(TextView(this).apply {
            text = getString(R.string.bridge_setlist_hint)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply { text = getString(R.string.bridge_setlist_cancel); setOnClickListener { finish() } })
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        web = WebView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        frame.addView(web)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12); visibility = View.GONE }
        actions.addView(Button(this).apply {
            text = getString(R.string.bridge_setlist_add)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { take(existing = true) }
        })
        actions.addView(Button(this).apply {
            text = getString(R.string.bridge_setlist_make)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { take(existing = false) }
        })
        root.addView(bar); root.addView(frame); root.addView(actions)
        setContentView(root)
        BridgeInsets.fit(root)
        // The phone's own WebView, as it is: no other identity, no header dropped.
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
        }
        web.webViewClient = object : SafeWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "http" && uri.scheme != "https") return true
                // setlist.fm's pages stay here; another site opens in the browser.
                if (Page.onSite(uri.toString())) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { shown(url) }
            override fun onPageFinished(view: WebView, url: String?) { shown(url) }
        }
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }
        web.loadUrl(Page.start(intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_LINK)))
    }

    /** The bar shows while a concert's setlist page is open. */
    private fun shown(url: String?) {
        actions.visibility = if (url != null && Page.isSetlist(url)) View.VISIBLE else View.GONE
    }

    /** Reads the page shown (once) and hands its setlist's parts back to Trackhop. */
    private fun take(existing: Boolean) {
        val url = web.url ?: return
        if (!Page.isSetlist(url)) return
        web.evaluateJavascript(Page.COLLECT) { value ->
            val parts = Page.decode(value)
            if (parts == null) { Toast.makeText(this, R.string.bridge_setlist_unread, Toast.LENGTH_LONG).show(); return@evaluateJavascript }
            BridgeNet.log("setlist", "a concert's page handed to Trackhop: ${parts.second} songs")
            setResult(RESULT_OK, Intent().putExtra(RESULT_HTML, parts.first).putExtra(RESULT_URL, url).putExtra(RESULT_EXISTING, existing))
            finish()
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        /** How Trackhop starts it (for a result), with [EXTRA_ARTIST] or [EXTRA_LINK]. */
        const val ACTION = "com.xlollx.songport.ytmbridge.SETLIST_BROWSE"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_LINK = "link"
        /** The answer: the page's parts the setlist is read from, its address, and "add to a playlist" (true) or "make one". */
        const val RESULT_HTML = "html"
        const val RESULT_URL = "url"
        const val RESULT_EXISTING = "existing"
    }

    /** The checks and the script, apart from the WebView so they can be tested on the JVM. */
    object Page {
        private const val HOME = "https://www.setlist.fm/"
        /** Trackhop's package id, unchanged since it was called Songport. */
        private const val TRACKHOP = "com.xlollx.songport"
        /** More than any concert's parts: an answer between apps holds well under a megabyte. */
        const val MAX_PARTS = 200_000
        private val SETLIST = Regex("""^https?://(www\.)?setlist\.fm/setlist/[^\s<>"']+""", RegexOption.IGNORE_CASE)

        /** Only Trackhop (or this app) started it for a result: anyone else gets nothing. */
        fun callerOk(caller: String?, own: String): Boolean = caller != null && (caller == TRACKHOP || caller == own)

        private fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()

        /** A page of setlist.fm: it stays in this screen. */
        fun onSite(url: String): Boolean {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return false
            val host = hostOf(url)
            return host == "setlist.fm" || host.endsWith(".setlist.fm")
        }

        /** A concert's setlist page. */
        fun isSetlist(url: String): Boolean = SETLIST.containsMatchIn(url) && onSite(url)

        /** A pasted setlist.fm link, else the artist's search on setlist.fm, else its home. */
        fun start(artist: String?, link: String?): String = when {
            link != null && isSetlist(link) -> link
            !artist.isNullOrBlank() -> HOME + "search?query=" + java.net.URLEncoder.encode(artist.trim(), "UTF-8").replace("+", "%20")
            else -> HOME
        }

        /**
         * The collector's value (evaluateJavascript hands back the returned string as a JSON literal):
         * the parts and how many songs they hold; null when the page shows no song yet, or more than
         * a concert's page would.
         */
        fun decode(value: String?): Pair<String, Int>? {
            val text = runCatching { (Json.parseToJsonElement(value ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: return null
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val songs = o["songs"]?.jsonPrimitive?.intOrNull ?: 0
            val html = (o["html"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (songs <= 0 || html.isEmpty() || html.length > MAX_PARTS) return null
            return html to songs
        }

        /**
         * Reads what the page shows, touching nothing: its og:title tag, the box of its date (month,
         * day, year) and each song label, each as the page has it (outerHTML), the same text
         * Trackhop's own screen reads in the whole page.
         */
        val COLLECT = """
(function(){
  var out = {songs: 0, html: ''};
  try {
    var parts = [];
    var t = document.querySelector('meta[property="og:title"]');
    if (t) parts.push(t.outerHTML);
    var m = document.querySelector('[class="month"]');
    if (m && m.parentElement) parts.push(m.parentElement.outerHTML.slice(0, 2000));
    var songs = document.querySelectorAll('[class="songLabel"]');
    for (var i = 0; i < songs.length && i < 300; i++) parts.push(songs[i].outerHTML.slice(0, 2000));
    out.songs = Math.min(songs.length, 300);
    out.html = parts.join('\n');
  } catch (e) {}
  return JSON.stringify(out);
})();
""".trimIndent()
    }
}

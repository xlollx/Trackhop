package com.xlollx.songport.ui

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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xlollx.songport.R
import com.xlollx.songport.auth.SafeWebViewClient
import com.xlollx.songport.sync.SetlistImport

/**
 * setlist.fm's own site, browsed by the person in the app (GitHub and F-Droid builds, no setlist.fm
 * key): a plain WebView with its normal user agent, on setlist.fm's search for an artist (or a link
 * the person pasted). The person opens a concert's page; while one is shown, a bar offers to make a
 * playlist of it or add it to one. Only that page, as the person sees it, is read (its DOM, once, when
 * they tap): no other request, nothing in the background. The setlist then goes to the Setlists page,
 * where the usual picker asks where it goes ([SetlistsKept.browsed]).
 */
class SetlistSiteActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    private lateinit var web: WebView
    private lateinit var actions: LinearLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        bar.addView(TextView(this).apply {
            text = getString(R.string.setlists_browse_hint)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply { text = getString(R.string.cancel); setOnClickListener { finish() } })
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        web = WebView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        frame.addView(web)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12); visibility = View.GONE }
        actions.addView(Button(this).apply {
            text = getString(R.string.releases_add_existing)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { take(existing = true) }
        })
        actions.addView(Button(this).apply {
            text = getString(R.string.setlists_make)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { take(existing = false) }
        })
        root.addView(bar); root.addView(frame); root.addView(actions)
        setContentView(root)
        // Edge to edge: the bars stay clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
        }
        web.webViewClient = object : SafeWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "http" && uri.scheme != "https") return true
                // setlist.fm's pages stay here; another site opens in the browser.
                if (onSetlistFm(uri)) return false
                openUrl(this@SetlistSiteActivity, uri.toString())
                return true
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { shown(url) }
            override fun onPageFinished(view: WebView, url: String?) { shown(url) }
        }
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }
        web.loadUrl(start(intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_LINK)))
    }

    /** The bar shows while a concert's setlist page is open. */
    private fun shown(url: String?) {
        actions.visibility = if (url != null && SetlistImport.isLink(url)) View.VISIBLE else View.GONE
    }

    /** Reads the page shown (its DOM, once) and hands the setlist to the Setlists page. */
    private fun take(existing: Boolean) {
        val url = web.url ?: return
        if (!SetlistImport.isLink(url)) return
        web.evaluateJavascript("document.documentElement.outerHTML") { dom ->
            val s = runCatching { SetlistImport.fromDom(dom ?: "null", url) }.getOrNull()
            if (s == null) { Toast.makeText(this, R.string.setlists_browse_unread, Toast.LENGTH_LONG).show(); return@evaluateJavascript }
            SetlistsKept.browsed(s, existing)
            finish()
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_LINK = "link"
        private const val HOME = "https://www.setlist.fm/"

        fun intent(ctx: Context, artist: String?, link: String?): Intent =
            Intent(ctx, SetlistSiteActivity::class.java).putExtra(EXTRA_ARTIST, artist).putExtra(EXTRA_LINK, link)

        private fun onSetlistFm(uri: Uri): Boolean = uri.host?.lowercase()?.let { it == "setlist.fm" || it.endsWith(".setlist.fm") } == true

        /** A pasted setlist.fm link, else the artist's search on setlist.fm, else its home. */
        internal fun start(artist: String?, link: String?): String = when {
            link != null && SetlistImport.isLink(link) -> link
            !artist.isNullOrBlank() -> HOME + "search?query=" + Uri.encode(artist)
            else -> HOME
        }
    }
}

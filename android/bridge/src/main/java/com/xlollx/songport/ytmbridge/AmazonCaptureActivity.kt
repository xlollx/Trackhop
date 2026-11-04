package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

/**
 * Development aid for the Amazon Music connector. The web player runs in this WebView with a small
 * script that mirrors every API call it makes (URL, request body, response) into a file on this
 * phone. The user browses their library, creates a playlist, adds and removes a song, then shares
 * the file so the connector can be completed against the real protocol.
 *
 * What is written: request bodies with the `headers` field removed (it carries the access token and
 * CSRF values), the request's own HTTP headers with every credential-looking value replaced by its
 * length, responses cut at 30 kB, and no cookies at all. Access tokens, bearer tokens and JWTs left
 * anywhere are blanked before writing.
 */
class AmazonCaptureActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var counter: TextView
    private var count = 0

    /**
     * Another site's web player to capture instead of Amazon's (a service being added, Qobuz…):
     * every call to its domain is written, not only Amazon's API paths.
     */
    private val startUrl: String? by lazy { intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://") } }
    /** The site's domain ("qobuz.com"): the calls to it and to its subdomains are written. */
    private val site: String? by lazy { startUrl?.let { android.net.Uri.parse(it).host }?.split('.')?.takeLast(2)?.joinToString(".") }

    private val file: File get() = File(filesDir, site?.let { "capture-$it.jsonl" } ?: "amazon-capture.jsonl")

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        count = if (file.exists()) file.readLines().size else 0

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 8) }
        counter = TextView(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val share = Button(this).apply { text = getString(R.string.bridge_amazon_capture_share); setOnClickListener { share() } }
        val clear = Button(this).apply { text = getString(R.string.bridge_amazon_capture_clear); setOnClickListener { file.delete(); count = 0; refresh() } }
        bar.addView(counter); bar.addView(clear); bar.addView(share)
        val hint = TextView(this).apply { text = if (site != null) getString(R.string.bridge_capture_site_intro, site) else getString(R.string.bridge_amazon_capture_intro); setPadding(16, 0, 16, 8); textSize = 12f }
        web = WebView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        root.addView(bar); root.addView(hint); root.addView(web)
        setContentView(root)
        BridgeInsets.fit(root)
        refresh()

        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // The phone-sized player hides playlist editing (rename, delete) behind "open the app";
            // the desktop player shows them in the playlist's menu. Identify as a desktop browser.
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        BridgeUa.apply(this, web)
        web.addJavascriptInterface(Sink(), "SpCapture")
        val hook = (site?.let { "window.__capSite = '${it.replace("'", "")}';\n" } ?: "") + HOOK
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(web, hook, setOf("*"))
        // Watching the real Amazon player here also refreshes the connector's own credentials.
        val amazon = site == null
        if (amazon) AmazonBridge.install(this, web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!early) { view.evaluateJavascript(hook, null); if (amazon) view.evaluateJavascript(AmazonBridge.HOOK, null) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!early) { view.evaluateJavascript(hook, null); if (amazon) view.evaluateJavascript(AmazonBridge.HOOK, null) }
            }
        }
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }
        web.loadUrl(startUrl ?: ("https://" + (AmazonSession.domain(this) ?: "music.amazon.com") + "/"))
    }

    private fun refresh() { counter.text = getString(R.string.bridge_amazon_capture_count, count) }

    private fun share() {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Trackhop: ${site ?: "Amazon Music"} capture")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.bridge_amazon_capture_share)))
    }

    private inner class Sink {
        @JavascriptInterface
        fun log(line: String) {
            if (file.exists() && file.length() > MAX_BYTES) return
            val safe = SECRETS.fold(TOKEN.replace(line, "\"accessToken\":\"[redacted]\"")) { s, r -> r.replace(s, "[redacted]") }
            synchronized(this) { file.appendText(safe.replace('\n', ' ') + "\n") }
            count++
            runOnUiThread { refresh() }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** The address to open for a capture of another site (https only). */
        const val EXTRA_URL = "url"
        private const val MAX_BYTES = 6L * 1024 * 1024
        private val TOKEN = Regex("\"accessToken\"\\s*:\\s*\"[^\"]*\"")
        /** Bearer tokens, JWTs and Amazon access tokens (Atna|…, Atza|…) wherever they appear. */
        private val SECRETS = listOf(
            Regex("Bearer\\s+[A-Za-z0-9._~+/=|-]+"),
            Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"),
            Regex("Atn[a-z]\\|[A-Za-z0-9._~+/=|-]+"),
            Regex("Atz[a-z]\\|[A-Za-z0-9._~+/=|-]+"),
        )

        // Mirrors fetch() and XMLHttpRequest calls towards the Amazon Music API into SpCapture.log.
        private val HOOK = """
            (function(){
              if (window.__spCap) return; window.__spCap = 1;
              // What the desktop player checks before it loads (read from its own code): the platform
              // must not be Linux (navigator.platform, "Linux aarch64" on a phone), the browser must
              // not be mobile, MSE must accept FLAC in MP4 and Widevine must accept it too. Otherwise
              // "Update Chrome". This screen never plays anything, so the codec answers are given.
              try { Object.defineProperty(Navigator.prototype, 'platform', { get: function(){ return 'Win32'; }, configurable: true }); } catch(e){}
              try { Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: function(){ return 0; }, configurable: true }); } catch(e){}
              try {
                var isType = MediaSource.isTypeSupported.bind(MediaSource);
                MediaSource.isTypeSupported = function(t){ return /audio\/mp4/.test(String(t)) ? true : isType(t); };
              } catch(e){}
              try {
                var realEme = navigator.requestMediaKeySystemAccess.bind(navigator);
                var stub = function(ks, cfgs){
                  var cfg = (cfgs && cfgs[0]) || {};
                  return { keySystem: ks, getConfiguration: function(){ return cfg; },
                    createMediaKeys: function(){ return Promise.resolve({
                      createSession: function(){ return { addEventListener: function(){}, removeEventListener: function(){}, generateRequest: function(){ return Promise.resolve(); }, update: function(){ return Promise.resolve(); }, close: function(){ return Promise.resolve(); }, closed: Promise.resolve() }; },
                      setServerCertificate: function(){ return Promise.resolve(true); } }); } };
                };
                navigator.requestMediaKeySystemAccess = function(ks, cfgs){ return realEme(ks, cfgs).catch(function(){ return stub(ks, cfgs); }); };
              } catch(e){}
              // A WebView has no window.chrome: a page that tells Chrome by it sees an old browser.
              try { if (!window.chrome) window.chrome = { runtime: {}, app: { isInstalled: false }, loadTimes: function(){ return {}; }, csi: function(){ return {}; } }; } catch(e){}
              // The "update your browser" page: what the player's own code checks, taken from its
              // scripts, so the check can be met instead of guessed at.
              var probe = function(){
                try {
                  var t = String(document.body && document.body.innerText || '');
                  if (!/Aggiornare Chrome|Update Chrome|browser non è più supportat|no longer supported|non è più supportata/i.test(t)) return;
                  var facts = { userAgent: navigator.userAgent, uaData: navigator.userAgentData ? JSON.stringify(navigator.userAgentData) : null,
                    chrome: !!window.chrome, plugins: navigator.plugins ? navigator.plugins.length : -1, vendor: navigator.vendor, platform: navigator.platform,
                    mediaSource: !!window.MediaSource, eme: !!navigator.requestMediaKeySystemAccess, wasm: !!window.WebAssembly, subtle: !!(window.crypto && crypto.subtle),
                    sw: !!navigator.serviceWorker, storage: !!navigator.storage, share: !!navigator.share, bt: !!navigator.bluetooth, usb: !!navigator.usb, hid: !!navigator.hid, pip: !!document.pictureInPictureEnabled,
                    gpu: !!navigator.gpu, wakeLock: !!navigator.wakeLock, credentials: !!navigator.credentials, cookies: !!window.cookieStore, scheduler: !!window.scheduler, url: location.href };
                  SpCapture.log(JSON.stringify({ t: Date.now(), url: 'diag://browser-check', method: 'facts', body: facts, status: 0, response: '' }));
                  var scripts = Array.prototype.slice.call(document.scripts).map(function(s){ return s.src; }).filter(Boolean);
                  scripts.forEach(function(src){
                    fetch(src).then(function(r){ return r.text(); }).then(function(js){
                      var out = [];
                      var re = /(unsupported|Unsupported|UNSUPPORTED|isSupportedBrowser|browserSupport|supportedBrowser|minimumVersion|minVersion|updateChrome|update-chrome|browserNotSupported|outdated)/g;
                      var m, n = 0;
                      while ((m = re.exec(js)) && n < 12) { out.push(js.substring(Math.max(0, m.index - 700), m.index + 700)); n++; re.lastIndex = m.index + 1; }
                      if (out.length) SpCapture.log(JSON.stringify({ t: Date.now(), url: 'diag://browser-check', method: 'script', body: src, status: js.length, response: out.join('\n----\n') }));
                    }).catch(function(){});
                  });
                } catch(e){}
              };
              setTimeout(probe, 4000); setTimeout(probe, 12000);
              // The old skill API, the music.amazon.* api paths, and since October 2026 the GraphQL
              // endpoint (gql.music.amazon.<tld>) the desktop player moved to.
              var abs = function(u){ try { return new URL(String(u||''), location.href).href; } catch(e) { return String(u||''); } };
              var match = function(u){
                var a = abs(u);
                // Another site: every call to its domain, pages and pictures aside.
                if (window.__capSite) { try { var h = new URL(a).hostname; return (h === window.__capSite || h.endsWith('.' + window.__capSite)) && !/\.(?:png|jpe?g|webp|gif|svg|css|woff2?|ico)(?:\?|$)/i.test(a); } catch(e) { return false; } }
                return /skill\.music\.a2z\.com|gql\.music\.amazon\.|music\.amazon\.[a-z.]+\/(?:[A-Z]{2}\/)?api\//.test(a);
              };
              // Passwords and secrets in addresses and form bodies (a sign-in posts them): blanked.
              var PARAM = /([?&]|^)((?:[a-z_]*pass(?:word)?|[a-z_]*secret|[a-z_]*token|email|username|login)=)[^&#]*/gi;
              var clean = function(s){ return String(s).replace(PARAM, '$1$2[redacted]'); };
              var KEY = /pass|secret|token|email|username|login|auth/i;
              var scrub = function(o){ if (o && typeof o === 'object') Object.keys(o).forEach(function(k){ if (KEY.test(k) && typeof o[k] !== 'object') o[k] = '[redacted]'; else scrub(o[k]); }); return o; };
              // Request headers as sent: names and values, credentials replaced by their length.
              var SECRET = /authori|token|cookie|csrf|session|signature|credential|x-amz-security/i;
              var hdrs = function(h){
                var out = {};
                try {
                  var each = function(v, k){ out[k] = SECRET.test(k) ? '[' + String(v).length + ' chars]' : String(v).slice(0, 300); };
                  if (!h) return out;
                  if (typeof h.forEach === 'function' && !(h instanceof Array)) h.forEach(each);
                  else if (h instanceof Array) h.forEach(function(p){ each(p[1], p[0]); });
                  else Object.keys(h).forEach(function(k){ each(h[k], k); });
                } catch(e){}
                return out;
              };
              var send = function(o){ try { SpCapture.log(JSON.stringify(o)); } catch(e){} };
              var redact = function(b){
                if (b == null) return null;
                if (typeof b !== 'string') { try { b = String(b); } catch(e) { return '[binary]'; } }
                try { var j = JSON.parse(b); if (j && typeof j === 'object') { delete j.headers; scrub(j); } return JSON.stringify(j).slice(0, 20000); }
                catch(e) { return clean(b).slice(0, 4000); }
              };
              var of = window.fetch;
              if (of) window.fetch = function(input, init){
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                var method = (init && init.method) || (input && input.method) || 'GET';
                var body = init && init.body;
                var reqHeaders = hdrs((init && init.headers) || (input && input.headers));
                var p = of.apply(this, arguments);
                if (match(url)) p.then(function(r){
                  try { r.clone().text().then(function(t){ send({t: Date.now(), url: clean(abs(url)), method: method, headers: reqHeaders, body: redact(body), status: r.status, response: t.slice(0, 30000)}); }); } catch(e){}
                });
                return p;
              };
              var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
              var osh = XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.setRequestHeader = function(k, v){ (this.__h = this.__h || []).push([k, v]); return osh.apply(this, arguments); };
              XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return oo.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function(b){
                var x = this;
                if (match(x.__u)) x.addEventListener('loadend', function(){
                  send({t: Date.now(), url: clean(abs(x.__u)), method: x.__m, headers: hdrs(x.__h), body: redact(b), status: x.status, response: String(x.responseText || '').slice(0, 30000)});
                });
                return os.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}

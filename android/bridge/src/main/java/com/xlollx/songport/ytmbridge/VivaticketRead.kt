package com.xlollx.songport.ytmbridge

import android.os.Bundle
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Vivaticket's dates for Trackhop's Concerts (Italy, unofficial), for the builds that do not ask its
 * site themselves (Google Play, AppGallery): the JSON its own web pages read, from its "API gateway",
 * asked here exactly as Trackhop's GitHub and F-Droid build asks it (`Vivaticket` in Trackhop's core,
 * which reads the dates out of the answer). The method `vivaticket.read` answers it.
 *
 * The same limits as there: only GETs of the gateway's search, artist and event answers
 * ([allowed]), no sign-in, no cookie, no key; at least [gapMs] between two requests (Trackhop paces
 * them too); the app's own name as User-Agent, never a browser's ([userAgent]). A redirect is not
 * followed: its status goes back, and Trackhop leaves the site alone for an hour, as with any error.
 */
object VivaticketRead {
    const val HOST = "apigatewayb2cstore.vivaticket.com"
    /** What the app calls itself when Trackhop names nothing (or something that poses as a browser). */
    const val DEFAULT_UA = "Trackhop ( https://trackhop.org )"
    /** Larger than any of the gateway's answers: a Bundle across processes holds about 1 MB. */
    const val MAX_ANSWER = 400_000
    private val PATHS = Regex("""^/api/(Home/Search|Artist|Event)/""")

    @Volatile internal var gapMs = 1_000L
    private val lock = Any()
    @Volatile private var lastDone = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .withHook().build()

    /** The gateway's search, artist and event answers over https, nothing else. */
    fun allowed(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.host?.lowercase() != HOST || (uri.port != -1 && uri.port != 443) || uri.rawUserInfo != null) return false
        return PATHS.containsMatchIn(uri.rawPath.orEmpty())
    }

    /** The name Trackhop asks for ([asked]), unless it is missing or a browser's: then the app's own. */
    fun userAgent(asked: String?): String {
        val ua = asked?.trim().orEmpty()
        return if (ua.isEmpty() || ua.length > 200 || ua.startsWith("Mozilla/") || ua.any { it < ' ' }) DEFAULT_UA else ua
    }

    /** One GET of [url]: `code` and the body as `json`, or `error`. Blocks: never on the main thread. */
    fun read(url: String, extras: Bundle?): Bundle {
        if (!allowed(url)) return err("vivaticket: only the gateway's search, artist and event answers are read")
        synchronized(lock) {
            val wait = lastDone + gapMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            try {
                val req = Request.Builder().url(url).get()
                    .header("User-Agent", userAgent(extras?.getString("userAgent")))
                    .header("Accept", "application/json")
                    .build()
                return http.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    BridgeNet.log("vivaticket", "${java.net.URI(url).rawPath.substringAfter("/api/").substringBefore('/')}: ${r.code}, ${body.length} characters")
                    if (body.length > MAX_ANSWER) err("vivaticket: the answer is too large")
                    else Bundle().apply { putInt("code", r.code); putString("json", body) }
                }
            } finally {
                lastDone = System.currentTimeMillis()
            }
        }
    }

    private fun err(message: String) = Bundle().apply { putString("error", message) }
}

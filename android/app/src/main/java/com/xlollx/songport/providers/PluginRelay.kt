package com.xlollx.songport.providers

import android.content.Context
import android.os.Bundle
import com.xlollx.songport.net.Relay
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.android
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The builds without built-in connectors hand the requests of the read-only link sources and of the
 * TikTok lookups to a connector plugin ([Relay]): with one installed that answers "net.status",
 * those features are there; without it they are not offered at all. The same for Concerts' TicketOne
 * and Vivaticket dates in Italy, and for setlist.fm browsed without a key (a plugin new enough for them).
 */
object PluginRelay {
    @Volatile private var checked: Pair<Long, Boolean>? = null

    /** Whether an installed plugin relays requests; asked again at most once a minute. */
    fun capable(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        checked?.let { (at, ok) -> if (now - at < 60_000) return ok }
        val ok = BridgePlugin.installed(ctx) && runCatching { BridgePlugin.call(ctx, "net.status")?.getBoolean("ok", false) == true }.getOrDefault(false)
        checked = now to ok
        return ok
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        Relay.available = { env -> capable(env.android) }
        com.xlollx.songport.sync.TikTokShare.gate = { capable(app) }
        Relay.via = { c -> withContext(Dispatchers.IO) { relay(app, c) } }
        com.xlollx.songport.sync.TikTokShare.recognizer = recognizer(app)
        com.xlollx.songport.sync.TicketOne.gate = { capable(app) }
        com.xlollx.songport.sync.TicketOne.reader = ticketOneReader(app)
        // Vivaticket's dates in Italy (unofficial): its gateway asked by a plugin that knows how.
        com.xlollx.songport.sync.Vivaticket.reader = vivaticketReader(app)
        com.xlollx.songport.sync.Vivaticket.gate = { sitesGate(capable(app), BridgePlugin.version(app)) }
        com.xlollx.songport.sync.Vivaticket.enabled = true
    }

    /**
     * Whether the plugin does what the GitHub and F-Droid build does itself with setlist.fm and
     * Vivaticket: it relays ([capable]) and reports [BridgePlugin.SITES_VERSION] or more. An older
     * one is not asked: no Vivaticket dates, and Browse setlist.fm says to update it.
     */
    fun sitesGate(capable: Boolean, version: Int): Boolean = capable && version >= BridgePlugin.SITES_VERSION

    /** The same for a tap on Browse setlist.fm: the plugin asked now (it may just have been updated). Blocks. */
    fun browsesSetlists(ctx: Context): Boolean = BridgePlugin.version(ctx, ask = true) >= BridgePlugin.SITES_VERSION

    /**
     * Concerts in Italy: one answer of Vivaticket's gateway asked by the connectors (the plugin's
     * `vivaticket.read` in the store builds), with this app's own User-Agent. Null when the plugin is
     * too old to know the method; its other errors fail the request.
     */
    fun vivaticketReader(app: Context): suspend (String) -> com.xlollx.songport.sync.Vivaticket.Answer? = { url ->
        withContext(Dispatchers.IO) {
            val b = BridgePlugin.call(app, "vivaticket.read", url, Bundle().apply { putString("userAgent", com.xlollx.songport.ai.ArtistMixSource.userAgent) })
                ?: throw java.io.IOException("the gateway reader did not answer")
            vivaticketAnswer(b.getString("error"), b.getInt("code", 0), b.getString("json"))
        }
    }

    /** The plugin's answer to `vivaticket.read`: null for a plugin that does not know it, an error thrown. */
    fun vivaticketAnswer(error: String?, code: Int, json: String?): com.xlollx.songport.sync.Vivaticket.Answer? {
        error?.let { if (it.startsWith("unknown method")) return null; throw java.io.IOException(it) }
        return com.xlollx.songport.sync.Vivaticket.Answer(code, json.orEmpty())
    }

    /**
     * The concert the person opened on setlist.fm in the plugin's screen: the parts of that page it
     * hands back ([html], its address [url]), read like the whole page in the GitHub build. Throws
     * when they hold no setlist.
     */
    fun setlistFromPlugin(html: String?, url: String?): com.xlollx.songport.sync.Setlists.Setlist {
        if (html.isNullOrEmpty() || url == null || !com.xlollx.songport.sync.SetlistImport.isLink(url)) throw java.io.IOException("setlist.fm: the page could not be read")
        return com.xlollx.songport.sync.SetlistImport.fromPage(html, url)
    }

    /**
     * Concerts in Italy: a TicketOne page read by the connectors' browser engine (in-process in the
     * full build, the plugin's in the Play one), as the JSON the core reads its dates from. Null when
     * the plugin is too old to know "ticketone.read"; its other errors fail the page.
     */
    fun ticketOneReader(app: Context): suspend (String) -> String? = { url ->
        withContext(Dispatchers.IO) {
            val b = BridgePlugin.call(app, "ticketone.read", url) ?: throw java.io.IOException("the page reader did not answer")
            b.getString("error")?.let { if (it.startsWith("unknown method")) return@withContext null; throw java.io.IOException(it) }
            b.getString("json")
        }
    }

    /**
     * The song in a TikTok "original sound", heard by the connectors (in-process in the full build,
     * the plugin's in the Play one): null when nothing matched, or when the plugin is too old to
     * know "audio.recognize" (its error is not an answer).
     */
    fun recognizer(app: Context): suspend (String) -> com.xlollx.songport.model.Track? = { url ->
        withContext(Dispatchers.IO) {
            val b = BridgePlugin.call(app, "audio.recognize", url) ?: return@withContext null
            b.getString("error")?.let { com.xlollx.songport.data.Diagnostics.log(app, "shazam", "not recognised: ${it.take(200)}"); return@withContext null }
            val j = com.xlollx.songport.net.parseJson(b.getString("json") ?: return@withContext null)
            val title = j["title"].str?.takeIf { it.isNotBlank() } ?: return@withContext null
            com.xlollx.songport.model.Track(
                id = j["isrc"].str ?: "shazam:$title", title = title,
                artists = listOfNotNull(j["artist"].str?.takeIf { it.isNotBlank() }),
                album = j["album"].str.orEmpty(), isrc = j["isrc"].str,
            )
        }
    }

    private fun relay(ctx: Context, c: Relay.Call): Relay.Answer {
        val b = BridgePlugin.call(ctx, "net.fetch", c.url, Bundle().apply {
            putString("method", c.method)
            putStringArray("headers", c.headers.map { (k, v) -> "$k\n$v" }.toTypedArray())
            c.body?.let { putString("body", it) }
            c.contentType?.let { putString("contentType", it) }
        }) ?: throw java.io.IOException("the connector plugin did not answer")
        b.getString("error")?.let { throw java.io.IOException(it) }
        val text = StringBuilder(b.getString("chunk").orEmpty())
        val length = b.getInt("length", text.length)
        val id = b.getString("id")
        while (id != null && text.length < length) {
            val more = BridgePlugin.call(ctx, "net.chunk", id, Bundle().apply { putInt("offset", text.length) })?.getString("chunk")
            if (more.isNullOrEmpty()) break
            text.append(more)
        }
        val headers = b.getStringArray("headers").orEmpty().mapNotNull { h -> h.split('\n', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return Relay.Answer(b.getInt("code", 0), text.toString(), b.getString("finalUrl") ?: c.url, headers)
    }
}

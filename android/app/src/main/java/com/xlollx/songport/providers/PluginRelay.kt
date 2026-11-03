package com.xlollx.songport.providers

import android.content.Context
import android.os.Bundle
import com.xlollx.songport.net.Relay
import com.xlollx.songport.platform.android
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The builds without built-in connectors hand the requests of the read-only link sources and of the
 * TikTok lookups to a connector plugin ([Relay]): with one installed that answers "net.status",
 * those features are there; without it they are not offered at all.
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

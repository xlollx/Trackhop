package com.xlollx.songport.net

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Two addresses for one personal server (2.2): the one at home and the one away from home
 * (Navidrome, Jellyfin, Plex… reached on the home network by its local address, outside by a
 * domain or a tunnel). Every request [Http] makes to either goes to the one chosen now ([route]);
 * when that one does not answer at all, the same request goes to the other ([failover]), which is
 * then remembered as the one that works on this kind of network.
 *
 * The first choice on a network: on Wi-Fi the home address, on mobile data the away one; where the
 * kind of network is not known (Desktop, Docker) the home address. Afterwards, the last that worked.
 */
object ServerAddresses {
    /** Each registered address to its other one (both ways). */
    private val other = ConcurrentHashMap<String, String>()
    /** The home address of each pair. */
    private val home = ConcurrentHashMap<String, String>()
    /** The address that worked last, by home address and kind of network. */
    private val worked = ConcurrentHashMap<String, String>()

    /** True on Wi-Fi (or Ethernet), false on mobile data, null where it is not known (servers). The app sets it. */
    @Volatile var onWifi: () -> Boolean? = { null }

    /**
     * [homeBase] and [awayBase] (no final slash) are the two addresses of one server. A new away
     * address replaces the one before, with what was remembered of it; the same pair again keeps it.
     */
    fun register(homeBase: String, awayBase: String) {
        val h = homeBase.trimEnd('/'); val a = awayBase.trimEnd('/')
        if (h.isEmpty() || a.isEmpty() || h == a) return
        if (home[h] == h && other[h] != a) unregister(h)
        other[h] = a; other[a] = h
        home[h] = h; home[a] = h
    }

    /** The away address registered for [homeBase], or null. */
    fun awayOf(homeBase: String): String? = homeBase.trimEnd('/').let { h -> other[h]?.takeIf { home[h] == h } }

    /** Forgets a server's pair (its second address taken away). */
    fun unregister(homeBase: String) {
        val h = homeBase.trimEnd('/')
        val a = other.remove(h) ?: return
        other.remove(a); home.remove(h); home.remove(a)
        worked.keys.removeIf { it.startsWith("$h|") }
    }

    private fun network(): String = runCatching { onWifi() }.getOrNull()?.let { if (it) "wifi" else "mobile" } ?: "any"

    /** The address of [homeBase]'s server to use now. */
    fun current(homeBase: String): String {
        val h = homeBase.trimEnd('/')
        val away = other[h] ?: return h
        val net = network()
        return worked["$h|$net"] ?: if (net == "mobile") away else h
    }

    /** The registered address [url] starts with, or null. */
    private fun baseOf(url: String): String? = other.keys.filter { b ->
        url.startsWith(b) && (url.length == b.length || url[b.length] == '/' || url[b.length] == '?')
    }.maxByOrNull { it.length }

    /** True when [url] is on a server with two addresses (it can fail over). */
    fun paired(url: String): Boolean = baseOf(url) != null

    /** [url] sent to the address chosen now for its server; any other url as it is. */
    fun route(url: String): String {
        val b = baseOf(url) ?: return url
        val now = current(home[b] ?: return url)
        return if (now == b) url else now + url.substring(b.length)
    }

    /** [url] (already routed) answered: its address is the one that works here. */
    fun answered(url: String) {
        val b = baseOf(url) ?: return
        val h = home[b] ?: return
        worked["$h|${network()}"] = b
    }

    /**
     * [url] could not reach its server ([e]): the same request for the other address, or null when
     * there is none or the failure is not one where nothing was sent (a refused connection, an
     * unknown name, no route, a connection or TLS handshake that timed out or failed).
     */
    fun failover(url: String, e: IOException): String? {
        if (!unreachable(e)) return null
        val b = baseOf(url) ?: return null
        val o = other[b] ?: return null
        return o + url.substring(b.length)
    }

    /** The request never left: safe to send it again elsewhere, a POST too. */
    fun unreachable(e: IOException): Boolean = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> true
        is javax.net.ssl.SSLHandshakeException -> true
        is java.net.SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
        else -> false
    }

    /** Tests: forget every pair. */
    fun reset() { other.clear(); home.clear(); worked.clear(); onWifi = { null } }
}

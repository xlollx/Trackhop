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
 *
 * An address on the home network or a VPN (a private IP, Tailscale's 100.64/10, `.local`) answers at
 * once or not at all: it is given [LOCAL_CONNECT_MS] to connect, not the 20 s a domain gets, so a
 * try of both addresses lasts seconds. When neither answers the failure is a [BothAddressesDown].
 */
object ServerAddresses {
    /** Each registered address to its other one (both ways). */
    private val other = ConcurrentHashMap<String, String>()
    /** The home address of each pair. */
    private val home = ConcurrentHashMap<String, String>()
    /** The address that worked last, by home address and kind of network. */
    private val worked = ConcurrentHashMap<String, String>()
    /** The address that answered last, by home address, whatever the network: what the "at home / away" line says. */
    private val lastAnswered = ConcurrentHashMap<String, String>()
    /** The server's name, by home address, for the message when neither address answers. */
    private val names = ConcurrentHashMap<String, String>()

    /** Connection time an address on the home network or a VPN gets (see [isLocal]). */
    const val LOCAL_CONNECT_MS = 4_000L

    /** Where the failures of both addresses are written (the app's and the server's log). */
    @Volatile var log: (String) -> Unit = {}

    /** True on Wi-Fi (or Ethernet), false on mobile data, null where it is not known (servers). The app sets it. */
    @Volatile var onWifi: () -> Boolean? = { null }

    /**
     * [homeBase] and [awayBase] (no final slash) are the two addresses of one server. A new away
     * address replaces the one before, with what was remembered of it; the same pair again keeps it.
     */
    fun register(homeBase: String, awayBase: String, name: String? = null) {
        val h = homeBase.trimEnd('/'); val a = awayBase.trimEnd('/')
        if (h.isEmpty() || a.isEmpty() || h == a) return
        if (home[h] == h && other[h] != a) unregister(h)
        if (!name.isNullOrBlank()) names[h] = name
        other[h] = a; other[a] = h
        home[h] = h; home[a] = h
    }

    /** The away address registered for [homeBase], or null. */
    fun awayOf(homeBase: String): String? = homeBase.trimEnd('/').let { h -> other[h]?.takeIf { home[h] == h } }

    /** Forgets a server's pair (its second address taken away). */
    fun unregister(homeBase: String) {
        val h = homeBase.trimEnd('/')
        val a = other.remove(h) ?: return
        other.remove(a); home.remove(h); home.remove(a); lastAnswered.remove(h)
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
        lastAnswered[h] = b
    }

    /** The address of [homeBase]'s server that answered last; null when none has yet, or the last try reached neither. */
    fun answeredLast(homeBase: String): String? = lastAnswered[homeBase.trimEnd('/')]

    /**
     * Both addresses of [url]'s server failed ([first] at [url], [second] at [otherUrl]): written to
     * the log, each with its own failure, and answered as one [BothAddressesDown].
     */
    fun bothDown(url: String, first: IOException, otherUrl: String, second: IOException): BothAddressesDown {
        val b = baseOf(url)
        val h = b?.let { home[it] } ?: url
        val a = other[h] ?: otherUrl
        lastAnswered.remove(h)
        // The kind of failure, not its text: that names the address in full (the log keeps no server address).
        fun why(e: IOException) = if (e is java.net.SocketTimeoutException) "no connection in time" else e.javaClass.simpleName
        val atHome = if (b == h) first else second
        val away = if (b == h) second else first
        val line = "home ${short(hostOf(h))} no answer (${why(atHome)}), away ${short(hostOf(a))} no answer (${why(away)})"
        runCatching { log(line) }
        return BothAddressesDown(names[h] ?: hostOf(h), line, second).also { it.addSuppressed(first) }
    }

    /** Connection time for [url]: short on the home network or a VPN, null (the client's own) for a domain. */
    fun connectTimeoutMs(url: String): Long? = if (isLocal(hostOf(url))) LOCAL_CONNECT_MS else null

    /** The start of an address, enough to tell home from away in the log: "192.168…", "music.…". */
    private fun short(host: String): String {
        val parts = host.split('.')
        return if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) "${parts[0]}.${parts[1]}…" else parts.first().take(12) + "…"
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    /**
     * A host on the home network or a VPN: localhost, `.local`, `.lan`, `.home.arpa`, private IPv4
     * (10/8, 172.16/12, 192.168/16, 127/8), Tailscale's 100.64/10, IPv6 loopback and private (fc00::/7, fe80::/10).
     */
    fun isLocal(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) {
            val v4 = parts.map { it.toIntOrNull() ?: 999 }
            if (v4.any { it !in 0..255 }) return false
            val a = v4[0]; val b = v4[1]
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        if (':' in h) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || Regex("^fe[89ab]").containsMatchIn(h)
        return false
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
    fun reset() { other.clear(); home.clear(); worked.clear(); lastAnswered.clear(); names.clear(); onWifi = { null }; log = {} }
}

/**
 * Neither address of a personal server answered ([server]: its name): the server is off, or the VPN
 * that reaches it from away is not connected. [detail] says both tries, for the log.
 */
class BothAddressesDown(val server: String, val detail: String, cause: Throwable) : java.net.ConnectException(detail) {
    init { initCause(cause) }
}

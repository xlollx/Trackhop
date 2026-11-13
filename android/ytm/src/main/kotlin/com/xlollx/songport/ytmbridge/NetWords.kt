package com.xlollx.songport.ytmbridge

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A lost or unstable connection said in the words Trackhop's NetError reads as the network's
 * ("Unable to resolve host", "Failed to connect", "timed out", "Network is unreachable"): the web
 * connectors' errors cross a process as text (the Bridge app), so a failure worded otherwise ("the
 * page did not load (error -2)", "TypeError: Failed to fetch") ended a run as a raw error instead of
 * a wait for the connection. Only failures that surely are the connection's get these words; any
 * other text is left exactly as it was. No Android here: the server build uses it too.
 */
object NetWords {
    // WebViewClient.ERROR_* (android.webkit), as plain numbers: this module has no Android.
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8

    /** Chromium's own names for a request that never reached the site. */
    private val OFFLINE_NAMES = listOf("INTERNET_DISCONNECTED", "ADDRESS_UNREACHABLE", "NETWORK_CHANGED")
    private val DNS_NAMES = listOf("NAME_NOT_RESOLVED", "NAME_RESOLUTION_FAILED")
    private val TIMEOUT_NAMES = listOf("TIMED_OUT")
    private val CONNECT_NAMES = listOf("CONNECTION_REFUSED", "CONNECTION_RESET", "CONNECTION_CLOSED", "CONNECTION_FAILED", "CONNECTION_ABORTED")

    /**
     * Whether a page's load error ([code] and Chromium's [description], as a WebView reports it) is
     * the connection's: no internet, a name not resolved, a connection refused, cut or timed out.
     * A certificate, a scheme or a blocked request is not.
     */
    fun isNetworkPageError(code: Int, description: String?): Boolean {
        val d = description.orEmpty()
        return code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT ||
            (OFFLINE_NAMES + DNS_NAMES + TIMEOUT_NAMES + CONNECT_NAMES).any { d.contains(it, true) }
    }

    /**
     * A page that did not load ([what], for instance "the web player", from [host]) in the network's
     * words when it is the connection's; otherwise "[what] could not load (description)" as before.
     */
    fun pageLoad(what: String, host: String, code: Int, description: String?): String {
        val d = description.orEmpty()
        return when {
            OFFLINE_NAMES.any { d.contains(it, true) } -> "$what could not load (Network is unreachable: $d)"
            code == ERROR_HOST_LOOKUP || DNS_NAMES.any { d.contains(it, true) } -> "$what could not load (Unable to resolve host $host: $d)"
            code == ERROR_TIMEOUT || TIMEOUT_NAMES.any { d.contains(it, true) } -> "$what could not load (timed out: $d)"
            code == ERROR_CONNECT || CONNECT_NAMES.any { d.contains(it, true) } -> "$what could not load (Failed to connect to $host: $d)"
            else -> "$what could not load (${d.ifEmpty { "error $code" }})"
        }
    }

    /** Whether [message] already says it is the connection, in words NetError reads. */
    fun readable(message: String?): Boolean {
        val m = message ?: return false
        // A bare "timeout" (OkHttp's) is not: once wrapped ("Deezer unreachable: timeout") NetError no longer reads it.
        return READABLE.any { m.contains(it, true) }
    }

    private val READABLE = listOf(
        "Unable to resolve host", "No address associated with hostname", "UnknownHostException", "Failed to connect",
        "ConnectException", "timed out", "SocketTimeoutException", "Network is unreachable", "ENETUNREACH",
    )

    /**
     * [e]'s message as before, with the connection's words added when [e] (or a cause) is a transport
     * failure whose own text does not say so: "Deezer unreachable: timeout" becomes readable as a
     * timeout. Any other failure keeps its text exactly.
     */
    fun of(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        if (readable(msg)) return msg
        val words = transport(e) ?: return msg
        return "$msg ($words)"
    }

    /** The connection's words for [e] or one of its causes, or null when none is a transport failure. */
    fun transport(e: Throwable?): String? {
        var t = e
        var depth = 0
        while (t != null && depth++ < 8) {
            when {
                t is UnknownHostException -> return "Unable to resolve host"
                t is NoRouteToHostException || t is PortUnreachableException -> return "Failed to connect to the service: ${t.javaClass.simpleName}"
                t is ConnectException -> return if (unreachable(t.message)) "Network is unreachable" else "Failed to connect to the service"
                t is SocketTimeoutException -> return "timed out"
                // OkHttp's call timeout.
                t is InterruptedIOException && t.message?.contains("timeout", true) == true -> return "timed out"
                t is SocketException && unreachable(t.message) -> return "Network is unreachable"
            }
            if (t.cause === t) break
            t = t.cause
        }
        return null
    }

    private fun unreachable(m: String?) = m != null && (m.contains("Network is unreachable", true) || m.contains("ENETUNREACH", true))

    /**
     * A `fetch()` made in a page that failed without an answer ("TypeError: Failed to fetch"): the
     * browser does not say why. A blocked request fails the same way, so the connection is looked at
     * ([probe] of [host]); only when it cannot be reached do the words say so. Blocks a few seconds at
     * most: never on the main thread.
     */
    fun fetchFailure(host: String, message: String, probe: (String) -> String? = ::probe): String {
        if (!isFetchFailure(message) || readable(message)) return message
        val why = runCatching { probe(host) }.getOrNull() ?: return message
        return "$message ($why)"
    }

    /** The texts of a `fetch()` that got no answer at all (Chromium, Firefox, Safari), or one made from a page that never loaded. */
    fun isFetchFailure(message: String?): Boolean {
        val m = message ?: return false
        return m.contains("Failed to fetch", true) || m.contains("NetworkError when attempting to fetch", true) ||
            m.contains("Load failed", true) || m.contains("Failed to parse URL from /", true)
    }

    /**
     * Whether [host] can be reached at all now: its name looked up, then a connection to port 443,
     * each within [timeoutMs]. Null when both work; otherwise what failed, in the network's words.
     */
    fun probe(host: String, timeoutMs: Long = 4_000): String? {
        val task = CompletableFuture.supplyAsync {
            val address = try { InetAddress.getByName(host) } catch (e: UnknownHostException) { return@supplyAsync "Unable to resolve host \"$host\": ${e.message ?: "no address"}" }
            try {
                Socket().use { it.connect(InetSocketAddress(address, 443), timeoutMs.toInt()) }
                null
            } catch (e: SocketTimeoutException) {
                "Failed to connect to $host: timed out"
            } catch (e: java.io.IOException) {
                if (unreachable(e.message)) "Network is unreachable (${e.message})" else "Failed to connect to $host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return try {
            task.get(timeoutMs * 2 + 500, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            "Unable to resolve host \"$host\": no answer in ${(timeoutMs * 2 + 500) / 1000} s"
        } catch (e: Exception) {
            null
        }
    }
}

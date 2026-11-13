package com.xlollx.songport.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What a failed call says about the connection, in the words a person can act on. The app and the
 * server share it: the engine stops a run cleanly on [Kind.OFFLINE], and every screen says the
 * plain words of [Kind] instead of the platform's exception text (which stays in the log).
 *
 * Only transport failures are classified. A service's own answer (an HTTP status, a refusal) is
 * [Kind.NONE] and keeps its message as before.
 */
object NetError {
    enum class Kind {
        /** Not a network problem (or not one we can name): the message is shown as it is. */
        NONE,
        /** No internet connection at all: nothing can be reached. */
        OFFLINE,
        /** Online, but the service did not answer in time. */
        TIMEOUT,
        /** Online, but this service's address could not be reached (DNS, a refused connection, a VPN). */
        UNREACHABLE,
    }

    /**
     * What the platform knows about the device's own connection: false when it knows there is no
     * internet (Android's validated network gone), true when it knows there is, null when it cannot
     * tell (the default, and the server). Set by the app's connectivity monitor.
     */
    @Volatile var networkUp: () -> Boolean? = { null }

    private fun up(): Boolean? = runCatching { networkUp() }.getOrNull()

    /** True only when the platform knows there is no internet now (not when it cannot tell). */
    fun knownOffline(): Boolean = up() == false

    /** The kind of [e], looking through its causes. */
    fun kind(e: Throwable?): Kind {
        var t: Throwable? = e
        var depth = 0
        var found = Kind.NONE
        while (t != null && depth++ < 8) {
            val k = own(t)
            // The most telling one wins: offline over a timeout found further down.
            if (k == Kind.OFFLINE) return k
            if (found == Kind.NONE) found = k
            if (t.cause === t) break
            t = t.cause
        }
        return found
    }

    fun isOffline(e: Throwable?): Boolean = kind(e) == Kind.OFFLINE

    /** Any transport failure of [kind]: a run waits it out or tries again later, it is not the service's answer. */
    fun isNetwork(e: Throwable?): Boolean = kind(e) != Kind.NONE

    private fun own(t: Throwable): Kind {
        val down = up() == false
        return when (t) {
            is UnknownHostException -> if (up() == true) Kind.UNREACHABLE else Kind.OFFLINE
            is NoRouteToHostException, is PortUnreachableException -> if (up() == true) Kind.UNREACHABLE else Kind.OFFLINE
            is ConnectException -> when {
                down -> Kind.OFFLINE
                // "Network is unreachable": no route out at all.
                unreachableNet(t.message) || t.cause?.message.let { unreachableNet(it) } -> if (up() == true) Kind.UNREACHABLE else Kind.OFFLINE
                // Refused, or a host that does not answer: this service (a server at home that is off), not the internet.
                else -> Kind.UNREACHABLE
            }
            is SocketTimeoutException -> if (down) Kind.OFFLINE else Kind.TIMEOUT
            is SSLException -> if (down) Kind.OFFLINE else Kind.NONE
            is InterruptedIOException -> if (down) Kind.OFFLINE else if (t.message?.contains("timeout", true) == true) Kind.TIMEOUT else Kind.NONE
            is SocketException -> if (down) Kind.OFFLINE else if (unreachableNet(t.message)) Kind.OFFLINE else Kind.NONE
            is IOException -> if (down && transportMessage(t.message)) Kind.OFFLINE else messageKind(t.message)
            // A connector that only passes the text on (the Bridge app, a ProviderException wrapping the line).
            else -> messageKind(t.message)
        }
    }

    /**
     * The kind of a failure known only by its text: an error that crossed a process (the Bridge), or
     * a report stored before this classification. Only unmistakable transport texts count.
     */
    fun messageKind(msg: String?): Kind {
        val m = msg ?: return Kind.NONE
        val dns = m.contains("Unable to resolve host", true) || m.contains("No address associated with hostname", true) ||
            m.contains("UnknownHostException", true) || m.contains("Name or service not known", true) ||
            m.contains("nodename nor servname", true) || m.contains("Temporary failure in name resolution", true)
        // A web player's page that did not load (the Bridge's browser engine), in Chromium's own words.
        val noNet = unreachableNet(m) || m.contains("ERR_INTERNET_DISCONNECTED", true) || m.contains("ERR_ADDRESS_UNREACHABLE", true)
        return when {
            dns || noNet -> if (up() == true) Kind.UNREACHABLE else Kind.OFFLINE
            up() == false && transportMessage(m) -> Kind.OFFLINE
            m.contains("SocketTimeoutException", true) || m.contains("timed out", true) || m.equals("timeout", true) -> Kind.TIMEOUT
            m.contains("Failed to connect to", true) || m.contains("ConnectException", true) -> Kind.UNREACHABLE
            // The token asked of a page that is not the player's (an error page): the page did not load.
            m.contains("Failed to parse URL from /", true) || m.contains("ERR_NAME_NOT_RESOLVED", true) || m.contains("ERR_NETWORK_CHANGED", true) ->
                if (up() == false) Kind.OFFLINE else Kind.UNREACHABLE
            // Chromium's names for a request that never got an answer (a page or a fetch of the Bridge's browser engine).
            m.contains("ERR_CONNECTION_TIMED_OUT", true) || m.contains("net::ERR_TIMED_OUT", true) -> Kind.TIMEOUT
            CHROMIUM_CONNECT.any { m.contains(it, true) } -> if (up() == false) Kind.OFFLINE else Kind.UNREACHABLE
            else -> Kind.NONE
        }
    }

    private fun unreachableNet(m: String?): Boolean =
        m != null && (m.contains("Network is unreachable", true) || m.contains("ENETUNREACH", true) || m.contains("Network unreachable", true))

    /** A text that says the line itself failed (the same family [com.xlollx.songport.sync.isTransientNetwork] waits out). */
    private fun transportMessage(m: String?): Boolean {
        val s = m ?: return false
        return s.contains("timeout", true) || s.contains("timed out", true) || s.contains("Failed to connect", true) ||
            s.contains("connection abort", true) || s.contains("reset by peer", true) || s.contains("connection closed", true) ||
            s.contains("unexpected end of stream", true) || s.contains("Unable to resolve host", true) || s.contains("unreachable", true) ||
            // A page's fetch() that got no answer (the Bridge's browser engine): the line, when the device is known offline.
            s.contains("Failed to fetch", true) || s.contains("NetworkError when attempting to fetch", true)
    }

    /** Chromium's net errors for a connection refused, cut or never made. */
    private val CHROMIUM_CONNECT = listOf(
        "ERR_CONNECTION_REFUSED", "ERR_CONNECTION_RESET", "ERR_CONNECTION_CLOSED", "ERR_CONNECTION_FAILED",
        "ERR_CONNECTION_ABORTED", "ERR_NAME_RESOLUTION_FAILED",
    )

    /**
     * The plain words for [kind], by the name of their text in strings.xml (the app's resources, also
     * read by the server); null for [Kind.NONE], which keeps its own message.
     */
    fun textName(kind: Kind, server: Boolean = false): String? = when (kind) {
        Kind.NONE -> null
        Kind.OFFLINE -> if (server) "net_offline_server" else "net_offline"
        Kind.TIMEOUT -> "net_timeout"
        Kind.UNREACHABLE -> "net_unreachable"
    }
}

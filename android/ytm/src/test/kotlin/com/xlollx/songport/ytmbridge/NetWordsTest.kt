package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A lost connection said in the words Trackhop reads as the network's; every other failure keeps its text. */
class NetWordsTest {
    @Test fun aPageThatDidNotLoadIsSaidInTheNetworksWords() {
        assertEquals(
            "the web player could not load (Network is unreachable: net::ERR_INTERNET_DISCONNECTED)",
            NetWords.pageLoad("the web player", "open.spotify.com", -1, "net::ERR_INTERNET_DISCONNECTED"),
        )
        assertEquals(
            "the web player could not load (Unable to resolve host open.spotify.com: net::ERR_NAME_NOT_RESOLVED)",
            NetWords.pageLoad("the web player", "open.spotify.com", NetWords.ERROR_HOST_LOOKUP, "net::ERR_NAME_NOT_RESOLVED"),
        )
        assertEquals("the page could not load (timed out: net::ERR_TIMED_OUT)", NetWords.pageLoad("the page", "example.org", NetWords.ERROR_TIMEOUT, "net::ERR_TIMED_OUT"))
        assertTrue(NetWords.pageLoad("the page", "example.org", -1, "net::ERR_CONNECTION_RESET").contains("Failed to connect to example.org"))
        // Not the connection: said as before.
        assertEquals("the page could not load (net::ERR_CERT_DATE_INVALID)", NetWords.pageLoad("the page", "example.org", -11, "net::ERR_CERT_DATE_INVALID"))
        assertEquals("the page could not load (error -10)", NetWords.pageLoad("the page", "example.org", -10, null))
    }

    @Test fun onlyTheConnectionsPageErrorsCount() {
        assertTrue(NetWords.isNetworkPageError(NetWords.ERROR_HOST_LOOKUP, null))
        assertTrue(NetWords.isNetworkPageError(NetWords.ERROR_CONNECT, null))
        assertTrue(NetWords.isNetworkPageError(NetWords.ERROR_TIMEOUT, null))
        assertTrue(NetWords.isNetworkPageError(-1, "net::ERR_INTERNET_DISCONNECTED"))
        assertTrue(NetWords.isNetworkPageError(-1, "net::ERR_NETWORK_CHANGED"))
        assertFalse(NetWords.isNetworkPageError(-11, "net::ERR_SSL_PROTOCOL_ERROR"))
        assertFalse(NetWords.isNetworkPageError(-10, "net::ERR_UNKNOWN_URL_SCHEME"))
        assertFalse(NetWords.isNetworkPageError(-1, "net::ERR_BLOCKED_BY_CLIENT"))
    }

    @Test fun aTransportFailureGetsWordsItsTextLacked() {
        assertEquals("timeout (timed out)", NetWords.of(InterruptedIOException("timeout").let { IOException("timeout", it) }))
        assertEquals("SocketTimeoutException", NetWords.of(SocketTimeoutException()))
        assertEquals("No route to host (Failed to connect to the service: NoRouteToHostException)", NetWords.of(NoRouteToHostException("No route to host")))
        assertEquals("api.example.org (Unable to resolve host)", NetWords.of(UnknownHostException("api.example.org")))
        // Already readable, or not the connection: exactly as before.
        assertEquals("Unable to resolve host \"api.example.org\": No address associated with hostname",
            NetWords.of(UnknownHostException("Unable to resolve host \"api.example.org\": No address associated with hostname")))
        assertEquals("Failed to connect to /10.0.0.1:443", NetWords.of(ConnectException("Failed to connect to /10.0.0.1:443")))
        assertEquals("Deezer deezer.pageProfile: no playlists in the answer", NetWords.of(BridgeException("Deezer deezer.pageProfile: no playlists in the answer")))
        assertEquals("IllegalStateException", NetWords.of(IllegalStateException()))
        assertEquals("connection closed", NetWords.of(IOException("connection closed")))
    }

    @Test fun theCauseIsKeptAndLookedThrough() {
        val e = BridgeException("Deezer unreachable: timeout", SocketTimeoutException("timeout"))
        assertTrue(e.cause is SocketTimeoutException)
        assertEquals("Deezer unreachable: timeout (timed out)", NetWords.of(e))
        assertNull(NetWords.transport(BridgeException("the Deezer web session has expired: sign out of Deezer and sign in again")))
    }

    @Test fun aFailedFetchIsTheConnectionOnlyWhenTheSiteCannotBeReached() {
        var probed: String? = null
        val down: (String) -> String? = { host -> probed = host; "Unable to resolve host \"$host\": No address associated with hostname" }
        val up: (String) -> String? = { null }
        assertEquals(
            "TypeError: Failed to fetch (Unable to resolve host \"play.anghami.com\": No address associated with hostname)",
            NetWords.fetchFailure("play.anghami.com", "TypeError: Failed to fetch", down),
        )
        assertEquals("play.anghami.com", probed)
        // Reachable: a blocked request, said as it is.
        assertEquals("TypeError: Failed to fetch", NetWords.fetchFailure("play.anghami.com", "TypeError: Failed to fetch", up))
        // Not a fetch that got no answer: never probed.
        probed = null
        assertEquals("HTTP 500", NetWords.fetchFailure("play.anghami.com", "HTTP 500", down))
        assertNull(probed)
    }

    @Test fun readableTextsAreTheOnesTrackhopReads() {
        assertFalse(NetWords.readable("timeout"))
        assertTrue(NetWords.readable("Read timed out"))
        assertTrue(NetWords.readable("the page could not load (Network is unreachable: net::ERR_INTERNET_DISCONNECTED)"))
        assertFalse(NetWords.readable("the page did not load (HTTP 503)"))
        assertFalse(NetWords.readable(null))
    }
}

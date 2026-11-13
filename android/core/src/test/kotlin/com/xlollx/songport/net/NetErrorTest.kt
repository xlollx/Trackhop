package com.xlollx.songport.net

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.NetError.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The plain words a failed call gets: the connection gone, too slow, or a service out of reach; anything else keeps its message. */
class NetErrorTest {
    @After fun tearDown() { NetError.networkUp = { null } }

    private fun up(v: Boolean?) { NetError.networkUp = { v } }

    @Test fun noNameResolutionOrRouteIsOfflineWhenTheDeviceCannotTell() {
        up(null)
        assertEquals(Kind.OFFLINE, NetError.kind(UnknownHostException("api.example.org")))
        assertEquals(Kind.OFFLINE, NetError.kind(UnknownHostException("Unable to resolve host \"api.example.org\": No address associated with hostname")))
        assertEquals(Kind.OFFLINE, NetError.kind(NoRouteToHostException("No route to host")))
        assertEquals(Kind.OFFLINE, NetError.kind(ConnectException("Failed to connect to /10.0.0.1:443").apply { initCause(java.net.SocketException("Network is unreachable")) }))
    }

    @Test fun aServiceThatRefusesIsUnreachableNotOffline() {
        up(null)
        assertEquals(Kind.UNREACHABLE, NetError.kind(ConnectException("Failed to connect to jellyfin.home/192.168.1.5:8096")))
        // With the device online, a name that does not resolve is that service's address, not the internet.
        up(true)
        assertEquals(Kind.UNREACHABLE, NetError.kind(UnknownHostException("music.home.example")))
    }

    @Test fun timeoutsAndTlsAreOfflineOnlyWhenTheDeviceSaysSo() {
        up(true)
        assertEquals(Kind.TIMEOUT, NetError.kind(SocketTimeoutException("timeout")))
        assertEquals(Kind.NONE, NetError.kind(SSLHandshakeException("Trust anchor for certification path not found.")))
        up(false)
        assertEquals(Kind.OFFLINE, NetError.kind(SocketTimeoutException("timeout")))
        assertEquals(Kind.OFFLINE, NetError.kind(SSLHandshakeException("connection closed")))
        assertEquals(Kind.OFFLINE, NetError.kind(IOException("unexpected end of stream on https://api.example.org/...")))
    }

    @Test fun theCauseIsLookedThrough() {
        up(null)
        val wrapped = RuntimeException("read failed", IOException("io", UnknownHostException("api.example.org")))
        assertEquals(Kind.OFFLINE, NetError.kind(wrapped))
    }

    @Test fun aTextThatCrossedAProcessIsReadByItsWords() {
        up(null)
        assertEquals(Kind.OFFLINE, NetError.kind(ProviderException("Unable to resolve host \"music.example.com\": No address associated with hostname")))
        assertEquals(Kind.TIMEOUT, NetError.messageKind("java.net.SocketTimeoutException: timeout"))
        assertEquals(Kind.UNREACHABLE, NetError.messageKind("Failed to connect to /10.0.0.2:8096"))
    }

    @Test fun answersOfTheServiceKeepTheirMessage() {
        up(false)
        assertEquals(Kind.NONE, NetError.kind(ProviderException("Example API 404: playlist not found")))
        assertEquals(Kind.NONE, NetError.kind(IllegalStateException("bad answer")))
        assertEquals(Kind.NONE, NetError.messageKind(null))
        up(null)
        assertEquals(Kind.NONE, NetError.kind(ProviderException("Example API 429: rate limit, retry in 30 s")))
    }

    @Test fun eachKindHasItsTextAndTheServerSaysItIsItsOwnConnection() {
        assertEquals("net_offline", NetError.textName(Kind.OFFLINE))
        assertEquals("net_offline_server", NetError.textName(Kind.OFFLINE, server = true))
        assertEquals("net_timeout", NetError.textName(Kind.TIMEOUT))
        assertEquals("net_unreachable", NetError.textName(Kind.UNREACHABLE))
        assertEquals(null, NetError.textName(Kind.NONE))
    }

    /** The Spotify web player's page that did not load: its token error is the network's, not a failure to show raw. */
    @Test fun aWebPlayerPageThatDidNotLoadIsTheNetwork() {
        val parse = "Spotify did not hand out a token: TypeError: Failed to execute 'fetch' on 'Window': Failed to parse URL from /get_access_token?reason=transport (trying again shortly)"
        up(false)
        assertEquals(Kind.OFFLINE, NetError.messageKind(parse))
        assertEquals(Kind.OFFLINE, NetError.messageKind("the web player could not load (net::ERR_INTERNET_DISCONNECTED)"))
        up(true)
        assertEquals(Kind.UNREACHABLE, NetError.messageKind(parse))
        assertEquals(Kind.UNREACHABLE, NetError.messageKind("the web player could not load (Unable to resolve host open.spotify.com: net::ERR_NAME_NOT_RESOLVED)"))
    }

    /** Chromium's own net errors, as a page or a fetch of the Bridge's browser engine reports them. */
    @Test fun chromiumNetErrorsAreTheNetwork() {
        up(true)
        assertEquals(Kind.TIMEOUT, NetError.messageKind("TicketOne: the page could not load (net::ERR_CONNECTION_TIMED_OUT)"))
        assertEquals(Kind.UNREACHABLE, NetError.messageKind("the Anghami player could not load (net::ERR_CONNECTION_RESET)"))
        assertEquals(Kind.UNREACHABLE, NetError.messageKind("net::ERR_CONNECTION_REFUSED"))
        up(false)
        assertEquals(Kind.OFFLINE, NetError.messageKind("net::ERR_CONNECTION_CLOSED"))
        // Not the connection: a certificate or a blocked request keeps its text.
        up(true)
        assertEquals(Kind.NONE, NetError.messageKind("net::ERR_CERT_AUTHORITY_INVALID"))
        assertEquals(Kind.NONE, NetError.messageKind("net::ERR_BLOCKED_BY_CLIENT"))
    }

    /** A page's fetch() that got no answer is the network only when the device knows it is offline (a blocked request fails the same way). */
    @Test fun aFailedFetchIsOfflineOnlyWhenTheDeviceSaysSo() {
        val failed = "Anghami: anghami request failed in the browser engine: TypeError: Failed to fetch"
        up(false)
        assertEquals(Kind.OFFLINE, NetError.messageKind(failed))
        assertEquals(Kind.OFFLINE, NetError.messageKind("TypeError: NetworkError when attempting to fetch resource."))
        up(true)
        assertEquals(Kind.NONE, NetError.messageKind(failed))
        up(null)
        assertEquals(Kind.NONE, NetError.messageKind(failed))
        // The Bridge looked at the connection and says so: read as the network's.
        assertEquals(Kind.OFFLINE, NetError.messageKind("$failed (Unable to resolve host \"play.anghami.com\": No address associated with hostname)"))
    }

    /** The texts the connectors give a lost connection (NetWords): each one read as the network's. */
    @Test fun theConnectorsWordsAreRead() {
        up(true)
        assertEquals(Kind.TIMEOUT, NetError.messageKind("Deezer unreachable: timeout (timed out)"))
        assertEquals(Kind.UNREACHABLE, NetError.messageKind("the browser engine for anghami could not be started: the Anghami player could not load (Failed to connect to play.anghami.com: net::ERR_CONNECTION_REFUSED)"))
        assertEquals(Kind.UNREACHABLE, NetError.messageKind("SoundCloud unreachable: No route to host (Failed to connect to the service: NoRouteToHostException)"))
        up(false)
        assertEquals(Kind.OFFLINE, NetError.messageKind("the Amazon Music player could not load (Network is unreachable: net::ERR_INTERNET_DISCONNECTED)"))
    }
}

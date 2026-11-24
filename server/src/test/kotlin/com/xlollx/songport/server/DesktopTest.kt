package com.xlollx.songport.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Trackhop Desktop: where its data goes, its settings, and a server that answers only to the loopback address. */
class DesktopTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun dataGoesToThePlatformsUserFolder() {
        assertEquals(File("C:\\Users\\me\\AppData\\Roaming", "Trackhop"), DesktopApp.dataDir(mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming"), "Windows 11", "C:\\Users\\me"))
        assertEquals(File("/home/me/.local/share", "trackhop"), DesktopApp.dataDir(emptyMap(), "Linux", "/home/me"))
        assertEquals(File("/xdg", "trackhop"), DesktopApp.dataDir(mapOf("XDG_DATA_HOME" to "/xdg"), "Linux", "/home/me"))
        assertEquals(File("/Users/me", "Library/Application Support/Trackhop"), DesktopApp.dataDir(emptyMap(), "Mac OS X", "/Users/me"))
    }

    @Test fun theStorePackageLeavesStartingWithTheComputerToWindows() {
        assertTrue(Autostart.storePackage("C:\\Program Files\\WindowsApps\\xlollx.TrackhopDesktop_1.5.2.0_x64__abc\\Trackhop.exe"))
        assertFalse(Autostart.storePackage("C:\\Users\\me\\AppData\\Local\\Trackhop\\Trackhop.exe"))
        assertFalse(Autostart.storePackage("/opt/trackhop/bin/Trackhop"))
    }

    @Test fun theStorePackageOffersOnlyTheOfficialConnections() {
        installServerConnectors(officialOnly = true)
        val ids = com.xlollx.songport.providers.Providers.services().map { it.id }
        assertTrue(ids.containsAll(listOf("spotify", "tidal", "subsonic", "jellyfin", "plex", "csv")))
        // Link sources are unofficial routes, but MusicBrainz's: its official API, in every package.
        assertTrue(com.xlollx.songport.providers.Providers.services().none { it is CookieConnector || (it.linkOnly && it.serviceId != "musicbrainz") || it.serviceId == "fortnite" })
        assertTrue(ids.contains("musicbrainz"))
        installServerConnectors()
        assertTrue(com.xlollx.songport.providers.Providers.services().any { it is CookieConnector })
    }

    @Test fun signInsComeBackToTheLoopbackAddressAndThereIsNoPassword() {
        val c = DesktopApp.config(mapOf("SONGPORT_PASSWORD" to "x", "SONGPORT_LOCALE" to "it"))
        assertEquals("http://127.0.0.1:${DesktopApp.PORT}", c.baseUrl)
        assertEquals(DesktopApp.PORT, c.port)
        assertNull(c.password)
        assertEquals("it", c.locale)
    }

    /** A raw request, so the Host header is whatever the test says (as a rebinding page would send). */
    private fun request(port: Int, method: String, path: String, host: String, extra: String = ""): Int =
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $host\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }

    @Test fun answersOnlyRequestsAddressedToTheLoopbackAndQuitsFromThePage() {
        installServerConnectors()
        val env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1:0")
        val quit = CountDownLatch(1)
        val server = Web(env, Runner(env), Auth(env), null, "test", Html.Edition.DESKTOP, onQuit = { quit.countDown() }).start(0, loopbackOnly = true)
        try {
            val port = server.address.port
            assertTrue(server.address.address.isLoopbackAddress)
            assertEquals(200, request(port, "GET", "/", "127.0.0.1:$port"))
            assertEquals(200, request(port, "GET", "/", "localhost:$port"))
            assertEquals(403, request(port, "GET", "/", "evil.example:$port"))
            assertEquals(403, request(port, "GET", "/health", "evil.example"))
            // Quitting is a POST from the page itself.
            assertEquals(404, request(port, "GET", "/quit", "127.0.0.1:$port"))
            assertEquals(403, request(port, "POST", "/quit", "127.0.0.1:$port", "Origin: http://evil.example\r\n"))
            assertFalse(quit.await(500, TimeUnit.MILLISECONDS))
            assertEquals(200, request(port, "POST", "/quit", "127.0.0.1:$port", "Origin: http://127.0.0.1:$port\r\n"))
            assertTrue(quit.await(5, TimeUnit.SECONDS))
        } finally {
            server.stop(0)
        }
    }
}

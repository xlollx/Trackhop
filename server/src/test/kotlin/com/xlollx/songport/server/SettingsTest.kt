package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

/** Settings: the look each person picks, and "a new version is out" against a stand-in for GitHub. */
class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var github: HttpServer
    private lateinit var env: ServerEnv
    private val asked = AtomicInteger()
    @Volatile private var releases = ""

    @Before fun start() {
        github = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        github.createContext("/") { ex ->
            asked.incrementAndGet()
            val bytes = releases.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        github.start()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://localhost")
        Updates.configure(env.dataDir, "0.7.0", Html.Edition.SERVER, emptyMap())
        Updates.api = "http://127.0.0.1:${github.address.port}/releases"
    }

    @After fun stop() {
        github.stop(0)
        Updates.reset()
    }

    private fun release(tag: String, draft: Boolean = false, pre: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/xlollx/Trackhop/releases/tag/$tag"}"""

    @Test fun versionsAreComparedByNumber() {
        assertTrue(Updates.newer("0.7.1", "0.7.0"))
        assertTrue(Updates.newer("0.10.0", "0.9.3"))
        assertTrue(Updates.newer("1.0", "0.9.9"))
        assertFalse(Updates.newer("0.7.0", "0.7.0"))
        assertFalse(Updates.newer("0.6.9", "0.7.0"))
    }

    @Test fun onlyThePublishedServerReleasesCount() = runBlocking {
        // The app's releases (v1.x) come first in GitHub's list and are newer by number: not ours. The
        // server's are all marked pre-release on GitHub (so the app's stay "latest"); a test version has "-rc".
        releases = "[" + listOf(release("v1.6.1"), release("server-v0.9.0", draft = true), release("server-v0.8.0-rc1", pre = true),
            release("server-v0.7.1", pre = true), release("server-v0.7.0", pre = true)).joinToString(",") + "]"
        assertTrue(Updates.check())
        assertEquals("0.7.1", Updates.available()?.version)
        assertEquals("https://github.com/xlollx/Trackhop/releases/tag/server-v0.7.1", Updates.available()?.url)
    }

    @Test fun twiceADayAtMostAndNotWhenTurnedOff() = runBlocking {
        releases = "[" + release("server-v0.7.0") + "]"
        Updates.checkIfDue()
        Updates.checkIfDue()
        assertEquals(1, asked.get())
        assertNull(Updates.available())
        Updates.checkIfDue(System.currentTimeMillis() + 13 * 3600_000L)
        assertEquals(2, asked.get())
        Updates.setEnabled(false)
        Updates.checkIfDue(System.currentTimeMillis() + 48 * 3600_000L)
        assertEquals(2, asked.get())
    }

    @Test fun theEnvironmentAndTheStoresTurnItOff() = runBlocking {
        Updates.configure(env.dataDir, "0.7.0", Html.Edition.SERVER, mapOf("SONGPORT_UPDATE_CHECK" to "false"))
        Updates.api = "http://127.0.0.1:${github.address.port}/releases"
        assertFalse(Updates.check())
        assertEquals(0, asked.get())
        assertEquals("the Microsoft Store", Updates.desktopUpdater("", "", emptyMap(), store = true))
        assertEquals("Scoop (scoop update trackhop)", Updates.desktopUpdater("C:\\Users\\me\\scoop\\apps\\trackhop\\current\\Trackhop.exe", "", emptyMap(), false))
        assertTrue(Updates.desktopUpdater("/opt/homebrew/Caskroom/x", "/opt/homebrew/Cellar/trackhop/0.7.0/libexec", emptyMap(), false)!!.startsWith("Homebrew"))
        assertTrue(Updates.desktopUpdater("/app/bin/Trackhop", "", mapOf("FLATPAK_ID" to "org.trackhop.Trackhop"), false)!!.startsWith("Flathub"))
        assertTrue(Updates.desktopUpdater("/snap/trackhop/12/Trackhop/bin/Trackhop", "", mapOf("SNAP_NAME" to "trackhop"), false)!!.contains("Snap Store"))
        assertNull(Updates.desktopUpdater("C:\\Users\\me\\AppData\\Local\\Trackhop\\Trackhop.exe", "", emptyMap(), false))
    }

    @Test fun thePagesWearTheChosenThemeAndTellAboutTheNewVersion() = runBlocking {
        releases = "[" + release("server-v0.7.1") + "]"
        Updates.check()
        val server = Web(env, Runner(env), Auth(env), null, "0.7.0 (engine 1.6.1)").start(0)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).build(), HttpResponse.BodyHandlers.ofString()).body()
            fun post(form: String) = client.send(
                HttpRequest.newBuilder(URI("$base/settings")).header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "http://localhost")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val home = get("/")
            assertFalse(home.contains("data-theme"))
            assertTrue(home, home.contains("0.7.1 is out"))
            assertTrue(get("/settings").contains("docker pull ghcr.io/xlollx/trackhop:latest"))

            assertEquals(303, post("action=theme&theme=dark").statusCode())
            assertTrue(get("/").contains("""<html lang="en" data-theme="dark">"""))
            post("action=theme&theme=nonsense")
            assertTrue(get("/").contains("""data-theme="dark""""))
            post("action=theme&theme=system")
            assertFalse(get("/").contains("data-theme"))

            // The home page's column: left by default, on the right, only some cards, or none.
            assertTrue(get("/").contains("""<div class="dash"><div class="dash-main">"""))
            assertTrue(get("/").contains("Last two weeks"))
            post("action=home&side=right&card=runs&card=services")
            val right = get("/")
            assertTrue(right.contains("dash side-right"))
            assertFalse(right.contains("Last two weeks"))
            post("action=home&side=hidden&card=activity")
            val hidden = get("/")
            assertFalse(hidden.contains("dash-side"))
            assertFalse(hidden.contains("""<main class="wide">"""))
            post("action=home&side=left&card=activity")
            assertTrue(get("/").contains("Last two weeks"))

            post("action=updates")
            assertFalse(get("/").contains("is out"))
            assertTrue(get("/style.css").contains(":root[data-theme=dark]{"))
        } finally {
            server.stop(0)
        }
    }
}

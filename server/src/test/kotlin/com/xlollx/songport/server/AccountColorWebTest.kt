package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** An account's colour on the web page: chosen on its page with a plain form, worn as a ring round its logo on the Services page. */
class AccountColorWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        env.store.updateSettings { it.copy(connectors = listOf("jellyfin", "emby"), connectorsInit = true) }
    }

    @After fun stop() = server.stop(0)

    private fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun post(path: String, body: String, origin: String = base) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", origin)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun HttpResponse<String>.location() = URLDecoder.decode(headers().firstValue("Location").orElse(""), Charsets.UTF_8)

    @Test fun theAccountPageOffersTheColoursAsRadioButtons() {
        val page = get("/connectors/jellyfin").body()
        assertTrue(page, page.contains("""action="/connectors/jellyfin/color""""))
        assertTrue(page.contains("""<input type="radio" name="color" value="none" checked"""))
        listOf("red", "amber", "green", "blue", "purple", "pink", "teal").forEach { assertTrue(it, page.contains("""name="color" value="$it"""")) }
        assertFalse(page.contains("ringed"))
    }

    @Test fun aColourPostedIsStoredAndWornOnTheServicesPage() {
        val saved = post("/connectors/jellyfin/color", "color=teal")
        assertEquals(303, saved.statusCode())
        assertEquals("/connectors/jellyfin?ok=Saved.", saved.location())
        assertEquals(mapOf("jellyfin" to "teal"), env.store.data.settings.connectorColors)
        val list = get("/connectors").body()
        assertTrue(list, Regex("""<span class="logo ringed" style="--s:40px;background:#[0-9A-F]{6};box-shadow:0 0 0 2\.5px var\(--surface\),0 0 0 5\.0px #12A594" role="img" aria-label="Jellyfin"""").containsMatchIn(list))
        // Only the account that has one: Emby wears none.
        assertEquals(1, Regex("logo ringed").findAll(list).count())
        assertTrue(get("/connectors/jellyfin").body().contains("""value="teal" checked"""))
        // None takes it off again; a key that is no colour changes nothing.
        assertTrue(post("/connectors/jellyfin/color", "color=chartreuse").location().contains("err="))
        assertEquals("teal", env.store.data.settings.connectorColors["jellyfin"])
        post("/connectors/jellyfin/color", "color=none")
        assertNull(env.store.data.settings.connectorColors["jellyfin"])
        assertFalse(get("/connectors").body().contains("ringed"))
    }

    @Test fun aPostFromAnotherSiteIsRefused() {
        val cross = post("/connectors/jellyfin/color", "color=red", origin = "http://evil.example")
        assertEquals(403, cross.statusCode())
        assertTrue(env.store.data.settings.connectorColors.isEmpty())
        // And a GET changes nothing.
        assertEquals(405, get("/connectors/jellyfin/color").statusCode())
    }
}

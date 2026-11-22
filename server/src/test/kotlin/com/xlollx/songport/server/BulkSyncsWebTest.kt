package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
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

/** The home page's Select: several syncs turned on, off or deleted at once, with plain links and forms. */
class BulkSyncsWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    private fun job(id: String, name: String, linked: String? = null, twin: Boolean = false) =
        SyncJob(id, name, PlaylistRef("spotify", "s$id", name), PlaylistRef("deezer", "d$id", name), linkedJobId = linked, twin = twin)

    @Before fun start() {
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        env.store.update { d -> d.copy(jobs = listOf(job("a", "Alpha"), job("b", "Bravo"), job("c", "Charlie"), job("p", "Pair", linked = "q"), job("q", "Pair ↔", linked = "p", twin = true))) }
    }

    @After fun stop() = server.stop(0)

    private fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun post(path: String, body: String, origin: String = base) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", origin)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun HttpResponse<String>.location() = URLDecoder.decode(headers().firstValue("Location").orElse(""), Charsets.UTF_8)
    private fun boxes(page: String) = Regex("""<input type="checkbox" name="job" value="([^"]+)" form="bulk-syncs"[^>]*>""").findAll(page).toList()

    @Test fun selectModeRendersCheckboxesAndSelectAllTicksThemAll() {
        val home = get("/").body()
        assertTrue(home, home.contains("5 syncs · <a href=\"/?select=1\">Select</a>"))
        assertTrue(boxes(home).isEmpty())
        val select = get("/?select=1").body()
        assertEquals(setOf("a", "b", "c", "p", "q"), boxes(select).map { it.groupValues[1] }.toSet())
        assertTrue(boxes(select).none { it.value.contains("checked") })
        assertTrue(select.contains("href=\"/?select=1&amp;all=1\">Select all</a>") && select.contains("href=\"/\">Done</a>"))
        assertTrue(select.contains("action=\"/syncs/bulk\"") && select.contains(">Run now<") && select.contains(">Turn on<") && select.contains(">Turn off<") && select.contains(">Delete…<"))
        val all = get("/?select=1&all=1").body()
        assertEquals(5, boxes(all).count { it.value.contains("checked") })
    }

    @Test fun turnOffAndOnChangeExactlyTheTickedSyncs() {
        val off = post("/syncs/bulk", "action=off&job=a&job=c")
        assertEquals(303, off.statusCode())
        assertEquals("/?ok=2 syncs turned off", off.location())
        assertEquals(mapOf("a" to false, "b" to true, "c" to false, "p" to true, "q" to true), env.store.data.jobs.associate { it.id to it.enabled })
        // Turned on here: scheduled here from now on, as a sync's own toggle.
        env.store.update { d -> d.copy(jobs = d.jobs.map { if (it.id == "a") it.copy(scheduledOn = "Phone") else it }) }
        val on = post("/syncs/bulk", "action=on&job=a")
        assertEquals("/?ok=1 sync turned on", on.location())
        assertEquals(mapOf("a" to true, "b" to true, "c" to false, "p" to true, "q" to true), env.store.data.jobs.associate { it.id to it.enabled })
        assertNull(env.store.job("a")!!.scheduledOn)
        // A two-way pair goes off together, as with its own toggle.
        post("/syncs/bulk", "action=off&job=p")
        assertFalse(env.store.job("q")!!.enabled)
        // Nothing ticked: back to the selection, nothing changed.
        assertTrue(post("/syncs/bulk", "action=off").location().startsWith("/?select=1&err="))
    }

    @Test fun deleteAsksFirstThenDeletesTheTickedOnesAndTheirOtherHalves() {
        val ask = post("/syncs/bulk", "action=delete&job=a&job=p")
        assertEquals(200, ask.statusCode())
        val page = ask.body()
        assertTrue(page, page.contains("Delete 3 syncs?") && page.contains("<li>Alpha</li>") && page.contains("<li>Pair</li>") && page.contains("<li>Pair ↔</li>"))
        assertFalse(page.contains("<li>Bravo</li>"))
        assertTrue(page.contains("The playlists on the services stay as they are; only the syncs go."))
        assertTrue(page.contains(">Delete 3 syncs</button>") && page.contains("href=\"/?select=1\">Cancel</a>"))
        assertEquals(5, env.store.data.jobs.size)
        val done = post("/syncs/bulk", "action=delete-confirmed&job=a&job=p&job=q")
        assertEquals(303, done.statusCode())
        assertEquals("/?ok=3 syncs deleted", done.location())
        assertEquals(listOf("b", "c"), env.store.data.jobs.map { it.id })
    }

    @Test fun aPostFromAnotherSiteIsRefused() {
        assertEquals(403, post("/syncs/bulk", "action=delete-confirmed&job=a&job=b", origin = "http://evil.example").statusCode())
        assertEquals(403, post("/syncs/bulk", "action=off&job=a", origin = "null").statusCode())
        assertEquals(5, env.store.data.jobs.size)
        assertTrue(env.store.data.jobs.all { it.enabled })
    }
}

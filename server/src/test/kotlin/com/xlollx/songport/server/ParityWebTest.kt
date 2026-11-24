package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** The pages of the app's features brought to Desktop and Docker in 2.2: plain forms and links, no JavaScript. */
class ParityWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\nOther Song,Other Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\n")
    }

    @After fun stop() { server.stop(0); Features.v2 = Features.START }

    private fun get(path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, vararg fields: Pair<String, String>) =
        client.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Content-Type", "application/x-www-form-urlencoded").header("Origin", base)
                .POST(HttpRequest.BodyPublishers.ofString(fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" })).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun location(r: HttpResponse<String>) = r.headers().firstValue("Location").orElse("")

    /** The page a background task ends on, once it is done. */
    private fun taskPage(path: String): String {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val body = get(path).body()
            if (!body.contains("<progress")) return body
            Thread.sleep(100)
        }
        throw AssertionError("$path never finished")
    }

    private fun sync(id: String = "s1") = SyncJob(id, "Mine", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out")).also { env.store.upsertJob(it) }

    @Test fun aSyncsPageSetsItsMatchingAndKeepsTheOrder() {
        sync()
        val page = get("/syncs/s1").body()
        assertTrue(page.contains("How close a match must be"))
        assertTrue(page.contains("name=\"keepOrder\""))
        val saved = post("/syncs/s1/schedule", "name" to "Mine", "every" to "1440", "at" to "09:00", "changes" to "add",
            "matching" to "1", "strictness" to "1", "explicit" to "-1", "studioOnly" to "on", "keepOrder" to "on")
        assertEquals(303, saved.statusCode())
        val job = env.store.job("s1")!!
        assertEquals(1, job.policy.strictness)
        assertEquals(-1, job.policy.explicit)
        assertTrue(job.policy.studioOnly && !job.policy.sameAlbum && job.keepOrder)
        // A form without the part (an older page) leaves them as they are.
        post("/syncs/s1/schedule", "name" to "Mine", "every" to "1440", "changes" to "add")
        assertTrue(env.store.job("s1")!!.keepOrder)
    }

    @Test fun thePreviewSaysWhatARunWouldDoAndChangesNothing() {
        sync()
        assertTrue(get("/syncs/s1").body().contains("/syncs/s1/preview"))
        val started = post("/syncs/s1/preview")
        assertEquals(303, started.statusCode())
        assertTrue(location(started), location(started).startsWith("/tools/task/"))
        val result = taskPage(location(started))
        assertTrue(result, result.contains("Will add (2)"))
        assertTrue(result, result.contains("Nothing was changed"))
        assertEquals("title,artist\n", File(data, "csv/Out.csv").readText())
    }

    @Test fun theServicesAreFoundAndFilteredAsInTheApp() {
        for (s in listOf("jellyfin", "subsonic", "deezer_bridge", "csv")) post("/connectors/add", "service" to s)
        val all = get("/connectors").body()
        assertTrue(all.contains("Find a service"))
        val web = get("/connectors?kind=web").body()
        assertTrue(web, web.contains("/connectors/deezer_bridge") && !web.contains("/connectors/jellyfin\""))
        val official = get("/connectors?kind=official").body()
        assertTrue(!official.contains("/connectors/deezer_bridge\"") && official.contains("/connectors/jellyfin\""))
        val found = get("/connectors?q=jelly").body()
        assertTrue(found.contains("/connectors/jellyfin\"") && !found.contains("/connectors/subsonic\""))
        assertTrue(get("/connectors?q=nothing-like-it").body().contains("No service matches"))
    }
}

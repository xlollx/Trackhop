package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** Tools › Saved matches on the server (1.18): hidden with the release switch off; on, the list, its filters and Forget. */
class SavedMatchesWebTest {
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
        val job = SyncJob("j", "Road Trip", PlaylistRef("spotify", "p", "Road Trip"), PlaylistRef("deezer", "d", "Road Trip"))
        val src = Track(id = "s1", title = "Paper Lanterns", artists = listOf("The Velvet Orchard"))
        env.store.update { d ->
            d.copy(
                jobs = listOf(job),
                reports = listOf(SyncReport(id = "r", jobId = "j", jobName = "Road Trip", startedEpoch = 1, durationMs = 1, unmatchedTracks = listOf(src))),
                matchCache = mapOf("spotify:s2>deezer" to "d2"),
                ignoredTracks = setOf("spotify:s1>deezer"),
            )
        }
    }

    @After fun stop() {
        Features.v2 = false
        server.stop(0)
    }

    private fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun post(path: String, body: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", base)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test fun hiddenWhileTheReleaseSwitchIsOff() {
        assertEquals(404, get("/tools/matches").statusCode())
        assertFalse(get("/tools").body().contains("/tools/matches"))
    }

    @Test fun theListNamesWhatTheReportsKnowAndForgetLeavesNothing() {
        Features.v2 = true
        assertTrue(get("/tools").body().contains("/tools/matches"))
        val page = get("/tools/matches").body()
        assertTrue(page, page.contains("Paper Lanterns") && page.contains("Ignored") && page.contains("Automatic"))
        assertFalse(get("/tools/matches?kind=IGNORED").body().contains("s2"))
        assertTrue(get("/tools/matches?q=velvet").body().contains("Paper Lanterns"))
        assertEquals(303, post("/tools/matches", "key=spotify%3As1%3Edeezer").statusCode())
        assertTrue(env.store.data.ignoredTracks.isEmpty())
        assertEquals(mapOf("spotify:s2>deezer" to "d2"), env.store.data.matchCache)
    }

    @Test fun forgetTheTickedOnesForgetsOnlyThose() {
        Features.v2 = true
        env.store.update { d -> d.copy(matchCache = d.matchCache + ("spotify:s3>deezer" to "d3")) }
        fun boxes(page: String) = Regex("""<input type="checkbox" name="key" value="([^"]+)" form="forget-matches"[^>]*>""").findAll(page).toList()
        val page = get("/tools/matches").body()
        assertTrue(page, page.contains(">Select all</a> · <a href=\"/tools/matches\">None</a>") && page.contains(">Forget the ticked ones</button>"))
        assertEquals(3, boxes(page).size)
        assertTrue(boxes(page).none { it.value.contains("checked") })
        assertEquals(3, boxes(get("/tools/matches?all=1").body()).count { it.value.contains("checked") })
        // The filters stay in Select all's link.
        assertTrue(get("/tools/matches?q=velvet").body().contains("href=\"/tools/matches?q=velvet&amp;all=1\""))
        val done = post("/tools/matches", "bulk=1&key=spotify%3As1%3Edeezer&key=spotify%3As3%3Edeezer")
        assertEquals(303, done.statusCode())
        assertTrue(done.headers().firstValue("Location").orElse("").contains("ok=2+matches+forgotten"))
        assertTrue(env.store.data.ignoredTracks.isEmpty())
        assertEquals(mapOf("spotify:s2>deezer" to "d2"), env.store.data.matchCache)
    }

    @Test fun forgetTheTickedOnesFromAnotherSiteIsRefused() {
        Features.v2 = true
        val refused = client.send(
            HttpRequest.newBuilder(URI("$base/tools/matches")).header("Authorization", auth).header("Origin", "http://evil.example")
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("bulk=1&key=spotify%3As2%3Edeezer")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, refused.statusCode())
        assertEquals(mapOf("spotify:s2>deezer" to "d2"), env.store.data.matchCache)
    }
}

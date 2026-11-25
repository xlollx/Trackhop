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
        Features.v2 = true
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

    @Test fun theWeeklyUnplayableCheckIsTurnedOnFromItsCard() {
        assertEquals(303, post("/tools/unplayable", "action" to "weekly", "weekly" to "on").statusCode())
        assertTrue(env.store.data.settings.unplayableCheck)
        assertEquals(303, post("/tools/unplayable", "action" to "weekly").statusCode())
        assertTrue(!env.store.data.settings.unplayableCheck)
        val now = post("/tools/unplayable", "action" to "weekly", "now" to "1")
        assertTrue(location(now).startsWith("/tools/task/"))
        assertTrue(taskPage(location(now)).contains("No connected service can tell"))
    }

    @Test fun toFixIsAPageWithBoxesAndButtons() {
        sync()
        assertTrue(get("/tofix").body().contains("Nothing to fix"))
        val missing = com.xlollx.songport.model.Track("x1", "Lost Song", listOf("Nobody"))
        env.store.addReport(com.xlollx.songport.model.SyncReport("r1", "s1", "Mine", 1_780_000_000_000, 10, unmatched = listOf(missing.toString()), unmatchedTracks = listOf(missing)))
        assertTrue(get("/").body().contains("To fix (1)"))
        val page = get("/tofix").body()
        assertTrue(page.contains("Lost Song") && page.contains("form=\"tofix\"") && page.contains("Choose another song"))
        val key = com.xlollx.songport.sync.ToFix.items(env.store.data).single().key
        assertEquals(303, post("/tofix", "action" to "later", "key" to key).statusCode())
        assertTrue(get("/tofix").body().contains("Later (1)"))
        assertTrue(location(post("/tofix", "action" to "ignore")).contains("err="))
        assertEquals(303, post("/tofix", "action" to "back", "key" to key).statusCode())
        assertEquals(303, post("/tofix", "action" to "ignore", "key" to key).statusCode())
        assertTrue(get("/tofix").body().contains("Nothing to fix"))
    }

    @Test fun savedSchedulesOnTheirPageAndOnASyncsPage() {
        sync(); sync("s2")
        assertTrue(get("/settings").body().contains("/settings/schedules"))
        assertEquals(303, post("/settings/schedules", "name" to "Weekend", "every" to "1440", "at" to "10:00", "day" to "6", "day" to "7").statusCode())
        val s = env.store.data.settings.savedSchedules.single()
        assertEquals(listOf(6, 7), s.days)
        // A sync's page offers it; picked, the sync follows it.
        assertTrue(get("/syncs/s1").body().contains("name=\"scheduleId\""))
        post("/syncs/s1/schedule", "name" to "Mine", "every" to "60", "changes" to "add", "scheduleId" to s.id)
        assertEquals(s.id, env.store.job("s1")!!.scheduleId)
        assertEquals("10:00", env.store.job("s1")!!.at)
        assertTrue(get("/settings/schedules").body().contains("1 sync"))
        // Several syncs from Select › Edit.
        assertTrue(post("/syncs/bulk", "action" to "edit", "job" to "s1", "job" to "s2").body().contains("Apply to 2 syncs"))
        assertEquals(303, post("/syncs/bulk", "action" to "edit-apply", "job" to "s1", "job" to "s2", "when" to "saved:${s.id}", "pick" to "teal", "changes" to "mirror").statusCode())
        assertEquals(s.id, env.store.job("s2")!!.scheduleId)
        assertTrue(env.store.job("s2")!!.mirrorRemovals)
        assertEquals("teal", env.store.job("s1")!!.label)
        // Deleted: the syncs keep the timing.
        post("/settings/schedules", "action" to "delete", "id" to s.id)
        assertEquals(null, env.store.job("s1")!!.scheduleId)
        assertEquals("10:00", env.store.job("s1")!!.at)
    }

    @Test fun recentTransfersAndASetlistLink() {
        assertTrue(get("/tools/transfers").body().contains("No transfers"))
        env.store.upsertJob(com.xlollx.songport.sync.Setlists.job("t1", "Muse at Forum", "In", PlaylistRef("csv", null, "")))
        assertTrue(get("/tools").body().contains("/tools/transfers"))
        assertTrue(get("/tools/transfers").body().contains("Muse at Forum"))
        // Not on the home page: it is not a sync.
        assertTrue(!get("/").body().contains("Muse at Forum"))
        assertEquals(303, post("/tools/transfers", "action" to "delete", "id" to "t1").statusCode())
        assertEquals(null, env.store.job("t1"))
        Features.v2 = true
        assertTrue(get("/tools/setlists").body().contains("A setlist.fm link"))
        assertTrue(location(get("/tools/setlists?link=https://example.org/nothing")).contains("err="))
    }

    @Test fun theNewToolsPages() {
        env.store.updateSettings { it.copy(connectors = listOf("csv")) }
        val tools = get("/tools").body()
        for (t in listOf("/tools/scan", "/tools/twins")) assertTrue(t, tools.contains(t))
        val scan = post("/tools/scan", "service" to "csv")
        assertTrue(location(scan), location(scan).startsWith("/tools/task/") || location(scan).contains("err="))
        assertTrue(get("/tools/twins").body().contains("Playlist twins"))
        assertTrue(location(post("/tools/twins", "service" to "csv", "action" to "delete")).contains("err="))
        // New playlist from one: on the playlist's page.
        assertTrue(get("/playlists/csv/In").body().contains("New playlist from this one"))
        val made = post("/playlists/csv/In", "action" to "make", "op" to "shuffle", "name" to "Mixed")
        assertTrue(location(made).startsWith("/tools/task/"))
        taskPage(location(made))
        assertTrue(File(data, "csv/Mixed.csv").isFile)
    }

    @Test fun aScanPageSavesItsOwnScanNotALaterOne() {
        env.store.updateSettings { it.copy(connectors = listOf("csv")) }
        File(data, "csv/B.csv").writeText("title,artist\nSong,Band\n")
        val first = taskPage(location(post("/tools/scan", "service" to "csv")))
        val scan = Regex("name=\"scan\" value=\"([^\"]+)\"").find(first)!!.groupValues[1]
        taskPage(location(post("/tools/scan", "service" to "csv")))
        assertTrue(location(post("/tools/scan", "service" to "csv", "action" to "save", "list" to "repeats", "scan" to scan)).contains("err="))
        val latest = taskPage(location(post("/tools/scan", "service" to "csv")))
        val now = Regex("name=\"scan\" value=\"([^\"]+)\"").find(latest)!!.groupValues[1]
        assertTrue(location(post("/tools/scan", "service" to "csv", "action" to "save", "list" to "repeats", "scan" to now)).contains("ok="))
    }

    @Test fun aPlaylistPastedOnTheFilePage() {
        assertTrue(get("/connectors/csv").body().contains("Paste a playlist"))
        val r = post("/files/text", "name" to "Pasted", "text" to "Moderat - A New Error\nBonobo - Kerala")
        assertTrue(location(r), location(r).contains("ok="))
        assertTrue(File(data, "csv/Pasted.csv").readText().contains("Moderat"))
        assertTrue(location(post("/files/text", "name" to "X", "text" to "")).contains("err="))
    }

    @Test fun theArtistsOfADiscoverSearchArePicked() {
        Features.v2 = true
        val page = get("/tools/concerts?service=csv&playlist=In&pick=1").body()
        assertTrue(page, page.contains("value=\"Band\"") && page.contains("value=\"Other Band\""))
        val narrowed = get("/tools/concerts?service=csv&playlist=In&pick=1&find=oth").body()
        assertTrue(!narrowed.contains("value=\"Band\"") && narrowed.contains("value=\"Other Band\""))
        assertTrue(get("/tools/concerts?service=csv&playlist=In&pick=1&find=Muse").body().contains("Add “Muse”"))
        assertEquals(setOf("Band", "Muse"), ArtistPicks.of(listOf("Band"), " Muse ,"))
        assertEquals(null, ArtistPicks.of(emptyList(), ""))
        val tracks = listOf(com.xlollx.songport.model.Track("1", "S", listOf("Björk")), com.xlollx.songport.model.Track("2", "T", listOf("Other")))
        assertEquals(listOf("Björk", "Muse"), ArtistPicks.chosen(tracks, setOf("bjork", "Muse")))
    }
}

package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** The web page's own checks, through a real server on a free port: password, cross-site posts, methods, ids. */
class WebTest {
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
    }

    @After fun stop() = server.stop(0)

    private fun get(path: String, withAuth: Boolean = true) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).apply { if (withAuth) header("Authorization", auth) }.GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String, vararg headers: String) =
        client.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Content-Type", "application/x-www-form-urlencoded")
                .apply { if (headers.isNotEmpty()) headers(*headers) }.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private val origin get() = arrayOf("Origin", base)

    @Test fun everyPageAsksForThePassword() {
        assertEquals(401, get("/", withAuth = false).statusCode())
        assertEquals(401, get("/log", withAuth = false).statusCode())
        assertEquals(200, get("/health", withAuth = false).statusCode())
        assertEquals(200, get("/").statusCode())
    }

    @Test fun withSongportUserTheNameCountsToo() {
        val named = Web(env, Runner(env), Auth(env), "sekret", "test", user = "Lorenzo").start(0)
        try {
            fun as_(user: String, pw: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${named.address.port}/"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$pw".toByteArray())).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()
            assertEquals(200, as_("lorenzo", "sekret"))
            assertEquals(401, as_("any", "sekret"))
            assertEquals(401, as_("lorenzo", "wrong"))
        } finally {
            named.stop(0)
        }
    }

        @Test fun theInstallableAppsFilesNeedNoPassword() {
        val manifest = get("/manifest.webmanifest", withAuth = false)
        assertEquals(200, manifest.statusCode())
        assertTrue(manifest.body().contains("\"display\": \"standalone\""))
        assertEquals(200, get("/sw.js", withAuth = false).statusCode())
        assertEquals(200, get("/icon-512.png", withAuth = false).statusCode())
        assertTrue(get("/").body().contains("rel=\"manifest\""))
        // Only those: the pages still ask.
        assertEquals(401, get("/icon-512.png/../log", withAuth = false).statusCode())
    }

    @Test fun ownFormsWorkInBrowsers() {
        // "no-referrer" makes browsers send "Origin: null" on the page's own forms, which is refused.
        assertEquals("same-origin", get("/").headers().firstValue("Referrer-Policy").orElse(""))
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
    }

    @Test fun crossSitePostsAreRefused() {
        assertEquals(403, post("/connectors/add", "service=csv", "Origin", "http://evil.example").statusCode())
        assertEquals(403, post("/connectors/add", "service=csv", "Origin", "null").statusCode())
        assertEquals(403, post("/connectors/add", "service=csv", "Sec-Fetch-Site", "cross-site").statusCode())
        assertEquals(403, post("/connectors/add", "service=csv", "Referer", "http://evil.example/page").statusCode())
        assertFalse(get("/connectors").body().contains("href=\"/connectors/csv\""))
        // Behind a reverse proxy that rewrites Host: the address in SONGPORT_URL.
        assertEquals(303, post("/connectors/add", "service=csv", "Origin", "https://songport.example.org").statusCode())
    }

    @Test fun sameOriginRules() {
        fun check(vararg h: Pair<String, String>) = sameOrigin({ name -> h.firstOrNull { it.first.equals(name, true) }?.second }, "https://songport.example.org")
        assertTrue(check("Host" to "nas:8080", "Origin" to "http://nas:8080"))
        assertTrue(check("Host" to "nas:8080", "Origin" to "https://songport.example.org"))
        assertFalse(check("Host" to "nas:8080", "Origin" to "http://nas:8080.evil.example"))
        assertFalse(check("Host" to "nas:8080", "Origin" to "null"))
        assertTrue(check("Host" to "nas:8080", "Sec-Fetch-Site" to "same-origin"))
        assertFalse(check("Host" to "nas:8080", "Sec-Fetch-Site" to "same-site"))
        assertTrue(check("Host" to "nas:8080", "Referer" to "http://nas:8080/syncs/x"))
        assertFalse(check("Host" to "nas:8080", "Referer" to "http://evil.example/nas:8080"))
        // No browser header at all (curl, a script): not a cross-site form; the password still applies.
        assertTrue(check("Host" to "nas:8080"))
    }

    @Test fun actionsNeedAPost() {
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        for (action in listOf("remove", "disconnect", "connect", "settings")) {
            assertEquals(action, 405, get("/connectors/csv/$action").statusCode())
        }
        assertTrue(get("/connectors").body().contains("href=\"/connectors/csv\""))
        assertEquals(303, post("/connectors/csv/remove", "", *origin).statusCode())
        assertFalse(get("/connectors").body().contains("href=\"/connectors/csv\""))
    }

    @Test fun aSyncOnlyTakesListedPlaylists() {
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        File(data, "csv").mkdirs()
        File(data, "csv/mine.csv").writeText("title,artist\nSong,Someone\n")
        // A playlist id that walks out of the CSV folder, on either side.
        val out = post("/syncs", "src=csv&dst=csv&srcPlaylist=mine&dstPlaylist=..%2F..%2Fescape&schedule=MANUAL", *origin)
        assertTrue(out.headers().firstValue("Location").orElse(""), out.headers().firstValue("Location").orElse("").contains("err="))
        val inside = post("/syncs", "src=csv&dst=csv&srcPlaylist=..%2Fsecret&schedule=MANUAL", *origin)
        assertTrue(inside.headers().firstValue("Location").orElse("").contains("err="))
        assertTrue(get("/").body().contains("No syncs yet."))
        // The listed one, to a new playlist: created.
        val ok = post("/syncs", "src=csv&dst=csv&srcPlaylist=mine&dstPlaylist=&name=Mine&schedule=MANUAL", *origin)
        assertTrue(ok.headers().firstValue("Location").orElse(""), ok.headers().firstValue("Location").orElse("").startsWith("/syncs/"))
        assertTrue(get("/").body().contains("Mine"))
    }

    @Test fun playlistFilesDownloadAndNothingElse() {
        File(data, "csv").mkdirs()
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong,Band\n")
        File(data, "secret.csv").writeText("no")
        val ok = get("/files/Road%20trip.csv")
        assertEquals(200, ok.statusCode())
        assertTrue(ok.body().contains("Song,Band"))
        assertTrue(ok.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"))
        assertTrue(get("/connectors/csv").body().contains("/files/Road+trip.csv"))
        assertEquals(404, get("/files/..%2Fsecret.csv").statusCode())
        assertEquals(404, get("/files/tokens.json").statusCode())
        assertEquals(401, get("/files/Road%20trip.csv", withAuth = false).statusCode())
        // A sync that writes to File offers its CSV on its card.
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("j1", "Trip", com.xlollx.songport.model.PlaylistRef("subsonic", "p", "Trip"), com.xlollx.songport.model.PlaylistRef("csv", "Road trip", "Road trip")))
        assertTrue(get("/").body().contains("Download CSV"))
    }

    @Test fun syncsMoveUpAndDownTheList() {
        fun job(id: String) = com.xlollx.songport.model.SyncJob(id, id, com.xlollx.songport.model.PlaylistRef("subsonic", id, id), com.xlollx.songport.model.PlaylistRef("csv", id, id))
        listOf("a", "b", "c").forEach { env.store.upsertJob(job(it)) }
        assertTrue(post("/syncs/c/move", "to=up", *origin).statusCode() in 300..399)
        assertEquals(listOf("a", "c", "b"), env.store.data.jobs.map { it.id })
        post("/syncs/a/move", "to=down", *origin)
        assertEquals(listOf("c", "a", "b"), env.store.data.jobs.map { it.id })
        post("/syncs/b/move", "to=top", *origin)
        assertEquals(listOf("b", "c", "a"), env.store.data.jobs.map { it.id })
        // A link can't move anything: a GET only shows the sync.
        get("/syncs/a/move")
        assertEquals(listOf("b", "c", "a"), env.store.data.jobs.map { it.id })
    }

    @Test fun reviewPicksIgnoresAndConfirms() {
        File(data, "csv").mkdirs()
        File(data, "csv/Out.csv").writeText("title,artist\n")
        val job = com.xlollx.songport.model.SyncJob("j2", "Mix", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out"))
        env.store.upsertJob(job)
        val a = com.xlollx.songport.model.Track(id = "a", title = "Alpha", artists = listOf("One"))
        val b = com.xlollx.songport.model.Track(id = "b", title = "Beta", artists = listOf("Two"))
        val c = com.xlollx.songport.model.Track(id = "c", title = "Gamma", artists = listOf("Three"))
        env.store.addReport(
            com.xlollx.songport.model.SyncReport(
                id = "r1", jobId = "j2", jobName = "Mix", startedEpoch = 1, durationMs = 1,
                unmatched = listOf(a.toString(), b.toString()), unmatchedTracks = listOf(a, b),
                reviewTracks = listOf(com.xlollx.songport.model.MatchReview(c, c.copy(id = "c2"), 0.7)),
            ),
        )
        val page = get("/syncs/j2/review/r1")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Alpha") && page.body().contains("Gamma"))
        assertTrue(get("/syncs/j2").body().contains("/syncs/j2/review/r1"))
        // Pick a track for Alpha: it goes into the destination and leaves the list.
        val chosen = java.net.URLEncoder.encode("""{"id":"x1","title":"Alpha","artists":["One"]}""", "UTF-8")
        assertEquals(303, post("/syncs/j2/review/r1/resolve", "track=a&chosen=$chosen", *origin).statusCode())
        assertTrue(File(data, "csv/Out.csv").readText().contains("Alpha"))
        // Ignore Beta; keep Gamma's match.
        assertEquals(303, post("/syncs/j2/review/r1/ignore", "track=b", *origin).statusCode())
        assertEquals(303, post("/syncs/j2/review/r1/confirm", "track=c", *origin).statusCode())
        val rep = env.store.data.reports.first { it.id == "r1" }
        assertTrue(rep.unmatchedTracks.isEmpty() && rep.reviewTracks.isEmpty())
        assertTrue("b" in env.store.job("j2")!!.ignoredSourceIds)
        assertTrue(get("/syncs/j2/review/r1").body().contains("Nothing left to review"))
        // Another sync's run is not reachable through this one.
        assertEquals(404, get("/syncs/j1/review/r1").statusCode())
    }

    @Test fun tickedRowsAreKeptRemovedOrIgnoredTogether() {
        File(data, "csv").mkdirs()
        File(data, "csv/Out.csv").writeText("title,artist\n")
        val job = com.xlollx.songport.model.SyncJob("j3", "Mix", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out"))
        env.store.upsertJob(job)
        fun t(id: String) = com.xlollx.songport.model.Track(id = id, title = "Song $id", artists = listOf("Artist $id"))
        fun m(id: String) = com.xlollx.songport.model.MatchReview(t(id), t(id + "2"), 0.7)
        env.store.addReport(
            com.xlollx.songport.model.SyncReport(
                id = "r3", jobId = "j3", jobName = "Mix", startedEpoch = 1, durationMs = 1,
                unmatched = listOf("a", "b", "c").map { t(it).toString() }, unmatchedTracks = listOf(t("a"), t("b"), t("c")),
                reviewTracks = listOf(m("d"), m("e"), m("f"), m("g")),
            ),
        )
        val page = get("/syncs/j3/review/r3").body()
        assertTrue(page.contains("Tick all") && page.contains("Remove the ticked") && page.contains("Ignore the ticked"))
        assertEquals(303, post("/syncs/j3/review/r3/ignore-many", "pick=a&pick=c", *origin).statusCode())
        assertEquals(303, post("/syncs/j3/review/r3/keep-many", "pick=d&pick=e", *origin).statusCode())
        assertEquals(303, post("/syncs/j3/review/r3/discard-many", "pick=f", *origin).statusCode())
        assertEquals(303, post("/syncs/j3/review/r3/discard", "track=g", *origin).statusCode())
        val rep = env.store.data.reports.first { it.id == "r3" }
        assertEquals(listOf("b"), rep.unmatchedTracks.map { it.id })
        assertTrue(rep.reviewTracks.isEmpty())
        assertEquals(setOf("a", "c", "f", "g"), env.store.job("j3")!!.ignoredSourceIds.toSet())
        // Nothing ticked: said so, nothing changed.
        val none = post("/syncs/j3/review/r3/ignore-many", "", *origin)
        assertTrue(none.headers().firstValue("Location").orElse("").contains("err="))
    }

    @Test fun playlistFilesUploadInAnyFormatAndExportInOthers() {
        val boundary = "----trackhop"
        val m3u = "#EXTM3U\r\n#EXTINF:200,Band - Song\r\nBand - Song.mp3\r\n"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Città.m3u\"\r\nContent-Type: audio/x-mpegurl\r\n\r\n$m3u\r\n--$boundary--\r\n"
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/files")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(303, res.statusCode())
        assertTrue(res.headers().firstValue("Location").orElse("").contains("ok="))
        val csv = File(data, "csv/Città.csv")
        assertTrue(csv.exists() && csv.readText().contains("Song"))
        val xspf = get("/files/Citt%C3%A0.csv?format=xspf")
        assertEquals(200, xspf.statusCode())
        assertTrue(xspf.body().contains("<creator>Band</creator>"))
        assertTrue(get("/files/Citt%C3%A0.csv?format=txt").body().trim() == "Band - Song")
        assertEquals(303, post("/files/Citt%C3%A0.csv", "action=delete", *origin).statusCode())
        assertFalse(csv.exists())
        assertEquals(200, get("/tools").statusCode())
        assertEquals(200, get("/tools/export").statusCode())
    }

    @Test fun toolsFindAndRemoveDuplicatesInTheBackground() {
        File(data, "csv").mkdirs()
        File(data, "csv/Dup.csv").writeText("title,artist\nSong,Band\nOther,Band\nSong,Band\n")
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        val found = get("/tools/dedupe?service=csv&playlist=Dup")
        assertEquals(200, found.statusCode())
        assertTrue(found.body(), found.body().contains("more than once"))
        val started = post("/tools/dedupe", "service=csv&playlist=Dup", *origin)
        assertEquals(303, started.statusCode())
        val task = started.headers().firstValue("Location").get()
        assertTrue(task.startsWith("/tools/task/"))
        var page = ""
        repeat(50) { if (!page.contains("Removed")) { Thread.sleep(100); page = get(task).body() } }
        assertTrue(page, page.contains("Removed 1 extra copies"))
        assertEquals(2, File(data, "csv/Dup.csv").readLines().size - 1)
        // Backup settings: a folder that is not there is refused, a real one kept.
        assertTrue(post("/tools/backup", "action=settings&schedule=DAILY&folder=/no/such/dir", *origin).headers().firstValue("Location").get().contains("err="))
        val folder = tmp.newFolder("copies")
        assertEquals(303, post("/tools/backup", "action=settings&schedule=WEEKLY&folder=${java.net.URLEncoder.encode(folder.path, "UTF-8")}", *origin).statusCode())
        assertEquals(com.xlollx.songport.model.Schedule.WEEKLY, env.store.data.settings.backupSchedule)
        assertEquals("the file and the set-up", 2, Backups.copyToFolder(env))
        assertTrue(File(folder, "Dup.csv").exists())
        assertEquals(200, get("/tools/unplayable").statusCode())
        assertEquals(200, get("/tools").statusCode())
    }

    @Test fun aiPlaylistWithALocalOpenAiCompatibleModel() {
        // A stand-in for Ollama: whatever it is asked, three tracks.
        val ai = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        ai.createContext("/v1/chat/completions") { ex ->
            val content = "[{\\\"artist\\\":\\\"Band\\\",\\\"title\\\":\\\"One\\\"},{\\\"artist\\\":\\\"Band\\\",\\\"title\\\":\\\"Two\\\"},{\\\"artist\\\":\\\"Other\\\",\\\"title\\\":\\\"Three\\\"}]"
            val body = """{"choices":[{"message":{"content":"$content"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        ai.start()
        try {
            assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
            val base = java.net.URLEncoder.encode("http://127.0.0.1:${ai.address.port}/v1", "UTF-8")
            val saved = post("/tools/ai", "action=settings&vendor=CUSTOM&key=&model=local&base=$base", *origin)
            assertTrue(saved.headers().firstValue("Location").get(), saved.headers().firstValue("Location").get().contains("ok="))
            assertTrue(get("/tools/ai").body().contains("Write the playlist"))
            val started = post("/tools/ai", "action=generate&description=test&count=5&name=From+AI&target=csv", *origin)
            val task = started.headers().firstValue("Location").get()
            var page = ""
            repeat(80) { if (!page.contains("tracks.")) { Thread.sleep(100); page = get(task).body() } }
            assertTrue(page, page.contains("From AI") && page.contains("3 tracks"))
            assertTrue(File(data, "csv/From AI.csv").readText().contains("Three"))
        } finally {
            ai.stop(0)
        }
    }

    @Test fun theKeylessGeneratorIsThereWithoutAnAi() {
        File(data, "csv").mkdirs()
        File(data, "csv/Mine.csv").writeText("title,artist\nSong,Band\n")
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        val page = get("/tools/ai").body()
        assertTrue(page, page.contains("value=\"mix\"") && page.contains("name=\"artists\"") && page.contains("name=\"leaning\""))
        // A playlist of mine: its service first, then its playlists.
        val from = get("/tools/ai?mixfrom=csv").body()
        assertTrue(from, from.contains("name=\"playlist\"") && from.contains("Mine"))
        // Nothing to start from: said at once, no task started.
        val empty = post("/tools/ai", "action=mix&artists=&count=25&target=csv", *origin)
        assertTrue(empty.headers().firstValue("Location").get().contains("err="))
    }

    @Test fun playlistsCanBeSeenRenamedAndTrimmed() {
        File(data, "csv").mkdirs()
        File(data, "csv/Mix.csv").writeText("title,artist\nSong,Band\nOther,Band\n")
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        assertTrue(get("/playlists").body().contains("/playlists/csv"))
        assertTrue(get("/playlists/csv").body().contains("Mix"))
        val page = get("/playlists/csv/Mix").body()
        assertTrue(page, page.contains("Other") && page.contains("Rename"))
        val id = Regex("""name="t_([^"]+)"[^>]*> Band — <b>Other""").find(page)?.groupValues?.get(1) ?: error(page)
        assertEquals(303, post("/playlists/csv/Mix", "action=remove&t_${java.net.URLEncoder.encode(id, "UTF-8")}=on", *origin).statusCode())
        assertFalse(File(data, "csv/Mix.csv").readText().contains("Other"))
        assertEquals(303, post("/playlists/csv/Mix", "action=rename&name=Road", *origin).statusCode())
        assertTrue(File(data, "csv/Road.csv").exists())
        assertEquals(303, post("/playlists/csv/Road", "action=delete", *origin).statusCode())
        assertFalse(File(data, "csv/Road.csv").exists())
    }

    @Test fun backLeadsOneLevelUp() {
        assertEquals(null, Html.backTo(""))
        assertEquals(null, Html.backTo("tools"))
        assertEquals("/", Html.backTo("syncs/new"))
        assertEquals("/syncs/j1", Html.backTo("syncs/j1/review/r1"))
        assertEquals("/tools", Html.backTo("tools/backup"))
        assertEquals("/tools", Html.backTo("tools/task/abc"))
        assertEquals("/playlists/csv", Html.backTo("playlists/csv/Road trip"))
        assertTrue(get("/tools/backup").body().contains("""class="back" href="/tools""""))
        assertFalse(get("/").body().contains("class=\"back\""))
    }

    @Test fun apiNeedsItsTokenListsAndStartsSyncsAndCallsTheWebhook() {
        fun api(path: String, token: String?, method: String = "GET") = client.send(
            HttpRequest.newBuilder(URI("$base$path")).apply { if (token != null) header("Authorization", "Bearer $token") }
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, api("/api/v1/syncs", "x").statusCode())
        val made = post("/tools/api", "action=token", *origin).body()
        val token = Regex("""<pre>(th_[A-Za-z0-9_-]+)</pre>""").find(made)?.groupValues?.get(1) ?: error(made)
        assertEquals(401, api("/api/v1/syncs", null).statusCode())
        assertEquals(401, api("/api/v1/syncs", "th_wrong").statusCode())
        // The page's password is not asked for on the API, nor the token on the page.
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\n")
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("j9", "Api", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", null, "Out")))
        val list = api("/api/v1/syncs", token)
        assertEquals(200, list.statusCode())
        assertTrue(list.body(), list.body().contains("\"id\":\"j9\""))
        // A webhook to a local receiver: the run's end arrives there.
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        val hook = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        hook.createContext("/hook") { ex -> got.add(ex.requestHeaders.getFirst("X-Trackhop-Signature") + " " + String(ex.requestBody.readBytes())); ex.sendResponseHeaders(204, -1); ex.close() }
        hook.start()
        try {
            assertEquals(303, post("/tools/api", "action=webhook&url=${java.net.URLEncoder.encode("http://127.0.0.1:${hook.address.port}/hook", "UTF-8")}&secret=s3", *origin).statusCode())
            assertEquals(202, api("/api/v1/syncs/j9/run", token, "POST").statusCode())
            val event = got.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: error("no webhook")
            assertTrue(event, event.startsWith("sha256=") && event.contains("sync.finished") && event.contains("\"added\":1"))
        } finally {
            hook.stop(0)
        }
        assertTrue(api("/api/v1/syncs/j9", token).body().contains("\"runs\""))
        assertEquals(200, api("/api/v1/services", token).statusCode())
        // Off and on again from a script.
        assertTrue(api("/api/v1/syncs/j9/disable", token, "POST").body().contains("\"enabled\":false"))
        assertTrue(api("/api/v1/syncs/j9/enable", token, "POST").body().contains("\"enabled\":true"))
        // Prometheus metrics: one series per sync, labelled with its name.
        val metrics = api("/api/v1/metrics", token)
        assertTrue(metrics.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
        assertTrue(metrics.body(), metrics.body().contains("trackhop_syncs 1") && metrics.body().contains("trackhop_sync_last_added{id=\"j9\",name=\"Api\"} 1"))
        // The set-up as a .trackhop file.
        assertTrue(api("/api/v1/config", token).body().contains("\"format\": \"trackhop-config\""))
        // A playlist file pushed in the body, then listed.
        val pushed = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/files/Pushed.m3u")).header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString("#EXTM3U\n#EXTINF:200,Band - Song\nsong.mp3\n")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(pushed.body(), 201, pushed.statusCode())
        assertTrue(api("/api/v1/files", token).body().contains("Pushed"))
        // A full backup started from the API, followed through its task.
        val backup = api("/api/v1/backup", token, "POST")
        assertEquals(backup.body(), 202, backup.statusCode())
        val taskId = Regex(""""task":"([^"]+)"""").find(backup.body())!!.groupValues[1]
        assertTrue(api("/api/v1/tasks/$taskId", token).body().contains("\"title\":\"Backup\""))
        assertEquals(200, api("/api/v1/backup", token).statusCode())
        // Playlists and tracks on a service (File here): create, add by title/artist, list, remove, rename, delete.
        fun send(path: String, method: String, body: String) = client.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val created = send("/api/v1/services/csv/playlists", "POST", """{"name":"From API"}""")
        assertEquals(created.body(), 201, created.statusCode())
        val pid = Regex(""""id":"([^"]+)"""").find(created.body())!!.groupValues[1]
        val added = send("/api/v1/services/csv/playlists/${java.net.URLEncoder.encode(pid, "UTF-8").replace("+", "%20")}/tracks", "POST",
            """{"tracks":[{"title":"Song","artist":"Band"},"Other Band - Other Song"]}""")
        assertEquals(added.body(), 200, added.statusCode())
        assertTrue(added.body(), added.body().contains("\"title\":\"Song\"") && added.body().contains("Other Song"))
        val enc = java.net.URLEncoder.encode(pid, "UTF-8").replace("+", "%20")
        assertTrue(api("/api/v1/services/csv/playlists/$enc/tracks", token).body().contains("Other Song"))
        // Adding it again: already there, not twice.
        assertTrue(send("/api/v1/services/csv/playlists/$enc/tracks", "POST", """{"tracks":["Band - Song"]}""").body().contains("\"alreadyThere\":[\"Band - Song\"]"))
        val removed = send("/api/v1/services/csv/playlists/$enc/tracks", "DELETE", """{"tracks":[{"title":"Song","artist":"Band"}]}""")
        assertTrue(removed.body(), removed.body().contains("\"removed\":[{") && removed.body().contains("\"notFound\":[]"))
        assertFalse(api("/api/v1/services/csv/playlists/$enc/tracks", token).body().contains("\"title\":\"Song\""))
        assertEquals(200, send("/api/v1/services/csv/playlists/$enc", "DELETE", "").statusCode())
        assertEquals(404, api("/api/v1/services/nope/playlists", token).statusCode())
        assertEquals(404, api("/api/v1/nope", token).statusCode())
        assertEquals(303, post("/tools/api", "action=revoke", *origin).statusCode())
        assertEquals(403, api("/api/v1/syncs", token).statusCode())
    }

    @Test fun aRadioSyncIsMadeFromAStreamAndWearsTheRadioMark() {
        assertTrue(get("/syncs/radio").body().contains("Find a station"))
        val step = get("/syncs/radio?station=Test%20FM&stream=${java.net.URLEncoder.encode("http://127.0.0.1:9/stream", "UTF-8")}&dst=csv").body()
        assertTrue(step, step.contains("Start listening") && step.contains("name=\"day1\""))
        val made = post("/syncs/radio", "station=Test+FM&stream=http%3A%2F%2F127.0.0.1%3A9%2Fstream&dst=csv&dstPlaylist=&day1=on&day2=on&start=08%3A00&end=10%3A00", *origin)
        assertEquals(303, made.statusCode())
        val job = env.store.data.jobs.single { it.radio != null }
        assertEquals(listOf(1, 2), job.radio!!.days)
        assertEquals(8 * 60, job.radio!!.startMinute)
        assertEquals("Test FM radio", job.target.playlistName)
        val home = get("/").body()
        assertTrue(home.contains("aria-label=\"Radio sync\"") && home.contains("Mon, Tue 08:00–10:00") && home.contains("Radio: Test FM"))
        // Its hours can change on its page.
        assertEquals(303, post("/syncs/${job.id}/radio", "day6=on&day7=on&start=12%3A00&end=12%3A00", *origin).statusCode())
        assertEquals("Weekends, all day", com.xlollx.songport.sync.RadioTitles.describe(env.store.job(job.id)!!.radio!!))
        assertTrue(post("/syncs/${job.id}/radio", "start=12%3A00&end=13%3A00", *origin).headers().firstValue("Location").orElse("").contains("err="))
    }

    /** "Keep every track": chosen in the new sync's form and on its page, shown on its card, kept by a config backup. */
    @Test fun anArchiveSyncIsMadeShownChangedAndBackedUp() {
        assertEquals(303, post("/connectors/add", "service=csv", *origin).statusCode())
        File(data, "csv").mkdirs()
        File(data, "csv/Weekly.csv").writeText("title,artist\nSong,Someone\n")
        val form = get("/syncs/new?src=csv&dst=csv").body()
        assertTrue(form, form.contains("name=\"changes\" value=\"archive\"") && form.contains("Keep every track (archive)"))
        assertTrue(form.contains("Copy the latest version (replace)"))
        val made = post("/syncs", "src=csv&dst=csv&srcPlaylist=Weekly&dstPlaylist=&name=Weekly&schedule=WEEKLY&changes=archive&follows=on", *origin)
        val at = made.headers().firstValue("Location").orElse("")
        assertTrue(at, at.startsWith("/syncs/") && !at.contains("err="))
        val job = env.store.data.jobs.single()
        assertTrue(job.archive)
        assertFalse("an archive never mirrors", job.mirrorRemovals || job.followsChanges)
        assertTrue(get("/").body().contains("keeps every track"))
        assertTrue(get("/syncs/${job.id}").body().contains("value=\"archive\" checked"))
        // Back to the latest version, then to only adding, from its page.
        assertEquals(303, post("/syncs/${job.id}/schedule", "name=Weekly&changes=mirror&follows=on", *origin).statusCode())
        env.store.job(job.id)!!.let { assertTrue(it.mirrorRemovals && it.followsChanges && !it.archive) }
        assertEquals(303, post("/syncs/${job.id}/schedule", "name=Weekly&changes=archive", *origin).statusCode())
        env.store.job(job.id)!!.let { assertTrue(it.archive && !it.mirrorRemovals) }
        // The older checkbox still means what it did.
        assertEquals(303, post("/syncs/${job.id}/schedule", "name=Weekly&mirror=on", *origin).statusCode())
        env.store.job(job.id)!!.let { assertTrue(it.mirrorRemovals && !it.archive) }
        assertEquals(303, post("/syncs/${job.id}/schedule", "name=Weekly&changes=archive", *origin).statusCode())
        // A config backup carries it to another Trackhop.
        val text = get("/tools/config?download=1").body()
        val env2 = ServerEnv(tmp.newFolder("other-archive"), Strings(emptyMap()), "http://127.0.0.1")
        com.xlollx.songport.data.ConfigBackup.import(env2, com.xlollx.songport.data.ConfigBackup.read(text))
        assertTrue(env2.store.job(job.id)!!.archive)
        // And the API says it.
        val api = Api.job(env, env.store.job(job.id)!!, running = false).toString()
        assertTrue(api, api.contains("\"archive\":true") && api.contains("\"mirrorRemovals\":false"))
    }

    @Test fun configurationExportsAndImportsWithoutSignIns() {
        File(data, "csv").mkdirs()
        File(data, "csv/Keep.csv").writeText("title,artist\nSong,Band\n")
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("c1", "Mine", com.xlollx.songport.model.PlaylistRef("csv", "Keep", "Keep"), com.xlollx.songport.model.PlaylistRef("csv", null, "Copy"), color = "teal"))
        env.store.update { it.copy(matchCache = mapOf("a:b>c" to "d"), settings = it.settings.copy(clientSecrets = mapOf("youtube" to "secret!"), clientIds = mapOf("spotify" to "id1"))) }
        env.tokens.set("spotify", com.xlollx.songport.data.Tokens(accessToken = "tok", refreshToken = "ref"))
        val file = get("/tools/config?download=1")
        assertEquals(200, file.statusCode())
        val text = file.body()
        assertTrue(text.contains("trackhop-config") && text.contains("\"Mine\"") && text.contains("Keep"))
        assertFalse("no secrets", text.contains("secret!") || text.contains("tok") && text.contains("ref\""))
        // Into an empty Trackhop.
        val other = tmp.newFolder("other")
        val env2 = ServerEnv(other, Strings(emptyMap()), "http://127.0.0.1")
        val s = com.xlollx.songport.data.ConfigBackup.import(env2, com.xlollx.songport.data.ConfigBackup.read(text))
        assertEquals(1, s.syncs); assertEquals(1, s.files)
        assertEquals("teal", env2.store.job("c1")?.color)
        assertEquals("d", env2.store.data.matchCache["a:b>c"])
        assertEquals("id1", env2.store.data.settings.clientIds["spotify"])
        assertTrue(env2.store.data.settings.clientSecrets.isEmpty())
        assertTrue(File(other, "csv/Keep.csv").exists())
        // Through the page: read first (a summary), then import.
        val boundary = "----th"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"action\"\r\n\r\nread\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.trackhop\"\r\n\r\n$text\r\n--$boundary--\r\n"
        val read = client.send(
            HttpRequest.newBuilder(URI("$base/tools/config")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "multipart/form-data; boundary=$boundary").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, read.statusCode())
        assertTrue(read.body(), read.body().contains("1 syncs"))
        val pending = Regex("""name="pending" value="([^"]+)"""").find(read.body())!!.groupValues[1]
        assertEquals(303, post("/tools/config", "action=import&pending=$pending", *origin).statusCode())
        assertTrue(get("/tools/config?download=1").body().contains("trackhop-config"))
        assertEquals("not a file", 303, post("/tools/config", "action=import&pending=nope", *origin).statusCode())
    }
}

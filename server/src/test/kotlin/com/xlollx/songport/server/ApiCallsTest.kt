package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** The API's calls beyond the first ones (ApiSyncs.kt, ApiMore.kt, ApiDocs.kt), against a real server with File playlists. */
class ApiCallsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\nOther Song,Other Band\nSong,Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\n")
    }

    @After fun stop() = server.stop(0)

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
        val arr: JsonArray get() = Json.parseToJsonElement(text).jsonArray
    }

    private fun call(method: String, path: String, body: String? = null, auth: Boolean = true): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).apply { if (auth) header("Authorization", "Bearer $token") }
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun favouritesLabelsAndTagsAreWrittenFilteredAndRunAsAGroup() {
        fun make(name: String, extra: String) =
            call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv"},"name":"$name"$extra}""").also { assertEquals(it.text, 201, it.code) }
        val a = make("A", ""","tags":{" Genre ":"Metal","mood":"gym"},"favorite":true,"color":"teal"""").obj
        val b = make("B", ""","tags":{"genre":"Heavy Metal","kids":null},"enabled":false""").obj
        val c = make("C", "").obj
        assertEquals("""{"Genre":"Metal","mood":"gym"}""", a["tags"].toString())
        assertEquals("true", a.s("favorite"))
        // A ready colour is its own label.
        assertEquals("teal", a.s("label"))
        assertEquals("""{"genre":"Heavy Metal","kids":""}""", b["tags"].toString())
        assertEquals("false", c.s("favorite"))
        assertEquals("{}", c["tags"].toString())

        fun names(q: String) = call("GET", "syncs$q").also { assertEquals(it.text, 200, it.code) }.arr.map { it.jsonObject.s("name") }
        assertEquals(listOf("A", "B", "C"), names(""))
        // Exactly that tag, whatever the case; "metal" is not "Heavy Metal".
        assertEquals(listOf("A"), names("?tag=genre:metal"))
        assertEquals(listOf("A"), names("?tag=GENRE%3DMetal"))
        assertEquals(listOf("B"), names("?tag=genre%3Aheavy%20metal"))
        // The key alone, any value; a key without a value.
        assertEquals(listOf("A", "B"), names("?tag=genre"))
        assertEquals(listOf("B"), names("?tag=kids"))
        // Several: all wanted.
        assertEquals(listOf("A"), names("?tag=genre,mood:gym"))
        assertEquals(emptyList<String?>(), names("?tag=genre:metal,kids"))
        assertEquals(listOf("A"), names("?favorite=true"))
        assertEquals(listOf("B", "C"), names("?favorite=false"))
        assertEquals(400, call("GET", "syncs?favorite=maybe").code)
        assertEquals(400, call("GET", "syncs?tag=%3Ametal").code)
        assertEquals("""{"Genre":["Heavy Metal","Metal"],"kids":[],"mood":["gym"]}""", call("GET", "tags").obj.toString())

        // Labels: a ready colour named, a colour of one's own saved, a sync given it by name.
        val gym = call("POST", "labels", """{"color":"teal","name":"Gym"}""")
        assertEquals(gym.text, 201, gym.code)
        assertEquals("teal", gym.obj.s("id")); assertEquals("1", gym.obj.s("syncs"))
        assertEquals(listOf("A"), names("?label=Gym"))
        assertEquals(listOf("A"), names("?label=teal"))
        assertEquals(emptyList<String?>(), names("?label=nope"))
        assertEquals("Gym", call("GET", "syncs/${a.s("id")}").obj.s("labelName"))
        val mine = call("POST", "labels", """{"color":"#12a594","name":"Mine"}""").obj
        val labelId = mine.s("id")!!
        assertEquals("#12A594", mine.s("color"))
        val worn = call("PATCH", "syncs/${c.s("id")}", """{"label":"mine"}""")
        assertEquals(worn.text, 200, worn.code)
        assertEquals("#12A594", worn.obj.s("color")); assertEquals(labelId, worn.obj.s("label")); assertEquals("Mine", worn.obj.s("labelName"))
        assertEquals(400, call("PATCH", "syncs/${c.s("id")}", """{"label":"nope"}""").code)
        assertEquals(400, call("POST", "labels", """{"color":"mauve","name":"x"}""").code)
        assertEquals(400, call("POST", "labels", """{"color":"red"}""").code)
        // Recoloured and renamed: the sync follows.
        val ours = call("PATCH", "labels/$labelId", """{"color":"#000001","name":"Ours"}""")
        assertEquals(ours.text, 200, ours.code)
        assertEquals("Ours", ours.obj.s("name"))
        assertEquals("#000001", env.store.job(c.s("id")!!)!!.color)
        assertEquals(400, call("PATCH", "labels/teal", """{"color":"#000001"}""").code)
        assertEquals(9, call("GET", "labels").arr.size)
        // A colour given as such takes the label off.
        assertEquals("null", call("PATCH", "syncs/${c.s("id")}", """{"color":"#abcdef"}""").obj["label"].toString())
        call("PATCH", "syncs/${c.s("id")}", """{"label":"$labelId"}""")
        // Deleted: the colour stays, without the name.
        assertEquals(200, call("DELETE", "labels/$labelId").code)
        assertEquals("#000001", env.store.job(c.s("id")!!)!!.color)
        assertEquals(null, env.store.job(c.s("id")!!)!!.label)
        assertEquals(404, call("DELETE", "labels/$labelId").code)
        // "label": null takes label and colour off.
        assertEquals("null", call("PATCH", "syncs/${c.s("id")}", """{"label":null}""").obj["color"].toString())

        // Tags are replaced as a whole; not an object is refused.
        assertEquals("""{"x":""}""", call("PATCH", "syncs/${c.s("id")}", """{"tags":{"x":null}}""").obj["tags"].toString())
        assertEquals("{}", call("PATCH", "syncs/${c.s("id")}", """{"tags":null}""").obj["tags"].toString())
        assertEquals(400, call("PATCH", "syncs/${c.s("id")}", """{"tags":"genre"}""").code)

        // A group run: needs a group; starts those turned on (B is off).
        assertEquals(400, call("POST", "syncs/run").code)
        assertEquals(400, call("POST", "syncs/run?tag=%3D").code)
        val none = call("POST", "syncs/run?tag=nothing")
        assertEquals(none.text, 202, none.code); assertEquals("0", none.obj.s("started"))
        val run = call("POST", "syncs/run?tag=genre")
        assertEquals(run.text, 202, run.code)
        assertEquals("1", run.obj.s("started"))
        assertEquals(listOf(a.s("id")), run.obj["syncs"]!!.jsonArray.map { it.jsonObject.s("id") })
        val until = System.currentTimeMillis() + 20_000
        while (env.store.job(a.s("id")!!)!!.lastReportId == null && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue(env.store.job(a.s("id")!!)!!.lastReportId != null)
        assertEquals(null, env.store.job(b.s("id")!!)!!.lastReportId)
        assertEquals(null, env.store.job(c.s("id")!!)!!.lastReportId)
    }

    @Test fun aSyncIsMadeChangedMovedAndDeleted() {
        val made = call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"},"name":"Mine","schedule":"daily","at":"07:30","changes":"mirror","color":"teal"}""")
        assertEquals(made.text, 201, made.code)
        val id = made.obj.s("id")!!
        assertEquals("Mine", made.obj.s("name"))
        assertEquals("daily at 07:30", made.obj.s("timing"))
        assertEquals("true", made.obj.s("mirrorRemovals"))
        assertEquals("teal", made.obj.s("color"))
        // The page's checks: a playlist the service does not list, a playlist onto itself, an unknown service.
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"csv","playlist":"Nope"},"target":{"service":"csv"}}""").code)
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"In"}}""").code)
        assertEquals(404, call("POST", "syncs", """{"source":{"service":"nope","playlist":"In"},"target":{"service":"csv"}}""").code)
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv"},"schedule":"sometimes"}""").code)
        // Changed by halves: what is not named stays.
        val edited = call("PATCH", "syncs/$id", """{"name":"Renamed","every":180,"changes":"archive","color":"#12a594"}""")
        assertEquals(edited.text, 200, edited.code)
        assertEquals("Renamed", edited.obj.s("name"))
        assertEquals("every 3 h", edited.obj.s("timing"))
        assertEquals("true", edited.obj.s("archive"))
        assertEquals("#12A594", edited.obj.s("color"))
        assertEquals(400, call("PATCH", "syncs/$id", """{"at":"25:00"}""").code)
        assertEquals(400, call("PATCH", "syncs/$id", """{"color":"mauve"}""").code)
        // Weekly on chosen days.
        assertEquals("Mon, Fri at 08:00", call("PATCH", "syncs/$id", """{"schedule":"daily","days":[1,5],"at":"08:00"}""").obj.s("timing"))
        // Moved in the list.
        env.store.upsertJob(SyncJob("other", "Other", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", null, "Copy")))
        assertEquals("1", call("POST", "syncs/other/move", """{"to":"top"}""").obj.s("position"))
        assertEquals("2", call("POST", "syncs/other/move", """{"to":2}""").obj.s("position"))
        assertEquals(listOf(id, "other"), env.store.data.jobs.map { it.id })
        // Stop when nothing runs: said so.
        assertEquals(409, call("POST", "syncs/$id/stop").code)
        assertEquals(200, call("DELETE", "syncs/$id").code)
        assertEquals(null, env.store.job(id))
        assertEquals(404, call("DELETE", "syncs/$id").code)
    }

    @Test fun previewSaysWhatARunWouldDoAndChangesNothing() {
        env.store.upsertJob(SyncJob("p1", "Prev", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out")))
        val p = call("GET", "syncs/p1/preview")
        assertEquals(p.text, 200, p.code)
        assertTrue(p.text, p.obj["toAdd"]!!.jsonArray.any { it.jsonObject.s("title") == "Other Song" })
        assertEquals("title,artist\n", File(data, "csv/Out.csv").readText())
    }

    @Test fun runsArePagedAndAReviewIsDoneFromAScript() {
        env.store.upsertJob(SyncJob("r", "Rev", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out")))
        val a = Track(id = "a", title = "Alpha", artists = listOf("One"))
        val b = Track(id = "b", title = "Beta", artists = listOf("Two"))
        val c = Track(id = "c", title = "Gamma", artists = listOf("Three"))
        env.store.addReport(SyncReport(id = "r0", jobId = "r", jobName = "Rev", startedEpoch = 1, durationMs = 1))
        env.store.addReport(
            SyncReport(
                id = "r1", jobId = "r", jobName = "Rev", startedEpoch = 2, durationMs = 1, notes = listOf("A note"),
                unmatched = listOf(a.toString(), b.toString()), unmatchedTracks = listOf(a, b),
                reviewTracks = listOf(MatchReview(c, c.copy(id = "c2"), 0.7)),
            ),
        )
        val page = call("GET", "runs?sync=r&limit=1")
        assertEquals("2", page.obj.s("total"))
        assertEquals("r1", page.obj["runs"]!!.jsonArray.single().jsonObject.s("id"))
        assertEquals("r0", call("GET", "syncs/r/runs?offset=1").obj["runs"]!!.jsonArray.single().jsonObject.s("id"))
        val run = call("GET", "runs/r1").obj
        assertEquals("A note", run["notes"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("a", run["notFoundTracks"]!!.jsonArray.first().jsonObject.s("id"))
        val review = call("GET", "runs/r1/review").obj
        assertEquals(2, review["notFound"]!!.jsonArray.size)
        // Pick a track for Alpha by "Artist - Title" on the target (File takes any), ignore Beta, keep Gamma.
        val picked = call("POST", "runs/r1/review", """{"action":"pick","tracks":["a"],"choice":"One - Alpha"}""")
        assertEquals(picked.text, 200, picked.code)
        assertTrue(File(data, "csv/Out.csv").readText().contains("Alpha"))
        assertEquals(200, call("POST", "runs/r1/review", """{"action":"ignore","tracks":"all"}""").code)
        val kept = call("POST", "runs/r1/review", """{"action":"keep","tracks":["c"]}""")
        assertEquals(kept.text, 200, kept.code)
        val left = kept.obj["review"]!!.jsonObject
        assertTrue(left["notFound"]!!.jsonArray.isEmpty() && left["toCheck"]!!.jsonArray.isEmpty())
        assertTrue("b" in env.store.job("r")!!.ignoredSourceIds)
        assertEquals(400, call("POST", "runs/r1/review", """{"action":"keep","tracks":["c"]}""").code)
        assertEquals(400, call("POST", "runs/r1/review", """{"action":"dance"}""").code)
    }

    @Test fun filesSettingsPatternsZipAndDelete() {
        assertEquals("AUTO", call("GET", "files/settings").obj.s("layout"))
        assertEquals(400, call("PUT", "files/settings", """{"layout":"CUSTOM"}""").code)
        assertEquals(400, call("PUT", "files/settings", """{"patterns":["{artist} - [{album}"]}""").code)
        val saved = call("PUT", "files/settings", """{"layout":"CUSTOM","patterns":["{artist}/{album}/{track} {title}"]}""")
        assertEquals(saved.text, 200, saved.code)
        assertEquals("CUSTOM", env.store.data.settings.fileNameLayout)
        val tried = call("POST", "files/try", """{"sample":"Music/Band/Album/01 Song.mp3"}""").obj
        assertEquals("Band", tried.s("artist")); assertEquals("Album", tried.s("album")); assertEquals("Song", tried.s("title"))
        // Tried with other patterns, never saved.
        assertEquals("false", call("POST", "files/try", """{"sample":"x","patterns":["{title} ({artist})"]}""").obj.s("matched"))
        assertEquals(listOf("{artist}/{album}/{track} {title}"), env.store.data.settings.fileNamePatterns)
        val zip = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/files/zip")).header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString("""{"files":["In.csv","Out"],"format":"m3u"}""")).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, zip.statusCode())
        val names = java.util.zip.ZipInputStream(zip.body().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("In.m3u", "Out.m3u"), names)
        assertEquals(200, call("DELETE", "files/Out.csv").code)
        assertFalse(File(data, "csv/Out.csv").exists())
        assertEquals(404, call("DELETE", "files/Out.csv").code)
    }

    @Test fun theSetUpComesBackInWithADryRunFirst() {
        env.store.upsertJob(SyncJob("cfg", "Saved", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", null, "Copy")))
        val file = call("GET", "config").text
        env.store.deleteJob("cfg")
        val dry = call("POST", "config?dryRun=true", file).obj
        assertEquals("false", dry.s("imported")); assertEquals("1", dry.s("syncs"))
        assertEquals(null, env.store.job("cfg"))
        assertEquals("true", call("POST", "config", file).obj.s("imported"))
        assertEquals("Saved", env.store.job("cfg")!!.name)
        assertEquals(400, call("POST", "config", "not a set-up").code)
    }

    @Test fun webhookNotificationsAndSettings() {
        assertEquals(listOf("sync.finished", "backup.finished"), call("GET", "webhook").obj["events"]!!.jsonArray.map { it.jsonPrimitive.content })
        val hook = call("PUT", "webhook", """{"url":"https://example.org/hook","secret":"s3cret","events":["sync.started","sync.failed"]}""").obj
        assertEquals("true", hook.s("secret"))
        assertFalse(hook.toString().contains("s3cret"))
        assertEquals(setOf("sync.started", "sync.failed"), Api.events(env))
        assertEquals(400, call("PUT", "webhook", """{"url":"ftp://x"}""").code)
        assertEquals(400, call("PUT", "webhook", """{"url":"https://x","events":["sync.exploded"]}""").code)
        assertEquals("", call("DELETE", "webhook").obj.s("url"))
        // Channels: the kinds, one added, changed (its token kept), removed.
        assertTrue(call("GET", "notifications/kinds").obj["kinds"]!!.jsonArray.any { it.jsonObject.s("id") == "ntfy" })
        val added = call("POST", "notifications", """{"kind":"gotify","name":"Phone","settings":{"server":"https://gotify.example.org","token":"AbCdEfGhIjKlMn"},"events":["failed"]}""")
        assertEquals(added.text, 201, added.code)
        assertFalse(added.text.contains("AbCdEfGhIjKlMn"))
        val cid = added.obj.s("id")!!
        val changed = call("PATCH", "notifications/$cid", """{"name":"Laptop","enabled":false}""")
        assertEquals(changed.text, 200, changed.code)
        assertEquals("AbCdEfGhIjKlMn", Notify.channel(env, cid)!!.value("token"))
        assertEquals(false, Notify.channel(env, cid)!!.enabled)
        assertEquals(400, call("POST", "notifications", """{"kind":"gotify","settings":{"server":"nope","token":"x"}}""").code)
        assertEquals(200, call("DELETE", "notifications/$cid").code)
        assertEquals(null, Notify.channel(env, cid))
        // Settings.
        val s = call("PATCH", "settings", """{"theme":"dark","backupSchedule":"weekly"}""")
        assertEquals(s.text, 200, s.code)
        assertEquals("dark", s.obj.s("theme")); assertEquals("weekly", s.obj.s("backupSchedule"))
        assertEquals(400, call("PATCH", "settings", """{"theme":"neon"}""").code)
        assertEquals(400, call("PATCH", "settings", """{"backupFolder":"/no/such/folder"}""").code)
    }

    @Test fun servicesSearchConvertDuplicatesExportAndStats() {
        val svc = call("GET", "services/csv").obj
        assertEquals("true", svc.s("canCreatePlaylists"))
        assertEquals(404, call("GET", "services/nope").code)
        val found = call("GET", "services/csv/search?q=Band%20-%20Song").arr
        assertTrue(found.toString(), found.isNotEmpty())
        val conv = call("GET", "convert?track=Band%20-%20Song&to=csv").obj
        assertEquals("Band - Song", conv.s("asked"))
        val many = call("POST", "convert", """{"to":"csv","tracks":["Band - Song",{"title":"Other Song","artist":"Other Band"}]}""").arr
        assertEquals(2, many.size)
        val dupes = call("GET", "services/csv/playlists/In/duplicates").obj
        assertEquals("1", dupes.s("extraCopies"))
        val removal = call("POST", "services/csv/playlists/In/duplicates/remove")
        assertEquals(removal.text, 202, removal.code)
        val task = removal.obj.s("task")!!
        val deadline = System.currentTimeMillis() + 20_000
        while (call("GET", "tasks/$task").obj.s("done") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(call("GET", "tasks").arr.any { it.jsonObject.s("id") == task })
        assertEquals("0", call("GET", "services/csv/playlists/In/duplicates").obj.s("extraCopies"))
        val m3u = call("GET", "services/csv/playlists/In/export?format=m3u")
        assertTrue(m3u.text, m3u.code == 200 && m3u.text.startsWith("#EXTM3U"))
        assertEquals(200, call("GET", "stats").code)
        // The log is the admin's: here the only user is the admin.
        assertEquals(200, call("GET", "log?lines=5").code)
    }

    @Test fun theDescriptionIsPublicAndListsEveryCall() {
        val doc = call("GET", "openapi.json", auth = false)
        assertEquals(200, doc.code)
        val paths = doc.obj["paths"]!!.jsonObject
        for (c in ApiDocs.CALLS) assertTrue("${c.method} ${c.path}", paths[c.path]?.jsonObject?.containsKey(c.method.lowercase()) == true)
        // Every call in the description is answered (a 404 for its unknown ids, never "Unknown call").
        for (c in ApiDocs.CALLS.filter { it.method == "GET" && "{" !in it.path }) {
            val a = call("GET", c.path.removePrefix("/"))
            assertFalse("${c.path}: ${a.text}", a.text.contains("Unknown call"))
        }
        // And the guide names each of them.
        val guide = listOf(File("../docs/guide/docker.md"), File("docs/guide/docker.md")).first { it.isFile || it.path.startsWith("docs") }
        if (guide.isFile) {
            val text = guide.readText()
            for (c in ApiDocs.CALLS) assertTrue("guide lacks ${c.method} ${c.path}", text.contains("${c.method} /api/v1${c.path}"))
        }
    }
}

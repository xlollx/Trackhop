package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
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

/** The app's features brought to Desktop and Docker in 2.2, through the API, against a real server with File playlists. */
class ParityApiTest {
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
        Features.v2 = true
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\nOther Song,Other Band\nSong,Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\n")
    }

    @After fun stop() { server.stop(0); Features.v2 = Features.START }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
        val arr: JsonArray get() = Json.parseToJsonElement(text).jsonArray
    }

    private fun call(method: String, path: String, body: String? = null): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    private fun newSync(extra: String = ""): JsonObject =
        call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"}$extra}""").also { assertEquals(it.text, 201, it.code) }.obj

    @Test fun matchingChoicesAndTheSourceOrderAreSetAndShown() {
        val made = newSync(""","policy":{"strictness":"strict","studioOnly":true},"keepOrder":true""")
        assertEquals("strict", made.o("policy").s("strictness"))
        assertEquals("either", made.o("policy").s("explicit"))
        assertEquals("true", made.o("policy").s("studioOnly"))
        assertEquals("true", made.s("keepOrder"))
        val id = made.s("id")!!
        assertEquals(1, env.store.job(id)!!.policy.strictness)
        // Only what is named changes.
        val edited = call("PATCH", "syncs/$id", """{"policy":{"explicit":"clean","strictness":-1},"keepOrder":false}""").obj
        assertEquals("lenient", edited.o("policy").s("strictness"))
        assertEquals("clean", edited.o("policy").s("explicit"))
        assertEquals("true", edited.o("policy").s("studioOnly"))
        assertEquals("false", edited.s("keepOrder"))
        assertEquals(400, call("PATCH", "syncs/$id", """{"policy":{"strictness":"very"}}""").code)
        assertEquals(400, call("PATCH", "syncs/$id", """{"policy":"strict"}""").code)
        assertTrue(env.store.job(id)!!.policy.explicit == -1)
    }

    @Test fun theServicesListFindsAndFilters() {
        env.store.updateSettings { it.copy(connectors = listOf("csv", "jellyfin", "deezer_bridge")) }
        val all = call("GET", "services").arr.map { it.jsonObject.s("id") }
        assertEquals(listOf("csv", "jellyfin", "deezer_bridge"), all)
        assertEquals(listOf("deezer_bridge"), call("GET", "services?kind=web").arr.map { it.jsonObject.s("id") })
        assertEquals(listOf("csv", "jellyfin"), call("GET", "services?kind=official").arr.map { it.jsonObject.s("id") })
        assertEquals(listOf("jellyfin"), call("GET", "services?q=JELLY").arr.map { it.jsonObject.s("id") })
        assertEquals(400, call("GET", "services?kind=nope").code)
    }

    @Test fun theWeeklyUnplayableCheckIsTurnedOnRunAndDue() {
        assertEquals("false", call("GET", "tools/unplayable/weekly").obj.s("enabled"))
        assertTrue(!UnplayableWeekly.isDue(env, System.currentTimeMillis()))
        assertEquals("true", call("PATCH", "tools/unplayable/weekly", """{"enabled":true}""").obj.s("enabled"))
        assertTrue(env.store.data.settings.unplayableCheck)
        assertTrue(UnplayableWeekly.isDue(env, System.currentTimeMillis()))
        val task = call("POST", "tools/unplayable/weekly/run").also { assertEquals(it.text, 202, it.code) }.obj.s("task")!!
        val deadline = System.currentTimeMillis() + 20_000
        while (call("GET", "tasks/$task").obj.s("done") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val last = call("GET", "tools/unplayable/weekly").obj.o("last")
        assertEquals("No connected service can tell unplayable tracks apart.", last.s("summary"))
        // Looked now: due again in a week.
        assertTrue(!UnplayableWeekly.isDue(env, System.currentTimeMillis()))
        assertTrue(UnplayableWeekly.isDue(env, System.currentTimeMillis() + UnplayableWeekly.EVERY_MS))
        assertEquals(400, call("PATCH", "tools/unplayable/weekly", "{}").code)
    }

    @Test fun toFixListsOnceAndFixesEverywhere() {
        val missing = com.xlollx.songport.model.Track("x1", "Lost Song", listOf("Nobody"))
        val loose = com.xlollx.songport.model.Track("x2", "Song", listOf("Band"))
        val chosen = com.xlollx.songport.model.Track("Band - Song (Live)", "Song (Live)", listOf("Band"))
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("j1", "Mine", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out")))
        env.store.addReport(com.xlollx.songport.model.SyncReport("r1", "j1", "Mine", 1_780_000_000_000, 10, unmatched = listOf(missing.toString()), unmatchedTracks = listOf(missing),
            reviewTracks = listOf(com.xlollx.songport.model.MatchReview(loose, chosen, 0.74))))
        val list = call("GET", "tofix").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals("2", list.s("count"))
        val rows = list["groups"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        val lost = rows.first { it.o("track").s("title") == "Lost Song" }.s("key")!!
        val check = rows.first { it.o("track").s("title") == "Song" }
        assertEquals("to_check", check.s("kind"))
        assertEquals("74", check.s("score"))
        assertEquals("1", call("GET", "tofix?by=service").obj["groups"]!!.jsonArray.size.toString())
        // Later, then back.
        assertEquals("1", call("POST", "tofix", """{"action":"later","keys":["$lost"]}""").obj.s("left"))
        assertEquals(1, call("GET", "tofix").obj["later"]!!.jsonArray.size)
        assertEquals("2", call("POST", "tofix", """{"action":"back","keys":"all"}""").obj.s("left"))
        // Ignored: not searched for again by this sync.
        assertEquals("1", call("POST", "tofix", """{"action":"ignore","keys":["$lost"]}""").obj.s("left"))
        assertTrue("x1" in env.store.job("j1")!!.ignoredSourceIds)
        // The suggestion kept: nothing left.
        assertEquals("0", call("POST", "tofix", """{"action":"use","keys":"all"}""").obj.s("left"))
        assertEquals(400, call("POST", "tofix", """{"action":"use","keys":["nope"]}""").code)
        assertEquals(400, call("POST", "tofix", """{"action":"frob","keys":"all"}""").code)
        assertEquals(400, call("GET", "tofix?by=year").code)
    }

    @Test fun savedSchedulesAndBulkEditThroughTheApi() {
        val id = newSync().s("id")!!
        val made = call("POST", "schedules", """{"name":"Mornings","every":1440,"at":"7:30"}""").also { assertEquals(it.text, 201, it.code) }.obj
        assertEquals("daily at 07:30", made.s("timing"))
        val linked = call("PATCH", "syncs/$id", """{"savedSchedule":"Mornings"}""").obj
        assertEquals(made.s("id"), linked.s("savedSchedule"))
        assertEquals(1, call("GET", "schedules").arr.single().jsonObject["syncs"]!!.jsonArray.size)
        assertEquals("08:00", call("PATCH", "schedules/${made.s("id")}", """{"at":"08:00"}""").obj.s("at"))
        assertEquals("08:00", env.store.job(id)!!.at)
        assertEquals(400, call("POST", "schedules", """{"every":0}""").code)
        assertEquals(400, call("PATCH", "syncs/$id", """{"savedSchedule":"Nope"}""").code)
        val bulk = call("POST", "syncs/bulk", """{"syncs":["$id"],"every":360,"changes":"mirror"}""").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals("1", bulk.s("changed"))
        assertTrue(env.store.job(id)!!.mirrorRemovals)
        assertEquals(360, env.store.job(id)!!.everyMinutes)
        assertEquals(404, call("POST", "syncs/bulk", """{"syncs":["nope"]}""").code)
        assertEquals("1", call("DELETE", "schedules/Mornings").let { call("GET", "schedules").arr.size + 1 }.toString())
    }

    @Test fun aSyncSaysWhetherItsSourceIsStillAsAtItsLastRun() {
        var version = "v1"
        val csv = com.xlollx.songport.providers.Providers.byId("csv")!!
        com.xlollx.songport.providers.Providers.testDoubles = listOf(object : com.xlollx.songport.providers.MusicProvider by csv {
            override val id = "versioned"
            override suspend fun playlistVersion(ctx: com.xlollx.songport.platform.Env, playlistId: String): String? = version
        })
        try {
            env.store.upsertJob(com.xlollx.songport.model.SyncJob("f1", "Fresh", com.xlollx.songport.model.PlaylistRef("versioned", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out"),
                lastRunEpoch = 1, sourceVersion = "v1"))
            assertEquals("current", call("GET", "syncs/f1?fresh=check").obj.s("fresh"))
            version = "v2"
            assertEquals("changed", call("GET", "syncs/f1?fresh=check").obj.s("fresh"))
            assertEquals("changed", call("GET", "syncs/f1").obj.s("fresh"))
            // Never run: not checked.
            env.store.upsertJob(com.xlollx.songport.model.SyncJob("f2", "New", com.xlollx.songport.model.PlaylistRef("versioned", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out")))
            assertEquals(null, call("GET", "syncs/f2?fresh=check").obj["fresh"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        } finally {
            com.xlollx.songport.providers.Providers.testDoubles = emptyList()
        }
    }

    @Test fun recentTransfersAreListedAndRemoved() {
        assertEquals(0, call("GET", "transfers").arr.size)
        val job = com.xlollx.songport.sync.Setlists.job("t1", "Muse at Forum", "In", com.xlollx.songport.model.PlaylistRef("csv", null, ""))
        env.store.upsertJob(job)
        env.store.addReport(com.xlollx.songport.model.SyncReport("rt", "t1", "Muse at Forum", System.currentTimeMillis(), 10, sourceCount = 3, added = 2,
            unmatched = listOf("Nobody - Lost"), unmatchedTracks = listOf(com.xlollx.songport.model.Track("x", "Lost", listOf("Nobody")))))
        val list = call("GET", "transfers").arr
        assertEquals(1, list.size)
        val t = list[0].jsonObject
        assertEquals("2", t.o("result").s("added"))
        assertEquals("1", t.o("result").s("toReview"))
        // Not a sync.
        assertTrue(call("GET", "syncs").arr.none { it.jsonObject.s("id") == "t1" })
        assertEquals(400, call("POST", "transfers", """{"file":"Nope","to":"csv"}""").code)
        assertEquals(400, call("POST", "transfers", """{"file":"In","to":"csv"}""").code)
        assertEquals(200, call("DELETE", "transfers/t1").code)
        assertEquals(0, call("GET", "transfers").arr.size)
        assertEquals(404, call("GET", "transfers/t1").code)
    }

    @Test fun extendingWithAiNeedsTheAiAndAPlaylist() {
        assertTrue(call("POST", "tools/ai/extend", """{"service":"csv","playlist":"In"}""").let { it.code == 400 && it.text.contains("Set up your AI") })
        com.xlollx.songport.ai.AiClient.save(env, com.xlollx.songport.ai.AiClient.Config(com.xlollx.songport.ai.AiClient.Vendor.OPENROUTER, "k", "m", ""))
        assertEquals(400, call("POST", "tools/ai/extend", """{"service":"csv","playlist":"Nope"}""").code)
        assertEquals(400, call("POST", "tools/ai/extend", """{"service":"csv","playlist":"In","into":"sideways"}""").code)
    }

    private fun waitTask(task: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (call("GET", "tasks/$task").obj.s("done") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals("null", call("GET", "tasks/$task").obj["error"].toString())
    }

    @Test fun newPlaylistsFromOne() {
        File(data, "csv/B.csv").writeText("title,artist\nZed,Alpha\nSong,Band\n")
        waitTask(call("POST", "services/csv/playlists/In/new", """{"op":"copy"}""").also { assertEquals(it.text, 202, it.code) }.obj.s("task")!!)
        assertTrue(File(data, "csv/In (copy).csv").readText().contains("Other Song"))
        waitTask(call("POST", "services/csv/playlists/B/new", """{"op":"sort","by":"artist","name":"Sorted"}""").obj.s("task")!!)
        val sorted = File(data, "csv/Sorted.csv").readLines().drop(1)
        assertTrue(sorted.toString(), sorted[0].contains("Alpha"))
        waitTask(call("POST", "services/csv/playlists/B/new", """{"op":"split","size":1,"name":"Part"}""").obj.s("task")!!)
        assertTrue(File(data, "csv/Part (part 2).csv").isFile)
        waitTask(call("POST", "services/csv/playlists/In/new", """{"op":"merge","with":["B"],"name":"Both"}""").obj.s("task")!!)
        // Song by Band is in both and twice in In: once.
        assertEquals(3, File(data, "csv/Both.csv").readLines().drop(1).size)
        assertEquals(400, call("POST", "services/csv/playlists/In/new", """{"op":"merge"}""").code)
        assertEquals(400, call("POST", "services/csv/playlists/In/new", """{"op":"fold"}""").code)
        assertEquals(404, call("POST", "services/csv/playlists/Nope/new", """{"op":"copy"}""").code)
    }

    @Test fun aLibraryScanFindsSongsInSeveralPlaylists() {
        File(data, "csv/B.csv").writeText("title,artist\nZed,Alpha\nSong,Band\n")
        val r = call("GET", "services/csv/scan").also { assertEquals(it.text, 200, it.code) }.obj
        val repeats = r["repeats"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Song"), repeats.map { it.o("track").s("title") })
        assertEquals(2, repeats[0]["playlists"]!!.jsonArray.size)
        assertEquals(404, call("GET", "services/nope/scan").code)
    }

    @Test fun aPlaylistPastedAsText() {
        val r = call("POST", "files/text", """{"name":"Radio","text":"1. Moderat - A New Error\n2. Bonobo - Kerala\n"}""").also { assertEquals(it.text, 201, it.code) }.obj
        assertEquals("Radio.csv", r.s("name"))
        assertEquals("2", r.s("tracks"))
        assertTrue(File(data, "csv/Radio.csv").readText().contains("Kerala"))
        assertEquals(400, call("POST", "files/text", """{"text":"   "}""").code)
    }

    @Test fun aSongLinkNeedsALinkAndAService() {
        env.store.updateSettings { it.copy(connectors = listOf("csv")) }
        assertEquals(400, call("POST", "tracks/link", """{"service":"csv"}""").code)
        assertEquals(404, call("POST", "tracks/link", """{"link":"https://open.spotify.com/track/abc","service":"nope"}""").code)
        assertEquals(400, call("POST", "tracks/link", """{"link":"hello","service":"csv"}""").code)
    }

    @Test fun aConnectionTestAndYouTubesQuota() {
        val t = call("POST", "services/csv/test").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals(3, t["steps"]!!.jsonArray.size)
        assertEquals("true", t["steps"]!!.jsonArray[1].jsonObject.s("ok"))
        assertEquals(404, call("POST", "services/nope/test").code)
        env.store.update { it.copy(quota = mapOf("youtube" to com.xlollx.songport.data.QuotaDay(com.xlollx.songport.sync.QuotaMeter.dayKey(), 340))) }
        val yt = call("GET", "services/youtube").obj
        assertEquals("340", yt.o("quota").s("units"))
        assertEquals(null, call("GET", "services/csv").obj["quota"])
    }

    @Test fun aRoastNeedsTheAi() {
        assertTrue(call("POST", "tools/roast", """{"service":"csv","playlist":"In"}""").let { it.code == 400 && it.text.contains("Set up your AI") })
        assertEquals(400, call("POST", "tools/roast", """{"service":"csv","playlist":"In","level":"nuclear"}""").code)
    }

    @Test fun vanishedSongsFromAMirrorSyncsRuns() {
        assertEquals(0, call("GET", "vanished").arr.size)
        env.store.upsertJob(com.xlollx.songport.model.SyncJob("m1", "Mirror", com.xlollx.songport.model.PlaylistRef("csv", "In", "In"), com.xlollx.songport.model.PlaylistRef("csv", "Out", "Out"), mirrorRemovals = true))
        env.store.addReport(com.xlollx.songport.model.SyncReport("rm", "m1", "Mirror", 1_780_000_000_000, 10, sourceCount = 40, removed = 1, removedTracks = listOf(com.xlollx.songport.model.Track("g", "Gone Song", listOf("Ghost")))))
        val v = call("GET", "vanished").arr
        assertEquals(1, v.size)
        assertEquals("Gone Song", v[0].jsonObject.o("track").s("title"))
        assertEquals("m1", v[0].jsonObject.s("sync"))
    }

    @Test fun aPlaylistAsAUniversalLink() {
        val r = call("GET", "services/csv/playlists/In/link").also { assertEquals(it.text, 200, it.code) }.obj
        val l = r["links"]!!.jsonArray[0].jsonObject
        assertTrue(l.s("url")!!.startsWith("https://trackhop.org/open#"))
        assertEquals("3", l.s("total"))
        assertTrue(ShareLink.qrSvg("https://trackhop.org/open#short")!!.startsWith("<svg"))
        assertEquals(null, ShareLink.qrSvg("x".repeat(5000)))
        assertEquals(404, call("GET", "services/csv/playlists/Nope/link").code)
    }

    @Test fun twoSubscriptionsNeedTwoServices() {
        assertEquals(400, call("POST", "tools/overlap", """{"a":"csv","b":"csv"}""").code)
        val r = com.xlollx.songport.sync.LibraryOverlap.compute(listOf(com.xlollx.songport.model.Track("1", "Song", listOf("Band"))), listOf(com.xlollx.songport.model.Track("2", "Song", listOf("Band"))))
        assertTrue(Overlap.verdict(r, "A", "B").startsWith("Almost the same"))
    }

    @Test fun theCompatibilityOfTwoPlaylists() {
        File(data, "csv/B.csv").writeText("title,artist\nZed,Alpha\nSong,Band\n")
        val r = call("POST", "tools/compatibility", """{"a":{"service":"csv","playlist":"In"},"b":{"service":"csv","playlist":"B"}}""").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals(1, r["common"]!!.jsonArray.size)
        assertTrue(r.s("score")!!.toInt() > 0)
        assertEquals(400, call("POST", "tools/compatibility", """{"a":{"service":"csv","playlist":"In"},"b":{"link":"not a link"}}""").code)
    }

    @Test fun markingAsListenedNeedsAListeningService() {
        assertTrue(call("POST", "listens", """{"service":"csv","playlist":"In"}""").let { it.code == 400 && it.text.contains("Pick Last.fm") })
        assertEquals(400, call("POST", "listens", "{}").code)
        assertEquals(1_795_000_000_000, Listened.time("1795000000000"))
        assertEquals(400, call("POST", "listens", """{"service":"csv","playlist":"In","at":"someday"}""").code)
    }

    @Test fun allTimeStatsFromAnExport() {
        assertEquals("null", call("GET", "tools/lifetime").text)
        val export = """[{"ts":"2024-03-01T10:00:00Z","ms_played":200000,"master_metadata_track_name":"Song","master_metadata_album_artist_name":"Band"},""" +
            """{"ts":"2025-03-01T10:00:00Z","ms_played":3800000,"master_metadata_track_name":"Long","master_metadata_album_artist_name":"Other"}]"""
        val s = call("PUT", "tools/lifetime", export).also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals("2", s.s("plays"))
        assertEquals("Other", s["topArtists"]!!.jsonArray[0].jsonObject.s("name"))
        assertEquals("2", call("GET", "tools/lifetime").obj.s("plays"))
        assertEquals(400, call("PUT", "tools/lifetime", "hello").code)
        call("DELETE", "tools/lifetime")
        assertEquals("null", call("GET", "tools/lifetime").text)
    }

    @Test fun venuesFollowedAndTheDiaryNeedsAUser() {
        assertEquals(0, call("GET", "venues").obj["venues"]!!.jsonArray.size)
        assertEquals(200, call("POST", "venues", """{"id":"mb:123","name":"Magnolia","city":"Milan"}""").code)
        assertEquals("Magnolia", call("GET", "venues").obj["venues"]!!.jsonArray[0].jsonObject.s("name"))
        assertEquals(200, call("DELETE", "venues/mb:123").code)
        assertEquals(0, env.store.data.settings.followedVenues.size)
        assertEquals(400, call("POST", "venues", "{}").code)
        assertEquals(400, call("GET", "diary").code)
        assertEquals(409, call("GET", "records/mine").code)
    }

    @Test fun aDrawnCoverAndServicesThatTakeNone() {
        val a = CoverDraw.png(CoverDraw.gradient("Road trip", listOf("Band", "Other Band"), 0, 200))
        val b = CoverDraw.png(CoverDraw.gradient("Road trip", listOf("Band", "Other Band"), 1, 200))
        assertTrue(a.size > 100 && a[1] == 'P'.code.toByte())
        assertTrue(!a.contentEquals(b))
        assertTrue(a.contentEquals(CoverDraw.png(CoverDraw.gradient("Road trip", listOf("Band", "Other Band"), 0, 200))))
        env.store.updateSettings { it.copy(connectors = listOf("csv")) }
        val r = call("PUT", "services/csv/playlists/In/cover", """{"variant":2}""")
        assertTrue(r.text, r.code == 400 || r.code == 404)
    }

    @Test fun sharedPlaylistsAsAMember() {
        assertEquals(0, call("GET", "shared").arr.size)
        assertEquals(400, call("POST", "shared", """{"link":"https://example.org","service":"csv"}""").code)
        val me = com.xlollx.songport.shared.Identity.create()
        val folder = com.xlollx.songport.shared.Invite("0123456789abcdef", "Friends", me.id, transport = com.xlollx.songport.shared.Invite.FOLDER).link()
        assertTrue(com.xlollx.songport.shared.Invite.parse(folder) != null)
        assertTrue(call("POST", "shared", """{"link":"$folder","service":"csv"}""").text.contains("shared folder"))
        assertEquals(404, call("DELETE", "shared/nope").code)
    }
}

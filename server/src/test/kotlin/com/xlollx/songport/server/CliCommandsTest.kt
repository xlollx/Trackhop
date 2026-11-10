package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.serialization.json.Json
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** The command line's newer commands (CliCommands.kt) and their MCP tools, against a real server as on Desktop. */
class CliCommandsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var data: File
    private lateinit var env: ServerEnv

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        LocalKey.write(data, server.address.port)
        File(data, "csv").mkdirs()
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong One,Band A\nSong Two,Band B\nSong One,Band A\n")
        env.store.upsertJob(SyncJob("j1", "Road trip", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Road trip copy")))
    }

    @After fun stop() {
        server.stop(0)
        LocalKey.reset()
    }

    private class Ran(val code: Int, val out: String, val err: String) {
        override fun toString() = "code $code\nout: $out\nerr: $err"
    }

    private fun cli(vararg args: String): Ran {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        // println ends lines with \r\n on Windows: compared as \n.
        return Ran(code, out.toString("UTF-8").replace("\r\n", "\n"), err.toString("UTF-8").replace("\r\n", "\n"))
    }

    @Test fun tagsLabelsAndFavouritesFromTheCommandLine() {
        val edited = cli("edit", "Road trip", "--tag", "genre=metal", "--tag=kids", "--favorite", "--label", "teal")
        assertEquals(edited.toString(), 0, edited.code)
        assertTrue(edited.out, edited.out.contains("genre=metal") && edited.out.contains("kids") && edited.out.contains("starred"))
        var job = env.store.job("j1")!!
        assertEquals(mapOf("genre" to "metal", "kids" to ""), job.tags)
        assertTrue(job.favorite)
        assertEquals("teal", job.label); assertEquals("teal", job.color)
        val made = cli("new", "csv", "Road trip", "csv", "--name", "Copy", "--tag", "genre:pop", "--tag", "mood=\"late night\"")
        assertEquals(made.toString(), 0, made.code)
        assertEquals(mapOf("genre" to "pop", "mood" to "late night"), env.store.data.jobs.first { it.name == "Copy" }.tags)

        // list (the same as syncs) with a tag, a key alone, a label, the star.
        val metal = cli("list", "--tag", "genre=metal")
        assertTrue(metal.out, metal.out.contains("Road trip") && !metal.out.contains("Copy  ["))
        val both = cli("syncs", "--tag", "genre")
        assertTrue(both.out, both.out.contains("Road trip  [") && both.out.contains("Copy  ["))
        assertTrue(cli("list", "--tag", "genre", "--tag", "kids").out.let { it.contains("Road trip  [") && !it.contains("Copy  [") })
        assertTrue(cli("list", "--favorite").out.let { it.contains("Road trip  [") && !it.contains("Copy  [") })
        assertTrue(cli("list", "--label", "teal").out.let { it.contains("Road trip  [") && !it.contains("Copy  [") })
        assertEquals("No sync matches.\n", cli("list", "--tag", "genre=jazz").out)
        assertEquals(2, cli("list", "--tag", "=metal").code)
        assertEquals(2, cli("list", "--tag").code)

        // A tag changed and one taken off; the star off; the label off (with its colour).
        cli("edit", "Road trip", "--untag", "KIDS", "--tag", "Genre=doom", "--unfavorite", "--label", "none")
        job = env.store.job("j1")!!
        assertEquals(mapOf("Genre" to "doom"), job.tags)
        assertFalse(job.favorite)
        assertEquals(null, job.label); assertEquals(null, job.color)

        // A group run: only the syncs with the tag.
        val run = cli("run", "--tag", "genre=doom", "--wait")
        assertEquals(run.toString(), 0, run.code)
        assertTrue(run.out, run.out.contains("Road trip: +") && !run.out.contains("Copy"))
        assertEquals(null, env.store.data.jobs.first { it.name == "Copy" }.lastReportId)
        assertTrue(cli("run", "--tag", "nothing").out.contains("nothing (no sync turned on matches)"))
        assertEquals(2, cli("run", "Road trip", "--tag", "genre").code)
        assertEquals(2, cli("run", "--all", "--favorite").code)
        assertEquals(2, cli("run").code)
    }

    @Test fun aSyncIsMadeChangedMovedPreviewedAndDeleted() {
        val made = cli("new", "csv", "Road trip", "csv", "--name", "Copy", "--schedule", "daily", "--at", "08:00", "--changes", "mirror", "--color", "blue")
        assertEquals(made.toString(), 0, made.code)
        assertTrue(made.out, made.out.contains("Made \"Copy\"") && made.out.contains("daily at 08:00"))
        val copy = env.store.data.jobs.first { it.name == "Copy" }
        assertTrue(copy.mirrorRemovals)
        assertEquals("blue", copy.color)
        val edited = cli("edit", "Copy", "--every=180", "--changes", "archive", "--color", "none")
        assertTrue(edited.toString(), edited.out.contains("every 3 h") && edited.out.contains("archive"))
        assertEquals(null, env.store.job(copy.id)!!.color)
        assertEquals(2, cli("edit", "Copy").code)
        assertEquals(2, cli("new", "csv", "Road trip", "csv", "--days", "someday").code)
        assertTrue(cli("move", "Copy", "top").out.contains("now 1 of 2"))
        assertEquals(copy.id, env.store.data.jobs.first().id)
        val preview = cli("preview", "Road trip")
        assertTrue(preview.toString(), preview.out.contains("Would add ") && preview.out.contains("+ Band A - Song One"))
        assertEquals(1, cli("stop", "Copy").code)
        assertTrue(cli("delete", "Copy").out.contains("Deleted Copy"))
        assertEquals(null, env.store.job(copy.id))
    }

    @Test fun aTwoWayPairFromTheCommandLine() {
        File(data, "csv/Other.csv").writeText("title,artist\nSong Nine,Band Z\n")
        val made = cli("new", "csv", "Road trip", "csv", "Other", "--name", "Both", "--two-way", "--two-way-removals")
        assertEquals(made.toString(), 0, made.code)
        assertTrue(made.out, made.out.contains("two-way with removals"))
        val both = env.store.data.jobs.first { it.name == "Both" }
        val twin = env.store.job(both.linkedJobId!!)!!
        assertTrue(twin.twin && twin.twoWayRemovals && both.twoWayRemovals)
        assertEquals("Other", twin.source.playlistId)
        assertTrue(cli("show", "Both").out.contains("two-way with removals"))
        val calmer = cli("edit", "Both", "--no-two-way-removals")
        assertTrue(calmer.toString(), calmer.out.contains("two-way") && !calmer.out.contains("with removals"))
        assertFalse(env.store.job(twin.id)!!.twoWayRemovals)
        // One way again: the reverse sync goes.
        val single = cli("edit", "Both", "--one-way")
        assertEquals(single.toString(), 0, single.code)
        assertFalse(single.out, single.out.contains("two-way"))
        assertEquals(null, env.store.job(twin.id))
        // Made on a sync that already exists; refused towards a playlist still to be made.
        assertEquals(0, cli("edit", "Both", "--two-way").code)
        assertTrue(env.store.job(both.id)!!.linkedJobId != null)
        val refused = cli("edit", "Road trip", "--two-way")
        assertEquals(1, refused.code); assertTrue(refused.err, refused.err.contains("existing destination"))
        assertEquals(2, cli("edit", "Both", "--two-way", "--one-way").code)
        // The assistant's tools take the same.
        val api = Cli.connect(null, null) { data }
        val off = Mcp.run(api, Mcp.callFor("edit_sync", Json.parseToJsonElement("""{"sync":"Both","two_way":false}""").jsonObject))
        assertTrue(off.second, off.first && off.second.contains("\"twoWay\": false"))
        val on = Mcp.run(api, Mcp.callFor("edit_sync", Json.parseToJsonElement("""{"sync":"Both","two_way":true,"two_way_removals":true}""").jsonObject))
        assertTrue(on.second, on.first && on.second.contains("\"twoWayRemovals\": true"))
    }

    @Test fun heldRemovalsAreConfirmedFromTheCommandLine() {
        File(data, "csv/Mirror.csv").writeText("title,artist\nSong One,Band A\n" + (1..12).joinToString("") { "Gone $it,Old Band $it\n" })
        env.store.upsertJob(SyncJob("m", "Mirrored", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, "Mirror", "Mirror"), mirrorRemovals = true))
        val held = cli("run", "Mirrored", "--wait")
        assertEquals(held.toString(), 0, held.code)
        assertTrue(held.out, held.out.contains("12 removals held back") && held.out.contains("--confirm-removals"))
        cli("preview", "Mirrored").let { assertTrue(it.toString(), it.out.contains("--confirm-removals")) }
        // Only for syncs named, never a whole group.
        assertEquals(2, cli("run", "--all", "--confirm-removals").code)
        assertEquals(2, cli("run", "--favorite", "--confirm-removals").code)
        val done = cli("run", "Mirrored", "--wait", "--confirm-removals")
        assertEquals(done.toString(), 0, done.code)
        assertTrue(done.out, done.out.contains("-12") && !done.out.contains("held back"))
        assertFalse(File(data, "csv/Mirror.csv").readText().contains("Gone"))
        // The assistant's tool passes the same switch.
        val asked = Mcp.callFor("run_sync", Json.parseToJsonElement("""{"sync":"Mirrored","confirm_removals":true}""").jsonObject)
        assertTrue(asked.opts.has("confirm-removals"))
        assertFalse(Mcp.callFor("run_sync", Json.parseToJsonElement("""{"sync":"Mirrored"}""").jsonObject).opts.has("confirm-removals"))
    }

    @Test fun theSongsWithoutMusicBrainzTagsFromTheCommandLine() {
        // No such server connected: said, as a failure.
        val none = cli("mbtags")
        assertEquals(1, none.code); assertTrue(none.err, none.err.contains("MusicBrainz"))
        val navidrome = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val songs = if (ex.requestURI.path.endsWith("search3")) ""","searchResult3":{"song":[
                    {"id":"a","title":"Tagged","artist":"Band","musicBrainzId":"5fb524f1-8cc8-4c04-a921-e34c0a911ea7"},
                    {"id":"b","title":"Bare","artist":"Band","album":"Demo"}]}""" else ""
                val body = """{"subsonic-response":{"status":"ok","version":"1.16.1"$songs}}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            env.tokens.set("subsonic", com.xlollx.songport.data.Tokens(accessToken = "pw", userName = "me", extra = mapOf("url" to "http://127.0.0.1:${navidrome.address.port}")))
            env.store.updateSettings { it.copy(connectors = it.connectors + "subsonic") }
            val listed = cli("mbtags")
            assertEquals(listed.toString(), 0, listed.code)
            assertTrue(listed.out, listed.out.contains("1 of 2 songs") && listed.out.contains("Band - Bare  [Demo]") && listed.out.contains("picard"))
            assertTrue(cli("mbtags", "subsonic", "--json").out.contains("\"untagged\": 1"))
            val tool = Mcp.run(Cli.connect(null, null) { data }, Mcp.callFor("find_untagged_songs", JsonObject(emptyMap())))
            assertTrue(tool.second, tool.first && tool.second.contains("\"title\": \"Bare\""))
        } finally {
            navidrome.stop(0)
        }
    }

    @Test fun runsReportsAndTheReview() {
        assertEquals(0, cli("run", "j1", "--wait").code)
        val runs = cli("runs", "Road trip")
        assertTrue(runs.toString(), runs.out.contains("Road trip  +2 -0"))
        val id = Regex("""\(([^)]+)\)\s*$""").find(runs.out.trim().lines().first())!!.groupValues[1]
        assertTrue(cli("report", id).out.contains("+ Band A - Song One"))
        // A run with things to review: the newest is chosen, rows named by number.
        val a = Track(id = "a", title = "Alpha", artists = listOf("One"))
        val c = Track(id = "c", title = "Gamma", artists = listOf("Three"))
        env.store.addReport(
            SyncReport(
                id = "rv", jobId = "j1", jobName = "Road trip", startedEpoch = System.currentTimeMillis(), durationMs = 1,
                unmatched = listOf(a.toString()), unmatchedTracks = listOf(a), reviewTracks = listOf(MatchReview(c, c.copy(id = "c2"), 0.7)),
            ),
        )
        val shown = cli("review", "Road trip")
        assertTrue(shown.toString(), shown.out.contains("Not found:\n  1. One - Alpha") && shown.out.contains("To check:\n  1. Three - Gamma"))
        val json = Json.parseToJsonElement(cli("--json", "review", "Road trip").out).jsonObject
        assertEquals("a", json["notFound"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(cli("review", "Road trip", "ignore", "1").toString().contains("Ignored 1"))
        assertTrue(cli("review", "Road trip", "keep", "all").out.contains("Kept 1"))
        assertTrue(cli("review", "Road trip").out.contains("Nothing to review"))
        assertEquals(2, cli("review", "Road trip", "keep", "7", "--run", "rv").code)
    }

    @Test fun servicesFilesPatternsAndTheSetUp() {
        assertTrue(cli("search", "csv", "Band A - Song One").out.contains("Song One"))
        val dupes = cli("duplicates", "csv", "Road trip")
        assertTrue(dupes.toString(), dupes.out.contains("Band A - Song One  x2"))
        assertEquals(0, cli("duplicates", "csv", "Road trip", "--remove", "--wait").code)
        assertTrue(cli("duplicates", "csv", "Road trip").out.contains("No duplicates"))
        val out = File(tmp.root, "road.m3u")
        assertTrue(cli("export", "csv", "Road trip", out.path).out.contains("Saved"))
        assertTrue(out.readText().startsWith("#EXTM3U"))
        assertTrue(cli("files", "get", "Road trip").out.startsWith("title,artist"))
        val zip = File(tmp.root, "all.zip")
        assertEquals(0, cli("files", "zip", zip.path, "--format", "txt").code)
        assertTrue(zip.length() > 0)
        // Patterns: set, tried, cleared.
        assertTrue(cli("patterns", "set", "{artist}/{album}/{track} {title}").out.contains("{artist}/{album}/{track} {title}"))
        assertEquals("CUSTOM", env.store.data.settings.fileNameLayout)
        assertTrue(cli("patterns", "try", "Music/Band/Album/01 Song.flac").out.contains("Artist: Band"))
        assertEquals(1, cli("patterns", "try", "nothing here", "--pattern", "{title} ({artist})").code)
        assertEquals(1, cli("patterns", "set", "{artist} - [{title}").code)
        cli("patterns", "clear")
        assertEquals("AUTO", env.store.data.settings.fileNameLayout)
        // The set-up, out and back in (a dry run first).
        val setUp = File(tmp.root, "setup.trackhop")
        assertEquals(0, cli("config", setUp.path).code)
        env.store.deleteJob("j1")
        assertTrue(cli("config", "import", setUp.path, "--dry-run").out.contains("Nothing changed"))
        assertEquals(null, env.store.job("j1"))
        assertTrue(cli("config", "import", setUp.path).out.contains("Imported 1 syncs"))
        assertEquals("Road trip", env.store.job("j1")!!.name)
        assertTrue(cli("files", "rm", "Road trip").out.contains("Deleted Road trip.csv"))
        assertFalse(File(data, "csv/Road trip.csv").exists())
    }

    @Test fun notificationsWebhookSettingsAndTheRest() {
        assertTrue(cli("notify", "kinds").out.contains("--set topic="))
        val added = cli("notify", "add", "ntfy", "--name", "Phone", "--set", "topic=trackhop-test", "--events", "failed,changes")
        assertEquals(added.toString(), 0, added.code)
        assertTrue(cli("notify", "off", "Phone").out.contains("[off]"))
        assertTrue(cli("notify", "edit", "Phone", "--name", "Tablet").out.contains("Tablet"))
        assertTrue(cli("notify", "rm", "Tablet").out.contains("Removed Tablet"))
        assertEquals(2, cli("notify", "add", "ntfy", "--set", "topic").code)
        val hook = cli("webhook", "set", "https://example.org/hook", "--secret", "s3cret", "--events", "sync.failed")
        assertTrue(hook.toString(), hook.out.contains("(signed)") && hook.out.contains("sync.failed") && !hook.out.contains("s3cret"))
        assertTrue(cli("webhook", "off").out.contains("No webhook"))
        assertTrue(cli("settings", "set", "theme", "dark").out.contains("theme: dark"))
        assertEquals(1, cli("settings", "set", "theme", "neon").code)
        assertEquals(0, cli("stats").code)
        assertEquals(0, cli("tasks").code)
        assertEquals(0, cli("log", "--lines", "3").code)
    }

    @Test fun helpForEachCommandAndShellCompletions() {
        val all = cli("help").out
        for (c in Cli.COMMANDS.filter { it.name !in setOf("disable", "pause", "resume", "task") }) assertTrue("help lacks ${c.name}", all.contains(c.usage.first()))
        assertTrue(cli("help", "review").out.contains("trackhop review <sync> pick <n> <track>"))
        assertTrue(cli("new", "--help").out.contains("--changes add|mirror|archive"))
        assertEquals(2, cli("help", "frobnicate").code)
        assertTrue(cli("completion", "bash").out.contains("complete -F _trackhop trackhop"))
        assertTrue(cli("completion", "zsh").out.startsWith("#compdef trackhop"))
        assertTrue(cli("completion", "fish").out.contains("-a review"))
        assertEquals(2, cli("completion", "tcsh").code)
        assertEquals(2, cli("run", "--frobnicate").code)
        // Words after -- are words, even with dashes.
        assertEquals(1, cli("show", "--", "--not-a-sync").code)
    }

    @Test fun everyCommandHasItsMcpTool() {
        // The tools that change things say so; the destructive ones say that too.
        val list = Mcp.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), "test") { error("no call") }!!
        val tools = list["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        fun hint(name: String, k: String) = tools.first { it["name"]!!.jsonPrimitive.content == name }["annotations"]!!.jsonObject[k]?.jsonPrimitive?.content
        for (t in listOf("delete_sync", "review_tracks", "remove_duplicates", "delete_files")) assertEquals(t, "true", hint(t, "destructiveHint"))
        for (t in listOf("preview_sync", "list_runs", "get_review", "search_tracks", "convert_tracks", "find_duplicates", "find_untagged_songs", "export_playlist", "get_stats")) assertEquals(t, "true", hint(t, "readOnlyHint"))
        // And they run the commands.
        val api = Cli.connect(null, null) { data }
        fun call(tool: String, args: String): Pair<Boolean, String> = Mcp.run(api, Mcp.callFor(tool, Json.parseToJsonElement(args).jsonObject))
        val made = call("create_sync", """{"source_service":"csv","source_playlist":"Road trip","target_service":"csv","name":"By AI","schedule":"weekly","days":"sat","at":"09:00"}""")
        assertTrue(made.second, made.first && made.second.contains("\"timing\": \"every Sat at 09:00\""))
        val preview = call("preview_sync", """{"sync":"By AI"}""")
        assertTrue(preview.second, preview.first && preview.second.contains("toAdd"))
        assertTrue(call("edit_sync", """{"sync":"By AI","every":60}""").second.contains("hourly"))
        assertTrue(call("search_tracks", """{"service":"csv","query":"Band B - Song Two","limit":5}""").first)
        assertTrue(call("try_file_patterns", """{"sample":"Band - Song","patterns":["{artist} - {title}"]}""").second.contains("\"matched\": true"))
        assertTrue(call("delete_sync", """{"sync":"By AI"}""").first)
        // A pick without the track to use is refused before anything is called.
        val review = runCatching { Mcp.callFor("review_tracks", Json.parseToJsonElement("""{"sync":"Road trip","action":"pick","tracks":["x"]}""").jsonObject) }
        assertTrue(review.exceptionOrNull()?.message.orEmpty().contains("choice"))
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
}

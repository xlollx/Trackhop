package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * A sync run by hand, or a long tool, that the stop of the server cut short (RunMarks): the next
 * start says "Interrupted by a restart" on the page (with Retry), in the API and in the command
 * line, and never runs it again by itself.
 */
class RunMarksTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var data: File
    private var server: HttpServer? = null
    private val client = HttpClient.newHttpClient()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        Log.reset()
    }

    @After fun stop() {
        server?.stop(0)
        LocalKey.reset()
    }

    private fun env() = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")

    /** The server as it comes up on the data folder: what the last one left running is found, then the pages are served. */
    private fun restarted(): ServerEnv {
        val env = env()
        RunMarks.afterRestart(env)
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0).also { LocalKey.write(data, it.address.port) }
        return env
    }

    private fun get(path: String) =
        client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.address.port}$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String = "") = client.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:${server!!.address.port}$path")).header("Authorization", auth)
            .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "http://127.0.0.1:${server!!.address.port}")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun cli(vararg args: String): String {
        val out = ByteArrayOutputStream()
        Cli.run(args.toList(), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(ByteArrayOutputStream(), true, "UTF-8")) { data }
        return out.toString("UTF-8").replace("\r\n", "\n")
    }

    private fun job(env: ServerEnv) = SyncJob("j1", "Road trip", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Road trip copy"))
        .also { env.store.upsertJob(it); env.store.flush() }

    @Test fun aRunThatEndsLeavesNoMark() {
        val env = env()
        val gate = CompletableDeferred<Unit>()
        val task = Tasks.start("Remove duplicates", "/tools/dedupe?service=x", {}, env.owner, env.marks) { gate.await(); "done" }
        // While it runs it is written on the disk, where a restart finds it.
        assertTrue(File(data, "kv/running.json").readText().contains(task.id))
        assertTrue("running is not interrupted", env.marks.interrupted().isEmpty())
        gate.complete(Unit)
        val until = System.currentTimeMillis() + 10_000
        while (!task.done && System.currentTimeMillis() < until) Thread.sleep(10)
        while (File(data, "kv/running.json").readText().contains(task.id) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(File(data, "kv/running.json").readText().contains(task.id))
        // The next start has nothing to say.
        assertTrue(RunMarks.afterRestart(env()).isEmpty())
    }

    @Test fun startingATaskAgainClearsItsOlderInterruptedMarks() {
        val marks = env().marks
        marks.begin(RunMarks.TASK, "t1", "Full backup", "/tools/backup", now = 1)
        marks.begin(RunMarks.TASK, "t2", "Remove duplicates", "/tools/dedupe", now = 2)
        marks.begin(RunMarks.SYNC, "s1", "Full backup", now = 3)
        assertEquals(3, marks.afterRestart().size)
        // Tried again: a new task, a new id.
        marks.begin(RunMarks.TASK, "t3", "Full backup", "/tools/backup", now = 4)
        assertEquals(setOf("t2", "s1"), marks.interrupted().map { it.id }.toSet())
    }

    @Test fun aTaskStillRunningInThisServerIsNotCalledInterrupted() {
        val env = env()
        val gate = CompletableDeferred<Unit>()
        val task = Tasks.start("Full backup", "/tools/backup", {}, env.owner, env.marks) { gate.await(); "done" }
        // The person's engine is set up again while the server keeps going (a shared server).
        assertTrue(RunMarks.afterRestart(env()).isEmpty())
        gate.complete(Unit)
        val until = System.currentTimeMillis() + 10_000
        while (!task.done && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test fun aManualRunCutByARestartIsSaidWithRetryAndNeverStartedAgain() {
        // The server that was stopped: a sync run by hand and a tool were under way.
        env().let { before ->
            job(before)
            before.marks.begin(RunMarks.SYNC, "j1", "Road trip", "/syncs/j1", now = 1_700_000_000_000)
            before.marks.begin(RunMarks.TASK, "task-1", "Remove duplicates", "/tools/dedupe?service=csv&playlist=Road+trip")
        }
        val env = restarted()

        // Nothing started by itself.
        assertTrue(SyncState.running.value.isEmpty())
        assertTrue(Tasks.all().none { it.title == "Remove duplicates" && !it.done })
        assertEquals(setOf("j1", "task-1"), env.marks.interrupted().map { it.id }.toSet())

        // The sync's history has the interruption as its last result.
        val last = env.store.job("j1")!!.lastReportId?.let { env.store.report(it) }
        assertNotNull(last)
        assertTrue(last!!.error!!, last.error!!.startsWith("Interrupted by a restart"))
        assertTrue(Log.recent().toString(), Log.recent().any { it.contains("Interrupted by a restart: Road trip") })

        // The page: said, with Retry for the sync and for the tool (its page, as it was asked).
        val home = get("/").body()
        assertTrue(home, home.contains("Interrupted by a restart") && home.contains(">Retry</button>"))
        assertTrue(home, home.contains("""href="/tools/dedupe?service=csv&amp;playlist=Road+trip">Retry</a>"""))
        val taskPage = get("/tools/task/task-1")
        assertEquals(200, taskPage.statusCode())
        assertTrue(taskPage.body(), taskPage.body().contains("Interrupted by a restart") && !taskPage.body().contains("no longer kept"))

        // The API and the command line.
        val status = Json.parseToJsonElement(cli("status", "--json")).jsonObject
        assertEquals(listOf("sync", "task"), status["interrupted"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }.sorted())
        val said = cli("status")
        assertTrue(said, said.contains("Interrupted by a restart: Road trip") && said.contains("Interrupted by a restart: Remove duplicates"))
        val asTask = cli("task", "task-1")
        assertTrue(asTask, asTask.contains("failed: Interrupted by a restart"))
        assertTrue(cli("syncs", "--json"), Json.parseToJsonElement(cli("syncs", "--json")).jsonArray.single().jsonObject["interrupted"]!!.jsonPrimitive.content == "true")

        // A second restart does not say it twice, nor add a second report.
        val reports = env.store.data.reports.size
        assertTrue(RunMarks.afterRestart(env).isEmpty())
        assertEquals(reports, env.store.data.reports.size)

        // Dismiss takes the tool's note away and runs nothing; Retry runs the sync, whose note goes.
        post("/tools/interrupted", "kind=task&id=task-1&back=/")
        assertNull(env.marks.interrupted(RunMarks.TASK, "task-1"))
        assertEquals(404, get("/tools/task/task-1").statusCode())
        File(data, "csv").mkdirs()
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong One,Band A\n")
        post("/syncs/j1/run")
        val until = System.currentTimeMillis() + 20_000
        while ((SyncState.running.value.containsKey("j1") || env.store.job("j1")!!.lastReportId == last.id) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertNull(env.marks.interrupted(RunMarks.SYNC, "j1"))
        assertTrue(env.marks.interrupted().isEmpty())
        assertFalse(File(data, "kv/running.json").readText().contains("j1"))
    }
}

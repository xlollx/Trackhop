package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** The commands of the app's features brought to Desktop and Docker in 2.2 (CliParity.kt), against a real server as on Desktop. */
class ParityCliTest {
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
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong One,Band A\nSong Two,Band B\n")
        env.store.upsertJob(SyncJob("j1", "Road trip", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Road trip copy")))
        env.store.upsertJob(SyncJob("j2", "Other", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Other copy")))
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
        return Ran(code, out.toString("UTF-8").replace("\r\n", "\n"), err.toString("UTF-8").replace("\r\n", "\n"))
    }

    @Test fun matchingAndOrderFromTheCommandLine() {
        val r = cli("edit", "Road trip", "--strictness", "strict", "--studio-only", "--keep-order")
        assertEquals(r.toString(), 0, r.code)
        val job = env.store.job("j1")!!
        assertEquals(1, job.policy.strictness)
        assertTrue(job.policy.studioOnly && job.keepOrder)
        assertEquals(1, cli("edit", "Road trip", "--strictness", "very").code)
    }

    @Test fun aTwinsCopyIsFoundByItsIdBeforeItsNumber() {
        fun o(id: String) = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive(id)) }
        val copies = listOf(o("9"), o("1"), o("abc"))
        assertEquals(o("1"), byIdOrNumber(copies, "id", "1"))
        assertEquals(o("1"), byIdOrNumber(copies, "id", "2"))
        assertEquals(o("abc"), byIdOrNumber(copies, "id", "3"))
        assertEquals(o("abc"), byIdOrNumber(copies, "id", "abc"))
        assertEquals(null, byIdOrNumber(copies, "id", "4"))
        assertEquals(null, byIdOrNumber(copies, "id", null))
    }

    @Test fun servicesAreFound() {
        assertEquals(0, cli("services", "--kind", "official").code)
        assertTrue(cli("services", "nothing-like-it").out.contains("No service matches"))
    }

    @Test fun theWeeklyUnplayableCheck() {
        assertTrue(cli("unplayable", "weekly", "on").out.contains(": on"))
        assertTrue(env.store.data.settings.unplayableCheck)
        assertTrue(cli("unplayable", "weekly", "off").out.contains(": off"))
        val now = cli("unplayable", "weekly", "now", "--wait")
        assertEquals(now.toString(), 0, now.code)
        assertTrue(cli("unplayable", "weekly").out.contains("No connected service"))
        assertEquals(2, cli("unplayable").code)
    }

    @Test fun toFixFromTheCommandLine() {
        assertTrue(cli("tofix").out.contains("Nothing to fix"))
        val missing = com.xlollx.songport.model.Track("x1", "Lost Song", listOf("Nobody"))
        env.store.addReport(com.xlollx.songport.model.SyncReport("r1", "j1", "Road trip", 1_780_000_000_000, 10, unmatched = listOf(missing.toString()), unmatchedTracks = listOf(missing)))
        assertTrue(cli("tofix").out.contains("1. Nobody - Lost Song"))
        assertTrue(cli("tofix", "later", "1").out.contains("0 left"))
        assertTrue(cli("tofix").out.contains("Later"))
        assertTrue(cli("tofix", "back", "all").out.contains("1 left"))
        assertTrue(cli("tofix", "ignore", "1").out.contains("0 left"))
        assertEquals(1, cli("tofix", "use", "7").code)
    }

    @Test fun savedSchedulesAndSeveralSyncsAtOnce() {
        val made = cli("schedules", "add", "Mornings", "--every", "1440", "--at", "7:30")
        assertEquals(made.toString(), 0, made.code)
        assertTrue(made.out, made.out.contains("Mornings") && made.out.contains("07:30"))
        assertEquals(0, cli("edit", "Road trip", "--use-schedule", "mornings").code)
        val id = env.store.data.settings.savedSchedules.single().id
        assertEquals(id, env.store.job("j1")!!.scheduleId)
        assertEquals("07:30", env.store.job("j1")!!.at)
        // Changed: the sync follows.
        cli("schedules", "edit", "Mornings", "--at", "08:15")
        assertEquals("08:15", env.store.job("j1")!!.at)
        val many = cli("edit-many", "Road trip", "Other", "--every", "360", "--label", "teal")
        assertEquals(many.toString(), 0, many.code)
        assertTrue(many.out.contains("2 syncs changed"))
        assertEquals(listOf(360, 360), listOf("j1", "j2").map { env.store.job(it)!!.everyMinutes })
        assertEquals(null, env.store.job("j1")!!.scheduleId)
        assertEquals("teal", env.store.job("j2")!!.label)
        assertTrue(cli("schedules", "rm", "Mornings").out.contains("Deleted"))
        assertTrue(cli("schedules").out.contains("No saved schedule"))
        assertEquals(2, cli("edit-many", "Road trip").code)
    }
}

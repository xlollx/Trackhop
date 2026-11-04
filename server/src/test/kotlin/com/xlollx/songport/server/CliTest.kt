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

/** `trackhop`, the command line, against a real server on a free port, as on Desktop or in the container. */
class CliTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var data: File
    private lateinit var env: ServerEnv

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
        // With a password for the pages, as a server has: the command line does not need it.
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        LocalKey.write(data, server.address.port)
        File(data, "csv").mkdirs()
        File(data, "csv/Road trip.csv").writeText("title,artist\nSong One,Band A\nSong Two,Band B\n")
        env.store.upsertJob(SyncJob("j1", "Road trip", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Road trip copy")))
        env.store.upsertJob(SyncJob("j2", "Road trip 2", PlaylistRef(LocalFilesProvider.id, "Road trip", "Road trip"), PlaylistRef(LocalFilesProvider.id, null, "Other copy")))
    }

    @After fun stop() {
        server.stop(0)
        LocalKey.reset()
    }

    private class Ran(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String, env: Map<String, String> = emptyMap(), dir: File = data): Ran {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(args.toList(), env, PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { dir }
        return Ran(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test fun findsTheRunningTrackhopWithNothingSetUp() {
        val status = cli("status")
        assertEquals(0, status.code)
        assertTrue(status.out, status.out.contains("2 syncs, none running"))
        val list = cli("syncs")
        assertTrue(list.out, list.out.contains("Road trip  [manual]"))
        assertTrue(list.out, list.out.contains("File: Road trip -> File: Road trip copy"))
        val json = cli("--json", "syncs")
        assertTrue(json.out, json.out.trim().startsWith("[") && json.out.contains("\"id\": \"j1\""))
    }

    @Test fun aSyncIsNamedByItsNameOrIdAndAmbiguityIsSaid() {
        assertTrue(cli("show", "road trip").out.contains("Road trip  (j1)"))
        assertTrue(cli("show", "j2").out.contains("Road trip 2  (j2)"))
        // An archive says so.
        env.store.upsertJob(env.store.job("j2")!!.copy(archive = true))
        assertTrue(cli("show", "j2").out.contains("keeps every track (archive)"))
        assertTrue(cli("--json", "show", "j2").out.contains("\"archive\": true"))
        val unsure = cli("show", "Road")
        assertEquals(1, unsure.code)
        assertTrue(unsure.err, unsure.err.contains("could be"))
        assertEquals(1, cli("show", "Jazz").code)
    }

    @Test fun runWaitsAndPrintsTheResult() {
        val ran = cli("run", "j1", "--wait")
        assertEquals(ran.err, 0, ran.code)
        assertTrue(ran.out, ran.out.contains("Road trip: +2 -0"))
        assertTrue(File(data, "csv/Road trip copy.csv").readText().contains("Song Two"))
        val off = cli("disable", "j1")
        assertTrue(off.out, off.out.contains("Road trip: off"))
        assertEquals(false, env.store.job("j1")!!.enabled)
    }

    @Test fun filesComeInAndAreListed() {
        val f = tmp.newFile("Focus.m3u").apply { writeText("#EXTM3U\n#EXTINF:200,Band C - Song Three\nsong.mp3\n") }
        val imported = cli("import", f.path)
        assertEquals(imported.err, 0, imported.code)
        assertTrue(imported.out, imported.out.contains("1 tracks"))
        assertTrue(cli("files").out.contains("Focus"))
    }

    @Test fun withoutARunningTrackhopItSaysSo() {
        val none = cli("status", dir = tmp.newFolder("empty"))
        assertEquals(3, none.code)
        assertTrue(none.err, none.err.contains("no Trackhop is running"))
        assertEquals(2, cli("frobnicate").code)
        assertEquals(0, cli("help").code)
    }

    @Test fun onASharedServerUserPicksWhoseTrackhop() {
        val shared = tmp.newFolder("shared")
        val anna = cli("--user", "Anna", "status", dir = shared)
        assertEquals(3, anna.code)
        assertTrue(anna.err, anna.err.contains(File(File(shared, "users"), "anna").path))
        assertTrue(cli("status", env = mapOf("TRACKHOP_USER" to "marco"), dir = shared).err.contains(File(File(shared, "users"), "marco").path))
        // The admin's is the data folder's own.
        assertEquals(0, cli("--user", "admin", "status").code)
        assertEquals(2, cli("--user", "../etc", "status").code)
    }

    @Test fun aServerElsewhereNeedsTheApiToken() {
        val url = "http://127.0.0.1:${server.address.port}"
        val off = cli("--url", url, "--token", "th_wrong", "status")
        assertEquals(3, off.code)
        assertTrue(off.err, off.err.contains("The API is off"))
        val token = Api.newToken(env)
        assertEquals(0, cli("status", env = mapOf("TRACKHOP_URL" to url, "TRACKHOP_TOKEN" to token)).code)
        assertEquals(3, cli("--url", url, "--token", "th_wrong", "status").code)
    }
}

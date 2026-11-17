package com.xlollx.songport.server

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.providers.LocalFilesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The server stopped, the disk full or the power cut while a file was being written: what was saved
 * stays readable, what cannot be read is kept aside and said on the pages, and a copy to the backup
 * folder is whole or not there.
 */
class InterruptedStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun env(dir: File = tmp.newFolder()) = ServerEnv(dir, Strings(emptyMap()), "http://localhost:8080")
    private fun damagedCopies(dir: File, name: String) = dir.listFiles { f -> f.name.startsWith("$name.damaged-") }.orEmpty().toList()

    @Test fun signInsThatCannotBeReadAreKeptAsideAndSaid() {
        val dir = tmp.newFolder()
        val file = File(dir, "tokens.json")
        val broken = """{"spotify":{"accessToken":"a","refr"""
        file.writeText(broken)
        val e = env(dir)
        assertNull(e.tokens.get("spotify"))
        assertEquals(listOf(broken), damagedCopies(dir, "tokens.json").map { it.readText() })
        assertTrue(e.storageTrouble().orEmpty().contains("sign-ins"))
        // Connecting again writes a new file; the copy aside still holds the old one.
        e.tokens.set("deezer", Tokens(accessToken = "b"))
        assertEquals("b", JsonTokenVault(file).get("deezer")?.accessToken)
        assertEquals(listOf(broken), damagedCopies(dir, "tokens.json").map { it.readText() })
    }

    @Test fun aStoreThatCannotBeReadIsSaidOnThePagesAndAHealthyOneSaysNothing() {
        assertNull(env().storageTrouble())
        val dir = tmp.newFolder()
        File(dir, "store.json").writeText("""{"jobs":[{"id":"j","na""")
        val e = env(dir)
        val said = e.storageTrouble()
        assertNotNull(said)
        assertTrue(said!!, said.contains(e.store.damagedCopy!!.name) && said.contains("Nothing was deleted"))
    }

    @Test fun aWriteThatFailsLeavesTheFileAndNoPieceBeside() {
        val file = File(tmp.newFolder(), "tokens.json")
        writePrivate(file, "{}")
        // The disk refuses the file beside it (here: its name is taken by a folder).
        val blocker = File(file.parentFile, "tokens.json.tmp").also { it.mkdirs(); File(it, "x").writeText("x") }
        assertTrue(runCatching { writePrivate(file, """{"a":"b"}""") }.isFailure)
        assertEquals("{}", file.readText())
        blocker.deleteRecursively()
        writePrivate(file, """{"a":"b"}""")
        assertEquals("""{"a":"b"}""", file.readText())
        assertEquals(listOf("tokens.json"), file.parentFile.list()!!.toList())
    }

    @Test fun aCopyToTheBackupFolderStoppedHalfWayIsMadeAgainWhole() {
        val e = env()
        val folder = tmp.newFolder("copies")
        e.store.updateSettings { it.copy(backupFolder = folder.path) }
        File(LocalFilesProvider.dir(e), "Road trip.csv").writeText("title,artist\nSong,Band\n")
        val blocker = File(folder, ".Road trip.csv.tmp").also { it.mkdirs(); File(it, "x").writeText("x") }
        assertTrue(runCatching { Backups.copyToFolder(e) }.isFailure)
        assertFalse("no file cut short under the playlist's name", File(folder, "Road trip.csv").exists())
        blocker.deleteRecursively()
        assertEquals("the file and the set-up", 2, Backups.copyToFolder(e))
        assertEquals("title,artist\nSong,Band\n", File(folder, "Road trip.csv").readText())
        assertEquals("nothing left to copy", 0, Backups.copyToFolder(e))
    }
}

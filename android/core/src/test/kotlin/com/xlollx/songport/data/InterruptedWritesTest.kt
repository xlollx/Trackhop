package com.xlollx.songport.data

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Writes that are stopped half way (a full disk, the process killed, the phone switched off): the
 * file keeps what it held, the state that could not be read is kept aside instead of being written
 * over, and a restore never leaves a file cut short under its final name.
 */
class InterruptedWritesTest {
    private fun dir() = Files.createTempDirectory("interrupted").toFile()
    private fun sync(id: String) = SyncJob(id = id, name = id, source = PlaylistRef("a", id), target = PlaylistRef("b"))
    /** Whatever [AtomicFiles] writes for [f] fails: its file beside is a folder that cannot be opened as a file. */
    private fun block(f: File) = File(f.parentFile, ".${f.name}.tmp").also { it.mkdirs(); File(it, "x").writeText("x") }

    @Test fun aWriteReplacesTheFileAndLeavesNothingBeside() {
        val f = File(dir(), "a.json")
        AtomicFiles.write(f, "one")
        AtomicFiles.write(f, "two")
        assertEquals("two", f.readText())
        assertEquals(listOf("a.json"), f.parentFile.list()!!.toList())
    }

    @Test fun aWriteThatFailsLeavesTheFileAsItWas() {
        val f = File(dir(), "a.json")
        AtomicFiles.write(f, "the last good state")
        val blocker = block(f)
        assertTrue(runCatching { AtomicFiles.write(f, "half") }.isFailure)
        assertEquals("the last good state", f.readText())
        blocker.deleteRecursively()
        AtomicFiles.write(f, "the next state")
        assertEquals("the next state", f.readText())
    }

    @Test fun aCopyThatFailsLeavesNoFileUnderTheName() {
        val d = dir()
        val from = File(d, "from.csv").apply { writeText("a,b\n1,2\n") }
        val to = File(File(d, "out"), "to.csv")
        val blocker = block(to)
        assertTrue(runCatching { AtomicFiles.copy(from, to) }.isFailure)
        assertFalse(to.exists())
        blocker.deleteRecursively()
        AtomicFiles.copy(from, to)
        assertEquals(from.readText(), to.readText())
    }

    @Test fun aStoreThatCannotBeReadIsKeptAsideNotWrittenOver() {
        val d = dir()
        // Cut short, as a power cut leaves it.
        val broken = """{"jobs":[{"id":"j","name":"Gym","source":{"provider":"spo"""
        File(d, "store.json").writeText(broken)
        val store = FileStore(d)
        val kept = store.damagedCopy
        assertNotNull("the unreadable state is kept", kept)
        assertTrue(kept!!.name.startsWith(FileStore.DAMAGED_PREFIX))
        assertEquals(broken, kept.readText())
        assertTrue(store.data.jobs.isEmpty())
        // The empty start is saved over store.json; the copy aside still holds what was there.
        store.upsertJob(sync("new"))
        store.flush()
        assertEquals(broken, kept.readText())
        val again = FileStore(d)
        assertNull("readable again: nothing to say", again.damagedCopy)
        assertEquals(listOf("new"), again.data.jobs.map { it.id })
    }

    @Test fun aReadableOrMissingStoreKeepsNothingAside() {
        val d = dir()
        assertNull(FileStore(d).damagedCopy)
        FileStore(d).apply { upsertJob(sync("a")); flush() }
        val store = FileStore(d)
        assertNull(store.damagedCopy)
        assertEquals(listOf("a"), store.data.jobs.map { it.id })
        assertTrue(d.list()!!.none { it.startsWith(FileStore.DAMAGED_PREFIX) })
    }

    @Test fun aStateThatCannotBeWrittenIsSaidAndTheLastOneStays() {
        val d = dir()
        val store = FileStore(d)
        store.upsertJob(sync("a"))
        store.flush()
        assertNull(store.writeError)
        val saved = File(d, "store.json").readText()
        val blocker = block(File(d, "store.json"))
        store.upsertJob(sync("b"))
        store.flush()
        assertNotNull("a failed write is not silent", store.writeError)
        assertEquals(saved, File(d, "store.json").readText())
        // The disk has room again: the next write carries everything, and the error is gone.
        blocker.deleteRecursively()
        store.flush()
        assertNull(store.writeError)
        assertEquals(listOf("a", "b"), FileStore(d).data.jobs.map { it.id })
    }

    @Test fun aRestoreStoppedWhileWritingAFileLeavesNoHalfFileAndBringsNothingIn() {
        val from = ContractEnv()
        LocalFilesProvider.dir(from).mkdirs()
        File(LocalFilesProvider.dir(from), "Road trip.csv").writeText("title,artist\nSong,Band\n")
        from.store.upsertJob(sync("s"))
        val snap = ConfigBackup.read(ConfigBackup.export(from, "test"))
        assertEquals(1, snap.files.size)

        val here = ContractEnv()
        val target = File(LocalFilesProvider.dir(here), snap.files.keys.first().let { com.xlollx.songport.sync.CsvCodec.safeName(it) } + ".csv")
        val blocker = block(target)
        assertTrue(runCatching { ConfigBackup.import(here, snap) }.isFailure)
        assertFalse("no file cut short under the playlist's name", target.exists())
        assertTrue("the syncs are not brought in without their files", here.store.data.jobs.isEmpty())
        // Tried again once the disk lets it: everything arrives, once.
        blocker.deleteRecursively()
        val r = ConfigBackup.import(here, snap)
        assertEquals(1, r.files)
        assertEquals(snap.files.values.first(), target.readText())
        assertEquals(listOf("s"), here.store.data.jobs.map { it.id })
    }
}

package com.xlollx.songport.data

import com.xlollx.songport.sync.BackupExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A file saved where the picker said: whole, or said as not saved with what was written taken away.
 * Never "exported" for a file that could not be opened, never half a playlist left looking whole.
 */
class DocumentWriteTest {
    private val words = "The file could not be saved"

    @Test fun aFileWrittenWholeIsKept() {
        val out = ByteArrayOutputStream()
        var discarded = false
        DocumentWrite.whole({ out }, { discarded = true }, words) { it.write("a,b\n1,2\n".toByteArray()) }
        assertEquals("a,b\n1,2\n", out.toString())
        assertFalse(discarded)
    }

    @Test fun aFileThatCannotBeOpenedIsNotReportedAsSaved() {
        var discarded = false
        val e = runCatching { DocumentWrite.whole({ null }, { discarded = true }, words) { it.write(1) } }.exceptionOrNull()
        assertTrue("$e", e is DocumentWrite.NotSaved)
        assertEquals(words, e!!.message)
        assertTrue("the empty file the picker made is removed", discarded)
    }

    @Test fun aWriteStoppedHalfWayRemovesTheHalfFileAndSaysSo() {
        // No space left after the first bytes.
        val full = object : OutputStream() {
            var written = 0
            override fun write(b: Int) { if (++written > 4) throw IOException("ENOSPC") }
        }
        var discarded = 0
        val e = runCatching { DocumentWrite.whole({ full }, { discarded++ }, words) { it.write("title,artist\nSong,Band\n".toByteArray()) } }.exceptionOrNull()
        assertTrue(e is DocumentWrite.NotSaved)
        assertEquals("plain words for the person, the cause for the log", words to "ENOSPC", e!!.message to e.cause?.message)
        assertEquals(1, discarded)
    }

    @Test fun aRemovalThatFailsStillSaysTheFileWasNotSaved() {
        val e = runCatching { DocumentWrite.whole({ null }, { throw SecurityException("no delete here") }, words) { } }.exceptionOrNull()
        assertTrue("$e", e is DocumentWrite.NotSaved)
    }

    @Test fun theBackupFolderTakesWhatIsMissingAndTheFileCutShortLastTime() {
        val inFolder = setOf("spotify-Gym-2026-11-16.csv", "spotify-Gym-2026-11-17.csv")
        assertTrue("not there yet", BackupExport.needsCopy("deezer-Run-2026-11-17.csv", inFolder, null))
        assertFalse("there, whole: left alone", BackupExport.needsCopy("spotify-Gym-2026-11-16.csv", inFolder, null))
        // The app was closed while this one was being copied: what the folder holds under its name may be half of it.
        assertTrue(BackupExport.needsCopy("spotify-Gym-2026-11-17.csv", inFolder, "spotify-Gym-2026-11-17.csv"))
        assertFalse("the others are not copied again", BackupExport.needsCopy("spotify-Gym-2026-11-16.csv", inFolder, "spotify-Gym-2026-11-17.csv"))
    }
}

package com.xlollx.songport.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A file written whole or not at all: the text goes to a file beside it, is pushed to the disk, and
 * only then takes the name. A process killed, a phone switched off or a full disk half way leaves
 * the file as it was (or absent), never cut short: what reads it next finds the old content, and a
 * restore that looks for "a file with this name" never takes half of one for the whole.
 */
object AtomicFiles {
    fun write(file: File, text: String) = write(file, text.toByteArray(Charsets.UTF_8))

    fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // On the disk before it takes the name: after a power cut the name never points at an empty file.
                runCatching { out.fd.sync() }
            }
            move(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** [from] copied to [to] the same way: beside it first, then under its name. */
    fun copy(from: File, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, ".${to.name}.tmp")
        try {
            from.inputStream().use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out); out.flush(); runCatching { out.fd.sync() } } }
            move(tmp, to)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** On Windows a file being read cannot be replaced for an instant: tried again for up to a second. */
    private fun move(tmp: File, file: File) {
        var attempt = 0
        while (true) {
            try {
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: IOException) {
                if (++attempt >= 20 || !tmp.exists() || file.isDirectory) throw e
                Thread.sleep(50)
            }
        }
    }
}

package com.xlollx.songport.server

import com.xlollx.songport.data.AtomicFiles
import java.io.File

/**
 * The files the command line saves (an export, the set-up, a report): written whole or not at all.
 * Interrupted half way (Ctrl+C, a full disk), the name keeps what it held before, or stays absent:
 * never half a playlist that reads as a whole one.
 */
internal object CliFiles {
    fun save(path: String, text: String) = save(path, text.toByteArray(Charsets.UTF_8))

    fun save(path: String, bytes: ByteArray) {
        val file = File(path).absoluteFile
        // A pipe or a device (/dev/stdout) cannot be replaced by a rename: written straight, as before.
        if (file.exists() && !file.isFile) file.writeBytes(bytes) else AtomicFiles.write(file, bytes)
    }
}

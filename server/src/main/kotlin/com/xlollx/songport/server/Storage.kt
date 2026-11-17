package com.xlollx.songport.server

import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.net.json
import com.xlollx.songport.platform.KeyValues
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Writes [text] to [file] atomically (temporary file, then rename), readable by the owner only. */
internal fun writePrivate(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    try {
        java.io.FileOutputStream(tmp).use { out ->
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            // On the disk before it takes the name: a power cut never leaves the name on an empty file.
            runCatching { out.fd.sync() }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        // A full disk half way: the file keeps what it held, and no piece is left beside it.
        tmp.delete()
        throw e
    }
}

/**
 * [file] is there but cannot be read (cut short, damaged): a copy of it is kept beside it, as
 * `<name>.damaged-<time>`, before anything is written over it, and the log says so. What it held
 * (the sign-ins, the notification channels) starts empty; the copy is what a repair starts from.
 */
internal fun keepDamaged(file: File): File? = runCatching {
    val keep = File(file.parentFile, "${file.name}.damaged-${System.currentTimeMillis()}")
    // Readable by the owner only, as the file it copies (sign-ins), before a byte is written in it.
    keep.writeBytes(ByteArray(0))
    runCatching { Files.setPosixFilePermissions(keep.toPath(), PosixFilePermissions.fromString("rw-------")) }
    file.inputStream().use { i -> java.io.FileOutputStream(keep).use { i.copyTo(it) } }
    keep
}.getOrNull().also {
    Log.add("storage", "${file.name} could not be read, so what it held starts empty" + (it?.let { k -> "; the unreadable file is kept as ${k.name}" } ?: ""))
}

/** [file]'s JSON read by [decode], or [empty] when it is not there; unreadable, it is kept aside ([keepDamaged]) and [onDamaged] told. */
private fun <T> readOrKeep(file: File, empty: T, onDamaged: (File?) -> Unit = {}, decode: (String) -> T): T {
    if (!file.exists()) return empty
    return runCatching { decode(file.readText()) }.getOrElse { onDamaged(keepDamaged(file)); empty }
}

/**
 * Access tokens and server credentials, one JSON file in the data folder readable by its owner only.
 * The container's volume is the boundary here, as the phone's encrypted storage is in the app.
 */
class JsonTokenVault(private val file: File) : TokenVault {
    private val serializer = MapSerializer(String.serializer(), Tokens.serializer())
    private val lock = Any()
    private var cache: Map<String, Tokens>? = null

    /** True when the sign-ins were there but could not be read: every service has to be connected again, and the pages say so. */
    @Volatile var damaged: Boolean = false
        private set

    private fun all(): Map<String, Tokens> = cache ?: readOrKeep(file, emptyMap(), { damaged = true }) { json.decodeFromString(serializer, it) }.also { cache = it }

    override fun get(providerId: String): Tokens? = synchronized(lock) { all()[providerId] }

    override fun set(providerId: String, tokens: Tokens) {
        synchronized(lock) { save(all() + (providerId to tokens)) }
    }

    override fun clear(providerId: String) {
        synchronized(lock) { save(all() - providerId) }
    }

    override fun ids(): Set<String> = synchronized(lock) { all().keys.toSet() }

    private fun save(next: Map<String, Tokens>) {
        writePrivate(file, json.encodeToString(serializer, next))
        cache = next
    }
}

/** A small persistent string map in `kv/<name>.json`, written in full at each change (they stay small). */
class JsonKeyValues(private val file: File) : KeyValues {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val lock = Any()
    private var map: Map<String, String> = readOrKeep(file, emptyMap()) { json.decodeFromString(serializer, it) }

    override fun get(key: String): String? = synchronized(lock) { map[key] }

    override fun set(key: String, value: String) {
        synchronized(lock) {
            if (map[key] == value) return
            map = map + (key to value)
            writePrivate(file, json.encodeToString(serializer, map))
        }
    }

    override val size: Int get() = synchronized(lock) { map.size }

    override fun clear() {
        synchronized(lock) {
            map = emptyMap()
            writePrivate(file, "{}")
        }
    }
}

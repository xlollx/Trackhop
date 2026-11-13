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
    tmp.writeText(text)
    runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/**
 * Access tokens and server credentials, one JSON file in the data folder readable by its owner only.
 * The container's volume is the boundary here, as the phone's encrypted storage is in the app.
 */
class JsonTokenVault(private val file: File) : TokenVault {
    private val serializer = MapSerializer(String.serializer(), Tokens.serializer())
    private val lock = Any()
    private var cache: Map<String, Tokens>? = null

    private fun all(): Map<String, Tokens> = cache ?: (
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap()) else emptyMap()
    ).also { cache = it }

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
    private var map: Map<String, String> =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap()) else emptyMap()

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
